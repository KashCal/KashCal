package org.onekash.kashcal.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.junit.Test

/**
 * Pure unit tests for the theme face model: [ThemeMode] mapping and light/dark face resolution.
 * The accent color scheme's WCAG contrast is proven separately in [AccentSchemeTest]; the
 * retired-teal migration is covered in [ColorSourceTest].
 */
class ThemeModeTest {

    // ---- prefValue mapping ----

    @Test
    fun `each mode prefValue matches the DataStore theme constant`() {
        assertEquals(KashCalDataStore.THEME_SYSTEM, ThemeMode.SYSTEM.prefValue)
        assertEquals(KashCalDataStore.THEME_LIGHT, ThemeMode.LIGHT.prefValue)
        assertEquals(KashCalDataStore.THEME_DARK, ThemeMode.DARK.prefValue)
    }

    @Test
    fun `fromPrefValue round-trips every mode`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromPrefValue(mode.prefValue))
        }
    }

    @Test
    fun `fromPrefValue falls back to SYSTEM for unknown or null`() {
        // The retired "teal" theme string is unknown to the face model and falls back to SYSTEM;
        // its brand color is preserved separately via the accent seed (see ColorSourceTest).
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromPrefValue(KashCalDataStore.THEME_TEAL))
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

    // ---- forcedDark pin ----

    @Test
    fun `forcedDark is null for SYSTEM and pins the forced faces`() {
        assertEquals(null, ThemeMode.SYSTEM.forcedDark)
        assertEquals(false, ThemeMode.LIGHT.forcedDark)
        assertEquals(true, ThemeMode.DARK.forcedDark)
    }

    @Test
    fun `every mode exposes a label and description resource`() {
        ThemeMode.entries.forEach { mode ->
            assertTrue("labelRes set for $mode", mode.labelRes != 0)
            assertTrue("descriptionRes set for $mode", mode.descriptionRes != 0)
        }
    }
}
