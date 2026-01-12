package org.onekash.kashcal.sync.client

import org.junit.Assert.*
import org.junit.Test
import org.onekash.kashcal.sync.client.model.*

/**
 * Tests for CalDAV model classes.
 */
class CalDavModelsTest {

    // CalDavCalendar tests

    @Test
    fun `CalDavCalendar stores all properties`() {
        val calendar = CalDavCalendar(
            href = "/user/calendars/work/",
            url = "https://caldav.example.com/user/calendars/work/",
            displayName = "Work",
            color = "#FF5733",
            ctag = "abc123",
            isReadOnly = false
        )

        assertEquals("/user/calendars/work/", calendar.href)
        assertEquals("https://caldav.example.com/user/calendars/work/", calendar.url)
        assertEquals("Work", calendar.displayName)
        assertEquals("#FF5733", calendar.color)
        assertEquals("abc123", calendar.ctag)
        assertFalse(calendar.isReadOnly)
    }

    @Test
    fun `CalDavCalendar color can be null`() {
        val calendar = CalDavCalendar(
            href = "/cal/",
            url = "https://example.com/cal/",
            displayName = "Default",
            color = null,
            ctag = null
        )

        assertNull(calendar.color)
        assertNull(calendar.ctag)
    }

    @Test
    fun `CalDavCalendar isReadOnly defaults to false`() {
        val calendar = CalDavCalendar(
            href = "/cal/",
            url = "https://example.com/cal/",
            displayName = "Default",
            color = null,
            ctag = null
        )

        assertFalse(calendar.isReadOnly)
    }

    // CalDavEvent tests

    @Test
    fun `CalDavEvent stores all properties`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:test-123
            SUMMARY:Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event = CalDavEvent(
            href = "/user/calendars/work/event1.ics",
            url = "https://caldav.example.com/user/calendars/work/event1.ics",
            etag = "etag-abc",
            icalData = icalData
        )

        assertEquals("/user/calendars/work/event1.ics", event.href)
        assertEquals("https://caldav.example.com/user/calendars/work/event1.ics", event.url)
        assertEquals("etag-abc", event.etag)
        assertTrue(event.icalData.contains("UID:test-123"))
    }

    @Test
    fun `CalDavEvent etag can be null`() {
        val event = CalDavEvent(
            href = "/event.ics",
            url = "https://example.com/event.ics",
            etag = null,
            icalData = "BEGIN:VCALENDAR\nEND:VCALENDAR"
        )

        assertNull(event.etag)
    }

    // SyncReport tests

    @Test
    fun `SyncReport stores sync token and changes`() {
        val changed = listOf(
            SyncItem("/event1.ics", "etag1", SyncItemStatus.OK),
            SyncItem("/event2.ics", "etag2", SyncItemStatus.OK)
        )
        val deleted = listOf("/event3.ics")

        val report = SyncReport(
            syncToken = "sync-token-123",
            changed = changed,
            deleted = deleted
        )

        assertEquals("sync-token-123", report.syncToken)
        assertEquals(2, report.changed.size)
        assertEquals(1, report.deleted.size)
        assertEquals("/event3.ics", report.deleted[0])
    }

    @Test
    fun `SyncReport syncToken can be null`() {
        val report = SyncReport(
            syncToken = null,
            changed = emptyList(),
            deleted = emptyList()
        )

        assertNull(report.syncToken)
    }

    // SyncItem tests

    @Test
    fun `SyncItem stores href and etag`() {
        val item = SyncItem(
            href = "/calendars/work/meeting.ics",
            etag = "abc123",
            status = SyncItemStatus.OK
        )

        assertEquals("/calendars/work/meeting.ics", item.href)
        assertEquals("abc123", item.etag)
        assertEquals(SyncItemStatus.OK, item.status)
    }

    @Test
    fun `SyncItem etag can be null`() {
        val item = SyncItem(
            href = "/event.ics",
            etag = null,
            status = SyncItemStatus.NOT_FOUND
        )

        assertNull(item.etag)
    }

    // SyncItemStatus tests

    @Test
    fun `SyncItemStatus has expected values`() {
        assertEquals(3, SyncItemStatus.entries.size)
        assertTrue(SyncItemStatus.entries.contains(SyncItemStatus.OK))
        assertTrue(SyncItemStatus.entries.contains(SyncItemStatus.NOT_FOUND))
        assertTrue(SyncItemStatus.entries.contains(SyncItemStatus.ERROR))
    }

    // Data class equality tests

    @Test
    fun `CalDavCalendar equals works correctly`() {
        val cal1 = CalDavCalendar("/cal/", "https://ex.com/cal/", "Cal", "#FFF", "ctag")
        val cal2 = CalDavCalendar("/cal/", "https://ex.com/cal/", "Cal", "#FFF", "ctag")
        val cal3 = CalDavCalendar("/other/", "https://ex.com/other/", "Other", "#000", "ctag2")

        assertEquals(cal1, cal2)
        assertNotEquals(cal1, cal3)
    }

    @Test
    fun `CalDavEvent equals works correctly`() {
        val event1 = CalDavEvent("/e.ics", "https://ex.com/e.ics", "etag", "ical")
        val event2 = CalDavEvent("/e.ics", "https://ex.com/e.ics", "etag", "ical")
        val event3 = CalDavEvent("/other.ics", "https://ex.com/other.ics", "etag2", "ical2")

        assertEquals(event1, event2)
        assertNotEquals(event1, event3)
    }

    @Test
    fun `SyncItem equals works correctly`() {
        val item1 = SyncItem("/e.ics", "etag", SyncItemStatus.OK)
        val item2 = SyncItem("/e.ics", "etag", SyncItemStatus.OK)
        val item3 = SyncItem("/e.ics", "etag", SyncItemStatus.ERROR)

        assertEquals(item1, item2)
        assertNotEquals(item1, item3)
    }

    // Copy tests

    @Test
    fun `CalDavCalendar copy works correctly`() {
        val original = CalDavCalendar("/cal/", "https://ex.com/cal/", "Cal", "#FFF", "ctag")
        val copied = original.copy(displayName = "New Name")

        assertEquals("New Name", copied.displayName)
        assertEquals(original.href, copied.href)
        assertEquals(original.url, copied.url)
    }

    @Test
    fun `CalDavEvent copy works correctly`() {
        val original = CalDavEvent("/e.ics", "https://ex.com/e.ics", "etag", "ical")
        val copied = original.copy(etag = "new-etag")

        assertEquals("new-etag", copied.etag)
        assertEquals(original.href, copied.href)
        assertEquals(original.icalData, copied.icalData)
    }
}
