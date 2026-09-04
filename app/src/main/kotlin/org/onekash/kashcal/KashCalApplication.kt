package org.onekash.kashcal

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.kashcal.data.calendar_provider.CalendarProviderManager
import org.onekash.kashcal.data.contacts.ContactEventManager
import org.onekash.kashcal.data.credential.CredentialMigration
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.di.ApplicationScope
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.reminder.notification.InviteNotificationChannels
import org.onekash.kashcal.reminder.notification.ReminderNotificationChannels
import org.onekash.kashcal.reminder.worker.ReminderRefreshWorker
import org.onekash.kashcal.sync.adapter.SystemAccountRegistrar
import org.onekash.kashcal.sync.notification.SyncNotificationChannels
import org.onekash.kashcal.sync.scheduler.ContactSyncScheduleReconciler
import org.onekash.kashcal.sync.scheduler.IcsRefreshScheduleReconciler
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.widget.WidgetPreviewRegistrar
import org.onekash.kashcal.widget.WidgetUpdateManager
import java.time.ZoneId
import javax.inject.Inject

/**
 * Application class with Hilt dependency injection and WorkManager integration.
 *
 * Implements Configuration.Provider to use HiltWorkerFactory for injecting
 * dependencies into WorkManager workers (like CalDavSyncWorker).
 *
 * Features:
 * - Network monitoring for offline-first architecture
 * - Automatic sync trigger when network is restored
 * - WorkManager integration with Hilt
 */
