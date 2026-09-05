package org.onekash.kashcal.reminder.receiver

import android.content.Intent
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
        stubPost(notificationManager, reminder)
        coEvery { scheduler.getSiblingReminderIds(reminder) } returns emptyList()
        coJustRun { scheduler.markAsFired(REMINDER_ID) }

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        verify(exactly = 1) { notificationManager.postNotification(reminder, any()) }
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

        verify(exactly = 0) { notificationManager.postNotification(any(), any()) }
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

        verify(exactly = 0) { notificationManager.postNotification(any(), any()) }
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

        verify(exactly = 0) { notificationManager.postNotification(any(), any()) }
        coVerify(exactly = 0) { scheduler.shouldFireReminder(any()) }
        coVerify(exactly = 0) { scheduler.cancelRemindersForEvent(any()) }
    }

    @Test
    fun `handleAlarm skips dismissed reminder without touching event state`() = runTest {
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder(status = ReminderStatus.DISMISSED)

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        verify(exactly = 0) { notificationManager.postNotification(any(), any()) }
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
        stubPost(notificationManager, reminder)
        coEvery { scheduler.getSiblingReminderIds(reminder) } returns emptyList()
        coJustRun { scheduler.markAsFired(REMINDER_ID) }

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        verify(exactly = 1) { notificationManager.postNotification(reminder, any()) }
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
        stubPost(notificationManager, reminder)
        coEvery { scheduler.getSiblingReminderIds(reminder) } returns emptyList()
        coJustRun { scheduler.markAsFired(REMINDER_ID) }

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        verify(exactly = 1) { notificationManager.postNotification(reminder, any()) }
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

        verify(exactly = 0) { notificationManager.postNotification(any(), any()) }
        coVerify(exactly = 1) { scheduler.cancelRemindersForEvent(EVENT_ID) }
    }

    // ==================== Same-occurrence Coalescing Tests ====================
    //
    // An occurrence with two reminders (1 hour and 15 minutes before) posts one
    // notification per offset, so the user ends up dismissing the same meeting
    // twice. The firing reminder clears the occurrence's other notifications.
    //
    // Note these must assert positively. The sibling lookup deliberately
    // swallows exceptions so a lookup failure can never cost the user the
    // notification, and that also swallows the strict mock's "no answer found",
    // so a missing stub shows up as a missing cancel rather than an error.

    @Test
    fun `handleAlarm clears the occurrence's other notifications before posting`() = runTest {
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        val reminder = reminder(status = ReminderStatus.PENDING)
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns true
        coEvery { scheduler.hasLiveOccurrenceForReminder(reminder) } returns true
        coEvery { scheduler.getSiblingReminderIds(reminder) } returns listOf(41L, 42L)
        every { notificationManager.cancelNotification(any()) } returns Unit
        stubPost(notificationManager, reminder)
        coJustRun { scheduler.markAsFired(REMINDER_ID) }

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        // Both orderings matter, and both protect the same thing: the user must
        // never end up with zero notifications for the occurrence. Clearing has to
        // precede the post (post-then-clear could clear the survivor), and building
        // has to precede the clear (building can suspend, so failing there must cost
        // the new notification rather than the one already on screen).
        coVerifyOrder {
            notificationManager.buildNotification(reminder)
            notificationManager.cancelNotification(41L)
            notificationManager.cancelNotification(42L)
            notificationManager.postNotification(reminder, any())
        }
    }

    @Test
    fun `handleAlarm never clears its own notification`() = runTest {
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        val reminder = reminder(status = ReminderStatus.PENDING)
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns true
        coEvery { scheduler.hasLiveOccurrenceForReminder(reminder) } returns true
        coEvery { scheduler.getSiblingReminderIds(reminder) } returns listOf(41L)
        every { notificationManager.cancelNotification(any()) } returns Unit
        stubPost(notificationManager, reminder)
        coJustRun { scheduler.markAsFired(REMINDER_ID) }

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        verify(exactly = 0) { notificationManager.cancelNotification(REMINDER_ID) }
    }

    @Test
    fun `handleAlarm clears nothing when the occurrence has a single reminder`() = runTest {
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        val reminder = reminder(status = ReminderStatus.PENDING)
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns true
        coEvery { scheduler.hasLiveOccurrenceForReminder(reminder) } returns true
        coEvery { scheduler.getSiblingReminderIds(reminder) } returns emptyList()
        stubPost(notificationManager, reminder)
        coJustRun { scheduler.markAsFired(REMINDER_ID) }

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        verify(exactly = 0) { notificationManager.cancelNotification(any()) }
        verify(exactly = 1) { notificationManager.postNotification(reminder, any()) }
    }

    @Test
    fun `handleAlarm still notifies when the sibling lookup fails`() = runTest {
        // Tidying up other notifications is cosmetic. A broken lookup must cost
        // the user the tidy-up, never the reminder itself.
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        val reminder = reminder(status = ReminderStatus.PENDING)
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns true
        coEvery { scheduler.hasLiveOccurrenceForReminder(reminder) } returns true
        coEvery { scheduler.getSiblingReminderIds(reminder) } throws RuntimeException("database closed")
        stubPost(notificationManager, reminder)
        coJustRun { scheduler.markAsFired(REMINDER_ID) }

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        verify(exactly = 1) { notificationManager.postNotification(reminder, any()) }
        coVerify(exactly = 1) { scheduler.markAsFired(REMINDER_ID) }
    }

    @Test
    fun `handleAlarm does not look up siblings when the event is gone`() = runTest {
        // No notification will be posted, so there is nothing to coalesce with.
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder(status = ReminderStatus.PENDING)
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns false
        coJustRun { scheduler.cancelRemindersForEvent(EVENT_ID) }

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        coVerify(exactly = 0) { scheduler.getSiblingReminderIds(any()) }
        verify(exactly = 0) { notificationManager.cancelNotification(any()) }
    }

    @Test
    fun `handleAlarm does not look up siblings when the occurrence is cancelled`() = runTest {
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        val reminder = reminder(status = ReminderStatus.PENDING)
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns true
        coEvery { scheduler.hasLiveOccurrenceForReminder(reminder) } returns false
        coJustRun { scheduler.cancelReminderForOccurrence(EVENT_ID, reminder.occurrenceTime) }

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        coVerify(exactly = 0) { scheduler.getSiblingReminderIds(any()) }
        verify(exactly = 0) { notificationManager.cancelNotification(any()) }
    }

    @Test
    fun `handleAlarm does not mark superseded siblings dismissed`() = runTest {
        // Only the notification goes away. The sibling keeps its row and its own
        // alarm, so a snoozed reminder still comes back on its own schedule.
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        val reminder = reminder(status = ReminderStatus.PENDING)
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns true
        coEvery { scheduler.hasLiveOccurrenceForReminder(reminder) } returns true
        coEvery { scheduler.getSiblingReminderIds(reminder) } returns listOf(41L)
        every { notificationManager.cancelNotification(any()) } returns Unit
        stubPost(notificationManager, reminder)
        coJustRun { scheduler.markAsFired(REMINDER_ID) }

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        coVerify(exactly = 0) { scheduler.markAsDismissed(any()) }
        coVerify(exactly = 0) { scheduler.cancelRemindersForEvent(any()) }
        coVerify(exactly = 0) { scheduler.cancelReminderForOccurrence(any(), any()) }
    }

    @Test
    fun `handleAlarm keeps the other notifications when building its own fails`() = runTest {
        // The reason building comes first. If the clear ran first and the post then
        // failed, the user would be left with no notification at all for a meeting
        // they previously had one for — worse than the duplicate this fixes.
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        val reminder = reminder(status = ReminderStatus.PENDING)
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns true
        coEvery { scheduler.hasLiveOccurrenceForReminder(reminder) } returns true
        // A real sibling to clear, so "nothing was cleared" is a real observation
        // rather than the vacuous pass an empty list would give.
        coEvery { scheduler.getSiblingReminderIds(reminder) } returns listOf(41L)
        every { notificationManager.cancelNotification(any()) } returns Unit
        coEvery { notificationManager.buildNotification(reminder) } throws
            RuntimeException("preferences unreadable")

        val result = runCatching { receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID) }

        assertTrue("A failed build must surface, not be swallowed", result.isFailure)
        verify(exactly = 0) { notificationManager.cancelNotification(any()) }
        coVerify(exactly = 0) { scheduler.markAsFired(any()) }
    }

    // ==================== Helpers ====================

    /** Accept the build-then-post pair the fire path uses for [reminder]. */
    private fun stubPost(
        notificationManager: ReminderNotificationManager,
        reminder: ScheduledReminder,
    ) {
        coEvery { notificationManager.buildNotification(reminder) } returns mockk()
        every { notificationManager.postNotification(reminder, any()) } returns 1
    }

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
