package org.onekash.kashcal.data.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Unit tests for ContactEventUtils.
 *
 * Tests:
 * - parseContactDate: RFC 6350, ISO, US format, slash variants, invalid dates, leap years
 * - formatOrdinal: ordinal suffixes including teens, 21st-23rd, 111th
 * - calculateYearsSince: simple subtraction from occurrence timestamp
 * - formatBirthdayTitle: with/without year, edge cases (age 0, age 150+)
 * - formatAnniversaryTitle: with/without year, edge cases (years 0, 150+)
 * - encodeEventYear / decodeEventYear: round-trip, null, embedded text
 * - generateYearlyRRule: expected RRULE string
 * - getEventTimestamp: UTC midnight for given date
 * - getNextEventTimestamp: future vs past date
 * - minutesToIsoDuration: various time ranges
 */
class ContactEventUtilsTest {

    // ---------------------------------------------------------------
    // parseContactDate
    // ---------------------------------------------------------------

    @Test
    fun `parseContactDate - RFC 6350 no-year format`() {
        val result = ContactEventUtils.parseContactDate("--03-15")
        assertNotNull(result)
        assertEquals(3, result!!.month)
        assertEquals(15, result.day)
        assertNull(result.year)
    }

    @Test
    fun `parseContactDate - ISO format YYYY-MM-DD`() {
        val result = ContactEventUtils.parseContactDate("1990-07-04")
        assertNotNull(result)
        assertEquals(7, result!!.month)
        assertEquals(4, result.day)
        assertEquals(1990, result.year)
    }

    @Test
    fun `parseContactDate - slash format YYYY slash MM slash DD`() {
        val result = ContactEventUtils.parseContactDate("1985/12/25")
        assertNotNull(result)
        assertEquals(12, result!!.month)
        assertEquals(25, result.day)
        assertEquals(1985, result.year)
    }

    @Test
    fun `parseContactDate - US format MM slash DD slash YYYY`() {
        val result = ContactEventUtils.parseContactDate("07/04/1990")
        assertNotNull(result)
        assertEquals(7, result!!.month)
        assertEquals(4, result.day)
        assertEquals(1990, result.year)
    }

    @Test
    fun `parseContactDate - US format with dashes MM-DD-YYYY`() {
        val result = ContactEventUtils.parseContactDate("12-25-2000")
        assertNotNull(result)
        assertEquals(12, result!!.month)
        assertEquals(25, result.day)
        assertEquals(2000, result.year)
    }

    @Test
    fun `parseContactDate - leap year Feb 29 with valid year`() {
        val result = ContactEventUtils.parseContactDate("2000-02-29")
        assertNotNull(result)
        assertEquals(2, result!!.month)
        assertEquals(29, result.day)
        assertEquals(2000, result.year)
    }

    @Test
    fun `parseContactDate - Feb 29 on non-leap year returns null`() {
        val result = ContactEventUtils.parseContactDate("2001-02-29")
        assertNull(result)
    }

    @Test
    fun `parseContactDate - null input returns null`() {
        assertNull(ContactEventUtils.parseContactDate(null))
    }

    @Test
    fun `parseContactDate - blank input returns null`() {
        assertNull(ContactEventUtils.parseContactDate("   "))
    }

    @Test
    fun `parseContactDate - invalid month returns null`() {
        assertNull(ContactEventUtils.parseContactDate("2000-13-01"))
    }

    @Test
    fun `parseContactDate - invalid day returns null`() {
        assertNull(ContactEventUtils.parseContactDate("2000-04-31"))
    }

    @Test
    fun `parseContactDate - RFC 6350 invalid month-day returns null`() {
        assertNull(ContactEventUtils.parseContactDate("--13-05"))
    }

    @Test
    fun `parseContactDate - year out of range returns null`() {
        assertNull(ContactEventUtils.parseContactDate("1899-06-15"))
        assertNull(ContactEventUtils.parseContactDate("2101-06-15"))
    }

    // ---------------------------------------------------------------
    // formatOrdinal
    // ---------------------------------------------------------------

