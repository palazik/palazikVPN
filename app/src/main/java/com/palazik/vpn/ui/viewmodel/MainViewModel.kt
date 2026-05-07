package com.palazik.vpn.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.palazik.vpn.data.model.*
import com.palazik.vpn.data.codec.ProfileCodec
import com.palazik.vpn.data.repository.ProfileRepository
import com.palazik.vpn.service.XrayConfigBuilder
import com.palazik.vpn.service.palazikVpnService
import com.palazik.vpn.ui.theme.AppTheme
import com.palazik.vpn.ui.theme.DarkModePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UiState(
    val vpnState: VpnState                = VpnState.DISCONNECTED,
    val activeProfile: VpnProfile?        = null,
    val profiles: List<VpnProfile>        = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val appTheme: AppTheme                = AppTheme.CYBER,
    val darkMode: DarkModePreference      = DarkModePreference.SYSTEM,
    val pingMode: PingMode                = PingMode.TCP,
    val settings: AppSettings             = AppSettings(),
    val installedApps: List<InstalledApp>  = emptyList(),
    val bytesIn: Long                     = 0L,
    val bytesOut: Long                    = 0L,
    val connectedSince: Long              = 0L,
    val diagnostics: List<String>         = emptyList(),
    val lastError: String?                = null,
    val snackMessage: String?             = null,
    val snackActionLabel: String?         = null,
    val shareLink: String?                = null,
    val isUpdatingSubscriptions: Boolean  = false,
)

