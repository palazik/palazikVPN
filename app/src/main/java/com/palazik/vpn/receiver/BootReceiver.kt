package com.palazik.vpn.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs: SharedPreferences =
            context.getSharedPreferences("palazik_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("start_on_boot", false)) {
            // optionally auto-start the VPN — user must grant permission first
        }
    }
}
