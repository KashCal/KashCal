package org.onekash.kashcal.ui.components.pickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [dateTimeSheetLayout] with the heights the sheet's middle gets: the screen less the status
 * bar 24dp, the top strip 60dp (handle 20dp, the 40dp switch), the Done bar 64dp and the
 * navigation bar (24dp gesture, 48dp 3-button), at 100% font unless stated. The spare and
 * overflow figures are derived from the returned parts, as the sheet's centering and scrolling
 * see them.
 */
class DateTimeSheetLayoutTest {

    private fun timed(middle: Float, minRow: Float = 32f) =
        Result(middle, dateTimeSheetLayout(middle, minRow, hintLineHeight = 24f, isAllDay = false))

    private fun allDay(middle: Float, hint: Float = 24f) =
        Result(middle, dateTimeSheetLayout(middle, 32f, hintLineHeight = hint, isAllDay = true))

    /**
     * Adds what the sheet does with the layout: the content's height from the layout's parts,
     * then the spare the sheet centers (split evenly) or the overflow it scrolls.
     */
    private class Result(middle: Float, layout: DateTimeSheetLayout) {
        val wheelRows = layout.wheelRows
        val rowHeight = layout.rowHeight
        val weekdayRowHeight = layout.weekdayRowHeight
        val dividerHeight = layout.dividerHeight
        val showChips = layout.showChips
        val hintLineHeight = layout.hintLineHeight
        val tightened = (32f - weekdayRowHeight) + if (layout.wheelRows == 0) 0f else 9f - dividerHeight
        private val content = 48f + weekdayRowHeight + 6 * 48f + dividerHeight +
            wheelRows * rowHeight + hintLineHeight + if (showChips) 48f else 0f
        val overflow = (content - middle).coerceAtLeast(0f)
        private val spare = (middle - content).coerceAtLeast(0f)
        val spareAbove = kotlin.math.floor(spare / 2f)
        val spareBelow = spare - spareAbove
    }

    @Test
    fun `a tall phone gets five rows, chips and the spare split evenly`() {
        val layout = timed(742f) // 411x914dp, gesture navigation

        assertEquals(5, layout.wheelRows)
        assertEquals(32f, layout.rowHeight, 0f)
        assertTrue(layout.showChips)
        assertEquals(78f, layout.spareAbove, 0f)
        assertEquals(79f, layout.spareBelow, 0f)
        assertEquals(0f, layout.overflow, 0f)
        assertEquals(0f, layout.tightened, 0f)
    }

    @Test
    fun `a taller middle splits a larger spare evenly`() {
        val layout = timed(750f)

        assertTrue(layout.showChips)
        assertEquals(82f, layout.spareAbove, 0f)
        assertEquals(83f, layout.spareBelow, 0f)
    }

    @Test
    fun `a small phone gets three rows and no chips`() {
        val layout = timed(468f) // 360x640dp, gesture navigation

        assertEquals(3, layout.wheelRows)
        assertFalse(layout.showChips)
        assertEquals(5f, layout.tightened, 0f)
        assertEquals(4f, layout.dividerHeight, 0f)
        assertEquals(32f, layout.weekdayRowHeight, 0f)
        assertEquals(0f, layout.overflow, 0f)
    }

    @Test
    fun `three-button navigation on a small phone uses all the spacing and scrolls 5dp`() {
        val layout = timed(444f) // 360x640dp, 3-button navigation

        assertEquals(3, layout.wheelRows)
        assertFalse(layout.showChips)
        assertEquals(24f, layout.tightened, 0f)
        assertEquals(16f, layout.weekdayRowHeight, 0f)
        assertEquals(1f, layout.dividerHeight, 0f)
        assertEquals(5f, layout.overflow, 0f)
    }

    @Test
    fun `the divider gives way before the weekday row`() {
        val layout = timed(452f)

        assertEquals(3, layout.wheelRows)
        assertEquals(21f, layout.tightened, 0f)
        assertEquals(1f, layout.dividerHeight, 0f)
        assertEquals(19f, layout.weekdayRowHeight, 0f)
        assertEquals(0f, layout.overflow, 0f)
    }

    @Test
    fun `proportional 200 percent font rows overflow a small phone`() {
        val layout = timed(476f, minRow = 56f) // API 31-33 scale 24sp to 48dp

        assertEquals(3, layout.wheelRows)
        assertEquals(56f, layout.rowHeight, 0f)
        assertEquals(24f, layout.tightened, 0f)
        assertEquals(45f, layout.overflow, 0f)
    }

    @Test
    fun `non-linear 200 percent font rows overflow a small phone by a little`() {
        val layout = timed(476f, minRow = 44f) // API 34+ scale 24sp to 36dp

        assertEquals(3, layout.wheelRows)
        assertEquals(24f, layout.tightened, 0f)
        assertEquals(9f, layout.overflow, 0f)
    }

