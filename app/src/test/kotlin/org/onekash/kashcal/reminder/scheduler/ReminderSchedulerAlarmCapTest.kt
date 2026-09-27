package org.onekash.kashcal.reminder.scheduler

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventWithOccurrenceAndColor
import org.onekash.kashcal.data.db.dao.OccurrencesDao
import org.onekash.kashcal.data.db.dao.ScheduledRemindersDao
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.onekash.kashcal.domain.reader.EventReader
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that reminder scheduling survives the platform refusing an alarm.
 *
 * AlarmManager caps each app at a fixed number of pending alarms (500 by default) and throws
 * IllegalStateException for every further set call, including one that would only replace an
 * alarm the app already holds. The cap counts exact and inexact alarms alike, so falling back to
 * an inexact alarm can't help. The refusal must be absorbed so it never reaches the event save
 * that triggered it. [ReminderScheduler.scheduleAlarm] is also checked against a revoked or
 * denied exact-alarm permission, where it falls back to an inexact alarm.
 *
 * A reminder row is written before its alarm is set. A row whose alarm was refused on first
 * scheduling is removed again, so the refresh scan writes it again from the event once alarms
 * have fired and freed room. A row that is only being re-armed (boot, app update, time zone
 * change, the scan finding an existing row, or a snooze) is kept: on update and time zone change
 * the old alarm is still set, and the platform refuses the replacement without removing it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReminderSchedulerAlarmCapTest {

    private lateinit var alarmManager: AlarmManager
    private lateinit var scheduledRemindersDao: ScheduledRemindersDao
    private lateinit var eventReader: EventReader
    private lateinit var attendeesDao: AttendeesDao
    private lateinit var scheduler: ReminderScheduler

    private val now = System.currentTimeMillis()
    private val triggerTime = now + 60 * 60 * 1000L
    private val occurrenceStart = now + 2 * 60 * 60 * 1000L
    private val insertedReminderId = 42L
    private val alarmLimitRefusal =
        IllegalStateException("Maximum limit of concurrent alarms 500 reached")

    private val event = Event(
        id = 100L,
        uid = "alarm-cap@kashcal.test",
        calendarId = 10L,
        title = "Standup",
        startTs = occurrenceStart,
        endTs = occurrenceStart + 30 * 60 * 1000L,
        dtstamp = 0L,
        reminders = listOf("-PT15M")
    )
    private val occurrence = Occurrence(
        eventId = event.id,
        calendarId = event.calendarId,
        startTs = occurrenceStart,
        endTs = occurrenceStart + 30 * 60 * 1000L,
        startDay = 20260926,
        endDay = 20260926
    )

    @Before
    fun setup() {
        alarmManager = mockk(relaxed = true)
        val context = spyk(ApplicationProvider.getApplicationContext<Context>())
        every { context.getSystemService(Context.ALARM_SERVICE) } returns alarmManager
        every { alarmManager.canScheduleExactAlarms() } returns true

        scheduledRemindersDao = mockk()
        coEvery { scheduledRemindersDao.findExisting(any(), any(), any()) } returns null
        coEvery { scheduledRemindersDao.insertIfAbsent(any()) } returns insertedReminderId
        coEvery { scheduledRemindersDao.deleteById(any()) } just runs

        eventReader = mockk()
        attendeesDao = mockk()
        coEvery { attendeesDao.getDeclinedAttendeesForEvents(any()) } returns emptyList()

        scheduler = ReminderScheduler(
            context = context,
            scheduledRemindersDao = scheduledRemindersDao,
            eventReader = eventReader,
            channels = mockk(relaxed = true),
            attendeesDao = attendeesDao,
            accountsDao = mockk<AccountsDao>(),
            calendarsDao = mockk<CalendarsDao>(),
            occurrencesDao = mockk<OccurrencesDao>()
        )
    }

    // ===== Arming a single alarm =====

    @Test
    fun `exact alarm refused at the alarm limit reports not armed without retrying inexact`() {
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws alarmLimitRefusal

        val armed = scheduler.scheduleAlarm(reminderId = 1L, triggerTime = triggerTime)

        assertFalse(armed)
        verify(exactly = 1) { alarmManager.setExactAndAllowWhileIdle(any(), triggerTime, any()) }
        verify(exactly = 0) { alarmManager.setAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun `inexact fallback refused at the alarm limit reports not armed`() {
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("revoked")
        every { alarmManager.setAndAllowWhileIdle(any(), any(), any()) } throws alarmLimitRefusal

        val armed = scheduler.scheduleAlarm(reminderId = 2L, triggerTime = triggerTime)

        assertFalse(armed)
        verify(exactly = 1) { alarmManager.setAndAllowWhileIdle(any(), triggerTime, any()) }
    }

    @Test
    fun `inexact-only alarm refused at the alarm limit reports not armed`() {
        every { alarmManager.canScheduleExactAlarms() } returns false
        every { alarmManager.setAndAllowWhileIdle(any(), any(), any()) } throws alarmLimitRefusal

        val armed = scheduler.scheduleAlarm(reminderId = 3L, triggerTime = triggerTime)

        assertFalse(armed)
        verify(exactly = 1) { alarmManager.setAndAllowWhileIdle(any(), triggerTime, any()) }
        verify(exactly = 0) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun `revoked exact permission still falls back to an inexact alarm`() {
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("revoked")

        val armed = scheduler.scheduleAlarm(reminderId = 4L, triggerTime = triggerTime)

        assertTrue(armed)
        verify(exactly = 1) { alarmManager.setAndAllowWhileIdle(any(), triggerTime, any()) }
    }

    @Test
    fun `exact and inexact both denied reports not armed`() {
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("revoked")
        every { alarmManager.setAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("denied")

        val armed = scheduler.scheduleAlarm(reminderId = 5L, triggerTime = triggerTime)

        assertFalse(armed)
    }

    @Test
    fun `exact alarm set reports armed`() {
        val armed = scheduler.scheduleAlarm(reminderId = 6L, triggerTime = triggerTime)

        assertTrue(armed)
        verify(exactly = 1) { alarmManager.setExactAndAllowWhileIdle(any(), triggerTime, any()) }
    }

    // ===== Reminder rows when an alarm is refused =====

    @Test
    fun `refused alarm drops the new reminder row so the refresh scan can retry it`() = runTest {
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws alarmLimitRefusal

        scheduler.scheduleRemindersForEvent(event, listOf(occurrence), calendarColor = 0)

        coVerify(exactly = 1) { scheduledRemindersDao.insertIfAbsent(any()) }
        coVerify(exactly = 1) { scheduledRemindersDao.deleteById(insertedReminderId) }
    }

    @Test
    fun `armed alarm keeps the new reminder row`() = runTest {
        scheduler.scheduleRemindersForEvent(event, listOf(occurrence), calendarColor = 0)

        coVerify(exactly = 1) { scheduledRemindersDao.insertIfAbsent(any()) }
        verify(exactly = 1) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
        coVerify(exactly = 0) { scheduledRemindersDao.deleteById(any()) }
    }

    @Test
    fun `refresh scan drops a refused reminder row and does not count it as scheduled`() = runTest {
        coEvery { eventReader.getEventsWithRemindersInRange(any(), any()) } returns listOf(
            EventWithOccurrenceAndColor(
                event = event,
                occurrenceStartTs = occurrence.startTs,
                occurrenceEndTs = occurrence.endTs,
                calendarColor = 0
            )
        )
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws alarmLimitRefusal

        val scheduled = scheduler.scheduleUpcomingReminders()

        assertEquals(0, scheduled)
        coVerify(exactly = 1) { scheduledRemindersDao.insertIfAbsent(any()) }
        coVerify(exactly = 1) { scheduledRemindersDao.deleteById(insertedReminderId) }
    }

    @Test
    fun `re-arming an existing reminder keeps its row when the alarm is refused`() = runTest {
        coEvery { scheduledRemindersDao.getAllPendingAfter(any()) } returns listOf(
            ScheduledReminder(
                id = 7L,
                eventId = event.id,
                occurrenceTime = occurrence.startTs,
                triggerTime = triggerTime,
                reminderOffset = "-PT15M",
                status = ReminderStatus.PENDING,
                eventTitle = event.title,
                calendarColor = 0
            )
        )
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws alarmLimitRefusal

        scheduler.rescheduleAllPending()

        verify(exactly = 1) { alarmManager.setExactAndAllowWhileIdle(any(), triggerTime, any()) }
        coVerify(exactly = 0) { scheduledRemindersDao.deleteById(any()) }
    }

    @Test
    fun `snooze keeps the reminder row when the alarm is refused`() = runTest {
        coEvery { scheduledRemindersDao.snooze(any(), any()) } just runs
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws alarmLimitRefusal

        scheduler.snoozeReminder(reminderId = 8L)

        verify(exactly = 1) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
        coVerify(exactly = 0) { scheduledRemindersDao.deleteById(any()) }
    }
}
