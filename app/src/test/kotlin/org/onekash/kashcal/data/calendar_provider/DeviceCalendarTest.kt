package org.onekash.kashcal.data.calendar_provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for DeviceCalendar data class.
 *
 * Tests the computed `isWritable` property which gates write operations.
 * Access level constants from CalendarContract.Calendars:
 * - CAL_ACCESS_NONE = 0
 * - CAL_ACCESS_FREEBUSY = 100
 * - CAL_ACCESS_READ = 200
 * - CAL_ACCESS_RESPOND = 300
 * - CAL_ACCESS_OVERRIDE = 400
 * - CAL_ACCESS_CONTRIBUTOR = 500
 * - CAL_ACCESS_EDITOR = 600
 * - CAL_ACCESS_OWNER = 700
 * - CAL_ACCESS_ROOT = 800
 */
class DeviceCalendarTest {

    private fun calendar(accessLevel: Int) = DeviceCalendar(
        id = 1L,
        displayName = "Test",
        color = 0xFF0000.toInt(),
        accountName = "test@example.com",
        accountType = "com.test",
        visible = true,
        accessLevel = accessLevel
    )

    // ========== isWritable Tests ==========

    @Test
    fun `CAL_ACCESS_NONE is not writable`() {
        assertFalse(calendar(accessLevel = 0).isWritable)
    }

    @Test
    fun `CAL_ACCESS_FREEBUSY is not writable`() {
        assertFalse(calendar(accessLevel = 100).isWritable)
    }

    @Test
    fun `CAL_ACCESS_READ is not writable`() {
        assertFalse(calendar(accessLevel = 200).isWritable)
    }

    @Test
    fun `CAL_ACCESS_RESPOND is not writable`() {
        assertFalse(calendar(accessLevel = 300).isWritable)
    }

    @Test
    fun `CAL_ACCESS_OVERRIDE is not writable`() {
        assertFalse(calendar(accessLevel = 400).isWritable)
    }

    @Test
    fun `CAL_ACCESS_CONTRIBUTOR is writable`() {
        assertTrue(calendar(accessLevel = 500).isWritable)
    }

    @Test
    fun `CAL_ACCESS_EDITOR is writable`() {
        assertTrue(calendar(accessLevel = 600).isWritable)
    }

    @Test
    fun `CAL_ACCESS_OWNER is writable`() {
        assertTrue(calendar(accessLevel = 700).isWritable)
    }

    @Test
    fun `CAL_ACCESS_ROOT is writable`() {
        assertTrue(calendar(accessLevel = 800).isWritable)
    }

    @Test
    fun `boundary - access level 499 is not writable`() {
        assertFalse(calendar(accessLevel = 499).isWritable)
    }

    @Test
    fun `boundary - access level 500 is writable`() {
        assertTrue(calendar(accessLevel = 500).isWritable)
    }
}
