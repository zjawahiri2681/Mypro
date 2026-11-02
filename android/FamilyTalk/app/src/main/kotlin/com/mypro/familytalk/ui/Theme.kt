package com.mypro.familytalk.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * "Oasis" palette. Deep teal = calm and trustworthy (the brand / security colour),
 * warm sand = family warmth for highlights. Call green / red follow the convention
 * every phone user already knows. All text pairs meet WCAG AA (4.5:1) contrast.
 */
object Brand {
    val Teal = Color(0xFF0F766E)
    val TealLight = Color(0xFF5EEAD4)
    val Sand = Color(0xFFB45309)
    val SandLight = Color(0xFFFBBF24)
    val Accept = Color(0xFF15803D)
    val Decline = Color(0xFFDC2626)
    val Verified = Color(0xFF15803D)
    val Unverified = Color(0xFFB45309)
    val CallBg = Color(0xFF071311)

    /** Avatar colours: distinct hues, all readable with white initials. */
    val Avatars = listOf(
        Color(0xFF0F766E), Color(0xFF7C3AED), Color(0xFFB45309), Color(0xFF2563EB),
        Color(0xFFBE185D), Color(0xFF4D7C0F), Color(0xFF9333EA), Color(0xFF0E7490),
    )
}

private val Light = lightColorScheme(
    primary = Brand.Teal, onPrimary = Color.White,
    primaryContainer = Color(0xFFCCF2EC), onPrimaryContainer = Color(0xFF00201C),
    secondary = Brand.Sand, onSecondary = Color.White,
    secondaryContainer = Color(0xFFFDEBC8), onSecondaryContainer = Color(0xFF3A2000),
    background = Color(0xFFF6F8F7), onBackground = Color(0xFF15201E),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF15201E),
    surfaceVariant = Color(0xFFE6EEEC), onSurfaceVariant = Color(0xFF3F4947),
    outline = Color(0xFF6F7977), error = Brand.Decline,
)

private val Dark = darkColorScheme(
    primary = Brand.TealLight, onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF0B4F49), onPrimaryContainer = Color(0xFFCCF2EC),
    secondary = Brand.SandLight, onSecondary = Color(0xFF3A2000),
    secondaryContainer = Color(0xFF5A3A07), onSecondaryContainer = Color(0xFFFDEBC8),
    background = Color(0xFF0B1514), onBackground = Color(0xFFE2EBE9),
    surface = Color(0xFF111C1B), onSurface = Color(0xFFE2EBE9),
    surfaceVariant = Color(0xFF1C2927), onSurfaceVariant = Color(0xFFB9C8C5),
    outline = Color(0xFF889895), error = Color(0xFFFF8A80),
)

private val Type = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun FamilyTalkTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
}
