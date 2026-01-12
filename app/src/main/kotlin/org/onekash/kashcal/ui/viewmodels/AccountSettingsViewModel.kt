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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.sync.discovery.AccountDiscoveryService
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.ui.screens.AccountSettingsUiState
import org.onekash.kashcal.ui.screens.settings.ICloudConnectionState
import org.onekash.kashcal.ui.screens.settings.IcsSubscriptionUiModel
import org.onekash.kashcal.ui.screens.settings.SubscriptionColors
import javax.inject.Inject
import org.onekash.kashcal.data.db.entity.IcsSubscription as IcsSubscriptionEntity

private const val TAG = "AccountSettingsVM"

// Maximum time to wait for iCloud discovery (30 seconds)
// This prevents UI from hanging indefinitely on network issues
private const val DISCOVERY_TIMEOUT_MS = 30_000L

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

    // Calendars
    private val _calendars = MutableStateFlow<List<Calendar>>(emptyList())
    val calendars: StateFlow<List<Calendar>> = _calendars.asStateFlow()

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

    init {
        loadInitialState()
        observeCalendars()
        observeICloudCalendarCount()
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
            eventCoordinator.getAllCalendars().collect { calendarList ->
                _calendars.value = calendarList
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

                // Update calendar count in Connected state
                val currentState = _uiState.value
                val iCloudState = currentState.iCloudState
                if (iCloudState is ICloudConnectionState.Connected) {
                    _uiState.update {
                        it.copy(iCloudState = iCloudState.copy(calendarCount = count))
                    }
                }
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
    }

    /**
     * Update the show event emojis preference.
     */
    fun setShowEventEmojis(show: Boolean) {
        viewModelScope.launch {
            dataStore.setShowEventEmojis(show)
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

            // Discover account and calendars with timeout (validates credentials against server)
            // Timeout ensures UI never hangs indefinitely on network issues
            val result = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                discoveryService.discoverAndCreateAccount(
                    username = appleIdInput.trim(),
                    password = passwordInput.trim()
                )
            }

            when {
                result == null -> {
                    // Timeout occurred - network too slow or server unreachable
                    Log.e(TAG, "Discovery timed out after ${DISCOVERY_TIMEOUT_MS}ms")
                    errorMessage = "Connection timed out. Check your internet and try again."
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

                    // Update state to connected
                    _uiState.update {
                        it.copy(
                            iCloudState = ICloudConnectionState.Connected(
                                appleId = result.account.email,
                                lastSyncTime = null,
                                calendarCount = result.calendars.size
                            ),
                            showICloudSignInSheet = false, // Close sheet on success
                            pendingFinishActivity = isInitialSetup // Auto-navigate on initial setup
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
}
