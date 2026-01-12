package org.onekash.kashcal.ui.components.pickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Calendar

/**
 * Unit tests for CalendarPicker component.
 * Tests calendar data structures and selection logic.
 */
class CalendarPickerTest {

    // ==================== Calendar Entity Tests ====================

    @Test
    fun `Calendar entity stores all fields`() {
        val calendar = Calendar(
            id = 1L,
            accountId = 100L,
            caldavUrl = "https://caldav.example.com/calendar",
            displayName = "Work",
            color = 0xFF0000FF.toInt(),
            ctag = "etag123",
            syncToken = "token456",
            isVisible = true
        )

        assertEquals(1L, calendar.id)
        assertEquals(100L, calendar.accountId)
        assertEquals("Work", calendar.displayName)
        assertEquals(0xFF0000FF.toInt(), calendar.color)
        assertEquals("https://caldav.example.com/calendar", calendar.caldavUrl)
        assertEquals("etag123", calendar.ctag)
        assertEquals("token456", calendar.syncToken)
        assertTrue(calendar.isVisible)
    }

    @Test
    fun `Calendar entity default visibility is true`() {
        val calendar = Calendar(
            id = 1L,
            accountId = 100L,
            caldavUrl = "https://caldav.example.com/calendar",
            displayName = "Test",
            color = 0xFF00FF00.toInt()
        )

        assertTrue(calendar.isVisible)
    }

    @Test
    fun `Calendar entity can have null optional fields`() {
        val calendar = Calendar(
            id = 1L,
            accountId = 100L,
            caldavUrl = "https://caldav.example.com/local",
            displayName = "Local",
            color = 0xFFFF0000.toInt(),
            ctag = null,
            syncToken = null,
            isVisible = true
        )

        assertEquals(null, calendar.ctag)
        assertEquals(null, calendar.syncToken)
    }

    // ==================== Calendar Selection Tests ====================

    @Test
    fun `calendar selection finds calendar by id`() {
        val calendars = listOf(
            createTestCalendar(1L, "Work"),
            createTestCalendar(2L, "Personal"),
            createTestCalendar(3L, "Family")
        )

        val selected = calendars.find { it.id == 2L }

        assertNotNull(selected)
        assertEquals("Personal", selected?.displayName)
    }

    @Test
    fun `calendar selection returns null for unknown id`() {
        val calendars = listOf(
            createTestCalendar(1L, "Work"),
            createTestCalendar(2L, "Personal")
        )

        val selected = calendars.find { it.id == 999L }

        assertEquals(null, selected)
    }

    @Test
    fun `empty calendar list is handled`() {
        val calendars = emptyList<Calendar>()

        assertTrue(calendars.isEmpty())
        assertEquals(null, calendars.firstOrNull())
    }

    // ==================== Color Tests ====================

    @Test
    fun `calendar color is stored as Int`() {
        val blueColor = 0xFF0000FF.toInt()
        val calendar = createTestCalendar(1L, "Blue Calendar", blueColor)

        assertEquals(blueColor, calendar.color)
    }

    @Test
    fun `different calendar colors are distinguishable`() {
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        val blue = 0xFF0000FF.toInt()

        val calendars = listOf(
            createTestCalendar(1L, "Red", red),
            createTestCalendar(2L, "Green", green),
            createTestCalendar(3L, "Blue", blue)
        )

        assertEquals(red, calendars[0].color)
        assertEquals(green, calendars[1].color)
        assertEquals(blue, calendars[2].color)
        assertFalse(calendars[0].color == calendars[1].color)
    }

    // ==================== Display Name Tests ====================

    @Test
    fun `display name is correctly stored`() {
        val calendar = createTestCalendar(1L, "My Important Calendar")
        assertEquals("My Important Calendar", calendar.displayName)
    }

    @Test
    fun `display name can contain special characters`() {
        val calendar = createTestCalendar(1L, "Work (Main) - 2024")
        assertEquals("Work (Main) - 2024", calendar.displayName)
    }

    @Test
    fun `display name can be empty string`() {
        val calendar = createTestCalendar(1L, "")
        assertEquals("", calendar.displayName)
    }

    // ==================== Account Association Tests ====================

    @Test
    fun `calendars can be filtered by account`() {
        val calendars = listOf(
            createTestCalendar(1L, "Work", accountId = 100L),
            createTestCalendar(2L, "Personal", accountId = 100L),
            createTestCalendar(3L, "Other", accountId = 200L)
        )

        val account100Calendars = calendars.filter { it.accountId == 100L }

        assertEquals(2, account100Calendars.size)
        assertTrue(account100Calendars.all { it.accountId == 100L })
    }

    // ==================== Visibility Filter Tests ====================

    @Test
    fun `visible calendars can be filtered`() {
        val calendars = listOf(
            Calendar(1L, 100L, "https://cal.example.com/1", "Visible", 0xFF0000FF.toInt(), null, null, true),
            Calendar(2L, 100L, "https://cal.example.com/2", "Hidden", 0xFF00FF00.toInt(), null, null, false),
            Calendar(3L, 100L, "https://cal.example.com/3", "Also Visible", 0xFFFF0000.toInt(), null, null, true)
        )

        val visibleCalendars = calendars.filter { it.isVisible }

        assertEquals(2, visibleCalendars.size)
        assertTrue(visibleCalendars.none { it.displayName == "Hidden" })
    }

    // ==================== Helper Functions ====================

    private fun createTestCalendar(
        id: Long,
        displayName: String,
        color: Int = 0xFF0000FF.toInt(),
        accountId: Long = 100L
    ): Calendar {
        return Calendar(
            id = id,
            accountId = accountId,
            caldavUrl = "https://caldav.example.com/calendar/$id",
            displayName = displayName,
            color = color,
            ctag = null,
            syncToken = null,
            isVisible = true
        )
    }
}
