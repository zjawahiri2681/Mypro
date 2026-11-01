package com.mypro.iptvbox.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mypro.iptvbox.IptvApp
import com.mypro.iptvbox.data.ThemeId

private fun ThemeId.scheme() = darkColorScheme(
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    primaryContainer = Color(primary).copy(alpha = 0.25f).compositeOverBlack(Color(surface)),
    onPrimaryContainer = Color(0xFFF2F5FA),
    secondary = Color(secondary),
    onSecondary = Color(0xFF231A00),
    secondaryContainer = Color(secondary).copy(alpha = 0.22f).compositeOverBlack(Color(surface)),
    onSecondaryContainer = Color(0xFFF2F5FA),
    tertiary = Color(secondary),
    background = Color(background),
    onBackground = Color(0xFFE8ECF4),
    surface = Color(surface),
    onSurface = Color(0xFFE8ECF4),
    surfaceVariant = Color(surfaceVariant),
    onSurfaceVariant = Color(0xFFB9C2D3),
    surfaceContainer = Color(surface),
    surfaceContainerHigh = Color(surfaceVariant),
    surfaceContainerHighest = Color(surfaceVariant),
    outline = Color(0xFF55607A),
    outlineVariant = Color(0xFF353E55),
    error = Color(0xFFFF6B6B),
)

private fun Color.compositeOverBlack(base: Color): Color {
    val a = alpha
    return Color(red * a + base.red * (1 - a), green * a + base.green * (1 - a), blue * a + base.blue * (1 - a), 1f)
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    val settings by IptvApp.settings.state.collectAsState()
    MaterialTheme(colorScheme = settings.theme.scheme(), shapes = shapes, content = content)
}

/** Soft diagonal gradient used behind the home screen. */
@Composable
fun appBackgroundBrush(): Brush {
    val c = MaterialTheme.colorScheme
    return Brush.linearGradient(listOf(c.primary.copy(alpha = 0.16f).compositeOverBlack(c.background), c.background, c.secondary.copy(alpha = 0.10f).compositeOverBlack(c.background)))
}

/** Gradient for accent surfaces (hero cards, rail header). */
@Composable
fun accentBrush(): Brush {
    val c = MaterialTheme.colorScheme
    return Brush.horizontalGradient(listOf(c.primary, c.secondary))
}
