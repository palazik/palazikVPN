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
                // Init xray assets path
                Libv2ray.initCoreEnv(applicationContext.filesDir.absolutePath, "")

                val config = XrayConfigBuilder.build(profile)
                Log.d(TAG, "Starting xray: ${profile.name} (${profile.protocol}+${profile.transport})")

                // Create TUN interface first to get the fd
                val iface = buildVpnInterface()
                vpnInterface = iface

                // NewCoreController is the factory function (gomobile: NewXxx -> newXxx)
                // CoreCallbackHandler methods: startup/shutdown return Long, onEmitStatus(Long, String): Long
                val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
                    override fun startup(): Long {
                        Log.d(TAG, "xray core started")
                        _connectionState.value = ServiceState.RUNNING
                        updateNotification("Connected — ${profile.name}")
                        return 0L
                    }
                    override fun shutdown(): Long {
                        Log.d(TAG, "xray core stopped")
                        return 0L
                    }
                    override fun onEmitStatus(level: Long, msg: String): Long {
                        Log.d(TAG, "xray[$level]: $msg")
                        return 0L
                    }
                })

                // In the compiled AAR, startLoop takes only config string.
                // TUN fd is passed via the "xray.tun.fd" env var which is set inside
                // StartLoop in the Go source — but this version reads it from env before starting.
                System.setProperty("xray.tun.fd", iface.fd.toString())
                controller.startLoop(config)
                coreController = controller

                _bytesIn.value  = 0L
                _bytesOut.value = 0L

                // QueryStats(tag, direction) — two params, returns Long
                statsJob = scope.launch {
                    while (isActive) {
                        delay(1000)
                        runCatching {
                            _bytesIn.value  += controller.queryStats("proxy", "downlink")
                            _bytesOut.value += controller.queryStats("proxy", "uplink")
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
