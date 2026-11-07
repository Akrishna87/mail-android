package com.akrishna87.mail

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlin.math.abs

/** Colour themes offered in Settings. [hue] tints the whole app; null means not a tinted theme. */
enum class AppTheme(val label: String, val hue: Float?) {
    NIGHT("Night", null),
    OCEAN("Ocean", 212f),
    FOREST("Forest", 150f),
    SUNSET("Sunset", 18f),
    LAVENDER("Lavender", 268f),
    ROSE("Rose", 338f),
    WALLPAPER("Wallpaper", null),
    CLASSIC("Classic", null),
    ;

    /** Wallpaper colours need Android 12. */
    val available: Boolean get() = this != WALLPAPER || Build.VERSION.SDK_INT >= 31
}

/** The theme's gradient (title, chips, Compose button, unread glow). White text must stay readable on it. */
val LocalAccent = staticCompositionLocalOf { NIGHT_ACCENT }

@Composable
fun MailTheme(theme: AppTheme, content: @Composable () -> Unit) {
    // Night is always dark, like its design; the others follow the phone's dark mode.
    val dark = theme == AppTheme.NIGHT || isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        theme == AppTheme.NIGHT -> NightScheme
        theme == AppTheme.WALLPAPER && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        theme.hue != null -> tintedScheme(theme.hue, dark)
        else -> if (dark) GmailDark else GmailLight
    }
    val accent = accentGradient(theme) ?: if (theme == AppTheme.WALLPAPER && Build.VERSION.SDK_INT >= 31) {
        dynamicLightColorScheme(context).let { listOf(it.primary, it.tertiary) }
    } else {
        listOf(Color(0xFF0B57D0), Color(0xFF7C3AED))
    }
    // Light status bar icons on dark backgrounds and the other way round.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    CompositionLocalProvider(LocalAccent provides accent) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

/** Gradient stops for Night and the tinted themes; null when it comes from the phone (Wallpaper) or Gmail (Classic). */
fun accentGradient(theme: AppTheme): List<Color>? = when (theme) {
    // Two hand-picked colours each: a hue-shifted formula went through browns and olives.
    AppTheme.NIGHT -> NIGHT_ACCENT
    AppTheme.OCEAN -> listOf(Color(0xFF1D4ED8), Color(0xFF7C3AED))
    AppTheme.FOREST -> listOf(Color(0xFF15803D), Color(0xFF0F766E))
    AppTheme.SUNSET -> listOf(Color(0xFFC2410C), Color(0xFFBE185D))
    AppTheme.LAVENDER -> listOf(Color(0xFF6D28D9), Color(0xFFA21CAF))
    AppTheme.ROSE -> listOf(Color(0xFFBE123C), Color(0xFF7C3AED))
    AppTheme.WALLPAPER, AppTheme.CLASSIC -> null
}

private val NIGHT_ACCENT = listOf(Color(0xFF2563EB), Color(0xFF7C3AED), Color(0xFFDB2777))

/** Design C: deep navy with violet accents. */
val NightScheme = darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF1E1048),
    primaryContainer = Color(0xFF251C3D),
    onPrimaryContainer = Color(0xFFEDE9FE),
    secondary = Color(0xFF93C5FD),
    onSecondary = Color(0xFF0B1A33),
    secondaryContainer = Color(0xFF2E2350),
    onSecondaryContainer = Color(0xFFEDE9FE),
    tertiary = Color(0xFFF9A8D4),
    onTertiary = Color(0xFF3B0A24),
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF0B1020),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainerLowest = Color(0xFF070B16),
    surfaceContainerLow = Color(0xFF0F1629),
    surfaceContainer = Color(0xFF111827),
    surfaceContainerHigh = Color(0xFF1E293B),
    surfaceContainerHighest = Color(0xFF263449),
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF1F2937),
)

/**
 * A full colour scheme from one hue: tinted backgrounds and surfaces instead of plain white,
 * with text tones chosen to stay readable (see ThemeContrastTest).
 */
