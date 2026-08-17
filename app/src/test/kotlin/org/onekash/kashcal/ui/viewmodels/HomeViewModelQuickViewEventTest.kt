package org.onekash.kashcal.ui.viewmodels

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus

/**
 * Pins the quick-view sheet's live-by-id behavior.
 *
 * The Room quick-view sheet must render the event re-read reactively by
 * its id ([HomeViewModel.quickViewEventLive]) rather than the immutable
 * snapshot captured at tap time. That way an edit's new title/time is
 * reflected even when the on-screen list that produced the tapped
 * snapshot was stale (e.g. search results, which never re-run after an
 * edit). Only the event id has to be right — and it never changes on an
 * edit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelQuickViewEventTest {

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

    private fun event(id: Long, title: String) = Event(
        id = id,
        calendarId = 1L,
        uid = "uid-$id",
        title = title,
        startTs = 0L,
        endTs = 0L,
        dtstamp = 0L,
    )

    @Test
    fun `quickViewEventLive is null when no event is active`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.quickViewEventLive.test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `quickViewEventLive emits the active event and re-emits its updated title`() = runTest {
        val backing = MutableStateFlow<Event?>(event(7L, "Old title"))
        every { eventReader.getEventByIdFlow(7L) } returns backing

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.quickViewEventLive.test {
            assertNull(awaitItem()) // no active event yet

            viewModel.setQuickViewEventId(7L)
            assertEquals("Old title", awaitItem()?.title)

            // Simulate an edit persisting a new title to the source of truth.
            backing.value = event(7L, "New title")
            assertEquals("New title", awaitItem()?.title)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `quickViewEventLive switches to the newly active event id`() = runTest {
        every { eventReader.getEventByIdFlow(1L) } returns MutableStateFlow(event(1L, "First"))
        every { eventReader.getEventByIdFlow(2L) } returns MutableStateFlow(event(2L, "Second"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.quickViewEventLive.test {
            assertNull(awaitItem())

            viewModel.setQuickViewEventId(1L)
            assertEquals("First", awaitItem()?.title)

            viewModel.setQuickViewEventId(2L)
            assertEquals("Second", awaitItem()?.title)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
