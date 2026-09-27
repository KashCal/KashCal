package org.onekash.kashcal.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [WidgetThemeSource]: pref-value round trips, the [WidgetThemeSource.FOLLOW_APP] fallback,
 * the stored pref values and each source's label and description resources. The "system" value an
 * older widget-theme setting stored is unknown here and must map to FOLLOW_APP, the default.
 */
class WidgetThemeSourceTest {

    @Test
    fun `fromPrefValue round-trips every source`() {
        WidgetThemeSource.entries.forEach { source ->
            assertEquals(source, WidgetThemeSource.fromPrefValue(source.prefValue))
        }
    }

    @Test
    fun `fromPrefValue falls back to FOLLOW_APP for unknown or null`() {
        assertEquals(WidgetThemeSource.FOLLOW_APP, WidgetThemeSource.fromPrefValue(null))
        assertEquals(WidgetThemeSource.FOLLOW_APP, WidgetThemeSource.fromPrefValue(""))
        // The "system" value an older widget-theme setting stored.
        assertEquals(WidgetThemeSource.FOLLOW_APP, WidgetThemeSource.fromPrefValue("system"))
        assertEquals(WidgetThemeSource.FOLLOW_APP, WidgetThemeSource.fromPrefValue("bogus"))
    }

    @Test
    fun `pref values are the stable persisted contract`() {
        assertEquals("follow_app", WidgetThemeSource.FOLLOW_APP.prefValue)
        assertEquals("light", WidgetThemeSource.LIGHT.prefValue)
        assertEquals("dark", WidgetThemeSource.DARK.prefValue)
    }

    @Test
    fun `every source exposes a label and description resource`() {
        WidgetThemeSource.entries.forEach { source ->
            assertTrue("labelRes set for $source", source.labelRes != 0)
            assertTrue("descriptionRes set for $source", source.descriptionRes != 0)
        }
    }
}
