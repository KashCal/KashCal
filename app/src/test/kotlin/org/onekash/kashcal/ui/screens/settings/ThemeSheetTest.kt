package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.ui.theme.ThemeMode

/**
 * Pure tests for the theme-picker option model that backs [ThemeSheet]. The options derive from
 * [ThemeMode.entries] and each mode's own label/description resources, so adding a new theme needs
 * no change here — this pins that derivation and the menu ordering.
 */
class ThemeSheetTest {

    @Test
    fun `options cover every ThemeMode in enum order`() {
        assertEquals(ThemeMode.entries.toList(), themeSheetOptions().map { it.mode })
    }

    @Test
    fun `each option's label and description come from its ThemeMode`() {
        themeSheetOptions().forEach { option ->
            assertEquals(option.mode.labelRes, option.labelRes)
            assertEquals(option.mode.descriptionRes, option.descriptionRes)
        }
    }

    @Test
    fun `string resource ids are all distinct`() {
        val labelIds = themeSheetOptions().map { it.labelRes }
        val descIds = themeSheetOptions().map { it.descriptionRes }
        assertTrue("labels distinct", labelIds.toSet().size == labelIds.size)
        assertTrue("descriptions distinct", descIds.toSet().size == descIds.size)
    }
}
