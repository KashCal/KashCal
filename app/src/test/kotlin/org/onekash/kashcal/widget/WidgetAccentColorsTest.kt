package org.onekash.kashcal.widget

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.onekash.kashcal.data.preferences.KashCalDataStore

/**
 * Verifies the widget color resolution. Only the in-app SEED accent yields app-derived providers;
 * the automatic (Material You) source resolves to null so the widget renders on the device's
 * genuine dynamic palette (`GlanceTheme.colors` at the call site) rather than an app-reseeded tint.
 * A legacy teal user is migrated onto the seed path.
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

    @Test
    fun `seed source yields accent color providers`() = runTest {
        val providers = resolveWidgetAccentColors(
            dataStore(colorSource = org.onekash.kashcal.ui.theme.ColorSource.SEED.prefValue),
        )
        assertNotNull(providers)
    }

    @Test
    fun `dynamic source resolves to null so the widget uses genuine Material You`() = runTest {
        val providers = resolveWidgetAccentColors(
            dataStore(colorSource = org.onekash.kashcal.ui.theme.ColorSource.DYNAMIC.prefValue),
        )
        assertNull(providers)
    }

    @Test
    fun `unset source defaults to dynamic and resolves to null`() = runTest {
        val providers = resolveWidgetAccentColors(dataStore(colorSource = null))
        assertNull(providers)
    }

    @Test
    fun `legacy teal user is migrated to seed providers`() = runTest {
        val providers = resolveWidgetAccentColors(
            dataStore(colorSource = null, theme = KashCalDataStore.THEME_TEAL),
        )
        assertNotNull(providers)
    }
}
