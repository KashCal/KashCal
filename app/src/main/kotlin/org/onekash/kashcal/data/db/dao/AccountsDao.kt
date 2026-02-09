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
 * Data Access Object for Account operations.
 *
 * Provides CRUD operations and queries for CalDAV accounts.
 * All suspend functions run on IO dispatcher via Room.
 */
@Dao
interface AccountsDao {

    // ========== Read Operations ==========

    /**
     * Get all accounts as reactive Flow.
     * Emits new list when accounts change.
     */
    @Query("SELECT * FROM accounts ORDER BY created_at ASC")
    fun getAll(): Flow<List<Account>>

    /**
     * Get all accounts as one-shot query.
     */
    @Query("SELECT * FROM accounts ORDER BY created_at ASC")
    suspend fun getAllOnce(): List<Account>

    /**
     * Get account by ID.
     */
    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): Account?

    /**
     * Get account by ID as reactive Flow.
     * Emits new value when the account row changes.
     */
    @Query("SELECT * FROM accounts WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Account?>

    /**
     * Get account by provider and email (unique constraint).
     * Room TypeConverter handles AccountProvider → String conversion.
     */
    @Query("SELECT * FROM accounts WHERE provider = :provider AND email = :email")
    suspend fun getByProviderAndEmail(provider: AccountProvider, email: String): Account?

    /**
     * Get accounts by provider type.
     * Used to list all CalDAV accounts, all iCloud accounts, etc.
     */
    @Query("SELECT * FROM accounts WHERE provider = :provider ORDER BY created_at ASC")
    suspend fun getByProvider(provider: AccountProvider): List<Account>

    /**
     * Get count of accounts by provider type as Flow.
     * Used for Settings UI to show account count badges.
     */
    @Query("SELECT COUNT(*) FROM accounts WHERE provider = :provider")
    fun getAccountCountByProvider(provider: AccountProvider): Flow<Int>

    /**
     * Get all enabled accounts for sync.
     */
    @Query("SELECT * FROM accounts WHERE is_enabled = 1 ORDER BY created_at ASC")
    suspend fun getEnabledAccounts(): List<Account>

    /**
     * Get accounts with sync errors (consecutive failures > 0).
     */
    @Query("SELECT * FROM accounts WHERE consecutive_sync_failures > 0")
    suspend fun getAccountsWithSyncErrors(): List<Account>

    /**
     * Count accounts with matching display name (case-insensitive).
     * Used for uniqueness validation when creating/editing accounts.
     *
     * @param displayName Display name to check
     * @param excludeAccountId Account ID to exclude (for edit mode, null for new accounts)
     * @return Number of accounts with matching display name
     */
    @Query("""
        SELECT COUNT(*) FROM accounts
        WHERE LOWER(display_name) = LOWER(:displayName)
        AND (:excludeAccountId IS NULL OR id != :excludeAccountId)
    """)
    suspend fun countByDisplayName(displayName: String, excludeAccountId: Long? = null): Int

    // ========== Write Operations ==========

    /**
     * Insert new account. Returns row ID.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: Account): Long

    /**
     * Update existing account.
     */
    @Update
    suspend fun update(account: Account)

    /**
     * Delete account.
     * CASCADE: All calendars and their events will be deleted.
     */
    @Delete
    suspend fun delete(account: Account)

    /**
     * Delete account by ID.
     */
    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: Long)

    // ========== Sync Metadata Updates ==========

    /**
     * Update last sync timestamp.
     */
    @Query("UPDATE accounts SET last_sync_at = :timestamp WHERE id = :id")
    suspend fun updateLastSyncAt(id: Long, timestamp: Long)

    /**
     * Record successful sync.
     */
    @Query("""
        UPDATE accounts
        SET last_sync_at = :timestamp,
            last_successful_sync_at = :timestamp,
            consecutive_sync_failures = 0
        WHERE id = :id
    """)
    suspend fun recordSyncSuccess(id: Long, timestamp: Long)

    /**
     * Record sync failure with increment.
     */
    @Query("""
        UPDATE accounts
        SET last_sync_at = :timestamp,
            consecutive_sync_failures = consecutive_sync_failures + 1
        WHERE id = :id
    """)
    suspend fun recordSyncFailure(id: Long, timestamp: Long)

    /**
     * Update CalDAV URLs after discovery.
     */
    @Query("""
        UPDATE accounts
        SET principal_url = :principalUrl,
            home_set_url = :homeSetUrl
        WHERE id = :id
    """)
    suspend fun updateCalDavUrls(id: Long, principalUrl: String?, homeSetUrl: String?)

    /**
     * Update account enabled state.
     */
    @Query("UPDATE accounts SET is_enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    /**
     * Get accounts by provider as reactive Flow.
     * Emits new list when accounts change.
     */
    @Query("SELECT * FROM accounts WHERE provider = :provider ORDER BY created_at ASC")
    fun getByProviderFlow(provider: AccountProvider): Flow<List<Account>>
}
