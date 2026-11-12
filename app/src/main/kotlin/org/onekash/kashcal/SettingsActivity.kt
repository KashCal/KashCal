package org.onekash.kashcal

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.onekash.kashcal.data.preferences.UserPreferencesRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.ui.permission.LocalNetworkPermissionManager
import org.onekash.kashcal.ui.permission.NotificationPermissionManager
import org.onekash.kashcal.ui.screens.SettingsRoute
import org.onekash.kashcal.ui.screens.settings.SettingsDestination
import org.onekash.kashcal.ui.theme.ColorSource
import org.onekash.kashcal.ui.theme.ThemeMode
import org.onekash.kashcal.ui.viewmodels.AccountSettingsViewModel
import org.onekash.kashcal.ui.viewmodels.CalendarExportEvent
import org.onekash.kashcal.ui.viewmodels.CalendarExportFailure
import org.onekash.kashcal.ui.viewmodels.CalendarBackupViewModel
import org.onekash.kashcal.ui.viewmodels.CalendarExportViewModel
import org.onekash.kashcal.ui.viewmodels.DebugReportEvent
import org.onekash.kashcal.ui.viewmodels.DebugReportViewModel
import org.onekash.kashcal.ui.viewmodels.OnboardingRoute
import org.onekash.kashcal.util.IcsFileReader
import org.onekash.kashcal.util.ShareChooser
import javax.inject.Inject

private const val TAG = "SettingsActivity"

/**
 * Hosts [SettingsRoute], which owns the view-model collection, the activity-result launchers and
 * the theme wrapper.
 *
 * The activity keeps only work that needs an injected collaborator, the content resolver or the
 * activity itself. It passes narrow lambdas down for the ICS export share intent, ICS file reads
 * and imports, the backup stream I/O, the local-network, notification and calendar permission
 * reads, the notification settings screens and the app's settings page, and does the cold-start
 * theme seed reads, the `onResume` permission refresh and the intent-extra bootstrap itself.
 */
@AndroidEntryPoint
class SettingsActivity : FragmentActivity() {

