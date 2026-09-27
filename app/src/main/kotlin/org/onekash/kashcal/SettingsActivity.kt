package org.onekash.kashcal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
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
import org.onekash.kashcal.ui.screens.SettingsRoute
import org.onekash.kashcal.ui.theme.ColorSource
import org.onekash.kashcal.ui.theme.ThemeMode
import org.onekash.kashcal.ui.viewmodels.AccountSettingsViewModel
import org.onekash.kashcal.util.IcsExporter
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
 * and imports, the backup stream I/O and the local-network permission reads, and does the
 * cold-start theme seed reads, the `onResume` permission refresh and the intent-extra bootstrap
 * itself.
 */
@AndroidEntryPoint
class SettingsActivity : FragmentActivity() {

    companion object {
        const val EXTRA_OPEN_ICLOUD_SIGNIN = "open_icloud_signin"
        const val EXTRA_SUBSCRIPTION_URL = "subscription_url"
        const val EXTRA_OPEN_TAGS = "open_tags"
    }

    private val viewModel: AccountSettingsViewModel by viewModels()

    private val localNetworkPermissionManager by lazy {
        LocalNetworkPermissionManager(applicationContext)
    }

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

        setContent {
            SettingsRoute(
                viewModel = viewModel,
                initialThemeMode = initialThemeMode,
                initialColorSource = initialColorSource,
                initialAccentSeed = initialAccentSeed,
                syncSessionStore = syncSessionStore,
                openTagsInitially = openTags,
                onFinish = { finish() },
                onExportCalendar = ::exportCalendar,
                readIcsContent = { uri -> icsFileReader.readIcsContent(uri) },
                importIcsToRoom = { events, calendarId ->
                    eventCoordinator.importIcsEvents(events, calendarId)
                },
                writeBackup = ::writeBackup,
                readBackup = ::readBackup,
                resolveLanPermissionState = {
                    localNetworkPermissionManager.resolveState(this@SettingsActivity)
                },
                shouldShowLanRationale = {
                    localNetworkPermissionManager.shouldShowRationale(this@SettingsActivity)
                },
            )
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
        viewModel.refreshContactsPermission()
        viewModel.refreshCalendarPermission()
        // Reflect a local-network grant made in system Settings. Upgrade-only: it must not
        // clobber a PermanentlyDenied set by the request classifier (a live read can't
        // represent it), or the banner would nag again on every resume.
        viewModel.reconcileLocalNetworkPermissionOnResume(
            localNetworkPermissionManager.resolveState(this)
        )
    }

    /** Exports a calendar to ICS and hands it to the system share sheet. */
    private fun exportCalendar(calendarId: Long) {
        lifecycleScope.launch {
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
