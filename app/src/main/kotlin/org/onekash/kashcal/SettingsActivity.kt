package org.onekash.kashcal

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.ics.IcsParserService
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.preferences.UserPreferencesRepository
import org.onekash.kashcal.domain.backup.BackupFilename
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.ui.components.CalDavSignInSheet
import org.onekash.kashcal.ui.permission.LocalNetworkPermissionManager
import org.onekash.kashcal.ui.permission.classifyLocalNetworkAfterRequest
import org.onekash.kashcal.ui.permission.shouldShowLanBanner
import org.onekash.kashcal.ui.screens.settings.CalDavConnectionState
import org.onekash.kashcal.util.isLanHost
import org.onekash.kashcal.ui.components.ICloudSignInSheet
import org.onekash.kashcal.ui.components.IcsImportSheet
import org.onekash.kashcal.ui.components.SyncHistorySheet
import org.onekash.kashcal.ui.screens.AccountSettingsScreen
import org.onekash.kashcal.ui.screens.BackupRestoreUiState
import org.onekash.kashcal.ui.screens.settings.AccountConnectedSheet
import org.onekash.kashcal.ui.screens.settings.AccountsScreen
import org.onekash.kashcal.ui.screens.settings.BirthdaysAndAnniversariesScreen
import org.onekash.kashcal.ui.screens.settings.DeviceCalendarsScreen
import org.onekash.kashcal.ui.screens.settings.ICloudAccountUiModel
import org.onekash.kashcal.ui.screens.settings.ICloudConnectionState
import org.onekash.kashcal.ui.screens.settings.RestoreConfirmationDialog
import org.onekash.kashcal.ui.screens.settings.RestoreErrorDialog
import org.onekash.kashcal.ui.screens.settings.RestoreSuccessDialog
import org.onekash.kashcal.ui.screens.settings.SubscriptionsScreen
import org.onekash.kashcal.ui.lock.AppLockDisableAction
import org.onekash.kashcal.ui.lock.AppLockEnrollmentAction
import org.onekash.kashcal.ui.lock.decideDisableAction
import org.onekash.kashcal.ui.lock.decideEnrollmentAction
import org.onekash.kashcal.ui.theme.ColorSource
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.onekash.kashcal.ui.theme.ThemeMode
import org.onekash.kashcal.ui.viewmodels.AccountSettingsViewModel
import org.onekash.kashcal.util.IcsExporter
import org.onekash.kashcal.util.IcsFileReader
import org.onekash.kashcal.util.ShareChooser
import javax.inject.Inject

private const val TAG = "SettingsActivity"
private const val BACKUP_MIME_TYPE = "application/json"

/**
 * Settings activity hosting AccountSettingsScreen.
 * Manages iCloud account, calendar settings, and app preferences.
 */
@AndroidEntryPoint
class SettingsActivity : FragmentActivity() {

    companion object {
        const val EXTRA_OPEN_ICLOUD_SIGNIN = "open_icloud_signin"
        const val EXTRA_SUBSCRIPTION_URL = "subscription_url"
    }

    private val viewModel: AccountSettingsViewModel by viewModels()

    // Guards against stacking two disable prompts: the toggle reflects the
    // persisted pref, which only flips after a successful auth, so it still reads
    // "on" between the first tap and the prompt resolving — a second tap would
    // otherwise fire a second BiometricPrompt. Mirrors MainActivity's unlock guard.
    private var isDisablePromptShowing = false

    @Inject
    lateinit var eventCoordinator: EventCoordinator

    @Inject
    lateinit var icsExporter: IcsExporter

    @Inject
    lateinit var syncSessionStore: SyncSessionStore

    @Inject
    lateinit var icsFileReader: IcsFileReader

    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        enableEdgeToEdge()

