package org.onekash.kashcal.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.data.preferences.KashCalDataStore

/**
 * The color source decides whether the app colors itself from the user's chosen accent seed
 * or from the platform's Material You / baseline (dynamic). It is resolved from an explicit
 * stored value, with a migration path for users who had picked the retired "teal" theme.
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
        // Existing users (and fresh installs) keep Material You / baseline until they opt into an accent.
        assertEquals(ColorSource.DYNAMIC, ColorSource.fromPrefValue(explicit = null, legacyTheme = null))
        assertEquals(
            ColorSource.DYNAMIC,
            ColorSource.fromPrefValue(explicit = null, legacyTheme = KashCalDataStore.THEME_SYSTEM),
        )
    }

    @Test
    fun `legacy teal theme migrates to SEED (brand-teal accent) when no explicit source stored`() {
        // A user who had picked the old "KashCal Teal" theme should land on the seed path so their
        // brand color is preserved (the accent seed defaults to brand teal).
        assertEquals(
            ColorSource.SEED,
            ColorSource.fromPrefValue(explicit = null, legacyTheme = KashCalDataStore.THEME_TEAL),
        )
    }

    @Test
    fun `explicit stored source wins over legacy teal`() {
        // Once the user has explicitly chosen, the legacy value is ignored.
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
