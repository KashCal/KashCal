package org.onekash.kashcal

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.onekash.kashcal.data.contacts.ContactBirthdayManager
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.reminder.notification.ReminderNotificationChannels
import org.onekash.kashcal.reminder.worker.ReminderRefreshWorker
import org.onekash.kashcal.sync.notification.SyncNotificationChannels
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.widget.WidgetUpdateManager
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
        private const val PREFS_NAME = "kashcal_upgrade"
        private const val KEY_LAST_VERSION = "last_version_code"
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
    lateinit var widgetUpdateManager: WidgetUpdateManager

    @Inject
    lateinit var contactBirthdayManager: ContactBirthdayManager

    @Inject
    lateinit var dataStore: KashCalDataStore

    @Inject
    lateinit var eventsDao: EventsDao

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Handle app upgrade - cancel stale sync work to prevent crashes
        handleAppUpgrade()

        // Check if parser version changed - clear etags to force re-parse
        checkParserVersionAndClearEtags()

        // Create notification channels at app startup
        notificationChannels.createChannels()
        reminderNotificationChannels.createChannels()

        // Start network monitoring with sync trigger on restore
        networkMonitor.startMonitoring {
            Log.d(TAG, "Network restored, triggering sync")
            syncScheduler.requestImmediateSync()
        }

        // Schedule widget updates (periodic + midnight)
        widgetUpdateManager.schedulePeriodicUpdates()
        widgetUpdateManager.scheduleMidnightUpdate()

        // Initialize contact birthday observer (if enabled)
        contactBirthdayManager.initialize()

        // Schedule periodic reminder refresh (catches events entering window)
        ReminderRefreshWorker.schedule(this)

        Log.d(TAG, "KashCal application started")
    }

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

        // Store current version
        if (lastVersion != currentVersion) {
            prefs.edit().putInt(KEY_LAST_VERSION, currentVersion).apply()
            Log.d(TAG, "Stored version code: $currentVersion")
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
