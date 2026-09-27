package org.onekash.kashcal.ui.screens.settings

import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.R
import org.onekash.kashcal.network.AiaCertificateChainCompleter
import org.onekash.kashcal.network.readBoundedBody
import org.onekash.kashcal.ui.util.UiMessage
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/** Tracks the iCloud sign-in flow for the account settings UI. */
@Immutable
sealed class ICloudConnectionState {
    /** Not connected: the sign-in form's Apple ID, password, help toggle and error. */
    data class NotConnected(
        val appleId: String = "",
        val password: String = "",
        val showHelp: Boolean = false,
        val error: UiMessage? = null
    ) : ICloudConnectionState()

    /** Sign-in in progress. */
    data object Connecting : ICloudConnectionState()

    /** Connected: the account and its sync status. */
    data class Connected(
        val accountId: Long,
        val appleId: String,
        val lastSyncTime: Long? = null,
        val calendarCount: Int = 0,
        val consecutiveSyncFailures: Int = 0
    ) : ICloudConnectionState()
}

/**
 * Describes the connected iCloud account for the accounts list, derived from
 * [ICloudConnectionState.Connected].
 */
@Immutable
data class ICloudAccountUiModel(
    val accountId: Long,
    /** The Apple ID (email). */
    val email: String,
    val calendarCount: Int,
    /** 0 when healthy. */
    val consecutiveSyncFailures: Int = 0,
    /** Null when the account never synced. */
    val lastSuccessfulSyncAt: Long? = null
)

/**
 * Describes an ICS subscription for the UI. The Room entity is
 * [org.onekash.kashcal.data.db.entity.IcsSubscription].
 */
@Immutable
data class IcsSubscriptionUiModel(
    /** Null for a subscription not yet saved. */
    val id: Long?,
    val url: String,
    val name: String,
    /** ARGB. */
    val color: Int,
    val enabled: Boolean = true,
    /** 0 when never synced. */
    val lastSync: Long = 0,
    /** The Room calendar holding the subscription's events. */
    val eventTypeId: Long? = null,
    /** The last sync attempt's error, or null. */
    val lastError: String? = null,
    val syncIntervalHours: Int = 24
) {
    /** Returns true when the last sync left a non-blank error. */
    fun hasError(): Boolean = !lastError.isNullOrBlank()
}

/**
 * Holds the result of validating an ICS feed with [fetchCalendarInfo], for the add-subscription
 * dialog and [HolidayCatalogPicker].
 */
@Immutable
sealed class FetchCalendarState {
    data object Idle : FetchCalendarState()

    data object Loading : FetchCalendarState()

    /** The feed's calendar name (blank when it has none) and VEVENT count. */
    data class Success(val name: String, val eventCount: Int) : FetchCalendarState()

    /**
     * The fetch failed; [message] is a [UiMessage] so the UI localizes it.
     *
     * @property connectionFailed true when the fetch threw an exception [fetchCalendarInfo]
     *   doesn't map to a message: a connect timeout, a refused connection or an unknown host,
     *   but also a malformed URL, an oversize body or an SSL error other than a handshake
     *   failure. An HTTP error, a handshake failure, an empty body or non-calendar content
     *   leaves it false. Only a true value can arm the Android 17 local-network hint
     *   ([resolveSubscriptionLanUi]).
     */
    data class Error(
        val message: UiMessage,
        val connectionFailed: Boolean = false,
    ) : FetchCalendarState()
}

/**
 * Fetches and validates an ICS feed, returning [FetchCalendarState.Success] or
 * [FetchCalendarState.Error]. Every exception the fetch throws becomes an Error.
 *
 * @param rawUrl the feed URL; webcal:// and webcals:// are rewritten to https://.
 */
suspend fun fetchCalendarInfo(rawUrl: String): FetchCalendarState = withContext(Dispatchers.IO) {
    // OkHttp speaks only http and https and throws on any other scheme. The field can be
    // pre-filled from a webcal:// link, and the save path stores the same rewrite.
    val url = normalizeSubscriptionUrl(rawUrl)
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
            // Try AIA certificate chain completion, as IcsFetcher does.
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
                        return@withContext FetchCalendarState.Error(
                            UiMessage.ResId(R.string.error_network_ssl))
                    }
                }
                is AiaCertificateChainCompleter.Result.Failed -> {
                    return@withContext FetchCalendarState.Error(
                        UiMessage.ResId(R.string.error_network_ssl))
                }
            }
        }

        if (!response.isSuccessful) {
            return@withContext FetchCalendarState.Error(
                UiMessage.ResId(R.string.ics_fetch_error_http, listOf(response.code)))
        }

        val content = response.readBoundedBody()
        if (content.isBlank()) {
            return@withContext FetchCalendarState.Error(
                UiMessage.ResId(R.string.ics_fetch_error_empty))
        }

        if (!content.contains("BEGIN:VCALENDAR")) {
            return@withContext FetchCalendarState.Error(
                UiMessage.ResId(R.string.ics_fetch_error_not_calendar))
        }

        // Parse only the VCALENDAR preamble for the name, and count VEVENTs with a regex: a full
        // parse is slow on large feeds (tens of MB, tens of thousands of events).
        val preamble = content.substringBefore("BEGIN:VEVENT")
            .substringBefore("BEGIN:VTODO")
            .substringBefore("BEGIN:VJOURNAL") + "END:VCALENDAR"
        val parsed = ICalParser().parse(preamble).getOrNull()
        val name = parsed?.effectiveName?.takeIf { it.isNotBlank() }.orEmpty()
        val eventCount = Regex("BEGIN:VEVENT").findAll(content).count()

        FetchCalendarState.Success(name, eventCount)
    } catch (e: Exception) {
        // Show the exception's text as a Literal, since it isn't an app resource. Every exception
        // here sets connectionFailed: a blocked local-network socket, the case the flag is for,
        // but also a malformed URL or an oversize body.
        FetchCalendarState.Error(
            UiMessage.Literal(e.message ?: e.javaClass.simpleName),
            connectionFailed = true,
        )
    }
}

