package org.onekash.kashcal.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Reads and writes the accounts table, for every provider. Room runs the suspend functions off
 * the main thread.
 */
@Dao
interface AccountsDao {

    // ========== Read Operations ==========

    @Query("SELECT * FROM accounts ORDER BY created_at ASC")
    fun getAll(): Flow<List<Account>>

    @Query("SELECT * FROM accounts ORDER BY created_at ASC")
    suspend fun getAllOnce(): List<Account>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): Account?

    @Query("SELECT * FROM accounts WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Account?>

    /**
     * Returns an account with this provider and email. The unique index also includes
     * `home_set_url`, so for CalDAV, where one username can exist on different servers, use
     * [getByProviderEmailAndHomeSetUrl].
     */
    @Query("SELECT * FROM accounts WHERE provider = :provider AND email = :email")
    suspend fun getByProviderAndEmail(provider: AccountProvider, email: String): Account?

    @Query("SELECT * FROM accounts WHERE provider = :provider AND email = :email AND home_set_url = :homeSetUrl")
    suspend fun getByProviderEmailAndHomeSetUrl(
        provider: AccountProvider,
        email: String,
        homeSetUrl: String
    ): Account?

    @Query("SELECT * FROM accounts WHERE provider = :provider ORDER BY created_at ASC")
    suspend fun getByProvider(provider: AccountProvider): List<Account>

    /** Account settings collect this for the CalDAV account count. */
    @Query("SELECT COUNT(*) FROM accounts WHERE provider = :provider")
    fun getAccountCountByProvider(provider: AccountProvider): Flow<Int>

    @Query("SELECT * FROM accounts WHERE is_enabled = 1 ORDER BY created_at ASC")
    suspend fun getEnabledAccounts(): List<Account>

    @Query("SELECT * FROM accounts WHERE consecutive_sync_failures > 0")
    suspend fun getAccountsWithSyncErrors(): List<Account>

    /**
     * Counts accounts whose display name matches case-insensitively, for the uniqueness check
     * on create and edit.
     *
     * @param excludeAccountId the account being edited, or null for a new account
     */
    @Query("""
        SELECT COUNT(*) FROM accounts
        WHERE LOWER(display_name) = LOWER(:displayName)
        AND (:excludeAccountId IS NULL OR id != :excludeAccountId)
    """)
    suspend fun countByDisplayName(displayName: String, excludeAccountId: Long? = null): Int

    // ========== Write Operations ==========

    /** Returns the new row ID. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: Account): Long

    @Update
    suspend fun update(account: Account)

    /** Deletes the account; its calendars (with their events) and address books cascade. */
    @Delete
    suspend fun delete(account: Account)

    /** Deletes the account like [delete]. */
    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: Long)

    // ========== Sync Metadata Updates ==========

    @Query("UPDATE accounts SET last_sync_at = :timestamp WHERE id = :id")
    suspend fun updateLastSyncAt(id: Long, timestamp: Long)

    /** Sets both sync timestamps and resets the failure count. */
    @Query("""
        UPDATE accounts
        SET last_sync_at = :timestamp,
            last_successful_sync_at = :timestamp,
            consecutive_sync_failures = 0
        WHERE id = :id
    """)
    suspend fun recordSyncSuccess(id: Long, timestamp: Long)

    /** Sets the last sync time and increments the failure count. */
    @Query("""
        UPDATE accounts
        SET last_sync_at = :timestamp,
            consecutive_sync_failures = consecutive_sync_failures + 1
        WHERE id = :id
    """)
    suspend fun recordSyncFailure(id: Long, timestamp: Long)

    /** Stores the discovered CalDAV URLs; a null clears the column. */
    @Query("""
        UPDATE accounts
        SET principal_url = :principalUrl,
            home_set_url = :homeSetUrl
        WHERE id = :id
    """)
    suspend fun updateCalDavUrls(id: Long, principalUrl: String?, homeSetUrl: String?)

    /**
     * Stores the account's calendar-user-address-set (RFC 6638 §2.4.1). It writes one column, so
     * it and concurrent sync writes to other columns can't overwrite each other, as a
     * read-modify-write of the whole row could.
     */
    @Query("UPDATE accounts SET calendar_user_addresses = :addresses WHERE id = :id")
    suspend fun updateCalendarUserAddresses(id: Long, addresses: List<String>)

    /**
     * Stores the principal's scheduling Outbox URL (RFC 6638 §2.1.1); null clears it. Writes
     * one column for the reason on [updateCalendarUserAddresses].
     */
    @Query("UPDATE accounts SET schedule_outbox_url = :outboxUrl WHERE id = :id")
    suspend fun updateScheduleOutboxUrl(id: Long, outboxUrl: String?)

    @Query("UPDATE accounts SET is_enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    /** Sets the per-login CardDAV contact-sync opt-in. */
    @Query("UPDATE accounts SET contact_sync_enabled = :enabled WHERE id = :id")
    suspend fun setContactSyncEnabled(id: Long, enabled: Boolean)

    @Query("SELECT * FROM accounts WHERE provider = :provider ORDER BY created_at ASC")
    fun getByProviderFlow(provider: AccountProvider): Flow<List<Account>>
}