    @Test
    fun `formatOrdinal - standard suffixes`() {
        assertEquals("1st", ContactEventUtils.formatOrdinal(1))
        assertEquals("2nd", ContactEventUtils.formatOrdinal(2))
        assertEquals("3rd", ContactEventUtils.formatOrdinal(3))
        assertEquals("4th", ContactEventUtils.formatOrdinal(4))
        assertEquals("10th", ContactEventUtils.formatOrdinal(10))
    }

    @Test
    fun `formatOrdinal - teen numbers are all th`() {
        assertEquals("11th", ContactEventUtils.formatOrdinal(11))
        assertEquals("12th", ContactEventUtils.formatOrdinal(12))
        assertEquals("13th", ContactEventUtils.formatOrdinal(13))
    }

    @Test
    fun `formatOrdinal - 21st 22nd 23rd`() {
        assertEquals("21st", ContactEventUtils.formatOrdinal(21))
        assertEquals("22nd", ContactEventUtils.formatOrdinal(22))
        assertEquals("23rd", ContactEventUtils.formatOrdinal(23))
    }

    @Test
    fun `formatOrdinal - 111th 112th 113th are teens`() {
        assertEquals("111th", ContactEventUtils.formatOrdinal(111))
        assertEquals("112th", ContactEventUtils.formatOrdinal(112))
        assertEquals("113th", ContactEventUtils.formatOrdinal(113))
    }

    // ---------------------------------------------------------------
    // calculateYearsSince
    // ---------------------------------------------------------------

