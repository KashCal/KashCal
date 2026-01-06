package org.onekash.kashcal.sync.discovery

import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar

/**
 * Result of account discovery operation.
 */
sealed class DiscoveryResult {
    /**
     * Discovery succeeded - account and calendars created.
     */
    data class Success(
        val account: Account,
        val calendars: List<Calendar>
    ) : DiscoveryResult()

    /**
     * Authentication failed - invalid credentials.
     */
    data class AuthError(val message: String) : DiscoveryResult()

    /**
     * Discovery failed - network or server error.
     */
    data class Error(val message: String) : DiscoveryResult()
}

/**
 * Interface for account discovery services.
 *
 * Each calendar provider (iCloud, Google, etc.) implements this interface
 * to handle the discovery and setup of accounts and calendars.
 *
 * Discovery flow:
 * 1. Validate credentials against server
 * 2. Discover principal/user URL
 * 3. Discover calendar home URL
 * 4. List available calendars
 * 5. Create Account and Calendar entities in Room
 */
interface AccountDiscoveryService {
    /**
     * Provider identifier (e.g., "icloud", "google").
     */
    val providerId: String

    /**
     * Discover and create an account with all its calendars.
     *
     * @param username Username or email for the account
     * @param password Password or app-specific password
     * @return DiscoveryResult with created Account and Calendars, or error
     */
    suspend fun discoverAndCreateAccount(
        username: String,
        password: String
    ): DiscoveryResult

    /**
     * Refresh calendar list for an existing account.
     * Adds new calendars, updates existing ones, removes deleted ones.
     *
     * @param accountId The account ID to refresh
     * @return DiscoveryResult with updated Account and Calendars
     */
    suspend fun refreshCalendars(accountId: Long): DiscoveryResult

    /**
     * Remove all data for an account (used during sign-out).
     *
     * @param accountId The account ID to remove
     */
    suspend fun removeAccount(accountId: Long)

    /**
     * Remove account by email address.
     *
     * Convenience method for sign-out flow when only email is available.
     *
     * @param email The email address of the account to remove
     */
    suspend fun removeAccountByEmail(email: String)
}
