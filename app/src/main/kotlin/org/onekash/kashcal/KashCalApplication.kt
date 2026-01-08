package org.onekash.kashcal

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import org.onekash.kashcal.data.contacts.ContactBirthdayManager
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

    override fun onCreate() {
        super.onCreate()

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
