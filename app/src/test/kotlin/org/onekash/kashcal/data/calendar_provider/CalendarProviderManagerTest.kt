package org.onekash.kashcal.data.calendar_provider

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.provider.CalendarContract
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.reminder.device.DeviceCalendarAlarmReceiver
import org.onekash.kashcal.reminder.device.DeviceCalendarChangeWatcher
import org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler
import org.onekash.kashcal.util.JobSchedulerIds
import org.onekash.kashcal.widget.WidgetUpdateManager
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests [CalendarProviderManager]: the enable and disable lifecycle,
 * [CalendarProviderManager.changeSignal], auto-disabling when READ_CALENDAR is revoked, settings
 * changes, stale calendar-id pruning (on the fake), and the debounced reminder and widget refresh
 * on provider changes delivered through the Robolectric ContentResolver.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CalendarProviderManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var dataStore: KashCalDataStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var testDataStoreFile: File
    private lateinit var deviceCalendarReminderScheduler: DeviceCalendarReminderScheduler
    private lateinit var widgetUpdateManager: WidgetUpdateManager
    private lateinit var manager: CalendarProviderManager
    private lateinit var jobScheduler: JobScheduler

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        // READ_CALENDAR is granted by default; the lifecycle tests need it.
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALENDAR)
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        testDataStoreFile = File(context.filesDir, "test_prefs_${System.nanoTime()}.preferences_pb")
        val testPrefsDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope
        ) { testDataStoreFile }
        dataStore = KashCalDataStore(context, testPrefsDataStore)
        jobScheduler = context.getSystemService(JobScheduler::class.java)
        jobScheduler.cancelAll()
        deviceCalendarReminderScheduler = mockk(relaxed = true)
        widgetUpdateManager = mockk(relaxed = true)
        manager = CalendarProviderManager(context, dataStore, deviceCalendarReminderScheduler, widgetUpdateManager, DeviceCalendarChangeWatcher(context, dataStore))
    }

    @After
    fun teardown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
        testDataStoreFile.delete()
    }

    @Test
    fun `changeSignal starts at 0`() = runTest {
        assertEquals(0, manager.changeSignal.first())
    }

    @Test
    fun `onEnabled increments changeSignal`() = runTest {
        val initial = manager.changeSignal.first()
        manager.onEnabled()
        assertEquals(initial + 1, manager.changeSignal.first())
    }

    @Test
    fun `onEnabled multiple times increments changeSignal each time`() = runTest {
        manager.onEnabled()
        manager.onEnabled()
        manager.onEnabled()
        assertEquals(3, manager.changeSignal.first())
    }

    @Test
    fun `onDisabled increments changeSignal to trigger UI refresh`() = runTest {
        manager.onEnabled()
        val afterEnable = manager.changeSignal.first()
        manager.onDisabled()
        assertEquals(afterEnable + 1, manager.changeSignal.first())
    }

    @Test
    fun `initialize with disabled does not register observer`() = runTest(testDispatcher) {
        // Disabled, so initialize registers no observer.
        dataStore.setDeviceCalendarsEnabled(false)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()
        // Passes if nothing throws; the observer count isn't asserted.
    }

    @Test
    fun `onDisabled is safe to call without prior onEnabled`() = runTest(testDispatcher) {
        // Must not throw when no observer was ever registered.
        manager.onDisabled()
    }

    // ========== Stale Calendar ID Pruning ==========
    // These run on FakeCalendarProviderRepository's copy of the pruning logic, not the real
    // repository's.

    @Test
    fun `pruneStaleCalendarIds removes IDs not in actual calendars`() = runTest {
        val fake = FakeCalendarProviderRepository()
        fake.calendars = listOf(
            DeviceCalendar(id = 1L, displayName = "Cal 1", color = 0, accountName = "a", accountType = "t", visible = true, accessLevel = 700),
            DeviceCalendar(id = 3L, displayName = "Cal 3", color = 0, accountName = "a", accountType = "t", visible = true, accessLevel = 700)
        )
        // Stored IDs 1, 2 and 3, but calendar 2 no longer exists.
        dataStore.setEnabledDeviceCalendarIds(setOf(1L, 2L, 3L))

        fake.pruneStaleCalendarIds(dataStore)

        val remaining = dataStore.getEnabledDeviceCalendarIds()
        assertEquals(setOf(1L, 3L), remaining)
    }

    @Test
    fun `pruneStaleCalendarIds does nothing when all IDs valid`() = runTest {
        val fake = FakeCalendarProviderRepository()
        fake.calendars = listOf(
            DeviceCalendar(id = 1L, displayName = "Cal 1", color = 0, accountName = "a", accountType = "t", visible = true, accessLevel = 700),
            DeviceCalendar(id = 2L, displayName = "Cal 2", color = 0, accountName = "a", accountType = "t", visible = true, accessLevel = 700)
        )
        dataStore.setEnabledDeviceCalendarIds(setOf(1L, 2L))

        fake.pruneStaleCalendarIds(dataStore)

        val remaining = dataStore.getEnabledDeviceCalendarIds()
        assertEquals(setOf(1L, 2L), remaining)
    }

    @Test
    fun `pruneStaleCalendarIds does nothing when stored IDs empty`() = runTest {
        val fake = FakeCalendarProviderRepository()
        fake.calendars = listOf(
            DeviceCalendar(id = 1L, displayName = "Cal 1", color = 0, accountName = "a", accountType = "t", visible = true, accessLevel = 700)
        )
        dataStore.setEnabledDeviceCalendarIds(emptySet())

        fake.pruneStaleCalendarIds(dataStore)

        val remaining = dataStore.getEnabledDeviceCalendarIds()
        assertEquals(emptySet<Long>(), remaining)
    }

    // ========== Permission Revocation ==========

    @Test
    fun `initialize with enabled but no permission does not crash and auto-disables feature`() = runTest(testDispatcher) {
        // The user enabled device calendars, then revoked READ_CALENDAR in system settings.
        dataStore.setDeviceCalendarsEnabled(true)

        // Revoke READ_CALENDAR.
        Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CALENDAR)

        // A new manager, so it sees the denied permission.
        manager = CalendarProviderManager(context, dataStore, deviceCalendarReminderScheduler, widgetUpdateManager, DeviceCalendarChangeWatcher(context, dataStore))
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        // The feature is switched off without a crash.
        assertFalse(
            "Feature should be auto-disabled when permission is revoked",
            dataStore.deviceCalendarsEnabled.first()
        )
    }

    // ========== Widget Refresh On Provider Changes ==========

    /**
     * Delivers [count] provider change notifications through the real ContentResolver, as
     * another app or a sync adapter writing an event would, then runs the observer's Handler
     * dispatch so each notification reaches the debounce.
     */
    private fun notifyEventChanges(count: Int = 1) = notifyEventsChanged(context, count)

    private fun advanceDebounce(ms: Long) {
        testDispatcher.scheduler.advanceTimeBy(ms)
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    fun `a settings change starts observing when a restore switched device calendars on`() = runTest(testDispatcher) {
        // A settings restore writes the enabled flag straight to DataStore, with no
        // onEnabled() and no process restart, so no observer is registered yet.
        dataStore.setDeviceCalendarsEnabled(true)
        val before = manager.changeSignal.value

        manager.onDeviceCalendarSettingsChanged()

        assertEquals(before + 1, manager.changeSignal.value)
        assertEquals(1, eventsObserverCount(context))
        notifyEventChanges()
        advanceDebounce(CalendarProviderManager.OBSERVER_DEBOUNCE_MS + 1)
        coVerify(exactly = 1) { widgetUpdateManager.updateAllWidgets("device_calendar_changed") }
    }

    @Test
    fun `a settings change while device calendars are off only re-queries`() = runTest(testDispatcher) {
        dataStore.setDeviceCalendarsEnabled(false)
        val before = manager.changeSignal.value

        manager.onDeviceCalendarSettingsChanged()

        assertEquals(before + 1, manager.changeSignal.value)
        assertEquals(0, eventsObserverCount(context))
    }

    @Test
    fun `applying the stored setting starts observing when on and stops when off`() = runTest(testDispatcher) {
        dataStore.setDeviceCalendarsEnabled(true)
        manager.applyDeviceCalendarsSetting()
        assertEquals(1, eventsObserverCount(context))

        dataStore.setDeviceCalendarsEnabled(false)
        manager.applyDeviceCalendarsSetting()
        assertEquals(0, eventsObserverCount(context))
        verify { deviceCalendarReminderScheduler.cancelPendingAlarm() }
    }

    @Test
    fun `the app's own change signal re-queries without registering an observer`() = runTest(testDispatcher) {
        val before = manager.changeSignal.value

        manager.notifyDeviceCalendarChanged()

        assertEquals(before + 1, manager.changeSignal.value)
        assertEquals(0, eventsObserverCount(context))
    }

    @Test
    fun `provider change refreshes widgets once after the debounce window`() = runTest(testDispatcher) {
        manager.onEnabled()
        assertEquals(
            1,
            Shadows.shadowOf(context.contentResolver)
                .getContentObservers(CalendarContract.Events.CONTENT_URI).size
        )

        notifyEventChanges()
        advanceDebounce(2999)
        coVerify(exactly = 0) { widgetUpdateManager.updateAllWidgets(any()) }

        advanceDebounce(2)
        coVerify(exactly = 1) { widgetUpdateManager.updateAllWidgets(any()) }
    }

    @Test
    fun `burst of provider changes refreshes widgets only once`() = runTest(testDispatcher) {
        manager.onEnabled()

        notifyEventChanges(count = 20)
        advanceDebounce(3001)

        coVerify(exactly = 1) { widgetUpdateManager.updateAllWidgets(any()) }
    }

    @Test
    fun `bursts in separate debounce windows refresh widgets once per window`() = runTest(testDispatcher) {
        manager.onEnabled()

        notifyEventChanges(count = 5)
        advanceDebounce(3001)
        notifyEventChanges(count = 5)
        advanceDebounce(3001)

        coVerify(exactly = 2) { widgetUpdateManager.updateAllWidgets(any()) }
    }

    @Test
    fun `provider change still bumps changeSignal and reschedules device reminders`() = runTest(testDispatcher) {
        manager.onEnabled()
        consumeEnableRepick()
        val afterEnable = manager.changeSignal.value

        notifyEventChanges()
        advanceDebounce(3001)

        assertEquals(afterEnable + 1, manager.changeSignal.value)
        coVerify(exactly = 1) { deviceCalendarReminderScheduler.scheduleNextReminder() }
    }

    @Test
    fun `slow widget refresh does not hold up the reminder reschedule`() = runTest(testDispatcher) {
        coEvery { widgetUpdateManager.updateAllWidgets(any()) } coAnswers { awaitCancellation() }
        manager.onEnabled()
        consumeEnableRepick()

        notifyEventChanges()
        advanceDebounce(3001)

        coVerify(exactly = 1) { widgetUpdateManager.updateAllWidgets(any()) }
        coVerify(exactly = 1) { deviceCalendarReminderScheduler.scheduleNextReminder() }
    }

    @Test
    fun `slow reminder reschedule does not hold up the widget refresh`() = runTest(testDispatcher) {
        manager.onEnabled()
        consumeEnableRepick()
        // Only the observer's re-pick hangs, so the enable's job reconcile isn't held behind it.
        coEvery { deviceCalendarReminderScheduler.scheduleNextReminder() } coAnswers { awaitCancellation() }

        notifyEventChanges()
        advanceDebounce(3001)

        coVerify(exactly = 1) { deviceCalendarReminderScheduler.scheduleNextReminder() }
        coVerify(exactly = 1) { widgetUpdateManager.updateAllWidgets(any()) }
    }

    @Test
    fun `provider change refreshes widgets when device calendars were enabled at startup`() = runTest(testDispatcher) {
        dataStore.setDeviceCalendarsEnabled(true)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        notifyEventChanges()
        advanceDebounce(3001)

        coVerify(exactly = 1) { widgetUpdateManager.updateAllWidgets(any()) }
    }

    @Test
    fun `provider change does not refresh widgets when device calendars are disabled`() = runTest(testDispatcher) {
        dataStore.setDeviceCalendarsEnabled(false)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        notifyEventChanges()
        advanceDebounce(3001)

        coVerify(exactly = 0) { widgetUpdateManager.updateAllWidgets(any()) }
    }

    @Test
    fun `provider change does not refresh widgets without calendar permission`() = runTest(testDispatcher) {
        Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CALENDAR)
        manager.onEnabled()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            "No observer should be registered without calendar permission",
            Shadows.shadowOf(context.contentResolver)
                .getContentObservers(CalendarContract.Events.CONTENT_URI).isEmpty()
        )
        notifyEventChanges()
        advanceDebounce(3001)

        coVerify(exactly = 0) { widgetUpdateManager.updateAllWidgets(any()) }
    }

    @Test
    fun `provider change after disabling does not refresh widgets`() = runTest(testDispatcher) {
        manager.onEnabled()
        manager.onDisabled()

        notifyEventChanges()
        advanceDebounce(3001)

        coVerify(exactly = 0) { widgetUpdateManager.updateAllWidgets(any()) }
    }

    @Test
    fun `disabling during the debounce window cancels the pending widget refresh`() = runTest(testDispatcher) {
        manager.onEnabled()

        notifyEventChanges()
        advanceDebounce(1000)
        manager.onDisabled()
        advanceDebounce(3001)

        coVerify(exactly = 0) { widgetUpdateManager.updateAllWidgets(any()) }
    }

    // ========== Calendar-change job and app-start re-pick ==========

    /**
     * Runs the re-pick that switching on starts, checks it happened once and forgets it, so a
     * later count is the observer's alone.
     */
    private fun consumeEnableRepick() {
        testDispatcher.scheduler.runCurrent()
        coVerify(exactly = 1) { deviceCalendarReminderScheduler.scheduleNextReminder() }
        clearMocks(deviceCalendarReminderScheduler, answers = false)
    }

    private fun changeJobPending() = jobScheduler.getPendingJob(JobSchedulerIds.DEVICE_CALENDAR_CHANGE) != null

    private suspend fun enableDeviceReminders() {
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setDeviceCalendarRemindersEnabled(true)
    }

    @Test
    fun `app start re-picks the next device reminder`() = runTest(testDispatcher) {
        enableDeviceReminders()

        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { deviceCalendarReminderScheduler.scheduleNextReminder() }
        manager.onDisabled()
    }

    @Test
    fun `app start schedules the calendar-change job`() = runTest(testDispatcher) {
        enableDeviceReminders()

        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("calendar-change job must be pending after app start", changeJobPending())
        manager.onDisabled()
    }

    @Test
    fun `app start with device calendars off cancels a leftover calendar-change job`() = runTest(testDispatcher) {
        enableDeviceReminders()
        DeviceCalendarChangeWatcher(context, dataStore).reconcile()
        assertTrue(changeJobPending())
        dataStore.setDeviceCalendarsEnabled(false)

        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(changeJobPending())
    }

    @Test
    fun `app start after READ_CALENDAR was revoked leaves no calendar-change job`() = runTest(testDispatcher) {
        enableDeviceReminders()
        DeviceCalendarChangeWatcher(context, dataStore).reconcile()
        Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CALENDAR)

        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(dataStore.deviceCalendarsEnabled.first())
        assertFalse(changeJobPending())
    }

    @Test
    fun `a failing re-pick at app start still leaves the calendar-change job scheduled`() = runTest(testDispatcher) {
        enableDeviceReminders()
        coEvery { deviceCalendarReminderScheduler.scheduleNextReminder() } throws IllegalStateException("provider died")

        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(changeJobPending())
        manager.onDisabled()
    }

    @Test
    fun `switching device calendars on and off schedules and cancels the calendar-change job`() = runTest(testDispatcher) {
        enableDeviceReminders()
        manager.onEnabled()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(changeJobPending())

        dataStore.setDeviceCalendarsEnabled(false)
        manager.onDisabled()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(changeJobPending())
    }

    @Test
    fun `app start replaces a pending calendar-change job`() = runTest(testDispatcher) {
        // A copy left by a run that was killed looks current but may not run again for minutes.
        enableDeviceReminders()
        DeviceCalendarChangeWatcher(context, dataStore).reconcile()
        val seeded = jobScheduler.getPendingJob(JobSchedulerIds.DEVICE_CALENDAR_CHANGE)

        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        val now = jobScheduler.getPendingJob(JobSchedulerIds.DEVICE_CALENDAR_CHANGE)
        assertTrue(now != null && now !== seeded)
        manager.onDisabled()
    }

    @Test
    fun `the app-start re-pick runs before the calendar-change job is replaced`() = runTest(testDispatcher) {
        // Replacing the job stops a run JobScheduler started this process for and releases its
        // wakelock, so the re-pick goes first, while that wakelock is still held.
        enableDeviceReminders()
        DeviceCalendarChangeWatcher(context, dataStore).reconcile()
        val seeded = jobScheduler.getPendingJob(JobSchedulerIds.DEVICE_CALENDAR_CHANGE)
        var atRepick: JobInfo? = null
        coEvery { deviceCalendarReminderScheduler.scheduleNextReminder() } coAnswers {
            atRepick = jobScheduler.getPendingJob(JobSchedulerIds.DEVICE_CALENDAR_CHANGE)
        }

        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("the re-pick must still see the job it started with", atRepick === seeded)
        val now = jobScheduler.getPendingJob(JobSchedulerIds.DEVICE_CALENDAR_CHANGE)
        assertTrue("the job must be replaced after the re-pick", now != null && now !== seeded)
        manager.onDisabled()
    }

    @Test
    fun `app start with device reminders off cancels a pending calendar-change job`() = runTest(testDispatcher) {
        enableDeviceReminders()
        DeviceCalendarChangeWatcher(context, dataStore).reconcile()
        dataStore.setDeviceCalendarRemindersEnabled(false)

        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(changeJobPending())
        manager.onDisabled()
    }

    @Test
    fun `switching device calendars on re-picks the next device reminder`() = runTest(testDispatcher) {
        // Nothing in the provider changes when the switch turns on, so only onEnabled can re-pick.
        enableDeviceReminders()

        manager.onEnabled()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { deviceCalendarReminderScheduler.scheduleNextReminder() }
        manager.onDisabled()
    }

    @Test
    fun `a calendar setting change while device calendars are on re-picks the next device reminder`() = runTest(testDispatcher) {
        // A tick or the declined-events filter, so a newly ticked calendar's reminder is armed
        // without waiting for the provider write to reach the observer.
        enableDeviceReminders()

        manager.onDeviceCalendarSettingsChanged()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { deviceCalendarReminderScheduler.scheduleNextReminder() }
        manager.onDisabled()
    }

    @Test
    fun `a restore that turns device calendars on re-picks the next device reminder`() = runTest(testDispatcher) {
        enableDeviceReminders()

        manager.applyDeviceCalendarsSetting()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { deviceCalendarReminderScheduler.scheduleNextReminder() }
        manager.onDisabled()
    }

    @Test
    fun `a failing re-pick on enable still reconciles the calendar-change job`() = runTest(testDispatcher) {
        enableDeviceReminders()
        coEvery { deviceCalendarReminderScheduler.scheduleNextReminder() } throws IllegalStateException("provider died")

        manager.onEnabled()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { deviceCalendarReminderScheduler.scheduleNextReminder() }
        assertTrue(changeJobPending())
        manager.onDisabled()
    }

    @Test
    fun `an event written while the app wasn't running gets its reminder armed at app start`() = runTest(testDispatcher) {
        // The proven relaunch case: another app wrote the event while KashCal's process was dead,
        // so no observer saw it; opening the app must arm it.
        val provider = SqliteCalendarProvider.install()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        Shadows.shadowOf(alarmManager).scheduledAlarms.mapNotNull { it.operation }.forEach { alarmManager.cancel(it) }
        try {
            provider.seedCalendar(7L)
            enableDeviceReminders()
            dataStore.setEnabledDeviceCalendarIds(setOf(7L))
            val start = System.currentTimeMillis() + 2 * 60 * 60_000L
            val eventId = provider.insertRow(
                SqliteCalendarProvider.EVENTS,
                CalendarContract.Events.CALENDAR_ID to 7L, CalendarContract.Events.TITLE to "Written elsewhere",
                CalendarContract.Events.DTSTART to start, CalendarContract.Events.DTEND to start + 60 * 60_000L,
                CalendarContract.Events.EVENT_TIMEZONE to "UTC", CalendarContract.Events.ALL_DAY to 0,
                CalendarContract.Events.STATUS to CalendarContract.Events.STATUS_CONFIRMED,
            )
            provider.insertRow(
                SqliteCalendarProvider.REMINDERS,
                CalendarContract.Reminders.EVENT_ID to eventId, CalendarContract.Reminders.MINUTES to 15,
                CalendarContract.Reminders.METHOD to CalendarContract.Reminders.METHOD_ALERT,
            )
            val scheduler = DeviceCalendarReminderScheduler(context, SqliteCalendarProvider.repository(context), dataStore)
            manager = CalendarProviderManager(context, dataStore, scheduler, widgetUpdateManager, DeviceCalendarChangeWatcher(context, dataStore))

            manager.initialize()

            awaitUntil("the reminder to be armed") {
                deviceAlarms(alarmManager) == listOf(eventId to start - 15 * 60_000L)
            }
        } finally {
            manager.onDisabled()
            provider.db.close()
        }
    }

    /** Device reminder alarms currently set, as event id to trigger time. */
    private fun deviceAlarms(alarmManager: AlarmManager): List<Pair<Long, Long>> =
        Shadows.shadowOf(alarmManager).scheduledAlarms.mapNotNull { alarm ->
            val intent = alarm.operation?.let { Shadows.shadowOf(it).savedIntent } ?: return@mapNotNull null
            if (intent.component?.className != DeviceCalendarAlarmReceiver::class.java.name) return@mapNotNull null
            intent.getLongExtra(DeviceCalendarReminderScheduler.EXTRA_EVENT_ID, -1L) to alarm.triggerAtMs
        }

    private suspend fun TestScope.awaitUntil(what: String, condition: suspend () -> Boolean) {
        // The repository reads on Dispatchers.IO, so poll as well as advancing the test dispatcher.
        val deadline = System.currentTimeMillis() + 20_000
        while (true) {
            testScheduler.advanceUntilIdle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }
}
