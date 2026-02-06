package org.onekash.kashcal.ui.viewmodels

import android.app.Application
import android.content.pm.PackageManager
import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.credential.AccountCredentials
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.reader.SyncLogReader
import org.onekash.kashcal.data.db.entity.SyncLog
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.data.preferences.UserPreferencesRepository
import org.onekash.kashcal.sync.discovery.AccountDiscoveryService
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.onekash.kashcal.sync.provider.caldav.CalDavAccountDiscoveryService
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.ui.screens.AccountSettingsUiState
import org.onekash.kashcal.ui.screens.settings.ICloudConnectionState
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.ics.IcsSubscriptionRepository
import org.onekash.kashcal.data.ics.IcsRefreshWorker
import org.onekash.kashcal.data.contacts.ContactBirthdayManager
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.widget.WidgetUpdateManager
import kotlinx.coroutines.delay

/**
 * Unit tests for AccountSettingsViewModel.
 *
 * Tests cover:
 * - Initial state loading (Loading → Connected or NotConnected)
 * - Apple ID/Password input changes
 * - Help toggle
 * - Sign in flow (credentials validation, save, sync trigger)
 * - Sign out flow
 * - Calendar visibility toggle
 * - Default calendar selection
 * - Sync interval changes
 * - Reminder preference changes
 * - Notification permission state
 * - Flow integration with backend services
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    // Mocks
    private lateinit var application: Application
    private lateinit var accountRepository: AccountRepository
    private lateinit var userPreferences: UserPreferencesRepository
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var discoveryService: AccountDiscoveryService
    private lateinit var calDavDiscoveryService: CalDavAccountDiscoveryService
    private lateinit var eventCoordinator: EventCoordinator
    private lateinit var syncLogReader: SyncLogReader
    private lateinit var contactBirthdayManager: ContactBirthdayManager
    private lateinit var dataStore: KashCalDataStore
    private lateinit var widgetUpdateManager: WidgetUpdateManager

    // Flows we control
    private lateinit var calendarsFlow: MutableStateFlow<List<Calendar>>
    private lateinit var iCloudCalendarCountFlow: MutableStateFlow<Int>
    private lateinit var calDavAccountCountFlow: MutableStateFlow<Int>
    private lateinit var defaultCalendarIdFlow: MutableStateFlow<Long?>
    private lateinit var syncIntervalFlow: MutableStateFlow<Long>
    private lateinit var defaultReminderTimedFlow: MutableStateFlow<Int>
    private lateinit var defaultReminderAllDayFlow: MutableStateFlow<Int>
    private lateinit var syncLogsFlow: MutableStateFlow<List<SyncLog>>
    private lateinit var contactBirthdaysEnabledFlow: MutableStateFlow<Boolean>
    private lateinit var contactBirthdaysLastSyncFlow: MutableStateFlow<Long>
    private lateinit var defaultEventDurationFlow: MutableStateFlow<Int>

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
        ),
        Calendar(
            id = 3L,
            accountId = 1L,
            caldavUrl = "https://caldav.icloud.com/cal3",
            displayName = "Family",
            color = 0xFFFF9800.toInt()
        )
    )

    private val testDbAccount = Account(
        id = 1L,
        provider = AccountProvider.ICLOUD,
        email = "test@icloud.com",
        displayName = "iCloud",
        principalUrl = "https://caldav.icloud.com/123/principal",
        homeSetUrl = "https://caldav.icloud.com/123/calendars"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Initialize mocks
        application = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
        userPreferences = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)
        discoveryService = mockk(relaxed = true)
        calDavDiscoveryService = mockk(relaxed = true)
        eventCoordinator = mockk(relaxed = true)
        syncLogReader = mockk(relaxed = true)
        contactBirthdayManager = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        widgetUpdateManager = mockk(relaxed = true)

        // Setup flows
        calendarsFlow = MutableStateFlow(emptyList())
        iCloudCalendarCountFlow = MutableStateFlow(0)
        calDavAccountCountFlow = MutableStateFlow(0)
        defaultCalendarIdFlow = MutableStateFlow(null)
        syncIntervalFlow = MutableStateFlow(24 * 60 * 60 * 1000L) // 24 hours
        defaultReminderTimedFlow = MutableStateFlow(15)
        defaultReminderAllDayFlow = MutableStateFlow(1440)
        syncLogsFlow = MutableStateFlow(emptyList())
        contactBirthdaysEnabledFlow = MutableStateFlow(false)
        contactBirthdaysLastSyncFlow = MutableStateFlow(0L)
        defaultEventDurationFlow = MutableStateFlow(60) // Default 60 minutes

        // Setup default behaviors - EventCoordinator for calendars (architecture compliant)
        // IMPORTANT: ViewModel uses combine() on getAllCalendars + getAllAccounts + defaultCalendarId
        every { eventCoordinator.getAllCalendars() } returns calendarsFlow
        every { eventCoordinator.getAllAccounts() } returns flowOf(emptyList())
        every { eventCoordinator.getICloudCalendarCount() } returns iCloudCalendarCountFlow
        every { eventCoordinator.getCalDavAccountCount() } returns calDavAccountCountFlow
        every { userPreferences.defaultCalendarId } returns defaultCalendarIdFlow
        every { userPreferences.syncIntervalMs } returns syncIntervalFlow
        every { userPreferences.defaultReminderTimed } returns defaultReminderTimedFlow
        every { userPreferences.defaultReminderAllDay } returns defaultReminderAllDayFlow
        every { userPreferences.defaultEventDuration } returns defaultEventDurationFlow
        every { syncLogReader.getRecentLogs(any()) } returns syncLogsFlow

        // Default: no iCloud account configured
        coEvery { accountRepository.getAccountsByProvider(AccountProvider.ICLOUD) } returns emptyList()
        coEvery { accountRepository.hasCredentials(any()) } returns false

        // Mock application context for notification permission check
        every { application.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED

        // Mock ICS subscriptions flow
        every { eventCoordinator.getAllIcsSubscriptions() } returns flowOf(emptyList())

        // Mock contact birthdays flows
        every { dataStore.contactBirthdaysEnabled } returns contactBirthdaysEnabledFlow
        every { dataStore.contactBirthdaysLastSync } returns contactBirthdaysLastSyncFlow
        coEvery { eventCoordinator.getContactBirthdaysColor() } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): AccountSettingsViewModel {
        return AccountSettingsViewModel(
            application = application,
            accountRepository = accountRepository,
            userPreferences = userPreferences,
            syncScheduler = syncScheduler,
            discoveryService = discoveryService,
            calDavDiscoveryService = calDavDiscoveryService,
            eventCoordinator = eventCoordinator,
            syncLogReader = syncLogReader,
            contactBirthdayManager = contactBirthdayManager,
            dataStore = dataStore,
            widgetUpdateManager = widgetUpdateManager
        )
    }

    // ==================== Initial State Tests ====================

    @Test
    fun `initial state is Loading`() = runTest {
        val viewModel = createViewModel()

        // Should start with Loading state before coroutines complete
        assertTrue(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `shows NotConnected when no credentials saved`() = runTest {
        coEvery { accountRepository.getAccountsByProvider(AccountProvider.ICLOUD) } returns emptyList()

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = expectMostRecentItem()
            assertTrue(state.iCloudState is ICloudConnectionState.NotConnected)
        }
    }

    @Test
    fun `shows Connected when credentials exist`() = runTest {
        val account = testDbAccount.copy(lastSuccessfulSyncAt = System.currentTimeMillis())
        coEvery { accountRepository.getAccountsByProvider(AccountProvider.ICLOUD) } returns listOf(account)
        coEvery { accountRepository.hasCredentials(account.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = expectMostRecentItem()
            assertTrue(state.iCloudState is ICloudConnectionState.Connected)
            val connected = state.iCloudState as ICloudConnectionState.Connected
            assertEquals("test@icloud.com", connected.appleId)
        }
    }

    @Test
    fun `loads calendars on init`() = runTest {
        coEvery { accountRepository.getAccountsByProvider(AccountProvider.ICLOUD) } returns listOf(testDbAccount)
        coEvery { accountRepository.hasCredentials(testDbAccount.id) } returns true
        calendarsFlow.value = testCalendars

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.calendars.test {
            val calendars = expectMostRecentItem()
            assertEquals(3, calendars.size)
            assertEquals("Personal", calendars[0].displayName)
        }
    }

    @Test
    fun `updates calendar count in Connected state`() = runTest {
        coEvery { accountRepository.getAccountsByProvider(AccountProvider.ICLOUD) } returns listOf(testDbAccount)
        coEvery { accountRepository.hasCredentials(testDbAccount.id) } returns true
        calendarsFlow.value = testCalendars
        iCloudCalendarCountFlow.value = 3  // Checkpoint 2: Now uses iCloud-only count

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = expectMostRecentItem()
            assertTrue(state.iCloudState is ICloudConnectionState.Connected)
            assertEquals(3, (state.iCloudState as ICloudConnectionState.Connected).calendarCount)
        }
    }

    // ==================== Input Change Tests ====================

    @Test
    fun `onAppleIdChange updates NotConnected state`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onAppleIdChange("user@icloud.com")
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = expectMostRecentItem()
            assertTrue(state.iCloudState is ICloudConnectionState.NotConnected)
            assertEquals("user@icloud.com", (state.iCloudState as ICloudConnectionState.NotConnected).appleId)
        }
    }

    @Test
    fun `onPasswordChange updates NotConnected state`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onPasswordChange("test-password")
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = expectMostRecentItem()
            assertTrue(state.iCloudState is ICloudConnectionState.NotConnected)
            assertEquals("test-password", (state.iCloudState as ICloudConnectionState.NotConnected).password)
        }
    }

    @Test
    fun `onToggleHelp toggles help visibility`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Initially help is hidden
        viewModel.uiState.test {
            val state = expectMostRecentItem().iCloudState as ICloudConnectionState.NotConnected
            assertEquals(false, state.showHelp)
        }

        // Toggle help on
        viewModel.onToggleHelp()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = expectMostRecentItem().iCloudState as ICloudConnectionState.NotConnected
            assertEquals(true, state.showHelp)
        }

        // Toggle help off
        viewModel.onToggleHelp()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = expectMostRecentItem().iCloudState as ICloudConnectionState.NotConnected
            assertEquals(false, state.showHelp)
        }
    }

    // ==================== Sign In Tests ====================

    @Test
    fun `onSignIn shows Connecting state then Connected`() = runTest {
        // Mock successful discovery
        coEvery { discoveryService.discoverAndCreateAccount(any(), any()) } returns DiscoveryResult.Success(
            account = testDbAccount,
            calendars = testCalendars
        )
        coEvery { accountRepository.saveCredentials(any(), any()) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onAppleIdChange("test@icloud.com")
        viewModel.onPasswordChange("xxxx-xxxx-xxxx-xxxx")
        advanceUntilIdle()

        viewModel.onSignIn()
        advanceUntilIdle()

        // After sign in completes, should be Connected
        viewModel.uiState.test {
            val state = expectMostRecentItem()
            assertTrue(state.iCloudState is ICloudConnectionState.Connected)
        }
    }

    @Test
    fun `onSignIn fails with empty credentials`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onSignIn()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = expectMostRecentItem()
            assertTrue(state.iCloudState is ICloudConnectionState.NotConnected)
            assertEquals("Apple ID and password are required", (state.iCloudState as ICloudConnectionState.NotConnected).error)
        }
    }

    @Test
    fun `onSignIn saves credentials and shows Connected`() = runTest {
        // Mock successful discovery
        coEvery { discoveryService.discoverAndCreateAccount(any(), any()) } returns DiscoveryResult.Success(
            account = testDbAccount,
            calendars = testCalendars
        )
        coEvery { accountRepository.saveCredentials(any(), any()) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onAppleIdChange("test@icloud.com")
        viewModel.onPasswordChange("xxxx-xxxx-xxxx-xxxx")
        advanceUntilIdle()

        viewModel.onSignIn()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = expectMostRecentItem()
            assertTrue(state.iCloudState is ICloudConnectionState.Connected)
            assertEquals("test@icloud.com", (state.iCloudState as ICloudConnectionState.Connected).appleId)
        }

        coVerify { accountRepository.saveCredentials(testDbAccount.id, any()) }
    }

    @Test
    fun `onSignIn triggers immediate sync`() = runTest {
        // Mock successful discovery
        coEvery { discoveryService.discoverAndCreateAccount(any(), any()) } returns DiscoveryResult.Success(
            account = testDbAccount,
            calendars = testCalendars
        )
        coEvery { accountRepository.saveCredentials(any(), any()) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onAppleIdChange("test@icloud.com")
        viewModel.onPasswordChange("xxxx-xxxx-xxxx-xxxx")
        advanceUntilIdle()

        viewModel.onSignIn()
        advanceUntilIdle()

        verify { syncScheduler.requestImmediateSync(forceFullSync = true) }
    }

    @Test
    fun `onSignIn shows error when discovery fails with auth error`() = runTest {
        // Mock auth failure
        coEvery { discoveryService.discoverAndCreateAccount(any(), any()) } returns DiscoveryResult.AuthError(
            message = "Invalid credentials"
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onAppleIdChange("test@icloud.com")
        viewModel.onPasswordChange("xxxx-xxxx-xxxx-xxxx")
        advanceUntilIdle()

        viewModel.onSignIn()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = expectMostRecentItem()
            assertTrue(state.iCloudState is ICloudConnectionState.NotConnected)
            assertTrue((state.iCloudState as ICloudConnectionState.NotConnected).error?.contains("Invalid credentials") == true)
        }
    }

    @Test
    fun `onSignIn times out and shows error after 30 seconds`() = runTest {
        // Mock slow discovery that takes longer than timeout
        coEvery { discoveryService.discoverAndCreateAccount(any(), any()) } coAnswers {
            // Simulate network delay longer than the 30s timeout
            delay(60_000L) // 60 seconds
            DiscoveryResult.Success(
                account = testDbAccount,
                calendars = testCalendars
            )
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onAppleIdChange("test@icloud.com")
        viewModel.onPasswordChange("xxxx-xxxx-xxxx-xxxx")
        advanceUntilIdle()

        viewModel.onSignIn()
        // Advance time past the timeout (30 seconds)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = expectMostRecentItem()
            assertTrue(state.iCloudState is ICloudConnectionState.NotConnected)
            val error = (state.iCloudState as ICloudConnectionState.NotConnected).error
            assertTrue(error?.contains("timed out") == true)
        }
    }

    @Test
    fun `onSignIn shows error when discovery fails with general error`() = runTest {
        // Mock general failure
        coEvery { discoveryService.discoverAndCreateAccount(any(), any()) } returns DiscoveryResult.Error(
            message = "Network error"
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onAppleIdChange("test@icloud.com")
        viewModel.onPasswordChange("xxxx-xxxx-xxxx-xxxx")
        advanceUntilIdle()

        viewModel.onSignIn()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = expectMostRecentItem()
            assertTrue(state.iCloudState is ICloudConnectionState.NotConnected)
            assertTrue((state.iCloudState as ICloudConnectionState.NotConnected).error?.contains("Network error") == true)
        }
    }

    // ==================== Sign Out Tests ====================

    @Test
    fun `onSignOut clears credentials and shows NotConnected`() = runTest {
        coEvery { accountRepository.getAccountsByProvider(AccountProvider.ICLOUD) } returns listOf(testDbAccount)
        coEvery { accountRepository.hasCredentials(testDbAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Verify connected first
        assertTrue(viewModel.uiState.value.iCloudState is ICloudConnectionState.Connected)

        viewModel.onSignOut()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = expectMostRecentItem()
            assertTrue(state.iCloudState is ICloudConnectionState.NotConnected)
        }

        // Verify account removal was called (which handles credential cleanup)
        coVerify { discoveryService.removeAccountByEmail(testDbAccount.email) }
    }

    @Test
    fun `onSignOut cancels periodic sync`() = runTest {
        coEvery { accountRepository.getAccountsByProvider(AccountProvider.ICLOUD) } returns listOf(testDbAccount)
        coEvery { accountRepository.hasCredentials(testDbAccount.id) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onSignOut()
        advanceUntilIdle()

        verify { syncScheduler.cancelPeriodicSync() }
    }

    // ==================== Force Full Sync Tests ====================

    @Test
    fun `forceFullSync sets banner flag and requests sync`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.forceFullSync()
        advanceUntilIdle()

        // Verify banner flag is set BEFORE sync request
        verify(ordering = io.mockk.Ordering.ORDERED) {
            syncScheduler.setShowBannerForSync(true)
            syncScheduler.requestImmediateSync(forceFullSync = true)
        }
    }

    // ==================== Calendar Visibility Tests ====================

    @Test
    fun `onToggleCalendar calls eventCoordinator setCalendarVisibility`() = runTest {
        calendarsFlow.value = testCalendars
        coEvery { eventCoordinator.setCalendarVisibility(any(), any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Hide calendar 1
        viewModel.onToggleCalendar(1L, false)
        advanceUntilIdle()

        coVerify { eventCoordinator.setCalendarVisibility(1L, false) }
    }

    @Test
    fun `onToggleCalendar shows calendar`() = runTest {
        calendarsFlow.value = testCalendars
        coEvery { eventCoordinator.setCalendarVisibility(any(), any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Show calendar 1
        viewModel.onToggleCalendar(1L, true)
        advanceUntilIdle()

        coVerify { eventCoordinator.setCalendarVisibility(1L, true) }
    }

    @Test
    fun `onShowAllCalendars sets all calendars visible via EventCoordinator`() = runTest {
        calendarsFlow.value = testCalendars
        coEvery { eventCoordinator.setCalendarVisibility(any(), any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onShowAllCalendars()
        advanceUntilIdle()

        // Should call setCalendarVisibility(true) for each calendar
        coVerify { eventCoordinator.setCalendarVisibility(1L, true) }
        coVerify { eventCoordinator.setCalendarVisibility(2L, true) }
        coVerify { eventCoordinator.setCalendarVisibility(3L, true) }
    }

    @Test
    fun `onHideAllCalendars keeps one calendar visible via EventCoordinator`() = runTest {
        calendarsFlow.value = testCalendars
        coEvery { eventCoordinator.setCalendarVisibility(any(), any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onHideAllCalendars()
        advanceUntilIdle()

        // First calendar stays visible, others hidden
        coVerify { eventCoordinator.setCalendarVisibility(1L, true) }
        coVerify { eventCoordinator.setCalendarVisibility(2L, false) }
        coVerify { eventCoordinator.setCalendarVisibility(3L, false) }
    }

    // ==================== Default Calendar Tests ====================

    @Test
    fun `onDefaultCalendarSelect updates preference`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDefaultCalendarSelect(2L)
        advanceUntilIdle()

        coVerify { userPreferences.setDefaultCalendarId(2L) }
    }

    @Test
    fun `observes default calendar changes`() = runTest {
        defaultCalendarIdFlow.value = 1L

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Verify initial value
        assertEquals(1L, viewModel.defaultCalendarId.value)

        // Change default calendar
        defaultCalendarIdFlow.value = 2L
        advanceUntilIdle()

        // Verify updated value
        assertEquals(2L, viewModel.defaultCalendarId.value)
    }

    // ==================== Sync Interval Tests ====================

    @Test
    fun `onSyncIntervalChange updates preference and scheduler`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val oneHourMs = 60 * 60 * 1000L
        viewModel.onSyncIntervalChange(oneHourMs)
        advanceUntilIdle()

        coVerify { userPreferences.setSyncIntervalMs(oneHourMs) }
        verify { syncScheduler.updatePeriodicSyncInterval(60L) } // 60 minutes
    }

    @Test
    fun `onSyncIntervalChange cancels sync when manual only`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onSyncIntervalChange(Long.MAX_VALUE)
        advanceUntilIdle()

        coVerify { userPreferences.setSyncIntervalMs(Long.MAX_VALUE) }
        verify { syncScheduler.cancelPeriodicSync() }
    }

    @Test
    fun `observes sync interval changes`() = runTest {
        val sixHoursMs = 6 * 60 * 60 * 1000L
        syncIntervalFlow.value = sixHoursMs

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.syncIntervalMs.test {
            assertEquals(sixHoursMs, expectMostRecentItem())
        }
    }

    // ==================== Reminder Preference Tests ====================

    @Test
    fun `onDefaultReminderTimedChange updates preference`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDefaultReminderTimedChange(30)
        advanceUntilIdle()

        coVerify { userPreferences.setDefaultReminderTimed(30) }
    }

    @Test
    fun `onDefaultReminderAllDayChange updates preference`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDefaultReminderAllDayChange(2880) // 2 days
        advanceUntilIdle()

        coVerify { userPreferences.setDefaultReminderAllDay(2880) }
    }

    @Test
    fun `observes default reminder timed changes`() = runTest {
        defaultReminderTimedFlow.value = 15

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Verify initial value
        assertEquals(15, viewModel.defaultReminderTimed.value)

        // Update reminder value
        defaultReminderTimedFlow.value = 60
        advanceUntilIdle()

        // Verify updated value
        assertEquals(60, viewModel.defaultReminderTimed.value)
    }

    @Test
    fun `observes default reminder all-day changes`() = runTest {
        defaultReminderAllDayFlow.value = 1440

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.defaultReminderAllDay.test {
            assertEquals(1440, expectMostRecentItem())
        }
    }

    // ==================== Subscription Tests ====================

    @Test
    fun `subscriptions list is initially empty`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.subscriptions.test {
            assertTrue(expectMostRecentItem().isEmpty())
        }
    }

    @Test
    fun `subscriptionSyncing is initially false`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.subscriptionSyncing.test {
            assertEquals(false, expectMostRecentItem())
        }
    }

    @Test
    fun `subscriptions flow updates when subscriptions are loaded`() = runTest {
        val testSubscription = IcsSubscription(
            id = 1L,
            url = "https://example.com/calendar.ics",
            name = "Test Calendar",
            color = 0xFF2196F3.toInt(),
            calendarId = 10L,
            lastSync = System.currentTimeMillis(),
            enabled = true
        )
        val subscriptionsFlow = MutableStateFlow(listOf(testSubscription))
        every { eventCoordinator.getAllIcsSubscriptions() } returns subscriptionsFlow

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.subscriptions.test {
            val subscriptions = expectMostRecentItem()
            assertEquals(1, subscriptions.size)
            assertEquals("Test Calendar", subscriptions[0].name)
        }
    }

    @Test
    fun `onAddSubscription calls eventCoordinator with correct parameters`() = runTest {
        // Mock IcsRefreshWorker companion object to avoid WorkManager initialization
        mockkObject(IcsRefreshWorker.Companion)
        every { IcsRefreshWorker.schedulePeriodicRefresh(any(), any()) } just Runs

        try {
            val testSubscription = IcsSubscription(
                id = 1L,
                url = "https://example.com/holidays.ics",
                name = "US Holidays",
                color = 0xFFFF5722.toInt(),
                calendarId = 10L
            )
            coEvery { eventCoordinator.addIcsSubscription(any(), any(), any()) } returns
                IcsSubscriptionRepository.SubscriptionResult.Success(testSubscription)

            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.onAddSubscription(
                url = "https://example.com/holidays.ics",
                name = "US Holidays",
                color = 0xFFFF5722.toInt()
            )
            advanceUntilIdle()

            coVerify {
                eventCoordinator.addIcsSubscription(
                    "https://example.com/holidays.ics",
                    "US Holidays",
                    0xFFFF5722.toInt()
                )
            }
        } finally {
            unmockkObject(IcsRefreshWorker.Companion)
        }
    }

    @Test
    fun `onAddSubscription handles error gracefully`() = runTest {
        coEvery { eventCoordinator.addIcsSubscription(any(), any(), any()) } returns
            IcsSubscriptionRepository.SubscriptionResult.Error("Invalid URL format")

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Should not throw exception
        viewModel.onAddSubscription(
            url = "invalid-url",
            name = "Test",
            color = 0xFF000000.toInt()
        )
        advanceUntilIdle()

        // Verify the method was called
        coVerify { eventCoordinator.addIcsSubscription("invalid-url", "Test", 0xFF000000.toInt()) }
    }

    @Test
    fun `onDeleteSubscription calls eventCoordinator`() = runTest {
        coEvery { eventCoordinator.removeIcsSubscription(any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDeleteSubscription(subscriptionId = 42L)
        advanceUntilIdle()

        coVerify { eventCoordinator.removeIcsSubscription(42L) }
    }

    @Test
    fun `onToggleSubscription enables subscription`() = runTest {
        coEvery { eventCoordinator.setIcsSubscriptionEnabled(any(), any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleSubscription(subscriptionId = 1L, enabled = true)
        advanceUntilIdle()

        coVerify { eventCoordinator.setIcsSubscriptionEnabled(1L, true) }
    }

    @Test
    fun `onToggleSubscription disables subscription`() = runTest {
        coEvery { eventCoordinator.setIcsSubscriptionEnabled(any(), any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleSubscription(subscriptionId = 1L, enabled = false)
        advanceUntilIdle()

        coVerify { eventCoordinator.setIcsSubscriptionEnabled(1L, false) }
    }

    @Test
    fun `onSyncAllSubscriptions sets subscriptionSyncing during sync`() = runTest {
        val syncCount = IcsSubscriptionRepository.SyncCount(added = 5, updated = 2, deleted = 1)
        coEvery { eventCoordinator.forceRefreshAllIcsSubscriptions() } coAnswers {
            delay(100) // Simulate async work
            listOf(IcsSubscriptionRepository.SyncResult.Success(syncCount))
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onSyncAllSubscriptions()

        // Need to advance scheduler slightly to let the coroutine start and set the flag
        testScheduler.advanceTimeBy(10)
        testScheduler.runCurrent()

        // Should be syncing during the async work
        assertTrue(viewModel.subscriptionSyncing.value)

        advanceUntilIdle()

        // Should be false after completion
        assertEquals(false, viewModel.subscriptionSyncing.value)
    }

    @Test
    fun `onSyncAllSubscriptions calls eventCoordinator`() = runTest {
        val syncCount = IcsSubscriptionRepository.SyncCount(added = 10, updated = 0, deleted = 0)
        coEvery { eventCoordinator.forceRefreshAllIcsSubscriptions() } returns
            listOf(IcsSubscriptionRepository.SyncResult.Success(syncCount))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onSyncAllSubscriptions()
        advanceUntilIdle()

        coVerify { eventCoordinator.forceRefreshAllIcsSubscriptions() }
    }

    @Test
    fun `onSyncAllSubscriptions handles mixed results`() = runTest {
        val successResult = IcsSubscriptionRepository.SyncResult.Success(
            IcsSubscriptionRepository.SyncCount(added = 5, updated = 0, deleted = 0)
        )
        val errorResult = IcsSubscriptionRepository.SyncResult.Error("Network error")
        coEvery { eventCoordinator.forceRefreshAllIcsSubscriptions() } returns
            listOf(successResult, errorResult)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Should not throw exception
        viewModel.onSyncAllSubscriptions()
        advanceUntilIdle()

        coVerify { eventCoordinator.forceRefreshAllIcsSubscriptions() }
        assertEquals(false, viewModel.subscriptionSyncing.value)
    }

    @Test
    fun `onSyncAllSubscriptions handles exception`() = runTest {
        coEvery { eventCoordinator.forceRefreshAllIcsSubscriptions() } throws
            RuntimeException("Unexpected error")

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Should not throw exception to caller
        viewModel.onSyncAllSubscriptions()
        advanceUntilIdle()

        // subscriptionSyncing should be reset even after error
        assertEquals(false, viewModel.subscriptionSyncing.value)
    }

    @Test
    fun `onRefreshSubscription calls eventCoordinator with correct id`() = runTest {
        val syncCount = IcsSubscriptionRepository.SyncCount(added = 0, updated = 5, deleted = 0)
        coEvery { eventCoordinator.refreshIcsSubscription(any()) } returns
            IcsSubscriptionRepository.SyncResult.Success(syncCount)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onRefreshSubscription(subscriptionId = 123L)
        advanceUntilIdle()

        coVerify { eventCoordinator.refreshIcsSubscription(123L) }
    }

    @Test
    fun `onUpdateSubscription updates name, color, and interval`() = runTest {
        coEvery { eventCoordinator.updateIcsSubscriptionSettings(any(), any(), any(), any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onUpdateSubscription(
            subscriptionId = 1L,
            name = "New Name",
            color = 0xFF00FF00.toInt(),
            syncIntervalHours = 12
        )
        advanceUntilIdle()

        coVerify {
            eventCoordinator.updateIcsSubscriptionSettings(
                1L,
                "New Name",
                0xFF00FF00.toInt(),
                12
            )
        }
    }

    @Test
    fun `subscriptions list updates when subscription is added`() = runTest {
        val subscriptionsFlow = MutableStateFlow<List<IcsSubscription>>(emptyList())
        every { eventCoordinator.getAllIcsSubscriptions() } returns subscriptionsFlow

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Initially empty
        viewModel.subscriptions.test {
            assertTrue(expectMostRecentItem().isEmpty())
        }

        // Add subscription
        val newSubscription = IcsSubscription(
            id = 1L,
            url = "https://example.com/cal.ics",
            name = "New Calendar",
            color = 0xFF2196F3.toInt(),
            calendarId = 10L
        )
        subscriptionsFlow.value = listOf(newSubscription)
        advanceUntilIdle()

        // Should reflect new subscription
        viewModel.subscriptions.test {
            assertEquals(1, expectMostRecentItem().size)
        }
    }

    @Test
    fun `subscriptions list updates when subscription is removed`() = runTest {
        val testSubscription = IcsSubscription(
            id = 1L,
            url = "https://example.com/cal.ics",
            name = "Test Calendar",
            color = 0xFF2196F3.toInt(),
            calendarId = 10L
        )
        val subscriptionsFlow = MutableStateFlow(listOf(testSubscription))
        every { eventCoordinator.getAllIcsSubscriptions() } returns subscriptionsFlow

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Initially has one subscription
        viewModel.subscriptions.test {
            assertEquals(1, expectMostRecentItem().size)
        }

        // Remove subscription
        subscriptionsFlow.value = emptyList()
        advanceUntilIdle()

        // Should be empty
        viewModel.subscriptions.test {
            assertTrue(expectMostRecentItem().isEmpty())
        }
    }

    @Test
    fun `subscription with error displays error state`() = runTest {
        val subscriptionWithError = IcsSubscription(
            id = 1L,
            url = "https://example.com/broken.ics",
            name = "Broken Calendar",
            color = 0xFFFF0000.toInt(),
            calendarId = 10L,
            lastError = "HTTP 404 Not Found"
        )
        every { eventCoordinator.getAllIcsSubscriptions() } returns flowOf(listOf(subscriptionWithError))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.subscriptions.test {
            val subscriptions = expectMostRecentItem()
            assertEquals(1, subscriptions.size)
            assertTrue(subscriptions[0].hasError())
            assertEquals("HTTP 404 Not Found", subscriptions[0].lastError)
        }
    }

    @Test
    fun `disabled subscription is included in list`() = runTest {
        val disabledSubscription = IcsSubscription(
            id = 1L,
            url = "https://example.com/cal.ics",
            name = "Disabled Calendar",
            color = 0xFF9E9E9E.toInt(),
            calendarId = 10L,
            enabled = false
        )
        every { eventCoordinator.getAllIcsSubscriptions() } returns flowOf(listOf(disabledSubscription))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.subscriptions.test {
            val subscriptions = expectMostRecentItem()
            assertEquals(1, subscriptions.size)
            assertEquals(false, subscriptions[0].enabled)
        }
    }

    @Test
    fun `multiple subscriptions are loaded correctly`() = runTest {
        val subscriptions = listOf(
            IcsSubscription(
                id = 1L, url = "https://example.com/cal1.ics",
                name = "Calendar 1", color = 0xFF2196F3.toInt(), calendarId = 10L
            ),
            IcsSubscription(
                id = 2L, url = "https://example.com/cal2.ics",
                name = "Calendar 2", color = 0xFF4CAF50.toInt(), calendarId = 11L
            ),
            IcsSubscription(
                id = 3L, url = "https://example.com/cal3.ics",
                name = "Calendar 3", color = 0xFFFF9800.toInt(), calendarId = 12L
            )
        )
        every { eventCoordinator.getAllIcsSubscriptions() } returns flowOf(subscriptions)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.subscriptions.test {
            val result = expectMostRecentItem()
            assertEquals(3, result.size)
            assertEquals("Calendar 1", result[0].name)
            assertEquals("Calendar 2", result[1].name)
            assertEquals("Calendar 3", result[2].name)
        }
    }

    // ==================== Sync Logs Tests ====================

    @Test
    fun `loadSyncLogs populates sync logs`() = runTest {
        val testLogs = listOf(
            SyncLog(
                id = 1L,
                timestamp = System.currentTimeMillis(),
                calendarId = 1L,
                action = "PULL",
                result = "SUCCESS"
            )
        )
        syncLogsFlow.value = testLogs

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loadSyncLogs()
        advanceUntilIdle()

        viewModel.syncLogs.test {
            val logs = expectMostRecentItem()
            assertEquals(1, logs.size)
            assertEquals("PULL", logs[0].action)
        }
    }

    // ==================== Flow Integration Tests ====================

    @Test
    fun `calendars flow updates state`() = runTest {
        calendarsFlow.value = testCalendars.take(2)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Verify initial value
        assertEquals(2, viewModel.calendars.value.size)

        // Update calendars
        calendarsFlow.value = testCalendars
        advanceUntilIdle()

        // Verify updated value
        assertEquals(3, viewModel.calendars.value.size)
    }

    // ==================== Notification Permission Tests ====================

    @Test
    fun `notifications enabled by default on older Android`() = runTest {
        // Default mock returns PERMISSION_GRANTED
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.notificationsEnabled.test {
            assertEquals(true, expectMostRecentItem())
        }
    }

    @Test
    fun `refreshNotificationPermission updates state`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Change permission state (would need to mock Build.VERSION.SDK_INT for real test)
        viewModel.refreshNotificationPermission()
        advanceUntilIdle()

        // Should still be true in test environment
        viewModel.notificationsEnabled.test {
            assertEquals(true, expectMostRecentItem())
        }
    }

    // ==================== Preferences Tests ====================

    @Test
    fun `setShowEventEmojis calls dataStore`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setShowEventEmojis(true)
        advanceUntilIdle()

        coVerify { dataStore.setShowEventEmojis(true) }
    }

    @Test
    fun `setShowEventEmojis can be toggled off`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setShowEventEmojis(false)
        advanceUntilIdle()

        coVerify { dataStore.setShowEventEmojis(false) }
    }

    @Test
    fun `setTimeFormat calls dataStore with format string`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setTimeFormat("24h")
        advanceUntilIdle()

        coVerify { dataStore.setTimeFormat("24h") }
    }

    @Test
    fun `setTimeFormat accepts various formats`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setTimeFormat("12h")
        advanceUntilIdle()
        coVerify { dataStore.setTimeFormat("12h") }

        viewModel.setTimeFormat("system")
        advanceUntilIdle()
        coVerify { dataStore.setTimeFormat("system") }
    }

    @Test
    fun `setFirstDayOfWeek calls dataStore`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setFirstDayOfWeek(java.util.Calendar.MONDAY)
        advanceUntilIdle()

        coVerify { dataStore.setFirstDayOfWeek(java.util.Calendar.MONDAY) }
    }

    @Test
    fun `setFirstDayOfWeek accepts sunday`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setFirstDayOfWeek(java.util.Calendar.SUNDAY)
        advanceUntilIdle()

        coVerify { dataStore.setFirstDayOfWeek(java.util.Calendar.SUNDAY) }
    }

    @Test
    fun `onDefaultEventDurationChange calls userPreferences`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDefaultEventDurationChange(30) // 30 minutes
        advanceUntilIdle()

        coVerify { userPreferences.setDefaultEventDuration(30) }
    }

    @Test
    fun `onDefaultEventDurationChange accepts various durations`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDefaultEventDurationChange(60) // 1 hour
        advanceUntilIdle()
        coVerify { userPreferences.setDefaultEventDuration(60) }

        viewModel.onDefaultEventDurationChange(120) // 2 hours
        advanceUntilIdle()
        coVerify { userPreferences.setDefaultEventDuration(120) }
    }

    // ==================== Contact Birthdays Tests ====================

    @Test
    fun `onContactBirthdaysColorChange updates color via eventCoordinator`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val newColor = 0xFFE91E63.toInt() // Pink
        viewModel.onContactBirthdaysColorChange(newColor)
        advanceUntilIdle()

        coVerify { eventCoordinator.updateContactBirthdaysColor(newColor) }
    }

    @Test
    fun `onContactBirthdaysReminderChange calls dataStore`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onContactBirthdaysReminderChange(1440) // 1 day before
        advanceUntilIdle()

        coVerify { dataStore.setBirthdayReminder(1440) }
    }

    @Test
    fun `onContactBirthdaysReminderChange accepts various reminder times`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onContactBirthdaysReminderChange(0) // At time of event
        advanceUntilIdle()
        coVerify { dataStore.setBirthdayReminder(0) }

        viewModel.onContactBirthdaysReminderChange(10080) // 1 week before
        advanceUntilIdle()
        coVerify { dataStore.setBirthdayReminder(10080) }
    }

    @Test
    fun `onToggleContactBirthdays enable triggers sync via onEnabled`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleContactBirthdays(true)
        advanceUntilIdle()

        verify { contactBirthdayManager.onEnabled() }
    }

    @Test
    fun `onToggleContactBirthdays disable calls onDisabled`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleContactBirthdays(false)
        advanceUntilIdle()

        verify { contactBirthdayManager.onDisabled() }
    }

    // ==================== UI Sheet State Tests ====================

    @Test
    fun `showICloudSignInSheet sets state to true`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showICloudSignInSheet()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showICloudSignInSheet)
    }

    @Test
    fun `hideICloudSignInSheet sets state to false`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showICloudSignInSheet()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showICloudSignInSheet)

        viewModel.hideICloudSignInSheet()
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.showICloudSignInSheet)
    }

    @Test
    fun `showSnackbar sets snackbar message`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showSnackbar("Test message")
        advanceUntilIdle()

        assertEquals("Test message", viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `clearSnackbar removes snackbar message`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showSnackbar("Test message")
        advanceUntilIdle()
        assertEquals("Test message", viewModel.uiState.value.pendingSnackbarMessage)

        viewModel.clearSnackbar()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.pendingSnackbarMessage)
    }

    // ==================== Account Connected Sheet Tests ====================

    @Test
    fun `showAccountConnectedSheet sets state correctly`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showAccountConnectedSheet("iCloud", "test@icloud.com", 5)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.showAccountConnectedSheet)
        assertEquals("iCloud", state.connectedProviderName)
        assertEquals("test@icloud.com", state.connectedEmail)
        assertEquals(5, state.connectedCalendarCount)
    }

    @Test
    fun `hideAccountConnectedSheet clears state`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showAccountConnectedSheet("iCloud", "test@icloud.com", 5)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showAccountConnectedSheet)

        viewModel.hideAccountConnectedSheet()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(false, state.showAccountConnectedSheet)
        assertEquals("", state.connectedProviderName)
        assertEquals("", state.connectedEmail)
        assertEquals(0, state.connectedCalendarCount)
    }

    @Test
    fun `onAccountConnectedDone sets pendingFinishActivity`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showAccountConnectedSheet("iCloud", "test@icloud.com", 5)
        advanceUntilIdle()

        viewModel.onAccountConnectedDone()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(false, state.showAccountConnectedSheet)
        assertTrue(state.pendingFinishActivity)
    }

    @Test
    fun `onSignIn shows success sheet when not in initial setup`() = runTest {
        // Mock successful discovery
        coEvery { discoveryService.discoverAndCreateAccount(any(), any()) } returns DiscoveryResult.Success(
            account = testDbAccount,
            calendars = testCalendars
        )
        coEvery { accountRepository.saveCredentials(any(), any()) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        // NOT in initial setup mode (default)
        viewModel.onAppleIdChange("test@icloud.com")
        viewModel.onPasswordChange("xxxx-xxxx-xxxx-xxxx")
        advanceUntilIdle()

        viewModel.onSignIn()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.showAccountConnectedSheet)
        assertEquals("iCloud", state.connectedProviderName)
        assertEquals(3, state.connectedCalendarCount)
        assertEquals(false, state.pendingFinishActivity)
    }

    @Test
    fun `onSignIn skips success sheet in initial setup mode`() = runTest {
        // Mock successful discovery
        coEvery { discoveryService.discoverAndCreateAccount(any(), any()) } returns DiscoveryResult.Success(
            account = testDbAccount,
            calendars = testCalendars
        )
        coEvery { accountRepository.saveCredentials(any(), any()) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Set initial setup mode
        viewModel.setInitialSetupMode(true)
        viewModel.onAppleIdChange("test@icloud.com")
        viewModel.onPasswordChange("xxxx-xxxx-xxxx-xxxx")
        advanceUntilIdle()

        viewModel.onSignIn()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(false, state.showAccountConnectedSheet)
        assertTrue(state.pendingFinishActivity)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `handles empty calendar list gracefully`() = runTest {
        calendarsFlow.value = emptyList()

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.calendars.test {
            assertTrue(expectMostRecentItem().isEmpty())
        }
    }

    @Test
    fun `handles null default calendar gracefully`() = runTest {
        defaultCalendarIdFlow.value = null

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.defaultCalendarId.test {
            assertNull(expectMostRecentItem())
        }
    }

    @Test
    fun `handles credentials with whitespace`() = runTest {
        // Mock successful discovery
        coEvery { discoveryService.discoverAndCreateAccount(any(), any()) } returns DiscoveryResult.Success(
            account = testDbAccount,
            calendars = testCalendars
        )
        coEvery { accountRepository.saveCredentials(any(), any()) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onAppleIdChange("  test@icloud.com  ")
        viewModel.onPasswordChange("  xxxx-xxxx-xxxx-xxxx  ")
        advanceUntilIdle()

        viewModel.onSignIn()
        advanceUntilIdle()

        // Verify trimmed credentials are passed to discovery
        coVerify {
            discoveryService.discoverAndCreateAccount(
                "test@icloud.com",
                "xxxx-xxxx-xxxx-xxxx"
            )
        }
    }
}
