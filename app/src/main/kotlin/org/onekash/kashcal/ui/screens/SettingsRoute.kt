package org.onekash.kashcal.ui.screens

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.onekash.kashcal.BuildConfig
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.ics.IcsParserService
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.domain.backup.BackupFilename
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.ui.components.CalDavSignInSheet
import org.onekash.kashcal.ui.components.ICloudSignInSheet
import org.onekash.kashcal.ui.components.IcsImportSheet
import org.onekash.kashcal.ui.components.SyncHistorySheet
import org.onekash.kashcal.ui.permission.LocalNetworkPermissionState
import org.onekash.kashcal.ui.permission.classifyLocalNetworkAfterRequest
import org.onekash.kashcal.ui.permission.contactSyncPermissionGranted
import org.onekash.kashcal.ui.permission.contactSyncToggleRequiresPermissionRequest
import org.onekash.kashcal.ui.permission.shouldShowLanBanner
import org.onekash.kashcal.ui.screens.settings.AccountConnectedSheet
import org.onekash.kashcal.ui.screens.settings.AccountsScreen
import org.onekash.kashcal.ui.screens.settings.BirthdaysAndAnniversariesScreen
import org.onekash.kashcal.ui.screens.settings.CalDavConnectionState
import org.onekash.kashcal.ui.screens.settings.DeviceCalendarsScreen
import org.onekash.kashcal.ui.screens.settings.ICloudAccountUiModel
import org.onekash.kashcal.ui.screens.settings.ICloudConnectionState
import org.onekash.kashcal.ui.screens.settings.RestoreConfirmationDialog
import org.onekash.kashcal.ui.screens.settings.SettingsDestination
import org.onekash.kashcal.ui.screens.settings.RestoreErrorDialog
import org.onekash.kashcal.ui.screens.settings.RestoreSuccessDialog
import org.onekash.kashcal.ui.screens.settings.SubscriptionsScreen
import org.onekash.kashcal.ui.screens.settings.TagsScreen
import org.onekash.kashcal.ui.theme.ColorSource
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.onekash.kashcal.ui.theme.ThemeMode
import org.onekash.kashcal.ui.viewmodels.AccountSettingsViewModel
import org.onekash.kashcal.ui.viewmodels.TagsViewModel
import org.onekash.kashcal.util.isLanHost
import java.time.Instant
import java.time.ZoneId

private const val TAG = "SettingsRoute"

