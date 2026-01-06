package org.onekash.kashcal.ui.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.onekash.kashcal.data.preferences.UserPreferencesRepository

/**
 * Manages notification permission state and request flow.
 *
 * Follows Android best practices (developer.android.com/develop/ui/views/notifications/notification-permission):
 * - Request after meaningful user action (saving event with reminder)
 * - Show rationale when shouldShowRequestPermissionRationale() returns true
 * - Track denial count to detect "permanently denied" state
 * - Provide path to re-enable via system Settings
 *
 * Architecture:
 * ```
 * User saves event with reminder
 *          │
 *          ▼
 * checkPermissionState()
 *          │
 *     ┌────┴────┬──────────────┬────────────────┐
 *     ▼         ▼              ▼                ▼
 *  Granted  NotYetRequested  ShouldShow   Permanently
 *  NotRequired               Rationale      Denied
 *     │         │              │                │
 *     ▼         ▼              ▼                ▼
 *  proceed   launch         show dialog    proceed
 *            system dialog  → launch         (graceful)
 * ```
 */
class NotificationPermissionManager(
    private val context: Context,
    private val userPreferences: UserPreferencesRepository
) {

    /**
     * Represents the current state of notification permission.
     */
    sealed class PermissionState {
        /** Permission is granted - notifications will work */
        object Granted : PermissionState()

        /** Pre-Android 13 - no runtime permission needed */
        object NotRequired : PermissionState()

        /** First time requesting - show system dialog directly */
        object NotYetRequested : PermissionState()

        /** User denied once - show rationale before asking again */
        object ShouldShowRationale : PermissionState()

        /** User denied 2+ times or checked "Don't ask again" - direct to Settings */
        object PermanentlyDenied : PermissionState()
    }

    companion object {
        /** Number of denials before considering permission "permanently denied" */
        private const val PERMANENTLY_DENIED_THRESHOLD = 2
    }

    /**
     * Check current permission state.
     *
     * @param activity The activity context (needed for shouldShowRequestPermissionRationale)
     * @return Current [PermissionState]
     */
    suspend fun checkPermissionState(activity: Activity): PermissionState {
        // Pre-Android 13 (Tiramisu) doesn't need POST_NOTIFICATIONS permission
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return PermissionState.NotRequired
        }

        // Check if permission is already granted
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            return PermissionState.Granted
        }

        // Check if we should show rationale (user denied once but didn't check "Don't ask again")
        val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val denialCount = userPreferences.getNotificationPermissionDeniedCount()

        return when {
            shouldShowRationale -> PermissionState.ShouldShowRationale

            // If denial count >= threshold and no rationale should be shown,
            // user likely checked "Don't ask again"
            denialCount >= PERMANENTLY_DENIED_THRESHOLD -> PermissionState.PermanentlyDenied

            else -> PermissionState.NotYetRequested
        }
    }

    /**
     * Check if notifications are enabled at the system level.
     *
     * This catches cases where user granted permission but then
     * disabled notifications in system settings.
     *
     * @return true if notifications are enabled
     */
    fun areNotificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Called when permission is denied to track denial count.
     * Should be called from the permission result callback.
     */
    suspend fun onPermissionDenied() {
        userPreferences.incrementNotificationPermissionDeniedCount()
    }

    /**
     * Called when permission is granted to reset denial count.
     * Should be called from the permission result callback.
     */
    suspend fun onPermissionGranted() {
        userPreferences.resetNotificationPermissionDeniedCount()
    }

    /**
     * Check if permission is required for Android version.
     * Convenience method for pre-checks.
     */
    fun isPermissionRequired(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    /**
     * Check if permission is currently granted.
     * Convenience method that doesn't require Activity context.
     */
    fun isPermissionGranted(): Boolean {
        if (!isPermissionRequired()) return true

        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
