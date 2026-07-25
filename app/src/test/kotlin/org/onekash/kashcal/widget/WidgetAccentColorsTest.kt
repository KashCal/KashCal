package org.onekash.kashcal.widget

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.onekash.kashcal.data.preferences.KashCalDataStore

/**
 * Verifies the widget color resolution: both the seed source and the automatic (Material You)
 * source yield tinted providers so the widget body carries accent on either — seed from the user's
 * pick, automatic from the system accent. A legacy teal user is migrated onto the seed path, and a
 * device that can't report a system accent falls back to null (bare Material You).
 */
class WidgetAccentColorsTest {

    private fun dataStore(
        colorSource: String?,
        theme: String = KashCalDataStore.THEME_SYSTEM,
        accentSeed: Int = KashCalDataStore.ACCENT_SEED_DEFAULT,
    ): KashCalDataStore = mockk {
        every { this@mockk.colorSource } returns flowOf(colorSource)
        every { this@mockk.theme } returns flowOf(theme)
        every { this@mockk.accentSeed } returns flowOf(accentSeed)
    }

    /** A context whose system-accent lookup returns [accent], or throws when [accent] is null. */
    private fun context(accent: Int? = 0xFF3F51B5.toInt()): Context = mockk {
        if (accent != null) {
            every { getColor(any()) } returns accent
        } else {
            every { getColor(any()) } throws Resources_NotFound()
        }
    }

    private class Resources_NotFound : RuntimeException()

    @Test
    fun `seed source yields accent color providers`() = runTest {
        val providers = resolveWidgetAccentColors(
            context(),
            dataStore(colorSource = org.onekash.kashcal.ui.theme.ColorSource.SEED.prefValue),
        )
        assertNotNull(providers)
    }

    @Test
    fun `dynamic source yields providers seeded from the system accent`() = runTest {
        val providers = resolveWidgetAccentColors(
            context(),
            dataStore(colorSource = org.onekash.kashcal.ui.theme.ColorSource.DYNAMIC.prefValue),
        )
        assertNotNull(providers)
    }

    @Test
    fun `unset source defaults to dynamic and still yields providers`() = runTest {
        val providers = resolveWidgetAccentColors(context(), dataStore(colorSource = null))
        assertNotNull(providers)
    }

    @Test
    fun `dynamic source falls back to null when the system accent is unavailable`() = runTest {
        val providers = resolveWidgetAccentColors(
            context(accent = null),
            dataStore(colorSource = org.onekash.kashcal.ui.theme.ColorSource.DYNAMIC.prefValue),
        )
        assertNull(providers)
    }

    @Test
    fun `legacy teal user is migrated to seed providers`() = runTest {
        val providers = resolveWidgetAccentColors(
            context(),
            dataStore(colorSource = null, theme = KashCalDataStore.THEME_TEAL),
        )
        assertNotNull(providers)
    }
}
