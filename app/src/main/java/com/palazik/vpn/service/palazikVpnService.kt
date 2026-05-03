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
            Log.e(TAG, "activeProfile is null"); stopSelf(); return
        }

        _connectionState.value = ServiceState.STARTING
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        scope.launch {
            try {
                copyAssetIfNeeded("geoip.dat")
                copyAssetIfNeeded("geosite.dat")

                // Set asset path so xray finds geo files
                Libv2ray.initCoreEnv(filesDir.absolutePath, "")

                // Establish TUN before starting core
                val iface = buildVpnInterface()
                vpnInterface = iface

                // Embed tunFd into the xray config so the core can open it directly.
                // startLoop() takes only config string — tunFd is passed via env var
                // that initCoreEnv already handles through xray's internal mechanism.
                // We set it as an OS env var via the standard Java API before startLoop.
                setenv("xray.tun.fd", iface.fd.toString())

                val config = XrayConfigBuilder.build(profile)
                Log.d(TAG, "Starting xray for ${profile.name} tunFd=${iface.fd}")

                val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
                    override fun startup(): Long {
                        Log.d(TAG, "xray startup()")
                        _connectionState.value = ServiceState.RUNNING
                        updateNotification("Connected — ${profile.name}")
                        startStatsLoop()
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

                // startLoop blocks until stopped
                controller.startLoop(config)

            } catch (e: Exception) {
                Log.e(TAG, "VPN start failed: ${e.message}", e)
                withContext(Dispatchers.Main) { stopVpn() }
            }
        }
    }

    /**
     * Set an OS-level environment variable so the Go runtime (xray core) can read it
     * via os.Getenv(). Java System.setProperty() is JVM-only and invisible to native code.
     * We use Android's libc setenv via a small reflection trick on ProcessEnvironment.
     */
    @Suppress("DiscouragedPrivateApi")
    private fun setenv(key: String, value: String) {
        try {
            val processEnvironment = Class.forName("java.lang.ProcessEnvironment")
            val theUnmodifiableEnvironment = processEnvironment
                .getDeclaredField("theUnmodifiableEnvironment")
                .apply { isAccessible = true }
                .get(null)
            // The backing map of theUnmodifiableEnvironment
            val m = theUnmodifiableEnvironment.javaClass
                .getDeclaredField("m")
                .apply { isAccessible = true }
                .get(theUnmodifiableEnvironment) as MutableMap<Any, Any>
            // ProcessEnvironment uses special String subclasses for keys/values
            val theEnvironment = processEnvironment
                .getDeclaredField("theEnvironment")
                .apply { isAccessible = true }
                .get(null) as MutableMap<Any, Any>
            val strClass = Class.forName("java.lang.ProcessEnvironment\$Variable")
            val valueOf = strClass.getDeclaredMethod("valueOf", String::class.java)
                .apply { isAccessible = true }
            val k = valueOf.invoke(null, key)
            val v = valueOf.invoke(null, value)
            theEnvironment[k!!] = v!!
            m[k] = v
            Log.d(TAG, "setenv $key=$value")
        } catch (e: Exception) {
            // Fallback: try native setenv via Runtime — unreliable but worth trying
            Log.w(TAG, "setenv reflection failed, trying native: ${e.message}")
            runCatching {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "export $key=$value"))
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
