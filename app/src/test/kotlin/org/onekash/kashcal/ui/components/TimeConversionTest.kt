package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.ui.components.pickers.to24Hour
import org.onekash.kashcal.ui.components.pickers.to12Hour

/**
 * Unit tests for 12-hour <-> 24-hour time conversion functions.
 *
 * These conversions are critical for the time picker UI which displays
 * 12-hour format but stores 24-hour format in EventFormState.
 */
class TimeConversionTest {

    // ========== 12-hour to 24-hour Tests ==========

    @Test
    fun `12 AM converts to hour 0`() {
        assertEquals(0, to24Hour(12, isAm = true))
    }

    @Test
    fun `12-30 AM converts to hour 0`() {
        // Midnight hour, any minute
        assertEquals(0, to24Hour(12, isAm = true))
    }

    @Test
    fun `1 AM converts to hour 1`() {
        assertEquals(1, to24Hour(1, isAm = true))
    }

    @Test
    fun `2 AM converts to hour 2`() {
        assertEquals(2, to24Hour(2, isAm = true))
    }

    @Test
    fun `11 AM converts to hour 11`() {
        assertEquals(11, to24Hour(11, isAm = true))
    }

    @Test
    fun `12 PM converts to hour 12`() {
        assertEquals(12, to24Hour(12, isAm = false))
    }

    @Test
    fun `1 PM converts to hour 13`() {
        assertEquals(13, to24Hour(1, isAm = false))
    }

    @Test
    fun `2 PM converts to hour 14`() {
        assertEquals(14, to24Hour(2, isAm = false))
    }

    @Test
    fun `11 PM converts to hour 23`() {
        assertEquals(23, to24Hour(11, isAm = false))
    }

    // ========== 24-hour to 12-hour Tests ==========

    @Test
    fun `hour 0 converts to 12 AM`() {
        val (hour12, isAm) = to12Hour(0)
        assertEquals(12, hour12)
        assertEquals(true, isAm)
    }

    @Test
    fun `hour 1 converts to 1 AM`() {
        val (hour12, isAm) = to12Hour(1)
        assertEquals(1, hour12)
        assertEquals(true, isAm)
    }

    @Test
    fun `hour 11 converts to 11 AM`() {
        val (hour12, isAm) = to12Hour(11)
        assertEquals(11, hour12)
        assertEquals(true, isAm)
    }

    @Test
    fun `hour 12 converts to 12 PM`() {
        val (hour12, isAm) = to12Hour(12)
        assertEquals(12, hour12)
        assertEquals(false, isAm)
    }

    @Test
    fun `hour 13 converts to 1 PM`() {
        val (hour12, isAm) = to12Hour(13)
        assertEquals(1, hour12)
        assertEquals(false, isAm)
    }

    @Test
    fun `hour 23 converts to 11 PM`() {
        val (hour12, isAm) = to12Hour(23)
        assertEquals(11, hour12)
        assertEquals(false, isAm)
    }

    // ========== Round-Trip Tests ==========

    @Test
    fun `all hours round-trip correctly`() {
        for (hour24 in 0..23) {
            val (hour12, isAm) = to12Hour(hour24)
            val convertedBack = to24Hour(hour12, isAm)
            assertEquals("Hour $hour24 should round-trip correctly", hour24, convertedBack)
        }
    }

    @Test
    fun `all 12-hour values with AM round-trip correctly`() {
        for (hour12 in 1..12) {
            val hour24 = to24Hour(hour12, isAm = true)
            val (convertedHour, convertedIsAm) = to12Hour(hour24)
            assertEquals("$hour12 AM should round-trip correctly", hour12, convertedHour)
            assertEquals("$hour12 AM isAm should be true", true, convertedIsAm)
        }
    }

    @Test
    fun `all 12-hour values with PM round-trip correctly`() {
        for (hour12 in 1..12) {
            val hour24 = to24Hour(hour12, isAm = false)
            val (convertedHour, convertedIsAm) = to12Hour(hour24)
            assertEquals("$hour12 PM should round-trip correctly", hour12, convertedHour)
            assertEquals("$hour12 PM isAm should be false", false, convertedIsAm)
        }
    }

    // ========== Edge Cases ==========

    @Test
    fun `boundary midnight 12 AM is hour 0`() {
        assertEquals(0, to24Hour(12, isAm = true))
    }

    @Test
    fun `boundary noon 12 PM is hour 12`() {
        assertEquals(12, to24Hour(12, isAm = false))
    }

    @Test
    fun `boundary 11-59 PM is before midnight`() {
        // 11 PM = 23:00
        assertEquals(23, to24Hour(11, isAm = false))
    }

    @Test
    fun `boundary 12-59 AM is just after midnight`() {
        // 12:59 AM = 00:59
        assertEquals(0, to24Hour(12, isAm = true))
    }
}
