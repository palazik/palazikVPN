package com.palazik.vpn.data.repository

import android.content.Context
import com.palazik.vpn.data.codec.ProfileCodec
import com.palazik.vpn.data.model.PingMode
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
) {
    private val prefs = context.getSharedPreferences("palazik_profiles", Context.MODE_PRIVATE)

    private val _profiles      = MutableStateFlow<List<VpnProfile>>(emptyList())
    val profiles: StateFlow<List<VpnProfile>> = _profiles.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    private val _pingMode      = MutableStateFlow(PingMode.TCP)
    val pingMode: StateFlow<PingMode> = _pingMode.asStateFlow()

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
        // FIX #2: atomically remove both sub and its profiles, then save both
        val newProfiles = _profiles.value.filter { it.subscriptionId != id }
        val newSubs     = _subscriptions.value.filter { it.id != id }
        _profiles.value      = newProfiles
        _subscriptions.value = newSubs
        saveProfiles()
        saveSubscriptions()
    }

    suspend fun updateSubscription(sub: Subscription): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val req    = Request.Builder().url(sub.url).build()
            val body   = httpClient.newCall(req).execute().use { it.body?.string() ?: "" }
            val fresh  = ProfileCodec.decodeSubscriptionBody(body)
                .map { it.copy(subscriptionId = sub.id) }

            // Deduplicate by (address, port, uuid, protocol) fingerprint.
            // Snapshot current profiles atomically before mutating.
            fun VpnProfile.fingerprint() = "$address:$port:$uuid:${protocol.name}"

            val snapshot = _profiles.value                          // single atomic read
            val nonSubProfiles = snapshot.filter { it.subscriptionId != sub.id }
            val existingByFingerprint = snapshot
                .filter { it.subscriptionId == sub.id }
                .associateBy { it.fingerprint() }

            val merged = fresh.map { newProfile ->
                val existing = existingByFingerprint[newProfile.fingerprint()]
                if (existing != null) {
                    newProfile.copy(
                        id             = existing.id,
                        isActive       = existing.isActive,
                        latencyMs      = existing.latencyMs,
                        subscriptionId = sub.id,
                    )
                } else {
                    newProfile
                }
            }

            // Single atomic write — prevents duplication from concurrent updates
            _profiles.value = nonSubProfiles + merged
            saveProfiles()

            val updated = sub.copy(lastUpdated = System.currentTimeMillis(), profileCount = merged.size)
            _subscriptions.value = _subscriptions.value.map { if (it.id == sub.id) updated else it }
            saveSubscriptions()

            merged.size
        }
    }

    suspend fun updateAllSubscriptions(): List<Result<Int>> =
        _subscriptions.value.map { updateSubscription(it) }

    // ── Ping ──────────────────────────────────────────────────────────────────

    fun setPingMode(mode: PingMode) {
        _pingMode.value = mode
        prefs.edit().putString("ping_mode", mode.name).apply()
    }

    suspend fun pingProfile(profile: VpnProfile): Long = withContext(Dispatchers.IO) {
        val latency = runCatching {
            when (_pingMode.value) {
                PingMode.TCP -> {
                    val start = System.currentTimeMillis()
                    Socket().use { sock ->
                        sock.connect(InetSocketAddress(profile.address, profile.port), 5000)
                    }
                    System.currentTimeMillis() - start
                }
                PingMode.HTTP_GET -> {
                    val req = Request.Builder()
                        .url("http://cp.cloudflare.com/")
                        .get()
                        .build()
                    val start = System.currentTimeMillis()
                    httpClient.newCall(req).execute().use { }
                    System.currentTimeMillis() - start
                }
                PingMode.HTTP_HEAD -> {
                    val req = Request.Builder()
                        .url("http://cp.cloudflare.com/")
                        .head()
                        .build()
                    val start = System.currentTimeMillis()
                    httpClient.newCall(req).execute().use { }
                    System.currentTimeMillis() - start
                }
            }
        }.getOrElse { -1L }

        updateProfile(profile.copy(latencyMs = latency))
        latency
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun saveProfiles() {
        val links = JSONArray()
        val meta  = JSONArray()
        _profiles.value.forEach { p ->
            links.put(ProfileCodec.encodePalazik(p))
            meta.put(JSONObject().apply {
                put("id",       p.id)
                put("isActive", p.isActive)
                put("latency",  p.latencyMs)
                put("subId",    p.subscriptionId ?: "")
            })
        }
        prefs.edit()
            .putString("profiles_links", links.toString())
            .putString("profiles_meta",  meta.toString())
            .apply()
    }

    private fun saveSubscriptions() {
        val arr = JSONArray()
        _subscriptions.value.forEach { sub ->
            arr.put(JSONObject().apply {
                put("id",           sub.id)
                put("name",         sub.name)
                put("url",          sub.url)
                put("lastUpdated",  sub.lastUpdated)
                put("profileCount", sub.profileCount)
            })
        }
        prefs.edit().putString("subscriptions_json", arr.toString()).apply()
    }

    private fun loadFromPrefs() {
        val linksJson = prefs.getString("profiles_links", null)
        val metaJson  = prefs.getString("profiles_meta",  null)

        data class Meta(val isActive: Boolean, val latency: Long, val subId: String?)
        val metaMap = mutableMapOf<String, Meta>()
        if (metaJson != null) runCatching {
            val arr = JSONArray(metaJson)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                metaMap[o.getString("id")] = Meta(
                    isActive = o.optBoolean("isActive", false),
                    latency  = o.optLong("latency", -1L),
                    subId    = o.optString("subId").takeIf { it.isNotEmpty() },
                )
            }
        }

        if (linksJson != null) runCatching {
            val arr    = JSONArray(linksJson)
            val loaded = mutableListOf<VpnProfile>()
            for (i in 0 until arr.length()) {
                ProfileCodec.decode(arr.getString(i))?.let { profile ->
                    val m = metaMap[profile.id]
                    loaded.add(profile.copy(
                        isActive       = m?.isActive ?: false,
                        latencyMs      = m?.latency  ?: -1L,
                        subscriptionId = m?.subId    ?: profile.subscriptionId,
                    ))
                }
            }
            _profiles.value = loaded
        }

        val subsJson = prefs.getString("subscriptions_json", null)
        if (subsJson != null) runCatching {
            val arr    = JSONArray(subsJson)
            val loaded = mutableListOf<Subscription>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                loaded.add(Subscription(
                    id           = o.getString("id"),
                    name         = o.getString("name"),
                    url          = o.getString("url"),
                    lastUpdated  = o.optLong("lastUpdated", 0L),
                    profileCount = o.optInt("profileCount", 0),
                ))
            }
            _subscriptions.value = loaded
        }

        val saved = prefs.getString("ping_mode", PingMode.TCP.name)
        _pingMode.value = runCatching { PingMode.valueOf(saved ?: "") }.getOrElse {
            when (saved) {
                "PROXY_GET"  -> PingMode.HTTP_GET
                "PROXY_HEAD" -> PingMode.HTTP_HEAD
                else         -> PingMode.TCP
            }
        }
    }
}
