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
        val profile = activeProfile ?: run {
            Log.e(TAG, "activeProfile is null"); stopSelf(); return
        }

        _connectionState.value = ServiceState.STARTING
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        scope.launch {
            try {
                copyAssetIfNeeded("geoip.dat")
                copyAssetIfNeeded("geosite.dat")

                Libv2ray.initCoreEnv(filesDir.absolutePath, "")

                val config = XrayConfigBuilder.build(profile)
                Log.d(TAG, "Starting xray for ${profile.name} (${profile.protocol}+${profile.transport})")

                val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
                    override fun startup(): Long {
                        Log.d(TAG, "xray startup — establishing TUN")
                        // Start TUN only after xray core is running and ready
                        scope.launch(Dispatchers.IO) {
                            try {
                                vpnInterface = buildVpnInterface()
                                _connectionState.value = ServiceState.RUNNING
                                updateNotification("Connected — ${profile.name}")
                                startStatsLoop()
                            } catch (e: Exception) {
                                Log.e(TAG, "TUN setup failed: ${e.message}", e)
                                stopVpn()
                            }
                        }
                        return 0L
                    }

                    override fun shutdown(): Long {
                        Log.d(TAG, "xray shutdown()")
                        return 0L
                    }

                    override fun onEmitStatus(p0: Long, p1: String): Long {
                        Log.d(TAG, "xray[$p0]: $p1")
                        return 0L
                    }
                })

                coreController = controller
                // startLoop blocks until stopLoop() is called
                controller.startLoop(config)

            } catch (e: Exception) {
                Log.e(TAG, "VPN start failed: ${e.message}", e)
                withContext(Dispatchers.Main) { stopVpn() }
            }
        }
    }

    private fun startStatsLoop() {
        val ctrl = coreController ?: return
        statsJob = scope.launch {
            while (isActive) {
                delay(1000)
                runCatching {
                    _bytesIn.value  += ctrl.queryStats("proxy", "downlink")
                    _bytesOut.value += ctrl.queryStats("proxy", "uplink")
                }
            }
        }
    }

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

    /**
     * TUN interface that routes ALL traffic to xray's SOCKS5 inbound on 127.0.0.1:10808.
     * xray is already running at this point (called from startup() callback).
     * Android routes all TUN traffic through xray via the 10808 SOCKS5 listener.
     */
    private fun buildVpnInterface(): ParcelFileDescriptor =
        Builder()
            .setSession("palazikVPN")
            // TUN address — Android VPN virtual interface
            .addAddress("26.26.26.1", 24)
            // Route all IPv4 and IPv6 through TUN
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            // Use xray's built-in DNS (via its inbound on localhost)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .setMtu(1500)
            // Exclude our own app so xray's outbound traffic bypasses TUN
            // This prevents the routing loop: app → TUN → xray → TUN → xray...
            .addDisallowedApplication(packageName)
            .establish()
            ?: throw IllegalStateException("establish() returned null — VPN permission not granted?")

    private fun copyAssetIfNeeded(filename: String) {
        val dest = java.io.File(filesDir, filename)
        if (dest.exists()) return
        runCatching {
            assets.open(filename).use { it.copyTo(dest.outputStream()) }
            Log.d(TAG, "Copied $filename")
        }.onFailure { Log.w(TAG, "$filename missing from assets: ${it.message}") }
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
