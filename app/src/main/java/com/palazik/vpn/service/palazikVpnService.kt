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
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

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
    private var coreController: CoreController? = null
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
                // 1. Copy geo assets to filesDir where xray can read them
                copyAssetIfNeeded("geoip.dat")
                copyAssetIfNeeded("geosite.dat")

                // 2. Init xray env — sets asset path so geo files are found
                Libv2ray.initCoreEnv(filesDir.absolutePath, "")

                // 3. Build xray JSON config
                val config = XrayConfigBuilder.build(profile)
                Log.d(TAG, "Config built for ${profile.name} (${profile.protocol}+${profile.transport})")

                // 4. Establish TUN interface — must happen before StartLoop
                val iface = buildVpnInterface()
                vpnInterface = iface
                val tunFd = iface.fd

                // 5. Create controller with callbacks
                val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
                    override fun startup(): Int {
                        Log.d(TAG, "xray core started")
                        _connectionState.value = ServiceState.RUNNING
                        updateNotification("Connected — ${profile.name}")
                        startStatsLoop(this@apply as CoreController)
                        return 0
                    }

                    override fun shutdown(): Int {
                        Log.d(TAG, "xray core stopped")
                        return 0
                    }

                    override fun onEmitStatus(level: Int, msg: String): Int {
                        Log.d(TAG, "xray[$level]: $msg")
                        return 0
                    }
                })

                coreController = controller

                // 6. StartLoop — blocks until stopped; tunFd routes traffic into xray
                controller.startLoop(config, tunFd.toLong().toInt())

            } catch (e: Exception) {
                Log.e(TAG, "VPN start failed: ${e.message}", e)
                withContext(Dispatchers.Main) { stopVpn() }
            }
        }
    }

    private fun startStatsLoop(controller: CoreController) {
        statsJob = scope.launch {
            while (isActive) {
                delay(1000)
                runCatching {
                    _bytesIn.value  += controller.queryStats("proxy", "downlink")
                    _bytesOut.value += controller.queryStats("proxy", "uplink")
                }
            }
        }
    }

    // ── Stop ─────────────────────────────────────────────────────────────────

    private fun stopVpn() {
        _connectionState.value = ServiceState.STOPPING
        statsJob?.cancel(); statsJob = null
        runCatching { coreController?.stopLoop() }
        coreController = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        _connectionState.value = ServiceState.STOPPED
        _bytesIn.value  = 0L
        _bytesOut.value = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── TUN ───────────────────────────────────────────────────────────────────

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
            ?: throw IllegalStateException("establish() returned null — VPN permission not granted?")

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun copyAssetIfNeeded(filename: String) {
        val dest = java.io.File(filesDir, filename)
        if (dest.exists()) return
        runCatching {
            assets.open(filename).use { it.copyTo(dest.outputStream()) }
            Log.d(TAG, "Copied $filename to filesDir")
        }.onFailure {
            Log.w(TAG, "$filename missing from assets: ${it.message}")
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
