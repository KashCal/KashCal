package org.onekash.kashcal.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.ui.components.ICloudSignInSheet
import org.onekash.kashcal.ui.screens.settings.AddSubscriptionDialog
import org.onekash.kashcal.ui.screens.settings.AlertsSheet
import org.onekash.kashcal.ui.screens.settings.AccentColors
import org.onekash.kashcal.ui.screens.settings.DebugMenuSheet
import org.onekash.kashcal.ui.screens.settings.DefaultCalendarSheet
import org.onekash.kashcal.ui.screens.settings.EventDurationSheet
import org.onekash.kashcal.ui.screens.settings.EventEmojisSheet
import org.onekash.kashcal.ui.screens.settings.ICloudConnectionState
import org.onekash.kashcal.ui.screens.settings.IcsSubscriptionUiModel
import org.onekash.kashcal.ui.screens.settings.SectionHeader
import org.onekash.kashcal.ui.screens.settings.SettingsCard
import org.onekash.kashcal.ui.screens.settings.SettingsRow
import org.onekash.kashcal.ui.screens.settings.SignOutConfirmationSheet
import org.onekash.kashcal.ui.screens.settings.VersionFooter
import org.onekash.kashcal.ui.screens.settings.VisibleCalendarsSheet
import org.onekash.kashcal.ui.shared.formatDuration
import org.onekash.kashcal.ui.shared.formatReminderShort
import org.onekash.kashcal.ui.shared.maskEmail

/**
 * UI state for the account settings screen.
 * Now uses a unified state with separate iCloudState for sign-in flow.
 * This allows showing all settings sections regardless of iCloud connection status.
 */
data class AccountSettingsUiState(
    val isLoading: Boolean = false,
    val iCloudState: ICloudConnectionState = ICloudConnectionState.NotConnected(),
    val showICloudSignInSheet: Boolean = false,
    /** Show add subscription dialog (controlled by ViewModel for external intents) */
    val showAddSubscriptionDialog: Boolean = false,
    /** Pre-fill URL for subscription dialog (from webcal:// intent) */
    val prefillSubscriptionUrl: String? = null,
    /** Pending snackbar message to display */
    val pendingSnackbarMessage: String? = null,
    /** Signal to finish Activity after successful initial iCloud setup */
    val pendingFinishActivity: Boolean = false
)

