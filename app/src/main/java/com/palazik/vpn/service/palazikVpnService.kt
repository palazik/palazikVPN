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

/**
 * palazikVpnService
 *
 * Uses AndroidLibXrayLite (libv2ray.aar) — xray-core compiled as Go mobile AAR.
 *
 * Real API (from libv2ray_main.go source):
 *   - Libv2ray.initCoreEnv(assetPath, xudpKey)  — sets up asset/cert paths
 *   - CoreController                              — manages xray instance lifecycle
 *   - CoreCallbackHandler                        — interface: Startup/Shutdown/OnEmitStatus
 *   - TUN fd passed via env var "xray.tun.fd"    — set before StartCore()
 *   - CoreController.StartCore(config)           — starts xray with JSON config string
 *   - CoreController.StopCore()                  — stops xray
 *   - CoreController.IsRunning                   — current state
 *
 * libv2ray.aar setup:
 *   Run: ./gradlew downloadLibxray   (or let preBuild do it automatically)
 *   This downloads libv2ray.aar into app/libs/ from AndroidLibXrayLite releases.
 */
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

        // Set by MainViewModel before sending ACTION_START
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
            Log.e(TAG, "activeProfile not set — cannot start")
            return
        }

        _connectionState.value = ServiceState.STARTING
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        scope.launch {
            try {
                // 1. Init libxray env — pass assets dir for geoip.dat / geosite.dat
                val assetPath = applicationContext.filesDir.absolutePath
                Libv2ray.initCoreEnv(assetPath, "")

                // 2. Build xray JSON config from profile
                val config = XrayConfigBuilder.build(profile)
                Log.d(TAG, "xray config built for ${profile.name} (${profile.protocol}+${profile.transport})")

                // 3. Create TUN interface
                val iface = buildVpnInterface()
                vpnInterface = iface

                // 4. Pass TUN fd to xray via env var — libxray reads "xray.tun.fd" on StartCore()
                System.setProperty("xray.tun.fd", iface.fd.toString())

                // 5. Create controller with callback handler
                val controller = CoreController()
                controller.CallbackHandler = object : CoreCallbackHandler {
                    override fun Startup(): Int {
                        Log.d(TAG, "xray core started")
                        _connectionState.value = ServiceState.RUNNING
                        updateNotification("Connected — ${profile.name}")
                        return 0
                    }
                    override fun Shutdown(): Int {
                        Log.d(TAG, "xray core stopped")
                        return 0
                    }
                    override fun OnEmitStatus(level: Int, msg: String): Int {
                        Log.d(TAG, "xray[$level]: $msg")
                        return 0
                    }
                }

                // 6. Start xray core with config
                controller.StartCore(config)
                coreController = controller

                _bytesIn.value  = 0L
                _bytesOut.value = 0L

                // 7. Poll traffic stats
                statsJob = scope.launch {
                    while (isActive) {
                        delay(1000)
                        runCatching {
                            _bytesIn.value  = controller.QueryStats("inbound>>>socks>>>traffic>>>downlink")
                            _bytesOut.value = controller.QueryStats("inbound>>>socks>>>traffic>>>uplink")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "VPN start error: ${e.message}", e)
                stopVpn()
            }
        }
    }

    private fun buildVpnInterface(): ParcelFileDescriptor =
        Builder()
            .setSession("palazikVPN")
            .addAddress("26.26.26.1", 24)     // xray-core default TUN client addr
            .addRoute("0.0.0.0", 0)           // route all IPv4
            .addRoute("::", 0)                // route all IPv6
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .setMtu(1500)
            .addDisallowedApplication(packageName) // don't route our own traffic into VPN
            .establish()
            ?: throw IllegalStateException("VPN establish() returned null — permission missing?")

    // ── Stop ──────────────────────────────────────────────────────────────────

    private fun stopVpn() {
        _connectionState.value = ServiceState.STOPPING
        statsJob?.cancel(); statsJob = null
        runCatching { coreController?.StopCore() }
        coreController = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        System.clearProperty("xray.tun.fd")
        _connectionState.value = ServiceState.STOPPED
        _bytesIn.value  = 0L
        _bytesOut.value = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
