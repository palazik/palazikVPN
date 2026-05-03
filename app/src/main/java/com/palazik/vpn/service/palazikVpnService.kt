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
        val profile = activeProfile ?: run {
            Log.e(TAG, "activeProfile is null"); stopSelf(); return
        }

        _connectionState.value = ServiceState.STARTING
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        scope.launch {
            try {
                copyAssetIfNeeded("geoip.dat")
                copyAssetIfNeeded("geosite.dat")

                val iface = buildVpnInterface()
                vpnInterface = iface

                val config = XrayConfigBuilder.build(profile)
                Log.d(TAG, "Config built for ${profile.name}, tunFd=${iface.fd}")

                val point = V2RayPoint(
                    object : V2RayVPNServiceSupportsSet {
                        override fun shutdown(): Long {
                            Log.d(TAG, "V2RayVPNServiceSupportsSet.shutdown()")
                            this@palazikVpnService.stopVpn()
                            return 0L
                        }

                        override fun prepare(): Long {
                            return 0L
                        }

                        override fun protect(socket: Long): Boolean {
                            return this@palazikVpnService.protect(socket.toInt())
                        }

                        override fun onEmitStatus(level: Long, msg: String): Long {
                            Log.d(TAG, "v2ray[$level]: $msg")
                            return 0L
                        }

                        override fun setup(fd: String): Long {
                            Log.d(TAG, "setup() fd=$fd")
                            return 0L
                        }
                    }
                )

                point.configureFileContent = config
                point.domainName           = profile.address
                point.enableLocalDns       = false
                point.blockedApps          = ""
                point.vpnFd                = iface.fd

                v2rayPoint = point
                point.runLoop()

                _connectionState.value = ServiceState.RUNNING
                updateNotification("Connected — ${profile.name}")

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
            ?: throw IllegalStateException("establish() returned null")

    private fun copyAssetIfNeeded(filename: String) {
        val dest = java.io.File(filesDir, filename)
        if (dest.exists()) return
        runCatching {
            assets.open(filename).use { it.copyTo(dest.outputStream()) }
            Log.d(TAG, "Copied $filename")
        }.onFailure { Log.w(TAG, "$filename missing: ${it.message}") }
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
