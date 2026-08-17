package org.onekash.kashcal.ui.viewmodels

import io.mockk.every
import io.mockk.mockk
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
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus

/**
 * Pins the immediate-refresh contract for device-calendar writes.
 *
 * Device events live in Android's CalendarProvider, not Room, so the reactive
 * views only re-query them when [DisplayEventRepository.deviceCalendarChangeSignal]
 * emits. That signal is normally driven by a debounced ContentObserver, which
 * left the calendar view stale for up to the debounce window after the app's
 * own create/edit/delete. Each device write must therefore explicitly request
 * an immediate device-view refresh so the change appears without lag.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelDeviceViewRefreshTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var eventCoordinator: EventCoordinator
    private lateinit var eventReader: EventReader
    private lateinit var displayEventRepository: DisplayEventRepository
    private lateinit var dataStore: KashCalDataStore
    private lateinit var accountRepository: AccountRepository
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var fakeCalendarProviderRepository: FakeCalendarProviderRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

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

        io.mockk.coEvery { dataStore.defaultCalendarId } returns MutableStateFlow(null)
        io.mockk.coEvery { dataStore.defaultReminderMinutes } returns MutableStateFlow(15)
        io.mockk.coEvery { dataStore.defaultAllDayReminder } returns MutableStateFlow(1440)
        io.mockk.coEvery { dataStore.defaultEventDuration } returns MutableStateFlow(20)
        io.mockk.coEvery { dataStore.timeFormat } returns MutableStateFlow("system")
        io.mockk.coEvery { dataStore.showEventEmojis } returns MutableStateFlow(true)
        io.mockk.coEvery { dataStore.onboardingDismissed } returns MutableStateFlow(true)

        every { eventCoordinator.getAllCalendars() } returns MutableStateFlow(emptyList())
        every { eventReader.getVisibleOccurrencesInRange(any(), any()) } returns MutableStateFlow(emptyList())
        every { eventReader.getVisibleOccurrencesForDay(any()) } returns MutableStateFlow(emptyList())
        every { eventReader.getVisibleOccurrencesWithEventsForDay(any()) } returns MutableStateFlow(emptyList())
        io.mockk.coEvery { accountRepository.getAccountsByProvider(any()) } returns emptyList()
        io.mockk.coEvery { accountRepository.hasCredentials(any()) } returns false
        every { displayEventRepository.deviceCalendarChangeSignal } returns MutableStateFlow(0)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HomeViewModel = HomeViewModel(
        eventCoordinator = eventCoordinator,
        eventReader = eventReader,
        displayEventRepository = displayEventRepository,
        dataStore = dataStore,
        accountRepository = accountRepository,
        syncScheduler = syncScheduler,
        networkMonitor = networkMonitor,
        calendarProviderRepository = fakeCalendarProviderRepository,
        attendeeBackfill = mockk(relaxed = true),
        contactEmailReader = mockk(relaxed = true),
        context = mockk(relaxed = true),
        ioDispatcher = testDispatcher,
    )

    @Test
    fun `creating a device event immediately refreshes the device view`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.createDeviceEvent(
            calendarId = 1L,
            title = "Lunch",
            description = null,
            location = null,
            startTs = 1_000L,
            endTs = 2_000L,
            isAllDay = false,
            rrule = null,
            timezone = "UTC",
            reminders = emptyList(),
        )
        advanceUntilIdle()

        verify { displayEventRepository.notifyDeviceCalendarChanged() }
    }

    @Test
    fun `editing a device event immediately refreshes the device view`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateDeviceEvent(
            eventId = 42L,
            title = "Lunch (moved)",
            description = null,
            location = null,
            startTs = 1_000L,
            endTs = 2_000L,
            isAllDay = false,
            rrule = null,
            timezone = "UTC",
            reminders = emptyList(),
        )
        advanceUntilIdle()

        verify { displayEventRepository.notifyDeviceCalendarChanged() }
    }

    @Test
    fun `deleting a device event immediately refreshes the device view`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteDeviceEvent(42L)
        advanceUntilIdle()

        verify { displayEventRepository.notifyDeviceCalendarChanged() }
    }

    @Test
    fun `importing ICS events into a device calendar immediately refreshes the device view`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.importIcsToDeviceCalendar(
            events = listOf(
                org.onekash.kashcal.data.db.entity.Event(
                    id = 0L,
                    calendarId = 0L,
                    uid = "import-1",
                    title = "Imported",
                    startTs = 1_000L,
                    endTs = 2_000L,
                    dtstamp = 0L,
                ),
            ),
            calendarId = 5L,
        )
        advanceUntilIdle()

        verify { displayEventRepository.notifyDeviceCalendarChanged() }
    }

    @Test
    fun `a Room-event write does not poke the device change signal`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Room events live in Room's reactive Flow, so their refresh must NOT
        // bump the device signal — doing so would blank and rebuild the month
        // dot cache (a visible flicker) on every Room edit and background sync.
        viewModel.deleteEvent(99L)
        advanceUntilIdle()

        verify(exactly = 0) { displayEventRepository.notifyDeviceCalendarChanged() }
    }
}
