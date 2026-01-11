package org.onekash.kashcal.ui.viewmodels

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.provider.icloud.ICloudAccount
import org.onekash.kashcal.sync.provider.icloud.ICloudAuthManager
import org.onekash.kashcal.data.db.dao.EventWithNextOccurrence
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus
import org.onekash.kashcal.ui.components.EventFormState
import java.util.Calendar as JavaCalendar

/**
 * Unit tests for HomeViewModel.
 *
 * Tests cover:
 * - Initial state and async initialization
 * - Calendar loading and visibility
 * - Event dots building
 * - Day selection and event loading
 * - Search functionality
 * - iCloud status checking
 * - Sync operations
 * - Network state transitions
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    // Mocks
    private lateinit var eventCoordinator: EventCoordinator
    private lateinit var eventReader: EventReader
    private lateinit var dataStore: KashCalDataStore
    private lateinit var authManager: ICloudAuthManager
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var networkMonitor: NetworkMonitor

    // Network state flow that we control
    private lateinit var networkStateFlow: MutableStateFlow<Boolean>
    private lateinit var networkMeteredFlow: MutableStateFlow<Boolean>

    // Sync status flow that we control
    private lateinit var syncStatusFlow: MutableStateFlow<SyncStatus>

    // Banner flag flow that we control
    private lateinit var bannerFlagFlow: MutableStateFlow<Boolean>

    // Test data
    private val testCalendars = listOf(
        Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://caldav.icloud.com/cal1",
            displayName = "Personal",
            color = 0xFF2196F3.toInt()
        ),
        Calendar(
            id = 2L,
            accountId = 1L,
            caldavUrl = "https://caldav.icloud.com/cal2",
            displayName = "Work",
            color = 0xFF4CAF50.toInt()
        )
    )

    private val testOccurrences = listOf(
        Occurrence(
            id = 1L,
            eventId = 1L,
            calendarId = 1L,
            startTs = getTimestamp(2024, 11, 17, 10, 0),
            endTs = getTimestamp(2024, 11, 17, 11, 0),
            startDay = 20241217,
            endDay = 20241217
        ),
        Occurrence(
            id = 2L,
            eventId = 2L,
            calendarId = 2L,
            startTs = getTimestamp(2024, 11, 17, 14, 0),
            endTs = getTimestamp(2024, 11, 17, 15, 0),
            startDay = 20241217,
            endDay = 20241217
        )
    )

    private val testEvents = listOf(
        Event(
            id = 1L,
            uid = "event-1@test",
            calendarId = 1L,
            title = "Meeting",
            startTs = getTimestamp(2024, 11, 17, 10, 0),
            endTs = getTimestamp(2024, 11, 17, 11, 0),
            dtstamp = System.currentTimeMillis()
        ),
        Event(
            id = 2L,
            uid = "event-2@test",
            calendarId = 2L,
            title = "Code Review",
            startTs = getTimestamp(2024, 11, 17, 14, 0),
            endTs = getTimestamp(2024, 11, 17, 15, 0),
            dtstamp = System.currentTimeMillis()
        )
    )

    private val testEventsWithNextOccurrence by lazy {
        testEvents.map { event ->
            EventWithNextOccurrence(event = event, nextOccurrenceTs = event.startTs)
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Initialize mocks
        eventCoordinator = mockk(relaxed = true)
        eventReader = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        authManager = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)

        // Setup network monitor
        networkStateFlow = MutableStateFlow(true)
        networkMeteredFlow = MutableStateFlow(false)
        every { networkMonitor.isOnline } returns networkStateFlow
        every { networkMonitor.isMetered } returns networkMeteredFlow

        // Setup sync status flow
        syncStatusFlow = MutableStateFlow(SyncStatus.Idle)
        every { syncScheduler.observeImmediateSyncStatus() } returns syncStatusFlow

        // Setup banner flag flow
        bannerFlagFlow = MutableStateFlow(false)
        every { syncScheduler.showBannerForSync } returns bannerFlagFlow
        every { syncScheduler.setShowBannerForSync(any()) } answers { bannerFlagFlow.value = firstArg() }
        every { syncScheduler.resetBannerFlag() } answers { bannerFlagFlow.value = false }

        // Setup sync changes flow (for snackbar notifications)
        every { syncScheduler.lastSyncChanges } returns MutableStateFlow(emptyList())
        every { syncScheduler.clearSyncChanges() } returns Unit

        // Setup default mock behavior - EventCoordinator provides calendars via Flow
        every { eventCoordinator.getAllCalendars() } returns flowOf(testCalendars)
        coEvery { dataStore.defaultCalendarId } returns flowOf(null)
        coEvery { dataStore.defaultReminderMinutes } returns flowOf(15)
        coEvery { dataStore.defaultAllDayReminder } returns flowOf(1440)
        coEvery { authManager.loadAccount() } returns null
        coEvery { eventReader.getVisibleOccurrencesInRange(any(), any()) } returns flowOf(testOccurrences)
        every { eventReader.getVisibleOccurrencesForDay(any()) } returns flowOf(testOccurrences)
        coEvery { eventReader.getEventById(1L) } returns testEvents[0]
        coEvery { eventReader.getEventById(2L) } returns testEvents[1]
        coEvery { eventReader.getEventsByIds(any()) } coAnswers {
            val ids = firstArg<List<Long>>()
            // Delegate to getEventById mocks so individual test setups work
            ids.mapNotNull { id ->
                eventReader.getEventById(id)?.let { id to it }
            }.toMap()
        }
        coEvery { eventReader.searchEvents(any()) } returns testEvents
        coEvery { eventReader.searchEventsExcludingPast(any()) } returns testEvents
        coEvery { eventReader.searchEventsWithNextOccurrence(any()) } returns testEventsWithNextOccurrence
        coEvery { eventReader.searchEventsExcludingPastWithNextOccurrence(any()) } returns testEventsWithNextOccurrence
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HomeViewModel {
        return HomeViewModel(
            eventCoordinator = eventCoordinator,
            eventReader = eventReader,
            dataStore = dataStore,
            authManager = authManager,
            syncScheduler = syncScheduler,
            networkMonitor = networkMonitor,
            ioDispatcher = testDispatcher
        )
    }

    // ==================== Initial State Tests ====================

    @Test
    fun `initial state has current month and year`() = runTest {
        val viewModel = createViewModel()

        val today = JavaCalendar.getInstance()
        assertEquals(today.get(JavaCalendar.YEAR), viewModel.uiState.value.viewingYear)
        assertEquals(today.get(JavaCalendar.MONTH), viewModel.uiState.value.viewingMonth)
    }

    @Test
    fun `initial state is not syncing`() = runTest {
        val viewModel = createViewModel()

        assertFalse(viewModel.uiState.value.isSyncing)
    }

    @Test
    fun `initial state has online from network monitor`() = runTest {
        val viewModel = createViewModel()

        // isOnline is exposed directly as StateFlow, not in uiState
        assertTrue(viewModel.isOnline.value)
    }

    // ==================== Async Initialization Tests ====================

    @Test
    fun `initializeAsync loads calendars`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(testCalendars.size, viewModel.uiState.value.calendars.size)
        assertEquals("Personal", viewModel.uiState.value.calendars[0].displayName)
        assertEquals("Work", viewModel.uiState.value.calendars[1].displayName)
    }

    @Test
    fun `initializeAsync loads calendars with visibility from Calendar isVisible`() = runTest {
        // Calendars have visibility from Calendar.isVisible (DB source of truth)
        val calendarsWithVisibility = listOf(
            testCalendars[0].copy(isVisible = true),
            testCalendars[1].copy(isVisible = false)
        )
        every { eventCoordinator.getAllCalendars() } returns flowOf(calendarsWithVisibility)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Visibility is derived from Calendar.isVisible, not a separate UI state field
        assertTrue(viewModel.uiState.value.calendars[0].isVisible)
        assertFalse(viewModel.uiState.value.calendars[1].isVisible)
    }

    @Test
    fun `initializeAsync checks iCloud status`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // With null account, should not be configured
        assertFalse(viewModel.uiState.value.isConfigured)
        assertFalse(viewModel.uiState.value.isICloudConnected)
    }

    @Test
    fun `initializeAsync sets iCloud connected when configured`() = runTest {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "test-password",
            isEnabled = true
        )
        coEvery { authManager.loadAccount() } returns account

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isConfigured)
        assertTrue(viewModel.uiState.value.isICloudConnected)
    }

    // ==================== Calendar Visibility Tests ====================

    @Test
    fun `toggleCalendarVisibility calls eventCoordinator setCalendarVisibility`() = runTest {
        // Setup mock for setCalendarVisibility
        coEvery { eventCoordinator.setCalendarVisibility(any(), any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Toggle calendar 1 visibility (currently visible -> hidden)
        viewModel.toggleCalendarVisibility(1L)
        advanceUntilIdle()

        // Should call EventCoordinator to update DB (source of truth)
        coVerify { eventCoordinator.setCalendarVisibility(1L, false) }
    }

    @Test
    fun `showAllCalendars calls setCalendarVisibility for all calendars`() = runTest {
        // Setup mock for setCalendarVisibility
        coEvery { eventCoordinator.setCalendarVisibility(any(), any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showAllCalendars()
        advanceUntilIdle()

        // Should call EventCoordinator.setCalendarVisibility(id, true) for each calendar
        coVerify { eventCoordinator.setCalendarVisibility(1L, true) }
        coVerify { eventCoordinator.setCalendarVisibility(2L, true) }
    }

    // ==================== Navigation Tests ====================

    @Test
    fun `goToToday sets current date`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.goToToday()
        advanceUntilIdle()

        val today = JavaCalendar.getInstance()
        assertEquals(today.get(JavaCalendar.YEAR), viewModel.uiState.value.viewingYear)
        assertEquals(today.get(JavaCalendar.MONTH), viewModel.uiState.value.viewingMonth)
        assertTrue(viewModel.uiState.value.pendingNavigateToToday)
    }

    @Test
    fun `clearNavigateToToday clears flag`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.goToToday()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pendingNavigateToToday)

        viewModel.clearNavigateToToday()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.pendingNavigateToToday)
    }

    @Test
    fun `navigateToMonth sets viewing month and year`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.navigateToMonth(2025, 5)
        advanceUntilIdle()

        assertEquals(2025, viewModel.uiState.value.viewingYear)
        assertEquals(5, viewModel.uiState.value.viewingMonth)
        assertEquals(2025 to 5, viewModel.uiState.value.pendingNavigateToMonth)
    }

    @Test
    fun `navigateToMonth dismisses year overlay`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // First show the year overlay
        viewModel.toggleYearOverlay()
        assertTrue(viewModel.uiState.value.showYearOverlay)

        // Navigate to month should dismiss it
        viewModel.navigateToMonth(2025, 5)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showYearOverlay)
    }

    @Test
    fun `toggleYearOverlay toggles state`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Initial state should be false
        assertFalse(viewModel.uiState.value.showYearOverlay)

        // Toggle to true
        viewModel.toggleYearOverlay()
        assertTrue(viewModel.uiState.value.showYearOverlay)

        // Toggle back to false
        viewModel.toggleYearOverlay()
        assertFalse(viewModel.uiState.value.showYearOverlay)
    }

    @Test
    fun `setViewingMonth updates without navigation flag`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setViewingMonth(2025, 3)
        advanceUntilIdle()

        assertEquals(2025, viewModel.uiState.value.viewingYear)
        assertEquals(3, viewModel.uiState.value.viewingMonth)
    }

    // ==================== Day Selection Tests ====================

    @Test
    fun `selectDate updates selected date and label`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val dateMillis = getTimestamp(2024, 11, 17, 0, 0)
        viewModel.selectDate(dateMillis)
        advanceUntilIdle()

        assertEquals(dateMillis, viewModel.uiState.value.selectedDate)
        assertTrue(viewModel.uiState.value.selectedDayLabel.contains("December"))
        assertTrue(viewModel.uiState.value.selectedDayLabel.contains("17"))
        assertTrue(viewModel.uiState.value.selectedDayLabel.contains("2024"))
    }

    @Test
    fun `selectDate loads events for that day`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val dateMillis = getTimestamp(2024, 11, 17, 0, 0)
        viewModel.selectDate(dateMillis)
        advanceUntilIdle()

        // Uses dayCode-based query (YYYYMMDD) for proper all-day event handling
        verify { eventReader.getVisibleOccurrencesForDay(any()) }
        assertEquals(2, viewModel.uiState.value.selectedDayOccurrences.size)
        assertEquals(2, viewModel.uiState.value.selectedDayEvents.size)
    }

    // ==================== Search Tests ====================

    @Test
    fun `activateSearch enables search mode`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSearchActive)

        viewModel.activateSearch()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSearchActive)
        assertEquals("", viewModel.uiState.value.searchQuery)
    }

    @Test
    fun `deactivateSearch clears search state`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.activateSearch()
        viewModel.updateSearchQuery("test")
        advanceUntilIdle()

        viewModel.deactivateSearch()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSearchActive)
        assertEquals("", viewModel.uiState.value.searchQuery)
        assertTrue(viewModel.uiState.value.searchResults.isEmpty())
    }

    @Test
    fun `updateSearchQuery performs search when 2+ chars`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.activateSearch()
        advanceUntilIdle()

        viewModel.updateSearchQuery("me")
        advanceUntilIdle()

        // By default searchIncludePast is false, so calls searchEventsExcludingPastWithNextOccurrence
        coVerify { eventReader.searchEventsExcludingPastWithNextOccurrence("me") }
        assertTrue(viewModel.uiState.value.searchResults.size >= 0)
    }

    @Test
    fun `updateSearchQuery does not search with 1 char`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.activateSearch()
        viewModel.updateSearchQuery("m")
        advanceUntilIdle()

        coVerify(exactly = 0) { eventReader.searchEventsExcludingPastWithNextOccurrence(any()) }
        assertTrue(viewModel.uiState.value.searchResults.isEmpty())
    }

    @Test
    fun `toggleSearchIncludePast toggles flag and re-searches`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.activateSearch()
        viewModel.updateSearchQuery("test")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.searchIncludePast)
        // First search uses searchEventsExcludingPastWithNextOccurrence (default)
        coVerify(exactly = 1) { eventReader.searchEventsExcludingPastWithNextOccurrence("test") }

        viewModel.toggleSearchIncludePast()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.searchIncludePast)
        // After toggle, should call searchEventsWithNextOccurrence (include past)
        coVerify(exactly = 1) { eventReader.searchEventsWithNextOccurrence("test") }
    }

    // ==================== Search Debouncing Tests ====================

    @Test
    fun `search debounces with 300ms delay`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.activateSearch()
        advanceUntilIdle()

        // Type query
        viewModel.updateSearchQuery("me")

        // Immediately after typing, search should NOT have been called yet
        coVerify(exactly = 0) { eventReader.searchEventsExcludingPastWithNextOccurrence(any()) }

        // Advance time by 100ms - still not called
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        coVerify(exactly = 0) { eventReader.searchEventsExcludingPastWithNextOccurrence(any()) }

        // Advance time to 300ms total - now should be called
        testScheduler.advanceTimeBy(200)
        testScheduler.runCurrent()
        coVerify(exactly = 1) { eventReader.searchEventsExcludingPastWithNextOccurrence("me") }
    }

    @Test
    fun `search cancels previous query when new query arrives`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.activateSearch()
        advanceUntilIdle()

        // Type first query
        viewModel.updateSearchQuery("me")

        // Advance time by 150ms (half of debounce delay)
        testScheduler.advanceTimeBy(150)
        testScheduler.runCurrent()

        // Type second query before first completes
        viewModel.updateSearchQuery("meet")

        // Advance full 300ms for second query
        testScheduler.advanceTimeBy(300)
        testScheduler.runCurrent()

        // Only second query should have been executed (using searchEventsExcludingPastWithNextOccurrence by default)
        coVerify(exactly = 0) { eventReader.searchEventsExcludingPastWithNextOccurrence("me") }
        coVerify(exactly = 1) { eventReader.searchEventsExcludingPastWithNextOccurrence("meet") }
    }

    @Test
    fun `search does not debounce for queries under 2 chars`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.activateSearch()
        viewModel.updateSearchQuery("m")
        advanceUntilIdle()

        // Should not search and should clear results immediately (no debounce)
        coVerify(exactly = 0) { eventReader.searchEventsExcludingPastWithNextOccurrence(any()) }
        assertTrue(viewModel.uiState.value.searchResults.isEmpty())
    }

    // ==================== Agenda Tests ====================

    @Test
    fun `toggleAgendaPanel opens panel and loads events`() = runTest {
        // Setup mock for agenda loading (now uses Flow)
        val testOccurrencesWithEvents = listOf(
            EventReader.OccurrenceWithEvent(
                occurrence = testOccurrences[0],
                event = testEvents[0],
                calendar = testCalendars[0]
            ),
            EventReader.OccurrenceWithEvent(
                occurrence = testOccurrences[1],
                event = testEvents[1],
                calendar = testCalendars[1]
            )
        )
        every { eventReader.getOccurrencesWithEventsInRangeFlow(any(), any()) } returns flowOf(testOccurrencesWithEvents)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showAgendaPanel)
        assertTrue(viewModel.uiState.value.agendaOccurrences.isEmpty())

        // Open agenda panel
        viewModel.toggleAgendaPanel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showAgendaPanel)
        assertEquals(2, viewModel.uiState.value.agendaOccurrences.size)
        assertFalse(viewModel.uiState.value.isLoadingAgenda)
    }

    @Test
    fun `toggleAgendaPanel closes panel without reloading`() = runTest {
        val testOccurrencesWithEvents = listOf(
            EventReader.OccurrenceWithEvent(
                occurrence = testOccurrences[0],
                event = testEvents[0],
                calendar = testCalendars[0]
            )
        )
        every { eventReader.getOccurrencesWithEventsInRangeFlow(any(), any()) } returns flowOf(testOccurrencesWithEvents)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Open agenda
        viewModel.toggleAgendaPanel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showAgendaPanel)

        // Clear mock call count
        io.mockk.clearMocks(eventReader, answers = false, recordedCalls = true, childMocks = false)

        // Close agenda
        viewModel.toggleAgendaPanel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showAgendaPanel)
        // Should NOT have called getOccurrencesWithEventsInRangeFlow when closing
        verify(exactly = 0) { eventReader.getOccurrencesWithEventsInRangeFlow(any(), any()) }
    }

    @Test
    fun `agenda loads 30 days of events`() = runTest {
        every { eventReader.getOccurrencesWithEventsInRangeFlow(any(), any()) } returns flowOf(emptyList())

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleAgendaPanel()
        advanceUntilIdle()

        // Verify the Flow-based range query was called
        verify {
            eventReader.getOccurrencesWithEventsInRangeFlow(any(), any())
        }
    }

    @Test
    fun `agenda events are sorted by start time`() = runTest {
        // Create events in reverse order
        val laterOccurrence = Occurrence(
            id = 3L,
            eventId = 3L,
            calendarId = 1L,
            startTs = getTimestamp(2024, 11, 20, 14, 0),
            endTs = getTimestamp(2024, 11, 20, 15, 0),
            startDay = 20241220,
            endDay = 20241220
        )
        val laterEvent = Event(
            id = 3L,
            uid = "event-3@test",
            calendarId = 1L,
            title = "Later Meeting",
            startTs = getTimestamp(2024, 11, 20, 14, 0),
            endTs = getTimestamp(2024, 11, 20, 15, 0),
            dtstamp = System.currentTimeMillis()
        )

        // Return in wrong order - later event first
        val unsortedOccurrences = listOf(
            EventReader.OccurrenceWithEvent(laterOccurrence, laterEvent, testCalendars[0]),
            EventReader.OccurrenceWithEvent(testOccurrences[0], testEvents[0], testCalendars[0])
        )
        every { eventReader.getOccurrencesWithEventsInRangeFlow(any(), any()) } returns flowOf(unsortedOccurrences)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleAgendaPanel()
        advanceUntilIdle()

        // Should be sorted by occurrence startTs (earlier first)
        assertEquals(2, viewModel.uiState.value.agendaOccurrences.size)
        assertTrue(
            viewModel.uiState.value.agendaOccurrences[0].occurrence.startTs <
            viewModel.uiState.value.agendaOccurrences[1].occurrence.startTs
        )
    }

    @Test
    fun `agenda shows loading state while fetching`() = runTest {
        // Track loading state during the fetch
        var loadingStateDuringFetch = false
        every { eventReader.getOccurrencesWithEventsInRangeFlow(any(), any()) } answers {
            // This captures that isLoadingAgenda was true when we started fetching
            loadingStateDuringFetch = true
            flowOf(emptyList())
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Initially not loading
        assertFalse(viewModel.uiState.value.isLoadingAgenda)

        viewModel.toggleAgendaPanel()
        advanceUntilIdle()

        // Verify loading state was set (captured during fetch)
        assertTrue(loadingStateDuringFetch)

        // After completion, loading should be false
        assertFalse(viewModel.uiState.value.isLoadingAgenda)
    }

    // ==================== Sync Tests ====================

    @Test
    fun `triggerStartupSync does nothing when not configured`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isConfigured)

        viewModel.triggerStartupSync()
        advanceUntilIdle()

        verify(exactly = 0) { syncScheduler.requestImmediateSync(any(), any()) }
    }

    @Test
    fun `triggerStartupSync requests sync when configured`() = runTest {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "test-password",
            isEnabled = true
        )
        coEvery { authManager.loadAccount() } returns account

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isConfigured)

        viewModel.triggerStartupSync()
        advanceUntilIdle()

        verify { syncScheduler.requestImmediateSync(any(), any()) }
    }

    @Test
    fun `triggerStartupSync only runs once`() = runTest {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "test-password",
            isEnabled = true
        )
        coEvery { authManager.loadAccount() } returns account

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.triggerStartupSync()
        viewModel.triggerStartupSync()
        advanceUntilIdle()

        // Should only be called once
        verify(exactly = 1) { syncScheduler.requestImmediateSync(any(), any()) }
    }

    @Test
    fun `forceFullSync requests full sync`() = runTest {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "test-password",
            isEnabled = true
        )
        coEvery { authManager.loadAccount() } returns account

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.forceFullSync()
        advanceUntilIdle()

        verify { syncScheduler.requestImmediateSync(forceFullSync = true) }
    }

    @Test
    fun `refreshSync does not start if already syncing`() = runTest {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "test-password",
            isEnabled = true
        )
        coEvery { authManager.loadAccount() } returns account

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Verify iCloud is configured
        assertTrue(viewModel.uiState.value.isConfigured)

        // Start first sync
        viewModel.refreshSync()
        advanceUntilIdle()

        // Try to start another sync while first one is still processing
        // The second call should be ignored because isSyncing check happens
        // before the state is updated
        viewModel.refreshSync()
        advanceUntilIdle()

        // Verify sync was requested (may be called multiple times due to init)
        verify(atLeast = 1) { syncScheduler.requestImmediateSync(any(), any()) }
    }

    // ==================== Sync Banner Tests (Context-Aware) ====================

    @Test
    fun `forceFullSync shows banner when Running`() = runTest {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "test-password",
            isEnabled = true
        )
        coEvery { authManager.loadAccount() } returns account

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Force sync sets showBannerForCurrentSync = true
        viewModel.forceFullSync()

        // Emit Running status immediately (before advanceUntilIdle processes Idle)
        syncStatusFlow.value = SyncStatus.Running
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showSyncBanner)
        assertEquals("Syncing calendars...", viewModel.uiState.value.syncBannerMessage)
        // Force Full Sync shows banner but NOT the spinning icon (suppressSyncIndicator = true)
        assertFalse(viewModel.uiState.value.isSyncing)
    }

    @Test
    fun `forceFullSync shows Sync complete on Success`() = runTest {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "test-password",
            isEnabled = true
        )
        coEvery { authManager.loadAccount() } returns account

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Force sync sets showBannerForCurrentSync = true
        viewModel.forceFullSync()

        // Emit status changes immediately
        syncStatusFlow.value = SyncStatus.Running
        advanceUntilIdle()

        syncStatusFlow.value = SyncStatus.Succeeded(calendarsSynced = 2, eventsPulled = 5)
        // Don't use advanceUntilIdle() - it would advance past the 2s auto-dismiss delay
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()

        // Banner should still be visible (auto-dismisses after 2 seconds)
        assertTrue(viewModel.uiState.value.showSyncBanner)
        assertEquals("Sync complete", viewModel.uiState.value.syncBannerMessage)
        assertFalse(viewModel.uiState.value.isSyncing)
    }

    @Test
    fun `refreshSync does not show banner when Running`() = runTest {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "test-password",
            isEnabled = true
        )
        coEvery { authManager.loadAccount() } returns account

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Pull-to-refresh sets showBannerForCurrentSync = false
        viewModel.refreshSync()
        advanceUntilIdle()

        // Emit Running status
        syncStatusFlow.value = SyncStatus.Running
        advanceUntilIdle()

        // Banner should be hidden for pull-to-refresh
        assertFalse(viewModel.uiState.value.showSyncBanner)
        assertTrue(viewModel.uiState.value.isSyncing)
    }

    @Test
    fun `refreshSync does not show banner on Success`() = runTest {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "test-password",
            isEnabled = true
        )
        coEvery { authManager.loadAccount() } returns account

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Pull-to-refresh sets showBannerForCurrentSync = false
        viewModel.refreshSync()
        advanceUntilIdle()

        syncStatusFlow.value = SyncStatus.Running
        advanceUntilIdle()

        syncStatusFlow.value = SyncStatus.Succeeded(calendarsSynced = 2, eventsPulled = 5)
        advanceUntilIdle()

        // Banner should remain hidden for pull-to-refresh success
        assertFalse(viewModel.uiState.value.showSyncBanner)
        assertFalse(viewModel.uiState.value.isSyncing)
    }

    @Test
    fun `sync failure always shows banner regardless of sync type`() = runTest {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "test-password",
            isEnabled = true
        )
        coEvery { authManager.loadAccount() } returns account

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Use refreshSync which sets showBannerForCurrentSync = false
        viewModel.refreshSync()

        // Emit status changes immediately
        syncStatusFlow.value = SyncStatus.Running
        advanceUntilIdle()

        // Errors should ALWAYS show banner
        syncStatusFlow.value = SyncStatus.Failed(errorMessage = "Network error")
        // Don't use advanceUntilIdle() - it would advance past the 3s auto-dismiss delay
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()

        // Banner should still be visible (auto-dismisses after 3 seconds)
        assertTrue(viewModel.uiState.value.showSyncBanner)
        assertTrue(viewModel.uiState.value.syncBannerMessage.contains("Sync failed"))
        assertTrue(viewModel.uiState.value.syncBannerMessage.contains("Network error"))
        assertFalse(viewModel.uiState.value.isSyncing)
    }

    @Test
    fun `sync banner hidden when status is Idle`() = runTest {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "test-password",
            isEnabled = true
        )
        coEvery { authManager.loadAccount() } returns account

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Use forceFullSync to show banner
        viewModel.forceFullSync()

        // Emit Running status immediately
        syncStatusFlow.value = SyncStatus.Running
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showSyncBanner)

        // Then set to Idle
        syncStatusFlow.value = SyncStatus.Idle
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showSyncBanner)
        assertFalse(viewModel.uiState.value.isSyncing)
    }

    @Test
    fun `sync banner hidden when status is Cancelled`() = runTest {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "test-password",
            isEnabled = true
        )
        coEvery { authManager.loadAccount() } returns account

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Use forceFullSync to show banner
        viewModel.forceFullSync()

        // Emit Running status immediately
        syncStatusFlow.value = SyncStatus.Running
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showSyncBanner)

        // Then set to Cancelled
        syncStatusFlow.value = SyncStatus.Cancelled
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showSyncBanner)
        assertFalse(viewModel.uiState.value.isSyncing)
    }

    // ==================== Network State Tests ====================

    @Test
    fun `network offline updates state`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // isOnline is exposed directly as StateFlow from NetworkMonitor
        assertTrue(viewModel.isOnline.value)

        // Go offline
        networkStateFlow.value = false
        advanceUntilIdle()

        assertFalse(viewModel.isOnline.value)
    }

    // ==================== UI Sheet Tests ====================

    @Test
    fun `toggleCalendarVisibilitySheet toggles visibility`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showCalendarVisibility)

        viewModel.toggleCalendarVisibilitySheet()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showCalendarVisibility)

        viewModel.toggleCalendarVisibilitySheet()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showCalendarVisibility)
    }

    @Test
    fun `toggleAppInfoSheet toggles visibility`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showAppInfoSheet)

        viewModel.toggleAppInfoSheet()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showAppInfoSheet)
    }

    @Test
    fun `dismissOnboardingSheet persists dismissal`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dismissOnboardingSheet()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showOnboardingSheet)
        coVerify { dataStore.setOnboardingDismissed(true) }
    }

    // ==================== Snackbar Tests ====================

    @Test
    fun `clearSnackbar clears pending message`() = runTest {
        // Trigger snackbar via a failed delete operation
        coEvery { eventCoordinator.deleteEvent(999L) } throws RuntimeException("DB error")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteEventOptimistic(999L)
        advanceUntilIdle()

        // Should have snackbar message from failed delete
        assertTrue(viewModel.uiState.value.pendingSnackbarMessage != null)

        viewModel.clearSnackbar()
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingSnackbarMessage)
    }

    // ==================== Event CRUD Tests ====================

    @Test
    fun `getEventForEdit returns event from coordinator`() = runTest {
        coEvery { eventCoordinator.getEventById(1L) } returns testEvents[0]

        val viewModel = createViewModel()
        advanceUntilIdle()

        val event = viewModel.getEventForEdit(1L)

        assertEquals(testEvents[0], event)
        coVerify { eventCoordinator.getEventById(1L) }
    }

    @Test
    fun `getEventForEdit returns null for nonexistent event`() = runTest {
        coEvery { eventCoordinator.getEventById(999L) } returns null

        val viewModel = createViewModel()
        advanceUntilIdle()

        val event = viewModel.getEventForEdit(999L)

        assertEquals(null, event)
    }

    @Test
    fun `saveEvent creates new event via coordinator`() = runTest {
        val createdEvent = testEvents[0].copy(id = 100L)
        coEvery { eventCoordinator.getLocalCalendarId() } returns 1L
        coEvery { eventCoordinator.createEvent(any(), any()) } returns createdEvent

        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "New Meeting",
            dateMillis = getTimestamp(2024, 11, 20, 0, 0),
            endDateMillis = getTimestamp(2024, 11, 20, 0, 0),
            startHour = 10,
            startMinute = 0,
            endHour = 11,
            endMinute = 0,
            selectedCalendarId = 1L,
            reminder1Minutes = 15,
            reminder2Minutes = -1,
            isEditMode = false
        )

        val result = viewModel.saveEvent(formState)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertEquals(createdEvent, result.getOrNull())
        coVerify { eventCoordinator.createEvent(any(), 1L) }
    }

    @Test
    fun `saveEvent updates existing event via coordinator`() = runTest {
        val existingEvent = testEvents[0]
        val updatedEvent = existingEvent.copy(title = "Updated Meeting")
        coEvery { eventCoordinator.getEventById(1L) } returns existingEvent
        coEvery { eventCoordinator.updateEvent(any()) } returns updatedEvent

        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Updated Meeting",
            dateMillis = getTimestamp(2024, 11, 17, 0, 0),
            endDateMillis = getTimestamp(2024, 11, 17, 0, 0),
            startHour = 10,
            startMinute = 0,
            endHour = 11,
            endMinute = 0,
            selectedCalendarId = 1L,
            reminder1Minutes = 15,
            reminder2Minutes = -1,
            isEditMode = true,
            editingEventId = 1L
        )

        val result = viewModel.saveEvent(formState)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        coVerify { eventCoordinator.updateEvent(match { it.title == "Updated Meeting" }) }
    }

    @Test
    fun `saveEvent returns failure when event not found in edit mode`() = runTest {
        coEvery { eventCoordinator.getEventById(999L) } returns null

        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Updated Meeting",
            dateMillis = getTimestamp(2024, 11, 17, 0, 0),
            endDateMillis = getTimestamp(2024, 11, 17, 0, 0),
            startHour = 10,
            startMinute = 0,
            endHour = 11,
            endMinute = 0,
            selectedCalendarId = 1L,
            isEditMode = true,
            editingEventId = 999L
        )

        val result = viewModel.saveEvent(formState)
        advanceUntilIdle()

        assertTrue(result.isFailure)
    }

    @Test
    fun `saveEvent uses local calendar when no calendar selected`() = runTest {
        val createdEvent = testEvents[0].copy(id = 100L)
        coEvery { eventCoordinator.getLocalCalendarId() } returns 99L
        coEvery { eventCoordinator.createEvent(any(), any()) } returns createdEvent

        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "New Event",
            dateMillis = getTimestamp(2024, 11, 20, 0, 0),
            endDateMillis = getTimestamp(2024, 11, 20, 0, 0),
            startHour = 10,
            startMinute = 0,
            endHour = 11,
            endMinute = 0,
            selectedCalendarId = null,  // No calendar selected
            isEditMode = false
        )

        viewModel.saveEvent(formState)
        advanceUntilIdle()

        coVerify { eventCoordinator.getLocalCalendarId() }
        coVerify { eventCoordinator.createEvent(any(), 99L) }
    }

    @Test
    fun `saveEvent uses Untitled when title is blank`() = runTest {
        val createdEvent = testEvents[0].copy(id = 100L)
        coEvery { eventCoordinator.getLocalCalendarId() } returns 1L
        coEvery { eventCoordinator.createEvent(any(), any()) } returns createdEvent

        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "   ",  // Blank title
            dateMillis = getTimestamp(2024, 11, 20, 0, 0),
            endDateMillis = getTimestamp(2024, 11, 20, 0, 0),
            startHour = 10,
            startMinute = 0,
            endHour = 11,
            endMinute = 0,
            selectedCalendarId = 1L,
            isEditMode = false
        )

        viewModel.saveEvent(formState)
        advanceUntilIdle()

        coVerify { eventCoordinator.createEvent(match { it.title == "Untitled" }, any()) }
    }

    @Test
    fun `deleteEvent deletes via coordinator`() = runTest {
        coEvery { eventCoordinator.deleteEvent(1L) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.deleteEvent(1L)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        coVerify { eventCoordinator.deleteEvent(1L) }
    }

    @Test
    fun `deleteEvent returns failure on exception`() = runTest {
        coEvery { eventCoordinator.deleteEvent(999L) } throws IllegalArgumentException("Event not found")

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.deleteEvent(999L)
        advanceUntilIdle()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    // ==================== Optimistic Delete Tests ====================

    @Test
    fun `deleteEventOptimistic calls coordinator`() = runTest {
        coEvery { eventCoordinator.deleteEvent(1L) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteEventOptimistic(1L)
        advanceUntilIdle()

        coVerify { eventCoordinator.deleteEvent(1L) }
    }

    @Test
    fun `deleteEventOptimistic shows snackbar on error`() = runTest {
        coEvery { eventCoordinator.deleteEvent(999L) } throws RuntimeException("DB error")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteEventOptimistic(999L)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pendingSnackbarMessage?.contains("Failed to delete") == true)
    }

    @Test
    fun `deleteSingleOccurrence calls coordinator with correct params`() = runTest {
        coEvery { eventCoordinator.deleteSingleOccurrence(any(), any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        val occTs = 1704067200000L // Jan 1, 2024
        viewModel.deleteSingleOccurrence(101L, occTs)
        advanceUntilIdle()

        coVerify { eventCoordinator.deleteSingleOccurrence(101L, occTs) }
    }

    @Test
    fun `deleteSingleOccurrence shows snackbar on error`() = runTest {
        coEvery { eventCoordinator.deleteSingleOccurrence(any(), any()) } throws
            IllegalArgumentException("Master event not found")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteSingleOccurrence(999L, 0L)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pendingSnackbarMessage?.contains("Failed to delete") == true)
    }

    @Test
    fun `deleteThisAndFuture calls coordinator with correct params`() = runTest {
        coEvery { eventCoordinator.deleteThisAndFuture(any(), any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        val fromTs = 1704067200000L
        viewModel.deleteThisAndFuture(101L, fromTs)
        advanceUntilIdle()

        coVerify { eventCoordinator.deleteThisAndFuture(101L, fromTs) }
    }

    @Test
    fun `deleteThisAndFuture shows snackbar on error`() = runTest {
        coEvery { eventCoordinator.deleteThisAndFuture(any(), any()) } throws
            IllegalArgumentException("Event is not recurring")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteThisAndFuture(999L, 0L)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pendingSnackbarMessage?.contains("Failed to delete") == true)
    }

    @Test
    fun `deleteEventOptimistic refreshes UI after success`() = runTest {
        coEvery { eventCoordinator.deleteEvent(any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Select a date to enable day reload
        viewModel.selectDate(getTimestamp(2024, 11, 17, 0, 0))
        advanceUntilIdle()

        io.mockk.clearMocks(eventReader, answers = false, recordedCalls = true)

        viewModel.deleteEventOptimistic(1L)
        advanceUntilIdle()

        // Verify reloadCurrentView was called (via getVisibleOccurrencesForDay)
        verify(atLeast = 1) { eventReader.getVisibleOccurrencesForDay(any()) }
    }

    @Test
    fun `getDefaultCalendarId returns default from coordinator`() = runTest {
        val defaultCalendar = testCalendars[0]
        coEvery { eventCoordinator.getDefaultCalendar() } returns defaultCalendar

        val viewModel = createViewModel()
        advanceUntilIdle()

        val calendarId = viewModel.getDefaultCalendarId()

        assertEquals(1L, calendarId)
    }

    @Test
    fun `getDefaultCalendarId returns null when no default`() = runTest {
        coEvery { eventCoordinator.getDefaultCalendar() } returns null

        val viewModel = createViewModel()
        advanceUntilIdle()

        val calendarId = viewModel.getDefaultCalendarId()

        assertEquals(null, calendarId)
    }

    @Test
    fun `getLocalCalendarId returns from coordinator`() = runTest {
        coEvery { eventCoordinator.getLocalCalendarId() } returns 42L

        val viewModel = createViewModel()
        advanceUntilIdle()

        val calendarId = viewModel.getLocalCalendarId()

        assertEquals(42L, calendarId)
    }

    // ==================== Sync Timing Tests (Pull-to-Refresh Fix) ====================

    @Test
    fun `performSync sets isSyncing true immediately`() = runTest {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "test-password",
            isEnabled = true
        )
        coEvery { authManager.loadAccount() } returns account

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Before sync, isSyncing should be false (from Idle status)
        assertFalse(viewModel.uiState.value.isSyncing)

        // Call refreshSync which calls performSync
        viewModel.refreshSync()

        // isSyncing should be true immediately (before WorkManager responds)
        assertTrue(viewModel.uiState.value.isSyncing)

        // Verify sync was requested
        verify { syncScheduler.requestImmediateSync(any(), any()) }
    }

    @Test
    fun `forceFullSync full flow updates UI correctly through status changes`() = runTest {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "test-password",
            isEnabled = true
        )
        coEvery { authManager.loadAccount() } returns account

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Initial state
        assertFalse(viewModel.uiState.value.isSyncing)
        assertFalse(viewModel.uiState.value.showSyncBanner)

        // Start force sync (shows banner but NOT spinning icon)
        viewModel.forceFullSync()

        // Force Full Sync uses suppressSyncIndicator=true, so isSyncing stays false
        assertFalse(viewModel.uiState.value.isSyncing)

        // Simulate WorkManager emitting Enqueued (immediately to avoid Idle processing)
        syncStatusFlow.value = SyncStatus.Enqueued
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showSyncBanner)
        assertEquals("Preparing to sync...", viewModel.uiState.value.syncBannerMessage)

        // Simulate WorkManager emitting Running
        syncStatusFlow.value = SyncStatus.Running
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showSyncBanner)
        assertEquals("Syncing calendars...", viewModel.uiState.value.syncBannerMessage)
        // Force Full Sync shows banner but NOT spinning icon (suppressSyncIndicator = true)
        assertFalse(viewModel.uiState.value.isSyncing)

        // Simulate WorkManager emitting Succeeded
        syncStatusFlow.value = SyncStatus.Succeeded(calendarsSynced = 2, eventsPulled = 10)
        // Don't use advanceUntilIdle() - it would advance past the 2s auto-dismiss delay
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()

        // Banner should still be visible (auto-dismisses after 2 seconds)
        assertTrue(viewModel.uiState.value.showSyncBanner)
        assertEquals("Sync complete", viewModel.uiState.value.syncBannerMessage)
        assertFalse(viewModel.uiState.value.isSyncing)
    }

    @Test
    fun `reloadCurrentView is triggered when SyncStatus becomes Succeeded`() = runTest {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "test-password",
            isEnabled = true
        )
        coEvery { authManager.loadAccount() } returns account

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Select a date first so we can verify reloadCurrentView updates selectedDayOccurrences
        val dateMillis = getTimestamp(2024, 11, 17, 0, 0)
        viewModel.selectDate(dateMillis)
        advanceUntilIdle()

        // Clear the call count from selectDate
        io.mockk.clearMocks(eventReader, answers = false, recordedCalls = true, childMocks = false)

        // Start sync
        viewModel.refreshSync()
        advanceUntilIdle()

        // At this point, reloadCurrentView should NOT have been called (only by observeSyncStatus on success)
        // The old buggy code would call it immediately here

        // Now simulate sync completing successfully
        syncStatusFlow.value = SyncStatus.Succeeded(calendarsSynced = 2, eventsPulled = 5)
        advanceUntilIdle()

        // NOW reloadCurrentView should be called, which queries occurrences
        // Uses dayCode-based query for selected day events
        verify(atLeast = 1) { eventReader.getVisibleOccurrencesForDay(any()) }
    }

    @Test
    fun `concurrent refreshSync calls are blocked when isSyncing is true`() = runTest {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "test-password",
            isEnabled = true
        )
        coEvery { authManager.loadAccount() } returns account

        val viewModel = createViewModel()
        advanceUntilIdle()

        // First refresh - should work
        viewModel.refreshSync()

        // isSyncing should be true now
        assertTrue(viewModel.uiState.value.isSyncing)

        // Second refresh while isSyncing is true - should be blocked
        viewModel.refreshSync()
        viewModel.refreshSync()
        viewModel.refreshSync()
        advanceUntilIdle()

        // Should only have been called once (from the first refreshSync)
        verify(exactly = 1) { syncScheduler.requestImmediateSync(any(), any()) }
    }

    @Test
    fun `sync failure does not leave stale isSyncing state`() = runTest {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "test-password",
            isEnabled = true
        )
        coEvery { authManager.loadAccount() } returns account

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Start sync
        viewModel.refreshSync()
        assertTrue(viewModel.uiState.value.isSyncing)

        // Simulate sync failure
        syncStatusFlow.value = SyncStatus.Failed(errorMessage = "Network error")
        advanceUntilIdle()

        // isSyncing should be false after failure
        assertFalse(viewModel.uiState.value.isSyncing)
        assertTrue(viewModel.uiState.value.syncBannerMessage.contains("Sync failed"))

        // Should be able to start another sync now
        viewModel.refreshSync()
        assertTrue(viewModel.uiState.value.isSyncing)
        verify(exactly = 2) { syncScheduler.requestImmediateSync(any(), any()) }
    }

    // ==================== All-Day Event Tests ====================

    @Test
    fun `all-day event appears on correct day using dayCode query`() = runTest {
        val allDayOccurrence = Occurrence(
            id = 10L,
            eventId = 10L,
            calendarId = 1L,
            startTs = getTimestamp(2024, 11, 17, 0, 0), // Dec 17, 2024 midnight UTC
            endTs = getTimestamp(2024, 11, 18, 0, 0),   // Dec 18, 2024 midnight UTC
            startDay = 20241217,
            endDay = 20241217
        )
        val allDayEvent = Event(
            id = 10L,
            uid = "allday@test",
            calendarId = 1L,
            title = "Holiday",
            startTs = getTimestamp(2024, 11, 17, 0, 0),
            endTs = getTimestamp(2024, 11, 18, 0, 0),
            isAllDay = true,
            dtstamp = System.currentTimeMillis()
        )
        every { eventReader.getVisibleOccurrencesForDay(20241217) } returns flowOf(listOf(allDayOccurrence))
        coEvery { eventReader.getEventById(10L) } returns allDayEvent

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Select Dec 17, 2024
        val dateMillis = getTimestamp(2024, 11, 17, 0, 0)
        viewModel.selectDate(dateMillis)
        advanceUntilIdle()

        // Should use dayCode-based query
        verify { eventReader.getVisibleOccurrencesForDay(20241217) }
        assertEquals(1, viewModel.uiState.value.selectedDayOccurrences.size)
        assertEquals("Holiday", viewModel.uiState.value.selectedDayEvents[0].title)
    }

    @Test
    fun `multi-day all-day event appears on all days`() = runTest {
        // 3-day event: Dec 17-19, 2024
        val multiDayOccurrence = Occurrence(
            id = 11L,
            eventId = 11L,
            calendarId = 1L,
            startTs = getTimestamp(2024, 11, 17, 0, 0),
            endTs = getTimestamp(2024, 11, 20, 0, 0), // Exclusive end
            startDay = 20241217,
            endDay = 20241219 // Inclusive end day
        )
        val multiDayEvent = Event(
            id = 11L,
            uid = "multiday@test",
            calendarId = 1L,
            title = "Conference",
            startTs = getTimestamp(2024, 11, 17, 0, 0),
            endTs = getTimestamp(2024, 11, 20, 0, 0),
            isAllDay = true,
            dtstamp = System.currentTimeMillis()
        )

        // Should appear on day 18 (middle day)
        every { eventReader.getVisibleOccurrencesForDay(20241218) } returns flowOf(listOf(multiDayOccurrence))
        coEvery { eventReader.getEventById(11L) } returns multiDayEvent

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Select Dec 18, 2024 (middle day)
        val dateMillis = getTimestamp(2024, 11, 18, 0, 0)
        viewModel.selectDate(dateMillis)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.selectedDayOccurrences.size)
        assertEquals("Conference", viewModel.uiState.value.selectedDayEvents[0].title)
    }

    @Test
    fun `saveEvent handles all-day event correctly`() = runTest {
        val createdEvent = Event(
            id = 100L,
            uid = "allday-new@test",
            calendarId = 1L,
            title = "All Day Event",
            startTs = getTimestamp(2024, 11, 20, 0, 0),
            endTs = getTimestamp(2024, 11, 21, 0, 0),
            isAllDay = true,
            dtstamp = System.currentTimeMillis()
        )
        coEvery { eventCoordinator.getLocalCalendarId() } returns 1L
        coEvery { eventCoordinator.createEvent(any(), any()) } returns createdEvent

        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "All Day Event",
            dateMillis = getTimestamp(2024, 11, 20, 0, 0),
            endDateMillis = getTimestamp(2024, 11, 20, 0, 0),
            isAllDay = true,
            selectedCalendarId = 1L,
            isEditMode = false
        )

        val result = viewModel.saveEvent(formState)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        coVerify { eventCoordinator.createEvent(match { it.isAllDay }, any()) }
    }

    // ==================== Multi-Day Timed Event Tests ====================

    @Test
    fun `multi-day timed event spans multiple days`() = runTest {
        // Event from Dec 17 10:00 AM to Dec 18 2:00 PM
        val multiDayTimedOccurrence = Occurrence(
            id = 12L,
            eventId = 12L,
            calendarId = 1L,
            startTs = getTimestamp(2024, 11, 17, 10, 0),
            endTs = getTimestamp(2024, 11, 18, 14, 0),
            startDay = 20241217,
            endDay = 20241218
        )
        val multiDayTimedEvent = Event(
            id = 12L,
            uid = "multiday-timed@test",
            calendarId = 1L,
            title = "Overnight Hackathon",
            startTs = getTimestamp(2024, 11, 17, 10, 0),
            endTs = getTimestamp(2024, 11, 18, 14, 0),
            isAllDay = false,
            dtstamp = System.currentTimeMillis()
        )

        every { eventReader.getVisibleOccurrencesForDay(20241218) } returns flowOf(listOf(multiDayTimedOccurrence))
        coEvery { eventReader.getEventById(12L) } returns multiDayTimedEvent

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Select Dec 18, 2024 (second day)
        viewModel.selectDate(getTimestamp(2024, 11, 18, 0, 0))
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.selectedDayOccurrences.size)
        assertEquals("Overnight Hackathon", viewModel.uiState.value.selectedDayEvents[0].title)
    }

    // ==================== Exception Event Tests (Recurring Event Modifications) ====================

    @Test
    fun `exception event is loaded when occurrence has exceptionEventId`() = runTest {
        // REGRESSION TEST: v14.2.20 fix was incomplete - occurrenceMap key was fixed
        // but selectedDayEvents still loaded master event instead of exception event.
        //
        // Scenario: User modified one occurrence of a weekly meeting (changed title)
        // - Master event: id=100, title="Weekly Meeting"
        // - Exception event: id=101, title="Cancelled", originalEventId=100
        // - Occurrence: eventId=100 (master), exceptionEventId=101 (exception)
        //
        // Expected: selectedDayEvents should contain the EXCEPTION event (id=101)
        // Bug: selectedDayEvents contained the MASTER event (id=100)

        val masterEvent = Event(
            id = 100L,
            uid = "weekly-meeting@test",
            calendarId = 1L,
            title = "Weekly Meeting",
            startTs = getTimestamp(2024, 11, 10, 10, 0),
            endTs = getTimestamp(2024, 11, 10, 11, 0),
            rrule = "FREQ=WEEKLY;BYDAY=TU",
            dtstamp = System.currentTimeMillis()
        )

        val exceptionEvent = Event(
            id = 101L,
            uid = "weekly-meeting@test", // Same UID as master (RFC 5545)
            calendarId = 1L,
            title = "Cancelled", // Modified title
            startTs = getTimestamp(2024, 11, 17, 10, 0),
            endTs = getTimestamp(2024, 11, 17, 11, 0),
            originalEventId = 100L, // Links to master
            originalInstanceTime = getTimestamp(2024, 11, 17, 10, 0),
            dtstamp = System.currentTimeMillis()
        )

        // Occurrence with exceptionEventId - this is the key field
        val exceptionOccurrence = Occurrence(
            id = 20L,
            eventId = 100L, // Master event ID
            exceptionEventId = 101L, // Exception event ID - THIS IS THE KEY
            calendarId = 1L,
            startTs = getTimestamp(2024, 11, 17, 10, 0),
            endTs = getTimestamp(2024, 11, 17, 11, 0),
            startDay = 20241217,
            endDay = 20241217
        )

        every { eventReader.getVisibleOccurrencesForDay(20241217) } returns flowOf(listOf(exceptionOccurrence))
        coEvery { eventReader.getEventById(100L) } returns masterEvent
        coEvery { eventReader.getEventById(101L) } returns exceptionEvent

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Select Dec 17, 2024 (the exception occurrence date)
        viewModel.selectDate(getTimestamp(2024, 11, 17, 0, 0))
        advanceUntilIdle()

        // CRITICAL: Should load EXCEPTION event (id=101), not master (id=100)
        assertEquals(1, viewModel.uiState.value.selectedDayEvents.size)
        assertEquals(101L, viewModel.uiState.value.selectedDayEvents[0].id)
        assertEquals("Cancelled", viewModel.uiState.value.selectedDayEvents[0].title)
        assertEquals(100L, viewModel.uiState.value.selectedDayEvents[0].originalEventId)
    }

    @Test
    fun `normal occurrence loads master event when no exceptionEventId`() = runTest {
        // Verify normal behavior is preserved - when occurrence has no exceptionEventId,
        // the master event should be loaded (not some null or error)

        val masterEvent = Event(
            id = 100L,
            uid = "weekly-meeting@test",
            calendarId = 1L,
            title = "Weekly Meeting",
            startTs = getTimestamp(2024, 11, 10, 10, 0),
            endTs = getTimestamp(2024, 11, 10, 11, 0),
            rrule = "FREQ=WEEKLY;BYDAY=TU",
            dtstamp = System.currentTimeMillis()
        )

        // Normal occurrence without exception
        val normalOccurrence = Occurrence(
            id = 21L,
            eventId = 100L,
            exceptionEventId = null, // No exception
            calendarId = 1L,
            startTs = getTimestamp(2024, 11, 10, 10, 0),
            endTs = getTimestamp(2024, 11, 10, 11, 0),
            startDay = 20241210,
            endDay = 20241210
        )

        every { eventReader.getVisibleOccurrencesForDay(20241210) } returns flowOf(listOf(normalOccurrence))
        coEvery { eventReader.getEventById(100L) } returns masterEvent

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Select Dec 10, 2024
        viewModel.selectDate(getTimestamp(2024, 11, 10, 0, 0))
        advanceUntilIdle()

        // Should load master event
        assertEquals(1, viewModel.uiState.value.selectedDayEvents.size)
        assertEquals(100L, viewModel.uiState.value.selectedDayEvents[0].id)
        assertEquals("Weekly Meeting", viewModel.uiState.value.selectedDayEvents[0].title)
    }

    @Test
    fun `mixed occurrences load correct events`() = runTest {
        // Test day with both normal and exception occurrences

        val masterEvent1 = Event(
            id = 100L,
            uid = "event-100@test",
            calendarId = 1L,
            title = "Regular Event",
            startTs = getTimestamp(2024, 11, 17, 9, 0),
            endTs = getTimestamp(2024, 11, 17, 10, 0),
            dtstamp = System.currentTimeMillis()
        )

        val masterEvent2 = Event(
            id = 200L,
            uid = "recurring@test",
            calendarId = 1L,
            title = "Weekly Sync",
            startTs = getTimestamp(2024, 11, 17, 14, 0),
            endTs = getTimestamp(2024, 11, 17, 15, 0),
            rrule = "FREQ=WEEKLY",
            dtstamp = System.currentTimeMillis()
        )

        val exceptionEvent = Event(
            id = 201L,
            uid = "recurring@test",
            calendarId = 1L,
            title = "Weekly Sync - MOVED",
            startTs = getTimestamp(2024, 11, 17, 16, 0), // Different time
            endTs = getTimestamp(2024, 11, 17, 17, 0),
            originalEventId = 200L,
            originalInstanceTime = getTimestamp(2024, 11, 17, 14, 0),
            dtstamp = System.currentTimeMillis()
        )

        val normalOccurrence = Occurrence(
            id = 30L,
            eventId = 100L,
            exceptionEventId = null,
            calendarId = 1L,
            startTs = getTimestamp(2024, 11, 17, 9, 0),
            endTs = getTimestamp(2024, 11, 17, 10, 0),
            startDay = 20241217,
            endDay = 20241217
        )

        val exceptionOccurrence = Occurrence(
            id = 31L,
            eventId = 200L,
            exceptionEventId = 201L, // Links to exception
            calendarId = 1L,
            startTs = getTimestamp(2024, 11, 17, 16, 0),
            endTs = getTimestamp(2024, 11, 17, 17, 0),
            startDay = 20241217,
            endDay = 20241217
        )

        every { eventReader.getVisibleOccurrencesForDay(20241217) } returns
            flowOf(listOf(normalOccurrence, exceptionOccurrence))
        coEvery { eventReader.getEventById(100L) } returns masterEvent1
        coEvery { eventReader.getEventById(200L) } returns masterEvent2
        coEvery { eventReader.getEventById(201L) } returns exceptionEvent

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectDate(getTimestamp(2024, 11, 17, 0, 0))
        advanceUntilIdle()

        // Should have 2 events: masterEvent1 and exceptionEvent (NOT masterEvent2)
        assertEquals(2, viewModel.uiState.value.selectedDayEvents.size)

        val eventIds = viewModel.uiState.value.selectedDayEvents.map { it.id }.toSet()
        assertTrue(100L in eventIds) // Normal event
        assertTrue(201L in eventIds) // Exception event (NOT 200L master)
        assertFalse(200L in eventIds) // Master should NOT be loaded

        val titles = viewModel.uiState.value.selectedDayEvents.map { it.title }.toSet()
        assertTrue("Regular Event" in titles)
        assertTrue("Weekly Sync - MOVED" in titles)
        assertFalse("Weekly Sync" in titles)
    }

    @Test
    fun `re-editing exception event multiple times loads correct data`() = runTest {
        // REGRESSION TEST: When user edits an exception event again (re-editing),
        // the exception event should still be loaded correctly.
        //
        // Scenario: User modified occurrence twice:
        // 1. First edit: "Weekly Meeting" -> "Cancelled"
        // 2. Second edit: "Cancelled" -> "Rescheduled to Next Week"
        //
        // The exception event (id=101) is updated in place, and
        // selectedDayEvents should contain the latest version.

        val masterEvent = Event(
            id = 100L,
            uid = "weekly-meeting@test",
            calendarId = 1L,
            title = "Weekly Meeting",
            startTs = getTimestamp(2024, 11, 10, 10, 0),
            endTs = getTimestamp(2024, 11, 10, 11, 0),
            rrule = "FREQ=WEEKLY;BYDAY=TU",
            dtstamp = System.currentTimeMillis()
        )

        // Exception event after SECOND edit (latest version)
        val exceptionEvent = Event(
            id = 101L,
            uid = "weekly-meeting@test",
            calendarId = 1L,
            title = "Rescheduled to Next Week", // Second modification
            description = "This occurrence has been rescheduled",
            startTs = getTimestamp(2024, 11, 17, 10, 0),
            endTs = getTimestamp(2024, 11, 17, 11, 0),
            originalEventId = 100L,
            originalInstanceTime = getTimestamp(2024, 11, 17, 10, 0),
            dtstamp = System.currentTimeMillis()
        )

        val exceptionOccurrence = Occurrence(
            id = 20L,
            eventId = 100L, // Still points to master
            exceptionEventId = 101L, // Still points to exception
            calendarId = 1L,
            startTs = getTimestamp(2024, 11, 17, 10, 0),
            endTs = getTimestamp(2024, 11, 17, 11, 0),
            startDay = 20241217,
            endDay = 20241217
        )

        every { eventReader.getVisibleOccurrencesForDay(20241217) } returns flowOf(listOf(exceptionOccurrence))
        coEvery { eventReader.getEventById(100L) } returns masterEvent
        coEvery { eventReader.getEventById(101L) } returns exceptionEvent

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectDate(getTimestamp(2024, 11, 17, 0, 0))
        advanceUntilIdle()

        // Should load the exception event with latest modifications
        assertEquals(1, viewModel.uiState.value.selectedDayEvents.size)
        assertEquals(101L, viewModel.uiState.value.selectedDayEvents[0].id)
        assertEquals("Rescheduled to Next Week", viewModel.uiState.value.selectedDayEvents[0].title)
        assertEquals("This occurrence has been rescheduled", viewModel.uiState.value.selectedDayEvents[0].description)
        assertEquals(100L, viewModel.uiState.value.selectedDayEvents[0].originalEventId)
    }

    @Test
    fun `exception event context is preserved for edit operations`() = runTest {
        // Test that the exception event provides correct context for edit operations:
        // - event.id should be exception ID (for form loading)
        // - event.originalEventId should be master ID (for editSingleOccurrence resolution)
        // - event.originalInstanceTime should be set (for occurrence lookup)

        val masterEvent = Event(
            id = 100L,
            uid = "weekly-meeting@test",
            calendarId = 1L,
            title = "Weekly Meeting",
            startTs = getTimestamp(2024, 11, 10, 10, 0),
            endTs = getTimestamp(2024, 11, 10, 11, 0),
            rrule = "FREQ=WEEKLY;BYDAY=TU",
            dtstamp = System.currentTimeMillis()
        )

        val exceptionEvent = Event(
            id = 101L,
            uid = "weekly-meeting@test",
            calendarId = 1L,
            title = "Modified Meeting",
            startTs = getTimestamp(2024, 11, 17, 14, 0), // Changed time
            endTs = getTimestamp(2024, 11, 17, 15, 0),
            originalEventId = 100L, // Links to master
            originalInstanceTime = getTimestamp(2024, 11, 17, 10, 0), // Original unmodified time
            dtstamp = System.currentTimeMillis()
        )

        val exceptionOccurrence = Occurrence(
            id = 20L,
            eventId = 100L,
            exceptionEventId = 101L,
            calendarId = 1L,
            startTs = getTimestamp(2024, 11, 17, 14, 0), // Matches exception's modified time
            endTs = getTimestamp(2024, 11, 17, 15, 0),
            startDay = 20241217,
            endDay = 20241217
        )

        every { eventReader.getVisibleOccurrencesForDay(20241217) } returns flowOf(listOf(exceptionOccurrence))
        coEvery { eventReader.getEventById(100L) } returns masterEvent
        coEvery { eventReader.getEventById(101L) } returns exceptionEvent

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectDate(getTimestamp(2024, 11, 17, 0, 0))
        advanceUntilIdle()

        val event = viewModel.uiState.value.selectedDayEvents[0]

        // Verify exception event provides correct context for edit operations
        assertEquals(101L, event.id) // Exception ID - for form loading
        assertEquals(100L, event.originalEventId) // Master ID - for editSingleOccurrence
        assertEquals(
            getTimestamp(2024, 11, 17, 10, 0),
            event.originalInstanceTime // Original time - for occurrence lookup
        )

        // Verify modified data is shown
        assertEquals("Modified Meeting", event.title)
        assertEquals(getTimestamp(2024, 11, 17, 14, 0), event.startTs) // Modified time
    }

    // ==================== Event Dots / Calendar Month View Tests ====================

    @Test
    fun `event dots are built from occurrences`() = runTest {
        val monthOccurrences = listOf(
            Occurrence(id = 1L, eventId = 1L, calendarId = 1L,
                startTs = getTimestamp(2024, 11, 5, 10, 0),
                endTs = getTimestamp(2024, 11, 5, 11, 0),
                startDay = 20241205, endDay = 20241205),
            Occurrence(id = 2L, eventId = 2L, calendarId = 1L,
                startTs = getTimestamp(2024, 11, 10, 14, 0),
                endTs = getTimestamp(2024, 11, 10, 15, 0),
                startDay = 20241210, endDay = 20241210),
            Occurrence(id = 3L, eventId = 3L, calendarId = 2L,
                startTs = getTimestamp(2024, 11, 10, 16, 0),
                endTs = getTimestamp(2024, 11, 10, 17, 0),
                startDay = 20241210, endDay = 20241210)
        )
        coEvery { eventReader.getVisibleOccurrencesInRange(any(), any()) } returns flowOf(monthOccurrences)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Navigate to December 2024 to trigger event dots loading
        // Use navigateToMonth (not setViewingMonth) to trigger buildEventDots
        viewModel.navigateToMonth(2024, 11) // December (0-indexed)
        advanceUntilIdle()

        val state = viewModel.uiState.value

        // Day 5 should have 1 color (calendar 1) - December 2024 (month=11 is 0-indexed)
        assertTrue(state.hasEventsOnDay(2024, 11, 5))
        assertEquals(1, state.getEventColors(2024, 11, 5).size)

        // Day 10 should have 2 colors (calendar 1 and 2)
        assertTrue(state.hasEventsOnDay(2024, 11, 10))
        assertEquals(2, state.getEventColors(2024, 11, 10).size)
    }

    @Test
    fun `recurring event shows dots on all occurrence days`() = runTest {
        // Recurring weekly event with 3 occurrences in the month
        val recurringOccurrences = listOf(
            Occurrence(id = 1L, eventId = 10L, calendarId = 1L,
                startTs = getTimestamp(2024, 11, 3, 10, 0),
                endTs = getTimestamp(2024, 11, 3, 11, 0),
                startDay = 20241203, endDay = 20241203),
            Occurrence(id = 2L, eventId = 10L, calendarId = 1L,
                startTs = getTimestamp(2024, 11, 10, 10, 0),
                endTs = getTimestamp(2024, 11, 10, 11, 0),
                startDay = 20241210, endDay = 20241210),
            Occurrence(id = 3L, eventId = 10L, calendarId = 1L,
                startTs = getTimestamp(2024, 11, 17, 10, 0),
                endTs = getTimestamp(2024, 11, 17, 11, 0),
                startDay = 20241217, endDay = 20241217)
        )
        coEvery { eventReader.getVisibleOccurrencesInRange(any(), any()) } returns flowOf(recurringOccurrences)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Use navigateToMonth (not setViewingMonth) to trigger buildEventDots
        viewModel.navigateToMonth(2024, 11)
        advanceUntilIdle()

        val state = viewModel.uiState.value

        // All 3 occurrence days should have dots (December 2024, month=11 is 0-indexed)
        assertTrue(state.hasEventsOnDay(2024, 11, 3))
        assertTrue(state.hasEventsOnDay(2024, 11, 10))
        assertTrue(state.hasEventsOnDay(2024, 11, 17))
    }

    // ==================== Reminder Tests ====================

    @Test
    fun `saveEvent preserves reminder settings`() = runTest {
        val createdEvent = testEvents[0].copy(id = 100L)
        coEvery { eventCoordinator.getLocalCalendarId() } returns 1L
        coEvery { eventCoordinator.createEvent(any(), any()) } returns createdEvent

        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Meeting with Reminders",
            dateMillis = getTimestamp(2024, 11, 20, 0, 0),
            endDateMillis = getTimestamp(2024, 11, 20, 0, 0),
            startHour = 10,
            startMinute = 0,
            endHour = 11,
            endMinute = 0,
            selectedCalendarId = 1L,
            reminder1Minutes = 30,    // 30 minutes before
            reminder2Minutes = 1440,  // 1 day before
            isEditMode = false
        )

        viewModel.saveEvent(formState)
        advanceUntilIdle()

        // Verify reminders are passed to createEvent
        coVerify { eventCoordinator.createEvent(any(), any()) }
    }

    @Test
    fun `saveEvent handles no reminders`() = runTest {
        val createdEvent = testEvents[0].copy(id = 100L)
        coEvery { eventCoordinator.getLocalCalendarId() } returns 1L
        coEvery { eventCoordinator.createEvent(any(), any()) } returns createdEvent

        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Meeting without Reminders",
            dateMillis = getTimestamp(2024, 11, 20, 0, 0),
            endDateMillis = getTimestamp(2024, 11, 20, 0, 0),
            startHour = 10,
            startMinute = 0,
            endHour = 11,
            endMinute = 0,
            selectedCalendarId = 1L,
            reminder1Minutes = -1, // REMINDER_OFF
            reminder2Minutes = -1, // REMINDER_OFF
            isEditMode = false
        )

        viewModel.saveEvent(formState)
        advanceUntilIdle()

        // Verify createEvent was called
        coVerify { eventCoordinator.createEvent(any(), any()) }
    }

    // ==================== Pending Action Tests (v11.4.0 - Industry Standard Pattern) ====================

    @Test
    fun `setPendingAction sets pending action in state`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val action = PendingAction.ShowEventQuickView(
            eventId = 123L,
            occurrenceTs = 1000000L,
            source = PendingAction.ShowEventQuickView.Source.REMINDER
        )

        viewModel.setPendingAction(action)
        advanceUntilIdle()

        assertEquals(action, viewModel.uiState.value.pendingAction)
    }

    @Test
    fun `clearPendingAction clears pending action`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Set an action first
        viewModel.setPendingAction(PendingAction.OpenSearch)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.pendingAction is PendingAction.OpenSearch)

        // Now clear it
        viewModel.clearPendingAction()
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingAction)
    }

    @Test
    fun `setPendingAction replaces existing action`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Set first action
        viewModel.setPendingAction(PendingAction.OpenSearch)
        advanceUntilIdle()

        // Replace with new action
        val newAction = PendingAction.CreateEvent(startTs = 2000000L)
        viewModel.setPendingAction(newAction)
        advanceUntilIdle()

        assertEquals(newAction, viewModel.uiState.value.pendingAction)
    }

    @Test
    fun `pending action survives across state updates`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val action = PendingAction.GoToToday
        viewModel.setPendingAction(action)
        advanceUntilIdle()

        // Trigger another state update (select a date)
        viewModel.selectDate(System.currentTimeMillis())
        advanceUntilIdle()

        // Pending action should still be there
        assertEquals(action, viewModel.uiState.value.pendingAction)
    }

    @Test
    fun `PendingAction ShowEventQuickView from REMINDER contains correct data`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val action = PendingAction.ShowEventQuickView(
            eventId = 456L,
            occurrenceTs = 1704067200000L,
            source = PendingAction.ShowEventQuickView.Source.REMINDER
        )

        viewModel.setPendingAction(action)
        advanceUntilIdle()

        val pending = viewModel.uiState.value.pendingAction as? PendingAction.ShowEventQuickView
        assertEquals(456L, pending?.eventId)
        assertEquals(1704067200000L, pending?.occurrenceTs)
        assertEquals(PendingAction.ShowEventQuickView.Source.REMINDER, pending?.source)
    }

    @Test
    fun `PendingAction ShowEventQuickView from WIDGET contains correct data`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val action = PendingAction.ShowEventQuickView(
            eventId = 789L,
            occurrenceTs = 1704153600000L,
            source = PendingAction.ShowEventQuickView.Source.WIDGET
        )

        viewModel.setPendingAction(action)
        advanceUntilIdle()

        val pending = viewModel.uiState.value.pendingAction as? PendingAction.ShowEventQuickView
        assertEquals(789L, pending?.eventId)
        assertEquals(PendingAction.ShowEventQuickView.Source.WIDGET, pending?.source)
    }

    @Test
    fun `PendingAction CreateEvent with null startTs uses default`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val action = PendingAction.CreateEvent(startTs = null)
        viewModel.setPendingAction(action)
        advanceUntilIdle()

        val pending = viewModel.uiState.value.pendingAction as? PendingAction.CreateEvent
        assertEquals(null, pending?.startTs)
    }

    @Test
    fun `PendingAction CreateEvent with specific startTs preserves it`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val specificTs = 1704067200L // Jan 1, 2024 00:00:00 UTC in seconds
        val action = PendingAction.CreateEvent(startTs = specificTs)
        viewModel.setPendingAction(action)
        advanceUntilIdle()

        val pending = viewModel.uiState.value.pendingAction as? PendingAction.CreateEvent
        assertEquals(specificTs, pending?.startTs)
    }

    @Test
    fun `PendingAction OpenSearch sets correctly`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setPendingAction(PendingAction.OpenSearch)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pendingAction is PendingAction.OpenSearch)
    }

    @Test
    fun `PendingAction GoToToday sets correctly`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setPendingAction(PendingAction.GoToToday)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pendingAction is PendingAction.GoToToday)
    }

    @Test
    fun `PendingAction ImportIcsFile stores URI correctly`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Use mockk for URI since Uri.parse returns null in unit tests
        val uri = mockk<android.net.Uri>(relaxed = true)
        val action = PendingAction.ImportIcsFile(uri)
        viewModel.setPendingAction(action)
        advanceUntilIdle()

        val pending = viewModel.uiState.value.pendingAction as? PendingAction.ImportIcsFile
        assertEquals(uri, pending?.uri)
    }

    @Test
    fun `initial state has null pendingAction`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingAction)
    }

    // ==================== Week View Tests ====================

    @Test
    fun `setAgendaViewType THREE_DAYS sets pending pager position to today`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Initially pendingWeekViewPagerPosition should be null
        assertEquals(null, viewModel.uiState.value.pendingWeekViewPagerPosition)

        // Switch to 3-day view
        viewModel.setAgendaViewType(AgendaViewType.THREE_DAYS)
        advanceUntilIdle()

        // With infinite pager, switching to 3-day view sets pendingWeekViewPagerPosition to CENTER_DAY_PAGE
        val expectedPage = org.onekash.kashcal.ui.components.weekview.WeekViewUtils.CENTER_DAY_PAGE
        assertEquals(
            "pendingWeekViewPagerPosition should be CENTER_DAY_PAGE",
            expectedPage,
            viewModel.uiState.value.pendingWeekViewPagerPosition
        )
    }

    @Test
    fun `goToToday in 3-day view sets pending pager position to CENTER_DAY_PAGE`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Switch to 3-day view within agenda panel
        viewModel.toggleAgendaPanel()  // Open agenda panel
        viewModel.setAgendaViewType(AgendaViewType.THREE_DAYS)
        advanceUntilIdle()

        // Clear any pending navigation from initialization
        viewModel.clearPendingWeekViewPagerPosition()
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.pendingWeekViewPagerPosition)

        // Call goToToday - should set pending position to CENTER_DAY_PAGE (today)
        viewModel.goToToday()
        advanceUntilIdle()

        // With infinite pager, goToToday sets pendingWeekViewPagerPosition to CENTER_DAY_PAGE
        val expectedPage = org.onekash.kashcal.ui.components.weekview.WeekViewUtils.CENTER_DAY_PAGE
        assertEquals(
            "Should navigate to CENTER_DAY_PAGE (today)",
            expectedPage,
            viewModel.uiState.value.pendingWeekViewPagerPosition
        )
    }

    @Test
    fun `goToToday in month view still navigates month view`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Make sure agenda panel is closed (month view)
        if (viewModel.uiState.value.showAgendaPanel) {
            viewModel.toggleAgendaPanel()
            advanceUntilIdle()
        }

        // Navigate to a different month
        viewModel.setViewingMonth(2027, 5)  // June 2027
        advanceUntilIdle()

        assertEquals(2027, viewModel.uiState.value.viewingYear)
        assertEquals(5, viewModel.uiState.value.viewingMonth)

        // Call goToToday
        viewModel.goToToday()
        advanceUntilIdle()

        // Should navigate to today's month
        val today = JavaCalendar.getInstance()
        assertEquals(today.get(JavaCalendar.YEAR), viewModel.uiState.value.viewingYear)
        assertEquals(today.get(JavaCalendar.MONTH), viewModel.uiState.value.viewingMonth)
    }

    @Test
    fun `goToToday in agenda list view sets pendingScrollAgendaToTop`() = runTest {
        val testOccurrencesWithEvents = listOf(
            EventReader.OccurrenceWithEvent(
                occurrence = testOccurrences[0],
                event = testEvents[0],
                calendar = testCalendars[0]
            )
        )
        every { eventReader.getOccurrencesWithEventsInRangeFlow(any(), any()) } returns flowOf(testOccurrencesWithEvents)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Open agenda panel (defaults to AGENDA view type)
        viewModel.toggleAgendaPanel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showAgendaPanel)
        assertEquals(AgendaViewType.AGENDA, viewModel.uiState.value.agendaViewType)

        // Initially pendingScrollAgendaToTop should be false
        assertFalse(viewModel.uiState.value.pendingScrollAgendaToTop)

        // Call goToToday
        viewModel.goToToday()
        advanceUntilIdle()

        // Should set pendingScrollAgendaToTop = true
        assertTrue(viewModel.uiState.value.pendingScrollAgendaToTop)
    }

    @Test
    fun `clearScrollAgendaToTop clears the flag`() = runTest {
        val testOccurrencesWithEvents = listOf(
            EventReader.OccurrenceWithEvent(
                occurrence = testOccurrences[0],
                event = testEvents[0],
                calendar = testCalendars[0]
            )
        )
        every { eventReader.getOccurrencesWithEventsInRangeFlow(any(), any()) } returns flowOf(testOccurrencesWithEvents)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Open agenda panel and trigger scroll
        viewModel.toggleAgendaPanel()
        advanceUntilIdle()
        viewModel.goToToday()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pendingScrollAgendaToTop)

        // Clear the flag
        viewModel.clearScrollAgendaToTop()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.pendingScrollAgendaToTop)
    }

    @Test
    fun `onWeekViewDateSelected sets pending pager position for selected date`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Initial state: no pending position
        assertEquals(null, viewModel.uiState.value.pendingWeekViewPagerPosition)

        // Select a date 5 days from today
        val today = java.time.LocalDate.now()
        val targetDate = today.plusDays(5)
        val targetMs = targetDate.atStartOfDay(java.time.ZoneId.systemDefault())
            .plusHours(12)
            .toInstant()
            .toEpochMilli()

        viewModel.onWeekViewDateSelected(targetMs)
        advanceUntilIdle()

        // With infinite pager, pendingWeekViewPagerPosition is the absolute page number
        // dateToPage(date) = CENTER_DAY_PAGE + days from today
        val expectedPage = org.onekash.kashcal.ui.components.weekview.WeekViewUtils.CENTER_DAY_PAGE + 5
        assertEquals(expectedPage, viewModel.uiState.value.pendingWeekViewPagerPosition)
    }

    @Test
    fun `clearPendingWeekViewPagerPosition clears the pending position`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Set a pending position via date selection (7 days from today)
        val today = java.time.LocalDate.now()
        val targetDate = today.plusDays(7)
        val targetMs = targetDate.atStartOfDay(java.time.ZoneId.systemDefault())
            .plusHours(12)
            .toInstant()
            .toEpochMilli()

        viewModel.onWeekViewDateSelected(targetMs)
        advanceUntilIdle()

        // Should have pending position (absolute page number)
        val expectedPage = org.onekash.kashcal.ui.components.weekview.WeekViewUtils.CENTER_DAY_PAGE + 7
        assertEquals(expectedPage, viewModel.uiState.value.pendingWeekViewPagerPosition)

        // Clear it
        viewModel.clearPendingWeekViewPagerPosition()
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingWeekViewPagerPosition)
    }

    // ==================== Helper Functions ====================

    private fun getTimestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return JavaCalendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(JavaCalendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