fun tintedScheme(hue: Float, dark: Boolean): ColorScheme = if (!dark) {
    lightColorScheme(
        primary = hsl(hue, 0.62f, 0.32f),
        onPrimary = Color.White,
        primaryContainer = hsl(hue, 0.85f, 0.86f),
        onPrimaryContainer = hsl(hue, 0.75f, 0.14f),
        secondary = hsl(hue + 25f, 0.40f, 0.36f),
        onSecondary = Color.White,
        secondaryContainer = hsl(hue, 0.70f, 0.89f),
        onSecondaryContainer = hsl(hue, 0.65f, 0.15f),
        tertiary = hsl(hue + 60f, 0.45f, 0.36f),
        onTertiary = Color.White,
        background = hsl(hue, 0.55f, 0.975f),
        onBackground = hsl(hue, 0.20f, 0.11f),
        surface = hsl(hue, 0.55f, 0.975f),
        onSurface = hsl(hue, 0.20f, 0.11f),
        surfaceVariant = hsl(hue, 0.35f, 0.90f),
        onSurfaceVariant = hsl(hue, 0.15f, 0.30f),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = hsl(hue, 0.55f, 0.955f),
        surfaceContainer = hsl(hue, 0.55f, 0.94f),
        surfaceContainerHigh = hsl(hue, 0.60f, 0.91f),
        surfaceContainerHighest = hsl(hue, 0.55f, 0.88f),
        outline = hsl(hue, 0.10f, 0.48f),
        outlineVariant = hsl(hue, 0.25f, 0.80f),
    )
} else {
    darkColorScheme(
        primary = hsl(hue, 0.80f, 0.78f),
        onPrimary = hsl(hue, 0.85f, 0.15f),
        primaryContainer = hsl(hue, 0.55f, 0.28f),
        onPrimaryContainer = hsl(hue, 0.85f, 0.90f),
        secondary = hsl(hue + 25f, 0.45f, 0.75f),
        onSecondary = hsl(hue + 25f, 0.60f, 0.15f),
        secondaryContainer = hsl(hue, 0.35f, 0.25f),
        onSecondaryContainer = hsl(hue, 0.60f, 0.88f),
        tertiary = hsl(hue + 60f, 0.50f, 0.75f),
        onTertiary = hsl(hue + 60f, 0.60f, 0.15f),
        background = hsl(hue, 0.25f, 0.08f),
        onBackground = hsl(hue, 0.15f, 0.90f),
        surface = hsl(hue, 0.25f, 0.08f),
        onSurface = hsl(hue, 0.15f, 0.90f),
        surfaceVariant = hsl(hue, 0.18f, 0.22f),
        onSurfaceVariant = hsl(hue, 0.15f, 0.76f),
        surfaceContainerLowest = hsl(hue, 0.25f, 0.06f),
        surfaceContainerLow = hsl(hue, 0.22f, 0.11f),
        surfaceContainer = hsl(hue, 0.22f, 0.13f),
        surfaceContainerHigh = hsl(hue, 0.22f, 0.17f),
        surfaceContainerHighest = hsl(hue, 0.20f, 0.21f),
        outline = hsl(hue, 0.10f, 0.56f),
        outlineVariant = hsl(hue, 0.15f, 0.30f),
    )
}

/** Gmail's own colours, kept as the Classic theme. */
private val GmailLight = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC2E7FF),
    onPrimaryContainer = Color(0xFF001D35),
    secondaryContainer = Color(0xFFD3E3FD),
    onSecondaryContainer = Color(0xFF041E49),
    background = Color.White,
    surface = Color.White,
    onSurface = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFF444746),
    surfaceContainerLow = Color(0xFFF8FAFD),
    surfaceContainer = Color(0xFFF6F8FC),
    surfaceContainerHigh = Color(0xFFEAF1FB),
    outline = Color(0xFF747775),
    outlineVariant = Color(0xFFC4C7C5),
)

private val GmailDark = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF004A77),
    onPrimaryContainer = Color(0xFFC2E7FF),
    secondaryContainer = Color(0xFF0842A0),
    onSecondaryContainer = Color(0xFFD3E3FD),
    background = Color(0xFF131314),
    surface = Color(0xFF131314),
    onSurface = Color(0xFFE3E3E3),
    onSurfaceVariant = Color(0xFFC4C7C5),
    surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainer = Color(0xFF1E1F20),
    surfaceContainerHigh = Color(0xFF282A2C),
    outline = Color(0xFF8E918F),
    outlineVariant = Color(0xFF444746),
)

/** Gmail's colours for its inbox tabs. */
fun categoryColor(category: String): Color? = when (category) {
    "promotions" -> Color(0xFF188038)
    "social" -> Color(0xFF1A73E8)
    "updates" -> Color(0xFFE37400)
    "forums" -> Color(0xFF9334E6)
    else -> null
}

/** HSL (hue in degrees, saturation and lightness 0..1) to a colour. */
fun hsl(hue: Float, saturation: Float, lightness: Float): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val c = (1f - abs(2f * lightness - 1f)) * saturation
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = lightness - c / 2f
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}