    companion object {
        // The onboarding sheet's routes: each opens one screen, and closing it returns to
        // HomeScreen ([AccountSettingsViewModel.applyOnboardingRoute] for the sign-ins,
        // [SettingsDestination.closesActivity] for Device calendars).
        const val EXTRA_OPEN_ICLOUD_SIGNIN = "open_icloud_signin"
        const val EXTRA_OPEN_CALDAV_SIGNIN = "open_caldav_signin"
        const val EXTRA_OPEN_DEVICE_CALENDARS = "open_device_calendars"

        const val EXTRA_SUBSCRIPTION_URL = "subscription_url"
        const val EXTRA_OPEN_TAGS = "open_tags"

        /** Opens the Automatic calendar backup sheet, as the backup failure notification does. */
        const val EXTRA_OPEN_CALENDAR_BACKUP = "open_calendar_backup"

        /** Opens Settings on the Sync lookback sheet; search's Sync settings link sends it. */
        const val EXTRA_OPEN_SYNC_LOOKBACK = "open_sync_lookback"

        /**
         * Returns whether this create should open the Sync lookback sheet: only a fresh start
         * with [EXTRA_OPEN_SYNC_LOOKBACK]; after a recreation the sheet's own state decides.
         */
        fun opensSyncLookback(savedInstanceState: Bundle?, intent: Intent): Boolean =
            savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_SYNC_LOOKBACK, false)
    }

    private val viewModel: AccountSettingsViewModel by viewModels()
    private val debugReportViewModel: DebugReportViewModel by viewModels()
    private val calendarExportViewModel: CalendarExportViewModel by viewModels()
    private val calendarBackupViewModel: CalendarBackupViewModel by viewModels()

    private val localNetworkPermissionManager by lazy {
        LocalNetworkPermissionManager(applicationContext)
    }

    private val notificationPermissionManager by lazy {
        NotificationPermissionManager(applicationContext, userPreferencesRepository)
    }

    @Inject
    lateinit var eventCoordinator: EventCoordinator

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

        // Resolve the theme synchronously so the first frame renders in the chosen theme, with no
        // flash of the default on cold start. DataStore caches after the first read.
        val initialThemeString = runBlocking { userPreferencesRepository.theme.first() }
        val initialThemeMode = ThemeMode.fromPrefValue(initialThemeString)
        val initialColorSource = ColorSource.fromPrefValue(
            explicit = runBlocking { userPreferencesRepository.colorSource.first() },
            legacyTheme = initialThemeString,
        )
        val initialAccentSeed = runBlocking { userPreferencesRepository.accentSeed.first() }

        // The account hub launches straight into tag management (Settings has no Tags row), so
        // open on that screen and let its back finish the activity to the hub instead of
        // dropping onto the Settings root.
        val openTags = intent.getBooleanExtra(EXTRA_OPEN_TAGS, false)
        // Read on every create, like Tags, so Back still finishes the activity after a rotation.
        val route = onboardingRoute()
        // Only on a fresh start: after rotation the sheet's own saved state decides.
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_CALENDAR_BACKUP, false)) {
            calendarBackupViewModel.requestSheet()
        }
        if (opensSyncLookback(savedInstanceState, intent)) viewModel.requestSyncLookbackSheet()

        setContent {
            SettingsRoute(
                viewModel = viewModel,
                initialThemeMode = initialThemeMode,
                initialColorSource = initialColorSource,
                initialAccentSeed = initialAccentSeed,
                syncSessionStore = syncSessionStore,
                openTagsInitially = openTags,
                openDeviceCalendarsInitially = route == OnboardingRoute.DEVICE_CALENDARS,
                onFinish = { finish() },
                calendarExportViewModel = calendarExportViewModel,
                calendarBackupViewModel = calendarBackupViewModel,
                readIcsContent = { uri -> icsFileReader.readIcsContent(uri) },
                importIcsToRoom = { file, calendarId -> eventCoordinator.importIcsFile(file, calendarId) },
                previewIcsInRoom = { file, calendarId -> eventCoordinator.previewIcsImport(file, calendarId) },
                writeBackup = ::writeBackup,
                readBackup = ::readBackup,
                resolveLanPermissionState = {
                    localNetworkPermissionManager.resolveState(this@SettingsActivity)
                },
                shouldShowLanRationale = {
                    localNetworkPermissionManager.shouldShowRationale(this@SettingsActivity)
                },
                isNotificationPermissionPermanentlyDenied = {
                    notificationPermissionManager.checkPermissionState(this@SettingsActivity) ==
                        NotificationPermissionManager.PermissionState.PermanentlyDenied
                },
                openNotificationSettings = ::openNotificationSettings,
                onExportDebugReport = debugReportViewModel::export,
                shouldShowCalendarRationale = {
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        this@SettingsActivity, Manifest.permission.READ_CALENDAR
                    )
                },
                openAppDetailsSettings = ::openAppDetailsSettings,
            )
        }

        // The export outlives a rotation in its view model; its result shows only while the
        // screen is started, so the share menu never opens from the background.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                debugReportViewModel.events.collect(::showDebugReportEvent)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                calendarExportViewModel.events.collect(::showCalendarExportEvent)
            }
        }

        route?.let(viewModel::applyOnboardingRoute)

        // Auto-open subscription dialog if launched from webcal:// link
        intent.getStringExtra(EXTRA_SUBSCRIPTION_URL)?.let { url ->
            viewModel.openAddSubscriptionWithUrl(url)
        }
    }

    // The backup failure notification can reach a Settings screen that is already running.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_CALENDAR_BACKUP, false)) {
            calendarBackupViewModel.requestSheet()
        }
    }

    /** Returns the onboarding route this activity was launched with, or null. */
    private fun onboardingRoute(): OnboardingRoute? = when {
        intent.getBooleanExtra(EXTRA_OPEN_ICLOUD_SIGNIN, false) -> OnboardingRoute.ICLOUD
        intent.getBooleanExtra(EXTRA_OPEN_CALDAV_SIGNIN, false) -> OnboardingRoute.CALDAV
        intent.getBooleanExtra(EXTRA_OPEN_DEVICE_CALENDARS, false) -> OnboardingRoute.DEVICE_CALENDARS
        else -> null
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - refreshing permissions")
        viewModel.refreshContactsPermission()
        viewModel.refreshCalendarPermission()
        // Reflect a local-network grant made in system Settings. Upgrade-only: it must not
        // clobber a PermanentlyDenied set by the request classifier (a live read can't
        // represent it), or the banner would nag again on every resume.
        viewModel.reconcileLocalNetworkPermissionOnResume(
            localNetworkPermissionManager.resolveState(this)
        )
        viewModel.refreshChangeNotifications()
        calendarBackupViewModel.refreshNotifications()
    }

    private fun showDebugReportEvent(event: DebugReportEvent) {
        when (event) {
            is DebugReportEvent.Ready -> startActivity(
                ShareChooser.createFileChooser(
                    this, event.uri, "text/plain", getString(R.string.debug_report_chooser_title)
                )
            )
            DebugReportEvent.Failed -> viewModel.showSnackbar(getString(R.string.debug_report_failed))
        }
    }

    /** Opens this app's page in Android settings; returns false when no screen can show it. */
    private fun openAppDetailsSettings(): Boolean = try {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
        true
    } catch (e: android.content.ActivityNotFoundException) {
        Log.e(TAG, "No app settings screen", e)
        false
    }

    /** Opens the system notification settings for [channelId]'s channel, or the app's when null. */
    private fun openNotificationSettings(channelId: String?) {
        val intent = if (channelId != null) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(TAG, "No notification settings screen", e)
        }
    }

    /** Opens the share sheet for an exported calendar, or shows why there is no file. */
    private fun showCalendarExportEvent(event: CalendarExportEvent) {
        when (event) {
            is CalendarExportEvent.Share -> startActivity(
                ShareChooser.createFileChooser(
                    this, event.uri, "text/calendar", getString(R.string.export_calendar_chooser_title)
                )
            )
            is CalendarExportEvent.Failed -> viewModel.showSnackbar(
                getString(
                    when (event.reason) {
                        CalendarExportFailure.CALENDAR_NOT_FOUND -> R.string.error_calendar_not_found
                        CalendarExportFailure.NO_EVENTS -> R.string.error_export_no_events
                        CalendarExportFailure.FILE_FAILED -> R.string.export_file_failed
                    }
                )
            )
        }
    }

    /** Writes the backup JSON to the user-chosen document. Throws on I/O failure. */
    private suspend fun writeBackup(uri: Uri, json: String) {
        withContext(Dispatchers.IO) {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("Could not open output stream")
        }
    }

    /** Reads the user-chosen backup document as a UTF-8 string. Throws on I/O failure. */
    private suspend fun readBackup(uri: Uri): String = withContext(Dispatchers.IO) {
        contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: error("Could not open input stream")
    }
}
