package org.onekash.kashcal.reminder.device

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.UpcomingDeviceReminder
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Unit tests for DeviceCalendarReminderScheduler (Phase 4 - Chunk 3).
 *
 * Tests cover:
 * - Does nothing when feature disabled
 * - Does nothing when READ_CALENDAR permission missing
 * - Does nothing when no upcoming reminders
 * - Schedules alarm when reminder available
 * - Stores event details in intent extras
 * - Reschedules after fire
 * - Cancel functionality
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceCalendarReminderSchedulerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var dataStore: KashCalDataStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var testDataStoreFile: File
    private lateinit var fakeRepository: FakeCalendarProviderRepository
    private lateinit var scheduler: DeviceCalendarReminderScheduler
    private lateinit var shadowAlarmManager: ShadowAlarmManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        testDataStoreFile = File(context.filesDir, "test_prefs_${System.nanoTime()}.preferences_pb")
        val testPrefsDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope
        ) { testDataStoreFile }
        dataStore = KashCalDataStore(context, testPrefsDataStore)
        fakeRepository = FakeCalendarProviderRepository()

        scheduler = DeviceCalendarReminderScheduler(
            context = context,
            calendarProviderRepository = fakeRepository,
            dataStore = dataStore
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowAlarmManager = shadowOf(alarmManager)

        // Grant READ_CALENDAR permission by default
        Shadows.shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.READ_CALENDAR)
    }

    @After
    fun teardown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
        testDataStoreFile.delete()
    }

    // ========== Feature Toggle ==========

    @Test
    fun `scheduleNextReminder does nothing when feature disabled`() = runTest {
        // Feature is disabled by default
        assertFalse(dataStore.getDeviceCalendarRemindersEnabled())

        // Set up a reminder that would be scheduled if feature was enabled
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))
        fakeRepository.nextUpcomingReminder = createTestReminder()

        scheduler.scheduleNextReminder()

        // No alarm should be scheduled
        assertNull(shadowAlarmManager.nextScheduledAlarm)
    }

    @Test
    fun `scheduleNextReminder schedules when feature enabled`() = runTest {
        // Enable the feature
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))
        fakeRepository.nextUpcomingReminder = createTestReminder()

        scheduler.scheduleNextReminder()

        // Alarm should be scheduled
        assertNotNull(shadowAlarmManager.nextScheduledAlarm)
    }

    // ========== Permission Check ==========

    @Test
    fun `scheduleNextReminder does nothing when READ_CALENDAR permission missing`() = runTest {
        // Enable the feature but revoke permission
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))
        fakeRepository.nextUpcomingReminder = createTestReminder()

        // Revoke permission
        Shadows.shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.READ_CALENDAR)

        scheduler.scheduleNextReminder()

        // No alarm should be scheduled
        assertNull(shadowAlarmManager.nextScheduledAlarm)
    }

    // ========== No Reminders ==========

    @Test
    fun `scheduleNextReminder does nothing when no upcoming reminders`() = runTest {
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))
        fakeRepository.nextUpcomingReminder = null

        scheduler.scheduleNextReminder()

        assertNull(shadowAlarmManager.nextScheduledAlarm)
    }

    @Test
    fun `scheduleNextReminder does nothing when no enabled calendars`() = runTest {
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(emptySet())
        fakeRepository.nextUpcomingReminder = createTestReminder()

        scheduler.scheduleNextReminder()

        assertNull(shadowAlarmManager.nextScheduledAlarm)
    }

    // ========== Alarm Scheduling ==========

    @Test
    fun `scheduleNextReminder sets correct trigger time`() = runTest {
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))

        val triggerTime = System.currentTimeMillis() + 60_000 // 1 minute from now
        fakeRepository.nextUpcomingReminder = createTestReminder(triggerTime = triggerTime)

        scheduler.scheduleNextReminder()

        val alarm = shadowAlarmManager.nextScheduledAlarm
        assertNotNull(alarm)
        assertEquals(triggerTime, alarm!!.triggerAtTime)
    }

    @Test
    fun `scheduleNextReminder stores eventId in intent extras`() = runTest {
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))

        val reminder = createTestReminder(eventId = 456L)
        fakeRepository.nextUpcomingReminder = reminder

        scheduler.scheduleNextReminder()

        val alarm = shadowAlarmManager.nextScheduledAlarm
        assertNotNull(alarm)

        val intent = shadowOf(alarm!!.operation).savedIntent
        assertEquals(456L, intent.getLongExtra(DeviceCalendarReminderScheduler.EXTRA_EVENT_ID, -1))
    }

    @Test
    fun `scheduleNextReminder stores occurrenceTs in intent extras`() = runTest {
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))

        val occurrenceTs = 1709251200000L
        val reminder = createTestReminder(occurrenceStartTs = occurrenceTs)
        fakeRepository.nextUpcomingReminder = reminder

        scheduler.scheduleNextReminder()

        val alarm = shadowAlarmManager.nextScheduledAlarm
        assertNotNull(alarm)

        val intent = shadowOf(alarm!!.operation).savedIntent
        assertEquals(occurrenceTs, intent.getLongExtra(DeviceCalendarReminderScheduler.EXTRA_OCCURRENCE_TS, -1))
    }

    @Test
    fun `scheduleNextReminder stores title in intent extras`() = runTest {
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))

        val reminder = createTestReminder(title = "Team Meeting")
        fakeRepository.nextUpcomingReminder = reminder

        scheduler.scheduleNextReminder()

        val alarm = shadowAlarmManager.nextScheduledAlarm
        assertNotNull(alarm)

        val intent = shadowOf(alarm!!.operation).savedIntent
        assertEquals("Team Meeting", intent.getStringExtra(DeviceCalendarReminderScheduler.EXTRA_TITLE))
    }

    // ========== Cancel ==========

    @Test
    fun `cancelPendingAlarm cancels scheduled alarm`() = runTest {
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))
        fakeRepository.nextUpcomingReminder = createTestReminder()

        scheduler.scheduleNextReminder()
        assertNotNull(shadowAlarmManager.nextScheduledAlarm)

        scheduler.cancelPendingAlarm()

        // After cancel, the alarm list should be empty
        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    // ========== Reschedule ==========

    @Test
    fun `rescheduleAfterFire re-queries and schedules next`() = runTest {
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))

        // Set up first reminder
        val firstTrigger = System.currentTimeMillis() + 60_000
        fakeRepository.nextUpcomingReminder = createTestReminder(triggerTime = firstTrigger)

        scheduler.scheduleNextReminder()
        assertNotNull(shadowAlarmManager.nextScheduledAlarm)

        // Now simulate "after fire" - set up next reminder
        val secondTrigger = System.currentTimeMillis() + 120_000
        fakeRepository.nextUpcomingReminder = createTestReminder(triggerTime = secondTrigger)

        // Cancel old and reschedule
        scheduler.cancelPendingAlarm()
        scheduler.rescheduleAfterFire()

        val alarm = shadowAlarmManager.nextScheduledAlarm
        assertNotNull(alarm)
        assertEquals(secondTrigger, alarm!!.triggerAtTime)
    }

    // ========== Snooze Request Code Collision ==========

    @Test
    fun `snooze request codes use 100_000 bucket range`() {
        // The snooze range should be large enough to avoid birthday-paradox collisions
        // With 100_000 buckets, collision probability is <0.01% at 5 simultaneous snoozes
        assertEquals(100_000, DeviceCalendarReminderScheduler.SNOOZE_REQUEST_CODE_RANGE)
    }

    @Test
    fun `snooze request codes are always positive and above main alarm code`() {
        // Regression: negative XOR results must not produce negative request codes
        // that could collide with the main alarm code (5001)
        val testCases = listOf(
            Pair(Long.MAX_VALUE, 1L),           // Large positive XOR
            Pair(1L, Long.MAX_VALUE),           // Large positive XOR (reversed)
            Pair(-1L, 1L),                      // Negative eventId (shouldn't happen but defensive)
            Pair(0L, 0L),                       // Zero case
            Pair(123L, 456L),                   // Normal case
            Pair(999_999L, 1_709_251_200_000L)  // Realistic IDs
        )

        for ((eventId, occurrenceTs) in testCases) {
            val requestCode = DeviceCalendarReminderScheduler.computeSnoozeRequestCode(eventId, occurrenceTs)
            assertTrue(
                "Snooze request code $requestCode for ($eventId, $occurrenceTs) must be > 5001 (main alarm code)",
                requestCode > 5001
            )
            assertTrue(
                "Snooze request code $requestCode must be positive",
                requestCode > 0
            )
        }
    }

    @Test
    fun `snooze request codes do not collide for different events at scale`() {
        // Generate 50 different event/occurrence pairs and check for collisions
        val codes = mutableSetOf<Int>()
        val collisions = mutableListOf<String>()

        for (i in 1L..50L) {
            val eventId = i * 7  // Spread out event IDs
            val occurrenceTs = 1_700_000_000_000L + (i * 3_600_000L)  // 1hr apart
            val code = DeviceCalendarReminderScheduler.computeSnoozeRequestCode(eventId, occurrenceTs)
            if (!codes.add(code)) {
                collisions.add("eventId=$eventId, occurrenceTs=$occurrenceTs -> code=$code")
            }
        }

        assertTrue(
            "Expected 0 collisions among 50 events, got ${collisions.size}: $collisions",
            collisions.isEmpty()
        )
    }

    // ========== Test Helpers ==========

    private fun createTestReminder(
        eventId: Long = 123L,
        occurrenceStartTs: Long = System.currentTimeMillis() + 3600_000,
        title: String = "Test Event",
        location: String? = "Test Location",
        isAllDay: Boolean = false,
        reminderMinutes: Int = 15,
        triggerTime: Long = System.currentTimeMillis() + 60_000,
        calendarColor: Int = 0xFF0000,
        calendarId: Long = 1L
    ) = UpcomingDeviceReminder(
        eventId = eventId,
        occurrenceStartTs = occurrenceStartTs,
        title = title,
        location = location,
        isAllDay = isAllDay,
        reminderMinutes = reminderMinutes,
        triggerTime = triggerTime,
        calendarColor = calendarColor,
        calendarId = calendarId
    )
}
