package org.onekash.kashcal.reminder.device

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import java.io.File

/**
 * Tests [DeviceCalendarReminderScheduler] over [FakeCalendarProviderRepository] and
 * Robolectric's AlarmManager.
 *
 * - `scheduleNextReminder` arms no alarm, and cancels an armed one, when device reminders or
 *   device calendars are off, READ_CALENDAR is denied, no device calendar is enabled, or no
 *   reminder is upcoming. Otherwise it arms one at the reminder's trigger time, with the event
 *   id, occurrence start and title in the intent extras.
 * - `cancelPendingAlarm` clears the alarm; `rescheduleAfterFire` arms the next reminder.
 * - `shouldFireReminder` is false for each of those settings and permission cases and for an
 *   event that is no longer active, true otherwise.
 * - Snooze request codes: the 100,000 range, always above the reminder alarm's 5001, and 50
 *   events get distinct codes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceCalendarReminderSchedulerTest {

    private companion object {
        const val HEALTHY_EVENT_ID = 42L
        const val HEALTHY_CALENDAR_ID = 1L
    }

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

        // KashCalApplication.onCreate() runs under Robolectric and arms the widget midnight
        // alarm; clear it so these tests start with no alarms.
        clearAllAlarms()

        // READ_CALENDAR is granted unless a test denies it.
        Shadows.shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.READ_CALENDAR)
    }

    private fun clearAllAlarms() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Bounded so a cancel that silently fails can't loop forever.
        repeat(32) {
            val op = shadowAlarmManager.nextScheduledAlarm?.operation ?: return
            am.cancel(op)
        }
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
        dataStore.setDeviceCalendarRemindersEnabled(false)

        // A reminder that would be armed if device reminders were on.
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))
        fakeRepository.nextUpcomingReminder = createTestReminder()

        scheduler.scheduleNextReminder()

        assertNull(shadowAlarmManager.nextScheduledAlarm)
    }

    @Test
    fun `scheduleNextReminder schedules when feature enabled`() = runTest {
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))
        fakeRepository.nextUpcomingReminder = createTestReminder()

        scheduler.scheduleNextReminder()

        assertNotNull(shadowAlarmManager.nextScheduledAlarm)
    }

    // ========== Permission Check ==========

    @Test
    fun `scheduleNextReminder does nothing when READ_CALENDAR permission missing`() = runTest {
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))
        fakeRepository.nextUpcomingReminder = createTestReminder()

        Shadows.shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.READ_CALENDAR)

        scheduler.scheduleNextReminder()

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

        val triggerTime = System.currentTimeMillis() + 60_000
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

        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    // ========== Cancel on early-return paths ==========

    @Test
    fun `scheduleNextReminder cancels existing alarm when feature is disabled`() = runTest {
        // Arm an alarm with device reminders on.
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))
        fakeRepository.nextUpcomingReminder = createTestReminder()
        scheduler.scheduleNextReminder()
        assertTrue("prime: alarm should be scheduled", shadowAlarmManager.scheduledAlarms.isNotEmpty())

        // Turn them off and schedule again.
        dataStore.setDeviceCalendarRemindersEnabled(false)
        scheduler.scheduleNextReminder()

        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun `scheduleNextReminder cancels existing alarm when device calendars are disabled`() = runTest {
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))
        fakeRepository.nextUpcomingReminder = createTestReminder()
        scheduler.scheduleNextReminder()
        assertTrue("prime: alarm should be scheduled", shadowAlarmManager.scheduledAlarms.isNotEmpty())

        dataStore.setDeviceCalendarsEnabled(false)
        scheduler.scheduleNextReminder()

        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun `scheduleNextReminder cancels existing alarm when READ_CALENDAR revoked`() = runTest {
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))
        fakeRepository.nextUpcomingReminder = createTestReminder()
        scheduler.scheduleNextReminder()
        assertTrue("prime: alarm should be scheduled", shadowAlarmManager.scheduledAlarms.isNotEmpty())

        Shadows.shadowOf(context as android.app.Application)
            .denyPermissions(Manifest.permission.READ_CALENDAR)
        scheduler.scheduleNextReminder()

        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun `scheduleNextReminder cancels existing alarm when no enabled calendars`() = runTest {
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))
        fakeRepository.nextUpcomingReminder = createTestReminder()
        scheduler.scheduleNextReminder()
        assertTrue("prime: alarm should be scheduled", shadowAlarmManager.scheduledAlarms.isNotEmpty())

        dataStore.setEnabledDeviceCalendarIds(emptySet())
        scheduler.scheduleNextReminder()

        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun `scheduleNextReminder cancels existing alarm when no upcoming reminder`() = runTest {
        // The user deletes the only event, the observer runs and getNextUpcomingReminder
        // returns null. A surviving alarm would fire with the deleted event's extras.
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))
        fakeRepository.nextUpcomingReminder = createTestReminder()
        scheduler.scheduleNextReminder()
        assertTrue("prime: alarm should be scheduled", shadowAlarmManager.scheduledAlarms.isNotEmpty())

        fakeRepository.nextUpcomingReminder = null
        scheduler.scheduleNextReminder()

        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    // ========== Reschedule ==========

    @Test
    fun `rescheduleAfterFire re-queries and schedules next`() = runTest {
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))

        val firstTrigger = System.currentTimeMillis() + 60_000
        fakeRepository.nextUpcomingReminder = createTestReminder(triggerTime = firstTrigger)

        scheduler.scheduleNextReminder()
        assertNotNull(shadowAlarmManager.nextScheduledAlarm)

        // After the first fires, the repository reports the next reminder.
        val secondTrigger = System.currentTimeMillis() + 120_000
        fakeRepository.nextUpcomingReminder = createTestReminder(triggerTime = secondTrigger)

        scheduler.cancelPendingAlarm()
        scheduler.rescheduleAfterFire()

        val alarm = shadowAlarmManager.nextScheduledAlarm
        assertNotNull(alarm)
        assertEquals(secondTrigger, alarm!!.triggerAtTime)
    }

    // ========== shouldFireReminder ==========

    @Test
    fun `shouldFireReminder returns false when feature disabled`() = runTest {
        primeHealthyEvent()
        dataStore.setDeviceCalendarRemindersEnabled(false)
        assertFalse(scheduler.shouldFireReminder(HEALTHY_EVENT_ID))
    }

    @Test
    fun `shouldFireReminder returns false when device calendars disabled`() = runTest {
        primeHealthyEvent()
        dataStore.setDeviceCalendarsEnabled(false)
        assertFalse(scheduler.shouldFireReminder(HEALTHY_EVENT_ID))
    }

    @Test
    fun `shouldFireReminder returns false when READ_CALENDAR revoked`() = runTest {
        primeHealthyEvent()
        Shadows.shadowOf(context as android.app.Application)
            .denyPermissions(Manifest.permission.READ_CALENDAR)
        assertFalse(scheduler.shouldFireReminder(HEALTHY_EVENT_ID))
    }

    @Test
    fun `shouldFireReminder returns false when no enabled calendars`() = runTest {
        primeHealthyEvent()
        dataStore.setEnabledDeviceCalendarIds(emptySet())
        assertFalse(scheduler.shouldFireReminder(HEALTHY_EVENT_ID))
    }

    @Test
    fun `shouldFireReminder returns false when isEventActive returns false`() = runTest {
        // The event was soft-deleted (DELETED=1) after its alarm was armed. The fake models
        // this by removing the id from activeEventIds.
        primeHealthyEvent()
        fakeRepository.activeEventIds.remove(HEALTHY_EVENT_ID)
        assertFalse(scheduler.shouldFireReminder(HEALTHY_EVENT_ID))
    }

    @Test
    fun `shouldFireReminder returns true for healthy active event`() = runTest {
        primeHealthyEvent()
        assertTrue(scheduler.shouldFireReminder(HEALTHY_EVENT_ID))
    }

    private suspend fun primeHealthyEvent() {
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(HEALTHY_CALENDAR_ID))
        fakeRepository.activeEventIds.add(HEALTHY_EVENT_ID)
    }

    // ========== Snooze Request Codes ==========

    @Test
    fun `snooze request codes use 100_000 bucket range`() {
        // 100,000 buckets keep birthday-paradox collisions under 0.01% at 5 simultaneous
        // snoozes.
        assertEquals(100_000, DeviceCalendarReminderScheduler.SNOOZE_REQUEST_CODE_RANGE)
    }

    @Test
    fun `snooze request codes are always positive and above main alarm code`() {
        // A negative XOR must still give a positive code above the reminder alarm's (5001), so
        // the two can't collide.
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
