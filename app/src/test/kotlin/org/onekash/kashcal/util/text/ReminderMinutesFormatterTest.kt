package org.onekash.kashcal.util.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for formatRemindersFromMinutes().
 */
class ReminderMinutesFormatterTest {

    @Test
    fun `formatRemindersFromMinutes with single reminder returns formatted string`() {
        val result = formatRemindersFromMinutes(listOf(15))
        assertEquals("15 min before", result)
    }

    @Test
    fun `formatRemindersFromMinutes with 15 returns 15 min before`() {
        val result = formatRemindersFromMinutes(listOf(15))
        assertEquals("15 min before", result)
    }

    @Test
    fun `formatRemindersFromMinutes with 60 returns 1 hour before`() {
        val result = formatRemindersFromMinutes(listOf(60))
        assertEquals("1 hour before", result)
    }

    @Test
    fun `formatRemindersFromMinutes with 120 returns 2 hours before`() {
        val result = formatRemindersFromMinutes(listOf(120))
        assertEquals("2 hours before", result)
    }

    @Test
    fun `formatRemindersFromMinutes with 1440 returns 1 day before`() {
        val result = formatRemindersFromMinutes(listOf(1440))
        assertEquals("1 day before", result)
    }

    @Test
    fun `formatRemindersFromMinutes with 2880 returns 2 days before`() {
        val result = formatRemindersFromMinutes(listOf(2880))
        assertEquals("2 days before", result)
    }

    @Test
    fun `formatRemindersFromMinutes with multiple returns comma-separated sorted`() {
        val result = formatRemindersFromMinutes(listOf(60, 15, 1440))
        assertEquals("15 min before, 1 hour before, 1 day before", result)
    }

    @Test
    fun `formatRemindersFromMinutes with empty list returns null`() {
        val result = formatRemindersFromMinutes(emptyList())
        assertNull(result)
    }

    @Test
    fun `formatRemindersFromMinutes with 0 returns At time of event`() {
        val result = formatRemindersFromMinutes(listOf(0))
        assertEquals("At time of event", result)
    }

    @Test
    fun `formatRemindersFromMinutes with 1 minute returns 1 min before`() {
        val result = formatRemindersFromMinutes(listOf(1))
        assertEquals("1 min before", result)
    }

    @Test
    fun `formatRemindersFromMinutes with 90 returns 1 hour 30 min before`() {
        val result = formatRemindersFromMinutes(listOf(90))
        assertEquals("1 hour 30 min before", result)
    }

    @Test
    fun `formatRemindersFromMinutes with negative value coerces to 0`() {
        val result = formatRemindersFromMinutes(listOf(-15))
        assertEquals("At time of event", result)
    }
}
