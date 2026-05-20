package org.onekash.kashcal.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.identity.canEditAsOrganizer
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.di.IoDispatcher
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.SearchResult
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.error.CalendarError
import org.onekash.kashcal.error.ErrorActionCallback
import org.onekash.kashcal.error.ErrorMapper
import org.onekash.kashcal.error.ErrorPresentation
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus
import org.onekash.kashcal.sync.session.SyncTrigger
import org.onekash.kashcal.ui.components.EventFormState
import org.onekash.kashcal.ui.components.SyncBannerState
import org.onekash.kashcal.ui.components.attendees.AttendeeUiModel
import org.onekash.kashcal.ui.components.generateSnackbarMessage
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.shared.deduplicateAndSortReminders
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.util.DateTimeUtils
import org.onekash.kashcal.util.computeDurationString
import org.onekash.kashcal.util.importEventsToDeviceCalendar
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

private const val TAG = "HomeViewModel"

/**
 * ViewModel for the HomeScreen (main calendar view).
 *
 * Architecture:
 * - Offline-first: All operations work locally first
 * - EventCoordinator: Single entry point for event operations
 * - EventReader: Efficient queries via occurrences table
 * - Flow-based: Reactive state with StateFlow
 *
 * Features:
 * - Month view with event dots
 * - Day selection with event list
 * - Calendar visibility filtering
 * - Search functionality
 * - Network-aware sync
 */
