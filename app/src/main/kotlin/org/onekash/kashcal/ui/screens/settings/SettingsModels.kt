package org.onekash.kashcal.ui.screens.settings

import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.network.AiaCertificateChainCompleter
import org.onekash.kashcal.ui.util.UiMessage
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/**
 * iCloud connection state - tracks sign-in flow.
 *
 * Used by AccountSettingsViewModel and AccountSettingsScreen to manage
 * the iCloud connection UI state.
 */
@Immutable
sealed class ICloudConnectionState {
    /**
     * Not connected to iCloud.
     * Shows sign-in form with email/password fields.
     */
    data class NotConnected(
        val appleId: String = "",
        val password: String = "",
        val showHelp: Boolean = false,
        val error: UiMessage? = null
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
        val accountId: Long,
        val appleId: String,
        val lastSyncTime: Long? = null,
        val calendarCount: Int = 0,
        val consecutiveSyncFailures: Int = 0
    ) : ICloudConnectionState()
}

/**
 * iCloud account UI model for AccountsScreen display.
 *
 * Provides a simplified view of the connected iCloud account
 * for the accounts list, separate from the full ICloudConnectionState.
 */
@Immutable
data class ICloudAccountUiModel(
    /** Account database ID */
    val accountId: Long,
    /** User's Apple ID (email) */
    val email: String,
    /** Number of calendars synced from this account */
    val calendarCount: Int,
    /** Number of consecutive sync failures (0 = healthy) */
    val consecutiveSyncFailures: Int = 0,
    /** Timestamp of last successful sync (null = never synced) */
    val lastSuccessfulSyncAt: Long? = null
)

/**
 * ICS Calendar subscription UI model.
 *
 * IMPORTANT: This is the UI model, NOT the database entity.
 * The database entity is at data/db/entity/IcsSubscription.kt
 *
 * Renamed from `IcsSubscription` to `IcsSubscriptionUiModel` to avoid
 * confusion with the database entity class.
 */
@Immutable
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
@Immutable
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

        val response = try {
            client.newCall(request).execute()
        } catch (e: SSLHandshakeException) {
            // Attempt AIA certificate chain completion (same as IcsFetcher)
            Log.w("fetchCalendarInfo", "SSL failed, attempting AIA chain completion: ${e.message}")
            val parsedUrl = URL(url)
            val completer = AiaCertificateChainCompleter()
            val aiaResult = completer.attemptChainCompletion(
                hostname = parsedUrl.host,
                port = if (parsedUrl.port > 0) parsedUrl.port else 443,
                baseClientBuilder = client.newBuilder()
            )
            when (aiaResult) {
                is AiaCertificateChainCompleter.Result.Success -> {
                    try {
                        aiaResult.client.newCall(request).execute()
                    } catch (retryEx: Exception) {
                        Log.e("fetchCalendarInfo", "Retry after AIA failed: ${retryEx.message}", retryEx)
                        return@withContext FetchCalendarState.Error("SSL error: ${e.message}")
                    }
                }
                is AiaCertificateChainCompleter.Result.Failed -> {
                    return@withContext FetchCalendarState.Error("SSL error: ${e.message}")
                }
            }
        }

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

        // Parse only the VCALENDAR preamble (cheap) to get the human-readable
        // name. Counting VEVENTs via regex avoids a full parse on large feeds
        // (subscriptions can be tens of MB with tens of thousands of events).
        val preamble = content.substringBefore("BEGIN:VEVENT")
            .substringBefore("BEGIN:VTODO")
            .substringBefore("BEGIN:VJOURNAL") + "END:VCALENDAR"
        val parsed = ICalParser().parse(preamble).getOrNull()
        val name = parsed?.effectiveName?.takeIf { it.isNotBlank() } ?: "Calendar"
        val eventCount = Regex("BEGIN:VEVENT").findAll(content).count()

        FetchCalendarState.Success(name, eventCount)
    } catch (e: Exception) {
        FetchCalendarState.Error(e.message ?: e.javaClass.simpleName)
    }
}

