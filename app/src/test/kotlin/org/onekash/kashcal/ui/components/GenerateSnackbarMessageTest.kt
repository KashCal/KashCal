package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.SyncChange

/**
 * Unit tests for generateSnackbarMessage().
 *
 * Tests all message generation scenarios:
 * - Empty list
 * - Single new event (shows title)
 * - Multiple new events
 * - Single update
 * - Multiple updates
 * - Single deletion
 * - Multiple deletions
 * - Mixed changes
 * - Long title truncation
 * - Special characters in title
 */
class GenerateSnackbarMessageTest {

    private fun createChange(
        type: ChangeType,
        title: String = "Test Event",
        eventId: Long? = 1L,
        isAllDay: Boolean = false,
        isRecurring: Boolean = false
    ) = SyncChange(
        type = type,
        eventId = eventId,
        eventTitle = title,
        eventStartTs = System.currentTimeMillis(),
        isAllDay = isAllDay,
        isRecurring = isRecurring,
        calendarName = "Test Calendar",
        calendarColor = 0xFF2196F3.toInt()
    )

    @Test
    fun `empty list returns null`() {
        val result = generateSnackbarMessage(emptyList())
        assertNull(result)
    }

    @Test
    fun `single new event shows title`() {
        val changes = listOf(createChange(ChangeType.NEW, "Team Meeting"))
        val result = generateSnackbarMessage(changes)
        assertEquals("New event: Team Meeting", result)
    }

    @Test
    fun `single new event truncates long title`() {
        val longTitle = "This is a very long event title that should be truncated"
        val changes = listOf(createChange(ChangeType.NEW, longTitle))
        val result = generateSnackbarMessage(changes)
        assertEquals("New event: This is a very long event titl...", result)
    }

    @Test
    fun `single new event with exactly 30 chars shows no ellipsis`() {
        val title = "Exactly thirty characters long" // 30 chars
        val changes = listOf(createChange(ChangeType.NEW, title))
        val result = generateSnackbarMessage(changes)
        assertEquals("New event: Exactly thirty characters long", result)
    }

    @Test
    fun `multiple new events shows count`() {
        val changes = listOf(
            createChange(ChangeType.NEW, "Event 1"),
            createChange(ChangeType.NEW, "Event 2"),
            createChange(ChangeType.NEW, "Event 3")
        )
        val result = generateSnackbarMessage(changes)
        assertEquals("3 new events", result)
    }

    @Test
    fun `single update shows singular`() {
        val changes = listOf(createChange(ChangeType.MODIFIED, "Updated Event"))
        val result = generateSnackbarMessage(changes)
        assertEquals("1 event updated", result)
    }

    @Test
    fun `multiple updates shows count`() {
        val changes = listOf(
            createChange(ChangeType.MODIFIED, "Event 1"),
            createChange(ChangeType.MODIFIED, "Event 2"),
            createChange(ChangeType.MODIFIED, "Event 3"),
            createChange(ChangeType.MODIFIED, "Event 4"),
            createChange(ChangeType.MODIFIED, "Event 5")
        )
        val result = generateSnackbarMessage(changes)
        assertEquals("5 events updated", result)
    }

    @Test
    fun `single deletion shows singular`() {
        val changes = listOf(createChange(ChangeType.DELETED, "Deleted Event", eventId = null))
        val result = generateSnackbarMessage(changes)
        assertEquals("1 event removed", result)
    }

    @Test
    fun `multiple deletions shows count`() {
        val changes = listOf(
            createChange(ChangeType.DELETED, "Event 1", eventId = null),
            createChange(ChangeType.DELETED, "Event 2", eventId = null),
            createChange(ChangeType.DELETED, "Event 3", eventId = null)
        )
        val result = generateSnackbarMessage(changes)
        assertEquals("3 events removed", result)
    }

    @Test
    fun `mixed changes shows total count`() {
        val changes = listOf(
            createChange(ChangeType.NEW, "New Event"),
            createChange(ChangeType.MODIFIED, "Modified Event"),
            createChange(ChangeType.DELETED, "Deleted Event", eventId = null)
        )
        val result = generateSnackbarMessage(changes)
        assertEquals("3 calendar updates", result)
    }

    @Test
    fun `new and modified mixed shows total count`() {
        val changes = listOf(
            createChange(ChangeType.NEW, "New Event 1"),
            createChange(ChangeType.NEW, "New Event 2"),
            createChange(ChangeType.MODIFIED, "Modified Event")
        )
        val result = generateSnackbarMessage(changes)
        assertEquals("3 calendar updates", result)
    }

    @Test
    fun `modified and deleted mixed shows total count`() {
        val changes = listOf(
            createChange(ChangeType.MODIFIED, "Modified Event"),
            createChange(ChangeType.DELETED, "Deleted Event", eventId = null)
        )
        val result = generateSnackbarMessage(changes)
        assertEquals("2 calendar updates", result)
    }

    @Test
    fun `title with special characters preserved`() {
        val title = "Meeting & Discussion @ 3pm"
        val changes = listOf(createChange(ChangeType.NEW, title))
        val result = generateSnackbarMessage(changes)
        assertEquals("New event: Meeting & Discussion @ 3pm", result)
    }

    @Test
    fun `title with unicode characters preserved`() {
        val title = "Coffee ☕ with team"
        val changes = listOf(createChange(ChangeType.NEW, title))
        val result = generateSnackbarMessage(changes)
        assertEquals("New event: Coffee ☕ with team", result)
    }

    @Test
    fun `two new events shows count not title`() {
        val changes = listOf(
            createChange(ChangeType.NEW, "Event 1"),
            createChange(ChangeType.NEW, "Event 2")
        )
        val result = generateSnackbarMessage(changes)
        assertEquals("2 new events", result)
    }
}
