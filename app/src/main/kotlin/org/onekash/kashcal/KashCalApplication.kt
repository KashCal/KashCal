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
 * Runs app-start setup and supplies WorkManager's configuration.
 *
 * As a `Configuration.Provider` it hands WorkManager the [HiltWorkerFactory], so workers such as
 * `CalDavSyncWorker` get their dependencies injected. On start it also requests a sync whenever
 * the network comes back.
 */
@HiltAndroidApp
class KashCalApplication : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "KashCalApplication"
        const val PREFS_NAME = "kashcal_upgrade"
        const val KEY_LAST_VERSION = "last_version_code"
        /**
         * Holds the versionCode [handleAppUpgrade] read before overwriting [KEY_LAST_VERSION]
         * on a version change. 0 means a fresh install or an install older than this key.
         * It seeds the What's New sheet, so users who predate the feature aren't treated as a
         * silent fresh install by the DataStore default of 0.
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

        // Resolve Windows zone names ("Eastern Standard Time" to America/New_York) with Android
        // ICU, which follows CLDR. The icaldav library's properties file is the JVM fallback.
        ICalDateTime.customTimezoneResolver = { tzid ->
            android.icu.util.TimeZone.getIDForWindowsID(tzid, null)
                ?.let { try { ZoneId.of(it) } catch (_: Exception) { null } }
        }

        handleAppUpgrade()

        migrateCredentialsIfNeeded()

        checkParserVersionAndClearEtags()

        notificationChannels.createChannels()
        reminderNotificationChannels.createChannels()
        inviteNotificationChannels.createChannels()

        // Long-lived background registrations (network callbacks, WorkManager and AlarmManager
        // scheduling, content observers, the startup coroutines below) are skipped under unit
        // tests. In Robolectric they fire real side effects into process-global singletons
        // (ShadowAccountManager, ShadowAlarmManager, WorkManager) that aren't reset between test
        // classes sharing a JVM fork, leaking nondeterministic state into unrelated tests.
        if (!isUnitTestEnvironment()) {
            networkMonitor.startMonitoring {
                Log.d(TAG, "Network restored, triggering sync")
                syncScheduler.requestImmediateSync()
            }

            // Periodic and midnight widget updates.
            widgetUpdateManager.scheduleUpdates()

            // When birthdays or anniversaries are on, registers the contacts observer and syncs
            // them; without READ_CONTACTS it turns both features off instead.
            contactEventManager.initialize()

            // When device calendars are on, registers their observer; without READ_CALENDAR it
            // turns the feature off instead.
            calendarProviderManager.initialize()

            // Daily refresh that arms reminders that entered the scheduling window.
            ReminderRefreshWorker.schedule(this)

            // Register the KashCal account for CalendarProvider intent routing (#76). Off the
            // main thread, since AccountManager is IPC.
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

            // Bring the ICS feed refresh job in line with the feeds in the database. WorkManager's
            // database lives in the no-backup directory, so a job lost to a force-stop, an OEM
            // task killer or a backup restore is gone for good; without this re-arm, feeds would
            // update only on pull to refresh. No try/catch: the reconciler catches and logs.
            applicationScope.launch {
                icsRefreshScheduleReconciler.reconcile()
            }

            // Re-arm the periodic contact-sync job from the accounts in the database. A login
            // enrolled before contact sync shipped never had the job armed, and a spec lost to a
            // force-stop, task killer or backup restore has no other way back. No try/catch: the
            // reconciler catches and logs.
            applicationScope.launch {
                contactSyncScheduleReconciler.reconcile()
            }
        }

        Log.d(TAG, "KashCal application started")
    }

    /**
     * Returns true under Robolectric unit tests, which create the application for a context but
     * must not start real background work. Robolectric sets `Build.FINGERPRINT` to "robolectric";
     * device and instrumented builds never do.
     */
    private fun isUnitTestEnvironment(): Boolean =
        "robolectric".equals(android.os.Build.FINGERPRINT, ignoreCase = true)

    /**
     * Cancels legacy sync work and records the version change whenever the versionCode differs
     * from the stored one, a fresh install included.
     *
     * The cancelled tags and unique names are the ones used before the icaldav library migration
     * (for example v20.11.7); [SyncScheduler] schedules under other names, so its work survives.
     * Left queued, the old work can crash against the changed sync code.
     */
    private fun handleAppUpgrade() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentVersion = BuildConfig.VERSION_CODE
        val lastVersion = prefs.getInt(KEY_LAST_VERSION, 0)

        if (lastVersion != currentVersion) {
            if (lastVersion > 0) {
                Log.i(TAG, "App upgrade detected: $lastVersion → $currentVersion")
            }

            // Runs on a fresh install too, clearing work left by a previous install.
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

        // Keep the previous value so What's New can tell an upgrade from a fresh install.
        if (lastVersion != currentVersion) {
            prefs.edit()
                .putInt(KEY_PREVIOUS_VERSION, lastVersion)
                .putInt(KEY_LAST_VERSION, currentVersion)
                .apply()
            Log.d(TAG, "Stored version code: $currentVersion (previous=$lastVersion)")
        }
    }

    /**
     * Copies old iCloud and CalDAV credentials into the unified format in the background, through
     * [CredentialMigration], whose class doc says when it runs and retries. Logs the result and
     * never throws.
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
     * Clears every event etag when the stored parser version is below
     * [KashCalDataStore.CURRENT_PARSER_VERSION], so the next sync re-parses every event even
     * though its server etag hasn't changed. The version history is on that constant.
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
