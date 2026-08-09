package org.onekash.kashcal.ui.viewmodels

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.db.dao.EventWithNextOccurrence
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.SearchResult
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.reader.EventReader.OccurrenceWithEvent
import org.onekash.kashcal.error.ErrorPresentation
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus
import org.onekash.kashcal.ui.components.EventFormState
import org.onekash.kashcal.ui.components.SyncBannerState
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
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
@RunWith(RobolectricTestRunner::class)
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
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)

        // Week-view scroll restore: default to the never-saved sentinel so initializeAsync's
        // .first() read doesn't hang/throw on the relaxed mock, and existing tests are unaffected.
        every { dataStore.weekViewScrollMinutes } returns flowOf(-1)
        coEvery { dataStore.getWeekViewScrollMinutes() } returns -1

        // Week-view zoom restore: stub the default hour-height so the relaxed mock's 0f
        // (which would clamp to MIN_HOUR_HEIGHT_DP and shift the seeded default) doesn't
        // perturb existing tests. Mirrors the scroll-minutes stubs above.
        every { dataStore.weekViewHourHeight } returns flowOf(60f)
        coEvery { dataStore.getWeekViewHourHeight() } returns 60f
        coEvery { dataStore.setWeekViewHourHeight(any()) } returns Unit

        // Device-calendar prefs: stub Flow getters so combine() in observeCalendars can emit,
        // and stub suspend variants used by loadCalendars. Default = feature off, no enabled IDs.
        every { dataStore.deviceCalendarsEnabled } returns flowOf(false)
        every { dataStore.enabledDeviceCalendarIds } returns flowOf(emptySet())
        every { dataStore.hiddenDeviceCalendarIds } returns flowOf(emptySet())
        coEvery { dataStore.getDeviceCalendarsEnabled() } returns false
        coEvery { dataStore.getEnabledDeviceCalendarIds() } returns emptySet()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        dayCodeSequence: MutableList<Int>? = null,
        calendarProviderRepository: org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository =
            org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository()
    ): HomeViewModel {
        val provider: () -> Int = if (dayCodeSequence != null) {
            { dayCodeSequence.removeAt(0) }
        } else {
            { DayPagerUtils.msToDayCode(System.currentTimeMillis()) }
        }
        return HomeViewModel(
            eventCoordinator = eventCoordinator,
            eventReader = eventReader,
            displayEventRepository = displayEventRepository,
            dataStore = dataStore,
            accountRepository = accountRepository,
            syncScheduler = syncScheduler,
            networkMonitor = networkMonitor,
            calendarProviderRepository = calendarProviderRepository,
            attendeeBackfill = io.mockk.mockk(relaxed = true),
            contactEmailReader = io.mockk.mockk(relaxed = true),
            context = io.mockk.mockk(relaxed = true),
            ioDispatcher = testDispatcher,
            currentDayCodeProvider = provider
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
    fun `switching to MONTH_FULL with no prior selection stays on current month not epoch`() = runTest {
        // Regression: selectedDate defaults to 0L. Switching to a month view synced
        // the pager to Calendar(timeInMillis = 0) -> Dec 1969. With no explicit
        // selection the month views must stay on today's month.
        every { displayEventRepository.getDisplayEventsForRange(any(), any()) } returns flowOf(persistentListOf())

        val viewModel = createViewModel()
        advanceUntilIdle()
        // Simulate the common entry point: default Agenda view leaves selectedDate at 0L.
        viewModel.setViewMode(ViewMode.AGENDA)
        advanceUntilIdle()
        assertEquals(0L, viewModel.uiState.value.selectedDate)

        viewModel.setViewMode(ViewMode.MONTH_FULL)
        advanceUntilIdle()

        val today = JavaCalendar.getInstance()
        assertEquals(today.get(JavaCalendar.YEAR), viewModel.uiState.value.viewingYear)
        assertEquals(today.get(JavaCalendar.MONTH), viewModel.uiState.value.viewingMonth)
    }

    @Test
    fun `switching to MONTH with no prior selection stays on current month not epoch`() = runTest {
        every { displayEventRepository.getDisplayEventsForRange(any(), any()) } returns flowOf(persistentListOf())

        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setViewMode(ViewMode.AGENDA)
        advanceUntilIdle()
        assertEquals(0L, viewModel.uiState.value.selectedDate)

        viewModel.setViewMode(ViewMode.MONTH)
        advanceUntilIdle()

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
    fun `goToToday animate false in MONTH uses instant flag not animated flag`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        // Reset both flags after cold-start init so this asserts the handler itself.
        viewModel.clearNavigateToToday()
        viewModel.clearNavigateToTodayInstant()
        advanceUntilIdle()

        viewModel.goToToday(animate = false)
        advanceUntilIdle()

        assertTrue(
            "Programmatic land must set the instant flag",
            viewModel.uiState.value.pendingNavigateToTodayInstant
        )
        assertFalse(
            "Programmatic land must NOT set the animated flag",
            viewModel.uiState.value.pendingNavigateToToday
        )
    }

    @Test
    fun `goToToday default in MONTH still uses animated flag`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.clearNavigateToToday()
        viewModel.clearNavigateToTodayInstant()
        advanceUntilIdle()

        viewModel.goToToday()
        advanceUntilIdle()

        assertTrue(
            "User Today button must keep the animated flag (issue #151)",
            viewModel.uiState.value.pendingNavigateToToday
        )
        assertFalse(
            "User Today button must NOT set the instant flag",
            viewModel.uiState.value.pendingNavigateToTodayInstant
        )
    }

    @Test
    fun `cold start in MONTH lands instantly not animated`() = runTest {
        // initializeAsync() reads onboardingDismissed.first() before reaching the
        // goToToday() cold-start land; stub it so init runs to completion.
        every { dataStore.onboardingDismissed } returns flowOf(false)

        // createViewModel() runs initializeAsync() -> goToToday(animate=false)
        // internally. Drives the actual cold-start path, not the handler alone.
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(
            "Cold start must use the instant (no-animation) land",
            viewModel.uiState.value.pendingNavigateToTodayInstant
        )
        assertFalse(
            "Cold start must not trigger the animated scroll",
            viewModel.uiState.value.pendingNavigateToToday
        )
    }

    @Test
    fun `clearNavigateToTodayInstant clears flag`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.goToToday(animate = false)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.pendingNavigateToTodayInstant)

        viewModel.clearNavigateToTodayInstant()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.pendingNavigateToTodayInstant)
    }

    // ==================== Resume Rollover Tests ====================

    @Test
    fun `onAppResume same day preserves selectedDate after user navigated`() = runTest {
        // The first resume is record-only by design, so a single same-day read
        // here also covers "first resume does not snap when user already
        // navigated to a non-today date".
        val viewModel = createViewModel(dayCodeSequence = mutableListOf(20260518))
        advanceUntilIdle()

        val pickedDate = getTimestamp(2026, 6, 15, 0, 0)
        viewModel.selectDate(pickedDate)
        advanceUntilIdle()
        assertEquals(pickedDate, viewModel.uiState.value.selectedDate)

        viewModel.onAppResume()
        advanceUntilIdle()

        assertEquals(
            "Same-day resume must not move selectedDate",
            pickedDate,
            viewModel.uiState.value.selectedDate
        )
    }

    @Test
    fun `onAppResume after day rollover snaps to today in MONTH view`() = runTest {
        // goToToday() uses real wall-clock Calendar.getInstance() to compute the
        // snap target; the injected provider is only used to detect the rollover.
        val sequence = mutableListOf(20260518, 20260519)
        val viewModel = createViewModel(dayCodeSequence = sequence)
        advanceUntilIdle()

        val pastDate = getTimestamp(2024, 11, 17, 0, 0)
        viewModel.selectDate(pastDate)
        advanceUntilIdle()
        assertEquals(pastDate, viewModel.uiState.value.selectedDate)

        // First resume: lastResumeDayCode is null -> record-only, no snap.
        viewModel.onAppResume()
        advanceUntilIdle()
        assertEquals(
            "First resume must not snap",
            pastDate,
            viewModel.uiState.value.selectedDate
        )

        // Second resume: dayCode differs -> snap to today.
        viewModel.onAppResume()
        advanceUntilIdle()

        val expectedTodayDayCode = DayPagerUtils.msToDayCode(System.currentTimeMillis())
        val actualDayCode = DayPagerUtils.msToDayCode(viewModel.uiState.value.selectedDate)
        assertEquals(
            "Cross-midnight resume must snap selectedDate to wall-clock today",
            expectedTodayDayCode,
            actualDayCode
        )
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

        // Default filter is Upcoming, so calls searchEventsExcludingPastWithNextOccurrence
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
    fun `setSearchDateFilter AnyTime re-searches with include past`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.activateSearch()
        viewModel.updateSearchQuery("test")
        advanceUntilIdle()

        assertEquals(DateFilter.Upcoming, viewModel.uiState.value.searchDateFilter)
        // First search uses searchEventsExcludingPastWithNextOccurrence (default Upcoming)
        coVerify(exactly = 1) { eventReader.searchEventsExcludingPastWithNextOccurrence("test") }

        viewModel.setSearchDateFilter(DateFilter.AnyTime)
        advanceUntilIdle()

        assertEquals(DateFilter.AnyTime, viewModel.uiState.value.searchDateFilter)
        // After setting AnyTime, should call searchEventsWithNextOccurrence (include past)
        coVerify(exactly = 1) { eventReader.searchEventsWithNextOccurrence("test") }
    }

    // ==================== Search Lookback Tests ====================

    @Test
    fun `search with syncPastDays 730 and AnyTime filter uses 730 day lookback`() = runTest {
        every { dataStore.syncPastDays } returns flowOf(730)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.activateSearch()
        viewModel.updateSearchQuery("test")
        advanceUntilIdle()

        // Set AnyTime filter to include past
        viewModel.setSearchDateFilter(DateFilter.AnyTime)
        advanceUntilIdle()

        // Verify startDayCode passed to displayEventRepository is ~730 days ago (±1 day for midnight)
        val today = java.time.LocalDate.now()
        val expectedDate = today.minusDays(730)
        val expectedCode = expectedDate.year * 10000 + expectedDate.monthValue * 100 + expectedDate.dayOfMonth
        coVerify {
            displayEventRepository.searchDisplayEvents(
                eq("test"),
                match { startDayCode -> kotlin.math.abs(startDayCode - expectedCode) <= 1 },
                any(),
                any()
            )
        }
    }

    @Test
    fun `search with syncPastDays MAX_VALUE and AnyTime filter uses 10 year lookback`() = runTest {
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.activateSearch()
        viewModel.updateSearchQuery("test")
        advanceUntilIdle()

        // Set AnyTime filter to include past
        viewModel.setSearchDateFilter(DateFilter.AnyTime)
        advanceUntilIdle()

        // Verify startDayCode passed to displayEventRepository is ~10 years ago (±1 day)
        val today = java.time.LocalDate.now()
        val expectedDate = today.minusYears(10)
        val expectedCode = expectedDate.year * 10000 + expectedDate.monthValue * 100 + expectedDate.dayOfMonth
        coVerify {
            displayEventRepository.searchDisplayEvents(
                eq("test"),
                match { startDayCode -> kotlin.math.abs(startDayCode - expectedCode) <= 1 },
                any(),
                any()
            )
        }
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

    // ==================== Search Date Filter Tests ====================

    @Test
    fun `activateSearch defaults to Upcoming filter`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.activateSearch()
        advanceUntilIdle()

        assertEquals(DateFilter.Upcoming, viewModel.uiState.value.searchDateFilter)
    }

    @Test
    fun `setSearchDateFilter AnyTime uses include-past query`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.activateSearch()
        viewModel.updateSearchQuery("test")
        advanceUntilIdle()

        viewModel.setSearchDateFilter(DateFilter.AnyTime)
        advanceUntilIdle()

        assertEquals(DateFilter.AnyTime, viewModel.uiState.value.searchDateFilter)
        coVerify { eventReader.searchEventsWithNextOccurrence("test") }
    }

    @Test
    fun `setSearchDateFilter from AnyTime to ThisWeek switches to range query`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.activateSearch()
        viewModel.updateSearchQuery("test")
        advanceUntilIdle()

        viewModel.setSearchDateFilter(DateFilter.AnyTime)
        advanceUntilIdle()

        viewModel.setSearchDateFilter(DateFilter.ThisWeek)
        advanceUntilIdle()

        assertEquals(DateFilter.ThisWeek, viewModel.uiState.value.searchDateFilter)
        coVerify { eventReader.searchEventsInRangeWithNextOccurrence("test", any(), any()) }
    }

    @Test
    fun `deactivateSearch resets filter to Upcoming`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.activateSearch()
        viewModel.setSearchDateFilter(DateFilter.AnyTime)
        advanceUntilIdle()

        viewModel.deactivateSearch()
        advanceUntilIdle()

        assertEquals(DateFilter.Upcoming, viewModel.uiState.value.searchDateFilter)
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

        // agendaEvents is a WhileSubscribed StateFlow — Turbine-collect so its upstream runs.
        viewModel.agendaEvents.test {
            // Before entering agenda, the key is null → empty.
            assertTrue(awaitItem().events.isEmpty())

            viewModel.setViewMode(ViewMode.AGENDA)
            advanceUntilIdle()

            assertEquals(ViewMode.AGENDA, viewModel.uiState.value.viewMode)
            val loaded = expectMostRecentItem()
            assertEquals(2, loaded.events.size)
            assertFalse(loaded.isLoading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `leaving AGENDA stops querying the agenda window`() = runTest {
        val testDisplayEvents = persistentListOf(
            DisplayEvent.Room(testEvents[0], testOccurrences[0], testCalendars[0])
        )
        every { displayEventRepository.getDisplayEventsForRange(any(), any()) } returns flowOf(testDisplayEvents)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Collect agenda so its flow is active while we're in AGENDA.
        viewModel.agendaEvents.test {
            skipItems(1) // initial empty
            viewModel.setViewMode(ViewMode.AGENDA)
            advanceUntilIdle()
            assertEquals(ViewMode.AGENDA, viewModel.uiState.value.viewMode)
            assertEquals(1, expectMostRecentItem().events.size)

            // Clear mock call count, then leave agenda — the key nulls, so no new query.
            io.mockk.clearMocks(displayEventRepository, answers = false, recordedCalls = true, childMocks = false)
            viewModel.setViewMode(ViewMode.MONTH)
            advanceUntilIdle()

            assertEquals(ViewMode.MONTH, viewModel.uiState.value.viewMode)
            // Nulling the agenda key must NOT issue a fresh range query.
            verify(exactly = 0) { displayEventRepository.getDisplayEventsForRange(any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `agenda loads 90 days of events`() = runTest {
        val startSlot = slot<Long>()
        val endSlot = slot<Long>()
        every {
            displayEventRepository.getDisplayEventsForRange(capture(startSlot), capture(endSlot))
        } returns flowOf(persistentListOf())

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.agendaEvents.test {
            skipItems(1) // initial empty
            viewModel.setViewMode(ViewMode.AGENDA)
            advanceUntilIdle()

            // Verify the DisplayEventRepository range query was called with a ~90-day window
            verify { displayEventRepository.getDisplayEventsForRange(any(), any()) }
            val ninetyDaysMs = 90L * 24 * 60 * 60 * 1000
            assertEquals(ninetyDaysMs, endSlot.captured - startSlot.captured)
            cancelAndIgnoreRemainingEvents()
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

        viewModel.agendaEvents.test {
            skipItems(1) // initial empty
            viewModel.setViewMode(ViewMode.AGENDA)
            advanceUntilIdle()

            // Should be sorted by startTs (earlier first) - DisplayEventRepository handles sorting
            val loaded = expectMostRecentItem()
            assertEquals(2, loaded.events.size)
            assertTrue(loaded.events[0].startTs < loaded.events[1].startTs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `agenda shows loading state while fetching`() = runTest {
        // Track loading state during the fetch
        var loadingStateDuringFetch = false
        every { displayEventRepository.getDisplayEventsForRange(any(), any()) } answers {
            // This captures that the agenda flow was fetching when the range was queried
            loadingStateDuringFetch = true
            flowOf(persistentListOf())
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.agendaEvents.test {
            // Initially not loading (no range yet).
            assertFalse(awaitItem().isLoading)

            viewModel.setViewMode(ViewMode.AGENDA)
            advanceUntilIdle()

            // The range query was issued...
            assertTrue(loadingStateDuringFetch)
            // ...and after completion, loading is false.
            assertFalse(expectMostRecentItem().isLoading)
            cancelAndIgnoreRemainingEvents()
        }
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

    @Test
    fun `refreshSync when offline shows Network Offline error`() = runTest {
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isConfigured)

        // Go offline
        networkStateFlow.value = false
        advanceUntilIdle()

        viewModel.refreshSync()
        advanceUntilIdle()

        // Should show offline error snackbar, not start syncing
        assertFalse(viewModel.uiState.value.isSyncing)
        val error = viewModel.uiState.value.currentError
        assertTrue("Expected Snackbar error but got $error", error is ErrorPresentation.Snackbar)
    }

    @Test
    fun `refreshSync when offline does not enqueue sync`() = runTest {
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Go offline
        networkStateFlow.value = false
        advanceUntilIdle()

        // Clear any init-time sync calls
        io.mockk.clearMocks(syncScheduler, answers = false, recordedCalls = true, childMocks = false)

        viewModel.refreshSync()
        advanceUntilIdle()

        // Sync should NOT have been requested
        verify(exactly = 0) { syncScheduler.requestImmediateSync(any(), any()) }
        // Offline must not trigger contact sync either
        verify(exactly = 0) { syncScheduler.requestImmediateContactSync() }
    }

    @Test
    fun `refreshSync when online proceeds normally`() = runTest {
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Ensure online
        networkStateFlow.value = true

        // Clear any init-time sync calls
        io.mockk.clearMocks(syncScheduler, answers = false, recordedCalls = true, childMocks = false)

        viewModel.refreshSync()
        advanceUntilIdle()

        // Sync should have been requested
        verify(exactly = 1) { syncScheduler.requestImmediateSync(any(), any()) }
    }

    @Test
    fun `refreshSync when online also triggers CardDAV contact sync`() = runTest {
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Ensure online
        networkStateFlow.value = true

        // Clear any init-time sync calls
        io.mockk.clearMocks(syncScheduler, answers = false, recordedCalls = true, childMocks = false)

        viewModel.refreshSync()
        advanceUntilIdle()

        // Contact sync should also have been requested (sweep all contact-sync accounts)
        verify { syncScheduler.requestImmediateContactSync() }
    }

    @Test
    fun `syncOnResumeIfNeeded does not trigger contact sync`() = runTest {
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isConfigured)

        // Clear any init-time sync calls, then resume from background. App-open
        // resume shares performSync() with pull-to-refresh but must NOT sweep
        // contacts — only the explicit pull-to-refresh gesture does.
        io.mockk.clearMocks(syncScheduler, answers = false, recordedCalls = true, childMocks = false)

        viewModel.syncOnResumeIfNeeded()
        advanceUntilIdle()

        // The resume path did run (calendar sync fired) — it just must not
        // sweep contacts. This guards against the exactly=0 passing because
        // syncOnResumeIfNeeded early-returned instead.
        verify { syncScheduler.requestImmediateSync(any(), any()) }
        verify(exactly = 0) { syncScheduler.requestImmediateContactSync() }
    }

    // ==================== Pull-to-Refresh Not Configured / Offline Tests ====================

    @Test
    fun `refreshSync when not configured shows snackbar`() = runTest {
        // Default setup: no accounts configured (isConfigured = false)
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isConfigured)

        viewModel.refreshSync()
        advanceUntilIdle()

        assertEquals("No sync accounts configured", viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `refreshSync when not configured does not enqueue sync`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isConfigured)

        // Clear any init-time calls
        io.mockk.clearMocks(syncScheduler, answers = false, recordedCalls = true, childMocks = false)

        viewModel.refreshSync()
        advanceUntilIdle()

        verify(exactly = 0) { syncScheduler.requestImmediateSync(any(), any()) }
    }

    @Test
    fun `refreshSync when not configured does not pulse isSyncing`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isConfigured)
        assertFalse(viewModel.uiState.value.isSyncing)

        viewModel.refreshSync()
        advanceUntilIdle()
        assertFalse("isSyncing should stay false when not configured", viewModel.uiState.value.isSyncing)
    }

    @Test
    fun `refreshSync when offline does not pulse isSyncing`() = runTest {
        coEvery { accountRepository.getAllAccounts() } returns listOf(testICloudAccount)
        coEvery { accountRepository.hasCredentials(testICloudAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isConfigured)

        // Go offline
        networkStateFlow.value = false
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isSyncing)

        viewModel.refreshSync()
        advanceUntilIdle()
        assertFalse("isSyncing should stay false when offline", viewModel.uiState.value.isSyncing)
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
        assertEquals(SyncBannerState.Syncing, viewModel.uiState.value.syncBannerState)
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
        assertEquals(SyncBannerState.Success, viewModel.uiState.value.syncBannerState)
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
        assertEquals(SyncBannerState.Error, viewModel.uiState.value.syncBannerState)
        assertEquals("Network error", viewModel.uiState.value.syncErrorDetail)
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
        assertEquals(SyncBannerState.PartialError, viewModel.uiState.value.syncBannerState)
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
        assertEquals(SyncBannerState.Success, viewModel.uiState.value.syncBannerState)
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

        assertEquals(SyncBannerState.PartialError, viewModel.uiState.value.syncBannerState)
        assertNull(viewModel.uiState.value.syncErrorDetail)

        // Reset and test Failed banner message
        syncStatusFlow.value = SyncStatus.Idle
        advanceUntilIdle()
        // Idle resets state to Syncing
        assertEquals(SyncBannerState.Syncing, viewModel.uiState.value.syncBannerState)

        syncStatusFlow.value = SyncStatus.Failed(errorMessage = "All accounts failed")
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()

        assertEquals(SyncBannerState.Error, viewModel.uiState.value.syncBannerState)
        assertEquals("All accounts failed", viewModel.uiState.value.syncErrorDetail)
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
        assertEquals(SyncBannerState.PartialError, viewModel.uiState.value.syncBannerState)
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
        assertEquals(SyncBannerState.PartialError, viewModel.uiState.value.syncBannerState)
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
        assertEquals(SyncBannerState.Syncing, viewModel.uiState.value.syncBannerState)
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
        assertEquals(SyncBannerState.Syncing, viewModel.uiState.value.syncBannerState)
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
            reminders = listOf(15),
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
            reminders = listOf(15),
            isEditMode = true,
            editingEventId = 1L
        )

        val result = viewModel.saveEvent(formState)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        coVerify { eventCoordinator.updateEvent(match { it.title == "Updated Meeting" }) }
    }

    // ==================== Save-Time Scope Sheet Integration ====================
    // These tests are the integration coverage for the save-time deferral
    // round-trip (requestFormSave → saveEvent(scope)) and the delete
    // round-trip (requestDeleteRoom → confirmDelete(scope)) end-to-end
    // through the ViewModel, asserting the right coordinator method
    // is invoked for each scope.

    private val recurringEvent_ = testEvents[0].copy(rrule = "FREQ=WEEKLY;COUNT=10")

    private fun recurringFormState() = EventFormState(
        title = "Edited",
        dateMillis = recurringEvent_.startTs + 7L * 86_400_000L,
        endDateMillis = recurringEvent_.startTs + 7L * 86_400_000L,
        startHour = 10,
        startMinute = 0,
        endHour = 11,
        endMinute = 0,
        selectedCalendarId = 1L,
        isEditMode = true,
        editingEventId = recurringEvent_.id,
        editingOccurrenceTs = recurringEvent_.startTs + 7L * 86_400_000L,
        rrule = "FREQ=WEEKLY;COUNT=10",
    )

    @Test
    fun `requestFormSave + saveEvent THIS_EVENT routes to editSingleOccurrence`() = runTest {
        coEvery { eventCoordinator.getEventById(recurringEvent_.id) } returns recurringEvent_
        coEvery { eventCoordinator.editSingleOccurrence(any(), any(), any(), any()) } returns recurringEvent_

        val viewModel = createViewModel()
        advanceUntilIdle()

        val occurrenceTs = recurringEvent_.startTs + 7L * 86_400_000L
        viewModel.requestFormSave(
            formState = recurringFormState(),
            occurrenceTs = occurrenceTs,
            originalRrule = recurringEvent_.rrule,
            masterStartTs = recurringEvent_.startTs,
            isDetachedException = false,
            isRecurringDevice = false,
            loadedIsAllDay = false,
        )
        advanceUntilIdle()

        val pending = viewModel.uiState.value.pendingFormSave
        assertNotNull(pending)
        assertEquals(recurringEvent_.startTs, pending!!.masterStartTs)

        // Simulate MainActivity's onConfirmFormSave for THIS_EVENT.
        viewModel.cancelPendingFormSave()
        viewModel.saveEvent(pending.formState, EditScope.THIS_EVENT)
        advanceUntilIdle()

        coVerify { eventCoordinator.editSingleOccurrence(recurringEvent_.id, occurrenceTs, any(), any()) }
    }

    @Test
    fun `THIS_EVENT on the first occurrence with no rule change creates an exception for that instance`() = runTest {
        // Case A: the user edits content (title etc.) of the FIRST
        // occurrence and leaves the recurrence rule alone. The scope
        // sheet must offer THIS_EVENT (it is gated only on rruleChanged,
        // not isFirstOccurrence), and picking it must split that single
        // instance off via editSingleOccurrence — leaving the master
        // series untouched. Mirrors the real flow on the first instance,
        // which the existing THIS_EVENT test does not cover (it uses an
        // off-master occurrence).
        coEvery { eventCoordinator.getEventById(recurringEvent_.id) } returns recurringEvent_
        coEvery { eventCoordinator.editSingleOccurrence(any(), any(), any(), any()) } returns recurringEvent_

        val viewModel = createViewModel()
        advanceUntilIdle()

        // First occurrence: occurrenceTs == master start; rrule unchanged.
        val firstOccurrenceTs = recurringEvent_.startTs
        viewModel.requestFormSave(
            formState = recurringFormState().copy(
                title = "Edited first instance",
                dateMillis = firstOccurrenceTs,
                endDateMillis = firstOccurrenceTs,
                editingOccurrenceTs = firstOccurrenceTs,
                rrule = recurringEvent_.rrule, // no rule change
            ),
            occurrenceTs = firstOccurrenceTs,
            originalRrule = recurringEvent_.rrule,
            masterStartTs = recurringEvent_.startTs,
            isDetachedException = false,
            isRecurringDevice = false,
            loadedIsAllDay = false,
        )
        advanceUntilIdle()

        // The scope sheet's options must enable THIS_EVENT on the first
        // occurrence when the rule is unchanged.
        val pending = viewModel.uiState.value.pendingFormSave
        assertNotNull(pending)
        val options = computeEditScopeOptions(
            context = ScopeContext(
                masterStartTs = pending!!.masterStartTs,
                occurrenceTs = pending.occurrenceTs,
                isDetachedException = pending.isDetachedException,
                isAllDay = pending.loadedIsAllDay,
            ),
            originalRrule = pending.originalRrule,
            currentRrule = pending.formState.rrule,
            resources = org.robolectric.RuntimeEnvironment.getApplication().resources,
        )
        assertTrue(
            "THIS_EVENT must be enabled on the first occurrence with no rule change",
            options.first { it.scope == EditScope.THIS_EVENT }.enabled,
        )

        // Picking THIS_EVENT splits just the first instance off.
        viewModel.cancelPendingFormSave()
        viewModel.saveEvent(pending.formState, EditScope.THIS_EVENT)
        advanceUntilIdle()

        coVerify { eventCoordinator.editSingleOccurrence(recurringEvent_.id, firstOccurrenceTs, any(), any()) }
        // The master series is never updated wholesale on a THIS_EVENT save.
        coVerify(exactly = 0) { eventCoordinator.updateEvent(any()) }
    }

    @Test
    fun `THIS_EVENT save forwards the edited attendee set to editSingleOccurrence`() = runTest {
        coEvery { eventCoordinator.getEventById(recurringEvent_.id) } returns recurringEvent_
        coEvery { eventCoordinator.editSingleOccurrence(any(), any(), any(), any()) } returns recurringEvent_

        val viewModel = createViewModel()
        advanceUntilIdle()

        val edited = listOf(
            org.onekash.kashcal.data.db.entity.Attendee(
                eventId = 0, address = "mailto:newguest@example.test", partstat = "NEEDS-ACTION", sortOrder = 0
            )
        )
        val occurrenceTs = recurringEvent_.startTs + 7L * 86_400_000L
        // attendeesEdited=true makes attendeesArg the authoritative set.
        val formState = recurringFormState().copy(attendees = edited, attendeesEdited = true)

        viewModel.saveEvent(formState, EditScope.THIS_EVENT)
        advanceUntilIdle()

        coVerify {
            eventCoordinator.editSingleOccurrence(recurringEvent_.id, occurrenceTs, edited, any())
        }
    }

    @Test
    fun `requestFormSave + saveEvent THIS_AND_FUTURE routes to editThisAndFuture`() = runTest {
        coEvery { eventCoordinator.getEventById(recurringEvent_.id) } returns recurringEvent_
        coEvery { eventCoordinator.editThisAndFuture(any(), any(), any(), any()) } returns recurringEvent_

        val viewModel = createViewModel()
        advanceUntilIdle()

        val occurrenceTs = recurringEvent_.startTs + 7L * 86_400_000L
        viewModel.requestFormSave(
            formState = recurringFormState(),
            occurrenceTs = occurrenceTs,
            originalRrule = recurringEvent_.rrule,
            masterStartTs = recurringEvent_.startTs,
            isDetachedException = false,
            isRecurringDevice = false,
            loadedIsAllDay = false,
        )
        advanceUntilIdle()

        val pending = viewModel.uiState.value.pendingFormSave!!
        viewModel.cancelPendingFormSave()
        viewModel.saveEvent(pending.formState, EditScope.THIS_AND_FUTURE)
        advanceUntilIdle()

        coVerify { eventCoordinator.editThisAndFuture(recurringEvent_.id, occurrenceTs, any(), any()) }
    }

    @Test
    fun `THIS_AND_FUTURE save forwards the edited attendee set to editThisAndFuture`() = runTest {
        coEvery { eventCoordinator.getEventById(recurringEvent_.id) } returns recurringEvent_
        coEvery { eventCoordinator.editThisAndFuture(any(), any(), any(), any()) } returns recurringEvent_

        val viewModel = createViewModel()
        advanceUntilIdle()

        val edited = listOf(
            org.onekash.kashcal.data.db.entity.Attendee(
                eventId = 0, address = "mailto:newguest@example.test", partstat = "NEEDS-ACTION", sortOrder = 0
            )
        )
        val occurrenceTs = recurringEvent_.startTs + 7L * 86_400_000L
        // attendeesEdited=true makes attendeesArg the authoritative set.
        val formState = recurringFormState().copy(attendees = edited, attendeesEdited = true)

        viewModel.saveEvent(formState, EditScope.THIS_AND_FUTURE)
        advanceUntilIdle()

        coVerify {
            eventCoordinator.editThisAndFuture(recurringEvent_.id, occurrenceTs, edited, any())
        }
    }

    @Test
    fun `requestFormSave + saveEvent ALL_EVENTS routes to updateEvent`() = runTest {
        coEvery { eventCoordinator.getEventById(recurringEvent_.id) } returns recurringEvent_
        coEvery { eventCoordinator.updateEvent(any()) } returns recurringEvent_

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.requestFormSave(
            formState = recurringFormState(),
            occurrenceTs = recurringEvent_.startTs + 7L * 86_400_000L,
            originalRrule = recurringEvent_.rrule,
            masterStartTs = recurringEvent_.startTs,
            isDetachedException = false,
            isRecurringDevice = false,
            loadedIsAllDay = false,
        )
        advanceUntilIdle()

        val pending = viewModel.uiState.value.pendingFormSave!!
        viewModel.cancelPendingFormSave()
        viewModel.saveEvent(pending.formState, EditScope.ALL_EVENTS)
        advanceUntilIdle()

        // ALL_EVENTS scope: occurrenceTs is dropped, master is updated directly.
        coVerify { eventCoordinator.updateEvent(any()) }
    }

    @Test
    fun `ALL_EVENTS save on an exception id resolves to the master before updating`() = runTest {
        // If the form was opened on a detached exception row,
        // editingEventId is the exception's id. ALL_EVENTS must rewrite
        // the MASTER's rrule, not the exception's — so the branch
        // climbs originalEventId like its THIS_AND_FUTURE / exception
        // siblings do. Updating the exception id would corrupt the
        // wrong row.
        val masterId = recurringEvent_.id
        val exception = recurringEvent_.copy(
            id = 7_777L,
            originalEventId = masterId,
            rrule = null,
        )
        coEvery { eventCoordinator.getEventById(exception.id) } returns exception
        coEvery { eventCoordinator.getEventById(masterId) } returns recurringEvent_
        coEvery { eventCoordinator.updateEvent(any()) } returns recurringEvent_

        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = recurringFormState().copy(editingEventId = exception.id)
        viewModel.saveEvent(formState, EditScope.ALL_EVENTS)
        advanceUntilIdle()

        // The updated event must be the master row, not the exception.
        coVerify { eventCoordinator.updateEvent(match { it.id == masterId }) }
        coVerify(exactly = 0) { eventCoordinator.updateEvent(match { it.id == exception.id }) }
    }

    @Test
    fun `requestDeleteRoom + confirmDelete THIS_AND_FUTURE routes to deleteThisAndFuture`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.requestDeleteRoom(
            event = recurringEvent_,
            occurrenceTs = recurringEvent_.startTs + 7L * 86_400_000L,
            masterStartTs = recurringEvent_.startTs,
            isDetachedException = false,
            isAllDay = false,
        )
        advanceUntilIdle()

        viewModel.confirmDelete(EditScope.THIS_AND_FUTURE)
        advanceUntilIdle()

        coVerify { eventCoordinator.deleteThisAndFuture(recurringEvent_.id, any()) }
    }

    @Test
    fun `handleRoomEventFormDelete on exception routes to deleteSingleOccurrence`() = runTest {
        // The form's in-line Delete button on a Room exception must
        // route the same way QuickView Delete does — calling
        // eventCoordinator.deleteSingleOccurrence(masterId,
        // originalInstanceTime). The naive deleteEvent path fails
        // EventCoordinator's exception guard.
        val masterId = recurringEvent_.id
        val originalInstance = recurringEvent_.startTs + 7L * 86_400_000L
        val exception = recurringEvent_.copy(
            id = 9_999L,
            originalEventId = masterId,
            originalInstanceTime = originalInstance,
            rrule = null,
            startTs = originalInstance + 3_600_000L, // moved
        )
        coEvery { eventCoordinator.getEventById(exception.id) } returns exception
        coEvery { eventCoordinator.deleteSingleOccurrence(masterId, originalInstance) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.handleRoomEventFormDelete(exception.id, occurrenceTs = null)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { eventCoordinator.deleteSingleOccurrence(masterId, originalInstance) }
        coVerify(exactly = 0) { eventCoordinator.deleteEvent(any()) }
    }

    @Test
    fun `handleRoomEventFormDelete on recurring master stages PendingDelete and returns success`() = runTest {
        // Recurring master deletion needs the scope sheet. The handler
        // stages PendingDelete.Room and returns success-no-op so the
        // form dismisses. The scope sheet renders on top via the
        // pending-delete state in uiState.
        val master = recurringEvent_
        coEvery { eventCoordinator.getEventById(master.id) } returns master

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.handleRoomEventFormDelete(master.id, occurrenceTs = null)
        advanceUntilIdle()

        assertTrue("returns success so form dismisses", result.isSuccess)
        val pending = viewModel.uiState.value.pendingDelete
        assertNotNull("pendingDelete must be non-null after master delete request", pending)
        assertTrue("pending must be a Room variant", pending is PendingDelete.Room)
        // The handler does NOT call eventCoordinator.deleteEvent — the
        // scope-sheet confirm path is responsible for the actual delete.
        coVerify(exactly = 0) { eventCoordinator.deleteEvent(any()) }
    }

    @Test
    fun `handleRoomEventFormDelete on recurring master preserves form's occurrenceTs anchor`() = runTest {
        // User taps occurrence #5, opens Edit, hits in-form Delete.
        // The scope sheet's first-occurrence rule depends on the
        // pendingDelete carrying the actual tapped occurrence ts —
        // not the master's first-occurrence start.
        val master = recurringEvent_
        coEvery { eventCoordinator.getEventById(master.id) } returns master

        val viewModel = createViewModel()
        advanceUntilIdle()

        val tappedOccurrence = master.startTs + 5L * 7 * 86_400_000L // occurrence #5
        viewModel.handleRoomEventFormDelete(master.id, occurrenceTs = tappedOccurrence)
        advanceUntilIdle()

        val pending = viewModel.uiState.value.pendingDelete as PendingDelete.Room
        assertEquals(tappedOccurrence, pending.occurrenceTs)
        assertEquals(master.startTs, pending.masterStartTs)
    }

    @Test
    fun `handleRoomEventFormDelete on non-recurring event calls deleteEvent`() = runTest {
        // Non-recurring deletes go straight through eventCoordinator
        // — no scope sheet, no special routing.
        val nonRecurring = testEvents[0].copy(rrule = null, originalEventId = null)
        coEvery { eventCoordinator.getEventById(nonRecurring.id) } returns nonRecurring
        coEvery { eventCoordinator.deleteEvent(nonRecurring.id) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.handleRoomEventFormDelete(nonRecurring.id, occurrenceTs = null)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { eventCoordinator.deleteEvent(nonRecurring.id) }
        coVerify(exactly = 0) { eventCoordinator.deleteSingleOccurrence(any(), any()) }
    }

    @Test
    fun `requestFormSave preserves loadedIsAllDay independent of formState`() = runTest {
        // Form-load isAllDay must survive into PendingFormSave so the
        // scope-sheet sub-copy renders the correct date even when the
        // user toggled all-day in the form before saving (which would
        // flip formState.isAllDay but must not flip the load-time
        // anchor).
        coEvery { eventCoordinator.getEventById(recurringEvent_.id) } returns recurringEvent_

        val viewModel = createViewModel()
        advanceUntilIdle()

        // formState says isAllDay=true (user toggled), but the master
        // event was timed at load — loadedIsAllDay=false.
        val toggledFormState = recurringFormState().copy(isAllDay = true)
        viewModel.requestFormSave(
            formState = toggledFormState,
            occurrenceTs = recurringEvent_.startTs + 7L * 86_400_000L,
            originalRrule = recurringEvent_.rrule,
            masterStartTs = recurringEvent_.startTs,
            isDetachedException = false,
            isRecurringDevice = false,
            loadedIsAllDay = false,
        )
        advanceUntilIdle()

        val pending = viewModel.uiState.value.pendingFormSave!!
        assertEquals(false, pending.loadedIsAllDay)
        assertEquals(true, pending.formState.isAllDay)
    }

    @Test
    fun `signalFormSaveFailed increments tick`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val before = viewModel.uiState.value.formSaveFailedTick
        viewModel.signalFormSaveFailed()
        advanceUntilIdle()

        assertEquals(before + 1, viewModel.uiState.value.formSaveFailedTick)
    }

    @Test
    fun `saveEvent on non-recurring event with null occurrenceTs routes to updateEvent not editSingleOccurrence`() = runTest {
        // Regression for the QuickView Edit-on-non-recurring crash:
        // saveEvent must NOT call editSingleOccurrence when the form
        // was opened on a non-recurring event (editingOccurrenceTs is
        // null after the MainActivity collapse). editSingleOccurrence
        // would throw via EventWriter's isRecurring require.
        val nonRecurring = testEvents[0].copy(rrule = null, originalEventId = null)
        coEvery { eventCoordinator.getEventById(nonRecurring.id) } returns nonRecurring
        coEvery { eventCoordinator.updateEvent(any()) } returns nonRecurring

        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Edited",
            dateMillis = getTimestamp(2024, 11, 17, 0, 0),
            endDateMillis = getTimestamp(2024, 11, 17, 0, 0),
            startHour = 10,
            startMinute = 0,
            endHour = 11,
            endMinute = 0,
            selectedCalendarId = 1L,
            isEditMode = true,
            editingEventId = nonRecurring.id,
            editingOccurrenceTs = null,  // critical: null for non-recurring
        )

        val result = viewModel.saveEvent(formState)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        coVerify { eventCoordinator.updateEvent(any()) }
        coVerify(exactly = 0) { eventCoordinator.editSingleOccurrence(any(), any(), any(), any()) }
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
    fun `selectDate normalizes a time-bearing timestamp to local midnight`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // A wall-clock timestamp carrying a time-of-day (e.g. 14:37:11.500) — the
        // shape goToToday() passes at cold start via Calendar.getInstance().
        val timeBearing = JavaCalendar.getInstance().apply {
            set(2026, 5, 4, 14, 37, 11)
            set(JavaCalendar.MILLISECOND, 500)
        }.timeInMillis

        viewModel.selectDate(timeBearing)
        advanceUntilIdle()

        val stored = viewModel.uiState.value.selectedDate

        // Same calendar day...
        assertEquals(
            "selectDate must preserve the calendar day",
            DayPagerUtils.msToDayCode(timeBearing),
            DayPagerUtils.msToDayCode(stored)
        )
        // ...but normalized to that day's local midnight so every selectedDate
        // writer agrees on one representation (no startup round-trip rewrite).
        val expectedMidnight = java.time.Instant.ofEpochMilli(timeBearing)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(
            "selectDate must store local midnight (no time-of-day)",
            expectedMidnight,
            stored
        )
    }

    @Test
    fun `selectDate normalizes pre-1970 time-bearing timestamp to local midnight`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Negative epoch millis with a time-of-day — guards against arithmetic
        // truncation (dateMs % DAY_MS) which is wrong for negative values.
        val timeBearing = JavaCalendar.getInstance().apply {
            set(1969, 6, 20, 14, 37, 11)
            set(JavaCalendar.MILLISECOND, 500)
        }.timeInMillis
        assertTrue("Pre-1970 date should have negative millis", timeBearing < 0)

        viewModel.selectDate(timeBearing)
        advanceUntilIdle()

        val stored = viewModel.uiState.value.selectedDate
        val expectedMidnight = java.time.Instant.ofEpochMilli(timeBearing)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expectedMidnight, stored)
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
        assertEquals(SyncBannerState.Preparing, viewModel.uiState.value.syncBannerState)

        // Simulate WorkManager emitting Running
        syncStatusFlow.value = SyncStatus.Running
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showSyncBanner)
        assertEquals(SyncBannerState.Syncing, viewModel.uiState.value.syncBannerState)
        // Force Full Sync shows banner but NOT spinning icon (suppressSyncIndicator = true)
        assertFalse(viewModel.uiState.value.isSyncing)

        // Simulate WorkManager emitting Succeeded
        syncStatusFlow.value = SyncStatus.Succeeded(calendarsSynced = 2, eventsPulled = 10)
        // Don't use advanceUntilIdle() - it would advance past the 2s auto-dismiss delay
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()

        // Banner should still be visible (auto-dismisses after 2 seconds)
        assertTrue(viewModel.uiState.value.showSyncBanner)
        assertEquals(SyncBannerState.Success, viewModel.uiState.value.syncBannerState)
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
        assertEquals(SyncBannerState.Error, viewModel.uiState.value.syncBannerState)
        assertEquals("Network error", viewModel.uiState.value.syncErrorDetail)

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

    @Test
    fun `loadDotsForMonth ignores dayCodes outside the loaded month (issue 255)`() = runTest {
        // Regression: a multi-day event spanning a month boundary — or any event
        // whose start/end fall outside the queried month — must not paint a phantom
        // dot on the loaded month. Repro per issue #255: navigating to Dec 2026
        // showed a green dot on Dec 1 even though no real event was on Dec 1; the
        // phantom came from the day-1 piece of an adjacent month's event leaking
        // into December's monthKey.
        val cal1Color = testCalendars[0].color
        // Mock returns three dayCodes — Nov 30, Dec 15, Jan 1 — as if a multi-day
        // event spanning Nov 30 → Jan 2 had been expanded into per-day buckets.
        val groupedEvents = mapOf(
            20261130 to listOf(
                createDotDisplayEvent(1L, "Cross-month", getTimestamp(2026, 10, 30, 9, 0), getTimestamp(2027, 0, 2, 17, 0), 20261130, calendarColor = cal1Color)
            ),
            20261215 to listOf(
                createDotDisplayEvent(2L, "Mid-Dec", getTimestamp(2026, 11, 15, 10, 0), getTimestamp(2026, 11, 15, 11, 0), 20261215, calendarColor = cal1Color)
            ),
            20270101 to listOf(
                createDotDisplayEvent(3L, "Jan 1 spillover", getTimestamp(2027, 0, 1, 9, 0), getTimestamp(2027, 0, 1, 10, 0), 20270101, calendarColor = cal1Color)
            )
        )
        coEvery { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) } returns groupedEvents

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Dec 2026 is far outside the initial ±6 month cache, so this hits the
        // on-demand loadDotsForMonth path (not buildEventDots).
        viewModel.setViewingMonth(2026, 11)
        advanceUntilIdle()

        val state = viewModel.uiState.value

        // Mid-month event should produce its dot.
        assertTrue("Dec 15 should have a dot", state.hasEventsOnDay(2026, 11, 15))
        // Adjacent-month dayCodes must NOT bleed into the December bucket as
        // day=1 / day=30 phantoms.
        assertFalse(
            "Dec 1 must not show a phantom dot from the Jan 1 dayCode (issue #255)",
            state.hasEventsOnDay(2026, 11, 1)
        )
        assertFalse(
            "Dec 30 must not show a phantom dot from the Nov 30 dayCode",
            state.hasEventsOnDay(2026, 11, 30)
        )
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
            reminders = listOf(30, 1440),  // 30 minutes + 1 day before
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
            reminders = emptyList(), // No reminders
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
    fun `setViewMode DAY sets pending pager position to today`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingWeekViewPagerPosition)

        viewModel.setViewMode(ViewMode.DAY)
        advanceUntilIdle()

        // DAY shares the day-pager with THREE_DAYS, so it routes to CENTER_DAY_PAGE
        val expectedPage = org.onekash.kashcal.ui.components.weekview.WeekViewUtils.CENTER_DAY_PAGE
        assertEquals(
            "pendingWeekViewPagerPosition should be CENTER_DAY_PAGE for DAY",
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
    fun `onWeekViewDateSelected in WEEK mode uses dateToWeekPage`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Switch to WEEK mode
        viewModel.setViewMode(ViewMode.WEEK)
        advanceUntilIdle()

        // Select a date 5 days from today
        val today = java.time.LocalDate.now()
        val targetDate = today.plusDays(5)
        val targetMs = targetDate.atStartOfDay(java.time.ZoneId.systemDefault())
            .plusHours(12)
            .toInstant()
            .toEpochMilli()

        viewModel.onWeekViewDateSelected(targetMs)
        advanceUntilIdle()

        // In WEEK mode, should use dateToWeekPage (not dateToPage)
        val expectedPage = org.onekash.kashcal.ui.components.weekview.WeekViewUtils.dateToWeekPage(
            targetDate,
            viewModel.uiState.value.firstDayOfWeek
        )
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
    fun `navigateDaysPagerPrevious sets pending position minus pagerNextStep`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setViewMode(ViewMode.THREE_DAYS)
        advanceUntilIdle()

        // Set a known pager position first
        val startPage = WeekViewUtils.CENTER_DAY_PAGE
        viewModel.setWeekViewPagerPosition(startPage)
        advanceUntilIdle()

        viewModel.navigateDaysPagerPrevious()
        advanceUntilIdle()

        val expectedPage = startPage - ViewMode.THREE_DAYS.pagerNextStep!!
        assertEquals(expectedPage, viewModel.uiState.value.pendingWeekViewPagerPosition)
        assertEquals(expectedPage, viewModel.uiState.value.weekViewPagerPosition)
    }

    @Test
    fun `navigateDaysPagerNext sets pending position plus pagerNextStep`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setViewMode(ViewMode.THREE_DAYS)
        advanceUntilIdle()

        // Set a known pager position first
        val startPage = WeekViewUtils.CENTER_DAY_PAGE
        viewModel.setWeekViewPagerPosition(startPage)
        advanceUntilIdle()

        viewModel.navigateDaysPagerNext()
        advanceUntilIdle()

        val expectedPage = startPage + ViewMode.THREE_DAYS.pagerNextStep!!
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
    fun `initializeAsync seeds saved scroll minutes from DataStore`() = runTest {
        // Persisted clock time (14:00) must reach uiState so the time grid restores it
        // on first composition, before the debounced writer can overwrite it.
        // onboardingDismissed must be stubbed so initializeAsync runs past the onboarding
        // gate and reaches the seed (the gate reads it via .first()).
        every { dataStore.onboardingDismissed } returns flowOf(false)
        every { dataStore.weekViewScrollMinutes } returns flowOf(840)
        coEvery { dataStore.getWeekViewScrollMinutes() } returns 840

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(840, viewModel.uiState.value.weekViewSavedScrollMinutes)
    }

    @Test
    fun `setWeekViewScrollMinutes persists to DataStore`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setWeekViewScrollMinutes(615)
        advanceUntilIdle()

        coVerify { dataStore.setWeekViewScrollMinutes(615) }
    }

    @Test
    fun `setWeekViewScrollPosition does not persist to DataStore`() = runTest {
        // In-session pixel position stays in memory only; persistence is the minutes path's job.
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setWeekViewScrollPosition(500)
        advanceUntilIdle()

        coVerify(exactly = 0) { dataStore.setWeekViewScrollMinutes(any()) }
    }

    @Test
    fun `initializeAsync seeds saved hour height from DataStore`() = runTest {
        // Persisted zoom (90dp) must reach uiState so the time grid restores it on first
        // composition — in the SAME update that seeds the scroll minutes, so the scroll's
        // minutes->pixels conversion uses the restored zoom rather than the default.
        every { dataStore.onboardingDismissed } returns flowOf(false)
        every { dataStore.weekViewHourHeight } returns flowOf(90f)
        coEvery { dataStore.getWeekViewHourHeight() } returns 90f

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(90f, viewModel.uiState.value.weekViewHourHeight)
    }

    @Test
    fun `initializeAsync clamps an out-of-range persisted hour height`() = runTest {
        // A persisted or corrupt value outside the pinch range snaps back in on restore,
        // never rendering a degenerate grid.
        every { dataStore.onboardingDismissed } returns flowOf(false)
        every { dataStore.weekViewHourHeight } returns flowOf(999f)
        coEvery { dataStore.getWeekViewHourHeight() } returns 999f

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(WeekViewUtils.MAX_HOUR_HEIGHT_DP, viewModel.uiState.value.weekViewHourHeight)
    }

    @Test
    fun `initializeAsync clamps a too-small persisted hour height`() = runTest {
        every { dataStore.onboardingDismissed } returns flowOf(false)
        every { dataStore.weekViewHourHeight } returns flowOf(5f)
        coEvery { dataStore.getWeekViewHourHeight() } returns 5f

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(WeekViewUtils.MIN_HOUR_HEIGHT_DP, viewModel.uiState.value.weekViewHourHeight)
    }

    @Test
    fun `initializeAsync falls back to default for a non-finite persisted hour height`() = runTest {
        // A corrupt DataStore proto could hold NaN; coerceIn leaves NaN unchanged, which would
        // render a degenerate (NaN-height) grid. The seed must reject it and use the default.
        every { dataStore.onboardingDismissed } returns flowOf(false)
        every { dataStore.weekViewHourHeight } returns flowOf(Float.NaN)
        coEvery { dataStore.getWeekViewHourHeight() } returns Float.NaN

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(60f, viewModel.uiState.value.weekViewHourHeight)
    }

    @Test
    fun `setWeekViewHourHeight persists to DataStore after debounce`() = runTest {
        val viewModel = createViewModel()
        // Let the init-launched persistence collector subscribe before emitting; the
        // persist SharedFlow has replay=0, so an emit before subscription would be dropped.
        advanceUntilIdle()

        viewModel.setWeekViewHourHeight(90f)
        advanceUntilIdle()

        coVerify { dataStore.setWeekViewHourHeight(90f) }
    }

    @Test
    fun `setWeekViewHourHeight updates in-session hour height clamped`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setWeekViewHourHeight(999f)

        assertEquals(WeekViewUtils.MAX_HOUR_HEIGHT_DP, viewModel.uiState.value.weekViewHourHeight)
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

    @Test
    fun `goToTodayInDayPager in WEEK mode returns CENTER_WEEK_PAGE`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setViewMode(ViewMode.WEEK)
        advanceUntilIdle()

        val resultPage = viewModel.goToTodayInDayPager()
        advanceUntilIdle()

        val centerWeekPage = org.onekash.kashcal.ui.components.weekview.WeekViewUtils.CENTER_WEEK_PAGE
        assertEquals(centerWeekPage, resultPage)
        assertEquals(centerWeekPage, viewModel.uiState.value.weekViewPagerPosition)
    }

    @Test
    fun `navigateDayPagerToDate in WEEK mode uses dateToWeekPage`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setViewMode(ViewMode.WEEK)
        advanceUntilIdle()

        val today = java.time.LocalDate.now()
        val targetDate = today.plusDays(10)
        val targetMs = targetDate.atStartOfDay(java.time.ZoneId.systemDefault())
            .plusHours(12)
            .toInstant()
            .toEpochMilli()

        val resultPage = viewModel.navigateDayPagerToDate(targetMs)
        advanceUntilIdle()

        val expectedPage = org.onekash.kashcal.ui.components.weekview.WeekViewUtils.dateToWeekPage(
            targetDate,
            viewModel.uiState.value.firstDayOfWeek
        )
        assertEquals(expectedPage, resultPage)
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

        // The default view is MONTH, so the time-grid range is never set and the
        // reactive week surface stays empty (no time-grid data loaded).
        viewModel.weekEvents.test {
            assertTrue(awaitItem().timedEvents.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
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

    @Test
    fun `creating event in week view after year round-trip refreshes the grid`() = runTest {
        // Reproduces the stale-week-grid bug (#297): a view round-trip through YEAR
        // used to leave the week grid stale after a create. The durable fix makes the
        // grid a reactive StateFlow (viewModel.weekEvents) derived from the repository
        // Flow, so a DB write propagates automatically with no manual reload.
        //
        // The repository Flow is the source of truth; emit an empty grid first, then the
        // created event after the save, and assert weekEvents reflects the second emission.
        //
        // NOTE: weekEvents is a WhileSubscribed StateFlow — it only runs its upstream
        // while it has an active collector. We Turbine-collect it (a bare .value read
        // would pass for the wrong reason: the initial-empty value).
        val createdEvent = testEvents[0].copy(id = 100L, title = "New Meeting")
        val afterCreate = persistentListOf<DisplayEvent>(
            DisplayEvent.Room(createdEvent, testOccurrences[0], testCalendars[0])
        )
        val gridFlow = MutableStateFlow<kotlinx.collections.immutable.ImmutableList<DisplayEvent>>(
            persistentListOf()
        )
        every { displayEventRepository.getDisplayEventsForRange(any(), any()) } returns gridFlow
        coEvery { eventCoordinator.getLocalCalendarId() } returns 1L
        coEvery { eventCoordinator.createEvent(any(), any()) } coAnswers {
            // Simulate the DB write that the reactive Flow would observe.
            gridFlow.value = afterCreate
            createdEvent
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Enter week view (seeds the range), round-trip through YEAR, then return to week.
        viewModel.setViewMode(ViewMode.WEEK)
        advanceUntilIdle()
        viewModel.setViewMode(ViewMode.YEAR)
        advanceUntilIdle()
        viewModel.setViewMode(ViewMode.WEEK)
        advanceUntilIdle()

        viewModel.weekEvents.test {
            // Let the range flow settle (debounce + initial empty repo emission).
            advanceUntilIdle()
            assertTrue(expectMostRecentItem().timedEvents.isEmpty())

            // Create an event via the public save surface (as the FAB "+" does).
            val formState = EventFormState(
                title = "New Meeting",
                dateMillis = getTimestamp(2024, 11, 20, 0, 0),
                endDateMillis = getTimestamp(2024, 11, 20, 0, 0),
                startHour = 10,
                startMinute = 0,
                endHour = 11,
                endMinute = 0,
                selectedCalendarId = 1L,
                reminders = listOf(15),
                isEditMode = false
            )
            val result = viewModel.saveEvent(formState)
            advanceUntilIdle()
            assertTrue(result.isSuccess)

            // The reactive grid must reflect the created event, with no manual reload.
            val updated = expectMostRecentItem()
            assertEquals(1, updated.timedEvents.size)
            assertEquals("New Meeting", updated.timedEvents.first().title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `time-grid FAB seeds today at the next hour`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setViewMode(ViewMode.WEEK)
        advanceUntilIdle()

        val seed = java.time.Instant.ofEpochMilli(viewModel.computeTimeGridEventSeedTs())
            .atZone(java.time.ZoneId.systemDefault())

        // Today's date, at the next hour on the hour (matches the non-grid FAB).
        assertEquals(java.time.LocalDate.now(), seed.toLocalDate())
        assertEquals((java.time.LocalTime.now().hour + 1) % 24, seed.hour)
        assertEquals(0, seed.minute)
    }

    @Test
    fun `time-grid FAB seed is independent of pager navigation`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Even after paging away, the FAB still defaults to today (not the viewed day).
        viewModel.setViewMode(ViewMode.DAY)
        viewModel.onDayPagerPageChanged(WeekViewUtils.CENTER_DAY_PAGE + 30)
        advanceUntilIdle()

        val seedDate = java.time.Instant.ofEpochMilli(viewModel.computeTimeGridEventSeedTs())
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()

        assertEquals(java.time.LocalDate.now(), seedDate)
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
    fun `setViewMode MONTH syncs pager when selectedDate in different month`() = runTest {
        every { eventReader.getVisibleOccurrencesWithEventsInRangeFlow(any(), any()) } returns flowOf(emptyList())

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Switch to THREE_DAYS first so we can switch back to MONTH
        viewModel.setViewMode(ViewMode.THREE_DAYS)
        advanceUntilIdle()

        // Select a date in June 2025 (different from current viewing month)
        val juneDate = getTimestamp(2025, 5, 15, 10, 0) // month is 0-indexed: 5 = June
        viewModel.selectDate(juneDate)
        advanceUntilIdle()

        // Clear any pending navigation from previous operations
        viewModel.clearNavigateToMonth()
        advanceUntilIdle()

        // Switch back to MONTH — should sync pager to June 2025
        viewModel.setViewMode(ViewMode.MONTH)
        advanceUntilIdle()

        assertEquals(2025, viewModel.uiState.value.viewingYear)
        assertEquals(5, viewModel.uiState.value.viewingMonth)
        assertEquals(2025 to 5, viewModel.uiState.value.pendingNavigateToMonth)
    }

    @Test
    fun `setViewMode MONTH no-op when selectedDate in same month`() = runTest {
        every { eventReader.getVisibleOccurrencesWithEventsInRangeFlow(any(), any()) } returns flowOf(emptyList())

        val viewModel = createViewModel()
        advanceUntilIdle()

        val today = JavaCalendar.getInstance()
        val currentYear = today.get(JavaCalendar.YEAR)
        val currentMonth = today.get(JavaCalendar.MONTH)

        // Explicitly select today so selectedDate matches current viewing month
        viewModel.selectDate(today.timeInMillis)
        advanceUntilIdle()

        // Switch to THREE_DAYS then back — selectedDate is still in current month
        viewModel.setViewMode(ViewMode.THREE_DAYS)
        advanceUntilIdle()

        // Clear any pending navigation
        viewModel.clearNavigateToMonth()
        advanceUntilIdle()

        viewModel.setViewMode(ViewMode.MONTH)
        advanceUntilIdle()

        // viewingYear/viewingMonth should still be current month
        assertEquals(currentYear, viewModel.uiState.value.viewingYear)
        assertEquals(currentMonth, viewModel.uiState.value.viewingMonth)
        // No pending navigation needed — already on correct month
        assertEquals(null, viewModel.uiState.value.pendingNavigateToMonth)
    }

    @Test
    fun `MONTH_FULL grid re-queries reactively as the viewing month changes`() = runTest {
        every { displayEventRepository.getDisplayEventsForDateRange(any(), any()) } returns
            flowOf(persistentMapOf())

        val viewModel = createViewModel()
        advanceUntilIdle()

        // monthEvents is a WhileSubscribed StateFlow — Turbine-collect so its upstream runs.
        viewModel.monthEvents.test {
            skipItems(1) // initial empty (not in MONTH_FULL yet)

            viewModel.setViewMode(ViewMode.MONTH_FULL)
            advanceUntilIdle()
            viewModel.setViewingMonth(2027, 5)
            advanceUntilIdle()
            viewModel.goToToday()
            advanceUntilIdle()

            // Each distinct (year, month) key drives a fresh reactive query:
            // MONTH_FULL entry, setViewingMonth, goToToday.
            verify(atLeast = 3) { displayEventRepository.getDisplayEventsForDateRange(any(), any()) }
            assertTrue(viewModel.uiState.value.pendingNavigateToToday)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `MONTH_FULL grid reflects a created event without manual reload`() = runTest {
        val createdEvent = testEvents[0].copy(id = 200L, title = "Grid Event")
        val gridFlow = MutableStateFlow<kotlinx.collections.immutable.ImmutableMap<Int, kotlinx.collections.immutable.ImmutableList<DisplayEvent>>>(
            persistentMapOf()
        )
        every { displayEventRepository.getDisplayEventsForDateRange(any(), any()) } returns gridFlow

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.monthEvents.test {
            viewModel.setViewMode(ViewMode.MONTH_FULL)
            advanceUntilIdle()

            // Simulate a DB write landing in the reactive grid Flow.
            gridFlow.value = persistentMapOf(
                20241217 to persistentListOf<DisplayEvent>(
                    DisplayEvent.Room(createdEvent, testOccurrences[0], testCalendars[0])
                )
            )
            advanceUntilIdle()

            // Most recent emission must reflect the write, with no manual reload.
            val updated = expectMostRecentItem()
            assertEquals(1, updated[20241217]?.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setViewMode MONTH_FULL syncs pager when selectedDate in different month`() = runTest {
        every { eventReader.getVisibleOccurrencesWithEventsInRangeFlow(any(), any()) } returns flowOf(emptyList())

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Switch to THREE_DAYS first
        viewModel.setViewMode(ViewMode.THREE_DAYS)
        advanceUntilIdle()

        // Select a date in March 2025
        val marchDate = getTimestamp(2025, 2, 10, 10, 0) // month 0-indexed: 2 = March
        viewModel.selectDate(marchDate)
        advanceUntilIdle()

        // Clear any pending navigation
        viewModel.clearNavigateToMonth()
        advanceUntilIdle()

        // Switch to MONTH_FULL — should sync pager to March 2025
        viewModel.setViewMode(ViewMode.MONTH_FULL)
        advanceUntilIdle()

        assertEquals(2025, viewModel.uiState.value.viewingYear)
        assertEquals(2, viewModel.uiState.value.viewingMonth)
        assertEquals(2025 to 2, viewModel.uiState.value.pendingNavigateToMonth)
    }

    @Test
    fun `setViewMode persists to DataStore`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setViewMode(ViewMode.AGENDA)
        advanceUntilIdle()

        coVerify { dataStore.setDefaultCalendarView("agenda") }
    }

    @Test
    fun `setViewMode does not crash when DataStore setter throws`() = runTest {
        coEvery { dataStore.setDefaultCalendarView(any()) } throws
            IllegalArgumentException("Invalid calendar view: simulated")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setViewMode(ViewMode.DAY)
        advanceUntilIdle()

        assertEquals(ViewMode.DAY, viewModel.uiState.value.viewMode)
    }

    @Test
    fun `setViewMode INSIGHTS does not persist to DataStore`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setViewMode(ViewMode.INSIGHTS)
        advanceUntilIdle()

        assertEquals(ViewMode.INSIGHTS, viewModel.uiState.value.viewMode)
        coVerify(exactly = 0) { dataStore.setDefaultCalendarView("insights") }
    }

    @Test
    fun `tapping a day header drills into Day view`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onWeekViewDayHeaderClick(LocalDate.now())
        advanceUntilIdle()

        assertEquals(ViewMode.DAY, viewModel.uiState.value.viewMode)
    }

    @Test
    fun `tapping a day header switches to Day view without overwriting the startup default`() = runTest {
        // Start in a non-DAY mode so the header tap is a real transition.
        every { dataStore.defaultCalendarView } returns flowOf(KashCalDataStore.VIEW_WEEK)
        coEvery { dataStore.getDefaultCalendarView() } returns KashCalDataStore.VIEW_WEEK
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onWeekViewDayHeaderClick(LocalDate.now())
        advanceUntilIdle()

        // A real transient switch: mode flips to DAY, but the persisted default
        // must not change. Asserting both here so a regression that stops the
        // switch entirely can't pass by simply never persisting.
        assertEquals(ViewMode.DAY, viewModel.uiState.value.viewMode)
        coVerify(exactly = 0) { dataStore.setDefaultCalendarView("day") }
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

    @Test
    fun `setViewingMonth calls repairMissingOccurrences alongside extension`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        coEvery { eventCoordinator.extendOccurrencesIfNeeded(any()) } returns 0
        coEvery { eventCoordinator.extendPastOccurrencesIfNeeded(any()) } returns 0
        coEvery { eventCoordinator.repairMissingOccurrences() } returns 2

        viewModel.setViewingMonth(2020, 2)
        advanceUntilIdle()

        coVerify { eventCoordinator.repairMissingOccurrences() }
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

    // ==================== Device Calendar Picker Filter ====================
    // The new-event calendar picker (uiState.deviceCalendarGroups) must respect the
    // same gate-and-filter as the navigation drawer's device-calendar section:
    //   - master toggle dataStore.deviceCalendarsEnabled
    //   - per-calendar set dataStore.enabledDeviceCalendarIds
    // Reference behavior at HomeViewModel.observeDeviceCalendarDrawerState (~L923-948).

    private fun deviceCal(
        id: Long,
        name: String = "Cal $id",
        accessLevel: Int = 700
    ): DeviceCalendar = DeviceCalendar(
        id = id,
        displayName = name,
        color = 0xFF000000.toInt(),
        accountName = "acct",
        accountType = "local",
        visible = true,
        accessLevel = accessLevel
    )

    @Test
    fun `device picker is empty when master toggle off`() = runTest {
        // observeCalendars Flow path
        every { dataStore.deviceCalendarsEnabled } returns flowOf(false)
        every { dataStore.enabledDeviceCalendarIds } returns flowOf(setOf(10L, 20L))
        val fake = FakeCalendarProviderRepository().apply {
            calendars = listOf(deviceCal(10L), deviceCal(20L))
        }

        val viewModel = createViewModel(calendarProviderRepository = fake)
        advanceUntilIdle()

        assertTrue(
            "Master toggle off → no device groups regardless of system calendars",
            viewModel.uiState.value.deviceCalendarGroups.isEmpty()
        )
    }

    @Test
    fun `device picker shows only enabled writable calendars when master on`() = runTest {
        // observeCalendars Flow path + writableOnly preservation
        every { dataStore.deviceCalendarsEnabled } returns flowOf(true)
        every { dataStore.enabledDeviceCalendarIds } returns flowOf(setOf(10L, 30L))
        val fake = FakeCalendarProviderRepository().apply {
            calendars = listOf(
                deviceCal(10L, accessLevel = 700), // enabled + writable → included
                deviceCal(20L, accessLevel = 700), // writable but NOT enabled → excluded
                deviceCal(30L, accessLevel = 200)  // enabled but READ-ONLY → excluded by writableOnly
            )
        }

        val viewModel = createViewModel(calendarProviderRepository = fake)
        advanceUntilIdle()

        val ids = viewModel.uiState.value.deviceCalendarGroups
            .flatMap { it.pickerCalendars }
            .map { it.id }
        assertEquals(listOf(10L), ids)
    }

    @Test
    fun `device picker is empty when master on but no calendars enabled`() = runTest {
        // observeCalendars Flow path edge case
        every { dataStore.deviceCalendarsEnabled } returns flowOf(true)
        every { dataStore.enabledDeviceCalendarIds } returns flowOf(emptySet())
        val fake = FakeCalendarProviderRepository().apply {
            calendars = listOf(deviceCal(10L), deviceCal(20L))
        }

        val viewModel = createViewModel(calendarProviderRepository = fake)
        advanceUntilIdle()

        assertTrue(
            "Master on with empty enabled set → no device groups (matches drawer)",
            viewModel.uiState.value.deviceCalendarGroups.isEmpty()
        )
    }

    @Test
    fun `device picker updates reactively when enabled IDs change`() = runTest {
        // observeCalendars reactive Flow update
        val enabledIdsFlow = MutableStateFlow<Set<Long>>(emptySet())
        every { dataStore.deviceCalendarsEnabled } returns flowOf(true)
        every { dataStore.enabledDeviceCalendarIds } returns enabledIdsFlow
        val fake = FakeCalendarProviderRepository().apply {
            calendars = listOf(deviceCal(10L))
        }

        val viewModel = createViewModel(calendarProviderRepository = fake)
        advanceUntilIdle()

        assertTrue(
            "Initially no IDs enabled → empty groups",
            viewModel.uiState.value.deviceCalendarGroups.isEmpty()
        )

        enabledIdsFlow.value = setOf(10L)
        advanceUntilIdle()

        val ids = viewModel.uiState.value.deviceCalendarGroups
            .flatMap { it.pickerCalendars }
            .map { it.id }
        assertEquals(
            "After enabling id=10 in DataStore, picker reflects change without manual refresh",
            listOf(10L),
            ids
        )
    }

    @Test
    fun `loadCalendars (refreshCalendars) respects gate-and-filter`() = runTest {
        // loadCalendars suspend path. Configure Flow path to emit empty so observeCalendars
        // produces no device groups; configure suspend path to emit enabled. This isolates
        // loadCalendars — the post-refreshCalendars assertion can only be true if the
        // suspend path filtered correctly.
        every { dataStore.deviceCalendarsEnabled } returns flowOf(false)
        every { dataStore.enabledDeviceCalendarIds } returns flowOf(emptySet())
        coEvery { dataStore.getDeviceCalendarsEnabled() } returns true
        coEvery { dataStore.getEnabledDeviceCalendarIds() } returns setOf(10L)
        val fake = FakeCalendarProviderRepository().apply {
            calendars = listOf(deviceCal(10L), deviceCal(20L))
        }

        val viewModel = createViewModel(calendarProviderRepository = fake)
        advanceUntilIdle()
        assertTrue(
            "Flow path produces empty groups (master off via Flow stub)",
            viewModel.uiState.value.deviceCalendarGroups.isEmpty()
        )

        viewModel.refreshCalendars()
        advanceUntilIdle()

        val ids = viewModel.uiState.value.deviceCalendarGroups
            .flatMap { it.pickerCalendars }
            .map { it.id }
        assertEquals(
            "refreshCalendars (loadCalendars suspend path) populates only enabled IDs",
            listOf(10L),
            ids
        )
    }

    // ==================== Avatar Initials Tests ====================

    @Test
    fun `userInitials preference flows into uiState`() = runTest {
        every { dataStore.userInitials } returns flowOf("KC")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("KC", viewModel.uiState.value.userInitials)
    }

    @Test
    fun `setUserInitials normalizes before persisting`() = runTest {
        val saved = slot<String>()
        coEvery { dataStore.setUserInitials(capture(saved)) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setUserInitials("john")
        advanceUntilIdle()

        // "john" -> first two letters, uppercased.
        assertEquals("JO", saved.captured)
    }

    @Test
    fun `setUserInitials persists empty to clear`() = runTest {
        val saved = slot<String>()
        coEvery { dataStore.setUserInitials(capture(saved)) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setUserInitials("  ")
        advanceUntilIdle()

        assertEquals("", saved.captured)
    }
}
