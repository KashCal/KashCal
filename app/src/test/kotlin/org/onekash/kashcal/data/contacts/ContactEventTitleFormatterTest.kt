package org.onekash.kashcal.data.contacts

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus

class ContactEventTitleFormatterTest {

    private fun createEvent(
        title: String = "Alice",
        caldavUrl: String? = null,
        description: String? = null
    ) = Event(
        id = 1,
        uid = "test-uid",
        calendarId = 1,
        title = title,
        startTs = 1700000000000,
        endTs = 1700003600000,
        isAllDay = false,
        rrule = "FREQ=YEARLY;INTERVAL=1",
        dtstamp = 1700000000000,
        syncStatus = SyncStatus.SYNCED,
        caldavUrl = caldavUrl,
        description = description
    )

    // An occurrence timestamp in 2024 for testing
    // Use a fixed timestamp: Jan 15, 2024 12:00 UTC = 1705320000000
    private val occurrenceTs2024 = 1705320000000L

    @Test
    fun `birthday event with year formats correctly`() {
        val event = createEvent(
            title = "Alice",
            caldavUrl = "contact_birthday:abc123",
            description = "birthYear:1994"
        )
        val result = ContactEventTitleFormatter.format(event, occurrenceTs2024)
        assertEquals("Alice's 30th Birthday", result)
    }

    @Test
    fun `birthday event without year shows plain title`() {
        val event = createEvent(
            title = "Bob",
            caldavUrl = "contact_birthday:def456",
            description = null
        )
        val result = ContactEventTitleFormatter.format(event, occurrenceTs2024)
        assertEquals("Bob's Birthday", result)
    }

    @Test
    fun `anniversary event with year shows ordinal anniversary`() {
        val event = createEvent(
            title = "Alice",
            caldavUrl = "contact_anniversary:abc123",
            description = "birthYear:2014"
        )
        val result = ContactEventTitleFormatter.format(event, occurrenceTs2024)
        assertEquals("Alice's 10th Anniversary", result)
    }

    @Test
    fun `anniversary event without year shows plain title`() {
        val event = createEvent(
            title = "Alice",
            caldavUrl = "contact_anniversary:abc123",
            description = null
        )
        val result = ContactEventTitleFormatter.format(event, occurrenceTs2024)
        assertEquals("Alice's Anniversary", result)
    }

    @Test
    fun `non-contact event returns original title unchanged`() {
        val event = createEvent(
            title = "Team Meeting",
            caldavUrl = "https://caldav.example.com/event1.ics",
            description = "Weekly standup"
        )
        val result = ContactEventTitleFormatter.format(event, occurrenceTs2024)
        assertEquals("Team Meeting", result)
    }

    @Test
    fun `null occurrenceTs returns original title`() {
        val event = createEvent(
            title = "Alice",
            caldavUrl = "contact_birthday:abc123",
            description = "birthYear:1994"
        )
        val result = ContactEventTitleFormatter.format(event, null)
        assertEquals("Alice", result)
    }
}
