package org.onekash.kashcal.sync.provider.caldav

import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.onekash.kashcal.data.credential.AccountCredentials
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.CalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLHandshakeException

/**
 * Discovery service for generic CalDAV servers (Nextcloud, Baikal, Radicale, FastMail).
 *
 * Unlike ICloudAccountDiscoveryService which uses a fixed server URL and singleton client,
 * this service:
 * - Accepts user-provided server URL
 * - Creates isolated clients via CalDavClientFactory for each discovery
 * - Supports self-signed certificates via trustInsecure flag
 * - Saves credentials per-account via AccountRepository.saveCredentials()
 *
 * Discovery flow:
 * 1. Normalize and validate server URL
 * 2. Create isolated CalDavClient with credentials
 * 3. Discover principal URL (validates credentials)
 * 4. Discover calendar home URL
 * 5. List available calendars
 * 6. Create Account and Calendar entities in Room
 * 7. Save credentials to encrypted storage
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

        // Delay between path probes to avoid rate limiting (Issue #54)
        private const val PROBE_DELAY_MS = 150L

        // Known CalDAV server paths for fallback probing (Issue #54).
        // Trailing slashes required by Davis/Symfony, harmless for others.
        private val KNOWN_CALDAV_PATHS = listOf(
            "/dav/",              // Davis, generic sabre/dav
            "/remote.php/dav/",   // Nextcloud
            "/dav.php/",          // Baikal
            "/caldav/",           // Open-Xchange (mailbox.org)
            "/dav/cal/",          // Stalwart
            "/caldav.php/",       // Some servers
            "/cal.php/"           // Some servers
        )

        // Default calendar colors if server doesn't provide one
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
     * Discover and create a CalDAV account with all its calendars.
     *
     * @param serverUrl CalDAV server URL (e.g., "https://nextcloud.example.com")
     * @param username Username for authentication
     * @param password Password for authentication
     * @param trustInsecure Whether to trust self-signed certificates
     * @return DiscoveryResult with created Account and Calendars, or error
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

        // Create credentials for the client factory
        val credentials = Credentials(
            username = username,
            password = password,
            serverUrl = normalizedUrl,
            trustInsecure = trustInsecure
        )

        // Create isolated client with credentials and DefaultQuirks
        val quirks = DefaultQuirks(normalizedUrl)
        val client = calDavClientFactory.createClient(credentials, quirks)

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

                // Check for SSL errors specifically — no probing will help
                if (isSSLError(error)) {
                    return@withContext DiscoveryResult.Error(
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
                    return@withContext DiscoveryResult.Error(
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
                return@withContext DiscoveryResult.Error(
                    getErrorMessageForCalDavError(error, "find calendar home", trustInsecure)
                )
            }

            val calendarHomeUrl = (homeResult as CalDavResult.Success).data
            Log.d(TAG, "Calendar home URL: $calendarHomeUrl")

            // Step 3: List calendars
            Log.d(TAG, "Step 3: Listing calendars...")
            val calendarsResult = client.listCalendars(calendarHomeUrl)

            if (calendarsResult.isError()) {
                val error = calendarsResult as CalDavResult.Error
                Log.e(TAG, "Calendar listing failed: ${error.message}")
                return@withContext DiscoveryResult.Error(
                    getErrorMessageForCalDavError(error, "list calendars", trustInsecure)
                )
            }

            val discoveredCalendars = (calendarsResult as CalDavResult.Success).data
            Log.i(TAG, "Discovered ${discoveredCalendars.size} calendars")

            if (discoveredCalendars.isEmpty()) {
                return@withContext DiscoveryResult.Error(
                    "No calendars found on server. Please check your account settings."
                )
            }

            // Step 4: Create or update Account in Room
            val existingAccount = accountRepository.getAccountByProviderAndEmail(AccountProvider.CALDAV, username)
            val account = if (existingAccount != null) {
                Log.d(TAG, "Updating existing account: ${existingAccount.id}")
                val updated = existingAccount.copy(
                    principalUrl = principalUrl,
                    homeSetUrl = calendarHomeUrl,  // CRITICAL: Required for DefaultQuirks
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
                    homeSetUrl = calendarHomeUrl,  // CRITICAL: Required for DefaultQuirks
                    isEnabled = true
                )
                val accountId = accountRepository.createAccount(newAccount)
                newAccount.copy(id = accountId)
            }

            // Step 5: Save credentials to encrypted storage
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
                Log.w(TAG, "Failed to save credentials - encryption may be unavailable")
                // Continue anyway - credentials can be re-entered
            }

            // Step 6: Create Calendar entities for each discovered calendar
            val createdCalendars = mutableListOf<Calendar>()
            var isFirst = true

            for ((index, calDavCalendar) in discoveredCalendars.withIndex()) {
                // Skip reminders/tasks/birthday calendars (KashCal has its own birthday feature)
                val name = calDavCalendar.displayName.lowercase()
                if (name.contains("reminder") || name.contains("task") || name.contains("birthday")) {
                    Log.d(TAG, "Skipping non-calendar: ${calDavCalendar.displayName}")
                    continue
                }

                val existingCalendar = calendarRepository.getCalendarByUrl(calDavCalendar.url)
                val calendar = if (existingCalendar != null) {
                    Log.d(TAG, "Updating calendar: ${calDavCalendar.displayName}")
                    val updated = existingCalendar.copy(
                        displayName = calDavCalendar.displayName,
                        color = parseColor(calDavCalendar.color, index),
                        ctag = calDavCalendar.ctag,
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
                        isDefault = isFirst, // First calendar is default
                        isVisible = true
                    )
                    val calendarId = calendarRepository.createCalendar(newCalendar)
                    isFirst = false
                    newCalendar.copy(id = calendarId)
                }
                createdCalendars.add(calendar)
            }

            Log.i(TAG, "Discovery complete: account=${account.id}, calendars=${createdCalendars.size}")

            DiscoveryResult.Success(
                account = account,
                calendars = createdCalendars
            )
        } catch (e: Exception) {
            Log.e(TAG, "Discovery failed with exception", e)
            DiscoveryResult.Error(getErrorMessageForException(e, trustInsecure))
        }
    }

    /**
     * Refresh calendar list for an existing account.
     * Adds new calendars, updates existing ones, removes deleted ones.
     *
     * @param accountId The account ID to refresh
     * @return DiscoveryResult with updated Account and Calendars
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

        // Load credentials for this account
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

        // Create isolated client
        val quirks = DefaultQuirks(account.homeSetUrl!!)
        val client = calDavClientFactory.createClient(credentials, quirks)

        try {
            val calendarsResult = client.listCalendars(calendarHomeUrl)

            if (calendarsResult.isError()) {
                val error = calendarsResult as CalDavResult.Error
                return@withContext if (error.isAuthError()) {
                    DiscoveryResult.AuthError("Session expired. Please sign in again.")
                } else {
                    DiscoveryResult.Error("Could not refresh calendars: ${error.message}")
                }
            }

            val discoveredCalendars = (calendarsResult as CalDavResult.Success).data
            val existingCalendars = calendarRepository.getCalendarsForAccountOnce(accountId)
            val discoveredUrls = discoveredCalendars.map { it.url }.toSet()

            // Remove calendars no longer on server
            for (existing in existingCalendars) {
                if (existing.caldavUrl !in discoveredUrls) {
                    Log.d(TAG, "Removing deleted calendar: ${existing.displayName}")
                    calendarRepository.deleteCalendar(existing.id)
                }
            }

            // Add/update calendars from server
            val createdCalendars = mutableListOf<Calendar>()
            for ((index, calDavCalendar) in discoveredCalendars.withIndex()) {
                if (calDavCalendar.displayName.lowercase().contains("reminder") ||
                    calDavCalendar.displayName.lowercase().contains("task")) {
                    continue
                }

                val existingCalendar = calendarRepository.getCalendarByUrl(calDavCalendar.url)
                val calendar = if (existingCalendar != null) {
                    val updated = existingCalendar.copy(
                        displayName = calDavCalendar.displayName,
                        ctag = calDavCalendar.ctag,
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

            DiscoveryResult.Success(account, createdCalendars)
        } catch (e: Exception) {
            Log.e(TAG, "Refresh failed", e)
            DiscoveryResult.Error("Refresh failed: ${e.message}")
        }
    }

    /**
     * Remove all data for an account (used during sign-out).
     *
     * Uses AccountRepository.deleteAccount() which properly cleans up:
     * - WorkManager sync jobs
     * - Scheduled reminders
     * - Pending operations
     * - Encrypted credentials
     * - Cascade deletes calendars/events
     *
     * @param accountId The account ID to remove
     */
    suspend fun removeAccount(accountId: Long) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Removing account: $accountId")
        accountRepository.deleteAccount(accountId)
    }

    /**
     * Remove CalDAV account by email.
     *
     * @param email The email address of the account to remove
     */
    suspend fun removeAccountByEmail(email: String) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Removing CalDAV account: $email")
        val account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CALDAV, email)
        if (account != null) {
            accountRepository.deleteAccount(account.id)
        }
    }

    // ==================== Validation ====================

    /**
     * Check if a display name is available (not already in use).
     * Used for uniqueness validation when creating/editing accounts.
     *
     * @param displayName Display name to check
     * @param excludeAccountId Account ID to exclude from check (for edit mode)
     * @return true if name is available, false if already in use
     */
    suspend fun isDisplayNameAvailable(displayName: String, excludeAccountId: Long? = null): Boolean {
        return accountRepository.countByDisplayName(displayName, excludeAccountId) == 0
    }

    // ==================== Two-Phase Discovery ====================

    /**
     * Discover calendars without creating account.
     * Used for two-phase flow where user selects which calendars to sync.
     *
     * @param serverUrl CalDAV server URL
     * @param username Username for authentication
     * @param password Password for authentication
     * @param trustInsecure Whether to trust self-signed certificates
     * @return DiscoveryResult.CalendarsFound with discovered calendars, or error
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

        // Create credentials for the client factory
        val credentials = Credentials(
            username = username,
            password = password,
            serverUrl = normalizedUrl,
            trustInsecure = trustInsecure
        )

        // Create isolated client with credentials and DefaultQuirks
        val quirks = DefaultQuirks(normalizedUrl)
        val client = calDavClientFactory.createClient(credentials, quirks)

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

                // Check for SSL errors specifically — no probing will help
                if (isSSLError(error)) {
                    return@withContext DiscoveryResult.Error(
                        if (!trustInsecure) {
                            "Certificate verification failed. Enable 'Trust insecure connection' to continue."
                        } else {
                            "Secure connection failed even with 'Trust insecure connection' enabled. Please check the server URL and network settings."
                        }
                    )
                }

                // Probe known CalDAV paths (Issue #54).
                // Use original user URL as base, not well-known redirect URL.
                // Skip probing only if the user's original URL already has a known CalDAV path
                // AND wasn't redirected somewhere else via well-known.
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
                    return@withContext DiscoveryResult.Error(
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
                return@withContext DiscoveryResult.Error(
                    getErrorMessageForCalDavError(error, "find calendar home", trustInsecure)
                )
            }

            val calendarHomeUrl = (homeResult as CalDavResult.Success).data
            Log.d(TAG, "Calendar home URL: $calendarHomeUrl")

            // Step 3: List calendars
            Log.d(TAG, "Step 3: Listing calendars...")
            val calendarsResult = client.listCalendars(calendarHomeUrl)

            if (calendarsResult.isError()) {
                val error = calendarsResult as CalDavResult.Error
                Log.e(TAG, "Calendar listing failed: ${error.message}")
                return@withContext DiscoveryResult.Error(
                    getErrorMessageForCalDavError(error, "list calendars", trustInsecure)
                )
            }

            val discoveredCalendars = (calendarsResult as CalDavResult.Success).data
            Log.i(TAG, "Discovered ${discoveredCalendars.size} calendars")

            if (discoveredCalendars.isEmpty()) {
                return@withContext DiscoveryResult.Error(
                    "No calendars found on server. Please check your account settings."
                )
            }

            // Convert to DiscoveredCalendar objects (skip reminders/tasks/birthdays)
            val calendarList = discoveredCalendars.mapIndexedNotNull { index, calDavCalendar ->
                // Skip reminders/tasks/birthday calendars (KashCal has its own birthday feature)
                val name = calDavCalendar.displayName.lowercase()
                if (name.contains("reminder") || name.contains("task") || name.contains("birthday")) {
                    Log.d(TAG, "Skipping non-calendar: ${calDavCalendar.displayName}")
                    null
                } else {
                    org.onekash.kashcal.sync.discovery.DiscoveredCalendar(
                        href = calDavCalendar.url,
                        displayName = calDavCalendar.displayName,
                        color = parseColor(calDavCalendar.color, index),
                        supportsEvents = true,
                        isReadOnly = calDavCalendar.isReadOnly
                    )
                }
            }

            if (calendarList.isEmpty()) {
                return@withContext DiscoveryResult.Error(
                    "No calendars found. Only task/reminder calendars were discovered."
                )
            }

            Log.i(TAG, "Calendar discovery complete: ${calendarList.size} calendars available")

            DiscoveryResult.CalendarsFound(
                serverUrl = caldavUrl,  // Use well-known discovered URL
                username = username,
                calendarHomeUrl = calendarHomeUrl,
                principalUrl = principalUrl,
                calendars = calendarList
            )
        } catch (e: Exception) {
            Log.e(TAG, "Calendar discovery failed with exception", e)
            DiscoveryResult.Error(getErrorMessageForException(e, trustInsecure))
        }
    }

    /**
     * Create account with only selected calendars.
     * Second phase of two-phase discovery flow.
     *
     * @param serverUrl Normalized server URL (from CalendarsFound)
     * @param username Username for authentication
     * @param password Password for authentication
     * @param trustInsecure Whether to trust self-signed certificates
     * @param principalUrl Principal URL (from CalendarsFound)
     * @param calendarHomeUrl Calendar home URL (from CalendarsFound)
     * @param selectedCalendars List of selected calendars to create
     * @param displayName User-provided display name (optional, falls back to server hostname)
     * @return DiscoveryResult.Success with created Account and Calendars
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
            // Step 1: Create or update Account in Room
            val existingAccount = accountRepository.getAccountByProviderAndEmail(AccountProvider.CALDAV, username)
            val account = if (existingAccount != null) {
                Log.d(TAG, "Updating existing account: ${existingAccount.id}")
                val updated = existingAccount.copy(
                    principalUrl = principalUrl,
                    homeSetUrl = calendarHomeUrl,  // CRITICAL: Required for DefaultQuirks
                    isEnabled = true
                )
                accountRepository.updateAccount(updated)
                updated
            } else {
                Log.d(TAG, "Creating new account")
                // Use provided displayName if not blank, otherwise fall back to server hostname
                val accountDisplayName = displayName?.takeIf { it.isNotBlank() }
                    ?: extractServerDisplayName(serverUrl)
                val newAccount = Account(
                    provider = AccountProvider.CALDAV,
                    email = username,
                    displayName = accountDisplayName,
                    principalUrl = principalUrl,
                    homeSetUrl = calendarHomeUrl,  // CRITICAL: Required for DefaultQuirks
                    isEnabled = true
                )
                val accountId = accountRepository.createAccount(newAccount)
                newAccount.copy(id = accountId)
            }

            // Step 2: Save credentials to encrypted storage
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
                Log.w(TAG, "Failed to save credentials - encryption may be unavailable")
            }

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
     * Normalize server URL to standard format.
     * - Adds https:// if missing
     * - Removes trailing slash on root-only URLs (preserves on paths for Issue #54)
     * - Validates URL structure
     */
    private fun normalizeServerUrl(serverUrl: String): String {
        var url = serverUrl.trim()

        // Add protocol if missing
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        // Remove trailing slash only on root URLs.
        // Preserve trailing slashes on paths (e.g., /dav/) because some servers
        // like Davis/Symfony require them (Issue #54).
        val parsed = URI(url)
        if (parsed.path.isNullOrBlank() || parsed.path == "/") {
            url = url.trimEnd('/')
        }

        // Validate URL
        val uri = URI(url)
        if (uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("Invalid URL: missing host")
        }

        return url
    }

    /**
     * Extract base host (scheme://host:port) from a URL.
     * E.g., "https://example.com:8080/dav/" -> "https://example.com:8080"
     */
    private fun extractBaseHost(url: String): String {
        val uri = URI(url)
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "${uri.scheme}://${uri.host}$port"
    }

    /**
     * Check if the URL path already contains a known CalDAV path.
     * Uses slash-insensitive comparison so both "/dav" and "/dav/" match.
     */
    private fun urlContainsKnownCaldavPath(url: String): Boolean {
        val uri = URI(url)
        val path = uri.path?.trimEnd('/') ?: return false
        return KNOWN_CALDAV_PATHS.any { knownPath ->
            path == knownPath.trimEnd('/') || path.startsWith(knownPath.trimEnd('/') + "/")
        }
    }

    /**
     * Check if a CalDavResult error is SSL-related.
     */
    private fun isSSLError(error: CalDavResult.Error): Boolean {
        return error.message.contains("SSL", ignoreCase = true) ||
            error.message.contains("certificate", ignoreCase = true) ||
            error.message.contains("TLS", ignoreCase = true)
    }

    /**
     * Probe known CalDAV paths to discover the server endpoint (Issue #54).
     *
     * When well-known discovery fails and the user entered a root URL, tries
     * common CalDAV paths (/dav/, /remote.php/dav/, /dav.php/, etc.).
     *
     * @param client CalDAV client with credentials
     * @param baseUrl URL to extract host from for probing
     * @param triedUrl URL already tried (skipped during probing)
     * @return Pair of (probed URL, principal URL) on success, null if all fail
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

            // Skip if same as already-tried URL (slash-insensitive)
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

            // Stop probing on auth errors — systemic, won't succeed on other paths
            if (result is CalDavResult.Error && result.isAuthError()) {
                Log.d(TAG, "Auth error during probing, stopping: ${result.message}")
                return null
            }

            Log.d(TAG, "Probe failed for $probeUrl: ${(result as? CalDavResult.Error)?.message}")
        }

        return null
    }

    /**
     * Extract a display name from the server URL.
     * Examples:
     * - "https://nextcloud.example.com" -> "nextcloud.example.com"
     * - "https://caldav.fastmail.com" -> "FastMail"
     */
    private fun extractServerDisplayName(serverUrl: String): String {
        val uri = URI(serverUrl)
        val host = uri.host ?: return "CalDAV"

        // Known server display names
        return when {
            host.contains("fastmail", ignoreCase = true) -> "FastMail"
            host.contains("icloud", ignoreCase = true) -> "iCloud"
            host.contains("google", ignoreCase = true) -> "Google"
            host.contains("yahoo", ignoreCase = true) -> "Yahoo"
            host.contains("outlook", ignoreCase = true) -> "Outlook"
            else -> host
        }
    }

    /**
     * Parse color string from CalDAV server to ARGB int.
     * Handles various formats: #RRGGBB, #RRGGBBAA, etc.
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
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse color: $colorString, using default")
            DEFAULT_COLORS[index % DEFAULT_COLORS.size]
        }
    }

    /**
     * Convert exception to user-friendly error message.
     */
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
                val message = e.message?.lowercase() ?: ""
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
                        "Connection failed: ${e.message ?: "Unknown error"}"
                }
            }
        }
    }

    /**
     * Convert CalDavResult.Error to user-friendly error message.
     */
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
