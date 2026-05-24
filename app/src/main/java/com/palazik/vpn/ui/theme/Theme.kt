package com.palazik.vpn.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

enum class AppTheme { CYBER, OCEAN, FOREST, SUNSET, DYNAMIC }
enum class DarkModePreference { SYSTEM, ALWAYS_DARK, ALWAYS_LIGHT }

val LocalAppTheme   = compositionLocalOf { AppTheme.CYBER }
val LocalDarkMode   = compositionLocalOf { DarkModePreference.SYSTEM }

// ── MD3 color schemes ───────────────────────────────────────────────────────

private fun cyberDarkScheme() = darkColorScheme(
    primary            = CyberPrimary,
    onPrimary          = CyberOnPrimary,
    primaryContainer   = CyberPrimaryContainer,
    onPrimaryContainer = CyberOnPrimaryContainer,
    secondary          = CyberSecondary,
    background         = CyberBackground,
    surface            = CyberSurface,
    surfaceVariant     = CyberSurfaceVariant,
    onSurface          = CyberOnSurface,
    outline            = CyberOutline,
)

private fun cyberLightScheme() = lightColorScheme(
    primary            = CyberPrimaryContainer,
    onPrimary          = CyberOnPrimary,
    primaryContainer   = CyberPrimary,
    onPrimaryContainer = CyberBackground,
    secondary          = CyberSecondary,
    background         = Color(0xFFF0FDFF),
    surface            = Color(0xFFFFFFFF),
    onSurface          = CyberBackground,
)

private fun oceanScheme(dark: Boolean) = if (dark) darkColorScheme(
    primary = OceanPrimary, onPrimary = OceanOnPrimary,
    primaryContainer = OceanPrimaryContainer,
    onPrimaryContainer = OceanOnPrimaryContainer,
    secondary = OceanSecondary,
) else lightColorScheme(
    primary = OceanPrimary, onPrimary = OceanOnPrimary,
    primaryContainer = OceanPrimaryContainer,
    onPrimaryContainer = OceanOnPrimaryContainer,
    secondary = OceanSecondary,
    background = OceanBackground, surface = OceanSurface,
    surfaceVariant = OceanSurfaceVariant, onSurface = OceanOnSurface,
    outline = OceanOutline,
)

private fun forestScheme(dark: Boolean) = if (dark) darkColorScheme(
    primary = ForestPrimary, onPrimary = ForestOnPrimary,
    primaryContainer = ForestPrimaryContainer,
    onPrimaryContainer = ForestOnPrimaryContainer,
    secondary = ForestSecondary,
) else lightColorScheme(
    primary = ForestPrimary, onPrimary = ForestOnPrimary,
    primaryContainer = ForestPrimaryContainer,
    onPrimaryContainer = ForestOnPrimaryContainer,
    secondary = ForestSecondary,
    background = ForestBackground, surface = ForestSurface,
    surfaceVariant = ForestSurfaceVariant, onSurface = ForestOnSurface,
    outline = ForestOutline,
)

private fun sunsetScheme(dark: Boolean) = if (dark) darkColorScheme(
    primary = SunsetPrimary, onPrimary = SunsetOnPrimary,
    primaryContainer = SunsetPrimaryContainer,
    onPrimaryContainer = SunsetOnPrimaryContainer,
    secondary = SunsetSecondary,
) else lightColorScheme(
    primary = SunsetPrimary, onPrimary = SunsetOnPrimary,
    primaryContainer = SunsetPrimaryContainer,
    onPrimaryContainer = SunsetOnPrimaryContainer,
    secondary = SunsetSecondary,
    background = SunsetBackground, surface = SunsetSurface,
    surfaceVariant = SunsetSurfaceVariant, onSurface = SunsetOnSurface,
    outline = SunsetOutline,
)

// ── Resolve MD3 ColorScheme from AppTheme ───────────────────────────────────

@Composable
fun resolveColorScheme(appTheme: AppTheme, isDark: Boolean): ColorScheme {
    val context = LocalContext.current
    return when (appTheme) {
        AppTheme.DYNAMIC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (isDark) cyberDarkScheme() else cyberLightScheme()
        AppTheme.CYBER   -> if (isDark) cyberDarkScheme()  else cyberLightScheme()
        AppTheme.OCEAN   -> oceanScheme(isDark)
        AppTheme.FOREST  -> forestScheme(isDark)
        AppTheme.SUNSET  -> sunsetScheme(isDark)
    }
}

// ── Miuix color mode from DarkModePreference ────────────────────────────────

fun darkPrefToMiuixMode(pref: DarkModePreference) = when (pref) {
    DarkModePreference.SYSTEM       -> ColorSchemeMode.System
    DarkModePreference.ALWAYS_DARK  -> ColorSchemeMode.Dark
    DarkModePreference.ALWAYS_LIGHT -> ColorSchemeMode.Light
}

// ── Main theme wrapper ───────────────────────────────────────────────────────

@Composable
fun palazikVPNTheme(
    appTheme: AppTheme = AppTheme.CYBER,
    darkModePreference: DarkModePreference = DarkModePreference.SYSTEM,
    useMiuix: Boolean = true,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (darkModePreference) {
        DarkModePreference.ALWAYS_DARK  -> true
        DarkModePreference.ALWAYS_LIGHT -> false
        DarkModePreference.SYSTEM       -> systemDark
    }

    CompositionLocalProvider(
        LocalAppTheme provides appTheme,
        LocalDarkMode  provides darkModePreference,
    ) {
        if (useMiuix) {
            // Miuix theme wraps MD3 — we still supply the MD3 colour scheme
            // so Material3 components in the app keep their custom palette.
            val miuixController = remember(darkModePreference) {
                ThemeController(darkPrefToMiuixMode(darkModePreference))
            }
            MiuixTheme(controller = miuixController) {
                // Also provide MD3 theme underneath for widgets that use MaterialTheme
                val colorScheme = resolveColorScheme(appTheme, isDark)
                MaterialTheme(
                    colorScheme = colorScheme,
                    typography  = palazikTypography,
                    content     = content,
                )
            }
        } else {
            val colorScheme = resolveColorScheme(appTheme, isDark)
            MaterialTheme(
                colorScheme = colorScheme,
                typography  = palazikTypography,
                content     = content,
            )
        }
    }
}