/**
 * Account settings screen for managing iCloud connection and calendars.
 *
 * Redesigned in v14.2.0 to use flat list layout instead of nested accordions.
 * Each row taps to open a bottom sheet or navigate to a detail screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    uiState: AccountSettingsUiState,
    onShowICloudSignIn: () -> Unit = {},
    onHideICloudSignIn: () -> Unit = {},
    onAppleIdChange: (String) -> Unit = {},
    onPasswordChange: (String) -> Unit = {},
    onToggleHelp: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
    // Calendar settings (visibility derived from Calendar.isVisible)
    calendars: List<Calendar> = emptyList(),
    onToggleCalendar: (Long, Boolean) -> Unit = { _, _ -> },
    // Hidden from UI but preserved for future use
    onShowAllCalendars: () -> Unit = {},
    onHideAllCalendars: () -> Unit = {},
    // Sync settings
    syncIntervalMs: Long = 24 * 60 * 60 * 1000L,
    onSyncIntervalChange: (Long) -> Unit = {},
    onForceFullSync: () -> Unit = {},
    // Default calendar
    defaultCalendarId: Long? = null,
    onDefaultCalendarSelect: (Long) -> Unit = {},
    // ICS Subscription callbacks
    subscriptions: List<IcsSubscriptionUiModel> = emptyList(),
    subscriptionSyncing: Boolean = false,
    onAddSubscription: (url: String, name: String, color: Int) -> Unit = { _, _, _ -> },
    onHideAddSubscriptionDialog: () -> Unit = {},
    onDeleteSubscription: (Long) -> Unit = {},
    onToggleSubscription: (Long, Boolean) -> Unit = { _, _ -> },
    onRefreshSubscription: (Long) -> Unit = {},
    onUpdateSubscription: (subscriptionId: Long, name: String, color: Int, syncIntervalHours: Int) -> Unit = { _, _, _, _ -> },
    onSyncAllSubscriptions: () -> Unit = {},
    // Sync Logs
    onShowSyncLogs: () -> Unit = {},
    // Notifications
    notificationsEnabled: Boolean = true,
    onRequestNotificationPermission: () -> Unit = {},
    // Default reminder preferences
    defaultReminderTimed: Int = 15,
    defaultReminderAllDay: Int = 720,
    defaultEventDuration: Int = 30,
    onDefaultReminderTimedChange: (Int) -> Unit = {},
    onDefaultReminderAllDayChange: (Int) -> Unit = {},
    onDefaultEventDurationChange: (Int) -> Unit = {},
    // ICS Import/Export
    onImportCalendarFile: () -> Unit = {},
    onExportCalendar: (Long) -> Unit = {},
    // Navigation to detail screens
    onNavigateToSubscriptions: () -> Unit = {},
    // Display settings
    showEventEmojis: Boolean = true,
    onShowEventEmojisChange: (Boolean) -> Unit = {},
    // Version footer (Checkpoint 9)
    versionName: String = ""
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.isLoading) {
                LoadingContent()
            } else {
                FlatSettingsContent(
                    iCloudState = uiState.iCloudState,
                    onShowICloudSignIn = onShowICloudSignIn,
                    onSignOut = onSignOut,
                    calendars = calendars,
                    onToggleCalendar = onToggleCalendar,
                    onShowAllCalendars = onShowAllCalendars,
                    onHideAllCalendars = onHideAllCalendars,
                    syncIntervalMs = syncIntervalMs,
                    onSyncIntervalChange = onSyncIntervalChange,
                    onForceFullSync = onForceFullSync,
                    defaultCalendarId = defaultCalendarId,
                    onDefaultCalendarSelect = onDefaultCalendarSelect,
                    subscriptions = subscriptions,
                    subscriptionSyncing = subscriptionSyncing,
                    onAddSubscription = onAddSubscription,
                    onDeleteSubscription = onDeleteSubscription,
                    onToggleSubscription = onToggleSubscription,
                    onRefreshSubscription = onRefreshSubscription,
                    onUpdateSubscription = onUpdateSubscription,
                    onSyncAllSubscriptions = onSyncAllSubscriptions,
                    onShowSyncLogs = onShowSyncLogs,
                    notificationsEnabled = notificationsEnabled,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    defaultReminderTimed = defaultReminderTimed,
                    defaultReminderAllDay = defaultReminderAllDay,
                    defaultEventDuration = defaultEventDuration,
                    onDefaultReminderTimedChange = onDefaultReminderTimedChange,
                    onDefaultReminderAllDayChange = onDefaultReminderAllDayChange,
                    onDefaultEventDurationChange = onDefaultEventDurationChange,
                    onImportCalendarFile = onImportCalendarFile,
                    onExportCalendar = onExportCalendar,
                    onNavigateToSubscriptions = onNavigateToSubscriptions,
                    showEventEmojis = showEventEmojis,
                    onShowEventEmojisChange = onShowEventEmojisChange,
                    showAddSubscriptionDialogFromIntent = uiState.showAddSubscriptionDialog,
                    prefillSubscriptionUrl = uiState.prefillSubscriptionUrl,
                    onHideAddSubscriptionDialog = onHideAddSubscriptionDialog,
                    versionName = versionName
                )
            }
        }

        // iCloud Sign-In Sheet
        if (uiState.showICloudSignInSheet) {
            val iCloudState = uiState.iCloudState
            val notConnectedState = iCloudState as? ICloudConnectionState.NotConnected
            ICloudSignInSheet(
                appleId = notConnectedState?.appleId ?: "",
                password = notConnectedState?.password ?: "",
                showHelp = notConnectedState?.showHelp ?: false,
                error = notConnectedState?.error,
                isConnecting = iCloudState is ICloudConnectionState.Connecting,
                onAppleIdChange = onAppleIdChange,
                onPasswordChange = onPasswordChange,
                onToggleHelp = onToggleHelp,
                onSignIn = onSignIn,
                onDismiss = onHideICloudSignIn
            )
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

/**
 * Flat settings content - single column of tappable rows.
 *
 * Layout:
 * - iCloud row (connection status)
 * - Subscriptions row (badge count, navigates to detail screen)
 * - Add Calendar row (opens bottom sheet)
 * - Visible Calendars row (opens bottom sheet)
 * - Default Calendar row (opens bottom sheet)
 * - Default Alerts row (opens bottom sheet)
 * - Notifications row (status indicator)
 * - Version footer (long-press for debug)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlatSettingsContent(
    iCloudState: ICloudConnectionState,
    onShowICloudSignIn: () -> Unit,
    onSignOut: () -> Unit,
    calendars: List<Calendar>,
    onToggleCalendar: (Long, Boolean) -> Unit,
    onShowAllCalendars: () -> Unit,
    onHideAllCalendars: () -> Unit,
    syncIntervalMs: Long,
    onSyncIntervalChange: (Long) -> Unit,
    onForceFullSync: () -> Unit,
    defaultCalendarId: Long?,
    onDefaultCalendarSelect: (Long) -> Unit,
    subscriptions: List<IcsSubscriptionUiModel>,
    subscriptionSyncing: Boolean,
    onAddSubscription: (String, String, Int) -> Unit,
    onDeleteSubscription: (Long) -> Unit,
    onToggleSubscription: (Long, Boolean) -> Unit,
    onRefreshSubscription: (Long) -> Unit,
    onUpdateSubscription: (Long, String, Int, Int) -> Unit,
    onSyncAllSubscriptions: () -> Unit,
    onShowSyncLogs: () -> Unit,
    notificationsEnabled: Boolean,
    onRequestNotificationPermission: () -> Unit,
    defaultReminderTimed: Int,
    defaultReminderAllDay: Int,
    defaultEventDuration: Int,
    onDefaultReminderTimedChange: (Int) -> Unit,
    onDefaultReminderAllDayChange: (Int) -> Unit,
    onDefaultEventDurationChange: (Int) -> Unit,
    onImportCalendarFile: () -> Unit,
    onExportCalendar: (Long) -> Unit,
    onNavigateToSubscriptions: () -> Unit,
    showEventEmojis: Boolean,
    onShowEventEmojisChange: (Boolean) -> Unit,
    showAddSubscriptionDialogFromIntent: Boolean,
    prefillSubscriptionUrl: String?,
    onHideAddSubscriptionDialog: () -> Unit,
    versionName: String
) {
    val scrollState = rememberScrollState()

    // Sheet states
    var showVisibleCalendarsSheet by remember { mutableStateOf(false) }
    var showDefaultCalendarSheet by remember { mutableStateOf(false) }
    var showAlertsSheet by remember { mutableStateOf(false) }
    var showEventEmojisSheet by remember { mutableStateOf(false) }
    var showEventDurationSheet by remember { mutableStateOf(false) }
    var showDebugMenu by remember { mutableStateOf(false) }
    var showAddSubscriptionDialog by remember { mutableStateOf(false) }
    var showSignOutConfirmation by remember { mutableStateOf(false) }

    val visibleCalendarsSheetState = rememberModalBottomSheetState()
    val defaultCalendarSheetState = rememberModalBottomSheetState()
    val alertsSheetState = rememberModalBottomSheetState()
    val eventEmojisSheetState = rememberModalBottomSheetState()
    val eventDurationSheetState = rememberModalBottomSheetState()
    val debugSheetState = rememberModalBottomSheetState()
    val signOutSheetState = rememberModalBottomSheetState()

    // Derived values
    val isConnected = iCloudState is ICloudConnectionState.Connected
    val connectedState = iCloudState as? ICloudConnectionState.Connected
    val iCloudCalendarCount = connectedState?.calendarCount ?: 0

    // Memoized: only recompute when calendars list changes
    val visibleCalendarCount = remember(calendars) {
        calendars.count { it.isVisible }
    }
    val totalCalendarCount = calendars.size  // O(1), no memoization needed

    // Memoized: recompute when calendars OR defaultCalendarId changes
    val defaultCalendar = remember(calendars, defaultCalendarId) {
        calendars.find { it.id == defaultCalendarId }
    }

    // Memoized: only recompute when appleId changes
    val maskedEmail = remember(connectedState?.appleId) {
        maskEmail(connectedState?.appleId)
    }

    // Memoized: find local calendar for export
    val localCalendar = remember(calendars) {
        calendars.find { it.caldavUrl == "local://default" }
    }

    // Add Subscription Dialog - show if local trigger OR intent trigger
    if (showAddSubscriptionDialog || showAddSubscriptionDialogFromIntent) {
        AddSubscriptionDialog(
            initialUrl = prefillSubscriptionUrl,
            onDismiss = {
                showAddSubscriptionDialog = false
                onHideAddSubscriptionDialog()
            },
            onAdd = { url, name, color ->
                onAddSubscription(url, name, color)
                showAddSubscriptionDialog = false
                onHideAddSubscriptionDialog()
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        // ==================== ACCOUNT Section ====================
        SectionHeader("Account")
        SettingsCard {
            SettingsRow(
                icon = Icons.Default.Cloud,
                label = "iCloud",
                subtitle = if (isConnected) {
                    "$maskedEmail · $iCloudCalendarCount calendars"
                } else {
                    "Tap to connect"
                },
                onClick = {
                    if (isConnected) {
                        showSignOutConfirmation = true
                    } else {
                        onShowICloudSignIn()
                    }
                },
                showDivider = false  // Last item in card
            )
        }

        // ==================== CALENDARS Section ====================
        SectionHeader("Calendars")
        SettingsCard {
            // Subscriptions Row - always navigates to detail screen
            // (Contact Birthdays is in the detail screen regardless of ICS subscriptions)
            SettingsRow(
                icon = Icons.Default.Link,
                label = "Subscriptions",
                value = if (subscriptions.isNotEmpty()) "(${subscriptions.size})" else null,
                subtitle = if (subscriptions.isNotEmpty()) {
                    subscriptions.take(2).joinToString(", ") { it.name }
                } else {
                    "ICS feeds & Contact Birthdays"
                },
                onClick = onNavigateToSubscriptions
            )

            // Import from File Row
            SettingsRow(
                icon = Icons.Default.FileDownload,
                label = "Import from File",
                subtitle = "Import .ics file",
                onClick = onImportCalendarFile,
                showDivider = localCalendar != null || calendars.isNotEmpty()
            )

            // Export Local Calendar Row (conditional)
            localCalendar?.let { local ->
                SettingsRow(
                    icon = Icons.Default.FileUpload,
                    label = "Export Local Calendar",
                    subtitle = "Backup to .ics file",
                    onClick = { onExportCalendar(local.id) },
                    showDivider = calendars.isNotEmpty()
                )
            }

            // Visible Calendars Row (conditional)
            if (calendars.isNotEmpty()) {
                SettingsRow(
                    icon = Icons.Default.Visibility,
                    label = "Visible Calendars",
                    value = "$visibleCalendarCount / $totalCalendarCount",
                    onClick = { showVisibleCalendarsSheet = true }
                )

                // Default Calendar Row
                SettingsRow(
                    icon = Icons.Default.Star,
                    label = "Default Calendar",
                    subtitle = defaultCalendar?.displayName ?: "Not set",
                    onClick = { showDefaultCalendarSheet = true },
                    showDivider = false  // Last item in card
                )
            }
        }

        // ==================== DISPLAY Section ====================
        SectionHeader("Display")
        SettingsCard {
            SettingsRow(
                icon = Icons.Default.SentimentSatisfied,
                label = "Event Emojis",
                subtitle = if (showEventEmojis) "On" else "Off",
                onClick = { showEventEmojisSheet = true }
            )
            SettingsRow(
                icon = Icons.Default.Schedule,
                label = "Default Event Length",
                subtitle = formatDuration(defaultEventDuration),
                onClick = { showEventDurationSheet = true },
                showDivider = false
            )
        }

        // ==================== NOTIFICATIONS Section ====================
        SectionHeader("Notifications")
        SettingsCard {
            // Default Alerts Row (only if calendars exist)
            if (calendars.isNotEmpty()) {
                SettingsRow(
                    icon = Icons.Default.Notifications,
                    label = "Default Alerts",
                    subtitle = "${formatReminderShort(defaultReminderTimed)} · ${formatReminderShort(defaultReminderAllDay)}",
                    onClick = { showAlertsSheet = true }
                )
            }

            // Notifications Row
            SettingsRow(
                icon = if (notificationsEnabled) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                label = "Notifications",
                subtitle = if (notificationsEnabled) "Enabled" else "Tap to enable",
                onClick = {
                    if (!notificationsEnabled) {
                        onRequestNotificationPermission()
                    }
                },
                showChevron = !notificationsEnabled,
                trailing = if (notificationsEnabled) {
                    {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Enabled",
                            tint = AccentColors.Green,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else null,
                showDivider = false  // Last item in card
            )
        }

        // ==================== Version Footer ====================
        if (versionName.isNotEmpty()) {
            VersionFooter(
                versionName = versionName,
                onLongPress = { showDebugMenu = true }
            )
        }
    }

    // ==================== Bottom Sheets ====================

    // Visible Calendars Sheet
    if (showVisibleCalendarsSheet) {
        VisibleCalendarsSheet(
            sheetState = visibleCalendarsSheetState,
            calendars = calendars,
            onToggleCalendar = onToggleCalendar,
            // Hidden from main UI but functionality preserved for future use
            onShowAllCalendars = onShowAllCalendars,
            onHideAllCalendars = onHideAllCalendars,
            onDismiss = { showVisibleCalendarsSheet = false }
        )
    }

    // Default Calendar Sheet (exclude read-only calendars like ICS subscriptions)
    if (showDefaultCalendarSheet) {
        DefaultCalendarSheet(
            sheetState = defaultCalendarSheetState,
            calendars = calendars.filter { !it.isReadOnly },
            currentDefaultId = defaultCalendarId,
            onSelectDefault = onDefaultCalendarSelect,
            onDismiss = { showDefaultCalendarSheet = false }
        )
    }

    // Alerts Sheet
    if (showAlertsSheet) {
        AlertsSheet(
            sheetState = alertsSheetState,
            defaultReminderTimed = defaultReminderTimed,
            defaultReminderAllDay = defaultReminderAllDay,
            onTimedReminderChange = onDefaultReminderTimedChange,
            onAllDayReminderChange = onDefaultReminderAllDayChange,
            onDismiss = { showAlertsSheet = false }
        )
    }

    // Event Emojis Sheet
    if (showEventEmojisSheet) {
        EventEmojisSheet(
            sheetState = eventEmojisSheetState,
            showEventEmojis = showEventEmojis,
            onShowEventEmojisChange = onShowEventEmojisChange,
            onDismiss = { showEventEmojisSheet = false }
        )
    }

    // Event Duration Sheet
    if (showEventDurationSheet) {
        EventDurationSheet(
            sheetState = eventDurationSheetState,
            defaultEventDuration = defaultEventDuration,
            onEventDurationChange = onDefaultEventDurationChange,
            onDismiss = { showEventDurationSheet = false }
        )
    }

    // Debug Menu Sheet
    if (showDebugMenu) {
        DebugMenuSheet(
            sheetState = debugSheetState,
            syncIntervalMs = syncIntervalMs,
            onSyncIntervalChange = onSyncIntervalChange,
            onForceFullSync = onForceFullSync,
            onShowSyncLogs = onShowSyncLogs,
            onDismiss = { showDebugMenu = false }
        )
    }

    // Sign Out Confirmation Sheet
    if (showSignOutConfirmation) {
        // Reuse memoized maskedEmail with fallback
        val displayEmail = maskedEmail.ifEmpty { "iCloud" }

        SignOutConfirmationSheet(
            sheetState = signOutSheetState,
            email = displayEmail,
            onConfirm = onSignOut,
            onDismiss = { showSignOutConfirmation = false }
        )
    }
}
