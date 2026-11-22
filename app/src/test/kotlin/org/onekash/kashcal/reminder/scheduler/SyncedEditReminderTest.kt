package org.onekash.kashcal.reminder.scheduler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.verify
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.reminder.ReminderAlarmFixture
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.DAY
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.HOUR
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.MINUTE
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.ZoneOffset
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests what an edit of an event does to its reminders (#425). Every edit path (a pull that
 * reports the event MODIFIED, a feed refresh, a contact update, a local edit) runs
 * [ReminderScheduler.reconcileRemindersForEvent] and then
 * [ReminderScheduler.scheduleRemindersForEvent] over the event's occurrences in the window;
 * these tests run that pair over the real scheduler and an in-memory database.
 *
 * An edit that keeps the reminder keeps its row, so Snooze on the notification still on screen
 * finds it, a pending snooze still alerts, and a due reminder of an occurrence that has started
 * still fires. An edit that moves the occurrence or removes the reminder takes the row, the alarm
 * and a fired reminder's notification away together; a snooze outlives its offset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SyncedEditReminderTest {

    private lateinit var fixture: ReminderAlarmFixture
    private lateinit var savedZone: TimeZone
    private val now = System.currentTimeMillis()

    @Before
    fun setup() = runTest {
        // Before the zone change: Robolectric's AlarmManager shadow puts back, after every test,
        // the default zone it first saw.
        fixture = ReminderAlarmFixture(ApplicationProvider.getApplicationContext<Context>())
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        fixture.seedCalendar()
    }

    @After
    fun tearDown() {
        fixture.close()
        TimeZone.setDefault(savedZone)
    }

    /** Stores [edited] and runs the reminder step every edit path runs. */
    private suspend fun syncModified(edited: Event) {
        fixture.database.eventsDao().update(edited)
        fixture.scheduler.reconcileRemindersForEvent(edited)
        fixture.scheduler.scheduleRemindersForEvent(
            event = edited,
            occurrences = fixture.eventReader.getOccurrencesForEventInScheduleWindow(
                edited.id, ReminderScheduler.OCCURRENCE_LOOKAHEAD_DAYS, ReminderScheduler.STARTED_LOOKBACK_MS
            ),
            calendarColor = -1
        )
    }

    /** Moves [event]'s only occurrence to [newStart] and returns the event as stored. */
    private suspend fun moveOccurrence(event: Event, newStart: Long): Event {
        fixture.database.occurrencesDao().deleteForEvent(event.id)
        fixture.insertOccurrence(event, newStart)
        return event.copy(startTs = newStart, endTs = newStart + HOUR)
    }

    /** An event at [start] whose occurrence is changed by an exception, as a series stores one. */
    private suspend fun seriesWithException(
        start: Long,
        masterReminders: List<String>,
        exceptionReminders: List<String>
    ): Pair<Event, Event> {
        val master = fixture.insertEvent("Series", start, masterReminders)
        val exception = fixture.database.eventsDao().let { dao ->
            val row = master.copy(
                id = 0, title = "Series moved", originalEventId = master.id,
                originalInstanceTime = start, reminders = exceptionReminders,
                alarmCount = exceptionReminders.size
            )
            row.copy(id = dao.insert(row))
        }
        linkException(master, exception, start)
        return master to exception
    }

    private suspend fun linkException(master: Event, exception: Event, start: Long) {
        fixture.database.occurrencesDao().deleteForEvent(master.id)
        fixture.insertOccurrence(master, start, exceptionEventId = exception.id)
    }

    @Test
    fun `Snooze on a showing notification works after a synced title edit`() = runTest {
        // The 15-minute reminder fired 5 minutes ago; its notification is still on screen.
        val start = now + 10 * MINUTE
        val event = fixture.insertEvent("Test A", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE, ReminderStatus.FIRED)

        syncModified(event.copy(title = "Test A renamed"))
        fixture.scheduler.snoozeReminder(rowId, snoozeDurationMinutes = 15)

        // The alarm receiver looks the row up by this id and shows nothing without it.
        assertNotNull("row behind the notification", fixture.scheduler.getReminder(rowId))
        verify(exactly = 0) { fixture.channels.cancelForReminder(rowId) }
        assertEquals(ReminderStatus.SNOOZED, fixture.reminderRow(rowId)?.status)
    }

    @Test
    fun `a snoozed reminder still alerts at its snooze time after a synced title edit`() = runTest {
        val start = now + 10 * MINUTE
        val event = fixture.insertEvent("Test C", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE, ReminderStatus.FIRED)
        fixture.scheduler.snoozeReminder(rowId, snoozeDurationMinutes = 15)
        val snoozedAt = fixture.armedReminders().getValue(rowId)

        syncModified(event.copy(title = "Test C renamed"))

        assertEquals(snoozedAt, fixture.armedReminders()[rowId])
        assertEquals(ReminderStatus.SNOOZED, fixture.reminderRow(rowId)?.status)
    }

    @Test
    fun `a synced edit that removes the reminder clears its row, alarm and notification`() = runTest {
        val start = now + 10 * MINUTE
        val event = fixture.insertEvent("Removed", start, listOf("-PT15M"))
        // Fired, its notification still showing; not snoozed (a snooze outlives the offset).
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE, ReminderStatus.FIRED)

        syncModified(event.copy(reminders = emptyList(), alarmCount = 0))

        assertNull(fixture.reminderRow(rowId))
        assertFalse(rowId in fixture.armedReminders())
        verify { fixture.channels.cancelForReminder(rowId) }
    }

    @Test
    fun `today's all-day reminder still due later today survives a synced title edit`() = runTest {
        // All-day occurrences start at UTC midnight. At UTC-12 the 9 AM "day of" reminder is
        // still ahead until 21:00 UTC; the row was made before UTC midnight, when the occurrence
        // was still ahead.
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+12"))
        val todayUtc = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val trigger = DateTimeUtils.allDayReminderTriggerTime(todayUtc, 9 * HOUR)
        assumeTrue("9 AM still ahead at UTC-12", trigger > now + MINUTE)
        val event = fixture.insertEvent("Day off", todayUtc, listOf("PT9H"), isAllDay = true)
        val rowId = fixture.insertReminderRow(event, todayUtc, "PT9H", trigger)
        fixture.scheduler.scheduleAlarm(rowId, trigger)

        syncModified(event.copy(title = "Day off renamed"))

        assertEquals(mapOf(rowId to trigger), fixture.armedReminders())
    }

    @Test
    fun `a reminder set after a timed event's start survives a synced edit once the event started`() = runTest {
        // TRIGGER:PT10M (RFC 5545 §3.8.6.3 allows a positive offset): 10 minutes after a start
        // 5 minutes ago, so due in 5. Armed before the start.
        val start = now - 5 * MINUTE
        val event = fixture.insertEvent("Started", start, listOf("PT10M"))
        val trigger = start + 10 * MINUTE
        val rowId = fixture.insertReminderRow(event, start, "PT10M", trigger)
        fixture.scheduler.scheduleAlarm(rowId, trigger)

        syncModified(event.copy(title = "Started renamed"))

        assertEquals(mapOf(rowId to trigger), fixture.armedReminders())
    }

    @Test
    fun `a timed reminder still ahead is re-armed after a synced title edit`() = runTest {
        // Control: a reminder still ahead keeps its row and alarm.
        val start = now + 2 * HOUR
        val event = fixture.insertEvent("Later", start, listOf("-PT15M"))
        val trigger = start - 15 * MINUTE
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", trigger)
        fixture.scheduler.scheduleAlarm(rowId, trigger)

        syncModified(event.copy(title = "Later renamed"))

        assertEquals(mapOf(rowId to trigger), fixture.armedReminders())
        assertEquals(1, fixture.reminderRowCount())
    }

    @Test
    fun `deleting an event cancels its reminder notification still showing`() = runTest {
        // Event deletion and decline end in cancelRemindersForEvent.
        val start = now + 10 * MINUTE
        val event = fixture.insertEvent("Deleted", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE, ReminderStatus.FIRED)

        fixture.scheduler.cancelRemindersForEvent(event.id)

        assertNull(fixture.reminderRow(rowId))
        verify { fixture.channels.cancelForReminder(rowId) }
    }

    @Test
    fun `a snooze survives an edit that removes all reminders`() = runTest {
        val start = now + 10 * MINUTE
        val event = fixture.insertEvent("Snoozed", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE, ReminderStatus.FIRED)
        fixture.scheduler.snoozeReminder(rowId, snoozeDurationMinutes = 15)
        val snoozedAt = fixture.armedReminders().getValue(rowId)

        syncModified(event.copy(reminders = emptyList(), alarmCount = 0))

        assertEquals(snoozedAt, fixture.armedReminders()[rowId])
        assertEquals(ReminderStatus.SNOOZED, fixture.reminderRow(rowId)?.status)
    }

    @Test
    fun `an edit moving the occurrence drops a fired row and cancels its notification`() = runTest {
        // Moved by 30 seconds: rows are keyed by the exact start, so the row can't follow it.
        val start = now + 10 * MINUTE
        val event = fixture.insertEvent("Moved", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE, ReminderStatus.FIRED)

        syncModified(moveOccurrence(event, start + 30_000))

        assertNull(fixture.reminderRow(rowId))
        verify { fixture.channels.cancelForReminder(rowId) }
    }

    @Test
    fun `an edit moving an exception drops its fired row`() = runTest {
        val start = now + 10 * MINUTE
        val (master, exception) = seriesWithException(start, listOf("-PT15M"), listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(exception, start, "-PT15M", start - 15 * MINUTE, ReminderStatus.FIRED)

        // Linking the exception's new time moves the linked occurrence's start.
        linkException(master, exception, start + HOUR)
        syncModified(exception.copy(startTs = start + HOUR, endTs = start + 2 * HOUR))

        assertNull(fixture.reminderRow(rowId))
        verify { fixture.channels.cancelForReminder(rowId) }
    }

    @Test
    fun `changing 15 to 30 minutes drops the 15-minute alarm and arms 30`() = runTest {
        val start = now + 2 * HOUR
        val event = fixture.insertEvent("Changed", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE)
        fixture.scheduler.scheduleAlarm(rowId, start - 15 * MINUTE)

        syncModified(event.copy(reminders = listOf("-PT30M")))

        assertNull(fixture.reminderRow(rowId))
        assertEquals(listOf(start - 30 * MINUTE), fixture.armedReminders().values.toList())
    }

    @Test
    fun `after a zone change an edit keeps all-day reminders and moves their times`() = runTest {
        // Stored while the phone was in Kiritimati (UTC+14), then the phone moved to Pago Pago
        // (UTC-11) and the edit landed before the zone-change reschedule. The second day's
        // reminder moves past the 7-day window, so it loses its alarm and keeps its row.
        val kiritimati = ZoneId.of("Pacific/Kiritimati")
        val pagoPago = ZoneId.of("Pacific/Pago_Pago")
        val windowEnd = now + ReminderScheduler.SCHEDULE_WINDOW_MS
        val farDay = (0..9).map { LocalDate.now(ZoneOffset.UTC).plusDays(it.toLong()) }
            .map { it.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
            .first { day ->
                DateTimeUtils.allDayReminderTriggerTime(day, 9 * HOUR, kiritimati) <= windowEnd &&
                    DateTimeUtils.allDayReminderTriggerTime(day, 9 * HOUR, pagoPago) > windowEnd
            }
        val nearDay = farDay - 3 * DAY
        TimeZone.setDefault(TimeZone.getTimeZone(pagoPago))
        val near = fixture.insertEvent("Near", nearDay, listOf("PT9H"), isAllDay = true)
        val far = fixture.insertEvent("Far", farDay, listOf("PT9H"), isAllDay = true)
        val nearOld = DateTimeUtils.allDayReminderTriggerTime(nearDay, 9 * HOUR, kiritimati)
        val farOld = DateTimeUtils.allDayReminderTriggerTime(farDay, 9 * HOUR, kiritimati)
        val nearId = fixture.insertReminderRow(near, nearDay, "PT9H", nearOld)
        val farId = fixture.insertReminderRow(far, farDay, "PT9H", farOld)
        fixture.scheduler.scheduleAlarm(nearId, nearOld)
        fixture.scheduler.scheduleAlarm(farId, farOld)

        syncModified(near.copy(title = "Near renamed"))
        syncModified(far.copy(title = "Far renamed"))

        val nearNew = DateTimeUtils.allDayReminderTriggerTime(nearDay, 9 * HOUR, pagoPago)
        assertEquals(nearNew, fixture.reminderRow(nearId)?.triggerTime)
        assertEquals(nearNew, fixture.armedReminders()[nearId])
        assertEquals(DateTimeUtils.allDayReminderTriggerTime(farDay, 9 * HOUR, pagoPago), fixture.reminderRow(farId)?.triggerTime)
        assertFalse(farId in fixture.armedReminders())
    }

    @Test
    fun `a rename updates the kept rows and leaves other events alone`() = runTest {
        val start = now + 10 * MINUTE
        val event = fixture.insertEvent("Old title", start, listOf("-PT15M", "-PT5M"))
        val firedId = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE, ReminderStatus.FIRED)
        val dismissedId = fixture.insertReminderRow(event, start, "-PT5M", start - 5 * MINUTE, ReminderStatus.DISMISSED)
        val other = fixture.insertEvent("Other", start, listOf("-PT15M"))
        val otherId = fixture.insertReminderRow(other, start, "-PT15M", start - 15 * MINUTE, ReminderStatus.FIRED)

        syncModified(event.copy(title = "New title", location = "Room 2"))

        assertEquals("New title", fixture.reminderRow(firedId)?.eventTitle)
        assertEquals("Room 2", fixture.reminderRow(firedId)?.eventLocation)
        val color = fixture.database.calendarsDao().getById(event.calendarId)!!.color
        assertEquals(color, fixture.reminderRow(firedId)?.calendarColor)
        assertEquals(ReminderStatus.DISMISSED, fixture.reminderRow(dismissedId)?.status)
        assertEquals("Other", fixture.reminderRow(otherId)?.eventTitle)
    }

    @Test
    fun `an event moved into a muted calendar keeps its reminder rows without alarms`() = runTest {
        val start = now + 2 * HOUR
        val event = fixture.insertEvent("Quiet", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE)
        fixture.scheduler.scheduleAlarm(rowId, start - 15 * MINUTE)
        val muted = fixture.addCalendar("Muted")
        fixture.setMuted(muted, true)
        fixture.database.occurrencesDao().deleteForEvent(event.id)
        val moved = event.copy(calendarId = muted)
        fixture.insertOccurrence(moved, start)

        syncModified(moved)

        assertFalse(rowId in fixture.armedReminders())
        assertEquals(ReminderStatus.PENDING, fixture.reminderRow(rowId)?.status)
    }

    @Test
    fun `an exception with no reminders of its own keeps rows made with its master's offset`() = runTest {
        val start = now + 10 * MINUTE
        val (_, exception) = seriesWithException(start, listOf("-PT15M"), emptyList())
        val rowId = fixture.insertReminderRow(exception, start, "-PT15M", start - 15 * MINUTE, ReminderStatus.FIRED)

        syncModified(exception.copy(title = "Series moved again"))

        assertNotNull(fixture.reminderRow(rowId))
    }

    @Test
    fun `an exception with its own reminders keeps a row made with its master's offset`() = runTest {
        // The master's step arms its own offsets for every occurrence, the changed one included.
        val start = now + 10 * MINUTE
        val (_, exception) = seriesWithException(start, listOf("-PT15M"), listOf("-PT5M"))
        val rowId = fixture.insertReminderRow(exception, start, "-PT15M", start - 15 * MINUTE, ReminderStatus.FIRED)

        syncModified(exception.copy(title = "Series moved again"))

        assertNotNull(fixture.reminderRow(rowId))
    }

    @Test
    fun `cancelling one occurrence's reminders cancels its fired notification`() = runTest {
        val start = now + 10 * MINUTE
        val event = fixture.insertEvent("One", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE, ReminderStatus.FIRED)

        fixture.scheduler.cancelReminderForOccurrence(event.id, start)

        assertNull(fixture.reminderRow(rowId))
        verify { fixture.channels.cancelForReminder(rowId) }
    }

    @Test
    fun `cancelling the reminders of later occurrences cancels their fired notifications`() = runTest {
        val start = now + 10 * MINUTE
        val event = fixture.insertEvent("Later ones", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE, ReminderStatus.FIRED)

        fixture.scheduler.cancelRemindersForOccurrencesAfter(event.id, start)

        assertNull(fixture.reminderRow(rowId))
        verify { fixture.channels.cancelForReminder(rowId) }
    }

    @Test
    fun `a reminder written as -PT24H survives a pull that spells it -P1D`() = runTest {
        // The event form writes 1 day before as -PT24H (ContactEventUtils.minutesToIsoDuration);
        // a pull of the same VALARM stores -P1D (DurationUtils.format).
        val start = now + 10 * MINUTE
        val event = fixture.insertEvent("Spelled", start, listOf("-PT24H"))
        val rowId = fixture.insertReminderRow(event, start, "-PT24H", start - 24 * HOUR, ReminderStatus.FIRED)

        syncModified(event.copy(title = "Spelled renamed", reminders = listOf("-P1D")))
        fixture.scheduler.snoozeReminder(rowId, snoozeDurationMinutes = 15)

        assertEquals(ReminderStatus.SNOOZED, fixture.reminderRow(rowId)?.status)
        assertEquals("one row for the reminder", 1, fixture.reminderRowCount())
    }

    @Test
    fun `a pending at-start reminder written as PT0S keeps one row and alarm after a save as PT0M`() = runTest {
        // A pull stores "at the start" as PT0S; the event form saves it as PT0M.
        val start = now + 2 * HOUR
        val event = fixture.insertEvent("At start", start, listOf("PT0S"))
        val rowId = fixture.insertReminderRow(event, start, "PT0S", start)
        fixture.scheduler.scheduleAlarm(rowId, start)

        syncModified(event.copy(title = "At start renamed", reminders = listOf("PT0M")))

        assertEquals(mapOf(rowId to start), fixture.armedReminders())
        assertEquals(1, fixture.reminderRowCount())
        assertEquals("PT0M", fixture.reminderRow(rowId)?.reminderOffset)
    }

    @Test
    fun `an edit that cancels the occurrence drops its fired row and notification`() = runTest {
        val start = now + 10 * MINUTE
        val event = fixture.insertEvent("Skipped", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE, ReminderStatus.FIRED)
        fixture.database.occurrencesDao().deleteForEvent(event.id)
        fixture.insertOccurrence(event, start, isCancelled = true)

        syncModified(event.copy(title = "Skipped renamed"))

        assertNull(fixture.reminderRow(rowId))
        verify { fixture.channels.cancelForReminder(rowId) }
    }

    @Test
    fun `a master's row for an occurrence now changed by an exception is dropped`() = runTest {
        // The changed occurrence's reminders live under the exception's id.
        val start = now + 10 * MINUTE
        val master = fixture.insertEvent("Series", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(master, start, "-PT15M", start - 15 * MINUTE, ReminderStatus.FIRED)
        val exception = master.copy(id = 0, originalEventId = master.id, originalInstanceTime = start)
            .let { it.copy(id = fixture.database.eventsDao().insert(it)) }
        linkException(master, exception, start)

        syncModified(master.copy(title = "Series renamed"))

        assertNull(fixture.reminderRow(rowId))
    }

    @Test
    fun `all-day reminders written over a day before survive a pull that spells them with days`() = runTest {
        // The all-day picker's 2 days and 1 week before (9 AM) are -PT39H and -PT159H from the
        // event form; a pull stores the same VALARMs as -P1DT15H and -P6DT15H.
        val day = LocalDate.now(ZoneOffset.UTC).plusDays(10)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val event = fixture.insertEvent("Trip", day, listOf("-PT39H", "-PT159H"), isAllDay = true)
        val twoDays = DateTimeUtils.allDayReminderTriggerTime(day, -39 * HOUR)
        val week = DateTimeUtils.allDayReminderTriggerTime(day, -159 * HOUR)
        val twoDaysId = fixture.insertReminderRow(event, day, "-PT39H", twoDays)
        val weekId = fixture.insertReminderRow(event, day, "-PT159H", week)
        fixture.scheduler.scheduleAlarm(twoDaysId, twoDays)
        fixture.scheduler.scheduleAlarm(weekId, week)

        syncModified(event.copy(title = "Trip renamed", reminders = listOf("-P1DT15H", "-P6DT15H")))

        assertEquals(mapOf(twoDaysId to twoDays, weekId to week), fixture.armedReminders())
        assertEquals(2, fixture.reminderRowCount())
        assertEquals("-P1DT15H", fixture.reminderRow(twoDaysId)?.reminderOffset)
        assertEquals("-P6DT15H", fixture.reminderRow(weekId)?.reminderOffset)
    }
}
