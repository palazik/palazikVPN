package com.palazik.vpn.data.repository

import android.content.Context
import com.palazik.vpn.data.codec.ProfileCodec
import com.palazik.vpn.data.model.Subscription
import com.palazik.vpn.data.model.VpnProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
) {
    private val prefs = context.getSharedPreferences("palazik_profiles", Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow<List<VpnProfile>>(emptyList())
    val profiles: StateFlow<List<VpnProfile>> = _profiles.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    init { loadFromPrefs() }

    // ── Profiles ──────────────────────────────────────────────────────────────

    fun addProfile(profile: VpnProfile) {
        _profiles.value = _profiles.value + profile
        saveProfiles()
    }

    fun removeProfile(id: String) {
        _profiles.value = _profiles.value.filter { it.id != id }
        saveProfiles()
    }

    fun updateProfile(profile: VpnProfile) {
        _profiles.value = _profiles.value.map { if (it.id == profile.id) profile else it }
        saveProfiles()
    }

    fun getActiveProfile(): VpnProfile? = _profiles.value.firstOrNull { it.isActive }

    fun setActiveProfile(id: String) {
        _profiles.value = _profiles.value.map { it.copy(isActive = it.id == id) }
        saveProfiles()
    }

    // ── Subscriptions ─────────────────────────────────────────────────────────

    fun addSubscription(sub: Subscription) {
        _subscriptions.value = _subscriptions.value + sub
        saveSubscriptions()
    }

    fun removeSubscription(id: String) {
        _subscriptions.value = _subscriptions.value.filter { it.id != id }
        _profiles.value = _profiles.value.filter { it.subscriptionId != id }
        saveProfiles(); saveSubscriptions()
    }

    suspend fun updateSubscription(sub: Subscription): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val req      = Request.Builder().url(sub.url).build()
            val body     = httpClient.newCall(req).execute().use { it.body?.string() ?: "" }
            val parsed   = ProfileCodec.decodeSubscriptionBody(body)
                .map { it.copy(subscriptionId = sub.id) }

            // Replace old profiles from this sub
            _profiles.value = _profiles.value.filter { it.subscriptionId != sub.id } + parsed
            saveProfiles()

            val updated = sub.copy(lastUpdated = System.currentTimeMillis(), profileCount = parsed.size)
            _subscriptions.value = _subscriptions.value.map { if (it.id == sub.id) updated else it }
            saveSubscriptions()

            parsed.size
        }
    }

    suspend fun updateAllSubscriptions(): List<Result<Int>> =
        _subscriptions.value.map { updateSubscription(it) }

    // ── Ping ──────────────────────────────────────────────────────────────────

    suspend fun pingProfile(profile: VpnProfile): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        runCatching {
            val req = Request.Builder()
                .url("https://${profile.address}:${profile.port}")
                .head()
                .build()
            httpClient.newCall(req).execute().close()
        }
        val latency = System.currentTimeMillis() - start
        updateProfile(profile.copy(latencyMs = latency))
        latency
    }

    // ── Persistence (JSON via SharedPrefs — replace with Room for production) ─

    private fun saveProfiles() {
        val json = _profiles.value.joinToString(",", "[", "]") {
            ProfileCodec.encodePalazik(it)
        }
        prefs.edit().putString("profiles_raw", json).apply()
    }

    private fun saveSubscriptions() {
        val json = _subscriptions.value.joinToString(",", "[", "]") {
            """{"id":"${it.id}","name":"${it.name}","url":"${it.url}",""" +
            """"lastUpdated":${it.lastUpdated},"profileCount":${it.profileCount}}"""
        }
        prefs.edit().putString("subscriptions_raw", json).apply()
    }

    private fun loadFromPrefs() {
        // Minimal loader — reads back palazikVPN:// lines
        val raw = prefs.getString("profiles_raw", "") ?: ""
        _profiles.value = raw
            .removeSurrounding("[", "]")
            .split(",")
            .filter { it.startsWith("palazikVPN://") }
            .mapNotNull { ProfileCodec.decode(it) }

        // Subscriptions loaded separately (JSON parse omitted for brevity — add Gson/Moshi)
    }
}
