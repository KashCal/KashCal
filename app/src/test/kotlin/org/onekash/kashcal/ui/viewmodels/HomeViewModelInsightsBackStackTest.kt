package org.onekash.kashcal.ui.viewmodels

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.robolectric.RobolectricTestRunner

/**
 * Locks in the back-from-Insights contract: HomeViewModel tracks the
 * last non-INSIGHTS view in [HomeUiState.previousNonInsightsMode] so the
 * Insights screen can return the user to whichever view they came from.
 *
 * Critical invariants:
 * 1. On VM init the field is seeded from the user's persisted default —
 *    not hardcoded to MONTH — so a deep-link directly into Insights still
 *    backs out to the user's preferred view.
 * 2. setViewMode(non-INSIGHTS) updates both viewMode AND previousNonInsightsMode.
 * 3. setViewMode(INSIGHTS) updates only viewMode; previousNonInsightsMode
 *    is preserved so the back-target survives the transition.
 *
 * The DataStore invariant that prevents "insights" from being persisted as
 * the default view (and therefore poisoning the seed) is locked in by
 * KashCalDataStoreInvariantTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeViewModelInsightsBackStackTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var eventCoordinator: EventCoordinator
    private lateinit var eventReader: EventReader
    private lateinit var displayEventRepository: DisplayEventRepository
    private lateinit var dataStore: KashCalDataStore
    private lateinit var accountRepository: AccountRepository
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var networkMonitor: NetworkMonitor

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        eventCoordinator = mockk(relaxed = true)
        eventReader = mockk(relaxed = true)
        displayEventRepository = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)

        every { networkMonitor.isOnline } returns MutableStateFlow(true)
        every { networkMonitor.isMetered } returns MutableStateFlow(false)
        every { syncScheduler.observeImmediateSyncStatus() } returns MutableStateFlow(SyncStatus.Idle)
        every { syncScheduler.showBannerForSync } returns MutableStateFlow(false)
        every { syncScheduler.lastSyncChanges } returns MutableStateFlow(emptyList())
        every { eventCoordinator.getAllCalendars() } returns flowOf(emptyList())
        every { eventCoordinator.getAllAccounts() } returns flowOf(emptyList())
        every { dataStore.defaultCalendar } returns flowOf(null)
        coEvery { dataStore.defaultReminderMinutes } returns flowOf(15)
        coEvery { dataStore.defaultAllDayReminder } returns flowOf(1440)
        coEvery { accountRepository.getAllAccounts() } returns emptyList()
        coEvery { accountRepository.hasCredentials(any()) } returns false
        every { eventReader.getVisibleOccurrencesForDay(any()) } returns flowOf(emptyList())
        every { eventReader.getVisibleOccurrencesWithEventsForDay(any()) } returns flowOf(emptyList())
        coEvery { eventReader.getVisibleOccurrencesInRange(any(), any()) } returns flowOf(emptyList())
        every { displayEventRepository.deviceCalendarChangeSignal } returns MutableStateFlow(0)
        every { dataStore.defaultCalendarView } returns flowOf(KashCalDataStore.VIEW_MONTH)
        coEvery { dataStore.getDefaultCalendarView() } returns KashCalDataStore.VIEW_MONTH
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)
        every { dataStore.onboardingDismissed } returns flowOf(true)
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
        calendarProviderRepository = org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository(),
        attendeeBackfill = mockk(relaxed = true),
        contactEmailReader = mockk(relaxed = true),
        context = mockk(relaxed = true),
        ioDispatcher = testDispatcher,
        currentDayCodeProvider = { DayPagerUtils.msToDayCode(System.currentTimeMillis()) }
    )

    @Test
    fun `previousNonInsightsMode seeded from persisted default - month`() = runTest {
        coEvery { dataStore.getDefaultCalendarView() } returns KashCalDataStore.VIEW_MONTH
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(ViewMode.MONTH, viewModel.uiState.value.previousNonInsightsMode)
    }

    @Test
    fun `previousNonInsightsMode seeded from persisted default - agenda`() = runTest {
        coEvery { dataStore.getDefaultCalendarView() } returns KashCalDataStore.VIEW_AGENDA
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(ViewMode.AGENDA, viewModel.uiState.value.previousNonInsightsMode)
    }

    @Test
    fun `setViewMode to non-INSIGHTS updates previousNonInsightsMode`() = runTest {
        coEvery { dataStore.getDefaultCalendarView() } returns KashCalDataStore.VIEW_MONTH
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setViewMode(ViewMode.WEEK)
        advanceUntilIdle()

        assertEquals(ViewMode.WEEK, viewModel.uiState.value.viewMode)
        assertEquals(ViewMode.WEEK, viewModel.uiState.value.previousNonInsightsMode)
    }

    @Test
    fun `setViewMode to INSIGHTS preserves previousNonInsightsMode`() = runTest {
        coEvery { dataStore.getDefaultCalendarView() } returns KashCalDataStore.VIEW_MONTH
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setViewMode(ViewMode.WEEK)
        advanceUntilIdle()
        viewModel.setViewMode(ViewMode.INSIGHTS)
        advanceUntilIdle()

        assertEquals(ViewMode.INSIGHTS, viewModel.uiState.value.viewMode)
        assertEquals(ViewMode.WEEK, viewModel.uiState.value.previousNonInsightsMode)
    }

    @Test
    fun `setViewMode after returning from INSIGHTS updates previousNonInsightsMode`() = runTest {
        coEvery { dataStore.getDefaultCalendarView() } returns KashCalDataStore.VIEW_MONTH
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setViewMode(ViewMode.WEEK)
        advanceUntilIdle()
        viewModel.setViewMode(ViewMode.INSIGHTS)
        advanceUntilIdle()
        viewModel.setViewMode(ViewMode.MONTH)
        advanceUntilIdle()

        assertEquals(ViewMode.MONTH, viewModel.uiState.value.viewMode)
        assertEquals(ViewMode.MONTH, viewModel.uiState.value.previousNonInsightsMode)
    }
}
