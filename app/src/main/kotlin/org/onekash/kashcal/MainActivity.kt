package org.onekash.kashcal

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.UserPreferencesRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.data.ics.IcsParserService
import org.onekash.kashcal.ui.components.AppInfoSheet
import org.onekash.kashcal.ui.components.CalendarVisibilitySheet
import org.onekash.kashcal.ui.components.EventFormSheet
import org.onekash.kashcal.ui.components.EventQuickViewSheet
import org.onekash.kashcal.ui.components.IcsImportSheet
import org.onekash.kashcal.ui.components.NotificationPermissionDialog
import org.onekash.kashcal.ui.components.OnboardingBanner
import org.onekash.kashcal.ui.components.SyncChangesBottomSheet
import org.onekash.kashcal.util.IcsExporter
import org.onekash.kashcal.util.IcsFileReader
import org.onekash.kashcal.util.location.LocationSuggestionService
import org.onekash.kashcal.ui.permission.NotificationPermissionManager
import org.onekash.kashcal.ui.permission.NotificationPermissionManager.PermissionState
import org.onekash.kashcal.ui.screens.HomeScreen
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.onekash.kashcal.ui.viewmodels.DateFilter
import org.onekash.kashcal.ui.viewmodels.HomeViewModel
import org.onekash.kashcal.ui.viewmodels.PendingAction
import org.onekash.kashcal.reminder.notification.ReminderNotificationManager
import org.onekash.kashcal.util.CalendarIntentData
import org.onekash.kashcal.util.CalendarIntentParser
import org.onekash.kashcal.util.DateTimeUtils
import javax.inject.Inject

