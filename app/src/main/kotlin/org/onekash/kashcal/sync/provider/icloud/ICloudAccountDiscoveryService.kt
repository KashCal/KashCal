package org.onekash.kashcal.sync.provider.icloud

import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onekash.kashcal.data.credential.AccountCredentials
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavCalendar
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.discovery.AccountDiscoveryService
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.onekash.kashcal.sync.discovery.RefusalRecordingClient
import org.onekash.kashcal.sync.discovery.confirmUnlistedCalendars
import org.onekash.kashcal.sync.discovery.persistCalendarUserAddresses
import org.onekash.kashcal.sync.discovery.persistSchedulingDiscovery
import org.onekash.kashcal.sync.parser.ServerColorParser
import org.onekash.kashcal.util.maskEmail
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLHandshakeException

/**
 * Discovers and sets up iCloud accounts against the fixed server https://caldav.icloud.com.
 *
 * Principal, home set and calendar URLs are stored in canonical form ([ICloudUrlNormalizer]),
 * since iCloud answers with regional partition hosts. One account per Apple ID: an existing
 * account with the same email is updated, not duplicated.
 */
@Singleton
class ICloudAccountDiscoveryService @Inject constructor(
    private val clientFactory: CalDavClientFactory,
    private val credentialProvider: ICloudCredentialProvider,
    private val icloudQuirks: ICloudQuirks,
    private val accountRepository: AccountRepository,
    private val calendarRepository: CalendarRepository
) : AccountDiscoveryService {
    companion object {
        private const val TAG = "ICloudAccountDiscovery"
        private const val ICLOUD_SERVER = "https://caldav.icloud.com"
        private const val PROVIDER_ICLOUD = "icloud"

        // Calendar colors used when the server sends none or one that doesn't parse.
        private val DEFAULT_COLORS = listOf(
            0xFF4CAF50.toInt(), // Green
            0xFF2196F3.toInt(), // Blue
            0xFFFF9800.toInt(), // Orange
            0xFF9C27B0.toInt(), // Purple
            0xFFE91E63.toInt(), // Pink
            0xFF00BCD4.toInt(), // Cyan
            0xFFFF5722.toInt(), // Deep Orange
            0xFF3F51B5.toInt()  // Indigo
        )
    }

    override val providerId: String = PROVIDER_ICLOUD

    /**
     * Discovers an iCloud account and creates it with all its calendars.
     *
     * @param username Apple ID email
     * @param password app-specific password from the Apple ID settings
     */
    override suspend fun discoverAndCreateAccount(
        username: String,
        password: String
    ): DiscoveryResult = withContext(Dispatchers.IO) {
        val appleId = username
        val appSpecificPassword = password
        Log.i(TAG, "Starting discovery for: ${appleId.take(3)}***")

        // Each run builds its own client, so no mutable client state is shared across runs.
        val credentials = Credentials(
            username = appleId,
            password = appSpecificPassword,
            serverUrl = ICLOUD_SERVER
        )
        val client = RefusalRecordingClient(clientFactory.createClient(credentials, icloudQuirks))

        try {
            // Step 1: Discover principal URL (this validates credentials)
            Log.d(TAG, "Step 1: Discovering principal...")
            val principalResult = client.discoverPrincipal(ICLOUD_SERVER)

            if (principalResult.isAuthError()) {
                Log.e(TAG, "Authentication failed")
                return@withContext DiscoveryResult.AuthError(
                    "Invalid Apple ID or app-specific password. Please check your credentials."
                )
            }

            if (principalResult.isError()) {
                val error = principalResult as CalDavResult.Error
                Log.e(TAG, "Principal discovery failed: ${error.message}")
                return@withContext client.error(
                    getErrorMessageForCalDavError(error, "connect to iCloud")
                )
            }

            val principalUrl = ICloudUrlNormalizer.normalize(
                (principalResult as CalDavResult.Success).data
            ) ?: (principalResult as CalDavResult.Success).data
            Log.d(TAG, "Principal URL: $principalUrl")

            // Step 2: Discover calendar home
            Log.d(TAG, "Step 2: Discovering calendar home...")
            val homeResult = client.discoverCalendarHome(principalUrl)

            if (homeResult.isError()) {
                val error = homeResult as CalDavResult.Error
                Log.e(TAG, "Calendar home discovery failed: ${error.message}")
                return@withContext client.error(
                    getErrorMessageForCalDavError(error, "find calendar home")
                )
            }

            val calendarHomeUrls = (homeResult as CalDavResult.Success).data.map { url ->
                ICloudUrlNormalizer.normalize(url) ?: url
            }.sorted()
            val calendarHomeUrl = calendarHomeUrls.first()
            Log.d(TAG, "Calendar home URLs: $calendarHomeUrls")

            // Step 3: List calendars from all home sets
            Log.d(TAG, "Step 3: Listing calendars from ${calendarHomeUrls.size} home set(s)...")
            val allCalendars = mutableListOf<CalDavCalendar>()
            val seenUrls = mutableSetOf<String>()
            for (homeUrl in calendarHomeUrls) {
                val calendarsResult = client.listCalendars(homeUrl)
                if (calendarsResult.isSuccess()) {
                    for (cal in (calendarsResult as CalDavResult.Success).data) {
                        if (seenUrls.add(cal.url)) allCalendars.add(cal)
                    }
                } else {
                    Log.w(TAG, "Failed to list calendars from home set $homeUrl: ${(calendarsResult as CalDavResult.Error).message}")
                }
            }

            val discoveredCalendars = allCalendars
            Log.i(TAG, "Discovered ${discoveredCalendars.size} calendars across ${calendarHomeUrls.size} home set(s)")

            if (discoveredCalendars.isEmpty()) {
                return@withContext client.error(
                    "No calendars found on iCloud. Please check your account settings."
                )
            }

            // Step 4: Create or update Account in Room
            val existingAccount = accountRepository.getAccountByProviderAndEmail(AccountProvider.ICLOUD, appleId)
            val account = if (existingAccount != null) {
                Log.d(TAG, "Updating existing account: ${existingAccount.id}")
                val updated = existingAccount.copy(
                    principalUrl = principalUrl,
                    homeSetUrl = calendarHomeUrl,
                    isEnabled = true
                )
                accountRepository.updateAccount(updated)
                updated
            } else {
                Log.d(TAG, "Creating new account")
                val newAccount = Account(
                    provider = AccountProvider.ICLOUD,
                    email = appleId,
                    displayName = "iCloud",
                    principalUrl = principalUrl,
                    homeSetUrl = calendarHomeUrl,
                    isEnabled = true
                )
                val accountId = accountRepository.createAccount(newAccount)
                newAccount.copy(id = accountId)
            }

            // Step 4b: Save credentials to encrypted storage. Without them the account can't
            // sync, so a failed save deletes it.
            val accountCredentials = AccountCredentials(
                username = appleId,
                password = appSpecificPassword,
                serverUrl = AccountCredentials.ICLOUD_DEFAULT_SERVER_URL,
                principalUrl = principalUrl,
                calendarHomeSet = calendarHomeUrl
            )
            val credentialsSaved = accountRepository.saveCredentials(account.id, accountCredentials)
            if (!credentialsSaved) {
                Log.e(TAG, "Failed to save credentials - secure storage unavailable")
                accountRepository.deleteAccount(account.id)
                return@withContext DiscoveryResult.Error(
                    "Could not save credentials securely. Please try again, or restart your device if the problem persists."
                )
            }

            // Step 4c: Discover and persist the calendar-user-address-set (RFC 6638 §2.4.1).
            // Failures are non-fatal.
            persistCalendarUserAddresses(client, principalUrl, account.id, accountRepository, TAG)

            // Step 5: Create Calendar entities for each discovered calendar
            val createdCalendars = mutableListOf<Calendar>()
            var isFirst = true

            for ((index, calDavCalendar) in discoveredCalendars.withIndex()) {
                // Canonical form for both storage and lookup, or the lookup misses.
                val normalizedCalendarUrl = ICloudUrlNormalizer.normalize(calDavCalendar.url)
                    ?: calDavCalendar.url

                val existingCalendar = calendarRepository.getCalendarByUrl(normalizedCalendarUrl)
                val calendar = if (existingCalendar != null) {
                    Log.d(TAG, "Updating calendar: ${calDavCalendar.displayName}")
                    val updated = existingCalendar.copy(
                        displayName = calDavCalendar.displayName,
                        color = parseColor(calDavCalendar.color, index),
                        isReadOnly = calDavCalendar.isReadOnly
                    )
                    calendarRepository.updateCalendar(updated)
                    updated
                } else {
                    Log.d(TAG, "Creating calendar: ${calDavCalendar.displayName}")
                    val newCalendar = Calendar(
                        accountId = account.id,
                        caldavUrl = normalizedCalendarUrl,
                        displayName = calDavCalendar.displayName,
                        color = parseColor(calDavCalendar.color, index),
                        ctag = null,  // Don't store ctag - first sync must fetch events
                        isReadOnly = calDavCalendar.isReadOnly,
                        isDefault = isFirst, // The first created calendar is the default
                        isVisible = true
                    )
                    val calendarId = calendarRepository.createCalendar(newCalendar)
                    isFirst = false
                    newCalendar.copy(id = calendarId)
                }
                createdCalendars.add(calendar)
            }

            // Step 6: Discover scheduling-delivery facts (RFC 6638 §2, §2.1.1): the
            // principal's outbox URL and each collection's auto-schedule capability.
            // Failures are non-fatal.
            persistSchedulingDiscovery(
                client, principalUrl, account.id, createdCalendars,
                accountRepository, calendarRepository, TAG
            )

            Log.i(TAG, "Discovery complete: account=${account.id}, calendars=${createdCalendars.size}")

            DiscoveryResult.Success(
                account = account,
                calendars = createdCalendars
            )
        } catch (e: Exception) {
            Log.e(TAG, "Discovery failed with exception", e)
            client.error(getErrorMessageForException(e))
        }
    }

    /** Maps a discovery exception to a user-facing error message. */
    private fun getErrorMessageForException(e: Exception): String {
        return when (e) {
            is SocketTimeoutException ->
                "Connection timed out. Please check your internet connection and try again."
            is UnknownHostException ->
                "Unable to reach iCloud servers. Please check your internet connection."
            is SSLHandshakeException ->
                "Secure connection failed. Please check your network settings."
            is java.net.ConnectException ->
                "Could not connect to iCloud. Please check your internet connection."
            else -> {
                val message = e.message?.lowercase().orEmpty()
                when {
                    message.contains("timeout") ->
                        "Connection timed out. Please try again."
                    message.contains("network") || message.contains("connect") ->
                        "Network error. Please check your internet connection."
                    message.contains("ssl") || message.contains("certificate") ->
                        "Secure connection failed. Please check your network settings."
                    else ->
                        "Connection failed: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    /** Maps a CalDAV error to a user-facing message; [action] fills "Could not <action>". */
    private fun getErrorMessageForCalDavError(error: CalDavResult.Error, action: String): String {
        val message = error.message.lowercase()
        return when {
            error.code in 500..599 ->
                "iCloud service temporarily unavailable. Please try again later."
            error.code == 429 ->
                "Too many requests. Please wait a moment and try again."
            message.contains("network") || message.contains("timeout") ->
                "Network error. Please check your internet connection."
            message.contains("ssl") || message.contains("certificate") ->
                "Secure connection failed. Please check your network settings."
            else ->
                "Could not $action. Please try again."
        }
    }

    /**
     * Refreshes an existing account's calendar list: adds new calendars, updates listed ones,
     * and deletes unlisted ones the server confirms are gone ([confirmUnlistedCalendars]).
     * The result holds the listed and the kept unlisted calendars.
     */
    override suspend fun refreshCalendars(accountId: Long): DiscoveryResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Refreshing calendars for account: $accountId")

        val account = accountRepository.getAccountById(accountId)
        if (account == null) {
            return@withContext DiscoveryResult.Error("Account not found")
        }

        val calendarHomeUrl = account.homeSetUrl
        if (calendarHomeUrl == null) {
            return@withContext DiscoveryResult.Error("Calendar home URL not configured")
        }

        val credentials = credentialProvider.getCredentials(accountId)
        if (credentials == null) {
            return@withContext DiscoveryResult.AuthError("Credentials not found. Please sign in again.")
        }

        val client = RefusalRecordingClient(clientFactory.createClient(credentials, icloudQuirks))

        try {
            // Re-discover home sets from the principal so added or removed home sets are
            // picked up; falls back to the stored one.
            val calendarHomeUrls = if (account.principalUrl != null) {
                val homeResult = client.discoverCalendarHome(account.principalUrl)
                if (homeResult.isSuccess()) {
                    (homeResult as CalDavResult.Success).data.map { url ->
                        ICloudUrlNormalizer.normalize(url) ?: url
                    }.sorted()
                } else {
                    Log.w(TAG, "Re-discovery failed, falling back to stored homeSetUrl")
                    listOf(calendarHomeUrl)
                }
            } else {
                listOf(calendarHomeUrl)
            }
            Log.d(TAG, "Calendar home URLs for refresh: $calendarHomeUrls")

            // Refresh the calendar-user-address-set (RFC 6638 §2.4.1) so aliases added or
            // removed on the server are picked up. Failures are non-fatal.
            if (account.principalUrl != null) {
                persistCalendarUserAddresses(client, account.principalUrl, accountId, accountRepository, TAG)
            }

            // List calendars from all home sets. An auth error ends the refresh; other
            // failures skip that home set, and at least one must list.
            val allCalendars = mutableListOf<CalDavCalendar>()
            val seenUrls = mutableSetOf<String>()
            var anyHomeSetSucceeded = false
            for (homeUrl in calendarHomeUrls) {
                val calendarsResult = client.listCalendars(homeUrl)
                if (calendarsResult.isSuccess()) {
                    anyHomeSetSucceeded = true
                    for (cal in (calendarsResult as CalDavResult.Success).data) {
                        if (seenUrls.add(cal.url)) allCalendars.add(cal)
                    }
                } else {
                    val error = calendarsResult as CalDavResult.Error
                    if (error.isAuthError()) {
                        return@withContext DiscoveryResult.AuthError("Session expired. Please sign in again.")
                    }
                    Log.w(TAG, "Failed to list calendars from home set $homeUrl: ${error.message}")
                }
            }

            if (!anyHomeSetSucceeded) {
                return@withContext client.error("Could not refresh calendars from any home set")
            }

            val discoveredCalendars = allCalendars
            val existingCalendars = calendarRepository.getCalendarsForAccountOnce(accountId)
            // The server lists regional URLs and Room holds canonical ones, so compare canonical.
            val discoveredUrls = discoveredCalendars.map {
                ICloudUrlNormalizer.normalize(it.url) ?: it.url
            }.toSet()

            // Remove calendars no longer on the server. Missing from the listing isn't enough:
            // each one is confirmed gone by asking the server about it directly.
            val unlisted = existingCalendars.filter { it.caldavUrl !in discoveredUrls }
            val verdict = confirmUnlistedCalendars(client, unlisted, TAG)
            for (gone in verdict.gone) {
                Log.d(TAG, "Removing deleted calendar: ${gone.displayName}")
                calendarRepository.deleteCalendar(gone.id)
            }

            // Add or update listed calendars. An existing calendar keeps its color when the
            // server sends none that [ServerColorParser] reads.
            val createdCalendars = mutableListOf<Calendar>()
            for ((index, calDavCalendar) in discoveredCalendars.withIndex()) {
                // Canonical form for both storage and lookup, or the lookup misses.
                val normalizedCalendarUrl = ICloudUrlNormalizer.normalize(calDavCalendar.url)
                    ?: calDavCalendar.url

                val existingCalendar = calendarRepository.getCalendarByUrl(normalizedCalendarUrl)
                val calendar = if (existingCalendar != null) {
                    val updated = existingCalendar.copy(
                        displayName = calDavCalendar.displayName,
                        color = ServerColorParser.parseCaldavColorToArgb(calDavCalendar.color)
                            ?: existingCalendar.color,
                        isReadOnly = calDavCalendar.isReadOnly
                    )
                    calendarRepository.updateCalendar(updated)
                    updated
                } else {
                    val newCalendar = Calendar(
                        accountId = accountId,
                        caldavUrl = normalizedCalendarUrl,
                        displayName = calDavCalendar.displayName,
                        color = parseColor(calDavCalendar.color, index),
                        ctag = null, // Must be null so first sync does a full pull
                        isReadOnly = calDavCalendar.isReadOnly,
                        isDefault = false,
                        isVisible = true
                    )
                    val calendarId = calendarRepository.createCalendar(newCalendar)
                    newCalendar.copy(id = calendarId)
                }
                createdCalendars.add(calendar)
            }

            // Re-probe scheduling-delivery facts (RFC 6638 §2, §2.1.1) so they follow
            // server-side changes. Failures are non-fatal.
            if (account.principalUrl != null) {
                persistSchedulingDiscovery(
                    client, account.principalUrl, accountId, createdCalendars,
                    accountRepository, calendarRepository, TAG
                )
            }

            // Kept calendars are still the account's calendars, so they count toward
            // the result (scheduling discovery above only re-probes listed ones).
            DiscoveryResult.Success(account, createdCalendars + verdict.kept)
        } catch (e: Exception) {
            Log.e(TAG, "Refresh failed", e)
            client.error("Refresh failed: ${e.message}")
        }
    }

    /**
     * Removes all data for an account (sign-out); [AccountRepository.deleteAccount] lists the
     * cleanup.
     */
    override suspend fun removeAccount(accountId: Long) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Removing account: $accountId")
        accountRepository.deleteAccount(accountId)
    }

    /** Removes the iCloud account for [email], if there is one. */
    override suspend fun removeAccountByEmail(email: String) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Removing iCloud account: ${email.maskEmail()}")
        val account = accountRepository.getAccountByProviderAndEmail(AccountProvider.ICLOUD, email)
        if (account != null) {
            accountRepository.deleteAccount(account.id)
        }
    }

    /**
     * Parses a server calendar color (#RRGGBBAA, #RRGGBB, or RRGGBB without #) to ARGB. A
     * missing or unparseable color falls back to [DEFAULT_COLORS] by [index].
     */
    private fun parseColor(colorString: String?, index: Int): Int {
        if (colorString.isNullOrBlank()) {
            return DEFAULT_COLORS[index % DEFAULT_COLORS.size]
        }

        return try {
            // iCloud sends #RRGGBBAA.
            val cleanColor = colorString.trim()
            when {
                cleanColor.length == 9 && cleanColor.startsWith("#") -> {
                    val rgb = cleanColor.substring(1, 7)
                    val alpha = cleanColor.substring(7, 9)
                    Color.parseColor("#$alpha$rgb")
                }
                cleanColor.startsWith("#") -> {
                    Color.parseColor(cleanColor)
                }
                else -> {
                    Color.parseColor("#$cleanColor")
                }
            }
        } catch (_: Exception) {
            Log.w(TAG, "Could not parse color: $colorString, using default")
            DEFAULT_COLORS[index % DEFAULT_COLORS.size]
        }
    }

}
