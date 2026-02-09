package org.onekash.kashcal.reminder.notification

import android.app.Notification
import android.content.Context
import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

/**
 * Unit tests for ReminderNotificationManager.
 *
 * Tests verify:
 * - Notification building works correctly
 * - Absolute event time formatting is correct
 * - Cross-day date qualifiers work
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
    private lateinit var dataStore: KashCalDataStore
    private lateinit var manager: ReminderNotificationManager
    private lateinit var originalTimeZone: TimeZone
    private lateinit var originalLocale: Locale

    @Before
    fun setup() {
        originalTimeZone = TimeZone.getDefault()
        originalLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        Locale.setDefault(Locale.US)

        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        context = RuntimeEnvironment.getApplication()
        channels = ReminderNotificationChannels(context)
        channels.createChannels()
        dataStore = KashCalDataStore(context)
        manager = ReminderNotificationManager(context, channels, dataStore)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
        Locale.setDefault(originalLocale)
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
    fun `buildNotification creates notification successfully`() = runTest {
        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "Test Meeting",
            occurrenceTime = System.currentTimeMillis() + 900_000
        )

        val notification = manager.buildNotification(reminder)

        assertNotNull("Notification should be created", notification)
    }

    @Test
    fun `buildNotification sets auto-cancel flag`() = runTest {
        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "Test Meeting",
            occurrenceTime = System.currentTimeMillis() + 900_000
        )

        val notification = manager.buildNotification(reminder)

        val autoCancelFlag = notification.flags and android.app.Notification.FLAG_AUTO_CANCEL
        assertEquals(
            "Notification should have FLAG_AUTO_CANCEL set",
            android.app.Notification.FLAG_AUTO_CANCEL,
            autoCancelFlag
        )
    }

    @Test
    fun `buildNotification has content intent set`() = runTest {
        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "Test Meeting",
            occurrenceTime = System.currentTimeMillis() + 900_000
        )

        val notification = manager.buildNotification(reminder)

        assertNotNull("Notification should have content intent", notification.contentIntent)
    }

    @Test
    fun `buildNotification includes two action buttons`() = runTest {
        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "Test Meeting",
            occurrenceTime = System.currentTimeMillis() + 900_000
        )

        val notification = manager.buildNotification(reminder)

        assertEquals("Notification should have 2 action buttons", 2, notification.actions.size)
    }

    @Test
    fun `buildNotification action buttons are Snooze and Dismiss`() = runTest {
        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "Test Meeting",
            occurrenceTime = System.currentTimeMillis() + 900_000
        )

        val notification = manager.buildNotification(reminder)

        assertEquals("First action should be Snooze", "Snooze", notification.actions[0].title)
        assertEquals("Second action should be Dismiss", "Dismiss", notification.actions[1].title)
    }

    // ==================== Content Text Tests ====================

    @Test
    fun `content text shows 12h absolute time for timed event`() = runTest {
        dataStore.setTimeFormat("12h")

        // Event at 10:30 AM today in pinned timezone
        val zone = ZoneId.of("America/New_York")
        val eventTime = ZonedDateTime.of(
            LocalDate.now(zone), LocalTime.of(10, 30), zone
        ).toInstant().toEpochMilli()

        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "Morning Meeting",
            occurrenceTime = eventTime,
            triggerTime = eventTime - 900_000 // 15 min before
        )

        val notification = manager.buildNotification(reminder)
        val contentText = notification.extras.getString(Notification.EXTRA_TEXT)

        assertEquals("10:30 AM", contentText)
    }

    @Test
    fun `content text shows 24h absolute time for timed event`() = runTest {
        dataStore.setTimeFormat("24h")

        val zone = ZoneId.of("America/New_York")
        val eventTime = ZonedDateTime.of(
            LocalDate.now(zone), LocalTime.of(14, 30), zone
        ).toInstant().toEpochMilli()

        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "Afternoon Meeting",
            occurrenceTime = eventTime,
            triggerTime = eventTime - 900_000
        )

        val notification = manager.buildNotification(reminder)
        val contentText = notification.extras.getString(Notification.EXTRA_TEXT)

        assertEquals("14:30", contentText)
    }

    @Test
    fun `content text shows Starting now when diffMs is zero`() = runTest {
        val eventTime = System.currentTimeMillis() + 60_000

        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "Now Meeting",
            occurrenceTime = eventTime,
            triggerTime = eventTime // diffMs = 0
        )

        val notification = manager.buildNotification(reminder)
        val contentText = notification.extras.getString(Notification.EXTRA_TEXT)

        assertEquals("Starting now", contentText)
    }

    @Test
    fun `content text shows Starting now when diffMs is negative for timed event`() = runTest {
        val eventTime = System.currentTimeMillis() + 60_000

        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "Past Trigger Meeting",
            occurrenceTime = eventTime,
            triggerTime = eventTime + 300_000 // trigger 5 min after occurrence -> diffMs < 0
        )

        val notification = manager.buildNotification(reminder)
        val contentText = notification.extras.getString(Notification.EXTRA_TEXT)

        assertEquals("Starting now", contentText)
    }

    @Test
    fun `content text shows Today for all-day event with negative diffMs`() = runTest {
        val eventTime = System.currentTimeMillis()

        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "All Day Event",
            occurrenceTime = eventTime,
            triggerTime = eventTime + 60_000, // trigger after occurrence -> diffMs < 0
            isAllDay = true
        )

        val notification = manager.buildNotification(reminder)
        val contentText = notification.extras.getString(Notification.EXTRA_TEXT)

        assertEquals("Today", contentText)
    }

    @Test
    fun `content text shows duration for all-day event with positive diffMs`() = runTest {
        val triggerTime = System.currentTimeMillis()
        val occurrenceTime = triggerTime + 24 * 60 * 60 * 1000L // 1 day later

        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "Tomorrow All Day",
            occurrenceTime = occurrenceTime,
            triggerTime = triggerTime,
            isAllDay = true
        )

        val notification = manager.buildNotification(reminder)
        val contentText = notification.extras.getString(Notification.EXTRA_TEXT)

        // Should be a duration string (e.g., "24 hours"), not a clock time
        assertNotNull(contentText)
        assertFalse("All-day content should not contain colon (clock time)", contentText!!.contains(":"))
    }

    @Test
    fun `content text shows Tomorrow qualifier for next-day event`() = runTest {
        dataStore.setTimeFormat("12h")

        val zone = ZoneId.of("America/New_York")
        val tomorrow = LocalDate.now(zone).plusDays(1)
        val eventTime = ZonedDateTime.of(
            tomorrow, LocalTime.of(10, 30), zone
        ).toInstant().toEpochMilli()

        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "Tomorrow Meeting",
            occurrenceTime = eventTime,
            triggerTime = eventTime - 24 * 60 * 60 * 1000L // 1 day before
        )

        val notification = manager.buildNotification(reminder)
        val contentText = notification.extras.getString(Notification.EXTRA_TEXT)

        assertEquals("Tomorrow, 10:30 AM", contentText)
    }

    @Test
    fun `content text shows weekday qualifier for further-out event`() = runTest {
        dataStore.setTimeFormat("12h")

        val zone = ZoneId.of("America/New_York")
        val futureDate = LocalDate.now(zone).plusDays(3)
        val eventTime = ZonedDateTime.of(
            futureDate, LocalTime.of(9, 0), zone
        ).toInstant().toEpochMilli()

        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "Future Meeting",
            occurrenceTime = eventTime,
            triggerTime = eventTime - 3 * 24 * 60 * 60 * 1000L // 3 days before
        )

        val notification = manager.buildNotification(reminder)
        val contentText = notification.extras.getString(Notification.EXTRA_TEXT)

        // Should contain weekday abbreviation and time
        assertNotNull(contentText)
        assertTrue(
            "Should contain weekday and time, got: $contentText",
            contentText!!.contains("9:00 AM") && contentText.contains(",")
        )
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