    @Test
    fun `calculateYearsSince - simple subtraction`() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.clear()
        cal.set(2025, Calendar.JULY, 4)
        val age = ContactEventUtils.calculateYearsSince(1990, cal.timeInMillis)
        assertEquals(35, age)
    }

    // ---------------------------------------------------------------
    // formatBirthdayTitle
    // ---------------------------------------------------------------

    @Test
    fun `formatBirthdayTitle - with birth year shows ordinal age`() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.clear()
        cal.set(2020, Calendar.MARCH, 15)
        val title = ContactEventUtils.formatBirthdayTitle("Alice", 1990, cal.timeInMillis)
        assertEquals("Alice's 30th Birthday", title)
    }

    @Test
    fun `formatBirthdayTitle - without birth year shows plain title`() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.clear()
        cal.set(2025, Calendar.JUNE, 1)
        val title = ContactEventUtils.formatBirthdayTitle("Bob", null, cal.timeInMillis)
        assertEquals("Bob's Birthday", title)
    }

    @Test
    fun `formatBirthdayTitle - age 0 falls back to plain title`() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.clear()
        cal.set(2000, Calendar.JANUARY, 1)
        val title = ContactEventUtils.formatBirthdayTitle("Baby", 2000, cal.timeInMillis)
        assertEquals("Baby's Birthday", title)
    }

    @Test
    fun `formatBirthdayTitle - age 150 or above falls back to plain title`() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.clear()
        cal.set(2025, Calendar.JANUARY, 1)
        val title = ContactEventUtils.formatBirthdayTitle("Ancient", 1850, cal.timeInMillis)
        assertEquals("Ancient's Birthday", title)
    }

    @Test
    fun `formatBirthdayTitle - age 149 is valid`() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.clear()
        cal.set(2049, Calendar.JANUARY, 1)
        val title = ContactEventUtils.formatBirthdayTitle("Old", 1900, cal.timeInMillis)
        assertEquals("Old's 149th Birthday", title)
    }

    // ---------------------------------------------------------------
    // formatAnniversaryTitle
    // ---------------------------------------------------------------

    @Test
    fun `formatAnniversaryTitle with year shows ordinal`() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.clear()
        cal.set(2026, Calendar.JUNE, 15)
        val title = ContactEventUtils.formatAnniversaryTitle("Alice", 2016, cal.timeInMillis)
        assertEquals("Alice's 10th Anniversary", title)
    }

    @Test
    fun `formatAnniversaryTitle without year shows plain`() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.clear()
        cal.set(2026, Calendar.JUNE, 15)
        val title = ContactEventUtils.formatAnniversaryTitle("Alice", null, cal.timeInMillis)
        assertEquals("Alice's Anniversary", title)
    }

    @Test
    fun `formatAnniversaryTitle year 0 or 150+ falls back to plain`() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.clear()
        cal.set(2026, Calendar.JANUARY, 1)

        // year 0 (same year as occurrence)
        val title0 = ContactEventUtils.formatAnniversaryTitle("Bob", 2026, cal.timeInMillis)
        assertEquals("Bob's Anniversary", title0)

        // 150+ years
        val titleOld = ContactEventUtils.formatAnniversaryTitle("Bob", 1850, cal.timeInMillis)
        assertEquals("Bob's Anniversary", titleOld)
    }

    // ---------------------------------------------------------------
    // encodeEventYear / decodeEventYear
    // ---------------------------------------------------------------

    @Test
    fun `encodeEventYear - encodes year with prefix`() {
        assertEquals("birthYear:1990", ContactEventUtils.encodeEventYear(1990))
    }

    @Test
    fun `encodeEventYear - null returns null`() {
        assertNull(ContactEventUtils.encodeEventYear(null))
    }

    @Test
    fun `decodeEventYear - decodes from encoded string`() {
        assertEquals(1990, ContactEventUtils.decodeEventYear("birthYear:1990"))
    }

    @Test
    fun `decodeEventYear - null returns null`() {
        assertNull(ContactEventUtils.decodeEventYear(null))
    }

    @Test
    fun `decodeEventYear - string without prefix returns null`() {
        assertNull(ContactEventUtils.decodeEventYear("some random text"))
    }

    @Test
    fun `encodeEventYear and decodeEventYear round-trip`() {
        val year = 2005
        val encoded = ContactEventUtils.encodeEventYear(year)
        val decoded = ContactEventUtils.decodeEventYear(encoded)
        assertEquals(year, decoded)
    }

    @Test
    fun `decodeEventYear - extracts year from embedded text`() {
        val description = "Contact birthday\nbirthYear:1985\nSome other notes"
        assertEquals(1985, ContactEventUtils.decodeEventYear(description))
    }

    // ---------------------------------------------------------------
    // YEARLY_RRULE
    // ---------------------------------------------------------------

    @Test
    fun `YEARLY_RRULE - returns yearly rule`() {
        assertEquals("FREQ=YEARLY;INTERVAL=1", ContactEventUtils.YEARLY_RRULE)
    }

    // ---------------------------------------------------------------
    // getEventTimestamp
    // ---------------------------------------------------------------

    @Test
    fun `getEventTimestamp - returns UTC midnight`() {
        val ts = ContactEventUtils.getEventTimestamp(3, 15, 2025)

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ts
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, cal.get(Calendar.MONTH))
        assertEquals(15, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
        assertEquals(0, cal.get(Calendar.MILLISECOND))
    }

    // ---------------------------------------------------------------
    // getNextEventTimestamp
    // ---------------------------------------------------------------

    @Test
    fun `getNextEventTimestamp - past month returns next year`() {
        // January is always past if we are in February or later
        val ts = ContactEventUtils.getNextEventTimestamp(1, 10)

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ts

        val now = Calendar.getInstance()
        val expectedYear = now.get(Calendar.YEAR) + 1

        assertEquals(expectedYear, cal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, cal.get(Calendar.MONTH))
        assertEquals(10, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `getNextEventTimestamp - future month returns current year`() {
        // December is always future if we are in February
        val ts = ContactEventUtils.getNextEventTimestamp(12, 25)

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ts

        val now = Calendar.getInstance()
        val expectedYear = now.get(Calendar.YEAR)

        assertEquals(expectedYear, cal.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, cal.get(Calendar.MONTH))
        assertEquals(25, cal.get(Calendar.DAY_OF_MONTH))
    }

    // ---------------------------------------------------------------
    // getStartTimestamp
    // ---------------------------------------------------------------

    @Test
    fun `getStartTimestamp - known year returns that year UTC midnight`() {
        val ts = ContactEventUtils.getStartTimestamp(7, 4, 1990)

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ts
        assertEquals(1990, cal.get(Calendar.YEAR))
        assertEquals(Calendar.JULY, cal.get(Calendar.MONTH))
        assertEquals(4, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }

    @Test
    fun `getStartTimestamp - null year returns currentYear minus 1`() {
        val ts = ContactEventUtils.getStartTimestamp(6, 15, null)

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ts
        val expectedYear = Calendar.getInstance().get(Calendar.YEAR) - 1
        assertEquals(expectedYear, cal.get(Calendar.YEAR))
        assertEquals(Calendar.JUNE, cal.get(Calendar.MONTH))
        assertEquals(15, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `getStartTimestamp - known leap year Feb 29 returns correct date`() {
        val ts = ContactEventUtils.getStartTimestamp(2, 29, 1992)

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ts
        assertEquals(1992, cal.get(Calendar.YEAR))
        assertEquals(Calendar.FEBRUARY, cal.get(Calendar.MONTH))
        assertEquals(29, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `getStartTimestamp - null year Feb 29 uses nearest past leap year`() {
        val ts = ContactEventUtils.getStartTimestamp(2, 29, null)

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ts
        val year = cal.get(Calendar.YEAR)
        // Must be a leap year
        assertTrue("Year $year must be a leap year", year % 4 == 0 && (year % 100 != 0 || year % 400 == 0))
        // Must be in the past (before current year)
        assertTrue("Year $year must be before current year", year < Calendar.getInstance().get(Calendar.YEAR))
        // Must be Feb 29, not rolled to Mar 1
        assertEquals(Calendar.FEBRUARY, cal.get(Calendar.MONTH))
        assertEquals(29, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `getStartTimestamp - future month with known year still uses known year`() {
        // December with year 1985 should return 1985, not next year
        val ts = ContactEventUtils.getStartTimestamp(12, 25, 1985)

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ts
        assertEquals(1985, cal.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, cal.get(Calendar.MONTH))
        assertEquals(25, cal.get(Calendar.DAY_OF_MONTH))
    }

    // ---------------------------------------------------------------
    // minutesToIsoDuration
    // ---------------------------------------------------------------

    @Test
    fun `minutesToIsoDuration - zero returns PT0M`() {
        assertEquals("PT0M", ContactEventUtils.minutesToIsoDuration(0))
    }

    @Test
    fun `minutesToIsoDuration - minutes only`() {
        assertEquals("-PT30M", ContactEventUtils.minutesToIsoDuration(30))
    }

    @Test
    fun `minutesToIsoDuration - exact hours`() {
        assertEquals("-PT2H", ContactEventUtils.minutesToIsoDuration(120))
    }

    @Test
    fun `minutesToIsoDuration - hours and minutes`() {
        assertEquals("-PT1H30M", ContactEventUtils.minutesToIsoDuration(90))
    }

    @Test
    fun `minutesToIsoDuration - exact days`() {
        assertEquals("-P1D", ContactEventUtils.minutesToIsoDuration(1440))
    }

    @Test
    fun `minutesToIsoDuration - days and hours`() {
        assertEquals("-P1DT2H", ContactEventUtils.minutesToIsoDuration(1560))
    }

    @Test
    fun `minutesToIsoDuration - weeks`() {
        assertEquals("-P1W", ContactEventUtils.minutesToIsoDuration(10080))
    }

    // ---------------------------------------------------------------
    // ContactEventSyncResult
    // ---------------------------------------------------------------

    @Test
    fun `ContactEventSyncResult Success holds counts`() {
        val result = ContactEventSyncResult.Success(added = 3, updated = 1, deleted = 2)
        assertEquals(3, result.added)
        assertEquals(1, result.updated)
        assertEquals(2, result.deleted)
    }

    @Test
    fun `ContactEventSyncResult Error holds message`() {
        val result = ContactEventSyncResult.Error("test error")
        assertEquals("test error", result.message)
    }
}
