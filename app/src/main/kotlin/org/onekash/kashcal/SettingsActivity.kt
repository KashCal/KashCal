package org.onekash.kashcal

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.ics.IcsParserService
import org.onekash.kashcal.domain.backup.BackupFilename
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.ui.components.IcsImportSheet
import org.onekash.kashcal.ui.components.SyncHistorySheet
import androidx.compose.runtime.saveable.rememberSaveable
import org.onekash.kashcal.ui.components.CalDavSignInSheet
import org.onekash.kashcal.ui.components.ICloudSignInSheet
import org.onekash.kashcal.ui.screens.AccountSettingsScreen
import org.onekash.kashcal.ui.screens.BackupRestoreUiState
import org.onekash.kashcal.ui.screens.settings.AccountConnectedSheet
import org.onekash.kashcal.ui.screens.settings.RestoreConfirmationDialog
import org.onekash.kashcal.ui.screens.settings.RestoreErrorDialog
import org.onekash.kashcal.ui.screens.settings.RestoreSuccessDialog
import org.onekash.kashcal.ui.screens.settings.ICloudAccountUiModel
import org.onekash.kashcal.ui.screens.settings.ICloudConnectionState
import org.onekash.kashcal.ui.screens.settings.AccountsScreen
import org.onekash.kashcal.ui.screens.settings.BirthdaysAndAnniversariesScreen
import org.onekash.kashcal.ui.screens.settings.SubscriptionsScreen
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.onekash.kashcal.ui.viewmodels.AccountSettingsViewModel
import org.onekash.kashcal.util.IcsExporter
import org.onekash.kashcal.util.IcsFileReader
import javax.inject.Inject

private const val TAG = "SettingsActivity"
private const val BACKUP_MIME_TYPE = "application/json"

/**
 * Settings activity hosting AccountSettingsScreen.
 * Manages iCloud account, calendar settings, and app preferences.
 */
