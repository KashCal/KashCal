package org.onekash.kashcal.data.contacts

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Unit tests for ContactBirthdayUtils.
 *
 * Tests:
 * - parseBirthday: RFC 6350, ISO, US format, slash variants, invalid dates, leap years
 * - formatOrdinal: ordinal suffixes including teens, 21st-23rd, 111th
 * - calculateAge: simple subtraction from occurrence timestamp
 * - formatBirthdayTitle: with/without year, edge cases (age 0, age 150+)
 * - encodeBirthYear / decodeBirthYear: round-trip, null, embedded text
 * - generateBirthdayRRule: expected RRULE string
 * - getBirthdayTimestamp: UTC midnight for given date
 * - getNextBirthdayTimestamp: future vs past birthday
 */
class ContactBirthdayUtilsTest {

    // ---------------------------------------------------------------
    // parseBirthday
    // ---------------------------------------------------------------

    @Test
    fun `parseBirthday - RFC 6350 no-year format`() {
        val result = ContactBirthdayUtils.parseBirthday("--03-15")
        assertNotNull(result)
        assertEquals(3, result!!.month)
        assertEquals(15, result.day)
        assertNull(result.year)
    }

    @Test
    fun `parseBirthday - ISO format YYYY-MM-DD`() {
        val result = ContactBirthdayUtils.parseBirthday("1990-07-04")
        assertNotNull(result)
        assertEquals(7, result!!.month)
        assertEquals(4, result.day)
        assertEquals(1990, result.year)
    }

    @Test
    fun `parseBirthday - slash format YYYY slash MM slash DD`() {
        val result = ContactBirthdayUtils.parseBirthday("1985/12/25")
        assertNotNull(result)
        assertEquals(12, result!!.month)
        assertEquals(25, result.day)
        assertEquals(1985, result.year)
    }

    @Test
    fun `parseBirthday - US format MM slash DD slash YYYY`() {
        val result = ContactBirthdayUtils.parseBirthday("07/04/1990")
        assertNotNull(result)
        assertEquals(7, result!!.month)
        assertEquals(4, result.day)
        assertEquals(1990, result.year)
    }

    @Test
    fun `parseBirthday - US format with dashes MM-DD-YYYY`() {
        val result = ContactBirthdayUtils.parseBirthday("12-25-2000")
        assertNotNull(result)
        assertEquals(12, result!!.month)
        assertEquals(25, result.day)
        assertEquals(2000, result.year)
    }

    @Test
    fun `parseBirthday - leap year Feb 29 with valid year`() {
        val result = ContactBirthdayUtils.parseBirthday("2000-02-29")
        assertNotNull(result)
        assertEquals(2, result!!.month)
        assertEquals(29, result.day)
        assertEquals(2000, result.year)
    }

    @Test
    fun `parseBirthday - Feb 29 on non-leap year returns null`() {
        val result = ContactBirthdayUtils.parseBirthday("2001-02-29")
        assertNull(result)
    }

    @Test
    fun `parseBirthday - null input returns null`() {
        assertNull(ContactBirthdayUtils.parseBirthday(null))
    }

    @Test
    fun `parseBirthday - blank input returns null`() {
        assertNull(ContactBirthdayUtils.parseBirthday("   "))
    }

    @Test
    fun `parseBirthday - invalid month returns null`() {
        assertNull(ContactBirthdayUtils.parseBirthday("2000-13-01"))
    }

    @Test
    fun `parseBirthday - invalid day returns null`() {
        assertNull(ContactBirthdayUtils.parseBirthday("2000-04-31"))
    }

    @Test
    fun `parseBirthday - RFC 6350 invalid month-day returns null`() {
        assertNull(ContactBirthdayUtils.parseBirthday("--13-05"))
    }

    @Test
    fun `parseBirthday - year out of range returns null`() {
        assertNull(ContactBirthdayUtils.parseBirthday("1899-06-15"))
        assertNull(ContactBirthdayUtils.parseBirthday("2101-06-15"))
    }

    // ---------------------------------------------------------------
    // formatOrdinal
    // ---------------------------------------------------------------

    @Test
    fun `formatOrdinal - standard suffixes`() {
        assertEquals("1st", ContactBirthdayUtils.formatOrdinal(1))
        assertEquals("2nd", ContactBirthdayUtils.formatOrdinal(2))
        assertEquals("3rd", ContactBirthdayUtils.formatOrdinal(3))
        assertEquals("4th", ContactBirthdayUtils.formatOrdinal(4))
        assertEquals("10th", ContactBirthdayUtils.formatOrdinal(10))
    }

    @Test
    fun `formatOrdinal - teen numbers are all th`() {
        assertEquals("11th", ContactBirthdayUtils.formatOrdinal(11))
        assertEquals("12th", ContactBirthdayUtils.formatOrdinal(12))
        assertEquals("13th", ContactBirthdayUtils.formatOrdinal(13))
    }

    @Test
    fun `formatOrdinal - 21st 22nd 23rd`() {
        assertEquals("21st", ContactBirthdayUtils.formatOrdinal(21))
        assertEquals("22nd", ContactBirthdayUtils.formatOrdinal(22))
        assertEquals("23rd", ContactBirthdayUtils.formatOrdinal(23))
    }

