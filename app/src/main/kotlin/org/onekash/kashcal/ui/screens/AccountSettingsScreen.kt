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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewWeek
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
import android.text.format.DateFormat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.ui.components.AppInfoSheet
import org.onekash.kashcal.ui.components.CalDavSignInSheet
import org.onekash.kashcal.ui.components.ICloudSignInSheet
import org.onekash.kashcal.ui.screens.settings.AddSubscriptionDialog
import org.onekash.kashcal.ui.screens.settings.CalDavAccountUiModel
import org.onekash.kashcal.ui.screens.settings.CalDavConnectionState
import org.onekash.kashcal.ui.screens.settings.AlertsSheet
import org.onekash.kashcal.ui.screens.settings.AccentColors
import org.onekash.kashcal.ui.screens.settings.NewBadge
import org.onekash.kashcal.ui.screens.settings.DebugMenuSheet
import org.onekash.kashcal.ui.screens.settings.DefaultCalendarSheet
import org.onekash.kashcal.ui.screens.settings.DeviceCalendarsSheet
import org.onekash.kashcal.ui.screens.settings.EventDurationSheet
import org.onekash.kashcal.ui.screens.settings.EventEmojisSheet
import org.onekash.kashcal.ui.screens.settings.WidgetEventLimitSheet
import org.onekash.kashcal.ui.screens.settings.AccountDetailDiscoverStatus
import org.onekash.kashcal.ui.screens.settings.AccountDetailSyncStatus
import org.onekash.kashcal.ui.screens.settings.AccountDetailUiModel
import org.onekash.kashcal.ui.screens.settings.ICloudConnectionState
import org.onekash.kashcal.ui.screens.settings.IcsSubscriptionUiModel
import org.onekash.kashcal.ui.screens.settings.SectionHeader
import org.onekash.kashcal.ui.screens.settings.SettingsCard
import org.onekash.kashcal.ui.screens.settings.SettingsRow
import org.onekash.kashcal.ui.screens.settings.FirstDayOfWeekSheet
import org.onekash.kashcal.ui.screens.settings.SyncLookbackSheet
import org.onekash.kashcal.ui.screens.settings.TimeFormatSheet
import org.onekash.kashcal.ui.screens.settings.VersionFooter
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.ui.shared.formatDuration
import org.onekash.kashcal.ui.shared.formatSyncLookback
import org.onekash.kashcal.ui.shared.formatReminderShort
import org.onekash.kashcal.util.DateTimeUtils

/**
 * UI state for the account settings screen.
 * Now uses a unified state with separate iCloudState and calDavState for sign-in flows.
 * This allows showing all settings sections regardless of account connection status.
 */
