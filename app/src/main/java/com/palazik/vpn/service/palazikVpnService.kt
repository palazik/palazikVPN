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
            Log.e(TAG, "activeProfile is null")
            stopSelf(); return
        }

        _connectionState.value = ServiceState.STARTING
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        scope.launch {
            try {
                copyAssetIfNeeded("geoip.dat")
                copyAssetIfNeeded("geosite.dat")

                val config = XrayConfigBuilder.build(profile)
                Log.d(TAG, "Config built for ${profile.name}")

                // Build TUN interface BEFORE initialising xray so the fd is ready
                val iface = buildVpnInterface()
                vpnInterface = iface
                val tunFd = iface.fd

                // Initialise the V2RayPoint with our service callbacks
                val point = V2RayPoint(
                    object : V2RayVPNServiceSupportsSet {
                        override fun shutdown(): Long {
                            Log.d(TAG, "V2RayVPNServiceSupportsSet.shutdown()")
                            this@palazikVpnService.stopVpn()
                            return 0
                        }

                        override fun prepare(): Long {
                            // Called by the core when it needs a fresh TUN fd
                            // Re-establish if needed; return 0 on success
                            return 0
                        }

                        override fun protect(socket: Long): Boolean {
                            // Protect native sockets so they bypass the VPN TUN
                            return this@palazikVpnService.protect(socket.toInt())
                        }

                        override fun onEmitStatus(level: Long, msg: String): Long {
                            Log.d(TAG, "core: $msg")
                            return 0
                        }

                        override fun setup(fd: String): Long {
                            // Called by core to set the TUN fd.
                            // We already established the interface — just return 0.
                            Log.d(TAG, "setup() called with fd=$fd")
                            return 0
                        }
                    },
                    // isRunning callback
                    false,
                )

                point.configureFileContent = config
                point.domainName           = profile.address
                // Pass TUN fd — the AAR reads this via the env path set during configureFileContent
                point.enableLocalDns = true
                point.blockedApps    = ""
                point.vpnFd          = tunFd

                point.runLoop()
                v2rayPoint = point

                withContext(Dispatchers.Main) {
                    _connectionState.value = ServiceState.RUNNING
                    updateNotification("Connected — ${profile.name}")
                }

                _bytesIn.value  = 0L
                _bytesOut.value = 0L

                statsJob = scope.launch {
                    while (isActive) {
                        delay(1000)
                        runCatching {
                            _bytesIn.value  += point.queryStats("proxy", "downlink")
                            _bytesOut.value += point.queryStats("proxy", "uplink")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "VPN start failed: ${e.message}", e)
                withContext(Dispatchers.Main) { stopVpn() }
            }
        }
    }

    // ── Stop ─────────────────────────────────────────────────────────────────

    private fun stopVpn() {
        _connectionState.value = ServiceState.STOPPING
        statsJob?.cancel(); statsJob = null
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

    // ── TUN interface ─────────────────────────────────────────────────────────

    private fun buildVpnInterface(): ParcelFileDescriptor =
        Builder()
            .setSession("palazikVPN")
            .addAddress("26.26.26.1", 24)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("26.26.26.2")
            .setMtu(1500)
            .addDisallowedApplication(packageName)
            .establish()
            ?: throw IllegalStateException("VpnService.Builder.establish() returned null — " +
                "VPN permission not granted?")

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun copyAssetIfNeeded(filename: String) {
        val dest = java.io.File(filesDir, filename)
        if (dest.exists()) return
        runCatching {
            assets.open(filename).use { it.copyTo(dest.outputStream()) }
            Log.d(TAG, "Copied $filename to filesDir")
        }.onFailure {
            Log.w(TAG, "$filename missing from assets — xray geo-routing will be limited")
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
