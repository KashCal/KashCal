package org.onekash.kashcal.reminder.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
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
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReminderNotificationManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var channels: ReminderNotificationChannels
    private lateinit var dataStore: KashCalDataStore
    private lateinit var manager: ReminderNotificationManager
    private lateinit var originalTimeZone: TimeZone
    private lateinit var originalLocale: Locale
    private lateinit var testDataStoreFile: File

    @Before
    fun setup() {
        originalTimeZone = TimeZone.getDefault()
        originalLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        Locale.setDefault(Locale.US)

        Dispatchers.setMain(testDispatcher)

        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        context = RuntimeEnvironment.getApplication()
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        channels = ReminderNotificationChannels(context)
        channels.createChannels()

        // Use a dedicated CoroutineScope (not TestScope) for DataStore to avoid
        // UncaughtExceptionsBeforeTest: runTest/testScope.runTest catches leaked
        // coroutines from other test classes in the same JVM fork. Using a real
        // scope + runBlocking sidesteps this entirely.
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        testDataStoreFile = File(context.filesDir, "test_prefs_${System.nanoTime()}.preferences_pb")
        val testPrefsDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope
        ) { testDataStoreFile }
        dataStore = KashCalDataStore(context, testPrefsDataStore)
        manager = ReminderNotificationManager(context, channels, dataStore)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
        TimeZone.setDefault(originalTimeZone)
        Locale.setDefault(originalLocale)
        unmockkAll()
        testDataStoreFile.delete()
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
    fun `buildNotification creates notification successfully`() = runBlocking {
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
    fun `buildNotification sets auto-cancel flag`() = runBlocking {
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
    fun `buildNotification has content intent set`() = runBlocking {
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
    fun `buildNotification includes two action buttons`() = runBlocking {
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
    fun `buildNotification action buttons are Snooze and Dismiss`() = runBlocking {
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
    fun `content text shows 12h absolute time for timed event`() = runBlocking {
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
    fun `content text shows 24h absolute time for timed event`() = runBlocking {
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
    fun `content text shows Starting now when diffMs is zero`() = runBlocking {
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
    fun `content text shows Starting now when diffMs is negative for timed event`() = runBlocking {
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

    // ==================== All-day: Today / Tomorrow / In N days ====================

    private fun allDayReminder(eventDate: LocalDate, fireDateTime: ZonedDateTime): ScheduledReminder {
        val occurrenceUtcMidnight = ZonedDateTime.of(
            eventDate, LocalTime.MIDNIGHT, ZoneId.of("UTC")
        ).toInstant().toEpochMilli()
        return createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "All Day Event",
            occurrenceTime = occurrenceUtcMidnight,
            triggerTime = fireDateTime.toInstant().toEpochMilli(),
            isAllDay = true
        )
    }

    @Test
    fun `all-day reminder firing on the event date shows Today`() = runBlocking {
        // 9 AM day-of (PT9H): fires on the event's own date.
        val zone = ZoneId.of("America/New_York")
        val reminder = allDayReminder(
            LocalDate.of(2026, 1, 6),
            ZonedDateTime.of(LocalDate.of(2026, 1, 6), LocalTime.of(9, 0), zone)
        )

        val notification = manager.buildNotification(reminder)

        assertEquals("Today", notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `all-day reminder firing the day before shows Tomorrow`() = runBlocking {
        // 1d chip (-PT15H): fires 9 AM the day before.
        val zone = ZoneId.of("America/New_York")
        val reminder = allDayReminder(
            LocalDate.of(2026, 1, 6),
            ZonedDateTime.of(LocalDate.of(2026, 1, 5), LocalTime.of(9, 0), zone)
        )

        val notification = manager.buildNotification(reminder)

        assertEquals("Tomorrow", notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `all-day reminder firing two days before shows In 2 days`() = runBlocking {
        val zone = ZoneId.of("America/New_York")
        val reminder = allDayReminder(
            LocalDate.of(2026, 1, 6),
            ZonedDateTime.of(LocalDate.of(2026, 1, 4), LocalTime.of(9, 0), zone)
        )

        val notification = manager.buildNotification(reminder)

        assertEquals("In 2 days", notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `all-day reminder firing a week before shows In 6 days`() = runBlocking {
        // 1w chip (-PT159H = 6d15h): fires 9 AM, 6 calendar days before.
        val zone = ZoneId.of("America/New_York")
        val reminder = allDayReminder(
            LocalDate.of(2026, 1, 13),
            ZonedDateTime.of(LocalDate.of(2026, 1, 7), LocalTime.of(9, 0), zone)
        )

        val notification = manager.buildNotification(reminder)

        assertEquals("In 6 days", notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `all-day reminder subtitle never contains a clock time or hour-countdown`() = runBlocking {
        val zone = ZoneId.of("America/New_York")
        val reminder = allDayReminder(
            LocalDate.of(2026, 1, 6),
            ZonedDateTime.of(LocalDate.of(2026, 1, 5), LocalTime.of(9, 0), zone)
        )

        val text = manager.buildNotification(reminder).extras.getString(Notification.EXTRA_TEXT)

        assertNotNull(text)
        assertFalse("must not contain clock time", text!!.contains(":"))
        assertFalse("must not contain hour-countdown", text.contains("hour"))
    }

    @Test
    fun `snoozed all-day reminder past local midnight shows Today`() = runBlocking {
        // Snooze pushes the trigger to noon on the event day -> still "Today".
        val zone = ZoneId.of("America/New_York")
        val reminder = allDayReminder(
            LocalDate.of(2026, 1, 6),
            ZonedDateTime.of(LocalDate.of(2026, 1, 6), LocalTime.NOON, zone)
        )

        val notification = manager.buildNotification(reminder)

        assertEquals("Today", notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `content text shows Tomorrow qualifier for next-day event`() = runBlocking {
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
    fun `content text shows weekday qualifier for further-out event`() = runBlocking {
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

    // ==================== setWhen Rounding Offset Tests ====================

    @Test
    fun `buildNotification sets when to occurrenceTime plus 30s rounding offset`() = runBlocking {
        val occurrenceTime = System.currentTimeMillis() + 900_000L

        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "Test Meeting",
            occurrenceTime = occurrenceTime
        )

        val notification = manager.buildNotification(reminder)

        assertEquals(
            "setWhen should include 30s rounding offset to convert Android's floor to round",
            occurrenceTime + 30_000L,
            notification.`when`
        )
    }

    @Test
    fun `buildNotification hides header time for all-day reminder`() = runBlocking {
        // All-day events store occurrenceTime as UTC midnight. Showing it in the
        // notification header renders a misleading timezone-shifted clock time, so
        // the header timestamp must be suppressed for all-day reminders.
        val occurrenceTime = ZonedDateTime.of(
            LocalDate.of(2026, 1, 6), LocalTime.MIDNIGHT, ZoneId.of("UTC")
        ).toInstant().toEpochMilli()

        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "All Day Event",
            occurrenceTime = occurrenceTime,
            isAllDay = true
        )

        val notification = manager.buildNotification(reminder)

        assertFalse(
            "All-day reminder must not show a header timestamp",
            notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true)
        )
    }

    @Test
    fun `buildNotification shows header time for timed reminder`() = runBlocking {
        val occurrenceTime = System.currentTimeMillis() + 900_000L

        val reminder = createTestReminder(
            id = 1L,
            eventId = 100L,
            eventTitle = "Test Meeting",
            occurrenceTime = occurrenceTime,
            isAllDay = false
        )

        val notification = manager.buildNotification(reminder)

        assertTrue(
            "Timed reminder should keep the header countdown",
            notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, false)
        )
    }

    // ==================== Post / Cancel Round-trip ====================

    @Test
    fun `cancelNotification clears the notification showNotification posted`() = runBlocking {
        // The two sides compute the id independently, so re-keying one without
        // the other would silently strand notifications in the shade.
        val reminder = createTestReminder(
            id = 42L,
            eventId = 1L,
            eventTitle = "Standup",
            occurrenceTime = 1_800_000_000_000L
        )

        val postedId = manager.showNotification(reminder)
        assertNotNull(activeById(postedId))

        manager.cancelNotification(reminder.id)

        assertNull("Cancel must target the id that was posted", activeById(postedId))
    }

    @Test
    fun `two reminders on the same occurrence post under different ids`() = runBlocking {
        // Nothing collapses on its own: ids are per-reminder-row, which is why a
        // firing reminder has to clear its siblings explicitly.
        val occurrenceTime = 1_800_000_000_000L
        val hourBefore = createTestReminder(
            id = 42L, eventId = 1L, eventTitle = "Standup",
            occurrenceTime = occurrenceTime,
            triggerTime = occurrenceTime - 3_600_000, reminderOffset = "-PT1H"
        )
        val quarterHourBefore = createTestReminder(
            id = 43L, eventId = 1L, eventTitle = "Standup",
            occurrenceTime = occurrenceTime
        )

        val firstId = manager.showNotification(hourBefore)
        val secondId = manager.showNotification(quarterHourBefore)

        assertNotEquals(firstId, secondId)
        assertNotNull(activeById(firstId))
        assertNotNull(activeById(secondId))
    }

    private fun activeById(id: Int) =
        notificationManager.activeNotifications.firstOrNull { it.id == id }

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
