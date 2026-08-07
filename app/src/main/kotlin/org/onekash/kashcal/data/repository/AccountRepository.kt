package org.onekash.kashcal.data.repository

import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.credential.AccountCredentials
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * The result of the device-contact purge a contact-sync disable (or account
 * delete) attempts. Lets the UI message honestly instead of always claiming
 * removal.
 */
enum class ContactPurgeOutcome {
    /**
     * The purge ran and the device is verifiably clear of this login's synced
     * contacts (post-purge count read 0 after a delete that did not fail).
     */
    PURGED,

    /**
     * No purge was attempted: either this was an enable, a non-contacts account,
     * or a still-syncing same-email CardDAV sibling legitimately keeps the shared
     * contacts account (so its contacts must stay).
     */
    NOT_ATTEMPTED,

    /**
     * A purge was attempted but could not be confirmed clean — the scoped delete
     * failed (e.g. revoked WRITE_CONTACTS), or rows still remained after the
     * retry. The UI must NOT claim contacts were removed.
     */
    INCOMPLETE,
}

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

    /**
     * Get account by ID as reactive Flow.
     * Used for AccountDetailSheet to auto-update on sync metadata changes.
     */
    fun getAccountByIdFlow(id: Long): Flow<Account?>

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
     * Get account by provider, email, and home set URL.
     * Used for CalDAV where the same username can exist on different servers.
     * For iCloud/ICS/CONTACTS, use [getAccountByProviderAndEmail] instead.
     */
    suspend fun getAccountByProviderEmailAndHomeSetUrl(
        provider: AccountProvider,
        email: String,
        homeSetUrl: String
    ): Account?

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
     * Persist the CalDAV `calendar-user-address-set` (RFC 6638 §2.4.1)
     * for the given account. Stored verbatim — see [Account.calendarUserAddresses].
     */
    suspend fun updateCalendarUserAddresses(accountId: Long, addresses: List<String>)

    /**
     * Persist the principal's scheduling Outbox URL (RFC 6638 §2.1.1) for
     * the given account. Null clears it (server advertises no outbox).
     */
    suspend fun updateScheduleOutboxUrl(accountId: Long, outboxUrl: String?)

    /**
     * Set account enabled state.
     */
    suspend fun setEnabled(accountId: Long, enabled: Boolean)

    /**
     * Turn CardDAV contact sync on or off for a login.
     *
     * Enabling registers the login's dedicated contacts system account (so
     * Android surfaces the source and never purges its RawContacts) and persists
     * the per-account flag; disabling removes that system account (which also
     * purges the contacts Android holds under it) and clears the flag. Both are
     * idempotent. A no-op when the account no longer exists.
     *
     * @return the outcome of the device-contact purge, so callers can message
     *   honestly (a disable that a still-syncing sibling blocks did NOT remove
     *   contacts; a purge that couldn't be verified must not claim it did). See
     *   [ContactPurgeOutcome].
     */
    suspend fun setContactSyncEnabled(accountId: Long, enabled: Boolean): ContactPurgeOutcome

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