    @Test
    fun `formatOrdinal - 111th 112th 113th are teens`() {
        assertEquals("111th", ContactBirthdayUtils.formatOrdinal(111))
        assertEquals("112th", ContactBirthdayUtils.formatOrdinal(112))
        assertEquals("113th", ContactBirthdayUtils.formatOrdinal(113))
    }

    // ---------------------------------------------------------------
    // calculateAge
    // ---------------------------------------------------------------

    @Test
    fun `calculateAge - simple subtraction`() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.clear()
        cal.set(2025, Calendar.JULY, 4)
        val age = ContactBirthdayUtils.calculateAge(1990, cal.timeInMillis)
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
        val title = ContactBirthdayUtils.formatBirthdayTitle("Alice", 1990, cal.timeInMillis)
        assertEquals("Alice's 30th Birthday", title)
    }

    @Test
    fun `formatBirthdayTitle - without birth year shows plain title`() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.clear()
        cal.set(2025, Calendar.JUNE, 1)
        val title = ContactBirthdayUtils.formatBirthdayTitle("Bob", null, cal.timeInMillis)
        assertEquals("Bob's Birthday", title)
    }

    @Test
    fun `formatBirthdayTitle - age 0 falls back to plain title`() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.clear()
        cal.set(2000, Calendar.JANUARY, 1)
        val title = ContactBirthdayUtils.formatBirthdayTitle("Baby", 2000, cal.timeInMillis)
        assertEquals("Baby's Birthday", title)
    }

    @Test
    fun `formatBirthdayTitle - age 150 or above falls back to plain title`() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.clear()
        cal.set(2025, Calendar.JANUARY, 1)
        val title = ContactBirthdayUtils.formatBirthdayTitle("Ancient", 1850, cal.timeInMillis)
        assertEquals("Ancient's Birthday", title)
    }

    @Test
    fun `formatBirthdayTitle - age 149 is valid`() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.clear()
        cal.set(2049, Calendar.JANUARY, 1)
        val title = ContactBirthdayUtils.formatBirthdayTitle("Old", 1900, cal.timeInMillis)
        assertEquals("Old's 149th Birthday", title)
    }

    // ---------------------------------------------------------------
    // encodeBirthYear / decodeBirthYear
    // ---------------------------------------------------------------

    @Test
    fun `encodeBirthYear - encodes year with prefix`() {
        assertEquals("birthYear:1990", ContactBirthdayUtils.encodeBirthYear(1990))
    }

    @Test
    fun `encodeBirthYear - null returns null`() {
        assertNull(ContactBirthdayUtils.encodeBirthYear(null))
    }

    @Test
    fun `decodeBirthYear - decodes from encoded string`() {
        assertEquals(1990, ContactBirthdayUtils.decodeBirthYear("birthYear:1990"))
    }

    @Test
    fun `decodeBirthYear - null returns null`() {
        assertNull(ContactBirthdayUtils.decodeBirthYear(null))
    }

    @Test
    fun `decodeBirthYear - string without prefix returns null`() {
        assertNull(ContactBirthdayUtils.decodeBirthYear("some random text"))
    }

    @Test
    fun `encodeBirthYear and decodeBirthYear round-trip`() {
        val year = 2005
        val encoded = ContactBirthdayUtils.encodeBirthYear(year)
        val decoded = ContactBirthdayUtils.decodeBirthYear(encoded)
        assertEquals(year, decoded)
    }

    @Test
    fun `decodeBirthYear - extracts year from embedded text`() {
        val description = "Contact birthday\nbirthYear:1985\nSome other notes"
        assertEquals(1985, ContactBirthdayUtils.decodeBirthYear(description))
    }

    // ---------------------------------------------------------------
    // generateBirthdayRRule
    // ---------------------------------------------------------------

    @Test
    fun `generateBirthdayRRule - returns yearly rule`() {
        assertEquals("FREQ=YEARLY;INTERVAL=1", ContactBirthdayUtils.generateBirthdayRRule())
    }

    // ---------------------------------------------------------------
    // getBirthdayTimestamp
    // ---------------------------------------------------------------

    @Test
    fun `getBirthdayTimestamp - returns UTC midnight`() {
        val ts = ContactBirthdayUtils.getBirthdayTimestamp(3, 15, 2025)

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
    // getNextBirthdayTimestamp
    // ---------------------------------------------------------------

    @Test
    fun `getNextBirthdayTimestamp - past month returns next year`() {
        // January is always past if we are in February or later
        val ts = ContactBirthdayUtils.getNextBirthdayTimestamp(1, 10)

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ts

        val now = Calendar.getInstance()
        val expectedYear = now.get(Calendar.YEAR) + 1

        assertEquals(expectedYear, cal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, cal.get(Calendar.MONTH))
        assertEquals(10, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `getNextBirthdayTimestamp - future month returns current year`() {
        // December is always future if we are in February
        val ts = ContactBirthdayUtils.getNextBirthdayTimestamp(12, 25)

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ts

        val now = Calendar.getInstance()
        val expectedYear = now.get(Calendar.YEAR)

        assertEquals(expectedYear, cal.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, cal.get(Calendar.MONTH))
        assertEquals(25, cal.get(Calendar.DAY_OF_MONTH))
    }
}
