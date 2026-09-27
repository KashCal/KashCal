package org.onekash.kashcal.sync.discovery

import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult

/** Outcome of discovering an account's calendars. */
sealed class DiscoveryResult {
    /** The account and its calendars were created or refreshed in Room. */
    data class Success(
        val account: Account,
        val calendars: List<Calendar>
    ) : DiscoveryResult()

    /**
     * Calendars were found but no account is created yet: the user picks which calendars to
     * sync first.
     */
    data class CalendarsFound(
        val serverUrl: String,
        val username: String,
        val calendarHomeUrl: String,
        val principalUrl: String,
        val calendars: List<DiscoveredCalendar>
    ) : DiscoveryResult()

    /** The server rejected the credentials, or none are stored. */
    data class AuthError(val message: String) : DiscoveryResult()

    /**
     * Discovery failed for any other reason: network, server, or missing account data.
     *
     * @property reason a failure the UI explains with its own message, or null.
     */
    data class Error(
        val message: String,
        val reason: DiscoveryErrorReason? = null
    ) : DiscoveryResult()
}

/** Discovery failures the UI shows with a dedicated, translated message. */
enum class DiscoveryErrorReason {
    /**
     * KashCal refused a connection the server asked for: a redirect from https to
     * plain http (other than on the same host), plain http on an https account, or
     * a redirect loop. The password was not sent.
     */
    INSECURE_CONNECTION_REFUSED,
}

/**
 * The discovery client for one run, recording whether its latest discovery request
 * (well-known, principal, calendar home, calendar listing) was a connection KashCal
 * refused. A failure of the run then says so ([error]) only when the step that failed
 * was refused, not when an earlier refusal was followed by a fallback that worked.
 * Wrapping the client, not each call, also covers the fallback probes.
 */
internal class RefusalRecordingClient(private val client: CalDavClient) : CalDavClient by client {
    var lastCallRefused: Boolean = false
        private set

    private fun <T> note(result: CalDavResult<T>): CalDavResult<T> {
        lastCallRefused = result is CalDavResult.Error && result.code == CalDavResult.CODE_TRANSPORT_REFUSED
        return result
    }

    override suspend fun discoverWellKnown(serverUrl: String) = note(client.discoverWellKnown(serverUrl))
    override suspend fun discoverPrincipal(serverUrl: String) = note(client.discoverPrincipal(serverUrl))
    override suspend fun discoverCalendarHome(principalUrl: String) = note(client.discoverCalendarHome(principalUrl))
    override suspend fun listCalendars(calendarHomeUrl: String) = note(client.listCalendars(calendarHomeUrl))

    /** Returns a failure with [message], marked refused only if the failing step was refused. */
    fun error(message: String): DiscoveryResult.Error =
        DiscoveryResult.Error(message, reason = if (lastCallRefused) DiscoveryErrorReason.INSECURE_CONNECTION_REFUSED else null)
}

/** A calendar found on the server before its Room row exists ([DiscoveryResult.CalendarsFound]). */
data class DiscoveredCalendar(
    val href: String,
    val displayName: String,
    val color: Int,
    val supportsEvents: Boolean = true,
    val isReadOnly: Boolean = false
)

/**
 * Discovers an account's calendars and creates them in Room.
 *
 * Only the iCloud service implements this interface; the generic CalDAV service has its own
 * methods of the same shape. Discovery runs:
 * 1. Discover the principal URL, which also validates the credentials.
 * 2. Discover the calendar home URLs.
 * 3. List the calendars in every home.
 * 4. Create the Account and Calendar rows.
 */
interface AccountDiscoveryService {
    /** Provider identifier, e.g. "icloud". */
    val providerId: String

    /**
     * Discovers the account and creates it with all its calendars.
     *
     * @param password the password or app-specific password.
     */
    suspend fun discoverAndCreateAccount(
        username: String,
        password: String
    ): DiscoveryResult

    /**
     * Refreshes an existing account's calendar list: adds new calendars, updates existing ones,
     * and removes the ones the server confirms are gone ([confirmUnlistedCalendars]).
     */
    suspend fun refreshCalendars(accountId: Long): DiscoveryResult

    /** Removes all data for an account on sign-out. */
    suspend fun removeAccount(accountId: Long)

    /** Removes the account with [email], for a sign-out that only knows the email. */
    suspend fun removeAccountByEmail(email: String)
}
