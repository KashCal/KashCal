package org.onekash.kashcal.ui.viewmodels

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.onekash.kashcal.R
import org.onekash.kashcal.sync.provider.icloud.ICloudAccount
import org.onekash.kashcal.sync.provider.icloud.ICloudAuthManager
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.reader.SyncLogReader
import org.onekash.kashcal.data.db.entity.SyncLog
import org.onekash.kashcal.data.preferences.UserPreferencesRepository
import org.onekash.kashcal.data.contacts.ContactBirthdayManager
import org.onekash.kashcal.data.contacts.ContactBirthdayWorker
import org.onekash.kashcal.data.ics.IcsRefreshWorker
import org.onekash.kashcal.data.ics.IcsSubscriptionRepository
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.discovery.AccountDiscoveryService
import org.onekash.kashcal.sync.discovery.DiscoveredCalendar
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.onekash.kashcal.sync.provider.caldav.CalDavAccountDiscoveryService
import org.onekash.kashcal.sync.provider.caldav.CalDavCredentialManager
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.ui.screens.AccountSettingsUiState
import org.onekash.kashcal.ui.screens.settings.CalDavAccountUiModel
import org.onekash.kashcal.ui.screens.settings.CalDavConnectionState
import org.onekash.kashcal.ui.screens.settings.ICloudAccountUiModel
import org.onekash.kashcal.ui.screens.settings.ICloudConnectionState
import org.onekash.kashcal.ui.screens.settings.IcsSubscriptionUiModel
import org.onekash.kashcal.ui.screens.settings.SubscriptionColors
import org.onekash.kashcal.ui.model.CalendarGroup
import javax.inject.Inject
import org.onekash.kashcal.data.db.entity.IcsSubscription as IcsSubscriptionEntity

private const val TAG = "AccountSettingsVM"

// Maximum time to wait for iCloud discovery (30 seconds)
// This prevents UI from hanging indefinitely on network issues
private const val DISCOVERY_TIMEOUT_MS = 30_000L

// Retry configuration for discovery timeouts
private const val MAX_DISCOVERY_RETRIES = 2
private const val DISCOVERY_RETRY_DELAY_MS = 1000L

/**
 * Execute a block with timeout, retrying on timeout.
 *
 * Only retries on timeout (null result from withTimeoutOrNull).
 * Does NOT retry on errors returned by the block (e.g., auth errors).
 *
 * @param maxRetries Maximum number of attempts
 * @param timeoutMs Timeout per attempt in milliseconds
 * @param retryDelayMs Delay between retries
 * @param block The suspend block to execute
 * @return Result from block, or null if all attempts timed out
 */
private suspend fun <T> withRetryOnTimeout(
    maxRetries: Int = MAX_DISCOVERY_RETRIES,
    timeoutMs: Long = DISCOVERY_TIMEOUT_MS,
    retryDelayMs: Long = DISCOVERY_RETRY_DELAY_MS,
    block: suspend () -> T
): T? {
    repeat(maxRetries) { attempt ->
        val result = withTimeoutOrNull(timeoutMs) { block() }
        if (result != null) return result
        Log.w(TAG, "Discovery timeout (attempt ${attempt + 1}/$maxRetries)")
        if (attempt < maxRetries - 1) delay(retryDelayMs)
    }
    return null
}

/**
 * ViewModel for AccountSettingsScreen.
 * Manages account connection, calendar settings, and user preferences.
 *
 * Wires UI components to backend services:
 * - ICloudAuthManager for credential storage
 * - EventCoordinator for calendar data (follows architecture pattern)
 * - UserPreferencesRepository for user settings
 * - SyncScheduler for sync scheduling
 * - SyncLogReader for debug logs
 */
