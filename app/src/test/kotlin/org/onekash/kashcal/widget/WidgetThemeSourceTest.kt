package org.onekash.kashcal.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [WidgetThemeSource]: pref-value mapping and the follow-app fallback.
 * A legacy "system" value (written by the earlier widget-theme setting) is unknown here and
 * must map to [WidgetThemeSource.FOLLOW_APP], the current default.
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
        // The retired "system" value from the earlier widget-theme setting.
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
