package org.onekash.kashcal.ui.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.onekash.kashcal.R
import org.onekash.kashcal.di.ApplicationScope
import org.onekash.kashcal.data.calendar_provider.CalendarProviderManager
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.contacts.ContactEventManager
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncLog
import org.onekash.kashcal.data.ics.IcsSubscriptionRepository
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.preferences.UserPreferencesRepository
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.ContactPurgeOutcome
import org.onekash.kashcal.domain.backup.BackupImportError
import org.onekash.kashcal.domain.backup.BackupParseResult
import org.onekash.kashcal.domain.backup.SettingsBackupExporter
import org.onekash.kashcal.domain.backup.SettingsBackupImporter
import org.onekash.kashcal.domain.backup.toSummary
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.SyncLogReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.discovery.AccountDiscoveryService
import org.onekash.kashcal.sync.discovery.DiscoveredCalendar
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.onekash.kashcal.sync.provider.caldav.CalDavAccountDiscoveryService
import org.onekash.kashcal.sync.scheduler.IcsScheduler
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.model.localizedDisplayName
import org.onekash.kashcal.ui.permission.LocalNetworkPermissionState
import org.onekash.kashcal.ui.permission.PermissionChecker
import org.onekash.kashcal.ui.permission.contactSyncPermissionGranted
import org.onekash.kashcal.ui.permission.reconcileOnResume
import org.onekash.kashcal.ui.permission.failureIndicatesBlockedLan
import org.onekash.kashcal.ui.screens.AccountSettingsUiState
import org.onekash.kashcal.ui.screens.BackupRestoreUiState
import org.onekash.kashcal.ui.screens.settings.AccountDetailDiscoverStatus
import org.onekash.kashcal.ui.screens.settings.AccountDetailSyncStatus
import org.onekash.kashcal.ui.screens.settings.CalDavAccountUiModel
import org.onekash.kashcal.ui.screens.settings.ContactSyncConfirmation
import org.onekash.kashcal.ui.screens.settings.CalDavConnectionState
import org.onekash.kashcal.ui.screens.settings.ICloudConnectionState
import org.onekash.kashcal.ui.screens.settings.IcsSubscriptionUiModel
import org.onekash.kashcal.ui.screens.settings.toDetailUiModel
import org.onekash.kashcal.ui.shared.EventColorPalette
import org.onekash.kashcal.ui.shared.maskEmail
import org.onekash.kashcal.ui.theme.ColorSource
import org.onekash.kashcal.ui.theme.ThemeMode
import org.onekash.kashcal.ui.util.UiMessage
import org.onekash.kashcal.util.importEventsToDeviceCalendar
import org.onekash.kashcal.util.maskEmail
import org.onekash.kashcal.widget.WidgetUpdateManager
import javax.inject.Inject

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
 * - AccountRepository for account and credential operations
 * - EventCoordinator for calendar data (follows architecture pattern)
 * - UserPreferencesRepository for user settings
 * - SyncScheduler for sync scheduling
 * - SyncLogReader for debug logs
 */