@HiltAndroidApp
class KashCalApplication : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "KashCalApplication"
        const val PREFS_NAME = "kashcal_upgrade"
        const val KEY_LAST_VERSION = "last_version_code"
        /**
         * Previous versionCode captured by [handleAppUpgrade] before
         * [KEY_LAST_VERSION] is overwritten on each app start. 0 means
         * either fresh install or prior to this key being introduced.
         * Used as the seed signal for the What's New sheet so existing
         * users from before the feature shipped don't fall through the
         * "DataStore default 0 = silent fresh install" trap.
         */
        const val KEY_PREVIOUS_VERSION = "previous_version_code"
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    @Inject
    lateinit var syncScheduler: SyncScheduler

    @Inject
    lateinit var notificationChannels: SyncNotificationChannels

    @Inject
    lateinit var reminderNotificationChannels: ReminderNotificationChannels

    @Inject
    lateinit var inviteNotificationChannels: InviteNotificationChannels

    @Inject
    lateinit var widgetUpdateManager: WidgetUpdateManager

    @Inject
    lateinit var contactEventManager: ContactEventManager

    @Inject
    lateinit var calendarProviderManager: CalendarProviderManager

    @Inject
    lateinit var dataStore: KashCalDataStore

    @Inject
    lateinit var eventsDao: EventsDao

    @Inject
    lateinit var credentialMigration: CredentialMigration

    @Inject
    lateinit var icsRefreshScheduleReconciler: IcsRefreshScheduleReconciler

    @Inject
    lateinit var contactSyncScheduleReconciler: ContactSyncScheduleReconciler

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()

        // Set Windows timezone resolver using Android ICU (CLDR-maintained).
        // Primary resolver for names like "Eastern Standard Time" → America/New_York.
        // Properties file in icaldav library serves as fallback for JVM/non-Android.
        ICalDateTime.customTimezoneResolver = { tzid ->
            android.icu.util.TimeZone.getIDForWindowsID(tzid, null)
                ?.let { try { ZoneId.of(it) } catch (_: Exception) { null } }
        }

        // Handle app upgrade - cancel stale sync work to prevent crashes
        handleAppUpgrade()

        // Migrate credentials from old format to unified format (one-time)
        migrateCredentialsIfNeeded()

        // Check if parser version changed - clear etags to force re-parse
        checkParserVersionAndClearEtags()

        // Create notification channels at app startup
        notificationChannels.createChannels()
        reminderNotificationChannels.createChannels()
        inviteNotificationChannels.createChannels()

        // Long-lived background registrations (network callbacks, WorkManager
        // and AlarmManager scheduling, content observers, the account-
        // registration coroutine) are skipped under unit tests. In Robolectric
        // these fire real side effects into process-global singletons
        // (ShadowAccountManager, ShadowAlarmManager, WorkManager) that aren't
        // reset between test classes sharing a JVM fork, leaking nondeterministic
        // state into unrelated tests. Device/instrumented runs are unaffected.
        if (!isUnitTestEnvironment()) {
            // Start network monitoring with sync trigger on restore
            networkMonitor.startMonitoring {
                Log.d(TAG, "Network restored, triggering sync")
                syncScheduler.requestImmediateSync()
            }

            // Schedule widget updates (periodic + midnight)
            widgetUpdateManager.schedulePeriodicUpdates()
            widgetUpdateManager.scheduleMidnightUpdate()

            // Initialize contact event observers (birthdays and anniversaries, if enabled)
            contactEventManager.initialize()

            // Initialize device calendar observer (if enabled)
            calendarProviderManager.initialize()

            // Schedule periodic reminder refresh (catches events entering window)
            ReminderRefreshWorker.schedule(this)

            // Register KashCal account for CalendarProvider intent routing (#76).
            // Runs on IO thread to avoid blocking startup (AccountManager is IPC).
            applicationScope.launch {
                SystemAccountRegistrar(this@KashCalApplication).ensureAccount()
            }

            // Publish widget-picker previews. Startup is the only reliable trigger:
            // the platform call is rate limited per app, so it must not ride along
            // with widget refreshes. The registrar keeps its own per-widget state and
            // no-ops once everything is published for this build and month.
            applicationScope.launch {
                WidgetPreviewRegistrar.register(
                    this@KashCalApplication,
                    BuildConfig.VERSION_CODE
                )
            }

            // Bring the ICS feed refresh job in line with the feeds in the
            // database. Startup is where this heals itself: WorkManager's database
            // lives in the no-backup directory, so a job lost to a force-stop, an
            // OEM task killer, or a device restored from a backup is gone for
            // good, and until now nothing re-armed it — feeds then only ever
            // updated when the user pulled to refresh.
            // No try/catch here on purpose: the reconciler already catches and logs,
            // so a wrapper would be dead code.
            applicationScope.launch {
                icsRefreshScheduleReconciler.reconcile()
            }

            // Re-arm the periodic contact-sync job from the accounts in the
            // database. Startup is where this heals: a login enrolled before
            // contact sync shipped never had the recurring job armed, and an
            // install whose spec was lost (force-stop, task killer, backup restore)
            // has no other way back. The reconciler catches and logs on its own, so
            // a wrapper here would be dead code.
            applicationScope.launch {
                contactSyncScheduleReconciler.reconcile()
            }
        }

        Log.d(TAG, "KashCal application started")
    }

    /**
     * True when running under Robolectric/JVM unit tests, where the
     * application is instantiated to obtain a context but must not start
     * real background work. Robolectric sets Build.FINGERPRINT to
     * "robolectric"; device and instrumented builds never do.
     */
    private fun isUnitTestEnvironment(): Boolean =
        "robolectric".equals(android.os.Build.FINGERPRINT, ignoreCase = true)

    /**
     * Handle app upgrade by clearing stale WorkManager jobs.
     *
     * This prevents crashes when upgrading from older versions (e.g., v20.11.7)
     * where the sync code has changed significantly (icaldav library migration).
     *
     * On upgrade:
     * - Cancel all pending sync work (will be re-scheduled by user action)
     * - Store current version to detect future upgrades
     */
    private fun handleAppUpgrade() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentVersion = BuildConfig.VERSION_CODE
        val lastVersion = prefs.getInt(KEY_LAST_VERSION, 0)

        if (lastVersion != currentVersion) {
            if (lastVersion > 0) {
                Log.i(TAG, "App upgrade detected: $lastVersion → $currentVersion")
            }

            // Cancel all sync-related work to prevent crashes from stale code
            // This runs on upgrade AND fresh install (clears any stale work from previous installs)
            try {
                val workManager = WorkManager.getInstance(this)
                workManager.cancelAllWorkByTag("caldav_sync")
                workManager.cancelAllWorkByTag("sync_periodic")
                workManager.cancelUniqueWork("caldav_sync")
                workManager.cancelUniqueWork("caldav_periodic_sync")
                if (lastVersion > 0) {
                    Log.i(TAG, "Cancelled stale sync work after upgrade")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel stale work: ${e.message}")
            }
        }

        // Store current version, capturing the previous value first so
        // downstream features (e.g. What's New) can tell a real upgrade
        // from a fresh install.
        if (lastVersion != currentVersion) {
            prefs.edit()
                .putInt(KEY_PREVIOUS_VERSION, lastVersion)
                .putInt(KEY_LAST_VERSION, currentVersion)
                .apply()
            Log.d(TAG, "Stored version code: $currentVersion (previous=$lastVersion)")
        }
    }

    /**
     * Migrate credentials from old format to unified format.
     *
     * This is a one-time migration that runs on app launch. It migrates:
     * - iCloud: Single-key format → account-keyed format
     * - CalDAV: Old caldav_credentials → unified_credentials
     *
     * The migration is idempotent (DataStore flag) and non-destructive
     * (old credentials preserved until explicitly deleted).
     */
    private fun migrateCredentialsIfNeeded() {
        applicationScope.launch {
            try {
                val result = credentialMigration.migrateIfNeeded()
                when (result) {
                    is CredentialMigration.MigrationResult.Success ->
                        Log.i(TAG, "Credential migration completed")
                    is CredentialMigration.MigrationResult.AlreadyMigrated ->
                        Log.d(TAG, "Credentials already migrated")
                    is CredentialMigration.MigrationResult.NoCredentialsToMigrate ->
                        Log.d(TAG, "No credentials to migrate (fresh install)")
                    is CredentialMigration.MigrationResult.PartialSuccess ->
                        Log.w(TAG, "Partial credential migration: iCloud=${result.icloudSuccess}, CalDAV=${result.caldavSuccess}")
                    is CredentialMigration.MigrationResult.Failed ->
                        Log.e(TAG, "Credential migration failed: ${result.error}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Credential migration error: ${e.message}", e)
            }
        }
    }

    /**
     * Check if parser version changed and clear all etags if so.
     *
     * When iCalendar parsing logic changes (e.g., timezone handling), we need to
     * force all events to be re-parsed on next sync, even if their server etags
     * haven't changed. Clearing etags achieves this.
     *
     * @see KashCalDataStore.CURRENT_PARSER_VERSION for version history
     */
    private fun checkParserVersionAndClearEtags() {
        applicationScope.launch {
            try {
                val storedVersion = dataStore.getParserVersion()
                val currentVersion = KashCalDataStore.CURRENT_PARSER_VERSION

                if (storedVersion < currentVersion) {
                    Log.i(TAG, "Parser version changed: $storedVersion → $currentVersion")
                    eventsDao.clearAllEtags()
                    dataStore.setParserVersion(currentVersion)
                    Log.i(TAG, "Cleared all etags to force re-parse on next sync")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check parser version: ${e.message}")
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        networkMonitor.stopMonitoring()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
