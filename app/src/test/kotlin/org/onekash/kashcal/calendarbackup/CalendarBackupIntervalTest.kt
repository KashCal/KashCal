package org.onekash.kashcal.calendarbackup

import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarBackupIntervalTest {

    @Test
    fun `days and hours within 1 hour to 30 days are valid`() {
        assertEquals(CalendarBackupInterval.Check.Valid(1), CalendarBackupInterval.check(0, 1))
        assertEquals(CalendarBackupInterval.Check.Valid(12), CalendarBackupInterval.check(0, 12))
        assertEquals(CalendarBackupInterval.Check.Valid(54), CalendarBackupInterval.check(2, 6))
        assertEquals(CalendarBackupInterval.Check.Valid(720), CalendarBackupInterval.check(30, 0))
    }

    @Test
    fun `zero is too short`() {
        assertEquals(CalendarBackupInterval.Check.TooShort, CalendarBackupInterval.check(0, 0))
    }

    @Test
    fun `above 30 days is too long`() {
        assertEquals(CalendarBackupInterval.Check.TooLong, CalendarBackupInterval.check(30, 1))
        assertEquals(CalendarBackupInterval.Check.TooLong, CalendarBackupInterval.check(31, 0))
    }

    @Test
    fun `default is 12 hours`() {
        assertEquals(12, CalendarBackupInterval.DEFAULT_HOURS)
    }

    @Test
    fun `stored values outside the range read as the default`() {
        assertEquals(12, CalendarBackupInterval.storedOrDefault(null))
        assertEquals(12, CalendarBackupInterval.storedOrDefault(0))
        assertEquals(12, CalendarBackupInterval.storedOrDefault(721))
        assertEquals(1, CalendarBackupInterval.storedOrDefault(1))
        assertEquals(720, CalendarBackupInterval.storedOrDefault(720))
    }

    @Test
    fun `hours split into days and hours`() {
        assertEquals(0 to 12, CalendarBackupInterval.split(12))
        assertEquals(2 to 6, CalendarBackupInterval.split(54))
        assertEquals(30 to 0, CalendarBackupInterval.split(720))
    }
}
