package org.onekash.kashcal.widget

import android.content.Context
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.glance.color.DayNightColorProvider
import androidx.glance.unit.ColorProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.ui.theme.ColorSource

/**
 * Verifies the widget color resolution. By default (FOLLOW_APP, never touched widget settings)
 * the widgets mirror the app: only the in-app SEED accent yields app-derived providers; the
 * automatic (Material You) source resolves to null so the widget renders on the device's genuine
 * dynamic palette (`GlanceTheme.colors` at the call site). A legacy teal user is migrated onto
 * the seed path.
 *
 * The widget-specific overrides sit ON TOP of that: a widget-only SEED recolors the widgets
 * regardless of the app source, a widget DYNAMIC forces the genuine palette regardless of the
 * app accent, and the widget light/dark mode pins one face by publishing the forced scheme as
 * both the day and night palette — for DYNAMIC that means concrete pinned providers built from
 * the platform's own dynamic scheme instead of the null passthrough.
 */
class WidgetAccentColorsTest {

    /** Unused by the resolution under test — the dynamic scheme is always injected below. */
    private val context: Context = mockk()

    /** Stand-in platform dynamic palette; the real default reads the device's (minSdk 31). */
    private val fakeLight = lightColorScheme(primary = Color(0xFF445566))
    private val fakeDark = darkColorScheme(primary = Color(0xFF112233))
    private val fakeDynamicScheme = { dark: Boolean -> if (dark) fakeDark else fakeLight }

    private fun dataStore(
        colorSource: String?,
        theme: String = KashCalDataStore.THEME_SYSTEM,
        accentSeed: Int = KashCalDataStore.ACCENT_SEED_DEFAULT,
        widgetColorSource: String? = null,
        widgetAccentSeed: Int = 0xFFFF0000.toInt(),
        widgetThemeSource: String? = null,
    ): KashCalDataStore = mockk {
        every { this@mockk.colorSource } returns flowOf(colorSource)
        every { this@mockk.theme } returns flowOf(theme)
        every { this@mockk.accentSeed } returns flowOf(accentSeed)
        every { this@mockk.widgetColorSource } returns flowOf(widgetColorSource)
        every { this@mockk.widgetAccentSeed } returns flowOf(widgetAccentSeed)
        every { this@mockk.widgetThemeSource } returns flowOf(widgetThemeSource)
    }

    /** Resolves a Glance [ColorProvider]'s concrete color for the light (false) or dark (true) face. */
    private fun ColorProvider.resolve(dark: Boolean): Color =
        (this as DayNightColorProvider).getColor(dark)

    // ========== FOLLOW_APP (default) ==========

    @Test
    fun `seed app source yields accent color providers on the system face`() = runTest {
        val config = resolveWidgetAccentColors(
            context,
            dataStore(colorSource = ColorSource.SEED.prefValue),
            fakeDynamicScheme,
        )
        assertNotNull(config.colors)
        assertNull(config.forcedDark)
    }

    @Test
    fun `dynamic app source resolves to null so the widget uses genuine Material You`() = runTest {
        val config = resolveWidgetAccentColors(
            context,
            dataStore(colorSource = ColorSource.DYNAMIC.prefValue),
            fakeDynamicScheme,
        )
        assertNull(config.colors)
        assertNull(config.forcedDark)
    }

    @Test
    fun `unset sources default to follow-app dynamic and resolve to null`() = runTest {
        val config = resolveWidgetAccentColors(context, dataStore(colorSource = null), fakeDynamicScheme)
        assertNull(config.colors)
    }

    @Test
    fun `legacy teal user is migrated to seed providers`() = runTest {
        val config = resolveWidgetAccentColors(
            context,
            dataStore(colorSource = null, theme = KashCalDataStore.THEME_TEAL),
            fakeDynamicScheme,
        )
        assertNotNull(config.colors)
    }

    // ========== Widget-only color source ==========

    @Test
    fun `widget seed overrides the app's dynamic source and uses the widget seed`() = runTest {
        val widgetSeed = 0xFFFF0000.toInt()
        val config = resolveWidgetAccentColors(
            context,
            dataStore(
                colorSource = ColorSource.DYNAMIC.prefValue,
                widgetColorSource = WidgetColorSource.SEED.prefValue,
                widgetAccentSeed = widgetSeed,
            ),
            fakeDynamicScheme,
        )
        assertNotNull(config.colors)
        // Must be the WIDGET seed's scheme, not the app's seed.
        val expected = accentColorProviders(widgetSeed)
        assertEquals(expected.primary.resolve(false), config.colors!!.primary.resolve(false))
        assertEquals(expected.primary.resolve(true), config.colors!!.primary.resolve(true))
    }

    @Test
    fun `widget dynamic overrides the app's seed accent with the genuine palette`() = runTest {
        val config = resolveWidgetAccentColors(
            context,
            dataStore(
                colorSource = ColorSource.SEED.prefValue,
                widgetColorSource = WidgetColorSource.DYNAMIC.prefValue,
            ),
            fakeDynamicScheme,
        )
        assertNull(config.colors)
    }

    @Test
    fun `unknown widget color source falls back to follow-app`() = runTest {
        val config = resolveWidgetAccentColors(
            context,
            dataStore(colorSource = ColorSource.SEED.prefValue, widgetColorSource = "bogus"),
            fakeDynamicScheme,
        )
        assertNotNull(config.colors)
    }

    // ========== Widget light/dark pin ==========