@HiltViewModel
class AccountSettingsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val userPreferences: UserPreferencesRepository,
    private val syncScheduler: SyncScheduler,
    private val discoveryService: AccountDiscoveryService,
    private val calDavDiscoveryService: CalDavAccountDiscoveryService,
    private val eventCoordinator: EventCoordinator,
    private val eventWriter: EventWriter,
    private val syncLogReader: SyncLogReader,
    private val contactEventManager: ContactEventManager,
    private val calendarProviderManager: CalendarProviderManager,
    private val calendarProviderRepository: CalendarProviderRepository,
    private val dataStore: KashCalDataStore,
    private val widgetUpdateManager: WidgetUpdateManager,
    private val deviceCalendarReminderScheduler: org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler,
    private val backupExporter: SettingsBackupExporter,
    private val backupImporter: SettingsBackupImporter,
    private val permissionChecker: PermissionChecker,
    private val icsScheduler: IcsScheduler,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    // Account connection state
    private val _uiState = MutableStateFlow(AccountSettingsUiState(isLoading = true))
    val uiState: StateFlow<AccountSettingsUiState> = _uiState.asStateFlow()

    // Apple ID and password input (local to ViewModel)
    private var appleIdInput = ""
    private var passwordInput = ""
    private var showHelpState = false

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

    // Default calendar (legacy)
    private val _defaultCalendarId = MutableStateFlow<Long?>(null)
    val defaultCalendarId: StateFlow<Long?> = _defaultCalendarId.asStateFlow()

    // Default calendar (new format supporting Room and Device)
    private val _defaultCalendar = MutableStateFlow<DefaultCalendar?>(null)
    val defaultCalendar: StateFlow<DefaultCalendar?> = _defaultCalendar.asStateFlow()

    // Writable device calendars for default calendar picker (requires WRITE_CALENDAR)
    private val _writableDeviceCalendarGroups = MutableStateFlow<List<CalendarGroup>>(emptyList())
    val writableDeviceCalendarGroups: StateFlow<List<CalendarGroup>> = _writableDeviceCalendarGroups.asStateFlow()

    // ICS Subscriptions — exposed [subscriptions] filters out the row in the
    // delete-with-undo window so it disappears immediately on swipe (issue #133).
    private val _subscriptionsRaw = MutableStateFlow<List<IcsSubscriptionUiModel>>(emptyList())
    private val _pendingSubscriptionDeletionId = MutableStateFlow<Long?>(null)
    val subscriptions: StateFlow<List<IcsSubscriptionUiModel>> =
        combine(_subscriptionsRaw, _pendingSubscriptionDeletionId) { raw, pending ->
            if (pending == null) raw else raw.filter { it.id != pending }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    private val _subscriptionSyncing = MutableStateFlow(false)
    val subscriptionSyncing: StateFlow<Boolean> = _subscriptionSyncing.asStateFlow()

    // Sync settings
    private val _syncIntervalMs = MutableStateFlow(24 * 60 * 60 * 1000L)
    val syncIntervalMs: StateFlow<Long> = _syncIntervalMs.asStateFlow()

    private val _syncLookbackDays = MutableStateFlow(KashCalDataStore.DEFAULT_SYNC_PAST_DAYS)
    val syncLookbackDays: StateFlow<Int> = _syncLookbackDays.asStateFlow()

    // Default reminders
    private val _defaultReminderTimed = MutableStateFlow(15)
    val defaultReminderTimed: StateFlow<Int> = _defaultReminderTimed.asStateFlow()

    private val _defaultReminderAllDay = MutableStateFlow(1440)
    val defaultReminderAllDay: StateFlow<Int> = _defaultReminderAllDay.asStateFlow()

    // Default event duration
    private val _defaultEventDuration = MutableStateFlow(KashCalDataStore.DEFAULT_EVENT_DURATION_MINUTES)
    val defaultEventDuration: StateFlow<Int> = _defaultEventDuration.asStateFlow()

    // Settings search state. Owned exclusively by this ViewModel; sub-screens
    // never receive a non-empty query. Cleared on navigation away (see the
    // onCleared override).
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    // Sync logs
    private val _syncLogs = MutableStateFlow<List<SyncLog>>(emptyList())
    val syncLogs: StateFlow<List<SyncLog>> = _syncLogs.asStateFlow()

    // Contact Birthdays
    private val _contactBirthdaysEnabled = MutableStateFlow(false)
    val contactBirthdaysEnabled: StateFlow<Boolean> = _contactBirthdaysEnabled.asStateFlow()

    private val _contactBirthdaysColor = MutableStateFlow(EventColorPalette.randomArgb())
    val contactBirthdaysColor: StateFlow<Int> = _contactBirthdaysColor.asStateFlow()

    private val _contactBirthdaysReminder = MutableStateFlow(KashCalDataStore.DEFAULT_BIRTHDAY_REMINDER_MINUTES)
    val contactBirthdaysReminder: StateFlow<Int> = _contactBirthdaysReminder.asStateFlow()

    // Contact Anniversaries
    private val _contactAnniversariesEnabled = MutableStateFlow(false)
    val contactAnniversariesEnabled: StateFlow<Boolean> = _contactAnniversariesEnabled.asStateFlow()

    private val _contactAnniversariesColor = MutableStateFlow(EventColorPalette.randomArgb())
    val contactAnniversariesColor: StateFlow<Int> = _contactAnniversariesColor.asStateFlow()

    private val _contactAnniversariesReminder = MutableStateFlow(KashCalDataStore.DEFAULT_BIRTHDAY_REMINDER_MINUTES)
    val contactAnniversariesReminder: StateFlow<Int> = _contactAnniversariesReminder.asStateFlow()

    // Event counts
    private val _birthdayCount = MutableStateFlow(0)
    val birthdayCount: StateFlow<Int> = _birthdayCount.asStateFlow()

    private val _anniversaryCount = MutableStateFlow(0)
    val anniversaryCount: StateFlow<Int> = _anniversaryCount.asStateFlow()

    private val _hasContactsPermission = MutableStateFlow(false)
    val hasContactsPermission: StateFlow<Boolean> = _hasContactsPermission.asStateFlow()

    // Contact SYNC needs READ + WRITE (it mirrors server contacts onto the
    // device), unlike the birthday/anniversary reads above which need READ alone.
    // Kept separate so a read-granted/write-denied login doesn't flip the sync
    // toggle on without ever requesting WRITE.
    private val _hasContactsSyncPermission = MutableStateFlow(false)
    val hasContactsSyncPermission: StateFlow<Boolean> = _hasContactsSyncPermission.asStateFlow()

    /**
     * True when a background contact sync was skipped because WRITE_CONTACTS was
     * revoked. Drives the inline re-grant affordance in the account detail sheet.
     * App-global because WRITE_CONTACTS is a single app-wide runtime permission,
     * not per-account.
     */
    val contactSyncPermissionNeeded: StateFlow<Boolean> =
        dataStore.contactSyncPermissionNeeded.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    // Local-network permission (Android 17+): resolved by the Activity (needs
    // rationale read) and pushed here so the sign-in sheet can proactively ask
    // for LAN servers. Defaults to NotRequired so pre-37 OS never shows anything.
    private val _localNetworkPermissionState =
        MutableStateFlow<LocalNetworkPermissionState>(LocalNetworkPermissionState.NotRequired)
    val localNetworkPermissionState: StateFlow<LocalNetworkPermissionState> =
        _localNetworkPermissionState.asStateFlow()

    // Set when a discovery attempt fails in a way that looks like a blocked LAN
    // socket (permission required-but-ungranted). Drives the sign-in banner even
    // when the URL string isn't recognizably local (bare hostname / custom
    // domain), so the user still gets the Allow-access affordance, not just an
    // error message. Reset at the start of each discovery attempt.
    private val _localNetworkHintActive = MutableStateFlow(false)
    val localNetworkHintActive: StateFlow<Boolean> = _localNetworkHintActive.asStateFlow()

    // Device Calendars
    private val _deviceCalendarsEnabled = MutableStateFlow(false)
    val deviceCalendarsEnabled: StateFlow<Boolean> = _deviceCalendarsEnabled.asStateFlow()

    private val _hasReadCalendarPermission = MutableStateFlow(false)
    val hasReadCalendarPermission: StateFlow<Boolean> = _hasReadCalendarPermission.asStateFlow()

    private val _hasWriteCalendarPermission = MutableStateFlow(false)
    val hasWriteCalendarPermission: StateFlow<Boolean> = _hasWriteCalendarPermission.asStateFlow()

    // Legacy alias for backward compatibility
    val hasCalendarPermission: StateFlow<Boolean> = _hasReadCalendarPermission.asStateFlow()

    private val _deviceCalendars = MutableStateFlow<List<DeviceCalendar>>(emptyList())
    val deviceCalendars: StateFlow<List<DeviceCalendar>> = _deviceCalendars.asStateFlow()

    private val _enabledDeviceCalendarIds = MutableStateFlow<Set<Long>>(emptySet())
    val enabledDeviceCalendarIds: StateFlow<Set<Long>> = _enabledDeviceCalendarIds.asStateFlow()

    private val _showDeclinedEvents = MutableStateFlow(false)
    val showDeclinedEvents: StateFlow<Boolean> = _showDeclinedEvents.asStateFlow()

    // Device calendar reminders
    private val _deviceCalendarRemindersEnabled = MutableStateFlow(true)
    val deviceCalendarRemindersEnabled: StateFlow<Boolean> = _deviceCalendarRemindersEnabled.asStateFlow()

    // Display settings
    private val _showEventEmojis = MutableStateFlow(true)
    val showEventEmojis: StateFlow<Boolean> = _showEventEmojis.asStateFlow()

    private val _timeFormat = MutableStateFlow(KashCalDataStore.TIME_FORMAT_SYSTEM)
    val timeFormat: StateFlow<String> = _timeFormat.asStateFlow()

    /**
     * Current app theme choice, derived from the stored theme string. A cold flow (not stateIn):
     * the activity seeds the first frame with a synchronous read and collects this, whose first
     * emission is the same stored value — so there's no flash of the default theme on cold start.
     */
    val themeMode: Flow<ThemeMode> = dataStore.theme
        .map { ThemeMode.fromPrefValue(it) }

    /**
     * Where app + widget colors come from (dynamic Material You vs. accent seed). Combines the
     * explicit stored source with the legacy theme string so users who had picked the retired
     * "teal" theme land on the seed path (their brand color is preserved via the seed default).
     */
    val colorSource: Flow<ColorSource> = userPreferences.resolvedColorSource

    /** Current accent seed color (packed ARGB); meaningful when [colorSource] is SEED. */
    val accentSeed: Flow<Int> = dataStore.accentSeed

    private val _firstDayOfWeek = MutableStateFlow(java.util.Calendar.SUNDAY)
    val firstDayOfWeek: StateFlow<Int> = _firstDayOfWeek.asStateFlow()

    private val _showWeekNumbers = MutableStateFlow(false)
    val showWeekNumbers: StateFlow<Boolean> = _showWeekNumbers.asStateFlow()

    private val _quickAddEnabled = MutableStateFlow(false)
    val quickAddEnabled: StateFlow<Boolean> = _quickAddEnabled.asStateFlow()

    private val _titleSuggestionsEnabled = MutableStateFlow(true)
    val titleSuggestionsEnabled: StateFlow<Boolean> = _titleSuggestionsEnabled.asStateFlow()

    private val _widgetMaxEventsPerDay = MutableStateFlow(5)
    val widgetMaxEventsPerDay: StateFlow<Int> = _widgetMaxEventsPerDay.asStateFlow()

    private val _widgetDetailedRows = MutableStateFlow(false)
    val widgetDetailedRows: StateFlow<Boolean> = _widgetDetailedRows.asStateFlow()

    private val _monthWidgetEventTitles = MutableStateFlow(false)
    val monthWidgetEventTitles: StateFlow<Boolean> = _monthWidgetEventTitles.asStateFlow()

    // Backup & Restore dialog state
    private val _backupRestoreState = MutableStateFlow<BackupRestoreUiState>(BackupRestoreUiState.Idle)
    val backupRestoreState: StateFlow<BackupRestoreUiState> = _backupRestoreState.asStateFlow()

    // Backup JSON prepared in-memory between the VM building it and the SAF writer consuming it.
    // VM-scoped so it survives Activity config changes (e.g., rotation while the SAF picker is up).
    @Volatile
    private var pendingExportJson: String? = null

    init {
        loadInitialState()
        observeCalendars()
        observeICloudCalendarCount()
        observeCalDavAccountCount()
        observeCalDavAccounts()
        observeIcsSubscriptions()
        observeContactBirthdays()
        observeContactAnniversaries()
        refreshContactEventCounts()
        observeUserPreferences()
        observeDeviceCalendars()
        observeDisplaySettings()
        checkContactsPermission()
        checkCalendarPermission()
        loadWritableDeviceCalendars()
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            _uiState.value = AccountSettingsUiState(isLoading = true)

            // Check if we have an iCloud account with credentials
            val icloudAccounts = accountRepository.getAccountsByProvider(AccountProvider.ICLOUD)
            val account = icloudAccounts.firstOrNull()

            if (account != null && accountRepository.hasCredentials(account.id)) {
                val lastSync = account.lastSuccessfulSyncAt
                val calendarCount = eventCoordinator.getICloudCalendarCount().first()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        iCloudState = ICloudConnectionState.Connected(
                            accountId = account.id,
                            appleId = account.email,
                            lastSyncTime = if (lastSync != null && lastSync > 0) lastSync else null,
                            calendarCount = calendarCount,
                            consecutiveSyncFailures = account.consecutiveSyncFailures
                        )
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        iCloudState = ICloudConnectionState.NotConnected(
                            appleId = appleIdInput,
                            password = passwordInput,
                            showHelp = showHelpState
                        )
                    )
                }
            }
        }
    }

    private fun observeCalendars() {
        viewModelScope.launch {
            combine(
                eventCoordinator.getAllCalendars(),
                eventCoordinator.getAllAccounts()
            ) { calendarList, accounts ->
                val groups = CalendarGroup.fromCalendarsAndAccounts(
                    calendarList,
                    accounts,
                    localLabel = context.getString(R.string.drawer_account_offline),
                    icsLabel = context.getString(R.string.subscriptions_title),
                    localizeCalendarName = { it.localizedDisplayName(context.resources) }
                )
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
                        calendarCount = calendarCount,
                        consecutiveSyncFailures = account.consecutiveSyncFailures,
                        lastSuccessfulSyncAt = account.lastSuccessfulSyncAt
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
                val mapped = entities.map { entity ->
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
                _subscriptionsRaw.value = mapped

                // If the pending row vanished externally (server-side delete
                // arrived during the undo window), clear pending so a stray
                // settle doesn't try to re-delete a non-existent row.
                val pending = _pendingSubscriptionDeletionId.value
                if (pending != null && mapped.none { it.id == pending }) {
                    _pendingSubscriptionDeletionId.value = null
                    _uiState.update { it.copy(pendingSubscriptionDeletionId = null) }
                    clearSnackbar()
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
            // Refresh event counts when birthday sync completes
            dataStore.contactBirthdaysLastSync.collect {
                refreshContactEventCounts()
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

    private fun observeContactAnniversaries() {
        viewModelScope.launch {
            dataStore.contactAnniversariesEnabled.collect { enabled ->
                _contactAnniversariesEnabled.value = enabled
            }
        }
        viewModelScope.launch {
            dataStore.contactAnniversariesLastSync.collect { lastSync ->
                // Refresh event counts when sync completes
                refreshContactEventCounts()
            }
        }
        viewModelScope.launch {
            dataStore.anniversaryReminder.collect { reminder ->
                _contactAnniversariesReminder.value = reminder
            }
        }
        viewModelScope.launch {
            val color = eventCoordinator.getContactAnniversariesColor()
            if (color != null) {
                _contactAnniversariesColor.value = color
            }
        }
    }

    private fun refreshContactEventCounts() {
        viewModelScope.launch {
            _birthdayCount.value = eventCoordinator.getContactBirthdayEventCount()
            _anniversaryCount.value = eventCoordinator.getContactAnniversaryEventCount()
        }
    }

    private fun observeDeviceCalendars() {
        viewModelScope.launch {
            dataStore.deviceCalendarsEnabled.collect { enabled ->
                _deviceCalendarsEnabled.value = enabled
                if (enabled && _hasReadCalendarPermission.value) {
                    loadDeviceCalendars()
                }
            }
        }
        viewModelScope.launch {
            dataStore.enabledDeviceCalendarIds.collect { ids ->
                _enabledDeviceCalendarIds.value = ids
            }
        }
        viewModelScope.launch {
            dataStore.showDeclinedEvents.collect { show ->
                _showDeclinedEvents.value = show
            }
        }
        viewModelScope.launch {
            dataStore.deviceCalendarRemindersEnabled.collect { enabled ->
                _deviceCalendarRemindersEnabled.value = enabled
            }
        }
    }

    private suspend fun loadDeviceCalendars() {
        try {
            calendarProviderRepository.pruneStaleCalendarIds(dataStore)
            val calendars = calendarProviderRepository.getDeviceCalendars()
            _deviceCalendars.value = calendars
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar permission revoked", e)
            _deviceCalendars.value = emptyList()
        }
    }

    private fun checkCalendarPermission() {
        _hasReadCalendarPermission.value = permissionChecker.hasCalendarReadPermission()
        _hasWriteCalendarPermission.value = permissionChecker.hasCalendarWritePermission()
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
        // Observe new format DefaultCalendar
        viewModelScope.launch {
            userPreferences.defaultCalendar.collect { default ->
                _defaultCalendar.value = default
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
        viewModelScope.launch {
            dataStore.showWeekNumbers.collect { show ->
                _showWeekNumbers.value = show
            }
        }
        viewModelScope.launch {
            dataStore.widgetMaxEventsPerDay.collect { count ->
                _widgetMaxEventsPerDay.value = count
            }
        }
        viewModelScope.launch {
            dataStore.widgetDetailedRows.collect { detailed ->
                _widgetDetailedRows.value = detailed
            }
        }
        viewModelScope.launch {
            dataStore.monthWidgetEventTitles.collect { titles ->
                _monthWidgetEventTitles.value = titles
            }
        }
        viewModelScope.launch {
            dataStore.syncPastDays.collect { days ->
                _syncLookbackDays.value = days
            }
        }
        viewModelScope.launch {
            dataStore.quickAddEnabled.collect { enabled ->
                _quickAddEnabled.value = enabled
            }
        }
        viewModelScope.launch {
            dataStore.titleSuggestionsEnabled.collect { enabled ->
                _titleSuggestionsEnabled.value = enabled
            }
        }
    }

    // ==================== Settings search ====================

    /** Open the inline search bar; query starts empty. */
    fun onSearchOpen() {
        _isSearchActive.value = true
        _searchQuery.value = ""
    }

    /** Update the active query as the user types. */
    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    /** Close the search bar and clear the query. */
    fun onSearchClose() {
        _isSearchActive.value = false
        _searchQuery.value = ""
    }

    /**
     * Update the show event emojis preference.
     */
    fun setShowEventEmojis(show: Boolean) {
        viewModelScope.launch {
            dataStore.setShowEventEmojis(show)
        }
    }

    fun setQuickAddEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setQuickAddEnabled(enabled)
        }
    }

    fun setTitleSuggestionsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setTitleSuggestionsEnabled(enabled)
        }
    }

    /**
     * Update the time format preference.
     * Also triggers widget refresh since widgets display times.
     */
    fun setTimeFormat(format: String) {
        viewModelScope.launch {
            dataStore.setTimeFormat(format)
            widgetUpdateManager.updateAllWidgets("time_format_changed")
        }
    }

    /**
     * Update the first day of week preference.
     */
    fun setFirstDayOfWeek(day: Int) {
        viewModelScope.launch {
            dataStore.setFirstDayOfWeek(day)
            // The month widget lays out its columns from this preference, so refresh the widgets
            // now instead of waiting for the next periodic update.
            widgetUpdateManager.updateAllWidgets("first_day_of_week_changed")
        }
    }

    /**
     * Update the show week numbers preference.
     */
    fun setShowWeekNumbers(show: Boolean) {
        viewModelScope.launch {
            dataStore.setShowWeekNumbers(show)
            // The month widget's week-number gutter is driven by this preference, so refresh the
            // widgets immediately rather than on the next periodic tick.
            widgetUpdateManager.updateAllWidgets("week_numbers_changed")
        }
    }

    /**
     * Update the widget max events per day preference.
     * Also triggers widget refresh since widgets display events.
     */
    fun setWidgetMaxEventsPerDay(count: Int) {
        viewModelScope.launch {
            dataStore.setWidgetMaxEventsPerDay(count)
            widgetUpdateManager.updateAllWidgets("widget_max_events_changed")
        }
    }

    /**
     * Update the detailed-widget-rows preference (compact single line vs detailed two lines).
     * Also triggers widget refresh since it changes how event rows render.
     */
    fun setWidgetDetailedRows(detailed: Boolean) {
        viewModelScope.launch {
            dataStore.setWidgetDetailedRows(detailed)
            widgetUpdateManager.updateAllWidgets("widget_detailed_rows_changed")
        }
    }

    /**
     * Update the month-widget day-cell style preference (indicator dots vs event title rows).
     * Also triggers widget refresh since it changes how the month grid renders.
     */
    fun setMonthWidgetEventTitles(titles: Boolean) {
        viewModelScope.launch {
            dataStore.setMonthWidgetEventTitles(titles)
            widgetUpdateManager.updateAllWidgets("month_widget_event_titles_changed")
        }
    }

    private fun checkContactsPermission() {
        val read = permissionChecker.hasReadContactsPermission()
        _hasContactsPermission.value = read
        _hasContactsSyncPermission.value =
            contactSyncPermissionGranted(read, permissionChecker.hasWriteContactsPermission())
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
            _uiState.update { it.copy(iCloudState = ICloudConnectionState.Connecting) }

            // Snapshot input buffer so closure captures stable values, not mutable fields.
            // Guards against later edits clearing passwordInput before an error branch runs.
            val snappedAppleId = appleIdInput
            val snappedPassword = passwordInput
            val snappedShowHelp = showHelpState

            // Rebuilds iCloud NotConnected with the given error; collapses 4 copy-paste sites.
            val iCloudNotConnectedWith = { error: UiMessage? ->
                ICloudConnectionState.NotConnected(
                    appleId = snappedAppleId,
                    password = snappedPassword,
                    showHelp = snappedShowHelp,
                    error = error,
                )
            }

            // Validate inputs
            if (appleIdInput.isBlank() || passwordInput.isBlank()) {
                _uiState.update {
                    it.copy(
                        iCloudState = iCloudNotConnectedWith(
                            UiMessage.ResId(R.string.icloud_error_credentials_required)
                        )
                    )
                }
                return@launch
            }

            Log.i(TAG, "Starting iCloud discovery for: ${appleIdInput.trim().maskEmail()}")

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
                    _uiState.update {
                        it.copy(
                            iCloudState = iCloudNotConnectedWith(
                                UiMessage.ResId(R.string.icloud_error_connection_timeout)
                            )
                        )
                    }
                }

                result is DiscoveryResult.Success -> {
                    Log.i(TAG, "Discovery successful: ${result.calendars.size} calendars")

                    // Credentials are saved inside ICloudAccountDiscoveryService.
                    // If we reached here, they were saved successfully.

                    // Clear password from memory
                    passwordInput = ""

                    // Update state to connected (atomic state update)
                    _uiState.update {
                        it.copy(
                            iCloudState = ICloudConnectionState.Connected(
                                accountId = result.account.id,
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

                    // Trigger initial sync for the new account only (not all accounts)
                    syncScheduler.syncAccount(result.account.id, forceFullSync = true)

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
                    // TODO: result.message is English-only from the sync layer.
                    // A future refactor should make DiscoveryResult carry an error-kind
                    // enum that the UI maps to a localized string; Literal is a bridge.
                    Log.e(TAG, "Authentication failed: ${result.message}")
                    _uiState.update {
                        it.copy(iCloudState = iCloudNotConnectedWith(UiMessage.Literal(result.message)))
                    }
                }

                result is DiscoveryResult.Error -> {
                    Log.e(TAG, "Discovery failed: ${result.message}")
                    _uiState.update {
                        it.copy(iCloudState = iCloudNotConnectedWith(UiMessage.Literal(result.message)))
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
            val icloudAccounts = accountRepository.getAccountsByProvider(AccountProvider.ICLOUD)
            val account = icloudAccounts.firstOrNull()
            val accountEmail = account?.email

            // Cancel scheduled syncs
            syncScheduler.cancelPeriodicSync()

            // Remove Account, Calendar, and Event entities from Room
            // AccountRepository.deleteAccount() handles:
            // - WorkManager job cancellation
            // - Reminder cancellation
            // - Credentials deletion
            // - Cascade delete account → calendars → events
            if (accountEmail != null) {
                discoveryService.removeAccountByEmail(accountEmail)
            }

            // Reset state
            appleIdInput = ""
            passwordInput = ""
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
        _localNetworkHintActive.value = false

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
                        error = UiMessage.ResId(
                            R.string.error_display_name_exists,
                            listOf(displayName)
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
        } catch (_: Exception) {
            return username
        }

        // Known providers get friendly names
        val providerName = when {
            host.contains("fastmail", ignoreCase = true) -> "Fastmail"
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

            // Clear any prior blocked-LAN signal; re-set only if this attempt
            // fails the same way.
            _localNetworkHintActive.value = false

            // Snapshot input buffer so closure captures stable values, not mutable fields.
            val snappedServerUrl = calDavServerUrl
            val snappedDisplayName = calDavDisplayName
            val snappedUsername = calDavUsername
            val snappedPassword = calDavPassword
            val snappedTrustInsecure = calDavTrustInsecure

            // Rebuilds CalDav NotConnected with the given error; collapses ~10 copy-paste sites.
            // Server-provided strings (createResult.message, discoveryResult.message) wrap as
            // UiMessage.Literal — those are English-only; future refactor should make
            // DiscoveryResult carry an error-kind enum the UI maps to a localized string.
            val calDavNotConnectedWith = { error: UiMessage?, errorField: CalDavConnectionState.ErrorField? ->
                CalDavConnectionState.NotConnected(
                    serverUrl = snappedServerUrl,
                    displayName = snappedDisplayName,
                    username = snappedUsername,
                    password = snappedPassword,
                    trustInsecure = snappedTrustInsecure,
                    error = error,
                    errorField = errorField,
                )
            }

            // Validate inputs
            if (calDavServerUrl.isBlank() || calDavDisplayName.isBlank() || calDavUsername.isBlank() || calDavPassword.isBlank()) {
                _uiState.update {
                    it.copy(
                        calDavState = calDavNotConnectedWith(
                            UiMessage.ResId(
                                if (calDavDisplayName.isBlank())
                                    R.string.caldav_error_display_name_required
                                else
                                    R.string.caldav_error_credentials_required
                            ),
                            if (calDavDisplayName.isBlank()) CalDavConnectionState.ErrorField.DISPLAY_NAME else null
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
                        calDavState = calDavNotConnectedWith(
                            UiMessage.ResId(
                                R.string.error_display_name_exists,
                                listOf(effectiveDisplayName)
                            ),
                            CalDavConnectionState.ErrorField.DISPLAY_NAME
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
                            calDavState = calDavNotConnectedWith(
                                UiMessage.ResId(R.string.caldav_error_connection_timeout),
                                null
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
                                calDavState = calDavNotConnectedWith(
                                    UiMessage.ResId(R.string.caldav_error_no_calendars),
                                    null
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

                            // Trigger initial sync for the new account only (not all accounts)
                            syncScheduler.syncAccount(createResult.account.id, forceFullSync = true)

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
                                    calDavState = calDavNotConnectedWith(
                                        UiMessage.Literal(createResult.message),
                                        null
                                    )
                                )
                            }
                        }

                        else -> {
                            Log.e(TAG, "Unexpected result from createAccountWithSelectedCalendars: $createResult")
                            _uiState.update {
                                it.copy(
                                    calDavState = calDavNotConnectedWith(
                                        UiMessage.ResId(R.string.caldav_error_unexpected),
                                        null
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
                            calDavState = calDavNotConnectedWith(
                                UiMessage.Literal(discoveryResult.message),
                                CalDavConnectionState.ErrorField.CREDENTIALS
                            )
                        )
                    }
                }

                discoveryResult is DiscoveryResult.Error -> {
                    Log.e(TAG, "CalDAV discovery failed: ${discoveryResult.message}")
                    // A blocked local-network socket surfaces here; arm the
                    // Allow-access banner so the user has an action, not just the
                    // hint text (covers bare hostnames isLanHost can't classify).
                    if (isDiscoveryFailureBlockedLan()) {
                        _localNetworkHintActive.value = true
                    }
                    val errorField = when {
                        discoveryResult.message.contains("URL", ignoreCase = true) ||
                        discoveryResult.message.contains("server", ignoreCase = true) ->
                            CalDavConnectionState.ErrorField.SERVER
                        else -> null
                    }
                    _uiState.update {
                        it.copy(
                            calDavState = calDavNotConnectedWith(
                                withLanHintIfBlocked(discoveryResult.message),
                                errorField
                            )
                        )
                    }
                }

                else -> {
                    Log.e(TAG, "Unexpected CalDAV discovery result: $discoveryResult")
                    _uiState.update {
                        it.copy(
                            calDavState = calDavNotConnectedWith(
                                UiMessage.ResId(R.string.caldav_error_unexpected),
                                null
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
    fun onAddSubscription(
        url: String,
        name: String,
        color: Int,
        duplicateUrlMessage: String? = null,
    ) {
        viewModelScope.launch {
            Log.i(TAG, "Adding subscription: $url, $name")

            when (val result = eventCoordinator.addIcsSubscription(url, name, color)) {
                is IcsSubscriptionRepository.SubscriptionResult.Success -> {
                    Log.i(TAG, "Subscription added: ${result.subscription.name}")
                    // Schedule periodic refresh if this is the first subscription
                    icsScheduler.schedulePeriodicRefresh()
                }

                is IcsSubscriptionRepository.SubscriptionResult.Error -> {
                    Log.e(TAG, "Failed to add subscription: ${result.message}")
                    if (result.isDuplicate && duplicateUrlMessage != null) {
                        showSnackbar(duplicateUrlMessage)
                    }
                }
            }
        }
    }

    /**
     * Stage an ICS subscription for deletion behind a snackbar with an Undo action.
     *
     * Behavior (issue #133):
     * - The row is filtered out of [subscriptions] immediately.
     * - The deletion is *not* committed yet — [eventCoordinator.removeIcsSubscription]
     *   only fires when [onSubscriptionDeletionSettled] is called (snackbar timeout
     *   or dismissal).
     * - If a prior pending deletion exists, it is committed before the new one
     *   is staged (eager-replace semantics; matches Material's "snackbar replaces
     *   snackbar" UX).
     *
     * @param subscriptionId The subscription to remove.
     * @param removedMessage Pre-localized snackbar message (e.g. "Subscription removed").
     *   Resolved at the call site so the ViewModel can stay [Context]-free.
     * @param undoActionLabel Pre-localized action label (e.g. "Undo").
     */
    fun onDeleteSubscription(
        subscriptionId: Long,
        removedMessage: String,
        undoActionLabel: String
    ) {
        // Eager-replace: settle any prior pending deletion before staging the
        // new one so the user can never undo the wrong row.
        val prior = _pendingSubscriptionDeletionId.value
        if (prior != null && prior != subscriptionId) {
            Log.i(TAG, "Settling prior pending deletion before staging new: $prior")
            commitSubscriptionDeletion(prior)
        }

        Log.i(TAG, "Staging subscription deletion (with undo): $subscriptionId")
        _pendingSubscriptionDeletionId.value = subscriptionId
        _uiState.update { it.copy(pendingSubscriptionDeletionId = subscriptionId) }
        showSnackbar(
            message = removedMessage,
            actionLabel = undoActionLabel,
            action = { onUndoSubscriptionDeletion() }
        )
    }

    /**
     * Cancel a pending ICS subscription deletion. No persistent state is changed.
     * Idempotent: safe to call when no deletion is pending.
     */
    fun onUndoSubscriptionDeletion() {
        val pending = _pendingSubscriptionDeletionId.value ?: return
        Log.i(TAG, "Undo pending subscription deletion: $pending")
        _pendingSubscriptionDeletionId.value = null
        _uiState.update { it.copy(pendingSubscriptionDeletionId = null) }
        clearSnackbar()
    }

    /**
     * Commit a pending ICS subscription deletion via the coordinator.
     * Called by the snackbar host on `Dismissed` (timeout, navigate-away,
     * snackbar replaced).
     *
     * Idempotent: a no-op if no deletion is pending. Material 3 may fire
     * `Dismissed` after `ActionPerformed`; this guard ensures we never commit
     * after the user undid.
     */
    fun onSubscriptionDeletionSettled() {
        val pending = _pendingSubscriptionDeletionId.value ?: return
        Log.i(TAG, "Committing pending subscription deletion: $pending")
        _pendingSubscriptionDeletionId.value = null
        _uiState.update { it.copy(pendingSubscriptionDeletionId = null) }
        clearSnackbar()
        commitSubscriptionDeletion(pending)
    }

    /**
     * Commit any pending subscription deletion on ViewModel destruction.
     *
     * The snackbar's `Dismissed` callback is the normal trigger for
     * [onSubscriptionDeletionSettled], but when the Activity finish()es
     * mid-undo-window the LaunchedEffect coroutine throws
     * CancellationException and `Dismissed` is never delivered. This is
     * the fallback so the deletion still commits (issue #133, v23.7.9).
     */
    override fun onCleared() {
        val pending = _pendingSubscriptionDeletionId.value
        if (pending != null) {
            Log.i(TAG, "Committing pending deletion on ViewModel destruction: $pending")
            commitSubscriptionDeletion(pending)
        }
        // Clear search so the next ViewModel instance starts with a fresh list.
        onSearchClose()
        super.onCleared()
    }

    /**
     * Test-only hook for [onCleared]. Kotlin's [ViewModel.onCleared] is
     * `protected`, so tests can't call it directly without subclassing.
     */
    @androidx.annotation.VisibleForTesting
    internal fun onClearedForTest() = onCleared()

    /**
     * Run the subscription delete on [applicationScope] so it survives
     * Activity destroy mid-undo-window (issue #133). All commit paths
     * funnel through here so the scope choice can't drift.
     */
    private fun commitSubscriptionDeletion(subscriptionId: Long) {
        applicationScope.launch { eventCoordinator.removeIcsSubscription(subscriptionId) }
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
                // Enable: create calendar + sync in-process + start observer
                val color = _contactBirthdaysColor.value
                eventCoordinator.enableContactBirthdays(color)
                eventCoordinator.syncContactBirthdays()
                dataStore.setContactBirthdaysEnabled(true)
                contactEventManager.onBirthdaysEnabled()
            } else {
                // Disable: delete calendar + stop observer
                dataStore.setContactBirthdaysEnabled(false)
                dataStore.setContactBirthdaysLastSync(0L)
                contactEventManager.onBirthdaysDisabled()
                eventCoordinator.disableContactBirthdays()
            }
            refreshContactEventCounts()
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
     *
     * Persists the preference and — if the birthdays feature is currently
     * enabled — triggers a sync so every existing event's reminders and
     * AlarmManager alarms are rewritten to the new value immediately.
     * The cancel-previous-job guard mirrors [ContactEventObserver]'s
     * debounce pattern so rapid picker dismissals converge on the last value.
     */
    private var birthdayReminderJob: Job? = null
    fun onContactBirthdaysReminderChange(minutes: Int) {
        if (_contactBirthdaysReminder.value == minutes) return
        birthdayReminderJob?.cancel()
        birthdayReminderJob = viewModelScope.launch {
            Log.i(TAG, "Birthday reminder change: $minutes minutes")
            dataStore.setBirthdayReminder(minutes)
            if (_contactBirthdaysEnabled.value) {
                eventCoordinator.syncContactBirthdays()
            }
        }
    }

    // ==================== Contact Anniversaries ====================

    /**
     * Toggle contact anniversaries feature.
     *
     * Note: Permission should be checked by the caller before enabling.
     */
    fun onToggleContactAnniversaries(enabled: Boolean) {
        viewModelScope.launch {
            Log.i(TAG, "Toggle contact anniversaries: enabled=$enabled")

            if (enabled) {
                val color = _contactAnniversariesColor.value
                eventCoordinator.enableContactAnniversaries(color)
                eventCoordinator.syncContactAnniversaries()
                dataStore.setContactAnniversariesEnabled(true)
                contactEventManager.onAnniversariesEnabled()
            } else {
                dataStore.setContactAnniversariesEnabled(false)
                dataStore.setContactAnniversariesLastSync(0L)
                contactEventManager.onAnniversariesDisabled()
                eventCoordinator.disableContactAnniversaries()
            }
            refreshContactEventCounts()
        }
    }

    /**
     * Update contact anniversaries calendar color.
     */
    fun onContactAnniversariesColorChange(color: Int) {
        viewModelScope.launch {
            Log.i(TAG, "Contact anniversaries color change: $color")
            _contactAnniversariesColor.value = color
            eventCoordinator.updateContactAnniversariesColor(color)
        }
    }

    /**
     * Update anniversary reminder setting.
     *
     * See [onContactBirthdaysReminderChange] for the propagation contract.
     */
    private var anniversaryReminderJob: Job? = null
    fun onContactAnniversariesReminderChange(minutes: Int) {
        if (_contactAnniversariesReminder.value == minutes) return
        anniversaryReminderJob?.cancel()
        anniversaryReminderJob = viewModelScope.launch {
            Log.i(TAG, "Anniversary reminder change: $minutes minutes")
            dataStore.setAnniversaryReminder(minutes)
            if (_contactAnniversariesEnabled.value) {
                eventCoordinator.syncContactAnniversaries()
            }
        }
    }

    // ==================== Device Calendars ====================

    /**
     * Toggle device calendars feature.
     * Permission should be checked by the caller before enabling.
     */
    fun onToggleDeviceCalendars(enabled: Boolean) {
        viewModelScope.launch {
            Log.i(TAG, "Toggle device calendars: enabled=$enabled")
            dataStore.setDeviceCalendarsEnabled(enabled)
            if (enabled) {
                calendarProviderManager.onEnabled()
                loadDeviceCalendars()
            } else {
                calendarProviderManager.onDisabled()
                _deviceCalendars.value = emptyList()
            }
        }
    }

    /**
     * Toggle "show declined events" preference for device calendars.
     */
    fun onToggleShowDeclinedEvents(show: Boolean) {
        viewModelScope.launch {
            dataStore.setShowDeclinedEvents(show)
            // Increment change signal so views refresh with updated filter
            calendarProviderManager.onEnabled()
        }
    }

    /**
     * Toggle device calendar reminders on/off.
     * When enabled, schedules the next upcoming reminder.
     * When disabled, cancels any pending alarm.
     */
    fun onToggleDeviceCalendarReminders(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setDeviceCalendarRemindersEnabled(enabled)
            if (enabled) {
                deviceCalendarReminderScheduler.scheduleNextReminder()
            } else {
                deviceCalendarReminderScheduler.cancelPendingAlarm()
            }
        }
    }

    /**
     * Toggle a specific device calendar on/off.
     */
    fun onToggleDeviceCalendar(calendarId: Long, enabled: Boolean) {
        viewModelScope.launch {
            val currentIds = _enabledDeviceCalendarIds.value.toMutableSet()
            if (enabled) currentIds.add(calendarId) else currentIds.remove(calendarId)
            dataStore.setEnabledDeviceCalendarIds(currentIds)
            if (enabled) {
                calendarProviderRepository.ensureCalendarVisible(calendarId)
            } else {
                // When disabling, also clear from hidden set so it doesn't linger
                dataStore.removeFromHiddenDeviceCalendarIds(calendarId)
            }
            // Increment change signal so day view refreshes
            calendarProviderManager.onEnabled()
        }
    }

    /**
     * Refresh calendar permission state (call from Activity onResume or after permission result).
     */
    fun refreshCalendarPermission() {
        checkCalendarPermission()
        if (_hasReadCalendarPermission.value && _deviceCalendarsEnabled.value) {
            viewModelScope.launch { loadDeviceCalendars() }
        }
        // Also reload writable device calendars for default calendar picker
        loadWritableDeviceCalendars()
    }

    /**
     * Manually refresh device calendars list.
     * Use when user adds/removes calendar accounts and wants to see the updated list.
     */
    fun refreshDeviceCalendars() {
        if (_hasReadCalendarPermission.value && _deviceCalendarsEnabled.value) {
            viewModelScope.launch { loadDeviceCalendars() }
        }
    }

    /**
     * Load writable device calendars for the default calendar picker.
     * Only loads if WRITE_CALENDAR permission is granted.
     * Unlike loadDeviceCalendars(), this doesn't depend on deviceCalendarsEnabled setting
     * since writable calendars should be available for default selection regardless.
     */
    private fun loadWritableDeviceCalendars() {
        viewModelScope.launch {
            val groups = if (_hasWriteCalendarPermission.value) {
                try {
                    val deviceCalendars = calendarProviderRepository.getDeviceCalendars()
                    CalendarGroup.fromDeviceCalendars(deviceCalendars, writableOnly = true)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Calendar permission revoked while loading writable calendars", e)
                    emptyList()
                }
            } else {
                emptyList()
            }
            _writableDeviceCalendarGroups.value = groups
        }
    }

    /**
     * Set the default calendar for new events.
     * Supports both Room calendars (local, iCloud, CalDAV) and device calendars.
     */
    fun onDefaultCalendarSelect(calendar: DefaultCalendar) {
        viewModelScope.launch {
            userPreferences.setDefaultCalendar(calendar)
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
     * Update how far back to sync calendar events.
     *
     * When expanding the window (new > old), forces a full sync so older events
     * are actually fetched from the server. The sync uses etag comparison to skip
     * unchanged events (bandwidth optimization).
     *
     * When shrinking the window (new < old), deletes CalDAV events outside the new
     * window before syncing. Preserves: pending sync events, recurring masters,
     * exception events, local calendar events, and birthday/anniversary events.
     * Widgets are refreshed after cleanup.
     */
    fun onSyncLookbackChange(days: Int) {
        val safeDays = if (days == Int.MAX_VALUE) days else days.coerceAtLeast(1)
        val oldDays = _syncLookbackDays.value
        val isExpanding = safeDays > oldDays || safeDays == Int.MAX_VALUE
        val isShrinking = !isExpanding && safeDays != oldDays

        viewModelScope.launch {
            dataStore.setSyncPastDays(safeDays)

            // Cleanup events, occurrences, and exceptions outside new window when shrinking
            if (isShrinking && safeDays != Int.MAX_VALUE) {
                val now = System.currentTimeMillis()
                val cutoffTs = now - (safeDays.toLong() * 24 * 60 * 60 * 1000)

                val result = eventWriter.cleanupForShrinkingLookback(cutoffTs)
                if (result.totalDeleted > 0) {
                    Log.i(TAG, "Lookback cleanup: ${result.deletedEvents} events, " +
                        "${result.deletedOccurrences} occurrences, ${result.deletedExceptions} exceptions")
                    widgetUpdateManager.updateAllWidgets("lookback_shrink_cleanup")
                }
            }

            if (isExpanding) {
                syncScheduler.requestImmediateSync(forceFullSync = true)
            } else {
                syncScheduler.requestImmediateSync()
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

    /**
     * Refresh contacts permission state (call from Activity onResume).
     */
    fun refreshContactsPermission() {
        checkContactsPermission()
    }

    /**
     * Push the resolved local-network permission state from the Activity (which
     * owns the rationale read + request launcher). Called on open and after a
     * permission request returns. See [shouldShowLanBanner].
     */
    fun updateLocalNetworkPermissionState(state: LocalNetworkPermissionState) {
        _localNetworkPermissionState.value = state
        // A granted permission makes the blocked-LAN hint moot.
        if (state == LocalNetworkPermissionState.Granted) {
            _localNetworkHintActive.value = false
        }
    }

    /**
     * Reconcile permission state on resume (e.g. after the user changed it in
     * system Settings). Upgrade-only: a fresh live read can never represent
     * PermanentlyDenied (that's set only by the rationale-flip classifier after
     * a request), so it must not overwrite a PermanentlyDenied with a
     * banner-showing state — otherwise the banner would nag again on the next
     * app resume. Applies a detected grant; otherwise leaves the state as-is.
     */
    fun reconcileLocalNetworkPermissionOnResume(resolved: LocalNetworkPermissionState) {
        _localNetworkPermissionState.value =
            reconcileOnResume(_localNetworkPermissionState.value, resolved)
    }

    /**
     * Wrap a failed-discovery message with the local-network hint when the
     * permission is required-but-ungranted, so a blocked LAN server (including
     * bare-hostname ones [isLanHost] can't classify) is explained. Additive:
     * the server's real message is preserved as an arg, so a genuinely-down
     * public server is not mislabeled.
     */
    private fun withLanHintIfBlocked(serverMessage: String): UiMessage =
        if (isDiscoveryFailureBlockedLan()) {
            UiMessage.ResId(R.string.caldav_error_with_lan_hint, listOf(serverMessage))
        } else {
            UiMessage.Literal(serverMessage)
        }

    /**
     * True when a just-failed discovery looks like a blocked local-network
     * socket: the permission is required (API 37+) but not granted. Pure read
     * of the current permission state — no side effects.
     */
    private fun isDiscoveryFailureBlockedLan(): Boolean =
        _localNetworkPermissionState.value.failureIndicatesBlockedLan()


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

    // ==================== ICS Device Import ====================

    /**
     * Import ICS events into a device calendar via CalendarProvider.
     *
     * @param events Events parsed from ICS file
     * @param calendarId Target device calendar ID
     * @return Count of successfully imported events
     */
    suspend fun importIcsToDeviceCalendar(events: List<Event>, calendarId: Long): Int {
        return importEventsToDeviceCalendar(
            events = events,
            calendarId = calendarId,
            repo = calendarProviderRepository,
            defaultTimedReminderMinutes = dataStore.defaultReminderMinutes.first(),
            defaultAllDayReminderMinutes = dataStore.defaultAllDayReminder.first()
        )
    }

    // ==================== Snackbar ====================

    /**
     * Show a snackbar message to the user. Optional action turns the snackbar
     * into a Material "snackbar with action" (used by the subscription
     * delete-with-undo flow, issue #133).
     */
    fun showSnackbar(
        message: String,
        actionLabel: String? = null,
        action: (() -> Unit)? = null
    ) {
        _uiState.update {
            it.copy(
                pendingSnackbarMessage = message,
                pendingSnackbarActionLabel = actionLabel,
                pendingSnackbarAction = action
            )
        }
    }

    /**
     * Clear the pending snackbar after it's shown — including any action label
     * and callback set via [showSnackbar].
     */
    fun clearSnackbar() {
        _uiState.update {
            it.copy(
                pendingSnackbarMessage = null,
                pendingSnackbarActionLabel = null,
                pendingSnackbarAction = null
            )
        }
    }

    // ==================== Backup & Restore ====================

    /**
     * Build the backup JSON payload and stash it in [pendingExportJson] for the Activity's
     * SAF writer. Called after the user confirms the pre-export warning dialog.
     *
     * @return the built JSON payload
     */
    suspend fun prepareExport() {
        pendingExportJson = backupExporter.exportSettings()
    }

    /** Consume and clear the stashed JSON after the SAF writer finishes (or aborts). */
    fun consumePendingExportJson(): String? {
        val json = pendingExportJson
        pendingExportJson = null
        return json
    }

    /**
     * Parse a selected backup file. On success transitions to [BackupRestoreUiState.PendingConfirmation]
     * so the UI can show the confirmation dialog. On failure transitions to
     * [BackupRestoreUiState.Error]. No DB or prefs writes happen here.
     */
    fun onBackupFileSelected(json: String) {
        _backupRestoreState.value = when (val result = backupImporter.parseAndValidate(json)) {
            is BackupParseResult.Ok -> BackupRestoreUiState.PendingConfirmation(
                envelope = result.envelope,
                summary = result.envelope.toSummary(),
            )
            is BackupParseResult.Error -> BackupRestoreUiState.Error(result.error)
        }
    }

    /**
     * Apply the envelope surfaced in [BackupRestoreUiState.PendingConfirmation]. No-op if the
     * state is anything else (e.g., user raced dismiss + confirm).
     */
    fun confirmRestore() {
        val pending = _backupRestoreState.value as? BackupRestoreUiState.PendingConfirmation ?: return
        viewModelScope.launch {
            _backupRestoreState.value = try {
                BackupRestoreUiState.Success(backupImporter.applyBackup(pending.envelope))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "applyBackup failed", e)
                BackupRestoreUiState.Error(BackupImportError.ApplyFailed(e.message))
            }
        }
    }

    /** Dismiss any backup/restore dialog, returning to [BackupRestoreUiState.Idle]. */
    fun dismissDialog() {
        _backupRestoreState.value = BackupRestoreUiState.Idle
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

    // ==================== Account Detail ====================

    /** Job tracking sync status observation — cancelled in clearAccountDetail() */
    private var syncObservationJob: Job? = null

    /** Job tracking account detail Flow observation — cancelled in clearAccountDetail() */
    private var accountDetailJob: Job? = null

    /**
     * Observe account detail for the bottom sheet via Flow.
     * Auto-updates when Room's invalidation tracker fires (e.g., after sync metadata changes).
     *
     * @param accountId The account to observe
     */
    fun observeAccountDetail(accountId: Long) {
        accountDetailJob?.cancel()
        accountDetailJob = viewModelScope.launch {
            accountRepository.getAccountByIdFlow(accountId)
                .distinctUntilChanged()
                .collect { account ->
                    if (account == null) {
                        _uiState.update { it.copy(accountDetail = null) }
                        return@collect
                    }
                    val calendarCount = eventCoordinator.getCalendarCountForAccount(accountId)
                    _uiState.update { it.copy(accountDetail = account.toDetailUiModel(calendarCount)) }
                }
        }
    }

    /**
     * Clear account detail state and cancel sync observation.
     */
    fun clearAccountDetail() {
        accountDetailJob?.cancel()
        accountDetailJob = null
        syncObservationJob?.cancel()
        syncObservationJob = null
        _uiState.update {
            it.copy(
                accountDetail = null,
                accountDetailSyncStatus = AccountDetailSyncStatus.Idle,
                accountDetailDiscoverStatus = AccountDetailDiscoverStatus.Idle,
                // Clear the inline contact-sync confirmation too: it names a masked
                // account email and otherwise survives the sheet close on the VM
                // StateFlow, so it would surface again in the next account's sheet.
                contactSyncConfirmation = null
            )
        }
    }

    /**
     * Trigger per-account sync and observe result.
     *
     * @param accountId The account to sync
     */
    fun syncAccountNow(accountId: Long) {
        _uiState.update { it.copy(accountDetailSyncStatus = AccountDetailSyncStatus.Syncing) }
        syncObservationJob?.cancel()

        // "Sync now" covers contacts too when contact sync is on for this account
        // — otherwise the manual sync silently skips them until the next periodic
        // tick. Gate on the live grant like the enable path: a revoked
        // WRITE_CONTACTS would only skip-and-flag downstream. And, like the enable
        // path, own the re-grant banner here rather than leaving it to a background
        // worker that may never be scheduled: this is a manual entry point that can
        // run for a login whose periodic contact job never existed, so the worker
        // never fires to raise/clear it.
        viewModelScope.launch {
            val contactSyncOn = accountRepository.getAccountById(accountId)?.contactSyncEnabled == true
            if (!contactSyncOn) return@launch
            val granted = contactSyncPermissionGranted(
                readGranted = permissionChecker.hasReadContactsPermission(),
                writeGranted = permissionChecker.hasWriteContactsPermission(),
            )
            if (granted) {
                dataStore.setContactSyncPermissionNeeded(false)
                // Scope the pull to this account — "Sync now" for one login shouldn't
                // re-sweep every other contact-sync account's address books.
                syncScheduler.requestImmediateContactSync(accountId)
            } else {
                dataStore.setContactSyncPermissionNeeded(true)
            }
        }

        val workId = syncScheduler.syncAccount(accountId)
        syncObservationJob = viewModelScope.launch {
            syncScheduler.observeSyncStatus(workId).collect { status ->
                when (status) {
                    is SyncStatus.Succeeded -> {
                        _uiState.update {
                            it.copy(accountDetailSyncStatus = AccountDetailSyncStatus.Done(success = true))
                        }
                    }
                    is SyncStatus.Failed, is SyncStatus.Cancelled -> {
                        _uiState.update {
                            it.copy(accountDetailSyncStatus = AccountDetailSyncStatus.Done(success = false))
                        }
                    }
                    else -> { /* Enqueued, Running, Blocked, Idle — keep Syncing state */ }
                }
            }
        }
    }

    /**
     * Toggle account enabled/disabled for sync.
     *
     * @param accountId The account to toggle
     * @param enabled New enabled state
     */
    fun toggleAccountEnabled(accountId: Long, enabled: Boolean) {
        viewModelScope.launch {
            accountRepository.setEnabled(accountId, enabled)
        }
    }

    /**
     * Toggle CardDAV contact sync for a single account. Routes through the
     * repository (which enrols/removes the contacts system account and persists
     * the flag) — never a DAO directly. Disabling needs no permission.
     *
     * The UI gates enabling behind a READ + WRITE_CONTACTS request, but the pull
     * kick re-checks the live grant here rather than trusting the caller (the
     * same defense-in-depth the contact-birthday manager applies before its
     * sync). Without both permissions the flag still persists and we raise the
     * re-grant signal directly — the inline banner must surface even for a login
     * whose periodic job was never scheduled (manual-only, or predating this
     * feature), where the background worker never runs to raise it. With both
     * permissions we clear that signal and kick the pull.
     *
     * @param accountId The account to toggle
     * @param enabled New contact-sync state
     */
    fun onToggleContactSync(accountId: Long, enabled: Boolean) {
        viewModelScope.launch {
            val purgeOutcome = accountRepository.setContactSyncEnabled(accountId, enabled)

            // Resolve the account label once so both branches can name it. Fall
            // back to a blank label (maskEmail is null-safe) if the account has
            // vanished — the confirmation still reads sensibly.
            val maskedEmail = maskEmail(accountRepository.getAccountById(accountId)?.email)

            if (!enabled) {
                // Message from what the purge actually did, not a blanket "removed":
                // a same-email sibling can keep the contacts, and a revoked-permission
                // purge can't confirm removal. Claiming removal in those cases is a lie.
                // Tone mirrors the outcome so the sheet styles a destructive removal (or
                // an unverified "may remain") as a warning, not a celebratory checkmark;
                // contacts KEPT by a sibling is benign, so it stays positive.
                val confirmation = when (purgeOutcome) {
                    ContactPurgeOutcome.PURGED -> ContactSyncConfirmation(
                        context.getString(R.string.contact_sync_disabled_for, maskedEmail),
                        ContactSyncConfirmation.Tone.WARNING,
                    )
                    ContactPurgeOutcome.NOT_ATTEMPTED -> ContactSyncConfirmation(
                        context.getString(R.string.contact_sync_disabled_kept, maskedEmail),
                        ContactSyncConfirmation.Tone.POSITIVE,
                    )
                    ContactPurgeOutcome.INCOMPLETE -> ContactSyncConfirmation(
                        context.getString(R.string.contact_sync_disabled_incomplete, maskedEmail),
                        ContactSyncConfirmation.Tone.WARNING,
                    )
                }
                showContactSyncConfirmation(confirmation)
                return@launch
            }

            val granted = contactSyncPermissionGranted(
                readGranted = permissionChecker.hasReadContactsPermission(),
                writeGranted = permissionChecker.hasWriteContactsPermission(),
            )
            if (!granted) {
                // Surface the re-grant banner now rather than waiting for a
                // background worker that may never be scheduled to run. Don't show
                // the "Syncing…" confirmation — nothing will actually pull.
                dataStore.setContactSyncPermissionNeeded(true)
                return@launch
            }
            dataStore.setContactSyncPermissionNeeded(false)

            // Enabling only flips a flag and enrols the system account; nothing
            // pulls contacts until we kick it. Schedule the recurring job (in
            // case periodic sync predates this feature) and fire an immediate
            // one-shot pull so contacts start appearing within seconds, not at
            // the next periodic tick. Surface the background work inline.
            val intervalMs = userPreferences.syncIntervalMs.first()
            // Long.MAX_VALUE is the repository's "manual only" sentinel: as with
            // calendar sync, don't schedule a periodic job in that mode — but the
            // one-shot pull below still runs so enabling has an immediate effect.
            if (intervalMs != Long.MAX_VALUE) {
                syncScheduler.ensureContactSyncScheduled(intervalMs / (60 * 1000L))
            }
            syncScheduler.requestImmediateContactSync()
            showContactSyncConfirmation(
                ContactSyncConfirmation(
                    context.getString(R.string.contact_sync_enabled_for, maskedEmail),
                    ContactSyncConfirmation.Tone.POSITIVE,
                )
            )
        }
    }

    /**
     * Set the inline contact-sync confirmation shown inside the account detail
     * sheet.
     */
    private fun showContactSyncConfirmation(confirmation: ContactSyncConfirmation) {
        _uiState.update { it.copy(contactSyncConfirmation = confirmation) }
    }

    /** Clear the inline contact-sync confirmation once it's been shown. */
    fun clearContactSyncConfirmation() {
        _uiState.update { it.copy(contactSyncConfirmation = null) }
    }

    /**
     * Rename an account's display name.
     *
     * @param accountId The account to rename
     * @param newName New display name (trimmed, must not be empty)
     * @return true if renamed, false if validation failed
     */
    fun renameAccount(accountId: Long, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return

        viewModelScope.launch {
            val account = accountRepository.getAccountById(accountId) ?: return@launch
            accountRepository.updateAccount(account.copy(displayName = trimmed))
            // Refresh account lists in UI state
            refreshCalDavAccounts()
            refreshICloudState(account)
        }
    }

    /**
     * Change an account's password using save-then-validate pattern.
     *
     * Saves new credentials, validates via refreshCalendars(), reverts on failure.
     *
     * @param accountId The account to update
     * @param newPassword New password
     * @return Result with success or error message
     */
    fun changeAccountPassword(accountId: Long, newPassword: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val account = accountRepository.getAccountById(accountId)
            if (account == null) {
                onResult(Result.failure(Exception(context.getString(R.string.password_change_error_account_not_found))))
                return@launch
            }

            val oldCredentials = accountRepository.getCredentials(accountId)
            if (oldCredentials == null) {
                onResult(Result.failure(Exception(context.getString(R.string.password_change_error_no_credentials))))
                return@launch
            }

            // Save new credentials
            val newCredentials = oldCredentials.copy(password = newPassword)
            accountRepository.saveCredentials(accountId, newCredentials)

            // Validate by running refreshCalendars which uses stored credentials
            val result = when (account.provider) {
                AccountProvider.ICLOUD -> discoveryService.refreshCalendars(accountId)
                AccountProvider.CALDAV -> calDavDiscoveryService.refreshCalendars(accountId)
                else -> {
                    onResult(Result.failure(Exception(context.getString(R.string.password_change_error_unsupported_provider))))
                    return@launch
                }
            }

            when (result) {
                is DiscoveryResult.Success -> {
                    onResult(Result.success(Unit))
                }
                is DiscoveryResult.CalendarsFound -> {
                    // Unexpected for refresh, but credentials are valid
                    onResult(Result.success(Unit))
                }
                is DiscoveryResult.AuthError -> {
                    // Revert to old credentials
                    accountRepository.saveCredentials(accountId, oldCredentials)
                    onResult(Result.failure(Exception(context.getString(R.string.password_change_error_invalid))))
                }
                is DiscoveryResult.Error -> {
                    // Revert to old credentials
                    accountRepository.saveCredentials(accountId, oldCredentials)
                    onResult(Result.failure(Exception(context.getString(R.string.password_change_error_network))))
                }
            }
        }
    }

    /**
     * Discover new calendars for an existing account.
     *
     * @param accountId The account to refresh calendars for
     */
    fun discoverNewCalendars(accountId: Long) {
        _uiState.update { it.copy(accountDetailDiscoverStatus = AccountDetailDiscoverStatus.Discovering) }

        viewModelScope.launch {
            val account = accountRepository.getAccountById(accountId)
            if (account == null) {
                _uiState.update {
                    it.copy(accountDetailDiscoverStatus = AccountDetailDiscoverStatus.Error("Account not found"))
                }
                return@launch
            }

            val beforeCount = eventCoordinator.getCalendarCountForAccount(accountId)

            val result = when (account.provider) {
                AccountProvider.ICLOUD -> discoveryService.refreshCalendars(accountId)
                AccountProvider.CALDAV -> calDavDiscoveryService.refreshCalendars(accountId)
                else -> {
                    _uiState.update {
                        it.copy(accountDetailDiscoverStatus = AccountDetailDiscoverStatus.Error("Provider does not support discovery"))
                    }
                    return@launch
                }
            }

            when (result) {
                is DiscoveryResult.Success -> {
                    val afterCount = result.calendars.size
                    val newCount = (afterCount - beforeCount).coerceAtLeast(0)
                    _uiState.update {
                        it.copy(
                            accountDetailDiscoverStatus = AccountDetailDiscoverStatus.Done(
                                newCount = newCount,
                                totalCount = afterCount
                            )
                        )
                    }
                    if (newCount > 0) {
                        syncScheduler.syncAccount(accountId)
                    }
                }
                is DiscoveryResult.CalendarsFound -> {
                    // Shouldn't happen for refresh — treat as no change
                    _uiState.update {
                        it.copy(
                            accountDetailDiscoverStatus = AccountDetailDiscoverStatus.Done(
                                newCount = 0,
                                totalCount = beforeCount
                            )
                        )
                    }
                }
                is DiscoveryResult.AuthError -> {
                    _uiState.update {
                        it.copy(
                            accountDetailDiscoverStatus = AccountDetailDiscoverStatus.Error(
                                "Authentication failed. Update your password."
                            )
                        )
                    }
                }
                is DiscoveryResult.Error -> {
                    _uiState.update {
                        it.copy(accountDetailDiscoverStatus = AccountDetailDiscoverStatus.Error(result.message))
                    }
                }
            }
        }
    }

    /**
     * Refresh CalDAV accounts list after rename.
     */
    private suspend fun refreshCalDavAccounts() {
        val accounts = accountRepository.getAccountsByProvider(AccountProvider.CALDAV)
        val uiModels = accounts.map { account ->
            val count = eventCoordinator.getCalendarCountForAccount(account.id)
            CalDavAccountUiModel(
                id = account.id,
                email = account.email,
                displayName = account.displayName ?: account.provider.displayName,
                calendarCount = count,
                consecutiveSyncFailures = account.consecutiveSyncFailures,
                lastSuccessfulSyncAt = account.lastSuccessfulSyncAt
            )
        }
        _uiState.update { it.copy(calDavAccounts = uiModels) }
    }

    /**
     * Refresh iCloud state after rename (if renamed account is iCloud).
     */
    private fun refreshICloudState(account: Account) {
        if (account.provider != AccountProvider.ICLOUD) return
        val currentState = _uiState.value.iCloudState
        if (currentState is ICloudConnectionState.Connected) {
            // Connected state doesn't include displayName, so no update needed
            // The account detail sheet auto-updates via observeAccountDetail Flow
        }
    }
}
