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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.onekash.kashcal.sync.scheduler.SyncStatus
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.di.IoDispatcher
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.error.CalendarError
import org.onekash.kashcal.error.ErrorActionCallback
import org.onekash.kashcal.error.ErrorMapper
import org.onekash.kashcal.error.ErrorPresentation
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.session.SyncTrigger
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.ui.components.EventFormState
import org.onekash.kashcal.ui.components.generateSnackbarMessage
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.util.DateTimeUtils
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
class HomeViewModel @Inject constructor(
    private val eventCoordinator: EventCoordinator,
    private val eventReader: EventReader,
    private val dataStore: KashCalDataStore,
    private val accountRepository: AccountRepository,
    private val syncScheduler: SyncScheduler,
    private val networkMonitor: NetworkMonitor,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

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

    /** Time format preference: "system", "12h", or "24h" */
    val timeFormat: StateFlow<String> = dataStore.timeFormat
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), KashCalDataStore.TIME_FORMAT_SYSTEM)

    /** First day of week preference: 0=system, 1=Sunday, 2=Monday, 7=Saturday */
    val firstDayOfWeek: StateFlow<Int> = dataStore.firstDayOfWeek
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Calendar.SUNDAY)

    // Track if startup sync has been triggered
    private var hasTriggeredStartupSync = false

    // Job for search debouncing (cancel previous search when new query arrives)
    private var searchJob: Job? = null

    // Job for on-demand dots loading (cancel previous on fast swipe)
    private var loadDotsJob: Job? = null

    // Job for day events observation (cancel previous when date changes)
    // Uses Flow for progressive updates during sync
    private var dayEventsJob: Job? = null

    // Job for agenda events observation (cancel previous when reopened)
    // Uses Flow for progressive updates during sync
    private var agendaEventsJob: Job? = null

    // Job for occurrence extension (cancel previous on rapid swipe)
    private var extensionJob: Job? = null

    // Job for week view events observation (cancel previous when week changes)
    private var weekEventsJob: Job? = null

    // Job for day events cache loading (cancel previous when cache refresh needed)
    private var dayEventsCacheJob: Job? = null

    // Job for debounced day pager loading (cancel previous on fast swipe)
    private var dayPagerLoadJob: Job? = null

    // Track current loaded date range to avoid redundant loads
    private var currentLoadedRange: Pair<LocalDate, LocalDate>? = null

    // Suppress sync indicator for silent syncs (cold start, resume, force full sync with banner)
    // Only pull-to-refresh shows the spinning icon since it's user-initiated
    private var suppressSyncIndicator = false

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

            // Load default view from DataStore before building UI
            val defaultView = ViewMode.fromKey(dataStore.getDefaultCalendarView())
            _uiState.update { it.copy(viewMode = defaultView, defaultViewMode = defaultView) }

            // Load data for the default view
            when (defaultView) {
                ViewMode.AGENDA -> loadAgendaEvents()
                ViewMode.THREE_DAYS -> {} // goToToday() below handles week initialization
                ViewMode.MONTH -> {} // Default, no extra loading needed
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
                                syncBannerMessage = if (status is SyncStatus.Running)
                                    "Syncing calendars..." else "Preparing to sync..."
                            )
                        }
                    }
                    is SyncStatus.Succeeded -> {
                        suppressSyncIndicator = false  // Reset flag for next sync
                        val hasPartialError = status.errorMessage != null
                        _uiState.update {
                            it.copy(
                                isSyncing = false,
                                showSyncBanner = showBanner || hasPartialError,
                                syncBannerMessage = if (hasPartialError)
                                    "Sync complete with errors" else "Sync complete"
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
                                syncBannerMessage = "Sync failed: ${status.errorMessage ?: "Unknown error"}"
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
                                isSyncing = false
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
            dataStore.defaultCalendarView.collect { viewKey ->
                _uiState.update { it.copy(defaultViewMode = ViewMode.fromKey(viewKey)) }
            }
        }
    }

    // ==================== Sync Operations ====================

    /**
     * Pull-to-refresh sync.
     */
    fun refreshSync() {
        if (_uiState.value.isSyncing) {
            Log.d(TAG, "Sync already in progress, ignoring refresh")
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
                    dataStore.defaultCalendarId
                ) { calendars, accounts, userPrefId ->
                    // User preference takes priority, but validate it exists
                    val defaultCalId = userPrefId?.takeIf { id -> calendars.any { it.id == id } }
                        ?: calendars.find { it.isDefault }?.id  // Fallback to DB is_default
                        ?: calendars.firstOrNull()?.id          // Fallback to first calendar
                    // Group calendars by account for UI display
                    val groups = CalendarGroup.fromCalendarsAndAccounts(calendars, accounts)
                    Triple(calendars, groups, defaultCalId)
                }.collect { (calendars, groups, defaultCalId) ->
                    _uiState.update {
                        it.copy(
                            calendars = calendars.toPersistentList(),
                            calendarGroups = groups.toPersistentList(),
                            defaultCalendarId = defaultCalId
                        )
                    }
                    Log.d(TAG, "Calendars updated: ${calendars.size} calendars, ${groups.size} groups, default=$defaultCalId")
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
                val (calendars, groups, defaultCalId) = withContext(ioDispatcher) {
                    val cals = eventCoordinator.getAllCalendars().first()
                    val accounts = eventCoordinator.getAllAccounts().first()
                    // User preference > DB is_default > first calendar
                    val userPrefId = dataStore.getDefaultCalendarId()
                    val defaultId = userPrefId?.takeIf { id -> cals.any { it.id == id } }
                        ?: cals.find { it.isDefault }?.id
                        ?: cals.firstOrNull()?.id
                    // Group calendars by account for UI
                    val calGroups = CalendarGroup.fromCalendarsAndAccounts(cals, accounts)
                    Triple(cals, calGroups, defaultId)
                }
                _uiState.update {
                    it.copy(
                        calendars = calendars.toPersistentList(),
                        calendarGroups = groups.toPersistentList(),
                        defaultCalendarId = defaultCalId
                    )
                }
                Log.d(TAG, "Loaded ${calendars.size} calendars, ${groups.size} groups, default=$defaultCalId")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading calendars", e)
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
     * Toggle calendar visibility sheet.
     */
    fun toggleCalendarVisibilitySheet() {
        _uiState.update { it.copy(showCalendarVisibility = !it.showCalendarVisibility) }
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
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startTs = calendar.timeInMillis
                calendar.add(Calendar.MONTH, 1)
                val endTs = calendar.timeInMillis

                val occurrences = withContext(ioDispatcher) {
                    eventReader.getVisibleOccurrencesInRange(startTs, endTs).first()
                }

                val calendarColors = _uiState.value.calendars.associate { it.id to it.color }
                val monthKey = String.format("%04d-%02d", year, month + 1)
                val monthDots = mutableMapOf<Int, MutableList<Int>>()

                for (occurrence in occurrences) {
                    val color = calendarColors[occurrence.calendarId] ?: 0xFF6200EE.toInt()
                    var currentDayCode = occurrence.startDay
                    while (currentDayCode <= occurrence.endDay) {
                        val (occYear, occMonth, day) = parseDayFormat(currentDayCode)
                        // Only add dots for target month
                        if (occYear == year && occMonth == month) {
                            val dayColors = monthDots.getOrPut(day) { mutableListOf() }
                            if (!dayColors.contains(color)) {
                                dayColors.add(color)
                            }
                        }
                        currentDayCode = Occurrence.incrementDayCode(currentDayCode)
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

                // Calculate cache range bounds BEFORE modifying calendar
                val centerEncoded = encodeMonth(year, month)
                val startEncoded = centerEncoded - 6
                val endEncoded = centerEncoded + 6

                // Get range: 6 months before to 6 months after
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                // Go back 6 months
                calendar.add(Calendar.MONTH, -6)
                val startTs = calendar.timeInMillis

                // Go forward 13 months (to include all of +6 month)
                // 13 = -6 + 13 = +7, so endTs is first moment of month +7
                // This ensures all events in month +6 are included (start_ts <= endTs)
                calendar.add(Calendar.MONTH, 13)
                val endTs = calendar.timeInMillis

                // Query occurrences in range (uses visible calendars from DataStore)
                val occurrences = withContext(ioDispatcher) {
                    eventReader.getVisibleOccurrencesInRange(startTs, endTs).first()
                }

                // Get calendar colors map
                val calendarColors = _uiState.value.calendars.associate { it.id to it.color }

                // Group by month and day - iterate through ALL days of multi-day events
                for (occurrence in occurrences) {
                    val color = calendarColors[occurrence.calendarId] ?: 0xFF6200EE.toInt()

                    // Iterate through each day this occurrence spans
                    var currentDayCode = occurrence.startDay
                    while (currentDayCode <= occurrence.endDay) {
                        val (occYear, occMonth, day) = parseDayFormat(currentDayCode)
                        val key = String.format("%04d-%02d", occYear, occMonth + 1)

                        val monthMap = dots.getOrPut(key) { mutableMapOf() }
                        val dayColors = monthMap.getOrPut(day) { mutableListOf() }
                        if (!dayColors.contains(color)) {
                            dayColors.add(color)
                        }

                        currentDayCode = Occurrence.incrementDayCode(currentDayCode)
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

                val (startYear, startMonth) = decodeMonth(startEncoded)
                val (endYear, endMonth) = decodeMonth(endEncoded)
                Log.d(TAG, "Built event dots for ${dots.size} months, loaded ${loadedMonthsSet.size} months: $startYear-${startMonth + 1} to $endYear-${endMonth + 1}")
            } catch (e: Exception) {
                Log.e(TAG, "Error building event dots", e)
            }
        }
    }

    // ==================== Navigation ====================

    /**
     * Navigate to today and select it.
     * Context-aware: If in 3-day view, navigates week view to today.
     */
    fun goToToday() {
        when (_uiState.value.viewMode) {
            ViewMode.THREE_DAYS -> {
                goToTodayWeek()
            }
            ViewMode.AGENDA -> {
                _uiState.update { it.copy(pendingScrollAgendaToTop = true) }
            }
            ViewMode.MONTH -> {
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

                selectDate(today.timeInMillis)
            }
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

        // Load dots if outside cached range (on-demand loading)
        ensureDotsForMonth(year, month)

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

                val extended = withContext(ioDispatcher) {
                    eventCoordinator.extendOccurrencesIfNeeded(targetMs)
                }

                // Reload dots if we actually extended anything
                if (extended > 0) {
                    Log.d(TAG, "Extended occurrences for $extended events (navigated to $year-${month + 1})")
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

    /** Navigate backward by 3 days in the 3-day view (matches visible window). */
    fun navigateDaysPagerPrevious() {
        val currentPage = _uiState.value.weekViewPagerPosition
        if (currentPage <= 0) return
        val targetPage = currentPage - WeekViewUtils.VISIBLE_DAYS
        _uiState.update { it.copy(pendingWeekViewPagerPosition = targetPage) }
        onDayPagerPageChanged(targetPage)
    }

    /** Navigate forward by 3 days in the 3-day view (matches visible window). */
    fun navigateDaysPagerNext() {
        val currentPage = _uiState.value.weekViewPagerPosition
        val targetPage = currentPage + WeekViewUtils.VISIBLE_DAYS
        _uiState.update { it.copy(pendingWeekViewPagerPosition = targetPage) }
        onDayPagerPageChanged(targetPage)
    }

    /**
     * Navigate week view to today (infinite pager: today = CENTER_DAY_PAGE).
     */
    fun goToTodayWeek() {
        val targetPage = WeekViewUtils.CENTER_DAY_PAGE

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
                // Use reactive method that auto-filters by visibility via combine()
                eventReader.getVisibleOccurrencesWithEventsInRangeFlow(weekStartMs, weekEndMs)
                    .collect { visible ->
                        // Separate timed and all-day events
                        val timedOccurrences = visible
                            .filter { !it.event.isAllDay }
                            .sortedBy { it.occurrence.startTs }

                        val allDayOccurrences = visible
                            .filter { it.event.isAllDay }
                            .sortedBy { it.occurrence.startTs }

                        _uiState.update {
                            it.copy(
                                weekViewOccurrences = timedOccurrences.map { owe -> owe.occurrence }.toPersistentList(),
                                weekViewEvents = timedOccurrences.map { owe -> owe.event }.toPersistentList(),
                                weekViewAllDayOccurrences = allDayOccurrences.map { owe -> owe.occurrence }.toPersistentList(),
                                weekViewAllDayEvents = allDayOccurrences.map { owe -> owe.event }.toPersistentList(),
                                isLoadingWeekView = false
                            )
                        }

                        Log.d(TAG, "Week view updated: ${timedOccurrences.size} timed, ${allDayOccurrences.size} all-day")
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

            // Get visible and loading date ranges
            val (visibleStart, visibleEnd) = WeekViewUtils.getVisibleDateRange(currentPage)
            val (loadStart, loadEnd) = WeekViewUtils.getLoadingDateRange(currentPage)

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
                // Use reactive method that auto-filters by visibility via combine()
                eventReader.getVisibleOccurrencesWithEventsInRangeFlow(startMs, endMs)
                    .collect { visible ->
                        // Separate timed and all-day events
                        val timedOccurrences = visible
                            .filter { !it.event.isAllDay }
                            .sortedBy { it.occurrence.startTs }

                        val allDayOccurrences = visible
                            .filter { it.event.isAllDay }
                            .sortedBy { it.occurrence.startTs }

                        _uiState.update {
                            it.copy(
                                weekViewOccurrences = timedOccurrences.map { owe -> owe.occurrence }.toPersistentList(),
                                weekViewEvents = timedOccurrences.map { owe -> owe.event }.toPersistentList(),
                                weekViewAllDayOccurrences = allDayOccurrences.map { owe -> owe.occurrence }.toPersistentList(),
                                weekViewAllDayEvents = allDayOccurrences.map { owe -> owe.event }.toPersistentList(),
                                isLoadingWeekView = false
                            )
                        }

                        Log.d(TAG, "Day pager updated: ${timedOccurrences.size} timed, ${allDayOccurrences.size} all-day")
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
        val targetPage = WeekViewUtils.CENTER_DAY_PAGE

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
        val targetPage = WeekViewUtils.dateToPage(date)

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

        // Convert date to page in infinite pager
        val date = WeekViewUtils.epochMsToDate(dateMs)
        val targetPage = WeekViewUtils.dateToPage(date)

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
                selectedDayLabel = formatDateLabel(dateMillis),
                isLoadingDayEvents = true
            )
        }

        loadEventsForSelectedDay(dateMillis)
    }

    /**
     * Observe events for the selected day using reactive Flow.
     *
     * Uses dayCode-based query (YYYYMMDD) instead of timestamp-based query
     * to avoid timezone boundary issues with all-day events.
     *
     * All-day events are stored as UTC midnight timestamps. A timestamp-based
     * query using local timezone boundaries can incorrectly match events on
     * adjacent days due to UTC offset. The dayCode query uses pre-calculated
     * start_day/end_day columns that are timezone-aware.
     *
     * PROGRESSIVE LOADING: Events appear as they sync because this uses Flow
     * collection instead of one-shot query.
     *
     * REACTIVITY FIX (v20.10.6): Uses JOIN-based query that tracks BOTH the
     * occurrences AND events tables. This ensures that event metadata changes
     * (location, title, description) trigger Flow emissions even when occurrence
     * times don't change. Room automatically tracks all tables in a JOIN query.
     */
    private fun loadEventsForSelectedDay(dateMillis: Long) {
        // Cancel any previous day events observation
        dayEventsJob?.cancel()

        // Convert to dayCode using java.time (Android recommended API)
        // This gives us the LOCAL date the user selected in the UI
        val localDate = Instant.ofEpochMilli(dateMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        val dayCode = localDate.year * 10000 + localDate.monthValue * 100 + localDate.dayOfMonth

        // Start observing events for this day (reactive - updates as sync progresses)
        // JOIN query includes Event data, so no separate event fetch needed
        dayEventsJob = viewModelScope.launch {
            try {
                eventReader.getVisibleOccurrencesWithEventsForDay(dayCode)
                    .distinctUntilChanged()
                    .collect { occurrencesWithEvents ->
                        // Events already available from JOIN query
                        val occurrences = occurrencesWithEvents.map { it.occurrence }
                        val events = occurrencesWithEvents.map { it.event }

                        _uiState.update {
                            it.copy(
                                selectedDayOccurrences = occurrences.toPersistentList(),
                                selectedDayEvents = events.toPersistentList(),
                                isLoadingDayEvents = false
                            )
                        }
                        Log.d(TAG, "Day events updated: ${events.size} events (dayCode=$dayCode)")
                    }
            } catch (e: CancellationException) {
                // Normal cancellation when day changes - don't log as error
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error observing events for day", e)
                _uiState.update { it.copy(isLoadingDayEvents = false) }
            }
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

        val rangeStart = centerDateMs - (3 * DayPagerUtils.DAY_MS)
        val rangeEnd = centerDateMs + (4 * DayPagerUtils.DAY_MS) // +4 to include 3 days after

        Log.d(TAG, "Day pager cache: loading range centered on ${DayPagerUtils.msToDayCode(centerDateMs)}")

        dayEventsCacheJob = viewModelScope.launch {
            try {
                // Use reactive method that auto-filters by visibility via combine()
                eventReader.getVisibleOccurrencesWithEventsInRangeFlow(rangeStart, rangeEnd)
                    .collect { visible ->
                        // Group by dayCode for O(1) lookup
                        // Use pre-calculated startDay/endDay (already UTC-aware for all-day events)
                        // Expand multi-day events to all days they span
                        val grouped = visible
                            .flatMap { item ->
                                val startDay = item.occurrence.startDay
                                val endDay = item.occurrence.endDay
                                if (startDay == endDay) {
                                    listOf(startDay to item)
                                } else {
                                    // Multi-day: generate entry for each day in span
                                    generateDayCodesInRange(startDay, endDay).map { dayCode -> dayCode to item }
                                }
                            }
                            .groupBy({ it.first }, { it.second })
                            .mapValues { (_, list) ->
                                list.sortedBy { it.occurrence.startTs }.toPersistentList()
                            }
                            .toPersistentMap()

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

                        Log.d(TAG, "Day pager cache: loaded ${visible.size} events across ${grouped.size} days")
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading day pager cache", e)
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
        val format = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
        return format.format(dateMillis)
    }

    /**
     * Generate all day codes between start and end (inclusive).
     * Uses Occurrence.incrementDayCode for calendar-correct month/year boundaries.
     *
     * @param startDay Start day code (YYYYMMDD format)
     * @param endDay End day code (YYYYMMDD format)
     * @return List of day codes from start to end inclusive
     */
    private fun generateDayCodesInRange(startDay: Int, endDay: Int): List<Int> {
        if (startDay == endDay) return listOf(startDay)
        if (startDay > endDay) return emptyList() // Invalid range guard

        val result = mutableListOf<Int>()
        var current = startDay
        while (current <= endDay) {
            result.add(current)
            current = Occurrence.incrementDayCode(current)
        }
        return result
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
                searchDateFilter = DateFilter.AnyTime,
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
                searchDateFilter = DateFilter.AnyTime,
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

    /**
     * Toggle include past events in search.
     */
    fun toggleSearchIncludePast() {
        val newValue = !_uiState.value.searchIncludePast
        _uiState.update { it.copy(searchIncludePast = newValue) }

        // Re-run search with new setting
        if (_uiState.value.searchQuery.length >= 2) {
            performSearch(_uiState.value.searchQuery)
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

                // Use new search methods that return EventWithNextOccurrence
                val results = withContext(ioDispatcher) {
                    when {
                        // Date filter active - use combined FTS + date range query
                        timeRange != null -> {
                            eventReader.searchEventsInRangeWithNextOccurrence(query, timeRange.first, timeRange.second)
                        }
                        // No date filter, include past - use basic FTS
                        _uiState.value.searchIncludePast -> {
                            eventReader.searchEventsWithNextOccurrence(query)
                        }
                        // No date filter, exclude past - use future-only FTS
                        else -> {
                            eventReader.searchEventsExcludingPastWithNextOccurrence(query)
                        }
                    }
                }

                // Filter by visible calendars (using Calendar.isVisible as source of truth)
                val visibleCalendarIds = _uiState.value.calendars
                    .filter { it.isVisible }
                    .map { it.id }
                    .toSet()
                val filteredResults = results.filter { it.event.calendarId in visibleCalendarIds }

                _uiState.update { it.copy(searchResults = filteredResults.toPersistentList()) }

                Log.d(TAG, "Search '$query' returned ${filteredResults.size} results (filter=${dateFilter::class.simpleName})")
            } catch (e: Exception) {
                Log.e(TAG, "Search error", e)
            }
        }
    }

    // ==================== Agenda ====================

    /**
     * Observe agenda events - upcoming 30 days of occurrences using reactive Flow.
     * Each recurring event instance is shown separately.
     * Respects calendar visibility settings.
     *
     * PROGRESSIVE LOADING: Events appear as they sync because this uses Flow
     * collection instead of one-shot query. Room Flow emits updates when
     * occurrences table changes.
     */
    private fun loadAgendaEvents() {
        // Cancel any previous agenda observation
        agendaEventsJob?.cancel()

        _uiState.update { it.copy(isLoadingAgenda = true) }

        val now = System.currentTimeMillis()
        val oneMonthLater = now + (30L * 24 * 60 * 60 * 1000) // 30 days

        // Start observing agenda events (reactive - updates as sync progresses)
        // Uses combine() so visibility changes also trigger updates
        agendaEventsJob = viewModelScope.launch {
            try {
                eventReader.getVisibleOccurrencesWithEventsInRangeFlow(now, oneMonthLater)
                    .map { visible -> visible.sortedBy { it.occurrence.startTs } }
                    .collect { filteredOccurrences ->
                        _uiState.update {
                            it.copy(
                                agendaOccurrences = filteredOccurrences.toPersistentList(),
                                isLoadingAgenda = false
                            )
                        }
                        Log.d(TAG, "Agenda updated: ${filteredOccurrences.size} occurrences")
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
     * Switch calendar view mode.
     * Handles data loading for each view type and cancels unnecessary jobs.
     */
    fun setViewMode(mode: ViewMode) {
        val oldMode = _uiState.value.viewMode
        if (oldMode == mode) return

        _uiState.update { it.copy(viewMode = mode) }

        when (mode) {
            ViewMode.AGENDA -> loadAgendaEvents()
            ViewMode.THREE_DAYS -> {
                // Cancel agenda observation since we're leaving agenda view
                agendaEventsJob?.cancel()
                if (currentLoadedRange == null) {
                    goToTodayWeek()
                }
            }
            ViewMode.MONTH -> {
                agendaEventsJob?.cancel()
            }
        }
    }

    fun showViewPicker() {
        _uiState.update { it.copy(showViewPicker = true) }
    }

    fun hideViewPicker() {
        _uiState.update { it.copy(showViewPicker = false) }
    }

    /**
     * Persist the user's default view to DataStore.
     */
    fun setDefaultViewMode(mode: ViewMode) {
        viewModelScope.launch {
            dataStore.setDefaultCalendarView(mode.key)
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
     * Reload the current view (dots and selected day).
     *
     * Note: loadEventsForSelectedDay now uses Flow, so calling it restarts
     * the observation which will emit current data and continue auto-updating.
     * This is intentionally kept for explicit refresh scenarios like:
     * - Calendar visibility toggle
     * - Event CRUD operations
     * - Sync completion
     */
    private fun reloadCurrentView() {
        buildEventDots(_uiState.value.viewingYear, _uiState.value.viewingMonth)
        if (_uiState.value.selectedDate != 0L) {
            loadEventsForSelectedDay(_uiState.value.selectedDate)
        }
        // Also reload week view if 3-day view is active
        if (_uiState.value.viewMode == ViewMode.THREE_DAYS &&
            _uiState.value.weekViewStartDate != 0L) {
            loadEventsForWeek(_uiState.value.weekViewStartDate)
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
                val reminders = buildRemindersList(formState.reminder1Minutes, formState.reminder2Minutes)

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
                                // Preserve these fields from master for round-trip fidelity:
                                timezone = masterEvent.timezone,
                                status = masterEvent.status,
                                transp = masterEvent.transp,
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

    /**
     * Build reminders list from form values.
     * Converts minutes to ISO 8601 duration format (e.g., -PT15M for 15 minutes before).
     */
    private fun buildRemindersList(reminder1: Int, reminder2: Int): List<String>? {
        val reminders = mutableListOf<String>()

        if (reminder1 >= 0) {
            reminders.add(minutesToIsoDuration(reminder1))
        }
        if (reminder2 >= 0) {
            reminders.add(minutesToIsoDuration(reminder2))
        }

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
     * Get default calendar ID for new events.
     */
    suspend fun getDefaultCalendarId(): Long? {
        return withContext(ioDispatcher) {
            eventCoordinator.getDefaultCalendar()?.id
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
