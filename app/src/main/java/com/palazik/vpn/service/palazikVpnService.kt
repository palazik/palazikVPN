package com.palazik.vpn.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Base64
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
import java.io.File
import java.io.FileOutputStream

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

    // ── Network callback (API 28+): keeps setUnderlyingNetworks up to date so
    //   xray's outbound sockets are protected on the real network interface,
    //   not looped back into the TUN. Without this, xray connects but traffic
    //   goes TUN→xray→TUN→... and the internet doesn't work.
    private val connectivity by lazy {
        getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val networkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }
    private val networkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                setUnderlyingNetworks(arrayOf(network))
            }
            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP  -> stopVpn()
        }
        return START_STICKY
    }

    override fun onRevoke() { stopVpn() }
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Start ─────────────────────────────────────────────────────────────────

    private fun startVpn() {
        val profile = activeProfile ?: run {
            Log.e(TAG, "activeProfile is null")
            stopSelf()
            return
        }

        _connectionState.value = ServiceState.STARTING
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        scope.launch {
            try {
                prepareGeodata()
                initializeLibv2ray()

                val config = XrayConfigBuilder.build(profile)
                Log.d(TAG, "Xray config built for ${profile.name}")

                val iface = buildVpnInterface()
                vpnInterface = iface
                Log.d(TAG, "TUN established, fd=${iface.fd}")

                // Register network callback before startLoop so setUnderlyingNetworks
                // is set before xray tries to make outbound connections.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        connectivity.requestNetwork(networkRequest, networkCallback)
                    } catch (e: Exception) {
                        Log.w(TAG, "requestNetwork failed: ${e.message}")
                    }
                }

                val controller = Libv2ray.newCoreController(V2RayCallback())
                controller.registerProcessFinder(object : ProcessFinder {
                    override fun findProcessByConnection(
                        network: String, src: String, srcPort: Long,
                        dst: String, dstPort: Long,
                    ): Long = 0L
                })
                coreController = controller

                // Pass the actual TUN fd — xray uses it to read/write packets.
                // Passing 0 means xray runs as a plain proxy (socks/http only)
                // and never processes TUN traffic, so internet doesn't work.
                controller.startLoop(config, iface.fd.toLong())

                _connectionState.value = ServiceState.RUNNING
                updateNotification("Connected — ${profile.name}")
                startStatsPolling()

            } catch (e: Exception) {
                Log.e(TAG, "VPN start failed: ${e.message}", e)
                withContext(Dispatchers.Main) { stopVpn() }
            }
        }
    }

    // ── Geodata / init ────────────────────────────────────────────────────────

    private fun userAssetPath(): String {
        return (applicationContext.getExternalFilesDir("assets")
            ?: applicationContext.getDir("assets", 0)).absolutePath
    }

    private fun getDeviceIdForXUDPBaseKey(): String {
        val androidId = Settings.Secure.ANDROID_ID.toByteArray(Charsets.UTF_8)
        return Base64.encodeToString(
            androidId.copyOf(32),
            Base64.NO_PADDING or Base64.URL_SAFE
        )
    }

    private fun prepareGeodata() {
        val assets  = applicationContext.assets
        val destDir = File(userAssetPath()).also { it.mkdirs() }

        listOf("geoip.dat", "geosite.dat").forEach { fileName ->
            val dest = File(destDir, fileName)
            if (!dest.exists() || dest.length() == 0L) {
                try {
                    assets.open(fileName).use { input ->
                        FileOutputStream(dest).use { output -> input.copyTo(output) }
                    }
                    Log.d(TAG, "Copied $fileName → ${dest.absolutePath} (${dest.length()} bytes)")
                } catch (e: Exception) {
                    throw RuntimeException("Missing asset: $fileName", e)
                }
            } else {
                Log.d(TAG, "$fileName already present (${dest.length()} bytes)")
            }
        }
    }

    private fun initializeLibv2ray() {
        val assetPath = userAssetPath()
        val deviceId  = getDeviceIdForXUDPBaseKey()
        Libv2ray.initCoreEnv(assetPath, deviceId)
        Log.d(TAG, "✓ Libv2ray.initCoreEnv(assetPath=$assetPath)")
    }

    // ── TUN interface ─────────────────────────────────────────────────────────

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
            .also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    it.setMetered(false)
                }
            }
            .establish()
            ?: throw IllegalStateException("establish() returned null — missing VPN permission?")

    // ── Stop ──────────────────────────────────────────────────────────────────

    private fun stopVpn() {
        if (_connectionState.value == ServiceState.STOPPED) return
        _connectionState.value = ServiceState.STOPPING
        statsJob?.cancel()
        statsJob = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { connectivity.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        }

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
