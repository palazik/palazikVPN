package com.palazik.vpn.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.palazik.vpn.R
import com.palazik.vpn.data.model.AppSettings
import com.palazik.vpn.data.model.VpnProfile
import com.palazik.vpn.palazikVPNApp
import com.palazik.vpn.ui.MainActivity
import go.Seq
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import libv2ray.ProcessFinder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class palazikVpnService : VpnService() {

    companion object {
        const val ACTION_START    = "com.palazik.vpn.START"
        const val ACTION_STOP     = "com.palazik.vpn.STOP"
        const val EXTRA_PROFILE   = "profile_id"
        const val NOTIFICATION_ID = 1001
        private const val TAG     = "palazikVPN"

        // initCoreEnv must only be called once per process lifetime (like v2rayNG)
        private val coreEnvInitialized = AtomicBoolean(false)

        private val _connectionState = MutableStateFlow(ServiceState.STOPPED)
        val connectionState: StateFlow<ServiceState> = _connectionState

        private val _bytesIn  = MutableStateFlow(0L)
        private val _bytesOut = MutableStateFlow(0L)
        private val _connectedSince = MutableStateFlow(0L)
        private val _diagnostics = MutableStateFlow<List<String>>(emptyList())
        private val _lastError = MutableStateFlow<String?>(null)
        val bytesIn:  StateFlow<Long> = _bytesIn
        val bytesOut: StateFlow<Long> = _bytesOut
        val connectedSince: StateFlow<Long> = _connectedSince
        val diagnostics: StateFlow<List<String>> = _diagnostics
        val lastError: StateFlow<String?> = _lastError

        @Volatile var activeProfile: VpnProfile? = null
    }

    enum class ServiceState { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statsJob: Job? = null

    // v2rayNG: registerDefaultNetworkCallback returns our VPN interface, so we use
    // requestNetwork with a specific request to get the real underlying network,
    // then call setUnderlyingNetworks so xray's outbound sockets bypass the TUN.
    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val defaultNetworkCallback by lazy {
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

    override fun onCreate() {
        super.onCreate()
        // v2rayNG sets permitAll thread policy in onCreate to avoid NetworkOnMainThreadException
        // during core init which may do brief I/O
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP  -> stopVpn()
        }
        return START_STICKY
    }

    override fun onRevoke() { stopVpn() }

    override fun onDestroy() {
        if (_connectionState.value != ServiceState.STOPPED && _connectionState.value != ServiceState.ERROR) {
            stopVpn()
        }
        scope.cancel()
        super.onDestroy()
    }

    // ── Start ─────────────────────────────────────────────────────────────────

    private fun startVpn() {
        if (_connectionState.value == ServiceState.STARTING || _connectionState.value == ServiceState.RUNNING) {
            Log.d(TAG, "VPN already starting/running")
            addDiagnostic("Start ignored: VPN already starting/running")
            return
        }
        val profile = activeProfile ?: run {
            Log.e(TAG, "activeProfile is null")
            _lastError.value = "No active profile selected"
            addDiagnostic("Start failed: no active profile")
            _connectionState.value = ServiceState.ERROR
            stopSelf()
            return
        }

        _connectionState.value = ServiceState.STARTING
        _lastError.value = null
        addDiagnostic("Starting ${profile.name}")
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        scope.launch {
            try {
                prepareGeodata()
                initializeLibv2ray()

                val settings = loadAppSettings()
                val config = XrayConfigBuilder.build(profile, settings)
                Log.d(TAG, "Xray config built for ${profile.name}")

                // Register network callback BEFORE establish() so setUnderlyingNetworks
                // is set before the TUN interface captures all traffic
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
                    } catch (e: Exception) {
                        Log.w(TAG, "requestNetwork failed: ${e.message}")
                    }
                }

                val iface = buildVpnInterface(settings)
                vpnInterface = iface
                Log.d(TAG, "TUN established, fd=${iface.fd}")

                val controller = Libv2ray.newCoreController(V2RayCallback())
                controller.registerProcessFinder(object : ProcessFinder {
                    override fun findProcessByConnection(
                        network: String, src: String, srcPort: Long,
                        dst: String, dstPort: Long,
                    ): Long = 0L
                })
                coreController = controller

                // Pass actual TUN fd — xray reads packets from it via the "tun" inbound.
                // v2rayNG: tunFd = vpnInterface?.fd ?: 0
                controller.startLoop(config, iface.fd)

                _connectionState.value = ServiceState.RUNNING
                _connectedSince.value = System.currentTimeMillis()
                addDiagnostic("Connected: ${profile.name}")
                updateNotification("Connected — ${profile.name}")
                startStatsPolling()

            } catch (e: Exception) {
                Log.e(TAG, "VPN start failed: ${e.message}", e)
                _lastError.value = e.message ?: e.javaClass.simpleName
                addDiagnostic("Start failed: ${e.message ?: e.javaClass.simpleName}")
                withContext(Dispatchers.Main) { failVpn() }
            }
        }
    }

    // ── Geodata / init ────────────────────────────────────────────────────────

    // v2rayNG: getExternalFilesDir("assets") ?: getDir("assets", 0)
    private fun userAssetPath(): String {
        return (applicationContext.getExternalFilesDir("assets")
            ?: applicationContext.getDir("assets", 0)).absolutePath
    }

    // v2rayNG: ANDROID_ID.toByteArray().copyOf(32) → Base64(NO_PADDING | URL_SAFE)
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
            }
        }
    }

    // v2rayNG CoreNativeManager: Seq.setContext() first, then initCoreEnv(), only once via AtomicBoolean
    private fun initializeLibv2ray() {
        if (coreEnvInitialized.compareAndSet(false, true)) {
            try {
                Seq.setContext(applicationContext)
                val assetPath = userAssetPath()
                val deviceId  = getDeviceIdForXUDPBaseKey()
                Libv2ray.initCoreEnv(assetPath, deviceId)
                Log.d(TAG, "✓ Libv2ray.initCoreEnv($assetPath)")
            } catch (e: Exception) {
                coreEnvInitialized.set(false)
                throw e
            }
        } else {
            Log.d(TAG, "Libv2ray already initialized, skipping")
        }
    }

    // ── TUN interface ─────────────────────────────────────────────────────────

    // v2rayNG uses /30 mask with point-to-point pair (e.g. 10.10.14.1/30),
    // not /24. This matches xray-core's TUN expectations.
    private fun buildVpnInterface(settings: AppSettings): ParcelFileDescriptor {
        val builder = Builder()
            .setSession("palazikVPN")
            .setMtu(1500)
            .addAddress("10.10.14.1", 30)   // v2rayNG default: OPTION_1
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDisallowedApplication(packageName)

        settings.dnsServers.forEach { dns ->
            runCatching { builder.addDnsServer(dns) }
                .onFailure { Log.w(TAG, "Invalid DNS server ignored: $dns") }
        }

        settings.bypassPackages.forEach { pkg ->
            if (pkg != packageName) {
                runCatching { builder.addDisallowedApplication(pkg) }
                    .onFailure { Log.w(TAG, "Bypass package ignored: $pkg") }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        return builder
            .establish()
            ?: throw IllegalStateException("establish() returned null — missing VPN permission?")
    }

    private fun loadAppSettings(): AppSettings {
        val prefs = applicationContext.getSharedPreferences("palazik_profiles", Context.MODE_PRIVATE)
        val raw = prefs.getString("app_settings", null) ?: return AppSettings()
        return runCatching {
            val o = JSONObject(raw)
            AppSettings(
                dnsServers = o.optJSONArray("dnsServers")?.toStringList()
                    ?.filter { it.isNotBlank() }
                    ?.ifEmpty { AppSettings().dnsServers }
                    ?: AppSettings().dnsServers,
                remoteDns = o.optString("remoteDns", AppSettings().remoteDns).ifBlank { AppSettings().remoteDns },
                directDns = o.optString("directDns", AppSettings().directDns).ifBlank { AppSettings().directDns },
                bypassPackages = o.optJSONArray("bypassPackages")?.toStringList()
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList(),
                startOnBoot = o.optBoolean("startOnBoot", false),
            )
        }.getOrDefault(AppSettings())
    }

    private fun JSONArray.toStringList(): List<String> =
        buildList {
            for (i in 0 until length()) {
                add(optString(i))
            }
        }

    // ── Stop ──────────────────────────────────────────────────────────────────

    private fun stopVpn() {
        if (_connectionState.value == ServiceState.STOPPED) return
        _connectionState.value = ServiceState.STOPPING
        addDiagnostic("Stopping VPN")
        statsJob?.cancel()
        statsJob = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { connectivity.unregisterNetworkCallback(defaultNetworkCallback) } catch (_: Exception) {}
        }

        // v2rayNG: stopSelf() BEFORE mInterface.close() — otherwise core fails to stop
        // and subsequent startLoop calls report "port in use"
        try { coreController?.stopLoop() } catch (e: Exception) { Log.w(TAG, "stopLoop: ${e.message}") }
        coreController = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        // Small delay to allow async core stop before closing TUN (v2rayNG: Thread.sleep(100))
        Thread.sleep(100)

        try { vpnInterface?.close() } catch (e: Exception) { Log.w(TAG, "iface close: ${e.message}") }
        vpnInterface = null

        _connectionState.value = ServiceState.STOPPED
        _lastError.value = null
        _connectedSince.value = 0L
        addDiagnostic("Stopped")
        _bytesIn.value  = 0L
        _bytesOut.value = 0L
    }

    private fun failVpn() {
        if (_connectionState.value == ServiceState.STOPPED) {
            _connectionState.value = ServiceState.ERROR
            return
        }
        statsJob?.cancel()
        statsJob = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { connectivity.unregisterNetworkCallback(defaultNetworkCallback) } catch (_: Exception) {}
        }

        try { coreController?.stopLoop() } catch (e: Exception) { Log.w(TAG, "stopLoop: ${e.message}") }
        coreController = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        try { vpnInterface?.close() } catch (e: Exception) { Log.w(TAG, "iface close: ${e.message}") }
        vpnInterface = null
        _bytesIn.value = 0L
        _bytesOut.value = 0L
        _connectedSince.value = 0L
        _connectionState.value = ServiceState.ERROR
        addDiagnostic("Service entered error state")
        stopSelf()
    }

    // ── CoreCallbackHandler ───────────────────────────────────────────────────

    private inner class V2RayCallback : CoreCallbackHandler {
        override fun onEmitStatus(level: Long, msg: String): Long {
            Log.d(TAG, "xray[$level]: $msg")
            addDiagnostic("xray[$level]: $msg")
            return 0L
        }
        override fun shutdown(): Long {
            Log.d(TAG, "xray: shutdown")
            addDiagnostic("xray requested shutdown")
            scope.launch(Dispatchers.Main) { stopVpn() }
            return 0L
        }
        override fun startup(): Long = 0L
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
        val builder = NotificationCompat.Builder(this, palazikVPNApp.CHANNEL_VPN)
            .setContentTitle("palazikVPN")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setOngoing(true)
        builder.setContentIntent(pi)
        return builder.build()
    }

    private fun updateNotification(status: String) {
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun addDiagnostic(message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        _diagnostics.value = (_diagnostics.value + "$stamp  $message").takeLast(80)
    }
}
