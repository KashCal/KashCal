package org.onekash.kashcal.ui.components.pickers

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.util.Calendar

/**
 * Tests the date and time sheet grid's look helpers: [sheetDayLook], [sheetWeekdays] and
 * [bandSpan].
 */
class DateTimeSheetGridLookTest {

    // ---- sheetDayLook: selected > other range end > today > in range > plain ----

    @Test
    fun `the selected day wins over every other look`() {
        assertEquals(SheetDayLook.SELECTED, sheetDayLook(isSelected = true, isToday = true, part = RangePart.END))
        assertEquals(SheetDayLook.SELECTED, sheetDayLook(isSelected = true, isToday = false, part = RangePart.NONE))
    }

    @Test
    fun `the range end not being edited wins over today`() {
        assertEquals(SheetDayLook.RANGE_END, sheetDayLook(isSelected = false, isToday = true, part = RangePart.START))
        assertEquals(SheetDayLook.RANGE_END, sheetDayLook(isSelected = false, isToday = false, part = RangePart.END))
    }

    @Test
    fun `today wins over a day inside the range`() {
        assertEquals(SheetDayLook.TODAY, sheetDayLook(isSelected = false, isToday = true, part = RangePart.MIDDLE))
        assertEquals(SheetDayLook.TODAY, sheetDayLook(isSelected = false, isToday = true, part = RangePart.NONE))
    }

    @Test
    fun `days inside the range and plain days`() {
        assertEquals(SheetDayLook.IN_RANGE, sheetDayLook(isSelected = false, isToday = false, part = RangePart.MIDDLE))
        assertEquals(SheetDayLook.PLAIN, sheetDayLook(isSelected = false, isToday = false, part = RangePart.NONE))
    }

    @Test
    fun `weekend red shows only on plain days and days inside the range`() {
        assertTrue(sheetDayTextIsWeekend(SheetDayLook.PLAIN, isWeekend = true))
        assertTrue(sheetDayTextIsWeekend(SheetDayLook.IN_RANGE, isWeekend = true))
        assertFalse(sheetDayTextIsWeekend(SheetDayLook.SELECTED, isWeekend = true))
        assertFalse(sheetDayTextIsWeekend(SheetDayLook.RANGE_END, isWeekend = true))
        assertFalse(sheetDayTextIsWeekend(SheetDayLook.TODAY, isWeekend = true))
        assertFalse(sheetDayTextIsWeekend(SheetDayLook.PLAIN, isWeekend = false))
    }

    // ---- sheetWeekdays: weekend follows the day, not the column ----

    @Test
    fun `a Sunday-first week has the weekend at both ends`() {
        val days = sheetWeekdays(Calendar.SUNDAY)

        assertEquals(DayOfWeek.SUNDAY, days.first().first)
        assertEquals(listOf(true, false, false, false, false, false, true), days.map { it.second })
    }

    @Test
    fun `a Monday-first week has the weekend in the last two columns`() {
        val days = sheetWeekdays(Calendar.MONDAY)

        assertEquals(DayOfWeek.MONDAY, days.first().first)
        assertEquals(DayOfWeek.SUNDAY, days.last().first)
        assertEquals(listOf(false, false, false, false, false, true, true), days.map { it.second })
    }

    // ---- bandSpan: each end carries the band on its half toward the rest of the range ----

    @Test
    fun `the band covers the inner halves of the ends left to right`() {
        assertEquals(0.5f to 0.5f, bandSpan(RangePart.START, LayoutDirection.Ltr))
        assertEquals(0f to 0.5f, bandSpan(RangePart.END, LayoutDirection.Ltr))
        assertEquals(0f to 1f, bandSpan(RangePart.MIDDLE, LayoutDirection.Ltr))
    }

    @Test
    fun `the band mirrors right to left`() {
        assertEquals(0f to 0.5f, bandSpan(RangePart.START, LayoutDirection.Rtl))
        assertEquals(0.5f to 0.5f, bandSpan(RangePart.END, LayoutDirection.Rtl))
        assertEquals(0f to 1f, bandSpan(RangePart.MIDDLE, LayoutDirection.Rtl))
    }

    @Test
    fun `no band outside a range`() {
        assertNull(bandSpan(RangePart.NONE, LayoutDirection.Ltr))
    }
}
