package org.onekash.kashcal.ui.components.pickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Unit tests for DateTimePicker component helper functions.
 */
class DateTimePickerTest {

    // ==================== isMultiDay Tests ====================

    @Test
    fun `isMultiDay returns false for same day events`() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 15, 10, 0, 0)
        }
        val startMillis = cal.timeInMillis

        cal.set(Calendar.HOUR_OF_DAY, 14)
        val endMillis = cal.timeInMillis

        assertFalse(isMultiDay(startMillis, endMillis))
    }

    @Test
    fun `isMultiDay returns true for different day events`() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 15, 10, 0, 0)
        }
        val startMillis = cal.timeInMillis

        cal.add(Calendar.DAY_OF_MONTH, 1)
        val endMillis = cal.timeInMillis

        assertTrue(isMultiDay(startMillis, endMillis))
    }

    @Test
    fun `isMultiDay returns true for week-long events`() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 15, 10, 0, 0)
        }
        val startMillis = cal.timeInMillis

        cal.add(Calendar.DAY_OF_MONTH, 7)
        val endMillis = cal.timeInMillis

        assertTrue(isMultiDay(startMillis, endMillis))
    }

    // ==================== isMidnightCrossing Tests ====================

    @Test
    fun `isMidnightCrossing returns false for normal same-day range`() {
        // 10:00 AM to 2:00 PM
        assertFalse(isMidnightCrossing(10, 0, 14, 0))
    }

    @Test
    fun `isMidnightCrossing returns true when end is before start`() {
        // 10:00 PM to 2:00 AM (next day)
        assertTrue(isMidnightCrossing(22, 0, 2, 0))
    }

    @Test
    fun `isMidnightCrossing returns true for 11 PM to 1 AM`() {
        assertTrue(isMidnightCrossing(23, 0, 1, 0))
    }

    @Test
    fun `isMidnightCrossing returns false for midnight start to later time`() {
        // 12:00 AM to 6:00 AM - not a crossing, just early morning
        assertFalse(isMidnightCrossing(0, 0, 6, 0))
    }

    @Test
    fun `isMidnightCrossing returns true when end time equals start time with minutes`() {
        // 10:30 to 10:00 - end is "before" start in minutes
        assertTrue(isMidnightCrossing(10, 30, 10, 0))
    }

    @Test
    fun `isMidnightCrossing returns false when times are equal`() {
        // Same time is not a crossing
        assertFalse(isMidnightCrossing(10, 0, 10, 0))
    }

    // ==================== shouldShowSeparatePickers Tests ====================

    @Test
    fun `shouldShowSeparatePickers returns false for same day timed event`() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 15, 10, 0, 0)
        }
        val startMillis = cal.timeInMillis
        val endMillis = cal.timeInMillis // Same day

        assertFalse(
            shouldShowSeparatePickers(
                startDateMillis = startMillis,
                endDateMillis = endMillis,
                startHour = 10,
                startMinute = 0,
                endHour = 14,
                endMinute = 0,
                isAllDay = false
            )
        )
    }

    @Test
    fun `shouldShowSeparatePickers returns true for multi-day event`() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 15, 10, 0, 0)
        }
        val startMillis = cal.timeInMillis

        cal.add(Calendar.DAY_OF_MONTH, 2)
        val endMillis = cal.timeInMillis

        assertTrue(
            shouldShowSeparatePickers(
                startDateMillis = startMillis,
                endDateMillis = endMillis,
                startHour = 10,
                startMinute = 0,
                endHour = 14,
                endMinute = 0,
                isAllDay = false
            )
        )
    }

    @Test
    fun `shouldShowSeparatePickers returns true for different dates regardless of time`() {
        val startCal = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 15, 0, 0, 0)
        }
        val endCal = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 16, 0, 0, 0)
        }

        assertTrue(
            shouldShowSeparatePickers(
                startDateMillis = startCal.timeInMillis,
                endDateMillis = endCal.timeInMillis,
                startHour = 10,
                startMinute = 0,
                endHour = 10,
                endMinute = 0,
                isAllDay = false
            )
        )
    }

    @Test
    fun `shouldShowSeparatePickers handles year boundary`() {
        val startCal = Calendar.getInstance().apply {
            set(2025, Calendar.DECEMBER, 31, 0, 0, 0)
        }
        val endCal = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 1, 0, 0, 0)
        }

        assertTrue(
            shouldShowSeparatePickers(
                startDateMillis = startCal.timeInMillis,
                endDateMillis = endCal.timeInMillis,
                startHour = 22,
                startMinute = 0,
                endHour = 2,
                endMinute = 0,
                isAllDay = false
            )
        )
    }

    // ==================== DateSelectionMode Tests ====================

    @Test
    fun `DateSelectionMode has START and END values`() {
        assertEquals(2, DateSelectionMode.values().size)
        assertTrue(DateSelectionMode.values().contains(DateSelectionMode.START))
        assertTrue(DateSelectionMode.values().contains(DateSelectionMode.END))
    }

    // ==================== ActiveDateTimeSheet Tests ====================

    @Test
    fun `ActiveDateTimeSheet has all expected values`() {
        assertEquals(3, ActiveDateTimeSheet.values().size)
        assertTrue(ActiveDateTimeSheet.values().contains(ActiveDateTimeSheet.NONE))
        assertTrue(ActiveDateTimeSheet.values().contains(ActiveDateTimeSheet.START))
        assertTrue(ActiveDateTimeSheet.values().contains(ActiveDateTimeSheet.END))
    }

    // ==================== Edge Cases ====================

    @Test
    fun `isMidnightCrossing with minutes at boundary`() {
        // 23:59 to 00:01 - crosses midnight
        assertTrue(isMidnightCrossing(23, 59, 0, 1))
    }

    @Test
    fun `isMultiDay with same timestamp returns false`() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 15, 10, 0, 0)
        }
        val millis = cal.timeInMillis
        assertFalse(isMultiDay(millis, millis))
    }

    @Test
    fun `shouldShowSeparatePickers for all-day multi-day event`() {
        val startCal = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 15, 0, 0, 0)
        }
        val endCal = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 17, 0, 0, 0)
        }

        assertTrue(
            shouldShowSeparatePickers(
                startDateMillis = startCal.timeInMillis,
                endDateMillis = endCal.timeInMillis,
                startHour = 0,
                startMinute = 0,
                endHour = 0,
                endMinute = 0,
                isAllDay = true
            )
        )
    }
}