data class AccountSettingsUiState(
    val isLoading: Boolean = false,
    val iCloudState: ICloudConnectionState = ICloudConnectionState.NotConnected(),
    val showICloudSignInSheet: Boolean = false,
    /** CalDAV connection state for generic CalDAV servers */
    val calDavState: CalDavConnectionState = CalDavConnectionState.NotConnected(),
    val showCalDavSignInSheet: Boolean = false,
    /** Number of connected CalDAV accounts */
    val calDavAccountCount: Int = 0,
    /** List of connected CalDAV accounts for display */
    val calDavAccounts: List<CalDavAccountUiModel> = emptyList(),
    /** Show add subscription dialog (controlled by ViewModel for external intents) */
    val showAddSubscriptionDialog: Boolean = false,
    /** Pre-fill URL for subscription dialog (from webcal:// intent) */
    val prefillSubscriptionUrl: String? = null,
    /** Pending snackbar message to display */
    val pendingSnackbarMessage: String? = null,
    /** Signal to finish Activity after successful initial iCloud setup */
    val pendingFinishActivity: Boolean = false,
    /** Show success sheet after account connection */
    val showAccountConnectedSheet: Boolean = false,
    /** Provider name for success sheet (e.g., "iCloud", "Nextcloud") */
    val connectedProviderName: String = "",
    /** Email for success sheet */
    val connectedEmail: String = "",
    /** Calendar count for success sheet */
    val connectedCalendarCount: Int = 0,
    /** Account detail sheet state */
    val accountDetail: AccountDetailUiModel? = null,
    /** Sync status for account detail sheet */
    val accountDetailSyncStatus: AccountDetailSyncStatus = AccountDetailSyncStatus.Idle,
    /** Discovery status for account detail sheet */
    val accountDetailDiscoverStatus: AccountDetailDiscoverStatus = AccountDetailDiscoverStatus.Idle
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
    // CalDAV callbacks
    onShowCalDavSignIn: () -> Unit = {},
    onHideCalDavSignIn: () -> Unit = {},
    onCalDavServerUrlChange: (String) -> Unit = {},
    onCalDavDisplayNameChange: (String) -> Unit = {},
    onCalDavUsernameChange: (String) -> Unit = {},
    onCalDavPasswordChange: (String) -> Unit = {},
    onCalDavTrustInsecureChange: (Boolean) -> Unit = {},
    onCalDavDiscover: () -> Unit = {},
    onCalDavSignOut: (Long) -> Unit = {},
    onNavigateBack: () -> Unit = {},
    // Calendar settings (visibility derived from Calendar.isVisible)
    calendars: List<Calendar> = emptyList(),
    calendarGroups: List<CalendarGroup> = emptyList(),
    onToggleCalendar: (Long, Boolean) -> Unit = { _, _ -> },
    // Hidden from UI but preserved for future use
    onShowAllCalendars: () -> Unit = {},
    onHideAllCalendars: () -> Unit = {},
    // Sync settings
    syncIntervalMs: Long = 24 * 60 * 60 * 1000L,
    onSyncIntervalChange: (Long) -> Unit = {},
    onForceFullSync: () -> Unit = {},
    syncLookbackDays: Int = KashCalDataStore.DEFAULT_SYNC_PAST_DAYS,
    onSyncLookbackChange: (Int) -> Unit = {},
    // Default calendar
    defaultCalendar: DefaultCalendar? = null,
    writableDeviceCalendarGroups: List<CalendarGroup> = emptyList(),
    onDefaultCalendarSelect: (DefaultCalendar) -> Unit = {},
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
    onNavigateToAccounts: () -> Unit = {},
    onNavigateToSubscriptions: () -> Unit = {},
    onNavigateToBirthdaysAnniversaries: () -> Unit = {},
    // Contact event counts (for B&A row subtitle)
    birthdayCount: Int = 0,
    anniversaryCount: Int = 0,
    // Device calendars
    deviceCalendarsEnabled: Boolean = false,
    hasReadCalendarPermission: Boolean = false,
    hasWriteCalendarPermission: Boolean = false,
    deviceCalendars: List<DeviceCalendar> = emptyList(),
    enabledDeviceCalendarIds: Set<Long> = emptySet(),
    onToggleDeviceCalendars: (Boolean) -> Unit = {},
    onToggleDeviceCalendar: (Long, Boolean) -> Unit = { _, _ -> },
    onRequestWriteCalendarPermission: () -> Unit = {},
    showDeclinedEvents: Boolean = false,
    onToggleShowDeclinedEvents: (Boolean) -> Unit = {},
    deviceCalendarRemindersEnabled: Boolean = false,
    onToggleDeviceCalendarReminders: (Boolean) -> Unit = {},
    onRefreshDeviceCalendars: () -> Unit = {},
    // Display settings
    showEventEmojis: Boolean = true,
    onShowEventEmojisChange: (Boolean) -> Unit = {},
    quickAddEnabled: Boolean = false,
    onQuickAddEnabledChange: (Boolean) -> Unit = {},
    timeFormat: String = KashCalDataStore.TIME_FORMAT_SYSTEM,
    onTimeFormatChange: (String) -> Unit = {},
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY,
    onFirstDayOfWeekChange: (Int) -> Unit = {},
    showWeekNumbers: Boolean = false,
    onShowWeekNumbersChange: (Boolean) -> Unit = {},
    widgetMaxEventsPerDay: Int = 5,
    onWidgetMaxEventsPerDayChange: (Int) -> Unit = {},
    // Version footer (Checkpoint 9)
    versionName: String = ""
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
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
                    calDavAccounts = uiState.calDavAccounts,
                    onNavigateToAccounts = onNavigateToAccounts,
                    calendars = calendars,
                    calendarGroups = calendarGroups,
                    onToggleCalendar = onToggleCalendar,
                    onShowAllCalendars = onShowAllCalendars,
                    onHideAllCalendars = onHideAllCalendars,
                    syncIntervalMs = syncIntervalMs,
                    onSyncIntervalChange = onSyncIntervalChange,
                    onForceFullSync = onForceFullSync,
                    syncLookbackDays = syncLookbackDays,
                    onSyncLookbackChange = onSyncLookbackChange,
                    defaultCalendar = defaultCalendar,
                    writableDeviceCalendarGroups = writableDeviceCalendarGroups,
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
                    onNavigateToBirthdaysAnniversaries = onNavigateToBirthdaysAnniversaries,
                    birthdayCount = birthdayCount,
                    anniversaryCount = anniversaryCount,
                    deviceCalendarsEnabled = deviceCalendarsEnabled,
                    hasReadCalendarPermission = hasReadCalendarPermission,
                    hasWriteCalendarPermission = hasWriteCalendarPermission,
                    deviceCalendars = deviceCalendars,
                    enabledDeviceCalendarIds = enabledDeviceCalendarIds,
                    onToggleDeviceCalendars = onToggleDeviceCalendars,
                    onToggleDeviceCalendar = onToggleDeviceCalendar,
                    onRequestWriteCalendarPermission = onRequestWriteCalendarPermission,
                    showDeclinedEvents = showDeclinedEvents,
                    onToggleShowDeclinedEvents = onToggleShowDeclinedEvents,
                    deviceCalendarRemindersEnabled = deviceCalendarRemindersEnabled,
                    onToggleDeviceCalendarReminders = onToggleDeviceCalendarReminders,
                    onRefreshDeviceCalendars = onRefreshDeviceCalendars,
                    showEventEmojis = showEventEmojis,
                    onShowEventEmojisChange = onShowEventEmojisChange,
                    quickAddEnabled = quickAddEnabled,
                    onQuickAddEnabledChange = onQuickAddEnabledChange,
                    timeFormat = timeFormat,
                    onTimeFormatChange = onTimeFormatChange,
                    firstDayOfWeek = firstDayOfWeek,
                    onFirstDayOfWeekChange = onFirstDayOfWeekChange,
                    showWeekNumbers = showWeekNumbers,
                    onShowWeekNumbersChange = onShowWeekNumbersChange,
                    widgetMaxEventsPerDay = widgetMaxEventsPerDay,
                    onWidgetMaxEventsPerDayChange = onWidgetMaxEventsPerDayChange,
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
                appleId = notConnectedState?.appleId.orEmpty(),
                password = notConnectedState?.password.orEmpty(),
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

        // CalDAV Sign-In Sheet
        if (uiState.showCalDavSignInSheet) {
            CalDavSignInSheet(
                state = uiState.calDavState,
                onServerUrlChange = onCalDavServerUrlChange,
                onDisplayNameChange = onCalDavDisplayNameChange,
                onUsernameChange = onCalDavUsernameChange,
                onPasswordChange = onCalDavPasswordChange,
                onTrustInsecureChange = onCalDavTrustInsecureChange,
                onDiscover = onCalDavDiscover,
                onDismiss = onHideCalDavSignIn
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
    calDavAccounts: List<CalDavAccountUiModel>,
    onNavigateToAccounts: () -> Unit,
    calendars: List<Calendar>,
    calendarGroups: List<CalendarGroup>,
    onToggleCalendar: (Long, Boolean) -> Unit,
    onShowAllCalendars: () -> Unit,
    onHideAllCalendars: () -> Unit,
    syncIntervalMs: Long,
    onSyncIntervalChange: (Long) -> Unit,
    onForceFullSync: () -> Unit,
    syncLookbackDays: Int,
    onSyncLookbackChange: (Int) -> Unit,
    defaultCalendar: DefaultCalendar?,
    writableDeviceCalendarGroups: List<CalendarGroup>,
    onDefaultCalendarSelect: (DefaultCalendar) -> Unit,
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
    onNavigateToBirthdaysAnniversaries: () -> Unit,
    birthdayCount: Int,
    anniversaryCount: Int,
    deviceCalendarsEnabled: Boolean,
    hasReadCalendarPermission: Boolean,
    hasWriteCalendarPermission: Boolean,
    deviceCalendars: List<DeviceCalendar>,
    enabledDeviceCalendarIds: Set<Long>,
    onToggleDeviceCalendars: (Boolean) -> Unit,
    onToggleDeviceCalendar: (Long, Boolean) -> Unit,
    onRequestWriteCalendarPermission: () -> Unit,
    showDeclinedEvents: Boolean,
    onToggleShowDeclinedEvents: (Boolean) -> Unit,
    deviceCalendarRemindersEnabled: Boolean,
    onToggleDeviceCalendarReminders: (Boolean) -> Unit,
    onRefreshDeviceCalendars: () -> Unit,
    showEventEmojis: Boolean,
    onShowEventEmojisChange: (Boolean) -> Unit,
    quickAddEnabled: Boolean,
    onQuickAddEnabledChange: (Boolean) -> Unit,
    timeFormat: String,
    onTimeFormatChange: (String) -> Unit,
    firstDayOfWeek: Int,
    onFirstDayOfWeekChange: (Int) -> Unit,
    showWeekNumbers: Boolean,
    onShowWeekNumbersChange: (Boolean) -> Unit,
    widgetMaxEventsPerDay: Int,
    onWidgetMaxEventsPerDayChange: (Int) -> Unit,
    showAddSubscriptionDialogFromIntent: Boolean,
    prefillSubscriptionUrl: String?,
    onHideAddSubscriptionDialog: () -> Unit,
    versionName: String
) {
    val scrollState = rememberScrollState()

    // Sheet states
    var showDefaultCalendarSheet by remember { mutableStateOf(false) }
    var showAlertsSheet by remember { mutableStateOf(false) }
    var showEventEmojisSheet by remember { mutableStateOf(false) }
    var showTimeFormatSheet by remember { mutableStateOf(false) }
    var showFirstDayOfWeekSheet by remember { mutableStateOf(false) }
    var showEventDurationSheet by remember { mutableStateOf(false) }
    var showWidgetEventLimitSheet by remember { mutableStateOf(false) }
    var showDebugMenu by remember { mutableStateOf(false) }
    var showAppInfoSheet by remember { mutableStateOf(false) }
    var showAddSubscriptionDialog by remember { mutableStateOf(false) }
    var showDeviceCalendarsSheet by remember { mutableStateOf(false) }
    var showSyncLookbackSheet by remember { mutableStateOf(false) }

    val defaultCalendarSheetState = rememberModalBottomSheetState()
    val alertsSheetState = rememberModalBottomSheetState()
    val eventEmojisSheetState = rememberModalBottomSheetState()
    val timeFormatSheetState = rememberModalBottomSheetState()
    val firstDayOfWeekSheetState = rememberModalBottomSheetState()
    val eventDurationSheetState = rememberModalBottomSheetState()
    val widgetEventLimitSheetState = rememberModalBottomSheetState()
    val syncLookbackSheetState = rememberModalBottomSheetState()
    val debugSheetState = rememberModalBottomSheetState()

    // Derived values
    val isConnected = iCloudState is ICloudConnectionState.Connected
    val context = LocalContext.current
    val use24Hour = DateTimeUtils.isUse24Hour(timeFormat, DateFormat.is24HourFormat(context))

    // Memoized: resolve default calendar name (supports both Room and Device)
    val defaultCalendarName = remember(calendars, deviceCalendars, defaultCalendar) {
        when (defaultCalendar) {
            is DefaultCalendar.Room ->
                calendars.find { it.id == defaultCalendar.calendarId }?.displayName
            is DefaultCalendar.Device ->
                deviceCalendars.find { it.id == defaultCalendar.calendarId }?.displayName
            null -> null
        }
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
        // ==================== MY CALENDARS Section ====================
        SectionHeader(stringResource(R.string.settings_section_my_calendars))
        SettingsCard {
            // Accounts row
            val accountCount = (if (isConnected) 1 else 0) + calDavAccounts.size

            SettingsRow(
                icon = Icons.Default.Person,
                label = stringResource(R.string.accounts_row_label),
                subtitle = if (accountCount == 0) null
                    else pluralStringResource(R.plurals.accounts_count, accountCount, accountCount),
                onClick = onNavigateToAccounts
            )

            // Birthdays & Anniversaries row
            val baSubtitle = buildList {
                if (birthdayCount > 0) add(pluralStringResource(R.plurals.birthday_count, birthdayCount, birthdayCount))
                if (anniversaryCount > 0) add(pluralStringResource(R.plurals.anniversary_count, anniversaryCount, anniversaryCount))
            }.joinToString(", ").ifEmpty { null }

            SettingsRow(
                icon = Icons.Default.Cake,
                label = stringResource(R.string.birthdays_anniversaries_row_label),
                subtitle = baSubtitle,
                onClick = onNavigateToBirthdaysAnniversaries
            )

            // Subscriptions row
            val subscriptionCount = subscriptions.size

            SettingsRow(
                icon = Icons.Default.Link,
                label = stringResource(R.string.subscriptions_row_label),
                subtitle = if (subscriptionCount == 0) null
                    else pluralStringResource(R.plurals.subscriptions_count, subscriptionCount, subscriptionCount),
                onClick = onNavigateToSubscriptions
            )

            // Device Calendars row
            SettingsRow(
                icon = Icons.Default.CalendarMonth,
                label = stringResource(R.string.settings_device_calendars),
                subtitle = if (deviceCalendarsEnabled) stringResource(R.string.settings_device_calendars_enabled, enabledDeviceCalendarIds.size) else null,
                onClick = { showDeviceCalendarsSheet = true },
                showDivider = false
            )
        }

        // ==================== DISPLAY Section ====================
        SectionHeader(stringResource(R.string.settings_section_display))
        SettingsCard {
            if (calendars.isNotEmpty()) {
                // Default Calendar Row
                SettingsRow(
                    icon = Icons.Default.Star,
                    label = stringResource(R.string.settings_default_calendar),
                    subtitle = defaultCalendarName ?: stringResource(R.string.settings_not_set),
                    onClick = { showDefaultCalendarSheet = true }
                )
            }
            SettingsRow(
                icon = Icons.Default.Edit,
                label = stringResource(R.string.settings_quick_event_add),
                subtitle = if (quickAddEnabled) stringResource(R.string.settings_on) else stringResource(R.string.settings_off),
                badge = { NewBadge() },
                onClick = { onQuickAddEnabledChange(!quickAddEnabled) }
            )
            SettingsRow(
                icon = Icons.Default.SentimentSatisfied,
                label = stringResource(R.string.settings_event_emojis),
                subtitle = if (showEventEmojis) stringResource(R.string.settings_on) else stringResource(R.string.settings_off),
                onClick = { showEventEmojisSheet = true }
            )
            SettingsRow(
                icon = Icons.Filled.Tune,
                label = stringResource(R.string.settings_time_format),
                subtitle = when (timeFormat) {
                    KashCalDataStore.TIME_FORMAT_12H -> stringResource(R.string.option_12_hour)
                    KashCalDataStore.TIME_FORMAT_24H -> stringResource(R.string.option_24_hour)
                    else -> stringResource(R.string.option_system_default)
                },
                onClick = { showTimeFormatSheet = true }
            )
            SettingsRow(
                icon = Icons.Default.ViewWeek,
                label = stringResource(R.string.settings_start_week_on),
                subtitle = when (firstDayOfWeek) {
                    java.util.Calendar.SUNDAY -> stringResource(R.string.option_sunday)
                    java.util.Calendar.MONDAY -> stringResource(R.string.option_monday)
                    java.util.Calendar.SATURDAY -> stringResource(R.string.option_saturday)
                    else -> stringResource(R.string.option_system_default)
                },
                onClick = { showFirstDayOfWeekSheet = true }
            )
            SettingsRow(
                icon = Icons.Default.DateRange,
                label = stringResource(R.string.settings_week_numbers),
                subtitle = if (showWeekNumbers) stringResource(R.string.settings_on) else stringResource(R.string.settings_off),
                onClick = { onShowWeekNumbersChange(!showWeekNumbers) }
            )
            SettingsRow(
                icon = Icons.Default.Schedule,
                label = stringResource(R.string.settings_default_event_length),
                subtitle = formatDuration(defaultEventDuration, context.resources),
                onClick = { showEventDurationSheet = true }
            )
            SettingsRow(
                icon = Icons.Default.CalendarMonth,
                label = stringResource(R.string.settings_widget_event_limit),
                subtitle = stringResource(R.string.settings_per_day, widgetMaxEventsPerDay),
                onClick = { showWidgetEventLimitSheet = true },
                showDivider = false  // Last item in card
            )
        }

        // ==================== NOTIFICATIONS Section ====================
        SectionHeader(stringResource(R.string.settings_section_notifications))
        SettingsCard {
            // Default Alerts Row (only if calendars exist)
            if (calendars.isNotEmpty()) {
                SettingsRow(
                    icon = Icons.Default.Notifications,
                    label = stringResource(R.string.settings_default_alerts),
                    subtitle = "${formatReminderShort(defaultReminderTimed, use24Hour, LocalContext.current.resources)} · ${formatReminderShort(defaultReminderAllDay, use24Hour, LocalContext.current.resources)}",
                    onClick = { showAlertsSheet = true }
                )
            }

            // Notifications Row
            SettingsRow(
                icon = if (notificationsEnabled) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                label = stringResource(R.string.settings_notifications),
                subtitle = if (notificationsEnabled) stringResource(R.string.cd_enabled) else stringResource(R.string.settings_tap_to_enable),
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
                            contentDescription = stringResource(R.string.cd_enabled),
                            tint = AccentColors.Green,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else null,
                showDivider = false  // Last item in card
            )
        }

        // ==================== SYNC Section ====================
        SectionHeader(stringResource(R.string.settings_section_sync))
        SettingsCard {
            SettingsRow(
                icon = Icons.Default.Refresh,
                label = stringResource(R.string.settings_sync_lookback),
                subtitle = formatSyncLookback(syncLookbackDays, context.resources),
                onClick = { showSyncLookbackSheet = true },
                showDivider = false  // Only item in card
            )
        }

        // ==================== DATA Section ====================
        SectionHeader(stringResource(R.string.settings_section_data))
        SettingsCard {
            // Import from File Row
            SettingsRow(
                icon = Icons.Default.FileDownload,
                label = stringResource(R.string.action_import_from_file),
                subtitle = stringResource(R.string.settings_import_subtitle),
                onClick = onImportCalendarFile,
                showDivider = localCalendar != null  // Show divider if Export follows
            )

            // Export Local Calendar Row (conditional)
            localCalendar?.let { local ->
                SettingsRow(
                    icon = Icons.Default.FileUpload,
                    label = stringResource(R.string.action_export_local_calendar),
                    subtitle = stringResource(R.string.settings_export_subtitle),
                    onClick = { onExportCalendar(local.id) },
                    showDivider = false  // Last item in card
                )
            }
        }

        // ==================== Version Footer ====================
        if (versionName.isNotEmpty()) {
            VersionFooter(
                versionName = versionName,
                onClick = { showAppInfoSheet = true },
                onLongPress = { showDebugMenu = true }
            )
        }
    }

    // ==================== Bottom Sheets ====================

    // Device Calendars Sheet
    if (showDeviceCalendarsSheet) {
        DeviceCalendarsSheet(
            isEnabled = deviceCalendarsEnabled,
            hasReadPermission = hasReadCalendarPermission,
            hasWritePermission = hasWriteCalendarPermission,
            deviceCalendars = deviceCalendars,
            enabledCalendarIds = enabledDeviceCalendarIds,
            showDeclinedEvents = showDeclinedEvents,
            deviceCalendarRemindersEnabled = deviceCalendarRemindersEnabled,
            onDismiss = { showDeviceCalendarsSheet = false },
            onToggle = onToggleDeviceCalendars,
            onToggleCalendar = onToggleDeviceCalendar,
            onToggleShowDeclined = onToggleShowDeclinedEvents,
            onToggleDeviceCalendarReminders = onToggleDeviceCalendarReminders,
            onRequestWritePermission = onRequestWriteCalendarPermission,
            onRefresh = onRefreshDeviceCalendars
        )
    }

    // Default Calendar Sheet (exclude read-only calendars like ICS subscriptions)
    if (showDefaultCalendarSheet) {
        // Filter out read-only calendars from groups
        val writableGroups = remember(calendarGroups) {
            calendarGroups.mapNotNull { group: CalendarGroup ->
                val writableCals = group.calendars.filter { cal -> !cal.isReadOnly }
                if (writableCals.isNotEmpty()) {
                    group.copy(calendars = writableCals)
                } else null
            }
        }
        DefaultCalendarSheet(
            sheetState = defaultCalendarSheetState,
            calendarGroups = writableGroups,
            deviceCalendarGroups = writableDeviceCalendarGroups,
            currentDefault = defaultCalendar,
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
            use24Hour = use24Hour,
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

    // Time Format Sheet
    if (showTimeFormatSheet) {
        TimeFormatSheet(
            sheetState = timeFormatSheetState,
            currentFormat = timeFormat,
            onFormatSelect = onTimeFormatChange,
            onDismiss = { showTimeFormatSheet = false }
        )
    }

    // First Day of Week Sheet
    if (showFirstDayOfWeekSheet) {
        FirstDayOfWeekSheet(
            sheetState = firstDayOfWeekSheetState,
            currentValue = firstDayOfWeek,
            onSelect = onFirstDayOfWeekChange,
            onDismiss = { showFirstDayOfWeekSheet = false }
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

    // Widget Event Limit Sheet
    if (showWidgetEventLimitSheet) {
        WidgetEventLimitSheet(
            sheetState = widgetEventLimitSheetState,
            currentLimit = widgetMaxEventsPerDay,
            onLimitChange = onWidgetMaxEventsPerDayChange,
            onDismiss = { showWidgetEventLimitSheet = false }
        )
    }

    // Sync Lookback Sheet
    if (showSyncLookbackSheet) {
        SyncLookbackSheet(
            sheetState = syncLookbackSheetState,
            currentDays = syncLookbackDays,
            onSelect = onSyncLookbackChange,
            onDismiss = { showSyncLookbackSheet = false }
        )
    }

    // App Info Sheet (tap on version footer)
    if (showAppInfoSheet) {
        AppInfoSheet(onDismiss = { showAppInfoSheet = false })
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
}
