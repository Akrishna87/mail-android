package com.akrishna87.mail

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Text must stay readable on every tinted theme: WCAG AA contrast (4.5:1) for each text/background pair. */
class ThemeContrastTest {
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (x, y) = luminance(a) to luminance(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    @Test
    fun whiteTextIsReadableOnEveryGradient() {
        val failures = AppTheme.entries.mapNotNull { theme -> accentGradient(theme)?.let { theme to it } }
            .flatMap { (theme, stops) -> stops.mapIndexed { i, c -> "${theme.label} stop $i" to contrast(Color.White, c) } }
            .filter { it.second < 4.5 }
            .map { "${it.first} = %.2f".format(it.second) }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun tintedThemesHaveReadableText() {
        val failures = mutableListOf<String>()
        val schemes = AppTheme.entries.filter { it.hue != null }.flatMap { theme ->
            listOf(false, true).map { dark -> "${theme.label} ${if (dark) "dark" else "light"}" to tintedScheme(theme.hue!!, dark) }
        } + ("Night" to NightScheme)
        for ((label, s) in schemes) {
            run {
                val pairs = mapOf(
                    "onSurface/surface" to (s.onSurface to s.surface),
                    "onSurfaceVariant/surface" to (s.onSurfaceVariant to s.surface),
                    "onSurface/surfaceContainerHigh" to (s.onSurface to s.surfaceContainerHigh),
                    "onPrimary/primary" to (s.onPrimary to s.primary),
                    "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
                    "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
                )
                for ((name, pair) in pairs) {
                    val ratio = contrast(pair.first, pair.second)
                    if (ratio < 4.5) failures += "$label $name = %.2f".format(ratio)
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun hslMatchesKnownColours() {
        // Colours are stored as 8-bit sRGB, so compare to the nearest step.
        fun close(a: Float, b: Float) = kotlin.math.abs(a - b) < 1f / 255f
        val red = hsl(0f, 1f, 0.5f)
        assertTrue(close(red.red, 1f) && close(red.green, 0f) && close(red.blue, 0f))
        val grey = hsl(200f, 0f, 0.5f)
        assertTrue(close(grey.red, 0.5f) && close(grey.green, 0.5f) && close(grey.blue, 0.5f))
        val teal = hsl(180f, 1f, 0.25f)
        assertTrue(close(teal.red, 0f) && close(teal.green, 0.5f) && close(teal.blue, 0.5f))
    }
}