@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_ICLOUD_SIGNIN = "open_icloud_signin"
        const val EXTRA_SUBSCRIPTION_URL = "subscription_url"
    }

    private val viewModel: AccountSettingsViewModel by viewModels()

    @Inject
    lateinit var eventCoordinator: EventCoordinator

    @Inject
    lateinit var icsExporter: IcsExporter

    @Inject
    lateinit var syncSessionStore: SyncSessionStore

    @Inject
    lateinit var icsFileReader: IcsFileReader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        enableEdgeToEdge()

        setContent {
            KashCalTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val calendars by viewModel.calendars.collectAsStateWithLifecycle()
                val calendarGroups by viewModel.calendarGroups.collectAsStateWithLifecycle()
                val defaultCalendar by viewModel.defaultCalendar.collectAsStateWithLifecycle()
                val writableDeviceCalendarGroups by viewModel.writableDeviceCalendarGroups.collectAsStateWithLifecycle()
                val syncIntervalMs by viewModel.syncIntervalMs.collectAsStateWithLifecycle()
                val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
                val subscriptionSyncing by viewModel.subscriptionSyncing.collectAsStateWithLifecycle()
                val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
                val defaultReminderTimed by viewModel.defaultReminderTimed.collectAsStateWithLifecycle()
                val defaultReminderAllDay by viewModel.defaultReminderAllDay.collectAsStateWithLifecycle()
                val defaultEventDuration by viewModel.defaultEventDuration.collectAsStateWithLifecycle()
                val showEventEmojis by viewModel.showEventEmojis.collectAsStateWithLifecycle()
                val quickAddEnabled by viewModel.quickAddEnabled.collectAsStateWithLifecycle()
                val titleSuggestionsEnabled by viewModel.titleSuggestionsEnabled.collectAsStateWithLifecycle()
                val timeFormat by viewModel.timeFormat.collectAsStateWithLifecycle()
                val firstDayOfWeek by viewModel.firstDayOfWeek.collectAsStateWithLifecycle()
                val showWeekNumbers by viewModel.showWeekNumbers.collectAsStateWithLifecycle()
                val widgetMaxEventsPerDay by viewModel.widgetMaxEventsPerDay.collectAsStateWithLifecycle()
                val syncLookbackDays by viewModel.syncLookbackDays.collectAsStateWithLifecycle()

                // Contact birthdays state
                val contactBirthdaysEnabled by viewModel.contactBirthdaysEnabled.collectAsStateWithLifecycle()
                val contactBirthdaysColor by viewModel.contactBirthdaysColor.collectAsStateWithLifecycle()
                val contactBirthdaysReminder by viewModel.contactBirthdaysReminder.collectAsStateWithLifecycle()
                val hasContactsPermission by viewModel.hasContactsPermission.collectAsStateWithLifecycle()
                val birthdayCount by viewModel.birthdayCount.collectAsStateWithLifecycle()

                // Contact anniversaries state
                val contactAnniversariesEnabled by viewModel.contactAnniversariesEnabled.collectAsStateWithLifecycle()
                val contactAnniversariesColor by viewModel.contactAnniversariesColor.collectAsStateWithLifecycle()
                val contactAnniversariesReminder by viewModel.contactAnniversariesReminder.collectAsStateWithLifecycle()
                val anniversaryCount by viewModel.anniversaryCount.collectAsStateWithLifecycle()

                // Device calendars state
                val deviceCalendarsEnabled by viewModel.deviceCalendarsEnabled.collectAsStateWithLifecycle()
                val hasReadCalendarPermission by viewModel.hasReadCalendarPermission.collectAsStateWithLifecycle()
                val hasWriteCalendarPermission by viewModel.hasWriteCalendarPermission.collectAsStateWithLifecycle()
                val deviceCalendars by viewModel.deviceCalendars.collectAsStateWithLifecycle()
                val enabledDeviceCalendarIds by viewModel.enabledDeviceCalendarIds.collectAsStateWithLifecycle()
                val showDeclinedEvents by viewModel.showDeclinedEvents.collectAsStateWithLifecycle()
                val deviceCalendarRemindersEnabled by viewModel.deviceCalendarRemindersEnabled.collectAsStateWithLifecycle()

                // iCloud account for AccountsScreen — derived from uiState (single source of truth)
                val iCloudAccount = remember(uiState.iCloudState) {
                    (uiState.iCloudState as? ICloudConnectionState.Connected)?.let {
                        ICloudAccountUiModel(
                            accountId = it.accountId,
                            email = it.appleId,
                            calendarCount = it.calendarCount,
                            consecutiveSyncFailures = it.consecutiveSyncFailures,
                            lastSuccessfulSyncAt = it.lastSyncTime
                        )
                    }
                }

                // Track which toggle triggered contacts permission request
                var pendingContactPermissionAction by remember {
                    mutableStateOf<String?>(null) // "birthdays" or "anniversaries"
                }

                // Contacts permission launcher
                val contactsPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    viewModel.refreshContactsPermission()
                    if (isGranted) {
                        when (pendingContactPermissionAction) {
                            "birthdays" -> viewModel.onToggleContactBirthdays(true)
                            "anniversaries" -> viewModel.onToggleContactAnniversaries(true)
                        }
                    }
                    pendingContactPermissionAction = null
                }

                // Calendar permission launcher (for Device Calendars - READ + WRITE)
                // Requests both permissions upfront so users can create/edit device calendar events
                val calendarPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    viewModel.refreshCalendarPermission()
                    // Enable if at least READ was granted (WRITE is optional but preferred)
                    val readGranted = permissions[Manifest.permission.READ_CALENDAR] == true
                    if (readGranted) {
                        viewModel.onToggleDeviceCalendars(true)
                    }
                }

                // Snackbar state (defined early for use in permission launchers)
                val coroutineScope = rememberCoroutineScope()
                val snackbarHostState = remember { SnackbarHostState() }

                // Calendar permission launcher (for Device Calendars - WRITE)
                val writeCalendarPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    viewModel.refreshCalendarPermission()
                    if (!isGranted) {
                        // Permission denied - show instructions to toggle Calendar permission in Settings
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                message = "Toggle Calendar permission off/on in Settings to grant write access"
                            )
                        }
                    }
                }

                // Debug log sheet state
                var showDebugLogSheet by remember { mutableStateOf(false) }

                // Navigation state for detail screens (rememberSaveable for config change survival)
                var showAccountsScreen by rememberSaveable { mutableStateOf(false) }
                var showSubscriptionsScreen by rememberSaveable { mutableStateOf(false) }
                var showBirthdaysAnniversariesScreen by rememberSaveable { mutableStateOf(false) }

                // ICS import state
                var showIcsImportSheet by remember { mutableStateOf(false) }
                var icsImportEvents by remember { mutableStateOf<List<Event>>(emptyList()) }

                // Account connected success sheet state
                @OptIn(ExperimentalMaterial3Api::class)
                val accountConnectedSheetState = rememberModalBottomSheetState()

                // Show snackbar when message is pending
                LaunchedEffect(uiState.pendingSnackbarMessage) {
                    uiState.pendingSnackbarMessage?.let { message ->
                        snackbarHostState.showSnackbar(message)
                        viewModel.clearSnackbar()
                    }
                }

                // Auto-finish activity after initial iCloud setup (navigate back to HomeScreen)
                LaunchedEffect(uiState.pendingFinishActivity) {
                    if (uiState.pendingFinishActivity) {
                        Log.d(TAG, "Auto-navigating back to HomeScreen after iCloud setup")
                        finish()
                    }
                }

                // File picker for ICS import
                val importFileLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri?.let { selectedUri ->
                        coroutineScope.launch {
                            icsFileReader.readIcsContent(selectedUri)
                                .onSuccess { content ->
                                    try {
                                        val events = IcsParserService.parseIcsContent(content, 0, 0)
                                        if (events.isNotEmpty()) {
                                            icsImportEvents = events
                                            showIcsImportSheet = true
                                        } else {
                                            viewModel.showSnackbar("No events found in file")
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to parse ICS file", e)
                                        viewModel.showSnackbar("Invalid ICS file")
                                    }
                                }
                                .onFailure { e ->
                                    Log.e(TAG, "Failed to read ICS file", e)
                                    viewModel.showSnackbar("Could not read file")
                                }
                        }
                    }
                }

                val backupExportLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)
                ) { uri ->
                    val json = viewModel.consumePendingExportJson()
                    if (uri == null || json == null) return@rememberLauncherForActivityResult
                    coroutineScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                contentResolver.openOutputStream(uri)?.use { out ->
                                    out.write(json.toByteArray(Charsets.UTF_8))
                                } ?: error("Could not open output stream")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to write backup file", e)
                            viewModel.showSnackbar(getString(R.string.backup_error_write_failed))
                        }
                    }
                }

                val backupImportLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    coroutineScope.launch {
                        try {
                            val json = withContext(Dispatchers.IO) {
                                contentResolver.openInputStream(uri)?.use {
                                    it.readBytes().toString(Charsets.UTF_8)
                                } ?: error("Could not open input stream")
                            }
                            viewModel.onBackupFileSelected(json)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to read backup file", e)
                            viewModel.showSnackbar(getString(R.string.backup_error_read_failed))
                        }
                    }
                }

                val backupRestoreState by viewModel.backupRestoreState.collectAsStateWithLifecycle()

                Box(modifier = Modifier.fillMaxSize()) {
                    // State-based navigation between settings and detail screens
                    when {
                        showAccountsScreen -> {
                            AccountsScreen(
                                iCloudAccount = iCloudAccount,
                                showAddICloud = uiState.iCloudState is ICloudConnectionState.NotConnected,
                                calDavAccounts = uiState.calDavAccounts,
                                onNavigateBack = { showAccountsScreen = false },
                                onAddICloud = viewModel::showICloudSignInSheet,
                                onICloudSignOut = viewModel::onSignOut,
                                onAddCalDav = viewModel::showCalDavSignInSheet,
                                onCalDavSignOut = viewModel::onCalDavSignOut,
                                accountDetail = uiState.accountDetail,
                                accountDetailSyncStatus = uiState.accountDetailSyncStatus,
                                accountDetailDiscoverStatus = uiState.accountDetailDiscoverStatus,
                                onObserveAccountDetail = viewModel::observeAccountDetail,
                                onClearAccountDetail = viewModel::clearAccountDetail,
                                onSyncAccountNow = viewModel::syncAccountNow,
                                onToggleAccountEnabled = viewModel::toggleAccountEnabled,
                                onRenameAccount = viewModel::renameAccount,
                                onChangeAccountPassword = viewModel::changeAccountPassword,
                                onDiscoverCalendars = viewModel::discoverNewCalendars
                            )
                        }
                        showBirthdaysAnniversariesScreen -> {
                            BirthdaysAndAnniversariesScreen(
                                birthdaysEnabled = contactBirthdaysEnabled,
                                birthdaysColor = contactBirthdaysColor,
                                birthdaysReminder = contactBirthdaysReminder,
                                birthdayCount = birthdayCount,
                                anniversariesEnabled = contactAnniversariesEnabled,
                                anniversariesColor = contactAnniversariesColor,
                                anniversariesReminder = contactAnniversariesReminder,
                                anniversaryCount = anniversaryCount,
                                hasPermission = hasContactsPermission,
                                timeFormat = timeFormat,
                                onToggleBirthdays = { enabled ->
                                    if (enabled && !hasContactsPermission) {
                                        pendingContactPermissionAction = "birthdays"
                                        contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                                    } else {
                                        viewModel.onToggleContactBirthdays(enabled)
                                    }
                                },
                                onBirthdaysColorChange = viewModel::onContactBirthdaysColorChange,
                                onBirthdaysReminderChange = viewModel::onContactBirthdaysReminderChange,
                                onToggleAnniversaries = { enabled ->
                                    if (enabled && !hasContactsPermission) {
                                        pendingContactPermissionAction = "anniversaries"
                                        contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                                    } else {
                                        viewModel.onToggleContactAnniversaries(enabled)
                                    }
                                },
                                onAnniversariesColorChange = viewModel::onContactAnniversariesColorChange,
                                onAnniversariesReminderChange = viewModel::onContactAnniversariesReminderChange,
                                onNavigateBack = { showBirthdaysAnniversariesScreen = false }
                            )
                        }
                        showSubscriptionsScreen -> {
                            SubscriptionsScreen(
                                subscriptions = subscriptions,
                                onNavigateBack = { showSubscriptionsScreen = false },
                                onAddSubscription = viewModel::onAddSubscription,
                                onToggleSubscription = viewModel::onToggleSubscription,
                                onDeleteSubscription = viewModel::onDeleteSubscription,
                                onRefreshSubscription = viewModel::onRefreshSubscription,
                                onUpdateSubscription = viewModel::onUpdateSubscription
                            )
                        }
                        else -> {
                            AccountSettingsScreen(
                            uiState = uiState,
                            onShowICloudSignIn = viewModel::showICloudSignInSheet,
                            onHideICloudSignIn = viewModel::hideICloudSignInSheet,
                            onAppleIdChange = viewModel::onAppleIdChange,
                            onPasswordChange = viewModel::onPasswordChange,
                            onToggleHelp = viewModel::onToggleHelp,
                            onSignIn = viewModel::onSignIn,
                            onSignOut = viewModel::onSignOut,
                            // CalDAV callbacks
                            onShowCalDavSignIn = viewModel::showCalDavSignInSheet,
                            onHideCalDavSignIn = viewModel::hideCalDavSignInSheet,
                            onCalDavServerUrlChange = viewModel::onCalDavServerUrlChange,
                            onCalDavDisplayNameChange = viewModel::onCalDavDisplayNameChange,
                            onCalDavUsernameChange = viewModel::onCalDavUsernameChange,
                            onCalDavPasswordChange = viewModel::onCalDavPasswordChange,
                            onCalDavTrustInsecureChange = viewModel::onCalDavTrustInsecureChange,
                            onCalDavDiscover = viewModel::onCalDavDiscover,
                            onCalDavSignOut = viewModel::onCalDavSignOut,
                            onNavigateBack = { finish() },
                            // Calendar settings (visibility derived from Calendar.isVisible)
                            calendars = calendars,
                            calendarGroups = calendarGroups,
                            onToggleCalendar = viewModel::onToggleCalendar,
                            onShowAllCalendars = viewModel::onShowAllCalendars,
                            onHideAllCalendars = viewModel::onHideAllCalendars,
                            // Sync settings
                            syncIntervalMs = syncIntervalMs,
                            onSyncIntervalChange = viewModel::onSyncIntervalChange,
                            onForceFullSync = viewModel::forceFullSync,
                            syncLookbackDays = syncLookbackDays,
                            onSyncLookbackChange = viewModel::onSyncLookbackChange,
                            // Default calendar
                            defaultCalendar = defaultCalendar,
                            writableDeviceCalendarGroups = writableDeviceCalendarGroups,
                            onDefaultCalendarSelect = viewModel::onDefaultCalendarSelect,
                            // ICS Subscriptions
                            subscriptions = subscriptions,
                            subscriptionSyncing = subscriptionSyncing,
                            onAddSubscription = viewModel::onAddSubscription,
                            onHideAddSubscriptionDialog = viewModel::hideAddSubscriptionDialog,
                            onDeleteSubscription = viewModel::onDeleteSubscription,
                            onToggleSubscription = viewModel::onToggleSubscription,
                            onRefreshSubscription = viewModel::onRefreshSubscription,
                            onUpdateSubscription = viewModel::onUpdateSubscription,
                            onSyncAllSubscriptions = viewModel::onSyncAllSubscriptions,
                            // System
                            onShowSyncLogs = { showDebugLogSheet = true },
                            notificationsEnabled = notificationsEnabled,
                            onRequestNotificationPermission = viewModel::onRequestNotificationPermission,
                            // Default reminders and event duration
                            defaultReminderTimed = defaultReminderTimed,
                            defaultReminderAllDay = defaultReminderAllDay,
                            defaultEventDuration = defaultEventDuration,
                            onDefaultReminderTimedChange = viewModel::onDefaultReminderTimedChange,
                            onDefaultReminderAllDayChange = viewModel::onDefaultReminderAllDayChange,
                            onDefaultEventDurationChange = viewModel::onDefaultEventDurationChange,
                            // ICS Import
                            onImportCalendarFile = {
                                importFileLauncher.launch(arrayOf(
                                    "text/calendar",
                                    "application/ics",
                                    "text/x-vcalendar"
                                ))
                            },
                            onBackupSettings = {
                                coroutineScope.launch {
                                    try {
                                        viewModel.prepareExport()
                                        backupExportLauncher.launch(
                                            BackupFilename.generate(
                                                java.time.Instant.now(),
                                                java.time.ZoneId.systemDefault(),
                                            )
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to build backup JSON", e)
                                        viewModel.showSnackbar(getString(R.string.backup_error_build_failed))
                                    }
                                }
                            },
                            onRestoreSettings = {
                                backupImportLauncher.launch(arrayOf(BACKUP_MIME_TYPE))
                            },
                            // ICS Export
                            onExportCalendar = { calendarId ->
                                coroutineScope.launch {
                                    try {
                                        val calendar = eventCoordinator.getCalendarById(calendarId)
                                        if (calendar == null) {
                                            viewModel.showSnackbar("Calendar not found")
                                            return@launch
                                        }
                                        val events = eventCoordinator.getCalendarEventsForExport(calendarId)
                                        if (events.isEmpty()) {
                                            viewModel.showSnackbar("No events to export")
                                            return@launch
                                        }
                                        icsExporter.exportCalendar(
                                            context = this@SettingsActivity,
                                            events = events,
                                            calendarName = calendar.displayName
                                        ).onSuccess { uri ->
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/calendar"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            startActivity(Intent.createChooser(intent, "Export Calendar"))
                                            viewModel.showSnackbar(resources.getQuantityString(R.plurals.exported_events, events.size, events.size))
                                        }.onFailure { e ->
                                            Log.e(TAG, "Failed to export calendar", e)
                                            viewModel.showSnackbar("Export failed")
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to export calendar", e)
                                        viewModel.showSnackbar("Export failed")
                                    }
                                }
                            },
                            // Navigate to Subscriptions detail screen
                            onNavigateToSubscriptions = { showSubscriptionsScreen = true },
                            // Navigate to Birthdays & Anniversaries detail screen
                            onNavigateToBirthdaysAnniversaries = { showBirthdaysAnniversariesScreen = true },
                            // Contact event counts (for B&A row subtitle)
                            birthdayCount = birthdayCount,
                            anniversaryCount = anniversaryCount,
                            // Device calendars
                            deviceCalendarsEnabled = deviceCalendarsEnabled,
                            hasReadCalendarPermission = hasReadCalendarPermission,
                            hasWriteCalendarPermission = hasWriteCalendarPermission,
                            deviceCalendars = deviceCalendars,
                            enabledDeviceCalendarIds = enabledDeviceCalendarIds,
                            onToggleDeviceCalendars = { enabled ->
                                if (enabled && !hasReadCalendarPermission) {
                                    // Request both READ and WRITE permissions upfront
                                    calendarPermissionLauncher.launch(arrayOf(
                                        Manifest.permission.READ_CALENDAR,
                                        Manifest.permission.WRITE_CALENDAR
                                    ))
                                } else {
                                    viewModel.onToggleDeviceCalendars(enabled)
                                }
                            },
                            onToggleDeviceCalendar = viewModel::onToggleDeviceCalendar,
                            onRequestWriteCalendarPermission = {
                                writeCalendarPermissionLauncher.launch(Manifest.permission.WRITE_CALENDAR)
                            },
                            showDeclinedEvents = showDeclinedEvents,
                            onToggleShowDeclinedEvents = viewModel::onToggleShowDeclinedEvents,
                            deviceCalendarRemindersEnabled = deviceCalendarRemindersEnabled,
                            onToggleDeviceCalendarReminders = viewModel::onToggleDeviceCalendarReminders,
                            onRefreshDeviceCalendars = viewModel::refreshDeviceCalendars,
                            // Display settings
                            showEventEmojis = showEventEmojis,
                            onShowEventEmojisChange = viewModel::setShowEventEmojis,
                            quickAddEnabled = quickAddEnabled,
                            onQuickAddEnabledChange = viewModel::setQuickAddEnabled,
                            titleSuggestionsEnabled = titleSuggestionsEnabled,
                            onTitleSuggestionsEnabledChange = viewModel::setTitleSuggestionsEnabled,
                            timeFormat = timeFormat,
                            onTimeFormatChange = viewModel::setTimeFormat,
                            firstDayOfWeek = firstDayOfWeek,
                            onFirstDayOfWeekChange = viewModel::setFirstDayOfWeek,
                            showWeekNumbers = showWeekNumbers,
                            onShowWeekNumbersChange = viewModel::setShowWeekNumbers,
                            widgetMaxEventsPerDay = widgetMaxEventsPerDay,
                            onWidgetMaxEventsPerDayChange = viewModel::setWidgetMaxEventsPerDay,
                            // Version footer
                            versionName = BuildConfig.VERSION_NAME,
                            // Navigate to Accounts detail screen
                            onNavigateToAccounts = { showAccountsScreen = true }
                        )
                        }
                    }

                    // Snackbar host for displaying messages
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                    )

                    // ICS Import Sheet
                    if (showIcsImportSheet && icsImportEvents.isNotEmpty()) {
                        val defaultRoomCalendarId = (defaultCalendar as? DefaultCalendar.Room)?.calendarId
                        val defaultDeviceCalendarId = (defaultCalendar as? DefaultCalendar.Device)?.calendarId
                        IcsImportSheet(
                            events = icsImportEvents,
                            calendars = calendars,
                            defaultCalendarId = defaultRoomCalendarId,
                            deviceCalendarGroups = writableDeviceCalendarGroups,
                            defaultDeviceCalendarId = defaultDeviceCalendarId,
                            onDismiss = {
                                showIcsImportSheet = false
                                icsImportEvents = emptyList()
                            },
                            onImport = { calendarId, events, isDeviceCalendar ->
                                coroutineScope.launch {
                                    try {
                                        val count = if (isDeviceCalendar) {
                                            viewModel.importIcsToDeviceCalendar(events, calendarId)
                                        } else {
                                            eventCoordinator.importIcsEvents(events, calendarId)
                                        }
                                        viewModel.showSnackbar(
                                            resources.getQuantityString(R.plurals.imported_events, count, count)
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to import events", e)
                                        viewModel.showSnackbar("Import failed")
                                    }
                                    showIcsImportSheet = false
                                    icsImportEvents = emptyList()
                                }
                            }
                        )
                    }

                    // Sync history bottom sheet
                    if (showDebugLogSheet) {
                        SyncHistorySheet(
                            syncSessionStore = syncSessionStore,
                            onDismiss = { showDebugLogSheet = false }
                        )
                    }

                    // iCloud Sign-In Sheet (at top level so it shows from any screen)
                    if (uiState.showICloudSignInSheet) {
                        val iCloudState = uiState.iCloudState
                        val notConnectedState = iCloudState as? ICloudConnectionState.NotConnected
                        ICloudSignInSheet(
                            appleId = notConnectedState?.appleId.orEmpty(),
                            password = notConnectedState?.password.orEmpty(),
                            showHelp = notConnectedState?.showHelp ?: false,
                            error = notConnectedState?.error,
                            isConnecting = iCloudState is ICloudConnectionState.Connecting,
                            onAppleIdChange = viewModel::onAppleIdChange,
                            onPasswordChange = viewModel::onPasswordChange,
                            onToggleHelp = viewModel::onToggleHelp,
                            onSignIn = viewModel::onSignIn,
                            onDismiss = viewModel::hideICloudSignInSheet
                        )
                    }

                    // CalDAV Sign-In Sheet (at top level so it shows from any screen)
                    if (uiState.showCalDavSignInSheet) {
                        CalDavSignInSheet(
                            state = uiState.calDavState,
                            onServerUrlChange = viewModel::onCalDavServerUrlChange,
                            onDisplayNameChange = viewModel::onCalDavDisplayNameChange,
                            onUsernameChange = viewModel::onCalDavUsernameChange,
                            onPasswordChange = viewModel::onCalDavPasswordChange,
                            onTrustInsecureChange = viewModel::onCalDavTrustInsecureChange,
                            onDiscover = viewModel::onCalDavDiscover,
                            onDismiss = viewModel::hideCalDavSignInSheet
                        )
                    }

                    when (val state = backupRestoreState) {
                        is BackupRestoreUiState.PendingConfirmation -> RestoreConfirmationDialog(
                            summary = state.summary,
                            onConfirm = viewModel::confirmRestore,
                            onDismiss = viewModel::dismissDialog,
                        )
                        is BackupRestoreUiState.Error -> RestoreErrorDialog(
                            error = state.error,
                            onDismiss = viewModel::dismissDialog,
                        )
                        is BackupRestoreUiState.Success -> RestoreSuccessDialog(
                            result = state.result,
                            onDismiss = viewModel::dismissDialog,
                        )
                        BackupRestoreUiState.Idle -> Unit
                    }

                    // Account Connected Success Sheet (shown after iCloud or CalDAV connection)
                    @OptIn(ExperimentalMaterial3Api::class)
                    if (uiState.showAccountConnectedSheet) {
                        AccountConnectedSheet(
                            sheetState = accountConnectedSheetState,
                            providerName = uiState.connectedProviderName,
                            email = uiState.connectedEmail,
                            calendarCount = uiState.connectedCalendarCount,
                            onAddAnother = viewModel::hideAccountConnectedSheet,
                            onDone = viewModel::onAccountConnectedDone,
                            onDismiss = viewModel::hideAccountConnectedSheet
                        )
                    }
                }
            }
        }

        // Auto-open iCloud sign-in sheet if launched from onboarding
        if (intent.getBooleanExtra(EXTRA_OPEN_ICLOUD_SIGNIN, false)) {
            viewModel.setInitialSetupMode(true)  // Auto-navigate back after sign-in
            viewModel.showICloudSignInSheet()
        }

        // Auto-open subscription dialog if launched from webcal:// link
        intent.getStringExtra(EXTRA_SUBSCRIPTION_URL)?.let { url ->
            viewModel.openAddSubscriptionWithUrl(url)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - refreshing permissions")
        viewModel.refreshNotificationPermission()
        viewModel.refreshContactsPermission()
        viewModel.refreshCalendarPermission()
    }
}
