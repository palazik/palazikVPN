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
import libv2ray.ProcessFinder

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
            Log.e(TAG, "activeProfile is null"); stopSelf(); return
        }

        _connectionState.value = ServiceState.STARTING
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        scope.launch {
            try {
                val config = XrayConfigBuilder.build(profile)
                Log.d(TAG, "Xray config built for ${profile.name}")

                // Establish TUN interface
                val iface = buildVpnInterface()
                vpnInterface = iface
                Log.d(TAG, "TUN established, fd=${iface.fd}")

                // Create CoreController with our callbacks
                val controller = Libv2ray.newCoreController(V2RayCallback())
                controller.registerProcessFinder(object : ProcessFinder {
                    override fun findProcessByConnection(
                        network: String,
                        src: String,
                        srcPort: Long,
                        dst: String,
                        dstPort: Long,
                    ): String = ""
                })
                coreController = controller

                // startLoop(config, port) — pass 0 to use ports defined in config inbounds
                controller.startLoop(config, 0)

                _connectionState.value = ServiceState.RUNNING
                updateNotification("Connected — ${profile.name}")
                startStatsPolling()

            } catch (e: Exception) {
                Log.e(TAG, "VPN start failed: ${e.message}", e)
                withContext(Dispatchers.Main) { stopVpn() }
            }
        }
    }

    private fun buildVpnInterface(): ParcelFileDescriptor =
        Builder()
            .setSession("palazikVPN")
            .addAddress("26.26.26.1", 24)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .setMtu(1500)
            .addDisallowedApplication(packageName)
            .establish()
            ?: throw IllegalStateException("establish() returned null — missing VPN permission?")

    // ── Stop ──────────────────────────────────────────────────────────────────

    private fun stopVpn() {
        if (_connectionState.value == ServiceState.STOPPED) return
        _connectionState.value = ServiceState.STOPPING
        statsJob?.cancel()
        statsJob = null
        try { coreController?.stopLoop() } catch (e: Exception) { Log.w(TAG, "stopLoop: ${e.message}") }
        coreController = null
        try { vpnInterface?.close() } catch (e: Exception) { Log.w(TAG, "iface close: ${e.message}") }
        vpnInterface = null
        _connectionState.value = ServiceState.STOPPED
        _bytesIn.value  = 0L
        _bytesOut.value = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── CoreCallbackHandler ───────────────────────────────────────────────────

    private inner class V2RayCallback : CoreCallbackHandler {

        override fun onEmitStatus(level: Long, msg: String): Long {
            Log.d(TAG, "xray[$level]: $msg")
            return 0L
        }

        override fun shutdown(): Long {
            Log.d(TAG, "xray: shutdown requested")
            return 0L
        }

        override fun startup(): Long {
            Log.d(TAG, "xray: startup requested")
            return 0L
        }
    }

    // ── Stats polling ─────────────────────────────────────────────────────────

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(1000)
                try {
                    val ctrl = coreController ?: break
                    _bytesIn.value  += ctrl.queryStats("proxy", "downlink")
                    _bytesOut.value += ctrl.queryStats("proxy", "uplink")
                } catch (_: Exception) {}
            }
        }
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
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }
}
