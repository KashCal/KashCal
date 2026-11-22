package org.onekash.kashcal.reminder.scheduler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.reminder.ReminderAlarmFixture
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.DAY
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.MINUTE
import org.onekash.kashcal.util.DateTimeUtils
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that muting a calendar and unmuting it again through Calendar notifications
 * ([org.onekash.kashcal.domain.coordinator.EventCoordinator.setCalendarMuted]) gives back the
 * reminders it had: muting cancels the alarms and keeps the rows, unmuting re-arms the rows still
 * ahead in the window and leaves those whose time has passed unarmed.
 *
 * The rows matter because the window scan alone can't rebuild every reminder: it arms a reminder
 * only while its original time is ahead, which a snoozed one has passed, and it skips an
 * occurrence that started more than [ReminderScheduler.STARTED_LOOKBACK_MS] ago. Runs the real
 * coordinator and scheduler over an in-memory database, the phone in New York, and reads alarms
 * back from Robolectric's AlarmManager. The unmute racing the scan's sweep of muted calendars
 * ends with the reminder armed whichever runs first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MuteUnmuteReminderTest {

    private lateinit var fixture: ReminderAlarmFixture
    private val savedZone = TimeZone.getDefault()
    private val now = System.currentTimeMillis()

    @Before
    fun setup() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        fixture = ReminderAlarmFixture(ApplicationProvider.getApplicationContext<Context>())
        fixture.seedCalendar()
    }

    @After
    fun tearDown() {
        fixture.close()
        TimeZone.setDefault(savedZone)
    }

    private suspend fun muteAndUnmute() {
        fixture.coordinator().setCalendarMuted(fixture.calendarId, true)
        fixture.coordinator().setCalendarMuted(fixture.calendarId, false)
    }

    @Test
    fun `today's all-day reminder is armed again after mute and unmute`() = runTest {
        // Stored at UTC midnight today; "11 PM on the day" in New York is 03:00 UTC tomorrow, so
        // it is still ahead at any time of day. The row was armed days ago, while the occurrence
        // was still ahead. The unmute's restore and the scan after it can both arm it; the snooze
        // tests pin the restore on its own.
        val todayUtc = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val event = fixture.insertEvent("Birthday", todayUtc, listOf("PT23H"), isAllDay = true)
        val trigger = DateTimeUtils.allDayReminderTriggerTime(todayUtc, 23 * 60 * MINUTE)
        assertTrue("trigger still ahead", trigger > now)
        val rowId = fixture.insertReminderRow(event, todayUtc, "PT23H", triggerTime = trigger)
        fixture.scheduler.scheduleAlarm(rowId, trigger)

        muteAndUnmute()

        assertEquals(mapOf(rowId to trigger), fixture.armedReminders())
    }

    @Test
    fun `a snoozed reminder still alerts at its snooze time after mute and unmute`() = runTest {
        // The 15-minute reminder fired 5 minutes ago and was snoozed for 10.
        val start = now + 10 * MINUTE
        val event = fixture.insertEvent("Meeting", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE, ReminderStatus.FIRED)
        fixture.scheduler.snoozeReminder(rowId, snoozeDurationMinutes = 10)
        val snoozedAt = fixture.armedReminders().getValue(rowId)

        muteAndUnmute()

        assertEquals(snoozedAt, fixture.armedReminders()[rowId])
        assertEquals(ReminderStatus.SNOOZED, fixture.reminderRow(rowId)?.status)
    }

    @Test
    fun `muting cancels the alarms and keeps the rows as they were`() = runTest {
        val start = now + 2 * DAY
        val event = fixture.insertEvent("Later", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE)
        fixture.scheduler.scheduleAlarm(rowId, start - 15 * MINUTE)

        fixture.coordinator().setCalendarMuted(fixture.calendarId, true)

        assertFalse(rowId in fixture.armedReminders())
        assertEquals(ReminderStatus.PENDING, fixture.reminderRow(rowId)?.status)
    }

    @Test
    fun `a reminder whose time has passed is not armed on unmute, so it can't post late`() = runTest {
        // Its time was 10 minutes ago; the event starts in 10.
        val start = now + 10 * MINUTE
        val event = fixture.insertEvent("Missed", start, listOf("-PT20M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT20M", start - 20 * MINUTE)
        fixture.coordinator().setCalendarMuted(fixture.calendarId, true)

        fixture.coordinator().setCalendarMuted(fixture.calendarId, false)

        assertFalse(rowId in fixture.armedReminders())
        // Restore leaves it alone (an alarm may be on its way if the calendar was never silenced).
        assertEquals(ReminderStatus.PENDING, fixture.reminderRow(rowId)?.status)
    }

    @Test
    fun `a kept row beyond the arming window stays unarmed after unmute`() = runTest {
        val start = now + 10 * DAY
        val event = fixture.insertEvent("Far", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE)

        muteAndUnmute()

        assertFalse(rowId in fixture.armedReminders())
        assertEquals(ReminderStatus.PENDING, fixture.reminderRow(rowId)?.status)
    }

    @Test
    fun `Snooze on a notification still showing after mute and unmute arms the snooze`() = runTest {
        // The notification posted before the mute stays on screen; its row is kept.
        val start = now + 30 * MINUTE
        val event = fixture.insertEvent("Shown", start, listOf("-PT45M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT45M", start - 45 * MINUTE, ReminderStatus.FIRED)

        muteAndUnmute()
        fixture.scheduler.snoozeReminder(rowId, snoozeDurationMinutes = 10)

        assertEquals(ReminderStatus.SNOOZED, fixture.reminderRow(rowId)?.status)
        assertTrue(rowId in fixture.armedReminders())
    }

    @Test
    fun `an unmute that lands between the sweep's check and its cancel keeps a snoozed reminder`() = runTest {
        // The 15-minute reminder fired 5 minutes ago and was snoozed for 10. The scan after the
        // sweep can't re-arm it (its original time has passed), so only the sweep and the
        // restore taking turns keeps it armed.
        val start = now + 10 * MINUTE
        val event = fixture.insertEvent("Meeting", start, listOf("-PT15M"))
        val trigger = now + 5 * MINUTE
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", trigger, ReminderStatus.SNOOZED)
        fixture.setMuted(fixture.calendarId, true)
        val real = fixture.database.calendarsDao()
        lateinit var scheduler: ReminderScheduler
        var unmute: Job? = null
        // The sweep reads the flag as muted; the unmute (flag write, then restore) starts at once
        // and gets half a second before the sweep cancels.
        val unmuteAfterCheck = object : CalendarsDao by real {
            override suspend fun getById(id: Long): Calendar? {
                val calendar = real.getById(id)
                if (id == fixture.calendarId && unmute == null && calendar?.isNotificationMuted == true) {
                    unmute = CoroutineScope(Dispatchers.IO).launch {
                        fixture.coordinator(scheduler).setCalendarMuted(id, false)
                    }
                    Thread.sleep(500)
                }
                return calendar
            }
        }
        scheduler = ReminderScheduler(
            context = ApplicationProvider.getApplicationContext(),
            scheduledRemindersDao = fixture.database.scheduledRemindersDao(),
            eventReader = fixture.eventReader,
            channels = fixture.channels,
            attendeesDao = fixture.database.attendeesDao(),
            accountsDao = fixture.database.accountsDao(),
            calendarsDao = unmuteAfterCheck,
            occurrencesDao = fixture.database.occurrencesDao(),
        )

        withContext(Dispatchers.IO) {
            withTimeout(10_000) {
                scheduler.scheduleUpcomingReminders()
                unmute!!.join()
            }
        }

        assertFalse(fixture.database.calendarsDao().getById(fixture.calendarId)!!.isNotificationMuted)
        assertEquals(mapOf(rowId to trigger), fixture.armedReminders())
    }

    @Test
    fun `removing a calendar's reminders for its account still deletes the rows`() = runTest {
        // Account removal uses cancelRemindersForCalendar, which must not keep rows like a mute.
        val start = now + 2 * DAY
        val event = fixture.insertEvent("Gone", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE)
        fixture.scheduler.scheduleAlarm(rowId, start - 15 * MINUTE)

        fixture.scheduler.cancelRemindersForCalendar(fixture.calendarId)

        assertFalse(rowId in fixture.armedReminders())
        assertEquals(null, fixture.reminderRow(rowId))
    }
}
