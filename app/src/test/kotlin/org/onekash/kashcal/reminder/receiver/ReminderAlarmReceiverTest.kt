package org.onekash.kashcal.reminder.receiver

import android.content.Intent
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
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
 * Tests [ReminderAlarmReceiver].
 *
 * - Contract: the alarm action and reminder-id extra, and how a plain [Intent] reads a wrong
 *   action or a missing id.
 * - Fire path, through [ReminderAlarmReceiver.handleAlarm] over strict mocks: a live reminder
 *   posts and is marked fired (FIRED and SNOOZED rows included); a missing row or a DISMISSED
 *   one does nothing; a gone event cancels all its reminders; a cancelled occurrence cancels
 *   only that occurrence's reminders.
 * - Coalescing: the occurrence's other notifications are cleared after building and before
 *   posting, never its own, and none when it has one reminder; a suppressed reminder doesn't
 *   look them up, and a cleared sibling keeps its row. A failed lookup still posts; a failed
 *   build throws and clears nothing.
 * - Refill: the window is refilled after the post and the fired mark; a failed refill is
 *   swallowed, a cancelled one propagates, and a suppressed or dismissed reminder doesn't refill.
 *
 * The tests pass dependencies to handleAlarm, as DeviceCalendarAlarmReceiver's tests do, because
 * `@AndroidEntryPoint`'s generated `onReceive` re-runs field injection on every dispatch and
 * overwrites fields a test sets.
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
        coEvery { scheduler.scheduleUpcomingReminders() } returns 0

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        verify(exactly = 1) { notificationManager.postNotification(reminder, any()) }
        coVerify(exactly = 1) { scheduler.markAsFired(REMINDER_ID) }
    }

    @Test
    fun `handleAlarm suppresses notification and cleans up when event is gone`() = runTest {
        // The event was deleted, or a pull deleted it, after the alarm was armed.
        // shouldFireReminder returns false: no notification, and every reminder row and
        // alarm of the event is cancelled.
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
        // An organizer cancels one occurrence of a series (a pulled EXDATE or cancelled
        // exception). The master is live, so shouldFireReminder passes, but the
        // occurrence guard finds no live occurrence. Suppress and cancel only this
        // occurrence's reminders (cancelReminderForOccurrence); the series' other
        // occurrences must keep theirs.
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
        // One cancelled occurrence must not cancel the whole series' reminders.
        coVerify(exactly = 0) { scheduler.cancelRemindersForEvent(any()) }
        coVerify(exactly = 0) { scheduler.scheduleUpcomingReminders() }
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
        // DISMISSED is a no-op: no event check and no cleanup.
        coVerify(exactly = 0) { scheduler.shouldFireReminder(any()) }
        coVerify(exactly = 0) { scheduler.cancelRemindersForEvent(any()) }
    }

    @Test
    fun `handleAlarm still notifies a FIRED reminder for a live event (reboot re-fire)`() = runTest {
        // After a reboot, re-armed alarms can re-fire reminders already marked FIRED.
        // While the event is live, the user must still be reminded.
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        val reminder = reminder(status = ReminderStatus.FIRED)
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns true
        coEvery { scheduler.hasLiveOccurrenceForReminder(reminder) } returns true
        stubPost(notificationManager, reminder)
        coEvery { scheduler.getSiblingReminderIds(reminder) } returns emptyList()
        coJustRun { scheduler.markAsFired(REMINDER_ID) }
        coEvery { scheduler.scheduleUpcomingReminders() } returns 0

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        verify(exactly = 1) { notificationManager.postNotification(reminder, any()) }
    }

    @Test
    fun `handleAlarm notifies a SNOOZED reminder for a live event`() = runTest {
        // A snooze arms a new alarm minutes later. For a live event it must still notify.
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        val reminder = reminder(status = ReminderStatus.SNOOZED)
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns true
        coEvery { scheduler.hasLiveOccurrenceForReminder(reminder) } returns true
        stubPost(notificationManager, reminder)
        coEvery { scheduler.getSiblingReminderIds(reminder) } returns emptyList()
        coJustRun { scheduler.markAsFired(REMINDER_ID) }
        coEvery { scheduler.scheduleUpcomingReminders() } returns 0

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        verify(exactly = 1) { notificationManager.postNotification(reminder, any()) }
    }

    @Test
    fun `handleAlarm suppresses a SNOOZED reminder whose event is gone`() = runTest {
        // A snoozed alarm fires after its event was deleted: suppress and clean up.
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
    // Each reminder row has its own notification, so an occurrence with two reminders
    // (1 hour and 15 minutes before) would show the same meeting twice. The firing
    // reminder clears the occurrence's other notifications.
    //
    // These must assert positively. The sibling lookup swallows exceptions so a failed
    // lookup never costs the notification, and that also swallows the strict mock's
    // "no answer found", so a missing stub shows up as a missing cancel, not an error.

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
        coEvery { scheduler.scheduleUpcomingReminders() } returns 0

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        // Both orderings keep the user from ending up with zero notifications for the
        // occurrence. Clearing precedes the post (post-then-clear could clear the
        // survivor), and building precedes the clear (building can suspend, so failing
        // there must cost the new notification, not the one already on screen).
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
        coEvery { scheduler.scheduleUpcomingReminders() } returns 0

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
        coEvery { scheduler.scheduleUpcomingReminders() } returns 0

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        verify(exactly = 0) { notificationManager.cancelNotification(any()) }
        verify(exactly = 1) { notificationManager.postNotification(reminder, any()) }
    }

    @Test
    fun `handleAlarm still notifies when the sibling lookup fails`() = runTest {
        // Clearing other notifications is cosmetic. A broken lookup must cost the
        // tidy-up, never the reminder itself.
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        val reminder = reminder(status = ReminderStatus.PENDING)
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns true
        coEvery { scheduler.hasLiveOccurrenceForReminder(reminder) } returns true
        coEvery { scheduler.getSiblingReminderIds(reminder) } throws RuntimeException("database closed")
        stubPost(notificationManager, reminder)
        coJustRun { scheduler.markAsFired(REMINDER_ID) }
        coEvery { scheduler.scheduleUpcomingReminders() } returns 0

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
        coEvery { scheduler.scheduleUpcomingReminders() } returns 0

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        coVerify(exactly = 0) { scheduler.markAsDismissed(any()) }
        coVerify(exactly = 0) { scheduler.cancelRemindersForEvent(any()) }
        coVerify(exactly = 0) { scheduler.cancelReminderForOccurrence(any(), any()) }
    }

    @Test
    fun `handleAlarm keeps the other notifications when building its own fails`() = runTest {
        // Building comes before the clear: if the clear ran first and building then
        // failed, the user would be left with no notification for a meeting that had
        // one, which is worse than a duplicate.
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        val reminder = reminder(status = ReminderStatus.PENDING)
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns true
        coEvery { scheduler.hasLiveOccurrenceForReminder(reminder) } returns true
        // A sibling to clear, so "nothing was cleared" is an observation; an empty list
        // would pass vacuously.
        coEvery { scheduler.getSiblingReminderIds(reminder) } returns listOf(41L)
        every { notificationManager.cancelNotification(any()) } returns Unit
        coEvery { notificationManager.buildNotification(reminder) } throws
            RuntimeException("preferences unreadable")

        val result = runCatching { receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID) }

        assertTrue("A failed build must surface, not be swallowed", result.isFailure)
        verify(exactly = 0) { notificationManager.cancelNotification(any()) }
        coVerify(exactly = 0) { scheduler.markAsFired(any()) }
    }

    // ==================== Refill after firing ====================

    @Test
    fun `handleAlarm refills the reminder window after the notification is posted`() = runTest {
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        val reminder = reminder(status = ReminderStatus.PENDING)
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns true
        coEvery { scheduler.hasLiveOccurrenceForReminder(reminder) } returns true
        stubPost(notificationManager, reminder)
        coEvery { scheduler.getSiblingReminderIds(reminder) } returns emptyList()
        coJustRun { scheduler.markAsFired(REMINDER_ID) }
        coEvery { scheduler.scheduleUpcomingReminders() } returns 3

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        coVerifyOrder {
            notificationManager.postNotification(reminder, any())
            scheduler.markAsFired(REMINDER_ID)
            scheduler.scheduleUpcomingReminders()
        }
    }

    @Test
    fun `a failed refill still leaves the reminder shown and marked fired`() = runTest {
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        val reminder = reminder(status = ReminderStatus.PENDING)
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns true
        coEvery { scheduler.hasLiveOccurrenceForReminder(reminder) } returns true
        stubPost(notificationManager, reminder)
        coEvery { scheduler.getSiblingReminderIds(reminder) } returns emptyList()
        coJustRun { scheduler.markAsFired(REMINDER_ID) }
        coEvery { scheduler.scheduleUpcomingReminders() } throws IllegalStateException("database closed")

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        verify(exactly = 1) { notificationManager.postNotification(reminder, any()) }
        coVerify(exactly = 1) { scheduler.markAsFired(REMINDER_ID) }
    }

    @Test
    fun `a cancelled refill is not swallowed`() = runTest {
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        val reminder = reminder(status = ReminderStatus.PENDING)
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns true
        coEvery { scheduler.hasLiveOccurrenceForReminder(reminder) } returns true
        stubPost(notificationManager, reminder)
        coEvery { scheduler.getSiblingReminderIds(reminder) } returns emptyList()
        coJustRun { scheduler.markAsFired(REMINDER_ID) }
        coEvery { scheduler.scheduleUpcomingReminders() } throws CancellationException("timed out")

        val thrown = runCatching { receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID) }
            .exceptionOrNull()

        assertTrue("cancellation must reach the caller; got $thrown", thrown is CancellationException)
        coVerify(exactly = 1) { scheduler.markAsFired(REMINDER_ID) }
    }

    @Test
    fun `suppressed reminders do not refill the window`() = runTest {
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder(status = ReminderStatus.PENDING)
        coEvery { scheduler.shouldFireReminder(EVENT_ID) } returns false
        coJustRun { scheduler.cancelRemindersForEvent(EVENT_ID) }

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        coVerify(exactly = 0) { scheduler.scheduleUpcomingReminders() }
    }

    @Test
    fun `a dismissed reminder does not refill the window`() = runTest {
        val scheduler = mockk<ReminderScheduler>()
        val notificationManager = mockk<ReminderNotificationManager>()
        coEvery { scheduler.getReminder(REMINDER_ID) } returns reminder(status = ReminderStatus.DISMISSED)

        receiver.handleAlarm(scheduler, notificationManager, REMINDER_ID)

        coVerify(exactly = 0) { scheduler.scheduleUpcomingReminders() }
    }

    // ==================== Helpers ====================

    /** Stubs the build-then-post pair the fire path uses for [reminder]. */
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
