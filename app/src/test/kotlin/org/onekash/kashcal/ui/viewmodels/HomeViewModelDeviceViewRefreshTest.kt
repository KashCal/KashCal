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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceChangeNotifier
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus
import org.onekash.kashcal.ui.components.EventFormState

/**
 * Tests the immediate-refresh contract for device-calendar writes.
 *
 * Device events live in Android's CalendarProvider, not Room, so the reactive
 * views only re-query them when [DisplayEventRepository.deviceCalendarChangeSignal]
 * emits. The provider's ContentObserver drives that signal too, but it is
 * debounced, so the device writer bumps it after each successful write and the
 * change shows without lag. A create, edit, delete and ICS import each send it
 * once; an import that creates nothing and a Room-event write send none. The
 * import test also checks the count and default reminders. These tests drive
 * the real ViewModel and writer over [FakeCalendarProviderRepository].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelDeviceViewRefreshTest {

    /** Receives the device change signal the writer sends after each successful write. */
    private val deviceManager = deviceChangeNotifier()

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
        deviceEventReader = fakeCalendarProviderRepository.deviceEventReader(),
        deviceEventWriter = fakeCalendarProviderRepository.deviceEventWriter(dataStore, deviceManager),
        attendeeBackfill = mockk(relaxed = true),
        contactEmailReader = mockk(relaxed = true),
        context = mockk(relaxed = true),
        ioDispatcher = testDispatcher,
    )

    @Test
    fun `creating a device event immediately refreshes the device view`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.saveDeviceEvent(
            EventFormState(title = "Lunch", selectedCalendarId = 1L, timezone = "UTC", isDeviceCalendar = true)
        )
        advanceUntilIdle()

        verify(exactly = 1) { deviceManager.notifyDeviceCalendarChanged() }
    }

    @Test
    fun `editing a device event immediately refreshes the device view`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.saveDeviceEvent(
            EventFormState(
                title = "Lunch (moved)",
                selectedCalendarId = 1L,
                timezone = "UTC",
                isDeviceCalendar = true,
                editingDeviceEventId = 42L,
            )
        )
        advanceUntilIdle()

        assertEquals(listOf(42L), fakeCalendarProviderRepository.updatedEventIds)
        verify(exactly = 1) { deviceManager.notifyDeviceCalendarChanged() }
    }

    @Test
    fun `deleting a device event immediately refreshes the device view`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteDeviceEvent(42L)
        advanceUntilIdle()

        verify(exactly = 1) { deviceManager.notifyDeviceCalendarChanged() }
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

        verify(exactly = 1) { deviceManager.notifyDeviceCalendarChanged() }
    }

    private fun importedEvent(uid: String, isAllDay: Boolean = false) =
        org.onekash.kashcal.data.db.entity.Event(
            id = 0L,
            calendarId = 0L,
            uid = uid,
            title = "Imported $uid",
            startTs = 1_000L,
            endTs = 2_000L,
            dtstamp = 0L,
            isAllDay = isAllDay,
        )

    @Test
    fun `importing ICS events into a device calendar returns the count and applies default reminders`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val count = viewModel.importIcsToDeviceCalendar(
            events = listOf(importedEvent("timed"), importedEvent("all-day", isAllDay = true)),
            calendarId = 5L,
        )
        advanceUntilIdle()

        assertEquals(2, count)
        val created = fakeCalendarProviderRepository.createdEvents
        assertEquals(listOf(5L, 5L), created.map { it.calendarId })
        assertEquals("timed default", listOf(15), created[0].reminders)
        assertEquals("all-day default", listOf(1440), created[1].reminders)
    }

    @Test
    fun `an ICS import that creates nothing does not poke the device change signal`() = runTest {
        fakeCalendarProviderRepository.writeFailure =
            org.onekash.kashcal.error.CalendarError.DeviceCalendar.WriteFailed("boom")
        val viewModel = createViewModel()
        advanceUntilIdle()

        val count = viewModel.importIcsToDeviceCalendar(listOf(importedEvent("x")), calendarId = 5L)
        advanceUntilIdle()

        assertEquals(0, count)
        verify(exactly = 0) { deviceManager.notifyDeviceCalendarChanged() }
    }

    @Test
    fun `a Room-event write does not poke the device change signal`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Room events live in Room's reactive Flow, so their refresh must not bump
        // the device signal, which blanks and rebuilds the month dot cache (a
        // visible flicker) on every Room edit and background sync.
        viewModel.deleteEvent(99L)
        advanceUntilIdle()

        verify(exactly = 0) { deviceManager.notifyDeviceCalendarChanged() }
    }
}