    @Test
    fun `a landscape phone overflows and scrolls`() {
        val layout = timed(247f)

        assertEquals(3, layout.wheelRows)
        assertFalse(layout.showChips)
        assertEquals(202f, layout.overflow, 0f)
        assertEquals(0f, layout.spareAbove + layout.spareBelow, 0f)
    }

    @Test
    fun `rows never go below 32dp`() {
        assertEquals(32f, timed(750f, minRow = 20f).rowHeight, 0f)
    }

    @Test
    fun `five rows that fit exactly leave no spare and no chips`() {
        val layout = timed(537f)

        assertEquals(5, layout.wheelRows)
        assertFalse(layout.showChips)
        assertEquals(0f, layout.tightened, 0f)
        assertEquals(0f, layout.spareAbove + layout.spareBelow, 0f)
    }

    @Test
    fun `five rows one dp short take it from the divider`() {
        val layout = timed(536f)

        assertEquals(5, layout.wheelRows)
        assertEquals(1f, layout.tightened, 0f)
        assertEquals(8f, layout.dividerHeight, 0f)
        assertEquals(32f, layout.weekdayRowHeight, 0f)
    }

    @Test
    fun `five rows still fit with the full 24dp tightened`() {
        val layout = timed(513f)

        assertEquals(5, layout.wheelRows)
        assertFalse(layout.showChips)
        assertEquals(24f, layout.tightened, 0f)
        assertEquals(1f, layout.dividerHeight, 0f)
        assertEquals(16f, layout.weekdayRowHeight, 0f)
        assertEquals(0f, layout.overflow, 0f)
    }

    @Test
    fun `beyond 24dp short the wheel drops to three rows and the chips come back`() {
        val layout = timed(512f)

        assertEquals(3, layout.wheelRows)
        assertTrue(layout.showChips)
        assertEquals(9f, layout.tightened, 0f)
        assertEquals(1f, layout.dividerHeight, 0f)
        assertEquals(31f, layout.weekdayRowHeight, 0f)
        assertEquals(0f, layout.overflow, 0f)
    }

    @Test
    fun `five rows and chips show from 561dp, the spacing giving way`() {
        val withChips = timed(561f)
        val without = timed(560f)

        assertEquals(5, withChips.wheelRows)
        assertTrue(withChips.showChips)
        assertEquals(24f, withChips.tightened, 0f)
        assertEquals(0f, withChips.overflow, 0f)
        assertEquals(5, without.wheelRows)
        assertFalse(without.showChips)
        assertEquals(0f, without.tightened, 0f)
        assertEquals(23f, without.spareAbove + without.spareBelow, 0f)
    }

    @Test
    fun `chips with full spacing need 585dp`() {
        assertEquals(0f, timed(585f).tightened, 0f)
        assertTrue(timed(585f).showChips)
        assertEquals(1f, timed(584f).tightened, 0f)
    }

    @Test
    fun `a shorter phone keeps its chips by giving up spacing`() {
        val layout = timed(570f)

        assertTrue(layout.showChips)
        assertEquals(15f, layout.tightened, 0f)
        assertEquals(1f, layout.dividerHeight, 0f)
        assertEquals(25f, layout.weekdayRowHeight, 0f)
    }

    @Test
    fun `all-day has no wheel, always chips and the spare split evenly`() {
        val layout = allDay(750f)

        assertEquals(0, layout.wheelRows)
        assertTrue(layout.showChips)
        assertEquals(0f, layout.dividerHeight, 0f)
        assertEquals(24f, layout.hintLineHeight, 0f)
        assertEquals(155f, layout.spareAbove, 0f)
        assertEquals(155f, layout.spareBelow, 0f)
    }

    @Test
    fun `all-day keeps its chips on a small phone`() {
        val layout = allDay(476f)

        assertTrue(layout.showChips)
        assertEquals(36f, layout.spareAbove + layout.spareBelow, 0f)
        assertEquals(0f, layout.overflow, 0f)
    }

    @Test
    fun `all-day tightens the weekday row and then overflows`() {
        val layout = allDay(400f)

        assertEquals(16f, layout.tightened, 0f)
        assertEquals(16f, layout.weekdayRowHeight, 0f)
        assertEquals(24f, layout.overflow, 0f)
    }

    @Test
    fun `the weekday row starts 8dp taller than its labels need`() {
        assertEquals(32f, timed(750f).weekdayRowHeight, 0f)
        assertEquals(32f, allDay(750f).weekdayRowHeight, 0f)
    }

    @Test
    fun `a larger hint line from a larger font is reserved in full`() {
        val layout = allDay(750f, hint = 32f)

        assertEquals(32f, layout.hintLineHeight, 0f)
        assertEquals(302f, layout.spareAbove + layout.spareBelow, 0f)
    }

    @Test
    fun `a timed layout has no hint line`() {
        assertEquals(0f, timed(750f).hintLineHeight, 0f)
    }
}
