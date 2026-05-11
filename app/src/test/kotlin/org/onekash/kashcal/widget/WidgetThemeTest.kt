package org.onekash.kashcal.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure (non-@Composable) parts of [WidgetTheme].
 *
 * The selector returns enum-typed token names so the contrast contract can be
 * asserted at the unit-test layer; the composable hop from token -> ColorProvider
 * lives at WidgetTheme.kt as a small `when` block.
 */
class WidgetThemeTest {

    @Test
    fun `dayHeaderColors for today selects header background and primary text`() {
        val colors = dayHeaderColors(isToday = true)
        assertEquals(WidgetThemeColor.HeaderBackground, colors.background)
        assertEquals(WidgetThemeColor.PrimaryText, colors.text)
    }

    @Test
    fun `dayHeaderColors for non-today selects row tint background and row tint text`() {
        val colors = dayHeaderColors(isToday = false)
        assertEquals(WidgetThemeColor.RowTintBackground, colors.background)
        assertEquals(WidgetThemeColor.RowTintText, colors.text)
    }
}
