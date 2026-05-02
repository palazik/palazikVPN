package com.palazik.vpn.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.palazik.vpn.data.model.*
import com.palazik.vpn.data.codec.ProfileCodec
import com.palazik.vpn.data.repository.ProfileRepository
import com.palazik.vpn.service.palazikVpnService
import com.palazik.vpn.ui.theme.AppTheme
import com.palazik.vpn.ui.theme.DarkModePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    val bytesIn: Long                     = 0L,
    val bytesOut: Long                    = 0L,
    val snackMessage: String?             = null,
    val shareLink: String?                = null,
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

        // Mirror VPN service state
        viewModelScope.launch {
            palazikVpnService.connectionState.collect { svcState ->
                _ui.update { it.copy(vpnState = when (svcState) {
                    palazikVpnService.ServiceState.RUNNING  -> VpnState.CONNECTED
                    palazikVpnService.ServiceState.STARTING -> VpnState.CONNECTING
                    palazikVpnService.ServiceState.STOPPING -> VpnState.DISCONNECTING
                    palazikVpnService.ServiceState.STOPPED  -> VpnState.DISCONNECTED
                }) }
            }
        }
        viewModelScope.launch { palazikVpnService.bytesIn.collect  { b -> _ui.update { it.copy(bytesIn  = b) } } }
        viewModelScope.launch { palazikVpnService.bytesOut.collect { b -> _ui.update { it.copy(bytesOut = b) } } }
    }

    // ── VPN toggle ────────────────────────────────────────────────────────────

    fun prepareVpn(): Intent? = VpnService.prepare(context)

    fun connect() {
        val profile = _ui.value.activeProfile ?: run { snack("Select a profile first"); return }
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
        if (profile != null) { repo.addProfile(profile); snack("Profile \"${profile.name}\" imported") }
        else snack("Could not parse link")
    }

    fun addManualProfile(profile: VpnProfile) { repo.addProfile(profile); snack("Profile \"${profile.name}\" added") }
    fun removeProfile(id: String)             = repo.removeProfile(id)
    fun selectProfile(id: String)             = repo.setActiveProfile(id)

    fun generateShareLink() {
        val profile = _ui.value.activeProfile ?: run { snack("No active profile"); return }
        _ui.update { it.copy(shareLink = ProfileCodec.encodePalazik(profile)) }
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
            repo.updateSubscription(sub).fold(
                onSuccess = { count -> snack("Loaded $count profiles from \"$name\"") },
                onFailure = { snack("Failed to fetch subscription") },
            )
        }
    }

    fun removeSubscription(id: String) = repo.removeSubscription(id)

    fun updateSubscription(sub: Subscription) {
        viewModelScope.launch {
            snack("Updating \"${sub.name}\"…")
            repo.updateSubscription(sub).fold(
                onSuccess = { count -> snack("Updated: $count profiles") },
                onFailure = { snack("Update failed") },
            )
        }
    }

    fun updateAllSubscriptions() {
        viewModelScope.launch {
            snack("Updating all subscriptions…")
            repo.updateAllSubscriptions()
            snack("All subscriptions updated")
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun snack(msg: String) = _ui.update { it.copy(snackMessage = msg) }
    fun clearSnack()               = _ui.update { it.copy(snackMessage = null) }
}
