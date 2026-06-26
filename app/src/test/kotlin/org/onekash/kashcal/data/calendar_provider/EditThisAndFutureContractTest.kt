package org.onekash.kashcal.data.calendar_provider

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [CalendarProviderRepository.editThisAndFuture]
 * exercised against the [FakeCalendarProviderRepository]. The
 * real-device behavior depends on `ContentResolver.applyBatch`,
 * which Robolectric can't faithfully simulate; the multi-server and
 * device QA passes cover the wire-level behavior. These tests fix
 * the contract that callers (HomeViewModel) depend on.
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
