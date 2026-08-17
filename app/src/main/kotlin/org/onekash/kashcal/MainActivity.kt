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
import org.onekash.kashcal.domain.share.singleOccurrenceForShare
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
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
    lateinit var calendarProviderRepository: CalendarProviderRepository

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

        // Resolve the app-lock flag synchronously BEFORE first composition so the
        // veil is in place on the very first frame — no flash of calendar content.
        // A single cached boolean read; deliberately kept to one key (DataStore
        // caches after the first read, so rotation re-creates don't re-hit disk).
        val appLockEnabledAtStart = runBlocking { userPreferencesRepository.appLockEnabled.first() }
        appLockViewModel.onActivityCreated(enabled = appLockEnabledAtStart)

        // Resolve the theme synchronously so the first frame renders in the chosen theme — no
        // flash of the default on cold start (same rationale as the app-lock read above). DataStore
        // caches after the first read, so this doesn't re-hit disk on rotation.
        // Seed theme + accent source/seed synchronously so the first frame renders in the chosen
        // colors — no flash of the default/dynamic theme on cold start. Read each pref once.
        val initialThemeString = runBlocking { userPreferencesRepository.theme.first() }
        val initialThemeMode = ThemeMode.fromPrefValue(initialThemeString)
        val initialColorSource = ColorSource.fromPrefValue(
            explicit = runBlocking { userPreferencesRepository.colorSource.first() },
            legacyTheme = initialThemeString,
        )
        val initialAccentSeed = runBlocking { userPreferencesRepository.accentSeed.first() }

        // Handle webcal:// deep link if present
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

                // Contacts permission state for the attendee picker. The
                // rationale-flip signal (sampled before/after the request)
                // distinguishes "can ask again" from "don't ask again".
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
                    // A system-dialog denial that won't re-prompt (permanent) is
                    // a "no" — persist it so the banner doesn't return. A denial
                    // that's still askable (rationale) leaves the banner for a
                    // later, gentler retry.
                    if (!granted && !after) {
                        homeViewModel.declineContactSuggestions()
                    }
                }

                // Attendee-editing context (account + can-send-invitations) for
                // the form, resolved when the sheet opens or its calendar changes.
                var formAttendeeContext by remember {
                    mutableStateOf(org.onekash.kashcal.ui.viewmodels.FormAttendeeContext(null, true))
                }
                // Tracks the in-flight attendee-context resolution so a rapid
                // calendar switch cancels the prior lookup — otherwise two
                // resolutions could finish out of order and pin a stale context.
                var attendeeContextJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

                // Quick Add dialog state
                var showQuickAddDialog by remember { mutableStateOf(false) }
                // Seed for share-target opens. Null on user-initiated opens.
                var quickAddShareSeed by remember { mutableStateOf<PendingAction.QuickAddFromText?>(null) }

                // Event form sheet state
                var showEventFormSheet by remember { mutableStateOf(false) }
                var editingEventId by remember { mutableStateOf<Long?>(null) }
                var newEventStartTs by remember { mutableStateOf<Long?>(null) }
                var eventOccurrenceTs by remember { mutableStateOf<Long?>(null) }
                var duplicateFromEvent by remember { mutableStateOf<Event?>(null) }
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
                // One-shot coach mark, shared across BOTH the Room and the
                // device-event quick-view sheets. The flag is dismissed on
                // first show in whichever sheet appears first; the user only
                // ever sees the tooltip once per install regardless of which
                // entry point they discover share-as-card from.
                val shownShareCardTooltip by homeViewModel.shownShareCardTooltip
                    .collectAsStateWithLifecycle(initialValue = false)
                val quickViewAttendees by homeViewModel.quickViewAttendees.collectAsStateWithLifecycle()
                // Live event body for the active QuickView, re-read by id so an
                // edit's new title/time shows even if the tapped snapshot was stale.
                val liveQuickViewEvent by homeViewModel.quickViewEventLive.collectAsStateWithLifecycle()
                val formAttendees by homeViewModel.formAttendees.collectAsStateWithLifecycle()
                val formIsReadOnly by homeViewModel.formIsReadOnly.collectAsStateWithLifecycle()
                val contactsDeclined by homeViewModel.contactSuggestionsDeclined.collectAsStateWithLifecycle()
                val dayAttendeesMap by homeViewModel.dayAttendees.collectAsStateWithLifecycle()
                val pendingInvitesCount by homeViewModel.pendingInvitationsCount.collectAsStateWithLifecycle()
                val pendingInvitations by homeViewModel.pendingInvitations.collectAsStateWithLifecycle()
                // Drive HomeViewModel's attendee StateFlow whenever the active QuickView event changes.
                androidx.compose.runtime.LaunchedEffect(quickViewEvent?.id) {
                    homeViewModel.setQuickViewEventId(quickViewEvent?.id)
                }
                // Drive form-side attendee state when the form opens for an existing event.
                androidx.compose.runtime.LaunchedEffect(showEventFormSheet, editingEventId) {
                    homeViewModel.setFormEventId(if (showEventFormSheet) editingEventId else null)
                }
                // Resolve the form's attendee-editing context + sync the contacts
                // permission state when the sheet opens.
                androidx.compose.runtime.LaunchedEffect(showEventFormSheet, editingEventId) {
                    if (showEventFormSheet) {
                        // For an edit, use the event's calendar; for a new event,
                        // the form defaults to the user's default calendar, so
                        // resolve the schedulable/account context from that.
                        val calId = editingEventId?.let { homeViewModel.getEventForEdit(it)?.calendarId }
                            ?: uiState.defaultCalendar?.calendarId
                        formAttendeeContext = homeViewModel.getFormAttendeeContext(calId)
                        // Recompute the live permission state on every open so a
                        // grant/revoke done in system Settings while the app was
                        // alive is reflected (don't only upgrade to Granted, or a
                        // later revoke leaves a stale Granted that queries a
                        // revoked permission and hides the re-request banner).
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
                // Guests for the active device quick-view event. Loaded on
                // demand (separate Attendees query, never the bulk grid read)
                // whenever the active event changes; resets to empty between
                // events so a guest list never bleeds across sheets.
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
                // Fresh device event body, re-read directly from the provider
                // whenever the active occurrence changes. The tapped snapshot
                // can come from a list that hasn't refreshed since an edit
                // (device changes only propagate through a debounced signal),
                // so re-query by id so the sheet renders the new title/details.
                // A provider read here bypasses that debounce. Falls back to the
                // snapshot when the fresh read misses (e.g. the occurrence moved).
                var deviceQuickViewEventLive by remember { mutableStateOf<DisplayEvent.Device?>(null) }
                LaunchedEffect(deviceQuickViewEvent?.instance?.eventId, deviceQuickViewEvent?.startTs) {
                    val snapshot = deviceQuickViewEvent
                    deviceQuickViewEventLive = when {
                        snapshot == null -> null
                        // Non-recurring single instance: re-resolve by id so a
                        // changed start time or all-day toggle still lands — the
                        // tapped snapshot's old start no longer exists on the
                        // provider, so a start-keyed lookup would miss it.
                        !snapshot.instance.hasRrule && snapshot.instance.originalId == null ->
                            homeViewModel.getDeviceEventForQuickViewById(snapshot.instance.eventId)
                        // Recurring/exception: preserve the tapped occurrence by
                        // its start (which occurrence to show is otherwise
                        // ambiguous); a moved occurrence falls back to the snapshot.
                        else -> homeViewModel.getDeviceEventForQuickView(
                            eventId = snapshot.instance.eventId,
                            occurrenceTs = snapshot.startTs,
                        )
                    }
                }

                // Drawer state
                val drawerState = rememberDrawerState(DrawerValue.Closed)

                // ICS import state
                var icsImportEvents by remember { mutableStateOf<List<Event>>(emptyList()) }
                var showIcsImportSheet by remember { mutableStateOf(false) }

                // Shared launch helper for opening EventFormSheet from CalendarIntentData
                // (used by PendingAction.CreateEventFromCalendarIntent and QuickAdd expand/redirect).
                val launchEventFormWithIntent = { data: CalendarIntentData, invitees: List<String> ->
                    editingEventId = null
                    newEventStartTs = data.startTimeMillis
                    eventOccurrenceTs = null
                    calendarIntentData = data
                    calendarIntentInvitees = invitees
                    showEventFormSheet = true
                }

                // Single entry point for the share-as-card flow. Both the
                // Room and device-event quick views feed it; keeping the
                // mutation in one place keeps any future change (analytics,
                // peer-sheet dismissal, capture reset) from drifting.
                val openShareCard = { event: Event ->
                    shareCardEvent = event
                    showShareCardSheet = true
                }

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
                                    // Handle calendar intent from other apps (email clients, browsers, etc.)
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
                                        // The occurrence didn't match exactly (e.g. an external
                                        // launcher supplied a begin time slightly off the
                                        // materialized instance). Fall back to navigating to the
                                        // event's start date so the user lands near it rather than
                                        // on a dead-end "not found" — but only if the event exists.
                                        navigateToDeviceEventOrNotFound(homeViewModel, action.eventId)
                                    }
                                }
                                is PendingAction.OpenDeviceEventById -> {
                                    // External intent gave only the event ID. Open the quick-view
                                    // sheet at the resolved occurrence (next instance for a
                                    // recurring series, DTSTART otherwise). If no occurrence
                                    // resolves (e.g. an ended series), fall back to date nav.
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
                                    // Close peer surfaces so the seeded dialog is not occluded.
                                    showEventFormSheet = false
                                    showQuickViewSheet = false
                                    showDeviceQuickViewSheet = false
                                    quickAddShareSeed = action
                                    showQuickAddDialog = true
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
                            val message = (e.message ?: e.javaClass.simpleName).take(50)
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

                Log.d(TAG, "Composing with ${uiState.dayEventsCache.values.sumOf { it.size }} cached day events")

                // Publish per-tag custom colors to every chip below (home views,
                // form, quick view, week blocks) so recoloring a tag repaints its
                // chips without threading color through the occurrence stream.
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

                        // Quick Add: open dialog in every view when enabled. Quick Add
                        // seeds its reference day/time, so it works in the time-grid
                        // views (Day/3-Day/Week) too — the reference is chosen below.
                        if (quickAddEnabled) {
                            showQuickAddDialog = true
                        } else {
                            val eventTimestamp = if (uiState.viewMode.isTimeGrid) {
                                // New event defaults to today at the next hour.
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
                        quickViewOccurrenceTs = nextOccurrenceTs  // Pass occurrence context for recurring events
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
                    // App lock (moved out of Settings into the hub's Privacy section)
                    appLockEnabled = appLockEnabled,
                    onToggleAppLock = ::onToggleAppLock,
                    // The app-permissions screen deep-links here; route through the
                    // internal-activity launch so the lock veil doesn't re-lock on return.
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
                                    // Success: dismiss the form sheet and clear edit
                                    // state. The user's intent was committed.
                                    showEventFormSheet = false
                                    editingEventId = null
                                    newEventStartTs = null
                                    eventOccurrenceTs = null
                                    duplicateFromEvent = null
                                    editingDeviceEventId = null
                                    deviceEventOccurrenceTs = null
                                    deviceEventIsAllDay = false
                                    calendarIntentData = null
                                    calendarIntentInvitees = emptyList()
                                } else {
                                    // Failure: keep the form open so the user
                                    // sees their edits and can retry. The
                                    // ViewModel's existing snackbar surfaces
                                    // the underlying error message. We also
                                    // need to reset the form's isSaving flag,
                                    // which is set true at the moment the
                                    // scope sheet was opened — propagating
                                    // happens via the form's view of
                                    // uiState.pendingFormSave being null
                                    // again, plus the LaunchedEffect on
                                    // formSaveFailedAt below.
                                    homeViewModel.signalFormSaveFailed()
                                }
                            }
                        }
                    },
                    onCancelPendingFormSave = {
                        homeViewModel.cancelPendingFormSave()
                        // Cancel from the scope sheet returns to the dirty
                        // form. Reset isSaving so the Save button re-enables
                        // for retry.
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
                    // Prefer the reactively re-read event when it's for the active
                    // id; fall back to the tapped snapshot until the by-id flow
                    // warms up (or if the event was just deleted). Guarding on id
                    // prevents a lagging flow from briefly showing the prior event.
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
                            // Single Edit path: open the form pre-filled
                            // with the tapped occurrence's data when the
                            // event is recurring; otherwise open the
                            // master directly with no occurrenceTs.
                            // Scope (THIS_EVENT vs THIS_AND_FUTURE vs
                            // ALL_EVENTS) is decided at save-time.
                            val isRecurring = event.rrule != null
                            val isException = event.originalEventId != null
                            showQuickViewSheet = false
                            editingEventId = event.id
                            eventOccurrenceTs = when {
                                // Non-recurring one-off: NO occurrence ts
                                // (a non-null value misroutes saveEvent
                                // through editSingleOccurrence which
                                // throws on non-recurring masters).
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
                            // Three branches:
                            // - non-recurring: delete the row directly.
                            // - exception: route to deleteSingleOccurrence
                            //   on the master (adds EXDATE; the exception
                            //   row itself can't be deleted via deleteEvent
                            //   per the EventCoordinator guard).
                            // - recurring master: surface the scope sheet.
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
                                val dateFormat = java.text.SimpleDateFormat(DateTimeUtils.localizedPattern("yEEEMMMd"), java.util.Locale.getDefault())
                                val is24Hour = android.text.format.DateFormat.is24HourFormat(this@MainActivity)
                                val timePattern = DateTimeUtils.getTimePattern(uiState.timeFormat, is24Hour)
                                val timeFormat = java.text.SimpleDateFormat(timePattern, java.util.Locale.getDefault())

                                if (event.isAllDay) {
                                    // All-day: Use UTC to get correct calendar date
                                    val utcDateFormat = java.text.SimpleDateFormat(DateTimeUtils.localizedPattern("yEEEMMMd"), java.util.Locale.getDefault()).apply {
                                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                                    }
                                    val startDate = java.util.Date(event.startTs)
                                    val endDate = java.util.Date(event.endTs)
                                    val allDay = getString(R.string.label_all_day)

                                    // Check for multi-day
                                    val startStr = utcDateFormat.format(startDate)
                                    val endStr = utcDateFormat.format(endDate)
                                    if (startStr != endStr) {
                                        appendLine("$startStr - $endStr ($allDay)")
                                    } else {
                                        appendLine("$startStr ($allDay)")
                                    }
                                } else {
                                    // Timed event: Use local timezone
                                    val startDate = java.util.Date(event.startTs)
                                    val endDate = java.util.Date(event.endTs)
                                    val startDateStr = dateFormat.format(startDate)
                                    val endDateStr = dateFormat.format(endDate)
                                    if (startDateStr != endDateStr) {
                                        // Multi-day timed event: show both dates
                                        appendLine("$startDateStr ${timeFormat.format(startDate)} - $endDateStr ${timeFormat.format(endDate)}")
                                    } else {
                                        // Same-day timed event: show date once
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
                            // Don't dismiss the QuickViewSheet — user may
                            // back out of share and return to it.
                            openShareCard(event)
                        },
                        showShareCardTooltip = !shownShareCardTooltip,
                        onShareCardTooltipDismissed = {
                            homeViewModel.markShareCardTooltipShown()
                        },
                        timeFormat = uiState.timeFormat
                    )
                }

                // Share-as-card preview sheet — opened from the top-right
                // Share icon on EventQuickViewSheet. Renders a 1080×1350 PNG
                // via the on-screen preview's GraphicsLayer capture.
                if (showShareCardSheet && shareCardEvent != null) {
                    val event = shareCardEvent!!
                    // shareCardZone forces UTC for all-day events (every
                    // storage path — Room, ICS, device — anchors all-day
                    // startTs to UTC midnight) and falls back to system
                    // default for null / non-IANA timezones on timed
                    // events so legacy adapter strings like "Pacific
                    // Standard Time" don't crash the share flow.
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

                    // Multi-day classification. LocalDate-difference catches
                    // calendar-spanning events; an 18-hour duration floor
                    // excludes overnight events (Sat 10 PM → Sun 2 AM)
                    // which calendar-pedantically span two days but read
                    // to humans as a single Saturday-night affair.
                    val isMultiDay = remember(viewerStartTs, viewerEndTs, zone) {
                        val startDate = java.time.Instant.ofEpochMilli(viewerStartTs)
                            .atZone(zone).toLocalDate()
                        val endDate = java.time.Instant.ofEpochMilli(viewerEndTs)
                            .atZone(zone).toLocalDate()
                        val durationMs = viewerEndTs - viewerStartTs
                        val eighteenHoursMs = 18L * 60 * 60 * 1000
                        startDate != endDate && durationMs >= eighteenHoursMs
                    }

                    // Chip: single-day chip for non-multi-day events,
                    // range chip ("MAY 31 – JUN 3") for multi-day. The
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
                    // Subtitle composition (single source of truth):
                    //   single-day all-day      → "All day"
                    //   single-day timed        → "9:00 AM – 5:00 PM"
                    //   multi-day all-day       → "Sun – Wed · All day"
                    //   multi-day timed         → "Sun – Wed"
                    //
                    // For multi-day events the chip already shows the
                    // calendar dates ("MAY 31 – JUN 3"); the subtitle
                    // adds DOW. Showing a 9–5 time range on a 4-day
                    // timed event would mislead the recipient into
                    // thinking 9 AM–5 PM each day; the .ics carries
                    // the precise start/end times.
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
                    // Legacy fields preserved for ShareCardComposable's
                    // signature; the composable no longer reads them
                    // (timeRangeText is now the single source of subtitle
                    // truth).
                    val multiDayRangeText: String? = null

                    val shareCardViewModel: org.onekash.kashcal.ui.viewmodels.ShareCardViewModel =
                        hiltViewModel()
                    val selectedStyle by shareCardViewModel.selectedStyle.collectAsStateWithLifecycle()
                    // Re-key on showShareCardSheet so opening the same event
                    // a second time auto-picks fresh from the title (rather
                    // than holding a stale user override from a prior open).
                    // Use event.uid (always fresh per share) instead of
                    // event.id — synthetic device-event Events all have
                    // id=0L, so id can't tell two distinct shares apart.
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
                            // Synthesize a single-occurrence Event for the
                            // .ics so the recipient gets one standalone
                            // calendar entry, not the whole recurring
                            // series. The helper also strips rawIcal,
                            // organizer, and X-* properties so attendee
                            // emails don't leak to share-card recipients.
                            //
                            // File I/O on Dispatchers.IO so the Send tap
                            // doesn't jank on slow filesystems. Failure
                            // here is non-fatal; the sheet falls back to
                            // image-only ACTION_SEND.
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

                    // Render the freshly re-read event when it still matches the
                    // active occurrence; fall back to the tapped snapshot until
                    // the re-read lands (or if it missed).
                    val deviceSnapshot = deviceQuickViewEvent!!
                    val deviceIsSingleInstance =
                        !deviceSnapshot.instance.hasRrule && deviceSnapshot.instance.originalId == null
                    val deviceDisplayEvent = deviceQuickViewEventLive
                        ?.takeIf {
                            it.instance.eventId == deviceSnapshot.instance.eventId &&
                                // A non-recurring event has one instance, so id
                                // alone identifies it (and its start may have
                                // legitimately moved on an edit) — but only when
                                // the live read agrees it's still a single
                                // instance, so a snapshot that went stale before
                                // gaining an RRULE can't swap in a future
                                // occurrence. Recurring events match by occurrence.
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
                            // Single Edit path: open the form pre-filled
                            // with the tapped occurrence's data when the
                            // event is recurring; otherwise open the
                            // master directly with no occurrenceTs.
                            // Scope is decided at save-time.
                            val event = deviceDisplayEvent
                            val isException = event.instance.originalId != null
                            val isRecurringMaster = event.instance.hasRrule && !isException
                            val masterEventId = event.instance.originalId ?: event.instance.eventId
                            editingDeviceEventId = masterEventId
                            deviceEventOccurrenceTs = when {
                                // Non-recurring instance: NO occurrence ts.
                                !isRecurringMaster && !isException -> null
                                isException -> event.instance.originalInstanceTime ?: event.startTs
                                else -> event.startTs
                            }
                            deviceEventIsAllDay = event.instance.isAllDay
                            showDeviceQuickViewSheet = false
                            deviceQuickViewEvent = null
                            editingEventId = null // Clear Room edit state
                            duplicateFromEvent = null
                            showEventFormSheet = true
                        },
                        onEditOccurrence = { /* unused after save-time scope */ },
                        onDelete = {
                            // Three branches mirror the Room QuickView's
                            // onDeleteSingle:
                            // - non-recurring: deleteDeviceEvent (entire row).
                            // - exception (originalId set): deleteDeviceSingleOccurrence
                            //   on the master with the original instance time.
                            // - recurring master: scope sheet via requestDeleteDevice.
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
                            // Device event export: mirrors the Room handler above.
                            // Fetches master + exceptions directly from Events (preserving
                            // STATUS_CANCELED), maps each to a synthetic Room Event, then
                            // routes through the same icsExporter.exportEvent as Room events.
                            val event = deviceDisplayEvent
                            val masterEventId = event.instance.originalId ?: event.instance.eventId
                            coroutineScope.launch {
                                val pair = calendarProviderRepository.getDeviceEventWithExceptions(masterEventId)
                                if (pair == null) {
                                    Log.w(TAG, "Device event $masterEventId not found for export")
                                    homeViewModel.showSnackbar("Event not found")
                                    showDeviceQuickViewSheet = false
                                    deviceQuickViewEvent = null
                                    return@launch
                                }
                                val (master, exceptions) = pair
                                val allIds = (listOf(master.id) + exceptions.map { it.id }).toSet()
                                val remindersById = calendarProviderRepository.getRemindersForEvents(allIds)
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
                            // Synthesize a Room Event from the device
                            // instance and feed it into the shared
                            // share-card flow. The synthetic Event is
                            // never persisted, and attendee/organizer
                            // data can't sneak in because we don't
                            // read it from CalendarProvider here.
                            openShareCard(deviceDisplayEvent.toEventForShareCard())
                        },
                        showShareCardTooltip = !shownShareCardTooltip,
                        onShareCardTooltipDismissed = {
                            homeViewModel.markShareCardTooltipShown()
                        },
                        timeFormat = uiState.timeFormat
                    )
                }

                // Quick Add Dialog — ViewModel owned here (screen-level) per Google's
                // UI-layer guidance. State flows down; side-effects stay at the caller.
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
                            // Share-target open: anchor reference time to share arrival
                            // (NOT the day the user was browsing) so "tomorrow" in the
                            // shared text resolves correctly. Seed text + parsed location
                            // before snapshotFlow so the dialog renders the parse preview
                            // on first frame.
                            val anchor = java.time.Instant.ofEpochMilli(seed.referenceMs)
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDateTime()
                            quickAddViewModel.setReferenceTime(anchor)
                            // Programmatic edits bypass the field's InputTransformation,
                            // so apply the same newline-strip + hard cap here to keep the
                            // 500-char limit global (a shared payload can be arbitrarily long).
                            val seededText = org.onekash.kashcal.ui.components.QuickAddInputLimits
                                .takeGraphemes(
                                    seed.text.replace("\n", ""),
                                    org.onekash.kashcal.ui.components.QuickAddInputLimits.MAX_LENGTH
                                )
                            quickAddTextFieldState.edit { replace(0, length, seededText) }
                            quickAddViewModel.seedInput(seededText, seed.location)
                            quickAddShareSeed = null
                        } else {
                            // Undated input should default to the day the user is viewing,
                            // not today, when the form opens. Time-grid views (Day/3-Day/
                            // Week) don't track selectedDate as the grid pages, and their
                            // full-form FAB seeds today, so match that here rather than
                            // seed a stale selectedDate.
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
                                            // Default calendar is a device calendar — redirect to full form.
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
                            calendarIntentData = null
                            calendarIntentInvitees = emptyList()
                            // Clear device event state
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
                                    callback(false)  // Ensure callback fires even on error
                                }
                            }
                        },
                        locationSuggestionService = locationSuggestionService,
                        onSuggestTitles = { prefix -> homeViewModel.suggestTitles(prefix) },
                        categorySuggestions = uiState.categorySuggestions,
                        timeFormat = uiState.timeFormat,
                        firstDayOfWeek = uiState.firstDayOfWeek,
                        // Device calendar edit support
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
                            // Recompute the attendee/organizer context when the
                            // user switches the target calendar mid-form, so the
                            // schedulable gate + "You" detection track the new
                            // calendar's account. Cancel any prior in-flight
                            // resolution so rapid switches can't land out of order.
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
                    // hiltViewModel() returns the activity-scoped instance, so init
                    // only runs the first time the sheet opens. Re-fetch live system
                    // inputs (now, locale, 24h preference) on every open so a stale
                    // preview from a previous session doesn't leak through.
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
                } else if (uiState.whatsNewReleases.isNotEmpty()) {
                    // What's New only competes for the screen once onboarding
                    // is out of the way, so first-launch users see the iCloud
                    // prompt first.
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

                // App lock veil — composed LAST so it draws above all content;
                // the calendar is never visible while locked. Auto-fires the
                // biometric prompt on appearing and toggles FLAG_SECURE so the
                // recents thumbnail is hidden only while locked.
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
     * Fire the system biometric / device-credential prompt. Guarded so the
     * auto-fire (veil appearing) and a manual Unlock tap can't stack two sheets.
     *
     * Recovery: if the user removed all device credentials after enabling the
     * lock, the prompt would be unsatisfiable — so if nothing is enrolled we
     * unlock instead of trapping the user behind a veil that can never clear
     * (the device itself is now unsecured; there is nothing left to protect).
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
                    // Cancel / negative / any error: stay locked. The veil's
                    // always-present Unlock button re-fires this prompt.
                    isUnlockPromptShowing = false
                    appLockViewModel.onUnlockError()
                }
            },
        )
        // DEVICE_CREDENTIAL is allowed, so setNegativeButtonText must NOT be set
        // (build() would throw). setTitle is required; the instruction lives there.
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_lock_prompt_title))
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }

    /**
     * Enable / disable the app lock. Enabling adds protection so it commits behind an
     * inline confirmation; disabling REMOVES protection so it must be authenticated.
     * Capability / enrollment checks and the enrollment intent live here because they
     * need the activity and VMs must not start activities.
     */
    private fun onToggleAppLock(enabled: Boolean) {
        if (enabled) {
            when (decideEnrollmentAction(canAuthenticateForAppLock())) {
                AppLockEnrollmentAction.Enable -> {
                    appLockViewModel.setAppLockEnabled(true)
                    // Enabling adds protection, so it isn't gated behind auth —
                    // but confirm inline and set the expectation that the prompt
                    // appears on the next fresh open, not on the return to here.
                    homeViewModel.showSnackbar(getString(R.string.app_lock_enabled_message))
                }
                AppLockEnrollmentAction.RouteToEnroll ->
                    launchBiometricEnrollment()
                AppLockEnrollmentAction.Unsupported ->
                    homeViewModel.showSnackbar(getString(R.string.app_lock_unsupported_message))
            }
        } else {
            // Disabling REMOVES protection, so it must be authenticated: otherwise
            // anyone holding the already-unlocked phone could open the hub and
            // switch the lock off. Only commit false on success.
            authenticateThenDisableAppLock()
        }
    }

    /** Can the device satisfy the app lock with a strong biometric OR the screen-lock credential? */
    private fun canAuthenticateForAppLock(): Int =
        BiometricManager.from(this)
            .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)

    /**
     * Send the user to the system enrollment flow rather than enabling a lock
     * nothing can satisfy. The pre-API-30 intent (plain security settings) is
     * used as a fallback since ACTION_BIOMETRIC_ENROLL is API 30+.
     *
     * Routed through [launchInternalActivity] so the enrollment round trip does
     * not trip the re-lock on return.
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
     * Challenge the user before turning the lock OFF. The pref is only set to
     * false on a successful authentication, so possession of an already-unlocked
     * phone is not enough to disable the protection.
     *
     * Recovery: if all device credentials were removed after enabling the lock,
     * the prompt would be unsatisfiable — so when nothing is enrolled we disable
     * directly (the device is now unsecured; there is nothing left to gate on).
     * This mirrors the lock-out recovery in [promptForUnlock]. The prompt runs
     * in-process (no activity launch), so it doesn't trip the re-lock.
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
                    // Cancel / negative / any error: leave the lock ON. The toggle
                    // reflects the persisted pref, so it stays in the on state.
                    isDisablePromptShowing = false
                }
            },
        )
        // DEVICE_CREDENTIAL is allowed, so setNegativeButtonText must NOT be set
        // (build() would throw). The title carries the instruction.
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_lock_disable_prompt_title))
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }

    /**
     * Deep-link to the system settings page for a given permission kind.
     * Notifications has its own dedicated settings screen, so route there
     * directly; the other kinds have no per-permission page, so fall back to the
     * app info page (where every runtime permission can be toggled). Routed
     * through [launchInternalActivity] so the lock veil does not re-lock when the
     * user returns from the settings round trip.
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

    override fun onStart() {
        super.onStart()
        // onStart runs before onResume (which resets returningFromInternalActivity),
        // so the internal-nav signal is still valid here. Suppressing the re-lock
        // on internal-nav returns means a user who just enabled the lock in
        // Settings — or who lingered on the system enrollment screen past the
        // grace window — isn't immediately challenged on return.
        //
        // The enabled flag comes from the ViewModel's cached StateFlow (already
        // collected from DataStore) rather than a blocking read — onStart is a
        // hot path that fires on every foreground.
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

        // Handle device calendar reminder notification tap
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

        // Handle widget actions (check extras first, before URI handling)
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

        // Handle plain-text shares (ACTION_SEND text/plain) from other apps.
        // Anchored to System.currentTimeMillis() so "tomorrow" in the shared text
        // resolves relative to share-arrival, not whatever date the user was browsing.
        // intent.action is cleared after dispatch so rotation/recreation does not
        // re-fire the share over user edits.
        ShareIntentRouter.route(intent, System.currentTimeMillis())?.let { action ->
            Log.d(TAG, "Share intent: ${action::class.simpleName}")
            intent?.action = null
            homeViewModel.setPendingAction(action)
            return
        }

        // Handle shared .ics files (ACTION_SEND with the file in EXTRA_STREAM) -
        // the share-sheet counterpart to the ACTION_VIEW "open with" path below.
        // Checked after the text/plain share router so plain-text shares still win.
        // intent.action is cleared so rotation/recreation does not re-fire the import.
        IcsShareIntentParser.parse(intent)?.let { uri ->
            Log.d(TAG, "Handling shared ICS file: $uri")
            intent?.action = null
            homeViewModel.setPendingAction(PendingAction.ImportIcsFile(uri))
            return
        }

        // Handle calendar provider intents (ACTION_INSERT/EDIT) - for "Add to Calendar" from other apps
        CalendarIntentParser.parse(intent)?.let { (data, invitees) ->
            Log.d(TAG, "Calendar intent: title=${data.title}, start=${data.startTimeMillis}, invitees=${invitees.size}")
            homeViewModel.setPendingAction(PendingAction.CreateEventFromCalendarIntent(data, invitees))
            return
        }

        // Handle CalendarContract content URIs (VIEW/EDIT on content://com.android.calendar)
        // Used by launchers, clock widgets, and other apps
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
     * Navigate to a device event's start date, or show a "not found" snackbar if the event
     * can't be resolved. Used both when no occurrence timestamp was supplied and as the
     * fallback when an exact-occurrence quick-view lookup misses — so a stale or slightly-off
     * timestamp lands the user near the event instead of on a dead end.
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

    /**
     * Check if MIME type indicates an ICS calendar file. Single source of truth
     * shared with the share-sheet (ACTION_SEND) path.
     */
    private fun isIcsMimeType(mimeType: String?): Boolean =
        IcsShareIntentParser.isIcsMimeType(mimeType)
}
