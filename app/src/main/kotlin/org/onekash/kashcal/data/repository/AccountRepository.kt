package org.onekash.kashcal.data.repository

import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.credential.AccountCredentials
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Reports what the device-contact purge of [AccountRepository.setContactSyncEnabled] did, so the
 * UI claims removal only when it happened.
 */
enum class ContactPurgeOutcome {
    /** The scoped delete succeeded and a count afterwards read 0 synced contacts. */
    PURGED,

    /**
     * No purge was attempted: an enable, a missing account, a non-contacts account, or a
     * same-email CardDAV login still syncing contacts into the shared contacts account.
     */
    NOT_ATTEMPTED,

    /**
     * A purge ran but couldn't be confirmed clean: the scoped delete failed (for example,
     * WRITE_CONTACTS revoked), or rows remained afterwards. The UI must not claim contacts were
     * removed.
     */
    INCOMPLETE,
}

/**
 * Reads and writes accounts in place of the accounts DAO: CRUD, sync metadata, contact-sync
 * enrolment, credentials (delegated to the credential store), and account deletion with its
 * cleanup ([deleteAccount]).
 */
interface AccountRepository {

    // ========== Reactive Queries (Flow) ==========

    fun getAllAccountsFlow(): Flow<List<Account>>

    fun getAccountsByProviderFlow(provider: AccountProvider): Flow<List<Account>>

    /** Emits the number of accounts of [provider]; Settings reads the CalDAV count. */
    fun getAccountCountByProviderFlow(provider: AccountProvider): Flow<Int>

    /** Emits the account on every change, so the account detail sheet follows sync metadata. */
    fun getAccountByIdFlow(id: Long): Flow<Account?>

    // ========== One-Shot Queries ==========

    suspend fun getAccountById(id: Long): Account?

    /**
     * Returns an account of [provider] with [email]. Accounts are unique on provider, email and
     * home set URL, so for CalDAV, where two may match, use
     * [getAccountByProviderEmailAndHomeSetUrl].
     */
    suspend fun getAccountByProviderAndEmail(provider: AccountProvider, email: String): Account?

    /**
     * Returns the account matching provider, email and home set URL. CalDAV needs all three:
     * the same username can exist on different servers. For iCloud, ICS and CONTACTS, use
     * [getAccountByProviderAndEmail].
     */
    suspend fun getAccountByProviderEmailAndHomeSetUrl(
        provider: AccountProvider,
        email: String,
        homeSetUrl: String
    ): Account?

    suspend fun getEnabledAccounts(): List<Account>

    suspend fun getAllAccounts(): List<Account>

    suspend fun getAccountsByProvider(provider: AccountProvider): List<Account>

    /**
     * Counts accounts named [displayName], excluding [excludeAccountId]; CalDAV setup uses it to
     * keep display names unique.
     */
    suspend fun countByDisplayName(displayName: String, excludeAccountId: Long? = null): Int

    // ========== Write Operations ==========

    /** Inserts [account] and returns its row ID. */
    suspend fun createAccount(account: Account): Long

    suspend fun updateAccount(account: Account)

    /**
     * Deletes the account and its data, in order:
     * 1. Cancels its sync work and the shared one-shot and expedited sync work.
     * 2. Cancels reminders and deletes pending operations of its calendars' master events that
     *    aren't pending delete.
     * 3. Deletes its credentials.
     * 4. Deletes the account row, which cascades to its calendars and address books, their
     *    events, and the events' occurrences, attendees and scheduled reminders.
     * 5. Cancels the shared periodic sync and contact-sync work when no account that can sync
     *    remains.
     * 6. For a CardDAV-capable account, purges its device contacts and contacts system account
     *    and clears same-email logins' address books, unless a same-email CardDAV login still
     *    syncs contacts into it.
     */
    suspend fun deleteAccount(accountId: Long)

    // ========== Sync Metadata ==========

    suspend fun recordSyncSuccess(accountId: Long, timestamp: Long)

    suspend fun recordSyncFailure(accountId: Long, timestamp: Long)

    suspend fun updateCalDavUrls(accountId: Long, principalUrl: String?, homeSetUrl: String?)

    /**
     * Stores the CalDAV `calendar-user-address-set` (RFC 6638 §2.4.1) verbatim; see
     * [Account.calendarUserAddresses].
     */
    suspend fun updateCalendarUserAddresses(accountId: Long, addresses: List<String>)

    /**
     * Stores the principal's scheduling Outbox URL (RFC 6638 §2.1.1). Null clears it: the server
     * advertises no outbox.
     */
    suspend fun updateScheduleOutboxUrl(accountId: Long, outboxUrl: String?)

    suspend fun setEnabled(accountId: Long, enabled: Boolean)

    /**
     * Turns CardDAV contact sync on or off for a login.
     *
     * Enabling registers the login's contacts system account (so Android surfaces the source and
     * never purges its RawContacts) and sets the per-account flag. Disabling deletes the login's
     * device contacts and removes that system account, unless a same-email CardDAV login still
     * syncs contacts into it, and clears the flag. Both are idempotent; a no-op when the account
     * no longer exists.
     *
     * @return what the device-contact purge did ([ContactPurgeOutcome]), so the caller doesn't
     *   claim contacts were removed when a sibling kept them or the purge couldn't be verified.
     */
    suspend fun setContactSyncEnabled(accountId: Long, enabled: Boolean): ContactPurgeOutcome

    // ========== Credentials (Delegated) ==========

    suspend fun saveCredentials(accountId: Long, credentials: AccountCredentials): Boolean

    suspend fun getCredentials(accountId: Long): AccountCredentials?

    suspend fun hasCredentials(accountId: Long): Boolean

    suspend fun deleteCredentials(accountId: Long)
}
