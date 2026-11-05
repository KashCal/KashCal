package org.onekash.kashcal.reminder.scheduler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.TimeZone
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that a reminder whose occurrence has already started is armed while its own time is
 * still ahead. An all-day occurrence is stored at UTC midnight, but its alarms trigger from local
 * midnight (RFC 5545 §3.8.6.3), so today's "9 AM day of" reminder of an event that reaches the
 * phone after UTC midnight must still fire at 9 AM local.
 *
 * Runs the real scheduler over an in-memory database and reads alarms back from Robolectric's
 * AlarmManager. The phone zone is picked from the current UTC hour so local time is after
 * midnight and before 9 AM on the UTC date; the tests of today's 9 AM reminder skip from 21:00 to
 * 24:00 UTC, when no zone gives that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class StartedOccurrenceReminderTest {

    private lateinit var fixture: ReminderAlarmFixture
    private lateinit var savedZone: TimeZone
    private val now = System.currentTimeMillis()
    private val todayUtc = now - now % DAY
    private lateinit var zone: String

    @Before
    fun setup() = runTest {
        // Before the zone change: Robolectric's AlarmManager shadow puts back, after every test,
        // the default zone it first saw.
        fixture = ReminderAlarmFixture(ApplicationProvider.getApplicationContext<Context>())
        savedZone = TimeZone.getDefault()
        val utcHour = ((now - todayUtc) / HOUR).toInt()
        zone = "Etc/GMT+${minOf(utcHour, 12)}"
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        fixture.seedCalendar()
    }

    @After
    fun tearDown() {
        fixture.close()
        TimeZone.setDefault(savedZone)
    }

    /** Today's 9 AM local, skipping the test when it is not safely ahead. */
    private fun nineAmToday(): Long {
        val trigger = DateTimeUtils.allDayReminderTriggerTime(todayUtc, 9 * HOUR)
        assumeTrue("9 AM still ahead in $zone", trigger > now + MINUTE)
        return trigger
    }

    /** Runs the reminder step every event-change path runs (form save, sync, feed, contacts). */
    private suspend fun scheduleForEvent(event: Event) {
        val occurrences = if (event.originalEventId != null) {
            listOfNotNull(fixture.eventReader.getOccurrenceByExceptionEventId(event.id))
        } else {
            fixture.eventReader.getOccurrencesForEventInScheduleWindow(
                event.id, ReminderScheduler.OCCURRENCE_LOOKAHEAD_DAYS, ReminderScheduler.STARTED_LOOKBACK_MS
            )
        }
        fixture.scheduler.scheduleRemindersForEvent(event, occurrences, calendarColor = -1)
    }

    private suspend fun allDayToday(title: String, reminders: List<String> = listOf("PT9H")) =
        fixture.insertEvent(title, todayUtc, reminders, isAllDay = true)

    @Test
    fun `today's all-day day-of reminder is armed when the event arrives after UTC midnight`() = runTest {
        val trigger = nineAmToday()
        val event = allDayToday("Arrived today")

        scheduleForEvent(event)

        assertEquals("in $zone", listOf(trigger), fixture.armedReminders().values.toList())
    }

    @Test
    fun `the window scan arms today's all-day day-of reminder that has no row`() = runTest {
        val trigger = nineAmToday()
        allDayToday("Missed by the scan")

        fixture.scheduler.scheduleUpcomingReminders()

        assertEquals("in $zone", listOf(trigger), fixture.armedReminders().values.toList())
    }

    @Test
    fun `a timed reminder set after the start is armed once the event has started`() = runTest {
        // TRIGGER:PT10M on an event that started 5 minutes ago (RFC 5545 §3.8.6.3 allows it).
        val start = now - 5 * MINUTE
        val event = fixture.insertEvent("Started", start, listOf("PT10M"))

        scheduleForEvent(event)

        assertEquals(listOf(start + 10 * MINUTE), fixture.armedReminders().values.toList())
    }

    /** An all-day series today whose occurrence is changed by an exception with [reminders]. */
    private suspend fun exceptionToday(reminders: List<String>): Event {
        val master = allDayToday("Series")
        val exception = master.copy(
            id = 0, title = "Series changed", originalEventId = master.id,
            originalInstanceTime = todayUtc, reminders = reminders, alarmCount = reminders.size
        ).let { it.copy(id = fixture.database.eventsDao().insert(it)) }
        fixture.database.occurrencesDao().deleteForEvent(master.id)
        fixture.insertOccurrence(master, todayUtc, exceptionEventId = exception.id)
        return exception
    }

    @Test
    fun `a changed occurrence of an all-day series today gets its day-of reminder`() = runTest {
        val trigger = nineAmToday()
        val exception = exceptionToday(listOf("PT9H"))

        scheduleForEvent(exception)

        assertEquals("in $zone", listOf(trigger), fixture.armedReminders().values.toList())
        assertEquals(1, fixture.database.scheduledRemindersDao().getAllForEvent(exception.id).size)
    }

    @Test
    fun `the scan arms a changed occurrence today that inherits its series' reminder`() = runTest {
        val trigger = nineAmToday()
        val exception = exceptionToday(emptyList())

        fixture.scheduler.scheduleUpcomingReminders()

        assertEquals("in $zone", listOf(trigger), fixture.armedReminders().values.toList())
        assertEquals(1, fixture.database.scheduledRemindersDao().getAllForEvent(exception.id).size)
    }

    @Test
    fun `a started occurrence whose reminder time has passed gets nothing`() = runTest {
        // The 15 minutes before a meeting that started 5 minutes ago.
        val event = fixture.insertEvent("Too late", now - 5 * MINUTE, listOf("-PT15M"))

        scheduleForEvent(event)
        fixture.scheduler.scheduleUpcomingReminders()

        assertEquals(emptyMap<Long, Long>(), fixture.armedReminders())
        assertEquals(0, fixture.reminderRowCount())
    }

    @Test
    fun `a fired, dismissed or snoozed reminder of today is not created or armed again`() = runTest {
        // Each row's own time is still ahead, so only the existing row keeps it from coming back.
        val trigger = nineAmToday()
        val fired = allDayToday("Fired")
        val dismissed = allDayToday("Dismissed")
        val snoozed = allDayToday("Snoozed")
        fixture.insertReminderRow(fired, todayUtc, "PT9H", trigger, ReminderStatus.FIRED)
        fixture.insertReminderRow(dismissed, todayUtc, "PT9H", trigger, ReminderStatus.DISMISSED)
        val snoozedId = fixture.insertReminderRow(snoozed, todayUtc, "PT9H", trigger, ReminderStatus.FIRED)
        fixture.scheduler.snoozeReminder(snoozedId, snoozeDurationMinutes = 10)
        val snoozedAt = fixture.armedReminders().getValue(snoozedId)

        listOf(fired, dismissed, snoozed).forEach { scheduleForEvent(it) }
        fixture.scheduler.scheduleUpcomingReminders()

        assertEquals("in $zone", mapOf(snoozedId to snoozedAt), fixture.armedReminders())
        assertEquals(3, fixture.reminderRowCount())
    }

    @Test
    fun `an occurrence that started longer ago than the lookback gets nothing`() = runTest {
        // Pins the bound: an alarm 60 hours after a start 2 days ago, with no row yet, is a known
        // gap, left out to keep the scan on the start_ts index.
        val start = todayUtc - 2 * DAY
        val trigger = DateTimeUtils.allDayReminderTriggerTime(start, 60 * HOUR)
        assumeTrue("trigger still ahead in $zone", trigger > now + MINUTE)
        val event = fixture.insertEvent("Long trip", start, listOf("PT60H"), isAllDay = true)

        scheduleForEvent(event)
        fixture.scheduler.scheduleUpcomingReminders()

        assertEquals(emptyMap<Long, Long>(), fixture.armedReminders())
        assertEquals(0, fixture.reminderRowCount())
    }
}