private const val TAG = "MainActivity"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()
    private var isFirstResume = true
    // Skip sync when returning from internal activities (currently only SettingsActivity)
    // Note: Share/Export choosers are NOT internal - user leaves app, sync on return is appropriate
    private var returningFromInternalActivity = false

    @Inject
    lateinit var eventCoordinator: EventCoordinator

    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    @Inject
    lateinit var icsExporter: IcsExporter

    @Inject
    lateinit var locationSuggestionService: LocationSuggestionService

    @Inject
    lateinit var icsFileReader: IcsFileReader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        enableEdgeToEdge()

        // Handle webcal:// deep link if present
        handleIncomingIntent(intent)

        setContent {
            KashCalTheme {
                val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
                val isOnline by homeViewModel.isOnline.collectAsStateWithLifecycle()
                val defaultReminderTimed by homeViewModel.defaultReminderTimed.collectAsStateWithLifecycle()
                val defaultReminderAllDay by homeViewModel.defaultReminderAllDay.collectAsStateWithLifecycle()
                val defaultEventDuration by homeViewModel.defaultEventDuration.collectAsStateWithLifecycle()

                val coroutineScope = rememberCoroutineScope()

                // Notification permission state and manager
                val notificationPermissionManager = remember {
                    NotificationPermissionManager(this@MainActivity, userPreferencesRepository)
                }
                var pendingPermissionCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
                var showNotificationRationale by remember { mutableStateOf(false) }

                // Permission launcher for POST_NOTIFICATIONS
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    coroutineScope.launch {
                        if (isGranted) {
                            notificationPermissionManager.onPermissionGranted()
                        } else {
                            notificationPermissionManager.onPermissionDenied()
                        }
                    }
                    pendingPermissionCallback?.invoke(isGranted)
                    pendingPermissionCallback = null
                }

                // Event form sheet state
                var showEventFormSheet by remember { mutableStateOf(false) }
                var editingEventId by remember { mutableStateOf<Long?>(null) }
                var newEventStartTs by remember { mutableStateOf<Long?>(null) }
                var eventOccurrenceTs by remember { mutableStateOf<Long?>(null) }
                var duplicateFromEvent by remember { mutableStateOf<Event?>(null) }
                var calendarIntentData by remember { mutableStateOf<CalendarIntentData?>(null) }
                var calendarIntentInvitees by remember { mutableStateOf<List<String>>(emptyList()) }

                // Event quick view sheet state
                var showQuickViewSheet by remember { mutableStateOf(false) }
                var quickViewEvent by remember { mutableStateOf<Event?>(null) }
                var quickViewOccurrenceTs by remember { mutableStateOf<Long?>(null) }

                // Calendar visibility sheet state
                var showCalendarVisibilitySheet by remember { mutableStateOf(false) }

                // ICS import state
                var icsImportEvents by remember { mutableStateOf<List<Event>>(emptyList()) }
                var showIcsImportSheet by remember { mutableStateOf(false) }

                // Process pending actions from intents (notification, widget, shortcut, ICS file)
                // Uses ViewModel StateFlow pattern - Android's recommended approach for UI events
                // @see https://developer.android.com/topic/architecture/ui-layer/events
                LaunchedEffect(uiState.pendingAction) {
                    uiState.pendingAction?.let { action ->
                        Log.d(TAG, "Processing pending action: $action")

                        try {
                            when (action) {
                                is PendingAction.ShowEventQuickView -> {
                                    val event = homeViewModel.getEventForEdit(action.eventId)
                                    if (event != null) {
                                        quickViewEvent = event
                                        quickViewOccurrenceTs = action.occurrenceTs
                                        showQuickViewSheet = true
                                    } else {
                                        Log.w(TAG, "${action.source}: Event ${action.eventId} not found")
                                        homeViewModel.showSnackbar("Event not found")
                                    }
                                }
                                is PendingAction.CreateEvent -> {
                                    // Use provided startTs or default to today at next hour
                                    val startTs = action.startTs ?: run {
                                        val now = java.util.Calendar.getInstance()
                                        val nextHour = (now.get(java.util.Calendar.HOUR_OF_DAY) + 1) % 24
                                        java.util.Calendar.getInstance().apply {
                                            set(java.util.Calendar.HOUR_OF_DAY, nextHour)
                                            set(java.util.Calendar.MINUTE, 0)
                                            set(java.util.Calendar.SECOND, 0)
                                        }.timeInMillis
                                    }
                                    editingEventId = null
                                    newEventStartTs = startTs
                                    eventOccurrenceTs = null
                                    showEventFormSheet = true
                                }
                                is PendingAction.OpenSearch -> {
                                    homeViewModel.activateSearch()
                                }
                                is PendingAction.GoToToday -> {
                                    homeViewModel.goToToday()
                                }
                                is PendingAction.GoToDate -> {
                                    val date = org.onekash.kashcal.ui.util.DayPagerUtils.dayCodeToLocalDate(action.dayCode)
                                    homeViewModel.navigateToDate(date)
                                }
                                is PendingAction.ImportIcsFile -> {
                                    Log.d(TAG, "Processing ICS file import: ${action.uri}")
                                    val result = icsFileReader.readIcsContent(action.uri)
                                    result.onSuccess { content ->
                                        try {
                                            val events = IcsParserService.parseIcsContent(
                                                content = content,
                                                calendarId = 0, // Will be set during import
                                                subscriptionId = 0 // Not a subscription
                                            )
                                            if (events.isNotEmpty()) {
                                                Log.d(TAG, "Parsed ${events.size} events from ICS file")
                                                icsImportEvents = events
                                                showIcsImportSheet = true
                                            } else {
                                                Log.w(TAG, "No events found in ICS file")
                                                homeViewModel.showSnackbar("No events found in file")
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to parse ICS file", e)
                                            homeViewModel.showSnackbar("Invalid ICS file")
                                        }
                                    }.onFailure { e ->
                                        Log.e(TAG, "Failed to read ICS file", e)
                                        homeViewModel.showSnackbar("Could not read file")
                                    }
                                }
                                is PendingAction.CreateEventFromCalendarIntent -> {
                                    // Handle calendar intent from other apps (Gmail, Chrome, etc.)
                                    Log.d(TAG, "Processing calendar intent: title=${action.data.title}")
                                    editingEventId = null
                                    newEventStartTs = action.data.startTimeMillis
                                    eventOccurrenceTs = null
                                    calendarIntentData = action.data
                                    calendarIntentInvitees = action.invitees
                                    showEventFormSheet = true
                                }
                            }
                            // IMPORTANT: clearPendingAction() must be called AFTER all suspend work
                            // completes. Calling it before changes the LaunchedEffect key, cancelling
                            // the coroutine at the next suspension point.
                            // See: developer.android.com/topic/architecture/ui-layer/events
                            homeViewModel.clearPendingAction()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e  // Don't catch cancellation
                        } catch (e: Exception) {
                            homeViewModel.clearPendingAction()  // Clear on error too
                            Log.e(TAG, "Error processing pending action: $action", e)
                            val message = e.message?.take(50) ?: "Unknown error"
                            homeViewModel.showSnackbar("Action failed: $message")
                        }
                    }
                }

                // Trigger startup sync when configured
                LaunchedEffect(uiState.isConfigured) {
                    if (uiState.isConfigured) {
                        homeViewModel.triggerStartupSync()
                    }
                }

                // Ensure local calendar exists on startup
                LaunchedEffect(Unit) {
                    eventCoordinator.ensureLocalCalendarExists()
                    homeViewModel.refreshCalendars()
                }

                Log.d(TAG, "Composing with ${uiState.selectedDayEvents.size} day events")

                HomeScreen(
                    uiState = uiState,
                    isOnline = isOnline,
                    // Navigation callbacks
                    onDateSelected = { dateMillis -> homeViewModel.selectDate(dateMillis) },
                    onGoToToday = { homeViewModel.goToToday() },
                    onSetViewingMonth = { year, month -> homeViewModel.setViewingMonth(year, month) },
                    onClearNavigateToToday = { homeViewModel.clearNavigateToToday() },
                    onClearNavigateToMonth = { homeViewModel.clearNavigateToMonth() },
                    // Event callbacks
                    onEventClick = { event, occurrenceTs ->
                        Log.d(TAG, "Event clicked: ${event.title}, occurrenceTs=$occurrenceTs")
                        quickViewEvent = event
                        quickViewOccurrenceTs = occurrenceTs
                        showQuickViewSheet = true
                    },
                    onCreateEvent = {
                        Log.d(TAG, "Create event clicked")

                        // Check if in 3-day view
                        val isInThreeDaysView = uiState.viewMode == org.onekash.kashcal.ui.viewmodels.ViewMode.THREE_DAYS

                        val eventTimestamp = if (isInThreeDaysView && uiState.weekViewStartDate != 0L) {
                            // 3-day view: use current pager position and scroll position
                            val dayIndex = uiState.weekViewPagerPosition

                            // Calculate visible hour from scroll position
                            // Hour height is 60.dp, scroll position is in pixels
                            // Approximate conversion: scroll / densityDp / 60 + 6 (START_HOUR)
                            // Use a simplified approach: estimate based on screen density (~2.75)
                            val hourHeightPx = 60 * 2.75f  // Approximate for typical density
                            val visibleHour = (uiState.weekViewScrollPosition / hourHeightPx).toInt() + 6  // START_HOUR = 6
                            val hour = visibleHour.coerceIn(6, 22)

                            val eventCal = java.util.Calendar.getInstance().apply {
                                timeInMillis = uiState.weekViewStartDate
                                add(java.util.Calendar.DAY_OF_YEAR, dayIndex)
                                set(java.util.Calendar.HOUR_OF_DAY, hour)
                                set(java.util.Calendar.MINUTE, 0)
                                set(java.util.Calendar.SECOND, 0)
                            }
                            Log.d(TAG, "Week view FAB: dayIndex=$dayIndex, hour=$hour")
                            eventCal.timeInMillis
                        } else {
                            // Month view: use selected date with next hour
                            // 0L = no date selected (sentinel from HomeUiState.selectedDate default)
                            val selectedDateMillis = if (uiState.selectedDate != 0L) {
                                uiState.selectedDate
                            } else {
                                System.currentTimeMillis()
                            }
                            val now = java.util.Calendar.getInstance()
                            val nextHour = (now.get(java.util.Calendar.HOUR_OF_DAY) + 1) % 24
                            val eventCal = java.util.Calendar.getInstance().apply {
                                timeInMillis = selectedDateMillis
                                set(java.util.Calendar.HOUR_OF_DAY, nextHour)
                                set(java.util.Calendar.MINUTE, 0)
                                set(java.util.Calendar.SECOND, 0)
                            }
                            eventCal.timeInMillis
                        }

                        editingEventId = null
                        newEventStartTs = eventTimestamp
                        eventOccurrenceTs = null
                        showEventFormSheet = true
                    },
                    onCreateEventWithDateTime = { timestampMs ->
                        Log.d(TAG, "Create event with date/time: $timestampMs")
                        editingEventId = null
                        newEventStartTs = timestampMs
                        eventOccurrenceTs = null
                        showEventFormSheet = true
                    },
                    // Sync callbacks
                    onRefresh = { homeViewModel.refreshSync() },
                    // Search callbacks
                    onSearchClick = { homeViewModel.activateSearch() },
                    onSearchClose = { homeViewModel.deactivateSearch() },
                    onSearchQueryChange = { query -> homeViewModel.updateSearchQuery(query) },
                    onSearchResultClick = { event, nextOccurrenceTs ->
                        quickViewEvent = event
                        quickViewOccurrenceTs = nextOccurrenceTs  // Pass occurrence context for recurring events
                        showQuickViewSheet = true
                    },
                    onSearchIncludePastChange = { homeViewModel.toggleSearchIncludePast() },
                    // Search date filter callbacks
                    onSearchDateFilterChange = { filter -> homeViewModel.setSearchDateFilter(filter) },
                    onSearchShowDatePicker = { homeViewModel.showSearchDatePicker() },
                    onSearchHideDatePicker = { homeViewModel.hideSearchDatePicker() },
                    onSearchDateSelected = { dateMs -> homeViewModel.onSearchDateSelected(dateMs) },
                    // Settings/filter callbacks
                    onSettingsClick = {
                        launchInternalActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    },
                    onFilterClick = { showCalendarVisibilitySheet = true },
                    // Info callbacks
                    onInfoClick = { homeViewModel.toggleAppInfoSheet() },
                    // View picker callbacks
                    onViewPickerClick = { homeViewModel.showViewPicker() },
                    onViewPickerDismiss = { homeViewModel.hideViewPicker() },
                    onViewSelect = { mode ->
                        homeViewModel.hideViewPicker()
                        homeViewModel.setViewMode(mode)
                    },
                    onSetDefaultView = { mode ->
                        homeViewModel.setDefaultViewMode(mode)
                        homeViewModel.showSnackbar("Default view set to ${
                            when (mode) {
                                org.onekash.kashcal.ui.viewmodels.ViewMode.MONTH -> "Month"
                                org.onekash.kashcal.ui.viewmodels.ViewMode.AGENDA -> "Agenda"
                                org.onekash.kashcal.ui.viewmodels.ViewMode.THREE_DAYS -> "3 Days"
                            }
                        }")
                    },
                    // Year overlay callbacks
                    onMonthHeaderClick = { homeViewModel.toggleYearOverlay() },
                    onYearOverlayDismiss = { homeViewModel.toggleYearOverlay() },
                    onMonthSelected = { year, month -> homeViewModel.navigateToMonth(year, month) },
                    // Week view callbacks (infinite day pager)
                    onPreviousPage = { homeViewModel.navigateDaysPagerPrevious() },
                    onNextPage = { homeViewModel.navigateDaysPagerNext() },
                    onDayPagerPageChanged = { page -> homeViewModel.onDayPagerPageChanged(page) },
                    onWeekDatePickerRequest = { homeViewModel.showWeekViewDatePicker() },
                    onWeekDatePickerDismiss = { homeViewModel.hideWeekViewDatePicker() },
                    onWeekDateSelected = { dateMs -> homeViewModel.onWeekViewDateSelected(dateMs) },
                    onWeekScrollPositionChange = { position -> homeViewModel.setWeekViewScrollPosition(position) },
                    onClearPendingWeekPagerPosition = { homeViewModel.clearPendingWeekViewPagerPosition() },
                    // Agenda scroll callback
                    onClearScrollAgendaToTop = { homeViewModel.clearScrollAgendaToTop() },
                    // Snackbar callback
                    onClearSnackbar = { homeViewModel.clearSnackbar() },
                    // URL callback (for error actions)
                    onClearPendingUrl = { homeViewModel.clearPendingUrl() },
                    // Day pager cache callbacks
                    onLoadEventsForDayPagerRange = { centerDateMs -> homeViewModel.loadEventsForDayPagerRange(centerDateMs) },
                    shouldRefreshDayPagerCache = { currentDateMs -> homeViewModel.shouldRefreshDayPagerCache(currentDateMs) }
                )

                // Calendar Visibility Sheet
                if (showCalendarVisibilitySheet) {
                    CalendarVisibilitySheet(
                        calendarGroups = uiState.calendarGroups,
                        onToggleCalendar = { calendarId, _ ->
                            homeViewModel.toggleCalendarVisibility(calendarId)
                        },
                        onShowAll = { homeViewModel.showAllCalendars() },
                        onDismiss = { showCalendarVisibilitySheet = false }
                    )
                }

                // Event Quick View Sheet
                if (showQuickViewSheet && quickViewEvent != null) {
                    val event = quickViewEvent!!
                    val calendar = uiState.calendars.find { it.id == event.calendarId }
                    val calendarColor = calendar?.color ?: 0xFF6200EE.toInt()
                    val calendarName = calendar?.displayName ?: "Calendar"

                    EventQuickViewSheet(
                        event = event,
                        calendarColor = calendarColor,
                        calendarName = calendarName,
                        occurrenceTs = quickViewOccurrenceTs,
                        showEventEmojis = uiState.showEventEmojis,
                        isReadOnlyCalendar = calendar?.isReadOnly ?: false,
                        onDismiss = {
                            showQuickViewSheet = false
                            quickViewEvent = null
                            quickViewOccurrenceTs = null
                        },
                        onEdit = {
                            // Edit all occurrences
                            showQuickViewSheet = false
                            // BUG FIX: For exception events, edit the master when "Edit All" is selected
                            editingEventId = event.originalEventId ?: event.id
                            eventOccurrenceTs = null
                            newEventStartTs = null
                            quickViewEvent = null
                            quickViewOccurrenceTs = null
                            showEventFormSheet = true
                        },
                        onEditOccurrence = {
                            // Edit just this occurrence - use the tapped occurrence timestamp
                            showQuickViewSheet = false
                            // Load the actual event (exception if exists) to show current data
                            // Defensive check in saveEvent() resolves to master ID for operations
                            editingEventId = event.id
                            // BUG FIX #2: For exception events, use originalInstanceTime (not modified startTs)
                            eventOccurrenceTs = event.originalInstanceTime ?: quickViewOccurrenceTs ?: event.startTs
                            newEventStartTs = null
                            quickViewEvent = null
                            quickViewOccurrenceTs = null
                            showEventFormSheet = true
                        },
                        onDeleteSingle = {
                            // Optimistic UI: capture data, dismiss immediately, delete in background
                            val eventId = event.id
                            showQuickViewSheet = false
                            quickViewEvent = null
                            quickViewOccurrenceTs = null
                            homeViewModel.deleteEventOptimistic(eventId)
                        },
                        onDeleteOccurrence = {
                            // Optimistic UI: capture data, dismiss immediately, delete in background
                            // BUG FIX #1: For exception events, use master ID for recurring operations
                            val masterEventId = event.originalEventId ?: event.id
                            // BUG FIX #2: For exception events, use originalInstanceTime (not modified startTs)
                            val occTs = event.originalInstanceTime ?: quickViewOccurrenceTs ?: event.startTs
                            showQuickViewSheet = false
                            quickViewEvent = null
                            quickViewOccurrenceTs = null
                            homeViewModel.deleteSingleOccurrence(masterEventId, occTs)
                        },
                        onDeleteFuture = {
                            // Optimistic UI: capture data, dismiss immediately, delete in background
                            // BUG FIX #1: For exception events, use master ID for recurring operations
                            val masterEventId = event.originalEventId ?: event.id
                            // BUG FIX #2: For exception events, use originalInstanceTime (not modified startTs)
                            val occTs = event.originalInstanceTime ?: quickViewOccurrenceTs ?: event.startTs
                            showQuickViewSheet = false
                            quickViewEvent = null
                            quickViewOccurrenceTs = null
                            homeViewModel.deleteThisAndFuture(masterEventId, occTs)
                        },
                        onDuplicate = {
                            // Close preview and open new event form with copied data
                            showQuickViewSheet = false
                            editingEventId = null
                            newEventStartTs = event.startTs
                            eventOccurrenceTs = null
                            duplicateFromEvent = event
                            quickViewEvent = null
                            quickViewOccurrenceTs = null
                            showEventFormSheet = true
                        },
                        onShare = {
                            // Create share intent with event details
                            val shareText = buildString {
                                appendLine(event.title)

                                // Format date/time - use user's time format preference
                                val dateFormat = java.text.SimpleDateFormat("EEE, MMM d, yyyy", java.util.Locale.getDefault())
                                val is24Hour = android.text.format.DateFormat.is24HourFormat(this@MainActivity)
                                val timePattern = DateTimeUtils.getTimePattern(uiState.timeFormat, is24Hour)
                                val timeFormat = java.text.SimpleDateFormat(timePattern, java.util.Locale.getDefault())

                                if (event.isAllDay) {
                                    // All-day: Use UTC to get correct calendar date
                                    val utcDateFormat = java.text.SimpleDateFormat("EEE, MMM d, yyyy", java.util.Locale.getDefault()).apply {
                                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                                    }
                                    val startDate = java.util.Date(event.startTs)
                                    val endDate = java.util.Date(event.endTs)

                                    // Check for multi-day
                                    val startStr = utcDateFormat.format(startDate)
                                    val endStr = utcDateFormat.format(endDate)
                                    if (startStr != endStr) {
                                        appendLine("$startStr - $endStr (All day)")
                                    } else {
                                        appendLine("$startStr (All day)")
                                    }
                                } else {
                                    // Timed event: Use local timezone
                                    val startDate = java.util.Date(event.startTs)
                                    val endDate = java.util.Date(event.endTs)
                                    appendLine("${dateFormat.format(startDate)} ${timeFormat.format(startDate)} - ${timeFormat.format(endDate)}")
                                }

                                if (!event.location.isNullOrEmpty()) {
                                    appendLine("Location: ${event.location}")
                                }

                                appendLine()
                                appendLine("Shared from KashCal")
                            }

                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            startActivity(Intent.createChooser(intent, "Share Event"))

                            showQuickViewSheet = false
                            quickViewEvent = null
                            quickViewOccurrenceTs = null
                        },
                        onExportIcs = {
                            // Export event as .ics file
                            coroutineScope.launch {
                                val eventToExport = event
                                val exceptions = if (eventToExport.isRecurring) {
                                    eventCoordinator.getExceptionsForMaster(eventToExport.id)
                                } else {
                                    emptyList()
                                }

                                icsExporter.exportEvent(
                                    context = this@MainActivity,
                                    event = eventToExport,
                                    exceptions = exceptions
                                ).onSuccess { uri ->
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/calendar"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    startActivity(Intent.createChooser(intent, "Export Event"))
                                }.onFailure { e ->
                                    Log.e(TAG, "Failed to export event", e)
                                    homeViewModel.showSnackbar("Export failed: ${e.message}")
                                }

                                showQuickViewSheet = false
                                quickViewEvent = null
                                quickViewOccurrenceTs = null
                            }
                        },
                        timeFormat = uiState.timeFormat
                    )
                }

                // Event Form Sheet
                if (showEventFormSheet) {
                    EventFormSheet(
                        eventId = editingEventId,
                        initialStartTs = newEventStartTs,
                        occurrenceTs = eventOccurrenceTs,
                        duplicateFrom = duplicateFromEvent,
                        calendarIntentData = calendarIntentData,
                        calendarIntentInvitees = calendarIntentInvitees,
                        calendars = uiState.calendars,
                        calendarGroups = uiState.calendarGroups,
                        defaultCalendarId = uiState.defaultCalendarId ?: uiState.calendars.firstOrNull()?.id,
                        onDismiss = {
                            showEventFormSheet = false
                            editingEventId = null
                            newEventStartTs = null
                            eventOccurrenceTs = null
                            duplicateFromEvent = null
                            calendarIntentData = null
                            calendarIntentInvitees = emptyList()
                        },
                        onSave = { formState ->
                            homeViewModel.saveEvent(formState)
                        },
                        onDelete = { eventId ->
                            homeViewModel.deleteEvent(eventId)
                        },
                        onLoadEvent = { eventId ->
                            homeViewModel.getEventForEdit(eventId)
                        },
                        defaultReminderTimed = defaultReminderTimed,
                        defaultReminderAllDay = defaultReminderAllDay,
                        defaultEventDuration = defaultEventDuration,
                        onRequestNotificationPermission = { callback ->
                            coroutineScope.launch {
                                try {
                                    when (notificationPermissionManager.checkPermissionState(this@MainActivity)) {
                                        PermissionState.Granted,
                                        PermissionState.NotRequired -> callback(true)

                                        PermissionState.NotYetRequested -> {
                                            pendingPermissionCallback = callback
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }

                                        PermissionState.ShouldShowRationale -> {
                                            pendingPermissionCallback = callback
                                            showNotificationRationale = true
                                        }

                                        PermissionState.PermanentlyDenied -> callback(false)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Permission check failed", e)
                                    callback(false)  // Ensure callback fires even on error
                                }
                            }
                        },
                        locationSuggestionService = locationSuggestionService,
                        timeFormat = uiState.timeFormat,
                        firstDayOfWeek = uiState.firstDayOfWeek
                    )
                }

                // App Info Sheet
                if (uiState.showAppInfoSheet) {
                    AppInfoSheet(
                        onDismiss = { homeViewModel.toggleAppInfoSheet() }
                    )
                }

                // Onboarding Banner for first-time users
                if (uiState.showOnboardingSheet) {
                    OnboardingBanner(
                        onConnect = {
                            homeViewModel.dismissOnboardingSheet()
                            // Navigate to Settings and auto-open iCloud sign-in sheet
                            launchInternalActivity(Intent(this@MainActivity, SettingsActivity::class.java).apply {
                                putExtra(SettingsActivity.EXTRA_OPEN_ICLOUD_SIGNIN, true)
                            })
                        },
                        onDismiss = {
                            homeViewModel.dismissOnboardingSheet()
                        }
                    )
                }

                // Sync Changes Bottom Sheet
                if (uiState.showSyncChangesSheet) {
                    SyncChangesBottomSheet(
                        changes = uiState.syncChanges,
                        onDismiss = { homeViewModel.dismissSyncChangesSheet() },
                        onEventClick = { eventId ->
                            homeViewModel.dismissSyncChangesSheet()
                            // Navigate to event - find and show quick view
                            coroutineScope.launch {
                                val event = homeViewModel.getEventForEdit(eventId)
                                if (event != null) {
                                    quickViewEvent = event
                                    quickViewOccurrenceTs = null
                                    showQuickViewSheet = true
                                }
                            }
                        }
                    )
                }

                // ICS Import Sheet
                if (showIcsImportSheet && icsImportEvents.isNotEmpty()) {
                    IcsImportSheet(
                        events = icsImportEvents,
                        calendars = uiState.calendars,
                        defaultCalendarId = uiState.defaultCalendarId,
                        onDismiss = {
                            showIcsImportSheet = false
                            icsImportEvents = emptyList()
                        },
                        onImport = { calendarId, events ->
                            coroutineScope.launch {
                                try {
                                    val count = eventCoordinator.importIcsEvents(events, calendarId)
                                    homeViewModel.showSnackbar(
                                        "Imported $count event${if (count != 1) "s" else ""}"
                                    )
                                    // Navigate to first event's date
                                    events.firstOrNull()?.let { firstEvent ->
                                        homeViewModel.selectDate(firstEvent.startTs)
                                    }
                                    // Refresh calendar view
                                    homeViewModel.refreshCalendars()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to import events", e)
                                    homeViewModel.showSnackbar("Import failed")
                                }
                                showIcsImportSheet = false
                                icsImportEvents = emptyList()
                            }
                        }
                    )
                }

                // Notification Permission Rationale Dialog
                if (showNotificationRationale) {
                    NotificationPermissionDialog(
                        onEnable = {
                            showNotificationRationale = false
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onNotNow = {
                            showNotificationRationale = false
                            coroutineScope.launch {
                                notificationPermissionManager.onPermissionDenied()
                            }
                            pendingPermissionCallback?.invoke(false)
                            pendingPermissionCallback = null
                        },
                        onDismiss = {
                            showNotificationRationale = false
                            coroutineScope.launch {
                                notificationPermissionManager.onPermissionDenied()
                            }
                            pendingPermissionCallback?.invoke(false)
                            pendingPermissionCallback = null
                        }
                    )
                }
            }
        }
    }

    /**
     * Launch an internal activity (e.g., SettingsActivity) and set flag to skip sync on return.
     * Uses try-catch to reset flag if launch fails (rare but possible).
     */
    private fun launchInternalActivity(intent: Intent) {
        try {
            returningFromInternalActivity = true
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch internal activity", e)
            returningFromInternalActivity = false  // Reset - don't skip sync incorrectly
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume, isFirstResume=$isFirstResume, returningFromInternal=$returningFromInternalActivity")

        if (!isFirstResume) {
            Log.d(TAG, "Returning to calendar, refreshing")
            homeViewModel.refreshICloudStatus()
            homeViewModel.refreshCalendars()

            // Only sync if returning from external navigation (not Settings)
            if (!returningFromInternalActivity) {
                homeViewModel.syncOnResumeIfNeeded()
            } else {
                Log.d(TAG, "Skipping sync - returning from internal navigation")
            }
        }
        isFirstResume = false
        returningFromInternalActivity = false  // Reset for next time
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent: action=${intent.action}")
        setIntent(intent)  // Update activity's intent so getIntent() returns the new one
        handleIncomingIntent(intent)
        // No need for intentVersion++ anymore - StateFlow handles recomposition
    }

    /**
     * Handle incoming deep links for webcal:// subscription URLs, ICS file imports,
     * and reminder notification taps.
     *
     * Uses ViewModel's setPendingAction() following Android's recommended pattern:
     * - Convert events to state (not Channels)
     * - StateFlow for one-shot events with clear after consumption
     * - ViewModel owns state, UI observes
     * - Survives configuration changes
     */
    private fun handleIncomingIntent(intent: Intent?) {
        // Handle reminder notification tap (check action first, before widget/URI handling)
        if (intent?.action == ReminderNotificationManager.ACTION_SHOW_EVENT) {
            val eventId = intent.getLongExtra(ReminderNotificationManager.EXTRA_EVENT_ID, -1)
            val occurrenceTs = intent.getLongExtra(ReminderNotificationManager.EXTRA_OCCURRENCE_TS, -1)
            if (eventId != -1L && occurrenceTs != -1L) {
                Log.d(TAG, "Reminder notification: showing event $eventId at $occurrenceTs")
                homeViewModel.setPendingAction(
                    PendingAction.ShowEventQuickView(
                        eventId = eventId,
                        occurrenceTs = occurrenceTs,
                        source = PendingAction.ShowEventQuickView.Source.REMINDER
                    )
                )
            }
            return
        }

        // Handle widget actions (check extras first, before URI handling)
        intent?.getStringExtra(org.onekash.kashcal.widget.EXTRA_ACTION)?.let { action ->
            Log.d(TAG, "Handling widget action: $action")
            when (action) {
                org.onekash.kashcal.widget.ACTION_SHOW_EVENT -> {
                    val eventId = intent.getLongExtra(org.onekash.kashcal.widget.EXTRA_EVENT_ID, -1)
                    val occurrenceTs = intent.getLongExtra(org.onekash.kashcal.widget.EXTRA_OCCURRENCE_TS, -1)
                    if (eventId != -1L && occurrenceTs != -1L) {
                        Log.d(TAG, "Widget: showing event $eventId at $occurrenceTs")
                        homeViewModel.setPendingAction(
                            PendingAction.ShowEventQuickView(
                                eventId = eventId,
                                occurrenceTs = occurrenceTs,
                                source = PendingAction.ShowEventQuickView.Source.WIDGET
                            )
                        )
                    }
                    return
                }
                org.onekash.kashcal.widget.ACTION_CREATE_EVENT -> {
                    // Check for optional start timestamp from week widget
                    val startTs = intent.getLongExtra(org.onekash.kashcal.widget.EXTRA_CREATE_EVENT_START_TS, 0L)
                    Log.d(TAG, "Widget: creating new event (startTs=$startTs)")
                    if (startTs > 0) {
                        homeViewModel.setPendingAction(PendingAction.CreateEvent(startTs = startTs))
                    } else {
                        homeViewModel.setPendingAction(PendingAction.CreateEvent())
                    }
                    return
                }
                org.onekash.kashcal.widget.ACTION_GO_TO_DATE -> {
                    val dayCode = intent.getIntExtra(org.onekash.kashcal.widget.EXTRA_DAY_CODE, 0)
                    Log.d(TAG, "Widget: navigating to date (dayCode=$dayCode)")
                    if (dayCode > 0) {
                        homeViewModel.setPendingAction(PendingAction.GoToDate(dayCode))
                    }
                    return
                }
                org.onekash.kashcal.widget.ACTION_GO_TO_TODAY -> {
                    Log.d(TAG, "Widget: navigating to today")
                    homeViewModel.setPendingAction(PendingAction.GoToToday)
                    return
                }
                org.onekash.kashcal.widget.ACTION_OPEN_SEARCH -> {
                    Log.d(TAG, "Shortcut: opening search")
                    homeViewModel.setPendingAction(PendingAction.OpenSearch)
                    return
                }
            }
        }

        // Handle calendar provider intents (ACTION_INSERT) - for "Add to Calendar" from other apps
        CalendarIntentParser.parse(intent)?.let { (data, invitees) ->
            Log.d(TAG, "Calendar intent: title=${data.title}, start=${data.startTimeMillis}, invitees=${invitees.size}")
            homeViewModel.setPendingAction(PendingAction.CreateEventFromCalendarIntent(data, invitees))
            return
        }

        // Handle URI-based intents (webcal://, ICS files)
        intent?.data?.let { uri ->
            val scheme = uri.scheme
            when {
                // webcal:// subscription links → route to Settings
                scheme == "webcal" || scheme == "webcals" -> {
                    Log.d(TAG, "Handling webcal deep link: $uri")
                    launchInternalActivity(Intent(this, SettingsActivity::class.java).apply {
                        putExtra(SettingsActivity.EXTRA_SUBSCRIPTION_URL, uri.toString())
                    })
                }
                // content:// or file:// ICS files → show import sheet
                scheme == "content" || scheme == "file" -> {
                    val mimeType = intent.type ?: contentResolver.getType(uri)
                    if (isIcsMimeType(mimeType) || uri.path?.endsWith(".ics") == true) {
                        Log.d(TAG, "Handling ICS file import: $uri (mimeType=$mimeType)")
                        homeViewModel.setPendingAction(PendingAction.ImportIcsFile(uri))
                    }
                }
            }
        }
    }

    /**
     * Check if MIME type indicates an ICS calendar file.
     */
    private fun isIcsMimeType(mimeType: String?): Boolean {
        return mimeType in listOf(
            "text/calendar",
            "application/ics",
            "text/x-vcalendar"
        )
    }
}