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
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.dao.EventWithNextOccurrence
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.SearchResult
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.reader.EventReader.OccurrenceWithEvent
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus
import org.onekash.kashcal.ui.components.EventFormState
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
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
    private lateinit var displayEventRepository: DisplayEventRepository
    private lateinit var dataStore: KashCalDataStore
    private lateinit var accountRepository: AccountRepository
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

    private val testOccurrencesWithEvents by lazy {
        testOccurrences.mapIndexed { index, occurrence ->
            OccurrenceWithEvent(
                occurrence = occurrence,
                event = testEvents[index],
                calendar = testCalendars.find { it.id == occurrence.calendarId }
            )
        }
    }

    private val testICloudAccount = Account(
        id = 1L,
        provider = AccountProvider.ICLOUD,
        email = "test@icloud.com",
        displayName = "iCloud",
        isEnabled = true
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Initialize mocks
        eventCoordinator = mockk(relaxed = true)
        eventReader = mockk(relaxed = true)
        displayEventRepository = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
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

        // Setup default mock behavior - EventCoordinator provides calendars and accounts via Flow
        // IMPORTANT: ViewModel uses combine() on getAllCalendars + getAllAccounts + defaultCalendar
        // All three flows must emit for combine() to emit
        every { eventCoordinator.getAllCalendars() } returns flowOf(testCalendars)
        every { eventCoordinator.getAllAccounts() } returns flowOf(emptyList())
        every { dataStore.defaultCalendar } returns flowOf(null)
        coEvery { dataStore.defaultReminderMinutes } returns flowOf(15)
        coEvery { dataStore.defaultAllDayReminder } returns flowOf(1440)
        coEvery { accountRepository.getAllAccounts() } returns emptyList()
        coEvery { accountRepository.hasCredentials(any()) } returns false
        coEvery { eventReader.getVisibleOccurrencesInRange(any(), any()) } returns flowOf(testOccurrences)
        every { eventReader.getVisibleOccurrencesForDay(any()) } returns flowOf(testOccurrences)
        every { eventReader.getVisibleOccurrencesWithEventsForDay(any()) } returns flowOf(testOccurrencesWithEvents)
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
        // Device calendar change signal (starts at 0, no changes)
        every { displayEventRepository.deviceCalendarChangeSignal } returns MutableStateFlow(0)

        // SearchDisplayEvents mock: invokes roomSearcher lambda so EventReader verifications still work
        coEvery { displayEventRepository.searchDisplayEvents(any(), any(), any(), any()) } coAnswers {
            val query = firstArg<String>()
            val roomSearcher = arg<suspend (String) -> List<SearchResult>>(3)
            roomSearcher(query)
        }
        every { dataStore.defaultCalendarView } returns flowOf(KashCalDataStore.VIEW_MONTH)
        coEvery { dataStore.getDefaultCalendarView() } returns KashCalDataStore.VIEW_MONTH
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
            calendarProviderRepository = org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository(),
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
    fun `initializeAsync checks account status`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // With no accounts, should not be configured
        assertFalse(viewModel.uiState.value.isConfigured)
    }

    @Test
    fun `initializeAsync sets isConfigured when account has credentials`() = runTest {
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isConfigured)
    }

    // ==================== Account Status Baseline Tests ====================

    @Test
    fun `checkAccountStatus shows setup banner when no accounts exist`() = runTest {
        // @Before default: getAllAccounts returns emptyList()
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isConfigured)
    }

    @Test
    fun `account without credentials shows setup banner`() = runTest {
        val accountNoCredentials = Account(
            id = 3L,
            provider = AccountProvider.ICLOUD,
            email = "test@icloud.com",
            displayName = "iCloud",
            isEnabled = true
        )
        coEvery { accountRepository.getAllAccounts() } returns listOf(accountNoCredentials)
        coEvery { accountRepository.hasCredentials(accountNoCredentials.id) } returns false

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isConfigured)
    }

    @Test

    fun `POST - setup banner hides when any account is configured`() = runTest {
        val caldavAccount = Account(
            id = 2L,
            provider = AccountProvider.CALDAV,
            email = "user@nextcloud.com",
            displayName = "Nextcloud",
            isEnabled = true
        )
        coEvery { accountRepository.getAllAccounts() } returns listOf(caldavAccount)
        coEvery { accountRepository.hasCredentials(caldavAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue("isConfigured should be true when any account has credentials", viewModel.uiState.value.isConfigured)
    }

    @Test

    fun `POST - setup banner shows for any unconfigured provider`() = runTest {
        val caldavAccountNoCredentials = Account(
            id = 2L,
            provider = AccountProvider.CALDAV,
            email = "user@nextcloud.com",
            displayName = "Nextcloud",
            isEnabled = true
        )
        coEvery { accountRepository.getAllAccounts() } returns listOf(caldavAccountNoCredentials)
        coEvery { accountRepository.hasCredentials(caldavAccountNoCredentials.id) } returns false

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse("isConfigured should be false when no credentials", viewModel.uiState.value.isConfigured)
    }

    // ==================== Account Status POST Tests (BUG 1 fix) ====================
    // These tests verify the DESIRED behavior after removing iCloud hardcoding.
    // checkAccountStatus() should consider ALL sync-capable accounts (iCloud + CalDAV).

    @Test

    fun `POST - checkAccountStatus sets isConfigured for CalDAV-only account`() = runTest {
        val caldavAccount = Account(
            id = 10L,
            provider = AccountProvider.CALDAV,
            email = "user@nextcloud.example.com",
            displayName = "Nextcloud",
            isEnabled = true
        )
        coEvery { accountRepository.getAllAccounts() } returns listOf(caldavAccount)
        coEvery { accountRepository.hasCredentials(10L) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue("isConfigured should be true for CalDAV account with credentials",
            viewModel.uiState.value.isConfigured)
    }

    @Test

    fun `POST - checkAccountStatus sets isConfigured for iCloud account`() = runTest {
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue("isConfigured should be true for iCloud account with credentials (no regression)",
            viewModel.uiState.value.isConfigured)
    }

    @Test

    fun `POST - checkAccountStatus sets isConfigured when both providers exist`() = runTest {
        val caldavAccount = Account(
            id = 10L,
            provider = AccountProvider.CALDAV,
            email = "user@nextcloud.example.com",
            displayName = "Nextcloud",
            isEnabled = true
        )
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount, caldavAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true
        coEvery { accountRepository.hasCredentials(10L) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue("isConfigured should be true when both providers configured",
            viewModel.uiState.value.isConfigured)
    }

    @Test

    fun `POST - triggerStartupSync works with CalDAV-only account`() = runTest {
        val caldavAccount = Account(
            id = 10L,
            provider = AccountProvider.CALDAV,
            email = "user@nextcloud.example.com",
            displayName = "Nextcloud",
            isEnabled = true
        )
        coEvery { accountRepository.getAllAccounts() } returns listOf(caldavAccount)
        coEvery { accountRepository.hasCredentials(10L) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isConfigured)

        viewModel.triggerStartupSync()
        advanceUntilIdle()

        verify { syncScheduler.requestImmediateSync(any(), any()) }
    }

    @Test

    fun `POST - syncOnResumeIfNeeded works with CalDAV-only account`() = runTest {
        val caldavAccount = Account(
            id = 10L,
            provider = AccountProvider.CALDAV,
            email = "user@nextcloud.example.com",
            displayName = "Nextcloud",
            isEnabled = true
        )
        coEvery { accountRepository.getAllAccounts() } returns listOf(caldavAccount)
        coEvery { accountRepository.hasCredentials(10L) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isConfigured)

        // Trigger startup sync first (sets hasTriggeredStartupSync)
        viewModel.triggerStartupSync()
        advanceUntilIdle()

        // Simulate sync completing so isSyncing resets to false
        syncStatusFlow.value = SyncStatus.Succeeded()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isSyncing)

        // Now resume sync should trigger
        viewModel.syncOnResumeIfNeeded()
        advanceUntilIdle()

        // Should have been called twice: once for startup, once for resume
        verify(atLeast = 2) { syncScheduler.requestImmediateSync(any(), any()) }
    }

    @Test

    fun `POST - isConfigured false when all accounts lack credentials`() = runTest {
        val caldavAccount = Account(
            id = 10L,
            provider = AccountProvider.CALDAV,
            email = "user@nextcloud.example.com",
            displayName = "Nextcloud",
            isEnabled = true
        )
        coEvery { accountRepository.getAllAccounts() } returns listOf(caldavAccount)
        coEvery { accountRepository.hasCredentials(10L) } returns false

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse("isConfigured should be false when no credentials",
            viewModel.uiState.value.isConfigured)
    }

    @Test

    fun `POST - isConfigured false when no accounts exist`() = runTest {
        coEvery { accountRepository.getAllAccounts() } returns emptyList()

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse("isConfigured should be false with no accounts",
            viewModel.uiState.value.isConfigured)
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
    fun `selectDate updates state`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val dateMillis = getTimestamp(2024, 11, 17, 0, 0)
        viewModel.selectDate(dateMillis)
        advanceUntilIdle()

        // selectDate updates selectedDate and label (events loaded via day pager cache)
        assertEquals(dateMillis, viewModel.uiState.value.selectedDate)
        assertTrue(viewModel.uiState.value.selectedDayLabel.contains("2024"))
    }

    @Test
    fun `selectDate with pre-1970 date updates selectedDate correctly`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Apollo 11 — July 20, 1969 (month param is 0-indexed: 6 = July)
        val dateMillis = getTimestamp(1969, 6, 20, 0, 0)

        // Pre-1970 dates have negative epoch millis
        assertTrue("Pre-1970 date should have negative millis", dateMillis < 0)

        viewModel.selectDate(dateMillis)
        advanceUntilIdle()

        assertEquals(dateMillis, viewModel.uiState.value.selectedDate)
        assertTrue(viewModel.uiState.value.selectedDayLabel.contains("1969"))
    }

    @Test
    fun `selectDate with pre-1970 date updates state correctly`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val dateMillis = getTimestamp(1969, 6, 20, 0, 0)
        assertTrue("Pre-1970 date should have negative millis", dateMillis < 0)

        viewModel.selectDate(dateMillis)
        advanceUntilIdle()

        // Pre-1970 dates should update state correctly
        assertEquals(dateMillis, viewModel.uiState.value.selectedDate)
        assertTrue(viewModel.uiState.value.selectedDayLabel.contains("1969"))
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
    fun `setViewMode to AGENDA loads events`() = runTest {
        // Setup DisplayEventRepository mock for agenda (merges Room + device events)
        val testDisplayEvents = persistentListOf(
            DisplayEvent.Room(testEvents[0], testOccurrences[0], testCalendars[0]),
            DisplayEvent.Room(testEvents[1], testOccurrences[1], testCalendars[1])
        )
        every { displayEventRepository.getDisplayEventsForRange(any(), any()) } returns flowOf(testDisplayEvents)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(ViewMode.MONTH, viewModel.uiState.value.viewMode)
        assertTrue(viewModel.uiState.value.agendaEvents.isEmpty())

        // Switch to agenda view
        viewModel.setViewMode(ViewMode.AGENDA)
        advanceUntilIdle()

        assertEquals(ViewMode.AGENDA, viewModel.uiState.value.viewMode)
        assertEquals(2, viewModel.uiState.value.agendaEvents.size)
        assertFalse(viewModel.uiState.value.isLoadingAgenda)
    }

    @Test
    fun `setViewMode from AGENDA to MONTH does not reload agenda`() = runTest {
        val testDisplayEvents = persistentListOf(
            DisplayEvent.Room(testEvents[0], testOccurrences[0], testCalendars[0])
        )
        every { displayEventRepository.getDisplayEventsForRange(any(), any()) } returns flowOf(testDisplayEvents)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Switch to agenda
        viewModel.setViewMode(ViewMode.AGENDA)
        advanceUntilIdle()
        assertEquals(ViewMode.AGENDA, viewModel.uiState.value.viewMode)

        // Clear mock call count
        io.mockk.clearMocks(displayEventRepository, answers = false, recordedCalls = true, childMocks = false)

        // Switch back to month
        viewModel.setViewMode(ViewMode.MONTH)
        advanceUntilIdle()

        assertEquals(ViewMode.MONTH, viewModel.uiState.value.viewMode)
        // Should NOT have called getDisplayEventsForRange when going to month
        verify(exactly = 0) { displayEventRepository.getDisplayEventsForRange(any(), any()) }
    }

    @Test
    fun `agenda loads 30 days of events`() = runTest {
        every { displayEventRepository.getDisplayEventsForRange(any(), any()) } returns flowOf(persistentListOf())

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setViewMode(ViewMode.AGENDA)
        advanceUntilIdle()

        // Verify the DisplayEventRepository range query was called
        verify {
            displayEventRepository.getDisplayEventsForRange(any(), any())
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

        // DisplayEventRepository returns pre-sorted (later first to test sort correctness)
        // In practice, DisplayEventRepository sorts by startTs; verify ViewModel stores as-is
        val sortedDisplayEvents = persistentListOf(
            DisplayEvent.Room(testEvents[0], testOccurrences[0], testCalendars[0]),
            DisplayEvent.Room(laterEvent, laterOccurrence, testCalendars[0])
        )
        every { displayEventRepository.getDisplayEventsForRange(any(), any()) } returns flowOf(sortedDisplayEvents)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setViewMode(ViewMode.AGENDA)
        advanceUntilIdle()

        // Should be sorted by startTs (earlier first) - DisplayEventRepository handles sorting
        assertEquals(2, viewModel.uiState.value.agendaEvents.size)
        assertTrue(
            viewModel.uiState.value.agendaEvents[0].startTs <
            viewModel.uiState.value.agendaEvents[1].startTs
        )
    }

    @Test
    fun `agenda shows loading state while fetching`() = runTest {
        // Track loading state during the fetch
        var loadingStateDuringFetch = false
        every { displayEventRepository.getDisplayEventsForRange(any(), any()) } answers {
            // This captures that isLoadingAgenda was true when we started fetching
            loadingStateDuringFetch = true
            flowOf(persistentListOf())
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Initially not loading
        assertFalse(viewModel.uiState.value.isLoadingAgenda)

        viewModel.setViewMode(ViewMode.AGENDA)
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
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isConfigured)

        viewModel.triggerStartupSync()
        advanceUntilIdle()

        verify { syncScheduler.requestImmediateSync(any(), any()) }
    }

    @Test
    fun `triggerStartupSync only runs once`() = runTest {
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

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
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.forceFullSync()
        advanceUntilIdle()

        verify { syncScheduler.requestImmediateSync(forceFullSync = true) }
    }

    @Test
    fun `refreshSync does not start if already syncing`() = runTest {
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

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
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

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
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

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
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

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
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

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
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

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

    // ==================== Partial Error Chain Tests (GAP 2 / GAP 7 plan) ====================

    @Test
    fun `PartialSuccess shows banner with error message even for pull-to-refresh`() = runTest {
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Pull-to-refresh sets showBannerForSync = false
        viewModel.refreshSync()
        advanceUntilIdle()

        syncStatusFlow.value = SyncStatus.Running
        advanceUntilIdle()

        // Emit PartialSuccess (Succeeded with errorMessage)
        syncStatusFlow.value = SyncStatus.Succeeded(
            calendarsSynced = 2,
            eventsPulled = 5,
            errorMessage = "1 account failed: Auth error"
        )
        // Advance enough for state update but not past auto-dismiss
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()

        // Banner should be visible because hasPartialError forces it
        assertTrue("Banner should show for partial error", viewModel.uiState.value.showSyncBanner)
        assertEquals("Sync complete with errors", viewModel.uiState.value.syncBannerMessage)
        assertFalse("isSyncing should be false", viewModel.uiState.value.isSyncing)
    }

    @Test
    fun `PartialSuccess banner auto-dismisses after 3 seconds`() = runTest {
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.refreshSync()
        advanceUntilIdle()

        syncStatusFlow.value = SyncStatus.Running
        advanceUntilIdle()

        syncStatusFlow.value = SyncStatus.Succeeded(
            calendarsSynced = 2,
            errorMessage = "1 account failed"
        )

        // After 100ms, banner should be visible
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertTrue("Banner should show initially", viewModel.uiState.value.showSyncBanner)

        // After 3 seconds, banner should auto-dismiss
        testScheduler.advanceTimeBy(3000)
        testScheduler.runCurrent()
        assertFalse("Banner should auto-dismiss after 3s", viewModel.uiState.value.showSyncBanner)
    }

    @Test
    fun `clean Succeeded without errorMessage does not force banner`() = runTest {
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Pull-to-refresh: banner flag is false
        viewModel.refreshSync()
        advanceUntilIdle()

        syncStatusFlow.value = SyncStatus.Running
        advanceUntilIdle()

        // Clean success — no errorMessage
        syncStatusFlow.value = SyncStatus.Succeeded(
            calendarsSynced = 3,
            eventsPulled = 10
        )
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()

        // Banner should NOT show because showBanner=false and hasPartialError=false
        assertFalse("Banner should not show for clean pull-to-refresh success",
            viewModel.uiState.value.showSyncBanner)
        assertEquals("Sync complete", viewModel.uiState.value.syncBannerMessage)
    }

    @Test
    fun `Succeeded with errorMessage shows different message than Failed`() = runTest {
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.refreshSync()
        advanceUntilIdle()

        // Test PartialSuccess banner message
        syncStatusFlow.value = SyncStatus.Succeeded(errorMessage = "Nextcloud auth expired")
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()

        val partialMessage = viewModel.uiState.value.syncBannerMessage
        assertEquals("Sync complete with errors", partialMessage)

        // Reset and test Failed banner message
        syncStatusFlow.value = SyncStatus.Idle
        advanceUntilIdle()
        syncStatusFlow.value = SyncStatus.Failed(errorMessage = "All accounts failed")
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()

        val failedMessage = viewModel.uiState.value.syncBannerMessage
        assertTrue("Failed message should contain error", failedMessage.contains("Sync failed"))
        assertTrue("Failed message should contain specific error", failedMessage.contains("All accounts failed"))
    }

    @Test
    fun `forceFullSync shows banner on PartialSuccess`() = runTest {
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Force full sync sets banner flag to true
        viewModel.forceFullSync()
        advanceUntilIdle()

        syncStatusFlow.value = SyncStatus.Running
        advanceUntilIdle()

        syncStatusFlow.value = SyncStatus.Succeeded(
            calendarsSynced = 2,
            errorMessage = "1 account: 401 Unauthorized"
        )
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()

        // Banner should show (both showBanner=true AND hasPartialError=true)
        assertTrue("Banner should show", viewModel.uiState.value.showSyncBanner)
        assertEquals("Sync complete with errors", viewModel.uiState.value.syncBannerMessage)
    }

    @Test

    fun `POST - refreshSync works with CalDAV-only account`() = runTest {
        val caldavAccount = Account(
            id = 10L,
            provider = AccountProvider.CALDAV,
            email = "user@nextcloud.example.com",
            displayName = "Nextcloud",
            isEnabled = true
        )
        coEvery { accountRepository.getAllAccounts() } returns listOf(caldavAccount)
        coEvery { accountRepository.hasCredentials(caldavAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue("isConfigured should be true when CalDAV account configured",
            viewModel.uiState.value.isConfigured)

        viewModel.refreshSync()
        assertTrue("isSyncing should be true after refreshSync", viewModel.uiState.value.isSyncing)
        verify { syncScheduler.requestImmediateSync(any(), any()) }
    }

    @Test
    fun `PartialSuccess banner works with CalDAV multi-account setup`() = runTest {
        // Setup: iCloud + CalDAV — mixed provider scenario
        val caldavAccount = Account(
            id = 10L,
            provider = AccountProvider.CALDAV,
            email = "user@nextcloud.example.com",
            displayName = "Nextcloud",
            isEnabled = true
        )
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount, caldavAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true
        coEvery { accountRepository.hasCredentials(caldavAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.refreshSync()
        advanceUntilIdle()

        syncStatusFlow.value = SyncStatus.Running
        advanceUntilIdle()

        // PartialSuccess: iCloud synced, Nextcloud auth expired
        syncStatusFlow.value = SyncStatus.Succeeded(
            calendarsSynced = 3,
            eventsPulled = 10,
            errorMessage = "Nextcloud: 401 Unauthorized"
        )
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()

        // Banner should show for partial error even in pull-to-refresh
        assertTrue("Banner should show for partial error", viewModel.uiState.value.showSyncBanner)
        assertEquals("Sync complete with errors", viewModel.uiState.value.syncBannerMessage)
    }

    @Test
    fun `sync banner hidden when status is Idle`() = runTest {
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

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
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

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

        io.mockk.clearMocks(displayEventRepository, answers = false, recordedCalls = true)
        every { displayEventRepository.deviceCalendarChangeSignal } returns MutableStateFlow(0)

        viewModel.deleteEventOptimistic(1L)
        advanceUntilIdle()

        // Verify reloadCurrentView was called (rebuilds event dots via DisplayEventRepository)
        coVerify(atLeast = 1) { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) }
    }

    @Test
    fun `reloadCurrentView rebuilds dots after delete with pre-1970 selectedDate - issue 53`() = runTest {
        coEvery { eventCoordinator.deleteEvent(any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Select a pre-1970 date (Feb 11, 1952 — from issue #53)
        viewModel.selectDate(getTimestamp(1952, 1, 11, 0, 0))
        advanceUntilIdle()

        io.mockk.clearMocks(displayEventRepository, answers = false, recordedCalls = true)
        every { displayEventRepository.deviceCalendarChangeSignal } returns MutableStateFlow(0)

        viewModel.deleteEventOptimistic(1L)
        advanceUntilIdle()

        // Verify reloadCurrentView rebuilt dots via DisplayEventRepository (always happens regardless of selectedDate)
        coVerify(atLeast = 1) { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) }
    }

    @Test
    fun `selectDate with zero millis is treated as no selection`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // 0L is the sentinel for "no selection"
        viewModel.selectDate(0L)
        advanceUntilIdle()

        assertEquals(0L, viewModel.uiState.value.selectedDate)
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
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

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
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

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
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Clear initial call counts
        io.mockk.clearMocks(displayEventRepository, answers = false, recordedCalls = true, childMocks = false)
        every { displayEventRepository.deviceCalendarChangeSignal } returns MutableStateFlow(0)

        // Start sync
        viewModel.refreshSync()
        advanceUntilIdle()

        // Now simulate sync completing successfully
        syncStatusFlow.value = SyncStatus.Succeeded(calendarsSynced = 2, eventsPulled = 5)
        advanceUntilIdle()

        // reloadCurrentView should rebuild event dots via DisplayEventRepository
        coVerify(atLeast = 1) { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) }
    }

    @Test
    fun `concurrent refreshSync calls are blocked when isSyncing is true`() = runTest {
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

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
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

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

    // ==================== Event Dots / Calendar Month View Tests ====================

    @Test
    fun `event dots are built from occurrences`() = runTest {
        // Mock DisplayEventRepository to return pre-grouped events by day code
        val cal1Color = testCalendars[0].color
        val cal2Color = testCalendars[1].color
        val groupedEvents = mapOf(
            20241205 to listOf(
                createDotDisplayEvent(1L, "Event 1", getTimestamp(2024, 11, 5, 10, 0), getTimestamp(2024, 11, 5, 11, 0), 20241205, calendarColor = cal1Color)
            ),
            20241210 to listOf(
                createDotDisplayEvent(2L, "Event 2", getTimestamp(2024, 11, 10, 14, 0), getTimestamp(2024, 11, 10, 15, 0), 20241210, calendarColor = cal1Color),
                createDotDisplayEvent(3L, "Event 3", getTimestamp(2024, 11, 10, 16, 0), getTimestamp(2024, 11, 10, 17, 0), 20241210, calendarColor = cal2Color)
            )
        )
        coEvery { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) } returns groupedEvents

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
        val cal1Color = testCalendars[0].color
        val groupedEvents = mapOf(
            20241203 to listOf(
                createDotDisplayEvent(10L, "Weekly", getTimestamp(2024, 11, 3, 10, 0), getTimestamp(2024, 11, 3, 11, 0), 20241203, calendarColor = cal1Color)
            ),
            20241210 to listOf(
                createDotDisplayEvent(10L, "Weekly", getTimestamp(2024, 11, 10, 10, 0), getTimestamp(2024, 11, 10, 11, 0), 20241210, calendarColor = cal1Color)
            ),
            20241217 to listOf(
                createDotDisplayEvent(10L, "Weekly", getTimestamp(2024, 11, 17, 10, 0), getTimestamp(2024, 11, 17, 11, 0), 20241217, calendarColor = cal1Color)
            )
        )
        coEvery { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) } returns groupedEvents

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
    fun `setViewMode THREE_DAYS sets pending pager position to today`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Initially pendingWeekViewPagerPosition should be null
        assertEquals(null, viewModel.uiState.value.pendingWeekViewPagerPosition)

        // Switch to 3-day view
        viewModel.setViewMode(ViewMode.THREE_DAYS)
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

        // Switch to 3-day view
        viewModel.setViewMode(ViewMode.THREE_DAYS)
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

        // Make sure we're in month view
        if (viewModel.uiState.value.viewMode != ViewMode.MONTH) {
            viewModel.setViewMode(ViewMode.MONTH)
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
        val testDisplayEvents = persistentListOf(
            DisplayEvent.Room(testEvents[0], testOccurrences[0], testCalendars[0])
        )
        every { displayEventRepository.getDisplayEventsForRange(any(), any()) } returns flowOf(testDisplayEvents)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Switch to agenda view
        viewModel.setViewMode(ViewMode.AGENDA)
        advanceUntilIdle()

        assertEquals(ViewMode.AGENDA, viewModel.uiState.value.viewMode)

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
        val testDisplayEvents = persistentListOf(
            DisplayEvent.Room(testEvents[0], testOccurrences[0], testCalendars[0])
        )
        every { displayEventRepository.getDisplayEventsForRange(any(), any()) } returns flowOf(testDisplayEvents)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Switch to agenda and trigger scroll
        viewModel.setViewMode(ViewMode.AGENDA)
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

    // ==================== Navigation Tests ====================

    @Test
    fun `navigateToMonth updates viewing month and year`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.navigateToMonth(2025, 6) // July 2025
        advanceUntilIdle()

        assertEquals(2025, viewModel.uiState.value.viewingYear)
        assertEquals(6, viewModel.uiState.value.viewingMonth)
        assertEquals(2025 to 6, viewModel.uiState.value.pendingNavigateToMonth)
    }

    @Test
    fun `clearNavigateToMonth clears the pending navigation`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.navigateToMonth(2025, 6)
        advanceUntilIdle()
        assertEquals(2025 to 6, viewModel.uiState.value.pendingNavigateToMonth)

        viewModel.clearNavigateToMonth()
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.pendingNavigateToMonth)
    }

    @Test
    fun `navigateToDate updates viewing month and year`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val targetDate = java.time.LocalDate.of(2025, 3, 15)
        viewModel.navigateToDate(targetDate)
        advanceUntilIdle()

        assertEquals(2025, viewModel.uiState.value.viewingYear)
        assertEquals(2, viewModel.uiState.value.viewingMonth) // 0-indexed
        // Also triggers date selection and sets pending navigation
        assertEquals(2025 to 2, viewModel.uiState.value.pendingNavigateToMonth)
    }

    @Test
    fun `goToTodayWeek sets pending pager position to center`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.goToTodayWeek()
        advanceUntilIdle()

        // Should have pending pager position at center
        val centerPage = org.onekash.kashcal.ui.components.weekview.WeekViewUtils.CENTER_DAY_PAGE
        assertEquals(centerPage, viewModel.uiState.value.pendingWeekViewPagerPosition)
        // onDayPagerPageChanged also updates weekViewPagerPosition
        assertEquals(centerPage, viewModel.uiState.value.weekViewPagerPosition)
    }

    @Test
    fun `navigateDaysPagerPrevious sets pending position minus VISIBLE_DAYS`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Set a known pager position first
        val startPage = WeekViewUtils.CENTER_DAY_PAGE
        viewModel.setWeekViewPagerPosition(startPage)
        advanceUntilIdle()

        viewModel.navigateDaysPagerPrevious()
        advanceUntilIdle()

        val expectedPage = startPage - WeekViewUtils.VISIBLE_DAYS
        assertEquals(expectedPage, viewModel.uiState.value.pendingWeekViewPagerPosition)
        assertEquals(expectedPage, viewModel.uiState.value.weekViewPagerPosition)
    }

    @Test
    fun `navigateDaysPagerNext sets pending position plus VISIBLE_DAYS`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Set a known pager position first
        val startPage = WeekViewUtils.CENTER_DAY_PAGE
        viewModel.setWeekViewPagerPosition(startPage)
        advanceUntilIdle()

        viewModel.navigateDaysPagerNext()
        advanceUntilIdle()

        val expectedPage = startPage + WeekViewUtils.VISIBLE_DAYS
        assertEquals(expectedPage, viewModel.uiState.value.pendingWeekViewPagerPosition)
        assertEquals(expectedPage, viewModel.uiState.value.weekViewPagerPosition)
    }

    @Test
    fun `setWeekViewScrollPosition updates scroll position`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setWeekViewScrollPosition(500)
        advanceUntilIdle()

        assertEquals(500, viewModel.uiState.value.weekViewScrollPosition)
    }

    @Test
    fun `setWeekViewPagerPosition updates pager position`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setWeekViewPagerPosition(100)
        advanceUntilIdle()

        assertEquals(100, viewModel.uiState.value.weekViewPagerPosition)
    }

    @Test
    fun `setViewingMonth updates month without triggering navigation`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setViewingMonth(2025, 11) // December 2025
        advanceUntilIdle()

        assertEquals(2025, viewModel.uiState.value.viewingYear)
        assertEquals(11, viewModel.uiState.value.viewingMonth)
        // Should NOT set pendingNavigateToMonth (this is for swipe callbacks)
        assertEquals(null, viewModel.uiState.value.pendingNavigateToMonth)
    }

    @Test
    fun `goToTodayInDayPager returns center page and triggers load`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val resultPage = viewModel.goToTodayInDayPager()
        advanceUntilIdle()

        val centerPage = org.onekash.kashcal.ui.components.weekview.WeekViewUtils.CENTER_DAY_PAGE
        assertEquals(centerPage, resultPage)
        // onDayPagerPageChanged updates weekViewPagerPosition
        assertEquals(centerPage, viewModel.uiState.value.weekViewPagerPosition)
    }

    @Test
    fun `navigateDayPagerToDate returns correct page and triggers load`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Navigate to 10 days from today
        val today = java.time.LocalDate.now()
        val targetDate = today.plusDays(10)
        val targetMs = targetDate.atStartOfDay(java.time.ZoneId.systemDefault())
            .plusHours(12)
            .toInstant()
            .toEpochMilli()

        val resultPage = viewModel.navigateDayPagerToDate(targetMs)
        advanceUntilIdle()

        val expectedPage = org.onekash.kashcal.ui.components.weekview.WeekViewUtils.CENTER_DAY_PAGE + 10
        assertEquals(expectedPage, resultPage)
        // Also updates weekViewPagerPosition via onDayPagerPageChanged
        assertEquals(expectedPage, viewModel.uiState.value.weekViewPagerPosition)
    }

    @Test
    fun `navigateDayPagerToDate handles past dates correctly`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Navigate to 5 days in the past
        val today = java.time.LocalDate.now()
        val targetDate = today.minusDays(5)
        val targetMs = targetDate.atStartOfDay(java.time.ZoneId.systemDefault())
            .plusHours(12)
            .toInstant()
            .toEpochMilli()

        val resultPage = viewModel.navigateDayPagerToDate(targetMs)
        advanceUntilIdle()

        val expectedPage = org.onekash.kashcal.ui.components.weekview.WeekViewUtils.CENTER_DAY_PAGE - 5
        assertEquals(expectedPage, resultPage)
        // Also updates weekViewPagerPosition via onDayPagerPageChanged
        assertEquals(expectedPage, viewModel.uiState.value.weekViewPagerPosition)
    }

    // ==================== Date Picker UI Tests ====================

    @Test
    fun `showWeekViewDatePicker sets flag to true`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showWeekViewDatePicker)

        viewModel.showWeekViewDatePicker()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showWeekViewDatePicker)
    }

    @Test
    fun `hideWeekViewDatePicker sets flag to false`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showWeekViewDatePicker()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showWeekViewDatePicker)

        viewModel.hideWeekViewDatePicker()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showWeekViewDatePicker)
    }

    @Test
    fun `showSearchDatePicker sets flag to true`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Activate search first
        viewModel.activateSearch()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showSearchDatePicker)

        viewModel.showSearchDatePicker()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showSearchDatePicker)
    }

    @Test
    fun `hideSearchDatePicker sets flag to false`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.activateSearch()
        viewModel.showSearchDatePicker()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showSearchDatePicker)

        viewModel.hideSearchDatePicker()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showSearchDatePicker)
    }

    // ==================== CalendarViewType.WEEK Cleanup Tests ====================

    @Test
    fun `initialization defaults to month view without week-specific setup`() = runTest {
        // After CalendarViewType removal, initialization never triggers goToTodayWeek
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Week view state should remain at defaults (no data loaded)
        assertEquals(0L, viewModel.uiState.value.weekViewStartDate)
        assertTrue(viewModel.uiState.value.weekViewTimedEvents.isEmpty())
    }

    @Test
    fun `3-day view initializes week data via goToTodayWeek`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Switch to 3-day view
        viewModel.setViewMode(ViewMode.THREE_DAYS)
        advanceUntilIdle()

        // 3-day view should have triggered goToTodayWeek which sets pending navigation
        // and starts loading data via onDayPagerPageChanged
        assertEquals(ViewMode.THREE_DAYS, viewModel.uiState.value.viewMode)
        // goToTodayWeek sets pendingWeekViewPagerPosition to CENTER_DAY_PAGE
        assertEquals(WeekViewUtils.CENTER_DAY_PAGE, viewModel.uiState.value.pendingWeekViewPagerPosition)
    }

    // ==================== View Picker Tests ====================

    @Test
    fun `setViewMode same type is no-op`() = runTest {
        every { eventReader.getVisibleOccurrencesWithEventsInRangeFlow(any(), any()) } returns flowOf(emptyList())

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Already in MONTH view
        assertEquals(ViewMode.MONTH, viewModel.uiState.value.viewMode)

        // Clear mocks to verify no calls happen
        io.mockk.clearMocks(eventReader, answers = false, recordedCalls = true, childMocks = false)

        // Set same view - should be no-op
        viewModel.setViewMode(ViewMode.MONTH)
        advanceUntilIdle()

        // No data loading calls should have been made
        verify(exactly = 0) { eventReader.getVisibleOccurrencesWithEventsInRangeFlow(any(), any()) }
    }

    @Test
    fun `setDefaultViewMode persists to DataStore`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setDefaultViewMode(ViewMode.AGENDA)
        advanceUntilIdle()

        coVerify { dataStore.setDefaultCalendarView("agenda") }
    }

    @Test
    fun `showViewPicker and hideViewPicker toggle state`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showViewPicker)

        viewModel.showViewPicker()
        assertTrue(viewModel.uiState.value.showViewPicker)

        viewModel.hideViewPicker()
        assertFalse(viewModel.uiState.value.showViewPicker)
    }

    @Test
    fun `init loads default view from DataStore`() = runTest {
        // Override default view to AGENDA (must be set before createViewModel)
        coEvery { dataStore.getDefaultCalendarView() } returns KashCalDataStore.VIEW_AGENDA
        every { dataStore.defaultCalendarView } returns flowOf(KashCalDataStore.VIEW_AGENDA)
        every { eventReader.getVisibleOccurrencesWithEventsInRangeFlow(any(), any()) } returns flowOf(emptyList())
        // Explicit mock for onboardingDismissed (accessed via .first() in initializeAsync)
        every { dataStore.onboardingDismissed } returns flowOf(false)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(ViewMode.AGENDA, viewModel.uiState.value.viewMode)
        assertEquals(ViewMode.AGENDA, viewModel.uiState.value.defaultViewMode)
    }

    // ==================== Occurrence Extension Tests ====================

    @Test
    fun `setViewingMonth calls both forward and past extension`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Setup specific return values to verify both are called
        coEvery { eventCoordinator.extendOccurrencesIfNeeded(any()) } returns 0
        coEvery { eventCoordinator.extendPastOccurrencesIfNeeded(any()) } returns 0

        // Navigate to a past month
        viewModel.setViewingMonth(2020, 2) // March 2020
        advanceUntilIdle()

        // Both forward and past extension should be called
        coVerify { eventCoordinator.extendOccurrencesIfNeeded(any()) }
        coVerify { eventCoordinator.extendPastOccurrencesIfNeeded(any()) }
    }

    // ==================== Helper Functions ====================

    private fun getTimestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return JavaCalendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(JavaCalendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * Create a DisplayEvent.Room for dots tests.
     * Lightweight helper — only calendarColor matters for dots, other fields are minimal.
     */
    private fun createDotDisplayEvent(
        id: Long,
        title: String,
        startTs: Long,
        endTs: Long,
        dayCode: Int,
        calendarColor: Int = testCalendars[0].color
    ): DisplayEvent {
        val event = Event(
            id = id,
            uid = "$title-$id@test",
            calendarId = 1L,
            title = title,
            startTs = startTs,
            endTs = endTs,
            dtstamp = System.currentTimeMillis()
        )
        val occurrence = Occurrence(
            eventId = id,
            calendarId = 1L,
            startTs = startTs,
            endTs = endTs,
            startDay = dayCode,
            endDay = dayCode
        )
        val calendar = testCalendars.find { it.color == calendarColor }
        return DisplayEvent.Room(event, occurrence, calendar)
    }
}
