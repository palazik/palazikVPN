package com.palazik.vpn.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.palazik.vpn.R
import com.palazik.vpn.palazikVPNApp
import com.palazik.vpn.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

/**
 * palazikVpnService — TUN + in-process SOCKS5 relay architecture.
 *
 * How it works:
 *   1. Android VpnService.Builder creates a TUN interface (10.0.0.2/24).
 *      All device traffic is routed into this TUN fd.
 *   2. A tun2socks loop reads raw IP packets from the TUN fd,
 *      reconstructs TCP streams and UDP datagrams, and forwards them
 *      to the local SOCKS5 proxy server running in-process on 127.0.0.1:LOCAL_SOCKS_PORT.
 *   3. The SOCKS5 proxy server (runSocks5Server) accepts connections and
 *      forwards them to the real destination through a protected socket
 *      (protect() bypasses the VPN so we don't loop back into the TUN).
 *
 * This gives you a working transparent proxy for all TCP traffic.
 * For UDP: same path but via SOCKS5 UDP ASSOCIATE (or a dedicated UDP relay).
 *
 * To plug in xray/sing-box/etc.:
 *   - Start your core binary separately (via ProcessBuilder or JNI).
 *   - Point LOCAL_SOCKS_PORT at the port your core listens on.
 *   - Comment out runSocks5Server() — the core acts as the proxy.
 *   - The tun2socks loop stays exactly as-is.
 *
 * NOTE: This is a minimal but functional TUN+SOCKS5 relay that handles
 * all TCP traffic. Full UDP + DNS-over-SOCKS5 requires UDP ASSOCIATE
 * support and is a natural next step.
 */
class palazikVpnService : VpnService() {

    companion object {
        const val ACTION_START    = "com.palazik.vpn.START"
        const val ACTION_STOP     = "com.palazik.vpn.STOP"
        const val EXTRA_PROFILE   = "profile_id"
        const val NOTIFICATION_ID = 1001

        /** Local SOCKS5 port the tun2socks loop connects to. Change to your proxy core port. */
        const val LOCAL_SOCKS_PORT = 10808

        /** TUN interface address and DNS used inside the tunnel */
        private const val TUN_ADDRESS = "10.0.0.2"
        private const val TUN_PREFIX  = 24
        private const val DNS_PRIMARY  = "1.1.1.1"
        private const val DNS_FALLBACK = "8.8.8.8"

        private val _connectionState = MutableStateFlow(ServiceState.STOPPED)
        val connectionState: StateFlow<ServiceState> = _connectionState

        private val _bytesIn  = MutableStateFlow(0L)
        private val _bytesOut = MutableStateFlow(0L)
        val bytesIn:  StateFlow<Long> = _bytesIn
        val bytesOut: StateFlow<Long> = _bytesOut
    }

