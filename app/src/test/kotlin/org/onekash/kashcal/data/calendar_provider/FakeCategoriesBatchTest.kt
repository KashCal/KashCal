package org.onekash.kashcal.data.calendar_provider

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [FakeCalendarProviderRepository.getCategoriesForEvents] — documents
 * the batch-read contract [AndroidCalendarProviderRepository] must match:
 *  - keyed on the requested event ids (only events that have tags appear)
 *  - a denied read yields an empty map, never an exception
 *  - the display range still loads (tags are additive, not load-bearing)
 */
class FakeCategoriesBatchTest {

    @Test
    fun `batch returns only events that have tags, keyed by id`() = runTest {
        val fake = FakeCalendarProviderRepository()
        fake.eventCategories = mutableMapOf(
            1L to listOf("Work", "Urgent"),
            2L to emptyList(),
            3L to listOf("Home"),
        )

        val result = fake.getCategoriesForEvents(setOf(1L, 2L, 3L, 4L))

        assertEquals(listOf("Work", "Urgent"), result[1L])
        assertEquals(listOf("Home"), result[3L])
        // Untagged (2L) and unknown (4L) events are absent, not empty entries.
        assertTrue(2L !in result)
        assertTrue(4L !in result)
    }

    @Test
    fun `denied read yields empty map, not an exception`() = runTest {
        val fake = FakeCalendarProviderRepository()
        fake.eventCategories = mutableMapOf(1L to listOf("Work"))
        fake.shouldThrowSecurityException = true

        assertTrue(fake.getCategoriesForEvents(setOf(1L)).isEmpty())
    }

    @Test
    fun `empty request short-circuits to empty map`() = runTest {
        val fake = FakeCalendarProviderRepository()
        fake.eventCategories = mutableMapOf(1L to listOf("Work"))

        assertTrue(fake.getCategoriesForEvents(emptySet()).isEmpty())
    }
}
