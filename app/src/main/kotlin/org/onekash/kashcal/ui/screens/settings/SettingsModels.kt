package org.onekash.kashcal.ui.screens.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * iCloud connection state - tracks sign-in flow.
 *
 * Used by AccountSettingsViewModel and AccountSettingsScreen to manage
 * the iCloud connection UI state.
 */
sealed class ICloudConnectionState {
    /**
     * Not connected to iCloud.
     * Shows sign-in form with email/password fields.
     */
    data class NotConnected(
        val appleId: String = "",
        val password: String = "",
        val showHelp: Boolean = false,
        val error: String? = null
    ) : ICloudConnectionState()

    /**
     * Currently connecting to iCloud.
     * Shows loading indicator.
     */
    data object Connecting : ICloudConnectionState()

    /**
     * Successfully connected to iCloud.
     * Shows account info and sync status.
     */
    data class Connected(
        val appleId: String,
        val lastSyncTime: Long? = null,
        val calendarCount: Int = 0
    ) : ICloudConnectionState()
}

/**
 * ICS Calendar subscription UI model.
 *
 * IMPORTANT: This is the UI model, NOT the database entity.
 * The database entity is at data/db/entity/IcsSubscription.kt
 *
 * Renamed from `IcsSubscription` to `IcsSubscriptionUiModel` to avoid
 * confusion with the database entity class.
 */
data class IcsSubscriptionUiModel(
    /** Database ID (null for new subscriptions not yet saved) */
    val id: Long?,
    /** ICS feed URL */
    val url: String,
    /** Display name for the subscription */
    val name: String,
    /** Calendar color as ARGB integer */
    val color: Int,
    /** Whether sync is enabled for this subscription */
    val enabled: Boolean = true,
    /** Last sync timestamp (0 = never synced) */
    val lastSync: Long = 0,
    /** Associated calendar ID for event storage */
    val eventTypeId: Long? = null,
    /** Error message from last sync attempt (null = no error) */
    val lastError: String? = null,
    /** Sync interval in hours (default 24) */
    val syncIntervalHours: Int = 24
) {
    /**
     * Check if last sync resulted in an error.
     */
    fun hasError(): Boolean = !lastError.isNullOrBlank()
}

/**
 * State for fetching and validating ICS calendar URLs.
 * Used in the Add Subscription dialog.
 */
sealed class FetchCalendarState {
    /** Initial state - no fetch in progress */
    data object Idle : FetchCalendarState()

    /** Currently fetching and validating the URL */
    data object Loading : FetchCalendarState()

    /** Successfully fetched calendar info */
    data class Success(val name: String, val eventCount: Int) : FetchCalendarState()

    /** Fetch failed with error */
    data class Error(val message: String) : FetchCalendarState()
}

/**
 * Fetch and validate an ICS calendar URL.
 * Returns calendar name and event count on success, or error message on failure.
 *
 * @param url The ICS feed URL to fetch
 * @return FetchCalendarState with either Success or Error
 */
suspend fun fetchCalendarInfo(url: String): FetchCalendarState = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/calendar")
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            return@withContext FetchCalendarState.Error("HTTP ${response.code}: ${response.message}")
        }

        val content = response.body?.string()
        if (content.isNullOrBlank()) {
            return@withContext FetchCalendarState.Error("Empty response")
        }

        if (!content.contains("BEGIN:VCALENDAR")) {
            return@withContext FetchCalendarState.Error("Invalid ICS format")
        }

        // Extract calendar name from X-WR-CALNAME property
        val nameRegex = Regex("""X-WR-CALNAME[^:]*:(.+)""")
        val nameMatch = nameRegex.find(content)
        val name = nameMatch?.groupValues?.get(1)?.trim() ?: "Calendar"
        val eventCount = Regex("BEGIN:VEVENT").findAll(content).count()

        FetchCalendarState.Success(name, eventCount)
    } catch (e: Exception) {
        FetchCalendarState.Error(e.message ?: "Unknown error")
    }
}

