package org.onekash.kashcal.sync.provider.icloud

import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.discovery.AccountDiscoveryService
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.onekash.kashcal.domain.model.AccountProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * iCloud-specific implementation of AccountDiscoveryService.
 *
 * Handles iCloud account discovery and calendar setup:
 * 1. Validate credentials against iCloud CalDAV server
 * 2. Discover principal URL
 * 3. Discover calendar home URL
 * 4. List available calendars
 * 5. Create Account and Calendar entities in Room
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

    override val providerId: String = PROVIDER_ICLOUD

    /**
     * Discover and create an iCloud account with all its calendars.
     *
     * @param username Apple ID email
     * @param password App-specific password from Apple ID settings
     * @return DiscoveryResult with created Account and Calendars, or error
     */
    override suspend fun discoverAndCreateAccount(
        username: String,
        password: String
    ): DiscoveryResult = withContext(Dispatchers.IO) {
        val appleId = username
        val appSpecificPassword = password
        Log.i(TAG, "Starting discovery for: ${appleId.take(3)}***")

        // Create isolated client with credentials (no mutable singleton state)
        val credentials = Credentials(
            username = appleId,
            password = appSpecificPassword,
            serverUrl = ICLOUD_SERVER
        )
        val client = clientFactory.createClient(credentials, icloudQuirks)

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
                return@withContext DiscoveryResult.Error(
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
                return@withContext DiscoveryResult.Error(
                    getErrorMessageForCalDavError(error, "find calendar home")
                )
            }

            val calendarHomeUrl = ICloudUrlNormalizer.normalize(
                (homeResult as CalDavResult.Success).data
            ) ?: (homeResult as CalDavResult.Success).data
            Log.d(TAG, "Calendar home URL: $calendarHomeUrl")

            // Step 3: List calendars
            Log.d(TAG, "Step 3: Listing calendars...")
            val calendarsResult = client.listCalendars(calendarHomeUrl)

            if (calendarsResult.isError()) {
                val error = calendarsResult as CalDavResult.Error
                Log.e(TAG, "Calendar listing failed: ${error.message}")
                return@withContext DiscoveryResult.Error(
                    getErrorMessageForCalDavError(error, "list calendars")
                )
            }

            val discoveredCalendars = (calendarsResult as CalDavResult.Success).data
            Log.i(TAG, "Discovered ${discoveredCalendars.size} calendars")

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

            // Step 5: Create Calendar entities for each discovered calendar
            val createdCalendars = mutableListOf<Calendar>()
            var isFirst = true

            for ((index, calDavCalendar) in discoveredCalendars.withIndex()) {
                // Skip reminders/tasks calendars
                if (calDavCalendar.displayName.lowercase().contains("reminder") ||
                    calDavCalendar.displayName.lowercase().contains("task")) {
                    Log.d(TAG, "Skipping non-calendar: ${calDavCalendar.displayName}")
                    continue
                }

                // Normalize calendar URL to canonical form before storage/lookup
                val normalizedCalendarUrl = ICloudUrlNormalizer.normalize(calDavCalendar.url)
                    ?: calDavCalendar.url

                val existingCalendar = calendarRepository.getCalendarByUrl(normalizedCalendarUrl)
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
                        caldavUrl = normalizedCalendarUrl,
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
            DiscoveryResult.Error(getErrorMessageForException(e))
        }
    }

    /**
     * Convert exception to user-friendly error message.
     */
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
                val message = e.message?.lowercase() ?: ""
                when {
                    message.contains("timeout") ->
                        "Connection timed out. Please try again."
                    message.contains("network") || message.contains("connect") ->
                        "Network error. Please check your internet connection."
                    message.contains("ssl") || message.contains("certificate") ->
                        "Secure connection failed. Please check your network settings."
                    else ->
                        "Connection failed: ${e.message ?: "Unknown error"}"
                }
            }
        }
    }

    /**
     * Convert CalDavResult.Error to user-friendly error message.
     */
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
     * Refresh calendar list for an existing account.
     * Adds new calendars, updates existing ones, removes deleted ones.
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

        // Load credentials from encrypted storage
        val credentials = credentialProvider.getCredentials(accountId)
        if (credentials == null) {
            return@withContext DiscoveryResult.AuthError("Credentials not found. Please sign in again.")
        }

        // Create isolated client with credentials
        val client = clientFactory.createClient(credentials, icloudQuirks)

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
            // Normalize discovered URLs for comparison (server returns regional, DB has canonical)
            val discoveredUrls = discoveredCalendars.map {
                ICloudUrlNormalizer.normalize(it.url) ?: it.url
            }.toSet()

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

                // Normalize calendar URL to canonical form before storage/lookup
                val normalizedCalendarUrl = ICloudUrlNormalizer.normalize(calDavCalendar.url)
                    ?: calDavCalendar.url

                val existingCalendar = calendarRepository.getCalendarByUrl(normalizedCalendarUrl)
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
                        caldavUrl = normalizedCalendarUrl,
                        displayName = calDavCalendar.displayName,
                        color = parseColor(calDavCalendar.color, index),
                        ctag = calDavCalendar.ctag,
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
     */
    override suspend fun removeAccount(accountId: Long) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Removing account: $accountId")
        accountRepository.deleteAccount(accountId)
    }

    /**
     * Remove iCloud account by email.
     */
    override suspend fun removeAccountByEmail(email: String) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Removing iCloud account: $email")
        val account = accountRepository.getAccountByProviderAndEmail(AccountProvider.ICLOUD, email)
        if (account != null) {
            accountRepository.deleteAccount(account.id)
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
            // iCloud returns #RRGGBBAA format
            val cleanColor = colorString.trim()
            when {
                cleanColor.length == 9 && cleanColor.startsWith("#") -> {
                    // #RRGGBBAA -> convert to ARGB
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
}
