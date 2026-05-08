package com.palazik.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.palazik.vpn.data.model.AppSettings
import com.palazik.vpn.data.repository.SubscriptionUpdateScheduler
import dagger.hilt.android.HiltAndroidApp
import org.json.JSONObject

@HiltAndroidApp
class palazikVPNApp : Application() {

    companion object {
        const val CHANNEL_VPN = "palazikvpn_service"
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_VPN,
                "palazikVPN Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent VPN connection status"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        SubscriptionUpdateScheduler.sync(this, loadAppSettings())
    }

    private fun loadAppSettings(): AppSettings {
        val prefs = getSharedPreferences("palazik_profiles", Context.MODE_PRIVATE)
        val raw = prefs.getString("app_settings", null) ?: return AppSettings()
        return runCatching {
            val o = JSONObject(raw)
            AppSettings(
                autoUpdateSubscriptions = o.optBoolean("autoUpdateSubscriptions", true),
                subscriptionUpdateIntervalHours = o.optLong("subscriptionUpdateIntervalHours", 2L).coerceAtLeast(2L),
            )
        }.getOrDefault(AppSettings())
    }
}
