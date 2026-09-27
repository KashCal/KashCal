package org.onekash.kashcal.data.calendar_provider

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.HOUR
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.LOCAL_CAL
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.MINUTE
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.SYNCED_CAL
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.T0
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.occ
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.WEEK
import org.onekash.kashcal.domain.writer.DeviceEventDraft
import org.onekash.kashcal.error.CalendarError
import org.onekash.kashcal.error.CalendarErrorException
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Recurring device events written through the app's device writer and read
 * back through the occurrences the grid, reminders and quick view use: series
 * create and edit, one occurrence, this and future, and the deletes per scope.
 *
 * The series is weekly on Tuesdays at 10:00 New York from 2024-03-05. New York
 * moves its clocks forward on 2024-03-10, so from the second occurrence on the
 * same local time is an hour earlier in UTC; [occ] accounts for that.
 *
 * On the platform an exception only hides the series occurrence it replaces
 * when it carries the series' sync id, which the series only has once its
 * sync adapter has uploaded it (earlier exceptions pick it up then). Tests
 * about the normal case mark the series synced first; the local-series tests
 * pin what a local calendar, which never gets a sync id, shows. They pin the
 * fully expanded state: the platform maintains occurrences incrementally, and
 * right after such a write its own view can differ until it re-expands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceRecurringRoundTripTest {

    private val f = DeviceRoundTripFixture()

    @Before
    fun setUp() = f.setUp()

    @After
    fun tearDown() = f.tearDown()


    private val weekly = "FREQ=WEEKLY;BYDAY=TU;COUNT=4"

    private fun seriesDraft(rrule: String? = weekly, calendarId: Long = SYNCED_CAL, title: String = "Standup"): DeviceEventDraft =
        f.draft(calendarId = calendarId, title = title, rrule = rrule, startTs = T0, endTs = T0 + HOUR, reminders = listOf(10))

    private suspend fun createSeries(draft: DeviceEventDraft = seriesDraft(), synced: Boolean = true): Long {
        val id = f.writer.createEvent(draft).getOrThrow().eventId
        if (synced) f.markSynced(id, "series-$id")
        return id
    }

    private suspend fun march() = f.inMarch()

    private fun exceptionRows(masterId: Long) =
        f.provider.rows(SqliteCalendarProvider.EVENTS, "${Events.ORIGINAL_ID} = ?", masterId.toString())

    // ---- create ----

    @Test
    fun `a weekly series is stored with a rule and a duration and no end`() = runTest {
        val id = createSeries()

        val row = f.eventRow(id)!!
        assertEquals(weekly, row[Events.RRULE])
        assertNull(row[Events.DTEND])
        assertEquals(HOUR, SqliteCalendarProvider.parsePlatformDuration(row[Events.DURATION]!!))
    }

    @Test
    fun `a weekly series shows each occurrence at the same local time with the series fields`() = runTest {
        val id = createSeries()

        val occurrences = march()
        assertEquals((0..3).map(::occ), occurrences.map { it.startTs })
        assertTrue(occurrences.all { it.eventId == id && it.endTs - it.startTs == HOUR })
        assertTrue(occurrences.all { it.title == "Standup" && it.eventColor == EVENT_COLOR && it.hasRrule })
        assertEquals((0..3).map { 20240305 + 7 * it }, occurrences.map { it.startDay })
    }

    @Test
    fun `a daily series with an end date stops on that date`() = runTest {
        createSeries(seriesDraft(rrule = "FREQ=DAILY;UNTIL=20240307T235959Z"))

        assertEquals(listOf(T0, T0 + DAY, T0 + 2 * DAY), march().map { it.startTs })
    }

    @Test
    fun `a yearly all-day series shows a day-long occurrence each year`() = runTest {
        createSeries(f.draft(title = "Birthday", isAllDay = true, startTs = DAY0, endTs = DAY0 + DAY - 1, rrule = "FREQ=YEARLY;COUNT=2", reminders = emptyList()))

        val both = f.repository.getInstancesForDayRange(20240301, 20250331, setOf(SYNCED_CAL))
        assertEquals(listOf(20240305, 20250305), both.map { it.startDay })
        assertTrue(both.all { it.isAllDay && it.endDay == it.startDay })
    }

    // ---- all events ----

    @Test
    fun `editing the whole series changes every occurrence`() = runTest {
        val id = createSeries()

        f.writer.updateEvent(id, seriesDraft(title = "Standup (new room)"))

        assertTrue(march().all { it.title == "Standup (new room)" })
        assertEquals(4, march().size)
    }

    @Test
    fun `changing the series rule changes which occurrences exist`() = runTest {
        val id = createSeries()

        f.writer.updateEvent(id, seriesDraft(rrule = "FREQ=WEEKLY;BYDAY=TU;COUNT=2"))

        assertEquals(listOf(occ(0), occ(1)), march().map { it.startTs })
    }

    @Test
    fun `turning a series into a one-off leaves one occurrence with an end time`() = runTest {
        val id = createSeries()

        f.writer.updateEvent(id, seriesDraft(rrule = null))

        assertEquals(listOf(T0), march().map { it.startTs })
        val row = f.eventRow(id)!!
        assertNull(row[Events.RRULE])
        assertEquals((T0 + HOUR).toString(), row[Events.DTEND])
        assertNull(row[Events.DURATION])
    }

    // ---- one occurrence ----

    private fun movedDraft(i: Int, title: String = "Standup (moved)") =
        seriesDraft(title = title).copy(rrule = null, startTs = occ(i) + 2 * HOUR, endTs = occ(i) + 3 * HOUR)

    @Test
    fun `editing one occurrence of a synced series replaces just that occurrence`() = runTest {
        val id = createSeries()

        f.writer.editSingleOccurrence(id, occ(1), movedDraft(1))

        val occurrences = march()
        assertEquals(listOf(occ(0), occ(1) + 2 * HOUR, occ(2), occ(3)), occurrences.map { it.startTs })
        assertEquals("Standup (moved)", occurrences[1].title)
        assertEquals(id, occurrences[1].originalId)
        assertTrue(listOf(0, 2, 3).all { occurrences[it].title == "Standup" })
    }

    @Test
    fun `editing the same occurrence again updates its exception instead of adding one`() = runTest {
        val id = createSeries()
        f.writer.editSingleOccurrence(id, occ(1), movedDraft(1))

        f.writer.editSingleOccurrence(id, occ(1), movedDraft(1, title = "Standup (moved again)"))

        assertEquals(1, exceptionRows(id).size)
        assertEquals("Standup (moved again)", march()[1].title)
        assertEquals(4, march().size)
    }

    @Test
    fun `a new occurrence exception carries the series guests, tags and reminder types`() = runTest {
        val guest = f.guest("ana@example.test", "Ana")
        val id = createSeries(seriesDraft().copy(attendees = listOf(guest), categories = listOf("Team")))
        f.provider.insertRow(SqliteCalendarProvider.REMINDERS, Reminders.EVENT_ID to id, Reminders.MINUTES to 30, Reminders.METHOD to Reminders.METHOD_EMAIL)

        // A drag builds its draft without reminders, guests or tags.
        f.writer.editSingleOccurrence(id, occ(1), movedDraft(1).copy(reminders = null))

        val moved = march()[1]
        assertEquals(listOf("Team"), moved.categories)
        assertEquals(listOf(10, 30), moved.reminders)
        assertEquals(listOf(10 to Reminders.METHOD_ALERT, 30 to Reminders.METHOD_EMAIL), f.provider.reminderRows(moved.eventId))
        assertTrue("ana@example.test" in f.reader.getAttendeesWithOwner(moved.eventId, SYNCED_CAL).attendees.map { it.email })
        assertEquals(EVENT_COLOR, moved.eventColor)
    }

    @Test
    fun `editing one occurrence of a series that was never synced also leaves the original occurrence showing`() = runTest {
        // Platform outcome, not an app choice: without the series' sync id the
        // exception can't tell the provider which occurrence it replaces.
        // (Fully expanded state; see the class notes.)
        val id = createSeries(seriesDraft(calendarId = LOCAL_CAL), synced = false)

        f.writer.editSingleOccurrence(id, occ(1), movedDraft(1).copy(calendarId = LOCAL_CAL))

        assertEquals(listOf(occ(0), occ(1), occ(1) + 2 * HOUR, occ(2), occ(3)), march().map { it.startTs })
    }

    @Test
    fun `an occurrence edited before the series first syncs replaces it once the series syncs`() = runTest {
        val id = createSeries(synced = false)
        f.writer.editSingleOccurrence(id, occ(1), movedDraft(1))

        f.markSynced(id, "series-$id")

        assertEquals(listOf(occ(0), occ(1) + 2 * HOUR, occ(2), occ(3)), march().map { it.startTs })
    }

    // ---- one occurrence switched between timed and all-day ----

    /** Occurrence 1 (Tue 12 Mar) of the timed series, saved as an all-day event on that day. */
    private fun allDayOnOccurrence1(title: String = "Offsite") =
        f.draft(title = title, isAllDay = true, startTs = DAY0 + WEEK, endTs = DAY0 + WEEK + DAY - 1, reminders = null)

    @Test
    fun `saving one occurrence of a timed series as all-day replaces that occurrence`() = runTest {
        val id = createSeries()

        val exception = f.writer.editSingleOccurrence(id, occ(1), allDayOnOccurrence1()).getOrThrow().eventId

        val occurrences = march()
        assertEquals(4, occurrences.size)
        val onTheDay = occurrences.filter { it.startDay == DAY_CODE + 7 }
        assertEquals(listOf("Offsite"), onTheDay.map { it.title })
        assertTrue(onTheDay.single().isAllDay)
        assertEquals(listOf(occ(0), occ(2), occ(3)), occurrences.filter { !it.isAllDay }.map { it.startTs })
        val row = f.eventRow(exception)!!
        assertEquals(occ(1).toString(), row[Events.ORIGINAL_INSTANCE_TIME])
        assertEquals("0", row[Events.ORIGINAL_ALL_DAY])
    }

    @Test
    fun `saving one occurrence of an all-day series as timed replaces that day`() = runTest {
        val id = createSeries(f.draft(title = "Gym", isAllDay = true, startTs = DAY0, endTs = DAY0 + DAY - 1, rrule = "FREQ=DAILY;COUNT=3", reminders = emptyList()))
        val nineAm = occ(0) - HOUR + DAY // Wed 6 Mar 09:00 New York

        val exception = f.writer.editSingleOccurrence(
            id, DAY0 + DAY, f.draft(title = "Gym (morning)", startTs = nineAm, endTs = nineAm + HOUR, reminders = null),
        ).getOrThrow().eventId

        val occurrences = march()
        assertEquals(listOf("Gym", "Gym (morning)", "Gym"), occurrences.map { it.title })
        val row = f.eventRow(exception)!!
        assertEquals((DAY0 + DAY).toString(), row[Events.ORIGINAL_INSTANCE_TIME])
        assertEquals("1", row[Events.ORIGINAL_ALL_DAY])
    }

    @Test
    fun `editing an occurrence saved all-day again updates the same exception`() = runTest {
        val id = createSeries()
        f.writer.editSingleOccurrence(id, occ(1), allDayOnOccurrence1()).getOrThrow()

        f.writer.editSingleOccurrence(id, occ(1), allDayOnOccurrence1(title = "Offsite (room 2)")).getOrThrow()
        assertEquals(1, exceptionRows(id).size)
        f.writer.editSingleOccurrence(id, occ(1), seriesDraft(title = "Standup (back)").copy(startTs = occ(1), endTs = occ(1) + HOUR, rrule = null)).getOrThrow()

        assertEquals(1, exceptionRows(id).size)
        val occurrences = march()
        assertEquals((0..3).map(::occ), occurrences.map { it.startTs })
        assertEquals("Standup (back)", occurrences[1].title)
    }

    @Test
    fun `an occurrence saved all-day reopens as itself and can be deleted`() = runTest {
        val id = createSeries()
        f.writer.editSingleOccurrence(id, occ(1), allDayOnOccurrence1()).getOrThrow()
        val shown = march().single { it.title == "Offsite" }

        // Opened and deleted the way the app does: the series id with the
        // exception's original time and the exception's own all-day flag.
        val opened = f.reader.getEventForEdit(id, shown.originalInstanceTime, isAllDay = shown.isAllDay)!!
        assertEquals("Offsite", opened.event.title)
        assertTrue(opened.event.isAllDay)

        f.writer.deleteSingleOccurrence(id, shown.originalInstanceTime!!, isAllDay = shown.isAllDay).getOrThrow()

        assertEquals(listOf(occ(0), occ(2), occ(3)), march().map { it.startTs })
        assertEquals(1, exceptionRows(id).size)
        assertEquals(Events.STATUS_CANCELED.toString(), exceptionRows(id).single()[Events.STATUS])
    }

    // ---- this and future ----

    @Test
    fun `this and future on an open-ended series ends the old series before the split and starts a new one`() = runTest {
        val id = createSeries(seriesDraft(rrule = "FREQ=WEEKLY;BYDAY=TU"))

        val newId = f.writer.editThisAndFuture(id, occ(2), seriesDraft(rrule = "FREQ=WEEKLY;BYDAY=TU", title = "Standup v2").copy(startTs = occ(2), endTs = occ(2) + HOUR))
            .getOrThrow().eventId

        assertNotEquals(id, newId)
        val occurrences = march()
        assertEquals((0..3).map(::occ), occurrences.map { it.startTs })
        assertEquals(listOf("Standup", "Standup", "Standup v2", "Standup v2"), occurrences.map { it.title })
        assertEquals(listOf(id, id, newId, newId), occurrences.map { it.eventId })
        assertTrue("the old series gains an end", f.eventRow(id)!![Events.RRULE]!!.contains("UNTIL="))
    }

    @Test
    fun `this and future on a counted series keeps the total number of occurrences`() = runTest {
        val id = createSeries()

        f.writer.editThisAndFuture(id, occ(2), seriesDraft(title = "Standup v2").copy(startTs = occ(2), endTs = occ(2) + HOUR))

        val occurrences = march()
        assertEquals((0..3).map(::occ), occurrences.map { it.startTs })
        assertEquals(listOf("Standup", "Standup", "Standup v2", "Standup v2"), occurrences.map { it.title })
    }

    @Test
    fun `this and future on a counted all-day series keeps the total number of occurrences`() = runTest {
        val id = createSeries(f.draft(title = "Gym", isAllDay = true, startTs = DAY0, endTs = DAY0 + DAY - 1, rrule = "FREQ=DAILY;COUNT=5", reminders = emptyList()))

        f.writer.editThisAndFuture(
            id, DAY0 + 2 * DAY,
            f.draft(title = "Gym v2", isAllDay = true, startTs = DAY0 + 2 * DAY, endTs = DAY0 + 3 * DAY - 1, rrule = "FREQ=DAILY;COUNT=5", reminders = emptyList()),
        ).getOrThrow()

        val occurrences = march()
        assertEquals((0..4).map { DAY_CODE + it }, occurrences.map { it.startDay })
        assertEquals(listOf("Gym", "Gym", "Gym v2", "Gym v2", "Gym v2"), occurrences.map { it.title })
    }

    @Test
    fun `this and future from the last occurrence of a counted series changes just that one`() = runTest {
        val id = createSeries()

        val newId = f.writer.editThisAndFuture(id, occ(3), seriesDraft(title = "Standup v2").copy(startTs = occ(3), endTs = occ(3) + HOUR))
            .getOrThrow().eventId

        val occurrences = march()
        assertEquals((0..3).map(::occ), occurrences.map { it.startTs })
        assertEquals(listOf("Standup", "Standup", "Standup", "Standup v2"), occurrences.map { it.title })
        assertTrue(f.eventRow(id)!![Events.RRULE]!!.contains("COUNT=3"))
        assertTrue(f.eventRow(newId)!![Events.RRULE]!!.contains("COUNT=1"))
    }

    @Test
    fun `this and future on a counted series writes nothing when its past occurrences can't be counted`() = runTest {
        val id = createSeries()
        val rowsBefore = f.provider.rows(SqliteCalendarProvider.EVENTS)
        for (failure in SqliteCalendarProvider.QueryFailure.entries) {
            f.provider.queryFailures[SqliteCalendarProvider.INSTANCES] = failure

            val result = f.writer.editThisAndFuture(id, occ(2), seriesDraft(title = "Standup v2").copy(startTs = occ(2), endTs = occ(2) + HOUR))

            f.provider.queryFailures.clear()
            assertTrue("$failure must fail the split", result.isFailure)
            val error = (result.exceptionOrNull() as CalendarErrorException).error
            assertTrue("$failure must be a write failure, was $error", error is CalendarError.DeviceCalendar.WriteFailed)
            assertEquals("$failure must write nothing", rowsBefore, f.provider.rows(SqliteCalendarProvider.EVENTS))
        }
    }

    @Test
    fun `this and future on a dated or endless series doesn't need to count its past occurrences`() = runTest {
        for (rule in listOf("FREQ=WEEKLY;BYDAY=TU", "FREQ=WEEKLY;BYDAY=TU;UNTIL=20240331T000000Z")) {
            for (failure in SqliteCalendarProvider.QueryFailure.entries) {
                val id = createSeries(seriesDraft(rrule = rule))
                f.provider.queryFailures[SqliteCalendarProvider.INSTANCES] = failure

                val result = f.writer.editThisAndFuture(id, occ(2), seriesDraft(rrule = rule, title = "Standup v2").copy(startTs = occ(2), endTs = occ(2) + HOUR))

                f.provider.queryFailures.clear()
                val label = "$rule with $failure"
                val newId = result.getOrThrow().eventId
                val occurrences = march()
                assertEquals(label, (0..3).map(::occ), occurrences.map { it.startTs })
                assertEquals(label, listOf(id, id, newId, newId), occurrences.map { it.eventId })
                f.writer.deleteEvent(newId).getOrThrow()
                f.writer.deleteEvent(id).getOrThrow()
            }
        }
    }

    @Ignore("The split counts the occurrences the phone shows before it, so a deleted earlier occurrence isn't counted and the new series gets one occurrence too many")
    @Test
    fun `this and future on a counted series with an earlier deleted occurrence keeps the total`() = runTest {
        val id = createSeries()
        f.writer.deleteSingleOccurrence(id, occ(1), isAllDay = false).getOrThrow()

        f.writer.editThisAndFuture(id, occ(2), seriesDraft(title = "Standup v2").copy(startTs = occ(2), endTs = occ(2) + HOUR)).getOrThrow()

        val occurrences = f.inMarchAndApril()
        assertEquals(listOf(occ(0), occ(2), occ(3)), occurrences.map { it.startTs })
    }

    @Test
    fun `this and future removes the old series' exceptions from the split on`() = runTest {
        val id = createSeries(seriesDraft(rrule = "FREQ=WEEKLY;BYDAY=TU"))
        f.writer.editSingleOccurrence(id, occ(3), movedDraft(3))

        f.writer.editThisAndFuture(id, occ(2), seriesDraft(rrule = "FREQ=WEEKLY;BYDAY=TU").copy(startTs = occ(2), endTs = occ(2) + HOUR))

        assertTrue(exceptionRows(id).isEmpty())
        assertEquals((0..3).map(::occ), march().map { it.startTs })
    }

    @Ignore("The split deletes the old series' exceptions from the split on, cancelled ones included, and gives the new series no replacement, so a deleted future occurrence shows again")
    @Test
    fun `this and future keeps an occurrence deleted after the split deleted`() = runTest {
        val id = createSeries(seriesDraft(rrule = "FREQ=WEEKLY;BYDAY=TU"))
        f.writer.deleteSingleOccurrence(id, occ(3), isAllDay = false)

        val newId = f.writer.editThisAndFuture(id, occ(2), seriesDraft(rrule = "FREQ=WEEKLY;BYDAY=TU", title = "Standup v2").copy(startTs = occ(2), endTs = occ(2) + HOUR))
            .getOrThrow().eventId
        // Once its sync adapter uploads it, like the series before it.
        f.markSynced(newId, "series-$newId")

        assertEquals(listOf(occ(0), occ(1), occ(2)), march().map { it.startTs })
    }

    @Test
    fun `this and future from the first occurrence edits the whole series`() = runTest {
        val id = createSeries()

        val result = f.writer.editThisAndFuture(id, occ(0), seriesDraft(title = "Standup v2")).getOrThrow()

        assertEquals(id, result.eventId)
        assertTrue(march().all { it.title == "Standup v2" && it.eventId == id })
    }

    @Test
    fun `the new series of a this and future edit keeps the series guests and tags`() = runTest {
        val guest = f.guest("ana@example.test", "Ana")
        val id = createSeries(seriesDraft(rrule = "FREQ=WEEKLY;BYDAY=TU").copy(attendees = listOf(guest), categories = listOf("Team")))

        val newId = f.writer.editThisAndFuture(id, occ(2), seriesDraft(rrule = "FREQ=WEEKLY;BYDAY=TU").copy(startTs = occ(2), endTs = occ(2) + HOUR, reminders = null))
            .getOrThrow().eventId

        assertEquals(listOf("Team"), march().first { it.eventId == newId }.categories)
        assertTrue("ana@example.test" in f.reader.getAttendeesWithOwner(newId, SYNCED_CAL).attendees.map { it.email })
    }

    // ---- deletes ----

    @Test
    fun `deleting one occurrence of a synced series removes just that one, and again adds nothing`() = runTest {
        val id = createSeries()

        f.writer.deleteSingleOccurrence(id, occ(1), isAllDay = false)
        f.writer.deleteSingleOccurrence(id, occ(1), isAllDay = false)

        assertEquals(listOf(occ(0), occ(2), occ(3)), march().map { it.startTs })
        assertEquals(1, exceptionRows(id).size)
        assertEquals(Events.STATUS_CANCELED.toString(), exceptionRows(id).single()[Events.STATUS])
    }

    @Test
    fun `deleting one occurrence of a series that was never synced leaves it showing`() = runTest {
        // Platform outcome: the cancellation can't name the series' occurrence
        // without the series' sync id, and the app hides the cancellation row.
        // (Fully expanded state; see the class notes.)
        val id = createSeries(seriesDraft(calendarId = LOCAL_CAL), synced = false)

        f.writer.deleteSingleOccurrence(id, occ(1), isAllDay = false)

        assertEquals((0..3).map(::occ), march().map { it.startTs })
    }

    @Test
    fun `deleting this and future ends the series before that occurrence`() = runTest {
        val id = createSeries()

        f.writer.deleteThisAndFuture(id, occ(2), isAllDay = false)

        assertEquals(listOf(occ(0), occ(1)), march().map { it.startTs })
    }

    // ---- ending the old series keeps what it stored ----

    private val timeColumns = setOf(Events.DTSTART, Events.DURATION, Events.EVENT_TIMEZONE, Events.ALL_DAY)

    /** The columns of the last app update of series [id], the one that ended it. */
    private fun endingUpdateOf(id: Long) = f.provider.appEventUpdates.last { id in it.rowIds }.columns

    private fun timeFields(id: Long) = f.eventRow(id)!!.filterKeys { it in timeColumns }

    private class SeriesCase(val label: String, val draft: DeviceEventDraft, val splitAt: Long, val length: Long)

    /** Endless, counted and dated rules on both calendars, and an all-day series. */
    private fun seriesCases(): List<SeriesCase> = buildList {
        for (rule in listOf("FREQ=WEEKLY;BYDAY=TU", "FREQ=WEEKLY;BYDAY=TU;COUNT=4", "FREQ=WEEKLY;BYDAY=TU;UNTIL=20240331T000000Z")) {
            for (calendar in listOf(SYNCED_CAL, LOCAL_CAL)) {
                add(SeriesCase("$rule in calendar $calendar", seriesDraft(rrule = rule, calendarId = calendar), occ(2), HOUR))
            }
        }
        val allDay = f.draft(title = "Gym", isAllDay = true, startTs = DAY0, endTs = DAY0 + DAY - 1, rrule = "FREQ=DAILY;COUNT=5", reminders = emptyList())
        add(SeriesCase("all-day daily", allDay, DAY0 + 2 * DAY, DAY - 1))
    }

    private suspend fun createCase(case: SeriesCase): Long = createSeries(case.draft, synced = case.draft.calendarId == SYNCED_CAL)

    private suspend fun occurrencesBefore(split: Long) =
        march().filter { it.startTs < split }.map { Triple(it.eventId, it.startTs, it.title) }

    @Test
    fun `deleting this and future keeps the series' stored start, length, zone and all-day flag`() = runTest {
        for (case in seriesCases()) {
            val id = createCase(case)
            val stored = timeFields(id)
            val rule = f.eventRow(id)!![Events.RRULE]
            val before = occurrencesBefore(case.splitAt)

            f.writer.deleteThisAndFuture(id, case.splitAt, isAllDay = case.draft.isAllDay).getOrThrow()

            assertEquals(case.label, stored, timeFields(id))
            assertTrue(case.label, endingUpdateOf(id).containsAll(timeColumns + Events.RRULE))
            assertNotEquals(case.label, rule, f.eventRow(id)!![Events.RRULE])
            assertEquals(case.label, before, occurrencesBefore(case.splitAt))
            assertTrue(case.label, march().none { it.eventId == id && it.startTs >= case.splitAt })
            f.writer.deleteEvent(id).getOrThrow()
        }
    }

    @Test
    fun `this and future keeps the old series' stored start, length, zone and all-day flag`() = runTest {
        for (case in seriesCases()) {
            val id = createCase(case)
            val stored = timeFields(id)
            val rule = f.eventRow(id)!![Events.RRULE]
            val before = occurrencesBefore(case.splitAt)
            val future = case.draft.copy(title = "${case.draft.title} v2", startTs = case.splitAt, endTs = case.splitAt + case.length)

            val newId = f.writer.editThisAndFuture(id, case.splitAt, future).getOrThrow().eventId

            assertNotEquals(case.label, id, newId)
            assertEquals(case.label, stored, timeFields(id))
            assertTrue(case.label, endingUpdateOf(id).containsAll(timeColumns + Events.RRULE))
            assertNotEquals(case.label, rule, f.eventRow(id)!![Events.RRULE])
            assertEquals(case.label, before, occurrencesBefore(case.splitAt))
            f.writer.deleteEvent(newId).getOrThrow()
            f.writer.deleteEvent(id).getOrThrow()
        }
    }

    @Test
    fun `ending a series a sync adapter stored without a zone or all-day flag leaves them unset`() = runTest {
        val id = f.provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to SYNCED_CAL, Events.TITLE to "Standup", Events.DTSTART to T0,
            Events.DURATION to "P3600S", Events.RRULE to "FREQ=WEEKLY", Events._SYNC_ID to "adapter-series",
        )

        f.writer.deleteThisAndFuture(id, T0 + 2 * WEEK, isAllDay = false).getOrThrow()

        val row = f.eventRow(id)!!
        assertNull(row[Events.EVENT_TIMEZONE])
        assertNull(row[Events.ALL_DAY])
        assertEquals("P3600S", row[Events.DURATION])
        assertEquals(T0.toString(), row[Events.DTSTART])
        assertTrue(row[Events.RRULE]!!.contains("UNTIL="))
        assertEquals(setOf(Events.DTSTART, Events.DURATION, Events.RRULE), endingUpdateOf(id))
    }

    @Test
    fun `ending a series writes nothing when its stored start and length can't be read`() = runTest {
        val id = createSeries(seriesDraft(rrule = "FREQ=WEEKLY;BYDAY=TU"))
        val rowsBefore = f.provider.rows(SqliteCalendarProvider.EVENTS)
        for (failure in SqliteCalendarProvider.QueryFailure.entries) {
            // Only the read of the fields the ending update carries fails; the series itself
            // reads fine.
            f.provider.exactProjectionFailures[timeColumns] = failure

            val deleted = f.writer.deleteThisAndFuture(id, occ(2), isAllDay = false)
            val split = f.writer.editThisAndFuture(id, occ(2), seriesDraft(rrule = "FREQ=WEEKLY;BYDAY=TU").copy(startTs = occ(2), endTs = occ(2) + HOUR))

            f.provider.exactProjectionFailures.clear()
            assertTrue("$failure must fail the delete", deleted.isFailure)
            assertTrue("$failure must fail the split", split.isFailure)
            assertEquals("$failure must write nothing", rowsBefore, f.provider.rows(SqliteCalendarProvider.EVENTS))
        }
    }

    @Test
    fun `ending a series writes nothing when the series can't be read`() = runTest {
        val id = createSeries(seriesDraft(rrule = "FREQ=WEEKLY;BYDAY=TU"))
        val rowsBefore = f.provider.rows(SqliteCalendarProvider.EVENTS)
        for (failure in SqliteCalendarProvider.QueryFailure.entries) {
            f.provider.queryFailures[SqliteCalendarProvider.EVENTS] = failure

            val deleted = f.writer.deleteThisAndFuture(id, occ(2), isAllDay = false)
            val split = f.writer.editThisAndFuture(id, occ(2), seriesDraft(rrule = "FREQ=WEEKLY;BYDAY=TU").copy(startTs = occ(2), endTs = occ(2) + HOUR))

            f.provider.queryFailures.clear()
            assertTrue("$failure must fail the delete", deleted.isFailure)
            assertTrue("$failure must fail the split", split.isFailure)
            assertEquals("$failure must write nothing", rowsBefore, f.provider.rows(SqliteCalendarProvider.EVENTS))
        }
    }

    @Test
    fun `deleting this and future from the first occurrence removes the whole series`() = runTest {
        val id = createSeries()

        f.writer.deleteThisAndFuture(id, occ(0), isAllDay = false)

        assertTrue(march().isEmpty())
    }

    @Test
    fun `deleting a synced series removes every occurrence and the exceptions the app created`() = runTest {
        val id = createSeries()
        f.writer.editSingleOccurrence(id, occ(1), movedDraft(1))
        f.writer.deleteSingleOccurrence(id, occ(2), isAllDay = false)

        f.writer.deleteEvent(id)

        assertTrue(march().isEmpty())
        assertEquals("1", f.eventRow(id)!![Events.DELETED])
        assertTrue("never-synced exceptions go with it", exceptionRows(id).isEmpty())
    }

    // ---- reminders and quick view follow the edits ----

    @Test
    fun `a moved occurrence's reminder fires at its new time`() = runTest {
        val id = createSeries()
        f.writer.editSingleOccurrence(id, occ(1), movedDraft(1))

        val next = f.repository.getNextUpcomingReminder(setOf(SYNCED_CAL), afterMs = occ(0) + HOUR)

        assertEquals(occ(1) + 2 * HOUR - 10 * MINUTE, next?.triggerTime)
    }

    @Test
    fun `a deleted occurrence's reminder doesn't fire and the next one does`() = runTest {
        val id = createSeries()
        f.writer.deleteSingleOccurrence(id, occ(1), isAllDay = false)

        val next = f.repository.getNextUpcomingReminder(setOf(SYNCED_CAL), afterMs = occ(0) + HOUR)

        assertEquals(occ(2) - 10 * MINUTE, next?.triggerTime)
    }

    @Test
    fun `the quick view opens a series at its next occurrence and none once it has ended`() = runTest {
        val id = createSeries()

        assertEquals(occ(2), f.reader.resolveQuickViewOccurrenceStart(id, nowMs = occ(1) + 2 * DAY))
        assertNull(f.reader.resolveQuickViewOccurrenceStart(id, nowMs = occ(3) + 2 * DAY))
    }

    @Test
    fun `the quick view skips a deleted occurrence`() = runTest {
        val id = createSeries()
        f.writer.deleteSingleOccurrence(id, occ(2), isAllDay = false)

        assertEquals(occ(3), f.reader.resolveQuickViewOccurrenceStart(id, nowMs = occ(1) + 2 * DAY))
    }

    @Ignore("The next-occurrence lookup pads its window back a day and matches on overlap, so an occurrence that ended within the last day is returned instead of the next one")
    @Test
    fun `the quick view opens the next occurrence, not one that ended yesterday`() = runTest {
        val id = createSeries()

        // A day after the second occurrence started: it ended 23 hours ago.
        assertEquals(occ(2), f.reader.resolveQuickViewOccurrenceStart(id, nowMs = occ(1) + DAY))
    }

    // ---- export read ----

    @Test
    fun `the export read returns the series, every exception including cancelled ones, and their reminders`() = runTest {
        val id = createSeries()
        f.writer.editSingleOccurrence(id, occ(1), movedDraft(1))
        f.writer.deleteSingleOccurrence(id, occ(2), isAllDay = false)

        val export = f.reader.getEventWithExceptionsForExport(id)!!

        assertEquals(id, export.master.id)
        assertEquals(listOf(occ(1), occ(2)), export.exceptions.map { it.originalInstanceTime })
        assertEquals(listOf(Events.STATUS_CONFIRMED, Events.STATUS_CANCELED), export.exceptions.map { it.status })
        assertEquals(listOf(10), export.remindersById[id])
        assertEquals(listOf(10), export.remindersById[export.exceptions[0].id])
        assertFalse(export.exceptions.any { it.rrule != null })
    }

    @Test
    fun `an all-day series occurrence can be moved and deleted`() = runTest {
        val id = createSeries(f.draft(title = "Gym", isAllDay = true, startTs = DAY0, endTs = DAY0 + DAY - 1, rrule = "FREQ=DAILY;COUNT=3", reminders = emptyList()))

        f.writer.editSingleOccurrence(id, DAY0 + DAY, f.draft(title = "Gym (moved)", isAllDay = true, startTs = DAY0 + 5 * DAY, endTs = DAY0 + 6 * DAY - 1, reminders = emptyList()))
        f.writer.deleteSingleOccurrence(id, DAY0 + 2 * DAY, isAllDay = true)

        assertEquals(listOf(DAY_CODE, DAY_CODE + 5), march().map { it.startDay })
    }
}
