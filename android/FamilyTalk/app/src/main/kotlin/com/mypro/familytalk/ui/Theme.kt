package com.mypro.familytalk.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * WhatsApp-style palette, chosen so the app feels familiar to the whole family:
 * teal-green bars, light-green "sent" bubbles on a warm wallpaper, and the same
 * dark-mode greys. Call green / red follow the convention every phone user knows.
 * Status colours are theme-aware (see [trustOk] / [trustWarn]) so text stays >= 4.5:1.
 */
object Brand {
    /** Bars and buttons (4.7:1 with white text). */
    val Teal = Color(0xFF008069)
    val TealDeep = Color(0xFF075E54)
    /** Bright green accent (dark mode primary, call screen). */
    val TealLight = Color(0xFF00A884)
    val Green = Color(0xFF25D366)
    val Sand = Color(0xFFB45309)
    val SandLight = Color(0xFFFBBF24)
    val Accept = Color(0xFF15803D)
    val Decline = Color(0xFFDC2626)
    // Light-surface variants (5.0:1 on white). Use trustOk()/trustWarn() in UI code.
    val Verified = Color(0xFF15803D)
    val Unverified = Color(0xFFB45309)
    // Dark-surface variants.
    val VerifiedOnDark = Color(0xFF4ADE80)
    val UnverifiedOnDark = Color(0xFFFCD34D)
    val CallBg = Color(0xFF0B141A)
    /** Read ticks. */
    val ReadBlue = Color(0xFF53BDEB)

    /** Avatar colours: distinct hues, all readable with white initials. */
    val Avatars = listOf(
        Color(0xFF008069), Color(0xFF7C3AED), Color(0xFF9333EA), Color(0xFF2563EB),
        Color(0xFFBE185D), Color(0xFF4D7C0F), Color(0xFFA16207), Color(0xFF0E7490),
    )
}

/** Chat colours (bubbles and wallpaper) for the current theme. */
data class ChatPalette(
    val wallpaper: Color, val outBubble: Color, val inBubble: Color,
    val text: Color, val meta: Color, val link: Color, val system: Color, val systemText: Color,
)

private val LightChat = ChatPalette(
    wallpaper = Color(0xFFEFEAE2), outBubble = Color(0xFFD9FDD3), inBubble = Color.White,
    text = Color(0xFF111B21), meta = Color(0xFF5B6A73), link = Color(0xFF027EB5),
    system = Color(0xFFFFF5C4), systemText = Color(0xFF54656F),
)
private val DarkChat = ChatPalette(
    wallpaper = Color(0xFF0B141A), outBubble = Color(0xFF005C4B), inBubble = Color(0xFF202C33),
    text = Color(0xFFE9EDEF), meta = Color(0xFFB4C2C9), link = Color(0xFF53BDEB),
    system = Color(0xFF182229), systemText = Color(0xFFB4C2C9),
)

private val Light = lightColorScheme(
    primary = Brand.Teal, onPrimary = Color.White,
    primaryContainer = Color(0xFFD9FDD3), onPrimaryContainer = Color(0xFF0A3D2E),
    secondary = Brand.TealDeep, onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8FDD2), onSecondaryContainer = Color(0xFF0A3D2E),
    tertiary = Brand.Sand, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFDEBC8), onTertiaryContainer = Color(0xFF3A2000),
    background = Color.White, onBackground = Color(0xFF111B21),
    surface = Color.White, onSurface = Color(0xFF111B21),
    surfaceVariant = Color(0xFFF0F2F5), onSurfaceVariant = Color(0xFF54656F),
    surfaceContainer = Color.White,
    outline = Color(0xFF8696A0), error = Brand.Decline,
)

private val Dark = darkColorScheme(
    primary = Brand.TealLight, onPrimary = Color(0xFF0B141A),
    primaryContainer = Color(0xFF005C4B), onPrimaryContainer = Color(0xFFE9EDEF),
    secondary = Brand.Green, onSecondary = Color(0xFF0B141A),
    secondaryContainer = Color(0xFF103629), onSecondaryContainer = Color(0xFFD8FDD2),
    tertiary = Brand.SandLight, onTertiary = Color(0xFF3A2000),
    tertiaryContainer = Color(0xFF5A3A07), onTertiaryContainer = Color(0xFFFDEBC8),
    background = Color(0xFF111B21), onBackground = Color(0xFFE9EDEF),
    surface = Color(0xFF111B21), onSurface = Color(0xFFE9EDEF),
    surfaceVariant = Color(0xFF202C33), onSurfaceVariant = Color(0xFFAEBAC1),
    surfaceContainer = Color(0xFF1F2C34),
    outline = Color(0xFF8696A0), error = Color(0xFFFF8A80),
)

// Line heights are left to the platform so Arabic diacritics are never clipped.
private val Type = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp),
    bodyMedium = TextStyle(fontSize = 15.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
)

val LocalDarkTheme = staticCompositionLocalOf { false }

/** "Good / verified / connected" colour that stays readable on the current surface. */
@Composable fun trustOk(): Color = if (LocalDarkTheme.current) Brand.VerifiedOnDark else Brand.Verified

/** "Not verified yet / caution" colour that stays readable on the current surface. */
@Composable fun trustWarn(): Color = if (LocalDarkTheme.current) Brand.UnverifiedOnDark else Brand.Unverified

/** Bubble / wallpaper colours for chats. */
@Composable fun chatPalette(): ChatPalette = if (LocalDarkTheme.current) DarkChat else LightChat

/** Teal top bar in light mode, dark grey in dark mode (like WhatsApp). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable fun appBarColors() = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
    containerColor = if (LocalDarkTheme.current) Color(0xFF1F2C34) else Brand.Teal,
    titleContentColor = onAppBar(), navigationIconContentColor = onAppBar(), actionIconContentColor = onAppBar(),
)

/** Text/icon colour on the top bar. */
@Composable fun onAppBar(): Color = if (LocalDarkTheme.current) Color(0xFFE9EDEF) else Color.White

@Composable
fun FamilyTalkTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
    }
}
