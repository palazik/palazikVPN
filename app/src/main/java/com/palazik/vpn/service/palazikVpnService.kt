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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP  -> stopVpn()
        }
        return START_STICKY
    }

    override fun onRevoke() { stopVpn() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun startVpn() {
        val profile = activeProfile ?: run { Log.e(TAG, "activeProfile not set"); return }

        _connectionState.value = ServiceState.STARTING
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        scope.launch {
            try {
                // Init xray assets (geoip.dat, geosite.dat)
                val assetPath = applicationContext.filesDir.absolutePath
                Libv2ray.initCoreEnv(assetPath, "")

                val config = XrayConfigBuilder.build(profile)
                Log.d(TAG, "config built for ${profile.name}")

                val iface = buildVpnInterface()
                vpnInterface = iface

                // Pass TUN fd to xray via environment variable
                System.setProperty("xray.tun.fd", iface.fd.toString())

                // Go mobile lowercases method names: OnEmitStatus → onEmitStatus
                // CoreController constructor takes the handler directly
                val controller = CoreController(object : CoreCallbackHandler {
                    override fun startup(): Int {
                        Log.d(TAG, "xray started")
                        _connectionState.value = ServiceState.RUNNING
                        updateNotification("Connected — ${profile.name}")
                        return 0
                    }
                    override fun shutdown(): Int {
                        Log.d(TAG, "xray stopped")
                        return 0
                    }
                    override fun onEmitStatus(level: Int, msg: String): Int {
                        Log.d(TAG, "xray[$level]: $msg")
                        return 0
                    }
                })

                // Go mobile lowercases method names: StartCore → startCore
                controller.startCore(config)
                coreController = controller

                _bytesIn.value  = 0L
                _bytesOut.value = 0L

                statsJob = scope.launch {
                    while (isActive) {
                        delay(1000)
                        runCatching {
                            _bytesIn.value  = controller.queryStats("inbound>>>socks>>>traffic>>>downlink")
                            _bytesOut.value = controller.queryStats("inbound>>>socks>>>traffic>>>uplink")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "start error: ${e.message}", e)
                stopVpn()
            }
        }
    }

    private fun buildVpnInterface(): ParcelFileDescriptor =
        Builder()
            .setSession("palazikVPN")
            .addAddress("26.26.26.1", 24)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .setMtu(1500)
            .addDisallowedApplication(packageName)
            .establish()
            ?: throw IllegalStateException("establish() returned null")

    private fun stopVpn() {
        _connectionState.value = ServiceState.STOPPING
        statsJob?.cancel(); statsJob = null
        runCatching { coreController?.stopCore() }
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

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
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
