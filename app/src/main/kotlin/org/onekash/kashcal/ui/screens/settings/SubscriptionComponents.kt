package org.onekash.kashcal.ui.screens.settings

import android.content.res.Resources
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.onekash.kashcal.R

/**
 * Accent colors for UI feedback indicators (success, info, etc.).
 */
object AccentColors {
    // Success semantics stay fixed-hue (green) regardless of the app accent or dynamic color —
    // a "success" checkmark must never track a user-chosen accent hue. Only the shade adapts to
    // the surface: a slightly brighter green reads better against dark surfaces.
    val SuccessLight = Color(0xFF34C759)
    val SuccessDark = Color(0xFF30D158)

    /** Success green, shade-selected against the resolved theme surface (honors forced dark mode). */
    val Green: Color
        @Composable get() =
            if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) SuccessDark else SuccessLight
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
    val hours: Int
)

/**
 * Available sync interval options for subscription calendars.
 */
val subscriptionSyncIntervalOptions = listOf(
    SyncIntervalOption(1),
    SyncIntervalOption(6),
    SyncIntervalOption(12),
    SyncIntervalOption(24),
    SyncIntervalOption(168)
)

/**
 * Get the localized display label for a sync interval.
 *
 * @param hours Sync interval in hours
 * @return Human-readable, localized label
 */
fun getSyncIntervalLabel(hours: Int, resources: Resources): String {
    return when (hours) {
        1 -> resources.getString(R.string.ics_sync_every_hour)
        24 -> resources.getString(R.string.ics_sync_daily)
        168 -> resources.getString(R.string.ics_sync_weekly)
        else -> resources.getString(R.string.ics_sync_every_n_hours, hours)
    }
}

/**
 * Validate an ICS subscription URL, returning a localized error message if invalid.
 *
 * @param url URL to validate
 * @param resources for resolving the localized error message
 * @return Localized error message if invalid, null if valid
 */
fun validateSubscriptionUrl(url: String, resources: Resources): String? {
    val trimmed = url.trim()
    return when {
        trimmed.isBlank() -> resources.getString(R.string.ics_url_required)
        !trimmed.startsWith("http://") && !trimmed.startsWith("https://") &&
            !trimmed.startsWith("webcal://") -> resources.getString(R.string.ics_url_invalid_scheme)
        !trimmed.endsWith(".ics") && !trimmed.contains("calendar") && !trimmed.contains("ical") ->
            null
        else -> null
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
    // Rewrite only the leading scheme, not every occurrence, so a webcal://
    // literal inside a query param (e.g. ?redirect=webcal://…) is left intact.
    return when {
        trimmed.startsWith("webcal://") -> "https://" + trimmed.removePrefix("webcal://")
        trimmed.startsWith("webcals://") -> "https://" + trimmed.removePrefix("webcals://")
        else -> trimmed
    }
}
