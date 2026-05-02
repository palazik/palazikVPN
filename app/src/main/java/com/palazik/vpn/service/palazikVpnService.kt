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

class palazikVpnService : VpnService() {

    companion object {
        const val ACTION_START    = "com.palazik.vpn.START"
        const val ACTION_STOP     = "com.palazik.vpn.STOP"
        const val EXTRA_PROFILE   = "profile_id"
        const val NOTIFICATION_ID = 1001
        const val LOCAL_SOCKS_PORT = 10808

        private const val TUN_ADDRESS  = "10.0.0.2"
        private const val TUN_PREFIX   = 24
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
                socks5Server = ServerSocket(LOCAL_SOCKS_PORT, 50,
                    java.net.InetAddress.getLoopbackAddress())
                launch { runSocks5Server(socks5Server!!) }

                val iface = buildVpnInterface()
                vpnInterface = iface

                _connectionState.value = ServiceState.RUNNING
                _bytesIn.value  = 0L
                _bytesOut.value = 0L
                updateNotification("Connected")

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
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .setMtu(1500)
            .setBlocking(true)
            .establish()
            ?: throw IllegalStateException("VPN interface could not be established")

    // ── tun2socks ─────────────────────────────────────────────────────────────

    private suspend fun runTun2Socks(iface: ParcelFileDescriptor) = withContext(Dispatchers.IO) {
        val tunIn  = FileInputStream(iface.fileDescriptor)
        val tunOut = FileOutputStream(iface.fileDescriptor)
        val buf    = ByteArray(65535)

        while (isActive) {
            val len: Int
            try {
                len = tunIn.read(buf)
            } catch (_: Exception) {
                break
            }
            if (len < 20) continue

            val version = (buf[0].toInt() and 0xF0) shr 4
            if (version != 4) continue

            val protocol = buf[9].toInt() and 0xFF
            val dstIp    = "${buf[16].toInt() and 0xFF}.${buf[17].toInt() and 0xFF}" +
                           ".${buf[18].toInt() and 0xFF}.${buf[19].toInt() and 0xFF}"
            val ipHdrLen = (buf[0].toInt() and 0x0F) * 4

            when (protocol) {
                6 -> { // TCP
                    val flags      = buf[ipHdrLen + 13].toInt() and 0xFF
                    val isSyn      = (flags and 0x02) != 0
                    val isFinOrRst = (flags and 0x05) != 0
                    if (!isSyn || isFinOrRst) continue

                    val dstPort = ((buf[ipHdrLen + 2].toInt() and 0xFF) shl 8) or
                                   (buf[ipHdrLen + 3].toInt() and 0xFF)

                    _bytesOut.value += len

                    launch {
                        try {
                            bridgeTcpViaSocks5(dstIp, dstPort, iface, tunOut)
                        } catch (e: Exception) {
                            Log.d("palazikVPN", "TCP relay error $dstIp:$dstPort — ${e.message}")
                        }
                    }
                }
                17 -> { // UDP
                    val dstPort    = ((buf[ipHdrLen + 2].toInt() and 0xFF) shl 8) or
                                      (buf[ipHdrLen + 3].toInt() and 0xFF)
                    val payloadOff = ipHdrLen + 8
                    val payload    = buf.copyOfRange(payloadOff, len)

                    _bytesOut.value += payload.size

                    launch {
                        try {
                            val udpSock = java.net.DatagramSocket()
                            protect(udpSock)
                            val pkt = java.net.DatagramPacket(
                                payload, payload.size,
                                java.net.InetAddress.getByName(dstIp), dstPort,
                            )
                            udpSock.send(pkt)
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
                        } catch (e: Exception) {
                            Log.d("palazikVPN", "UDP relay error: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private suspend fun bridgeTcpViaSocks5(
        dstIp: String,
        dstPort: Int,
        iface: ParcelFileDescriptor,
        tunOut: FileOutputStream,
    ) = withContext(Dispatchers.IO) {
        val proxy = Socket()
        proxy.connect(InetSocketAddress("127.0.0.1", LOCAL_SOCKS_PORT), 5000)

        val proxyOut = proxy.getOutputStream()
        val proxyIn  = proxy.getInputStream()

        // SOCKS5 handshake
        proxyOut.write(byteArrayOf(0x05, 0x01, 0x00))
        proxyIn.read(ByteArray(2))

        val addrBytes = dstIp.split(".").map { it.toInt().toByte() }.toByteArray()
        val req = byteArrayOf(
            0x05, 0x01, 0x00, 0x01,
            *addrBytes,
            (dstPort shr 8).toByte(),
            (dstPort and 0xFF).toByte(),
        )
        proxyOut.write(req)
        proxyIn.read(ByteArray(10))

        val tunIn = FileInputStream(iface.fileDescriptor)

        // TUN → proxy
        launch {
            val buf = ByteArray(8192)
            while (true) {
                val n: Int
                try { n = tunIn.read(buf) } catch (_: Exception) { break }
                if (n <= 0) break
                try { proxyOut.write(buf, 0, n); proxyOut.flush() } catch (_: Exception) { break }
                _bytesOut.value += n
            }
        }

        // proxy → TUN
        val buf = ByteArray(8192)
        while (true) {
            val n: Int
            try { n = proxyIn.read(buf) } catch (_: Exception) { break }
            if (n <= 0) break
            try { synchronized(tunOut) { tunOut.write(buf, 0, n) } } catch (_: Exception) { break }
            _bytesIn.value += n
        }

        proxy.close()
    }

    // ── In-process SOCKS5 server ──────────────────────────────────────────────

    private suspend fun runSocks5Server(server: ServerSocket) = withContext(Dispatchers.IO) {
        while (isActive) {
            val client: Socket
            try { client = server.accept() } catch (_: Exception) { break }
            launch { handleSocks5Client(client) }
        }
    }

    private suspend fun handleSocks5Client(client: Socket) = withContext(Dispatchers.IO) {
        try {
            val cin  = client.getInputStream()
            val cout = client.getOutputStream()

            val hdr = ByteArray(2)
            cin.read(hdr)
            val methods = ByteArray(hdr[1].toInt())
            cin.read(methods)
            cout.write(byteArrayOf(0x05, 0x00))

            val req = ByteArray(4)
            cin.read(req)
            val atyp = req[3].toInt() and 0xFF

            val dstHost: String
            when (atyp) {
                0x01 -> {
                    val addr = ByteArray(4); cin.read(addr)
                    dstHost = addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
                0x03 -> {
                    val dlen = cin.read()
                    val domain = ByteArray(dlen); cin.read(domain)
                    dstHost = String(domain)
                }
                0x04 -> {
                    val addr = ByteArray(16); cin.read(addr)
                    dstHost = "[" + addr.toList().chunked(2)
                        .joinToString(":") { (a, b) ->
                            "%02x%02x".format(a.toInt() and 0xFF, b.toInt() and 0xFF)
                        } + "]"
                }
                else -> { client.close(); return@withContext }
            }
            val portHi = cin.read(); val portLo = cin.read()
            val dstPort = (portHi shl 8) or portLo

            val outSock = Socket()
            protect(outSock)
            try {
                outSock.connect(InetSocketAddress(dstHost, dstPort), 10_000)
            } catch (e: Exception) {
                cout.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                client.close()
                return@withContext
            }

            cout.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))

            val oIn  = outSock.getInputStream()
            val oOut = outSock.getOutputStream()

            // client → remote
            launch {
                val buf = ByteArray(8192)
                while (true) {
                    val n: Int
                    try { n = cin.read(buf) } catch (_: Exception) { break }
                    if (n < 0) break
                    try { oOut.write(buf, 0, n); oOut.flush() } catch (_: Exception) { break }
                }
                try { outSock.close() } catch (_: Exception) {}
            }

            // remote → client
            val buf = ByteArray(8192)
            while (true) {
                val n: Int
                try { n = oIn.read(buf) } catch (_: Exception) { break }
                if (n < 0) break
                try { cout.write(buf, 0, n); cout.flush() } catch (_: Exception) { break }
            }
        } catch (e: Exception) {
            Log.d("palazikVPN", "SOCKS5 client error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    private fun stopVpn() {
        _connectionState.value = ServiceState.STOPPING
        try { socks5Server?.close() } catch (_: Exception) {}
        socks5Server = null
        scope.coroutineContext.cancelChildren()
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        _connectionState.value = ServiceState.STOPPED
        _bytesIn.value  = 0L
        _bytesOut.value = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildUdpIpPacket(
        srcIp: String, dstIp: String,
        srcPort: Int, dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLen   = 8 + payload.size
        val totalLen = 20 + udpLen
        val buf      = ByteBuffer.allocate(totalLen)

        val src = srcIp.split(".").map { it.toInt().toByte() }.toByteArray()
        val dst = dstIp.split(".").map { it.toInt().toByte() }.toByteArray()

        buf.put(0x45.toByte()); buf.put(0x00)
        buf.putShort(totalLen.toShort())
        buf.putInt(0)
        buf.put(64); buf.put(17)
        buf.putShort(0)
        buf.put(src); buf.put(dst)

        var sum = 0
        val hdr = buf.array()
        for (i in 0 until 20 step 2)
            sum += ((hdr[i].toInt() and 0xFF) shl 8) or (hdr[i + 1].toInt() and 0xFF)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        buf.position(10); buf.putShort(sum.inv().toShort())

        buf.position(20)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0)
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
