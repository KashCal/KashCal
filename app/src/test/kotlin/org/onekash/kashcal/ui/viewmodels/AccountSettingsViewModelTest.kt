package org.onekash.kashcal.ui.viewmodels

import app.cash.turbine.test
import io.mockk.Ordering
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import org.onekash.kashcal.data.calendar_provider.CalendarProviderManager
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.contacts.ContactEventManager
import org.onekash.kashcal.data.contacts.ContactEventSyncResult
import org.onekash.kashcal.data.credential.AccountCredentials
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.db.entity.SyncLog
import org.onekash.kashcal.data.ics.IcsSubscriptionRepository
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.preferences.UserPreferencesRepository
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.SyncLogReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler
import org.onekash.kashcal.sync.discovery.AccountDiscoveryService
import org.onekash.kashcal.sync.discovery.DiscoveredCalendar
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.onekash.kashcal.sync.provider.caldav.CalDavAccountDiscoveryService
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus
import org.onekash.kashcal.ui.screens.settings.AccountDetailDiscoverStatus
import org.onekash.kashcal.ui.screens.settings.AccountDetailSyncStatus
import org.onekash.kashcal.ui.screens.settings.ContactSyncConfirmation
import org.onekash.kashcal.ui.screens.settings.ICloudConnectionState
import org.onekash.kashcal.ui.shared.EventColorPalette
import org.onekash.kashcal.widget.WidgetUpdateManager
import java.util.UUID

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

    // Independent scheduler so tests can advance viewModelScope and
    // applicationScope separately (issue #133 — proves the deferred commit
    // is queued on a process-lifetime scope, not viewModelScope).
    private val appDispatcher = StandardTestDispatcher()
    private val applicationScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + appDispatcher
    )

    // Mocks
    private lateinit var accountRepository: AccountRepository
    private lateinit var userPreferences: UserPreferencesRepository
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var discoveryService: AccountDiscoveryService
    private lateinit var calDavDiscoveryService: CalDavAccountDiscoveryService
    private lateinit var eventCoordinator: EventCoordinator
    private lateinit var syncLogReader: SyncLogReader
    private lateinit var contactEventManager: ContactEventManager
    private lateinit var calendarProviderManager: CalendarProviderManager
    private lateinit var calendarProviderRepository: CalendarProviderRepository
    private lateinit var dataStore: KashCalDataStore
    private lateinit var widgetUpdateManager: WidgetUpdateManager
    private lateinit var eventWriter: EventWriter
    private lateinit var deviceCalendarReminderScheduler: DeviceCalendarReminderScheduler
    private lateinit var backupExporter: org.onekash.kashcal.domain.backup.SettingsBackupExporter
    private lateinit var backupImporter: org.onekash.kashcal.domain.backup.SettingsBackupImporter
    private lateinit var permissionChecker: org.onekash.kashcal.ui.permission.FakePermissionChecker
    private lateinit var icsScheduler: org.onekash.kashcal.sync.scheduler.FakeIcsScheduler

    // Flows we control
    private lateinit var calendarsFlow: MutableStateFlow<List<Calendar>>
    private lateinit var iCloudCalendarCountFlow: MutableStateFlow<Int>
    private lateinit var calDavAccountCountFlow: MutableStateFlow<Int>
    private lateinit var defaultCalendarIdFlow: MutableStateFlow<Long?>
    private lateinit var defaultCalendarFlow: MutableStateFlow<DefaultCalendar?>
    private lateinit var syncIntervalFlow: MutableStateFlow<Long>
    private lateinit var defaultReminderTimedFlow: MutableStateFlow<Int>
    private lateinit var defaultReminderAllDayFlow: MutableStateFlow<Int>
    private lateinit var syncLogsFlow: MutableStateFlow<List<SyncLog>>
    private lateinit var contactBirthdaysEnabledFlow: MutableStateFlow<Boolean>
    private lateinit var contactBirthdaysLastSyncFlow: MutableStateFlow<Long>
    private lateinit var contactAnniversariesEnabledFlow: MutableStateFlow<Boolean>
    private lateinit var contactAnniversariesLastSyncFlow: MutableStateFlow<Long>
    private lateinit var birthdayReminderFlow: MutableStateFlow<Int>
    private lateinit var anniversaryReminderFlow: MutableStateFlow<Int>
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
        accountRepository = mockk(relaxed = true)
        userPreferences = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)
        discoveryService = mockk(relaxed = true)
        calDavDiscoveryService = mockk(relaxed = true)
        eventCoordinator = mockk(relaxed = true)
        syncLogReader = mockk(relaxed = true)
        contactEventManager = mockk(relaxed = true)
        calendarProviderManager = mockk(relaxed = true)
        calendarProviderRepository = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        widgetUpdateManager = mockk(relaxed = true)
        eventWriter = mockk(relaxed = true)
        deviceCalendarReminderScheduler = mockk(relaxed = true)
        backupExporter = mockk(relaxed = true)
        backupImporter = mockk(relaxed = true)
        permissionChecker = org.onekash.kashcal.ui.permission.FakePermissionChecker()
        icsScheduler = org.onekash.kashcal.sync.scheduler.FakeIcsScheduler()

        // Setup flows
        calendarsFlow = MutableStateFlow(emptyList())
        iCloudCalendarCountFlow = MutableStateFlow(0)
        calDavAccountCountFlow = MutableStateFlow(0)
        defaultCalendarIdFlow = MutableStateFlow(null)
        defaultCalendarFlow = MutableStateFlow(null)
        syncIntervalFlow = MutableStateFlow(24 * 60 * 60 * 1000L) // 24 hours
        defaultReminderTimedFlow = MutableStateFlow(15)
        defaultReminderAllDayFlow = MutableStateFlow(1440)
        syncLogsFlow = MutableStateFlow(emptyList())
        contactBirthdaysEnabledFlow = MutableStateFlow(false)
        contactBirthdaysLastSyncFlow = MutableStateFlow(0L)
        contactAnniversariesEnabledFlow = MutableStateFlow(false)
        contactAnniversariesLastSyncFlow = MutableStateFlow(0L)
        birthdayReminderFlow = MutableStateFlow(540)
        anniversaryReminderFlow = MutableStateFlow(540)
        defaultEventDurationFlow = MutableStateFlow(60) // Default 60 minutes

        // Setup default behaviors - EventCoordinator for calendars (architecture compliant)
        // IMPORTANT: ViewModel uses combine() on getAllCalendars + getAllAccounts + defaultCalendarId
        every { eventCoordinator.getAllCalendars() } returns calendarsFlow
        every { eventCoordinator.getAllAccounts() } returns flowOf(emptyList())
        every { eventCoordinator.getICloudCalendarCount() } returns iCloudCalendarCountFlow
        every { eventCoordinator.getCalDavAccountCount() } returns calDavAccountCountFlow
        every { userPreferences.defaultCalendarId } returns defaultCalendarIdFlow
        every { userPreferences.defaultCalendar } returns defaultCalendarFlow
        every { userPreferences.syncIntervalMs } returns syncIntervalFlow
        every { userPreferences.defaultReminderTimed } returns defaultReminderTimedFlow
        every { userPreferences.defaultReminderAllDay } returns defaultReminderAllDayFlow
        every { userPreferences.defaultEventDuration } returns defaultEventDurationFlow
        every { syncLogReader.getRecentLogs(any()) } returns syncLogsFlow

        // Default: no iCloud account configured
        coEvery { accountRepository.getAccountsByProvider(AccountProvider.ICLOUD) } returns emptyList()
        coEvery { accountRepository.hasCredentials(any()) } returns false

        // Mock ICS subscriptions flow
        every { eventCoordinator.getAllIcsSubscriptions() } returns flowOf(emptyList())

        // Mock contact birthdays flows
        every { dataStore.contactBirthdaysEnabled } returns contactBirthdaysEnabledFlow
        every { dataStore.contactBirthdaysLastSync } returns contactBirthdaysLastSyncFlow
        every { dataStore.birthdayReminder } returns birthdayReminderFlow
        coEvery { eventCoordinator.getContactBirthdaysColor() } returns null

        // Mock contact anniversaries flows
        every { dataStore.contactAnniversariesEnabled } returns contactAnniversariesEnabledFlow
        every { dataStore.contactAnniversariesLastSync } returns contactAnniversariesLastSyncFlow
        every { dataStore.anniversaryReminder } returns anniversaryReminderFlow
        coEvery { eventCoordinator.getContactAnniversariesColor() } returns null
        coEvery { eventCoordinator.getContactBirthdayEventCount() } returns 0
        coEvery { eventCoordinator.getContactAnniversaryEventCount() } returns 0

        // Accent color source/seed defaults (relaxed mock would return an empty flow, which
        // would stall collectors). Explicit per de-relax guidance.
        every { dataStore.theme } returns flowOf(KashCalDataStore.THEME_SYSTEM)
        every { dataStore.colorSource } returns flowOf(null)
        every { dataStore.accentSeed } returns flowOf(KashCalDataStore.ACCENT_SEED_DEFAULT)
        every { userPreferences.resolvedColorSource } returns flowOf(org.onekash.kashcal.ui.theme.ColorSource.DYNAMIC)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): AccountSettingsViewModel {
        // Stub context.getString(...) to return the resource id's symbolic name so
        // assertions on Exception.message remain stable under the relaxed mock.
        val stubContext: android.content.Context = io.mockk.mockk(relaxed = true) {
            every { getString(org.onekash.kashcal.R.string.password_change_error_account_not_found) } returns "Account not found"
            every { getString(org.onekash.kashcal.R.string.password_change_error_no_credentials) } returns "No existing credentials"
            every { getString(org.onekash.kashcal.R.string.password_change_error_unsupported_provider) } returns "Provider does not support password change"
            every { getString(org.onekash.kashcal.R.string.password_change_error_invalid) } returns "Invalid password"
            every { getString(org.onekash.kashcal.R.string.password_change_error_network) } returns "Network error, try again"
            every { getString(org.onekash.kashcal.R.string.contact_sync_enabled_for, "u***@example.com") } returns "Syncing contacts for u***@example.com"
            every { getString(org.onekash.kashcal.R.string.contact_sync_disabled_for, "u***@example.com") } returns "Device contacts for u***@example.com removed"
            every { getString(org.onekash.kashcal.R.string.contact_sync_disabled_kept, "u***@example.com") } returns "Contact sync off for u***@example.com. Contacts stay because another login still syncs them."
        }
        return AccountSettingsViewModel(
            accountRepository = accountRepository,
            userPreferences = userPreferences,
            syncScheduler = syncScheduler,
            discoveryService = discoveryService,
            calDavDiscoveryService = calDavDiscoveryService,
            eventCoordinator = eventCoordinator,
            syncLogReader = syncLogReader,
            contactEventManager = contactEventManager,
            calendarProviderManager = calendarProviderManager,
            calendarProviderRepository = calendarProviderRepository,
            dataStore = dataStore,
            widgetUpdateManager = widgetUpdateManager,
            eventWriter = eventWriter,
            deviceCalendarReminderScheduler = deviceCalendarReminderScheduler,
            backupExporter = backupExporter,
            backupImporter = backupImporter,
            permissionChecker = permissionChecker,
            icsScheduler = icsScheduler,
            context = stubContext,
            applicationScope = applicationScope,
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
    fun `themeMode maps a stored value with no default seed`() = runTest {
        // Cold flow: first emission is the stored value itself (no SYSTEM seed), which is what
        // lets the activity avoid a flash of the default theme on cold start.
        every { dataStore.theme } returns flowOf(KashCalDataStore.THEME_DARK)
        val viewModel = createViewModel()

        viewModel.themeMode.test {
            assertEquals(org.onekash.kashcal.ui.theme.ThemeMode.DARK, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `themeMode maps an unknown stored value to SYSTEM`() = runTest {
        every { dataStore.theme } returns flowOf("some-future-theme")
        val viewModel = createViewModel()

        viewModel.themeMode.test {
            assertEquals(org.onekash.kashcal.ui.theme.ThemeMode.SYSTEM, awaitItem())
            awaitComplete()
        }
    }

    // ---- accent color source + seed ----

    @Test
    fun `colorSource reflects the repository's resolved source`() = runTest {
        // The migration/resolution logic lives in UserPreferencesRepository.resolvedColorSource
        // (covered by ColorSourceTest); the VM simply surfaces it.
        every { userPreferences.resolvedColorSource } returns flowOf(org.onekash.kashcal.ui.theme.ColorSource.SEED)
        val viewModel = createViewModel()

        viewModel.colorSource.test {
            assertEquals(org.onekash.kashcal.ui.theme.ColorSource.SEED, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `accentSeed exposes the stored seed`() = runTest {
        every { dataStore.accentSeed } returns flowOf(0xFFFF6347.toInt())
        val viewModel = createViewModel()

        viewModel.accentSeed.test {
            assertEquals(0xFFFF6347.toInt(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `contactBirthdaysColor seeds to a palette entry`() = runTest {
        val paletteArgbs = EventColorPalette.entries.map { it.argb }.toSet()
        val viewModel = createViewModel()

        assertTrue(
            "contactBirthdaysColor seed must be a palette entry",
            viewModel.contactBirthdaysColor.value in paletteArgbs
        )
    }

    @Test
    fun `contactAnniversariesColor seeds to a palette entry`() = runTest {
        val paletteArgbs = EventColorPalette.entries.map { it.argb }.toSet()
        val viewModel = createViewModel()

        assertTrue(
            "contactAnniversariesColor seed must be a palette entry",
            viewModel.contactAnniversariesColor.value in paletteArgbs
        )
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
            assertEquals(
                org.onekash.kashcal.ui.util.UiMessage.ResId(
                    org.onekash.kashcal.R.string.icloud_error_credentials_required
                ),
                (state.iCloudState as ICloudConnectionState.NotConnected).error
            )
        }
    }

    @Test
    fun `onSignIn does not log the full Apple ID`() = runTest {
        val appleId = "jane.appleseed@icloud.com"
        coEvery { discoveryService.discoverAndCreateAccount(any(), any()) } returns DiscoveryResult.Success(
            account = testDbAccount,
            calendars = testCalendars
        )

        io.mockk.mockkStatic(android.util.Log::class)
        try {
            val logMessages = mutableListOf<String>()
            every { android.util.Log.v(any(), any<String>()) } returns 0
            every { android.util.Log.i(any(), capture(logMessages)) } returns 0
            every { android.util.Log.d(any(), any<String>()) } returns 0
            every { android.util.Log.w(any(), any<String>()) } returns 0
            every { android.util.Log.e(any(), any<String>()) } returns 0
            every { android.util.Log.e(any(), any<String>(), any()) } returns 0

            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.onAppleIdChange(appleId)
            viewModel.onPasswordChange("xxxx-xxxx-xxxx-xxxx")
            advanceUntilIdle()

            viewModel.onSignIn()
            advanceUntilIdle()

            // Guard against a vacuous pass: the discovery log line must actually
            // have been emitted, and it must carry the masked form, not the raw id.
            assertTrue(
                "Expected a discovery log line to be emitted; captured: $logMessages",
                logMessages.any { it.contains("Starting iCloud discovery") }
            )
            assertTrue(
                "No log line should contain the unmasked Apple ID; captured: $logMessages",
                logMessages.none { it.contains(appleId) }
            )
            assertTrue(
                "Discovery log should contain the masked Apple ID; captured: $logMessages",
                logMessages.any { it.contains("jan***@***.com") }
            )
        } finally {
            io.mockk.unmockkStatic(android.util.Log::class)
        }
    }

    @Test
    fun `onSignIn shows Connected on successful discovery`() = runTest {
        // Mock successful discovery (credentials are saved inside discovery service)
        coEvery { discoveryService.discoverAndCreateAccount(any(), any()) } returns DiscoveryResult.Success(
            account = testDbAccount,
            calendars = testCalendars
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
            assertTrue(state.iCloudState is ICloudConnectionState.Connected)
            assertEquals("test@icloud.com", (state.iCloudState as ICloudConnectionState.Connected).appleId)
        }
    }

    @Test
    fun `onSignIn triggers account-scoped sync`() = runTest {
        // Mock successful discovery (credentials saved inside discovery service)
        coEvery { discoveryService.discoverAndCreateAccount(any(), any()) } returns DiscoveryResult.Success(
            account = testDbAccount,
            calendars = testCalendars
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onAppleIdChange("test@icloud.com")
        viewModel.onPasswordChange("xxxx-xxxx-xxxx-xxxx")
        advanceUntilIdle()

        viewModel.onSignIn()
        advanceUntilIdle()

        verify { syncScheduler.syncAccount(testDbAccount.id, forceFullSync = true) }
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
            val error = (state.iCloudState as ICloudConnectionState.NotConnected).error
            assertEquals(
                org.onekash.kashcal.ui.util.UiMessage.Literal("Invalid credentials"),
                error
            )
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
            assertEquals(
                org.onekash.kashcal.ui.util.UiMessage.ResId(
                    org.onekash.kashcal.R.string.icloud_error_connection_timeout
                ),
                error
            )
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
            val error = (state.iCloudState as ICloudConnectionState.NotConnected).error
            assertEquals(
                org.onekash.kashcal.ui.util.UiMessage.Literal("Network error"),
                error
            )
        }
    }

    @Test
    fun `onCalDavDisplayNameChange with duplicate name emits UiMessage ResId with name arg`() = runTest {
        coEvery { calDavDiscoveryService.isDisplayNameAvailable("Personal") } returns false

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onCalDavDisplayNameChange("Personal")
        // Past the 300ms debounce in onCalDavDisplayNameChange
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = expectMostRecentItem()
            val calDavState = state.calDavState
            assertTrue(calDavState is org.onekash.kashcal.ui.screens.settings.CalDavConnectionState.NotConnected)
            val error = (calDavState as org.onekash.kashcal.ui.screens.settings.CalDavConnectionState.NotConnected).error
            assertEquals(
                org.onekash.kashcal.ui.util.UiMessage.ResId(
                    org.onekash.kashcal.R.string.error_display_name_exists,
                    listOf("Personal")
                ),
                error
            )
            assertEquals(
                org.onekash.kashcal.ui.screens.settings.CalDavConnectionState.ErrorField.DISPLAY_NAME,
                calDavState.errorField
            )
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
    }

    @Test
    fun `onAddSubscription success schedules periodic ICS refresh via icsScheduler`() = runTest {
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

        viewModel.onAddSubscription("https://example.com/holidays.ics", "US Holidays", 0xFFFF5722.toInt())
        advanceUntilIdle()

        assertEquals(
            listOf(org.onekash.kashcal.sync.scheduler.IcsScheduler.DEFAULT_INTERVAL_HOURS),
            icsScheduler.scheduleCalls
        )
    }

    @Test
    fun `onAddSubscription error does not schedule ICS refresh`() = runTest {
        coEvery { eventCoordinator.addIcsSubscription(any(), any(), any()) } returns
            IcsSubscriptionRepository.SubscriptionResult.Error("bad url")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onAddSubscription("https://example.com/holidays.ics", "US Holidays", 0xFFFF5722.toInt())
        advanceUntilIdle()

        assertTrue(
            "scheduler should not be invoked on error; got ${icsScheduler.scheduleCalls}",
            icsScheduler.scheduleCalls.isEmpty()
        )
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
    fun `onAddSubscription duplicate URL surfaces the localized message via snackbar`() = runTest {
        coEvery { eventCoordinator.addIcsSubscription(any(), any(), any()) } returns
            IcsSubscriptionRepository.SubscriptionResult.Error(
                message = "Subscription already exists for this URL",
                isDuplicate = true
            )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onAddSubscription(
            url = "https://example.com/holidays.ics",
            name = "Holidays",
            color = 0xFFFF5722.toInt(),
            duplicateUrlMessage = "Already subscribed to this URL"
        )
        advanceUntilIdle()

        assertEquals(
            "Already subscribed to this URL",
            viewModel.uiState.value.pendingSnackbarMessage
        )
    }

    @Test
    fun `onAddSubscription generic error does not surface duplicate message`() = runTest {
        coEvery { eventCoordinator.addIcsSubscription(any(), any(), any()) } returns
            IcsSubscriptionRepository.SubscriptionResult.Error(
                message = "Some other failure",
                isDuplicate = false
            )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onAddSubscription(
            url = "https://example.com/holidays.ics",
            name = "Holidays",
            color = 0xFFFF5722.toInt(),
            duplicateUrlMessage = "Already subscribed to this URL"
        )
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.pendingSnackbarMessage)
        assertTrue(
            "scheduler must not be invoked on error path",
            icsScheduler.scheduleCalls.isEmpty()
        )
    }

    @Test
    fun `onAddSubscription duplicate with null message stays silent (no snackbar, no scheduler)`() = runTest {
        // Locks in the contract: if the caller forgets to pass
        // duplicateUrlMessage, a duplicate is logged but does NOT
        // surface a snackbar (won't crash, won't show wrong text)
        // and the periodic refresh scheduler is NOT invoked.
        coEvery { eventCoordinator.addIcsSubscription(any(), any(), any()) } returns
            IcsSubscriptionRepository.SubscriptionResult.Error(
                message = "Subscription already exists for this URL",
                isDuplicate = true
            )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onAddSubscription(
            url = "https://example.com/holidays.ics",
            name = "Holidays",
            color = 0xFFFF5722.toInt(),
            // duplicateUrlMessage omitted — exercises the null default
        )
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.pendingSnackbarMessage)
        assertTrue(
            "scheduler must not be invoked on error path",
            icsScheduler.scheduleCalls.isEmpty()
        )
    }

    // ==================== ICS Subscription delete-with-undo (issue #133) ====================

    /**
     * Build the underlying-flow MutableStateFlow used to drive the
     * eventCoordinator.getAllIcsSubscriptions() mock for this group of tests.
     * Each test calls this once before createViewModel() so the VM observes it.
     */
    private fun buildSubscriptionFlow(
        initial: List<IcsSubscription> = emptyList()
    ): MutableStateFlow<List<IcsSubscription>> {
        val flow = MutableStateFlow(initial)
        every { eventCoordinator.getAllIcsSubscriptions() } returns flow
        return flow
    }

    private fun sampleSubscription(id: Long, name: String = "sub-$id"): IcsSubscription =
        IcsSubscription(
            id = id,
            url = "https://example.com/$id.ics",
            name = name,
            color = 0xFF2196F3.toInt(),
            calendarId = 100L + id
        )

    @Test
    fun `onDeleteSubscription stages pending and does not commit`() = runTest {
        buildSubscriptionFlow(listOf(sampleSubscription(42L)))
        coEvery { eventCoordinator.removeIcsSubscription(any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDeleteSubscription(
            subscriptionId = 42L,
            removedMessage = "Subscription removed",
            undoActionLabel = "Undo"
        )
        advanceUntilIdle()

        // Pending state set; coordinator NOT called yet.
        assertEquals(42L, viewModel.uiState.value.pendingSubscriptionDeletionId)
        coVerify(exactly = 0) { eventCoordinator.removeIcsSubscription(any()) }
    }

    @Test
    fun `onDeleteSubscription emits snackbar with action and label`() = runTest {
        buildSubscriptionFlow(listOf(sampleSubscription(42L)))
        coEvery { eventCoordinator.removeIcsSubscription(any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDeleteSubscription(
            subscriptionId = 42L,
            removedMessage = "Subscription removed",
            undoActionLabel = "Undo"
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.pendingSnackbarMessage)
        assertNotNull(state.pendingSnackbarActionLabel)
        assertNotNull(state.pendingSnackbarAction)
    }

    @Test
    fun `onDeleteSubscription filters pending id from subscriptions flow`() = runTest {
        val raw = buildSubscriptionFlow(
            listOf(sampleSubscription(42L), sampleSubscription(43L))
        )
        coEvery { eventCoordinator.removeIcsSubscription(any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()
        // Both visible before swipe
        assertEquals(2, viewModel.subscriptions.value.size)

        viewModel.onDeleteSubscription(
            subscriptionId = 42L,
            removedMessage = "Subscription removed",
            undoActionLabel = "Undo"
        )
        advanceUntilIdle()

        val ids = viewModel.subscriptions.value.map { it.id }
        assertFalse("pending id 42 must be filtered", ids.contains(42L))
        assertTrue("non-pending id 43 must remain", ids.contains(43L))
        // Sanity: keep raw flow as untouched evidence
        assertEquals(2, raw.value.size)
    }

    @Test
    fun `onUndoSubscriptionDeletion clears pending and does not commit`() = runTest {
        buildSubscriptionFlow(listOf(sampleSubscription(42L)))
        coEvery { eventCoordinator.removeIcsSubscription(any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDeleteSubscription(
            subscriptionId = 42L,
            removedMessage = "Subscription removed",
            undoActionLabel = "Undo"
        )
        advanceUntilIdle()
        viewModel.onUndoSubscriptionDeletion()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.pendingSubscriptionDeletionId)
        // Subscription is back in the displayed list
        assertTrue(viewModel.subscriptions.value.any { it.id == 42L })
        // Coordinator NEVER called
        coVerify(exactly = 0) { eventCoordinator.removeIcsSubscription(any()) }
    }

    @Test
    fun `onSubscriptionDeletionSettled commits pending via coordinator and clears state`() = runTest {
        buildSubscriptionFlow(listOf(sampleSubscription(42L)))
        coEvery { eventCoordinator.removeIcsSubscription(any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDeleteSubscription(
            subscriptionId = 42L,
            removedMessage = "Subscription removed",
            undoActionLabel = "Undo"
        )
        advanceUntilIdle()
        viewModel.onSubscriptionDeletionSettled()
        advanceUntilIdle()
        // Commit runs on applicationScope; drain its scheduler to observe it.
        appDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { eventCoordinator.removeIcsSubscription(42L) }
        assertNull(viewModel.uiState.value.pendingSubscriptionDeletionId)
    }

    @Test
    fun `commit runs on applicationScope so it survives ViewModel destruction`() = runTest {
        // Proves the deferred commit is launched on a scope that outlives the
        // ViewModel — required for the scenario where the user swipes and
        // immediately exits the Activity (issue #133, v23.7.8).
        buildSubscriptionFlow(listOf(sampleSubscription(42L)))
        coEvery { eventCoordinator.removeIcsSubscription(any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDeleteSubscription(
            subscriptionId = 42L,
            removedMessage = "Subscription removed",
            undoActionLabel = "Undo"
        )
        advanceUntilIdle()
        viewModel.onSubscriptionDeletionSettled()
        // Drain ONLY the viewModelScope's scheduler. If the commit were on
        // viewModelScope, it would run here.
        advanceUntilIdle()

        coVerify(exactly = 0) { eventCoordinator.removeIcsSubscription(any()) }

        // Now drain the applicationScope's scheduler. The commit must fire here.
        appDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { eventCoordinator.removeIcsSubscription(42L) }
    }

    @Test
    fun `eager-replace commit also runs on applicationScope`() = runTest {
        // The "swipe B while A is still pending" path must also commit A on
        // applicationScope, not viewModelScope (issue #133, v23.7.8).
        buildSubscriptionFlow(
            listOf(sampleSubscription(42L), sampleSubscription(43L))
        )
        coEvery { eventCoordinator.removeIcsSubscription(any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDeleteSubscription(42L, "Removed", "Undo")
        advanceUntilIdle()
        viewModel.onDeleteSubscription(43L, "Removed", "Undo")
        // Drain viewModelScope. The eager-replace commit for 42 must be queued
        // on applicationScope, not fired here.
        advanceUntilIdle()
        coVerify(exactly = 0) { eventCoordinator.removeIcsSubscription(42L) }

        appDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { eventCoordinator.removeIcsSubscription(42L) }
    }

    @Test
    fun `settle after undo is a no-op`() = runTest {
        buildSubscriptionFlow(listOf(sampleSubscription(42L)))
        coEvery { eventCoordinator.removeIcsSubscription(any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDeleteSubscription(
            subscriptionId = 42L,
            removedMessage = "Subscription removed",
            undoActionLabel = "Undo"
        )
        advanceUntilIdle()
        viewModel.onUndoSubscriptionDeletion()
        advanceUntilIdle()
        // Material may fire Dismissed after ActionPerformed; settle must no-op.
        viewModel.onSubscriptionDeletionSettled()
        advanceUntilIdle()

        coVerify(exactly = 0) { eventCoordinator.removeIcsSubscription(any()) }
    }

    @Test
    fun `ViewModel destruction commits any pending subscription deletion`() = runTest {
        // Reproduces the v23.7.8 user report: swipe a subscription, then exit
        // the Activity (back twice → finish() → ViewModel.onCleared) before
        // the snackbar times out. The deferred commit must still fire.
        buildSubscriptionFlow(listOf(sampleSubscription(42L)))
        coEvery { eventCoordinator.removeIcsSubscription(any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDeleteSubscription(
            subscriptionId = 42L,
            removedMessage = "Subscription removed",
            undoActionLabel = "Undo"
        )
        advanceUntilIdle()

        // Snackbar's LaunchedEffect coroutine throws CancellationException
        // when the Activity is destroyed; SnackbarResult.Dismissed never
        // fires. The commit must come from onCleared instead.
        viewModel.onClearedForTest()
        advanceUntilIdle()
        appDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { eventCoordinator.removeIcsSubscription(42L) }
    }

    @Test
    fun `ViewModel destruction with no pending deletion is a no-op`() = runTest {
        buildSubscriptionFlow(emptyList())
        coEvery { eventCoordinator.removeIcsSubscription(any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onClearedForTest()
        advanceUntilIdle()
        appDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { eventCoordinator.removeIcsSubscription(any()) }
    }

    @Test
    fun `ViewModel destruction after undo does not commit`() = runTest {
        // After undo, pending state is cleared; ViewModel destruction must
        // NOT commit a stale value.
        buildSubscriptionFlow(listOf(sampleSubscription(42L)))
        coEvery { eventCoordinator.removeIcsSubscription(any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDeleteSubscription(42L, "Removed", "Undo")
        advanceUntilIdle()
        viewModel.onUndoSubscriptionDeletion()
        advanceUntilIdle()
        viewModel.onClearedForTest()
        advanceUntilIdle()
        appDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { eventCoordinator.removeIcsSubscription(any()) }
    }

    @Test
    fun `second swipe while pending commits first then stages second`() = runTest {
        buildSubscriptionFlow(
            listOf(sampleSubscription(42L), sampleSubscription(43L))
        )
        coEvery { eventCoordinator.removeIcsSubscription(any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDeleteSubscription(
            subscriptionId = 42L,
            removedMessage = "Subscription removed",
            undoActionLabel = "Undo"
        )
        advanceUntilIdle()
        viewModel.onDeleteSubscription(
            subscriptionId = 43L,
            removedMessage = "Subscription removed",
            undoActionLabel = "Undo"
        )
        advanceUntilIdle()
        // Eager-replace commit runs on applicationScope.
        appDispatcher.scheduler.advanceUntilIdle()

        // First (42) committed; second (43) staged.
        coVerify(exactly = 1) { eventCoordinator.removeIcsSubscription(42L) }
        assertEquals(43L, viewModel.uiState.value.pendingSubscriptionDeletionId)
    }

    @Test
    fun `pending clears if underlying flow removes the pending id mid-window`() = runTest {
        val raw = buildSubscriptionFlow(
            listOf(sampleSubscription(42L), sampleSubscription(43L))
        )
        coEvery { eventCoordinator.removeIcsSubscription(any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDeleteSubscription(
            subscriptionId = 42L,
            removedMessage = "Subscription removed",
            undoActionLabel = "Undo"
        )
        advanceUntilIdle()
        // External actor (server-side delete) removes id=42 from underlying flow.
        raw.value = listOf(sampleSubscription(43L))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.pendingSubscriptionDeletionId)

        // A subsequent settle is now a no-op.
        viewModel.onSubscriptionDeletionSettled()
        advanceUntilIdle()
        coVerify(exactly = 0) { eventCoordinator.removeIcsSubscription(any()) }
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
    fun `setWidgetMaxEventsPerDay saves preference and triggers widget update`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setWidgetMaxEventsPerDay(10)
        advanceUntilIdle()

        coVerify { dataStore.setWidgetMaxEventsPerDay(10) }
        coVerify { widgetUpdateManager.updateAllWidgets("widget_max_events_changed") }
    }

    @Test
    fun `setFirstDayOfWeek calls dataStore and refreshes widgets`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setFirstDayOfWeek(java.util.Calendar.MONDAY)
        advanceUntilIdle()

        coVerify { dataStore.setFirstDayOfWeek(java.util.Calendar.MONDAY) }
        // The month/week widgets lay out from the first-day-of-week, so the change must reach
        // them now rather than waiting for the next periodic update.
        coVerify { widgetUpdateManager.updateAllWidgets(any()) }
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
    fun `setShowWeekNumbers saves preference and refreshes widgets`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setShowWeekNumbers(true)
        advanceUntilIdle()

        coVerify { dataStore.setShowWeekNumbers(true) }
        // The month widget's week-number gutter is driven by this preference, so the toggle must
        // refresh the widgets immediately, not on the next periodic tick.
        coVerify { widgetUpdateManager.updateAllWidgets(any()) }
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

        verify { contactEventManager.onBirthdaysEnabled() }
    }

    @Test
    fun `onToggleContactBirthdays enable calls syncContactBirthdays in-process`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleContactBirthdays(true)
        advanceUntilIdle()

        coVerify { eventCoordinator.syncContactBirthdays() }
    }

    @Test
    fun `onToggleContactBirthdays disable calls onDisabled`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleContactBirthdays(false)
        advanceUntilIdle()

        verify { contactEventManager.onBirthdaysDisabled() }
    }

    // ==================== Contact Anniversaries Tests ====================

    @Test
    fun `onToggleContactAnniversaries enable creates calendar and calls manager`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleContactAnniversaries(true)
        advanceUntilIdle()

        coVerify { eventCoordinator.enableContactAnniversaries(any()) }
        coVerify { dataStore.setContactAnniversariesEnabled(true) }
        verify { contactEventManager.onAnniversariesEnabled() }
    }

    @Test
    fun `onToggleContactAnniversaries enable calls syncContactAnniversaries in-process`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleContactAnniversaries(true)
        advanceUntilIdle()

        coVerify { eventCoordinator.syncContactAnniversaries() }
    }

    @Test
    fun `onToggleContactAnniversaries disable removes calendar and calls manager`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleContactAnniversaries(false)
        advanceUntilIdle()

        coVerify { dataStore.setContactAnniversariesEnabled(false) }
        coVerify { dataStore.setContactAnniversariesLastSync(0L) }
        verify { contactEventManager.onAnniversariesDisabled() }
        coVerify { eventCoordinator.disableContactAnniversaries() }
    }

    @Test
    fun `onContactAnniversariesColorChange updates color via coordinator`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val newColor = 0xFFE91E63.toInt()
        viewModel.onContactAnniversariesColorChange(newColor)
        advanceUntilIdle()

        coVerify { eventCoordinator.updateContactAnniversariesColor(newColor) }
    }

    @Test
    fun `onContactAnniversariesReminderChange updates DataStore`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onContactAnniversariesReminderChange(1440)
        advanceUntilIdle()

        coVerify { dataStore.setAnniversaryReminder(1440) }
    }

    // ==================== Reminder-change immediate-sync propagation ====================

    @Test
    fun `onContactBirthdaysReminderChange triggers syncContactBirthdays when enabled`() = runTest {
        contactBirthdaysEnabledFlow.value = true
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onContactBirthdaysReminderChange(30)
        advanceUntilIdle()

        coVerify { dataStore.setBirthdayReminder(30) }
        coVerify { eventCoordinator.syncContactBirthdays() }
    }

    @Test
    fun `onContactBirthdaysReminderChange does not sync when feature disabled`() = runTest {
        contactBirthdaysEnabledFlow.value = false
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onContactBirthdaysReminderChange(30)
        advanceUntilIdle()

        coVerify { dataStore.setBirthdayReminder(30) }
        coVerify(exactly = 0) { eventCoordinator.syncContactBirthdays() }
    }

    @Test
    fun `onContactAnniversariesReminderChange triggers syncContactAnniversaries when enabled`() = runTest {
        contactAnniversariesEnabledFlow.value = true
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onContactAnniversariesReminderChange(60)
        advanceUntilIdle()

        coVerify { dataStore.setAnniversaryReminder(60) }
        coVerify { eventCoordinator.syncContactAnniversaries() }
    }

    @Test
    fun `onContactAnniversariesReminderChange does not sync when feature disabled`() = runTest {
        contactAnniversariesEnabledFlow.value = false
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onContactAnniversariesReminderChange(60)
        advanceUntilIdle()

        coVerify { dataStore.setAnniversaryReminder(60) }
        coVerify(exactly = 0) { eventCoordinator.syncContactAnniversaries() }
    }

    @Test
    fun `rapid onContactBirthdaysReminderChange calls cancel prior in-flight sync`() = runTest {
        contactBirthdaysEnabledFlow.value = true
        // Make sync slow so the second call cancels the first before it reaches sync
        coEvery { eventCoordinator.syncContactBirthdays() } coAnswers {
            delay(1_000L)
            ContactEventSyncResult.Success(0, 0, 0)
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onContactBirthdaysReminderChange(30)
        viewModel.onContactBirthdaysReminderChange(60)
        advanceUntilIdle()

        // The first coroutine is cancelled before it runs (queued on StandardTestDispatcher,
        // cancelled by the second call before either reaches its first suspension point).
        // Only the most recent value is persisted, and only one sync call fires.
        coVerify(exactly = 0) { dataStore.setBirthdayReminder(30) }
        coVerify(exactly = 1) { dataStore.setBirthdayReminder(60) }
        coVerify(exactly = 1) { eventCoordinator.syncContactBirthdays() }
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

    // ==================== Account-Scoped Sync Tests (Bug 1 regression) ====================

    @Test
    fun `iCloud sign-in syncs only new account not all accounts`() = runTest {
        val iCloudAccount = testDbAccount.copy(id = 7L)
        coEvery { discoveryService.discoverAndCreateAccount(any(), any()) } returns DiscoveryResult.Success(
            account = iCloudAccount,
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

        // Should sync only the new account
        verify { syncScheduler.syncAccount(7L, forceFullSync = true) }
        // Should NOT trigger a global sync of all accounts
        verify(exactly = 0) { syncScheduler.requestImmediateSync(any()) }
    }

    @Test
    fun `CalDAV account creation syncs only new account not all accounts`() = runTest {
        val calDavAccount = Account(
            id = 42L,
            provider = AccountProvider.CALDAV,
            email = "user@nextcloud.example.com",
            displayName = "Nextcloud",
            principalUrl = "https://nextcloud.example.com/remote.php/dav/principals/users/user/",
            homeSetUrl = "https://nextcloud.example.com/remote.php/dav/calendars/user/"
        )
        val discoveredCalendars = listOf(
            DiscoveredCalendar(
                href = "/remote.php/dav/calendars/user/personal/",
                displayName = "Personal",
                color = 0xFF2196F3.toInt()
            )
        )

        // Mock display name available
        coEvery { calDavDiscoveryService.isDisplayNameAvailable(any()) } returns true

        // Mock discovery phase
        coEvery {
            calDavDiscoveryService.discoverCalendars(any(), any(), any(), any())
        } returns DiscoveryResult.CalendarsFound(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            calendarHomeUrl = "https://nextcloud.example.com/remote.php/dav/calendars/user/",
            principalUrl = "https://nextcloud.example.com/remote.php/dav/principals/users/user/",
            calendars = discoveredCalendars
        )

        // Mock account creation phase
        coEvery {
            calDavDiscoveryService.createAccountWithSelectedCalendars(
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns DiscoveryResult.Success(
            account = calDavAccount,
            calendars = listOf(
                Calendar(
                    id = 10L,
                    accountId = 42L,
                    caldavUrl = "https://nextcloud.example.com/remote.php/dav/calendars/user/personal/",
                    displayName = "Personal",
                    color = 0xFF2196F3.toInt()
                )
            )
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Set CalDAV inputs
        viewModel.onCalDavServerUrlChange("https://nextcloud.example.com")
        viewModel.onCalDavDisplayNameChange("Nextcloud")
        viewModel.onCalDavUsernameChange("user")
        viewModel.onCalDavPasswordChange("password123")
        advanceUntilIdle()

        viewModel.onCalDavDiscover()
        advanceUntilIdle()

        // Should sync only the new CalDAV account
        verify { syncScheduler.syncAccount(42L, forceFullSync = true) }
        // Should NOT trigger a global sync of all accounts
        verify(exactly = 0) { syncScheduler.requestImmediateSync(any()) }
    }

    // ==================== iCloud Race Condition Tests (Bug 2 regression) ====================

    @Test
    fun `iCloud account visible in uiState after CalDAV creation`() = runTest {
        // Set up iCloud account as already connected
        val iCloudAccount = testDbAccount.copy(lastSuccessfulSyncAt = System.currentTimeMillis())
        coEvery { accountRepository.getAccountsByProvider(AccountProvider.ICLOUD) } returns listOf(iCloudAccount)
        coEvery { accountRepository.hasCredentials(iCloudAccount.id) } returns true
        iCloudCalendarCountFlow.value = 2

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Verify iCloud is Connected
        val stateBeforeCalDav = viewModel.uiState.value.iCloudState
        assertTrue(
            "Expected Connected but was $stateBeforeCalDav",
            stateBeforeCalDav is ICloudConnectionState.Connected
        )

        // Now create a CalDAV account
        val calDavAccount = Account(
            id = 42L,
            provider = AccountProvider.CALDAV,
            email = "user@nextcloud.example.com",
            displayName = "Nextcloud",
            principalUrl = "https://nextcloud.example.com/remote.php/dav/principals/users/user/",
            homeSetUrl = "https://nextcloud.example.com/remote.php/dav/calendars/user/"
        )
        val discoveredCalendars = listOf(
            DiscoveredCalendar(
                href = "/remote.php/dav/calendars/user/personal/",
                displayName = "Personal",
                color = 0xFF2196F3.toInt()
            )
        )

        coEvery { calDavDiscoveryService.isDisplayNameAvailable(any()) } returns true
        coEvery {
            calDavDiscoveryService.discoverCalendars(any(), any(), any(), any())
        } returns DiscoveryResult.CalendarsFound(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            calendarHomeUrl = "https://nextcloud.example.com/remote.php/dav/calendars/user/",
            principalUrl = "https://nextcloud.example.com/remote.php/dav/principals/users/user/",
            calendars = discoveredCalendars
        )
        coEvery {
            calDavDiscoveryService.createAccountWithSelectedCalendars(
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns DiscoveryResult.Success(
            account = calDavAccount,
            calendars = emptyList()
        )

        viewModel.onCalDavServerUrlChange("https://nextcloud.example.com")
        viewModel.onCalDavDisplayNameChange("Nextcloud")
        viewModel.onCalDavUsernameChange("user")
        viewModel.onCalDavPasswordChange("password123")
        advanceUntilIdle()

        viewModel.onCalDavDiscover()
        advanceUntilIdle()

        // iCloud should STILL be Connected after CalDAV creation
        val stateAfterCalDav = viewModel.uiState.value.iCloudState
        assertTrue(
            "Expected Connected but was $stateAfterCalDav",
            stateAfterCalDav is ICloudConnectionState.Connected
        )
        assertEquals(
            "test@icloud.com",
            (stateAfterCalDav as ICloudConnectionState.Connected).appleId
        )
    }

    @Test
    fun `iCloudState Connected survives early calendar count emission`() = runTest {
        // Set up: iCloud account exists with credentials
        val iCloudAccount = testDbAccount.copy(lastSuccessfulSyncAt = System.currentTimeMillis())
        coEvery { accountRepository.getAccountsByProvider(AccountProvider.ICLOUD) } returns listOf(iCloudAccount)
        coEvery { accountRepository.hasCredentials(iCloudAccount.id) } returns true

        // Calendar count flow starts with non-zero value (emits immediately)
        iCloudCalendarCountFlow.value = 3

        // Create ViewModel — init launches both loadInitialState() and observeICloudCalendarCount()
        val viewModel = createViewModel()

        // Advance one step: Flow collector runs, reads NotConnected (loadInitialState hasn't completed),
        // but the removed else-branch means no side effect of setting _iCloudAccount = null
        testDispatcher.scheduler.advanceTimeBy(0)

        // Now let everything complete — loadInitialState() finishes, sets Connected
        advanceUntilIdle()

        // iCloudState should be Connected with the calendar count
        val state = viewModel.uiState.value.iCloudState
        assertTrue(
            "Expected Connected but was $state",
            state is ICloudConnectionState.Connected
        )
        assertEquals(3, (state as ICloudConnectionState.Connected).calendarCount)
    }

    // ==================== Account Detail Tests ====================

    private val testDetailAccount = Account(
        id = 10L,
        provider = AccountProvider.CALDAV,
        email = "user@example.com",
        displayName = "My CalDAV",
        principalUrl = "https://dav.example.com/principal",
        homeSetUrl = "https://dav.example.com/calendars",
        isEnabled = true,
        lastSuccessfulSyncAt = System.currentTimeMillis() - 60_000,
        consecutiveSyncFailures = 0
    )

    @Test
    fun `observeAccountDetail populates accountDetail in uiState`() = runTest {
        every { accountRepository.getAccountByIdFlow(10L) } returns flowOf(testDetailAccount)
        coEvery { eventCoordinator.getCalendarCountForAccount(10L) } returns 3

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.observeAccountDetail(10L)
        advanceUntilIdle()

        val detail = viewModel.uiState.value.accountDetail
        assertNotNull(detail)
        assertEquals(10L, detail!!.accountId)
        assertEquals("My CalDAV", detail.displayName)
        assertEquals(3, detail.calendarCount)
        assertTrue(detail.isEnabled)
    }

    @Test
    fun `observeAccountDetail sets null for unknown accountId`() = runTest {
        every { accountRepository.getAccountByIdFlow(999L) } returns flowOf(null)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.observeAccountDetail(999L)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.accountDetail)
    }

    @Test
    fun `clearAccountDetail resets all detail state`() = runTest {
        every { accountRepository.getAccountByIdFlow(10L) } returns flowOf(testDetailAccount)
        coEvery { eventCoordinator.getCalendarCountForAccount(10L) } returns 3

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.observeAccountDetail(10L)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.accountDetail)

        viewModel.clearAccountDetail()

        assertNull(viewModel.uiState.value.accountDetail)
        assertEquals(AccountDetailSyncStatus.Idle, viewModel.uiState.value.accountDetailSyncStatus)
        assertEquals(AccountDetailDiscoverStatus.Idle, viewModel.uiState.value.accountDetailDiscoverStatus)
    }

    @Test
    fun `syncAccountNow sets Syncing then Done on success`() = runTest {
        val workId = UUID.randomUUID()
        val syncStatusFlow = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
        every { syncScheduler.syncAccount(10L) } returns workId
        every { syncScheduler.observeSyncStatus(workId) } returns syncStatusFlow
        coEvery { accountRepository.getAccountById(10L) } returns testDetailAccount
        coEvery { eventCoordinator.getCalendarCountForAccount(10L) } returns 3

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.syncAccountNow(10L)
        advanceUntilIdle()

        assertEquals(AccountDetailSyncStatus.Syncing, viewModel.uiState.value.accountDetailSyncStatus)

        syncStatusFlow.value = SyncStatus.Succeeded()
        advanceUntilIdle()

        val status = viewModel.uiState.value.accountDetailSyncStatus
        assertTrue(status is AccountDetailSyncStatus.Done)
        assertTrue((status as AccountDetailSyncStatus.Done).success)
    }

    @Test
    fun `syncAccountNow sets Done with failure on Failed status`() = runTest {
        val workId = UUID.randomUUID()
        val syncStatusFlow = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
        every { syncScheduler.syncAccount(10L) } returns workId
        every { syncScheduler.observeSyncStatus(workId) } returns syncStatusFlow
        coEvery { accountRepository.getAccountById(10L) } returns testDetailAccount
        coEvery { eventCoordinator.getCalendarCountForAccount(10L) } returns 3

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.syncAccountNow(10L)
        advanceUntilIdle()

        syncStatusFlow.value = SyncStatus.Failed("Network error")
        advanceUntilIdle()

        val status = viewModel.uiState.value.accountDetailSyncStatus
        assertTrue(status is AccountDetailSyncStatus.Done)
        assertFalse((status as AccountDetailSyncStatus.Done).success)
    }

    @Test
    fun `syncAccountNow also pulls contacts when contact sync is enabled for the account`() = runTest {
        // "Sync now" in the account sheet must cover contacts too when the user
        // has contact sync on — otherwise the manual sync silently skips them and
        // they only refresh at the next periodic tick.
        val workId = UUID.randomUUID()
        val syncStatusFlow = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
        every { syncScheduler.syncAccount(10L) } returns workId
        every { syncScheduler.observeSyncStatus(workId) } returns syncStatusFlow
        coEvery { accountRepository.getAccountById(10L) } returns testDetailAccount.copy(contactSyncEnabled = true)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.syncAccountNow(10L)
        advanceUntilIdle()

        // Scoped to this account — a single-account "Sync now" must not re-sweep
        // every other contact-sync login's address books.
        verify { syncScheduler.requestImmediateContactSync(10L) }
    }

    @Test
    fun `syncAccountNow does not pull contacts when contact sync is off for the account`() = runTest {
        val workId = UUID.randomUUID()
        val syncStatusFlow = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
        every { syncScheduler.syncAccount(10L) } returns workId
        every { syncScheduler.observeSyncStatus(workId) } returns syncStatusFlow
        coEvery { accountRepository.getAccountById(10L) } returns testDetailAccount.copy(contactSyncEnabled = false)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.syncAccountNow(10L)
        advanceUntilIdle()

        verify(exactly = 0) { syncScheduler.requestImmediateContactSync() }
    }

    @Test
    fun `syncAccountNow does not pull contacts when contacts permission is missing`() = runTest {
        // Even with the flag on, a revoked WRITE_CONTACTS grant means the pull would
        // only skip-and-flag — don't kick it (mirrors the enable-path gate).
        permissionChecker.readContacts = false
        permissionChecker.writeContacts = false
        val workId = UUID.randomUUID()
        val syncStatusFlow = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
        every { syncScheduler.syncAccount(10L) } returns workId
        every { syncScheduler.observeSyncStatus(workId) } returns syncStatusFlow
        coEvery { accountRepository.getAccountById(10L) } returns testDetailAccount.copy(contactSyncEnabled = true)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.syncAccountNow(10L)
        advanceUntilIdle()

        verify(exactly = 0) { syncScheduler.requestImmediateContactSync() }
    }

    @Test
    fun `syncAccountNow raises the re-grant banner when contact sync is on but permission is missing`() = runTest {
        // "Sync now" is a manual entry point that can run for a login whose periodic
        // contact job was never scheduled, so the background worker never fires to
        // raise the banner. Raise it here, mirroring the enable path, so the user sees
        // why contacts didn't refresh.
        permissionChecker.readContacts = false
        permissionChecker.writeContacts = false
        val workId = UUID.randomUUID()
        val syncStatusFlow = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
        every { syncScheduler.syncAccount(10L) } returns workId
        every { syncScheduler.observeSyncStatus(workId) } returns syncStatusFlow
        coEvery { accountRepository.getAccountById(10L) } returns testDetailAccount.copy(contactSyncEnabled = true)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.syncAccountNow(10L)
        advanceUntilIdle()

        coVerify { dataStore.setContactSyncPermissionNeeded(true) }
    }

    @Test
    fun `syncAccountNow clears the re-grant banner when contact sync is on and permission is granted`() = runTest {
        val workId = UUID.randomUUID()
        val syncStatusFlow = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
        every { syncScheduler.syncAccount(10L) } returns workId
        every { syncScheduler.observeSyncStatus(workId) } returns syncStatusFlow
        coEvery { accountRepository.getAccountById(10L) } returns testDetailAccount.copy(contactSyncEnabled = true)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.syncAccountNow(10L)
        advanceUntilIdle()

        coVerify { dataStore.setContactSyncPermissionNeeded(false) }
    }

    @Test
    fun `syncAccountNow does not touch the re-grant banner when contact sync is off`() = runTest {
        // A login without contact sync shouldn't flip the contact-sync feature's
        // re-grant flag — that flag belongs to the feature, not to calendar sync.
        val workId = UUID.randomUUID()
        val syncStatusFlow = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
        every { syncScheduler.syncAccount(10L) } returns workId
        every { syncScheduler.observeSyncStatus(workId) } returns syncStatusFlow
        coEvery { accountRepository.getAccountById(10L) } returns testDetailAccount.copy(contactSyncEnabled = false)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.syncAccountNow(10L)
        advanceUntilIdle()

        coVerify(exactly = 0) { dataStore.setContactSyncPermissionNeeded(any()) }
    }

    @Test
    fun `toggleAccountEnabled calls setEnabled`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleAccountEnabled(10L, false)
        advanceUntilIdle()

        coVerify { accountRepository.setEnabled(10L, false) }
    }

    @Test
    fun `onToggleContactSync enable routes to repository`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleContactSync(10L, true)
        advanceUntilIdle()

        coVerify { accountRepository.setContactSyncEnabled(10L, true) }
    }

    @Test
    fun `onToggleContactSync disable routes to repository`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleContactSync(10L, false)
        advanceUntilIdle()

        coVerify { accountRepository.setContactSyncEnabled(10L, false) }
    }

    @Test
    fun `onToggleContactSync enable kicks an immediate contact pull and schedules the periodic job`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleContactSync(10L, true)
        advanceUntilIdle()

        // Without the immediate kick, contacts wouldn't sync until the next
        // periodic tick (>=15 min) — and only if periodic was ever scheduled.
        verify { syncScheduler.requestImmediateContactSync() }
        // Ensure the recurring job exists too, so it isn't a one-time import.
        verify { syncScheduler.ensureContactSyncScheduled(any()) }
    }

    @Test
    fun `onToggleContactSync enable shows an inline confirmation naming the masked account`() = runTest {
        // The confirmation lives in a sheet-local uiState field, NOT the snackbar:
        // the SnackbarHost sits in the base window and renders behind the open
        // ModalBottomSheet, so a snackbar only appears after the sheet is dismissed.
        coEvery { accountRepository.getAccountById(10L) } returns testDetailAccount
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleContactSync(10L, true)
        advanceUntilIdle()

        assertEquals(
            "Syncing contacts for u***@example.com",
            viewModel.uiState.value.contactSyncConfirmation?.message
        )
        // Enabling is benign — the checkmark tone, not a warning.
        assertEquals(
            ContactSyncConfirmation.Tone.POSITIVE,
            viewModel.uiState.value.contactSyncConfirmation?.tone
        )
        // Must not leak into the (behind-the-sheet) snackbar channel.
        assertNull(viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `onToggleContactSync disable shows an inline removed confirmation and kicks nothing`() = runTest {
        coEvery { accountRepository.getAccountById(10L) } returns testDetailAccount
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleContactSync(10L, false)
        advanceUntilIdle()

        verify(exactly = 0) { syncScheduler.requestImmediateContactSync() }
        verify(exactly = 0) { syncScheduler.ensureContactSyncScheduled(any()) }
        assertEquals(
            "Device contacts for u***@example.com removed",
            viewModel.uiState.value.contactSyncConfirmation?.message
        )
        // Removing device contacts is destructive — it must carry the warning tone so
        // the sheet doesn't render it with the same celebratory checkmark as enabling.
        assertEquals(
            ContactSyncConfirmation.Tone.WARNING,
            viewModel.uiState.value.contactSyncConfirmation?.tone
        )
        assertNull(viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `onToggleContactSync disable kept by a sibling stays a positive-tone confirmation`() = runTest {
        // Contacts weren't removed — a same-email sibling still syncs them, so the
        // purge was NOT_ATTEMPTED. Nothing destructive happened, so the confirmation
        // must read as benign (positive tone), not a warning.
        coEvery { accountRepository.getAccountById(10L) } returns testDetailAccount
        coEvery { accountRepository.setContactSyncEnabled(10L, false) } returns
            org.onekash.kashcal.data.repository.ContactPurgeOutcome.NOT_ATTEMPTED
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleContactSync(10L, false)
        advanceUntilIdle()

        assertEquals(
            ContactSyncConfirmation.Tone.POSITIVE,
            viewModel.uiState.value.contactSyncConfirmation?.tone
        )
    }

    @Test
    fun `clearContactSyncConfirmation resets the inline confirmation`() = runTest {
        coEvery { accountRepository.getAccountById(10L) } returns testDetailAccount
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleContactSync(10L, false)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.contactSyncConfirmation)

        viewModel.clearContactSyncConfirmation()

        assertNull(viewModel.uiState.value.contactSyncConfirmation)
    }

    @Test
    fun `onToggleContactSync enable without contacts permission persists flag but does not kick sync`() = runTest {
        // Defense-in-depth: the UI gates enabling behind a permission request,
        // but if the VM enable path is reached without READ+WRITE the pull would
        // only skip-and-flag downstream. Persist the flag (so the re-grant banner
        // shows) but don't kick a pull or claim we're syncing.
        permissionChecker.readContacts = false
        permissionChecker.writeContacts = false
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleContactSync(10L, true)
        advanceUntilIdle()

        coVerify { accountRepository.setContactSyncEnabled(10L, true) }
        verify(exactly = 0) { syncScheduler.requestImmediateContactSync() }
        verify(exactly = 0) { syncScheduler.ensureContactSyncScheduled(any()) }
        assertNull(viewModel.uiState.value.pendingSnackbarMessage)
        // No "Syncing contacts for…" claim when the pull can't actually run.
        assertNull(viewModel.uiState.value.contactSyncConfirmation)
    }

    @Test
    fun `onToggleContactSync enable with only write permission does not kick sync`() = runTest {
        // A partial grant (write but not read) can't mirror server contacts, so
        // it must not enable the pull — mirrors contactSyncPermissionGranted.
        permissionChecker.readContacts = false
        permissionChecker.writeContacts = true
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleContactSync(10L, true)
        advanceUntilIdle()

        verify(exactly = 0) { syncScheduler.requestImmediateContactSync() }
        assertNull(viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `hasContactsSyncPermission requires both read and write`() = runTest {
        // The sync toggle needs READ + WRITE (it mirrors server contacts onto the
        // device). It must NOT reuse the read-only hasContactsPermission signal
        // (that one gates the birthday/anniversary reads, which need READ alone) —
        // otherwise a read-granted/write-denied login flips the toggle on but never
        // requests WRITE, never pulls, and shows nothing.
        permissionChecker.readContacts = true
        permissionChecker.writeContacts = false
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.hasContactsPermission.value)
        assertFalse(viewModel.hasContactsSyncPermission.value)

        permissionChecker.writeContacts = true
        viewModel.refreshContactsPermission()
        advanceUntilIdle()

        assertTrue(viewModel.hasContactsSyncPermission.value)
    }

    @Test
    fun `onToggleContactSync enable without permission flags the re-grant banner`() = runTest {
        // The re-grant banner reads contactSyncPermissionNeeded. If we relied only
        // on the background worker to set it, a never-scheduled login (manual-only,
        // or predating the feature) would show no feedback at all — the worker
        // never runs. Flag it at enable time.
        permissionChecker.readContacts = false
        permissionChecker.writeContacts = false
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleContactSync(10L, true)
        advanceUntilIdle()

        coVerify { dataStore.setContactSyncPermissionNeeded(true) }
    }

    @Test
    fun `onToggleContactSync enable with permission clears the re-grant flag`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleContactSync(10L, true)
        advanceUntilIdle()

        coVerify { dataStore.setContactSyncPermissionNeeded(false) }
    }

    @Test
    fun `onToggleContactSync enable on manual-only interval imports once without scheduling periodic`() = runTest {
        // Manual-only is the repository's Long.MAX_VALUE sentinel. As with calendar
        // sync, we don't schedule a periodic contact job in that mode, but we still
        // fire the one-time pull so enabling has an immediate effect.
        syncIntervalFlow.value = Long.MAX_VALUE
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleContactSync(10L, true)
        advanceUntilIdle()

        verify { syncScheduler.requestImmediateContactSync() }
        verify(exactly = 0) { syncScheduler.ensureContactSyncScheduled(any()) }
    }

    @Test
    fun `renameAccount updates displayName and reloads`() = runTest {
        coEvery { accountRepository.getAccountById(10L) } returns testDetailAccount
        coEvery { eventCoordinator.getCalendarCountForAccount(10L) } returns 3
        coEvery { accountRepository.getAccountsByProvider(AccountProvider.CALDAV) } returns listOf(testDetailAccount)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.renameAccount(10L, "New Name")
        advanceUntilIdle()

        coVerify {
            accountRepository.updateAccount(match { it.displayName == "New Name" })
        }
    }

    @Test
    fun `renameAccount rejects empty name`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.renameAccount(10L, "   ")
        advanceUntilIdle()

        // Should not call updateAccount for empty names
        coVerify(exactly = 0) { accountRepository.updateAccount(any()) }
    }

    @Test
    fun `changeAccountPassword saves new credentials on success`() = runTest {
        val oldCreds = AccountCredentials(
            username = "user",
            password = "old-pass",
            serverUrl = "https://dav.example.com"
        )
        coEvery { accountRepository.getAccountById(10L) } returns testDetailAccount
        coEvery { accountRepository.getCredentials(10L) } returns oldCreds
        coEvery { calDavDiscoveryService.refreshCalendars(10L) } returns
            DiscoveryResult.Success(testDetailAccount, emptyList())

        val viewModel = createViewModel()
        advanceUntilIdle()

        var callbackResult: Result<Unit>? = null
        viewModel.changeAccountPassword(10L, "new-pass") { callbackResult = it }
        advanceUntilIdle()

        assertTrue(callbackResult!!.isSuccess)
        coVerify {
            accountRepository.saveCredentials(10L, match { it.password == "new-pass" })
        }
    }

    @Test
    fun `changeAccountPassword reverts credentials on AuthError`() = runTest {
        val oldCreds = AccountCredentials(
            username = "user",
            password = "old-pass",
            serverUrl = "https://dav.example.com"
        )
        coEvery { accountRepository.getAccountById(10L) } returns testDetailAccount
        coEvery { accountRepository.getCredentials(10L) } returns oldCreds
        coEvery { calDavDiscoveryService.refreshCalendars(10L) } returns
            DiscoveryResult.AuthError("Invalid credentials")

        val viewModel = createViewModel()
        advanceUntilIdle()

        var callbackResult: Result<Unit>? = null
        viewModel.changeAccountPassword(10L, "wrong-pass") { callbackResult = it }
        advanceUntilIdle()

        assertTrue(callbackResult!!.isFailure)
        assertEquals("Invalid password", callbackResult!!.exceptionOrNull()?.message)
        // Verify old credentials restored
        coVerify(ordering = Ordering.ORDERED) {
            accountRepository.saveCredentials(10L, match { it.password == "wrong-pass" })
            accountRepository.saveCredentials(10L, match { it.password == "old-pass" })
        }
    }

    @Test
    fun `changeAccountPassword reverts credentials on network Error`() = runTest {
        val oldCreds = AccountCredentials(
            username = "user",
            password = "old-pass",
            serverUrl = "https://dav.example.com"
        )
        coEvery { accountRepository.getAccountById(10L) } returns testDetailAccount
        coEvery { accountRepository.getCredentials(10L) } returns oldCreds
        coEvery { calDavDiscoveryService.refreshCalendars(10L) } returns
            DiscoveryResult.Error("Connection timeout")

        val viewModel = createViewModel()
        advanceUntilIdle()

        var callbackResult: Result<Unit>? = null
        viewModel.changeAccountPassword(10L, "new-pass") { callbackResult = it }
        advanceUntilIdle()

        assertTrue(callbackResult!!.isFailure)
        assertEquals("Network error, try again", callbackResult!!.exceptionOrNull()?.message)
        // Verify old credentials restored
        coVerify {
            accountRepository.saveCredentials(10L, match { it.password == "old-pass" })
        }
    }

    @Test
    fun `discoverNewCalendars routes to CalDAV service for CALDAV`() = runTest {
        coEvery { accountRepository.getAccountById(10L) } returns testDetailAccount
        coEvery { eventCoordinator.getCalendarCountForAccount(10L) } returns 3
        coEvery { calDavDiscoveryService.refreshCalendars(10L) } returns
            DiscoveryResult.Success(testDetailAccount, listOf(
                Calendar(id = 1L, accountId = 10L, caldavUrl = "/cal1", displayName = "Cal 1", color = 0xFF0000),
                Calendar(id = 2L, accountId = 10L, caldavUrl = "/cal2", displayName = "Cal 2", color = 0x00FF00),
                Calendar(id = 3L, accountId = 10L, caldavUrl = "/cal3", displayName = "Cal 3", color = 0x0000FF),
                Calendar(id = 4L, accountId = 10L, caldavUrl = "/cal4", displayName = "Cal 4", color = 0xFFFF00),
                Calendar(id = 5L, accountId = 10L, caldavUrl = "/cal5", displayName = "Cal 5", color = 0xFF00FF)
            ))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.discoverNewCalendars(10L)
        advanceUntilIdle()

        coVerify { calDavDiscoveryService.refreshCalendars(10L) }
        val status = viewModel.uiState.value.accountDetailDiscoverStatus
        assertTrue(status is AccountDetailDiscoverStatus.Done)
        assertEquals(2, (status as AccountDetailDiscoverStatus.Done).newCount)
        assertEquals(5, status.totalCount)

        // Discovery of new calendars must trigger sync
        coVerify { syncScheduler.syncAccount(10L) }
    }

    @Test
    fun `discoverNewCalendars does not trigger sync when no new calendars`() = runTest {
        coEvery { accountRepository.getAccountById(10L) } returns testDetailAccount
        coEvery { eventCoordinator.getCalendarCountForAccount(10L) } returns 3
        coEvery { calDavDiscoveryService.refreshCalendars(10L) } returns
            DiscoveryResult.Success(testDetailAccount, listOf(
                Calendar(id = 1L, accountId = 10L, caldavUrl = "/cal1", displayName = "Cal 1", color = 0xFF0000),
                Calendar(id = 2L, accountId = 10L, caldavUrl = "/cal2", displayName = "Cal 2", color = 0x00FF00),
                Calendar(id = 3L, accountId = 10L, caldavUrl = "/cal3", displayName = "Cal 3", color = 0x0000FF)
            ))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.discoverNewCalendars(10L)
        advanceUntilIdle()

        val status = viewModel.uiState.value.accountDetailDiscoverStatus
        assertTrue(status is AccountDetailDiscoverStatus.Done)
        assertEquals(0, (status as AccountDetailDiscoverStatus.Done).newCount)

        // No new calendars → no sync trigger
        coVerify(exactly = 0) { syncScheduler.syncAccount(10L) }
    }

    @Test
    fun `discoverNewCalendars routes to iCloud service for ICLOUD`() = runTest {
        val iCloudAccount = testDetailAccount.copy(id = 1L, provider = AccountProvider.ICLOUD)
        coEvery { accountRepository.getAccountById(1L) } returns iCloudAccount
        coEvery { eventCoordinator.getCalendarCountForAccount(1L) } returns 2
        coEvery { discoveryService.refreshCalendars(1L) } returns
            DiscoveryResult.Success(iCloudAccount, listOf(
                Calendar(id = 1L, accountId = 1L, caldavUrl = "/cal1", displayName = "Cal 1", color = 0xFF0000),
                Calendar(id = 2L, accountId = 1L, caldavUrl = "/cal2", displayName = "Cal 2", color = 0x00FF00),
                Calendar(id = 3L, accountId = 1L, caldavUrl = "/cal3", displayName = "Cal 3", color = 0x0000FF)
            ))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.discoverNewCalendars(1L)
        advanceUntilIdle()

        coVerify { discoveryService.refreshCalendars(1L) }
        coVerify(exactly = 0) { calDavDiscoveryService.refreshCalendars(any()) }
    }

    @Test
    fun `discoverNewCalendars handles AuthError`() = runTest {
        coEvery { accountRepository.getAccountById(10L) } returns testDetailAccount
        coEvery { eventCoordinator.getCalendarCountForAccount(10L) } returns 3
        coEvery { calDavDiscoveryService.refreshCalendars(10L) } returns
            DiscoveryResult.AuthError("Token expired")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.discoverNewCalendars(10L)
        advanceUntilIdle()

        val status = viewModel.uiState.value.accountDetailDiscoverStatus
        assertTrue(status is AccountDetailDiscoverStatus.Error)
        assertTrue((status as AccountDetailDiscoverStatus.Error).message.contains("Authentication failed"))
    }

    // ==================== Device Calendar Refresh Tests ====================

    @Test
    fun `refreshDeviceCalendars does nothing when permission not granted`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Forget any init-time interactions; we only care about refresh's effect.
        clearMocks(calendarProviderRepository, answers = false)

        // Permission is not granted by default in tests
        viewModel.refreshDeviceCalendars()
        advanceUntilIdle()

        // "Does nothing" = refresh triggers no device-calendar query
        coVerify(exactly = 0) { calendarProviderRepository.getDeviceCalendars() }
    }

    @Test
    fun `refreshDeviceCalendars does nothing when feature disabled`() = runTest {
        coEvery { dataStore.deviceCalendarsEnabled } returns MutableStateFlow(false)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Forget any init-time interactions; we only care about refresh's effect.
        clearMocks(calendarProviderRepository, answers = false)

        viewModel.refreshDeviceCalendars()
        advanceUntilIdle()

        // Feature disabled -> refresh triggers no device-calendar query
        coVerify(exactly = 0) { calendarProviderRepository.getDeviceCalendars() }
    }

    // Issue #170: MIUI Google calendars install with SYNC_EVENTS=0 so the sync
    // adapter never populates the Events table. Ticking a calendar must flip
    // that flag on and kick off a sync; unticking must NOT flip it off
    // (the user only meant "hide from KashCal", not "disable system sync").
    @Test
    fun `onToggleDeviceCalendar enabled calls ensureCalendarVisible`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleDeviceCalendar(calendarId = 42L, enabled = true)
        advanceUntilIdle()

        coVerify(exactly = 1) { calendarProviderRepository.ensureCalendarVisible(42L) }
    }

    @Test
    fun `onToggleDeviceCalendar disabled does not call ensureCalendarVisible`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleDeviceCalendar(calendarId = 42L, enabled = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { calendarProviderRepository.ensureCalendarVisible(any()) }
    }

    @Test
    fun `onToggleDeviceCalendar enabled re-toggles re-run ensureCalendarVisible`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleDeviceCalendar(calendarId = 42L, enabled = true)
        advanceUntilIdle()
        viewModel.onToggleDeviceCalendar(calendarId = 42L, enabled = false)
        advanceUntilIdle()
        viewModel.onToggleDeviceCalendar(calendarId = 42L, enabled = true)
        advanceUntilIdle()

        coVerify(exactly = 2) { calendarProviderRepository.ensureCalendarVisible(42L) }
    }

    // ==================== Default Calendar (DefaultCalendar type) Tests ====================

    @Test
    fun `writableDeviceCalendarGroups withWritePermission loadsWritableCalendars`() = runTest {
        permissionChecker.calendarRead = true
        permissionChecker.calendarWrite = true

        // Mock writable device calendars (accessLevel >= 500 = writable)
        val deviceCalendars = listOf(
            DeviceCalendar(
                id = 100L,
                displayName = "Personal",
                color = 0xFF2196F3.toInt(),
                accountName = "Google",
                accountType = "com.google",
                visible = true,
                accessLevel = 700 // OWNER - writable
            ),
            DeviceCalendar(
                id = 101L,
                displayName = "Work",
                color = 0xFF4CAF50.toInt(),
                accountName = "Google",
                accountType = "com.google",
                visible = true,
                accessLevel = 500 // CONTRIBUTOR - writable
            ),
            DeviceCalendar(
                id = 102L,
                displayName = "Holidays",
                color = 0xFFFF9800.toInt(),
                accountName = "Samsung",
                accountType = "com.samsung",
                visible = true,
                accessLevel = 200 // READ - not writable
            )
        )
        coEvery { calendarProviderRepository.getDeviceCalendars() } returns deviceCalendars

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.writableDeviceCalendarGroups.test {
            val groups = expectMostRecentItem()
            // Should have 1 group (Google) with 2 writable calendars
            // Samsung Holiday has accessLevel 200 (READ), so not writable
            assertEquals(1, groups.size)
            assertEquals("Google", groups[0].accountName)
            assertEquals(2, groups[0].pickerCalendars.size)
        }
    }

    @Test
    fun `writableDeviceCalendarGroups withoutWritePermission returnsEmpty`() = runTest {
        permissionChecker.calendarRead = false
        permissionChecker.calendarWrite = false
        // If the read-path were accidentally taken, this non-empty stub would surface
        // in the assertion — guards against false-positive "empty because empty source".
        coEvery { calendarProviderRepository.getDeviceCalendars() } returns listOf(
            DeviceCalendar(
                id = 100L,
                displayName = "Should Not Appear",
                color = 0,
                accountName = "Google",
                accountType = "com.google",
                visible = true,
                accessLevel = 700
            )
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.writableDeviceCalendarGroups.test {
            val groups = expectMostRecentItem()
            assertTrue(groups.isEmpty())
        }
    }

    @Test
    fun `onDefaultCalendarSelect roomCalendar persistsRoomFormat`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDefaultCalendarSelect(DefaultCalendar.Room(calendarId = 42L))
        advanceUntilIdle()

        coVerify {
            userPreferences.setDefaultCalendar(DefaultCalendar.Room(calendarId = 42L))
        }
    }

    @Test
    fun `onDefaultCalendarSelect deviceCalendar persistsDeviceFormat`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDefaultCalendarSelect(DefaultCalendar.Device(calendarId = 100L))
        advanceUntilIdle()

        coVerify {
            userPreferences.setDefaultCalendar(DefaultCalendar.Device(calendarId = 100L))
        }
    }

    @Test
    fun `defaultCalendar observesNewFormat emitsCorrectType`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Emit Room type
        defaultCalendarFlow.value = DefaultCalendar.Room(calendarId = 5L)
        advanceUntilIdle()

        viewModel.defaultCalendar.test {
            val current = expectMostRecentItem()
            assertTrue(current is DefaultCalendar.Room)
            assertEquals(5L, (current as DefaultCalendar.Room).calendarId)
        }

        // Emit Device type
        defaultCalendarFlow.value = DefaultCalendar.Device(calendarId = 200L)
        advanceUntilIdle()

        viewModel.defaultCalendar.test {
            val current = expectMostRecentItem()
            assertTrue(current is DefaultCalendar.Device)
            assertEquals(200L, (current as DefaultCalendar.Device).calendarId)
        }
    }

    // ==================== Settings Search State Tests ====================

    @Test
    fun `isSearchActive defaults to false`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(false, viewModel.isSearchActive.value)
    }

    @Test
    fun `searchQuery defaults to empty string`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals("", viewModel.searchQuery.value)
    }

    @Test
    fun `onSearchOpen sets isSearchActive true`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onSearchOpen()
        assertEquals(true, viewModel.isSearchActive.value)
        assertEquals("", viewModel.searchQuery.value)
    }

    @Test
    fun `onSearchQueryChange updates query without changing isSearchActive`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onSearchOpen()
        viewModel.onSearchQueryChange("time")
        assertEquals("time", viewModel.searchQuery.value)
        assertEquals(true, viewModel.isSearchActive.value)
    }

    @Test
    fun `onSearchClose resets both isSearchActive and searchQuery`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onSearchOpen()
        viewModel.onSearchQueryChange("abc")
        viewModel.onSearchClose()
        assertEquals(false, viewModel.isSearchActive.value)
        assertEquals("", viewModel.searchQuery.value)
    }

    @Test
    fun `onCleared resets search state for next ViewModel instance lifecycle`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onSearchOpen()
        viewModel.onSearchQueryChange("abc")
        viewModel.onClearedForTest()
        assertEquals(false, viewModel.isSearchActive.value)
        assertEquals("", viewModel.searchQuery.value)
    }
}
