package org.onekash.kashcal.reminder.receiver

import android.content.Intent
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.onekash.kashcal.reminder.notification.ReminderNotificationManager
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ReminderAlarmReceiver.
 *
 * Two groups:
 * - Contract tests: intent action/extra constants stay in sync with the scheduler.
 * - Fire-path tests: drive the extracted [ReminderAlarmReceiver.handleAlarm]
 *   directly with explicit dependencies. `@AndroidEntryPoint`'s generated
 *   `onReceive` re-runs field injection on every dispatch, clobbering any
 *   values a test sets manually — so the receiver exposes `handleAlarm(deps...)`
 *   for testability, mirroring DeviceCalendarAlarmReceiver.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReminderAlarmReceiverTest {

    private lateinit var receiver: ReminderAlarmReceiver

    @Before
    fun setup() {
        receiver = ReminderAlarmReceiver()
    }

    // ==================== Contract Tests ====================

    @Test
    fun `ACTION_REMINDER_ALARM constant has correct value`() {
        assertEquals(
            "org.onekash.kashcal.REMINDER_ALARM",
            ReminderScheduler.ACTION_REMINDER_ALARM
        )
    }

    @Test
    fun `EXTRA_REMINDER_ID constant has correct value`() {
        assertEquals("reminder_id", ReminderScheduler.EXTRA_REMINDER_ID)
    }

    @Test
    fun `intent without action does not match expected action`() {
        val intent = Intent()
        assertNotEquals(ReminderScheduler.ACTION_REMINDER_ALARM, intent.action)
    }

    @Test
    fun `intent with wrong action does not match expected action`() {
        val intent = Intent("wrong.action")
        assertNotEquals(ReminderScheduler.ACTION_REMINDER_ALARM, intent.action)
    }

    @Test
    fun `intent missing EXTRA_REMINDER_ID defaults to -1`() {
        val intent = Intent(ReminderScheduler.ACTION_REMINDER_ALARM)
        assertEquals(-1L, intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1))
    }

    @Test
    fun `intent with valid EXTRA_REMINDER_ID returns correct value`() {
        val intent = Intent(ReminderScheduler.ACTION_REMINDER_ALARM)
        intent.putExtra(ReminderScheduler.EXTRA_REMINDER_ID, 42L)
        assertEquals(42L, intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1))
    }

    // ==================== Fire-path Tests (handleAlarm) ====================

    @Test
    fun `handleAlarm shows notification and marks fired for a live event`() = runTest {
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        val reminder = reminder(status = ReminderStatus.PENDING)
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns true
        coEvery { scheduler.hasLiveOccurrenceForReminder(reminder) } returns true
        coEvery { notificationManager.showNotification(reminder) } returns 1
        coJustRun { scheduler.markAsFired(REMINDER_ID) }

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        coVerify(exactly = 1) { notificationManager.showNotification(reminder) }
        coVerify(exactly = 1) { scheduler.markAsFired(REMINDER_ID) }
    }

    @Test
    fun `handleAlarm suppresses notification and cleans up when event is gone`() = runTest {
        // User's bug: event deleted (or pulled-as-deleted) after the alarm was
        // armed. shouldFireReminder returns false → no notification, and the
        // stale row + sibling alarms are cleaned up.
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder(status = ReminderStatus.PENDING)
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns false
        coJustRun { scheduler.cancelRemindersForEvent(EVENT_ID) }

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        coVerify(exactly = 0) { notificationManager.showNotification(any()) }
        coVerify(exactly = 0) { scheduler.markAsFired(any()) }
        coVerify(exactly = 1) { scheduler.cancelRemindersForEvent(EVENT_ID) }
    }

    @Test
    fun `handleAlarm suppresses and cleans up the slot when its occurrence is cancelled`() = runTest {
        // User's bug: an organizer cancels a single instance of a recurring series
        // (server-pulled EXDATE / cancelled exception). The master event is still
        // live, so shouldFireReminder passes — but the occurrence-level guard finds
        // no live occurrence. Suppress, and clean up ONLY this slot's reminder
        // (cancelReminderForOccurrence), not every reminder on the master — the
        // series' other live occurrences must keep their reminders.
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        val reminder = reminder(status = ReminderStatus.PENDING)
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns true
        coEvery { scheduler.hasLiveOccurrenceForReminder(reminder) } returns false
        coJustRun { scheduler.cancelReminderForOccurrence(EVENT_ID, reminder.occurrenceTime) }

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        coVerify(exactly = 0) { notificationManager.showNotification(any()) }
        coVerify(exactly = 0) { scheduler.markAsFired(any()) }
        coVerify(exactly = 1) { scheduler.cancelReminderForOccurrence(EVENT_ID, reminder.occurrenceTime) }
        // Must NOT nuke the whole series' reminders for a single cancelled instance.
        coVerify(exactly = 0) { scheduler.cancelRemindersForEvent(any()) }
    }

    @Test
    fun `handleAlarm does nothing when reminder row is missing`() = runTest {
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        coEvery { scheduler.getReminder(REMINDER_ID) } returns null

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        coVerify(exactly = 0) { notificationManager.showNotification(any()) }
        coVerify(exactly = 0) { scheduler.shouldFireReminder(any()) }
        coVerify(exactly = 0) { scheduler.cancelRemindersForEvent(any()) }
    }

    @Test
    fun `handleAlarm skips dismissed reminder without touching event state`() = runTest {
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder(status = ReminderStatus.DISMISSED)

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        coVerify(exactly = 0) { notificationManager.showNotification(any()) }
        // DISMISSED is a no-op: don't even check the event or clean up.
        coVerify(exactly = 0) { scheduler.shouldFireReminder(any()) }
        coVerify(exactly = 0) { scheduler.cancelRemindersForEvent(any()) }
    }

    @Test
    fun `handleAlarm still notifies a FIRED reminder for a live event (reboot re-fire)`() = runTest {
        // After reboot, rescheduled alarms can re-fire reminders already marked
        // FIRED. As long as the event is live, the user must still be reminded.
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        val reminder = reminder(status = ReminderStatus.FIRED)
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns true
        coEvery { scheduler.hasLiveOccurrenceForReminder(reminder) } returns true
        coEvery { notificationManager.showNotification(reminder) } returns 1
        coJustRun { scheduler.markAsFired(REMINDER_ID) }

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        coVerify(exactly = 1) { notificationManager.showNotification(reminder) }
    }

    @Test
    fun `handleAlarm notifies a SNOOZED reminder for a live event`() = runTest {
        // SNOOZED is the one status that re-arms an alarm, so it is the most
        // likely to fire after a delete. For a live event it must still notify.
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        val reminder = reminder(status = ReminderStatus.SNOOZED)
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns true
        coEvery { scheduler.hasLiveOccurrenceForReminder(reminder) } returns true
        coEvery { notificationManager.showNotification(reminder) } returns 1
        coJustRun { scheduler.markAsFired(REMINDER_ID) }

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        coVerify(exactly = 1) { notificationManager.showNotification(reminder) }
    }

    @Test
    fun `handleAlarm suppresses a SNOOZED reminder whose event is gone`() = runTest {
        // The deferred-then-deleted case: a snoozed alarm re-fires after the
        // event was deleted → suppress and clean up, don't notify.
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder(status = ReminderStatus.SNOOZED)
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns false
        coJustRun { scheduler.cancelRemindersForEvent(EVENT_ID) }

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        coVerify(exactly = 0) { notificationManager.showNotification(any()) }
        coVerify(exactly = 1) { scheduler.cancelRemindersForEvent(EVENT_ID) }
    }

    // ==================== Helpers ====================

    private fun reminder(status: ReminderStatus) = ScheduledReminder(
        id = REMINDER_ID,
        eventId = EVENT_ID,
        occurrenceTime = System.currentTimeMillis() + 3_600_000,
        triggerTime = System.currentTimeMillis(),
        reminderOffset = "-PT15M",
        eventTitle = "Test Event",
        calendarColor = 0xFF0000,
        status = status
    )

    private companion object {
        const val REMINDER_ID = 1L
        const val EVENT_ID = 100L
    }
}
