package com.mypro.iptvbox.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mypro.iptvbox.IptvApp
import com.mypro.iptvbox.data.AppSettings

/** True on Android TV / Google TV / TV boxes (leanback UI mode). */
val LocalIsTv = staticCompositionLocalOf { false }

fun Context.isTvDevice(): Boolean {
    val ui = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature("android.software.leanback")
}

/** Alpha-composite [this] over an opaque [base]. */
fun Color.over(base: Color): Color {
    val a = alpha
    return Color(red * a + base.red * (1 - a), green * a + base.green * (1 - a), blue * a + base.blue * (1 - a), 1f)
}

private fun scheme(s: AppSettings) = with(s.theme) {
    val text = Color(s.textColor.argb)
    val bg = Color(background)
    val surf = Color(surface)
    val surfVar = Color(surfaceVariant)
    darkColorScheme(
        primary = Color(primary),
        onPrimary = Color(onPrimary),
        primaryContainer = Color(primary).copy(alpha = 0.28f).over(surf),
        onPrimaryContainer = text,
        secondary = Color(secondary),
        onSecondary = Color(0xFF231A00),
        secondaryContainer = Color(primary).copy(alpha = 0.18f).over(surf),
        onSecondaryContainer = text,
        tertiary = Color(secondary),
        background = bg,
        onBackground = text,
        surface = surf,
        onSurface = text,
        surfaceVariant = surfVar,
        // secondary text: the chosen text colour, slightly dimmed (stays above 7:1 on every theme)
        onSurfaceVariant = text.copy(alpha = 0.74f).over(surf),
        surfaceContainerLowest = bg,
        surfaceContainerLow = surf,
        surfaceContainer = surf,
        surfaceContainerHigh = surfVar,
        surfaceContainerHighest = surfVar,
        inverseSurface = text,
        inverseOnSurface = bg,
        outline = Color(0xFF6B7794),
        outlineVariant = Color(0xFF3A445C),
        error = Color(0xFFFF7A7A),
        onError = Color(0xFF3A0006),
        scrim = Color.Black,
    )
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
    val context = LocalContext.current
    val isTv = remember { context.isTvDevice() }
    val colors = scheme(settings)
    val density = LocalDensity.current
    // TV is read from ~3 m: start 15% larger there, then apply the user's text size.
    val fontScale = density.fontScale * settings.textSize.scale * (if (isTv) 1.15f else 1f)
    MaterialTheme(colorScheme = colors, shapes = shapes) {
        CompositionLocalProvider(
            // Without this, Text/Icon outside a Surface default to black: invisible on dark themes.
            LocalContentColor provides colors.onBackground,
            LocalDensity provides Density(density.density, fontScale),
            LocalIsTv provides isTv,
            content = content,
        )
    }
}

/** Window background matching the current theme (avoids a navy flash on other themes). */
fun themeWindowColor(): Int = Color(IptvApp.settings.current.theme.background).toArgb()

/** Soft diagonal gradient used behind the home screen. */
@Composable
fun appBackgroundBrush(): Brush {
    val c = MaterialTheme.colorScheme
    return Brush.linearGradient(listOf(c.primary.copy(alpha = 0.14f).over(c.background), c.background, c.secondary.copy(alpha = 0.08f).over(c.background)))
}

/** Gradient for accent surfaces (hero cards, rail header). */
@Composable
fun accentBrush(): Brush {
    val c = MaterialTheme.colorScheme
    return Brush.horizontalGradient(listOf(c.primary, c.secondary))
}

/** Translucent panel colour for overlays drawn on top of video. */
@Composable
fun panelColor(): Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
