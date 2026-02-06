package org.onekash.kashcal.data.repository

import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.credential.AccountCredentials
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Single source of truth for Account operations.
 *
 * Replaces direct DAO access for accounts. Handles:
 * - CRUD operations on Account entity
 * - Credential storage delegation
 * - Cleanup on account deletion (reminders, pending operations, WorkManager jobs)
 *
 * Usage:
 * ```kotlin
 * class MyService @Inject constructor(
 *     private val accountRepository: AccountRepository
 * )
 *
 * // Delete account with full cleanup
 * accountRepository.deleteAccount(accountId)
 * ```
 */
interface AccountRepository {

    // ========== Reactive Queries (Flow) ==========

    /**
     * Get all accounts as reactive Flow.
     * Emits new list when accounts change.
     */
    fun getAllAccountsFlow(): Flow<List<Account>>

    /**
     * Get accounts by provider type as Flow.
     * Used to list CalDAV accounts, iCloud accounts, etc.
     */
    fun getAccountsByProviderFlow(provider: AccountProvider): Flow<List<Account>>

    /**
     * Get account count by provider as Flow.
     * Used for Settings UI badges.
     */
    fun getAccountCountByProviderFlow(provider: AccountProvider): Flow<Int>

    // ========== One-Shot Queries ==========

    /**
     * Get account by ID.
     */
    suspend fun getAccountById(id: Long): Account?

    /**
     * Get account by provider and email (unique constraint).
     */
    suspend fun getAccountByProviderAndEmail(provider: AccountProvider, email: String): Account?

    /**
     * Get all enabled accounts for sync.
     */
    suspend fun getEnabledAccounts(): List<Account>

    /**
     * Get all accounts (one-shot).
     */
    suspend fun getAllAccounts(): List<Account>

    /**
     * Get accounts by provider (one-shot).
     */
    suspend fun getAccountsByProvider(provider: AccountProvider): List<Account>

    /**
     * Count accounts with matching display name.
     * Used for uniqueness validation.
     */
    suspend fun countByDisplayName(displayName: String, excludeAccountId: Long? = null): Int

    // ========== Write Operations ==========

    /**
     * Create new account. Returns row ID.
     */
    suspend fun createAccount(account: Account): Long

    /**
     * Update existing account.
     */
    suspend fun updateAccount(account: Account)

    /**
     * Delete account with full cleanup.
     *
     * Performs in order:
     * 1. Cancel WorkManager sync jobs
     * 2. Cancel all reminders for account's events
     * 3. Delete pending operations for account's events
     * 4. Delete credentials
     * 5. Cascade delete account → calendars → events
     *
     * @param accountId Account ID to delete
     */
    suspend fun deleteAccount(accountId: Long)

    // ========== Sync Metadata ==========

    /**
     * Record successful sync.
     */
    suspend fun recordSyncSuccess(accountId: Long, timestamp: Long)

    /**
     * Record sync failure.
     */
    suspend fun recordSyncFailure(accountId: Long, timestamp: Long)

    /**
     * Update CalDAV discovery URLs.
     */
    suspend fun updateCalDavUrls(accountId: Long, principalUrl: String?, homeSetUrl: String?)

    /**
     * Set account enabled state.
     */
    suspend fun setEnabled(accountId: Long, enabled: Boolean)

    // ========== Credentials (Delegated) ==========

    /**
     * Save credentials for account.
     */
    suspend fun saveCredentials(accountId: Long, credentials: AccountCredentials): Boolean

    /**
     * Get credentials for account.
     */
    suspend fun getCredentials(accountId: Long): AccountCredentials?

    /**
     * Check if credentials exist for account.
     */
    suspend fun hasCredentials(accountId: Long): Boolean

    /**
     * Delete credentials for account.
     */
    suspend fun deleteCredentials(accountId: Long)
}
