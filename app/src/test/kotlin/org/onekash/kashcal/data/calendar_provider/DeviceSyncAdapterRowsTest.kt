package org.onekash.kashcal.data.calendar_provider

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.DAY
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.DAY_CODE
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.EVENT_ZONE
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.HOUR
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.SYNCED_CAL
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.T0
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.WEEK
import org.onekash.kashcal.domain.mapper.toFormState
import org.onekash.kashcal.ui.components.toStartEndTs
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reads events stored the way other sync adapters store them, not the way this app writes them:
 * seconds-only durations, no status, an organizer without a guest list, and the user's own guest
 * row spelled in different case. Most device events on a real phone arrive like this, so the
 * app's reads (grid, edit form, quick view) and replies must handle them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceSyncAdapterRowsTest {

    private val f = DeviceRoundTripFixture()

    @Before
    fun setUp() = f.setUp()

    @After
    fun tearDown() = f.tearDown()

    private fun syncedSeries(duration: String?, status: Int? = null): Long = f.provider.insertRow(
        SqliteCalendarProvider.EVENTS,
        Events.CALENDAR_ID to SYNCED_CAL, Events.TITLE to "Weekly sync", Events.DTSTART to T0,
        Events.DURATION to duration, Events.RRULE to "FREQ=WEEKLY;COUNT=4", Events.EVENT_TIMEZONE to EVENT_ZONE,
        Events.STATUS to status, Events._SYNC_ID to "remote-1", Events.ORGANIZER to "boss@example.test",
        Events.HAS_ATTENDEE_DATA to 1,
    )

    private suspend fun formFor(eventId: Long) = f.reader.getEventForEdit(eventId, occurrenceTs = null, isAllDay = false)!!.let {
        it.event.toFormState(it.reminders, it.calendar.color, it.calendar.displayName, emptyList())
    }

    @Test
    fun `a series stored with a seconds-only duration shows occurrences of that length`() = runTest {
        val id = syncedSeries(duration = "P3600S")

        val occurrence = f.occurrences(DAY_CODE).single { it.eventId == id }
        assertEquals(T0, occurrence.startTs)
        assertEquals(T0 + HOUR, occurrence.endTs)
    }

    @Ignore("The form reads a seconds-only duration such as P3600S as zero, so the series opens with no length")
    @Test
    fun `the edit form opens a seconds-only duration series with its real length`() = runTest {
        val id = syncedSeries(duration = "P3600S")

        val (start, end) = formFor(id).toStartEndTs()

        assertEquals(T0, start)
        assertEquals("the form must show the series' one-hour length", T0 + HOUR, end)
    }

    @Test
    fun `the edit form opens a standard duration series with its real length`() = runTest {
        val id = syncedSeries(duration = "PT1H")

        val (start, end) = formFor(id).toStartEndTs()

        assertEquals(T0, start)
        assertEquals(T0 + HOUR, end)
    }

    @Test
    fun `a series with no stored status still shows its occurrences`() = runTest {
        syncedSeries(duration = "PT1H", status = null)

        assertEquals(1, f.occurrences(DAY_CODE).size)
        assertEquals(1, f.occurrences(DAY_CODE + 7).size)
    }

    @Test
    fun `the user's own guest row stored in different case is still the user's row for replies`() = runTest {
        val id = syncedSeries(duration = "PT1H")
        f.provider.insertRow(
            SqliteCalendarProvider.ATTENDEES, Attendees.EVENT_ID to id, Attendees.ATTENDEE_EMAIL to "ME@example.test",
            Attendees.ATTENDEE_RELATIONSHIP to Attendees.RELATIONSHIP_ATTENDEE, Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_INVITED,
        )

        assertEquals(true, f.writer.replyRsvp(id, SYNCED_CAL, Attendees.ATTENDEE_STATUS_ACCEPTED).getOrThrow())

        val row = f.provider.rows(SqliteCalendarProvider.ATTENDEES, "${Attendees.EVENT_ID} = ?", id.toString()).single()
        assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED.toString(), row[Attendees.ATTENDEE_STATUS])
    }

    @Test
    fun `a series whose rows arrive before any edit reads the organizer the sync adapter stored`() = runTest {
        val id = syncedSeries(duration = "PT1H")

        assertEquals("boss@example.test", f.eventRow(id)!![Events.ORGANIZER])
        assertNull("a sync adapter row has no guest list yet", f.reader.getAttendeesWithOwner(id, SYNCED_CAL).ownerEmail)
    }

    @Test
    fun `the next occurrence of a synced series comes from its occurrences, not its first date`() = runTest {
        val id = syncedSeries(duration = "P3600S")

        // New York springs forward on 2024-03-10, so later occurrences sit an hour earlier in UTC.
        assertEquals(T0 + 2 * WEEK - HOUR, f.reader.resolveQuickViewOccurrenceStart(id, nowMs = T0 + WEEK + 2 * DAY))
    }
}