        // Resolve the theme synchronously so the first frame renders in the chosen theme — no
        // flash of the default on cold start. DataStore caches after the first read.
        val initialThemeString = runBlocking { userPreferencesRepository.theme.first() }
        val initialThemeMode = ThemeMode.fromPrefValue(initialThemeString)
        val initialColorSource = ColorSource.fromPrefValue(
            explicit = runBlocking { userPreferencesRepository.colorSource.first() },
            legacyTheme = initialThemeString,
        )
        val initialAccentSeed = runBlocking { userPreferencesRepository.accentSeed.first() }

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle(initialValue = initialThemeMode)
            val colorSource by viewModel.colorSource.collectAsStateWithLifecycle(initialValue = initialColorSource)
            val accentSeed by viewModel.accentSeed.collectAsStateWithLifecycle(initialValue = initialAccentSeed)
            KashCalTheme(themeMode = themeMode, colorSource = colorSource, accentSeed = accentSeed) {
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
                val appLockEnabled by viewModel.appLockEnabled.collectAsStateWithLifecycle()
                val timeFormat by viewModel.timeFormat.collectAsStateWithLifecycle()
                val firstDayOfWeek by viewModel.firstDayOfWeek.collectAsStateWithLifecycle()
                val showWeekNumbers by viewModel.showWeekNumbers.collectAsStateWithLifecycle()
                val widgetMaxEventsPerDay by viewModel.widgetMaxEventsPerDay.collectAsStateWithLifecycle()
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

                // Local-network permission (Android 17+) for LAN CalDAV servers.
                // The manager owns the rationale read; the resolved state is
                // pushed to the VM so the sign-in sheet can proactively ask.
                val localNetworkPermissionManager = remember {
                    LocalNetworkPermissionManager(applicationContext)
                }
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
                            rationaleAfter = localNetworkPermissionManager.shouldShowRationale(this@SettingsActivity),
                        )
                    )
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

                // Account connected success sheet state
                @OptIn(ExperimentalMaterial3Api::class)
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

                BackHandler(
                    enabled = showAccountsScreen ||
                        showBirthdaysAnniversariesScreen ||
                        showSubscriptionsScreen ||
                        showDeviceCalendarsScreen
                ) {
                    showAccountsScreen = false
                    showBirthdaysAnniversariesScreen = false
                    showSubscriptionsScreen = false
                    showDeviceCalendarsScreen = false
                }

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
                                onAddSubscription = onAddSubscriptionWithDuplicateGuard,
                                onToggleSubscription = viewModel::onToggleSubscription,
                                onDeleteSubscription = onDeleteSubscriptionWithUndo,
                                onRefreshSubscription = viewModel::onRefreshSubscription,
                                onUpdateSubscription = viewModel::onUpdateSubscription
                            )
                        }
                        showDeviceCalendarsScreen -> {
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
                                            Manifest.permission.READ_CALENDAR,
                                            Manifest.permission.WRITE_CALENDAR
                                        ))
                                    } else {
                                        viewModel.onToggleDeviceCalendars(enabled)
                                    }
                                },
                                onToggleCalendar = viewModel::onToggleDeviceCalendar,
                                onToggleDeviceCalendarReminders = viewModel::onToggleDeviceCalendarReminders,
                                onRequestWritePermission = {
                                    writeCalendarPermissionLauncher.launch(Manifest.permission.WRITE_CALENDAR)
                                },
                                onRefresh = viewModel::refreshDeviceCalendars
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
                            onAddSubscription = onAddSubscriptionWithDuplicateGuard,
                            onHideAddSubscriptionDialog = viewModel::hideAddSubscriptionDialog,
                            onDeleteSubscription = onDeleteSubscriptionWithUndo,
                            onToggleSubscription = viewModel::onToggleSubscription,
                            onRefreshSubscription = viewModel::onRefreshSubscription,
                            onUpdateSubscription = viewModel::onUpdateSubscription,
                            onSyncAllSubscriptions = viewModel::onSyncAllSubscriptions,
                            // System
                            onShowSyncLogs = { showDebugLogSheet = true },
                            notificationsEnabled = notificationsEnabled,
                            onRequestNotificationPermission = {
                                // VMs should not start activities. Intent launch lives here.
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                                startActivity(intent)
                            },
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
                            // Privacy / app lock
                            appLockEnabled = appLockEnabled,
                            onToggleAppLock = { enabled ->
                                if (enabled) {
                                    // Capability / enrollment check lives here (needs context).
                                    // VMs must not start activities, so the enrollment
                                    // intent is launched from the Activity.
                                    when (decideEnrollmentAction(canAuthenticateForAppLock())) {
                                        AppLockEnrollmentAction.Enable -> {
                                            viewModel.setAppLockEnabled(true)
                                            // Enabling adds protection, so it isn't gated behind auth —
                                            // but confirm inline and set the expectation that the prompt
                                            // appears on the next fresh open, not on the return to here.
                                            viewModel.showSnackbar(getString(R.string.app_lock_enabled_message))
                                        }
                                        AppLockEnrollmentAction.RouteToEnroll ->
                                            launchBiometricEnrollment()
                                        AppLockEnrollmentAction.Unsupported ->
                                            viewModel.showSnackbar(getString(R.string.app_lock_unsupported_message))
                                    }
                                } else {
                                    // Disabling REMOVES protection, so it must be authenticated:
                                    // otherwise anyone holding the already-unlocked phone could open
                                    // Settings and switch the lock off. Only commit false on success.
                                    authenticateThenDisableAppLock()
                                }
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
                                            startActivity(ShareChooser.createKashCalChooser(this@SettingsActivity, intent, "Export Calendar"))
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
                        // Resolve live permission state when the sheet opens and
                        // reset the per-session dismissal.
                        LaunchedEffect(Unit) {
                            localNetworkBannerDismissed = false
                            viewModel.updateLocalNetworkPermissionState(
                                localNetworkPermissionManager.resolveState(this@SettingsActivity)
                            )
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
                                localNetworkRationaleBefore =
                                    localNetworkPermissionManager.shouldShowRationale(this@SettingsActivity)
                                localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
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
        // Reflect a local-network grant made in system Settings while the sheet
        // was open. Upgrade-only: must not clobber a PermanentlyDenied set by the
        // request classifier (a live read can't represent it), or the banner
        // would nag again on every resume.
        viewModel.reconcileLocalNetworkPermissionOnResume(
            LocalNetworkPermissionManager(applicationContext).resolveState(this)
        )
    }

    /** Can the device satisfy the app lock with a strong biometric OR the screen-lock credential? */
    private fun canAuthenticateForAppLock(): Int =
        BiometricManager.from(this)
            .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)

    /**
     * Send the user to the system enrollment flow rather than enabling a lock
     * nothing can satisfy. The pre-API-30 intent (plain security settings) is
     * used as a fallback since ACTION_BIOMETRIC_ENROLL is API 30+.
     */
    private fun launchBiometricEnrollment() {
        viewModel.showSnackbar(getString(R.string.app_lock_enroll_message))
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
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(TAG, "No enrollment activity available", e)
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
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
     * This mirrors the lock-out recovery in MainActivity's unlock prompt.
     */
    private fun authenticateThenDisableAppLock() {
        if (isDisablePromptShowing) return

        val authenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        if (decideDisableAction(canAuthenticateForAppLock()) == AppLockDisableAction.DisableDirectly) {
            Log.w(TAG, "No credential enrolled when disabling app lock; disabling without a challenge")
            viewModel.setAppLockEnabled(false)
            return
        }

        isDisablePromptShowing = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isDisablePromptShowing = false
                    viewModel.setAppLockEnabled(false)
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
}
