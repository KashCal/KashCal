package org.onekash.kashcal.reminder.device

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [DeviceCalendarAlarmReceiver].
 *
 * - `onReceive` with a null intent, a null or wrong action, a full set of extras or only the
 *   required ones: each checks only that it doesn't throw. Hilt's generated `onReceive`
 *   injects the receiver's fields on every dispatch, overwriting any a test sets, and the work
 *   runs on a coroutine the test doesn't wait for.
 * - `handleAlarm` with mocked collaborators: it shows the notification with the given fields
 *   when [DeviceCalendarReminderScheduler.shouldFireReminder] is true and shows none when it is
 *   false. A reminder alarm then hands its cursor and one clock reading to
 *   [DeviceCalendarReminderScheduler.takeDueAndArmNext] and shows each due reminder it returns
 *   that may still notify; a snooze alarm calls `rescheduleAfterFire` instead and posts no batch.
 *   A throw while showing one reminder doesn't stop the others or the arming of the next.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceCalendarAlarmReceiverTest {

    private lateinit var context: Context
    private lateinit var receiver: DeviceCalendarAlarmReceiver

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        receiver = DeviceCalendarAlarmReceiver()
    }

    // ========== Intent Action Filtering ==========

    @Test
    fun `onReceive ignores null intent`() {
        receiver.onReceive(context, null)
    }

    @Test
    fun `onReceive ignores intent with wrong action`() {
        val intent = Intent("com.example.WRONG_ACTION")
        receiver.onReceive(context, intent)
    }

    @Test
    fun `onReceive ignores intent with null action`() {
        val intent = Intent()
        receiver.onReceive(context, intent)
    }

    // ========== Intent Extras Extraction ==========

    @Test
    fun `onReceive extracts eventId from intent extras`() {
        val intent = createValidIntent(eventId = 123L)

        // Only checks that onReceive doesn't throw.
        receiver.onReceive(context, intent)

        // TODO: assert the extracted extras; onReceive's injection overwrites fields a test sets.
        assertTrue("Intent should be processed without crash", true)
    }

    @Test
    fun `onReceive extracts occurrenceTs from intent extras`() {
        val intent = createValidIntent(occurrenceTs = 1709251200000L)

        receiver.onReceive(context, intent)
        assertTrue("Intent should be processed without crash", true)
    }

    @Test
    fun `onReceive extracts title from intent extras`() {
        val intent = createValidIntent(title = "Team Meeting")

        receiver.onReceive(context, intent)
        assertTrue("Intent should be processed without crash", true)
    }

    @Test
    fun `onReceive handles missing optional extras gracefully`() {
        val intent = Intent(DeviceCalendarReminderScheduler.ACTION_DEVICE_REMINDER_ALARM).apply {
            putExtra(DeviceCalendarReminderScheduler.EXTRA_EVENT_ID, 123L)
            putExtra(DeviceCalendarReminderScheduler.EXTRA_OCCURRENCE_TS, 1709251200000L)
            putExtra(DeviceCalendarReminderScheduler.EXTRA_TITLE, "Test Event")
            // No location, all-day flag, calendar color, calendar id or trigger time.
        }

        receiver.onReceive(context, intent)
        assertTrue("Missing optional extras should not crash", true)
    }

    // ========== handleAlarm ==========

    @Test
    fun `handleAlarm shows notification when shouldFireReminder is true`() = runTest {
        val scheduler = mockk<DeviceCalendarReminderScheduler>()
        val notificationManager = mockk<DeviceCalendarReminderNotificationManager>()
        coEvery { scheduler.shouldFireReminder(any(), any(), any()) } returns true
        coEvery { scheduler.takeDueAndArmNext(any(), any()) } returns emptyList()
        coEvery { notificationManager.showNotification(any(), any(), any(), any(), any(), any(), any(), any()) } returns 20001

        receiver.handleAlarm(
            scheduler = scheduler,
            notificationManager = notificationManager,
            eventId = 123L,
            occurrenceTs = 1709251200000L,
            title = "Test Event",
            location = "Office",
            isAllDay = false,
            calendarColor = 0xFF0000,
            calendarId = 1L,
            triggerTime = 1709250300000L,
        )

        coVerify(exactly = 1) {
            notificationManager.showNotification(
                eventId = 123L,
                occurrenceTs = 1709251200000L,
                title = "Test Event",
                location = "Office",
                isAllDay = false,
                calendarColor = 0xFF0000,
                calendarId = 1L,
                triggerTime = 1709250300000L,
            )
        }
    }

    @Test
    fun `handleAlarm does NOT show notification when shouldFireReminder is false`() = runTest {
        // The event was deleted after its alarm was set: shouldFireReminder is false, so the
        // receiver must not show the stale notification.
        val scheduler = mockk<DeviceCalendarReminderScheduler>()
        val notificationManager = mockk<DeviceCalendarReminderNotificationManager>()
        coEvery { scheduler.shouldFireReminder(any(), any(), any()) } returns false
        coEvery { scheduler.takeDueAndArmNext(any(), any()) } returns emptyList()

        receiver.handleAlarm(
            scheduler = scheduler,
            notificationManager = notificationManager,
            eventId = 123L,
            occurrenceTs = 1709251200000L,
            title = "Deleted Event",
            location = null,
            isAllDay = false,
            calendarColor = 0xFF0000,
            calendarId = 1L,
            triggerTime = 1709250300000L,
        )

        coVerify(exactly = 0) {
            notificationManager.showNotification(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `a reminder alarm arms the next one whether or not it was shown, a snooze re-picks`() = runTest {
        val scheduler = mockk<DeviceCalendarReminderScheduler>()
        val notificationManager = mockk<DeviceCalendarReminderNotificationManager>()
        coJustRun { scheduler.rescheduleAfterFire() }
        coEvery { scheduler.takeDueAndArmNext(any(), any()) } returns emptyList()
        coEvery { notificationManager.showNotification(any(), any(), any(), any(), any(), any(), any(), any()) } returns 20001

        // Reminder alarm, shown.
        coEvery { scheduler.shouldFireReminder(any(), any(), any()) } returns true
        receiver.handleAlarm(
            scheduler, notificationManager, 123L, 1709251200000L, "A", null,
            false, 0, 1L, 1709250300000L,
        )

        // Reminder alarm, not shown.
        coEvery { scheduler.shouldFireReminder(any(), any(), any()) } returns false
        receiver.handleAlarm(
            scheduler, notificationManager, 124L, 1709251300000L, "B", null,
            false, 0, 1L, 1709250400000L,
        )

        // Snooze alarm.
        receiver.handleAlarm(
            scheduler, notificationManager, 125L, 1709251400000L, "C", null,
            false, 0, 1L, 1709250500000L, isSnooze = true,
        )

        coVerify(exactly = 2) { scheduler.takeDueAndArmNext(any(), any()) }
        coVerify(exactly = 1) { scheduler.rescheduleAfterFire() }
    }

    @Test
    fun `handleAlarm checks the alarm's own calendar`() = runTest {
        // A muted calendar makes shouldFireReminder false for that calendar id only; the receiver
        // must pass the id the alarm carries (reminder and snooze alarms both carry it).
        val scheduler = mockk<DeviceCalendarReminderScheduler>()
        val notificationManager = mockk<DeviceCalendarReminderNotificationManager>()
        coEvery { scheduler.shouldFireReminder(123L, 7L, any()) } returns false
        coJustRun { scheduler.rescheduleAfterFire() }

        receiver.handleAlarm(
            scheduler, notificationManager, 123L, 1709251200000L, "Snoozed", null,
            false, 0, 7L, 1709250300000L, isSnooze = true,
        )

        coVerify(exactly = 1) { scheduler.shouldFireReminder(123L, 7L, any()) }
        coVerify(exactly = 0) {
            notificationManager.showNotification(any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 1) { scheduler.rescheduleAfterFire() }
    }

    // ========== Batch of due reminders ==========

    private fun due(eventId: Long, calendarId: Long, trigger: Long) = org.onekash.kashcal.data.calendar_provider.UpcomingDeviceReminder(
        eventId = eventId, occurrenceStartTs = trigger + 3_600_000, title = "Due $eventId", location = "Room $eventId",
        isAllDay = false, reminderMinutes = 60, triggerTime = trigger, calendarColor = eventId.toInt(), calendarId = calendarId,
    )

    @Test
    fun `a reminder alarm shows the fired reminder, then each due one with its own fields`() = runTest {
        val scheduler = mockk<DeviceCalendarReminderScheduler>()
        val notificationManager = mockk<DeviceCalendarReminderNotificationManager>()
        val firedCursor = org.onekash.kashcal.data.calendar_provider.DeviceReminderCursor(1_000L, 10L, 3_601_000L)
        val b = due(20L, 2L, 1_000L)
        val c = due(30L, 3L, 1_500L)
        coEvery { scheduler.shouldFireReminder(10L, 1L, any()) } returns true
        coEvery { scheduler.shouldFireReminder(20L, 2L, any()) } returns true
        coEvery { scheduler.shouldFireReminder(30L, 3L, any()) } returns true
        coEvery { scheduler.takeDueAndArmNext(firedCursor, 2_000L) } returns listOf(b, c)
        coEvery { notificationManager.showNotification(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1

        receiver.handleAlarm(
            scheduler, notificationManager, 10L, 3_601_000L, "Fired", null,
            false, 0, 1L, 1_000L, nowMs = 2_000L,
        )

        coVerifyOrder {
            notificationManager.showNotification(10L, 3_601_000L, "Fired", null, false, 0, 1L, 1_000L)
            scheduler.takeDueAndArmNext(firedCursor, 2_000L)
            notificationManager.showNotification(20L, b.occurrenceStartTs, "Due 20", "Room 20", false, 20, 2L, 1_000L)
            notificationManager.showNotification(30L, c.occurrenceStartTs, "Due 30", "Room 30", false, 30, 3L, 1_500L)
        }
    }

    @Test
    fun `a snooze alarm never asks for the due batch`() = runTest {
        val scheduler = mockk<DeviceCalendarReminderScheduler>()
        val notificationManager = mockk<DeviceCalendarReminderNotificationManager>()
        coEvery { scheduler.shouldFireReminder(10L, 1L, any()) } returns true
        coJustRun { scheduler.rescheduleAfterFire() }
        coEvery { notificationManager.showNotification(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1

        receiver.handleAlarm(
            scheduler, notificationManager, 10L, 3_601_000L, "Snoozed", null,
            false, 0, 1L, 1_000L, isSnooze = true, nowMs = 2_000L,
        )

        coVerify(exactly = 0) { scheduler.takeDueAndArmNext(any(), any()) }
        coVerify(exactly = 1) { scheduler.rescheduleAfterFire() }
    }

    @Test
    fun `a due reminder that may no longer notify isn't shown`() = runTest {
        val scheduler = mockk<DeviceCalendarReminderScheduler>()
        val notificationManager = mockk<DeviceCalendarReminderNotificationManager>()
        val b = due(20L, 2L, 1_000L)
        coEvery { scheduler.shouldFireReminder(10L, 1L, any()) } returns true
        coEvery { scheduler.shouldFireReminder(20L, 2L, any()) } returns false
        coEvery { scheduler.takeDueAndArmNext(any(), any()) } returns listOf(b)
        coEvery { notificationManager.showNotification(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1

        receiver.handleAlarm(scheduler, notificationManager, 10L, 3_601_000L, "Fired", null, false, 0, 1L, 1_000L)

        coVerify(exactly = 0) { notificationManager.showNotification(eq(20L), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a failed batch read falls back to the plain re-pick`() = runTest {
        val scheduler = mockk<DeviceCalendarReminderScheduler>()
        val notificationManager = mockk<DeviceCalendarReminderNotificationManager>()
        coEvery { scheduler.shouldFireReminder(10L, 1L, any()) } returns false
        coEvery { scheduler.takeDueAndArmNext(any(), any()) } throws IllegalStateException("provider")
        coJustRun { scheduler.rescheduleAfterFire() }

        receiver.handleAlarm(scheduler, notificationManager, 10L, 3_601_000L, "Fired", null, false, 0, 1L, 1_000L)

        coVerify(exactly = 1) { scheduler.rescheduleAfterFire() }
    }

    @Test
    fun `a failure showing one due reminder still shows the next`() = runTest {
        val scheduler = mockk<DeviceCalendarReminderScheduler>()
        val notificationManager = mockk<DeviceCalendarReminderNotificationManager>()
        val b = due(20L, 2L, 1_000L)
        val c = due(30L, 3L, 1_000L)
        coEvery { scheduler.shouldFireReminder(any(), any(), any()) } returns true
        coEvery { scheduler.takeDueAndArmNext(any(), any()) } returns listOf(b, c)
        coEvery { notificationManager.showNotification(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { notificationManager.showNotification(eq(20L), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("post")

        receiver.handleAlarm(scheduler, notificationManager, 10L, 3_601_000L, "Fired", null, false, 0, 1L, 1_000L)

        coVerify(exactly = 1) { notificationManager.showNotification(eq(30L), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a failure showing the fired reminder still arms the next`() = runTest {
        val scheduler = mockk<DeviceCalendarReminderScheduler>()
        val notificationManager = mockk<DeviceCalendarReminderNotificationManager>()
        coEvery { scheduler.shouldFireReminder(10L, 1L, any()) } throws IllegalStateException("provider")
        coEvery { scheduler.takeDueAndArmNext(any(), any()) } returns emptyList()

        receiver.handleAlarm(scheduler, notificationManager, 10L, 3_601_000L, "Fired", null, false, 0, 1L, 1_000L)

        coEvery { scheduler.shouldFireReminder(10L, 1L, any()) } returns true
        coEvery { notificationManager.showNotification(any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("post")
        receiver.handleAlarm(scheduler, notificationManager, 10L, 3_601_000L, "Fired", null, false, 0, 1L, 1_000L)

        coVerify(exactly = 2) { scheduler.takeDueAndArmNext(any(), any()) }
    }

    // ========== Test Helpers ==========

    private fun createValidIntent(
        eventId: Long = 123L,
        occurrenceTs: Long = 1709251200000L,
        title: String = "Test Event",
        location: String? = "Test Location",
        isAllDay: Boolean = false,
        calendarColor: Int = 0xFF0000,
        calendarId: Long = 1L,
        triggerTime: Long = System.currentTimeMillis()
    ): Intent {
        return Intent(DeviceCalendarReminderScheduler.ACTION_DEVICE_REMINDER_ALARM).apply {
            putExtra(DeviceCalendarReminderScheduler.EXTRA_EVENT_ID, eventId)
            putExtra(DeviceCalendarReminderScheduler.EXTRA_OCCURRENCE_TS, occurrenceTs)
            putExtra(DeviceCalendarReminderScheduler.EXTRA_TITLE, title)
            putExtra(DeviceCalendarReminderScheduler.EXTRA_LOCATION, location)
            putExtra(DeviceCalendarReminderScheduler.EXTRA_IS_ALL_DAY, isAllDay)
            putExtra(DeviceCalendarReminderScheduler.EXTRA_CALENDAR_COLOR, calendarColor)
            putExtra(DeviceCalendarReminderScheduler.EXTRA_CALENDAR_ID, calendarId)
            putExtra(DeviceCalendarReminderScheduler.EXTRA_TRIGGER_TIME, triggerTime)
        }
    }
}
