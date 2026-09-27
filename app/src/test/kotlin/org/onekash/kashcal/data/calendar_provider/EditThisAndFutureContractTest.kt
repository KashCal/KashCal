package org.onekash.kashcal.data.calendar_provider

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that [FakeCalendarProviderRepository]'s [CalendarProviderRepository.editThisAndFuture]
 * records the split request and returns a configured write failure, which tests over the fake
 * depend on. The real repository's split runs over [SqliteCalendarProvider] in
 * `DeviceRecurringRoundTripTest` and `AndroidCalendarProviderRepositoryExceptionWriteTest`.
 */
class EditThisAndFutureContractTest {

    @Test
    fun `editThisAndFuture records split fields on the fake`() = runTest {
        val fake = FakeCalendarProviderRepository()

        val result = fake.editThisAndFuture(
            masterEventId = 42L,
            fromTimeMs = 1_700_000_000_000L,
            isAllDay = false,
            calendarId = 1L,
            title = "Future series",
            description = null,
            location = null,
            startTs = 1_700_000_000_000L,
            endTs = 1_700_003_600_000L,
            rrule = "FREQ=DAILY;COUNT=5",
            duration = null,
            timezone = "UTC",
            reminders = listOf(15),
            availability = 0,
            eventColor = null,
        )

        assertTrue("split request must succeed", result.isSuccess)
        val newId = result.getOrNull()
        assertNotNull("split result must carry a new event id", newId)
        assertEquals(1, fake.editedFutureSeries.size)
        val recorded = fake.editedFutureSeries.first()
        assertEquals(42L, recorded.masterEventId)
        assertEquals(1_700_000_000_000L, recorded.fromTimeMs)
        assertEquals("FREQ=DAILY;COUNT=5", recorded.rrule)
        assertEquals(false, recorded.isAllDay)
    }

    @Test
    fun `editThisAndFuture surfaces simulated write failure`() = runTest {
        val fake = FakeCalendarProviderRepository().apply {
            writeFailure = org.onekash.kashcal.error.CalendarError.DeviceCalendar.WriteFailed("disk full")
        }

        val result = fake.editThisAndFuture(
            masterEventId = 1L,
            fromTimeMs = 0L,
            isAllDay = false,
            calendarId = 1L,
            title = "T",
            description = null,
            location = null,
            startTs = 0L,
            endTs = 1L,
            rrule = "FREQ=DAILY",
            duration = null,
            timezone = "UTC",
            reminders = emptyList(),
            availability = 0,
            eventColor = null,
        )

        assertTrue("propagates write failure", result.isFailure)
    }
}