    @Test
    fun `pinned light face collapses day and night onto the light scheme`() = runTest {
        val config = resolveWidgetAccentColors(
            context,
            dataStore(
                colorSource = ColorSource.SEED.prefValue,
                widgetThemeSource = WidgetThemeSource.LIGHT.prefValue,
            ),
            fakeDynamicScheme,
        )
        assertEquals(false, config.forcedDark)
        val providers = config.colors!!
        assertEquals(providers.primary.resolve(false), providers.primary.resolve(true))
        assertEquals(providers.widgetBackground.resolve(false), providers.widgetBackground.resolve(true))
    }

    @Test
    fun `pinned dark face collapses day and night onto the dark scheme`() = runTest {
        val config = resolveWidgetAccentColors(
            context,
            dataStore(
                colorSource = ColorSource.SEED.prefValue,
                widgetThemeSource = WidgetThemeSource.DARK.prefValue,
            ),
            fakeDynamicScheme,
        )
        assertEquals(true, config.forcedDark)
        val providers = config.colors!!
        assertEquals(providers.primary.resolve(false), providers.primary.resolve(true))
        // ...and the pinned tone is the DARK scheme's, not the light one.
        val unpinned = accentColorProviders(KashCalDataStore.ACCENT_SEED_DEFAULT)
        assertEquals(unpinned.primary.resolve(true), providers.primary.resolve(false))
    }

    @Test
    fun `pinned dark face on the dynamic source yields concrete providers instead of null`() = runTest {
        val config = resolveWidgetAccentColors(
            context,
            dataStore(
                colorSource = ColorSource.DYNAMIC.prefValue,
                widgetThemeSource = WidgetThemeSource.DARK.prefValue,
            ),
            fakeDynamicScheme,
        )
        assertEquals(true, config.forcedDark)
        // No null passthrough here: the platform palette must be re-published pinned, so the
        // forced face shows even when the system is in the opposite mode.
        val providers = config.colors!!
        assertEquals(fakeDark.primary, providers.primary.resolve(false))
        assertEquals(fakeDark.primary, providers.primary.resolve(true))
    }

    @Test
    fun `pinned light face on the dynamic source pins the light platform scheme`() = runTest {
        val config = resolveWidgetAccentColors(
            context,
            dataStore(
                colorSource = ColorSource.DYNAMIC.prefValue,
                widgetThemeSource = WidgetThemeSource.LIGHT.prefValue,
            ),
            fakeDynamicScheme,
        )
        assertEquals(false, config.forcedDark)
        val providers = config.colors!!
        assertEquals(fakeLight.primary, providers.primary.resolve(false))
        assertEquals(fakeLight.primary, providers.primary.resolve(true))
    }

    @Test
    fun `pinned face also applies through follow-app dynamic`() = runTest {
        val config = resolveWidgetAccentColors(
            context,
            dataStore(
                colorSource = ColorSource.DYNAMIC.prefValue,
                widgetColorSource = WidgetColorSource.FOLLOW_APP.prefValue,
                widgetThemeSource = WidgetThemeSource.DARK.prefValue,
            ),
            fakeDynamicScheme,
        )
        assertEquals(true, config.forcedDark)
        assertNotNull(config.colors)
    }

    // ========== Follow-app theme source (default: track the app's face) ==========

    @Test
    fun `follow-app theme adopts the app forced-dark face`() = runTest {
        val config = resolveWidgetAccentColors(
            context,
            dataStore(
                colorSource = ColorSource.SEED.prefValue,
                theme = KashCalDataStore.THEME_DARK,
                // widgetThemeSource unset -> FOLLOW_APP by default.
            ),
            fakeDynamicScheme,
        )
        assertEquals(true, config.forcedDark)
    }

    @Test
    fun `follow-app theme adopts the app forced-light face`() = runTest {
        val config = resolveWidgetAccentColors(
            context,
            dataStore(
                colorSource = ColorSource.SEED.prefValue,
                theme = KashCalDataStore.THEME_LIGHT,
            ),
            fakeDynamicScheme,
        )
        assertEquals(false, config.forcedDark)
    }

    @Test
    fun `follow-app theme resolves to null when the app itself follows the device`() = runTest {
        // App on System -> no pin -> widget follows the device too (transitive device-following).
        val config = resolveWidgetAccentColors(
            context,
            dataStore(
                colorSource = ColorSource.SEED.prefValue,
                theme = KashCalDataStore.THEME_SYSTEM,
            ),
            fakeDynamicScheme,
        )
        assertNull(config.forcedDark)
    }

    @Test
    fun `legacy widget theme value system falls back to follow-app`() = runTest {
        // The earlier widget-theme setting persisted "system"; it's unknown to WidgetThemeSource and
        // must resolve to FOLLOW_APP — here the app is forced dark, so the widget adopts dark.
        val config = resolveWidgetAccentColors(
            context,
            dataStore(
                colorSource = ColorSource.SEED.prefValue,
                theme = KashCalDataStore.THEME_DARK,
                widgetThemeSource = "system",
            ),
            fakeDynamicScheme,
        )
        assertEquals(true, config.forcedDark)
    }

    @Test
    fun `explicit widget pin overrides the app face under follow-app color source`() = runTest {
        // App forced light, but the widget theme is explicitly pinned dark -> widget stays dark.
        val config = resolveWidgetAccentColors(
            context,
            dataStore(
                colorSource = ColorSource.SEED.prefValue,
                theme = KashCalDataStore.THEME_LIGHT,
                widgetThemeSource = WidgetThemeSource.DARK.prefValue,
            ),
            fakeDynamicScheme,
        )
        assertEquals(true, config.forcedDark)
    }
}
