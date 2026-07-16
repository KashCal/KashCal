package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderConverterTest {

    @Test
    fun `null input returns empty list`() {
        assertEquals(emptyList<Int>(), isoRemindersToMinutes(null))
    }

    @Test
    fun `empty list returns empty list`() {
        assertEquals(emptyList<Int>(), isoRemindersToMinutes(emptyList()))
    }

    @Test
    fun `PT15M converts to 15 minutes`() {
        assertEquals(listOf(15), isoRemindersToMinutes(listOf("-PT15M")))
    }

    @Test
    fun `PT1H converts to 60 minutes`() {
        assertEquals(listOf(60), isoRemindersToMinutes(listOf("-PT1H")))
    }

    @Test
    fun `P1D converts to 1440 minutes`() {
        assertEquals(listOf(1440), isoRemindersToMinutes(listOf("-P1D")))
    }

    @Test
    fun `P1W converts to 10080 minutes`() {
        assertEquals(listOf(10080), isoRemindersToMinutes(listOf("-P1W")))
    }

    @Test
    fun `PT0M converts to 0 minutes`() {
        assertEquals(listOf(0), isoRemindersToMinutes(listOf("-PT0M")))
    }

    @Test
    fun `invalid entries are skipped`() {
        assertEquals(emptyList<Int>(), isoRemindersToMinutes(listOf("invalid", "", "xyz")))
    }

    @Test
    fun `mixed valid and invalid entries`() {
        assertEquals(listOf(15, 60), isoRemindersToMinutes(listOf("-PT15M", "bad", "-PT1H")))
    }

    @Test
    fun `duplicates are removed`() {
        assertEquals(listOf(15), isoRemindersToMinutes(listOf("-PT15M", "-PT15M")))
    }

    @Test
    fun `result is sorted ascending`() {
        assertEquals(listOf(15, 60, 1440), isoRemindersToMinutes(listOf("-PT1H", "-P1D", "-PT15M")))
    }

    @Test
    fun `combined duration P1DT2H30M converts to 1590 minutes`() {
        assertEquals(listOf(1590), isoRemindersToMinutes(listOf("-P1DT2H30M")))
    }

    @Test
    fun `positive duration without minus sign also works`() {
        assertEquals(listOf(15), isoRemindersToMinutes(listOf("PT15M")))
    }

    @Test
    fun `overflowing reminder duration is skipped, not crashed on`() {
        // isoRemindersToMinutes does NOT wrap parseIsoDuration in try/catch, so an
        // overflowing value must return null internally (be dropped) rather than
        // throw an uncaught ArithmeticException. Valid entries still come through.
        assertEquals(
            listOf(15),
            isoRemindersToMinutes(listOf("-P999999999999W", "-PT15M"))
        )
    }
}
