package org.onekash.kashcal.ui.viewmodels

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.util.TimeZone

/**
 * Tests for HomeViewModel.getDeviceEventForQuickView().
 *
 * Verifies that widget taps correctly find device calendar events,
 * including all-day events in negative UTC offsets where UTC midnight
 * maps to the previous local day.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelDeviceQuickViewTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var eventCoordinator: EventCoordinator
    private lateinit var eventReader: EventReader
    private lateinit var displayEventRepository: DisplayEventRepository
    private lateinit var dataStore: KashCalDataStore
    private lateinit var accountRepository: AccountRepository
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var fakeCalendarProviderRepository: FakeCalendarProviderRepository

    private var savedTimeZone: TimeZone? = null

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        savedTimeZone = TimeZone.getDefault()

        eventCoordinator = mockk(relaxed = true)
        eventReader = mockk(relaxed = true)
        displayEventRepository = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        fakeCalendarProviderRepository = FakeCalendarProviderRepository()

        every { networkMonitor.isOnline } returns MutableStateFlow(true)
        every { networkMonitor.isMetered } returns MutableStateFlow(false)
        every { syncScheduler.observeImmediateSyncStatus() } returns MutableStateFlow(SyncStatus.Idle)
        every { syncScheduler.lastSyncChanges } returns MutableStateFlow(emptyList())
        every { syncScheduler.showBannerForSync } returns MutableStateFlow(false)
        every { syncScheduler.setShowBannerForSync(any()) } answers { }
        every { syncScheduler.resetBannerFlag() } answers { }

        coEvery { dataStore.defaultCalendarId } returns MutableStateFlow(null)
        coEvery { dataStore.defaultReminderMinutes } returns MutableStateFlow(15)
        coEvery { dataStore.defaultAllDayReminder } returns MutableStateFlow(1440)
        coEvery { dataStore.defaultEventDuration } returns MutableStateFlow(20)
        coEvery { dataStore.timeFormat } returns MutableStateFlow("system")
        coEvery { dataStore.showEventEmojis } returns MutableStateFlow(true)
        coEvery { dataStore.onboardingDismissed } returns MutableStateFlow(true)

        every { eventCoordinator.getAllCalendars() } returns MutableStateFlow(emptyList())
        every { eventReader.getVisibleOccurrencesInRange(any(), any()) } returns MutableStateFlow(emptyList())
        every { eventReader.getVisibleOccurrencesForDay(any()) } returns MutableStateFlow(emptyList())
        every { eventReader.getVisibleOccurrencesWithEventsForDay(any()) } returns MutableStateFlow(emptyList())
        coEvery { accountRepository.getAccountsByProvider(any()) } returns emptyList()
        coEvery { accountRepository.hasCredentials(any()) } returns false
        every { displayEventRepository.deviceCalendarChangeSignal } returns MutableStateFlow(0)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        TimeZone.setDefault(savedTimeZone)
    }

    private fun createViewModel(): HomeViewModel {
        return HomeViewModel(
            eventCoordinator = eventCoordinator,
            eventReader = eventReader,
            displayEventRepository = displayEventRepository,
            dataStore = dataStore,
            accountRepository = accountRepository,
            syncScheduler = syncScheduler,
            networkMonitor = networkMonitor,
            calendarProviderRepository = fakeCalendarProviderRepository,
            attendeeBackfill = io.mockk.mockk(relaxed = true),
            context = io.mockk.mockk(relaxed = true),
            ioDispatcher = testDispatcher
        )
    }

    private fun makeDeviceInstance(
        eventId: Long,
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean,
        title: String = "Test Event"
    ): DeviceCalendarInstance {
        val startDay = DateTimeUtils.eventTsToDayCode(startTs, isAllDay)
        val endDay = DateTimeUtils.eventTsToDayCode(endTs, isAllDay)
        return DeviceCalendarInstance(
            instanceId = eventId * 1000,
            eventId = eventId,
            title = title,
            description = "",
            location = "",
            startTs = startTs,
            endTs = endTs,
            startDay = startDay,
            endDay = endDay,
            isAllDay = isAllDay,
            hasRrule = false,
            rrule = null,
            reminders = emptyList(),
            calendarId = 1L,
            calendarDisplayName = "Test Calendar",
            calendarColor = 0xFF0000FF.toInt(),
            eventColor = null,
            status = 1,
            availability = 0,
            hasAlarm = false,
            selfAttendeeStatus = 0,
            isWritable = true,
            originalId = null,
            originalInstanceTime = null,
            timezone = "UTC",
            eventStartTs = startTs,
        )
    }

    // ==================== Timed Event Tests ====================

    @Test
    fun `timed event found by eventId and startTs`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")) // UTC-5

        // Timed event: March 7, 2026 10:00 AM ET = 15:00 UTC
        val startTs = Instant.parse("2026-03-07T15:00:00Z").toEpochMilli()
        val endTs = Instant.parse("2026-03-07T16:00:00Z").toEpochMilli()
        val instance = makeDeviceInstance(42L, startTs, endTs, isAllDay = false)
        val displayEvent = DisplayEvent.Device(instance)
        val dayCode = 20260307

        coEvery {
            displayEventRepository.getDisplayEventsGroupedByDayOnce(dayCode, dayCode)
        } returns persistentMapOf(dayCode to persistentListOf(displayEvent))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.getDeviceEventForQuickView(42L, startTs)
        advanceUntilIdle()

        assertNotNull("Timed event should be found", result)
        assertEquals(42L, result!!.instance.eventId)
    }

    @Test
    fun `returns null for non-existent eventId`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))

        val startTs = Instant.parse("2026-03-07T15:00:00Z").toEpochMilli()

        coEvery {
            displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any())
        } returns persistentMapOf()

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.getDeviceEventForQuickView(999L, startTs)
        advanceUntilIdle()

        assertNull("Non-existent event should return null", result)
    }

    // ==================== All-Day Event Timezone Tests ====================

    @Test
    fun `all-day event found in negative UTC offset`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")) // UTC-5

        // All-day event March 7: CalendarProvider BEGIN = March 7 00:00 UTC
        // In UTC-5: eventTsToDayCode(ts, false) = March 6 (wrong!)
        // In UTC:   eventTsToDayCode(ts, true) = March 7 (correct)
        val startTs = Instant.parse("2026-03-07T00:00:00Z").toEpochMilli()
        val endTs = Instant.parse("2026-03-07T23:59:59.999Z").toEpochMilli()
        val instance = makeDeviceInstance(42L, startTs, endTs, isAllDay = true, title = "All Day Event")
        val displayEvent = DisplayEvent.Device(instance)

        val correctDayCode = 20260307 // event's actual day (computed with isAllDay=true)
        val wrongDayCode = 20260306   // what isAllDay=false would give in UTC-5

        // The repository returns the event under its correct day code
        coEvery {
            displayEventRepository.getDisplayEventsGroupedByDayOnce(wrongDayCode, correctDayCode)
        } returns persistentMapOf(correctDayCode to persistentListOf(displayEvent))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.getDeviceEventForQuickView(42L, startTs)
        advanceUntilIdle()

        assertNotNull("All-day event should be found even in negative UTC offset", result)
        assertEquals("All Day Event", result!!.title)
    }

    @Test
    fun `all-day event found in positive UTC offset`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata")) // UTC+5:30

        // All-day event March 7: CalendarProvider BEGIN = March 7 00:00 UTC
        // In UTC+5:30: eventTsToDayCode(ts, false) = March 7 05:30 local = still March 7
        // Both day codes are the same, so single-day query suffices
        val startTs = Instant.parse("2026-03-07T00:00:00Z").toEpochMilli()
        val endTs = Instant.parse("2026-03-07T23:59:59.999Z").toEpochMilli()
        val instance = makeDeviceInstance(42L, startTs, endTs, isAllDay = true)
        val displayEvent = DisplayEvent.Device(instance)

        val dayCode = 20260307

        coEvery {
            displayEventRepository.getDisplayEventsGroupedByDayOnce(dayCode, dayCode)
        } returns persistentMapOf(dayCode to persistentListOf(displayEvent))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.getDeviceEventForQuickView(42L, startTs)
        advanceUntilIdle()

        assertNotNull("All-day event should be found in positive UTC offset", result)
    }

    @Test
    fun `all-day event found in UTC timezone`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

        val startTs = Instant.parse("2026-03-07T00:00:00Z").toEpochMilli()
        val endTs = Instant.parse("2026-03-07T23:59:59.999Z").toEpochMilli()
        val instance = makeDeviceInstance(42L, startTs, endTs, isAllDay = true)
        val displayEvent = DisplayEvent.Device(instance)

        val dayCode = 20260307

        coEvery {
            displayEventRepository.getDisplayEventsGroupedByDayOnce(dayCode, dayCode)
        } returns persistentMapOf(dayCode to persistentListOf(displayEvent))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.getDeviceEventForQuickView(42L, startTs)
        advanceUntilIdle()

        assertNotNull("All-day event should be found in UTC", result)
    }
}