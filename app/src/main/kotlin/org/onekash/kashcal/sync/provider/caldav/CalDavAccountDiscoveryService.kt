package org.onekash.kashcal.sync.provider.caldav

import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.onekash.kashcal.data.credential.AccountCredentials
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.CalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavCalendar
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.onekash.kashcal.sync.discovery.RefusalRecordingClient
import org.onekash.kashcal.sync.discovery.confirmUnlistedCalendars
import org.onekash.kashcal.sync.discovery.persistCalendarUserAddresses
import org.onekash.kashcal.sync.discovery.persistSchedulingDiscovery
import org.onekash.kashcal.sync.parser.ServerColorParser
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import org.onekash.kashcal.util.maskEmail
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLHandshakeException

/**
 * Discovers and sets up accounts on generic CalDAV servers (Nextcloud, Baikal, Radicale,
 * Fastmail and others).
 *
 * Unlike [org.onekash.kashcal.sync.provider.icloud.ICloudAccountDiscoveryService], which
 * always talks to one fixed server, this takes a user-entered server URL, builds a
 * [DefaultQuirks] for it, and honors `trustInsecure` for self-signed certificates. Each run
 * gets its own client from [CalDavClientFactory].
 *
 * Discovery flow:
 * 1. Normalize and validate the server URL.
 * 2. Discover the principal (this validates the credentials), falling back to
 *    [probeCaldavPaths].
 * 3. Discover the calendar home sets and list the calendars in each.
 * 4. Create or update the Account row in Room.
 * 5. Save the credentials to encrypted storage; if that fails, delete the account.
 * 6. Create or update the Calendar rows, and discover the calendar-user addresses and
 *    scheduling facts (non-fatal).
 */
