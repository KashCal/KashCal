package org.onekash.kashcal.ui.screens.settings

import androidx.compose.ui.graphics.Color

/**
 * Accent colors for UI feedback indicators (success, info, etc.).
 */
object AccentColors {
    val Green = Color(0xFF34C759)
    val Blue = Color(0xFF007AFF)
    val iCloudBlue = Color(0xFF5AC8FA)
}

/**
 * Standard subscription calendar colors.
 * Provides a consistent color palette for ICS subscription calendars.
 */
object SubscriptionColors {
    val Blue = 0xFF2196F3.toInt()
    val Green = 0xFF4CAF50.toInt()
    val Orange = 0xFFFF9800.toInt()
    val Pink = 0xFFE91E63.toInt()
    val Purple = 0xFF9C27B0.toInt()

    /**
     * List of all available subscription colors (5 colors, fits in one row).
     * Used in color picker dialogs.
     */
    val all = listOf(Blue, Green, Orange, Pink, Purple)

    /**
     * Default color for new subscriptions.
     */
    val default = Blue
}

/**
 * Sync interval option for subscription calendars.
 */
data class SyncIntervalOption(
    val label: String,
    val hours: Int
)

/**
 * Available sync interval options for subscription calendars.
 */
val subscriptionSyncIntervalOptions = listOf(
    SyncIntervalOption("Every hour", 1),
    SyncIntervalOption("Every 6 hours", 6),
    SyncIntervalOption("Every 12 hours", 12),
    SyncIntervalOption("Daily", 24),
    SyncIntervalOption("Weekly", 168)
)

/**
 * Get display label for a sync interval.
 *
 * @param hours Sync interval in hours
 * @return Human-readable label
 */
fun getSyncIntervalLabel(hours: Int): String {
    return subscriptionSyncIntervalOptions.find { it.hours == hours }?.label
        ?: "Every $hours hours"
}

/**
 * Validate an ICS subscription URL.
 *
 * @param url URL to validate
 * @return Error message if invalid, null if valid
 */
fun validateSubscriptionUrl(url: String): String? {
    val trimmed = url.trim()
    return when {
        trimmed.isBlank() -> "URL is required"
        !trimmed.startsWith("http://") && !trimmed.startsWith("https://") &&
            !trimmed.startsWith("webcal://") -> "URL must start with http://, https://, or webcal://"
        !trimmed.endsWith(".ics") && !trimmed.contains("calendar") && !trimmed.contains("ical") ->
            null // Could be valid, just unusual
        else -> null // Valid
    }
}

/**
 * Normalize a subscription URL.
 * Converts webcal:// to https:// for HTTP requests.
 *
 * @param url Original URL
 * @return Normalized URL for HTTP client
 */
fun normalizeSubscriptionUrl(url: String): String {
    val trimmed = url.trim()
    return when {
        trimmed.startsWith("webcal://") -> trimmed.replace("webcal://", "https://")
        trimmed.startsWith("webcals://") -> trimmed.replace("webcals://", "https://")
        else -> trimmed
    }
}