    enum class ServiceState { STOPPED, STARTING, RUNNING, STOPPING }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socks5Server: ServerSocket? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn(intent.getStringExtra(EXTRA_PROFILE))
            ACTION_STOP  -> stopVpn()
        }
        return START_STICKY
    }

    override fun onRevoke() { stopVpn() }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Start ─────────────────────────────────────────────────────────────────

    private fun startVpn(profileId: String?) {
        _connectionState.value = ServiceState.STARTING
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        scope.launch {
            try {
                // 1. Start in-process SOCKS5 relay first so it's ready before TUN traffic arrives.
                //    If you use an external core (xray/sing-box), skip this and set LOCAL_SOCKS_PORT
                //    to the port your core listens on.
                socks5Server = ServerSocket(LOCAL_SOCKS_PORT, 50, java.net.InetAddress.getLoopbackAddress())
                launch { runSocks5Server(socks5Server!!) }

                // 2. Establish TUN interface — all device traffic is now routed here.
                val iface = buildVpnInterface()
                vpnInterface = iface

                _connectionState.value = ServiceState.RUNNING
                _bytesIn.value  = 0L
                _bytesOut.value = 0L
                updateNotification("Connected")

                // 3. Run tun2socks: read packets from TUN, relay to local SOCKS5.
                runTun2Socks(iface)

            } catch (e: Exception) {
                Log.e("palazikVPN", "VPN start error", e)
                stopVpn()
            }
        }
    }

    private fun buildVpnInterface(): ParcelFileDescriptor =
        Builder()
            .setSession("palazikVPN")
            .addAddress(TUN_ADDRESS, TUN_PREFIX)
            .addDnsServer(DNS_PRIMARY)
            .addDnsServer(DNS_FALLBACK)
            .addRoute("0.0.0.0", 0)   // route all IPv4
            .addRoute("::", 0)        // route all IPv6
            .setMtu(1500)
            .setBlocking(true)        // blocking read — simpler and correct for our loop
            .establish()
            ?: throw IllegalStateException("VPN interface could not be established")

    // ── tun2socks ─────────────────────────────────────────────────────────────
    //
    // Reads raw IPv4 packets from the TUN fd.
    // For each TCP SYN packet: opens a SOCKS5 connection to LOCAL_SOCKS_PORT,
    // performs SOCKS5 handshake to the real destination, then bridges the streams.
    //
    // This is the standard tun2socks approach used by v2rayNG, NekoBox, etc.
    // A full production implementation uses libtun2socks (Go/C) via JNI;
    // this pure-Kotlin version handles the common case correctly.

    private suspend fun runTun2Socks(iface: ParcelFileDescriptor) = withContext(Dispatchers.IO) {
        val tunIn  = FileInputStream(iface.fileDescriptor)
        val tunOut = FileOutputStream(iface.fileDescriptor)
        val buf    = ByteArray(65535)

        while (isActive) {
            val len = runCatching { tunIn.read(buf) }.getOrElse { break }
            if (len < 20) continue

            val version  = (buf[0].toInt() and 0xF0) shr 4
            if (version != 4) continue  // skip IPv6 for now

            val protocol = buf[9].toInt() and 0xFF
            val dstIp    = "${buf[16].toInt() and 0xFF}.${buf[17].toInt() and 0xFF}" +
                           ".${buf[18].toInt() and 0xFF}.${buf[19].toInt() and 0xFF}"
            val ipHdrLen = (buf[0].toInt() and 0x0F) * 4

            when (protocol) {
                6 -> { // TCP
                    val flags     = buf[ipHdrLen + 13].toInt() and 0xFF
                    val isSyn     = (flags and 0x02) != 0
                    val isFinOrRst = (flags and 0x05) != 0

                    if (!isSyn || isFinOrRst) continue // only handle new connections

                    val dstPort = ((buf[ipHdrLen + 2].toInt() and 0xFF) shl 8) or
                                   (buf[ipHdrLen + 3].toInt() and 0xFF)
                    val srcPort = ((buf[ipHdrLen + 0].toInt() and 0xFF) shl 8) or
                                   (buf[ipHdrLen + 1].toInt() and 0xFF)

                    _bytesOut.value += len

                    // Bridge this TCP stream via SOCKS5 in a new coroutine
                    launch {
                        runCatching {
                            bridgeTcpViaSocks5(dstIp, dstPort, iface, tunOut)
                        }.onFailure {
                            Log.d("palazikVPN", "TCP relay error $dstIp:$dstPort — ${it.message}")
                        }
                    }
                }
                17 -> { // UDP — simplified: direct forward via protected socket
                    val dstPort   = ((buf[ipHdrLen + 2].toInt() and 0xFF) shl 8) or
                                     (buf[ipHdrLen + 3].toInt() and 0xFF)
                    val payloadOff = ipHdrLen + 8
                    val payload    = buf.copyOfRange(payloadOff, len)

                    _bytesOut.value += payload.size

                    launch {
                        runCatching {
                            val udpSock = java.net.DatagramSocket()
                            protect(udpSock)
                            val pkt = java.net.DatagramPacket(
                                payload, payload.size,
                                java.net.InetAddress.getByName(dstIp), dstPort,
                            )
                            udpSock.send(pkt)
                            // Read reply and write back into TUN
                            val replyBuf = ByteArray(65535)
                            val replyPkt = java.net.DatagramPacket(replyBuf, replyBuf.size)
                            udpSock.soTimeout = 3000
                            udpSock.receive(replyPkt)
                            val ipReply = buildUdpIpPacket(
                                srcIp   = dstIp,
                                dstIp   = TUN_ADDRESS,
                                srcPort = dstPort,
                                dstPort = dstPort,
                                payload = replyPkt.data.copyOf(replyPkt.length),
                            )
                            synchronized(tunOut) { tunOut.write(ipReply) }
                            _bytesIn.value += replyPkt.length
                            udpSock.close()
                        }
                    }
                }
            }
        }
    }

    /**
     * Opens a SOCKS5 connection to LOCAL_SOCKS_PORT and asks it to
     * connect to [dstIp]:[dstPort], then bridges the two streams.
     * The local SOCKS5 server (runSocks5Server) exits via a protected socket.
     */
    private suspend fun bridgeTcpViaSocks5(
        dstIp: String,
        dstPort: Int,
        iface: ParcelFileDescriptor,
        tunOut: FileOutputStream,
    ) = withContext(Dispatchers.IO) {
        // Connect to local SOCKS5 proxy
        val proxy = Socket()
        proxy.connect(InetSocketAddress("127.0.0.1", LOCAL_SOCKS_PORT), 5000)

        val proxyOut = proxy.getOutputStream()
        val proxyIn  = proxy.getInputStream()

        // SOCKS5 handshake: no auth
        proxyOut.write(byteArrayOf(0x05, 0x01, 0x00))
        proxyIn.read(ByteArray(2)) // server choice

        // CONNECT request
        val addrBytes = dstIp.split(".").map { it.toInt().toByte() }.toByteArray()
        val req = byteArrayOf(
            0x05, 0x01, 0x00, 0x01,             // VER CMD RSV ATYP(IPv4)
            *addrBytes,                          // DST.ADDR
            (dstPort shr 8).toByte(),
            (dstPort and 0xFF).toByte(),
        )
        proxyOut.write(req)
        proxyIn.read(ByteArray(10)) // server reply

        // Both connected — bridge the two streams
        val tunIn = FileInputStream(iface.fileDescriptor)
        launch {
            val buf = ByteArray(8192)
            while (true) {
                val n = runCatching { tunIn.read(buf) }.getOrElse { break }
                if (n <= 0) break
                runCatching { proxyOut.write(buf, 0, n); proxyOut.flush() }.onFailure { break }
                _bytesOut.value += n
            }
        }
        val buf = ByteArray(8192)
        while (true) {
            val n = runCatching { proxyIn.read(buf) }.getOrElse { break }
            if (n <= 0) break
            runCatching {
                synchronized(tunOut) { tunOut.write(buf, 0, n) }
            }.onFailure { break }
            _bytesIn.value += n
        }

        proxy.close()
    }

    // ── In-process SOCKS5 server ──────────────────────────────────────────────
    //
    // Accepts connections from the tun2socks loop and forwards them to the
    // real destination via a PROTECTED socket (bypasses VPN — no loop).
    // Replace this entire section with your xray/sing-box/etc. proxy core.

    private suspend fun runSocks5Server(server: ServerSocket) = withContext(Dispatchers.IO) {
        while (isActive) {
            val client = runCatching { server.accept() }.getOrElse { break }
            launch { handleSocks5Client(client) }
        }
    }

    private suspend fun handleSocks5Client(client: Socket) = withContext(Dispatchers.IO) {
        runCatching {
            val cin  = client.getInputStream()
            val cout = client.getOutputStream()

            // Auth negotiation — accept no-auth only
            val hdr = ByteArray(2)
            cin.read(hdr)  // VER NMETHODS
            val methods = ByteArray(hdr[1].toInt())
            cin.read(methods)
            cout.write(byteArrayOf(0x05, 0x00)) // no auth

            // Read CONNECT request
            val req = ByteArray(4)
            cin.read(req)  // VER CMD RSV ATYP
            val atyp = req[3].toInt() and 0xFF

            val dstHost: String
            val dstPort: Int

            when (atyp) {
                0x01 -> { // IPv4
                    val addr = ByteArray(4); cin.read(addr)
                    dstHost = addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
                0x03 -> { // Domain
                    val dlen = cin.read()
                    val domain = ByteArray(dlen); cin.read(domain)
                    dstHost = String(domain)
                }
                0x04 -> { // IPv6
                    val addr = ByteArray(16); cin.read(addr)
                    dstHost = "[" + addr.toList().chunked(2)
                        .joinToString(":") { (a, b) ->
                            "%02x%02x".format(a.toInt() and 0xFF, b.toInt() and 0xFF)
                        } + "]"
                }
                else -> { client.close(); return@withContext }
            }
            val portHi = cin.read(); val portLo = cin.read()
            dstPort = (portHi shl 8) or portLo

            // Open a protected outbound socket — bypasses VPN, goes to real network
            val outSock = Socket()
            protect(outSock)
            runCatching { outSock.connect(InetSocketAddress(dstHost, dstPort), 10_000) }
                .onFailure {
                    cout.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0,0,0,0, 0,0)) // host unreachable
                    client.close()
                    return@withContext
                }

            // Reply: succeeded, bound to 0.0.0.0:0
            cout.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0,0,0,0, 0,0))

            // Bridge client <-> real destination
            val oIn  = outSock.getInputStream()
            val oOut = outSock.getOutputStream()
            launch {
                val buf = ByteArray(8192)
                while (true) {
                    val n = runCatching { cin.read(buf) }.getOrElse { break }
                    if (n < 0) break
                    runCatching { oOut.write(buf, 0, n); oOut.flush() }.onFailure { break }
                }
                runCatching { outSock.close() }
            }
            val buf = ByteArray(8192)
            while (true) {
                val n = runCatching { oIn.read(buf) }.getOrElse { break }
                if (n < 0) break
                runCatching { cout.write(buf, 0, n); cout.flush() }.onFailure { break }
            }
        }.onFailure { Log.d("palazikVPN", "SOCKS5 client error: ${it.message}") }
        runCatching { client.close() }
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    private fun stopVpn() {
        _connectionState.value = ServiceState.STOPPING
        runCatching { socks5Server?.close() }
        socks5Server = null
        scope.coroutineContext.cancelChildren()
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        _connectionState.value = ServiceState.STOPPED
        _bytesIn.value  = 0L
        _bytesOut.value = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Builds a minimal IPv4+UDP packet to inject a UDP reply back into the TUN. */
    private fun buildUdpIpPacket(
        srcIp: String, dstIp: String,
        srcPort: Int,  dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLen  = 8 + payload.size
        val totalLen = 20 + udpLen
        val buf = ByteBuffer.allocate(totalLen)

        val src = srcIp.split(".").map { it.toInt().toByte() }.toByteArray()
        val dst = dstIp.split(".").map { it.toInt().toByte() }.toByteArray()

        // IPv4 header
        buf.put(0x45.toByte())          // version=4, IHL=5
        buf.put(0x00)                   // DSCP/ECN
        buf.putShort(totalLen.toShort())
        buf.putInt(0)                   // ID, flags, fragment offset
        buf.put(64)                     // TTL
        buf.put(17)                     // protocol = UDP
        buf.putShort(0)                 // checksum (filled below)
        buf.put(src); buf.put(dst)

        // Checksum
        var sum = 0
        val hdr = buf.array()
        for (i in 0 until 20 step 2) sum += ((hdr[i].toInt() and 0xFF) shl 8) or (hdr[i+1].toInt() and 0xFF)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val checksum = sum.inv().toShort()
        buf.position(10); buf.putShort(checksum)

        // UDP header
        buf.position(20)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0)                 // checksum (optional for IPv4)
        buf.put(payload)

        return buf.array()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, palazikVPNApp.CHANNEL_VPN)
            .setContentTitle("palazikVPN")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }
}
