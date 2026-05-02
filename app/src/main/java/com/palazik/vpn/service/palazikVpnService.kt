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

class palazikVpnService : VpnService() {

    // ── Public state (observed by ViewModel via a bound service or broadcast) ─
    companion object {
        const val ACTION_START    = "com.palazik.vpn.START"
        const val ACTION_STOP     = "com.palazik.vpn.STOP"
        const val EXTRA_PROFILE   = "profile_id"
        const val NOTIFICATION_ID = 1001

        private val _connectionState = MutableStateFlow(ServiceState.STOPPED)
        val connectionState: StateFlow<ServiceState> = _connectionState

        private val _bytesIn  = MutableStateFlow(0L)
        private val _bytesOut = MutableStateFlow(0L)
        val bytesIn:  StateFlow<Long> = _bytesIn
        val bytesOut: StateFlow<Long> = _bytesOut
    }

    enum class ServiceState { STOPPED, STARTING, RUNNING, STOPPING }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var trafficJob: Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn(intent.getStringExtra(EXTRA_PROFILE))
            ACTION_STOP  -> stopVpn()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopVpn()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Start ─────────────────────────────────────────────────────────────────

    private fun startVpn(profileId: String?) {
        _connectionState.value = ServiceState.STARTING
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        scope.launch {
            try {
                val iface = buildVpnInterface()
                vpnInterface = iface
                _connectionState.value = ServiceState.RUNNING
                updateNotification("Connected")

                // ── HOOK YOUR ROUTING PROTOCOL HERE ──────────────────────────
                // Pass iface.fileDescriptor to your xray/sing-box/etc. tunnel.
                // For now we keep the fd open so the OS routes traffic through
                // the VPN interface but do nothing (null tunnel).
                startTrafficCounter()

            } catch (e: Exception) {
                Log.e("palazikVPN", "VPN start error", e)
                stopVpn()
            }
        }
    }

    private fun buildVpnInterface(): ParcelFileDescriptor {
        return Builder()
            .setSession("palazikVPN")
            .addAddress("10.0.0.2", 24)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .addRoute("0.0.0.0", 0)          // route all IPv4
            .addRoute("::", 0)               // route all IPv6
            .setMtu(1500)
            .setBlocking(false)
            .establish()
            ?: throw IllegalStateException("VPN interface could not be established")
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    private fun stopVpn() {
        _connectionState.value = ServiceState.STOPPING
        trafficJob?.cancel()
        vpnInterface?.close()
        vpnInterface = null
        _connectionState.value = ServiceState.STOPPED
        _bytesIn.value  = 0L
        _bytesOut.value = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Traffic counter stub ───────────────────────────────────────────────────

    private fun startTrafficCounter() {
        trafficJob = scope.launch {
            while (isActive) {
                delay(1000)
                // Replace with real read from your tunnel interface
                _bytesIn.value  += (512..8192).random()
                _bytesOut.value += (256..4096).random()
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
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }
}
