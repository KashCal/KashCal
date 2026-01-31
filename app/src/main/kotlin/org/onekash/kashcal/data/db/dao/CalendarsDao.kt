package org.onekash.kashcal.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.entity.Calendar

/**
 * Data Access Object for Calendar operations.
 *
 * Provides CRUD operations and queries for calendar collections.
 */
@Dao
interface CalendarsDao {

    // ========== Read Operations ==========

    /**
     * Get all calendars as reactive Flow.
     */
    @Query("SELECT * FROM calendars ORDER BY sort_order ASC, display_name ASC")
    fun getAll(): Flow<List<Calendar>>

    /**
     * Get all calendars as one-shot query.
     */
    @Query("SELECT * FROM calendars ORDER BY sort_order ASC, display_name ASC")
    suspend fun getAllOnce(): List<Calendar>

    /**
     * Get calendar by ID.
     */
    @Query("SELECT * FROM calendars WHERE id = :id")
    suspend fun getById(id: Long): Calendar?

    /**
     * Get multiple calendars by IDs.
     * Used by PushStrategy to batch load calendars.
     */
    @Query("SELECT * FROM calendars WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Calendar>

    /**
     * Get calendar by CalDAV URL (unique).
     */
    @Query("SELECT * FROM calendars WHERE caldav_url = :caldavUrl")
    suspend fun getByCaldavUrl(caldavUrl: String): Calendar?

    /**
     * Get calendars for a specific account.
     */
    @Query("SELECT * FROM calendars WHERE account_id = :accountId ORDER BY sort_order ASC")
    fun getByAccountId(accountId: Long): Flow<List<Calendar>>

    /**
     * Get calendars for account (one-shot).
     */
    @Query("SELECT * FROM calendars WHERE account_id = :accountId ORDER BY sort_order ASC")
    suspend fun getByAccountIdOnce(accountId: Long): List<Calendar>

    /**
     * Get all visible calendars.
     */
    @Query("SELECT * FROM calendars WHERE is_visible = 1 ORDER BY sort_order ASC")
    fun getVisibleCalendars(): Flow<List<Calendar>>

    /**
     * Get all enabled (visible, non-read-only) calendars for sync.
     */
    @Query("SELECT * FROM calendars WHERE is_visible = 1")
    suspend fun getEnabledCalendars(): List<Calendar>

    /**
     * Get default calendar for an account.
     */
    @Query("SELECT * FROM calendars WHERE account_id = :accountId AND is_default = 1 LIMIT 1")
    suspend fun getDefaultCalendar(accountId: Long): Calendar?

    /**
     * Get any default calendar (for quick event creation).
     */
    @Query("SELECT * FROM calendars WHERE is_default = 1 LIMIT 1")
    suspend fun getAnyDefaultCalendar(): Calendar?

    // ========== Write Operations ==========

    /**
     * Insert new calendar. Returns row ID.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(calendar: Calendar): Long

    /**
     * Insert or update calendar by CalDAV URL.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(calendar: Calendar): Long

    /**
     * Update existing calendar.
     */
    @Update
    suspend fun update(calendar: Calendar)

    /**
     * Delete calendar.
     * CASCADE: All events and occurrences will be deleted.
     */
    @Delete
    suspend fun delete(calendar: Calendar)

    /**
     * Delete calendar by ID.
     */
    @Query("DELETE FROM calendars WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Delete all calendars for an account.
     */
    @Query("DELETE FROM calendars WHERE account_id = :accountId")
    suspend fun deleteByAccountId(accountId: Long)

    // ========== Sync Metadata Updates ==========

    /**
     * Update sync token and ctag after sync.
     */
    @Query("UPDATE calendars SET sync_token = :syncToken, ctag = :ctag WHERE id = :id")
    suspend fun updateSyncToken(id: Long, syncToken: String?, ctag: String?)

    /**
     * Update ctag only (for servers without sync-token support).
     */
    @Query("UPDATE calendars SET ctag = :ctag WHERE id = :id")
    suspend fun updateCtag(id: Long, ctag: String?)

    // ========== Display Settings ==========

    /**
     * Set calendar visibility.
     */
    @Query("UPDATE calendars SET is_visible = :visible WHERE id = :id")
    suspend fun setVisible(id: Long, visible: Boolean)

    /**
     * Set calendar as default (clears other defaults in same account).
     */
    @Query("UPDATE calendars SET is_default = (id = :calendarId) WHERE account_id = :accountId")
    suspend fun setDefaultCalendar(accountId: Long, calendarId: Long)

    /**
     * Update calendar color.
     */
    @Query("UPDATE calendars SET color = :color WHERE id = :id")
    suspend fun updateColor(id: Long, color: Int)

    /**
     * Update display name.
     */
    @Query("UPDATE calendars SET display_name = :displayName WHERE id = :id")
    suspend fun updateDisplayName(id: Long, displayName: String)

    /**
     * Update sort order.
     */
    @Query("UPDATE calendars SET sort_order = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    /**
     * Update CalDAV URL (for iCloud URL normalization migration).
     */
    @Query("UPDATE calendars SET caldav_url = :caldavUrl WHERE id = :id")
    suspend fun updateCaldavUrl(id: Long, caldavUrl: String)

    // ========== Provider-based Queries ==========

    /**
     * Get calendars by provider (e.g., "icloud", "local").
     * Joins with accounts table to filter by provider type.
     */
    @Query("""
        SELECT c.* FROM calendars c
        INNER JOIN accounts a ON c.account_id = a.id
        WHERE a.provider = :provider
        ORDER BY c.sort_order ASC
    """)
    fun getCalendarsByProvider(provider: String): Flow<List<Calendar>>

    /**
     * Get calendar count by provider (e.g., count iCloud calendars).
     */
    @Query("""
        SELECT COUNT(*) FROM calendars c
        INNER JOIN accounts a ON c.account_id = a.id
        WHERE a.provider = :provider
    """)
    fun getCalendarCountByProvider(provider: String): Flow<Int>
}
