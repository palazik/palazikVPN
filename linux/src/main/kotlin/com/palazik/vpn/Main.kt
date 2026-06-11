package com.palazik.vpn

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.palazik.vpn.data.model.DesignSystem
import com.palazik.vpn.data.model.VpnState
import com.palazik.vpn.service.VpnController
import com.palazik.vpn.ui.i18n.LocalStrings
import com.palazik.vpn.ui.i18n.stringsFor
import com.palazik.vpn.ui.screen.AppNavHost
import com.palazik.vpn.ui.screen.OnboardingScreen
import com.palazik.vpn.ui.theme.palazikVPNTheme
import com.palazik.vpn.ui.viewmodel.MainViewModel

/** Schemes the app can import straight from the command line (like Android deep links). */
private val IMPORT_SCHEMES = listOf(
    "palazikvpn", "vmess", "vless", "ss", "trojan", "hysteria2",
    "wireguard", "socks5", "tuic", "anytls", "xhttp", "httpproxy",
)

fun main(args: Array<String>) {
    val autoconnect = "--autoconnect" in args
    val importLink = args.firstOrNull { arg ->
        IMPORT_SCHEMES.any { arg.startsWith("$it://") }
    }

    // Never leave a TUN device / system proxy behind, whatever way we exit.
    Runtime.getRuntime().addShutdownHook(Thread { VpnController.shutdown() })

    application {
        val vm = remember { MainViewModel() }
        val ui by vm.ui.collectAsState()
        val vpnState = ui.vpnState
        val windowState = rememberWindowState(width = 480.dp, height = 860.dp)
        var windowVisible by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            importLink?.let { vm.importProfileFromLink(it) }
            if (autoconnect) {
                windowVisible = false
                vm.connect()
            }
        }

        val strings = stringsFor(ui.language)

        // Tray — the desktop stand-in for the persistent notification, QS tile and widget.
        Tray(
            icon = painterResource("icon.png"),
            tooltip = "palazikVPN — ${vpnState.name}",
            onAction = { windowVisible = true },
            menu = {
                Item(
                    if (vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING)
                        strings.disconnect else strings.connect,
                    onClick = { vm.toggleVpn() },
                )
                Item(if (windowVisible) "Hide" else "Show", onClick = { windowVisible = !windowVisible })
                Separator()
                Item("Quit", onClick = {
                    VpnController.shutdown()
                    exitApplication()
                })
            },
        )

        Window(
            onCloseRequest = { windowVisible = false },  // close to tray, like a foreground service
            state = windowState,
            visible = windowVisible,
            title = "palazikVPN",
            icon = painterResource("icon.png"),
        ) {
            CompositionLocalProvider(LocalStrings provides strings) {
                palazikVPNTheme(
                    appTheme           = ui.appTheme,
                    darkModePreference = ui.darkMode,
                    useMiuix           = ui.designSystem == DesignSystem.MIUIX,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color    = Color.Transparent,
                    ) {
                        val onboardingPrefs = remember { com.palazik.vpn.data.repository.ProfileRepository.themePrefs }
                        var showOnboarding by remember {
                            mutableStateOf(!onboardingPrefs.getBoolean("onboarding_done", false))
                        }
                        if (showOnboarding) {
                            OnboardingScreen(onFinish = {
                                onboardingPrefs.edit().putBoolean("onboarding_done", true).apply()
                                showOnboarding = false
                            })
                        } else {
                            AppNavHost(vm = vm)
                        }
                    }
                }
            }
        }
    }
}
