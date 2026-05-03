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

/**
 * palazikVpnService
 *
 * Architecture (same as v2rayNG):
 *
 *   1. VpnService.Builder creates TUN interface
 *   2. TUN fd → libxray (AndroidLibXrayLite .aar, full xray-core Go runtime)
 *   3. libxray handles everything: TCP stack, VLESS, VMess, XHTTP, REALITY, TLS, etc.
 *   4. This class only manages: TUN setup, lifecycle, notification, traffic stats
 *
 * libv2ray.aar setup:
 *   Download from https://github.com/2dust/AndroidLibXrayLite/releases/latest
 *   Place in app/libs/libv2ray.aar
 *   build.gradle.kts already has the downloadLibxray task that does this automatically.
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

        // Set by MainViewModel before calling ACTION_START
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
            Log.e(TAG, "No active profile set")
            return
        }

        _connectionState.value = ServiceState.STARTING
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        scope.launch {
            try {
                // 1. Build xray JSON config from the VpnProfile
                val config = XrayConfigBuilder.build(profile)
                Log.d(TAG, "Starting xray with config for: ${profile.name} " +
                    "(${profile.protocol} + ${profile.transport})")

                // 2. Create V2RayPoint — this is the libxray entry point
                val point = Libv2ray.newV2RayPoint(
                    object : V2RayVPNServiceSupportsSet {
                        // Called by libxray when it opens a raw socket that needs to
                        // bypass the VPN to prevent routing loops
                        override fun setup(fd: Long): Long {
                            protect(fd.toInt())
                            return 0
                        }
                        override fun prepare(): Long  = 0
                        override fun shutdown(): Long = 0
                        override fun onEmitStatus(status: String): Long {
                            Log.d(TAG, "xray status: $status")
                            return 0
                        }
                        override fun getVpnServiceStatus(): Long =
                            if (v2rayPoint?.isRunning == true) 1L else 0L
                    },
                    false
                )

                // 3. Feed the xray config
                point.configureFileContent = config
                point.domainName = profile.sni.ifEmpty { profile.address }

                // 4. Create TUN — must happen AFTER point is configured
                val iface = buildVpnInterface()
                vpnInterface = iface

                // 5. Give TUN fd to libxray and start the Go runtime
                point.vpnFd = iface.fd.toLong()
                point.runLoop()
                v2rayPoint = point

                _connectionState.value = ServiceState.RUNNING
                _bytesIn.value  = 0L
                _bytesOut.value = 0L
                updateNotification("Connected — ${profile.name}")

                // 6. Poll traffic stats every second
                statsJob = scope.launch {
                    while (isActive) {
                        delay(1000)
                        runCatching {
                            _bytesIn.value  = point.queryStats("inbound>>>socks>>>traffic>>>downlink")
                            _bytesOut.value = point.queryStats("inbound>>>socks>>>traffic>>>uplink")
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
            // xray-core default TUN range — matches XrayConfigBuilder fakeip pool
            .addAddress("26.26.26.1", 24)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .setMtu(1500)
            // Exclude our own app to prevent routing loops
            .addDisallowedApplication(packageName)
            .establish()
            ?: throw IllegalStateException("VPN establish() returned null — permission not granted?")

    // ── Stop ──────────────────────────────────────────────────────────────────

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