@Singleton
class CalDavAccountDiscoveryService @Inject constructor(
    private val calDavClientFactory: CalDavClientFactory,
    private val accountRepository: AccountRepository,
    private val calendarRepository: CalendarRepository
) {
    companion object {
        private const val TAG = "CalDavAccountDiscovery"
        private const val PROVIDER_CALDAV = "caldav"

        // Delay between path probes, so probing doesn't trip rate limiting (Issue #54).
        private const val PROBE_DELAY_MS = 150L

        // Server paths [probeCaldavPaths] tries (Issue #54). Davis/Symfony require
        // the trailing slashes; other servers accept them.
        private val KNOWN_CALDAV_PATHS = listOf(
            "/dav/",              // Davis, generic sabre/dav
            "/remote.php/dav/",   // Nextcloud
            "/dav.php/",          // Baikal
            "/caldav",            // Zoho (no trailing slash: Zoho returns 501 for /caldav/)
            "/caldav/",           // Open-Xchange (mailbox.org)
            "/dav/cal/",          // Stalwart
            "/caldav.php/",       // Some servers
            "/cal.php/"           // Some servers
        )

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

    val providerId: String = PROVIDER_CALDAV

    /**
     * Discovers a CalDAV account and creates it with all its calendars.
     *
     * Skips RFC 6764 well-known discovery; [discoverCalendars] tries it first. Only tests call
     * this; the app uses [discoverCalendars] then [createAccountWithSelectedCalendars].
     *
     * @param serverUrl CalDAV server URL (e.g., "https://nextcloud.example.com")
     * @param trustInsecure whether to trust self-signed certificates
     * @return [DiscoveryResult.Success] with the account and calendars, or an error
     */
    suspend fun discoverAndCreateAccount(
        serverUrl: String,
        username: String,
        password: String,
        trustInsecure: Boolean = false
    ): DiscoveryResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting discovery for: ${username.take(3)}*** at ${serverUrl.take(30)}...")

        // Step 0: Normalize the server URL
        val normalizedUrl = try {
            normalizeServerUrl(serverUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid server URL: $serverUrl", e)
            return@withContext DiscoveryResult.Error(
                "Invalid server URL. Please check the address and try again."
            )
        }
        Log.d(TAG, "Normalized URL: $normalizedUrl")

        val credentials = Credentials(
            username = username,
            password = password,
            serverUrl = normalizedUrl,
            trustInsecure = trustInsecure
        )

        val quirks = DefaultQuirks(normalizedUrl)
        val client = RefusalRecordingClient(calDavClientFactory.createClient(credentials, quirks))

        try {
            // Step 1: Discover principal URL (this validates credentials)
            Log.d(TAG, "Step 1: Discovering principal...")
            val principalResult = client.discoverPrincipal(normalizedUrl)

            if (principalResult.isAuthError()) {
                Log.e(TAG, "Authentication failed")
                return@withContext DiscoveryResult.AuthError(
                    "Invalid username or password. Please check your credentials."
                )
            }

            var principalUrl: String? = null

            if (principalResult.isSuccess()) {
                principalUrl = (principalResult as CalDavResult.Success).data
            } else {
                val error = principalResult as CalDavResult.Error
                Log.e(TAG, "Principal discovery failed: ${error.message}")

                // Probing other paths can't fix a certificate failure.
                if (isSSLError(error)) {
                    return@withContext client.error(
                        if (!trustInsecure) {
                            "Certificate verification failed. Enable 'Trust insecure connection' to continue."
                        } else {
                            "Secure connection failed even with 'Trust insecure connection' enabled. Please check the server URL and network settings."
                        }
                    )
                }

                // Probe known CalDAV paths if URL doesn't already contain one (Issue #54)
                if (!urlContainsKnownCaldavPath(normalizedUrl)) {
                    Log.i(TAG, "Probing known CalDAV paths...")
                    val probeResult = probeCaldavPaths(client, normalizedUrl, normalizedUrl)
                    if (probeResult != null) {
                        principalUrl = probeResult.second
                    }
                }

                if (principalUrl == null) {
                    return@withContext client.error(
                        if (urlContainsKnownCaldavPath(normalizedUrl)) {
                            getErrorMessageForCalDavError(error, "connect to server", trustInsecure)
                        } else {
                            "CalDAV service not found. Tried common server paths (/dav/, /remote.php/dav/, etc.). Please check the server address."
                        }
                    )
                }
            }

            Log.d(TAG, "Principal URL: $principalUrl")

            // Step 2: Discover calendar home
            Log.d(TAG, "Step 2: Discovering calendar home...")
            val homeResult = client.discoverCalendarHome(principalUrl)

            if (homeResult.isError()) {
                val error = homeResult as CalDavResult.Error
                Log.e(TAG, "Calendar home discovery failed: ${error.message}")
                return@withContext client.error(
                    getErrorMessageForCalDavError(error, "find calendar home", trustInsecure)
                )
            }

            val calendarHomeUrls = (homeResult as CalDavResult.Success).data.sorted()
            val calendarHomeUrl = calendarHomeUrls.first()
            val normalizedHomeUrl = normalizeHomeSetUrl(calendarHomeUrl)
            Log.d(TAG, "Calendar home URLs: $calendarHomeUrls (primary normalized: $normalizedHomeUrl)")

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
                    "No calendars found on server. Please check your account settings."
                )
            }

            // Step 4: Create or update Account in Room. The lookup includes the home set so
            // the same username on different servers gets separate accounts.
            val existingAccount = accountRepository.getAccountByProviderEmailAndHomeSetUrl(
                AccountProvider.CALDAV, username, normalizedHomeUrl
            )
            val account = if (existingAccount != null) {
                Log.d(TAG, "Updating existing account: ${existingAccount.id}")
                val updated = existingAccount.copy(
                    principalUrl = principalUrl,
                    homeSetUrl = normalizedHomeUrl,  // Sync builds DefaultQuirks from it
                    isEnabled = true
                )
                accountRepository.updateAccount(updated)
                updated
            } else {
                Log.d(TAG, "Creating new account")
                val displayName = extractServerDisplayName(normalizedUrl)
                val newAccount = Account(
                    provider = AccountProvider.CALDAV,
                    email = username,
                    displayName = displayName,
                    principalUrl = principalUrl,
                    homeSetUrl = normalizedHomeUrl,  // Sync builds DefaultQuirks from it
                    isEnabled = true
                )
                val accountId = accountRepository.createAccount(newAccount)
                newAccount.copy(id = accountId)
            }

            // Step 5: Save credentials to encrypted storage. Without them the account can't
            // sync, so a failed save deletes it.
            val accountCredentials = AccountCredentials(
                username = username,
                password = password,
                serverUrl = normalizedUrl,
                trustInsecure = trustInsecure,
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

            // Step 5b: Discover and persist the calendar-user-address-set (RFC 6638 §2.4.1).
            // Failures are non-fatal.
            persistCalendarUserAddresses(client, principalUrl, account.id, accountRepository, TAG)

            // Step 6: Create Calendar entities for each discovered calendar
            val createdCalendars = mutableListOf<Calendar>()
            var isFirst = true

            for ((index, calDavCalendar) in discoveredCalendars.withIndex()) {
                val existingCalendar = calendarRepository.getCalendarByUrl(calDavCalendar.url)
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
                        caldavUrl = calDavCalendar.url,
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

            // Step 7: Discover scheduling-delivery facts (RFC 6638 §2, §2.1.1): the
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
            client.error(getErrorMessageForException(e, trustInsecure))
        }
    }

    /**
     * Refreshes an existing account's calendar list: adds new calendars, updates listed ones,
     * and deletes unlisted ones the server confirms are gone ([confirmUnlistedCalendars]).
     *
     * @return [DiscoveryResult.Success] with the listed and the kept unlisted calendars
     */
    suspend fun refreshCalendars(accountId: Long): DiscoveryResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Refreshing calendars for account: $accountId")

        val account = accountRepository.getAccountById(accountId)
        if (account == null) {
            return@withContext DiscoveryResult.Error("Account not found")
        }

        val calendarHomeUrl = account.homeSetUrl
        if (calendarHomeUrl == null) {
            return@withContext DiscoveryResult.Error("Calendar home URL not configured")
        }

        val accountCredentials = accountRepository.getCredentials(accountId)
        if (accountCredentials == null) {
            return@withContext DiscoveryResult.AuthError(
                "Credentials not found. Please sign in again."
            )
        }

        val credentials = Credentials(
            username = accountCredentials.username,
            password = accountCredentials.password,
            serverUrl = accountCredentials.serverUrl,
            trustInsecure = accountCredentials.trustInsecure
        )

        val quirks = DefaultQuirks(account.homeSetUrl)
        val client = RefusalRecordingClient(calDavClientFactory.createClient(credentials, quirks))

        try {
            // Re-discover home sets from the principal so added or removed home sets are
            // picked up; falls back to the stored one.
            val calendarHomeUrls = if (account.principalUrl != null) {
                val homeResult = client.discoverCalendarHome(account.principalUrl)
                if (homeResult.isSuccess()) {
                    (homeResult as CalDavResult.Success).data.sorted()
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
            val discoveredUrls = discoveredCalendars.map { it.url }.toSet()

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
                val existingCalendar = calendarRepository.getCalendarByUrl(calDavCalendar.url)
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
                        caldavUrl = calDavCalendar.url,
                        displayName = calDavCalendar.displayName,
                        color = parseColor(calDavCalendar.color, index),
                        ctag = null,  // Must be null so first sync does a full pull
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
            DiscoveryResult.Error("Refresh failed: ${e.message}")
        }
    }

    /**
     * Removes all data for an account (sign-out); [AccountRepository.deleteAccount] lists the
     * cleanup.
     */
    suspend fun removeAccount(accountId: Long) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Removing account: $accountId")
        accountRepository.deleteAccount(accountId)
    }

    /**
     * Removes a CalDAV account by email.
     *
     * @deprecated Ambiguous when CalDAV accounts on different servers share an email. The app
     * removes CalDAV accounts through [removeAccount].
     */
    @Deprecated(
        message = "Ambiguous for multi-server CalDAV. Use removeAccount(accountId) instead.",
        replaceWith = ReplaceWith("removeAccount(accountId)")
    )
    suspend fun removeAccountByEmail(email: String) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Removing CalDAV account: ${email.maskEmail()}")
        val account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CALDAV, email)
        if (account != null) {
            accountRepository.deleteAccount(account.id)
        }
    }

    // ==================== Validation ====================

    /**
     * Returns true if no account uses [displayName], ignoring case, so account names stay
     * unique.
     *
     * @param excludeAccountId account left out of the check, so an edit can keep its own name
     */
    suspend fun isDisplayNameAvailable(displayName: String, excludeAccountId: Long? = null): Boolean {
        return accountRepository.countByDisplayName(displayName, excludeAccountId) == 0
    }

    // ==================== Two-Phase Discovery ====================

    /**
     * Discovers calendars without creating the account, so the user can pick which ones to
     * sync before [createAccountWithSelectedCalendars].
     *
     * Tries RFC 6764 well-known discovery first; [DiscoveryResult.CalendarsFound.serverUrl] is
     * the endpoint it found, or the normalized entered URL.
     *
     * @param trustInsecure whether to trust self-signed certificates
     * @return [DiscoveryResult.CalendarsFound], or an error
     */
    suspend fun discoverCalendars(
        serverUrl: String,
        username: String,
        password: String,
        trustInsecure: Boolean = false
    ): DiscoveryResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Discovering calendars for: ${username.take(3)}*** at ${serverUrl.take(30)}...")

        // Step 0: Normalize the server URL
        val normalizedUrl = try {
            normalizeServerUrl(serverUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid server URL: $serverUrl", e)
            return@withContext DiscoveryResult.Error(
                "Invalid server URL. Please check the address and try again."
            )
        }
        Log.d(TAG, "Normalized URL: $normalizedUrl")

        val credentials = Credentials(
            username = username,
            password = password,
            serverUrl = normalizedUrl,
            trustInsecure = trustInsecure
        )

        val quirks = DefaultQuirks(normalizedUrl)
        val client = RefusalRecordingClient(calDavClientFactory.createClient(credentials, quirks))

        try {
            // Step 0.5: Try RFC 6764 well-known discovery
            Log.d(TAG, "Step 0.5: Trying well-known discovery...")
            val wellKnownResult = client.discoverWellKnown(normalizedUrl)
            val caldavUrl = if (wellKnownResult.isSuccess()) {
                val discoveredUrl = wellKnownResult.getOrNull()!!
                if (discoveredUrl != normalizedUrl) {
                    Log.i(TAG, "Well-known discovery found CalDAV endpoint: $discoveredUrl")
                }
                discoveredUrl
            } else {
                normalizedUrl
            }

            // Step 1: Discover principal URL (validates credentials)
            Log.d(TAG, "Step 1: Discovering principal...")
            val principalResult = client.discoverPrincipal(caldavUrl)

            if (principalResult.isAuthError()) {
                Log.e(TAG, "Authentication failed")
                return@withContext DiscoveryResult.AuthError(
                    "Invalid username or password. Please check your credentials."
                )
            }

            var principalUrl: String? = null

            if (principalResult.isSuccess()) {
                principalUrl = (principalResult as CalDavResult.Success).data
            } else {
                val error = principalResult as CalDavResult.Error
                Log.e(TAG, "Principal discovery failed: ${error.message}")

                // Probing other paths can't fix a certificate failure.
                if (isSSLError(error)) {
                    return@withContext client.error(
                        if (!trustInsecure) {
                            "Certificate verification failed. Enable 'Trust insecure connection' to continue."
                        } else {
                            "Secure connection failed even with 'Trust insecure connection' enabled. Please check the server URL and network settings."
                        }
                    )
                }

                // Probe known CalDAV paths on the entered URL's host, not the well-known
                // redirect's (Issue #54). Skip probing only when the entered URL already has a
                // known CalDAV path and well-known didn't redirect elsewhere.
                val wasRedirected = caldavUrl.trimEnd('/') != normalizedUrl.trimEnd('/')
                val shouldProbe = wasRedirected || !urlContainsKnownCaldavPath(normalizedUrl)

                if (shouldProbe) {
                    Log.i(TAG, "Probing known CalDAV paths...")
                    val probeResult = probeCaldavPaths(client, normalizedUrl, caldavUrl)
                    if (probeResult != null) {
                        principalUrl = probeResult.second
                    }
                }

                if (principalUrl == null) {
                    return@withContext client.error(
                        if (!shouldProbe) {
                            getErrorMessageForCalDavError(error, "connect to server", trustInsecure)
                        } else {
                            "CalDAV service not found. Tried common server paths (/dav/, /remote.php/dav/, etc.). Please check the server address."
                        }
                    )
                }
            }

            Log.d(TAG, "Principal URL: $principalUrl")

            // Step 2: Discover calendar home
            Log.d(TAG, "Step 2: Discovering calendar home...")
            val homeResult = client.discoverCalendarHome(principalUrl)

            if (homeResult.isError()) {
                val error = homeResult as CalDavResult.Error
                Log.e(TAG, "Calendar home discovery failed: ${error.message}")
                return@withContext client.error(
                    getErrorMessageForCalDavError(error, "find calendar home", trustInsecure)
                )
            }

            val calendarHomeUrls = (homeResult as CalDavResult.Success).data.sorted()
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
                    "No calendars found on server. Please check your account settings."
                )
            }

            val calendarList = discoveredCalendars.mapIndexed { index, calDavCalendar ->
                org.onekash.kashcal.sync.discovery.DiscoveredCalendar(
                    href = calDavCalendar.url,
                    displayName = calDavCalendar.displayName,
                    color = parseColor(calDavCalendar.color, index),
                    supportsEvents = true,
                    isReadOnly = calDavCalendar.isReadOnly
                )
            }

            if (calendarList.isEmpty()) {
                return@withContext client.error(
                    "No event calendars found on server."
                )
            }

            Log.i(TAG, "Calendar discovery complete: ${calendarList.size} calendars available")

            DiscoveryResult.CalendarsFound(
                serverUrl = caldavUrl,  // The well-known endpoint, or the entered URL
                username = username,
                calendarHomeUrl = calendarHomeUrl,
                principalUrl = principalUrl,
                calendars = calendarList
            )
        } catch (e: Exception) {
            Log.e(TAG, "Calendar discovery failed with exception", e)
            client.error(getErrorMessageForException(e, trustInsecure))
        }
    }

    /**
     * Creates the account with only the calendars the user picked from [discoverCalendars].
     * [serverUrl], [principalUrl] and [calendarHomeUrl] are the values from
     * [DiscoveryResult.CalendarsFound].
     *
     * @param displayName user-entered account name; blank falls back to the server hostname
     * @return [DiscoveryResult.Success] with the account and created calendars, or an error
     */
    suspend fun createAccountWithSelectedCalendars(
        serverUrl: String,
        username: String,
        password: String,
        trustInsecure: Boolean,
        principalUrl: String,
        calendarHomeUrl: String,
        selectedCalendars: List<org.onekash.kashcal.sync.discovery.DiscoveredCalendar>,
        displayName: String? = null
    ): DiscoveryResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Creating account with ${selectedCalendars.size} selected calendars")

        if (selectedCalendars.isEmpty()) {
            return@withContext DiscoveryResult.Error("No calendars selected")
        }

        try {
            // Step 1: Create or update Account in Room. The lookup includes the home set so
            // the same username on different servers gets separate accounts.
            val normalizedHomeUrl = normalizeHomeSetUrl(calendarHomeUrl)
            val existingAccount = accountRepository.getAccountByProviderEmailAndHomeSetUrl(
                AccountProvider.CALDAV, username, normalizedHomeUrl
            )
            val account = if (existingAccount != null) {
                Log.d(TAG, "Updating existing account: ${existingAccount.id}")
                val updated = existingAccount.copy(
                    principalUrl = principalUrl,
                    homeSetUrl = normalizedHomeUrl,  // Sync builds DefaultQuirks from it
                    isEnabled = true
                )
                accountRepository.updateAccount(updated)
                updated
            } else {
                Log.d(TAG, "Creating new account")
                val accountDisplayName = displayName?.takeIf { it.isNotBlank() }
                    ?: extractServerDisplayName(serverUrl)
                val newAccount = Account(
                    provider = AccountProvider.CALDAV,
                    email = username,
                    displayName = accountDisplayName,
                    principalUrl = principalUrl,
                    homeSetUrl = normalizedHomeUrl,  // Sync builds DefaultQuirks from it
                    isEnabled = true
                )
                val accountId = accountRepository.createAccount(newAccount)
                newAccount.copy(id = accountId)
            }

            // Step 2: Save credentials to encrypted storage. Without them the account can't
            // sync, so a failed save deletes it.
            val accountCredentials = AccountCredentials(
                username = username,
                password = password,
                serverUrl = serverUrl,
                trustInsecure = trustInsecure,
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

            // Step 2b: Discover the calendar-user-address-set (RFC 6638 §2.4.1). Failures are
            // non-fatal.
            val addressClient = calDavClientFactory.createClient(
                Credentials(username, password, serverUrl, trustInsecure),
                DefaultQuirks(serverUrl)
            )
            persistCalendarUserAddresses(addressClient, principalUrl, account.id, accountRepository, TAG)

            // Step 3: Create Calendar entities for selected calendars only
            val createdCalendars = mutableListOf<Calendar>()
            var isFirst = true

            for (discoveredCalendar in selectedCalendars) {
                val existingCalendar = calendarRepository.getCalendarByUrl(discoveredCalendar.href)
                val calendar = if (existingCalendar != null) {
                    Log.d(TAG, "Updating calendar: ${discoveredCalendar.displayName}")
                    val updated = existingCalendar.copy(
                        displayName = discoveredCalendar.displayName,
                        color = discoveredCalendar.color,
                        isReadOnly = discoveredCalendar.isReadOnly
                    )
                    calendarRepository.updateCalendar(updated)
                    updated
                } else {
                    Log.d(TAG, "Creating calendar: ${discoveredCalendar.displayName}")
                    val newCalendar = Calendar(
                        accountId = account.id,
                        caldavUrl = discoveredCalendar.href,
                        displayName = discoveredCalendar.displayName,
                        color = discoveredCalendar.color,
                        ctag = null,  // Don't store ctag - first sync must fetch events
                        isReadOnly = discoveredCalendar.isReadOnly,
                        isDefault = isFirst,
                        isVisible = true
                    )
                    val calendarId = calendarRepository.createCalendar(newCalendar)
                    isFirst = false
                    newCalendar.copy(id = calendarId)
                }
                createdCalendars.add(calendar)
            }

            // Discover scheduling-delivery facts (RFC 6638 §2, §2.1.1) on the client used for
            // address-set discovery. Failures are non-fatal.
            persistSchedulingDiscovery(
                addressClient, principalUrl, account.id, createdCalendars,
                accountRepository, calendarRepository, TAG
            )

            Log.i(TAG, "Account creation complete: account=${account.id}, calendars=${createdCalendars.size}")

            DiscoveryResult.Success(
                account = account,
                calendars = createdCalendars
            )
        } catch (e: Exception) {
            Log.e(TAG, "Account creation failed", e)
            DiscoveryResult.Error("Failed to create account: ${e.message}")
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Normalizes an entered server URL: adds https:// when no scheme is given and drops a
     * trailing slash from a root URL.
     *
     * @throws IllegalArgumentException if the URL has no host
     */
    private fun normalizeServerUrl(serverUrl: String): String {
        var url = serverUrl.trim()

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        // Keep a trailing slash on a path (e.g. /dav/): Davis/Symfony require it (Issue #54).
        val parsed = URI(url)
        if (parsed.path.isNullOrBlank() || parsed.path == "/") {
            url = url.trimEnd('/')
        }

        val uri = URI(url)
        require(!uri.host.isNullOrBlank()) { "Invalid URL: missing host" }

        return url
    }

    /**
     * Normalizes a calendar home set URL so one server always yields one stored value, even
     * when discoveries differ by a trailing slash, an explicit default port or host case.
     *
     * Applied both before storing [Account.homeSetUrl] and before the lookup by
     * [AccountRepository.getAccountByProviderEmailAndHomeSetUrl]; if the two differ, a
     * re-login creates a duplicate account. Uses java.net.URI so plain JUnit tests can call it
     * without Robolectric.
     */
    internal fun normalizeHomeSetUrl(url: String): String {
        val uri = URI(url)
        val scheme = (uri.scheme ?: "https").lowercase()
        val host = (uri.host ?: return url).lowercase()
        val port = uri.port.let { p ->
            if (p == -1 || (scheme == "https" && p == 443) || (scheme == "http" && p == 80)) ""
            else ":$p"
        }
        val path = (uri.path ?: "/").trimEnd('/')
        return "$scheme://$host$port$path/"
    }

    /** Returns scheme://host:port, "https://ex.com:8080/dav/" -> "https://ex.com:8080". */
    private fun extractBaseHost(url: String): String {
        val uri = URI(url)
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "${uri.scheme}://${uri.host}$port"
    }

    /** Returns true if the URL path starts with a known CalDAV path, "/dav" and "/dav/" alike. */
    private fun urlContainsKnownCaldavPath(url: String): Boolean {
        val uri = URI(url)
        val path = uri.path?.trimEnd('/') ?: return false
        return KNOWN_CALDAV_PATHS.any { knownPath ->
            path == knownPath.trimEnd('/') || path.startsWith(knownPath.trimEnd('/') + "/")
        }
    }

    /** Returns true if the error message mentions SSL, TLS or a certificate. */
    private fun isSSLError(error: CalDavResult.Error): Boolean {
        return error.message.contains("SSL", ignoreCase = true) ||
            error.message.contains("certificate", ignoreCase = true) ||
            error.message.contains("TLS", ignoreCase = true)
    }

    /**
     * Probes [KNOWN_CALDAV_PATHS] on the host of [baseUrl] for a principal (Issue #54).
     *
     * Callers probe after principal discovery fails with neither an auth nor a certificate
     * error, when the entered URL has no known CalDAV path; [discoverCalendars] also probes
     * when well-known discovery pointed elsewhere.
     *
     * @param triedUrl URL already tried, skipped here
     * @return (probed URL, principal URL), or null if every path fails or one returns an auth
     *   error
     */
    private suspend fun probeCaldavPaths(
        client: CalDavClient,
        baseUrl: String,
        triedUrl: String
    ): Pair<String, String>? {
        val baseHost = extractBaseHost(baseUrl)
        val triedNormalized = triedUrl.trimEnd('/')

        for ((index, path) in KNOWN_CALDAV_PATHS.withIndex()) {
            val probeUrl = "$baseHost$path"

            // Skip the already-tried URL (slash-insensitive).
            if (probeUrl.trimEnd('/') == triedNormalized) continue

            if (index > 0) {
                delay(PROBE_DELAY_MS)
            }

            Log.d(TAG, "Probing CalDAV path: $probeUrl")
            val result = client.discoverPrincipal(probeUrl)

            if (result.isSuccess()) {
                val principalUrl = (result as CalDavResult.Success).data
                Log.i(TAG, "Found CalDAV service at $probeUrl (principal: $principalUrl)")
                return Pair(probeUrl, principalUrl)
            }

            // Bad credentials fail on every path, so stop probing.
            if (result is CalDavResult.Error && result.isAuthError()) {
                Log.d(TAG, "Auth error during probing, stopping: ${result.message}")
                return null
            }

            Log.d(TAG, "Probe failed for $probeUrl: ${(result as? CalDavResult.Error)?.message}")
        }

        return null
    }

    /**
     * Returns a default account name from the server URL: a known provider's name, else the
     * host. "https://caldav.fastmail.com" -> "Fastmail", "https://nextcloud.example.com" ->
     * "nextcloud.example.com".
     */
    private fun extractServerDisplayName(serverUrl: String): String {
        val uri = URI(serverUrl)
        val host = uri.host ?: return "CalDAV"

        return when {
            host.contains("fastmail", ignoreCase = true) -> "Fastmail"
            host.contains("icloud", ignoreCase = true) -> "iCloud"
            host.contains("google", ignoreCase = true) -> "Google"
            host.contains("yahoo", ignoreCase = true) -> "Yahoo"
            host.contains("outlook", ignoreCase = true) -> "Outlook"
            else -> host
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
            val cleanColor = colorString.trim()
            when {
                // #RRGGBBAA format (some servers)
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

    /** Maps a discovery exception to a user-facing error message. */
    private fun getErrorMessageForException(e: Exception, trustInsecure: Boolean): String {
        return when (e) {
            is SocketTimeoutException ->
                "Connection timed out. Please check your internet connection and try again."
            is UnknownHostException ->
                "Unable to reach server. Please check the server URL and your internet connection."
            is SSLHandshakeException -> {
                if (!trustInsecure) {
                    "Certificate verification failed. Enable 'Trust insecure connection' to continue."
                } else {
                    "Secure connection failed even with 'Trust insecure connection' enabled. Please check the server URL and network settings."
                }
            }
            is java.net.ConnectException ->
                "Could not connect to server. Please check the server URL and try again."
            else -> {
                val message = e.message?.lowercase().orEmpty()
                when {
                    message.contains("timeout") ->
                        "Connection timed out. Please try again."
                    message.contains("network") || message.contains("connect") ->
                        "Network error. Please check your internet connection."
                    message.contains("ssl") || message.contains("certificate") -> {
                        if (!trustInsecure) {
                            "Certificate verification failed. Enable 'Trust insecure connection' to continue."
                        } else {
                            "Secure connection failed even with 'Trust insecure connection' enabled. Please check the server URL and network settings."
                        }
                    }
                    else ->
                        "Connection failed: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    /** Maps a CalDAV error to a user-facing message; [action] fills "Could not <action>". */
    private fun getErrorMessageForCalDavError(error: CalDavResult.Error, action: String, trustInsecure: Boolean = false): String {
        val message = error.message.lowercase()
        return when {
            error.code in 500..599 ->
                "Server temporarily unavailable. Please try again later."
            error.code == 429 ->
                "Too many requests. Please wait a moment and try again."
            error.code == 404 ->
                "CalDAV service not found at this URL. Please check the server address."
            error.code == 403 ->
                "Permission denied. Please check your credentials and server URL."
            message.contains("network") || message.contains("timeout") ->
                "Network error. Please check your internet connection."
            message.contains("ssl") || message.contains("certificate") -> {
                if (!trustInsecure) {
                    "Certificate verification failed. Enable 'Trust insecure connection' to continue."
                } else {
                    "Secure connection failed even with 'Trust insecure connection' enabled. Please check the server URL and network settings."
                }
            }
            else ->
                "Could not $action. Please try again."
        }
    }

}
