package org.onekash.kashcal.data.calendar_provider

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for DeviceCalendarInstance computed properties and
 * FakeCalendarProviderRepository exception-aware deletion behavior.
 */
class DeviceCalendarInstanceTest {

    // ==================== isPartOfRecurringSeries ====================

    @Test
    fun `isPartOfRecurringSeries returns false for non-recurring instance`() {
        val instance = createInstance(hasRrule = false, originalId = null)
        assertFalse(instance.isPartOfRecurringSeries)
    }

    @Test
    fun `isPartOfRecurringSeries returns true for regular recurring occurrence`() {
        val instance = createInstance(hasRrule = true, originalId = null)
        assertTrue(instance.isPartOfRecurringSeries)
    }

    @Test
    fun `isPartOfRecurringSeries returns true for exception occurrence`() {
        // Exception: hasRrule is false (exception events have no RRULE),
        // but originalId is set (points to master event)
        val instance = createInstance(hasRrule = false, originalId = 100L)
        assertTrue(instance.isPartOfRecurringSeries)
    }

    @Test
    fun `isPartOfRecurringSeries returns true when both hasRrule and originalId set`() {
        val instance = createInstance(hasRrule = true, originalId = 100L)
        assertTrue(instance.isPartOfRecurringSeries)
    }

    // ==================== FakeCalendarProviderRepository.deleteSingleOccurrence ====================

    @Test
    fun `deleteSingleOccurrence succeeds when no exception exists`() = runTest {
        val fake = FakeCalendarProviderRepository()

        val result = fake.deleteSingleOccurrence(
            masterEventId = 200L,
            originalInstanceTime = 1709280000000L,
            isAllDay = false
        )

        assertTrue(result.isSuccess)
        assertEquals(1, fake.deletedOccurrences.size)
        assertEquals(200L, fake.deletedOccurrences[0].masterEventId)
        assertEquals(1709280000000L, fake.deletedOccurrences[0].originalInstanceTime)
    }

    @Test
    fun `deleteSingleOccurrence succeeds when exception already exists`() = runTest {
        val fake = FakeCalendarProviderRepository()
        // Pre-populate an existing exception for this occurrence
        fake.exceptionEvents[200L to 1709280000000L] = 500L

        val result = fake.deleteSingleOccurrence(
            masterEventId = 200L,
            originalInstanceTime = 1709280000000L,
            isAllDay = false
        )

        assertTrue(result.isSuccess)
        assertEquals(1, fake.deletedOccurrences.size)
        assertEquals(200L, fake.deletedOccurrences[0].masterEventId)
    }

    // ==================== Helpers ====================

    private fun createInstance(
        hasRrule: Boolean = false,
        originalId: Long? = null
    ) = DeviceCalendarInstance(
        instanceId = 1L,
        eventId = 10L,
        title = "Test Event",
        description = "",
        location = "",
        startTs = 1709280000000L,
        endTs = 1709283600000L,
        startDay = 20240301,
        endDay = 20240301,
        isAllDay = false,
        hasRrule = hasRrule,
        rrule = if (hasRrule) "FREQ=WEEKLY;COUNT=10" else null,
        reminders = emptyList(),
        calendarId = 1L,
        calendarDisplayName = "Test Calendar",
        calendarColor = 0xFF0000.toInt(),
        eventColor = null,
        status = 1,
        availability = 0,
        hasAlarm = false,
        selfAttendeeStatus = 0,
        isWritable = true,
        originalId = originalId,
        originalInstanceTime = if (originalId != null) 1709280000000L else null,
        timezone = "America/New_York",
        eventStartTs = 1709280000000L,
    )
}
