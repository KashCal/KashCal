package org.onekash.kashcal.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.onekash.kashcal.R
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.ui.components.AppInfoSheet
import org.onekash.kashcal.ui.components.CalDavSignInSheet
import org.onekash.kashcal.ui.components.ICloudSignInSheet
import org.onekash.kashcal.ui.components.SettingsTopAppBar
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.model.localizedDisplayName
import org.onekash.kashcal.ui.screens.settings.AccountDetailDiscoverStatus
import org.onekash.kashcal.ui.screens.settings.ContactSyncConfirmation
import org.onekash.kashcal.ui.screens.settings.AccountDetailSyncStatus
import org.onekash.kashcal.ui.screens.settings.AccountDetailUiModel
import org.onekash.kashcal.ui.permission.LocalNetworkPermissionState
import org.onekash.kashcal.ui.screens.settings.AddSubscriptionDialog
import org.onekash.kashcal.ui.screens.settings.AlertPickerSheet
import org.onekash.kashcal.ui.screens.settings.CalDavAccountUiModel
import org.onekash.kashcal.ui.screens.settings.CalDavConnectionState
import org.onekash.kashcal.ui.screens.settings.DebugMenuSheet
import org.onekash.kashcal.ui.screens.settings.DefaultCalendarSheet
import org.onekash.kashcal.ui.screens.settings.EventDurationSheet
import org.onekash.kashcal.ui.screens.settings.FirstDayOfWeekSheet
import org.onekash.kashcal.ui.screens.settings.ICloudConnectionState
import org.onekash.kashcal.ui.screens.settings.IcsSubscriptionUiModel
import org.onekash.kashcal.ui.screens.settings.SearchEmissionTracker
import org.onekash.kashcal.ui.screens.settings.SearchEmptyState
import org.onekash.kashcal.ui.screens.settings.SearchableSection
import org.onekash.kashcal.ui.screens.settings.SettingsRow
import org.onekash.kashcal.ui.screens.settings.SettingsRowInfo
import org.onekash.kashcal.ui.screens.settings.SettingsToggleRow
import org.onekash.kashcal.ui.screens.settings.SyncFrequencySheet
import org.onekash.kashcal.ui.screens.settings.SyncLookbackSheet
import org.onekash.kashcal.ui.screens.settings.TimeFormatSheet
import org.onekash.kashcal.ui.screens.settings.VersionFooter
import org.onekash.kashcal.ui.screens.settings.WidgetEventLimitSheet
import org.onekash.kashcal.ui.shared.formatReminderMedium
import org.onekash.kashcal.ui.shared.getAllDayReminderOptions
import org.onekash.kashcal.ui.shared.getTimedReminderOptions
import org.onekash.kashcal.ui.shared.formatSyncLookback
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
    /** Action label for the pending snackbar (null = no action button shown) */
    val pendingSnackbarActionLabel: String? = null,
    /** Action callback invoked when the user taps the snackbar action */
    val pendingSnackbarAction: (() -> Unit)? = null,
    /**
     * ID of the ICS subscription currently in the undo window.
     * Set by [AccountSettingsViewModel.onDeleteSubscription], cleared by
     * undo or settle. Filtered out of [AccountSettingsViewModel.subscriptions]
     * so the row hides immediately while the snackbar is displayed.
     */
    val pendingSubscriptionDeletionId: Long? = null,
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
    val accountDetailDiscoverStatus: AccountDetailDiscoverStatus = AccountDetailDiscoverStatus.Idle,
    /**
     * Short-lived inline confirmation shown inside the account detail sheet after
     * toggling contact sync (e.g. "Syncing contacts for j***@icloud.com"), carrying
     * the tone that should style it so a destructive outcome doesn't read as
     * celebratory. Cleared by [AccountSettingsViewModel.clearContactSyncConfirmation].
     */
    val contactSyncConfirmation: ContactSyncConfirmation? = null
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
    // Android 17+ local-network permission plumbing for the add-subscription dialog
    localNetworkPermissionState: LocalNetworkPermissionState =
        LocalNetworkPermissionState.NotRequired,
    onRequestLocalNetwork: () -> Unit = {},
    onSubscriptionDialogOpened: () -> Unit = {},
    // Sync Logs
    onShowSyncLogs: () -> Unit = {},
    // Default reminder preferences
    defaultReminderTimed: Int = 15,
    defaultReminderAllDay: Int = 900, // 9 AM the day before (-PT15H)
    defaultEventDuration: Int = 30,
    onDefaultReminderTimedChange: (Int) -> Unit = {},
    onDefaultReminderAllDayChange: (Int) -> Unit = {},
    onDefaultEventDurationChange: (Int) -> Unit = {},
    // ICS Import/Export
    onImportCalendarFile: () -> Unit = {},
    onExportCalendar: (Long) -> Unit = {},
    // Settings backup/restore
    onBackupSettings: () -> Unit = {},
    onRestoreSettings: () -> Unit = {},
    // Navigation to detail screens
    onNavigateToAccounts: () -> Unit = {},
    onNavigateToSubscriptions: () -> Unit = {},
    onNavigateToBirthdaysAnniversaries: () -> Unit = {},
    onNavigateToDeviceCalendars: () -> Unit = {},
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
    deviceCalendarRemindersEnabled: Boolean = true,
    onToggleDeviceCalendarReminders: (Boolean) -> Unit = {},
    onRefreshDeviceCalendars: () -> Unit = {},
    // Display settings
    showEventEmojis: Boolean = true,
    onShowEventEmojisChange: (Boolean) -> Unit = {},
    quickAddEnabled: Boolean = false,
    onQuickAddEnabledChange: (Boolean) -> Unit = {},
    titleSuggestionsEnabled: Boolean = true,
    onTitleSuggestionsEnabledChange: (Boolean) -> Unit = {},
    timeFormat: String = KashCalDataStore.TIME_FORMAT_SYSTEM,
    onTimeFormatChange: (String) -> Unit = {},
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY,
    onFirstDayOfWeekChange: (Int) -> Unit = {},
    showWeekNumbers: Boolean = false,
    onShowWeekNumbersChange: (Boolean) -> Unit = {},
    widgetMaxEventsPerDay: Int = 5,
    onWidgetMaxEventsPerDayChange: (Int) -> Unit = {},
    widgetDetailedRows: Boolean = false,
    onWidgetDetailedRowsChange: (Boolean) -> Unit = {},
    monthWidgetEventTitles: Boolean = false,
    onMonthWidgetEventTitlesChange: (Boolean) -> Unit = {},
    // Version footer (Checkpoint 9)
    versionName: String = "",
    // Settings search
    isSearchActive: Boolean = false,
    searchQuery: String = "",
    onSearchOpen: () -> Unit = {},
    onSearchClose: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
) {
    // Two-stage back: first clears the query, second closes the bar.
    // Note: while the search field has focus and the IME is open, Android
    // first hides the IME on system-back without firing the BackHandler.
    // The IME's "Search" key (ImeAction.Search wired in SettingsTopAppBar)
    // gives the user an explicit IME-dismiss path so they don't have to
    // exhaust a "phantom" back press to reach the two-stage flow.
    androidx.activity.compose.BackHandler(enabled = isSearchActive) {
        if (searchQuery.isNotEmpty()) {
            onSearchQueryChange("")
        } else {
            onSearchClose()
        }
    }

    Scaffold(
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.settings_title),
                onNavigateBack = onNavigateBack,
                isSearchActive = isSearchActive,
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                onSearchClose = onSearchClose,
                actions = {
                    if (!isSearchActive) {
                        IconButton(onClick = onSearchOpen) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.cd_search_settings),
                            )
                        }
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
                val scrollState = rememberScrollState()

                // Sheet states
                var showDefaultCalendarSheet by remember { mutableStateOf(false) }
                var showTimedAlertSheet by remember { mutableStateOf(false) }
                var showAllDayAlertSheet by remember { mutableStateOf(false) }
                var showTimeFormatSheet by remember { mutableStateOf(false) }
                var showFirstDayOfWeekSheet by remember { mutableStateOf(false) }
                var showEventDurationSheet by remember { mutableStateOf(false) }
                var showWidgetEventLimitSheet by remember { mutableStateOf(false) }
                var showDebugMenu by remember { mutableStateOf(false) }
                var showAppInfoSheet by remember { mutableStateOf(false) }
                var showAddSubscriptionDialog by remember { mutableStateOf(false) }
                var showSyncLookbackSheet by remember { mutableStateOf(false) }
                var showSyncFrequencySheet by remember { mutableStateOf(false) }

                val defaultCalendarSheetState = rememberModalBottomSheetState()
                // Alert sheets can swap to the scrollable wheel picker; skip the half-height
                // partially-expanded state so the wheel sits in a stable full-height sheet.
                val timedAlertSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                val allDayAlertSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                val timeFormatSheetState = rememberModalBottomSheetState()
                val firstDayOfWeekSheetState = rememberModalBottomSheetState()
                val eventDurationSheetState = rememberModalBottomSheetState()
                val widgetEventLimitSheetState = rememberModalBottomSheetState()
                val syncLookbackSheetState = rememberModalBottomSheetState()
                val syncFrequencySheetState = rememberModalBottomSheetState()
                val debugSheetState = rememberModalBottomSheetState()

                // Derived values
                val isConnected = uiState.iCloudState is ICloudConnectionState.Connected
                val context = LocalContext.current
                val resources = LocalResources.current

                val use24Hour = DateTimeUtils.isUse24Hour(timeFormat, DateFormat.is24HourFormat(context))

                // Memoized: resolve default calendar name (supports both Room and Device)
                val defaultCalendarName = remember(calendars, deviceCalendars, defaultCalendar) {
                    when (defaultCalendar) {
                        is DefaultCalendar.Room ->
                            calendars.find { it.id == defaultCalendar.calendarId }?.localizedDisplayName(resources)
                        is DefaultCalendar.Device ->
                            deviceCalendars.find { it.id == defaultCalendar.calendarId }?.displayName
                        null -> null
                    }
                }

                // Memoized: find local calendar for export
                val localCalendar = remember(calendars) {
                    calendars.find { it.caldavUrl == org.onekash.kashcal.domain.initializer.LocalCalendarInitializer.LOCAL_CALENDAR_URL }
                }

                // Add Subscription Dialog - show if local trigger OR intent trigger
                if (showAddSubscriptionDialog || uiState.showAddSubscriptionDialog) {
                    AddSubscriptionDialog(
                        initialUrl = uiState.prefillSubscriptionUrl,
                        onDismiss = {
                            showAddSubscriptionDialog = false
                            onHideAddSubscriptionDialog()
                        },
                        onAdd = { url, name, color ->
                            onAddSubscription(url, name, color)
                            showAddSubscriptionDialog = false
                            onHideAddSubscriptionDialog()
                        },
                        localNetworkPermissionState = localNetworkPermissionState,
                        onRequestLocalNetwork = onRequestLocalNetwork,
                        onDialogOpened = onSubscriptionDialogOpened,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    // Track whether any SearchableSection emitted UI; if every section
                    // collapses, render the empty-state composable. Driven by each
                    // section's onEmitted callback so the value is correct regardless
                    // of compose ordering — no imperative-var fragility.
                    val emittedTracker = remember { SearchEmissionTracker() }
                    emittedTracker.reset()

                    // ==================== CALENDARS Section ====================
                    SearchableSection(
                        query = searchQuery,
                        header = stringResource(R.string.settings_section_calendars),
                        tracker = emittedTracker,
                    ) {
                        val accountCount = (if (isConnected) 1 else 0) + uiState.calDavAccounts.size
                        val accountsSubtitle = if (accountCount == 0) stringResource(R.string.accounts_row_hint)
                            else pluralStringResource(R.plurals.accounts_count, accountCount, accountCount)
                        row(label = stringResource(R.string.accounts_row_label), subtitle = accountsSubtitle, id = "accounts") {
                            SettingsRow(
                                icon = Icons.Default.Person,
                                label = stringResource(R.string.accounts_row_label),
                                subtitle = accountsSubtitle,
                                onClick = onNavigateToAccounts,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }

                        val baSubtitle = buildList {
                            if (birthdayCount > 0) add(pluralStringResource(R.plurals.birthday_count, birthdayCount, birthdayCount))
                            if (anniversaryCount > 0) add(pluralStringResource(R.plurals.anniversary_count, anniversaryCount, anniversaryCount))
                        }.joinToString(", ").ifEmpty { stringResource(R.string.birthdays_anniversaries_row_hint) }
                        row(label = stringResource(R.string.birthdays_anniversaries_row_label), subtitle = baSubtitle, id = "birthdays") {
                            SettingsRow(
                                icon = Icons.Default.Cake,
                                label = stringResource(R.string.birthdays_anniversaries_row_label),
                                subtitle = baSubtitle,
                                onClick = onNavigateToBirthdaysAnniversaries,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }

                        val subscriptionCount = subscriptions.size
                        val subscriptionsSubtitle = if (subscriptionCount == 0) stringResource(R.string.subscriptions_row_hint)
                            else pluralStringResource(R.plurals.subscriptions_count, subscriptionCount, subscriptionCount)
                        row(label = stringResource(R.string.subscriptions_row_label), subtitle = subscriptionsSubtitle, id = "subscriptions") {
                            SettingsRow(
                                icon = Icons.Default.Link,
                                label = stringResource(R.string.subscriptions_row_label),
                                subtitle = subscriptionsSubtitle,
                                onClick = onNavigateToSubscriptions,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }

                        val deviceCalendarsSubtitle = if (deviceCalendarsEnabled) stringResource(R.string.settings_device_calendars_enabled, enabledDeviceCalendarIds.size) else stringResource(R.string.settings_device_calendars_hint)
                        row(label = stringResource(R.string.settings_device_calendars), subtitle = deviceCalendarsSubtitle, id = "device-calendars") {
                            SettingsRow(
                                icon = Icons.Default.CalendarMonth,
                                label = stringResource(R.string.settings_device_calendars),
                                subtitle = deviceCalendarsSubtitle,
                                onClick = onNavigateToDeviceCalendars,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }
                    }

                    // ==================== APPEARANCE Section ====================
                    SearchableSection(
                        query = searchQuery,
                        header = stringResource(R.string.settings_section_appearance),
                        tracker = emittedTracker,
                    ) {
                        // Tap-to-open picker rows first, then the inline toggles (a group's
                        // switches read cleanest clustered at the end).
                        val timeFormatSubtitle = when (timeFormat) {
                            KashCalDataStore.TIME_FORMAT_12H -> stringResource(R.string.option_12_hour)
                            KashCalDataStore.TIME_FORMAT_24H -> stringResource(R.string.option_24_hour)
                            else -> stringResource(R.string.option_system_default)
                        }
                        row(label = stringResource(R.string.settings_time_format), subtitle = timeFormatSubtitle, id = "time-format") {
                            SettingsRow(
                                icon = Icons.Filled.Tune,
                                label = stringResource(R.string.settings_time_format),
                                value = timeFormatSubtitle,
                                onClick = { showTimeFormatSheet = true },
                                showChevron = false,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }

                        val firstDaySubtitle = when (firstDayOfWeek) {
                            java.util.Calendar.SUNDAY -> stringResource(R.string.option_sunday)
                            java.util.Calendar.MONDAY -> stringResource(R.string.option_monday)
                            java.util.Calendar.SATURDAY -> stringResource(R.string.option_saturday)
                            else -> stringResource(R.string.option_system_default)
                        }
                        row(label = stringResource(R.string.settings_start_week_on), subtitle = firstDaySubtitle, id = "first-day") {
                            SettingsRow(
                                icon = Icons.Default.ViewWeek,
                                label = stringResource(R.string.settings_start_week_on),
                                value = firstDaySubtitle,
                                onClick = { showFirstDayOfWeekSheet = true },
                                showChevron = false,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }

                        val widgetLimitValue = stringResource(R.string.settings_per_day, widgetMaxEventsPerDay)
                        row(label = stringResource(R.string.settings_widget_event_limit), subtitle = widgetLimitValue, id = "widget-limit") {
                            SettingsRow(
                                icon = Icons.Default.Widgets,
                                label = stringResource(R.string.settings_widget_event_limit),
                                value = widgetLimitValue,
                                onClick = { showWidgetEventLimitSheet = true },
                                showChevron = false,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }

                        val detailedRowsInfo = SettingsRowInfo(
                            title = stringResource(R.string.settings_detailed_widget_rows),
                            text = stringResource(R.string.settings_detailed_widget_rows_info)
                        )
                        row(label = stringResource(R.string.settings_detailed_widget_rows), id = "widget-detailed-rows") {
                            SettingsToggleRow(
                                icon = Icons.Default.Widgets,
                                label = stringResource(R.string.settings_detailed_widget_rows),
                                checked = widgetDetailedRows,
                                onCheckedChange = onWidgetDetailedRowsChange,
                                info = detailedRowsInfo,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }

                        val monthEventTitlesInfo = SettingsRowInfo(
                            title = stringResource(R.string.settings_month_widget_event_titles),
                            text = stringResource(R.string.settings_month_widget_event_titles_info)
                        )
                        row(label = stringResource(R.string.settings_month_widget_event_titles), id = "month-widget-event-titles") {
                            SettingsToggleRow(
                                icon = Icons.Default.CalendarMonth,
                                label = stringResource(R.string.settings_month_widget_event_titles),
                                checked = monthWidgetEventTitles,
                                onCheckedChange = onMonthWidgetEventTitlesChange,
                                info = monthEventTitlesInfo,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }

                        row(label = stringResource(R.string.settings_week_numbers), id = "week-numbers") {
                            SettingsToggleRow(
                                icon = Icons.Default.DateRange,
                                label = stringResource(R.string.settings_week_numbers),
                                checked = showWeekNumbers,
                                onCheckedChange = onShowWeekNumbersChange,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }

                        row(label = stringResource(R.string.settings_show_declined), id = "show-declined") {
                            SettingsToggleRow(
                                icon = Icons.Default.EventBusy,
                                label = stringResource(R.string.settings_show_declined),
                                checked = showDeclinedEvents,
                                onCheckedChange = onToggleShowDeclinedEvents,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }

                        val emojisInfo = SettingsRowInfo(
                            title = stringResource(R.string.settings_event_emojis),
                            text = stringResource(R.string.settings_event_emojis_info)
                        )
                        row(label = stringResource(R.string.settings_event_emojis), id = "emojis") {
                            SettingsToggleRow(
                                icon = Icons.Default.SentimentSatisfied,
                                label = stringResource(R.string.settings_event_emojis),
                                checked = showEventEmojis,
                                onCheckedChange = onShowEventEmojisChange,
                                info = emojisInfo,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }
                    }

                    // ==================== EVENT PREFERENCES Section ====================
                    // Default calendar and Default alerts require a calendar to target;
                    // Default length doesn't.
                    SearchableSection(
                        query = searchQuery,
                        header = stringResource(R.string.settings_section_creating_events),
                        tracker = emittedTracker,
                    ) {
                        if (calendars.isNotEmpty()) {
                            val defaultCalendarValue = defaultCalendarName ?: stringResource(R.string.settings_not_set)
                            row(label = stringResource(R.string.settings_default_calendar), subtitle = defaultCalendarValue, id = "default-calendar") {
                                SettingsRow(
                                    icon = Icons.Default.Star,
                                    label = stringResource(R.string.settings_default_calendar),
                                    value = defaultCalendarValue,
                                    onClick = { showDefaultCalendarSheet = true },
                                    showChevron = false,
                                    showDivider = false,
                                    searchQuery = searchQuery
                                )
                            }
                        }

                        val durationValue = formatReminderMedium(defaultEventDuration, isAllDay = false, resources = resources)
                        row(label = stringResource(R.string.settings_default_event_length), subtitle = durationValue, id = "default-length") {
                            SettingsRow(
                                icon = Icons.Default.Schedule,
                                label = stringResource(R.string.settings_default_event_length),
                                value = durationValue,
                                onClick = { showEventDurationSheet = true },
                                showChevron = false,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }

                        if (calendars.isNotEmpty()) {
                            val timedAlertValue = formatReminderMedium(defaultReminderTimed, isAllDay = false, resources = resources)
                            row(label = stringResource(R.string.settings_timed_event_alert), subtitle = timedAlertValue, id = "timed-alert") {
                                SettingsRow(
                                    icon = Icons.Default.Notifications,
                                    label = stringResource(R.string.settings_timed_event_alert),
                                    value = timedAlertValue,
                                    onClick = { showTimedAlertSheet = true },
                                    showChevron = false,
                                    showDivider = false,
                                    searchQuery = searchQuery
                                )
                            }

                            val allDayAlertValue = formatReminderMedium(defaultReminderAllDay, isAllDay = true, resources = resources)
                            row(label = stringResource(R.string.settings_all_day_event_alert), subtitle = allDayAlertValue, id = "all-day-alert") {
                                SettingsRow(
                                    icon = Icons.Default.Notifications,
                                    label = stringResource(R.string.settings_all_day_event_alert),
                                    value = allDayAlertValue,
                                    onClick = { showAllDayAlertSheet = true },
                                    showChevron = false,
                                    showDivider = false,
                                    searchQuery = searchQuery
                                )
                            }
                        }

                        val quickAddInfo = SettingsRowInfo(
                            title = stringResource(R.string.settings_quick_event_add),
                            text = stringResource(R.string.settings_quick_event_add_info)
                        )
                        row(label = stringResource(R.string.settings_quick_event_add), id = "quick-add") {
                            SettingsToggleRow(
                                icon = Icons.Default.Edit,
                                label = stringResource(R.string.settings_quick_event_add),
                                checked = quickAddEnabled,
                                onCheckedChange = onQuickAddEnabledChange,
                                info = quickAddInfo,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }

                        val suggestTitlesInfo = SettingsRowInfo(
                            title = stringResource(R.string.settings_suggest_titles),
                            text = stringResource(R.string.settings_suggest_titles_info)
                        )
                        row(label = stringResource(R.string.settings_suggest_titles), id = "suggest-titles") {
                            SettingsToggleRow(
                                icon = Icons.Default.History,
                                label = stringResource(R.string.settings_suggest_titles),
                                checked = titleSuggestionsEnabled,
                                onCheckedChange = onTitleSuggestionsEnabledChange,
                                info = suggestTitlesInfo,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }
                    }

                    // ==================== Sync Section ====================
                    SearchableSection(
                        query = searchQuery,
                        header = stringResource(R.string.settings_section_sync),
                        tracker = emittedTracker,
                    ) {
                        val syncFrequencyValue = DateTimeUtils.formatSyncInterval(syncIntervalMs, resources)
                        row(label = stringResource(R.string.settings_sync_frequency), subtitle = syncFrequencyValue, id = "sync-frequency") {
                            SettingsRow(
                                icon = Icons.Default.Refresh,
                                label = stringResource(R.string.settings_sync_frequency),
                                value = syncFrequencyValue,
                                onClick = { showSyncFrequencySheet = true },
                                showChevron = false,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }

                        val syncLookbackValue = formatSyncLookback(syncLookbackDays, resources)
                        row(label = stringResource(R.string.settings_sync_lookback), subtitle = syncLookbackValue, id = "sync-lookback") {
                            SettingsRow(
                                icon = Icons.Default.History,
                                label = stringResource(R.string.settings_sync_lookback),
                                value = syncLookbackValue,
                                onClick = { showSyncLookbackSheet = true },
                                showChevron = false,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }
                    }

                    // ==================== Backup & Restore Section ====================
                    SearchableSection(
                        query = searchQuery,
                        header = stringResource(R.string.settings_section_data),
                        tracker = emittedTracker,
                    ) {
                        localCalendar?.let { local ->
                            row(label = stringResource(R.string.action_export_local_calendar), subtitle = stringResource(R.string.settings_export_subtitle), id = "export") {
                                SettingsRow(
                                    icon = Icons.Default.FileUpload,
                                    label = stringResource(R.string.action_export_local_calendar),
                                    subtitle = stringResource(R.string.settings_export_subtitle),
                                    onClick = { onExportCalendar(local.id) },
                                    showChevron = false,
                                    showDivider = false,
                                    searchQuery = searchQuery
                                )
                            }
                        }
                        row(label = stringResource(R.string.backup_settings_label), subtitle = stringResource(R.string.backup_settings_subtitle), id = "backup") {
                            SettingsRow(
                                icon = Icons.Default.Backup,
                                label = stringResource(R.string.backup_settings_label),
                                subtitle = stringResource(R.string.backup_settings_subtitle),
                                onClick = onBackupSettings,
                                showChevron = false,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }
                        row(label = stringResource(R.string.action_import_from_file), subtitle = stringResource(R.string.settings_import_subtitle), id = "import") {
                            SettingsRow(
                                icon = Icons.Default.FileDownload,
                                label = stringResource(R.string.action_import_from_file),
                                subtitle = stringResource(R.string.settings_import_subtitle),
                                onClick = onImportCalendarFile,
                                showChevron = false,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }
                        row(label = stringResource(R.string.restore_settings_label), subtitle = stringResource(R.string.restore_settings_subtitle), id = "restore") {
                            SettingsRow(
                                icon = Icons.Default.Restore,
                                label = stringResource(R.string.restore_settings_label),
                                subtitle = stringResource(R.string.restore_settings_subtitle),
                                onClick = onRestoreSettings,
                                showChevron = false,
                                showDivider = false,
                                searchQuery = searchQuery
                            )
                        }
                    }

                    // Empty-state when search is active and no section matched.
                    if (searchQuery.isNotBlank() && !emittedTracker.anyEmitted) {
                        SearchEmptyState(query = searchQuery)
                    }

                    // ==================== Version Footer ====================
                    // Only render when not actively searching.
                    if (versionName.isNotEmpty() && searchQuery.isBlank()) {
                        VersionFooter(
                            versionName = versionName,
                            onClick = { showAppInfoSheet = true },
                            onLongPress = { showDebugMenu = true }
                        )
                    }
                }

                // ==================== Bottom Sheets ====================

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

                // Timed event alert sheet (presets + Custom… wheel)
                if (showTimedAlertSheet) {
                    AlertPickerSheet(
                        sheetState = timedAlertSheetState,
                        title = stringResource(R.string.settings_timed_event_alert),
                        options = getTimedReminderOptions(resources),
                        currentValue = defaultReminderTimed,
                        isAllDay = false,
                        use24Hour = use24Hour,
                        onSelect = onDefaultReminderTimedChange,
                        onDismiss = { showTimedAlertSheet = false }
                    )
                }

                // All-day event alert sheet (presets + Custom… wheel)
                if (showAllDayAlertSheet) {
                    AlertPickerSheet(
                        sheetState = allDayAlertSheetState,
                        title = stringResource(R.string.settings_all_day_event_alert),
                        options = getAllDayReminderOptions(resources),
                        currentValue = defaultReminderAllDay,
                        isAllDay = true,
                        use24Hour = use24Hour,
                        onSelect = onDefaultReminderAllDayChange,
                        onDismiss = { showAllDayAlertSheet = false }
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

                // Sync Frequency Sheet
                if (showSyncFrequencySheet) {
                    SyncFrequencySheet(
                        sheetState = syncFrequencySheetState,
                        currentIntervalMs = syncIntervalMs,
                        onSelect = onSyncIntervalChange,
                        onDismiss = { showSyncFrequencySheet = false }
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
                        onForceFullSync = onForceFullSync,
                        onShowSyncLogs = onShowSyncLogs,
                        onDismiss = { showDebugMenu = false }
                    )
                }
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
