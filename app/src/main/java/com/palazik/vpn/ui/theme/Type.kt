package com.palazik.vpn.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.palazik.vpn.R

// Uses Rajdhani (display) + Inter-style system font for body.
// Add fonts/rajdhani_*.ttf to res/font/ and update R.font.* accordingly.
val RajdhaniFamily = FontFamily(
    Font(R.font.rajdhani_regular, FontWeight.Normal),
    Font(R.font.rajdhani_medium,  FontWeight.Medium),
    Font(R.font.rajdhani_semibold,FontWeight.SemiBold),
    Font(R.font.rajdhani_bold,    FontWeight.Bold),
)

val palazikTypography = Typography(
    displayLarge  = TextStyle(fontFamily = RajdhaniFamily, fontWeight = FontWeight.Bold,
        fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(fontFamily = RajdhaniFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall  = TextStyle(fontFamily = RajdhaniFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = RajdhaniFamily, fontWeight = FontWeight.Bold,
        fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium= TextStyle(fontFamily = RajdhaniFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = RajdhaniFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge    = TextStyle(fontFamily = RajdhaniFamily, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium   = TextStyle(fontFamily = RajdhaniFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall    = TextStyle(fontFamily = RajdhaniFamily, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge     = TextStyle(fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium    = TextStyle(fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall     = TextStyle(fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge    = TextStyle(fontFamily = RajdhaniFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium   = TextStyle(fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall    = TextStyle(fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)