private const val THEME_PREFS  = "palazik_theme"
private const val KEY_THEME    = "app_theme"
private const val KEY_DARKMODE = "dark_mode"

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: ProfileRepository,
) : ViewModel() {

    private val themePrefs = context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
    private var deletedProfile: VpnProfile? = null

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        // Restore persisted theme on startup
        val savedTheme = themePrefs.getString(KEY_THEME,    AppTheme.CYBER.name)
        val savedDark  = themePrefs.getString(KEY_DARKMODE, DarkModePreference.SYSTEM.name)
        _ui.update { it.copy(
            appTheme = runCatching { AppTheme.valueOf(savedTheme ?: "") }.getOrDefault(AppTheme.CYBER),
            darkMode = runCatching { DarkModePreference.valueOf(savedDark ?: "") }.getOrDefault(DarkModePreference.SYSTEM),
        ) }

        // Mirror repo flows into UI
        viewModelScope.launch {
            repo.profiles.collect { profiles ->
                _ui.update { it.copy(
                    profiles      = profiles,
                    activeProfile = profiles.firstOrNull { p -> p.isActive },
                ) }
            }
        }
        viewModelScope.launch {
            repo.subscriptions.collect { subs ->
                _ui.update { it.copy(subscriptions = subs) }
            }
        }
        viewModelScope.launch {
            repo.pingMode.collect { mode ->
                _ui.update { it.copy(pingMode = mode) }
            }
        }
        viewModelScope.launch {
            repo.settings.collect { settings ->
                _ui.update { it.copy(settings = settings) }
            }
        }

        // Mirror VPN service state
        viewModelScope.launch {
            palazikVpnService.connectionState.collect { svcState ->
                _ui.update { it.copy(vpnState = when (svcState) {
                    palazikVpnService.ServiceState.RUNNING  -> VpnState.CONNECTED
                    palazikVpnService.ServiceState.STARTING -> VpnState.CONNECTING
                    palazikVpnService.ServiceState.STOPPING -> VpnState.DISCONNECTING
                    palazikVpnService.ServiceState.STOPPED  -> VpnState.DISCONNECTED
                    palazikVpnService.ServiceState.ERROR    -> VpnState.ERROR
                }) }
            }
        }
        viewModelScope.launch { palazikVpnService.bytesIn.collect  { b -> _ui.update { it.copy(bytesIn  = b) } } }
        viewModelScope.launch { palazikVpnService.bytesOut.collect { b -> _ui.update { it.copy(bytesOut = b) } } }
        viewModelScope.launch { palazikVpnService.connectedSince.collect { t -> _ui.update { it.copy(connectedSince = t) } } }
        viewModelScope.launch { palazikVpnService.diagnostics.collect { logs -> _ui.update { it.copy(diagnostics = logs) } } }
        viewModelScope.launch { palazikVpnService.lastError.collect { error -> _ui.update { it.copy(lastError = error) } } }
        loadInstalledApps()
    }

    // ── VPN toggle ────────────────────────────────────────────────────────────

    fun prepareVpn(): Intent? = VpnService.prepare(context)

    fun connect() {
        val profile = _ui.value.activeProfile ?: run { snack("Select a profile first"); return }
        // Pass full profile object to service companion — the service needs it to build xray config
        palazikVpnService.activeProfile = profile
        context.startForegroundService(
            Intent(context, palazikVpnService::class.java).apply {
                action = palazikVpnService.ACTION_START
                putExtra(palazikVpnService.EXTRA_PROFILE, profile.id)
            }
        )
    }

    fun disconnect() {
        context.startService(
            Intent(context, palazikVpnService::class.java).apply {
                action = palazikVpnService.ACTION_STOP
            }
        )
    }

    fun toggleVpn(permLauncher: ActivityResultLauncher<Intent>) {
        when (_ui.value.vpnState) {
            VpnState.CONNECTED, VpnState.CONNECTING -> disconnect()
            else -> {
                val prepare = prepareVpn()
                if (prepare != null) permLauncher.launch(prepare) else connect()
            }
        }
    }

    // ── Profile management ────────────────────────────────────────────────────

    fun importProfileFromLink(raw: String) {
        val profile = ProfileCodec.decode(raw)
        if (profile != null) {
            val errors = ProfileValidator.validate(profile)
            if (errors.isNotEmpty()) {
                snack(errors.first())
                return
            }
            repo.addProfile(profile)
            snack("Profile \"${profile.name}\" imported")
        } else {
            snack("Could not parse link")
        }
    }

    fun addManualProfile(profile: VpnProfile): Boolean {
        val errors = ProfileValidator.validate(profile)
        if (errors.isNotEmpty()) {
            snack(errors.first())
            return false
        }
        repo.addProfile(profile)
        snack("Profile \"${profile.name}\" added")
        return true
    }

    /** Update an existing profile (used by the edit dialog). */
    fun updateProfile(profile: VpnProfile): Boolean {
        val errors = ProfileValidator.validate(profile)
        if (errors.isNotEmpty()) {
            snack(errors.first())
            return false
        }
        repo.updateProfile(profile)
        if (palazikVpnService.activeProfile?.id == profile.id) {
            palazikVpnService.activeProfile = profile
        }
        snack("Profile \"${profile.name}\" updated")
        return true
    }

    fun removeProfile(id: String) {
        val profile = _ui.value.profiles.firstOrNull { it.id == id }
        deletedProfile = profile
        if (profile?.id == palazikVpnService.activeProfile?.id) disconnect()
        repo.removeProfile(id)
        snack("Profile deleted", "Undo")
    }

    fun undoSnackAction() {
        deletedProfile?.let {
            repo.addProfile(it)
            snack("Profile restored")
        }
        deletedProfile = null
    }

    fun selectProfile(id: String) = repo.setActiveProfile(id)

    fun duplicateProfile(profile: VpnProfile) {
        val copy = profile.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = "${profile.name} Copy",
            isActive = false,
            latencyMs = -1L,
            addedAt = System.currentTimeMillis(),
        )
        repo.addProfile(copy)
        snack("Profile duplicated")
    }

    fun generateShareLink() {
        val profile = _ui.value.activeProfile ?: run { snack("No active profile"); return }
        _ui.update { it.copy(shareLink = ProfileCodec.encodePalazik(profile)) }
    }

    fun generateNativeLink(profile: VpnProfile) {
        _ui.update { it.copy(shareLink = ProfileCodec.encodeNative(profile)) }
    }

    fun generateJsonConfig(profile: VpnProfile) {
        _ui.update { it.copy(shareLink = XrayConfigBuilder.build(profile, _ui.value.settings)) }
    }

    fun clearShareLink() = _ui.update { it.copy(shareLink = null) }

    // ── Ping ─────────────────────────────────────────────────────────────────

    fun pingProfile(profile: VpnProfile) {
        viewModelScope.launch {
            snack("Pinging ${profile.name}…")
            val ms = repo.pingProfile(profile)
            snack(if (ms >= 0) "${profile.name}: ${ms}ms" else "${profile.name}: timeout")
        }
    }

    fun pingAll() {
        viewModelScope.launch { _ui.value.profiles.forEach { repo.pingProfile(it) } }
    }

    // ── Subscriptions ─────────────────────────────────────────────────────────

    fun addSubscription(name: String, url: String) {
        val sub = Subscription(name = name, url = url)
        repo.addSubscription(sub)
        viewModelScope.launch {
            _ui.update { it.copy(isUpdatingSubscriptions = true) }
            repo.updateSubscription(sub).fold(
                onSuccess = { count -> snack("Loaded $count profiles from \"$name\"") },
                onFailure = {
                    repo.removeSubscription(sub.id)
                    snack("Failed to fetch subscription")
                },
            )
            _ui.update { it.copy(isUpdatingSubscriptions = false) }
        }
    }

    fun removeSubscription(id: String) {
        if (_ui.value.profiles.any { it.subscriptionId == id && it.id == palazikVpnService.activeProfile?.id }) {
            disconnect()
        }
        repo.removeSubscription(id)
    }

    fun updateSubscription(sub: Subscription) {
        viewModelScope.launch {
            _ui.update { it.copy(isUpdatingSubscriptions = true) }
            snack("Updating \"${sub.name}\"…")
            repo.updateSubscription(sub).fold(
                onSuccess = { count -> snack("Updated: $count profiles") },
                onFailure = { snack("Update failed") },
            )
            _ui.update { it.copy(isUpdatingSubscriptions = false) }
        }
    }

    fun updateAllSubscriptions() {
        viewModelScope.launch {
            _ui.update { it.copy(isUpdatingSubscriptions = true) }
            snack("Updating all subscriptions…")
            val results = repo.updateAllSubscriptions()
            val failed = results.count { it.isFailure }
            val updated = results.sumOf { it.getOrDefault(0) }
            snack(if (failed == 0) "Updated $updated profiles" else "Updated $updated profiles, $failed failed")
            _ui.update { it.copy(isUpdatingSubscriptions = false) }
        }
    }

    // ── Theme ─────────────────────────────────────────────────────────────────

    fun setAppTheme(theme: AppTheme) {
        _ui.update { it.copy(appTheme = theme) }
        themePrefs.edit().putString(KEY_THEME, theme.name).apply()
    }

    fun setDarkMode(pref: DarkModePreference) {
        _ui.update { it.copy(darkMode = pref) }
        themePrefs.edit().putString(KEY_DARKMODE, pref.name).apply()
    }

    // ── Ping mode ─────────────────────────────────────────────────────────────

    fun setPingMode(mode: PingMode) = repo.setPingMode(mode)

    fun updateAppSettings(settings: AppSettings) = repo.updateSettings(settings)

    private fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == context.packageName) return@mapNotNull null
                    InstalledApp(
                        label = info.loadLabel(pm)?.toString()?.ifBlank { pkg } ?: pkg,
                        packageName = pkg,
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
            _ui.update { it.copy(installedApps = apps) }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun snack(msg: String, action: String? = null) =
        _ui.update { it.copy(snackMessage = msg, snackActionLabel = action) }

    fun clearSnack() = _ui.update { it.copy(snackMessage = null, snackActionLabel = null) }
}
