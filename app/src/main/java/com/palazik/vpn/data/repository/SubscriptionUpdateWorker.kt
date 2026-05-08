package com.palazik.vpn.data.repository

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.palazik.vpn.data.model.AppSettings
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

class SubscriptionUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("palazik_profiles", Context.MODE_PRIVATE)
        val enabled = runCatching {
            org.json.JSONObject(prefs.getString("app_settings", null) ?: "{}")
                .optBoolean("autoUpdateSubscriptions", AppSettings().autoUpdateSubscriptions)
        }.getOrDefault(AppSettings().autoUpdateSubscriptions)
        if (!enabled) return Result.success()

        val repo = ProfileRepository(applicationContext, directClient(), proxyClient())
        if (repo.subscriptions.value.isEmpty()) return Result.success()

        val results = repo.updateAllSubscriptions()
        return if (results.any { it.isSuccess }) Result.success() else Result.retry()
    }

    private fun directClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    private fun proxyClient(): OkHttpClient =
        OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808)))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
}
