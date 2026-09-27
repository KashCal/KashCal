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
 * Reads the notification permission state and tracks denials for the request flow.
 *
 * Follows the Android guidance
 * (developer.android.com/develop/ui/views/notifications/notification-permission):
 * - Request after a meaningful user action (saving an event with a reminder).
 * - Show a rationale when shouldShowRequestPermissionRationale() returns true.
 * - Count denials to detect the permanently denied state.
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

    /** Names the notification permission state and what the caller does in it. */
    sealed class PermissionState {
        /** Granted: notifications work. */
        object Granted : PermissionState()

        /** Below Android 13: no runtime permission needed. */
        object NotRequired : PermissionState()

        /** No rationale due and fewer than 2 recorded denials: show the system dialog directly. */
        object NotYetRequested : PermissionState()

        /** The system reports a rationale is due: show it before asking again. */
        object ShouldShowRationale : PermissionState()

        /** At least 2 recorded denials and no rationale due: proceed without asking. */
        object PermanentlyDenied : PermissionState()
    }

    companion object {
        /** Recorded denials at which a no-rationale state counts as permanently denied. */
        private const val PERMANENTLY_DENIED_THRESHOLD = 2
    }

    /**
     * Returns the current [PermissionState].
     *
     * @param activity needed for shouldShowRequestPermissionRationale
     */
    suspend fun checkPermissionState(activity: Activity): PermissionState {
        // Below Android 13 (Tiramisu) POST_NOTIFICATIONS isn't a runtime permission
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return PermissionState.NotRequired
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            return PermissionState.Granted
        }

        // True after a denial without "Don't ask again"
        val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val denialCount = userPreferences.getNotificationPermissionDeniedCount()

        return when {
            shouldShowRationale -> PermissionState.ShouldShowRationale

            // At the threshold with no rationale, the user likely chose "Don't ask again"
            denialCount >= PERMANENTLY_DENIED_THRESHOLD -> PermissionState.PermanentlyDenied

            else -> PermissionState.NotYetRequested
        }
    }

    /**
     * Returns whether notifications are enabled at the system level, which is false when the user
     * granted the permission but then turned notifications off in system settings.
     */
    fun areNotificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Records a denial. Called from the permission result callback and when the user declines
     * the rationale dialog.
     */
    suspend fun onPermissionDenied() {
        userPreferences.incrementNotificationPermissionDeniedCount()
    }

    /** Resets the denial count. Called from the permission result callback on a grant. */
    suspend fun onPermissionGranted() {
        userPreferences.resetNotificationPermissionDeniedCount()
    }

    /** Returns true on Android 13 and later, where POST_NOTIFICATIONS is a runtime permission. */
    fun isPermissionRequired(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    /** Returns whether the permission is granted now, without needing an Activity. */
    fun isPermissionGranted(): Boolean {
        if (!isPermissionRequired()) return true

        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
