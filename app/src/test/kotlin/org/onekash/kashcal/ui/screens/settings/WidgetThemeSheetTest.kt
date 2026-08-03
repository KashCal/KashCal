package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.widget.WidgetThemeSource

/**
 * Pure tests for the widget-theme-picker option model that backs [WidgetThemeSheet]. The options
 * derive from [WidgetThemeSource.entries] and each source's own label/description resources, so a
 * new source needs no change here — this pins that derivation and the menu ordering. In particular
 * the first option is Follow app (the default), and System is not offered.
 */
class WidgetThemeSheetTest {

    @Test
    fun `options cover every WidgetThemeSource in enum order`() {
        assertEquals(WidgetThemeSource.entries.toList(), widgetThemeSheetOptions().map { it.source })
    }

    @Test
    fun `first option is Follow app`() {
        assertEquals(WidgetThemeSource.FOLLOW_APP, widgetThemeSheetOptions().first().source)
    }

    @Test
    fun `each option's label and description come from its WidgetThemeSource`() {
        widgetThemeSheetOptions().forEach { option ->
            assertEquals(option.source.labelRes, option.labelRes)
            assertEquals(option.source.descriptionRes, option.descriptionRes)
        }
    }

    @Test
    fun `string resource ids are all distinct`() {
        val labelIds = widgetThemeSheetOptions().map { it.labelRes }
        val descIds = widgetThemeSheetOptions().map { it.descriptionRes }
        assertTrue("labels distinct", labelIds.toSet().size == labelIds.size)
        assertTrue("descriptions distinct", descIds.toSet().size == descIds.size)
    }
}
