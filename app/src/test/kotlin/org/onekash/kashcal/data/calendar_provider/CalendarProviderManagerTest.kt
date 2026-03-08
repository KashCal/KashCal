package org.onekash.kashcal.data.calendar_provider

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Unit tests for CalendarProviderManager.
 *
 * Tests lifecycle methods and changeSignal behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CalendarProviderManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var dataStore: KashCalDataStore
    private lateinit var deviceCalendarReminderScheduler: DeviceCalendarReminderScheduler
    private lateinit var manager: CalendarProviderManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        // Grant READ_CALENDAR by default so lifecycle tests work
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALENDAR)
        dataStore = KashCalDataStore(context)
        deviceCalendarReminderScheduler = mockk(relaxed = true)
        manager = CalendarProviderManager(context, dataStore, deviceCalendarReminderScheduler)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
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
        // Default is disabled, so initialize should not register observer
        dataStore.setDeviceCalendarsEnabled(false)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()
        // No crash = success (observer not registered means no unregister needed)
    }

    @Test
    fun `onDisabled is safe to call without prior onEnabled`() = runTest(testDispatcher) {
        // Should not throw even if observer was never registered
        manager.onDisabled()
    }

    // ========== Stale Calendar ID Pruning ==========

    @Test
    fun `pruneStaleCalendarIds removes IDs not in actual calendars`() = runTest {
        val fake = FakeCalendarProviderRepository()
        fake.calendars = listOf(
            DeviceCalendar(id = 1L, displayName = "Cal 1", color = 0, accountName = "a", accountType = "t", visible = true, accessLevel = 700),
            DeviceCalendar(id = 3L, displayName = "Cal 3", color = 0, accountName = "a", accountType = "t", visible = true, accessLevel = 700)
        )
        // Store IDs 1, 2, 3 — but calendar 2 no longer exists
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
        // Simulate: user enabled device calendars, then revoked READ_CALENDAR in system settings
        dataStore.setDeviceCalendarsEnabled(true)

        // Deny READ_CALENDAR permission (simulating revocation)
        Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CALENDAR)

        // Recreate manager so it picks up the denied permission state
        manager = CalendarProviderManager(context, dataStore, deviceCalendarReminderScheduler)
        manager.initialize()
        // Pump dispatcher + real-time waits for DataStore IO to complete
        repeat(10) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(50)
        }

        // Feature should be auto-disabled, no crash
        assertFalse(
            "Feature should be auto-disabled when permission is revoked",
            dataStore.deviceCalendarsEnabled.first()
        )
    }
}
