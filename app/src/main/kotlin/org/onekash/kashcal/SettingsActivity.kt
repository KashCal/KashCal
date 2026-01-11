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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import kotlinx.coroutines.launch
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.ics.RfcIcsParser
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.ui.components.IcsImportSheet
import org.onekash.kashcal.ui.components.SyncHistorySheet
import org.onekash.kashcal.ui.screens.AccountSettingsScreen
import org.onekash.kashcal.ui.screens.settings.SubscriptionsScreen
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.onekash.kashcal.ui.viewmodels.AccountSettingsViewModel
import org.onekash.kashcal.util.IcsExporter
import org.onekash.kashcal.util.IcsFileReader
import javax.inject.Inject

private const val TAG = "SettingsActivity"

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        enableEdgeToEdge()

        setContent {
            KashCalTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val calendars by viewModel.calendars.collectAsStateWithLifecycle()
                val defaultCalendarId by viewModel.defaultCalendarId.collectAsStateWithLifecycle()
                val syncIntervalMs by viewModel.syncIntervalMs.collectAsStateWithLifecycle()
                val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
                val subscriptionSyncing by viewModel.subscriptionSyncing.collectAsStateWithLifecycle()
                val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
                val defaultReminderTimed by viewModel.defaultReminderTimed.collectAsStateWithLifecycle()
                val defaultReminderAllDay by viewModel.defaultReminderAllDay.collectAsStateWithLifecycle()
                val defaultEventDuration by viewModel.defaultEventDuration.collectAsStateWithLifecycle()
                val showEventEmojis by viewModel.showEventEmojis.collectAsStateWithLifecycle()

                // Contact birthdays state
                val contactBirthdaysEnabled by viewModel.contactBirthdaysEnabled.collectAsStateWithLifecycle()
                val contactBirthdaysColor by viewModel.contactBirthdaysColor.collectAsStateWithLifecycle()
                val contactBirthdaysReminder by viewModel.contactBirthdaysReminder.collectAsStateWithLifecycle()
                val contactBirthdaysLastSync by viewModel.contactBirthdaysLastSync.collectAsStateWithLifecycle()
                val hasContactsPermission by viewModel.hasContactsPermission.collectAsStateWithLifecycle()

                // Contacts permission launcher
                val contactsPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    viewModel.refreshContactsPermission()
                    if (isGranted) {
                        // Permission granted, enable contact birthdays
                        viewModel.onToggleContactBirthdays(true)
                    }
                }

                // Debug log sheet state
                var showDebugLogSheet by remember { mutableStateOf(false) }

                // Navigation state for detail screens
                var showSubscriptionsScreen by remember { mutableStateOf(false) }

                // ICS import state
                var showIcsImportSheet by remember { mutableStateOf(false) }
                var icsImportEvents by remember { mutableStateOf<List<Event>>(emptyList()) }
                val coroutineScope = rememberCoroutineScope()

                // Snackbar state
                val snackbarHostState = remember { SnackbarHostState() }

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
                            IcsFileReader.readIcsContent(this@SettingsActivity, selectedUri)
                                .onSuccess { content ->
                                    try {
                                        val events = RfcIcsParser.parseIcsContent(content, 0, 0)
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

                Box(modifier = Modifier.fillMaxSize()) {
                    // State-based navigation between settings and detail screens
                    if (showSubscriptionsScreen) {
                        SubscriptionsScreen(
                            subscriptions = subscriptions,
                            contactBirthdaysEnabled = contactBirthdaysEnabled,
                            contactBirthdaysColor = contactBirthdaysColor,
                            contactBirthdaysReminder = contactBirthdaysReminder,
                            contactBirthdaysLastSync = contactBirthdaysLastSync,
                            hasContactsPermission = hasContactsPermission,
                            onNavigateBack = { showSubscriptionsScreen = false },
                            onAddSubscription = viewModel::onAddSubscription,
                            onToggleSubscription = viewModel::onToggleSubscription,
                            onDeleteSubscription = viewModel::onDeleteSubscription,
                            onRefreshSubscription = viewModel::onRefreshSubscription,
                            onUpdateSubscription = viewModel::onUpdateSubscription,
                            onToggleContactBirthdays = { enabled ->
                                if (enabled && !hasContactsPermission) {
                                    // Request permission first
                                    contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                                } else {
                                    viewModel.onToggleContactBirthdays(enabled)
                                }
                            },
                            onContactBirthdaysColorChange = viewModel::onContactBirthdaysColorChange,
                            onContactBirthdaysReminderChange = viewModel::onContactBirthdaysReminderChange
                        )
                    } else {
                        AccountSettingsScreen(
                            uiState = uiState,
                            onShowICloudSignIn = viewModel::showICloudSignInSheet,
                            onHideICloudSignIn = viewModel::hideICloudSignInSheet,
                            onAppleIdChange = viewModel::onAppleIdChange,
                            onPasswordChange = viewModel::onPasswordChange,
                            onToggleHelp = viewModel::onToggleHelp,
                            onSignIn = viewModel::onSignIn,
                            onSignOut = viewModel::onSignOut,
                            onNavigateBack = { finish() },
                            // Calendar settings (visibility derived from Calendar.isVisible)
                            calendars = calendars,
                            onToggleCalendar = viewModel::onToggleCalendar,
                            onShowAllCalendars = viewModel::onShowAllCalendars,
                            onHideAllCalendars = viewModel::onHideAllCalendars,
                            // Sync settings
                            syncIntervalMs = syncIntervalMs,
                            onSyncIntervalChange = viewModel::onSyncIntervalChange,
                            onForceFullSync = viewModel::forceFullSync,
                            // Default calendar
                            defaultCalendarId = defaultCalendarId,
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
                                            viewModel.showSnackbar("Exported ${events.size} event${if (events.size != 1) "s" else ""}")
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
                            // Display settings
                            showEventEmojis = showEventEmojis,
                            onShowEventEmojisChange = viewModel::setShowEventEmojis,
                            // Version footer
                            versionName = BuildConfig.VERSION_NAME
                        )
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
                        IcsImportSheet(
                            events = icsImportEvents,
                            calendars = calendars,
                            defaultCalendarId = defaultCalendarId,
                            onDismiss = {
                                showIcsImportSheet = false
                                icsImportEvents = emptyList()
                            },
                            onImport = { calendarId, events ->
                                coroutineScope.launch {
                                    try {
                                        val count = eventCoordinator.importIcsEvents(events, calendarId)
                                        viewModel.showSnackbar(
                                            "Imported $count event${if (count != 1) "s" else ""}"
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
    }
}
