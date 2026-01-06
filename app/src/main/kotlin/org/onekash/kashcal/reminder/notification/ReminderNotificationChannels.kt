package org.onekash.kashcal.reminder.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages notification channel for event reminders.
 *
 * Creates a high-importance channel for reminder notifications:
 * - Heads-up display
 * - Sound and vibration
 * - Badge on app icon
 *
 * Per Android best practices:
 * - Channel created once at app startup
 * - Users can customize settings
 * - Settings persist after creation
 */
@Singleton
class ReminderNotificationChannels @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // Channel ID
        const val CHANNEL_REMINDERS = "event_reminders"

        // Notification ID base (avoid collision with sync notifications 1001-1003)
        const val NOTIFICATION_ID_BASE = 2000
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /**
     * Create the reminder notification channel.
     * Should be called at app startup.
     */
    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return // Channels not needed before Android 8
        }

        val channel = NotificationChannel(
            CHANNEL_REMINDERS,
            "Event Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for upcoming calendar events"
            setShowBadge(true)
            enableVibration(true)
            enableLights(true)
            lightColor = android.graphics.Color.BLUE
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Generate unique notification ID from reminder ID.
     * Uses modulo to keep within reasonable range.
     */
    fun getNotificationId(reminderId: Long): Int {
        return (NOTIFICATION_ID_BASE + (reminderId % 10000)).toInt()
    }

    /**
     * Check if notifications are enabled for the app.
     */
    fun areNotificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Check if the reminders channel is enabled.
     */
    fun isChannelEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return areNotificationsEnabled()
        }

        val channel = notificationManager.getNotificationChannel(CHANNEL_REMINDERS)
            ?: return false

        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Cancel a notification by ID.
     */
    fun cancel(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    /**
     * Cancel notification for a specific reminder.
     */
    fun cancelForReminder(reminderId: Long) {
        cancel(getNotificationId(reminderId))
    }
}
