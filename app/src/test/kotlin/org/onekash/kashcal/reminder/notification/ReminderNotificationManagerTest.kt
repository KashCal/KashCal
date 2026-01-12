package org.onekash.kashcal.reminder.notification

import android.content.Context
import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for ReminderNotificationManager.
 *
 * Tests verify:
 * - Notification building works correctly
 * - Time formatting is correct
 * - Constants are correctly defined for deep linking
 *
 * Note: The actual intent creation is tested indirectly through the notification
 * content intent. Integration testing (manual) verifies the deep link flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReminderNotificationManagerTest {

    private lateinit var context: Context
    private lateinit var channels: ReminderNotificationChannels
    private lateinit var manager: ReminderNotificationManager

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        context = RuntimeEnvironment.getApplication()
        channels = ReminderNotificationChannels(context)
        channels.createChannels()
        manager = ReminderNotificationManager(context, channels)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== Constant Tests ====================

    @Test
    fun `ACTION_SHOW_EVENT constant is correctly defined`() {
        assertEquals(
            "org.onekash.kashcal.SHOW_REMINDER_EVENT",
            ReminderNotificationManager.ACTION_SHOW_EVENT
        )
    }

    @Test
    fun `EXTRA_EVENT_ID constant is correctly defined`() {
        assertEquals(
            "reminder_event_id",
            ReminderNotificationManager.EXTRA_EVENT_ID
        )
    }

    @Test
    fun `EXTRA_OCCURRENCE_TS constant is correctly defined`() {
        assertEquals(
            "reminder_occurrence_ts",
            ReminderNotificationManager.EXTRA_OCCURRENCE_TS
        )
    }

    // ==================== Notification Building Tests ====================

    @Test
    fun `buildNotification creates notification successfully`() {
        // Given a reminder
        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "Test Meeting",
            occurrenceTime = System.currentTimeMillis() + 900_000 // 15 min in future
        )

        // When building notification
        val notification = manager.buildNotification(reminder)

        // Then verify notification is created
        assertNotNull("Notification should be created", notification)
    }

    @Test
    fun `buildNotification sets auto-cancel flag`() {
        // Given a reminder
        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "Test Meeting",
            occurrenceTime = System.currentTimeMillis() + 900_000
        )

        // When building notification
        val notification = manager.buildNotification(reminder)

        // Then verify auto-cancel is set
        val autoCancelFlag = notification.flags and android.app.Notification.FLAG_AUTO_CANCEL
        assertEquals(
            "Notification should have FLAG_AUTO_CANCEL set",
            android.app.Notification.FLAG_AUTO_CANCEL,
            autoCancelFlag
        )
    }

    @Test
    fun `buildNotification has content intent set`() {
        // Given a reminder
        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "Test Meeting",
            occurrenceTime = System.currentTimeMillis() + 900_000
        )

        // When building notification
        val notification = manager.buildNotification(reminder)

        // Then verify content intent is set
        assertNotNull("Notification should have content intent", notification.contentIntent)
    }

    @Test
    fun `buildNotification includes two action buttons`() {
        // Given a reminder
        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "Test Meeting",
            occurrenceTime = System.currentTimeMillis() + 900_000
        )

        // When building notification
        val notification = manager.buildNotification(reminder)

        // Then verify notification has two actions (Snooze and Dismiss)
        assertEquals("Notification should have 2 action buttons", 2, notification.actions.size)
    }

    @Test
    fun `buildNotification action buttons are Snooze and Dismiss`() {
        // Given a reminder
        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "Test Meeting",
            occurrenceTime = System.currentTimeMillis() + 900_000
        )

        // When building notification
        val notification = manager.buildNotification(reminder)

        // Then verify action labels
        assertEquals("First action should be Snooze", "Snooze", notification.actions[0].title)
        assertEquals("Second action should be Dismiss", "Dismiss", notification.actions[1].title)
    }

    // ==================== Time Formatting Tests ====================

    @Test
    fun `formatTimeUntil returns correct string for 1 minute`() {
        val durationMs = 60 * 1000L
        assertEquals("1 minute", manager.formatTimeUntil(durationMs))
    }

    @Test
    fun `formatTimeUntil returns correct string for 15 minutes`() {
        val durationMs = 15 * 60 * 1000L
        assertEquals("15 minutes", manager.formatTimeUntil(durationMs))
    }

    @Test
    fun `formatTimeUntil returns correct string for 1 hour`() {
        val durationMs = 60 * 60 * 1000L
        assertEquals("1 hour", manager.formatTimeUntil(durationMs))
    }

    @Test
    fun `formatTimeUntil returns correct string for 2 hours`() {
        val durationMs = 2 * 60 * 60 * 1000L
        assertEquals("2 hours", manager.formatTimeUntil(durationMs))
    }

    @Test
    fun `formatTimeUntil returns correct string for 1 hour 1 minute`() {
        val durationMs = (60 + 1) * 60 * 1000L
        assertEquals("1 hour 1 minute", manager.formatTimeUntil(durationMs))
    }

    @Test
    fun `formatTimeUntil returns correct string for 2 hours 30 minutes`() {
        val durationMs = (2 * 60 + 30) * 60 * 1000L
        assertEquals("2 hours 30 minutes", manager.formatTimeUntil(durationMs))
    }

    @Test
    fun `formatTimeUntil returns 0 minutes for 0 duration`() {
        val durationMs = 0L
        assertEquals("0 minutes", manager.formatTimeUntil(durationMs))
    }

    // Helper function to create test reminders
    private fun createTestReminder(
        id: Long,
        eventId: Long,
        eventTitle: String,
        occurrenceTime: Long,
        triggerTime: Long = occurrenceTime - 900_000, // 15 min before
        reminderOffset: String = "-PT15M",
        eventLocation: String? = null,
        isAllDay: Boolean = false,
        calendarColor: Int = 0xFF6200EE.toInt()
    ) = ScheduledReminder(
        id = id,
        eventId = eventId,
        occurrenceTime = occurrenceTime,
        triggerTime = triggerTime,
        reminderOffset = reminderOffset,
        eventTitle = eventTitle,
        eventLocation = eventLocation,
        isAllDay = isAllDay,
        calendarColor = calendarColor
    )
}