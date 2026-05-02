package com.palazik.vpn.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class AppTheme { CYBER, OCEAN, FOREST, SUNSET, DYNAMIC }
enum class DarkModePreference { SYSTEM, ALWAYS_DARK, ALWAYS_LIGHT }

val LocalAppTheme = compositionLocalOf { AppTheme.CYBER }
val LocalDarkMode = compositionLocalOf { DarkModePreference.SYSTEM }

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

@Composable
fun palazikVPNTheme(
    appTheme: AppTheme = AppTheme.CYBER,
    darkModePreference: DarkModePreference = DarkModePreference.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (darkModePreference) {
        DarkModePreference.ALWAYS_DARK  -> true
        DarkModePreference.ALWAYS_LIGHT -> false
        DarkModePreference.SYSTEM       -> systemDark
    }

    val context = LocalContext.current
    val colorScheme = when (appTheme) {
        AppTheme.DYNAMIC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (isDark) cyberDarkScheme() else cyberLightScheme()
        AppTheme.CYBER   -> if (isDark) cyberDarkScheme()  else cyberLightScheme()
        AppTheme.OCEAN   -> oceanScheme(isDark)
        AppTheme.FOREST  -> forestScheme(isDark)
        AppTheme.SUNSET  -> sunsetScheme(isDark)
    }

    CompositionLocalProvider(
        LocalAppTheme provides appTheme,
        LocalDarkMode  provides darkModePreference,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = palazikTypography,
            content     = content,
        )
    }
}
