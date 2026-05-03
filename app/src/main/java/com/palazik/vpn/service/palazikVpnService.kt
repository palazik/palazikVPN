package com.palazik.vpn.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.palazik.vpn.R
import com.palazik.vpn.data.model.VpnProfile
import com.palazik.vpn.palazikVPNApp
import com.palazik.vpn.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import libv2ray.Libv2ray
import libv2ray.V2RayPoint
import libv2ray.V2RayVPNServiceSupportsSet

class palazikVpnService : VpnService() {

    companion object {
        const val ACTION_START    = "com.palazik.vpn.START"
        const val ACTION_STOP     = "com.palazik.vpn.STOP"
        const val EXTRA_PROFILE   = "profile_id"
        const val NOTIFICATION_ID = 1001
        private const val TAG     = "palazikVPN"

        private val _connectionState = MutableStateFlow(ServiceState.STOPPED)
        val connectionState: StateFlow<ServiceState> = _connectionState

        private val _bytesIn  = MutableStateFlow(0L)
        private val _bytesOut = MutableStateFlow(0L)
        val bytesIn:  StateFlow<Long> = _bytesIn
        val bytesOut: StateFlow<Long> = _bytesOut

        @Volatile var activeProfile: VpnProfile? = null
    }

    enum class ServiceState { STOPPED, STARTING, RUNNING, STOPPING }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var v2rayPoint: V2RayPoint? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statsJob: Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP  -> stopVpn()
        }
        return START_STICKY
    }

    override fun onRevoke() { stopVpn() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    // ── Start ─────────────────────────────────────────────────────────────────

    private fun startVpn() {
        val profile = activeProfile ?: run {
            Log.e(TAG, "activeProfile is null"); stopSelf(); return
        }

        _connectionState.value = ServiceState.STARTING
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        scope.launch {
            try {
                copyAssetIfNeeded("geoip.dat")
                copyAssetIfNeeded("geosite.dat")

                val config = XrayConfigBuilder.build(profile)
                Log.d(TAG, "Xray config built for ${profile.name}")

                // Libv2ray.newV2RayPoint(supportSet, enableLocalDns)
                // DO NOT pass true for enableLocalDns unless you have a local DNS inbound —
                // it will intercept port 53 and break resolution if misconfigured.
                val point = Libv2ray.newV2RayPoint(V2RayCallback(), false)
                point.configureFileContent = config
                point.domainName           = profile.address
                point.enableLocalDns       = false
                point.blockedApps          = ""
                point.packageName          = packageName

                v2rayPoint = point

                // runLoop() is BLOCKING. It calls setup() on our callback first to get
                // the tun fd, starts the Xray core, then blocks until stopLoop() is called.
                point.runLoop()

                // We only get here after stopLoop() — clean up
                withContext(Dispatchers.Main) { stopVpn() }

            } catch (e: Exception) {
                Log.e(TAG, "VPN start failed: ${e.message}", e)
                withContext(Dispatchers.Main) { stopVpn() }
            }
        }
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    private fun stopVpn() {
        if (_connectionState.value == ServiceState.STOPPED) return
        _connectionState.value = ServiceState.STOPPING
        statsJob?.cancel()
        statsJob = null
        runCatching { v2rayPoint?.stopLoop() }
        v2rayPoint = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        _connectionState.value = ServiceState.STOPPED
        _bytesIn.value  = 0L
        _bytesOut.value = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── V2RayVPNServiceSupportsSet ────────────────────────────────────────────
    //
    // libv2ray calls these from the Go side over JNI.
    //
    // IMPORTANT — return types:
    //   shutdown()      → Long  (0 = ok)
    //   prepare()       → Long  (0 = ok)
    //   protect(Long)   → Long  (0 = protected, 1 = failed)   ← NOT Boolean
    //   onEmitStatus()  → Long  (0 = ok)
    //   setup(String)   → Long  (the tun fd number)           ← NOT 0L
    //
    // setup() is how libv2ray gets the tun fd from us.
    // We call establish() here and return iface.fd.toLong().
    // Returning 0L or 1L here is wrong — it tells the Go core fd=0 or fd=1.

    private inner class V2RayCallback : V2RayVPNServiceSupportsSet {

        override fun shutdown(): Long {
            Log.d(TAG, "shutdown() called by libv2ray")
            stopVpn()
            return 0L
        }

        override fun prepare(): Long {
            // VpnService.prepare() permission is handled in MainActivity before
            // we ever start the service, so nothing to do here.
            return 0L
        }

        override fun protect(socket: Long): Long {
            return if (this@palazikVpnService.protect(socket.toInt())) 0L else 1L
        }

        override fun onEmitStatus(level: Long, msg: String): Long {
            Log.d(TAG, "xray[$level]: $msg")
            // Xray core emits "Xray X.X.X started" when it's up
            if (msg.contains("started", ignoreCase = true)) {
                _connectionState.value = ServiceState.RUNNING
                updateNotification("Connected — ${activeProfile?.name ?: ""}")
                startStatsPolling()
            }
            return 0L
        }

        /**
         * libv2ray calls setup() when it needs the tun fd.
         * We establish the VPN interface here and return iface.fd.toLong().
         * Any value <= 0 is treated as an error by libv2ray.
         */
        override fun setup(parameters: String): Long {
            Log.d(TAG, "setup() called by libv2ray, params=$parameters")
            return try {
                // Close any stale interface from a previous connection
                runCatching { vpnInterface?.close() }

                val builder = Builder()
                    .setSession("palazikVPN")
                    .addAddress("26.26.26.1", 24)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("1.1.1.1")
                    .setMtu(1500)
                    // Exclude our own app so the VPN doesn't loop back on itself
                    .addDisallowedApplication(packageName)

                val iface = builder.establish()
                    ?: run {
                        Log.e(TAG, "establish() returned null — missing VPN permission?")
                        return 1L
                    }

                vpnInterface = iface
                Log.d(TAG, "TUN fd=${iface.fd}")
                iface.fd.toLong()   // ← THIS is how libv2ray gets the fd
            } catch (e: Exception) {
                Log.e(TAG, "setup() exception: ${e.message}", e)
                1L
            }
        }
    }

    // ── Stats polling ─────────────────────────────────────────────────────────

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(1000)
                runCatching {
                    _bytesIn.value  += v2rayPoint?.queryStats("proxy", "downlink") ?: 0L
                    _bytesOut.value += v2rayPoint?.queryStats("proxy", "uplink")   ?: 0L
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun copyAssetIfNeeded(filename: String) {
        val dest = java.io.File(filesDir, filename)
        if (dest.exists()) return
        runCatching {
            assets.open(filename).use { it.copyTo(dest.outputStream()) }
            Log.d(TAG, "Copied asset $filename → filesDir")
        }.onFailure {
            Log.w(TAG, "$filename not found in assets — routing rules won't work: ${it.message}")
        }
    }

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
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }
}
