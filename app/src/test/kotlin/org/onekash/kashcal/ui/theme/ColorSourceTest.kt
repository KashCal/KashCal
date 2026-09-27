package org.onekash.kashcal.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.data.preferences.KashCalDataStore

/**
 * Tests [ColorSource.fromPrefValue], which decides whether the app colors itself from the user's
 * accent seed or from the platform's Material You or baseline scheme (dynamic). An explicit stored
 * value wins; without one, users of the retired "teal" theme get the seed and everyone else,
 * unknown values included, gets dynamic.
 */
class ColorSourceTest {

    @Test
    fun `explicit SEED and DYNAMIC round-trip through prefValue`() {
        ColorSource.entries.forEach { source ->
            assertEquals(source, ColorSource.fromPrefValue(source.prefValue, legacyTheme = null))
        }
    }

    @Test
    fun `default when nothing stored is DYNAMIC`() {
        // Existing users and fresh installs keep Material You or baseline until they pick an
        // accent.
        assertEquals(ColorSource.DYNAMIC, ColorSource.fromPrefValue(explicit = null, legacyTheme = null))
        assertEquals(
            ColorSource.DYNAMIC,
            ColorSource.fromPrefValue(explicit = null, legacyTheme = KashCalDataStore.THEME_SYSTEM),
        )
    }

    @Test
    fun `legacy teal theme migrates to SEED (brand-teal accent) when no explicit source stored`() {
        // A user of the retired "KashCal Teal" theme lands on the seed path and keeps the brand
        // color, since the accent seed defaults to brand teal.
        assertEquals(
            ColorSource.SEED,
            ColorSource.fromPrefValue(explicit = null, legacyTheme = KashCalDataStore.THEME_TEAL),
        )
    }

    @Test
    fun `explicit stored source wins over legacy teal`() {
        // Once the user has chosen explicitly, the legacy value is ignored.
        assertEquals(
            ColorSource.DYNAMIC,
            ColorSource.fromPrefValue(explicit = ColorSource.DYNAMIC.prefValue, legacyTheme = KashCalDataStore.THEME_TEAL),
        )
    }

    @Test
    fun `unknown explicit value falls back to DYNAMIC`() {
        assertEquals(ColorSource.DYNAMIC, ColorSource.fromPrefValue(explicit = "wallpaper-2099", legacyTheme = null))
    }
}
