package org.onekash.kashcal

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import android.text.format.DateFormat
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.onekash.kashcal.domain.reader.DeviceEventReader
import org.onekash.kashcal.domain.share.singleOccurrenceForShare
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.ics.IcsParserService
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.preferences.UserPreferencesRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.mapper.toExportEvent
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.buildShareText
import org.onekash.kashcal.domain.model.toEventForDuplicate
import org.onekash.kashcal.domain.model.toEventForShareCard
import org.onekash.kashcal.reminder.device.DeviceCalendarReminderNotificationManager
import org.onekash.kashcal.reminder.notification.ReminderNotificationManager
import org.onekash.kashcal.ui.components.AppInfoSheet
import org.onekash.kashcal.ui.components.category.LocalTagColors
import org.onekash.kashcal.ui.components.DeviceEventQuickViewSheet
import org.onekash.kashcal.ui.components.EventFormSheet
import org.onekash.kashcal.ui.components.EventQuickViewSheet
import org.onekash.kashcal.ui.components.IcsImportSheet
import org.onekash.kashcal.ui.components.NotificationPermissionDialog
import org.onekash.kashcal.ui.components.OnboardingBanner
import org.onekash.kashcal.ui.components.WhatsNewBanner
import org.onekash.kashcal.ui.components.QuickAddDialog
import org.onekash.kashcal.ui.components.ShareAvailabilitySheet
import org.onekash.kashcal.ui.components.SyncChangesBottomSheet
import org.onekash.kashcal.ui.permission.AppPermissionKind
import org.onekash.kashcal.ui.permission.NotificationPermissionManager
import org.onekash.kashcal.ui.permission.NotificationPermissionManager.PermissionState
import org.onekash.kashcal.ui.lock.AppLockDisableAction
import org.onekash.kashcal.ui.lock.AppLockEnrollmentAction
import org.onekash.kashcal.ui.lock.AppLockVeil
import org.onekash.kashcal.ui.lock.decideDisableAction
import org.onekash.kashcal.ui.lock.decideEnrollmentAction
import org.onekash.kashcal.ui.model.localizedDisplayName
import org.onekash.kashcal.ui.screens.HomeScreen
import org.onekash.kashcal.ui.theme.ColorSource
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.onekash.kashcal.ui.theme.ThemeMode
import org.onekash.kashcal.ui.viewmodels.AppLockViewModel
import org.onekash.kashcal.ui.viewmodels.DeviceCalendarException
import org.onekash.kashcal.ui.viewmodels.HomeViewModel
import org.onekash.kashcal.ui.viewmodels.PendingAction
import org.onekash.kashcal.ui.viewmodels.QuickAddViewModel
import org.onekash.kashcal.ui.viewmodels.ShareAvailabilityViewModel
import org.onekash.kashcal.util.CalendarContractAction
import org.onekash.kashcal.util.CalendarIntentData
import org.onekash.kashcal.util.CalendarIntentParser
import org.onekash.kashcal.util.DateTimeUtils
import org.onekash.kashcal.util.IcsExporter
import org.onekash.kashcal.util.IcsShareIntentParser
import org.onekash.kashcal.util.ShareChooser
import org.onekash.kashcal.util.ShareIntentRouter
import org.onekash.kashcal.util.IcsFileReader
import org.onekash.kashcal.util.buildShareAvailabilityChooserIntent
import org.onekash.kashcal.util.location.LocationSuggestionService
import java.time.LocalTime
import javax.inject.Inject

