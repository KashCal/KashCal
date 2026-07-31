package org.onekash.kashcal

import android.content.Intent
import android.net.Uri
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
import org.onekash.kashcal.ui.lock.AppLockDisableAction
import org.onekash.kashcal.ui.lock.AppLockEnrollmentAction
import org.onekash.kashcal.ui.lock.decideDisableAction
import org.onekash.kashcal.ui.lock.decideEnrollmentAction
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
 * Settings activity hosting [SettingsRoute].
 * Manages iCloud account, calendar settings, and app preferences.
 *
 * This is a thin host: [SettingsRoute] owns the view-model collection, the
 * activity-result launchers, and the theme wrapper. The activity retains only the
 * work that genuinely needs a [FragmentActivity], an injected collaborator, or the
 * content resolver — the biometric app-lock flow, the notification-settings /
 * enrollment / share intents, the backup stream I/O, the local-network permission
 * reads, the cold-start theme seed reads, `onResume` permission refresh, and the
 * intent-extra bootstrap — and passes each down as a narrow lambda.
 */
@AndroidEntryPoint
class SettingsActivity : FragmentActivity() {

    companion object {
        const val EXTRA_OPEN_ICLOUD_SIGNIN = "open_icloud_signin"
        const val EXTRA_SUBSCRIPTION_URL = "subscription_url"
        const val EXTRA_OPEN_TAGS = "open_tags"
    }

    private val viewModel: AccountSettingsViewModel by viewModels()

    // Guards against stacking two disable prompts: the toggle reflects the
    // persisted pref, which only flips after a successful auth, so it still reads
    // "on" between the first tap and the prompt resolving — a second tap would
    // otherwise fire a second BiometricPrompt. Mirrors MainActivity's unlock guard.
    private var isDisablePromptShowing = false

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

        // Resolve the theme synchronously so the first frame renders in the chosen theme — no
        // flash of the default on cold start. DataStore caches after the first read.
        val initialThemeString = runBlocking { userPreferencesRepository.theme.first() }
        val initialThemeMode = ThemeMode.fromPrefValue(initialThemeString)
        val initialColorSource = ColorSource.fromPrefValue(
            explicit = runBlocking { userPreferencesRepository.colorSource.first() },
            legacyTheme = initialThemeString,
        )
        val initialAccentSeed = runBlocking { userPreferencesRepository.accentSeed.first() }

        // Launched straight into tag management from the account hub (there is no
        // Tags row in Settings itself), so open on that screen and let its back
        // finish the activity back to the hub rather than drop onto the Settings root.
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
                onOpenNotificationSettings = {
                    // VMs should not start activities. Intent launch lives here.
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    startActivity(intent)
                },
                onToggleAppLock = ::onToggleAppLock,
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
        viewModel.refreshNotificationPermission()
        viewModel.refreshContactsPermission()
        viewModel.refreshCalendarPermission()
        // Reflect a local-network grant made in system Settings while the sheet
        // was open. Upgrade-only: must not clobber a PermanentlyDenied set by the
        // request classifier (a live read can't represent it), or the banner
        // would nag again on every resume.
        viewModel.reconcileLocalNetworkPermissionOnResume(
            localNetworkPermissionManager.resolveState(this)
        )
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
            // Disabling REMOVES protection, so it must be authenticated: otherwise
            // anyone holding the already-unlocked phone could open Settings and
            // switch the lock off. Only commit false on success.
            authenticateThenDisableAppLock()
        }
    }

    /** Export a calendar to ICS and hand it to the system share sheet. */
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

    /** Write the prepared backup JSON to the user-chosen document. Throws on I/O failure. */
    private suspend fun writeBackup(uri: Uri, json: String) {
        withContext(Dispatchers.IO) {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("Could not open output stream")
        }
    }

    /** Read the user-chosen backup document as a UTF-8 string. Throws on I/O failure. */
    private suspend fun readBackup(uri: Uri): String = withContext(Dispatchers.IO) {
        contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: error("Could not open input stream")
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
