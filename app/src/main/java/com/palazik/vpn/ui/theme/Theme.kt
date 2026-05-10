package com.palazik.vpn.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

enum class AppTheme { CYBER, OCEAN, FOREST, SUNSET, DYNAMIC }
enum class DarkModePreference { SYSTEM, ALWAYS_DARK, ALWAYS_LIGHT }

val LocalAppTheme = compositionLocalOf { AppTheme.CYBER }
val LocalDarkMode = compositionLocalOf { DarkModePreference.SYSTEM }

private val PalazikShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

private fun lightScheme(primary: Color = SignalGreen, soft: Color = SignalGreenSoft): ColorScheme =
    lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = soft,
        onPrimaryContainer = Ink900,
        secondary = SignalBlue,
        onSecondary = Color.White,
        secondaryContainer = SignalBlueSoft,
        tertiary = SignalAmber,
        onTertiary = Ink900,
        tertiaryContainer = SignalAmberSoft,
        error = SignalRed,
        errorContainer = SignalRedSoft,
        background = Ink100,
        onBackground = Ink900,
        surface = Color.White,
        onSurface = Ink900,
        surfaceVariant = Ink200,
        onSurfaceVariant = Ink500,
        outline = Color(0xFFC4C9C2),
        outlineVariant = Color(0xFFE0E3DD),
        inverseSurface = Ink900,
        inverseOnSurface = Ink100,
    )

private fun darkScheme(primary: Color = SignalGreen, soft: Color = SignalGreenDark): ColorScheme =
    darkColorScheme(
        primary = primary,
        onPrimary = Ink900,
        primaryContainer = soft,
        onPrimaryContainer = Ink100,
        secondary = Color(0xFF8DB7FF),
        onSecondary = Ink900,
        secondaryContainer = Color(0xFF1E3762),
        tertiary = SignalAmber,
        onTertiary = Ink900,
        tertiaryContainer = Color(0xFF59431E),
        error = Color(0xFFFFA7A7),
        errorContainer = Color(0xFF5C2020),
        background = Ink900,
        onBackground = Ink100,
        surface = Ink800,
        onSurface = Ink100,
        surfaceVariant = Ink700,
        onSurfaceVariant = Color(0xFFB8BFB7),
        outline = Color(0xFF464D48),
        outlineVariant = Color(0xFF2A302B),
        inverseSurface = Ink100,
        inverseOnSurface = Ink900,
    )

@Composable
fun palazikVPNTheme(
    appTheme: AppTheme = AppTheme.CYBER,
    darkModePreference: DarkModePreference = DarkModePreference.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (darkModePreference) {
        DarkModePreference.ALWAYS_DARK -> true
        DarkModePreference.ALWAYS_LIGHT -> false
        DarkModePreference.SYSTEM -> systemDark
    }

    val context = LocalContext.current
    val scheme = when (appTheme) {
        AppTheme.DYNAMIC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (isDark) darkScheme() else lightScheme()
        AppTheme.CYBER -> if (isDark) darkScheme() else lightScheme()
        AppTheme.OCEAN -> if (isDark) darkScheme(OceanPrimary, Color(0xFF173A42)) else lightScheme(OceanPrimary, OceanSoft)
        AppTheme.FOREST -> if (isDark) darkScheme(ForestPrimary, Color(0xFF1F4A38)) else lightScheme(ForestPrimary, ForestSoft)
        AppTheme.SUNSET -> if (isDark) darkScheme(SunsetPrimary, Color(0xFF5C3325)) else lightScheme(SunsetPrimary, SunsetSoft)
    }

    CompositionLocalProvider(
        LocalAppTheme provides appTheme,
        LocalDarkMode provides darkModePreference,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = palazikTypography,
            shapes = PalazikShapes,
            content = content,
        )
    }
}
