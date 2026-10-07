package org.onekash.kashcal.ui.components

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.changes.RecentChangeEntry
import org.onekash.kashcal.sync.model.ChangeType
import org.robolectric.RobolectricTestRunner

/**
 * Tests [generateSnackbarMessage] over the recorded Recent changes entries, with the app's
 * English resources: null for none, the title for one new event (cut at 30 characters, special
 * and emoji characters kept), a count for more than one of a kind, singular for one update or
 * deletion, cancelled occurrences told apart from removed events, and a total for any mix.
 */
@RunWith(RobolectricTestRunner::class)
class GenerateSnackbarMessageTest {

    private val resources: Resources =
        ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private fun generateSnackbarMessage(changes: List<RecentChangeEntry>) =
        generateSnackbarMessage(changes, resources)

    private fun createChange(
        type: ChangeType,
        title: String = "Test Event",
        eventId: Long? = 1L,
        isAllDay: Boolean = false,
        isRecurring: Boolean = false,
        instanceTs: Long = 0
    ) = RecentChangeEntry(
        calendarId = 1L,
        eventUid = title,
        instanceTs = instanceTs,
        eventId = eventId,
        type = type,
        title = title,
        startTs = System.currentTimeMillis(),
        endTs = System.currentTimeMillis(),
        isAllDay = isAllDay,
        isRecurring = isRecurring,
        changedFields = emptySet(),
        previousStartTs = null,
        previousIsAllDay = null
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

    private fun cancelled(title: String = "Standup", instance: Long = 1_800_000_000_000L) =
        createChange(ChangeType.DELETED, title, eventId = null, isRecurring = true, instanceTs = instance)

    @Test
    fun `one cancelled occurrence says cancelled, as the sheet does`() {
        assertEquals("1 occurrence cancelled", generateSnackbarMessage(listOf(cancelled())))
    }

    @Test
    fun `several cancelled occurrences show a count`() {
        val changes = listOf(cancelled(instance = 1L), cancelled(instance = 2L), cancelled("Gym", 3L))
        assertEquals("3 occurrences cancelled", generateSnackbarMessage(changes))
    }

    @Test
    fun `cancellations with removed events show the total`() {
        val changes = listOf(cancelled(), createChange(ChangeType.DELETED, "Old event", eventId = null))
        assertEquals("2 calendar updates", generateSnackbarMessage(changes))
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
