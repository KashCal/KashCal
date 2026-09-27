package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.widget.WidgetThemeSource

/**
 * Tests [widgetThemeSheetOptions], the option model behind [WidgetThemeSheet]: enum order with
 * Follow app (the default) first, labels and descriptions taken from each [WidgetThemeSource], and
 * distinct string ids. The options derive from [WidgetThemeSource.entries], so a new source needs
 * no change here. System is not offered, since the enum has no such entry (not asserted here).
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