/**
 * Stateful wrapper for [AccountSettingsScreen]. Owns the [AccountSettingsViewModel]
 * collection, the seven activity-result launchers, and the [KashCalTheme] wrapper —
 * everything that used to live inline in `SettingsActivity.setContent`.
 * [AccountSettingsScreen] itself stays stateless and param-driven so its compose
 * tests drive it directly with no Hilt/Robolectric activity.
 *
 * The view model is passed in (not obtained via `hiltViewModel()` inside the route)
 * because the host activity retains it for its `FragmentActivity`-bound side effects
 * (the biometric app-lock flow) and its post-render intent-extra bootstrap: both must
 * reference the same instance. Passing it in also lets the wiring guard inject a
 * `mockk` of the real type. The three cold-start theme values are read synchronously
 * in the host before render and passed in as seeds so the first frame doesn't flash
 * the default theme.
 *
 * Genuinely host-bound work (needs the `FragmentActivity`, an injected collaborator,
 * or the content resolver) is passed down as narrow lambdas; everything else lives here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    viewModel: AccountSettingsViewModel,
    initialThemeMode: ThemeMode,
    initialColorSource: ColorSource,
    initialAccentSeed: Int,
    syncSessionStore: SyncSessionStore,
    // When true the host launched us straight into tag management (from the account
    // hub). We open on the Tags screen, and backing out of it finishes the activity
    // to the hub rather than revealing the Settings root the user never chose.
    openTagsInitially: Boolean = false,
    onFinish: () -> Unit = {},
    onExportCalendar: (Long) -> Unit = {},
    // The four content-resolver I/O lambdas are required, not defaulted: a no-op
    // default would silently write an empty backup / import zero events while
    // showing a success message. A caller that forgets to wire one must fail to
    // compile, not ship silent data loss. (The UI side-effect lambdas above/below
    // stay defaulted — omitting one yields an inert control, never lost data.)
    readIcsContent: suspend (Uri) -> Result<String>,
    importIcsToRoom: suspend (events: List<Event>, calendarId: Long) -> Int,
    writeBackup: suspend (uri: Uri, json: String) -> Unit,
    readBackup: suspend (uri: Uri) -> String,
    resolveLanPermissionState: () -> LocalNetworkPermissionState = { LocalNetworkPermissionState.NotRequired },
    shouldShowLanRationale: () -> Boolean = { false },
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle(initialValue = initialThemeMode)
    val colorSource by viewModel.colorSource.collectAsStateWithLifecycle(initialValue = initialColorSource)
    val accentSeed by viewModel.accentSeed.collectAsStateWithLifecycle(initialValue = initialAccentSeed)
    KashCalTheme(themeMode = themeMode, colorSource = colorSource, accentSeed = accentSeed) {
        val context = LocalContext.current
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        val calendars by viewModel.calendars.collectAsStateWithLifecycle()
        val calendarGroups by viewModel.calendarGroups.collectAsStateWithLifecycle()
        val defaultCalendar by viewModel.defaultCalendar.collectAsStateWithLifecycle()
        val writableDeviceCalendarGroups by viewModel.writableDeviceCalendarGroups.collectAsStateWithLifecycle()
        val syncIntervalMs by viewModel.syncIntervalMs.collectAsStateWithLifecycle()
        val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
        val subscriptionSyncing by viewModel.subscriptionSyncing.collectAsStateWithLifecycle()
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
        val widgetDetailedRows by viewModel.widgetDetailedRows.collectAsStateWithLifecycle()
        val monthWidgetEventTitles by viewModel.monthWidgetEventTitles.collectAsStateWithLifecycle()
        val syncLookbackDays by viewModel.syncLookbackDays.collectAsStateWithLifecycle()
        val isSearchActive by viewModel.isSearchActive.collectAsStateWithLifecycle()
        val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

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

        // Per-account CardDAV contact-sync toggle. Enabling requires BOTH
        // READ + WRITE_CONTACTS (the sync mirrors server contacts onto the device);
        // a denial surfaces as an inline banner in the sheet rather than a dialog.
        val contactSyncPermissionNeeded by viewModel.contactSyncPermissionNeeded
            .collectAsStateWithLifecycle()
        var pendingContactSyncAccountId by remember { mutableStateOf<Long?>(null) }
        val contactSyncPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            viewModel.refreshContactsPermission()
            val granted = contactSyncPermissionGranted(
                readGranted = permissions[android.Manifest.permission.READ_CONTACTS] == true,
                writeGranted = permissions[android.Manifest.permission.WRITE_CONTACTS] == true,
            )
            val accountId = pendingContactSyncAccountId
            if (granted && accountId != null) {
                viewModel.onToggleContactSync(accountId, true)
            }
            pendingContactSyncAccountId = null
        }
        val onToggleContactSync: (Long, Boolean) -> Unit = { accountId, enabled ->
            // Sync needs READ + WRITE, so gate on hasContactsSyncPermission — not
            // hasContactsPermission, which is READ-only (it gates the birthday
            // reads). Using the read-only signal here would skip the WRITE request
            // for a read-granted/write-denied login, leaving a silent dead toggle.
            if (contactSyncToggleRequiresPermissionRequest(enabled, viewModel.hasContactsSyncPermission.value)) {
                // Defer the enable until the permission returns granted.
                pendingContactSyncAccountId = accountId
                contactSyncPermissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.READ_CONTACTS,
                        android.Manifest.permission.WRITE_CONTACTS,
                    )
                )
            } else {
                viewModel.onToggleContactSync(accountId, enabled)
            }
        }
        val onGrantContactsPermission = {
            contactSyncPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.READ_CONTACTS,
                    android.Manifest.permission.WRITE_CONTACTS,
                )
            )
        }

        // Local-network permission (Android 17+) for LAN CalDAV servers.
        // The host owns the rationale/state reads (they need the activity ref); the
        // resolved state is pushed to the VM so the sign-in sheet can proactively ask.
        // User dismissal of the banner for the current sheet session.
        var localNetworkBannerDismissed by remember { mutableStateOf(false) }
        // Rationale sampled just before launching, so the callback can
        // detect the rationale-flip that signals "don't ask again".
        var localNetworkRationaleBefore by remember { mutableStateOf(false) }
        val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            viewModel.updateLocalNetworkPermissionState(
                classifyLocalNetworkAfterRequest(
                    granted = isGranted,
                    rationaleBefore = localNetworkRationaleBefore,
                    rationaleAfter = shouldShowLanRationale(),
                )
            )
        }
        // Shared local-network wiring reused by the CalDAV sign-in sheet and the
        // ICS add-subscription dialog: one launcher, one on-open state seed.
        val localNetworkPermissionState by viewModel.localNetworkPermissionState
            .collectAsStateWithLifecycle()
        val onRequestLocalNetwork = {
            localNetworkRationaleBefore = shouldShowLanRationale()
            localNetworkPermissionLauncher.launch(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        val onSubscriptionDialogOpened = {
            viewModel.updateLocalNetworkPermissionState(resolveLanPermissionState())
        }

        // Calendar permission launcher (for Device Calendars - READ + WRITE)
        // Requests both permissions upfront so users can create/edit device calendar events
        val calendarPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            viewModel.refreshCalendarPermission()
            // Enable if at least READ was granted (WRITE is optional but preferred)
            val readGranted = permissions[android.Manifest.permission.READ_CALENDAR] == true
            if (readGranted) {
                viewModel.onToggleDeviceCalendars(true)
            }
        }

        // Snackbar state (defined early for use in permission launchers)
        val coroutineScope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }

        // Resolved at composable scope for the launcher/callback coroutines below.
        val calendarPermissionDeniedMessage =
            stringResource(R.string.error_device_calendar_permission_denied)

        // Calendar permission launcher (for Device Calendars - WRITE)
        val writeCalendarPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            viewModel.refreshCalendarPermission()
            if (!isGranted) {
                // Permission denied - show instructions to toggle Calendar permission in Settings
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        message = calendarPermissionDeniedMessage
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
        // Seeded from the launch intent so a hub-initiated open lands on Tags
        // immediately; rememberSaveable then preserves the choice across rotation.
        var showTagsScreen by rememberSaveable { mutableStateOf(openTagsInitially) }
        var showDeviceCalendarsScreen by rememberSaveable { mutableStateOf(false) }

        // ICS import state
        var showIcsImportSheet by remember { mutableStateOf(false) }
        var icsImportEvents by remember { mutableStateOf<List<Event>>(emptyList()) }

        // Subscription snackbar strings (issue #133). Hoisted so both
        // bind sites resolve them the same way and the ViewModel stays
        // Context-free.
        val subscriptionRemovedMessage = stringResource(R.string.snackbar_subscription_removed)
        val subscriptionUndoLabel = stringResource(R.string.snackbar_action_undo)
        val subscriptionAlreadyExistsMessage = stringResource(R.string.snackbar_subscription_already_exists)
        val onDeleteSubscriptionWithUndo: (Long) -> Unit = { id ->
            viewModel.onDeleteSubscription(id, subscriptionRemovedMessage, subscriptionUndoLabel)
        }
        val onAddSubscriptionWithDuplicateGuard: (String, String, Int) -> Unit = { url, name, color ->
            viewModel.onAddSubscription(url, name, color, subscriptionAlreadyExistsMessage)
        }

        // Backup error strings, resolved at composable scope for the launcher/callback coroutines.
        val backupBuildFailedMessage = stringResource(R.string.backup_error_build_failed)
        val backupWriteFailedMessage = stringResource(R.string.backup_error_write_failed)
        val backupReadFailedMessage = stringResource(R.string.backup_error_read_failed)

        // Account connected success sheet state
        val accountConnectedSheetState = rememberModalBottomSheetState()

        // Snackbar action (when present) belongs to the subscription
        // delete-with-undo flow: ActionPerformed → undo, Dismissed → commit.
        LaunchedEffect(uiState.pendingSnackbarMessage, uiState.pendingSnackbarActionLabel) {
            uiState.pendingSnackbarMessage?.let { message ->
                val action = uiState.pendingSnackbarAction
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = uiState.pendingSnackbarActionLabel,
                    duration = SnackbarDuration.Short
                )
                when (result) {
                    SnackbarResult.ActionPerformed -> action?.invoke()
                    SnackbarResult.Dismissed -> viewModel.onSubscriptionDeletionSettled()
                }
                viewModel.clearSnackbar()
            }
        }

        // Auto-finish activity after initial iCloud setup (navigate back to HomeScreen)
        LaunchedEffect(uiState.pendingFinishActivity) {
            if (uiState.pendingFinishActivity) {
                Log.d(TAG, "Auto-navigating back to HomeScreen after iCloud setup")
                onFinish()
            }
        }

        // ICS import snackbar strings, resolved at composable scope for the
        // launcher/callback coroutines below.
        val icsImportNoEventsMessage = stringResource(R.string.error_import_no_events)
        val icsImportInvalidMessage = stringResource(R.string.error_import_invalid_format)
        val icsImportReadFailedMessage = stringResource(R.string.error_import_file_not_found)
        val icsImportFailedMessage = stringResource(R.string.error_import_failed)

        // File picker for ICS import
        val importFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let { selectedUri ->
                coroutineScope.launch {
                    readIcsContent(selectedUri)
                        .onSuccess { content ->
                            try {
                                val events = IcsParserService.parseIcsContent(content, 0, 0)
                                if (events.isNotEmpty()) {
                                    icsImportEvents = events
                                    showIcsImportSheet = true
                                } else {
                                    viewModel.showSnackbar(icsImportNoEventsMessage)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse ICS file", e)
                                viewModel.showSnackbar(icsImportInvalidMessage)
                            }
                        }
                        .onFailure { e ->
                            Log.e(TAG, "Failed to read ICS file", e)
                            viewModel.showSnackbar(icsImportReadFailedMessage)
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
                    writeBackup(uri, json)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write backup file", e)
                    viewModel.showSnackbar(backupWriteFailedMessage)
                }
            }
        }

        val backupImportLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            coroutineScope.launch {
                try {
                    val json = readBackup(uri)
                    viewModel.onBackupFileSelected(json)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read backup file", e)
                    viewModel.showSnackbar(backupReadFailedMessage)
                }
            }
        }

        val backupRestoreState by viewModel.backupRestoreState.collectAsStateWithLifecycle()

        // Backing out of the Tags screen finishes the activity when it was the
        // launch destination (hub-initiated), so we don't reveal the Settings root
        // the user never navigated to. Otherwise it just closes the detail screen.
        val closeTags = {
            if (openTagsInitially) onFinish() else showTagsScreen = false
        }

        BackHandler(
            enabled = showAccountsScreen ||
                showBirthdaysAnniversariesScreen ||
                showSubscriptionsScreen ||
                showTagsScreen ||
                showDeviceCalendarsScreen
        ) {
            if (showTagsScreen) {
                closeTags()
                return@BackHandler
            }
            showAccountsScreen = false
            showBirthdaysAnniversariesScreen = false
            showSubscriptionsScreen = false
            showDeviceCalendarsScreen = false
        }

        // State-based navigation between settings and detail screens, animated as a
        // directional slide: drilling into a detail slides it in from the trailing
        // edge, backing out to the root reverses it.
        val settingsDestination = SettingsDestination.from(
            accounts = showAccountsScreen,
            birthdaysAnniversaries = showBirthdaysAnniversariesScreen,
            subscriptions = showSubscriptionsScreen,
            tags = showTagsScreen,
            deviceCalendars = showDeviceCalendarsScreen,
        )

        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = settingsDestination,
                transitionSpec = {
                    // Start/End (not Left/Right) so the drill-in direction follows
                    // layout direction and reads correctly in RTL locales. All panes
                    // are fillMaxSize, so a null SizeTransform avoids the default
                    // clip/size animation and gives a clean cross-slide.
                    val towards = if (initialState.isForwardTo(targetState)) {
                        AnimatedContentTransitionScope.SlideDirection.Start
                    } else {
                        AnimatedContentTransitionScope.SlideDirection.End
                    }
                    (slideIntoContainer(towards) + fadeIn()) togetherWith
                        (slideOutOfContainer(towards) + fadeOut()) using null
                },
                label = "settingsDestination",
            ) { destination ->
                when (destination) {
                    SettingsDestination.Accounts -> {
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
                            onToggleContactSync = onToggleContactSync,
                            contactSyncPermissionNeeded = contactSyncPermissionNeeded,
                            contactSyncConfirmation = uiState.contactSyncConfirmation,
                            onDismissContactSyncConfirmation = viewModel::clearContactSyncConfirmation,
                            onGrantContactsPermission = onGrantContactsPermission,
                            onRenameAccount = viewModel::renameAccount,
                            onChangeAccountPassword = viewModel::changeAccountPassword,
                            onDiscoverCalendars = viewModel::discoverNewCalendars
                        )
                    }
                    SettingsDestination.BirthdaysAnniversaries -> {
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
                                    contactsPermissionLauncher.launch(android.Manifest.permission.READ_CONTACTS)
                                } else {
                                    viewModel.onToggleContactBirthdays(enabled)
                                }
                            },
                            onBirthdaysColorChange = viewModel::onContactBirthdaysColorChange,
                            onBirthdaysReminderChange = viewModel::onContactBirthdaysReminderChange,
                            onToggleAnniversaries = { enabled ->
                                if (enabled && !hasContactsPermission) {
                                    pendingContactPermissionAction = "anniversaries"
                                    contactsPermissionLauncher.launch(android.Manifest.permission.READ_CONTACTS)
                                } else {
                                    viewModel.onToggleContactAnniversaries(enabled)
                                }
                            },
                            onAnniversariesColorChange = viewModel::onContactAnniversariesColorChange,
                            onAnniversariesReminderChange = viewModel::onContactAnniversariesReminderChange,
                            onNavigateBack = { showBirthdaysAnniversariesScreen = false }
                        )
                    }
                    SettingsDestination.Subscriptions -> {
                        SubscriptionsScreen(
                            subscriptions = subscriptions,
                            onNavigateBack = { showSubscriptionsScreen = false },
                            onAddSubscription = onAddSubscriptionWithDuplicateGuard,
                            onToggleSubscription = viewModel::onToggleSubscription,
                            onDeleteSubscription = onDeleteSubscriptionWithUndo,
                            onRefreshSubscription = viewModel::onRefreshSubscription,
                            onUpdateSubscription = viewModel::onUpdateSubscription,
                            localNetworkPermissionState = localNetworkPermissionState,
                            onRequestLocalNetwork = onRequestLocalNetwork,
                            onSubscriptionDialogOpened = onSubscriptionDialogOpened,
                        )
                    }
                    SettingsDestination.Tags -> {
                        val tagsViewModel: TagsViewModel = hiltViewModel()
                        val tags by tagsViewModel.tags.collectAsStateWithLifecycle()
                        val tagDeleteUndoLabel = stringResource(R.string.tags_delete_undo)
                        // Resources (not LocalContext) so the deleted-message format
                        // reflects a locale change; the tag name is only known at tap.
                        val resources = LocalResources.current
                        TagsScreen(
                            tags = tags,
                            onNavigateBack = closeTags,
                            onSetColor = { name, color -> tagsViewModel.onSetColor(name, color) },
                            onRename = tagsViewModel::onRename,
                            onDelete = { name ->
                                // Optimistic delete with an undo window: the row
                                // disappears immediately (live Room flow) and the
                                // snackbar restores it verbatim if the user undoes.
                                tagsViewModel.onDelete(name)
                                val deletedMessage =
                                    resources.getString(R.string.tags_deleted, name)
                                coroutineScope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = deletedMessage,
                                        actionLabel = tagDeleteUndoLabel,
                                        duration = SnackbarDuration.Short,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        tagsViewModel.onUndoDelete()
                                    }
                                }
                            },
                        )
                    }
                    SettingsDestination.DeviceCalendars -> {
                        DeviceCalendarsScreen(
                            isEnabled = deviceCalendarsEnabled,
                            hasReadPermission = hasReadCalendarPermission,
                            hasWritePermission = hasWriteCalendarPermission,
                            deviceCalendars = deviceCalendars,
                            enabledCalendarIds = enabledDeviceCalendarIds,
                            deviceCalendarRemindersEnabled = deviceCalendarRemindersEnabled,
                            onNavigateBack = { showDeviceCalendarsScreen = false },
                            onToggle = { enabled ->
                                if (enabled && !hasReadCalendarPermission) {
                                    calendarPermissionLauncher.launch(arrayOf(
                                        android.Manifest.permission.READ_CALENDAR,
                                        android.Manifest.permission.WRITE_CALENDAR
                                    ))
                                } else {
                                    viewModel.onToggleDeviceCalendars(enabled)
                                }
                            },
                            onToggleCalendar = viewModel::onToggleDeviceCalendar,
                            onToggleDeviceCalendarReminders = viewModel::onToggleDeviceCalendarReminders,
                            onRequestWritePermission = {
                                writeCalendarPermissionLauncher.launch(android.Manifest.permission.WRITE_CALENDAR)
                            },
                            onRefresh = viewModel::refreshDeviceCalendars
                        )
                    }
                    SettingsDestination.Root -> {
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
                            onNavigateBack = onFinish,
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
                            onAddSubscription = onAddSubscriptionWithDuplicateGuard,
                            onHideAddSubscriptionDialog = viewModel::hideAddSubscriptionDialog,
                            onDeleteSubscription = onDeleteSubscriptionWithUndo,
                            onToggleSubscription = viewModel::onToggleSubscription,
                            onRefreshSubscription = viewModel::onRefreshSubscription,
                            onUpdateSubscription = viewModel::onUpdateSubscription,
                            onSyncAllSubscriptions = viewModel::onSyncAllSubscriptions,
                            // Android 17+ local-network permission for LAN subscription URLs
                            localNetworkPermissionState = localNetworkPermissionState,
                            onRequestLocalNetwork = onRequestLocalNetwork,
                            onSubscriptionDialogOpened = onSubscriptionDialogOpened,
                            // System
                            onShowSyncLogs = { showDebugLogSheet = true },
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
                                                Instant.now(),
                                                ZoneId.systemDefault(),
                                            )
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to build backup JSON", e)
                                        viewModel.showSnackbar(backupBuildFailedMessage)
                                    }
                                }
                            },
                            onRestoreSettings = {
                                backupImportLauncher.launch(arrayOf(BACKUP_MIME_TYPE))
                            },
                            // ICS Export
                            onExportCalendar = onExportCalendar,
                            // Navigate to Subscriptions detail screen
                            onNavigateToSubscriptions = { viewModel.onSearchClose(); showSubscriptionsScreen = true },
                            // Navigate to Birthdays & Anniversaries detail screen
                            onNavigateToBirthdaysAnniversaries = { viewModel.onSearchClose(); showBirthdaysAnniversariesScreen = true },
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
                                        android.Manifest.permission.READ_CALENDAR,
                                        android.Manifest.permission.WRITE_CALENDAR
                                    ))
                                } else {
                                    viewModel.onToggleDeviceCalendars(enabled)
                                }
                            },
                            onToggleDeviceCalendar = viewModel::onToggleDeviceCalendar,
                            onRequestWriteCalendarPermission = {
                                writeCalendarPermissionLauncher.launch(android.Manifest.permission.WRITE_CALENDAR)
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
                            widgetDetailedRows = widgetDetailedRows,
                            onWidgetDetailedRowsChange = viewModel::setWidgetDetailedRows,
                            monthWidgetEventTitles = monthWidgetEventTitles,
                            onMonthWidgetEventTitlesChange = viewModel::setMonthWidgetEventTitles,
                            // Version footer
                            versionName = BuildConfig.VERSION_NAME,
                            // Navigate to Accounts detail screen
                            onNavigateToAccounts = { viewModel.onSearchClose(); showAccountsScreen = true },
                            // Navigate to Device Calendars detail screen
                            onNavigateToDeviceCalendars = { viewModel.onSearchClose(); showDeviceCalendarsScreen = true },
                            // Inline search
                            isSearchActive = isSearchActive,
                            searchQuery = searchQuery,
                            onSearchOpen = viewModel::onSearchOpen,
                            onSearchClose = viewModel::onSearchClose,
                            onSearchQueryChange = viewModel::onSearchQueryChange,
                        )
                    }
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
                                    importIcsToRoom(events, calendarId)
                                }
                                viewModel.showSnackbar(
                                    context.resources.getQuantityString(R.plurals.imported_events, count, count)
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to import events", e)
                                viewModel.showSnackbar(icsImportFailedMessage)
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
                // Resolve live permission state when the sheet opens and
                // reset the per-session dismissal.
                LaunchedEffect(Unit) {
                    localNetworkBannerDismissed = false
                    viewModel.updateLocalNetworkPermissionState(resolveLanPermissionState())
                }
                val lanPermissionState by viewModel.localNetworkPermissionState.collectAsStateWithLifecycle()
                val lanHintActive by viewModel.localNetworkHintActive.collectAsStateWithLifecycle()
                val serverUrl = (uiState.calDavState as? CalDavConnectionState.NotConnected)?.serverUrl.orEmpty()
                // Show the banner proactively for a recognizably-local URL, OR
                // reactively after a discovery failure that looks like a blocked
                // LAN socket (covers bare hostnames isLanHost can't classify).
                val showLanBanner = !localNetworkBannerDismissed &&
                    shouldShowLanBanner(isLanHost(serverUrl) || lanHintActive, lanPermissionState)

                CalDavSignInSheet(
                    state = uiState.calDavState,
                    onServerUrlChange = viewModel::onCalDavServerUrlChange,
                    onDisplayNameChange = viewModel::onCalDavDisplayNameChange,
                    onUsernameChange = viewModel::onCalDavUsernameChange,
                    onPasswordChange = viewModel::onCalDavPasswordChange,
                    onTrustInsecureChange = viewModel::onCalDavTrustInsecureChange,
                    onDiscover = viewModel::onCalDavDiscover,
                    onDismiss = viewModel::hideCalDavSignInSheet,
                    showLocalNetworkBanner = showLanBanner,
                    onRequestLocalNetwork = {
                        localNetworkRationaleBefore = shouldShowLanRationale()
                        localNetworkPermissionLauncher.launch(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
                    },
                    onDismissLocalNetworkBanner = { localNetworkBannerDismissed = true },
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

private const val BACKUP_MIME_TYPE = "application/json"
