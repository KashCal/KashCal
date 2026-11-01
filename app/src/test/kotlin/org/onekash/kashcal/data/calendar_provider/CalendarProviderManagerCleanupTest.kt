package org.onekash.kashcal.data.calendar_provider

import android.Manifest
import android.app.Application
import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.reminder.device.DeviceCalendarChangeWatcher
import org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler
import org.onekash.kashcal.widget.WidgetUpdateManager
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Tests the reminder cleanup in [CalendarProviderManager]: onDisabled and onRemindersDisabled
 * cancel the pending alarm, onRemindersEnabled schedules the next reminder, and both reminder
 * switches bring the calendar-change job in step.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CalendarProviderManagerCleanupTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Application
    private lateinit var dataStore: KashCalDataStore
    private lateinit var deviceCalendarReminderScheduler: DeviceCalendarReminderScheduler
    private lateinit var widgetUpdateManager: WidgetUpdateManager
    private lateinit var changeWatcher: DeviceCalendarChangeWatcher
    private lateinit var manager: CalendarProviderManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        context = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        deviceCalendarReminderScheduler = mockk(relaxed = true)
        widgetUpdateManager = mockk(relaxed = true)
        // Side-effect collaborator (schedules or cancels a job), so relaxed is allowed.
        changeWatcher = mockk(relaxed = true)

        every { dataStore.deviceCalendarsEnabled } returns MutableStateFlow(false)
        every { context.contentResolver } returns mockk(relaxed = true)

        manager = CalendarProviderManager(context, dataStore, deviceCalendarReminderScheduler, widgetUpdateManager, changeWatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `onDisabled cancels pending alarm`() = runTest {
        manager.onDisabled()
        advanceUntilIdle()

        verify { deviceCalendarReminderScheduler.cancelPendingAlarm() }
    }

    @Test
    fun `onRemindersDisabled cancels alarm without unregistering observer`() = runTest {
        // Enable first, which registers the observer.
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)

        val realDataStore = mockk<KashCalDataStore>(relaxed = true)
        every { realDataStore.deviceCalendarsEnabled } returns MutableStateFlow(true)

        val realManager = CalendarProviderManager(app, realDataStore, deviceCalendarReminderScheduler, widgetUpdateManager, changeWatcher)
        realManager.onEnabled()
        advanceUntilIdle()

        // Then disable only reminders.
        realManager.onRemindersDisabled()
        advanceUntilIdle()

        verify { deviceCalendarReminderScheduler.cancelPendingAlarm() }

        // That the observer stays registered isn't asserted here.
    }

    @Test
    fun `onRemindersEnabled schedules next reminder`() = runTest {
        coEvery { deviceCalendarReminderScheduler.scheduleNextReminder() } returns Unit

        manager.onRemindersEnabled()
        advanceUntilIdle()

        coVerify { deviceCalendarReminderScheduler.scheduleNextReminder() }
    }

    @Test
    fun `switching device reminders on and off reconciles the calendar-change job`() = runTest {
        manager.onRemindersEnabled()
        manager.onRemindersDisabled()
        advanceUntilIdle()

        coVerify(exactly = 2) { changeWatcher.reconcile() }
    }

    // The ContentObserver callback (reminder reschedule, change signal, widget refresh on a
    // CalendarProvider change) is covered in CalendarProviderManagerTest, which delivers real
    // change notifications through the Robolectric ContentResolver.
}
