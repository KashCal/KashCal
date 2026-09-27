package org.onekash.kashcal.data.calendar_provider

import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.DAY
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.DAY0
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.DAY_CODE
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.EVENT_COLOR
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.EVENT_ZONE
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.HOUR
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.LOCAL_CAL
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.MINUTE
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.OWNER
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.SYNCED_CAL
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.T0
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.WORK_COLOR
import org.onekash.kashcal.domain.writer.DeviceEventDraft
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One-off, all-day and multi-day device events written through the app's
 * device writer and read back through every read the app uses: the stored
 * provider row (what other apps and the sync adapter see), the event read, the
 * edit-form read, the grid's occurrence read, search, title suggestions, the
 * next-occurrence lookup and the upcoming-reminder lookup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceOneOffRoundTripTest {

    private val f = DeviceRoundTripFixture()

    @Before
    fun setUp() = f.setUp()

    @After
    fun tearDown() = f.tearDown()

    private suspend fun create(draft: DeviceEventDraft = f.draft()): Long =
        f.writer.createEvent(draft).getOrThrow().eventId

    private suspend fun edit(eventId: Long, draft: DeviceEventDraft) {
        assertTrue(f.writer.updateEvent(eventId, draft).isSuccess)
    }

    /** The stored row without DIRTY, which every edit sets. */
    private fun storedFields(eventId: Long) = f.eventRow(eventId)!! - setOf(Events.DIRTY)

    // ---- create ----

    @Test
    fun `a timed event with every field set is stored as the platform expects`() = runTest {
        val id = create()

        val row = f.eventRow(id)!!
        assertEquals(SYNCED_CAL.toString(), row[Events.CALENDAR_ID])
        assertEquals("Design review", row[Events.TITLE])
        assertEquals("Agenda: roadmap", row[Events.DESCRIPTION])
        assertEquals("Room 4", row[Events.EVENT_LOCATION])
        assertEquals(T0.toString(), row[Events.DTSTART])
        assertEquals((T0 + 90 * MINUTE).toString(), row[Events.DTEND])
        assertNull(row[Events.DURATION])
        assertNull(row[Events.RRULE])
        assertEquals("0", row[Events.ALL_DAY])
        assertEquals(EVENT_ZONE, row[Events.EVENT_TIMEZONE])
        assertEquals(Events.AVAILABILITY_FREE.toString(), row[Events.AVAILABILITY])
        assertEquals(EVENT_COLOR.toString(), row[Events.EVENT_COLOR])
        assertEquals("the platform names the calendar owner", OWNER, row[Events.ORGANIZER])
        assertEquals(listOf(10 to Reminders.METHOD_ALERT, 60 to Reminders.METHOD_ALERT), f.provider.reminderRows(id))
    }

    @Test
    fun `a timed event with every field set reads back the same through the event and form reads`() = runTest {
        val id = create()

        val event = f.repository.getDeviceEvent(id)!!
        assertEquals("Design review", event.title)
        assertEquals("Agenda: roadmap", event.description)
        assertEquals("Room 4", event.location)
        assertEquals(T0, event.startTs)
        assertEquals(T0 + 90 * MINUTE, event.endTs)
        assertFalse(event.isAllDay)
        assertEquals(EVENT_ZONE, event.timezone)
        assertEquals(Events.AVAILABILITY_FREE, event.availability)
        assertEquals(EVENT_COLOR, event.eventColor)
        assertNull(event.rrule)

        val forEdit = f.reader.getEventForEdit(id, occurrenceTs = null, isAllDay = false)!!
        assertEquals(event, forEdit.event)
        assertEquals(listOf(10, 60), forEdit.reminders)
        assertEquals(SYNCED_CAL, forEdit.calendar.id)
        assertEquals("Work", forEdit.calendar.displayName)
    }

    @Test
    fun `a timed event with every field set reads back the same through the grid`() = runTest {
        val id = create()

        val occurrence = f.occurrences(DAY_CODE).single()
        assertEquals(id, occurrence.eventId)
        assertEquals("Design review", occurrence.title)
        assertEquals("Agenda: roadmap", occurrence.description)
        assertEquals("Room 4", occurrence.location)
        assertEquals(T0, occurrence.startTs)
        assertEquals(T0 + 90 * MINUTE, occurrence.endTs)
        assertEquals(DAY_CODE, occurrence.startDay)
        assertEquals(DAY_CODE, occurrence.endDay)
        assertFalse(occurrence.isAllDay)
        assertFalse(occurrence.hasRrule)
        assertEquals(EVENT_ZONE, occurrence.timezone)
        assertEquals(EVENT_COLOR, occurrence.eventColor)
        assertEquals(Events.AVAILABILITY_FREE, occurrence.availability)
        assertEquals(listOf(10, 60), occurrence.reminders)
        assertTrue(occurrence.hasAlarm)
        assertEquals("Work", occurrence.calendarDisplayName)
        assertEquals(WORK_COLOR, occurrence.calendarColor)
        assertTrue(occurrence.isWritable)
        assertNull(occurrence.originalId)
    }

    @Test
    fun `a tentative event keeps tentative availability`() = runTest {
        val id = create(f.draft(availability = Events.AVAILABILITY_TENTATIVE))

        assertEquals(Events.AVAILABILITY_TENTATIVE.toString(), f.eventRow(id)!![Events.AVAILABILITY])
        assertEquals(Events.AVAILABILITY_TENTATIVE, f.occurrences(DAY_CODE).single().availability)
    }

    @Test
    fun `an event with no optional fields reads back without them`() = runTest {
        val id = create(
            f.draft(description = null, location = null, eventColor = null, reminders = emptyList()),
        )

        val row = f.eventRow(id)!!
        assertNull(row[Events.DESCRIPTION])
        assertNull(row[Events.EVENT_LOCATION])
        assertNull(row[Events.EVENT_COLOR])
        assertTrue(f.provider.reminderRows(id).isEmpty())
        val event = f.repository.getDeviceEvent(id)!!
        assertNull(event.description)
        assertNull(event.location)
        assertNull(event.eventColor)
        assertTrue(event.categories.isEmpty())
        val occurrence = f.occurrences(DAY_CODE).single()
        assertEquals("", occurrence.description)
        assertEquals("", occurrence.location)
        assertNull(occurrence.eventColor)
        assertTrue(occurrence.reminders.isEmpty())
        assertFalse(occurrence.hasAlarm)
        assertTrue(f.reader.getAttendeesWithOwner(id, SYNCED_CAL).attendees.isEmpty())
    }

    @Test
    fun `an event in the local calendar reads back with that calendar`() = runTest {
        val id = create(f.draft(calendarId = LOCAL_CAL))

        assertEquals(LOCAL_CAL, f.occurrences(DAY_CODE).single { it.eventId == id }.calendarId)
        assertEquals("Personal", f.occurrences(DAY_CODE).single().calendarDisplayName)
    }

    // ---- all-day ----

    private fun allDayDraft(days: Int) = f.draft(
        isAllDay = true, startTs = DAY0, endTs = DAY0 + days * DAY - 1, timezone = EVENT_ZONE,
    )

    @Test
    fun `an all-day event is stored at UTC midnights with an exclusive end`() = runTest {
        val id = create(allDayDraft(days = 1))

        val row = f.eventRow(id)!!
        assertEquals(DAY0.toString(), row[Events.DTSTART])
        assertEquals((DAY0 + DAY).toString(), row[Events.DTEND])
        assertEquals("UTC", row[Events.EVENT_TIMEZONE])
        assertEquals("1", row[Events.ALL_DAY])
    }

    @Test
    fun `an all-day event reads back on its own day with the inclusive end`() = runTest {
        val id = create(allDayDraft(days = 1))

        val event = f.repository.getDeviceEvent(id)!!
        assertTrue(event.isAllDay)
        assertEquals(DAY0, event.startTs)
        assertEquals(DAY0 + DAY - 1, event.endTs)
        val occurrence = f.occurrences(DAY_CODE).single()
        assertTrue(occurrence.isAllDay)
        assertEquals(DAY0, occurrence.startTs)
        assertEquals(DAY0 + DAY - 1, occurrence.endTs)
        assertEquals(DAY_CODE, occurrence.startDay)
        assertEquals(DAY_CODE, occurrence.endDay)
    }

    @Test
    fun `a three-day all-day event covers exactly its three days`() = runTest {
        val id = create(allDayDraft(days = 3))

        assertEquals((DAY0 + 3 * DAY).toString(), f.eventRow(id)!![Events.DTEND])
        val occurrence = f.occurrences(DAY_CODE).single()
        assertEquals(DAY_CODE, occurrence.startDay)
        assertEquals(DAY_CODE + 2, occurrence.endDay)
        assertEquals(DAY0 + 3 * DAY - 1, f.repository.getDeviceEvent(id)!!.endTs)
    }

    @Test
    fun `a timed event running past midnight ends on the next day`() = runTest {
        // 22:00 Berlin to 01:00 Berlin the next day.
        val start = T0 + 6 * HOUR
        create(f.draft(startTs = start, endTs = start + 3 * HOUR))

        val occurrence = f.occurrences(DAY_CODE, DAY_CODE + 1).single()
        assertEquals(DAY_CODE, occurrence.startDay)
        assertEquals(DAY_CODE + 1, occurrence.endDay)
    }

    // ---- edit one field at a time ----

    /**
     * Edits a fully-set event with [change] and checks that only the [changed] columns moved and,
     * when the reminders are unchanged, that their rows are too.
     */
    private suspend fun assertEditChangesOnly(change: (DeviceEventDraft) -> DeviceEventDraft, vararg changed: String): Long {
        val base = f.draft()
        val id = create(base)
        val before = storedFields(id)
        val remindersBefore = f.provider.reminderRows(id)

        edit(id, change(base))

        val after = storedFields(id)
        val moved = (before.keys + after.keys).filter { before[it] != after[it] }.toSet()
        assertEquals("columns changed by the edit", changed.toSet(), moved)
        if (change(base).reminders == base.reminders) assertEquals(remindersBefore, f.provider.reminderRows(id))
        return id
    }

    @Test
    fun `editing the title changes only the title`() = runTest {
        val id = assertEditChangesOnly({ it.copy(title = "Design review v2") }, Events.TITLE)
        assertEquals("Design review v2", f.occurrences(DAY_CODE).single().title)
        assertEquals("Design review v2", f.repository.getDeviceEvent(id)!!.title)
    }

    @Test
    fun `clearing the description changes only the description`() = runTest {
        val id = assertEditChangesOnly({ it.copy(description = null) }, Events.DESCRIPTION)
        assertNull(f.repository.getDeviceEvent(id)!!.description)
    }

    @Test
    fun `editing the location changes only the location`() = runTest {
        assertEditChangesOnly({ it.copy(location = "Room 9") }, Events.EVENT_LOCATION)
        assertEquals("Room 9", f.occurrences(DAY_CODE).single().location)
    }

    @Test
    fun `moving the times changes only the start and end`() = runTest {
        assertEditChangesOnly({ it.copy(startTs = T0 + HOUR, endTs = T0 + 3 * HOUR) }, Events.DTSTART, Events.DTEND)
        val occurrence = f.occurrences(DAY_CODE).single()
        assertEquals(T0 + HOUR, occurrence.startTs)
        assertEquals(T0 + 3 * HOUR, occurrence.endTs)
    }

    @Test
    fun `changing the timezone changes only the timezone`() = runTest {
        assertEditChangesOnly({ it.copy(timezone = "Asia/Tokyo") }, Events.EVENT_TIMEZONE)
        assertEquals("Asia/Tokyo", f.occurrences(DAY_CODE).single().timezone)
    }

    @Test
    fun `turning all-day on stores UTC midnights and the UTC zone`() = runTest {
        val id = assertEditChangesOnly(
            { it.copy(isAllDay = true, startTs = DAY0, endTs = DAY0 + DAY - 1) },
            Events.DTSTART, Events.DTEND, Events.ALL_DAY, Events.EVENT_TIMEZONE,
        )
        assertEquals("UTC", f.eventRow(id)!![Events.EVENT_TIMEZONE])
        assertTrue(f.occurrences(DAY_CODE).single().isAllDay)
    }

    @Test
    fun `turning all-day off stores the chosen times and zone`() = runTest {
        val id = create(allDayDraft(days = 1))

        edit(id, f.draft())

        val row = f.eventRow(id)!!
        assertEquals("0", row[Events.ALL_DAY])
        assertEquals(T0.toString(), row[Events.DTSTART])
        assertEquals((T0 + 90 * MINUTE).toString(), row[Events.DTEND])
        assertEquals(EVENT_ZONE, row[Events.EVENT_TIMEZONE])
    }

    @Test
    fun `setting a different colour changes only the colour`() = runTest {
        assertEditChangesOnly({ it.copy(eventColor = 0xFF998877.toInt()) }, Events.EVENT_COLOR)
        assertEquals(0xFF998877.toInt(), f.occurrences(DAY_CODE).single().eventColor)
    }

    @Test
    fun `clearing the colour changes only the colour`() = runTest {
        assertEditChangesOnly({ it.copy(eventColor = null) }, Events.EVENT_COLOR)
        assertNull(f.occurrences(DAY_CODE).single().eventColor)
    }

    @Test
    fun `changing availability changes only availability`() = runTest {
        assertEditChangesOnly({ it.copy(availability = Events.AVAILABILITY_BUSY) }, Events.AVAILABILITY)
        assertEquals(Events.AVAILABILITY_BUSY, f.occurrences(DAY_CODE).single().availability)
    }

    @Test
    fun `replacing reminders changes only the reminder rows`() = runTest {
        val id = assertEditChangesOnly({ it.copy(reminders = listOf(5)) })
        assertEquals(listOf(5 to Reminders.METHOD_ALERT), f.provider.reminderRows(id))
        assertEquals(listOf(5), f.occurrences(DAY_CODE).single().reminders)
    }

    @Test
    fun `clearing reminders removes them and the alarm`() = runTest {
        val id = assertEditChangesOnly({ it.copy(reminders = emptyList()) })
        assertTrue(f.provider.reminderRows(id).isEmpty())
        assertFalse(f.occurrences(DAY_CODE).single().hasAlarm)
    }

    @Test
    fun `an unchanged save changes nothing`() = runTest {
        assertEditChangesOnly({ it })
    }

    // ---- delete ----

    @Test
    fun `deleting a never-synced event removes it from every read`() = runTest {
        val id = create()

        assertTrue(f.writer.deleteEvent(id).isSuccess)

        assertNull(f.eventRow(id))
        assertNull(f.repository.getDeviceEvent(id))
        assertFalse(f.repository.isEventActive(id))
        assertTrue(f.occurrences(DAY_CODE).isEmpty())
    }

    @Test
    fun `deleting a synced event leaves only a deleted row no list read shows`() = runTest {
        val id = create()
        f.markSynced(id)

        assertTrue(f.writer.deleteEvent(id).isSuccess)

        assertEquals("1", f.eventRow(id)!![Events.DELETED])
        assertFalse(f.repository.isEventActive(id))
        assertTrue(f.occurrences(DAY_CODE).isEmpty())
        assertTrue(f.repository.searchInstances("design", DAY_CODE, DAY_CODE, setOf(SYNCED_CAL)).isEmpty())
        assertTrue(
            f.repository.suggestTitlesByPrefix("Des", T0 - DAY, T0 + DAY, setOf(SYNCED_CAL), minFreq = 1).isEmpty(),
        )
        assertNull(f.repository.getNextOccurrenceStart(id, afterMs = T0 - HOUR))
        assertNull(f.repository.getNextUpcomingReminder(setOf(SYNCED_CAL), afterMs = T0 - 2 * HOUR))
    }

    @Ignore("The single-event reads don't skip rows marked deleted, so a deleted event still opens by id until its sync adapter purges it")
    @Test
    fun `a deleted synced event can no longer be opened by id`() = runTest {
        val id = create()
        f.markSynced(id)
        f.writer.deleteEvent(id)

        // The platform keeps the row until the sync adapter purges it; a deleted
        // row "should be ignored", so opening it from a notification or a link
        // must not show the event.
        assertNull(f.reader.getEventForEdit(id, occurrenceTs = null, isAllDay = false))
        assertNull(f.reader.resolveQuickViewOccurrenceStart(id, nowMs = T0 - DAY))
    }

    // ---- the other reads ----

    @Test
    fun `search, suggestions, next occurrence and reminders see a new event and forget it once deleted`() = runTest {
        val id = create()

        assertEquals(listOf(id), f.repository.searchInstances("roadmap", DAY_CODE, DAY_CODE, setOf(SYNCED_CAL)).map { it.eventId })
        assertEquals(
            listOf("Design review"),
            f.repository.suggestTitlesByPrefix("des", T0 - DAY, T0 + DAY, setOf(SYNCED_CAL), minFreq = 1).map { it.title },
        )
        assertEquals(T0, f.repository.getNextOccurrenceStart(id, afterMs = T0 - HOUR))
        assertEquals(T0 - 60 * MINUTE, f.repository.getNextUpcomingReminder(setOf(SYNCED_CAL), afterMs = T0 - 2 * HOUR)?.triggerTime)

        f.writer.deleteEvent(id)

        assertTrue(f.repository.searchInstances("roadmap", DAY_CODE, DAY_CODE, setOf(SYNCED_CAL)).isEmpty())
        assertTrue(f.repository.suggestTitlesByPrefix("des", T0 - DAY, T0 + DAY, setOf(SYNCED_CAL), minFreq = 1).isEmpty())
        assertNull(f.repository.getNextOccurrenceStart(id, afterMs = T0 - HOUR))
        assertNull(f.repository.getNextUpcomingReminder(setOf(SYNCED_CAL), afterMs = T0 - 2 * HOUR))
    }

    @Test
    fun `an all-day event's day-of reminder fires at that local clock time`() = runTest {
        // -540 minutes = 9:00 on the day, in the phone's zone (Berlin: 08:00Z in March).
        create(allDayDraft(days = 1).copy(reminders = listOf(-540)))

        val next = f.repository.getNextUpcomingReminder(setOf(SYNCED_CAL), afterMs = DAY0 - 2 * DAY)

        assertNotNull(next)
        assertEquals(DAY0 + 8 * HOUR, next!!.triggerTime)
    }

    @Test
    fun `the quick view opens a one-off event at its own start`() = runTest {
        val id = create()

        assertEquals(T0, f.reader.resolveQuickViewOccurrenceStart(id, nowMs = T0 + 10 * DAY))
    }
}
