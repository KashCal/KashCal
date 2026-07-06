package org.onekash.kashcal.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.onekash.kashcal.R
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.junit.Test

/**
 * Pure unit tests for the theme model: [ThemeMode] mapping, face resolution, per-mode metadata,
 * and the teal brand schemes' WCAG AA contrast. No Robolectric / Compose runtime.
 */
class ThemeModeTest {

    // ---- prefValue mapping ----

    @Test
    fun `each mode prefValue matches the DataStore theme constant`() {
        assertEquals(KashCalDataStore.THEME_SYSTEM, ThemeMode.SYSTEM.prefValue)
        assertEquals(KashCalDataStore.THEME_LIGHT, ThemeMode.LIGHT.prefValue)
        assertEquals(KashCalDataStore.THEME_DARK, ThemeMode.DARK.prefValue)
        assertEquals(KashCalDataStore.THEME_TEAL, ThemeMode.TEAL.prefValue)
    }

    @Test
    fun `fromPrefValue round-trips every mode`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromPrefValue(mode.prefValue))
        }
    }

    @Test
    fun `fromPrefValue falls back to SYSTEM for unknown or null`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromPrefValue("teal-neon-2099"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromPrefValue(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromPrefValue(null))
    }

    // ---- isDark() face resolution ----

    @Test
    fun `SYSTEM follows the device dark setting`() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemInDark = true))
        assertEquals(false, ThemeMode.SYSTEM.isDark(systemInDark = false))
    }

    @Test
    fun `LIGHT is always light and DARK is always dark, regardless of device`() {
        assertEquals(false, ThemeMode.LIGHT.isDark(systemInDark = true))
        assertEquals(false, ThemeMode.LIGHT.isDark(systemInDark = false))
        assertTrue(ThemeMode.DARK.isDark(systemInDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemInDark = false))
    }

    @Test
    fun `TEAL follows the device dark setting`() {
        assertTrue(ThemeMode.TEAL.isDark(systemInDark = true))
        assertEquals(false, ThemeMode.TEAL.isDark(systemInDark = false))
    }

    // ---- palette: only branded modes carry a fixed palette ----

    @Test
    fun `only TEAL has a fixed palette while System Light Dark use dynamic`() {
        // Light/Dark stay on Material You (dynamic) — only a branded theme carries a fixed palette.
        assertNull(ThemeMode.SYSTEM.palette)
        assertNull(ThemeMode.LIGHT.palette)
        assertNull(ThemeMode.DARK.palette)
        assertNotNull(ThemeMode.TEAL.palette)
    }

    @Test
    fun `every mode exposes a label and description resource`() {
        ThemeMode.entries.forEach { mode ->
            assertTrue("labelRes set for $mode", mode.labelRes != 0)
            assertTrue("descriptionRes set for $mode", mode.descriptionRes != 0)
        }
        assertEquals(R.string.option_kashcal_teal, ThemeMode.TEAL.labelRes)
        assertEquals(R.string.settings_theme_teal_desc, ThemeMode.TEAL.descriptionRes)
    }

    // ---- teal palette anchors (exact website brand values) ----

    @Test
    fun `teal palette uses the website brand anchor colors`() {
        val teal = ThemeMode.TEAL.palette!!
        assertEquals(Color(0xFF0E6E62), teal.light.primary)
        assertEquals(Color(0xFFFBFCFB), teal.light.surface)

        assertEquals(Color(0xFF45C2AD), teal.dark.primary)
        assertEquals(Color(0xFF0D1413), teal.dark.surface)
    }

    // ---- WCAG AA contrast (>= 4.5:1 for text on fills) ----

    @Test
    fun `every teal text-on-fill pair passes WCAG AA`() {
        val l = ThemeMode.TEAL.palette!!.light
        val d = ThemeMode.TEAL.palette!!.dark

        assertAA("light onPrimary/primary", l.onPrimary, l.primary)
        assertAA("light onPrimaryContainer/primaryContainer", l.onPrimaryContainer, l.primaryContainer)
        assertAA("light onSecondaryContainer/secondaryContainer", l.onSecondaryContainer, l.secondaryContainer)
        assertAA("light onSurface/surface", l.onSurface, l.surface)
        assertAA("light onSurfaceVariant/surfaceVariant", l.onSurfaceVariant, l.surfaceVariant)

        assertAA("dark onPrimary/primary", d.onPrimary, d.primary)
        assertAA("dark onPrimaryContainer/primaryContainer", d.onPrimaryContainer, d.primaryContainer)
        assertAA("dark onSecondaryContainer/secondaryContainer", d.onSecondaryContainer, d.secondaryContainer)
        assertAA("dark onSurface/surface", d.onSurface, d.surface)
        assertAA("dark onSurfaceVariant/surfaceVariant", d.onSurfaceVariant, d.surfaceVariant)
    }

    @Test
    fun `onSurface stays legible on every surface-container role`() {
        val l = ThemeMode.TEAL.palette!!.light
        val d = ThemeMode.TEAL.palette!!.dark
        listOf(
            "light containerLowest" to l.surfaceContainerLowest,
            "light containerLow" to l.surfaceContainerLow,
            "light container" to l.surfaceContainer,
            "light containerHigh" to l.surfaceContainerHigh,
            "light containerHighest" to l.surfaceContainerHighest,
        ).forEach { (label, bg) -> assertAA("$label / onSurface", l.onSurface, bg) }
        listOf(
            "dark containerLowest" to d.surfaceContainerLowest,
            "dark containerLow" to d.surfaceContainerLow,
            "dark container" to d.surfaceContainer,
            "dark containerHigh" to d.surfaceContainerHigh,
            "dark containerHighest" to d.surfaceContainerHighest,
        ).forEach { (label, bg) -> assertAA("$label / onSurface", d.onSurface, bg) }
    }

    private fun assertAA(label: String, fg: Color, bg: Color) {
        val ratio = contrastRatio(fg, bg)
        assertTrue("$label contrast $ratio must be >= 4.5:1 (WCAG AA)", ratio >= 4.5)
    }

    /** WCAG 2.x contrast ratio between two opaque sRGB colors. */
    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(c: Color): Double {
        fun lin(channel: Float): Double {
            val cs = channel.toDouble()
            return if (cs <= 0.03928) cs / 12.92 else Math.pow((cs + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    // Sanity: reference the ColorScheme type so a wrong return type fails to compile.
    @Suppress("unused")
    private fun schemesAreColorSchemes(): List<ColorScheme> =
        listOf(ThemeMode.TEAL.palette!!.light, ThemeMode.TEAL.palette!!.dark)
}
