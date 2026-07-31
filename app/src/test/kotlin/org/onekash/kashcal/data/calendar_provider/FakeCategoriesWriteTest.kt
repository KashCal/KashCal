package org.onekash.kashcal.data.calendar_provider

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents the categories write contract [AndroidCalendarProviderRepository]
 * must honour: create/update carry the tag set through, and the nullable
 * parameter distinguishes "manage tags" (non-null, authoritative) from "leave
 * tags alone" (null). The null case is the load-bearing guard: reschedule and
 * single-occurrence exception edits pass null so they never wipe existing tags.
 */
class FakeCategoriesWriteTest {

    private suspend fun FakeCalendarProviderRepository.create(categories: List<String>?) =
        createEvent(
            calendarId = 1L,
            title = "T",
            description = null,
            location = null,
            startTs = 0L,
            endTs = null,
            isAllDay = false,
            rrule = null,
            duration = null,
            timezone = "UTC",
            reminders = emptyList(),
            categories = categories,
        )

    private suspend fun FakeCalendarProviderRepository.update(eventId: Long, categories: List<String>?) =
        updateEvent(
            eventId = eventId,
            title = "T",
            description = null,
            location = null,
            startTs = 0L,
            endTs = null,
            isAllDay = false,
            rrule = null,
            duration = null,
            timezone = "UTC",
            reminders = emptyList(),
            categories = categories,
        )

    @Test
    fun `create carries the tag set through`() = runTest {
        val fake = FakeCalendarProviderRepository()
        fake.create(listOf("Work", "Urgent"))

        assertEquals(listOf("Work", "Urgent"), fake.createdEvents.single().categories)
    }

    @Test
    fun `create with null tags records null (not managing tags)`() = runTest {
        val fake = FakeCalendarProviderRepository()
        fake.create(null)

        assertNull(fake.createdEvents.single().categories)
    }

    @Test
    fun `update carries the authoritative tag set through`() = runTest {
        val fake = FakeCalendarProviderRepository()
        fake.update(42L, listOf("Home"))

        assertEquals(listOf("Home"), fake.updatedEvents.single().categories)
    }

    @Test
    fun `update with null tags leaves them unmanaged - reschedule and exception guard`() = runTest {
        val fake = FakeCalendarProviderRepository()
        fake.update(42L, null)

        assertNull(fake.updatedEvents.single().categories)
    }

    @Test
    fun `update with non-null empty list is the clear-all signal`() = runTest {
        val fake = FakeCalendarProviderRepository()
        fake.update(42L, emptyList())

        val captured = fake.updatedEvents.single().categories
        assertTrue(captured != null && captured.isEmpty())
    }
}
