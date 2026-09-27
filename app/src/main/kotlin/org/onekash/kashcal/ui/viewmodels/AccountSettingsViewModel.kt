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
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.onekash.kashcal.R
import org.onekash.kashcal.di.ApplicationScope
import org.onekash.kashcal.data.calendar_provider.CalendarProviderManager
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.contacts.ContactEventManager
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncLog
import org.onekash.kashcal.data.ics.IcsSubscriptionRepository
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.preferences.PreferencesKeys
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
import org.onekash.kashcal.domain.reader.DeviceEventReader
import org.onekash.kashcal.domain.reader.SyncLogReader
import org.onekash.kashcal.domain.writer.DeviceEventWriter
import org.onekash.kashcal.sync.discovery.AccountDiscoveryService
import org.onekash.kashcal.sync.discovery.DiscoveredCalendar
import org.onekash.kashcal.sync.discovery.DiscoveryErrorReason
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.onekash.kashcal.sync.provider.caldav.CalDavAccountDiscoveryService
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
import org.onekash.kashcal.ui.util.resolve
import org.onekash.kashcal.util.maskEmail
import org.onekash.kashcal.widget.WidgetUpdateManager
import javax.inject.Inject

private const val TAG = "AccountSettingsVM"

// Per-attempt limit for iCloud and CalDAV sign-in discovery, so a stalled network can't hang the
// sign-in sheet.
private const val DISCOVERY_TIMEOUT_MS = 30_000L

// Retry configuration for discovery timeouts
private const val MAX_DISCOVERY_RETRIES = 2
private const val DISCOVERY_RETRY_DELAY_MS = 1000L

/**
 * Runs [block] with a per-attempt timeout, trying again only after a timeout.
 *
 * A result the block returns, an auth error included, is never retried. A block that returns
 * null reads as a timeout.
 *
 * @param maxRetries total attempts, the first one included
 * @return the block's result, or null if every attempt timed out
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
 * Holds the state and actions of the settings screens that `SettingsActivity` hosts through
 * [org.onekash.kashcal.ui.screens.SettingsRoute]: iCloud and CalDAV sign-in and account detail,
 * calendar visibility, ICS subscriptions, contact birthdays, anniversaries and contact sync,
 * device calendars, display, sync and reminder preferences, and settings backup.
 */
