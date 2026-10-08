package org.onekash.kashcal.reminder.scheduler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.reminder.ReminderAlarmFixture
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.DAY
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.HOUR
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.MINUTE
import org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler
import org.onekash.kashcal.reminder.receiver.BootRecoveryHandler
import org.onekash.kashcal.util.DateTimeUtils
import org.onekash.kashcal.ui.appicon.DateIconRefresher
import org.onekash.kashcal.widget.TimezoneChangeHandler
import org.onekash.kashcal.widget.WidgetUpdateManager
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Tests that reminders are armed only when they are due within the next seven days.
 *
 * Android caps an app at 500 pending alarms, and KashCal sets one alarm per reminder per
 * occurrence, so arming a month ahead lets a busy calendar reach the cap, after which every
 * further reminder is silently lost. The window is counted on the reminder's own trigger time,
 * not the occurrence start, so a reminder set a week before its event is armed as soon as that
 * week begins.
 *
 * Covers saving an event, the scan, rows left without an alarm, repeated and concurrent scans,
 * reboot recovery and a time-zone change. Runs the real scheduler, [BootRecoveryHandler] and
 * [TimezoneChangeHandler] over an in-memory database and reads the armed alarms back from
 * Robolectric's AlarmManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReminderArmingWindowTest {

    private lateinit var fixture: ReminderAlarmFixture
    private lateinit var scheduler: ReminderScheduler
    private val originalZone: TimeZone = TimeZone.getDefault()
    private val now = System.currentTimeMillis()

    @Before
    fun setup() = runTest {
        fixture = ReminderAlarmFixture(ApplicationProvider.getApplicationContext<Context>())
        fixture.seedCalendar()
        scheduler = fixture.scheduler
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
        fixture.close()
    }

    private suspend fun saveReminders(eventId: Long) {
        val event = fixture.eventReader.getEventById(eventId)!!
        val occurrences = fixture.eventReader.getOccurrencesForEventInScheduleWindow(
            eventId, ReminderScheduler.OCCURRENCE_LOOKAHEAD_DAYS
        )
        scheduler.scheduleRemindersForEvent(event, occurrences, calendarColor = -1)
    }

    // ===== The window =====

    @Test
    fun `saving an event arms only the occurrences whose reminder is due within seven days`() = runTest {
        val event = fixture.insertEvent("Standup", now + 1 * DAY, listOf("-PT15M"))
        fixture.insertOccurrence(event, now + 6 * DAY)
        fixture.insertOccurrence(event, now + 10 * DAY)

        saveReminders(event.id)

        assertEquals(
            setOf(now + 1 * DAY - 15 * MINUTE, now + 6 * DAY - 15 * MINUTE),
            fixture.armedReminders().values.toSet()
        )
        assertEquals(2, fixture.reminderRowCount())
    }

    @Test
    fun `saving an event arms a week-before reminder once its week begins`() = runTest {
        val inRange = fixture.insertEvent("Trip", now + 7 * DAY + 12 * HOUR, listOf("-P1W"))
        val tooFar = fixture.insertEvent("Conference", now + 15 * DAY, listOf("-P1W"))

        saveReminders(inRange.id)
        saveReminders(tooFar.id)

        assertEquals(listOf(now + 12 * HOUR), fixture.armedReminders().values.toList())
        assertEquals(1, fixture.reminderRowCount())
    }

    @Test
    fun `scan arms a week-before reminder once its week begins`() = runTest {
        fixture.insertEvent("Trip", now + 7 * DAY + 12 * HOUR, listOf("-P1W"))
        fixture.insertEvent("Conference", now + 15 * DAY, listOf("-P1W"))

        val scheduled = scheduler.scheduleUpcomingReminders()

        assertEquals(1, scheduled)
        assertEquals(listOf(now + 12 * HOUR), fixture.armedReminders().values.toList())
    }

    @Test
    fun `scan creates rows and alarms only for reminders due within seven days`() = runTest {
        fixture.insertEvent("Soon", now + 2 * DAY, listOf("-PT15M"))
        fixture.insertEvent("Later", now + 10 * DAY, listOf("-PT15M"))

        val scheduled = scheduler.scheduleUpcomingReminders()

        assertEquals(1, scheduled)
        assertEquals(listOf(now + 2 * DAY - 15 * MINUTE), fixture.armedReminders().values.toList())
        assertEquals(1, fixture.reminderRowCount())
    }

    // ===== Rows that exist without an alarm =====

    @Test
    fun `scan arms an existing pending reminder that has no alarm once it is in range`() = runTest {
        val start = now + 3 * DAY
        val event = fixture.insertEvent("Review", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE)

        val scheduled = scheduler.scheduleUpcomingReminders()

        assertEquals("an existing row is re-armed, not newly scheduled", 0, scheduled)
        assertEquals(mapOf(rowId to start - 15 * MINUTE), fixture.armedReminders())
        assertEquals(1, fixture.reminderRowCount())
    }

    @Test
    fun `scan re-arms a snoozed reminder at its snoozed time`() = runTest {
        val start = now + 1 * DAY
        val event = fixture.insertEvent("Call", start, listOf("-PT15M"))
        val snoozedUntil = now + 10 * MINUTE
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", snoozedUntil, ReminderStatus.SNOOZED)

        scheduler.scheduleUpcomingReminders()

        assertEquals(mapOf(rowId to snoozedUntil), fixture.armedReminders())
    }

    @Test
    fun `scan never arms a fired or dismissed reminder`() = runTest {
        val firstStart = now + 2 * DAY
        val secondStart = now + 4 * DAY
        val event = fixture.insertEvent("Lunch", firstStart, listOf("-PT15M"))
        fixture.insertOccurrence(event, secondStart)
        fixture.insertReminderRow(event, firstStart, "-PT15M", firstStart - 15 * MINUTE, ReminderStatus.FIRED)
        fixture.insertReminderRow(event, secondStart, "-PT15M", secondStart - 15 * MINUTE, ReminderStatus.DISMISSED)

        scheduler.scheduleUpcomingReminders()

        assertTrue(fixture.armedReminders().isEmpty())
    }

    @Test
    fun `scan does not arm a pending reminder whose stored time has passed`() = runTest {
        val start = now + 2 * DAY
        val event = fixture.insertEvent("Stale", start, listOf("-PT15M"))
        fixture.insertReminderRow(event, start, "-PT15M", now - HOUR)

        scheduler.scheduleUpcomingReminders()

        assertTrue(fixture.armedReminders().isEmpty())
    }

    // ===== No duplicates =====

    @Test
    fun `scanning again adds no alarms and no rows`() = runTest {
        fixture.insertEvent("Soon", now + 2 * DAY, listOf("-PT15M", "-PT1H"))
        fixture.insertEvent("Also soon", now + 5 * DAY, listOf("-PT15M"))

        scheduler.scheduleUpcomingReminders()
        val alarmsAfterFirst = fixture.reminderAlarms()
        val rowsAfterFirst = fixture.reminderRowCount()
        scheduler.scheduleUpcomingReminders()

        assertEquals(3, alarmsAfterFirst.size)
        assertEquals(alarmsAfterFirst.sortedBy { it.first }, fixture.reminderAlarms().sortedBy { it.first })
        assertEquals(rowsAfterFirst, fixture.reminderRowCount())
    }

    @Test
    fun `a scan after saving adds no alarms and no rows`() = runTest {
        val event = fixture.insertEvent("Standup", now + 1 * DAY, listOf("-PT15M"))
        fixture.insertOccurrence(event, now + 3 * DAY)

        saveReminders(event.id)
        val alarmsAfterSave = fixture.reminderAlarms()
        scheduler.scheduleUpcomingReminders()

        assertEquals(2, alarmsAfterSave.size)
        assertEquals(alarmsAfterSave.sortedBy { it.first }, fixture.reminderAlarms().sortedBy { it.first })
        assertEquals(2, fixture.reminderRowCount())
    }

    @Test
    fun `scans running at the same time leave one alarm per reminder`() = runTest {
        repeat(20) { i ->
            fixture.insertEvent("Event $i", now + 1 * DAY + i * HOUR, listOf("-PT15M"))
        }

        (1..4).map { async(Dispatchers.IO) { scheduler.scheduleUpcomingReminders() } }.awaitAll()

        val alarms = fixture.reminderAlarms()
        assertEquals(20, fixture.reminderRowCount())
        assertEquals(20, alarms.size)
        assertEquals(20, alarms.map { it.second }.toSet().size)
    }

    @Test
    fun `a scan never blocks reminders saved inside a database transaction`() = runTest {
        // A subscription refresh saves events and schedules their reminders in one
        // transaction while a fired reminder's refill may be scanning.
        repeat(30) { i ->
            fixture.insertEvent("Busy $i", now + 1 * DAY + i * HOUR, listOf("-PT15M"))
        }
        val imported = fixture.insertEvent("Imported", now + 2 * DAY, listOf("-PT30M"))
        val outsideTransaction = CoroutineScope(Dispatchers.IO)
        var scan: Deferred<Int>? = null

        val finished = withContext(Dispatchers.Default) {
            withTimeoutOrNull(10_000) {
                fixture.database.runInTransaction {
                    scan = outsideTransaction.async { scheduler.scheduleUpcomingReminders() }
                    delay(300)
                    saveReminders(imported.id)
                }
                scan!!.await()
            }
        }

        assertNotNull("the scan and the transaction waited on each other", finished)
        assertEquals(31, fixture.reminderRowCount())
        assertEquals(31, fixture.reminderAlarms().size)
    }

    // ===== Reboot and time-zone change =====

    @Test
    fun `rescheduling after a reboot arms only pending reminders due within seven days`() = runTest {
        val event = fixture.insertEvent("Series", now + 1 * DAY, listOf("-PT15M"))
        val soon = fixture.insertReminderRow(event, now + 1 * DAY, "-PT15M", now + 1 * DAY - 15 * MINUTE)
        fixture.insertReminderRow(event, now + 10 * DAY, "-PT15M", now + 10 * DAY - 15 * MINUTE)
        val snoozed = fixture.insertReminderRow(event, now - HOUR, "-PT15M", now + 10 * MINUTE, ReminderStatus.SNOOZED)
        fixture.insertReminderRow(event, now + 2 * DAY, "-PT15M", now + 2 * DAY - 15 * MINUTE, ReminderStatus.FIRED)

        scheduler.rescheduleAllPending()

        assertEquals(
            mapOf(soon to now + 1 * DAY - 15 * MINUTE, snoozed to now + 10 * MINUTE),
            fixture.armedReminders()
        )
    }

    @Test
    fun `rescheduling updates an all-day reminder beyond the window without arming it`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val occurrence = utcMidnightDaysFromNow(12)
        val event = fixture.insertEvent("Birthday", occurrence, listOf("PT9H"), isAllDay = true)
        // Stored as it was computed in UTC, before the zone changed
        val rowId = fixture.insertReminderRow(event, occurrence, "PT9H", occurrence + 9 * HOUR)

        scheduler.rescheduleAllPending()

        val expected = DateTimeUtils.allDayReminderTriggerTime(occurrence, 9 * HOUR, ZoneId.of("America/New_York"))
        assertEquals(expected, fixture.reminderRow(rowId)?.triggerTime)
        assertTrue(fixture.armedReminders().isEmpty())
    }

    @Test
    fun `boot recovery arms every pending reminder inside the window and none beyond it`() = runTest {
        val event = fixture.insertEvent("Series", now + 1 * DAY, listOf("-PT15M"))
        val first = fixture.insertReminderRow(event, now + 1 * DAY, "-PT15M", now + 1 * DAY - 15 * MINUTE)
        val second = fixture.insertReminderRow(event, now + 6 * DAY, "-PT15M", now + 6 * DAY - 15 * MINUTE)
        fixture.insertReminderRow(event, now + 8 * DAY, "-PT15M", now + 8 * DAY - 15 * MINUTE)
        fixture.insertReminderRow(event, now + 20 * DAY, "-PT15M", now + 20 * DAY - 15 * MINUTE)
        fixture.clearAlarms()

        BootRecoveryHandler(
            scheduler,
            mockk<DeviceCalendarReminderScheduler>(relaxed = true),
            mockk<WidgetUpdateManager>(relaxed = true)
        ).rescheduleReminders()

        assertEquals(
            mapOf(first to now + 1 * DAY - 15 * MINUTE, second to now + 6 * DAY - 15 * MINUTE),
            fixture.armedReminders()
        )
    }

    @Test
    fun `a time-zone change moves in-window all-day alarms and leaves none at a stale time beyond the window`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val nearDay = utcMidnightDaysFromNow(3)
        val farDay = utcMidnightDaysFromNow(9)
        val near = fixture.insertEvent("Holiday", nearDay, listOf("PT9H"), isAllDay = true)
        val far = fixture.insertEvent("Anniversary", farDay, listOf("PT9H"), isAllDay = true)
        val nearRow = fixture.insertReminderRow(near, nearDay, "PT9H", nearDay + 9 * HOUR)
        val farRow = fixture.insertReminderRow(far, farDay, "PT9H", farDay + 9 * HOUR)
        // Both armed at their UTC times, as an install that armed 30 days ahead would have
        assertTrue(scheduler.scheduleAlarm(nearRow, nearDay + 9 * HOUR))
        assertTrue(scheduler.scheduleAlarm(farRow, farDay + 9 * HOUR))

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        TimezoneChangeHandler(
            mockk<WidgetUpdateManager>(relaxed = true),
            scheduler,
            mockk<DeviceCalendarReminderScheduler>(relaxed = true),
            mockk<DateIconRefresher>(relaxed = true),
        ).handleChange("timezone_changed")

        val tokyo = ZoneId.of("Asia/Tokyo")
        val nearTrigger = DateTimeUtils.allDayReminderTriggerTime(nearDay, 9 * HOUR, tokyo)
        assertEquals(mapOf(nearRow to nearTrigger), fixture.armedReminders())
        val farTrigger = DateTimeUtils.allDayReminderTriggerTime(farDay, 9 * HOUR, tokyo)
        assertEquals(farTrigger, fixture.reminderRow(farRow)?.triggerTime)
    }

    @Test
    fun `a time-zone change leaves a timed alarm beyond the window alone`() = runTest {
        val start = now + 20 * DAY
        val event = fixture.insertEvent("Far meeting", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE)
        assertTrue(scheduler.scheduleAlarm(rowId, start - 15 * MINUTE))

        scheduler.rescheduleAllPending()

        // Same instant in every zone, so the alarm an older install set is still right
        assertEquals(mapOf(rowId to start - 15 * MINUTE), fixture.armedReminders())
    }

    private fun utcMidnightDaysFromNow(days: Long): Long =
        LocalDate.now(ZoneOffset.UTC).plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
