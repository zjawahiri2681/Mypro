package com.mypro.iptvbox.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Teal = Color(0xFF2EC4B6)
val Navy = Color(0xFF0B1220)
val Panel = Color(0xFF121A2B)
val PanelHigh = Color(0xFF1B2538)
val Amber = Color(0xFFFFB703)

private val scheme = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF155E58),
    onPrimaryContainer = Color(0xFFBFF5EE),
    secondary = Amber,
    onSecondary = Color(0xFF3A2A00),
    background = Navy,
    onBackground = Color(0xFFE6EAF2),
    surface = Panel,
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = PanelHigh,
    onSurfaceVariant = Color(0xFFB7C0D0),
    outline = Color(0xFF3A4660),
    error = Color(0xFFFF6B6B),
)

@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