@HiltViewModel
class AccountSettingsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val userPreferences: UserPreferencesRepository,
    private val syncScheduler: SyncScheduler,
    private val discoveryService: AccountDiscoveryService,
    private val calDavDiscoveryService: CalDavAccountDiscoveryService,
    private val eventCoordinator: EventCoordinator,
    private val syncLogReader: SyncLogReader,
    private val contactEventManager: ContactEventManager,
    private val calendarProviderManager: CalendarProviderManager,
    private val deviceEventReader: DeviceEventReader,
    private val deviceEventWriter: DeviceEventWriter,
    private val dataStore: KashCalDataStore,
    private val widgetUpdateManager: WidgetUpdateManager,
    private val deviceCalendarReminderScheduler: org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler,
    private val backupExporter: SettingsBackupExporter,
    private val backupImporter: SettingsBackupImporter,
    private val permissionChecker: PermissionChecker,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountSettingsUiState(isLoading = true))
    val uiState: StateFlow<AccountSettingsUiState> = _uiState.asStateFlow()

    // Apple ID and password input (local to ViewModel)
    private var appleIdInput = ""
    private var passwordInput = ""
    private var showHelpState = false

    // Set when launched from onboarding: a successful iCloud sign-in finishes the activity back to
    // HomeScreen instead of showing the success sheet.
    private var isInitialSetup = false

    // CalDAV sign-in sheet input
    private var calDavServerUrl = ""
    private var calDavDisplayName = ""
    private var calDavDisplayNameManuallyEdited = false  // Stops the name auto-fill
    private var calDavUsername = ""
    private var calDavPassword = ""
    private var calDavTrustInsecure = false
    private var validateDisplayNameJob: Job? = null  // Debounced display-name uniqueness check
    // Last discovery's result; written and cleared, never read.
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

    // ICS subscriptions. [subscriptions] leaves out the row in its delete-with-undo window, so it
    // disappears on swipe (issue #133).
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

    // Settings search state. SettingsRoute closes search before opening a sub-screen, so
    // sub-screens never receive a non-empty query.
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

    // Contact sync needs READ and WRITE (it writes server contacts to the device); the birthday
    // and anniversary reads above need READ alone. Kept separate so a read-granted, write-denied
    // login doesn't turn the sync toggle on without ever requesting WRITE.
    private val _hasContactsSyncPermission = MutableStateFlow(false)
    val hasContactsSyncPermission: StateFlow<Boolean> = _hasContactsSyncPermission.asStateFlow()

    /**
     * Drives the inline re-grant row in the account detail sheet; the conditions that set and
     * clear it are on [KashCalDataStore.contactSyncPermissionNeeded]. App-global because the
     * contacts permissions are app-wide, not per account.
     */
    val contactSyncPermissionNeeded: StateFlow<Boolean> =
        dataStore.contactSyncPermissionNeeded.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    // Local-network permission (Android 17+): resolved by the host, which needs the activity for
    // the rationale read, and pushed here so the CalDAV sign-in sheet and the add-subscription
    // dialog can ask up front for LAN servers. Defaults to NotRequired so an OS before API 37
    // never shows anything.
    private val _localNetworkPermissionState =
        MutableStateFlow<LocalNetworkPermissionState>(LocalNetworkPermissionState.NotRequired)
    val localNetworkPermissionState: StateFlow<LocalNetworkPermissionState> =
        _localNetworkPermissionState.asStateFlow()

    // Set when a discovery attempt fails while the permission is required but not granted, which
    // looks like a blocked LAN socket. Drives the sign-in banner even when the URL isn't
    // recognizably local (a bare hostname or custom domain), so the user gets the Allow-access
    // action and not only an error message. Reset at the start of each attempt, when the sheet
    // closes and when the permission is granted.
    private val _localNetworkHintActive = MutableStateFlow(false)
    val localNetworkHintActive: StateFlow<Boolean> = _localNetworkHintActive.asStateFlow()

    // Device Calendars
    private val _deviceCalendarsEnabled = MutableStateFlow(false)
    val deviceCalendarsEnabled: StateFlow<Boolean> = _deviceCalendarsEnabled.asStateFlow()

    private val _hasReadCalendarPermission = MutableStateFlow(false)
    val hasReadCalendarPermission: StateFlow<Boolean> = _hasReadCalendarPermission.asStateFlow()

    private val _hasWriteCalendarPermission = MutableStateFlow(false)
    val hasWriteCalendarPermission: StateFlow<Boolean> = _hasWriteCalendarPermission.asStateFlow()

    // Legacy alias of [hasReadCalendarPermission]
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
     * Emits the app theme choice, derived from the stored theme string. A cold flow, not stateIn:
     * the activity seeds the first frame with a synchronous read and this flow's first emission is
     * the same stored value, so there's no flash of the default theme on cold start.
     */
    val themeMode: Flow<ThemeMode> = dataStore.theme
        .map { ThemeMode.fromPrefValue(it) }

    /**
     * Emits where app and widget colors come from, dynamic Material You or the accent seed;
     * [UserPreferencesRepository.resolvedColorSource] maps a retired "teal" theme to the seed,
     * whose default keeps the brand color.
     */
    val colorSource: Flow<ColorSource> = userPreferences.resolvedColorSource

    /** Current accent seed color (packed ARGB); meaningful when [colorSource] is SEED. */
    val accentSeed: Flow<Int> = dataStore.accentSeed

    private val _firstDayOfWeek = MutableStateFlow(java.util.Calendar.SUNDAY)
    val firstDayOfWeek: StateFlow<Int> = _firstDayOfWeek.asStateFlow()

    private val _showWeekNumbers = MutableStateFlow(false)
    val showWeekNumbers: StateFlow<Boolean> = _showWeekNumbers.asStateFlow()

    private val _showMultiDayTimedInAllDayStrip =
        MutableStateFlow(PreferencesKeys.DEFAULT_SHOW_MULTIDAY_TIMED_IN_ALLDAY_STRIP)
    val showMultiDayTimedInAllDayStrip: StateFlow<Boolean> = _showMultiDayTimedInAllDayStrip.asStateFlow()

    private val _quickAddEnabled = MutableStateFlow(false)
    val quickAddEnabled: StateFlow<Boolean> = _quickAddEnabled.asStateFlow()

    private val _titleSuggestionsEnabled = MutableStateFlow(true)
    val titleSuggestionsEnabled: StateFlow<Boolean> = _titleSuggestionsEnabled.asStateFlow()

    private val _widgetMaxEventsPerDay = MutableStateFlow(5)
    val widgetMaxEventsPerDay: StateFlow<Int> = _widgetMaxEventsPerDay.asStateFlow()

    private val _widgetDetailedRows = MutableStateFlow(false)
    val widgetDetailedRows: StateFlow<Boolean> = _widgetDetailedRows.asStateFlow()

    // Backup & Restore dialog state
    private val _backupRestoreState = MutableStateFlow<BackupRestoreUiState>(BackupRestoreUiState.Idle)
    val backupRestoreState: StateFlow<BackupRestoreUiState> = _backupRestoreState.asStateFlow()

    // Backup JSON held in memory between [prepareExport] and the SAF writer consuming it.
    // ViewModel-scoped so it survives a configuration change, such as rotation while the SAF
    // picker is up.
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
            }
        }
    }

    /**
     * Keeps the Connected iCloud state's calendar count live. The count joins on the account's
     * provider, so local calendars aren't counted.
     */
    private fun observeICloudCalendarCount() {
        viewModelScope.launch {
            eventCoordinator.getICloudCalendarCount().collect { count ->
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

    private fun observeCalDavAccountCount() {
        viewModelScope.launch {
            eventCoordinator.getCalDavAccountCount().collect { count ->
                _uiState.update { it.copy(calDavAccountCount = count) }
            }
        }
    }

    /**
     * Maps CalDAV accounts to [CalDavAccountUiModel] rows with calendar counts. Counts are read
     * when the account list emits, so a calendar change alone doesn't refresh them.
     */
    private fun observeCalDavAccounts() {
        viewModelScope.launch {
            eventCoordinator.getCalDavAccounts().collect { accounts ->
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

                // The pending row vanished from the database during the undo window: clear
                // pending so a later settle doesn't delete a row that no longer exists.
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
            dataStore.birthdayReminder.collect { reminder ->
                _contactBirthdaysReminder.value = reminder
            }
        }
        viewModelScope.launch {
            // Seed the color from the birthdays calendar when it exists
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
            deviceEventWriter.pruneStaleCalendarIds()
            val calendars = deviceEventReader.getDeviceCalendars()
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
        // The DefaultCalendar format, which covers Room and device calendars
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
            dataStore.showMultiDayTimedInAllDayStrip.collect { show ->
                _showMultiDayTimedInAllDayStrip.value = show
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

    /** Opens the inline search bar with an empty query. */
    fun onSearchOpen() {
        _isSearchActive.value = true
        _searchQuery.value = ""
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    /** Closes the search bar and clears the query. */
    fun onSearchClose() {
        _isSearchActive.value = false
        _searchQuery.value = ""
    }

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

    /** Stores the time format and refreshes the widgets, which show times. */
    fun setTimeFormat(format: String) {
        viewModelScope.launch {
            dataStore.setTimeFormat(format)
            widgetUpdateManager.updateAllWidgets("time_format_changed")
        }
    }

    fun setFirstDayOfWeek(day: Int) {
        viewModelScope.launch {
            dataStore.setFirstDayOfWeek(day)
            // The month widget lays out its columns from this preference, so refresh the widgets
            // now instead of waiting for the next periodic update.
            widgetUpdateManager.updateAllWidgets("first_day_of_week_changed")
        }
    }

    fun setShowWeekNumbers(show: Boolean) {
        viewModelScope.launch {
            dataStore.setShowWeekNumbers(show)
            // The month widget's week-number gutter follows this preference, so refresh the
            // widgets now instead of on the next periodic update.
            widgetUpdateManager.updateAllWidgets("week_numbers_changed")
        }
    }

    fun setShowMultiDayTimedInAllDayStrip(show: Boolean) {
        viewModelScope.launch {
            dataStore.setShowMultiDayTimedInAllDayStrip(show)
        }
    }

    /** Stores the widget's events-per-day limit and refreshes the widgets. */
    fun setWidgetMaxEventsPerDay(count: Int) {
        viewModelScope.launch {
            dataStore.setWidgetMaxEventsPerDay(count)
            widgetUpdateManager.updateAllWidgets("widget_max_events_changed")
        }
    }

    /**
     * Stores the widget row style, compact single line or detailed two lines, and refreshes the
     * widgets.
     */
    fun setWidgetDetailedRows(detailed: Boolean) {
        viewModelScope.launch {
            dataStore.setWidgetDetailedRows(detailed)
            widgetUpdateManager.updateAllWidgets("widget_detailed_rows_changed")
        }
    }

    private fun checkContactsPermission() {
        val read = permissionChecker.hasReadContactsPermission()
        _hasContactsPermission.value = read
        _hasContactsSyncPermission.value =
            contactSyncPermissionGranted(read, permissionChecker.hasWriteContactsPermission())
    }

    // ==================== Account Actions ====================

    fun showICloudSignInSheet() {
        _uiState.update { it.copy(showICloudSignInSheet = true) }
    }

    fun hideICloudSignInSheet() {
        _uiState.update { it.copy(showICloudSignInSheet = false) }
    }

    /**
     * Makes a successful iCloud sign-in finish the activity back to HomeScreen instead of showing
     * the success sheet. `SettingsActivity` sets it when onboarding launches it.
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
     * Signs in to iCloud: discovers the account and its calendars, which creates the account
     * and saves its credentials, then requests a full sync of it and schedules periodic sync.
     * Blank input, a timeout, an auth error or a discovery error returns the sheet to
     * NotConnected with the message.
     */
    fun onSignIn() {
        viewModelScope.launch {
            _uiState.update { it.copy(iCloudState = ICloudConnectionState.Connecting) }

            // Snapshot the input so a later edit clearing passwordInput can't change what an
            // error branch restores.
            val snappedAppleId = appleIdInput
            val snappedPassword = passwordInput
            val snappedShowHelp = showHelpState

            // Rebuilds the NotConnected state from the snapshot with the given error.
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

            val result = withRetryOnTimeout {
                discoveryService.discoverAndCreateAccount(
                    username = appleIdInput.trim(),
                    password = passwordInput.trim()
                )
            }

            when {
                result == null -> {
                    // Every attempt timed out: network too slow or server unreachable
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

                    // Discovery saved the credentials; a failed save returns an Error instead.
                    passwordInput = ""

                    _uiState.update {
                        it.copy(
                            iCloudState = ICloudConnectionState.Connected(
                                accountId = result.account.id,
                                appleId = result.account.email,
                                lastSyncTime = null,
                                calendarCount = result.calendars.size
                            ),
                            showICloudSignInSheet = false,
                            // Initial setup finishes back to HomeScreen; otherwise the success
                            // sheet shows.
                            pendingFinishActivity = isInitialSetup,
                            showAccountConnectedSheet = !isInitialSetup,
                            connectedProviderName = if (!isInitialSetup) "iCloud" else "",
                            connectedEmail = if (!isInitialSetup) result.account.email else "",
                            connectedCalendarCount = if (!isInitialSetup) result.calendars.size else 0
                        )
                    }

                    // Initial sync of the new account only
                    syncScheduler.syncAccount(result.account.id, forceFullSync = true)

                    // Periodic sync at the configured interval, unless it is manual only
                    val intervalMinutes = userPreferences.syncIntervalMs.first() / (60 * 1000L)
                    if (intervalMinutes > 0 && intervalMinutes != Long.MAX_VALUE / (60 * 1000L)) {
                        syncScheduler.schedulePeriodicSync(intervalMinutes)
                        val hours = intervalMinutes / 60
                        val displayInterval = if (hours >= 24) "${hours / 24} day(s)" else "$hours hour(s)"
                        Log.d(TAG, "Periodic background sync: every $displayInterval")
                    }
                }

                result is DiscoveryResult.AuthError -> {
                    // TODO: result.message is English-only from the sync layer. Make
                    // DiscoveryResult carry an error kind the UI maps to a localized string.
                    Log.e(TAG, "Authentication failed: ${result.message}")
                    _uiState.update {
                        it.copy(iCloudState = iCloudNotConnectedWith(UiMessage.Literal(result.message)))
                    }
                }

                result is DiscoveryResult.Error -> {
                    Log.e(TAG, "Discovery failed: ${result.message}")
                    _uiState.update {
                        it.copy(iCloudState = iCloudNotConnectedWith(discoveryErrorMessage(result)))
                    }
                }
            }
        }
    }

    /**
     * Signs out of iCloud: deletes the account and its data ([AccountRepository.deleteAccount]
     * lists the cleanup) and resets the whole UI state to NotConnected.
     */
    fun onSignOut() {
        viewModelScope.launch {
            Log.i(TAG, "Signing out from iCloud")

            val icloudAccounts = accountRepository.getAccountsByProvider(AccountProvider.ICLOUD)
            val account = icloudAccounts.firstOrNull()
            val accountEmail = account?.email

            if (accountEmail != null) {
                discoveryService.removeAccountByEmail(accountEmail)
            }

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

    fun showCalDavSignInSheet() {
        _uiState.update { it.copy(showCalDavSignInSheet = true) }
    }

    /** Hides the CalDAV sign-in sheet and resets its input, discovery and LAN-hint state. */
    fun hideCalDavSignInSheet() {
        validateDisplayNameJob?.cancel()
        validateDisplayNameJob = null

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
        // Auto-fill the display name from server URL and username until the user edits it
        if (!calDavDisplayNameManuallyEdited) {
            calDavDisplayName = generateDefaultDisplayName(serverUrl, calDavUsername)
            // The name changed, so a display-name error no longer applies
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
        calDavDisplayNameManuallyEdited = true
        updateCalDavNotConnectedState()  // Before starting the validation job

        // Uniqueness check for manual edits only, debounced 300 ms
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
                // Clear a display-name error once the name is valid
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
        // Same display-name auto-fill as [onCalDavServerUrlChange]
        if (!calDavDisplayNameManuallyEdited) {
            calDavDisplayName = generateDefaultDisplayName(calDavServerUrl, username)
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
     * Builds the default CalDAV display name: `username@Provider` for a known host,
     * `username@<first host label>` otherwise, or `username@<address>` for an IPv4 host.
     *
     * @return "" when the URL or username is blank, the bare username when no host parses
     */
    private fun generateDefaultDisplayName(serverUrl: String, username: String): String {
        if (serverUrl.isBlank() || username.isBlank()) return ""

        val host = try {
            val url = if (serverUrl.startsWith("http")) serverUrl else "https://$serverUrl"
            java.net.URI(url).host ?: return username
        } catch (_: Exception) {
            return username
        }

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
            val isIpAddress = host.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))
            val hostPart = if (isIpAddress) host else host.split(".").firstOrNull() ?: host
            "$username@$hostPart"
        }
    }

    /**
     * Connects a CalDAV account from the sign-in sheet:
     * 1. Checks the inputs are filled and the display name is unused.
     * 2. Discovers the calendars, with the timeout retry.
     * 3. With at least one calendar, creates the account with every discovered calendar.
     * 4. Shows the success sheet, requests a full sync of the account and schedules periodic
     *    sync unless it is manual only.
     *
     * Any failure returns the sheet to NotConnected with the message and, where known, the field.
     */
    fun onCalDavDiscover() {
        viewModelScope.launch {
            Log.i(TAG, "Starting CalDAV discovery for: ${calDavUsername.take(3)}***")

            // Clear the blocked-LAN signal; set again only if this attempt fails the same way.
            _localNetworkHintActive.value = false

            // Snapshot the input so the error states below restore what was submitted.
            val snappedServerUrl = calDavServerUrl
            val snappedDisplayName = calDavDisplayName
            val snappedUsername = calDavUsername
            val snappedPassword = calDavPassword
            val snappedTrustInsecure = calDavTrustInsecure

            // Rebuilds the NotConnected state from the snapshot with the given error. Service
            // messages wrap as UiMessage.Literal and are English-only; DiscoveryResult should
            // carry an error kind the UI maps to a localized string.
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

            _uiState.update {
                it.copy(
                    calDavState = CalDavConnectionState.Discovering(
                        serverUrl = calDavServerUrl,
                        username = calDavUsername
                    )
                )
            }

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

                    calDavDiscoveredPrincipalUrl = discoveryResult.principalUrl
                    calDavDiscoveredCalendarHomeUrl = discoveryResult.calendarHomeUrl
                    calDavDiscoveredCalendars = discoveryResult.calendars
                    calDavServerUrl = discoveryResult.serverUrl

                    val createResult = calDavDiscoveryService.createAccountWithSelectedCalendars(
                        serverUrl = discoveryResult.serverUrl,
                        username = calDavUsername,
                        password = calDavPassword,
                        trustInsecure = calDavTrustInsecure,
                        principalUrl = discoveryResult.principalUrl,
                        calendarHomeUrl = discoveryResult.calendarHomeUrl,
                        selectedCalendars = discoveryResult.calendars,  // every calendar
                        displayName = calDavDisplayName.takeIf { it.isNotBlank() }
                    )

                    when (createResult) {
                        is DiscoveryResult.Success -> {
                            Log.i(TAG, "CalDAV account created: ${createResult.account.id}")

                            calDavPassword = ""

                            // Success-sheet title: the display name or the generated default
                            val providerName = calDavDisplayName.takeIf { it.isNotBlank() }
                                ?: generateDefaultDisplayName(discoveryResult.serverUrl, calDavUsername).ifBlank { "CalDAV" }

                            // One update closes the sign-in sheet and opens the success sheet
                            _uiState.update {
                                it.copy(
                                    calDavState = CalDavConnectionState.NotConnected(),
                                    showCalDavSignInSheet = false,
                                    showAccountConnectedSheet = true,
                                    connectedProviderName = providerName,
                                    connectedEmail = calDavUsername,
                                    connectedCalendarCount = discoveryResult.calendars.size
                                )
                            }

                            calDavServerUrl = ""
                            calDavDisplayName = ""
                            calDavDisplayNameManuallyEdited = false
                            calDavUsername = ""
                            calDavTrustInsecure = false
                            calDavDiscoveredPrincipalUrl = null
                            calDavDiscoveredCalendarHomeUrl = null
                            calDavDiscoveredCalendars = emptyList()

                            // Initial sync of the new account only
                            syncScheduler.syncAccount(createResult.account.id, forceFullSync = true)

                            // Periodic sync, unless it is manual only
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
                                        discoveryErrorMessage(createResult),
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
                    // A blocked local-network socket surfaces here. Arm the Allow-access banner
                    // so the user has an action and not only the hint text; this covers bare
                    // hostnames `isLanHost` can't classify.
                    if (isDiscoveryFailureBlockedLan()) {
                        _localNetworkHintActive.value = true
                    }
                    val (message, errorField) = if (discoveryResult.reason != null) {
                        discoveryErrorMessage(discoveryResult) to CalDavConnectionState.ErrorField.SERVER
                    } else {
                        val field = if (discoveryResult.message.contains("URL", ignoreCase = true) ||
                            discoveryResult.message.contains("server", ignoreCase = true)
                        ) CalDavConnectionState.ErrorField.SERVER else null
                        withLanHintIfBlocked(discoveryResult.message) to field
                    }
                    _uiState.update {
                        it.copy(calDavState = calDavNotConnectedWith(message, errorField))
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

    /** Signs out of a CalDAV account: cancels its reminders, then deletes the account. */
    fun onCalDavSignOut(accountId: Long) {
        viewModelScope.launch {
            Log.i(TAG, "Signing out from CalDAV account: $accountId")

            // Before the cascade deletes the events the reminders are found by
            eventCoordinator.cancelRemindersForCalDavAccount(accountId)

            // [AccountRepository.deleteAccount] lists the cleanup
            calDavDiscoveryService.removeAccount(accountId)

            Log.i(TAG, "CalDAV account $accountId removed")
        }
    }

    // ==================== Calendar Actions ====================

    fun onToggleCalendar(calendarId: Long, visible: Boolean) {
        viewModelScope.launch {
            // The calendar's visible flag is the source of truth for which calendars show
            eventCoordinator.setCalendarVisibility(calendarId, visible)
        }
    }

    fun onShowAllCalendars() {
        viewModelScope.launch {
            _calendars.value.forEach { calendar ->
                eventCoordinator.setCalendarVisibility(calendar.id, true)
            }
        }
    }

    fun onHideAllCalendars() {
        viewModelScope.launch {
            // At least one calendar stays visible: the first one
            val firstCalendarId = _calendars.value.firstOrNull()?.id
            _calendars.value.forEach { calendar ->
                eventCoordinator.setCalendarVisibility(calendar.id, calendar.id == firstCalendarId)
            }
        }
    }

    // ==================== Subscription Actions ====================

    /**
     * Opens the add-subscription dialog with [url] filled in; `SettingsActivity` calls it when a
     * webcal:// link launches it.
     */
    fun openAddSubscriptionWithUrl(url: String) {
        _uiState.update {
            it.copy(
                showAddSubscriptionDialog = true,
                prefillSubscriptionUrl = url
            )
        }
    }

    fun hideAddSubscriptionDialog() {
        _uiState.update {
            it.copy(
                showAddSubscriptionDialog = false,
                prefillSubscriptionUrl = null
            )
        }
    }

    /**
     * Adds an ICS subscription and fetches its feed.
     *
     * @param url the feed URL; webcal:// and webcals:// are rewritten to https://
     * @param color calendar color (ARGB)
     * @param duplicateUrlMessage snackbar text shown when the URL is already subscribed; null
     *   shows nothing
     */
    fun onAddSubscription(
        url: String,
        name: String,
        color: Int,
        duplicateUrlMessage: String? = null,
    ) {
        // applicationScope, not viewModelScope: the user may close the sheet while the fetch is
        // in flight, and cancelling then would abandon the add part-way and leave the feed with
        // no refresh job. Same reason as [commitSubscriptionDeletion].
        applicationScope.launch {
            Log.i(TAG, "Adding subscription: $url, $name")

            when (val result = eventCoordinator.addIcsSubscription(url, name, color)) {
                is IcsSubscriptionRepository.SubscriptionResult.Success -> {
                    Log.i(TAG, "Subscription added: ${result.subscription.name}")
                    // The coordinator reconciles the refresh schedule after each subscription
                    // change it makes, so no screen has to arm the refresh job.
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
     * Stages an ICS subscription for deletion behind a snackbar with an Undo action (issue #133).
     *
     * - The row leaves [subscriptions] at once.
     * - Nothing is deleted until [onSubscriptionDeletionSettled], [onCleared] or the next staged
     *   deletion commits it.
     * - A different subscription already pending is committed first, so undo can never
     *   restore the wrong row.
     *
     * @param removedMessage localized snackbar message, such as "Subscription removed"
     * @param undoActionLabel localized action label, such as "Undo"
     */
    fun onDeleteSubscription(
        subscriptionId: Long,
        removedMessage: String,
        undoActionLabel: String
    ) {
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
     * Cancels the pending ICS subscription deletion without touching stored data. Idempotent: a
     * no-op when no deletion is pending.
     */
    fun onUndoSubscriptionDeletion() {
        val pending = _pendingSubscriptionDeletionId.value ?: return
        Log.i(TAG, "Undo pending subscription deletion: $pending")
        _pendingSubscriptionDeletionId.value = null
        _uiState.update { it.copy(pendingSubscriptionDeletionId = null) }
        clearSnackbar()
    }

    /**
     * Commits the pending ICS subscription deletion. The settings snackbar host calls it on
     * `Dismissed`; a snackbar cut off by the screen closing reports nothing, which [onCleared]
     * covers.
     *
     * Idempotent: a no-op if no deletion is pending. Material 3 may fire `Dismissed` after
     * `ActionPerformed`; the guard means a deletion the user undid is never committed.
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
     * Commits any pending subscription deletion when the ViewModel is destroyed.
     *
     * When the Activity finishes inside the undo window, the snackbar's LaunchedEffect is
     * cancelled and `Dismissed` never reaches [onSubscriptionDeletionSettled]; this commits the
     * deletion instead (issue #133, v23.7.9).
     */
    override fun onCleared() {
        val pending = _pendingSubscriptionDeletionId.value
        if (pending != null) {
            Log.i(TAG, "Committing pending deletion on ViewModel destruction: $pending")
            commitSubscriptionDeletion(pending)
        }
        onSearchClose()
        super.onCleared()
    }

    /** Exposes [onCleared] to tests; [ViewModel.onCleared] is `protected`. */
    @androidx.annotation.VisibleForTesting
    internal fun onClearedForTest() = onCleared()

    /**
     * Deletes the subscription on [applicationScope] so the delete survives the Activity being
     * destroyed inside the undo window (issue #133). Every commit path calls this, so the scope
     * choice can't drift.
     */
    private fun commitSubscriptionDeletion(subscriptionId: Long) {
        applicationScope.launch { eventCoordinator.removeIcsSubscription(subscriptionId) }
    }

    /**
     * Enables or disables an ICS subscription on [applicationScope], so the change and the
     * refresh-schedule update it triggers survive the settings screen closing.
     */
    fun onToggleSubscription(subscriptionId: Long, enabled: Boolean) {
        applicationScope.launch {
            Log.i(TAG, "Toggle subscription: $subscriptionId, enabled=$enabled")
            eventCoordinator.setIcsSubscriptionEnabled(subscriptionId, enabled)
        }
    }

    /** Refreshes every enabled ICS subscription now, due or not. */
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

    fun onRefreshSubscription(subscriptionId: Long) {
        viewModelScope.launch {
            Log.i(TAG, "Refreshing subscription: $subscriptionId")
            eventCoordinator.refreshIcsSubscription(subscriptionId)
        }
    }

    /**
     * Updates a subscription's name, color and sync interval on [applicationScope]: a changed
     * interval has to reach the periodic refresh job even if the user backs out of the sheet at
     * once.
     */
    fun onUpdateSubscription(subscriptionId: Long, name: String, color: Int, syncIntervalHours: Int) {
        applicationScope.launch {
            Log.i(TAG, "Updating subscription: $subscriptionId, name=$name, interval=${syncIntervalHours}h")
            eventCoordinator.updateIcsSubscriptionSettings(subscriptionId, name, color, syncIntervalHours)
        }
    }

    // ==================== Contact Birthdays ====================

    /**
     * Turns contact birthdays on (creates the calendar, syncs it, starts observing contacts) or
     * off (deletes the calendar; observing stops once anniversaries are off too). The caller must
     * hold READ_CONTACTS before enabling; without it [ContactEventManager.onBirthdaysEnabled]
     * turns both contact features back off.
     */
    fun onToggleContactBirthdays(enabled: Boolean) {
        viewModelScope.launch {
            Log.i(TAG, "Toggle contact birthdays: enabled=$enabled")

            if (enabled) {
                val color = _contactBirthdaysColor.value
                eventCoordinator.enableContactBirthdays(color)
                eventCoordinator.syncContactBirthdays()
                dataStore.setContactBirthdaysEnabled(true)
                contactEventManager.onBirthdaysEnabled()
            } else {
                dataStore.setContactBirthdaysEnabled(false)
                dataStore.setContactBirthdaysLastSync(0L)
                contactEventManager.onBirthdaysDisabled()
                eventCoordinator.disableContactBirthdays()
            }
            refreshContactEventCounts()
        }
    }

    fun onContactBirthdaysColorChange(color: Int) {
        viewModelScope.launch {
            Log.i(TAG, "Contact birthdays color change: $color")
            _contactBirthdaysColor.value = color
            eventCoordinator.updateContactBirthdaysColor(color)
        }
    }

    private var birthdayReminderJob: Job? = null
    /**
     * Stores the birthday reminder and, while birthdays are on, syncs them so every existing
     * event's reminders and AlarmManager alarms move to the new value now. Each change cancels the
     * previous job, as [org.onekash.kashcal.data.contacts.ContactEventObserver] does, so rapid
     * picker dismissals end on the last value.
     */
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

    /** Same as [onToggleContactBirthdays] for anniversaries. */
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

    fun onContactAnniversariesColorChange(color: Int) {
        viewModelScope.launch {
            Log.i(TAG, "Contact anniversaries color change: $color")
            _contactAnniversariesColor.value = color
            eventCoordinator.updateContactAnniversariesColor(color)
        }
    }

    private var anniversaryReminderJob: Job? = null
    /** Same as [onContactBirthdaysReminderChange] for anniversaries. */
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
     * Turns device calendars on or off. The caller requests READ_CALENDAR before enabling;
     * without it [CalendarProviderManager.onEnabled] switches the feature back off.
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

    fun onToggleShowDeclinedEvents(show: Boolean) {
        viewModelScope.launch {
            dataStore.setShowDeclinedEvents(show)
            // Re-query device events so views refresh with the updated filter
            calendarProviderManager.onDeviceCalendarSettingsChanged()
        }
    }

    /** Turns device calendar reminders on (schedules the next one) or off (cancels its alarm). */
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
     * Ticks or unticks one device calendar. Ticking makes it visible in the provider; unticking
     * also drops it from the hidden set so it doesn't linger there.
     */
    fun onToggleDeviceCalendar(calendarId: Long, enabled: Boolean) {
        viewModelScope.launch {
            val currentIds = _enabledDeviceCalendarIds.value.toMutableSet()
            if (enabled) currentIds.add(calendarId) else currentIds.remove(calendarId)
            dataStore.setEnabledDeviceCalendarIds(currentIds)
            if (enabled) {
                deviceEventWriter.ensureCalendarVisible(calendarId)
            } else {
                dataStore.removeFromHiddenDeviceCalendarIds(calendarId)
            }
            // Re-query device events so the calendar views refresh
            calendarProviderManager.onDeviceCalendarSettingsChanged()
        }
    }

    /**
     * Re-reads the calendar permissions and reloads the device calendar lists that depend on them.
     * Called on resume and after a calendar permission request returns.
     */
    fun refreshCalendarPermission() {
        checkCalendarPermission()
        if (_hasReadCalendarPermission.value && _deviceCalendarsEnabled.value) {
            viewModelScope.launch { loadDeviceCalendars() }
        }
        loadWritableDeviceCalendars()
    }

    /**
     * Reloads the device calendar list, for example after the user adds a calendar account. A
     * no-op without READ_CALENDAR or with device calendars off.
     */
    fun refreshDeviceCalendars() {
        if (_hasReadCalendarPermission.value && _deviceCalendarsEnabled.value) {
            viewModelScope.launch { loadDeviceCalendars() }
        }
    }

    /**
     * Loads the writable device calendars for the default-calendar picker; empty without
     * WRITE_CALENDAR. Unlike [loadDeviceCalendars] it ignores the device-calendars switch, so a
     * device calendar can be the default either way.
     */
    private fun loadWritableDeviceCalendars() {
        viewModelScope.launch {
            val groups = if (_hasWriteCalendarPermission.value) {
                try {
                    val deviceCalendars = deviceEventReader.getDeviceCalendars()
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

    /** Sets the default calendar for new events, a Room or a device calendar. */
    fun onDefaultCalendarSelect(calendar: DefaultCalendar) {
        viewModelScope.launch {
            userPreferences.setDefaultCalendar(calendar)
        }
    }

    // ==================== Sync Settings ====================

    fun onSyncIntervalChange(intervalMs: Long) {
        viewModelScope.launch {
            userPreferences.setSyncIntervalMs(intervalMs)

            // Long.MAX_VALUE is manual only
            if (intervalMs != Long.MAX_VALUE) {
                val intervalMinutes = intervalMs / (60 * 1000L)
                syncScheduler.updatePeriodicSyncInterval(intervalMinutes)
            } else {
                syncScheduler.cancelPeriodicSync()
            }
        }
    }

    /**
     * Sets how many days back calendar events sync, at least 1 or Int.MAX_VALUE for all.
     *
     * Widening the window, or choosing all, forces a full sync so older events are fetched from
     * the server; the sync compares etags to skip unchanged events.
     *
     * Narrowing it runs [EventCoordinator.cleanupEventsOutsideLookback] before a normal sync.
     * Widgets refresh when anything was deleted.
     */
    fun onSyncLookbackChange(days: Int) {
        val safeDays = if (days == Int.MAX_VALUE) days else days.coerceAtLeast(1)
        val oldDays = _syncLookbackDays.value
        val isExpanding = safeDays > oldDays || safeDays == Int.MAX_VALUE
        val isShrinking = !isExpanding && safeDays != oldDays

        viewModelScope.launch {
            dataStore.setSyncPastDays(safeDays)

            if (isShrinking && safeDays != Int.MAX_VALUE) {
                val now = System.currentTimeMillis()
                val cutoffTs = now - (safeDays.toLong() * 24 * 60 * 60 * 1000)

                val deleted = eventCoordinator.cleanupEventsOutsideLookback(cutoffTs)
                if (deleted > 0) {
                    Log.i(TAG, "Lookback cleanup: $deleted events")
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
     * Requests a full sync with the HomeScreen banner and sync notifications, for when data seems
     * out of step with the server.
     */
    fun forceFullSync() {
        syncScheduler.setShowBannerForSync(true)
        syncScheduler.requestImmediateSync(forceFullSync = true, showNotification = true)
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

    /** Re-reads the contacts permissions; called on resume and after a contacts request returns. */
    fun refreshContactsPermission() {
        checkContactsPermission()
    }

    /**
     * Takes the local-network permission state the host resolved (it owns the rationale read and
     * the request launcher). Called when the CalDAV sign-in sheet or the add-subscription dialog
     * opens and after a permission request returns. See
     * [org.onekash.kashcal.ui.permission.shouldShowLanBanner].
     */
    fun updateLocalNetworkPermissionState(state: LocalNetworkPermissionState) {
        _localNetworkPermissionState.value = state
        // A granted permission makes the blocked-LAN hint moot.
        if (state == LocalNetworkPermissionState.Granted) {
            _localNetworkHintActive.value = false
        }
    }

    /**
     * Merges a live read of the local-network permission into the stored state on resume, for
     * example after the user changed it in system Settings. [reconcileOnResume] decides what the
     * live read may replace; it never overwrites PermanentlyDenied, or the banner would nag again
     * on every resume.
     */
    fun reconcileLocalNetworkPermissionOnResume(resolved: LocalNetworkPermissionState) {
        _localNetworkPermissionState.value =
            reconcileOnResume(_localNetworkPermissionState.value, resolved)
    }

    /**
     * The message for a failed discovery: a translated one for the failures the app
     * explains itself, else the service's own text.
     */
    private fun discoveryErrorMessage(error: DiscoveryResult.Error): UiMessage = when (error.reason) {
        DiscoveryErrorReason.INSECURE_CONNECTION_REFUSED ->
            UiMessage.ResId(R.string.caldav_error_connection_refused_insecure)
        null -> UiMessage.Literal(error.message)
    }

    /**
     * Adds the local-network hint to a failed-discovery message when the permission is required
     * but not granted, so a blocked LAN server is explained, bare hostnames that
     * [org.onekash.kashcal.util.isLanHost] can't classify included. The server's message stays
     * as an argument, so a public server that is down isn't mislabeled.
     */
    private fun withLanHintIfBlocked(serverMessage: String): UiMessage =
        if (isDiscoveryFailureBlockedLan()) {
            UiMessage.ResId(R.string.caldav_error_with_lan_hint, listOf(serverMessage))
        } else {
            UiMessage.Literal(serverMessage)
        }

    /**
     * True when a failed discovery looks like a blocked local-network socket: the permission is
     * required (API 37 and later) but not granted. Reads the current permission state only.
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

    // ==================== Default reminders ====================

    suspend fun getDefaultReminderTimed(): Int {
        return userPreferences.defaultReminderTimed.first()
    }

    suspend fun getDefaultReminderAllDay(): Int {
        return userPreferences.defaultReminderAllDay.first()
    }

    // ==================== ICS Device Import ====================

    /**
     * Imports events parsed from an ICS file into device calendar [calendarId] and returns how
     * many were created.
     *
     * Uses the same writer method as the home screen's import, which signals the device change
     * so the calendar views show the new events at once.
     */
    suspend fun importIcsToDeviceCalendar(events: List<Event>, calendarId: Long): Int =
        deviceEventWriter.importIcsEvents(events, calendarId)

    // ==================== Snackbar ====================

    /**
     * Sets the pending snackbar. An [action] adds an action button, which the subscription
     * delete-with-undo flow uses (issue #133).
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

    /** Clears the pending snackbar, its action label and callback included. */
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
     * Builds the backup JSON and holds it in [pendingExportJson] for the SAF writer, which takes
     * it through [consumePendingExportJson].
     */
    suspend fun prepareExport() {
        pendingExportJson = backupExporter.exportSettings()
    }

    /** Returns the held backup JSON and clears it. */
    fun consumePendingExportJson(): String? {
        val json = pendingExportJson
        pendingExportJson = null
        return json
    }

    /**
     * Parses a selected backup file into [BackupRestoreUiState.PendingConfirmation], which shows
     * the confirmation dialog, or [BackupRestoreUiState.Error]. Writes nothing.
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
     * Applies the backup held by [BackupRestoreUiState.PendingConfirmation]. A no-op in any other
     * state, for example when a dismiss beat the confirm.
     */
    fun confirmRestore() {
        val pending = _backupRestoreState.value as? BackupRestoreUiState.PendingConfirmation ?: return
        viewModelScope.launch {
            try {
                _backupRestoreState.value =
                    BackupRestoreUiState.Success(backupImporter.applyBackup(pending.envelope))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "applyBackup failed", e)
                _backupRestoreState.value = BackupRestoreUiState.Error(BackupImportError.ApplyFailed(e.message))
            } finally {
                // The restore may have switched device calendars on or off without the
                // switch, so start or stop observing the provider to match. Runs even when the
                // apply failed or was cancelled, since settings may already be written.
                withContext(NonCancellable) {
                    calendarProviderManager.applyDeviceCalendarsSetting()
                }
            }
        }
    }

    /** Dismisses any backup or restore dialog, returning to [BackupRestoreUiState.Idle]. */
    fun dismissDialog() {
        _backupRestoreState.value = BackupRestoreUiState.Idle
    }

    // ==================== Account Connected Sheet ====================

    /**
     * Shows the account-connected success sheet.
     *
     * @param provider the sheet's provider label, such as "iCloud" or a CalDAV display name
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

    /** Hides the success sheet without leaving settings. */
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

    /** Hides the success sheet and finishes the activity back to HomeScreen. */
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

    /** Observes the "Sync now" work status; cancelled in [clearAccountDetail]. */
    private var syncObservationJob: Job? = null

    /** Observes the detail sheet's account; cancelled in [clearAccountDetail]. */
    private var accountDetailJob: Job? = null

    /**
     * Observes [accountId] for the account detail sheet. Updates whenever the account row changes,
     * for example after sync metadata is recorded; a deleted account clears the detail.
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

    /** Clears the account detail state and cancels both observations. */
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
                // The contact-sync confirmation names a masked account email and would otherwise
                // outlive the sheet and show again in the next account's sheet.
                contactSyncConfirmation = null
            )
        }
    }

    /**
     * Syncs one account's calendars and tracks the result in the detail sheet. When the account
     * has contact sync on, also syncs its contacts, or raises the re-grant row when the contacts
     * permissions are missing.
     */
    fun syncAccountNow(accountId: Long) {
        _uiState.update { it.copy(accountDetailSyncStatus = AccountDetailSyncStatus.Syncing) }
        syncObservationJob?.cancel()

        // Without this, "Sync now" would silently skip contacts until the next periodic run.
        // Check the live grant, as the enable path does, and set or clear the re-grant row here:
        // a login whose periodic contact job never existed has no worker to do it.
        viewModelScope.launch {
            val contactSyncOn = accountRepository.getAccountById(accountId)?.contactSyncEnabled == true
            if (!contactSyncOn) return@launch
            val granted = contactSyncPermissionGranted(
                readGranted = permissionChecker.hasReadContactsPermission(),
                writeGranted = permissionChecker.hasWriteContactsPermission(),
            )
            if (granted) {
                dataStore.setContactSyncPermissionNeeded(false)
                // This account only: "Sync now" for one login shouldn't re-sync every other
                // contact-sync login's address books.
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
                    else -> { /* Enqueued, Running, Blocked, Idle: stay Syncing */ }
                }
            }
        }
    }

    /** Enables or disables an account for sync. */
    fun toggleAccountEnabled(accountId: Long, enabled: Boolean) {
        viewModelScope.launch {
            accountRepository.setEnabled(accountId, enabled)
        }
    }

    /**
     * Turns CardDAV contact sync on or off for one account through
     * [AccountRepository.setContactSyncEnabled], never a DAO. Disabling needs no permission and
     * shows a confirmation that matches what the purge did.
     *
     * The UI requests READ and WRITE_CONTACTS before enabling, but the pull re-checks the live
     * grant here, as the contact-birthday manager does before its sync. Without both the flag
     * still persists and the re-grant row is raised here: a login whose periodic job was never
     * scheduled (manual only, or older than contact sync) has no worker to raise it. With both,
     * the row is cleared, the periodic contact job is ensured unless sync is manual only, and a
     * pull of every contact-sync login starts now.
     */
    fun onToggleContactSync(accountId: Long, enabled: Boolean) {
        viewModelScope.launch {
            val purgeOutcome = accountRepository.setContactSyncEnabled(accountId, enabled)

            // Both branches name the account. A vanished account gives a blank label
            // (maskEmail takes null), and the confirmation still reads sensibly.
            val maskedEmail = maskEmail(accountRepository.getAccountById(accountId)?.email)

            if (!enabled) {
                // Say what the purge did, not a blanket "removed": a same-email sibling can keep
                // the contacts, and a purge without the permission can't confirm removal. A
                // removal or an unconfirmed "may remain" shows as a warning; contacts a sibling
                // kept are benign, so that stays positive.
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
                // Raise the re-grant row now; no worker may ever run to do it. Skip the
                // "Syncing" confirmation, since nothing will pull.
                dataStore.setContactSyncPermissionNeeded(true)
                return@launch
            }
            dataStore.setContactSyncPermissionNeeded(false)

            // Enabling only sets a flag and registers the system account; nothing pulls
            // contacts until a sync runs. Ensure the periodic job, which an older periodic
            // setup lacks, and start a one-shot pull of every contact-sync login so contacts
            // appear within seconds instead of at the next periodic run.
            val intervalMs = userPreferences.syncIntervalMs.first()
            // Long.MAX_VALUE means manual only: as with calendar sync, no periodic job, but the
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

    private fun showContactSyncConfirmation(confirmation: ContactSyncConfirmation) {
        _uiState.update { it.copy(contactSyncConfirmation = confirmation) }
    }

    /** Clears the inline contact-sync confirmation once it has been shown. */
    fun clearContactSyncConfirmation() {
        _uiState.update { it.copy(contactSyncConfirmation = null) }
    }

    /**
     * Renames an account to [newName], trimmed. A blank name or a missing account is ignored
     * without feedback.
     */
    fun renameAccount(accountId: Long, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return

        viewModelScope.launch {
            val account = accountRepository.getAccountById(accountId) ?: return@launch
            accountRepository.updateAccount(account.copy(displayName = trimmed))
            refreshCalDavAccounts()
            refreshICloudState(account)
        }
    }

    /**
     * Changes an account's password: saves the new credentials, validates them by refreshing the
     * account's calendars, and restores the old ones on an auth or discovery error.
     *
     * @param onResult gets success, or a failure whose message is the localized reason
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

            val newCredentials = oldCredentials.copy(password = newPassword)
            accountRepository.saveCredentials(accountId, newCredentials)

            // refreshCalendars reads the stored credentials, so it tests the new password
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
                    // Not a refresh result, but the credentials worked
                    onResult(Result.success(Unit))
                }
                is DiscoveryResult.AuthError -> {
                    accountRepository.saveCredentials(accountId, oldCredentials)
                    onResult(Result.failure(Exception(context.getString(R.string.password_change_error_invalid))))
                }
                is DiscoveryResult.Error -> {
                    accountRepository.saveCredentials(accountId, oldCredentials)
                    val message = if (result.reason != null) {
                        discoveryErrorMessage(result).resolve(context)
                    } else {
                        context.getString(R.string.password_change_error_network)
                    }
                    onResult(Result.failure(Exception(message)))
                }
            }
        }
    }

    /**
     * Re-discovers an existing account's calendars, reports how many are new in the detail
     * sheet, and syncs the account when any are.
     */
    fun discoverNewCalendars(accountId: Long) {
        _uiState.update { it.copy(accountDetailDiscoverStatus = AccountDetailDiscoverStatus.Discovering) }

        viewModelScope.launch {
            val account = accountRepository.getAccountById(accountId)
            if (account == null) {
                _uiState.update {
                    it.copy(accountDetailDiscoverStatus = AccountDetailDiscoverStatus.Error(UiMessage.Literal("Account not found")))
                }
                return@launch
            }

            val beforeCount = eventCoordinator.getCalendarCountForAccount(accountId)

            val result = when (account.provider) {
                AccountProvider.ICLOUD -> discoveryService.refreshCalendars(accountId)
                AccountProvider.CALDAV -> calDavDiscoveryService.refreshCalendars(accountId)
                else -> {
                    _uiState.update {
                        it.copy(accountDetailDiscoverStatus = AccountDetailDiscoverStatus.Error(UiMessage.Literal("Provider does not support discovery")))
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
                    // Not a refresh result; treat it as no change
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
                                UiMessage.Literal("Authentication failed. Update your password.")
                            )
                        )
                    }
                }
                is DiscoveryResult.Error -> {
                    _uiState.update {
                        it.copy(accountDetailDiscoverStatus = AccountDetailDiscoverStatus.Error(discoveryErrorMessage(result)))
                    }
                }
            }
        }
    }

    /** Reloads the CalDAV account rows after a rename. */
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

    /** Does nothing: the iCloud Connected state has no display name to update. */
    private fun refreshICloudState(account: Account) {
        if (account.provider != AccountProvider.ICLOUD) return
        val currentState = _uiState.value.iCloudState
        if (currentState is ICloudConnectionState.Connected) {
            // The account detail sheet picks up the new name through [observeAccountDetail]
        }
    }
}
