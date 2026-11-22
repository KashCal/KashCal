package org.onekash.kashcal.domain.reader

import android.provider.CalendarContract.Attendees
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.LOCAL_CAL
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.OWNER
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.SYNCED_CAL
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.T0
import org.onekash.kashcal.data.calendar_provider.SqliteCalendarProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [DeviceEventReader.countInstancesWithReminders] over the real provider repository: an
 * instance counts when one of its reminders triggers inside the window and it isn't declined.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceEventReaderRemindersCountRealProviderTest {

    private val f = DeviceRoundTripFixture()
    private val now = T0 - HOUR
    private val windowEnd = now + 7 * DAY

    @Before
    fun setUp() = f.setUp()

    @After
    fun tearDown() = f.tearDown()

    private suspend fun create(
        title: String,
        start: Long,
        reminders: List<Int>? = listOf(10),
        calendarId: Long = SYNCED_CAL,
    ): Long = f.writer.createEvent(
        f.draft(title = title, startTs = start, endTs = start + HOUR, reminders = reminders, calendarId = calendarId)
    ).getOrThrow().eventId

    private suspend fun count(ids: Set<Long> = setOf(SYNCED_CAL)) =
        f.reader.countInstancesWithReminders(ids, now, windowEnd)

    @Test
    fun `an instance whose reminder triggers in the window counts`() = runTest {
        create("Review", start = T0 + 2 * HOUR)

        assertEquals(1, count())
    }

    @Test
    fun `an instance without reminders doesn't count`() = runTest {
        create("Lunch", start = T0 + 2 * HOUR, reminders = emptyList())

        assertEquals(0, count())
    }

    @Test
    fun `a declined instance doesn't count`() = runTest {
        val id = create("Offsite", start = T0 + 2 * HOUR)
        f.provider.insertRow(
            SqliteCalendarProvider.ATTENDEES, Attendees.EVENT_ID to id, Attendees.ATTENDEE_EMAIL to OWNER,
            Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_INVITED,
        )
        f.writer.replyRsvp(id, SYNCED_CAL, Attendees.ATTENDEE_STATUS_DECLINED)

        assertEquals(0, count())
    }

    @Test
    fun `reminders triggering before now or after the window don't count`() = runTest {
        create("Earlier today", start = now - 2 * HOUR)
        create("Next week", start = windowEnd + 2 * HOUR)

        assertEquals(0, count())
    }

    @Test
    fun `an all-day reminder counts from the event day's local midnight`() = runTest {
        // Berlin is UTC+1, so the day after T0 starts at 23:00 UTC on T0's day. 570 minutes before
        // that is 30 minutes before now; counting from UTC midnight would land 30 minutes after.
        val nextDayUtc = T0 - T0 % DAY + DAY
        f.writer.createEvent(
            f.draft(
                title = "Holiday", startTs = nextDayUtc, endTs = nextDayUtc + DAY, isAllDay = true,
                timezone = "UTC", reminders = listOf(570),
            )
        ).getOrThrow()

        assertEquals(0, count())
    }

    @Test
    fun `a long-lead reminder counts when its event starts after the window`() = runTest {
        create("Conference", start = windowEnd + 2 * DAY, reminders = listOf(3 * 24 * 60))

        assertEquals(1, count())
    }

    @Test
    fun `instances of calendars outside the ids don't count`() = runTest {
        create("Personal", start = T0 + 2 * HOUR, calendarId = LOCAL_CAL)

        assertEquals(0, count())
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
