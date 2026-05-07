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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("direct") private val directClient: OkHttpClient,
    @Named("proxy")  private val proxyClient:  OkHttpClient,
) {
    private val prefs = context.getSharedPreferences("palazik_profiles", Context.MODE_PRIVATE)

    private val _profiles      = MutableStateFlow<List<VpnProfile>>(emptyList())
    val profiles: StateFlow<List<VpnProfile>> = _profiles.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    private val _pingMode      = MutableStateFlow(PingMode.TCP)
    val pingMode: StateFlow<PingMode> = _pingMode.asStateFlow()

    // Serializes concurrent subscription updates to prevent profile duplication
    private val updateMutex = Mutex()

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
        // Atomically remove both the subscription and all its profiles, then persist both
        val newProfiles = _profiles.value.filter { it.subscriptionId != id }
        val newSubs     = _subscriptions.value.filter { it.id != id }
        _profiles.value      = newProfiles
        _subscriptions.value = newSubs
        saveProfiles()
        saveSubscriptions()
    }

    /**
     * Update a subscription by fetching its URL and replacing old profiles with new ones.
     *
     * Strategy (mirrors v2rayNG AngConfigManager.updateConfigViaSub):
     *  1. Try via the local SOCKS proxy (127.0.0.1:10808) — works when VPN is running.
     *  2. On any failure, fall back to a direct connection — works when VPN is off.
     *
     * Profile replacement (mirrors v2rayNG parseBatchConfig with append=false):
     *  - Remember the currently active profile for this subscription.
     *  - DELETE all old profiles that belong to this subscription.
     *  - ADD all freshly parsed profiles.
     *  - If the previously-active profile appears in the new list (matched by fingerprint),
     *    restore its active flag so the user's selection is preserved across updates.
     */
    suspend fun updateSubscription(sub: Subscription): Result<Int> = withContext(Dispatchers.IO) {
        updateMutex.withLock {
            runCatching {
                val body = fetchSubscriptionBody(sub.url)

                val freshProfiles = ProfileCodec.decodeSubscriptionBody(body)
                    .map { it.copy(subscriptionId = sub.id) }

                // v2rayNG: remember which profile was selected before wiping
                val snapshot      = _profiles.value
                val prevActive    = snapshot.firstOrNull { it.subscriptionId == sub.id && it.isActive }
                fun VpnProfile.fingerprint() = "$address:$port:$uuid:${protocol.name}"
                val prevFingerprint = prevActive?.fingerprint()

                // All profiles NOT belonging to this subscription are kept untouched
                val retained = snapshot.filter { it.subscriptionId != sub.id }

                // Map new profiles; restore active flag if fingerprint matches previous active
                val merged = freshProfiles.map { p ->
                    if (prevFingerprint != null && p.fingerprint() == prevFingerprint) {
                        p.copy(isActive = true)
                    } else {
                        p
                    }
                }
                val retainedProfiles = if (merged.any { it.isActive }) {
                    retained.map { it.copy(isActive = false) }
                } else {
                    retained
                }

                // Single atomic write — old sub profiles deleted, new ones added
                _profiles.value = retainedProfiles + merged
                saveProfiles()

                val updated = sub.copy(
                    lastUpdated  = System.currentTimeMillis(),
                    profileCount = merged.size,
                )
                _subscriptions.value = _subscriptions.value.map { if (it.id == sub.id) updated else it }
                saveSubscriptions()

                merged.size
            }
        }
    }

    suspend fun updateAllSubscriptions(): List<Result<Int>> =
        _subscriptions.value.map { updateSubscription(it) }

    // ── Ping ──────────────────────────────────────────────────────────────────

    fun setPingMode(mode: PingMode) {
        _pingMode.value = mode
        prefs.edit().putString("ping_mode", mode.name).apply()
    }

    /**
     * Ping a profile using the currently-selected ping mode.
     *
     * TCP  — Direct TCP connect to the server's address:port (v2rayNG SpeedtestManager.tcping).
     *         Works without the VPN running. Measures raw reachability of the server.
     *
     * GET / HEAD — HTTP request to https://cp.cloudflare.com/ routed through the local SOCKS
     *         proxy (127.0.0.1:10808) so it actually travels through the xray/proxy outbound.
     *         v2rayNG routes real-ping tests through its local proxy port so the result
     *         reflects end-to-end latency through the proxy profile.
     *         Requires the VPN / xray service to be running.
     */
    suspend fun pingProfile(profile: VpnProfile): Long = withContext(Dispatchers.IO) {
        val latency = runCatching {
            when (_pingMode.value) {
                PingMode.TCP -> {
                    // v2rayNG SpeedtestManager.socketConnectTime: try twice, keep the best
                    var best = -1L
                    repeat(2) {
                        val start  = System.currentTimeMillis()
                        runCatching {
                            Socket().use { sock ->
                                sock.connect(InetSocketAddress(profile.address, profile.port), 3000)
                            }
                        }.onSuccess {
                            val t = System.currentTimeMillis() - start
                            if (best == -1L || t < best) best = t
                        }
                    }
                    if (best == -1L) throw Exception("TCP connect failed")
                    best
                }

                PingMode.HTTP_GET -> {
                    // Route through local SOCKS proxy so the request travels via xray outbound.
                    // v2rayNG: CoreNativeManager.measureOutboundDelay uses the running core's
                    // local socks port for this same reason.
                    val req = Request.Builder()
                        .url("https://cp.cloudflare.com/")
                        .get()
                        .build()
                    val start = System.currentTimeMillis()
                    proxyClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful && resp.code != 204) {
                            throw Exception("HTTP GET returned ${resp.code}")
                        }
                    }
                    System.currentTimeMillis() - start
                }

                PingMode.HTTP_HEAD -> {
                    val req = Request.Builder()
                        .url("https://cp.cloudflare.com/")
                        .head()
                        .build()
                    val start = System.currentTimeMillis()
                    proxyClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful && resp.code != 204) {
                            throw Exception("HTTP HEAD returned ${resp.code}")
                        }
                    }
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
                "PROXY_GET",  "HTTP_GET"  -> PingMode.HTTP_GET
                "PROXY_HEAD", "HTTP_HEAD" -> PingMode.HTTP_HEAD
                else                      -> PingMode.TCP
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Fetch the raw subscription body.
     *
     * Mirrors v2rayNG AngConfigManager.updateConfigViaSub:
     *  1. Try via local SOCKS/HTTP proxy (works when xray service is running).
     *  2. On any error, retry direct (works when VPN is off).
     *
     * Returns the raw string (may be base64 or plain links). Throws if both fail.
     */
    private fun fetchSubscriptionBody(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "v2rayNG/1.0")
            .build()

        // Attempt 1: through proxy (so the fetch itself goes through the active profile)
        val proxyResult = runCatching {
            proxyClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                resp.body?.string()?.takeIf { it.isNotBlank() }
                    ?: throw Exception("Empty body")
            }
        }
        if (proxyResult.isSuccess) return proxyResult.getOrThrow()

        // Attempt 2: direct (VPN not running, or proxy refused)
        return directClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            resp.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw Exception("Empty body from direct fetch")
        }
    }
}