@HiltViewModel
class AccountSettingsViewModel @Inject constructor(
    application: Application,
    private val authManager: ICloudAuthManager,
    private val userPreferences: UserPreferencesRepository,
    private val syncScheduler: SyncScheduler,
    private val discoveryService: AccountDiscoveryService,
    private val calDavDiscoveryService: CalDavAccountDiscoveryService,
    private val calDavCredentialManager: CalDavCredentialManager,
    private val eventCoordinator: EventCoordinator,
    private val syncLogReader: SyncLogReader,
    private val contactBirthdayManager: ContactBirthdayManager,
    private val dataStore: KashCalDataStore
) : AndroidViewModel(application) {

    // Account connection state
    private val _uiState = MutableStateFlow(AccountSettingsUiState(isLoading = true))
    val uiState: StateFlow<AccountSettingsUiState> = _uiState.asStateFlow()

    // Apple ID and password input (local to ViewModel)
    private var appleIdInput = ""
    private var passwordInput = ""
    private var showHelpState = false
    private var errorMessage: String? = null

    // Initial setup mode - when true, auto-navigate back to HomeScreen after sign-in
    private var isInitialSetup = false

    // CalDAV state variables for two-phase discovery flow
    private var calDavServerUrl = ""
    private var calDavDisplayName = ""
    private var calDavDisplayNameManuallyEdited = false  // Track if user has manually edited
    private var calDavUsername = ""
    private var calDavPassword = ""
    private var calDavTrustInsecure = false
    private var validateDisplayNameJob: Job? = null  // Debounced validation job
    // Discovered data from phase 1 (needed for phase 2)
    private var calDavDiscoveredPrincipalUrl: String? = null
    private var calDavDiscoveredCalendarHomeUrl: String? = null
    private var calDavDiscoveredCalendars: List<DiscoveredCalendar> = emptyList()

    // Calendars
    private val _calendars = MutableStateFlow<List<Calendar>>(emptyList())
    val calendars: StateFlow<List<Calendar>> = _calendars.asStateFlow()

    // Calendar groups (grouped by account)
    private val _calendarGroups = MutableStateFlow<List<CalendarGroup>>(emptyList())
    val calendarGroups: StateFlow<List<CalendarGroup>> = _calendarGroups.asStateFlow()

    // Default calendar
    private val _defaultCalendarId = MutableStateFlow<Long?>(null)
    val defaultCalendarId: StateFlow<Long?> = _defaultCalendarId.asStateFlow()

    // ICS Subscriptions
    private val _subscriptions = MutableStateFlow<List<IcsSubscriptionUiModel>>(emptyList())
    val subscriptions: StateFlow<List<IcsSubscriptionUiModel>> = _subscriptions.asStateFlow()

    private val _subscriptionSyncing = MutableStateFlow(false)
    val subscriptionSyncing: StateFlow<Boolean> = _subscriptionSyncing.asStateFlow()

    // Sync settings
    private val _syncIntervalMs = MutableStateFlow(24 * 60 * 60 * 1000L)
    val syncIntervalMs: StateFlow<Long> = _syncIntervalMs.asStateFlow()

    // Notification permission
    private val _notificationsEnabled = MutableStateFlow(true)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    // Default reminders
    private val _defaultReminderTimed = MutableStateFlow(15)
    val defaultReminderTimed: StateFlow<Int> = _defaultReminderTimed.asStateFlow()

    private val _defaultReminderAllDay = MutableStateFlow(1440)
    val defaultReminderAllDay: StateFlow<Int> = _defaultReminderAllDay.asStateFlow()

    // Default event duration
    private val _defaultEventDuration = MutableStateFlow(KashCalDataStore.DEFAULT_EVENT_DURATION_MINUTES)
    val defaultEventDuration: StateFlow<Int> = _defaultEventDuration.asStateFlow()

    // Sync logs
    private val _syncLogs = MutableStateFlow<List<SyncLog>>(emptyList())
    val syncLogs: StateFlow<List<SyncLog>> = _syncLogs.asStateFlow()

    // iCloud calendar count (separate from total calendars - see Checkpoint 2 fix)
    private val _iCloudCalendarCount = MutableStateFlow(0)
    val iCloudCalendarCount: StateFlow<Int> = _iCloudCalendarCount.asStateFlow()

    // iCloud account UI model for AccountsScreen
    private val _iCloudAccount = MutableStateFlow<ICloudAccountUiModel?>(null)
    val iCloudAccount: StateFlow<ICloudAccountUiModel?> = _iCloudAccount.asStateFlow()

    // Contact Birthdays
    private val _contactBirthdaysEnabled = MutableStateFlow(false)
    val contactBirthdaysEnabled: StateFlow<Boolean> = _contactBirthdaysEnabled.asStateFlow()

    private val _contactBirthdaysColor = MutableStateFlow(SubscriptionColors.Purple)
    val contactBirthdaysColor: StateFlow<Int> = _contactBirthdaysColor.asStateFlow()

    private val _contactBirthdaysLastSync = MutableStateFlow(0L)
    val contactBirthdaysLastSync: StateFlow<Long> = _contactBirthdaysLastSync.asStateFlow()

    private val _contactBirthdaysReminder = MutableStateFlow(KashCalDataStore.DEFAULT_BIRTHDAY_REMINDER_MINUTES)
    val contactBirthdaysReminder: StateFlow<Int> = _contactBirthdaysReminder.asStateFlow()

    private val _hasContactsPermission = MutableStateFlow(false)
    val hasContactsPermission: StateFlow<Boolean> = _hasContactsPermission.asStateFlow()

    // Display settings
    private val _showEventEmojis = MutableStateFlow(true)
    val showEventEmojis: StateFlow<Boolean> = _showEventEmojis.asStateFlow()

    private val _timeFormat = MutableStateFlow(KashCalDataStore.TIME_FORMAT_SYSTEM)
    val timeFormat: StateFlow<String> = _timeFormat.asStateFlow()

    private val _firstDayOfWeek = MutableStateFlow(java.util.Calendar.SUNDAY)
    val firstDayOfWeek: StateFlow<Int> = _firstDayOfWeek.asStateFlow()

    init {
        loadInitialState()
        observeCalendars()
        observeICloudCalendarCount()
        observeCalDavAccountCount()
        observeCalDavAccounts()
        observeIcsSubscriptions()
        observeContactBirthdays()
        observeUserPreferences()
        observeDisplaySettings()
        checkNotificationPermission()
        checkContactsPermission()
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            _uiState.value = AccountSettingsUiState(isLoading = true)

            // Check if we have saved credentials
            val account = authManager.loadAccount()
            if (account != null && account.hasCredentials()) {
                val lastSync = authManager.getLastSyncTime()
                _uiState.value = AccountSettingsUiState(
                    isLoading = false,
                    iCloudState = ICloudConnectionState.Connected(
                        appleId = account.appleId,
                        lastSyncTime = if (lastSync > 0) lastSync else null,
                        calendarCount = 0 // Will be updated by calendar observer
                    )
                )
            } else {
                _uiState.value = AccountSettingsUiState(
                    isLoading = false,
                    iCloudState = ICloudConnectionState.NotConnected(
                        appleId = appleIdInput,
                        password = passwordInput,
                        showHelp = showHelpState,
                        error = errorMessage
                    )
                )
            }
        }
    }

    private fun observeCalendars() {
        viewModelScope.launch {
            combine(
                eventCoordinator.getAllCalendars(),
                eventCoordinator.getAllAccounts()
            ) { calendarList, accounts ->
                val groups = CalendarGroup.fromCalendarsAndAccounts(calendarList, accounts)
                calendarList to groups
            }.collect { (calendarList, groups) ->
                _calendars.value = calendarList
                _calendarGroups.value = groups
                // Note: iCloud calendar count is now observed separately via observeICloudCalendarCount()
            }
        }
    }

    /**
     * Observe iCloud calendar count separately.
     * Uses JOIN query to count only calendars where account.provider = "icloud".
     * Fixes bug where local calendars were incorrectly included in the count.
     */
    private fun observeICloudCalendarCount() {
        viewModelScope.launch {
            eventCoordinator.getICloudCalendarCount().collect { count ->
                _iCloudCalendarCount.value = count

                // Update calendar count in Connected state and iCloudAccount UI model
                val currentState = _uiState.value
                val iCloudState = currentState.iCloudState
                if (iCloudState is ICloudConnectionState.Connected) {
                    _uiState.update {
                        it.copy(iCloudState = iCloudState.copy(calendarCount = count))
                    }
                    // Update iCloudAccount UI model for AccountsScreen
                    _iCloudAccount.value = ICloudAccountUiModel(
                        email = iCloudState.appleId,
                        calendarCount = count
                    )
                } else {
                    _iCloudAccount.value = null
                }
            }
        }
    }

    /**
     * Observe CalDAV account count.
     */
    private fun observeCalDavAccountCount() {
        viewModelScope.launch {
            eventCoordinator.getCalDavAccountCount().collect { count ->
                _uiState.update { it.copy(calDavAccountCount = count) }
            }
        }
    }

    /**
     * Observe CalDAV accounts and populate the list for Settings UI.
     * Maps Account entities to CalDavAccountUiModel with calendar counts.
     */
    private fun observeCalDavAccounts() {
        viewModelScope.launch {
            eventCoordinator.getCalDavAccounts().collect { accounts ->
                // Map accounts to UI models with calendar counts
                val uiModels = accounts.map { account ->
                    val calendarCount = eventCoordinator.getCalendarCountForAccount(account.id)
                    CalDavAccountUiModel(
                        id = account.id,
                        email = account.email,
                        displayName = account.displayName ?: "CalDAV",
                        calendarCount = calendarCount
                    )
                }
                _uiState.update { it.copy(calDavAccounts = uiModels) }
            }
        }
    }

    private fun observeIcsSubscriptions() {
        viewModelScope.launch {
            eventCoordinator.getAllIcsSubscriptions().collect { entities ->
                // Map entity to UI model
                _subscriptions.value = entities.map { entity ->
                    IcsSubscriptionUiModel(
                        id = entity.id,
                        name = entity.name,
                        url = entity.url,
                        color = entity.color,
                        enabled = entity.enabled,
                        lastSync = entity.lastSync,
                        lastError = entity.lastError,
                        syncIntervalHours = entity.syncIntervalHours,
                        eventTypeId = entity.calendarId
                    )
                }
            }
        }
    }

    private fun observeContactBirthdays() {
        viewModelScope.launch {
            // Observe enabled state
            dataStore.contactBirthdaysEnabled.collect { enabled ->
                _contactBirthdaysEnabled.value = enabled
            }
        }
        viewModelScope.launch {
            // Observe last sync time
            dataStore.contactBirthdaysLastSync.collect { lastSync ->
                _contactBirthdaysLastSync.value = lastSync
            }
        }
        viewModelScope.launch {
            // Observe birthday reminder setting
            dataStore.birthdayReminder.collect { reminder ->
                _contactBirthdaysReminder.value = reminder
            }
        }
        viewModelScope.launch {
            // Load initial color from calendar (if exists)
            val color = eventCoordinator.getContactBirthdaysColor()
            if (color != null) {
                _contactBirthdaysColor.value = color
            }
        }
    }

    private fun observeUserPreferences() {
        viewModelScope.launch {
            combine(
                userPreferences.defaultCalendarId,
                userPreferences.syncIntervalMs,
                userPreferences.defaultReminderTimed,
                userPreferences.defaultReminderAllDay,
                userPreferences.defaultEventDuration
            ) { defaultId, syncInterval, reminderTimed, reminderAllDay, eventDuration ->
                Preferences(defaultId, syncInterval, reminderTimed, reminderAllDay, eventDuration)
            }.collect { prefs ->
                _defaultCalendarId.value = prefs.defaultCalendarId
                _syncIntervalMs.value = prefs.syncIntervalMs
                _defaultReminderTimed.value = prefs.defaultReminderTimed
                _defaultReminderAllDay.value = prefs.defaultReminderAllDay
                _defaultEventDuration.value = prefs.defaultEventDuration
            }
        }
    }

    private data class Preferences(
        val defaultCalendarId: Long?,
        val syncIntervalMs: Long,
        val defaultReminderTimed: Int,
        val defaultReminderAllDay: Int,
        val defaultEventDuration: Int
    )

    private fun observeDisplaySettings() {
        viewModelScope.launch {
            dataStore.showEventEmojis.collect { show ->
                _showEventEmojis.value = show
            }
        }
        viewModelScope.launch {
            dataStore.timeFormat.collect { format ->
                _timeFormat.value = format
            }
        }
        viewModelScope.launch {
            dataStore.firstDayOfWeek.collect { day ->
                _firstDayOfWeek.value = day
            }
        }
    }

    /**
     * Update the show event emojis preference.
     */
    fun setShowEventEmojis(show: Boolean) {
        viewModelScope.launch {
            dataStore.setShowEventEmojis(show)
        }
    }

    /**
     * Update the time format preference.
     */
    fun setTimeFormat(format: String) {
        viewModelScope.launch {
            dataStore.setTimeFormat(format)
        }
    }

    /**
     * Update the first day of week preference.
     */
    fun setFirstDayOfWeek(day: Int) {
        viewModelScope.launch {
            dataStore.setFirstDayOfWeek(day)
        }
    }

    private fun checkNotificationPermission() {
        val context = getApplication<Application>()
        _notificationsEnabled.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun checkContactsPermission() {
        val context = getApplication<Application>()
        _hasContactsPermission.value = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ==================== Account Actions ====================

    /**
     * Show the iCloud sign-in sheet.
     */
    fun showICloudSignInSheet() {
        _uiState.update { it.copy(showICloudSignInSheet = true) }
    }

    /**
     * Hide the iCloud sign-in sheet.
     */
    fun hideICloudSignInSheet() {
        _uiState.update { it.copy(showICloudSignInSheet = false) }
    }

    /**
     * Set initial setup mode.
     * When true, auto-navigate back to HomeScreen after successful sign-in.
     * Called from SettingsActivity when launched from onboarding.
     */
    fun setInitialSetupMode(initial: Boolean) {
        isInitialSetup = initial
    }

    fun onAppleIdChange(appleId: String) {
        appleIdInput = appleId
        updateNotConnectedState()
    }

    fun onPasswordChange(password: String) {
        passwordInput = password
        updateNotConnectedState()
    }

    fun onToggleHelp() {
        showHelpState = !showHelpState
        updateNotConnectedState()
    }

    private fun updateNotConnectedState() {
        val currentState = _uiState.value
        val iCloudState = currentState.iCloudState
        if (iCloudState is ICloudConnectionState.NotConnected) {
            _uiState.update {
                it.copy(
                    iCloudState = iCloudState.copy(
                        appleId = appleIdInput,
                        password = passwordInput,
                        showHelp = showHelpState,
                        error = errorMessage
                    )
                )
            }
        }
    }

    /**
     * Attempt to sign in with iCloud credentials.
     * Validates credentials, discovers calendars, creates Account/Calendar entities,
     * then triggers initial event sync.
     */
    fun onSignIn() {
        viewModelScope.launch {
            errorMessage = null
            _uiState.update { it.copy(iCloudState = ICloudConnectionState.Connecting) }

            // Validate inputs
            if (appleIdInput.isBlank() || passwordInput.isBlank()) {
                errorMessage = "Apple ID and password are required"
                _uiState.update {
                    it.copy(
                        iCloudState = ICloudConnectionState.NotConnected(
                            appleId = appleIdInput,
                            password = passwordInput,
                            showHelp = showHelpState,
                            error = errorMessage
                        )
                    )
                }
                return@launch
            }

            Log.i(TAG, "Starting iCloud discovery for: ${appleIdInput.trim()}")

            // Discover account and calendars with timeout and retry
            // Retries on timeout only, not on auth errors
            val result = withRetryOnTimeout {
                discoveryService.discoverAndCreateAccount(
                    username = appleIdInput.trim(),
                    password = passwordInput.trim()
                )
            }

            when {
                result == null -> {
                    // All retries timed out - network too slow or server unreachable
                    Log.e(TAG, "Discovery timed out after $MAX_DISCOVERY_RETRIES attempts")
                    errorMessage = "Connection timed out. Please try again."
                    _uiState.update {
                        it.copy(
                            iCloudState = ICloudConnectionState.NotConnected(
                                appleId = appleIdInput,
                                password = passwordInput,
                                showHelp = showHelpState,
                                error = errorMessage
                            )
                        )
                    }
                }

                result is DiscoveryResult.Success -> {
                    Log.i(TAG, "Discovery successful: ${result.calendars.size} calendars")

                    // Save credentials to secure storage AFTER successful validation
                    val icloudAccount = ICloudAccount(
                        appleId = appleIdInput.trim(),
                        appSpecificPassword = passwordInput.trim(),
                        calendarHomeUrl = result.account.homeSetUrl
                    )
                    val saved = authManager.saveAccount(icloudAccount)
                    if (!saved) {
                        Log.e(TAG, "Failed to save credentials securely")
                    }

                    // Clear password from memory
                    passwordInput = ""

                    // Update state to connected (atomic state update)
                    _uiState.update {
                        it.copy(
                            iCloudState = ICloudConnectionState.Connected(
                                appleId = result.account.email,
                                lastSyncTime = null,
                                calendarCount = result.calendars.size
                            ),
                            showICloudSignInSheet = false, // Close sign-in sheet
                            // Initial setup: auto-navigate to HomeScreen
                            // Normal setup: show success sheet
                            pendingFinishActivity = isInitialSetup,
                            showAccountConnectedSheet = !isInitialSetup,
                            connectedProviderName = if (!isInitialSetup) "iCloud" else "",
                            connectedEmail = if (!isInitialSetup) result.account.email else "",
                            connectedCalendarCount = if (!isInitialSetup) result.calendars.size else 0
                        )
                    }

                    // Trigger initial event sync for discovered calendars
                    syncScheduler.requestImmediateSync(forceFullSync = true)

                    // Schedule periodic background sync with user's configured interval
                    val intervalMinutes = userPreferences.syncIntervalMs.first() / (60 * 1000L)
                    if (intervalMinutes > 0 && intervalMinutes != Long.MAX_VALUE / (60 * 1000L)) {
                        syncScheduler.schedulePeriodicSync(intervalMinutes)
                        // User-friendly format for log
                        val hours = intervalMinutes / 60
                        val displayInterval = if (hours >= 24) "${hours / 24} day(s)" else "$hours hour(s)"
                        Log.d(TAG, "Periodic background sync: every $displayInterval")
                    }
                }

                result is DiscoveryResult.AuthError -> {
                    Log.e(TAG, "Authentication failed: ${result.message}")
                    errorMessage = result.message
                    _uiState.update {
                        it.copy(
                            iCloudState = ICloudConnectionState.NotConnected(
                                appleId = appleIdInput,
                                password = passwordInput,
                                showHelp = showHelpState,
                                error = errorMessage
                            )
                        )
                    }
                }

                result is DiscoveryResult.Error -> {
                    Log.e(TAG, "Discovery failed: ${result.message}")
                    errorMessage = result.message
                    _uiState.update {
                        it.copy(
                            iCloudState = ICloudConnectionState.NotConnected(
                                appleId = appleIdInput,
                                password = passwordInput,
                                showHelp = showHelpState,
                                error = errorMessage
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Sign out from iCloud - clears credentials, removes Account/Calendar entities.
     */
    fun onSignOut() {
        viewModelScope.launch {
            Log.i(TAG, "Signing out from iCloud")

            // Get current account email before clearing
            val account = authManager.loadAccount()
            val accountEmail = account?.appleId

            // Cancel reminders BEFORE cascade delete to prevent orphaned AlarmManager alarms
            // Android best practice: AlarmManager.cancel() is safe on non-existent alarms (no-op)
            if (accountEmail != null) {
                eventCoordinator.cancelRemindersForAccount(accountEmail)
            }

            // Clear credentials from secure storage
            authManager.clearAccount()

            // Cancel scheduled syncs
            syncScheduler.cancelPeriodicSync()

            // Remove Account and Calendar entities from Room (cascades to events)
            if (accountEmail != null) {
                discoveryService.removeAccountByEmail(accountEmail)
            }

            // Reset state
            appleIdInput = ""
            passwordInput = ""
            errorMessage = null
            showHelpState = false

            _uiState.value = AccountSettingsUiState(
                isLoading = false,
                iCloudState = ICloudConnectionState.NotConnected()
            )
        }
    }

    // ==================== CalDAV Account Actions ====================

    /**
     * Show the CalDAV sign-in sheet.
     */
    fun showCalDavSignInSheet() {
        _uiState.update { it.copy(showCalDavSignInSheet = true) }
    }

    /**
     * Hide the CalDAV sign-in sheet and reset state.
     */
    fun hideCalDavSignInSheet() {
        // Cancel pending validation job
        validateDisplayNameJob?.cancel()
        validateDisplayNameJob = null

        // Reset CalDAV state
        calDavServerUrl = ""
        calDavDisplayName = ""
        calDavDisplayNameManuallyEdited = false
        calDavUsername = ""
        calDavPassword = ""
        calDavTrustInsecure = false
        calDavDiscoveredPrincipalUrl = null
        calDavDiscoveredCalendarHomeUrl = null
        calDavDiscoveredCalendars = emptyList()

        _uiState.update {
            it.copy(
                showCalDavSignInSheet = false,
                calDavState = CalDavConnectionState.NotConnected()
            )
        }
    }

    fun onCalDavServerUrlChange(serverUrl: String) {
        calDavServerUrl = serverUrl
        // Auto-populate display name from server URL + username if not manually edited
        if (!calDavDisplayNameManuallyEdited) {
            calDavDisplayName = generateDefaultDisplayName(serverUrl, calDavUsername)
            // Clear display name error since name changed
            _uiState.update {
                val current = it.calDavState as? CalDavConnectionState.NotConnected ?: return@update it
                if (current.errorField == CalDavConnectionState.ErrorField.DISPLAY_NAME) {
                    it.copy(calDavState = current.copy(error = null, errorField = null))
                } else it
            }
        }
        updateCalDavNotConnectedState()
    }

    fun onCalDavDisplayNameChange(displayName: String) {
        calDavDisplayName = displayName
        calDavDisplayNameManuallyEdited = true  // User has manually edited
        updateCalDavNotConnectedState()  // Update state FIRST, before validation job

        // Real-time validation only for manual edits (debounced 300ms)
        validateDisplayNameJob?.cancel()
        validateDisplayNameJob = viewModelScope.launch {
            delay(300)
            if (displayName.isNotBlank() && !calDavDiscoveryService.isDisplayNameAvailable(displayName)) {
                _uiState.update {
                    val current = it.calDavState as? CalDavConnectionState.NotConnected ?: return@launch
                    it.copy(calDavState = current.copy(
                        error = getApplication<Application>().getString(
                            R.string.error_display_name_exists, displayName
                        ),
                        errorField = CalDavConnectionState.ErrorField.DISPLAY_NAME
                    ))
                }
            } else {
                // Clear error if now valid
                _uiState.update {
                    val current = it.calDavState as? CalDavConnectionState.NotConnected ?: return@launch
                    if (current.errorField == CalDavConnectionState.ErrorField.DISPLAY_NAME) {
                        it.copy(calDavState = current.copy(error = null, errorField = null))
                    } else it
                }
            }
        }
    }

    fun onCalDavUsernameChange(username: String) {
        calDavUsername = username
        // Auto-populate display name from server URL + username if not manually edited
        if (!calDavDisplayNameManuallyEdited) {
            calDavDisplayName = generateDefaultDisplayName(calDavServerUrl, username)
            // Clear display name error since name changed
            _uiState.update {
                val current = it.calDavState as? CalDavConnectionState.NotConnected ?: return@update it
                if (current.errorField == CalDavConnectionState.ErrorField.DISPLAY_NAME) {
                    it.copy(calDavState = current.copy(error = null, errorField = null))
                } else it
            }
        }
        updateCalDavNotConnectedState()
    }

    fun onCalDavPasswordChange(password: String) {
        calDavPassword = password
        updateCalDavNotConnectedState()
    }

    fun onCalDavTrustInsecureChange(trustInsecure: Boolean) {
        calDavTrustInsecure = trustInsecure
        updateCalDavNotConnectedState()
    }

    private fun updateCalDavNotConnectedState() {
        val currentState = _uiState.value
        val calDavState = currentState.calDavState
        if (calDavState is CalDavConnectionState.NotConnected) {
            _uiState.update {
                it.copy(
                    calDavState = calDavState.copy(
                        serverUrl = calDavServerUrl,
                        displayName = calDavDisplayName,
                        username = calDavUsername,
                        password = calDavPassword,
                        trustInsecure = calDavTrustInsecure
                    )
                )
            }
        }
    }

    /**
     * Generate default display name from server URL and username.
     * Format: username@provider or username@hostname_prefix
     */
    private fun generateDefaultDisplayName(serverUrl: String, username: String): String {
        if (serverUrl.isBlank() || username.isBlank()) return ""

        val host = try {
            val url = if (serverUrl.startsWith("http")) serverUrl else "https://$serverUrl"
            java.net.URI(url).host ?: return username
        } catch (e: Exception) {
            return username
        }

        // Known providers get friendly names
        val providerName = when {
            host.contains("fastmail", ignoreCase = true) -> "FastMail"
            host.contains("nextcloud", ignoreCase = true) -> "Nextcloud"
            host.contains("icloud", ignoreCase = true) -> "iCloud"
            host.contains("google", ignoreCase = true) -> "Google"
            host.contains("yahoo", ignoreCase = true) -> "Yahoo"
            host.contains("outlook", ignoreCase = true) -> "Outlook"
            host.contains("mailbox.org", ignoreCase = true) -> "Mailbox.org"
            host.contains("posteo", ignoreCase = true) -> "Posteo"
            host.contains("fruux", ignoreCase = true) -> "fruux"
            host.contains("zoho", ignoreCase = true) -> "Zoho"
            else -> null
        }

        return if (providerName != null) {
            "$username@$providerName"
        } else {
            // IPv4 addresses use full address; hostnames use first part
            val isIpAddress = host.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))
            val hostPart = if (isIpAddress) host else host.split(".").firstOrNull() ?: host
            "$username@$hostPart"
        }
    }

    /**
     * Discover and connect to CalDAV server.
     *
     * Simplified flow (v21.5.0):
     * 1. Discover calendars from server
     * 2. Auto-create account with ALL discovered calendars
     * 3. Show success sheet (or error)
     */
    fun onCalDavDiscover() {
        viewModelScope.launch {
            Log.i(TAG, "Starting CalDAV discovery for: ${calDavUsername.take(3)}***")

            // Validate inputs
            if (calDavServerUrl.isBlank() || calDavUsername.isBlank() || calDavPassword.isBlank()) {
                _uiState.update {
                    it.copy(
                        calDavState = CalDavConnectionState.NotConnected(
                            serverUrl = calDavServerUrl,
                            displayName = calDavDisplayName,
                            username = calDavUsername,
                            password = calDavPassword,
                            trustInsecure = calDavTrustInsecure,
                            error = "Server URL, username, and password are required"
                        )
                    )
                }
                return@launch
            }

            // Validate display name uniqueness
            val effectiveDisplayName = calDavDisplayName.ifBlank {
                generateDefaultDisplayName(calDavServerUrl, calDavUsername)
            }

            if (!calDavDiscoveryService.isDisplayNameAvailable(effectiveDisplayName)) {
                _uiState.update {
                    it.copy(
                        calDavState = CalDavConnectionState.NotConnected(
                            serverUrl = calDavServerUrl,
                            displayName = calDavDisplayName,
                            username = calDavUsername,
                            password = calDavPassword,
                            trustInsecure = calDavTrustInsecure,
                            error = getApplication<Application>().getString(
                                R.string.error_display_name_exists, effectiveDisplayName
                            ),
                            errorField = CalDavConnectionState.ErrorField.DISPLAY_NAME
                        )
                    )
                }
                return@launch
            }

            // Transition to Discovering state
            _uiState.update {
                it.copy(
                    calDavState = CalDavConnectionState.Discovering(
                        serverUrl = calDavServerUrl,
                        username = calDavUsername
                    )
                )
            }

            // Discover calendars with timeout/retry
            val discoveryResult = withRetryOnTimeout {
                calDavDiscoveryService.discoverCalendars(
                    serverUrl = calDavServerUrl,
                    username = calDavUsername,
                    password = calDavPassword,
                    trustInsecure = calDavTrustInsecure
                )
            }

            when {
                discoveryResult == null -> {
                    Log.e(TAG, "CalDAV discovery timed out after $MAX_DISCOVERY_RETRIES attempts")
                    _uiState.update {
                        it.copy(
                            calDavState = CalDavConnectionState.NotConnected(
                                serverUrl = calDavServerUrl,
                                displayName = calDavDisplayName,
                                username = calDavUsername,
                                password = calDavPassword,
                                trustInsecure = calDavTrustInsecure,
                                error = "Connection timed out. Please try again."
                            )
                        )
                    }
                }

                discoveryResult is DiscoveryResult.CalendarsFound -> {
                    Log.i(TAG, "CalDAV discovery successful: ${discoveryResult.calendars.size} calendars")

                    // Check for zero calendars
                    if (discoveryResult.calendars.isEmpty()) {
                        _uiState.update {
                            it.copy(
                                calDavState = CalDavConnectionState.NotConnected(
                                    serverUrl = calDavServerUrl,
                                    displayName = calDavDisplayName,
                                    username = calDavUsername,
                                    password = calDavPassword,
                                    trustInsecure = calDavTrustInsecure,
                                    error = "No calendars found on this server."
                                )
                            )
                        }
                        return@launch
                    }

                    // Store discovered data for account creation
                    calDavDiscoveredPrincipalUrl = discoveryResult.principalUrl
                    calDavDiscoveredCalendarHomeUrl = discoveryResult.calendarHomeUrl
                    calDavDiscoveredCalendars = discoveryResult.calendars
                    calDavServerUrl = discoveryResult.serverUrl

                    // Auto-create account with ALL discovered calendars
                    val createResult = calDavDiscoveryService.createAccountWithSelectedCalendars(
                        serverUrl = discoveryResult.serverUrl,
                        username = calDavUsername,
                        password = calDavPassword,
                        trustInsecure = calDavTrustInsecure,
                        principalUrl = discoveryResult.principalUrl,
                        calendarHomeUrl = discoveryResult.calendarHomeUrl,
                        selectedCalendars = discoveryResult.calendars,  // ALL calendars
                        displayName = calDavDisplayName.takeIf { it.isNotBlank() }
                    )

                    when (createResult) {
                        is DiscoveryResult.Success -> {
                            Log.i(TAG, "CalDAV account created: ${createResult.account.id}")

                            // Clear password from memory
                            calDavPassword = ""

                            // Derive provider name from display name or generated default
                            val providerName = calDavDisplayName.takeIf { it.isNotBlank() }
                                ?: generateDefaultDisplayName(discoveryResult.serverUrl, calDavUsername).ifBlank { "CalDAV" }

                            // Atomic state update: close sign-in sheet + show success sheet
                            _uiState.update {
                                it.copy(
                                    calDavState = CalDavConnectionState.NotConnected(),  // Reset
                                    showCalDavSignInSheet = false,
                                    showAccountConnectedSheet = true,
                                    connectedProviderName = providerName,
                                    connectedEmail = calDavUsername,
                                    connectedCalendarCount = discoveryResult.calendars.size
                                )
                            }

                            // Reset CalDAV input state
                            calDavServerUrl = ""
                            calDavDisplayName = ""
                            calDavDisplayNameManuallyEdited = false
                            calDavUsername = ""
                            calDavTrustInsecure = false
                            calDavDiscoveredPrincipalUrl = null
                            calDavDiscoveredCalendarHomeUrl = null
                            calDavDiscoveredCalendars = emptyList()

                            // Trigger initial sync for the new account
                            syncScheduler.requestImmediateSync(forceFullSync = true)

                            // Schedule periodic sync
                            val intervalMinutes = userPreferences.syncIntervalMs.first() / (60 * 1000L)
                            if (intervalMinutes > 0 && intervalMinutes != Long.MAX_VALUE / (60 * 1000L)) {
                                syncScheduler.schedulePeriodicSync(intervalMinutes)
                            }
                        }

                        is DiscoveryResult.Error -> {
                            Log.e(TAG, "CalDAV account creation failed: ${createResult.message}")
                            _uiState.update {
                                it.copy(
                                    calDavState = CalDavConnectionState.NotConnected(
                                        serverUrl = calDavServerUrl,
                                        displayName = calDavDisplayName,
                                        username = calDavUsername,
                                        password = calDavPassword,
                                        trustInsecure = calDavTrustInsecure,
                                        error = createResult.message
                                    )
                                )
                            }
                        }

                        else -> {
                            Log.e(TAG, "Unexpected result from createAccountWithSelectedCalendars: $createResult")
                            _uiState.update {
                                it.copy(
                                    calDavState = CalDavConnectionState.NotConnected(
                                        serverUrl = calDavServerUrl,
                                        displayName = calDavDisplayName,
                                        username = calDavUsername,
                                        password = calDavPassword,
                                        trustInsecure = calDavTrustInsecure,
                                        error = "Unexpected error. Please try again."
                                    )
                                )
                            }
                        }
                    }
                }

                discoveryResult is DiscoveryResult.AuthError -> {
                    Log.e(TAG, "CalDAV authentication failed: ${discoveryResult.message}")
                    _uiState.update {
                        it.copy(
                            calDavState = CalDavConnectionState.NotConnected(
                                serverUrl = calDavServerUrl,
                                displayName = calDavDisplayName,
                                username = calDavUsername,
                                password = calDavPassword,
                                trustInsecure = calDavTrustInsecure,
                                error = discoveryResult.message,
                                errorField = CalDavConnectionState.ErrorField.CREDENTIALS
                            )
                        )
                    }
                }

                discoveryResult is DiscoveryResult.Error -> {
                    Log.e(TAG, "CalDAV discovery failed: ${discoveryResult.message}")
                    val errorField = when {
                        discoveryResult.message.contains("URL", ignoreCase = true) ||
                        discoveryResult.message.contains("server", ignoreCase = true) ->
                            CalDavConnectionState.ErrorField.SERVER
                        else -> null
                    }
                    _uiState.update {
                        it.copy(
                            calDavState = CalDavConnectionState.NotConnected(
                                serverUrl = calDavServerUrl,
                                displayName = calDavDisplayName,
                                username = calDavUsername,
                                password = calDavPassword,
                                trustInsecure = calDavTrustInsecure,
                                error = discoveryResult.message,
                                errorField = errorField
                            )
                        )
                    }
                }

                else -> {
                    Log.e(TAG, "Unexpected CalDAV discovery result: $discoveryResult")
                    _uiState.update {
                        it.copy(
                            calDavState = CalDavConnectionState.NotConnected(
                                serverUrl = calDavServerUrl,
                                displayName = calDavDisplayName,
                                username = calDavUsername,
                                password = calDavPassword,
                                trustInsecure = calDavTrustInsecure,
                                error = "Unexpected error. Please try again."
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Sign out from a CalDAV account.
     *
     * @param accountId The CalDAV account ID to remove
     */
    fun onCalDavSignOut(accountId: Long) {
        viewModelScope.launch {
            Log.i(TAG, "Signing out from CalDAV account: $accountId")

            // Cancel reminders before cascade delete
            eventCoordinator.cancelRemindersForCalDavAccount(accountId)

            // Remove account (credentials + Room entities)
            calDavDiscoveryService.removeAccount(accountId)

            Log.i(TAG, "CalDAV account $accountId removed")
        }
    }

    // ==================== Calendar Actions ====================

    fun onToggleCalendar(calendarId: Long, visible: Boolean) {
        viewModelScope.launch {
            // Update via EventCoordinator (source of truth for EventReader, Widget, UI)
            eventCoordinator.setCalendarVisibility(calendarId, visible)
        }
    }

    fun onShowAllCalendars() {
        viewModelScope.launch {
            // Update via EventCoordinator for each calendar (source of truth)
            _calendars.value.forEach { calendar ->
                eventCoordinator.setCalendarVisibility(calendar.id, true)
            }
        }
    }

    fun onHideAllCalendars() {
        viewModelScope.launch {
            // Can't hide all calendars - keep first one visible
            val firstCalendarId = _calendars.value.firstOrNull()?.id
            // Update via EventCoordinator for each calendar (source of truth)
            _calendars.value.forEach { calendar ->
                eventCoordinator.setCalendarVisibility(calendar.id, calendar.id == firstCalendarId)
            }
        }
    }

    fun onDefaultCalendarSelect(calendarId: Long) {
        viewModelScope.launch {
            userPreferences.setDefaultCalendarId(calendarId)
        }
    }

    // ==================== Subscription Actions ====================

    /**
     * Open the add subscription dialog with a pre-filled URL.
     * Used when handling webcal:// deep links.
     */
    fun openAddSubscriptionWithUrl(url: String) {
        _uiState.update {
            it.copy(
                showAddSubscriptionDialog = true,
                prefillSubscriptionUrl = url
            )
        }
    }

    /**
     * Hide the add subscription dialog and clear any pre-filled URL.
     */
    fun hideAddSubscriptionDialog() {
        _uiState.update {
            it.copy(
                showAddSubscriptionDialog = false,
                prefillSubscriptionUrl = null
            )
        }
    }

    /**
     * Add a new ICS calendar subscription.
     *
     * @param url The ICS feed URL (supports webcal:// and https://)
     * @param name Display name for the subscription
     * @param color Calendar color (ARGB integer)
     */
    fun onAddSubscription(url: String, name: String, color: Int) {
        viewModelScope.launch {
            Log.i(TAG, "Adding subscription: $url, $name")

            when (val result = eventCoordinator.addIcsSubscription(url, name, color)) {
                is IcsSubscriptionRepository.SubscriptionResult.Success -> {
                    Log.i(TAG, "Subscription added: ${result.subscription.name}")
                    // Schedule periodic refresh if this is the first subscription
                    IcsRefreshWorker.schedulePeriodicRefresh(getApplication())
                }

                is IcsSubscriptionRepository.SubscriptionResult.Error -> {
                    Log.e(TAG, "Failed to add subscription: ${result.message}")
                    // Could show error to user via separate error state
                }
            }
        }
    }

    /**
     * Delete an ICS subscription and all its events.
     */
    fun onDeleteSubscription(subscriptionId: Long) {
        viewModelScope.launch {
            Log.i(TAG, "Deleting subscription: $subscriptionId")
            eventCoordinator.removeIcsSubscription(subscriptionId)
        }
    }

    /**
     * Enable or disable an ICS subscription.
     */
    fun onToggleSubscription(subscriptionId: Long, enabled: Boolean) {
        viewModelScope.launch {
            Log.i(TAG, "Toggle subscription: $subscriptionId, enabled=$enabled")
            eventCoordinator.setIcsSubscriptionEnabled(subscriptionId, enabled)
        }
    }

    /**
     * Refresh all ICS subscriptions immediately.
     */
    fun onSyncAllSubscriptions() {
        viewModelScope.launch {
            _subscriptionSyncing.value = true
            Log.i(TAG, "Syncing all subscriptions")

            try {
                val results = eventCoordinator.forceRefreshAllIcsSubscriptions()
                val successCount = results.count { it is IcsSubscriptionRepository.SyncResult.Success }
                val errorCount = results.count { it is IcsSubscriptionRepository.SyncResult.Error }
                Log.i(TAG, "Subscription sync complete: $successCount success, $errorCount errors")
            } catch (e: Exception) {
                Log.e(TAG, "Subscription sync failed", e)
            }

            _subscriptionSyncing.value = false
        }
    }

    /**
     * Refresh a single ICS subscription.
     */
    fun onRefreshSubscription(subscriptionId: Long) {
        viewModelScope.launch {
            Log.i(TAG, "Refreshing subscription: $subscriptionId")
            eventCoordinator.refreshIcsSubscription(subscriptionId)
        }
    }

    /**
     * Update subscription settings (name, color, sync interval).
     */
    fun onUpdateSubscription(subscriptionId: Long, name: String, color: Int, syncIntervalHours: Int) {
        viewModelScope.launch {
            Log.i(TAG, "Updating subscription: $subscriptionId, name=$name, interval=${syncIntervalHours}h")
            eventCoordinator.updateIcsSubscriptionSettings(subscriptionId, name, color, syncIntervalHours)
        }
    }

    // ==================== Contact Birthdays ====================

    /**
     * Toggle contact birthdays feature.
     *
     * Note: Permission should be checked by the caller before enabling.
     * If permission is denied, this method should not be called with enabled=true.
     */
    fun onToggleContactBirthdays(enabled: Boolean) {
        viewModelScope.launch {
            Log.i(TAG, "Toggle contact birthdays: enabled=$enabled")

            if (enabled) {
                // Enable: create calendar + start sync
                val color = _contactBirthdaysColor.value
                eventCoordinator.enableContactBirthdays(color)
                dataStore.setContactBirthdaysEnabled(true)
                contactBirthdayManager.onEnabled()
            } else {
                // Disable: delete calendar + stop observer
                dataStore.setContactBirthdaysEnabled(false)
                dataStore.setContactBirthdaysLastSync(0L)
                contactBirthdayManager.onDisabled()
                eventCoordinator.disableContactBirthdays()
            }
        }
    }

    /**
     * Update contact birthdays calendar color.
     */
    fun onContactBirthdaysColorChange(color: Int) {
        viewModelScope.launch {
            Log.i(TAG, "Contact birthdays color change: $color")
            _contactBirthdaysColor.value = color
            eventCoordinator.updateContactBirthdaysColor(color)
        }
    }

    /**
     * Update birthday reminder setting.
     */
    fun onContactBirthdaysReminderChange(minutes: Int) {
        viewModelScope.launch {
            Log.i(TAG, "Birthday reminder change: $minutes minutes")
            dataStore.setBirthdayReminder(minutes)
        }
    }

    // ==================== Sync Settings ====================

    fun onSyncIntervalChange(intervalMs: Long) {
        viewModelScope.launch {
            userPreferences.setSyncIntervalMs(intervalMs)

            // Update sync scheduler with new interval
            if (intervalMs != Long.MAX_VALUE) {
                val intervalMinutes = intervalMs / (60 * 1000L)
                syncScheduler.updatePeriodicSyncInterval(intervalMinutes)
            } else {
                syncScheduler.cancelPeriodicSync()
            }
        }
    }

    /**
     * Force a full sync, re-downloading all calendar data.
     * Useful when data seems out of sync with server.
     */
    fun forceFullSync() {
        syncScheduler.setShowBannerForSync(true)  // Show banner on HomeScreen
        syncScheduler.requestImmediateSync(forceFullSync = true)
    }

    // ==================== Reminder Settings ====================

    fun onDefaultReminderTimedChange(minutes: Int) {
        viewModelScope.launch {
            userPreferences.setDefaultReminderTimed(minutes)
        }
    }

    fun onDefaultReminderAllDayChange(minutes: Int) {
        viewModelScope.launch {
            userPreferences.setDefaultReminderAllDay(minutes)
        }
    }

    fun onDefaultEventDurationChange(minutes: Int) {
        viewModelScope.launch {
            userPreferences.setDefaultEventDuration(minutes)
        }
    }

    // ==================== Notifications ====================

    fun onRequestNotificationPermission() {
        val context = getApplication<Application>()
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /**
     * Refresh permission state (call from Activity onResume).
     */
    fun refreshNotificationPermission() {
        checkNotificationPermission()
    }

    /**
     * Refresh contacts permission state (call from Activity onResume).
     */
    fun refreshContactsPermission() {
        checkContactsPermission()
    }

    // ==================== Sync Logs ====================

    fun loadSyncLogs() {
        viewModelScope.launch {
            syncLogReader.getRecentLogs(100).collect { logs ->
                _syncLogs.value = logs
            }
        }
    }

    // ==================== Navigation Callbacks ====================

    /**
     * Get the default reminder for new timed events.
     */
    suspend fun getDefaultReminderTimed(): Int {
        return userPreferences.defaultReminderTimed.first()
    }

    /**
     * Get the default reminder for new all-day events.
     */
    suspend fun getDefaultReminderAllDay(): Int {
        return userPreferences.defaultReminderAllDay.first()
    }

    // ==================== Snackbar ====================

    /**
     * Show a snackbar message to the user.
     */
    fun showSnackbar(message: String) {
        _uiState.update { it.copy(pendingSnackbarMessage = message) }
    }

    /**
     * Clear the pending snackbar message after it's shown.
     */
    fun clearSnackbar() {
        _uiState.update { it.copy(pendingSnackbarMessage = null) }
    }

    // ==================== Account Connected Sheet ====================

    /**
     * Show the success sheet after account connection.
     *
     * @param provider Display name of the provider (e.g., "iCloud", "Nextcloud")
     * @param email User email
     * @param calendarCount Number of calendars discovered
     */
    fun showAccountConnectedSheet(provider: String, email: String, calendarCount: Int) {
        _uiState.update {
            it.copy(
                showAccountConnectedSheet = true,
                connectedProviderName = provider,
                connectedEmail = email,
                connectedCalendarCount = calendarCount
            )
        }
    }

    /**
     * Hide the success sheet and stay on AccountsScreen.
     */
    fun hideAccountConnectedSheet() {
        _uiState.update {
            it.copy(
                showAccountConnectedSheet = false,
                connectedProviderName = "",
                connectedEmail = "",
                connectedCalendarCount = 0
            )
        }
    }

    /**
     * Handle "Done" button on success sheet - finish activity and return to HomeScreen.
     */
    fun onAccountConnectedDone() {
        _uiState.update {
            it.copy(
                showAccountConnectedSheet = false,
                connectedProviderName = "",
                connectedEmail = "",
                connectedCalendarCount = 0,
                pendingFinishActivity = true
            )
        }
    }
}