@HiltViewModel
class HomeViewModel(
    private val eventCoordinator: EventCoordinator,
    private val eventReader: EventReader,
    private val displayEventRepository: DisplayEventRepository,
    private val dataStore: KashCalDataStore,
    private val accountRepository: AccountRepository,
    private val syncScheduler: SyncScheduler,
    private val networkMonitor: NetworkMonitor,
    private val calendarProviderRepository: CalendarProviderRepository,
    private val attendeeBackfill: org.onekash.kashcal.domain.reader.AttendeeBackfill,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val currentDayCodeProvider: () -> Int
) : ViewModel() {

    @Inject
    constructor(
        eventCoordinator: EventCoordinator,
        eventReader: EventReader,
        displayEventRepository: DisplayEventRepository,
        dataStore: KashCalDataStore,
        accountRepository: AccountRepository,
        syncScheduler: SyncScheduler,
        networkMonitor: NetworkMonitor,
        calendarProviderRepository: CalendarProviderRepository,
        attendeeBackfill: org.onekash.kashcal.domain.reader.AttendeeBackfill,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        eventCoordinator,
        eventReader,
        displayEventRepository,
        dataStore,
        accountRepository,
        syncScheduler,
        networkMonitor,
        calendarProviderRepository,
        attendeeBackfill,
        ioDispatcher,
        { DayPagerUtils.msToDayCode(System.currentTimeMillis()) }
    )

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Network connectivity state for UI */
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    /** Default reminder for timed events (minutes before) */
    val defaultReminderTimed: StateFlow<Int> = dataStore.defaultReminderMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15)

    /** Default reminder for all-day events (minutes before) */
    val defaultReminderAllDay: StateFlow<Int> = dataStore.defaultAllDayReminder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1440) // 1 day

    /** Default event duration (minutes) */
    val defaultEventDuration: StateFlow<Int> = dataStore.defaultEventDuration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), KashCalDataStore.DEFAULT_EVENT_DURATION_MINUTES)

    /** Quick Add enabled state */
    val quickAddEnabled: StateFlow<Boolean> = dataStore.quickAddEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Suggest prior event titles matching [prefix] for the form autocomplete.
     *
     * Honors the `titleSuggestionsEnabled` user preference: when disabled,
     * returns empty list regardless of history. UI doesn't know about this
     * preference — enforcing it here keeps the composable preference-agnostic.
     */
    suspend fun suggestTitles(prefix: String): List<org.onekash.kashcal.data.db.dao.TitleSuggestion> {
        if (!dataStore.getTitleSuggestionsEnabled()) return emptyList()
        return displayEventRepository.suggestTitles(prefix)
    }

    /** Time format preference: "system", "12h", or "24h" */
    val timeFormat: StateFlow<String> = dataStore.timeFormat
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), KashCalDataStore.TIME_FORMAT_SYSTEM)

    /** First day of week preference: 0=system, 1=Sunday, 2=Monday, 7=Saturday */
    val firstDayOfWeek: StateFlow<Int> = dataStore.firstDayOfWeek
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Calendar.SUNDAY)

    /**
     * Reactive list of pending CalDAV invitations rendered by
     * `InvitationInboxSheet`. Backs both the count Flow below and the
     * sheet's row list, so the badge can never disagree with the sheet.
     */
    val pendingInvitations: StateFlow<List<org.onekash.kashcal.domain.reader.PendingInvitation>> =
        eventReader.getPendingInvitations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Single source of truth for the count of pending CalDAV invitations.
     *
     * The AppBar badge and the Invites overflow menu item both subscribe
     * here so a sync-churn re-emission can never drift the two views
     * apart. `distinctUntilChanged` collapses repeated same-size lists
     * (common when sync writes attendees but the NEEDS-ACTION set is
     * unchanged).
     */
    val pendingInvitationsCount: StateFlow<Int> = pendingInvitations
        .map { it.size }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Attendee chip surfaces.

    private val quickViewEventId = MutableStateFlow<Long?>(null)
    private val formEventId = MutableStateFlow<Long?>(null)
    private val dayVisibleEventIds = MutableStateFlow<List<Long>>(emptyList())

    /** UI projection of attendees for the active QuickView event. Null when no event is active. */
    val quickViewAttendees: StateFlow<EventAttendeeUiState?> =
        quickViewEventId
            .flatMapLatest { id -> if (id == null) flowOf(null) else buildAttendeeFlow(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** UI projection of attendees for the EventFormSheet's read-only chip row. */
    val formAttendees: StateFlow<EventAttendeeUiState?> =
        formEventId
            .flatMapLatest { id -> if (id == null) flowOf(null) else buildAttendeeFlow(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Drives the EventFormSheet's read-only banner + Save-disable gate.
     * True when the editing event has an ORGANIZER that doesn't match the
     * resolving account (single home of the rule via
     * [org.onekash.kashcal.domain.identity.canEditAsOrganizer]).
     */
    @Suppress("OPT_IN_USAGE")
    val formIsReadOnly: StateFlow<Boolean> =
        formEventId
            .flatMapLatest { id ->
                if (id == null) flowOf(false) else flow {
                    val event = eventReader.getEventById(id)
                    val calendar = event?.let { e ->
                        uiState.value.calendars.firstOrNull { it.id == e.calendarId }
                    }
                    val account = calendar?.accountId?.let { accountRepository.getAccountById(it) }
                    emit(
                        event != null && !account.canEditAsOrganizer(event)
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * UI projection map for the day-view chip badges. One Flow per visible-
     * event-set (no per-card subscription); slice by event ID at render time.
     * Each slice carries the per-event resolved account so [AttendeeUiModel.isYou]
     * is correct.
     */
    val dayAttendees: StateFlow<Map<Long, List<AttendeeUiModel>>> =
        dayVisibleEventIds
            .flatMapLatest { ids -> buildDayAttendeesFlow(ids) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun setQuickViewEventId(eventId: Long?) {
        quickViewEventId.value = eventId
    }

    fun setFormEventId(eventId: Long?) {
        formEventId.value = eventId
    }

    fun setVisibleEventIds(ids: List<Long>) {
        dayVisibleEventIds.value = ids
    }

    /**
     * Resolve the active event → calendar → account, run one-shot
     * `rawIcal` backfill (closes the etag-unchanged-skip gap from
     * inbound persistence — when the table is empty but `rawIcal` has
     * ATTENDEE lines, parse + persist), then subscribe to the attendees
     * Flow with the resolved account so [AttendeeUiModel.fromRoom] can
     * mark the current user as `isYou`.
     */
    private fun buildAttendeeFlow(eventId: Long): Flow<EventAttendeeUiState?> = flow {
        val event = eventReader.getEventById(eventId)
        val calendar = event?.let { e ->
            uiState.value.calendars.firstOrNull { it.id == e.calendarId }
        }
        val account = calendar?.accountId?.let { accountRepository.getAccountById(it) }

        try {
            attendeeBackfill.backfillIfEmpty(eventId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "backfillIfEmpty failed for event $eventId: ${e.javaClass.simpleName}")
        }

        emitAll(
            eventReader.getAttendeesForEvent(eventId).map { rows ->
                EventAttendeeUiState(
                    models = AttendeeUiModel.fromRoom(
                        attendees = rows,
                        currentAccount = account,
                        organizerAddress = event?.organizerEmail
                    ),
                    isCurrentUserOnList = AttendeeUiModel.isCurrentUserOnList(
                        rows, account, event?.organizerEmail
                    )
                )
            }
        )
    }

    /**
     * Bulk projection for the day pager. Fans out per-event account/organizer
     * resolution once (synchronous on cached `uiState.calendars` + a small
     * batched `accountsDao` lookup) and then maps every emission of the
     * attendees Flow through [AttendeeUiModel.fromRoom] so the day-card
     * badge can correctly show "Going / Pending / Hosting / off-list" per
     * event without the consumer re-resolving identity.
     */
    private fun buildDayAttendeesFlow(eventIds: List<Long>): Flow<Map<Long, List<AttendeeUiModel>>> = flow {
        if (eventIds.isEmpty()) {
            emit(emptyMap())
            return@flow
        }

        val events = eventReader.getEventsByIds(eventIds)
        val calendarsById = uiState.value.calendars.associateBy { it.id }
        val accountIds = events.values.mapNotNull { calendarsById[it.calendarId]?.accountId }.toSet()
        val accountsById = accountIds
            .mapNotNull { id -> accountRepository.getAccountById(id)?.let { id to it } }
            .toMap()

        emitAll(
            eventReader.getAttendeesForEvents(eventIds).map { rowsByEventId ->
                rowsByEventId.mapValues { (eventId, rows) ->
                    val event = events[eventId]
                    val calendar = event?.let { calendarsById[it.calendarId] }
                    val account = calendar?.accountId?.let { accountsById[it] }
                    AttendeeUiModel.fromRoom(
                        attendees = rows,
                        currentAccount = account,
                        organizerAddress = event?.organizerEmail
                    )
                }
            }
        )
    }

    // Track if startup sync has been triggered
    private var hasTriggeredStartupSync = false

    // Job for search debouncing (cancel previous search when new query arrives)
    private var searchJob: Job? = null

    // Job for on-demand dots loading (cancel previous on fast swipe)
    private var loadDotsJob: Job? = null

    // Job for agenda events observation (cancel previous when reopened)
    // Uses Flow for progressive updates during sync
    private var agendaEventsJob: Job? = null

    // Job for occurrence extension (cancel previous on rapid swipe)
    private var extensionJob: Job? = null
    private var occurrenceRepairDone = false

    // Job for week view events observation (cancel previous when week changes)
    private var weekEventsJob: Job? = null

    // Job for day events cache loading (cancel previous when cache refresh needed)
    private var dayEventsCacheJob: Job? = null

    // Job for month events loading (cancel previous on month swipe)
    private var monthEventsJob: Job? = null

    // Job for debounced day pager loading (cancel previous on fast swipe)
    private var dayPagerLoadJob: Job? = null

    // Job for year dots loading (cancel previous on fast year swipe)
    private var yearDotsJob: Job? = null

    // Track current loaded date range to avoid redundant loads
    private var currentLoadedRange: Pair<LocalDate, LocalDate>? = null

    // Suppress sync indicator for silent syncs (cold start, resume, force full sync with banner)
    // Only pull-to-refresh shows the spinning icon since it's user-initiated
    private var suppressSyncIndicator = false

    // Null until the first resume, so the first resume is record-only —
    // we only snap when we have a prior dayCode to compare against.
    private var lastResumeDayCode: Int? = null

    init {
        Log.d(TAG, "ViewModel init")

        // Set initial viewing state to today
        val today = Calendar.getInstance()
        _uiState.update {
            it.copy(
                viewingMonth = today.get(Calendar.MONTH),
                viewingYear = today.get(Calendar.YEAR)
            )
        }

        // Initialize asynchronously
        viewModelScope.launch {
            initializeAsync()
        }

        // Observe sync status for inline banner
        observeSyncStatus()

        // Observe sync changes for snackbar notification
        observeSyncChanges()

        // Observe display settings
        observeDisplaySettings()

        // Observe device calendar changes to invalidate event dots cache
        observeDeviceCalendarChanges()
    }

    /**
     * Async initialization - Android recommended pattern.
     * Avoids blocking main thread during startup.
     */
    private suspend fun initializeAsync() {
        try {
            Log.d(TAG, "initializeAsync - START")

            // Start observing calendars (reactive Flow - auto-updates when calendars change)
            // Note: Calendar visibility is derived from Calendar.isVisible (DB source of truth)
            observeCalendars()

            // Observe device calendar drawer state (enabled, visible IDs, calendar list)
            observeDeviceCalendarDrawerState()

            // Check if any sync-capable account is configured
            checkAccountStatus()

            // Show onboarding sheet if: not configured AND not dismissed before
            if (!_uiState.value.isConfigured) {
                val dismissed = dataStore.onboardingDismissed.first()
                if (!dismissed) {
                    Log.d(TAG, "Showing onboarding sheet (first launch, no account configured)")
                    _uiState.update { it.copy(showOnboardingSheet = true) }
                }
            }

            // Load persisted view from DataStore before building UI.
            // Seed previousNonInsightsMode from the same persisted default so back-from-Insights
            // (when Insights is the initial view) lands on the user's preferred view, not MONTH.
            // DataStore's VALID_VIEWS rejects "insights", so this seed is guaranteed non-INSIGHTS.
            val defaultView = ViewMode.fromKey(dataStore.getDefaultCalendarView())
            _uiState.update {
                it.copy(viewMode = defaultView, previousNonInsightsMode = defaultView)
            }

            // Load data for the default view
            when (defaultView) {
                ViewMode.AGENDA -> loadAgendaEvents()
                ViewMode.DAY -> {} // goToToday() below handles week initialization
                ViewMode.THREE_DAYS -> {} // goToToday() below handles week initialization
                ViewMode.WEEK -> {} // goToToday() below handles week initialization
                ViewMode.MONTH -> {} // goToToday() below handles dot loading + day selection
                ViewMode.MONTH_FULL -> loadMonthEvents(_uiState.value.viewingYear, _uiState.value.viewingMonth)
                ViewMode.YEAR -> loadYearDots(_uiState.value.viewingYear)
                ViewMode.INSIGHTS -> {}
            }

            // Build event dots for current month ±6 months
            val today = Calendar.getInstance()
            buildEventDots(today.get(Calendar.YEAR), today.get(Calendar.MONTH))

            // Auto-select today (routes to correct view based on viewMode)
            goToToday()

            Log.d(TAG, "initializeAsync - COMPLETE")
        } catch (e: Exception) {
            Log.e(TAG, "initializeAsync FAILED", e)
            _uiState.update {
                it.copy(syncMessage = "Initialization failed: ${e.message}")
            }
        }
    }

    // ==================== Account Status ====================

    /**
     * Check if any sync-capable account is configured and update state.
     * Considers all providers with supportsCalDAV (iCloud, CalDAV).
     */
    private suspend fun checkAccountStatus() {
        val allAccounts = withContext(ioDispatcher) {
            accountRepository.getAllAccounts()
        }
        val syncableAccounts = allAccounts.filter { it.provider.supportsCalDAV }

        val hasConfiguredAccount = syncableAccounts.any { account ->
            withContext(ioDispatcher) { accountRepository.hasCredentials(account.id) }
        }

        _uiState.update {
            it.copy(isConfigured = hasConfiguredAccount)
        }

        if (hasConfiguredAccount) {
            Log.d(TAG, "Account configured (${syncableAccounts.size} syncable accounts)")
        } else {
            Log.d(TAG, "No configured accounts")
        }
    }

    /**
     * Refresh account status (called when returning from settings).
     * Also reloads calendars to pick up any newly discovered calendars.
     */
    fun refreshAccountStatus() {
        viewModelScope.launch {
            checkAccountStatus()

            // Reload calendars to pick up newly discovered calendars
            // (observeCalendars Flow should auto-update, but force refresh for safety)
            loadCalendars()

            if (_uiState.value.isConfigured && !hasTriggeredStartupSync) {
                // First sync after account setup - show banner for user feedback
                hasTriggeredStartupSync = true
                suppressSyncIndicator = true  // Has banner - no spinning icon needed
                syncScheduler.setShowBannerForSync(true)  // Initial setup - user expects confirmation
                Log.d(TAG, "refreshAccountStatus: First sync after account setup (with banner, no icon)")
                performSync()
            }

            // Rebuild event dots with new calendars
            reloadCurrentView()
        }
    }

    // ==================== Startup Sync ====================

    /**
     * Trigger startup sync after UI is ready.
     * Called from Activity's LaunchedEffect to ensure lifecycle is STARTED.
     */
    fun triggerStartupSync() {
        if (!_uiState.value.isConfigured) {
            Log.d(TAG, "triggerStartupSync: Not configured, skipping")
            return
        }
        if (hasTriggeredStartupSync) {
            Log.d(TAG, "triggerStartupSync: Already triggered, skipping")
            return
        }
        hasTriggeredStartupSync = true
        suppressSyncIndicator = true  // Silent cold start - no spinning icon
        syncScheduler.setShowBannerForSync(false)
        Log.d(TAG, "triggerStartupSync: Starting sync (silent, no icon)")
        performSync(SyncTrigger.FOREGROUND_APP_OPEN)
    }

    // ==================== Sync Status Observation ====================

    /**
     * Observe sync status from WorkManager and update banner state.
     *
     * Banner visibility is context-aware (controlled by syncScheduler.showBannerForSync):
     * - Silent syncs (startup, pull-to-refresh): no banner shown
     * - Verbose syncs (force full sync, iCloud setup): full banner shown
     * - Errors: always shown regardless of flag
     */
    private fun observeSyncStatus() {
        viewModelScope.launch {
            syncScheduler.observeImmediateSyncStatus().collect { status ->
                val showBanner = syncScheduler.showBannerForSync.value
                Log.d(TAG, "Sync status changed: $status (showBanner=$showBanner)")
                when (status) {
                    is SyncStatus.Running, is SyncStatus.Enqueued -> {
                        // Only show icon if not suppressed (only pull-to-refresh shows icon)
                        // Only show banner if flag is set (force sync, iCloud setup)
                        _uiState.update {
                            it.copy(
                                isSyncing = !suppressSyncIndicator,
                                showSyncBanner = showBanner,
                                syncBannerState = if (status is SyncStatus.Running)
                                    SyncBannerState.Syncing else SyncBannerState.Preparing,
                                syncErrorDetail = null
                            )
                        }
                    }
                    is SyncStatus.Succeeded -> {
                        suppressSyncIndicator = false  // Reset flag for next sync
                        occurrenceRepairDone = false
                        val hasPartialError = status.errorMessage != null
                        _uiState.update {
                            it.copy(
                                isSyncing = false,
                                showSyncBanner = showBanner || hasPartialError,
                                syncBannerState = if (hasPartialError)
                                    SyncBannerState.PartialError else SyncBannerState.Success,
                                syncErrorDetail = null
                            )
                        }
                        // Reload events after successful sync
                        reloadCurrentView()
                        // Auto-dismiss after delay
                        if (showBanner || hasPartialError) {
                            delay(if (hasPartialError) 3000 else 2000)
                            _uiState.update { it.copy(showSyncBanner = false) }
                            syncScheduler.resetBannerFlag()
                        }
                    }
                    is SyncStatus.Failed -> {
                        suppressSyncIndicator = false  // Reset flag for next sync
                        // Always show errors regardless of flag
                        _uiState.update {
                            it.copy(
                                isSyncing = false,
                                showSyncBanner = true,
                                syncBannerState = SyncBannerState.Error,
                                syncErrorDetail = status.errorMessage
                            )
                        }
                        // Auto-dismiss after 3 seconds
                        delay(3000)
                        _uiState.update { it.copy(showSyncBanner = false) }
                        syncScheduler.resetBannerFlag()
                    }
                    is SyncStatus.Idle, is SyncStatus.Cancelled, is SyncStatus.Blocked -> {
                        suppressSyncIndicator = false  // Reset flag for next sync
                        _uiState.update {
                            it.copy(
                                showSyncBanner = false,
                                isSyncing = false,
                                syncBannerState = SyncBannerState.Syncing  // Reset to avoid stale Error flash
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Observe sync changes from SyncScheduler and show snackbar notification.
     *
     * Shows snackbar for ALL syncs (startup, pull-to-refresh, background) when changes are found.
     * The snackbar includes a "View" action to open the bottom sheet with change details.
     */
    private fun observeSyncChanges() {
        viewModelScope.launch {
            syncScheduler.lastSyncChanges.collect { changes ->
                if (changes.isNotEmpty()) {
                    val message = generateSnackbarMessage(changes)
                    if (message != null) {
                        Log.d(TAG, "Sync changes notification: $message (${changes.size} changes)")
                        // Store changes for bottom sheet
                        _uiState.update { it.copy(syncChanges = changes.toPersistentList()) }
                        // Show snackbar with "View" action
                        showSnackbar(message) {
                            // Open bottom sheet on "View" tap
                            _uiState.update { it.copy(showSyncChangesSheet = true) }
                        }
                    }
                    // Clear after consumed
                    syncScheduler.clearSyncChanges()
                }
            }
        }
    }

    /**
     * Observe display settings preferences.
     * Updates uiState when showEventEmojis, timeFormat, or firstDayOfWeek preferences change.
     */
    private fun observeDisplaySettings() {
        viewModelScope.launch {
            dataStore.showEventEmojis.collect { showEmojis ->
                _uiState.update { it.copy(showEventEmojis = showEmojis) }
            }
        }
        viewModelScope.launch {
            dataStore.timeFormat.collect { format ->
                _uiState.update { it.copy(timeFormat = format) }
            }
        }
        viewModelScope.launch {
            dataStore.firstDayOfWeek.collect { day ->
                _uiState.update { it.copy(firstDayOfWeek = day) }
            }
        }
        viewModelScope.launch {
            dataStore.showWeekNumbers.collect { show ->
                _uiState.update { it.copy(showWeekNumbers = show) }
            }
        }
    }

    /**
     * Observe device calendar changes (ContentObserver signal from CalendarProviderManager).
     * Invalidates event dots cache so dots rebuild with fresh device event data.
     * Day pager, agenda, and week view auto-update via DisplayEventRepository's combine() flows.
     */
    private fun observeDeviceCalendarChanges() {
        viewModelScope.launch {
            displayEventRepository.deviceCalendarChangeSignal
                .collect { signal ->
                    if (signal > 0) {
                        // Clear loaded months so dots rebuild with fresh data
                        _uiState.update {
                            it.copy(
                                loadedMonths = persistentSetOf(),
                                eventDots = persistentMapOf()
                            )
                        }
                        // Rebuild dots for current viewing month
                        buildEventDots(
                            _uiState.value.viewingYear,
                            _uiState.value.viewingMonth
                        )
                    }
                }
        }
    }

    // ==================== Sync Operations ====================

    /**
     * Pull-to-refresh sync.
     */
    fun refreshSync() {
        if (!_uiState.value.isConfigured) {
            Log.d(TAG, "Pull-to-refresh: not configured, showing snackbar")
            showSnackbar("No sync accounts configured")
            return
        }
        if (_uiState.value.isSyncing) {
            Log.d(TAG, "Sync already in progress, ignoring refresh")
            return
        }
        if (!networkMonitor.isOnline.value) {
            Log.d(TAG, "Pull-to-refresh: offline, showing error")
            showError(CalendarError.Network.Offline)
            return
        }
        suppressSyncIndicator = false  // User-initiated - show spinning icon
        syncScheduler.setShowBannerForSync(false)
        Log.d(TAG, "Pull-to-refresh: starting sync (with icon)")
        performSync(SyncTrigger.FOREGROUND_PULL_TO_REFRESH)
    }

    /**
     * Force full sync (clears sync tokens).
     */
    fun forceFullSync() {
        if (_uiState.value.isSyncing) {
            Log.d(TAG, "Sync already in progress, ignoring force sync")
            return
        }
        suppressSyncIndicator = true  // Has banner - no spinning icon needed
        syncScheduler.setShowBannerForSync(true)
        Log.d(TAG, "Force full sync requested (with banner, no icon)")

        // Clear parse failure retry state - force sync gives a fresh start (v16.7.0)
        viewModelScope.launch {
            dataStore.clearAllParseFailureRetries()
        }

        syncScheduler.requestImmediateSync(forceFullSync = true, trigger = SyncTrigger.FOREGROUND_MANUAL)
    }

    /**
     * Sync on app resume if not already syncing.
     * Called from Activity.onResume() for background-to-foreground transitions.
     *
     * No cooldown - syncs every time app resumes because:
     * - Casual users have long gaps (hours) between app opens anyway
     * - The ctag check is lightweight (~50ms) if nothing changed
     * - Shared calendar users need fresh data when returning to app
     */
    fun syncOnResumeIfNeeded() {
        if (!_uiState.value.isConfigured) {
            Log.d(TAG, "syncOnResumeIfNeeded: Not configured, skipping")
            return
        }
        if (_uiState.value.isSyncing) {
            Log.d(TAG, "syncOnResumeIfNeeded: Already syncing, skipping")
            return
        }
        Log.d(TAG, "syncOnResumeIfNeeded: Triggering sync on app resume")
        suppressSyncIndicator = true  // Silent sync - no spinning icon
        syncScheduler.setShowBannerForSync(false)
        performSync(SyncTrigger.FOREGROUND_APP_OPEN)
    }

    /**
     * Perform sync operation.
     *
     * Sets isSyncing=true immediately for duplicate sync guard, then enqueues WorkManager work.
     * All other state updates (isSyncing=false, reloadCurrentView) happen via observeSyncStatus()
     * when WorkManager emits SyncStatus.Succeeded/Failed/etc.
     *
     * @param trigger The sync trigger source for history tracking
     */
    private fun performSync(trigger: SyncTrigger = SyncTrigger.FOREGROUND_MANUAL) {
        if (!_uiState.value.isConfigured) {
            Log.d(TAG, "performSync: Not configured, skipping")
            return
        }

        // Set isSyncing immediately to prevent duplicate sync requests (race condition guard)
        // This closes the window between performSync() and observeSyncStatus() receiving Running status
        // The UI indicator is controlled separately by observeSyncStatus() using suppressSyncIndicator
        _uiState.update { it.copy(isSyncing = true) }

        // Request sync - observeSyncStatus() handles all other state updates
        // including calling reloadCurrentView() when sync succeeds
        Log.d(TAG, "performSync: Requesting immediate sync (trigger=${trigger.name}, showIcon=${!suppressSyncIndicator})")
        syncScheduler.requestImmediateSync(trigger = trigger)
    }

    // ==================== Calendar Loading ====================

    /**
     * Start observing calendars from database (reactive via Flow).
     * Uses EventCoordinator for proper architecture pattern.
     *
     * Default calendar priority:
     * 1. User preference from DataStore (set in Settings)
     * 2. Database is_default column (server-side default)
     * 3. First calendar in list
     */
    private fun observeCalendars() {
        viewModelScope.launch {
            try {
                // Combine calendars, accounts, and user preference
                combine(
                    eventCoordinator.getAllCalendars(),
                    eventCoordinator.getAllAccounts(),
                    dataStore.defaultCalendar
                ) { calendars, accounts, userPrefDefault ->
                    // Validate the default calendar exists
                    val validatedDefault = when (userPrefDefault) {
                        is DefaultCalendar.Room -> {
                            // Validate Room calendar exists
                            if (calendars.any { it.id == userPrefDefault.calendarId }) userPrefDefault
                            else null
                        }
                        is DefaultCalendar.Device -> {
                            // Device calendar validation happens at event creation time
                            // For UI display, just pass through (picker handles availability)
                            userPrefDefault
                        }
                        null -> null
                    }
                    // Group calendars by account for UI display
                    val groups = CalendarGroup.fromCalendarsAndAccounts(calendars, accounts)
                    Triple(calendars, groups, validatedDefault)
                }.collect { (calendars, groups, validatedDefault) ->
                    // Also load device calendars for EventFormSheet picker
                    val deviceCalendars = try {
                        calendarProviderRepository.getDeviceCalendars()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val deviceGroups = CalendarGroup.fromDeviceCalendars(deviceCalendars, writableOnly = true)

                    _uiState.update {
                        it.copy(
                            calendars = calendars.toPersistentList(),
                            calendarGroups = groups.toPersistentList(),
                            deviceCalendarGroups = deviceGroups.toPersistentList(),
                            defaultCalendar = validatedDefault
                        )
                    }
                    Log.d(TAG, "Calendars updated: ${calendars.size} calendars, ${groups.size} groups, ${deviceGroups.size} device groups, default=$validatedDefault")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing calendars", e)
            }
        }
    }

    /**
     * Load all calendars from database (one-shot for manual refresh).
     * Uses same default calendar priority as observeCalendars().
     */
    private fun loadCalendars() {
        viewModelScope.launch {
            try {
                val (calendars, groups, validatedDefault) = withContext(ioDispatcher) {
                    val cals = eventCoordinator.getAllCalendars().first()
                    val accounts = eventCoordinator.getAllAccounts().first()
                    // Get default calendar preference and validate
                    val userPrefDefault = dataStore.getDefaultCalendar()
                    val validDefault = when (userPrefDefault) {
                        is DefaultCalendar.Room -> {
                            if (cals.any { it.id == userPrefDefault.calendarId }) userPrefDefault
                            else null
                        }
                        is DefaultCalendar.Device -> userPrefDefault // Validated at event creation
                        null -> null
                    }
                    // Group calendars by account for UI
                    val calGroups = CalendarGroup.fromCalendarsAndAccounts(cals, accounts)
                    Triple(cals, calGroups, validDefault)
                }
                // Also load device calendars for EventFormSheet picker
                val deviceCalendars = try {
                    calendarProviderRepository.getDeviceCalendars()
                } catch (_: Exception) {
                    emptyList()
                }
                val deviceGroups = CalendarGroup.fromDeviceCalendars(deviceCalendars, writableOnly = true)

                _uiState.update {
                    it.copy(
                        calendars = calendars.toPersistentList(),
                        calendarGroups = groups.toPersistentList(),
                        deviceCalendarGroups = deviceGroups.toPersistentList(),
                        defaultCalendar = validatedDefault
                    )
                }
                Log.d(TAG, "Loaded ${calendars.size} calendars, ${groups.size} groups, ${deviceGroups.size} device groups, default=$validatedDefault")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading calendars", e)
            }
        }
    }

    /**
     * Observe device calendar drawer state: feature enabled, enabled IDs, hidden IDs.
     * Loads device calendar list only when enabled/enabledIds change (ContentResolver query).
     * Hidden IDs updates skip the query since only visibility state changes.
     */
    private fun observeDeviceCalendarDrawerState() {
        // Observe enabled state + enabled IDs — reload calendar list from ContentProvider
        viewModelScope.launch {
            combine(
                dataStore.deviceCalendarsEnabled,
                dataStore.enabledDeviceCalendarIds
            ) { enabled, enabledIds ->
                Pair(enabled, enabledIds)
            }.collect { (enabled, enabledIds) ->
                val deviceCalendars = if (enabled && enabledIds.isNotEmpty()) {
                    try {
                        calendarProviderRepository.getDeviceCalendars()
                            .filter { it.id in enabledIds }
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
                _uiState.update {
                    it.copy(
                        deviceCalendarsEnabled = enabled,
                        enabledDeviceCalendars = deviceCalendars.toPersistentList()
                    )
                }
            }
        }
        // Observe hidden IDs separately — lightweight state update, no ContentProvider query
        viewModelScope.launch {
            dataStore.hiddenDeviceCalendarIds.collect { hiddenIds ->
                _uiState.update {
                    it.copy(hiddenDeviceCalendarIds = hiddenIds.toPersistentSet())
                }
            }
        }
    }

    /**
     * Refresh calendars list.
     */
    fun refreshCalendars() {
        loadCalendars()
    }

    // ==================== Calendar Visibility ====================

    /**
     * Toggle calendar visibility.
     * Uses DB Calendar.isVisible as source of truth.
     */
    fun toggleCalendarVisibility(calendarId: Long) {
        viewModelScope.launch {
            // Get current visibility from calendar entity
            val calendar = _uiState.value.calendars.find { it.id == calendarId }
            val newVisible = !(calendar?.isVisible ?: true)

            // Update DB (source of truth) - UI updates automatically via calendars Flow observation
            eventCoordinator.setCalendarVisibility(calendarId, newVisible)

            // Only rebuild dots (one-shot query needs explicit refresh)
            // Week/agenda/pager/day views are now reactive via combine() - they auto-update
            buildEventDots(_uiState.value.viewingYear, _uiState.value.viewingMonth)
        }
    }

    /**
     * Show all calendars.
     * Uses DB Calendar.isVisible as source of truth.
     */
    fun showAllCalendars() {
        viewModelScope.launch {
            // Update DB for each calendar (source of truth)
            _uiState.value.calendars.forEach { calendar ->
                eventCoordinator.setCalendarVisibility(calendar.id, true)
            }
            // Only rebuild dots (one-shot query needs explicit refresh)
            // Week/agenda/pager/day views are now reactive via combine() - they auto-update
            buildEventDots(_uiState.value.viewingYear, _uiState.value.viewingMonth)
        }
    }

    /**
     * Toggle device calendar visibility in the drawer.
     * Uses hiddenDeviceCalendarIds preference — doesn't affect reminders or enablement.
     */
    fun toggleDeviceCalendarVisibility(calendarId: Long) {
        viewModelScope.launch {
            dataStore.toggleDeviceCalendarHidden(calendarId)
            reloadCurrentView()
        }
    }

    // ==================== Event Dots ====================

    /**
     * Encode year and month into a single integer for range comparison.
     * Format: year * 12 + month (handles year boundaries correctly)
     */
    private fun encodeMonth(year: Int, month: Int): Int = year * 12 + month

    /**
     * Decode encoded month back to year and month.
     */
    private fun decodeMonth(encoded: Int): Pair<Int, Int> = (encoded / 12) to (encoded % 12)

    /**
     * Check if a month has actually loaded dots (not just requested).
     * Uses Set-based tracking to avoid false cache hits from cancelled loads.
     */
    private fun isMonthCached(year: Int, month: Int): Boolean {
        val encoded = encodeMonth(year, month)
        return encoded in _uiState.value.loadedMonths
    }

    /**
     * Ensure dots are loaded for the given month.
     * Loads on-demand if not cached.
     */
    private fun ensureDotsForMonth(year: Int, month: Int) {
        if (!isMonthCached(year, month)) {
            loadDotsForMonth(year, month)
        }
    }

    /**
     * Load dots for a single month (on-demand loading for months beyond initial cache).
     * Cancels previous load if still running (handles fast swipe).
     */
    private fun loadDotsForMonth(year: Int, month: Int) {
        // Cancel previous load if still running (fast swipe scenario)
        loadDotsJob?.cancel()

        loadDotsJob = viewModelScope.launch {
            try {
                // month is 0-indexed (Calendar.MONTH), LocalDate uses 1-indexed
                val firstDay = LocalDate.of(year, month + 1, 1)
                val lastDay = firstDay.withDayOfMonth(firstDay.lengthOfMonth())
                val startDayCode = firstDay.year * 10000 + firstDay.monthValue * 100 + firstDay.dayOfMonth
                val endDayCode = lastDay.year * 10000 + lastDay.monthValue * 100 + lastDay.dayOfMonth

                val eventsMap = withContext(ioDispatcher) {
                    displayEventRepository.getDisplayEventsGroupedByDayOnce(startDayCode, endDayCode)
                }

                val monthKey = String.format(java.util.Locale.ROOT, "%04d-%02d", year, month + 1)
                val monthDots = mutableMapOf<Int, MutableList<Int>>()

                for ((dayCode, events) in eventsMap) {
                    val (_, _, day) = parseDayFormat(dayCode)
                    val dayColors = monthDots.getOrPut(day) { mutableListOf() }
                    for (event in events) {
                        val color = (event.eventColor ?: event.calendarColor).takeIf { it != 0 } ?: 0xFF6200EE.toInt()
                        if (!dayColors.contains(color)) {
                            dayColors.add(color)
                        }
                    }
                }

                // Merge into existing cache
                val currentDots = _uiState.value.eventDots.toMutableMap()
                currentDots[monthKey] = monthDots.mapValues { it.value.toPersistentList() }.toPersistentMap()

                // Mark month as actually loaded (not just requested)
                // This ensures cancelled loads don't falsely mark months as cached
                val loadedMonthEncoded = encodeMonth(year, month)
                _uiState.update {
                    it.copy(
                        eventDots = currentDots.toPersistentMap(),
                        loadedMonths = it.loadedMonths.add(loadedMonthEncoded)
                    )
                }

                Log.d(TAG, "Loaded dots for $year-${month + 1}, total cached months: ${_uiState.value.loadedMonths.size}")
            } catch (e: CancellationException) {
                throw e  // Don't catch cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Error loading dots for month $year-${month + 1}", e)
            }
        }
    }

    /**
     * Build event dots for ±6 months around the given month.
     */
    private fun buildEventDots(year: Int, month: Int) {
        viewModelScope.launch {
            try {
                val dots = mutableMapOf<String, MutableMap<Int, MutableList<Int>>>()

                // Calculate cache range bounds
                val centerEncoded = encodeMonth(year, month)
                val startEncoded = centerEncoded - 6
                val endEncoded = centerEncoded + 6

                // Compute day code range from ±6 months
                val (startYear, startMonth) = decodeMonth(startEncoded)
                val (endYear, endMonth) = decodeMonth(endEncoded)
                val firstDay = LocalDate.of(startYear, startMonth + 1, 1)
                val lastDay = LocalDate.of(endYear, endMonth + 1, 1)
                    .withDayOfMonth(LocalDate.of(endYear, endMonth + 1, 1).lengthOfMonth())
                val startDayCode = firstDay.year * 10000 + firstDay.monthValue * 100 + firstDay.dayOfMonth
                val endDayCode = lastDay.year * 10000 + lastDay.monthValue * 100 + lastDay.dayOfMonth

                // Query merged Room + device events grouped by day
                val eventsMap = withContext(ioDispatcher) {
                    displayEventRepository.getDisplayEventsGroupedByDayOnce(startDayCode, endDayCode)
                }

                // Build dots from pre-grouped events (multi-day expansion already handled)
                for ((dayCode, events) in eventsMap) {
                    val (occYear, occMonth, day) = parseDayFormat(dayCode)
                    val key = String.format(java.util.Locale.ROOT, "%04d-%02d", occYear, occMonth + 1)

                    val monthMap = dots.getOrPut(key) { mutableMapOf() }
                    val dayColors = monthMap.getOrPut(day) { mutableListOf() }
                    for (event in events) {
                        val color = (event.eventColor ?: event.calendarColor).takeIf { it != 0 } ?: 0xFF6200EE.toInt()
                        if (!dayColors.contains(color)) {
                            dayColors.add(color)
                        }
                    }
                }

                // Convert to persistent immutable collections
                val immutableDots = dots.mapValues { (_, monthMap) ->
                    monthMap.mapValues { (_, dayColors) -> dayColors.toPersistentList() }.toPersistentMap()
                }.toPersistentMap()

                // Build set of loaded months (all months in the ±6 range)
                val loadedMonthsSet = (startEncoded..endEncoded)
                    .toSet()
                    .toPersistentSet()

                // Update state with dots and loaded months set
                _uiState.update {
                    it.copy(
                        eventDots = immutableDots,
                        loadedMonths = loadedMonthsSet
                    )
                }

                Log.d(TAG, "Built event dots for ${dots.size} months, loaded ${loadedMonthsSet.size} months: $startYear-${startMonth + 1} to $endYear-${endMonth + 1}")
            } catch (e: CancellationException) {
                throw e  // Don't catch cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Error building event dots", e)
            }
        }
    }

    // ==================== Year View Dots ====================

    /**
     * Load event dots for an entire year (Jan 1 to Dec 31).
     * Cancels previous load if still running (fast-swipe protection).
     * Merges into existing eventDots map (additive, not replacement).
     */
    private fun loadYearDots(year: Int) {
        yearDotsJob?.cancel()

        yearDotsJob = viewModelScope.launch {
            try {
                val startDayCode = year * 10000 + 101   // Jan 1
                val endDayCode = year * 10000 + 1231    // Dec 31

                val eventsMap = withContext(ioDispatcher) {
                    displayEventRepository.getDisplayEventsGroupedByDayOnce(startDayCode, endDayCode)
                }

                val dots = mutableMapOf<String, MutableMap<Int, MutableList<Int>>>()

                for ((dayCode, events) in eventsMap) {
                    val (occYear, occMonth, day) = parseDayFormat(dayCode)
                    val key = String.format(java.util.Locale.ROOT, "%04d-%02d", occYear, occMonth + 1)

                    val monthMap = dots.getOrPut(key) { mutableMapOf() }
                    val dayColors = monthMap.getOrPut(day) { mutableListOf() }
                    for (event in events) {
                        val color = (event.eventColor ?: event.calendarColor).takeIf { it != 0 } ?: 0xFF6200EE.toInt()
                        if (!dayColors.contains(color)) {
                            dayColors.add(color)
                        }
                    }
                }

                // Merge into existing cache (additive — month view dots unaffected)
                val currentDots = _uiState.value.eventDots.toMutableMap()
                for ((key, monthMap) in dots) {
                    currentDots[key] = monthMap.mapValues { it.value.toPersistentList() }.toPersistentMap()
                }

                _uiState.update {
                    it.copy(
                        eventDots = currentDots.toPersistentMap(),
                        loadedYears = it.loadedYears.add(year)
                    )
                }

                Log.d(TAG, "Loaded year dots for $year, ${dots.size} months with events")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading year dots for $year", e)
            }
        }
    }

    /**
     * Ensure dots are loaded for the given year.
     * Loads on-demand if not cached.
     */
    fun ensureDotsForYear(year: Int) {
        if (year !in _uiState.value.loadedYears) {
            loadYearDots(year)
        }
    }

    // ==================== Navigation ====================

    /**
     * Navigate to today and select it.
     * Context-aware: If in 3-day view, navigates week view to today.
     */
    fun goToToday() {
        when (_uiState.value.viewMode) {
            ViewMode.DAY, ViewMode.THREE_DAYS, ViewMode.WEEK -> {
                goToTodayWeek()
            }
            ViewMode.AGENDA -> {
                _uiState.update { it.copy(pendingScrollAgendaToTop = true) }
            }
            ViewMode.MONTH, ViewMode.MONTH_FULL -> {
                val today = Calendar.getInstance()
                val year = today.get(Calendar.YEAR)
                val month = today.get(Calendar.MONTH)

                _uiState.update {
                    it.copy(
                        viewingYear = year,
                        viewingMonth = month,
                        pendingNavigateToToday = true
                    )
                }

                if (_uiState.value.viewMode == ViewMode.MONTH_FULL) {
                    loadMonthEvents(year, month)
                }

                selectDate(today.timeInMillis)
            }
            ViewMode.YEAR -> {
                _uiState.update { it.copy(pendingNavigateToToday = true) }
            }
            ViewMode.INSIGHTS -> {}
        }
    }

    /**
     * Clear the navigate to today flag (consumed by UI).
     */
    fun clearNavigateToToday() {
        _uiState.update { it.copy(pendingNavigateToToday = false) }
    }

    /**
     * Navigate calendar to a specific date.
     * Updates viewing month/year and selects the date.
     * Used by week widget for "go to date" action.
     *
     * @param date The target date to navigate to
     */
    fun navigateToDate(date: LocalDate) {
        // Update viewing month (handles cross-month navigation)
        _uiState.update {
            it.copy(
                viewingYear = date.year,
                viewingMonth = date.monthValue - 1,  // 0-indexed
                pendingNavigateToMonth = date.year to (date.monthValue - 1)
            )
        }

        // Select the date (triggers day events load)
        val dateMs = date.atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        selectDate(dateMs)
    }

    /**
     * Clear the scroll agenda to top flag (consumed by UI).
     */
    fun clearScrollAgendaToTop() {
        _uiState.update { it.copy(pendingScrollAgendaToTop = false) }
    }

    /**
     * Navigate to a specific month.
     */
    fun navigateToMonth(year: Int, month: Int) {
        _uiState.update {
            it.copy(
                viewingYear = year,
                viewingMonth = month,
                pendingNavigateToMonth = year to month,
                showYearOverlay = false  // Auto-dismiss year overlay on month selection
            )
        }

        // Only load if outside cached range (not full rebuild!)
        ensureDotsForMonth(year, month)
    }

    /**
     * Clear the navigate to month flag (consumed by UI).
     */
    fun clearNavigateToMonth() {
        _uiState.update { it.copy(pendingNavigateToMonth = null) }
    }

    /**
     * Set the viewing month/year (called on swipe).
     */
    fun setViewingMonth(year: Int, month: Int) {
        _uiState.update {
            it.copy(
                viewingYear = year,
                viewingMonth = month
            )
        }

        // Load dots if outside cached range (on-demand loading) — skip in MONTH_FULL mode (monthEventsMap has full data)
        if (_uiState.value.viewMode != ViewMode.MONTH_FULL) {
            ensureDotsForMonth(year, month)
        }

        // Load full month events for full-height grid
        if (_uiState.value.viewMode == ViewMode.MONTH_FULL) {
            loadMonthEvents(year, month)
        }

        // Trigger occurrence extension if navigating far into future (debounced)
        triggerOccurrenceExtension(year, month)
    }

    /**
     * Trigger on-demand occurrence extension with debouncing.
     * When user navigates far into the future, extends occurrences for recurring events
     * that don't have occurrences generated that far ahead.
     *
     * Debouncing prevents extension spam when user swipes rapidly through months.
     */
    private fun triggerOccurrenceExtension(year: Int, month: Int) {
        extensionJob?.cancel()
        extensionJob = viewModelScope.launch {
            delay(500L)  // Debounce rapid swipes

            try {
                val targetMs = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, 1)
                }.timeInMillis

                val (forwardExtended, pastExtended, repaired) = withContext(ioDispatcher) {
                    val forward = eventCoordinator.extendOccurrencesIfNeeded(targetMs)
                    val past = eventCoordinator.extendPastOccurrencesIfNeeded(targetMs)
                    val repair = if (!occurrenceRepairDone) {
                        eventCoordinator.repairMissingOccurrences()
                    } else 0
                    Triple(forward, past, repair)
                }

                if (repaired == 0) occurrenceRepairDone = true
                if (forwardExtended > 0 || pastExtended > 0 || repaired > 0) {
                    Log.d(TAG, "Extended occurrences: $forwardExtended forward, $pastExtended past, $repaired repaired (navigated to $year-${month + 1})")
                    loadDotsForMonth(year, month)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extend occurrences: ${e.message}")
            }
        }
    }

    // ==================== Week View Navigation ====================

    /** Navigate backward in the day/week pager (step depends on view mode). */
    fun navigateDaysPagerPrevious() {
        val currentPage = _uiState.value.weekViewPagerPosition
        if (currentPage <= 0) return
        val step = _uiState.value.viewMode.pagerNextStep ?: return
        val targetPage = currentPage - step
        _uiState.update { it.copy(pendingWeekViewPagerPosition = targetPage) }
        onDayPagerPageChanged(targetPage)
    }

    /** Navigate forward in the day/week pager (step depends on view mode). */
    fun navigateDaysPagerNext() {
        val currentPage = _uiState.value.weekViewPagerPosition
        val step = _uiState.value.viewMode.pagerNextStep ?: return
        val targetPage = currentPage + step
        _uiState.update { it.copy(pendingWeekViewPagerPosition = targetPage) }
        onDayPagerPageChanged(targetPage)
    }

    /**
     * Navigate week view to today.
     * Uses CENTER_WEEK_PAGE for WEEK mode, CENTER_DAY_PAGE for THREE_DAYS.
     */
    fun goToTodayWeek() {
        val targetPage = if (_uiState.value.viewMode == ViewMode.WEEK)
            WeekViewUtils.CENTER_WEEK_PAGE else WeekViewUtils.CENTER_DAY_PAGE

        // Clear cached range to force reload
        currentLoadedRange = null

        // Set pending navigation and trigger load
        _uiState.update {
            it.copy(pendingWeekViewPagerPosition = targetPage)
        }
        onDayPagerPageChanged(targetPage)
    }

    /**
     * Load events for the specified week.
     * Cancels any previous load operation (handles fast navigation).
     *
     * Loads both timed events and all-day events separately for proper week view rendering.
     */
    private fun loadEventsForWeek(weekStartMs: Long) {
        // Cancel previous load
        weekEventsJob?.cancel()

        _uiState.update { it.copy(isLoadingWeekView = true, weekViewError = null) }

        // Week end is 7 days later
        val weekEndMs = weekStartMs + (7L * 24 * 60 * 60 * 1000)

        weekEventsJob = viewModelScope.launch {
            try {
                displayEventRepository.getDisplayEventsForRange(weekStartMs, weekEndMs)
                    .collect { displayEvents ->
                        // Separate timed and all-day events
                        val timedEvents = displayEvents
                            .filter { !it.isAllDay }
                            .sortedBy { it.startTs }

                        val allDayEvents = displayEvents
                            .filter { it.isAllDay }
                            .sortedBy { it.startTs }

                        _uiState.update {
                            it.copy(
                                weekViewTimedEvents = timedEvents.toPersistentList(),
                                weekViewAllDayEvents = allDayEvents.toPersistentList(),
                                isLoadingWeekView = false
                            )
                        }

                        Log.d(TAG, "Week view updated: ${timedEvents.size} timed, ${allDayEvents.size} all-day")
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading week events", e)
                _uiState.update {
                    it.copy(
                        isLoadingWeekView = false,
                        weekViewError = "Failed to load events: ${e.message}"
                    )
                }
            }
        }
    }

    // ==================== Infinite Day Pager Functions ====================

    /**
     * Called when the day pager page changes (user swipes or animates).
     * Debounces loading to avoid rapid API calls during fast swipes.
     *
     * @param currentPage The current (leftmost visible) page in the pager
     */
    fun onDayPagerPageChanged(currentPage: Int) {
        // Update pager position immediately for FAB context
        _uiState.update { it.copy(weekViewPagerPosition = currentPage) }

        // Cancel previous debounce job
        dayPagerLoadJob?.cancel()
        dayPagerLoadJob = viewModelScope.launch {
            // Debounce: wait for scroll to settle
            delay(300)

            // Get visible and loading date ranges (week mode uses week pages, day mode uses day pages)
            val isWeekMode = _uiState.value.viewMode == ViewMode.WEEK
            val firstDayOfWeek = _uiState.value.firstDayOfWeek
            val (visibleStart, visibleEnd) = if (isWeekMode) {
                val start = WeekViewUtils.weekPageToStartDate(currentPage, firstDayOfWeek)
                start to start.plusDays(6)
            } else {
                WeekViewUtils.getVisibleDateRange(currentPage)
            }
            val (loadStart, loadEnd) = if (isWeekMode) {
                // Load current week + 1 week buffer on each side
                val start = WeekViewUtils.weekPageToStartDate(currentPage, firstDayOfWeek)
                start.minusDays(7) to start.plusDays(13)
            } else {
                WeekViewUtils.getLoadingDateRange(currentPage)
            }

            // Skip if range already loaded
            currentLoadedRange?.let { (loadedStart, loadedEnd) ->
                if (visibleStart >= loadedStart && visibleEnd <= loadedEnd) {
                    Log.d(TAG, "Day pager: range already loaded, skipping")
                    return@launch
                }
            }

            // Load events for new range
            Log.d(TAG, "Day pager: loading range $loadStart to $loadEnd")
            loadEventsForDateRange(loadStart, loadEnd)
            currentLoadedRange = loadStart to loadEnd
        }
    }

    /**
     * Load events for a date range (used by infinite day pager).
     * More flexible than loadEventsForWeek - accepts any date range.
     *
     * @param startDate First day to load (inclusive)
     * @param endDate Last day to load (inclusive)
     */
    private fun loadEventsForDateRange(startDate: LocalDate, endDate: LocalDate) {
        // Cancel previous load
        weekEventsJob?.cancel()

        val startMs = WeekViewUtils.dateToEpochMs(startDate)
        val endMs = WeekViewUtils.dateToEpochMs(endDate.plusDays(1)) // exclusive end

        _uiState.update { it.copy(isLoadingWeekView = true, weekViewError = null) }

        weekEventsJob = viewModelScope.launch {
            try {
                displayEventRepository.getDisplayEventsForRange(startMs, endMs)
                    .collect { displayEvents ->
                        // Separate timed and all-day events
                        val timedEvents = displayEvents
                            .filter { !it.isAllDay }
                            .sortedBy { it.startTs }

                        val allDayEvents = displayEvents
                            .filter { it.isAllDay }
                            .sortedBy { it.startTs }

                        _uiState.update {
                            it.copy(
                                weekViewTimedEvents = timedEvents.toPersistentList(),
                                weekViewAllDayEvents = allDayEvents.toPersistentList(),
                                isLoadingWeekView = false
                            )
                        }

                        Log.d(TAG, "Day pager updated: ${timedEvents.size} timed, ${allDayEvents.size} all-day")
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading events for date range", e)
                _uiState.update {
                    it.copy(
                        isLoadingWeekView = false,
                        weekViewError = "Failed to load events: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Navigate infinite day pager to today (CENTER_DAY_PAGE).
     * Returns the target page for the pager to scroll to.
     */
    fun goToTodayInDayPager(): Int {
        val targetPage = if (_uiState.value.viewMode == ViewMode.WEEK)
            WeekViewUtils.CENTER_WEEK_PAGE else WeekViewUtils.CENTER_DAY_PAGE

        // Clear cached range to force reload
        currentLoadedRange = null

        // Trigger immediate load for today's range
        onDayPagerPageChanged(targetPage)

        return targetPage
    }

    /**
     * Navigate infinite day pager to a specific date.
     * Returns the target page for the pager to scroll to.
     *
     * @param dateMs Date in epoch milliseconds
     */
    fun navigateDayPagerToDate(dateMs: Long): Int {
        val date = WeekViewUtils.epochMsToDate(dateMs)
        val targetPage = if (_uiState.value.viewMode == ViewMode.WEEK)
            WeekViewUtils.dateToWeekPage(date, _uiState.value.firstDayOfWeek)
        else WeekViewUtils.dateToPage(date)

        // Clear cached range to force reload
        currentLoadedRange = null

        // Trigger immediate load
        onDayPagerPageChanged(targetPage)

        return targetPage
    }

    /**
     * Save week view scroll position for state preservation.
     */
    fun setWeekViewScrollPosition(position: Int) {
        _uiState.update { it.copy(weekViewScrollPosition = position) }
    }

    fun setWeekViewHourHeight(height: Float) {
        _uiState.update { it.copy(weekViewHourHeight = height.coerceIn(WeekViewUtils.MIN_HOUR_HEIGHT_DP, WeekViewUtils.MAX_HOUR_HEIGHT_DP)) }
    }

    /**
     * Save week view pager position for context-aware FAB.
     */
    fun setWeekViewPagerPosition(position: Int) {
        _uiState.update { it.copy(weekViewPagerPosition = position) }
    }

    /**
     * Show week view date picker dialog.
     */
    fun showWeekViewDatePicker() {
        _uiState.update { it.copy(showWeekViewDatePicker = true) }
    }

    /**
     * Hide week view date picker dialog.
     */
    fun hideWeekViewDatePicker() {
        _uiState.update { it.copy(showWeekViewDatePicker = false) }
    }

    /**
     * Handle date selection from week view date picker.
     * Navigates the infinite day pager to the selected date.
     */
    fun onWeekViewDateSelected(dateMs: Long) {
        hideWeekViewDatePicker()

        // Convert date to page in infinite pager (mode-aware)
        val date = WeekViewUtils.epochMsToDate(dateMs)
        val targetPage = if (_uiState.value.viewMode == ViewMode.WEEK)
            WeekViewUtils.dateToWeekPage(date, _uiState.value.firstDayOfWeek)
        else WeekViewUtils.dateToPage(date)

        // Clear cached range to force reload
        currentLoadedRange = null

        // Set pending navigation and trigger load
        _uiState.update {
            it.copy(pendingWeekViewPagerPosition = targetPage)
        }
        onDayPagerPageChanged(targetPage)
    }

    /**
     * Clear pending pager position after it has been consumed by the UI.
     */
    fun clearPendingWeekViewPagerPosition() {
        _uiState.update { it.copy(pendingWeekViewPagerPosition = null) }
    }

    // ==================== Day Selection ====================

    /**
     * Select a date and load its events.
     */
    fun selectDate(dateMillis: Long) {
        _uiState.update {
            it.copy(
                selectedDate = dateMillis,
                selectedDayLabel = formatDateLabel(dateMillis)
            )
        }
    }

    // ==================== Day Detail Sheet ====================

    fun showDayDetail(dateMs: Long) {
        _uiState.update {
            it.copy(showDayDetailSheet = true, dayDetailDate = dateMs)
        }
    }

    fun dismissDayDetail() {
        _uiState.update {
            it.copy(showDayDetailSheet = false, dayDetailDate = 0L)
        }
    }

    // ==================== Day Pager Cache ====================

    /**
     * Load events for a 7-day range centered on the given date.
     * Used by the day swipe pager for smooth scrolling.
     *
     * Groups events by dayCode for O(1) lookup per page.
     * Uses Flow for reactive updates when events change.
     *
     * @param centerDateMs Center date of the range (epoch millis)
     */
    fun loadEventsForDayPagerRange(centerDateMs: Long) {
        dayEventsCacheJob?.cancel()

        Log.d(TAG, "Day pager cache: loading range centered on ${DayPagerUtils.msToDayCode(centerDateMs)}")

        dayEventsCacheJob = viewModelScope.launch {
            try {
                // DisplayEventRepository merges Room + device calendar events,
                // handles multi-day expansion, grouping by dayCode, and sorting
                displayEventRepository.getDisplayEventsForDayRange(centerDateMs)
                    .collect { grouped ->
                        // Track which dayCodes were loaded (even if empty)
                        val loadedCodes = (-3..3).map { offset ->
                            DayPagerUtils.msToDayCode(centerDateMs + (offset * DayPagerUtils.DAY_MS))
                        }.toPersistentSet()

                        _uiState.update {
                            it.copy(
                                dayEventsCache = grouped,
                                cacheRangeCenter = centerDateMs,
                                loadedDayCodes = loadedCodes
                            )
                        }

                        Log.d(TAG, "Day pager cache: loaded events across ${grouped.size} days")
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading day pager cache", e)
            }
        }
    }

    // ==================== Month Events (Full-Height Grid) ====================

    /**
     * Load events for the month grid covering viewing month +/- 1 month.
     * Uses Flow for reactive updates when events change during sync.
     *
     * The 3-month range ensures adjacent HorizontalPager pages have event data
     * during mid-swipe transitions.
     *
     * @param year Viewing year
     * @param month Viewing month (0-indexed, January = 0)
     */
    private fun loadMonthEvents(year: Int, month: Int) {
        monthEventsJob?.cancel()

        Log.d(TAG, "Month events: loading 3-month range centered on $year-${month + 1}")

        monthEventsJob = viewModelScope.launch {
            try {
                // Compute 3-month range via LocalDate arithmetic
                // Previous month first day minus max InDate offset (6 days)
                val prevMonth = LocalDate.of(year, month + 1, 1).minusMonths(1)
                val startDate = prevMonth.withDayOfMonth(1).minusDays(6)
                // Next month last day plus max OutDate offset (13 days)
                val nextMonth = LocalDate.of(year, month + 1, 1).plusMonths(1)
                val endDate = nextMonth.withDayOfMonth(nextMonth.lengthOfMonth()).plusDays(13)

                val startDayCode = startDate.year * 10000 + startDate.monthValue * 100 + startDate.dayOfMonth
                val endDayCode = endDate.year * 10000 + endDate.monthValue * 100 + endDate.dayOfMonth

                displayEventRepository.getDisplayEventsForDateRange(startDayCode, endDayCode)
                    .collect { grouped ->
                        _uiState.update {
                            it.copy(monthEventsMap = grouped)
                        }
                        Log.d(TAG, "Month events: loaded events across ${grouped.size} days")
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading month events", e)
            }
        }
    }

    /**
     * Check if the day pager cache needs to be refreshed.
     *
     * Returns true if:
     * - Cache is empty (cacheRangeCenter == 0)
     * - Current date is more than 1 day from cache center
     *
     * @param currentDateMs Current page date (epoch millis)
     * @return true if cache should be refreshed
     */
    fun shouldRefreshDayPagerCache(currentDateMs: Long): Boolean {
        val cacheCenter = _uiState.value.cacheRangeCenter
        if (cacheCenter == 0L) return true

        val distanceFromCenter = kotlin.math.abs(currentDateMs - cacheCenter)
        // Refresh when more than 1 day from center (leaves 2-day buffer on each side)
        return distanceFromCenter > DayPagerUtils.DAY_MS
    }

    /**
     * Format date for display (e.g., "December 17, 2024").
     */
    private fun formatDateLabel(dateMillis: Long): String {
        val format = SimpleDateFormat(DateTimeUtils.localizedPattern("yMMMMd"), Locale.getDefault())
        return format.format(dateMillis)
    }

    // ==================== Search ====================

    /**
     * Activate search mode.
     */
    fun activateSearch() {
        _uiState.update {
            it.copy(
                isSearchActive = true,
                searchQuery = "",
                searchResults = persistentListOf(),
                searchDateFilter = DateFilter.Upcoming,
                showSearchDatePicker = false,
                searchDateRangeStart = null
            )
        }
    }

    /**
     * Deactivate search mode.
     * Resets all search state including date filter.
     */
    fun deactivateSearch() {
        _uiState.update {
            it.copy(
                isSearchActive = false,
                searchQuery = "",
                searchResults = persistentListOf(),
                searchDateFilter = DateFilter.Upcoming,
                showSearchDatePicker = false,
                searchDateRangeStart = null
            )
        }
    }

    /**
     * Update search query with debouncing.
     * Cancels any pending search and waits 300ms before executing.
     */
    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }

        // Cancel any pending search
        searchJob?.cancel()

        if (query.length >= 2) {
            searchJob = viewModelScope.launch {
                delay(300)  // 300ms debounce
                performSearch(query)
            }
        } else {
            _uiState.update { it.copy(searchResults = persistentListOf()) }
        }
    }

    // ==================== Search Date Filter ====================

    /**
     * Set the search date filter and re-run search.
     * Called when user taps a filter chip or selects a date from picker.
     */
    fun setSearchDateFilter(filter: DateFilter) {
        _uiState.update {
            it.copy(
                searchDateFilter = filter,
                showSearchDatePicker = false,  // Auto-dismiss picker on selection
                searchDateRangeStart = null    // Reset range selection
            )
        }

        // Re-run search with new filter
        if (_uiState.value.searchQuery.length >= 2) {
            performSearch(_uiState.value.searchQuery)
        }
    }

    /**
     * Show the search date picker bottom sheet.
     */
    fun showSearchDatePicker() {
        _uiState.update {
            it.copy(
                showSearchDatePicker = true,
                searchDateRangeStart = null  // Reset range selection when opening
            )
        }
    }

    /**
     * Hide the search date picker bottom sheet.
     */
    fun hideSearchDatePicker() {
        _uiState.update {
            it.copy(
                showSearchDatePicker = false,
                searchDateRangeStart = null  // Reset range selection
            )
        }
    }

    /**
     * Handle date selection in the search date picker.
     *
     * Implements single-tap / double-tap behavior for date selection:
     * - First tap: Stores date as range start
     * - Second tap on same date: Creates SingleDay filter
     * - Second tap on different date: Creates CustomRange filter
     *
     * @param dateMs Selected date in epoch milliseconds
     */
    fun onSearchDateSelected(dateMs: Long) {
        val rangeStart = _uiState.value.searchDateRangeStart

        if (rangeStart == null) {
            // First tap - store as range start
            _uiState.update { it.copy(searchDateRangeStart = dateMs) }
        } else {
            // Second tap - determine if single day or range
            val normalizedStart = normalizeToMidnight(rangeStart)
            val normalizedEnd = normalizeToMidnight(dateMs)

            val filter = if (normalizedStart == normalizedEnd) {
                // Same day - single day filter
                DateFilter.SingleDay(dateMs)
            } else {
                // Different days - create range (ensure start <= end)
                val (start, end) = if (normalizedStart <= normalizedEnd) {
                    normalizedStart to normalizedEnd
                } else {
                    normalizedEnd to normalizedStart
                }
                DateFilter.CustomRange(start, end)
            }

            setSearchDateFilter(filter)
        }
    }

    /**
     * Normalize timestamp to midnight (start of day) in system timezone.
     */
    private fun normalizeToMidnight(epochMs: Long): Long {
        val instant = Instant.ofEpochMilli(epochMs)
        val localDate = instant.atZone(ZoneId.systemDefault()).toLocalDate()
        return localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /**
     * Perform search query.
     *
     * Uses occurrences table for time filtering (Android's recommended approach).
     * An event is included if it has ANY occurrence that hasn't ended yet.
     * This correctly handles multi-day events in progress and recurring events.
     *
     * When a date filter is active, uses searchEventsInRange() to combine FTS
     * text matching with occurrence date range filtering.
     */
    private fun performSearch(query: String) {
        viewModelScope.launch {
            try {
                val dateFilter = _uiState.value.searchDateFilter
                val timeRange = dateFilter.getTimeRange(ZoneId.systemDefault(), _uiState.value.firstDayOfWeek)
                val calendarMap = _uiState.value.calendars.associateBy { it.id }

                // Compute day code range for device calendar search
                val today = LocalDate.now()
                val todayCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
                val (searchStartDayCode, searchEndDayCode) = when {
                    timeRange != null -> {
                        DayPagerUtils.msToDayCode(timeRange.first) to DayPagerUtils.msToDayCode(timeRange.second)
                    }
                    dateFilter is DateFilter.AnyTime -> {
                        val syncPastDays = dataStore.syncPastDays.first()
                        val pastDate = if (syncPastDays == Int.MAX_VALUE) {
                            today.minusYears(10)  // Practical upper bound for device calendar
                        } else {
                            today.minusDays(syncPastDays.toLong())
                        }
                        val futureDate = today.plusYears(2)
                        (pastDate.year * 10000 + pastDate.monthValue * 100 + pastDate.dayOfMonth) to
                            (futureDate.year * 10000 + futureDate.monthValue * 100 + futureDate.dayOfMonth)
                    }
                    else -> {
                        val futureDate = today.plusYears(2)
                        todayCode to (futureDate.year * 10000 + futureDate.monthValue * 100 + futureDate.dayOfMonth)
                    }
                }

                // Room search lambda: wraps EventReader methods, converts to SearchResult
                val roomSearcher: suspend (String) -> List<SearchResult> = { q ->
                    val ewnoResults = when {
                        timeRange != null -> eventReader.searchEventsInRangeWithNextOccurrence(q, timeRange.first, timeRange.second)
                        dateFilter is DateFilter.AnyTime -> eventReader.searchEventsWithNextOccurrence(q)
                        else -> eventReader.searchEventsExcludingPastWithNextOccurrence(q)
                    }
                    ewnoResults.map { ewno ->
                        val event = ewno.event
                        val calendar = calendarMap[event.calendarId]
                        val syntheticOcc = Occurrence(
                            eventId = event.id,
                            calendarId = event.calendarId,
                            startTs = event.startTs,
                            endTs = event.endTs,
                            startDay = DateTimeUtils.eventTsToDayCode(event.startTs, event.isAllDay),
                            endDay = DateTimeUtils.eventTsToEndDayCode(
                                endTs = event.endTs,
                                startTs = event.startTs,
                                isAllDay = event.isAllDay
                            ),
                            isCancelled = false,
                            exceptionEventId = null
                        )
                        SearchResult(
                            displayEvent = DisplayEvent.Room(event, syntheticOcc, calendar),
                            displayTs = ewno.nextOccurrenceTs ?: event.startTs
                        )
                    }
                }

                // Merge Room + device results via DisplayEventRepository
                val results = withContext(ioDispatcher) {
                    displayEventRepository.searchDisplayEvents(
                        query, searchStartDayCode, searchEndDayCode, roomSearcher
                    )
                }

                // Filter by visible calendars (using Calendar.isVisible as source of truth)
                val visibleCalendarIds = _uiState.value.calendars
                    .filter { it.isVisible }
                    .map { it.id }
                    .toSet()
                val filteredResults = results.filter { result ->
                    when (val de = result.displayEvent) {
                        is DisplayEvent.Room -> de.event.calendarId in visibleCalendarIds
                        is DisplayEvent.Device -> true // already filtered by CalendarProviderRepository
                    }
                }

                _uiState.update { it.copy(searchResults = filteredResults.toPersistentList()) }

                Log.d(TAG, "Search '$query' returned ${filteredResults.size} results (filter=${dateFilter::class.simpleName})")
            } catch (e: Exception) {
                Log.e(TAG, "Search error", e)
            }
        }
    }

    // ==================== Agenda ====================

    /**
     * Observe agenda events - upcoming 30 days using reactive Flow.
     * Merges Room + device calendar events via DisplayEventRepository.
     * Each recurring event instance is shown separately.
     *
     * PROGRESSIVE LOADING: Events appear as they sync because this uses Flow
     * collection. DisplayEventRepository combines Room Flow + changeSignal.
     */
    private fun loadAgendaEvents() {
        // Cancel any previous agenda observation
        agendaEventsJob?.cancel()

        _uiState.update { it.copy(isLoadingAgenda = true) }

        val now = System.currentTimeMillis()
        val oneMonthLater = now + (30L * 24 * 60 * 60 * 1000) // 30 days

        // DisplayEventRepository merges Room + device calendar events,
        // sorted by startTs, with SecurityException fallback to Room-only
        agendaEventsJob = viewModelScope.launch {
            try {
                displayEventRepository.getDisplayEventsForRange(now, oneMonthLater)
                    .collect { displayEvents ->
                        _uiState.update {
                            it.copy(
                                agendaEvents = displayEvents,
                                isLoadingAgenda = false
                            )
                        }
                        Log.d(TAG, "Agenda updated: ${displayEvents.size} events")
                    }
            } catch (e: CancellationException) {
                // Normal cancellation when panel closes - don't log as error
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error observing agenda", e)
                _uiState.update { it.copy(isLoadingAgenda = false) }
            }
        }
    }

    // ==================== UI Sheets/Dialogs ====================

    fun toggleAppInfoSheet() {
        _uiState.update { it.copy(showAppInfoSheet = !it.showAppInfoSheet) }
    }

    fun openInvitationInbox() {
        _uiState.update { it.copy(isInvitationInboxOpen = true) }
    }

    fun dismissInvitationInbox() {
        _uiState.update { it.copy(isInvitationInboxOpen = false) }
    }

    fun toggleOnboardingSheet() {
        _uiState.update { it.copy(showOnboardingSheet = !it.showOnboardingSheet) }
    }

    fun dismissOnboardingSheet() {
        _uiState.update { it.copy(showOnboardingSheet = false) }
        viewModelScope.launch {
            dataStore.setOnboardingDismissed(true)
        }
    }

    fun toggleSyncChangesSheet() {
        _uiState.update { it.copy(showSyncChangesSheet = !it.showSyncChangesSheet) }
    }

    /**
     * Dismiss sync changes bottom sheet and clear sync changes.
     */
    fun dismissSyncChangesSheet() {
        _uiState.update {
            it.copy(
                showSyncChangesSheet = false,
                syncChanges = persistentListOf()
            )
        }
    }

    /**
     * Switch calendar view mode and persist as default.
     * Handles data loading for each view type and cancels unnecessary jobs.
     */
    fun setViewMode(mode: ViewMode) {
        val oldMode = _uiState.value.viewMode
        if (oldMode == mode) return

        _uiState.update {
            if (mode == ViewMode.INSIGHTS) {
                it.copy(viewMode = mode)
            } else {
                it.copy(viewMode = mode, previousNonInsightsMode = mode)
            }
        }

        if (mode == ViewMode.INSIGHTS) return

        // Best-effort persistence; a DataStore setter throw must never crash Looper.main.
        viewModelScope.launch {
            try {
                dataStore.setDefaultCalendarView(mode.key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to persist view mode ${mode.key}", e)
            }
        }

        when (mode) {
            ViewMode.AGENDA -> loadAgendaEvents()
            ViewMode.DAY, ViewMode.THREE_DAYS, ViewMode.WEEK -> {
                // Cancel agenda observation since we're leaving agenda view
                agendaEventsJob?.cancel()
                if (currentLoadedRange == null) {
                    goToTodayWeek()
                }
            }
            ViewMode.MONTH -> {
                agendaEventsJob?.cancel()
                syncPagerToSelectedDate()
            }
            ViewMode.MONTH_FULL -> {
                agendaEventsJob?.cancel()
                syncPagerToSelectedDate()
                loadMonthEvents(_uiState.value.viewingYear, _uiState.value.viewingMonth)
            }
            ViewMode.YEAR -> {
                agendaEventsJob?.cancel()
                weekEventsJob?.cancel()
                loadYearDots(_uiState.value.viewingYear)
            }
            ViewMode.INSIGHTS -> {}
        }
    }

    /**
     * Sync the month pager to match selectedDate's month on view switch.
     * Prevents flicker when the user browsed to a different month in THREE_DAYS/WEEK
     * view, then switches back to MONTH.
     */
    private fun syncPagerToSelectedDate() {
        val state = _uiState.value
        val selectedCal = Calendar.getInstance().apply { timeInMillis = state.selectedDate }
        val year = selectedCal.get(Calendar.YEAR)
        val month = selectedCal.get(Calendar.MONTH)
        if (year != state.viewingYear || month != state.viewingMonth) {
            navigateToMonth(year, month)
        }
    }


    fun toggleYearOverlay() {
        _uiState.update { it.copy(showYearOverlay = !it.showYearOverlay) }
    }

    // ==================== Snackbar ====================

    /**
     * Show a snackbar message.
     * Internal visibility for testing.
     */
    internal fun showSnackbar(message: String, action: (() -> Unit)? = null) {
        _uiState.update {
            it.copy(
                pendingSnackbarMessage = message,
                pendingSnackbarAction = action
            )
        }
    }

    /**
     * Clear the snackbar (consumed by UI).
     */
    fun clearSnackbar() {
        _uiState.update {
            it.copy(
                pendingSnackbarMessage = null,
                pendingSnackbarAction = null
            )
        }
    }

    // ==================== Pending Actions (from intents) ====================

    /**
     * Set a pending action to be processed by the UI.
     * Called from Activity's handleIncomingIntent() when notification/widget/shortcut tapped.
     *
     * This follows Android's recommended pattern for UI events:
     * - Convert events to state (not Channels)
     * - ViewModel owns state, UI observes via LaunchedEffect
     * - Clear after consumption (one-shot behavior)
     *
     * @param action The pending action to set
     * @see <a href="https://developer.android.com/topic/architecture/ui-layer/events">UI events</a>
     */
    fun setPendingAction(action: PendingAction) {
        Log.d(TAG, "setPendingAction: $action")
        _uiState.update { it.copy(pendingAction = action) }
    }

    /**
     * Clear the pending action after it's been processed by the UI.
     * Called by UI (LaunchedEffect) after handling the action.
     */
    fun clearPendingAction() {
        Log.d(TAG, "clearPendingAction")
        _uiState.update { it.copy(pendingAction = null) }
    }

    // ==================== Refresh ====================

    /**
     * Handle app resume from background. Snaps to today if the calendar day
     * has rolled over since the previous resume, then reloads events for
     * THREE_DAYS/WEEK views (other views use Room Flow and auto-emit).
     */
    fun onAppResume() {
        val currentDayCode = currentDayCodeProvider()
        val previous = lastResumeDayCode
        if (previous != null && previous != currentDayCode) {
            goToToday()
        }
        lastResumeDayCode = currentDayCode

        if (_uiState.value.viewMode.isTimeGrid && _uiState.value.weekViewStartDate != 0L) {
            loadEventsForWeek(_uiState.value.weekViewStartDate)
        }
    }

    /**
     * Reload the current view (dots, day pager cache, and active view).
     *
     * Called for explicit refresh scenarios like:
     * - Calendar visibility toggle
     * - Event CRUD operations
     * - Sync completion
     */
    private fun reloadCurrentView() {
        buildEventDots(_uiState.value.viewingYear, _uiState.value.viewingMonth)
        // Reload month events if full-height month view is active
        if (_uiState.value.viewMode == ViewMode.MONTH_FULL) {
            loadMonthEvents(_uiState.value.viewingYear, _uiState.value.viewingMonth)
        }
        // Reload day pager cache — skip in MONTH_FULL mode (uses monthEventsMap instead)
        if (_uiState.value.viewMode != ViewMode.MONTH_FULL && _uiState.value.cacheRangeCenter != 0L) {
            loadEventsForDayPagerRange(_uiState.value.cacheRangeCenter)
        }
        // Reload agenda if agenda view is active
        if (_uiState.value.viewMode == ViewMode.AGENDA) {
            loadAgendaEvents()
        }
        // Also reload week view if a time-grid view is active
        if (_uiState.value.viewMode.isTimeGrid && _uiState.value.weekViewStartDate != 0L) {
            loadEventsForWeek(_uiState.value.weekViewStartDate)
        }
        // Reload year dots if year view is active
        if (_uiState.value.viewMode == ViewMode.YEAR) {
            loadYearDots(_uiState.value.viewingYear)
        }
    }

    // ==================== Event CRUD Operations ====================

    /**
     * Get event by ID for editing.
     */
    suspend fun getEventForEdit(eventId: Long): org.onekash.kashcal.data.db.entity.Event? {
        return withContext(ioDispatcher) {
            eventCoordinator.getEventById(eventId)
        }
    }

    /**
     * Get device event for quick view from widget tap.
     *
     * Queries CalendarProvider for instances on the day of occurrenceTs,
     * then finds the instance matching eventId and startTs.
     *
     * @param eventId CalendarProvider event ID
     * @param occurrenceTs Timestamp of the specific occurrence
     * @return DisplayEvent.Device if found, null otherwise
     */
    suspend fun getDeviceEventForQuickView(eventId: Long, occurrenceTs: Long): DisplayEvent.Device? {
        return withContext(ioDispatcher) {
            try {
                // Compute day codes for both timed and all-day interpretations.
                // All-day events use UTC midnight timestamps, which in negative UTC offsets
                // map to the previous local day when interpreted as timed (isAllDay=false).
                // Query both possible days to handle either case in a single call.
                val timedDayCode = DateTimeUtils.eventTsToDayCode(occurrenceTs, isAllDay = false)
                val allDayDayCode = DateTimeUtils.eventTsToDayCode(occurrenceTs, isAllDay = true)
                val startDay = minOf(timedDayCode, allDayDayCode)
                val endDay = maxOf(timedDayCode, allDayDayCode)

                val eventsMap = displayEventRepository.getDisplayEventsGroupedByDayOnce(startDay, endDay)

                eventsMap.values.flatten()
                    .filterIsInstance<DisplayEvent.Device>()
                    .find { it.instance.eventId == eventId && it.startTs == occurrenceTs }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get device event for quick view: eventId=$eventId, occurrenceTs=$occurrenceTs", e)
                null
            }
        }
    }

    /**
     * Save event from form state.
     * Creates new event or updates existing one.
     *
     * @param formState The form state with event data
     * @return Result containing the created/updated event or error
     */
    suspend fun saveEvent(formState: EventFormState): Result<org.onekash.kashcal.data.db.entity.Event> {
        return withContext(ioDispatcher) {
            try {
                // Calculate timestamps from form state
                // CRITICAL: All-day events must be stored as UTC midnight for consistency
                // with iCal/CalDAV parsing. The UI date picker returns local time, so we
                // convert to UTC for all-day events.
                val (startTs, endTs) = if (formState.isAllDay) {
                    // All-day: Convert local date to UTC midnight
                    val startUtc = DateTimeUtils.localDateToUtcMidnight(formState.dateMillis)
                    val endUtc = DateTimeUtils.localDateToUtcMidnight(formState.endDateMillis)
                    // For all-day events, endTs should be end of the last day (23:59:59.999 UTC)
                    startUtc to DateTimeUtils.utcMidnightToEndOfDay(endUtc)
                } else {
                    // Timed: Use selected timezone (or device default)
                    // CRITICAL: The time picker shows hours/minutes in the SELECTED timezone,
                    // so we must interpret them in that timezone when calculating the UTC timestamp.
                    val selectedTz = formState.timezone?.let {
                        java.util.TimeZone.getTimeZone(it)
                    } ?: java.util.TimeZone.getDefault()

                    val startCalendar = Calendar.getInstance(selectedTz).apply {
                        timeInMillis = formState.dateMillis
                        set(Calendar.HOUR_OF_DAY, formState.startHour)
                        set(Calendar.MINUTE, formState.startMinute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val endCalendar = Calendar.getInstance(selectedTz).apply {
                        timeInMillis = formState.endDateMillis
                        set(Calendar.HOUR_OF_DAY, formState.endHour)
                        set(Calendar.MINUTE, formState.endMinute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    startCalendar.timeInMillis to endCalendar.timeInMillis
                }

                // Build reminders list
                val reminders = buildRemindersList(formState.reminders)

                // Get calendar ID (use local if not specified)
                val calendarId = formState.selectedCalendarId
                    ?: eventCoordinator.getLocalCalendarId()

                // Create or update event
                val savedEvent = if (formState.editingOccurrenceTs != null && formState.editingEventId != null) {
                    // Editing a single occurrence of a recurring event - create exception
                    // DEFENSIVE CHECK: If caller passed exception ID, resolve to master ID
                    // This handles edge cases where MainActivity fix wasn't applied
                    val editingEvent = eventCoordinator.getEventById(formState.editingEventId)
                    val masterEventId = editingEvent?.originalEventId ?: formState.editingEventId
                    eventCoordinator.editSingleOccurrence(
                        masterEventId = masterEventId,
                        occurrenceTimeMs = formState.editingOccurrenceTs,
                        changes = { masterEvent ->
                            masterEvent.copy(
                                title = formState.title.ifBlank { "Untitled" },
                                startTs = startTs,
                                endTs = endTs,
                                isAllDay = formState.isAllDay,
                                location = formState.location.ifBlank { null },
                                description = formState.description.ifBlank { null },
                                rrule = null, // Exception events don't have RRULE
                                reminders = reminders,
                                calendarId = calendarId,
                                transp = formState.transp,
                                color = formState.eventColor,
                                // Preserve these fields from master for round-trip fidelity:
                                timezone = masterEvent.timezone,
                                status = masterEvent.status,
                                classification = masterEvent.classification,
                                extraProperties = masterEvent.extraProperties,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                    )
                } else if (formState.isEditMode && formState.editingEventId != null) {
                    // Update entire event (or all occurrences for recurring)
                    val existingEvent = eventCoordinator.getEventById(formState.editingEventId)
                        ?: return@withContext Result.failure(IllegalStateException("Event not found"))

                    // Check if calendar is changing
                    val calendarChanged = existingEvent.calendarId != calendarId

                    // Note: SEQUENCE increment is handled by EventWriter (domain layer),
                    // following Android architecture best practices where business logic
                    // belongs in Data/Domain layer, not ViewModel (UI layer).

                    if (calendarChanged) {
                        // Calendar move requires DELETE + CREATE for CalDAV
                        // moveEventToCalendar handles this properly
                        eventCoordinator.moveEventToCalendar(formState.editingEventId, calendarId)

                        // After move, get the updated event and apply other field changes
                        val movedEvent = eventCoordinator.getEventById(formState.editingEventId)
                            ?: return@withContext Result.failure(IllegalStateException("Event not found after move"))

                        val finalEvent = movedEvent.copy(
                            title = formState.title.ifBlank { "Untitled" },
                            startTs = startTs,
                            endTs = endTs,
                            isAllDay = formState.isAllDay,
                            timezone = if (formState.isAllDay) null else (formState.timezone ?: movedEvent.timezone),
                            location = formState.location.ifBlank { null },
                            description = formState.description.ifBlank { null },
                            rrule = formState.rrule,
                            reminders = reminders,
                            transp = formState.transp,
                            color = formState.eventColor,
                            updatedAt = System.currentTimeMillis()
                        )
                        eventCoordinator.updateEvent(finalEvent)
                    } else {
                        // Same calendar - just update the event
                        val updatedEvent = existingEvent.copy(
                            title = formState.title.ifBlank { "Untitled" },
                            startTs = startTs,
                            endTs = endTs,
                            isAllDay = formState.isAllDay,
                            timezone = if (formState.isAllDay) null else (formState.timezone ?: existingEvent.timezone),
                            location = formState.location.ifBlank { null },
                            description = formState.description.ifBlank { null },
                            rrule = formState.rrule,
                            reminders = reminders,
                            calendarId = calendarId,
                            transp = formState.transp,
                            color = formState.eventColor,
                            updatedAt = System.currentTimeMillis()
                        )
                        eventCoordinator.updateEvent(updatedEvent)
                    }
                } else {
                    // Create new event
                    val now = System.currentTimeMillis()
                    val newEvent = org.onekash.kashcal.data.db.entity.Event(
                        uid = java.util.UUID.randomUUID().toString(),
                        calendarId = calendarId,
                        title = formState.title.ifBlank { "Untitled" },
                        startTs = startTs,
                        endTs = endTs,
                        // All-day events use null timezone (stored as UTC midnight)
                        // Timed events use user-selected timezone (or device default if null)
                        timezone = if (formState.isAllDay) null else (formState.timezone ?: java.util.TimeZone.getDefault().id),
                        isAllDay = formState.isAllDay,
                        location = formState.location.ifBlank { null },
                        description = formState.description.ifBlank { null },
                        rrule = formState.rrule,
                        reminders = reminders,
                        transp = formState.transp,
                        color = formState.eventColor,
                        dtstamp = now,
                        createdAt = now,
                        updatedAt = now
                    )

                    eventCoordinator.createEvent(newEvent, calendarId)
                }

                // Refresh the UI after save
                reloadCurrentView()

                Log.d(TAG, "Event saved: ${savedEvent.title} (id=${savedEvent.id})")
                Result.success(savedEvent)

            } catch (e: Exception) {
                Log.e(TAG, "Error saving event", e)
                Result.failure(e)
            }
        }
    }

    fun rescheduleEvent(
        displayEvent: DisplayEvent,
        targetDate: LocalDate,
        targetStartMinutes: Int,
        editScope: EditScope = EditScope.THIS_EVENT
    ) {
        val isRecurringNeedingDialog = when (displayEvent) {
            is DisplayEvent.Room -> displayEvent.event.rrule != null && displayEvent.event.originalEventId == null
            is DisplayEvent.Device -> displayEvent.instance.hasRrule
        }
        if (isRecurringNeedingDialog && editScope == EditScope.THIS_EVENT) {
            _uiState.update {
                it.copy(pendingDragReschedule = PendingDragReschedule(displayEvent, targetDate, targetStartMinutes))
            }
            return
        }

        performReschedule(displayEvent, targetDate, targetStartMinutes, editScope)
    }

    fun confirmReschedule(editScope: EditScope) {
        val pending = _uiState.value.pendingDragReschedule ?: return
        _uiState.update { it.copy(pendingDragReschedule = null) }
        performReschedule(pending.displayEvent, pending.targetDate, pending.targetStartMinutes, editScope)
    }

    fun cancelPendingReschedule() {
        _uiState.update { it.copy(pendingDragReschedule = null) }
    }

    private fun performReschedule(
        displayEvent: DisplayEvent,
        targetDate: LocalDate,
        targetStartMinutes: Int,
        editScope: EditScope
    ) {
        viewModelScope.launch {
            try {
                val durationMs = displayEvent.endTs - displayEvent.startTs
                val durationMinutes = (durationMs / 60000).toInt()
                val clampedStart = WeekViewUtils.clampDragStartMinutes(targetStartMinutes, durationMinutes)
                val (newStartTs, newEndTs) = WeekViewUtils.calculateNewTimestamps(
                    targetDate, clampedStart, durationMinutes
                )

                withContext(ioDispatcher) {
                    when (displayEvent) {
                        is DisplayEvent.Room -> {
                            val event = displayEvent.event
                            val isRecurring = event.rrule != null
                            val isException = event.originalEventId != null

                            when {
                                !isRecurring || isException -> {
                                    eventCoordinator.updateEvent(
                                        event.copy(startTs = newStartTs, endTs = newEndTs, updatedAt = System.currentTimeMillis())
                                    )
                                }
                                editScope == EditScope.THIS_EVENT -> {
                                    val masterEventId = event.originalEventId ?: event.id
                                    eventCoordinator.editSingleOccurrence(
                                        masterEventId = masterEventId,
                                        occurrenceTimeMs = displayEvent.occurrence.startTs,
                                        changes = { master ->
                                            master.copy(
                                                startTs = newStartTs,
                                                endTs = newEndTs,
                                                rrule = null,
                                                updatedAt = System.currentTimeMillis()
                                            )
                                        }
                                    )
                                }
                                editScope == EditScope.ALL_EVENTS -> {
                                    val delta = newStartTs - displayEvent.startTs
                                    eventCoordinator.updateEvent(
                                        event.copy(
                                            startTs = event.startTs + delta,
                                            endTs = event.endTs + delta,
                                            updatedAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                            }
                        }
                        is DisplayEvent.Device -> {
                            val instance = displayEvent.instance
                            val tz = instance.timezone ?: java.util.TimeZone.getDefault().id
                            if (instance.hasRrule && editScope == EditScope.THIS_EVENT) {
                                calendarProviderRepository.createException(
                                    calendarId = instance.calendarId,
                                    masterEventId = instance.eventId,
                                    originalInstanceTime = instance.startTs,
                                    title = instance.title,
                                    description = instance.description,
                                    location = instance.location,
                                    startTs = newStartTs,
                                    endTs = newEndTs,
                                    isAllDay = instance.isAllDay,
                                    timezone = tz,
                                    reminders = instance.reminders
                                )
                            } else {
                                calendarProviderRepository.updateEvent(
                                    eventId = instance.eventId,
                                    title = instance.title,
                                    description = instance.description,
                                    location = instance.location,
                                    startTs = newStartTs,
                                    endTs = newEndTs,
                                    isAllDay = instance.isAllDay,
                                    rrule = instance.rrule,
                                    duration = null,
                                    timezone = tz,
                                    reminders = instance.reminders
                                )
                            }
                        }
                    }
                }

                reloadCurrentView()
                showSnackbar("Event rescheduled")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error rescheduling event", e)
                showSnackbar("Failed to reschedule: ${e.message}")
            }
        }
    }

    /**
     * Delete an event.
     *
     * @param eventId The event ID to delete
     * @return Result indicating success or failure
     */
    suspend fun deleteEvent(eventId: Long): Result<Unit> {
        return withContext(ioDispatcher) {
            try {
                eventCoordinator.deleteEvent(eventId)
                Log.d(TAG, "Event deleted: $eventId")

                // Refresh the UI after delete
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    reloadCurrentView()
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting event", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Write the user's RSVP for an event they're attending.
     *
     * Optimistic-UI write path: the local attendee row's PARTSTAT is updated
     * inside the coordinator, so the chip row's Flow re-emits with the new
     * status before the network round-trip completes. The CalDAV PUT is
     * queued via PendingOperation and processed by PushStrategy.
     */
    fun replyRsvp(
        eventId: Long,
        status: org.onekash.kashcal.ui.components.attendees.AttendeeStatus
    ) {
        val partstat = status.toPartstat() ?: return
        viewModelScope.launch {
            try {
                val ok = withContext(ioDispatcher) {
                    eventCoordinator.replyRsvp(eventId, partstat)
                }
                if (!ok) {
                    Log.w(TAG, "RSVP write failed (account/attendee mismatch) for event $eventId")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error writing RSVP for event $eventId", e)
                showSnackbar("RSVP failed: ${e.message}")
            }
        }
    }

    /**
     * Save the user's reminder set on an event they're an attendee of.
     * Wraps [EventCoordinator.saveAttendeeReminders] for the read-only
     * attendee form path. Local-only — no server PUT. Failure surfaces
     * to the form sheet's `state.error` field via the [Result] return.
     */
    suspend fun saveAttendeeReminders(eventId: Long, reminders: List<Int>): Result<Unit> {
        return withContext(ioDispatcher) {
            try {
                eventCoordinator.saveAttendeeReminders(eventId, reminders).map { }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error saving attendee reminders for event $eventId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Delete event (fire-and-forget for optimistic UI).
     * Use this for QuickViewSheet where immediate dismissal is desired.
     * Note: Keep existing suspend deleteEvent() for EventFormSheet compatibility.
     *
     * @param eventId The event ID to delete
     */
    fun deleteEventOptimistic(eventId: Long) {
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    eventCoordinator.deleteEvent(eventId)
                }
                Log.d(TAG, "Event deleted (optimistic): $eventId")
                reloadCurrentView()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting event", e)
                showSnackbar("Failed to delete: ${e.message}")
            }
        }
    }

    /**
     * Delete a single occurrence of a recurring event (fire-and-forget for optimistic UI).
     * Adds EXDATE to master event.
     *
     * @param masterEventId The master recurring event ID
     * @param occurrenceTimeMs The occurrence timestamp to delete
     */
    fun deleteSingleOccurrence(masterEventId: Long, occurrenceTimeMs: Long) {
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    eventCoordinator.deleteSingleOccurrence(masterEventId, occurrenceTimeMs)
                }
                Log.d(TAG, "Occurrence deleted: event=$masterEventId, ts=$occurrenceTimeMs")
                reloadCurrentView()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting occurrence", e)
                showSnackbar("Failed to delete: ${e.message}")
            }
        }
    }

    /**
     * Delete this and all future occurrences (fire-and-forget for optimistic UI).
     * Truncates series with UNTIL.
     *
     * @param masterEventId The master recurring event ID
     * @param fromTimeMs Delete occurrences from this time onwards
     */
    fun deleteThisAndFuture(masterEventId: Long, fromTimeMs: Long) {
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    eventCoordinator.deleteThisAndFuture(masterEventId, fromTimeMs)
                }
                Log.d(TAG, "Future occurrences deleted: event=$masterEventId, from=$fromTimeMs")
                reloadCurrentView()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting future occurrences", e)
                showSnackbar("Failed to delete: ${e.message}")
            }
        }
    }

    // ==================== Device Calendar Write Operations ====================

    /**
     * Create a new event in a device calendar (CalendarProvider).
     *
     * @return Result containing created event ID on success
     */
    suspend fun createDeviceEvent(
        calendarId: Long,
        title: String,
        description: String?,
        location: String?,
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean,
        rrule: String?,
        timezone: String,
        reminders: List<Int>
    ): Result<Long> {
        return withContext(ioDispatcher) {
            // For recurring events, compute duration string
            val duration = if (rrule != null) {
                computeDurationString(startTs, endTs, isAllDay)
            } else null

            calendarProviderRepository.createEvent(
                calendarId = calendarId,
                title = title,
                description = description,
                location = location,
                startTs = startTs,
                endTs = if (rrule != null) null else endTs,
                isAllDay = isAllDay,
                rrule = rrule,
                duration = duration,
                timezone = timezone,
                reminders = reminders
            ).also { result ->
                result.onFailure { e ->
                    Log.e(TAG, "Failed to create device event", e)
                    showError(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error"))
                }
                result.onSuccess {
                    Log.d(TAG, "Device event created: id=$it")
                    reloadCurrentView()
                }
            }
        }
    }

    /**
     * Update an existing event in a device calendar.
     */
    suspend fun updateDeviceEvent(
        eventId: Long,
        title: String,
        description: String?,
        location: String?,
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean,
        rrule: String?,
        timezone: String,
        reminders: List<Int>
    ): Result<Unit> {
        return withContext(ioDispatcher) {
            val duration = if (rrule != null) {
                computeDurationString(startTs, endTs, isAllDay)
            } else null

            calendarProviderRepository.updateEvent(
                eventId = eventId,
                title = title,
                description = description,
                location = location,
                startTs = startTs,
                endTs = if (rrule != null) null else endTs,
                isAllDay = isAllDay,
                rrule = rrule,
                duration = duration,
                timezone = timezone,
                reminders = reminders
            ).also { result ->
                result.onFailure { e ->
                    Log.e(TAG, "Failed to update device event: $eventId", e)
                    showError(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error"))
                }
                result.onSuccess {
                    Log.d(TAG, "Device event updated: id=$eventId")
                    reloadCurrentView()
                }
            }
        }
    }

    /**
     * Delete an event from a device calendar.
     */
    suspend fun deleteDeviceEvent(eventId: Long): Result<Unit> {
        return withContext(ioDispatcher) {
            calendarProviderRepository.deleteEvent(eventId).also { result ->
                result.onFailure { e ->
                    Log.e(TAG, "Failed to delete device event: $eventId", e)
                    showError(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error"))
                }
                result.onSuccess {
                    Log.d(TAG, "Device event deleted: id=$eventId")
                    reloadCurrentView()
                }
            }
        }
    }

    /**
     * Delete a single occurrence of a recurring device calendar event.
     * Adds EXDATE to master event in CalendarProvider to exclude the occurrence.
     */
    suspend fun deleteDeviceSingleOccurrence(
        masterEventId: Long,
        originalInstanceTime: Long,
        isAllDay: Boolean = false
    ): Result<Unit> {
        return withContext(ioDispatcher) {
            calendarProviderRepository.deleteSingleOccurrence(
                masterEventId = masterEventId,
                originalInstanceTime = originalInstanceTime,
                isAllDay = isAllDay
            ).also { result ->
                result.onFailure { e ->
                    Log.e(TAG, "Failed to delete device occurrence: master=$masterEventId", e)
                    showError(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error"))
                }
                result.onSuccess {
                    Log.d(TAG, "Device occurrence deleted: master=$masterEventId, ts=$originalInstanceTime")
                    reloadCurrentView()
                }
            }
        }
    }

    /**
     * Route device event deletion from EventFormSheet based on form state.
     * When editingOccurrenceTs is set, deletes single occurrence; otherwise deletes entire event.
     * Mirrors the save routing logic at [saveDeviceEvent].
     */
    suspend fun handleDeviceEventFormDelete(formState: EventFormState): Result<Unit> {
        val deviceEventId = formState.editingDeviceEventId
            ?: return Result.failure(IllegalStateException("No device event to delete"))
        val occurrenceTs = formState.editingOccurrenceTs
        return if (occurrenceTs != null) {
            deleteDeviceSingleOccurrence(
                masterEventId = deviceEventId,
                originalInstanceTime = occurrenceTs,
                isAllDay = formState.isAllDay
            )
        } else {
            deleteDeviceEvent(deviceEventId)
        }
    }

    /**
     * Delete this and all future occurrences of a recurring device calendar event.
     * Truncates the master event's RRULE with an UNTIL clause.
     */
    suspend fun deleteDeviceThisAndFuture(
        masterEventId: Long,
        fromTimeMs: Long,
        isAllDay: Boolean = false
    ): Result<Unit> {
        return withContext(ioDispatcher) {
            calendarProviderRepository.deleteThisAndFuture(
                masterEventId = masterEventId,
                fromTimeMs = fromTimeMs,
                isAllDay = isAllDay
            ).also { result ->
                result.onFailure { e ->
                    Log.e(TAG, "Failed to delete device future occurrences: master=$masterEventId", e)
                    showError(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error"))
                }
                result.onSuccess {
                    Log.d(TAG, "Device future occurrences deleted: master=$masterEventId, from=$fromTimeMs")
                    reloadCurrentView()
                }
            }
        }
    }

    // ==================== Device Calendar Edit Support ====================

    /**
     * Check if a device event can be edited.
     *
     * @param calendarId The calendar ID containing the event
     * @return Pair of (canEdit, calendarName or null)
     */
    suspend fun canEditDeviceEvent(calendarId: Long): Pair<Boolean, String?> {
        return withContext(ioDispatcher) {
            val calendars = calendarProviderRepository.getDeviceCalendars()
            val calendar = calendars.find { it.id == calendarId }
            if (calendar != null && calendar.isWritable) {
                true to calendar.displayName
            } else {
                false to null
            }
        }
    }

    /**
     * Load a device event for editing.
     *
     * When occurrenceTs is provided, checks if an exception event exists for that occurrence.
     * If so, loads the exception event (with its own reminders) instead of the master.
     *
     * @param eventId Event ID to load (master event ID for recurring)
     * @param occurrenceTs Original occurrence timestamp (null for non-occurrence edits)
     * @param isAllDay Whether the event is all-day (for UTC midnight normalization)
     * @return DeviceEventEditData with event, reminders, and calendar info, or null if not found
     */
    suspend fun getDeviceEventForEdit(eventId: Long, occurrenceTs: Long? = null, isAllDay: Boolean = false): DeviceEventEditData? {
        return withContext(ioDispatcher) {
            val effectiveEventId = if (occurrenceTs != null) {
                calendarProviderRepository.findExceptionEventId(eventId, occurrenceTs, isAllDay)
                    ?: eventId
            } else eventId
            val event = calendarProviderRepository.getDeviceEvent(effectiveEventId) ?: return@withContext null
            val calendars = calendarProviderRepository.getDeviceCalendars()
            val calendar = calendars.find { it.id == event.calendarId } ?: return@withContext null
            val reminders = calendarProviderRepository.getReminders(effectiveEventId)

            DeviceEventEditData(
                event = event,
                reminders = reminders,
                calendarName = calendar.displayName,
                calendarColor = calendar.color,
                isWritable = calendar.isWritable
            )
        }
    }

    /**
     * Find an existing exception event for an occurrence.
     *
     * @param masterEventId Master recurring event ID
     * @param originalInstanceTime Original occurrence timestamp
     * @return Exception event ID if exists, null otherwise
     */
    suspend fun findExceptionEventId(masterEventId: Long, originalInstanceTime: Long, isAllDay: Boolean = false): Long? {
        return withContext(ioDispatcher) {
            calendarProviderRepository.findExceptionEventId(masterEventId, originalInstanceTime, isAllDay)
        }
    }

    /**
     * Import ICS events into a device calendar via CalendarProvider.
     *
     * @param events Events parsed from ICS file
     * @param calendarId Target device calendar ID
     * @return Count of successfully imported events
     */
    suspend fun importIcsToDeviceCalendar(events: List<Event>, calendarId: Long): Int {
        return withContext(ioDispatcher) {
            importEventsToDeviceCalendar(events, calendarId, calendarProviderRepository)
        }
    }

    /**
     * Save a device event from EventFormState.
     *
     * Routes to appropriate operation:
     * - If editing occurrence (editingOccurrenceTs != null): create/update exception
     * - If editing existing event: update event
     * - Otherwise: create new event
     *
     * @param formState The form state to save
     * @return Result containing event ID on success
     */
    suspend fun saveDeviceEvent(formState: org.onekash.kashcal.ui.components.EventFormState): Result<Long> {
        return withContext(ioDispatcher) {
            val calendarId = formState.selectedCalendarId
                ?: return@withContext Result.failure(IllegalStateException("No calendar selected"))

            // Compute timestamps from form state
            val (startTs, endTs) = computeTimestampsFromFormState(formState)

            // Build reminders list (just minutes, not ISO format)
            val reminders = buildDeviceReminders(formState.reminders)

            val timezone = formState.timezone ?: java.util.TimeZone.getDefault().id

            // Determine operation based on form state
            when {
                // Editing single occurrence of recurring event
                formState.editingDeviceEventId != null && formState.editingOccurrenceTs != null -> {
                    val masterEventId = formState.editingDeviceEventId
                    val originalInstanceTime = formState.editingOccurrenceTs

                    // Check if exception already exists
                    val existingExceptionId = calendarProviderRepository.findExceptionEventId(
                        masterEventId, originalInstanceTime, formState.isAllDay
                    )

                    if (existingExceptionId != null) {
                        // Update existing exception
                        calendarProviderRepository.updateEvent(
                            eventId = existingExceptionId,
                            title = formState.title,
                            description = formState.description.ifBlank { null },
                            location = formState.location.ifBlank { null },
                            startTs = startTs,
                            endTs = endTs,
                            isAllDay = formState.isAllDay,
                            rrule = null, // Exceptions don't have RRULE
                            duration = null,
                            timezone = timezone,
                            reminders = reminders,
                            availability = transpToAvailability(formState.transp),
                            eventColor = formState.eventColor
                        ).map { existingExceptionId }
                    } else {
                        // Create new exception
                        calendarProviderRepository.createException(
                            calendarId = calendarId,
                            masterEventId = masterEventId,
                            originalInstanceTime = originalInstanceTime,
                            title = formState.title,
                            description = formState.description.ifBlank { null },
                            location = formState.location.ifBlank { null },
                            startTs = startTs,
                            endTs = endTs,
                            isAllDay = formState.isAllDay,
                            timezone = timezone,
                            reminders = reminders,
                            availability = transpToAvailability(formState.transp),
                            eventColor = formState.eventColor
                        )
                    }
                }

                // Editing existing event (not occurrence)
                formState.editingDeviceEventId != null -> {
                    val eventId = formState.editingDeviceEventId
                    calendarProviderRepository.updateEvent(
                        eventId = eventId,
                        title = formState.title,
                        description = formState.description.ifBlank { null },
                        location = formState.location.ifBlank { null },
                        startTs = startTs,
                        endTs = if (formState.rrule != null) null else endTs,
                        isAllDay = formState.isAllDay,
                        rrule = formState.rrule,
                        duration = if (formState.rrule != null) computeDurationString(startTs, endTs, formState.isAllDay) else null,
                        timezone = timezone,
                        reminders = reminders,
                        availability = transpToAvailability(formState.transp),
                        eventColor = formState.eventColor
                    ).map { eventId }
                }

                // Creating new event
                else -> {
                    calendarProviderRepository.createEvent(
                        calendarId = calendarId,
                        title = formState.title,
                        description = formState.description.ifBlank { null },
                        location = formState.location.ifBlank { null },
                        startTs = startTs,
                        endTs = if (formState.rrule != null) null else endTs,
                        isAllDay = formState.isAllDay,
                        rrule = formState.rrule,
                        duration = if (formState.rrule != null) computeDurationString(startTs, endTs, formState.isAllDay) else null,
                        timezone = timezone,
                        reminders = reminders,
                        availability = transpToAvailability(formState.transp),
                        eventColor = formState.eventColor
                    )
                }
            }.also { result ->
                result.onSuccess { reloadCurrentView() }
                result.onFailure { e ->
                    Log.e(TAG, "Failed to save device event", e)
                    showError(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error"))
                }
            }
        }
    }

    private fun transpToAvailability(transp: String): Int =
        if (transp == "TRANSPARENT") 1 else 0

    /**
     * Compute start/end timestamps from form state.
     * Handles all-day UTC conversion.
     */
    private fun computeTimestampsFromFormState(formState: org.onekash.kashcal.ui.components.EventFormState): Pair<Long, Long> {
        return if (formState.isAllDay) {
            // All-day: convert local date to UTC midnight
            val startTs = DateTimeUtils.localDateToUtcMidnight(formState.dateMillis)
            val endTs = DateTimeUtils.localDateToUtcMidnight(formState.endDateMillis)
            // End is inclusive, so add end-of-day
            startTs to DateTimeUtils.utcMidnightToEndOfDay(endTs)
        } else {
            // Timed: combine date and time
            val startCal = java.util.Calendar.getInstance().apply {
                timeInMillis = formState.dateMillis
                set(java.util.Calendar.HOUR_OF_DAY, formState.startHour)
                set(java.util.Calendar.MINUTE, formState.startMinute)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val endCal = java.util.Calendar.getInstance().apply {
                timeInMillis = formState.endDateMillis
                set(java.util.Calendar.HOUR_OF_DAY, formState.endHour)
                set(java.util.Calendar.MINUTE, formState.endMinute)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            startCal.timeInMillis to endCal.timeInMillis
        }
    }

    /**
     * Build device reminders list from form minutes.
     * Returns list of minutes (not ISO format like Room events).
     * Deduplicates and sorts before returning.
     */
    private fun buildDeviceReminders(reminderMinutes: List<Int>): List<Int> {
        return deduplicateAndSortReminders(reminderMinutes)
    }

    /**
     * Build reminders list from form values.
     * Converts minutes to ISO 8601 duration format (e.g., -PT15M for 15 minutes before).
     * Deduplicates and sorts before converting.
     */
    private fun buildRemindersList(reminderMinutes: List<Int>): List<String>? {
        val deduplicated = deduplicateAndSortReminders(reminderMinutes)
        val reminders = deduplicated.map { minutesToIsoDuration(it) }
        return reminders.ifEmpty { null }
    }

    /**
     * Convert minutes to ISO 8601 duration format.
     * e.g., 15 minutes -> "-PT15M", 60 minutes -> "-PT1H"
     */
    private fun minutesToIsoDuration(minutes: Int): String {
        return when {
            minutes == 0 -> "-PT0M"
            minutes >= 1440 && minutes % 1440 == 0 -> "-P${minutes / 1440}D"
            minutes >= 60 && minutes % 60 == 0 -> "-PT${minutes / 60}H"
            else -> "-PT${minutes}M"
        }
    }

    /**
     * Get local calendar ID for fallback.
     */
    suspend fun getLocalCalendarId(): Long {
        return withContext(ioDispatcher) {
            eventCoordinator.getLocalCalendarId()
        }
    }

    // ==================== Error Handling ====================

    /**
     * Show an error to the user.
     *
     * Converts CalendarError to ErrorPresentation and displays appropriately:
     * - Snackbar: Sets currentError, consumed by ErrorSnackbarHost
     * - Dialog: Sets currentError + showErrorDialog
     * - Banner: Sets currentError + showErrorBanner
     * - Silent: Logs only, no UI change
     *
     * Usage:
     * ```
     * try {
     *     syncEngine.sync()
     * } catch (e: Exception) {
     *     showError(ErrorMapper.fromException(e))
     * }
     * ```
     */
    fun showError(error: CalendarError) {
        val presentation = ErrorMapper.toPresentation(error)

        when (presentation) {
            is ErrorPresentation.Snackbar -> {
                _uiState.update {
                    it.copy(
                        currentError = presentation,
                        showErrorDialog = false,
                        showErrorBanner = false
                    )
                }
            }
            is ErrorPresentation.Dialog -> {
                _uiState.update {
                    it.copy(
                        currentError = presentation,
                        showErrorDialog = true,
                        showErrorBanner = false
                    )
                }
            }
            is ErrorPresentation.Banner -> {
                _uiState.update {
                    it.copy(
                        currentError = presentation,
                        showErrorDialog = false,
                        showErrorBanner = true
                    )
                }
            }
            is ErrorPresentation.Silent -> {
                // Log only, no UI change
                Log.d(TAG, "Silent error: ${presentation.logMessage}")
            }
        }
    }

    /**
     * Handle error action callback from UI.
     *
     * Called when user taps action button on error Snackbar/Dialog/Banner.
     * Dispatches to appropriate handler based on callback type.
     */
    fun handleErrorAction(callback: ErrorActionCallback) {
        when (callback) {
            is ErrorActionCallback.Retry -> {
                Log.d(TAG, "Error action: Retry")
                clearError()
                performSync()
            }
            is ErrorActionCallback.OpenSettings -> {
                Log.d(TAG, "Error action: OpenSettings")
                clearError()
                // Navigation handled by Activity (observes this state)
                _uiState.update { it.copy(pendingSnackbarMessage = null) } // Clear any snackbar
            }
            is ErrorActionCallback.OpenAppSettings -> {
                Log.d(TAG, "Error action: OpenAppSettings")
                clearError()
                // Open Android app settings - handled by Activity
            }
            is ErrorActionCallback.OpenAppleIdWebsite -> {
                Log.d(TAG, "Error action: OpenAppleIdWebsite")
                clearError()
                // Open Apple ID website - handled by Activity
            }
            is ErrorActionCallback.ReAuthenticate -> {
                Log.d(TAG, "Error action: ReAuthenticate")
                clearError()
                // Trigger re-authentication flow - handled by Activity
            }
            is ErrorActionCallback.ForceFullSync -> {
                Log.d(TAG, "Error action: ForceFullSync")
                clearError()
                forceFullSync()
            }
            is ErrorActionCallback.ViewSyncDetails -> {
                Log.d(TAG, "Error action: ViewSyncDetails")
                clearError()
                _uiState.update { it.copy(showSyncChangesSheet = true) }
            }
            is ErrorActionCallback.Dismiss -> {
                Log.d(TAG, "Error action: Dismiss")
                clearError()
            }
            is ErrorActionCallback.OpenUrl -> {
                Log.d(TAG, "Error action: OpenUrl - ${callback.url}")
                _uiState.update { it.copy(pendingUrlToOpen = callback.url) }
                clearError()
            }
            is ErrorActionCallback.Custom -> {
                Log.d(TAG, "Error action: Custom")
                callback.action()
                clearError()
            }
        }
    }

    /**
     * Clear current error state.
     * Called after error is dismissed or action is taken.
     */
    fun clearError() {
        _uiState.update {
            it.copy(
                currentError = null,
                showErrorDialog = false,
                showErrorBanner = false
            )
        }
    }

    /**
     * Clear pending URL after it has been opened.
     */
    fun clearPendingUrl() {
        _uiState.update { it.copy(pendingUrlToOpen = null) }
    }

    /**
     * Show error from HTTP code.
     * Convenience method for sync layer integration.
     */
    fun showHttpError(code: Int, message: String? = null) {
        showError(ErrorMapper.fromHttpCode(code, message))
    }

    /**
     * Show error from exception.
     * Convenience method for exception handling.
     */
    fun showExceptionError(e: Throwable) {
        showError(ErrorMapper.fromException(e))
    }

    // ==================== Helper Functions ====================

    /**
     * Parse YYYYMMDD day format into (year, month, day) triple.
     * Month is 0-indexed (January = 0) for Calendar compatibility.
     */
    private fun parseDayFormat(dayFormat: Int): Triple<Int, Int, Int> {
        val year = dayFormat / 10000
        val month = (dayFormat % 10000) / 100 - 1  // 0-indexed for Calendar
        val day = dayFormat % 100
        return Triple(year, month, day)
    }
}

/**
 * Attendee state passed from [HomeViewModel] to chip surfaces (QuickView,
 * EventForm). Held in the ViewModel layer because the type ties the VM's
 * identity-resolution to the UI projection — no other layer should
 * construct it.
 */
data class EventAttendeeUiState(
    val models: List<AttendeeUiModel>,
    val isCurrentUserOnList: Boolean
)
