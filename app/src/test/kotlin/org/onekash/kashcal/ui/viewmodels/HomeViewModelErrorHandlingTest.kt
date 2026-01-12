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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.provider.icloud.ICloudAuthManager
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.error.CalendarError
import org.onekash.kashcal.error.ErrorActionCallback
import org.onekash.kashcal.error.ErrorPresentation
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus

/**
 * Tests for HomeViewModel error handling system.
 *
 * Verifies that errors are correctly:
 * - Mapped to appropriate UI presentations (Snackbar, Dialog, Banner, Silent)
 * - Stored in UI state
 * - Cleared after action handling
 *
 * Critical patterns tested (from CLAUDE.md):
 * - Error handling flows
 * - Action callback handling
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelErrorHandlingTest {

    private val testDispatcher = StandardTestDispatcher()

    // Mocks
    private lateinit var eventCoordinator: EventCoordinator
    private lateinit var eventReader: EventReader
    private lateinit var dataStore: KashCalDataStore
    private lateinit var authManager: ICloudAuthManager
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var networkMonitor: NetworkMonitor

    private lateinit var networkStateFlow: MutableStateFlow<Boolean>
    private lateinit var syncStatusFlow: MutableStateFlow<SyncStatus>
    private lateinit var bannerFlagFlow: MutableStateFlow<Boolean>

    private val testCalendars = listOf(
        Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://caldav.icloud.com/cal1",
            displayName = "Personal",
            color = 0xFF2196F3.toInt()
        )
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        eventCoordinator = mockk(relaxed = true)
        eventReader = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        authManager = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)

        networkStateFlow = MutableStateFlow(true)
        every { networkMonitor.isOnline } returns networkStateFlow
        every { networkMonitor.isMetered } returns MutableStateFlow(false)

        syncStatusFlow = MutableStateFlow(SyncStatus.Idle)
        every { syncScheduler.observeImmediateSyncStatus() } returns syncStatusFlow

        bannerFlagFlow = MutableStateFlow(false)
        every { syncScheduler.showBannerForSync } returns bannerFlagFlow
        every { syncScheduler.setShowBannerForSync(any()) } answers { bannerFlagFlow.value = firstArg() }
        every { syncScheduler.resetBannerFlag() } answers { bannerFlagFlow.value = false }
        every { syncScheduler.lastSyncChanges } returns MutableStateFlow(emptyList())
        every { syncScheduler.clearSyncChanges() } returns Unit

        every { eventCoordinator.getAllCalendars() } returns flowOf(testCalendars)
        coEvery { dataStore.defaultCalendarId } returns flowOf(null)
        coEvery { dataStore.defaultReminderMinutes } returns flowOf(15)
        coEvery { dataStore.defaultAllDayReminder } returns flowOf(1440)
        coEvery { dataStore.onboardingDismissed } returns flowOf(true)
        coEvery { authManager.loadAccount() } returns null
        coEvery { eventReader.getVisibleOccurrencesInRange(any(), any()) } returns flowOf(emptyList())
        every { eventReader.getVisibleOccurrencesForDay(any()) } returns flowOf(emptyList())
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

    // ==================== showError() Tests ====================

    @Test
    fun `showError with network timeout displays snackbar`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showError(CalendarError.Network.Timeout)

        val state = viewModel.uiState.value
        assertNotNull(state.currentError)
        assertTrue(state.currentError is ErrorPresentation.Snackbar)
        assertFalse(state.showErrorDialog)
        assertFalse(state.showErrorBanner)
    }

    @Test
    fun `showError with auth invalid credentials displays dialog`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showError(CalendarError.Auth.InvalidCredentials)

        val state = viewModel.uiState.value
        assertNotNull(state.currentError)
        assertTrue(state.currentError is ErrorPresentation.Dialog)
        assertTrue(state.showErrorDialog)
        assertFalse(state.showErrorBanner)
    }

    @Test
    fun `showError with no accounts configured displays banner`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showError(CalendarError.Sync.NoAccountsConfigured)

        val state = viewModel.uiState.value
        assertNotNull(state.currentError)
        assertTrue(state.currentError is ErrorPresentation.Banner)
        assertFalse(state.showErrorDialog)
        assertTrue(state.showErrorBanner)
    }

    @Test
    fun `showError with already syncing is silent`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showError(CalendarError.Sync.AlreadySyncing)

        val state = viewModel.uiState.value
        // Silent errors should not update currentError
        assertTrue(state.currentError == null || state.currentError is ErrorPresentation.Silent)
        assertFalse(state.showErrorDialog)
        assertFalse(state.showErrorBanner)
    }

    @Test
    fun `showError with network offline shows snackbar with retry`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showError(CalendarError.Network.Offline)

        val state = viewModel.uiState.value
        assertNotNull(state.currentError)
        assertTrue(state.currentError is ErrorPresentation.Snackbar)
        val snackbar = state.currentError as ErrorPresentation.Snackbar
        assertNotNull(snackbar.action)
        assertEquals(ErrorActionCallback.Retry, snackbar.action?.callback)
    }

    @Test
    fun `showError with server conflict shows dialog with force sync`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showError(CalendarError.Server.Conflict("Test Event"))

        val state = viewModel.uiState.value
        assertNotNull(state.currentError)
        assertTrue(state.currentError is ErrorPresentation.Dialog)
        assertTrue(state.showErrorDialog)
        val dialog = state.currentError as ErrorPresentation.Dialog
        assertEquals(ErrorActionCallback.ForceFullSync, dialog.primaryAction.callback)
    }

    @Test
    fun `showError with storage full shows dialog with settings action`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showError(CalendarError.Storage.StorageFull)

        val state = viewModel.uiState.value
        assertNotNull(state.currentError)
        assertTrue(state.currentError is ErrorPresentation.Dialog)
        assertTrue(state.showErrorDialog)
        val dialog = state.currentError as ErrorPresentation.Dialog
        assertEquals(ErrorActionCallback.OpenAppSettings, dialog.primaryAction.callback)
    }

    @Test
    fun `showError with permission denied shows dialog with settings action`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showError(CalendarError.Permission.NotificationDenied)

        val state = viewModel.uiState.value
        assertTrue(state.currentError is ErrorPresentation.Dialog)
        val dialog = state.currentError as ErrorPresentation.Dialog
        assertEquals(ErrorActionCallback.OpenAppSettings, dialog.primaryAction.callback)
    }

    @Test
    fun `showError with partial import shows snackbar`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showError(CalendarError.ImportExport.PartialImport(5, 2))

        val state = viewModel.uiState.value
        assertTrue(state.currentError is ErrorPresentation.Snackbar)
    }

    // ==================== handleErrorAction() Tests ====================

    @Test
    fun `handleErrorAction with Retry clears error and triggers sync`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // First show an error
        viewModel.showError(CalendarError.Network.Timeout)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.currentError)

        // Handle retry action
        viewModel.handleErrorAction(ErrorActionCallback.Retry)
        advanceUntilIdle()

        // Error should be cleared
        assertNull(viewModel.uiState.value.currentError)
        assertFalse(viewModel.uiState.value.showErrorDialog)
        assertFalse(viewModel.uiState.value.showErrorBanner)
    }

    @Test
    fun `handleErrorAction with Dismiss clears error only`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showError(CalendarError.Auth.InvalidCredentials)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showErrorDialog)

        viewModel.handleErrorAction(ErrorActionCallback.Dismiss)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.currentError)
        assertFalse(viewModel.uiState.value.showErrorDialog)
    }

    @Test
    fun `handleErrorAction with ForceFullSync clears error and triggers full sync`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showError(CalendarError.Server.Conflict())
        advanceUntilIdle()

        viewModel.handleErrorAction(ErrorActionCallback.ForceFullSync)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.currentError)
    }

    @Test
    fun `handleErrorAction with ViewSyncDetails opens sync changes sheet`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showError(CalendarError.Sync.PartialFailure(5, 2))
        advanceUntilIdle()

        viewModel.handleErrorAction(ErrorActionCallback.ViewSyncDetails)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showSyncChangesSheet)
        assertNull(viewModel.uiState.value.currentError)
    }

    @Test
    fun `handleErrorAction with Custom callback executes action`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        var customActionCalled = false
        val customCallback = ErrorActionCallback.Custom { customActionCalled = true }

        viewModel.handleErrorAction(customCallback)
        advanceUntilIdle()

        assertTrue(customActionCalled)
    }

    @Test
    fun `handleErrorAction with OpenSettings clears error`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showError(CalendarError.Sync.NoAccountsConfigured)
        advanceUntilIdle()

        viewModel.handleErrorAction(ErrorActionCallback.OpenSettings)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.currentError)
    }

    @Test
    fun `handleErrorAction with ReAuthenticate clears error`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showError(CalendarError.Auth.SessionExpired)
        advanceUntilIdle()

        viewModel.handleErrorAction(ErrorActionCallback.ReAuthenticate)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.currentError)
    }

    // ==================== clearError() Tests ====================

    @Test
    fun `clearError clears all error state`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Show a dialog error
        viewModel.showError(CalendarError.Auth.InvalidCredentials)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showErrorDialog)

        viewModel.clearError()

        assertNull(viewModel.uiState.value.currentError)
        assertFalse(viewModel.uiState.value.showErrorDialog)
        assertFalse(viewModel.uiState.value.showErrorBanner)
    }

    @Test
    fun `clearError on banner error clears banner state`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showError(CalendarError.Sync.NoAccountsConfigured)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showErrorBanner)

        viewModel.clearError()

        assertFalse(viewModel.uiState.value.showErrorBanner)
    }

    // ==================== Convenience Method Tests ====================

    @Test
    fun `showHttpError with 401 shows auth error`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showHttpError(401)

        val state = viewModel.uiState.value
        assertTrue(state.currentError is ErrorPresentation.Dialog)
        assertTrue(state.showErrorDialog)
    }

    @Test
    fun `showHttpError with 404 shows not found snackbar`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showHttpError(404, "calendar")

        val state = viewModel.uiState.value
        assertTrue(state.currentError is ErrorPresentation.Snackbar)
    }

    @Test
    fun `showHttpError with 429 shows rate limited snackbar`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showHttpError(429)

        val state = viewModel.uiState.value
        assertTrue(state.currentError is ErrorPresentation.Snackbar)
    }

    @Test
    fun `showHttpError with 500 shows server unavailable snackbar`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showHttpError(500)

        val state = viewModel.uiState.value
        assertTrue(state.currentError is ErrorPresentation.Snackbar)
    }

    @Test
    fun `showHttpError with 503 shows server unavailable snackbar`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showHttpError(503)

        assertTrue(viewModel.uiState.value.currentError is ErrorPresentation.Snackbar)
    }

    @Test
    fun `showExceptionError with timeout exception shows timeout error`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showExceptionError(java.net.SocketTimeoutException())

        assertTrue(viewModel.uiState.value.currentError is ErrorPresentation.Snackbar)
    }

    @Test
    fun `showExceptionError with unknown host shows network error`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showExceptionError(java.net.UnknownHostException("caldav.icloud.com"))

        assertTrue(viewModel.uiState.value.currentError is ErrorPresentation.Snackbar)
    }

    @Test
    fun `showExceptionError with SSL error shows SSL error`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showExceptionError(javax.net.ssl.SSLHandshakeException("Certificate error"))

        assertTrue(viewModel.uiState.value.currentError is ErrorPresentation.Snackbar)
    }

    // ==================== Error State Replacement Tests ====================

    @Test
    fun `showing new error replaces previous error`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Show snackbar error
        viewModel.showError(CalendarError.Network.Timeout)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.currentError is ErrorPresentation.Snackbar)

        // Replace with dialog error
        viewModel.showError(CalendarError.Auth.InvalidCredentials)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.currentError is ErrorPresentation.Dialog)
        assertTrue(viewModel.uiState.value.showErrorDialog)
    }

    @Test
    fun `showing dialog error replaces banner error`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Show banner
        viewModel.showError(CalendarError.Sync.NoAccountsConfigured)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showErrorBanner)

        // Replace with dialog
        viewModel.showError(CalendarError.Auth.InvalidCredentials)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showErrorDialog)
        assertFalse(viewModel.uiState.value.showErrorBanner)
    }

    // ==================== All Error Types Coverage ====================

    @Test
    fun `all network errors show snackbar`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val networkErrors = listOf(
            CalendarError.Network.Offline,
            CalendarError.Network.Timeout,
            CalendarError.Network.SslError,
            CalendarError.Network.UnknownHost,
            CalendarError.Network.ConnectionFailed("Test")
        )

        networkErrors.forEach { error ->
            viewModel.showError(error)
            assertTrue("$error should show snackbar", viewModel.uiState.value.currentError is ErrorPresentation.Snackbar)
            viewModel.clearError()
        }
    }

    @Test
    fun `all auth errors show dialog`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val authErrors = listOf(
            CalendarError.Auth.InvalidCredentials,
            CalendarError.Auth.AppSpecificPasswordRequired,
            CalendarError.Auth.SessionExpired,
            CalendarError.Auth.AccountLocked
        )

        authErrors.forEach { error ->
            viewModel.showError(error)
            assertTrue("$error should show dialog", viewModel.uiState.value.currentError is ErrorPresentation.Dialog)
            assertTrue("$error should set showErrorDialog", viewModel.uiState.value.showErrorDialog)
            viewModel.clearError()
        }
    }

    @Test
    fun `all event errors show snackbar`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val eventErrors = listOf(
            CalendarError.Event.NotFound(1L),
            CalendarError.Event.CalendarNotFound(1L),
            CalendarError.Event.ReadOnlyCalendar("Work"),
            CalendarError.Event.InvalidData("reason"),
            CalendarError.Event.InvalidRecurrence("FREQ=INVALID")
        )

        eventErrors.forEach { error ->
            viewModel.showError(error)
            assertTrue("$error should show snackbar", viewModel.uiState.value.currentError is ErrorPresentation.Snackbar)
            viewModel.clearError()
        }
    }

    @Test
    fun `storage errors show dialog`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val storageErrors = listOf(
            CalendarError.Storage.StorageFull,
            CalendarError.Storage.DatabaseCorruption,
            CalendarError.Storage.MigrationFailed(1, 2)
        )

        storageErrors.forEach { error ->
            viewModel.showError(error)
            assertTrue("$error should show dialog", viewModel.uiState.value.currentError is ErrorPresentation.Dialog)
            viewModel.clearError()
        }
    }

    @Test
    fun `permission errors show dialog`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showError(CalendarError.Permission.NotificationDenied)
        assertTrue(viewModel.uiState.value.currentError is ErrorPresentation.Dialog)
        viewModel.clearError()

        viewModel.showError(CalendarError.Permission.ExactAlarmDenied)
        assertTrue(viewModel.uiState.value.currentError is ErrorPresentation.Dialog)
        viewModel.clearError()

        // Storage permission is snackbar (less critical)
        viewModel.showError(CalendarError.Permission.StorageDenied)
        assertTrue(viewModel.uiState.value.currentError is ErrorPresentation.Snackbar)
    }

    @Test
    fun `import export errors show snackbar`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val importExportErrors = listOf(
            CalendarError.ImportExport.FileNotFound,
            CalendarError.ImportExport.InvalidIcsFormat("reason"),
            CalendarError.ImportExport.PartialImport(5, 2),
            CalendarError.ImportExport.ExportFailed("reason"),
            CalendarError.ImportExport.NoEventsToExport
        )

        importExportErrors.forEach { error ->
            viewModel.showError(error)
            assertTrue("$error should show snackbar", viewModel.uiState.value.currentError is ErrorPresentation.Snackbar)
            viewModel.clearError()
        }
    }
}
