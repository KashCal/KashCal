package org.onekash.kashcal.ui.screens.settings

import android.content.res.Resources
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.onekash.kashcal.R

/** Holds fixed-hue colors for UI feedback indicators. */
object AccentColors {
    // Success stays green whatever the app accent or dynamic color: a success checkmark must
    // never track a user-chosen accent hue. Only the shade adapts, since a slightly brighter
    // green reads better on dark surfaces.
    val SuccessLight = Color(0xFF34C759)
    val SuccessDark = Color(0xFF30D158)

    /**
     * Success green, shade-selected against the resolved theme surface (honors forced dark mode).
     */
    val Green: Color
        @Composable get() =
            if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) SuccessDark else SuccessLight
}

/**
 * Holds a five-color palette. `ContactEventType` takes its default birthday and anniversary
 * colors from it; new ICS subscriptions get `EventColorPalette.randomArgb()` instead.
 */
object SubscriptionColors {
    val Blue = 0xFF2196F3.toInt()
    val Green = 0xFF4CAF50.toInt()
    val Orange = 0xFFFF9800.toInt()
    val Pink = 0xFFE91E63.toInt()
    val Purple = 0xFF9C27B0.toInt()

    /** The five colors, which fit in one row. */
    val all = listOf(Blue, Green, Orange, Pink, Purple)

    val default = Blue
}

/** A sync interval choice for subscription calendars. */
data class SyncIntervalOption(
    val hours: Int
)

/** The sync intervals the edit-subscription dialog offers, in hours. */
val subscriptionSyncIntervalOptions = listOf(
    SyncIntervalOption(1),
    SyncIntervalOption(6),
    SyncIntervalOption(12),
    SyncIntervalOption(24),
    SyncIntervalOption(168)
)

/** Returns the localized label for a sync interval of [hours]. */
fun getSyncIntervalLabel(hours: Int, resources: Resources): String {
    return when (hours) {
        1 -> resources.getString(R.string.ics_sync_every_hour)
        24 -> resources.getString(R.string.ics_sync_daily)
        168 -> resources.getString(R.string.ics_sync_weekly)
        else -> resources.getString(R.string.ics_sync_every_n_hours, hours)
    }
}

/**
 * Returns a localized error for a blank URL or one not starting with http://, https:// or
 * webcal://, else null.
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

/** Trims [url] and rewrites a leading webcal:// or webcals:// to https:// for the HTTP client. */
fun normalizeSubscriptionUrl(url: String): String {
    val trimmed = url.trim()
    // Only the leading scheme, so a webcal:// inside a query param (e.g. ?redirect=webcal://…)
    // stays intact.
    return when {
        trimmed.startsWith("webcal://") -> "https://" + trimmed.removePrefix("webcal://")
        trimmed.startsWith("webcals://") -> "https://" + trimmed.removePrefix("webcals://")
        else -> trimmed
    }
}