private const val TAG = "MainActivity"

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()
    private val appLockViewModel: AppLockViewModel by viewModels()
    private var isFirstResume = true
    // Guards against stacking two biometric sheets (auto-fire + manual Unlock).
    private var isUnlockPromptShowing = false
    // Guards against stacking two disable-challenge sheets from rapid toggle taps.
    private var isDisablePromptShowing = false
    // Set by [launchInternalActivity], which opens SettingsActivity and the system enrollment and
    // permission settings screens, so the next onStart skips the re-lock and onResume the sync.
    // Share and export choosers leave the app, so a sync on return from them is wanted.
    private var returningFromInternalActivity = false

    @Inject
    lateinit var eventCoordinator: EventCoordinator

    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    @Inject
    lateinit var icsExporter: IcsExporter

    @Inject
    lateinit var deviceEventReader: DeviceEventReader

    @Inject
    lateinit var locationSuggestionService: LocationSuggestionService

    @Inject
    lateinit var icsFileReader: IcsFileReader

    @Inject
    lateinit var shareCardRenderer: org.onekash.kashcal.domain.share.ShareCardRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        enableEdgeToEdge()

        // Resolve the app-lock flag synchronously before first composition so the veil is in
        // place on the first frame, with no flash of calendar content. DataStore caches after
        // the first read, so a rotation re-create doesn't re-hit disk.
        val appLockEnabledAtStart = runBlocking { userPreferencesRepository.appLockEnabled.first() }
        appLockViewModel.onActivityCreated(enabled = appLockEnabledAtStart)

        // Seed the theme, color source and accent seed synchronously so the first frame renders
        // in the chosen colors, with no flash of the default or dynamic theme on cold start.
        // Read each pref once.
        val initialThemeString = runBlocking { userPreferencesRepository.theme.first() }
        val initialThemeMode = ThemeMode.fromPrefValue(initialThemeString)
        val initialColorSource = ColorSource.fromPrefValue(
            explicit = runBlocking { userPreferencesRepository.colorSource.first() },
            legacyTheme = initialThemeString,
        )
        val initialAccentSeed = runBlocking { userPreferencesRepository.accentSeed.first() }

        handleIncomingIntent(intent)

        setContent {
            val themeMode by homeViewModel.themeMode.collectAsStateWithLifecycle(initialValue = initialThemeMode)
            val colorSource by homeViewModel.colorSource.collectAsStateWithLifecycle(initialValue = initialColorSource)
            val accentSeed by homeViewModel.accentSeed.collectAsStateWithLifecycle(initialValue = initialAccentSeed)
            KashCalTheme(themeMode = themeMode, colorSource = colorSource, accentSeed = accentSeed) {
                val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
                val weekEvents by homeViewModel.weekEvents.collectAsStateWithLifecycle()
                val agendaEvents by homeViewModel.agendaEvents.collectAsStateWithLifecycle()
                val monthEvents by homeViewModel.monthEvents.collectAsStateWithLifecycle()
                val isOnline by homeViewModel.isOnline.collectAsStateWithLifecycle()
                val appLockEnabled by appLockViewModel.appLockEnabled.collectAsStateWithLifecycle()
                val defaultReminderTimed by homeViewModel.defaultReminderTimed.collectAsStateWithLifecycle()
                val defaultReminderAllDay by homeViewModel.defaultReminderAllDay.collectAsStateWithLifecycle()
                val defaultEventDuration by homeViewModel.defaultEventDuration.collectAsStateWithLifecycle()
                val quickAddEnabled by homeViewModel.quickAddEnabled.collectAsStateWithLifecycle()

                val coroutineScope = rememberCoroutineScope()

                val notificationPermissionManager = remember {
                    NotificationPermissionManager(this@MainActivity, userPreferencesRepository)
                }
                var pendingPermissionCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
                var showNotificationRationale by remember { mutableStateOf(false) }

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

                // Contacts permission state for the attendee picker. The rationale flag, sampled
                // before and after the request, tells "can ask again" from "don't ask again".
                var contactsPermissionState by remember {
                    mutableStateOf<org.onekash.kashcal.ui.permission.ContactsPermissionState>(
                        org.onekash.kashcal.ui.permission.ContactsPermissionState.NotRequested
                    )
                }
                var contactsRationaleBefore by remember { mutableStateOf(false) }
                val contactsPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    val after = ActivityCompat.shouldShowRequestPermissionRationale(
                        this@MainActivity, Manifest.permission.READ_CONTACTS
                    )
                    contactsPermissionState = org.onekash.kashcal.ui.permission.classifyAfterRequest(
                        granted = granted,
                        rationaleBefore = contactsRationaleBefore,
                        rationaleAfter = after,
                    )
                    // A denial that won't re-prompt is a "no": persist it so the banner doesn't
                    // return. A denial that's still askable leaves the banner for a later retry.
                    if (!granted && !after) {
                        homeViewModel.declineContactSuggestions()
                    }
                }

                // Attendee-editing context (account and can-send-invitations) for the form.
                var formAttendeeContext by remember {
                    mutableStateOf(org.onekash.kashcal.ui.viewmodels.FormAttendeeContext(null, true))
                }
                // The in-flight attendee-context lookup. A calendar switch cancels it; otherwise
                // two lookups could finish out of order and pin a stale context.
                var attendeeContextJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

                var showQuickAddDialog by remember { mutableStateOf(false) }
                // Seed for share-target opens; null on user-initiated opens.
                var quickAddShareSeed by remember { mutableStateOf<PendingAction.QuickAddFromText?>(null) }

                // Event form sheet state
                var showEventFormSheet by remember { mutableStateOf(false) }
                var editingEventId by remember { mutableStateOf<Long?>(null) }
                var newEventStartTs by remember { mutableStateOf<Long?>(null) }
                var eventOccurrenceTs by remember { mutableStateOf<Long?>(null) }
                var duplicateFromEvent by remember { mutableStateOf<Event?>(null) }
                // Source device calendar id for a device-event duplicate; null for Room
                // duplicates, whose source id is `Event.calendarId`.
                var duplicateFromDeviceCalendarId by remember { mutableStateOf<Long?>(null) }
                var calendarIntentData by remember { mutableStateOf<CalendarIntentData?>(null) }
                var calendarIntentInvitees by remember { mutableStateOf<List<String>>(emptyList()) }

                // Device event edit state
                var editingDeviceEventId by remember { mutableStateOf<Long?>(null) }
                var deviceEventOccurrenceTs by remember { mutableStateOf<Long?>(null) }
                var deviceEventIsAllDay by remember { mutableStateOf(false) }

                // Event quick view sheet state
                var showQuickViewSheet by remember { mutableStateOf(false) }
                var quickViewEvent by remember { mutableStateOf<Event?>(null) }
                var quickViewOccurrenceTs by remember { mutableStateOf<Long?>(null) }

                // Share-as-card sheet state
                var showShareCardSheet by remember { mutableStateOf(false) }
                var shareCardEvent by remember { mutableStateOf<Event?>(null) }
                // One-shot share-as-card coach mark, shared by the Room and device-event quick
                // views: whichever shows it first dismisses it, so it appears once per install.
                val shownShareCardTooltip by homeViewModel.shownShareCardTooltip
                    .collectAsStateWithLifecycle(initialValue = false)
                val quickViewAttendees by homeViewModel.quickViewAttendees.collectAsStateWithLifecycle()
                // Live event body for the active QuickView, re-read by id so an edit's new title
                // or time shows even if the tapped snapshot was stale.
                val liveQuickViewEvent by homeViewModel.quickViewEventLive.collectAsStateWithLifecycle()
                val formAttendees by homeViewModel.formAttendees.collectAsStateWithLifecycle()
                val formIsReadOnly by homeViewModel.formIsReadOnly.collectAsStateWithLifecycle()
                val contactsDeclined by homeViewModel.contactSuggestionsDeclined.collectAsStateWithLifecycle()
                val dayAttendeesMap by homeViewModel.dayAttendees.collectAsStateWithLifecycle()
                val pendingInvitesCount by homeViewModel.pendingInvitationsCount.collectAsStateWithLifecycle()
                val pendingInvitations by homeViewModel.pendingInvitations.collectAsStateWithLifecycle()
                // Drive HomeViewModel's attendee StateFlow from the active QuickView event.
                androidx.compose.runtime.LaunchedEffect(quickViewEvent?.id) {
                    homeViewModel.setQuickViewEventId(quickViewEvent?.id)
                }
                // Drive form-side attendee state when the form opens for an existing event.
                androidx.compose.runtime.LaunchedEffect(showEventFormSheet, editingEventId) {
                    homeViewModel.setFormEventId(if (showEventFormSheet) editingEventId else null)
                }
                // Resolve the form's attendee-editing context and the contacts permission state
                // when the sheet opens.
                androidx.compose.runtime.LaunchedEffect(showEventFormSheet, editingEventId) {
                    if (showEventFormSheet) {
                        // An edit uses the event's calendar; a new event starts in the default
                        // calendar, so resolve the context from that.
                        val calId = editingEventId?.let { homeViewModel.getEventForEdit(it)?.calendarId }
                            ?: uiState.defaultCalendar?.calendarId
                        formAttendeeContext = homeViewModel.getFormAttendeeContext(calId)
                        // Recompute the live state on every open so a grant or revoke made in
                        // system Settings shows. Don't only upgrade to Granted: a later revoke
                        // would leave a stale Granted that queries a revoked permission and
                        // hides the re-request banner.
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.READ_CONTACTS
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        contactsPermissionState = org.onekash.kashcal.ui.permission.resolveContactsPermissionState(
                            granted = granted,
                            shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                                this@MainActivity, Manifest.permission.READ_CONTACTS
                            ),
                        )
                    }
                }

                // Device event quick view sheet state
                var showDeviceQuickViewSheet by remember { mutableStateOf(false) }
                var deviceQuickViewEvent by remember { mutableStateOf<DisplayEvent.Device?>(null) }
                // Guests for the active device quick-view event, loaded by a separate Attendees
                // query (never the bulk grid read) whenever the active event changes. Null while
                // no event is active, so one event's guests don't carry into the next sheet.
                var deviceQuickViewAttendees by remember {
                    mutableStateOf<org.onekash.kashcal.ui.viewmodels.EventAttendeeUiState?>(null)
                }
                LaunchedEffect(deviceQuickViewEvent?.instance?.eventId) {
                    val event = deviceQuickViewEvent
                    deviceQuickViewAttendees = if (event == null) {
                        null
                    } else {
                        homeViewModel.getDeviceEventAttendeeState(
                            eventId = event.instance.eventId,
                            calendarId = event.instance.calendarId,
                        )
                    }
                }
                // Fresh device event body, re-read from the provider whenever the active
                // occurrence changes. The tapped snapshot can come from a list that hasn't
                // refreshed since an edit, because device changes arrive through a debounced
                // signal; this read bypasses the debounce. When it misses (for example the
                // occurrence moved), the sheet shows the snapshot.
                var deviceQuickViewEventLive by remember { mutableStateOf<DisplayEvent.Device?>(null) }
                LaunchedEffect(deviceQuickViewEvent?.instance?.eventId, deviceQuickViewEvent?.startTs) {
                    val snapshot = deviceQuickViewEvent
                    deviceQuickViewEventLive = when {
                        snapshot == null -> null
                        // One-off event: re-resolve by id so a changed start or all-day toggle
                        // still lands; a lookup keyed on the snapshot's old start would miss.
                        !snapshot.instance.hasRrule && snapshot.instance.originalId == null ->
                            homeViewModel.getDeviceEventForQuickViewById(snapshot.instance.eventId)
                        // Series or exception: keep the tapped occurrence by its start, since the
                        // id alone doesn't say which occurrence to show.
                        else -> homeViewModel.getDeviceEventForQuickView(
                            eventId = snapshot.instance.eventId,
                            occurrenceTs = snapshot.startTs,
                        )
                    }
                }

                val drawerState = rememberDrawerState(DrawerValue.Closed)

                var icsImportEvents by remember { mutableStateOf<List<Event>>(emptyList()) }
                var showIcsImportSheet by remember { mutableStateOf(false) }

                // Opens EventFormSheet from CalendarIntentData, for CreateEventFromCalendarIntent
                // and the Quick Add expand and redirect.
                val launchEventFormWithIntent = { data: CalendarIntentData, invitees: List<String> ->
                    editingEventId = null
                    newEventStartTs = data.startTimeMillis
                    eventOccurrenceTs = null
                    calendarIntentData = data
                    calendarIntentInvitees = invitees
                    showEventFormSheet = true
                }

                // Opens the share-as-card sheet for both the Room and device-event quick views,
                // so the two entry points can't drift.
                val openShareCard = { event: Event ->
                    shareCardEvent = event
                    showShareCardSheet = true
                }

                // Process the pending action [handleIncomingIntent] set from an intent
                // (notification, widget, shortcut, share, ICS file, calendar intent). It lives in
                // a ViewModel StateFlow, as Android recommends for UI events:
                // https://developer.android.com/topic/architecture/ui-layer/events
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
                                    if (quickAddEnabled && action.startTs == null) {
                                        showQuickAddDialog = true
                                    } else {
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
                                    // From an insert or edit intent, a CalendarContract URI
                                    // or a long text share.
                                    Log.d(TAG, "Processing calendar intent: title=${action.data.title}")
                                    launchEventFormWithIntent(action.data, action.invitees)
                                }
                                is PendingAction.ShowDeviceEventQuickView -> {
                                    val deviceEvent = homeViewModel.getDeviceEventForQuickView(
                                        action.eventId, action.occurrenceTs
                                    )
                                    if (deviceEvent != null) {
                                        deviceQuickViewEvent = deviceEvent
                                        showDeviceQuickViewSheet = true
                                    } else {
                                        // No exact occurrence match, for example an external
                                        // launcher's begin time slightly off the instance.
                                        navigateToDeviceEventOrNotFound(homeViewModel, action.eventId)
                                    }
                                }
                                is PendingAction.OpenDeviceEventById -> {
                                    // The intent gave only the event id. Open the quick view at the
                                    // resolved occurrence (the next instance of a series, else
                                    // DTSTART); when none resolves, for example an ended series,
                                    // fall back to the date.
                                    val deviceEvent = homeViewModel.getDeviceEventForQuickViewById(action.eventId)
                                    if (deviceEvent != null) {
                                        deviceQuickViewEvent = deviceEvent
                                        showDeviceQuickViewSheet = true
                                    } else {
                                        navigateToDeviceEventOrNotFound(homeViewModel, action.eventId)
                                    }
                                }
                                is PendingAction.QuickAddFromText -> {
                                    Log.d(TAG, "QuickAddFromText pending: text=${action.text.take(40)}")
                                    // Close peer surfaces so they don't cover the seeded dialog.
                                    showEventFormSheet = false
                                    showQuickViewSheet = false
                                    showDeviceQuickViewSheet = false
                                    quickAddShareSeed = action
                                    showQuickAddDialog = true
                                }
                            }
                            // clearPendingAction() must run after all suspend work: it changes the
                            // LaunchedEffect key, which cancels this coroutine at the next
                            // suspension point.
                            homeViewModel.clearPendingAction()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            homeViewModel.clearPendingAction()
                            Log.e(TAG, "Error processing pending action: $action", e)
                            val message = (e.message ?: e.javaClass.simpleName).take(50)
                            homeViewModel.showSnackbar("Action failed: $message")
                        }
                    }
                }

                LaunchedEffect(uiState.isConfigured) {
                    if (uiState.isConfigured) {
                        homeViewModel.triggerStartupSync()
                    }
                }

                LaunchedEffect(Unit) {
                    eventCoordinator.ensureLocalCalendarExists()
                    homeViewModel.refreshCalendars()
                }

                Log.d(TAG, "Composing with ${uiState.dayEventsCache.values.sumOf { it.size }} cached day events")

                // Publish per-tag custom colors to every chip below (home views, form, quick view,
                // week blocks) so recoloring a tag repaints its chips without threading color
                // through the occurrence stream.
                CompositionLocalProvider(LocalTagColors provides uiState.tagColors) {

                HomeScreen(
                    uiState = uiState,
                    weekEvents = weekEvents,
                    agendaEvents = agendaEvents,
                    monthEvents = monthEvents,
                    isOnline = isOnline,
                    // Navigation callbacks
                    onDateSelected = { dateMillis -> homeViewModel.selectDate(dateMillis) },
                    onGoToToday = { homeViewModel.goToToday() },
                    onSetViewingMonth = { year, month -> homeViewModel.setViewingMonth(year, month) },
                    onClearNavigateToToday = { homeViewModel.clearNavigateToToday() },
                    onClearNavigateToTodayInstant = { homeViewModel.clearNavigateToTodayInstant() },
                    onClearNavigateToMonth = { homeViewModel.clearNavigateToMonth() },
                    // Event callbacks
                    onEventClick = { event, occurrenceTs ->
                        Log.d(TAG, "Event clicked: ${event.title}, occurrenceTs=$occurrenceTs")
                        quickViewEvent = event
                        quickViewOccurrenceTs = occurrenceTs
                        showQuickViewSheet = true
                    },
                    onDeviceEventClick = { deviceEvent ->
                        Log.d(TAG, "Device event clicked: ${deviceEvent.title}")
                        deviceQuickViewEvent = deviceEvent
                        showDeviceQuickViewSheet = true
                    },
                    onCreateEvent = {
                        Log.d(TAG, "Create event clicked")

                        // When enabled, Quick Add opens in every view, the time-grid views (Day,
                        // 3-Day, Week) included: it seeds its own reference day and time.
                        if (quickAddEnabled) {
                            showQuickAddDialog = true
                        } else {
                            val eventTimestamp = if (uiState.viewMode.isTimeGrid) {
                                // Today at the next hour.
                                homeViewModel.computeTimeGridEventSeedTs()
                            } else {
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
                        }
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
                        quickViewOccurrenceTs = nextOccurrenceTs  // Next occurrence of a series.
                        showQuickViewSheet = true
                    },
                    // Search date filter callbacks
                    onSearchDateFilterChange = { filter -> homeViewModel.setSearchDateFilter(filter) },
                    onSearchShowDatePicker = { homeViewModel.showSearchDatePicker() },
                    onSearchHideDatePicker = { homeViewModel.hideSearchDatePicker() },
                    onSearchDateSelected = { dateMs -> homeViewModel.onSearchDateSelected(dateMs) },
                    // Settings/filter callbacks
                    onSettingsClick = {
                        launchInternalActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    },
                    onTagsClick = {
                        launchInternalActivity(
                            Intent(this@MainActivity, SettingsActivity::class.java)
                                .putExtra(SettingsActivity.EXTRA_OPEN_TAGS, true)
                        )
                    },
                    // App lock lives in the hub's Privacy and security section.
                    appLockEnabled = appLockEnabled,
                    onToggleAppLock = ::onToggleAppLock,
                    // The app-permissions screen deep-links to system settings.
                    onOpenPermissionSettings = ::openPermissionSettings,
                    // Drawer
                    drawerState = drawerState,
                    onDrawerToggleCalendar = { calendarId -> homeViewModel.toggleCalendarVisibility(calendarId) },
                    onDrawerToggleDeviceCalendarVisibility = { calendarId -> homeViewModel.toggleDeviceCalendarVisibility(calendarId) },
                    // Share availability
                    onShareAvailabilityClick = { homeViewModel.openShareAvailabilitySheet() },
                    // Info callbacks
                    onInfoClick = { homeViewModel.toggleAppInfoSheet() },
                    // Avatar hub: persist edited initials
                    onInitialsChange = { homeViewModel.setUserInitials(it) },
                    // View picker callback
                    onViewSelect = { mode -> homeViewModel.setViewMode(mode) },
                    // Year overlay callbacks
                    onMonthHeaderClick = { homeViewModel.toggleYearOverlay() },
                    onAgendaWeekBarToggle = {
                        homeViewModel.setAgendaWeekBarExpanded(!homeViewModel.uiState.value.agendaWeekBarExpanded)
                    },
                    onDayWeekBarToggle = {
                        homeViewModel.setDayWeekBarExpanded(!homeViewModel.uiState.value.dayWeekBarExpanded)
                    },
                    onYearOverlayDismiss = { homeViewModel.toggleYearOverlay() },
                    onMonthSelected = { year, month -> homeViewModel.navigateToMonth(year, month) },
                    // Week view callbacks (infinite day pager)
                    onDayPagerPageChanged = { page -> homeViewModel.onDayPagerPageChanged(page) },
                    onWeekDatePickerRequest = { homeViewModel.showWeekViewDatePicker() },
                    onWeekDayHeaderClick = { date -> homeViewModel.onWeekViewDayHeaderClick(date) },
                    onWeekDatePickerDismiss = { homeViewModel.hideWeekViewDatePicker() },
                    onWeekDateSelected = { dateMs -> homeViewModel.onWeekViewDateSelected(dateMs) },
                    onWeekScrollPositionChange = { position -> homeViewModel.setWeekViewScrollPosition(position) },
                    onWeekScrollMinutesChange = { minutes -> homeViewModel.setWeekViewScrollMinutes(minutes) },
                    onWeekHourHeightChange = { height -> homeViewModel.setWeekViewHourHeight(height) },
                    onAllDayRowsToggle = {
                        homeViewModel.setAllDayRowsExpanded(!homeViewModel.uiState.value.allDayRowsExpanded)
                    },
                    onClearPendingWeekPagerPosition = { homeViewModel.clearPendingWeekViewPagerPosition() },
                    onReschedule = { displayEvent, targetDate, targetStartMinutes ->
                        homeViewModel.rescheduleEvent(displayEvent, targetDate, targetStartMinutes)
                    },
                    onConfirmReschedule = { editScope -> homeViewModel.confirmReschedule(editScope) },
                    onCancelPendingReschedule = { homeViewModel.cancelPendingReschedule() },
                    onConfirmFormSave = { scope ->
                        val pending = uiState.pendingFormSave
                        homeViewModel.cancelPendingFormSave()
                        if (pending != null) {
                            coroutineScope.launch {
                                val result: Result<*> = if (pending.isRecurringDevice) {
                                    homeViewModel.saveDeviceEvent(pending.formState, scope)
                                } else {
                                    homeViewModel.saveEvent(pending.formState, scope)
                                }
                                if (result.isSuccess) {
                                    // Dismiss the form sheet and clear the edit state.
                                    showEventFormSheet = false
                                    editingEventId = null
                                    newEventStartTs = null
                                    eventOccurrenceTs = null
                                    duplicateFromEvent = null
                                    duplicateFromDeviceCalendarId = null
                                    editingDeviceEventId = null
                                    deviceEventOccurrenceTs = null
                                    deviceEventIsAllDay = false
                                    calendarIntentData = null
                                    calendarIntentInvitees = emptyList()
                                } else {
                                    // Keep the form open with the user's edits for a
                                    // retry. No error message reaches the user: the
                                    // event save only logs, and the error state the
                                    // device save sets is not rendered. The tick
                                    // resets the form's isSaving flag, set when the
                                    // scope sheet opened, through the form's
                                    // LaunchedEffect on `scopeSaveFailedTick`.
                                    homeViewModel.signalFormSaveFailed()
                                }
                            }
                        }
                    },
                    onCancelPendingFormSave = {
                        homeViewModel.cancelPendingFormSave()
                        // Cancel from the scope sheet returns to the dirty form;
                        // reset isSaving so the Save button re-enables.
                        homeViewModel.signalFormSaveFailed()
                    },
                    onConfirmDelete = { scope -> homeViewModel.confirmDelete(scope) },
                    onCancelPendingDelete = { homeViewModel.cancelPendingDelete() },
                    // Agenda scroll callback
                    onResume = { homeViewModel.onAppResume() },
                    onClearScrollAgendaToTop = { homeViewModel.clearScrollAgendaToTop() },
                    // Snackbar callback
                    onClearSnackbar = { homeViewModel.clearSnackbar() },
                    // URL callback (for error actions)
                    onClearPendingUrl = { homeViewModel.clearPendingUrl() },
                    // Day detail sheet callbacks
                    onShowDayDetail = { dateMs -> homeViewModel.showDayDetail(dateMs) },
                    onDismissDayDetail = { homeViewModel.dismissDayDetail() },
                    // Day pager cache callbacks
                    onLoadEventsForDayPagerRange = { centerDateMs -> homeViewModel.loadEventsForDayPagerRange(centerDateMs) },
                    shouldRefreshDayPagerCache = { currentDateMs -> homeViewModel.shouldRefreshDayPagerCache(currentDateMs) },
                    onEnsureDotsForYear = { year -> homeViewModel.ensureDotsForYear(year) },
                    dayAttendees = dayAttendeesMap,
                    onSetVisibleEventIds = { ids -> homeViewModel.setVisibleEventIds(ids) },
                    pendingInvitesCount = pendingInvitesCount,
                    pendingInvitations = pendingInvitations,
                    onOpenInvitationInbox = { homeViewModel.openInvitationInbox() },
                    onDismissInvitationInbox = { homeViewModel.dismissInvitationInbox() },
                    onRsvpFromInbox = { eventId, status -> homeViewModel.replyRsvp(eventId, status) }
                )

                // Event Quick View Sheet
                if (showQuickViewSheet && quickViewEvent != null) {
                    val snapshot = quickViewEvent!!
                    // Prefer the re-read event when it's for the active id; fall back to the tapped
                    // snapshot until the by-id flow warms up or when the event was deleted. The id
                    // check stops a lagging flow from briefly showing the prior event.
                    val event = liveQuickViewEvent?.takeIf { it.id == snapshot.id } ?: snapshot
                    val calendar = uiState.calendars.find { it.id == event.calendarId }
                    val calendarColor = calendar?.color ?: 0xFF6200EE.toInt()
                    val calendarName = calendar?.localizedDisplayName(LocalContext.current.resources)
                        ?: stringResource(R.string.label_calendar)

                    EventQuickViewSheet(
                        event = event,
                        calendarColor = calendarColor,
                        calendarName = calendarName,
                        occurrenceTs = quickViewOccurrenceTs,
                        showEventEmojis = uiState.showEventEmojis,
                        isReadOnlyCalendar = calendar?.isReadOnly ?: false,
                        attendees = quickViewAttendees?.models ?: emptyList(),
                        isCurrentUserOnList = quickViewAttendees?.isCurrentUserOnList ?: false,
                        onDismiss = {
                            showQuickViewSheet = false
                            quickViewEvent = null
                            quickViewOccurrenceTs = null
                        },
                        onEdit = {
                            // One Edit path: the form opens on the tapped
                            // occurrence of a series or exception, and on the
                            // event itself with no occurrenceTs otherwise. The
                            // scope (THIS_EVENT, THIS_AND_FUTURE, ALL_EVENTS)
                            // is chosen at save time.
                            val isRecurring = event.rrule != null
                            val isException = event.originalEventId != null
                            showQuickViewSheet = false
                            editingEventId = event.id
                            eventOccurrenceTs = when {
                                // One-off: no occurrence ts. A non-null value
                                // routes saveEvent through editSingleOccurrence,
                                // which throws on a non-recurring master.
                                !isRecurring && !isException -> null
                                // Exception: the original instance time
                                // anchors the exception lookup.
                                isException -> event.originalInstanceTime
                                    ?: quickViewOccurrenceTs
                                    ?: event.startTs
                                // Recurring master: the user's tapped
                                // occurrence drives the form date and
                                // the scope-sheet's occurrenceTs.
                                else -> quickViewOccurrenceTs ?: event.startTs
                            }
                            newEventStartTs = null
                            quickViewEvent = null
                            quickViewOccurrenceTs = null
                            showEventFormSheet = true
                        },
                        onEditOccurrence = { /* unused after save-time scope */ },
                        onDeleteSingle = {
                            // - One-off: delete the row.
                            // - Exception: deleteSingleOccurrence on the
                            //   master, which adds an EXDATE; deleteEvent
                            //   refuses an exception row.
                            // - Series master: show the scope sheet.
                            val isException = event.originalEventId != null
                            val isRecurringMaster = event.rrule != null && !isException
                            when {
                                isException -> {
                                    val masterId = event.originalEventId!!
                                    val occTs = event.originalInstanceTime
                                        ?: quickViewOccurrenceTs
                                        ?: event.startTs
                                    showQuickViewSheet = false
                                    quickViewEvent = null
                                    quickViewOccurrenceTs = null
                                    homeViewModel.deleteSingleOccurrence(masterId, occTs)
                                }
                                isRecurringMaster -> {
                                    val occTs = quickViewOccurrenceTs ?: event.startTs
                                    showQuickViewSheet = false
                                    quickViewEvent = null
                                    quickViewOccurrenceTs = null
                                    homeViewModel.requestDeleteRoom(
                                        event = event,
                                        occurrenceTs = occTs,
                                        masterStartTs = event.startTs,
                                        isDetachedException = false,
                                        isAllDay = event.isAllDay,
                                    )
                                }
                                else -> {
                                    val eventId = event.id
                                    showQuickViewSheet = false
                                    quickViewEvent = null
                                    quickViewOccurrenceTs = null
                                    homeViewModel.deleteEventOptimistic(eventId)
                                }
                            }
                        },
                        onDuplicate = {
                            showQuickViewSheet = false
                            editingEventId = null
                            newEventStartTs = event.startTs
                            eventOccurrenceTs = null
                            duplicateFromEvent = event
                            duplicateFromDeviceCalendarId = null
                            quickViewEvent = null
                            quickViewOccurrenceTs = null
                            showEventFormSheet = true
                        },
                        onShare = {
                            val shareText = buildString {
                                appendLine(event.title)

                                // The time follows the user's time format preference.
                                val dateFormat = java.text.SimpleDateFormat(DateTimeUtils.localizedPattern("yEEEMMMd"), java.util.Locale.getDefault())
                                val is24Hour = android.text.format.DateFormat.is24HourFormat(this@MainActivity)
                                val timePattern = DateTimeUtils.getTimePattern(uiState.timeFormat, is24Hour)
                                val timeFormat = java.text.SimpleDateFormat(timePattern, java.util.Locale.getDefault())

                                if (event.isAllDay) {
                                    // All-day starts are stored at UTC midnight, so format in UTC.
                                    val utcDateFormat = java.text.SimpleDateFormat(DateTimeUtils.localizedPattern("yEEEMMMd"), java.util.Locale.getDefault()).apply {
                                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                                    }
                                    val startDate = java.util.Date(event.startTs)
                                    val endDate = java.util.Date(event.endTs)
                                    val allDay = getString(R.string.label_all_day)

                                    val startStr = utcDateFormat.format(startDate)
                                    val endStr = utcDateFormat.format(endDate)
                                    if (startStr != endStr) {
                                        appendLine("$startStr - $endStr ($allDay)")
                                    } else {
                                        appendLine("$startStr ($allDay)")
                                    }
                                } else {
                                    // Timed events format in the device zone.
                                    val startDate = java.util.Date(event.startTs)
                                    val endDate = java.util.Date(event.endTs)
                                    val startDateStr = dateFormat.format(startDate)
                                    val endDateStr = dateFormat.format(endDate)
                                    if (startDateStr != endDateStr) {
                                        appendLine("$startDateStr ${timeFormat.format(startDate)} - $endDateStr ${timeFormat.format(endDate)}")
                                    } else {
                                        appendLine("$startDateStr ${timeFormat.format(startDate)} - ${timeFormat.format(endDate)}")
                                    }
                                }

                                if (!event.location.isNullOrEmpty()) {
                                    appendLine("${getString(R.string.label_location)}: ${event.location}")
                                }

                                appendLine()
                                appendLine(getString(R.string.share_from_kashcal_footer))
                            }

                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            startActivity(ShareChooser.createKashCalChooser(this@MainActivity, intent, getString(R.string.share_as_card_chooser_title)))

                            showQuickViewSheet = false
                            quickViewEvent = null
                            quickViewOccurrenceTs = null
                        },
                        onExportIcs = {
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
                                    startActivity(ShareChooser.createKashCalChooser(this@MainActivity, intent, "Export Event"))
                                }.onFailure { e ->
                                    Log.e(TAG, "Failed to export event", e)
                                    homeViewModel.showSnackbar("Export failed: ${e.message}")
                                }

                                showQuickViewSheet = false
                                quickViewEvent = null
                                quickViewOccurrenceTs = null
                            }
                        },
                        onRsvp = { status -> homeViewModel.replyRsvp(event.id, status) },
                        onShareAsCard = {
                            // Keep the QuickViewSheet open; the user may back
                            // out of the share and return to it.
                            openShareCard(event)
                        },
                        showShareCardTooltip = !shownShareCardTooltip,
                        onShareCardTooltipDismissed = {
                            homeViewModel.markShareCardTooltipShown()
                        },
                        timeFormat = uiState.timeFormat
                    )
                }

                // Share-as-card preview sheet, opened from either quick view. It renders a
                // 1080×1350 PNG from the on-screen preview's GraphicsLayer capture.
                if (showShareCardSheet && shareCardEvent != null) {
                    val event = shareCardEvent!!
                    // UTC for all-day events, else the event's zone or the system zone; the
                    // rules are on shareCardZone.
                    val zone = org.onekash.kashcal.domain.share.shareCardZone(
                        timezone = event.timezone,
                        isAllDay = event.isAllDay,
                    )
                    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                    val locales = androidx.core.os.ConfigurationCompat.getLocales(configuration)
                    val locale = if (locales.isEmpty) java.util.Locale.US else locales.get(0)!!
                    val is24Hour = android.text.format.DateFormat.is24HourFormat(this@MainActivity)

                    val viewerStartTs = quickViewOccurrenceTs ?: event.startTs
                    val viewerEndTs = if (quickViewOccurrenceTs != null) {
                        quickViewOccurrenceTs!! + (event.endTs - event.startTs)
                    } else {
                        event.endTs
                    }

                    // Multi-day means the dates differ and it lasts at least 18 hours. The floor
                    // excludes overnight events (Sat 10 PM to Sun 2 AM), which span two dates but
                    // read as one Saturday night.
                    val isMultiDay = remember(viewerStartTs, viewerEndTs, zone) {
                        val startDate = java.time.Instant.ofEpochMilli(viewerStartTs)
                            .atZone(zone).toLocalDate()
                        val endDate = java.time.Instant.ofEpochMilli(viewerEndTs)
                            .atZone(zone).toLocalDate()
                        val durationMs = viewerEndTs - viewerStartTs
                        val eighteenHoursMs = 18L * 60 * 60 * 1000
                        startDate != endDate && durationMs >= eighteenHoursMs
                    }

                    // A single-day chip, or a range chip ("MAY 31 – JUN 3") for multi-day. The
                    // sealed DateChipText carries both shapes.
                    val dateChip = remember(viewerStartTs, viewerEndTs, isMultiDay, zone, locale) {
                        if (isMultiDay) {
                            org.onekash.kashcal.domain.share.DateChipFormatter
                                .formatRange(viewerStartTs, viewerEndTs, zone, locale)
                        } else {
                            org.onekash.kashcal.domain.share.DateChipFormatter
                                .format(viewerStartTs, zone, locale)
                        }
                    }
                    val stripe = remember(viewerStartTs, viewerEndTs, event.isAllDay, zone) {
                        org.onekash.kashcal.domain.share.DayStripeMath.compute(
                            startTs = viewerStartTs,
                            endTs = viewerEndTs,
                            isAllDay = event.isAllDay,
                            zone = zone,
                        )
                    }
                    val stripeLabels = remember(is24Hour) {
                        org.onekash.kashcal.domain.share.StripeLabels.labelsFor(is24Hour)
                    }
                    val timePattern = remember(uiState.timeFormat, is24Hour) {
                        org.onekash.kashcal.util.DateTimeUtils.getTimePattern(
                            uiState.timeFormat, is24Hour
                        )
                    }
                    val allDayLabel = stringResource(R.string.share_as_card_all_day)
                    // The card's only subtitle source:
                    //   single-day all-day: "All day"
                    //   single-day timed:   "9:00 AM – 5:00 PM"
                    //   multi-day all-day:  "Sun – Wed · All day"
                    //   multi-day timed:    "Sun – Wed"
                    // A multi-day chip already shows the dates, so the subtitle adds weekdays. A
                    // 9–5 range on a 4-day timed event would read as 9 AM to 5 PM each day; the
                    // .ics carries the exact times.
                    val timeRangeText = remember(
                        viewerStartTs, viewerEndTs, event.isAllDay, isMultiDay,
                        timePattern, zone, locale, allDayLabel,
                    ) {
                        when {
                            isMultiDay -> {
                                val dow = org.onekash.kashcal.domain.share.DateChipFormatter
                                    .formatDowRange(viewerStartTs, viewerEndTs, zone, locale)
                                if (event.isAllDay) "$dow · $allDayLabel" else dow
                            }
                            event.isAllDay -> allDayLabel
                            else -> {
                                val start = java.time.Instant.ofEpochMilli(viewerStartTs)
                                    .atZone(zone)
                                    .format(java.time.format.DateTimeFormatter.ofPattern(timePattern, locale))
                                val end = java.time.Instant.ofEpochMilli(viewerEndTs)
                                    .atZone(zone)
                                    .format(java.time.format.DateTimeFormatter.ofPattern(timePattern, locale))
                                "$start – $end"
                            }
                        }
                    }
                    // Kept for ShareCardComposable's signature, which doesn't read it;
                    // timeRangeText is the subtitle.
                    val multiDayRangeText: String? = null

                    val shareCardViewModel: org.onekash.kashcal.ui.viewmodels.ShareCardViewModel =
                        hiltViewModel()
                    val selectedStyle by shareCardViewModel.selectedStyle.collectAsStateWithLifecycle()
                    // Re-key on showShareCardSheet so reopening the same event picks the style
                    // from the title again instead of keeping an earlier override. Key on
                    // event.uid, not event.id: every Event built from a device event has id 0
                    // and a fresh uid.
                    LaunchedEffect(showShareCardSheet, event.uid) {
                        if (showShareCardSheet) {
                            shareCardViewModel.loadEventTitle(event.title)
                        }
                    }

                    org.onekash.kashcal.ui.components.share.ShareCardSheet(
                        title = event.title,
                        location = event.location,
                        timeRangeText = timeRangeText,
                        dateChip = dateChip,
                        stripe = stripe,
                        stripeLabels = stripeLabels,
                        isAllDay = event.isAllDay,
                        isMultiDay = isMultiDay,
                        multiDayRangeText = multiDayRangeText,
                        selectedStyle = selectedStyle,
                        onStyleChange = { shareCardViewModel.setStyle(it) },
                        onDismiss = {
                            showShareCardSheet = false
                            shareCardEvent = null
                        },
                        renderer = shareCardRenderer,
                        fileNameHint = (event.title.ifBlank { "event" }),
                        icsUriProvider = {
                            // A single-occurrence copy, so the recipient
                            // gets one standalone entry instead of the series,
                            // without the sender's rawIcal, organizer or X-*
                            // properties ([singleOccurrenceForShare]). File
                            // I/O runs on Dispatchers.IO so the Send tap
                            // doesn't jank. A failure here is non-fatal: the
                            // sheet sends the image alone.
                            withContext(Dispatchers.IO) {
                                val occurrenceEvent = singleOccurrenceForShare(
                                    event = event,
                                    occurrenceStartTs = viewerStartTs,
                                    occurrenceEndTs = viewerEndTs,
                                )
                                icsExporter.exportEvent(this@MainActivity, occurrenceEvent)
                                    .getOrNull()
                            }
                        },
                    )
                }

                // Device Event Quick View Sheet
                if (showDeviceQuickViewSheet && deviceQuickViewEvent != null) {
                    val hasWriteCalendarPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        android.Manifest.permission.WRITE_CALENDAR
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                    // Render the re-read event when it still matches the active occurrence; fall
                    // back to the tapped snapshot until the re-read lands or when it missed.
                    val deviceSnapshot = deviceQuickViewEvent!!
                    val deviceIsSingleInstance =
                        !deviceSnapshot.instance.hasRrule && deviceSnapshot.instance.originalId == null
                    val deviceDisplayEvent = deviceQuickViewEventLive
                        ?.takeIf {
                            it.instance.eventId == deviceSnapshot.instance.eventId &&
                                // A one-off has one instance, so the id alone
                                // matches it, and its start may have moved on an
                                // edit. That holds only while the live read is a
                                // one-off too, so a snapshot that went stale
                                // before the event gained an RRULE can't swap in
                                // a future occurrence. Series match by start.
                                (
                                    (
                                        deviceIsSingleInstance &&
                                            !it.instance.hasRrule &&
                                            it.instance.originalId == null
                                    ) || it.startTs == deviceSnapshot.startTs
                                )
                        }
                        ?: deviceSnapshot

                    DeviceEventQuickViewSheet(
                        displayEvent = deviceDisplayEvent,
                        showEventEmojis = uiState.showEventEmojis,
                        hasWritePermission = hasWriteCalendarPermission,
                        isWritableCalendar = !deviceDisplayEvent.isReadOnly,
                        attendees = deviceQuickViewAttendees?.models ?: emptyList(),
                        isCurrentUserOnList = deviceQuickViewAttendees?.isCurrentUserOnList ?: false,
                        onRsvp = { status ->
                            val event = deviceDisplayEvent
                            coroutineScope.launch {
                                homeViewModel.replyDeviceRsvp(
                                    eventId = event.instance.eventId,
                                    calendarId = event.instance.calendarId,
                                    status = status,
                                )
                                // Refresh the chip row so the new status shows.
                                deviceQuickViewAttendees = homeViewModel.getDeviceEventAttendeeState(
                                    eventId = event.instance.eventId,
                                    calendarId = event.instance.calendarId,
                                )
                            }
                        },
                        onDismiss = {
                            showDeviceQuickViewSheet = false
                            deviceQuickViewEvent = null
                        },
                        onEdit = {
                            // One Edit path, as in the Room quick view: the
                            // form opens on the tapped occurrence of a series
                            // or exception, else with no occurrenceTs. The
                            // scope is chosen at save time.
                            val event = deviceDisplayEvent
                            val isException = event.instance.originalId != null
                            val isRecurringMaster = event.instance.hasRrule && !isException
                            val masterEventId = event.instance.originalId ?: event.instance.eventId
                            editingDeviceEventId = masterEventId
                            deviceEventOccurrenceTs = when {
                                // One-off: no occurrence ts.
                                !isRecurringMaster && !isException -> null
                                isException -> event.instance.originalInstanceTime ?: event.startTs
                                else -> event.startTs
                            }
                            deviceEventIsAllDay = event.instance.isAllDay
                            showDeviceQuickViewSheet = false
                            deviceQuickViewEvent = null
                            editingEventId = null
                            duplicateFromEvent = null
                            duplicateFromDeviceCalendarId = null
                            showEventFormSheet = true
                        },
                        onEditOccurrence = { /* unused after save-time scope */ },
                        onDelete = {
                            // Mirrors the Room quick view's onDeleteSingle:
                            // - One-off: deleteDeviceEvent removes the row.
                            // - Exception (originalId set): deleteDeviceSingleOccurrence
                            //   on the master at the original instance time.
                            // - Series master: the scope sheet via requestDeleteDevice.
                            val event = deviceDisplayEvent
                            val isException = event.instance.originalId != null
                            val isRecurringMaster = event.instance.hasRrule && !isException
                            when {
                                isException -> {
                                    val masterEventId = event.instance.originalId!!
                                    val occTs = event.instance.originalInstanceTime ?: event.startTs
                                    coroutineScope.launch {
                                        val result = homeViewModel.deleteDeviceSingleOccurrence(
                                            masterEventId = masterEventId,
                                            originalInstanceTime = occTs,
                                            isAllDay = event.instance.isAllDay,
                                        )
                                        if (result.isSuccess) {
                                            showDeviceQuickViewSheet = false
                                            deviceQuickViewEvent = null
                                        }
                                    }
                                }
                                isRecurringMaster -> {
                                    val masterEventId = event.instance.eventId
                                    val occTs = event.startTs
                                    val masterStartTs = event.instance.eventStartTs
                                    showDeviceQuickViewSheet = false
                                    deviceQuickViewEvent = null
                                    homeViewModel.requestDeleteDevice(
                                        masterEventId = masterEventId,
                                        calendarId = event.instance.calendarId,
                                        occurrenceTs = occTs,
                                        masterStartTs = masterStartTs,
                                        isDetachedException = false,
                                        isAllDay = event.instance.isAllDay,
                                    )
                                }
                                else -> {
                                    coroutineScope.launch {
                                        val result = homeViewModel.deleteDeviceEvent(event.instance.eventId)
                                        if (result.isSuccess) {
                                            showDeviceQuickViewSheet = false
                                            deviceQuickViewEvent = null
                                        }
                                    }
                                }
                            }
                        },
                        onDuplicate = {
                            val event = deviceDisplayEvent
                            duplicateFromEvent = event.toEventForDuplicate()
                            duplicateFromDeviceCalendarId = event.instance.calendarId
                            showDeviceQuickViewSheet = false
                            deviceQuickViewEvent = null
                            editingEventId = null
                            newEventStartTs = event.startTs
                            eventOccurrenceTs = null
                            showEventFormSheet = true
                        },
                        onShare = {
                            val event = deviceDisplayEvent
                            val is24Hour = android.text.format.DateFormat.is24HourFormat(this@MainActivity)
                            val timePattern = DateTimeUtils.getTimePattern(uiState.timeFormat, is24Hour)
                            val shareText = event.buildShareText(
                                timePattern = timePattern,
                                allDayLabel = getString(R.string.label_all_day),
                                locationPrefix = "${getString(R.string.label_location)}: ",
                                footer = getString(R.string.share_from_kashcal_footer)
                            )

                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            startActivity(ShareChooser.createKashCalChooser(this@MainActivity, intent, getString(R.string.share_as_card_chooser_title)))

                            showDeviceQuickViewSheet = false
                            deviceQuickViewEvent = null
                        },
                        onExportIcs = {
                            // Mirrors the Room handler: reads the master and its exceptions from
                            // Events, cancelled ones included, maps each to an unsaved Room Event
                            // and exports through the same icsExporter.exportEvent.
                            val event = deviceDisplayEvent
                            val masterEventId = event.instance.originalId ?: event.instance.eventId
                            coroutineScope.launch {
                                val export = deviceEventReader.getEventWithExceptionsForExport(masterEventId)
                                if (export == null) {
                                    Log.w(TAG, "Device event $masterEventId not found for export")
                                    homeViewModel.showSnackbar("Event not found")
                                    showDeviceQuickViewSheet = false
                                    deviceQuickViewEvent = null
                                    return@launch
                                }
                                val (master, exceptions, remindersById) = export
                                val masterSynthetic = master.toExportEvent(remindersById[master.id].orEmpty())
                                val exceptionsSynthetic = exceptions.map { ex ->
                                    ex.toExportEvent(remindersById[ex.id].orEmpty())
                                }

                                icsExporter.exportEvent(
                                    context = this@MainActivity,
                                    event = masterSynthetic,
                                    exceptions = exceptionsSynthetic
                                ).onSuccess { uri ->
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/calendar"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    startActivity(ShareChooser.createKashCalChooser(this@MainActivity, intent, "Export Event"))
                                }.onFailure { e ->
                                    Log.e(TAG, "Failed to export device event", e)
                                    homeViewModel.showSnackbar("Export failed: ${e.message}")
                                }

                                showDeviceQuickViewSheet = false
                                deviceQuickViewEvent = null
                            }
                        },
                        onShareAsCard = {
                            // An unsaved Room Event built from the device
                            // instance with no attendee or organizer data,
                            // per [toEventForShareCard].
                            openShareCard(deviceDisplayEvent.toEventForShareCard())
                        },
                        showShareCardTooltip = !shownShareCardTooltip,
                        onShareCardTooltipDismissed = {
                            homeViewModel.markShareCardTooltipShown()
                        },
                        timeFormat = uiState.timeFormat
                    )
                }

                // The Quick Add ViewModel is owned here at screen level, per Android's UI-layer
                // guidance: state flows down and side effects stay with the caller.
                if (showQuickAddDialog) {
                    val quickAddViewModel: QuickAddViewModel = hiltViewModel()
                    val parseResult by quickAddViewModel.parseResult.collectAsStateWithLifecycle()
                    val isSaveEnabled by quickAddViewModel.isSaveEnabled.collectAsStateWithLifecycle()
                    val isSaving by quickAddViewModel.isSaving.collectAsStateWithLifecycle()
                    val quickAddTextFieldState = remember { TextFieldState() }
                    LaunchedEffect(Unit) {
                        quickAddViewModel.resetState()
                        val seed = quickAddShareSeed
                        if (seed != null) {
                            // Share-target open: anchor the reference time to the share's
                            // arrival, not the day being browsed, so "tomorrow" in the shared
                            // text resolves from today. Seed the text and parsed location
                            // before snapshotFlow so the parse preview shows on the first frame.
                            val anchor = java.time.Instant.ofEpochMilli(seed.referenceMs)
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDateTime()
                            quickAddViewModel.setReferenceTime(anchor)
                            // Programmatic edits bypass the field's InputTransformation, so
                            // strip newlines and cap at MAX_LENGTH (500) here too; a shared payload
                            // can be any length.
                            val seededText = org.onekash.kashcal.ui.components.QuickAddInputLimits
                                .takeGraphemes(
                                    seed.text.replace("\n", ""),
                                    org.onekash.kashcal.ui.components.QuickAddInputLimits.MAX_LENGTH
                                )
                            quickAddTextFieldState.edit { replace(0, length, seededText) }
                            quickAddViewModel.seedInput(seededText, seed.location)
                            quickAddShareSeed = null
                        } else {
                            // Undated input defaults to the day being viewed. The time-grid
                            // views (Day, 3-Day, Week) don't track selectedDate as the grid
                            // pages, and their full-form FAB seeds today, so they use today
                            // too instead of a stale selectedDate.
                            val anchorMs = if (uiState.viewMode.isTimeGrid) {
                                System.currentTimeMillis()
                            } else {
                                uiState.selectedDate.takeIf { it != 0L }
                                    ?: System.currentTimeMillis()
                            }
                            val anchorDate = DateTimeUtils.eventTsToLocalDate(anchorMs, isAllDay = false)
                            quickAddViewModel.setReferenceTime(anchorDate.atTime(LocalTime.now()))
                        }
                        snapshotFlow { quickAddTextFieldState.text.toString() }
                            .collect { quickAddViewModel.onInputChanged(it) }
                    }
                    val quickAddHaptics = LocalHapticFeedback.current
                    val saveFailedMessage = stringResource(R.string.quick_add_save_failed)
                    QuickAddDialog(
                        textFieldState = quickAddTextFieldState,
                        parseResult = parseResult,
                        isSaveEnabled = isSaveEnabled,
                        isSaving = isSaving,
                        timeFormat = uiState.timeFormat,
                        showEventEmojis = uiState.showEventEmojis,
                        onDismiss = { showQuickAddDialog = false },
                        onSave = {
                            coroutineScope.launch {
                                quickAddViewModel.save()
                                    .onSuccess { event ->
                                        quickAddHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        showQuickAddDialog = false
                                        val dayCode = DateTimeUtils.eventTsToDayCode(event.startTs, event.isAllDay)
                                        val date = org.onekash.kashcal.ui.util.DayPagerUtils.dayCodeToLocalDate(dayCode)
                                        homeViewModel.navigateToDate(date)
                                    }
                                    .onFailure { e ->
                                        showQuickAddDialog = false
                                        if (e is DeviceCalendarException) {
                                            // The default calendar is a device calendar: open the
                                            // full form.
                                            launchEventFormWithIntent(quickAddViewModel.toCalendarIntentData(), emptyList())
                                        } else {
                                            homeViewModel.showSnackbar(e.message ?: saveFailedMessage)
                                        }
                                    }
                            }
                        },
                        onExpand = {
                            coroutineScope.launch {
                                val intentData = quickAddViewModel.toCalendarIntentData()
                                showQuickAddDialog = false
                                launchEventFormWithIntent(intentData, emptyList())
                            }
                        }
                    )
                }

                // Event Form Sheet
                if (showEventFormSheet) {
                    EventFormSheet(
                        eventId = editingEventId,
                        initialStartTs = newEventStartTs,
                        occurrenceTs = eventOccurrenceTs,
                        duplicateFrom = duplicateFromEvent,
                        duplicateFromDeviceCalendarId = duplicateFromDeviceCalendarId,
                        calendarIntentData = calendarIntentData,
                        calendarIntentInvitees = calendarIntentInvitees,
                        calendars = uiState.calendars,
                        calendarGroups = uiState.calendarGroups,
                        defaultCalendar = uiState.defaultCalendar,
                        onDismiss = {
                            showEventFormSheet = false
                            editingEventId = null
                            newEventStartTs = null
                            eventOccurrenceTs = null
                            duplicateFromEvent = null
                            duplicateFromDeviceCalendarId = null
                            calendarIntentData = null
                            calendarIntentInvitees = emptyList()
                            editingDeviceEventId = null
                            deviceEventOccurrenceTs = null
                            deviceEventIsAllDay = false
                        },
                        onSave = { formState ->
                            homeViewModel.saveEvent(formState)
                        },
                        onRequestRecurringSave = { formState, occurrenceTs, originalRrule, masterStartTs, isDetachedException, isRecurringDevice, loadedIsAllDay ->
                            homeViewModel.requestFormSave(
                                formState = formState,
                                occurrenceTs = occurrenceTs,
                                originalRrule = originalRrule,
                                masterStartTs = masterStartTs,
                                isDetachedException = isDetachedException,
                                isRecurringDevice = isRecurringDevice,
                                loadedIsAllDay = loadedIsAllDay,
                            )
                        },
                        scopeSaveFailedTick = uiState.formSaveFailedTick,
                        onDelete = { eventId, occurrenceTs ->
                            homeViewModel.handleRoomEventFormDelete(eventId, occurrenceTs)
                        },
                        onLoadEvent = { eventId ->
                            homeViewModel.getEventForEdit(eventId)
                        },
                        onLoadAttendees = { eventId ->
                            homeViewModel.getAttendeesForEdit(eventId)
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
                                    callback(false)  // The callback fires even on error.
                                }
                            }
                        },
                        locationSuggestionService = locationSuggestionService,
                        onSuggestTitles = { prefix -> homeViewModel.suggestTitles(prefix) },
                        categorySuggestions = uiState.categorySuggestions,
                        timeFormat = uiState.timeFormat,
                        firstDayOfWeek = uiState.firstDayOfWeek,
                        deviceEventId = editingDeviceEventId,
                        deviceOccurrenceTs = deviceEventOccurrenceTs,
                        onLoadDeviceEvent = { eventId ->
                            homeViewModel.getDeviceEventForEdit(eventId, deviceEventOccurrenceTs, deviceEventIsAllDay)
                        },
                        onSaveDeviceEvent = { formState ->
                            homeViewModel.saveDeviceEvent(formState)
                        },
                        onDeleteDeviceEvent = { formState ->
                            homeViewModel.handleDeviceEventFormDelete(formState)
                        },
                        deviceCalendarGroups = uiState.deviceCalendarGroups,
                        attendees = formAttendees?.models ?: emptyList(),
                        isCurrentUserOnList = formAttendees?.isCurrentUserOnList ?: false,
                        isReadOnly = formIsReadOnly,
                        onRsvp = { status ->
                            editingEventId?.let { id -> homeViewModel.replyRsvp(id, status) }
                        },
                        onSaveAttendeeReminders = { reminders ->
                            val id = editingEventId
                                ?: return@EventFormSheet Result.failure(
                                    IllegalStateException("No editing event ID")
                                )
                            homeViewModel.saveAttendeeReminders(id, reminders)
                        },
                        attendeeAccount = formAttendeeContext.account,
                        isSchedulable = formAttendeeContext.isSchedulable,
                        onCalendarSelected = { calId ->
                            // Recompute the attendee context on a calendar switch so
                            // the schedulable gate and "You" detection follow the new
                            // calendar's account. Cancel the prior lookup so rapid
                            // switches can't land out of order.
                            attendeeContextJob?.cancel()
                            attendeeContextJob = coroutineScope.launch {
                                formAttendeeContext = homeViewModel.getFormAttendeeContext(calId)
                            }
                        },
                        onQueryContacts = { prefix -> homeViewModel.queryContactEmails(prefix) },
                        contactsPermissionState = contactsPermissionState,
                        onRequestContactsPermission = {
                            contactsRationaleBefore = ActivityCompat.shouldShowRequestPermissionRationale(
                                this@MainActivity, Manifest.permission.READ_CONTACTS
                            )
                            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                        },
                        contactsDeclined = contactsDeclined,
                        onDeclineContacts = { homeViewModel.declineContactSuggestions() },
                        tagsAboveNotes = uiState.tagsAboveNotes,
                        onSetTagsAboveNotes = { above -> homeViewModel.setTagsAboveNotes(above) }
                    )
                }

                // App Info Sheet
                if (uiState.showAppInfoSheet) {
                    AppInfoSheet(
                        onDismiss = { homeViewModel.toggleAppInfoSheet() }
                    )
                }

                // Share Availability Sheet
                if (uiState.showShareAvailabilitySheet) {
                    val shareAvailabilityViewModel: ShareAvailabilityViewModel = hiltViewModel()
                    val shareUiState by shareAvailabilityViewModel.uiState.collectAsStateWithLifecycle()
                    // hiltViewModel() returns the activity-scoped instance, whose init runs only
                    // on the first open, so refresh the time, locale and 24h inputs on each open.
                    LaunchedEffect(Unit) {
                        shareAvailabilityViewModel.refresh()
                    }
                    ShareAvailabilitySheet(
                        uiState = shareUiState,
                        is24Hour = shareAvailabilityViewModel.resolveIs24Hour(),
                        onDaysPreview = { shareAvailabilityViewModel.previewDaysChange(it) },
                        onDaysCommit = { shareAvailabilityViewModel.commitPersistence() },
                        onHoursPreview = { start, end -> shareAvailabilityViewModel.previewWorkHoursChange(start, end) },
                        onHoursCommit = { shareAvailabilityViewModel.commitPersistence() },
                        onAllDayToggle = { shareAvailabilityViewModel.onAllDayToggle(it) },
                        onShare = { text ->
                            try {
                                startActivity(buildShareAvailabilityChooserIntent(this@MainActivity, text))
                            } catch (e: android.content.ActivityNotFoundException) {
                                homeViewModel.showSnackbar(getString(R.string.share_availability_share_failed))
                            }
                            homeViewModel.dismissShareAvailabilitySheet()
                        },
                        onDismiss = { homeViewModel.dismissShareAvailabilitySheet() }
                    )
                }

                if (uiState.showOnboardingSheet) {
                    OnboardingBanner(
                        onConnect = {
                            homeViewModel.dismissOnboardingSheet()
                            // Open Settings on the iCloud sign-in sheet.
                            launchInternalActivity(Intent(this@MainActivity, SettingsActivity::class.java).apply {
                                putExtra(SettingsActivity.EXTRA_OPEN_ICLOUD_SIGNIN, true)
                            })
                        },
                        onDismiss = {
                            homeViewModel.dismissOnboardingSheet()
                        }
                    )
                } else if (uiState.whatsNewReleases.isNotEmpty()) {
                    // What's New shows only once onboarding is gone, so first-launch users
                    // see the iCloud prompt first.
                    WhatsNewBanner(
                        releases = uiState.whatsNewReleases,
                        onDismiss = { homeViewModel.dismissWhatsNewSheet() },
                    )
                }

                // Sync Changes Bottom Sheet
                if (uiState.showSyncChangesSheet) {
                    SyncChangesBottomSheet(
                        changes = uiState.syncChanges,
                        onDismiss = { homeViewModel.dismissSyncChangesSheet() },
                        onEventClick = { eventId ->
                            homeViewModel.dismissSyncChangesSheet()
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
                    val defaultRoomCalendarId = (uiState.defaultCalendar as? DefaultCalendar.Room)?.calendarId
                    val defaultDeviceCalendarId = (uiState.defaultCalendar as? DefaultCalendar.Device)?.calendarId
                    IcsImportSheet(
                        events = icsImportEvents,
                        calendars = uiState.calendars,
                        defaultCalendarId = defaultRoomCalendarId,
                        deviceCalendarGroups = uiState.deviceCalendarGroups,
                        defaultDeviceCalendarId = defaultDeviceCalendarId,
                        onDismiss = {
                            showIcsImportSheet = false
                            icsImportEvents = emptyList()
                        },
                        onImport = { calendarId, events, isDeviceCalendar ->
                            coroutineScope.launch {
                                try {
                                    val count = if (isDeviceCalendar) {
                                        homeViewModel.importIcsToDeviceCalendar(events, calendarId)
                                    } else {
                                        eventCoordinator.importIcsEvents(events, calendarId)
                                    }
                                    homeViewModel.showSnackbar(
                                        resources.getQuantityString(R.plurals.imported_events, count, count)
                                    )
                                    events.firstOrNull()?.let { firstEvent ->
                                        homeViewModel.selectDate(firstEvent.startTs)
                                    }
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

                // The app lock veil is composed last so it draws above all content and the
                // calendar is never visible while locked. Locking fires the unlock prompt and
                // sets FLAG_SECURE, so the recents thumbnail is hidden only while locked.
                val isLocked by appLockViewModel.lockState.collectAsStateWithLifecycle()
                LaunchedEffect(isLocked) {
                    if (isLocked) {
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE,
                        )
                        promptForUnlock()
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
                if (isLocked) {
                    AppLockVeil(onUnlock = { promptForUnlock() })
                }
                } // end LocalTagColors provider
            }
        }
    }

    /**
     * Shows the system biometric or device-credential prompt. Guarded so the auto-fire on locking
     * and a manual Unlock tap can't stack two sheets.
     *
     * When nothing is enrolled, for example the user removed every device credential after
     * enabling the lock, it unlocks instead: the prompt can't be satisfied, and the device itself
     * is unsecured, so there is nothing left to protect.
     */
    private fun promptForUnlock() {
        if (isUnlockPromptShowing) return

        val authenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        ) {
            Log.w(TAG, "No credential enrolled at prompt time; unlocking to avoid lock-out")
            appLockViewModel.onUnlockSucceeded()
            return
        }

        isUnlockPromptShowing = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isUnlockPromptShowing = false
                    appLockViewModel.onUnlockSucceeded()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancel or any error: stay locked. The veil always shows an
                    // Unlock button that re-fires this prompt.
                    isUnlockPromptShowing = false
                    appLockViewModel.onUnlockError()
                }
            },
        )
        // With DEVICE_CREDENTIAL allowed, setNegativeButtonText must not be set, or build()
        // would throw. setTitle is required and carries the instruction.
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_lock_prompt_title))
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }

    /**
     * Enables or disables the app lock. Enabling adds protection, so it commits at once with an
     * inline confirmation; disabling removes protection, so it must be authenticated. The
     * capability checks and the enrollment intent live here because they need the activity, and
     * ViewModels must not start activities.
     */
    private fun onToggleAppLock(enabled: Boolean) {
        if (enabled) {
            when (decideEnrollmentAction(canAuthenticateForAppLock())) {
                AppLockEnrollmentAction.Enable -> {
                    appLockViewModel.setAppLockEnabled(true)
                    // Confirm inline, and say the prompt appears on the next open, not on the
                    // return to here.
                    homeViewModel.showSnackbar(getString(R.string.app_lock_enabled_message))
                }
                AppLockEnrollmentAction.RouteToEnroll ->
                    launchBiometricEnrollment()
                AppLockEnrollmentAction.Unsupported ->
                    homeViewModel.showSnackbar(getString(R.string.app_lock_unsupported_message))
            }
        } else {
            // Without the challenge, anyone holding the unlocked phone could open the hub
            // and switch the lock off. False is committed only on success.
            authenticateThenDisableAppLock()
        }
    }

    /** Returns the `canAuthenticate` code for a strong biometric or the screen-lock credential. */
    private fun canAuthenticateForAppLock(): Int =
        BiometricManager.from(this)
            .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)

    /**
     * Sends the user to the system enrollment flow instead of enabling a lock nothing can satisfy.
     * Plain security settings is the intent below API 30, the floor of ACTION_BIOMETRIC_ENROLL.
     * Routed through [launchInternalActivity] so the enrollment round trip doesn't trip the
     * re-lock on return.
     */
    private fun launchBiometricEnrollment() {
        homeViewModel.showSnackbar(getString(R.string.app_lock_enroll_message))
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                putExtra(
                    Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                    BIOMETRIC_STRONG or DEVICE_CREDENTIAL,
                )
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        try {
            launchInternalActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(TAG, "No enrollment activity available", e)
            launchInternalActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
    }

    /**
     * Challenges the user before turning the lock off. The pref is set to false only on a
     * successful authentication, so holding an unlocked phone isn't enough to disable it.
     *
     * When nothing is enrolled ([decideDisableAction]), the prompt can't be satisfied and the
     * device is unsecured, so it disables directly, as [promptForUnlock] unlocks. The prompt runs
     * in-process with no activity launch, so it doesn't trip the re-lock.
     */
    private fun authenticateThenDisableAppLock() {
        if (isDisablePromptShowing) return

        val authenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        if (decideDisableAction(canAuthenticateForAppLock()) == AppLockDisableAction.DisableDirectly) {
            Log.w(TAG, "No credential enrolled when disabling app lock; disabling without a challenge")
            appLockViewModel.setAppLockEnabled(false)
            return
        }

        isDisablePromptShowing = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isDisablePromptShowing = false
                    appLockViewModel.setAppLockEnabled(false)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancel or any error: the lock stays on, and so does the toggle, which
                    // shows the persisted pref.
                    isDisablePromptShowing = false
                }
            },
        )
        // With DEVICE_CREDENTIAL allowed, setNegativeButtonText must not be set or build()
        // throws. The title carries the instruction.
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_lock_disable_prompt_title))
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }

    /**
     * Opens the system settings page for [kind]. Notifications has its own settings screen; the
     * other kinds have no per-permission page, so they open the app info page, where every
     * runtime permission can be toggled. Routed through [launchInternalActivity] so the lock
     * veil doesn't re-lock on the return.
     */
    private fun openPermissionSettings(kind: AppPermissionKind) {
        val intent = when (kind) {
            AppPermissionKind.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            else -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
        }
        try {
            launchInternalActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(TAG, "No settings activity available for $kind", e)
        }
    }

    /**
     * Starts [intent] with [returningFromInternalActivity] set, so the return skips the resume
     * sync and the re-lock. A failed launch is logged and clears the flag; it never throws.
     */
    private fun launchInternalActivity(intent: Intent) {
        try {
            returningFromInternalActivity = true
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch internal activity", e)
            returningFromInternalActivity = false
        }
    }

    override fun onStart() {
        super.onStart()
        // onStart runs before onResume resets returningFromInternalActivity, so the flag still
        // holds here. Suppressing the re-lock on those returns means a user who just enabled
        // the lock, or lingered on the system enrollment screen past the grace window, isn't
        // challenged on return.
        //
        // The enabled flag comes from the ViewModel's cached StateFlow, not a blocking read:
        // onStart fires on every foreground.
        appLockViewModel.onForeground(
            enabled = appLockViewModel.appLockEnabled.value,
            nowElapsed = SystemClock.elapsedRealtime(),
            suppressRelock = returningFromInternalActivity,
        )
    }

    override fun onStop() {
        super.onStop()
        appLockViewModel.onBackground(SystemClock.elapsedRealtime())
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume, isFirstResume=$isFirstResume, returningFromInternal=$returningFromInternalActivity")

        if (!isFirstResume) {
            Log.d(TAG, "Returning to calendar, refreshing")
            homeViewModel.refreshAccountStatus()
            homeViewModel.refreshCalendars()

            if (!returningFromInternalActivity) {
                homeViewModel.syncOnResumeIfNeeded()
            } else {
                Log.d(TAG, "Skipping sync - returning from internal navigation")
            }
        }
        isFirstResume = false
        returningFromInternalActivity = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent: action=${intent.action}")
        setIntent(intent)  // So getIntent() returns the new intent.
        handleIncomingIntent(intent)
    }

    /**
     * Sets the [PendingAction] for an incoming intent, checking in order: reminder and device
     * reminder taps, widget and shortcut actions, plain-text and .ics shares, calendar insert and
     * edit intents, CalendarContract URIs, and ICS files opened by URI. The first branch that
     * matches returns, even when it sets no action (a reminder tap missing its extras, a
     * CalendarContract URI that only opens the app). A webcal:// link opens Settings instead.
     *
     * The action is state in the ViewModel, as Android recommends for UI events: the UI clears
     * it after consuming it, and it survives configuration changes.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        // Reminder notification tap, checked before the widget and URI handling.
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

        if (intent?.action == DeviceCalendarReminderNotificationManager.ACTION_DEVICE_SHOW_EVENT) {
            val eventId = intent.getLongExtra(DeviceCalendarReminderNotificationManager.EXTRA_EVENT_ID, -1)
            val occurrenceTs = intent.getLongExtra(DeviceCalendarReminderNotificationManager.EXTRA_OCCURRENCE_TS, -1)
            if (eventId != -1L && occurrenceTs != -1L) {
                Log.d(TAG, "Device reminder notification: showing event $eventId at $occurrenceTs")
                homeViewModel.setPendingAction(
                    PendingAction.ShowDeviceEventQuickView(
                        eventId = eventId,
                        occurrenceTs = occurrenceTs
                    )
                )
            }
            return
        }

        // Widget and shortcut actions, checked before the URI handling.
        intent?.getStringExtra(org.onekash.kashcal.widget.EXTRA_ACTION)?.let { action ->
            Log.d(TAG, "Handling widget action: $action")
            when (action) {
                org.onekash.kashcal.widget.ACTION_SHOW_EVENT -> {
                    val eventId = intent.getLongExtra(org.onekash.kashcal.widget.EXTRA_EVENT_ID, -1)
                    val occurrenceTs = intent.getLongExtra(org.onekash.kashcal.widget.EXTRA_OCCURRENCE_TS, -1)
                    val isDeviceEvent = intent.getBooleanExtra(org.onekash.kashcal.widget.EXTRA_IS_DEVICE_EVENT, false)
                    if (eventId != -1L && occurrenceTs != -1L) {
                        Log.d(TAG, "Widget: showing event $eventId at $occurrenceTs (isDevice=$isDeviceEvent)")
                        if (isDeviceEvent) {
                            homeViewModel.setPendingAction(
                                PendingAction.ShowDeviceEventQuickView(
                                    eventId = eventId,
                                    occurrenceTs = occurrenceTs
                                )
                            )
                        } else {
                            homeViewModel.setPendingAction(
                                PendingAction.ShowEventQuickView(
                                    eventId = eventId,
                                    occurrenceTs = occurrenceTs,
                                    source = PendingAction.ShowEventQuickView.Source.WIDGET
                                )
                            )
                        }
                    }
                    return
                }
                org.onekash.kashcal.widget.ACTION_CREATE_EVENT -> {
                    // The week widget may pass a start time.
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

        // Plain-text shares (ACTION_SEND text/plain). Anchored to the arrival time so "tomorrow"
        // in the shared text resolves from then, not from the date being browsed. The action is
        // cleared after dispatch so a recreation doesn't re-fire the share over user edits.
        ShareIntentRouter.route(intent, System.currentTimeMillis())?.let { action ->
            Log.d(TAG, "Share intent: ${action::class.simpleName}")
            intent?.action = null
            homeViewModel.setPendingAction(action)
            return
        }

        // Shared .ics files (ACTION_SEND with the file in EXTRA_STREAM), the share-sheet
        // counterpart of the ACTION_VIEW path below. Checked after the plain-text router so
        // plain-text shares win. The action is cleared so a recreation doesn't re-fire the import.
        IcsShareIntentParser.parse(intent)?.let { uri ->
            Log.d(TAG, "Handling shared ICS file: $uri")
            intent?.action = null
            homeViewModel.setPendingAction(PendingAction.ImportIcsFile(uri))
            return
        }

        // ACTION_INSERT and ACTION_EDIT, for "Add to Calendar" from other apps.
        CalendarIntentParser.parse(intent)?.let { (data, invitees) ->
            Log.d(TAG, "Calendar intent: title=${data.title}, start=${data.startTimeMillis}, invitees=${invitees.size}")
            homeViewModel.setPendingAction(PendingAction.CreateEventFromCalendarIntent(data, invitees))
            return
        }

        // CalendarContract content URIs (VIEW or EDIT on content://com.android.calendar), sent
        // by launchers, clock widgets and other apps.
        CalendarIntentParser.parseCalendarContractUri(intent)?.let { action ->
            when (action) {
                is CalendarContractAction.GoToDate -> {
                    Log.d(TAG, "CalendarContract: navigating to dayCode=${action.dayCode}")
                    homeViewModel.setPendingAction(PendingAction.GoToDate(action.dayCode))
                }
                is CalendarContractAction.CreateEvent -> {
                    Log.d(TAG, "CalendarContract: creating event, title=${action.data.title}")
                    homeViewModel.setPendingAction(
                        PendingAction.CreateEventFromCalendarIntent(action.data, action.invitees)
                    )
                }
                is CalendarContractAction.OpenDeviceEvent -> {
                    if (action.beginTimeMillis != null) {
                        Log.d(TAG, "CalendarContract: open device event ${action.eventId} at occurrence")
                        homeViewModel.setPendingAction(
                            PendingAction.ShowDeviceEventQuickView(action.eventId, action.beginTimeMillis)
                        )
                    } else {
                        Log.d(TAG, "CalendarContract: open device event ${action.eventId} (no occurrence; navigate to date)")
                        homeViewModel.setPendingAction(PendingAction.OpenDeviceEventById(action.eventId))
                    }
                }
                is CalendarContractAction.OpenApp -> {
                    Log.d(TAG, "CalendarContract: open app (fallback)")
                }
            }
            return
        }

        intent?.data?.let { uri ->
            val scheme = uri.scheme
            when {
                // webcal:// subscription links open Settings.
                scheme == "webcal" || scheme == "webcals" -> {
                    Log.d(TAG, "Handling webcal deep link: $uri")
                    launchInternalActivity(Intent(this, SettingsActivity::class.java).apply {
                        putExtra(SettingsActivity.EXTRA_SUBSCRIPTION_URL, uri.toString())
                    })
                }
                // content:// or file:// ICS files go to the import sheet.
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
     * Navigates to a device event's start date, or shows a "not found" snackbar when the event
     * can't be resolved. Used when no occurrence resolves for an id-only open and when an
     * exact-occurrence lookup misses, so a stale or slightly-off timestamp lands the user near
     * the event instead of on a dead end.
     */
    private suspend fun navigateToDeviceEventOrNotFound(homeViewModel: HomeViewModel, eventId: Long) {
        val dayCode = homeViewModel.getDeviceEventDayCode(eventId)
        if (dayCode != null) {
            Log.d(TAG, "Navigating to device event $eventId on $dayCode")
            homeViewModel.navigateToDate(
                org.onekash.kashcal.ui.util.DayPagerUtils.dayCodeToLocalDate(dayCode)
            )
        } else {
            Log.w(TAG, "Device event $eventId not found")
            homeViewModel.showSnackbar(getString(R.string.error_device_event_not_found))
        }
    }

    /** Returns whether [mimeType] is an ICS file, by the rule the share-sheet path uses. */
    private fun isIcsMimeType(mimeType: String?): Boolean =
        IcsShareIntentParser.isIcsMimeType(mimeType)
}
