package org.onekash.kashcal.data.calendar_provider

import android.Manifest
import android.app.Application
import android.content.Context
import android.provider.CalendarContract
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler
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
        deviceCalendarReminderScheduler = mockk(relaxed = true)
        widgetUpdateManager = mockk(relaxed = true)
        manager = CalendarProviderManager(context, dataStore, deviceCalendarReminderScheduler, widgetUpdateManager)
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
        manager = CalendarProviderManager(context, dataStore, deviceCalendarReminderScheduler, widgetUpdateManager)
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

        notifyEventChanges()
        advanceDebounce(3001)

        coVerify(exactly = 1) { widgetUpdateManager.updateAllWidgets(any()) }
        coVerify(exactly = 1) { deviceCalendarReminderScheduler.scheduleNextReminder() }
    }

    @Test
    fun `slow reminder reschedule does not hold up the widget refresh`() = runTest(testDispatcher) {
        coEvery { deviceCalendarReminderScheduler.scheduleNextReminder() } coAnswers { awaitCancellation() }
        manager.onEnabled()

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
}
