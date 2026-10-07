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
 * Reads and writes the `calendars` table.
 *
 * Deleting a calendar row cascades to its events (and their occurrences) and to its ICS
 * subscription row.
 */
@Dao
interface CalendarsDao {

    // ========== Read Operations ==========

    /** Emits every calendar, ordered by sort order then display name. */
    @Query("SELECT * FROM calendars ORDER BY sort_order ASC, display_name ASC")
    fun getAll(): Flow<List<Calendar>>

    /** One-shot variant of [getAll]. */
    @Query("SELECT * FROM calendars ORDER BY sort_order ASC, display_name ASC")
    suspend fun getAllOnce(): List<Calendar>

    @Query("SELECT * FROM calendars WHERE id = :id")
    suspend fun getById(id: Long): Calendar?

    /** Returns the calendars with [ids], in no particular order; unknown ids are skipped. */
    @Query("SELECT * FROM calendars WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Calendar>

    /** Returns the calendar at [caldavUrl], or null. `caldav_url` has a unique index. */
    @Query("SELECT * FROM calendars WHERE caldav_url = :caldavUrl")
    suspend fun getByCaldavUrl(caldavUrl: String): Calendar?

    @Query("SELECT * FROM calendars WHERE account_id = :accountId ORDER BY sort_order ASC")
    fun getByAccountId(accountId: Long): Flow<List<Calendar>>

    /** One-shot variant of [getByAccountId]. */
    @Query("SELECT * FROM calendars WHERE account_id = :accountId ORDER BY sort_order ASC")
    suspend fun getByAccountIdOnce(accountId: Long): List<Calendar>

    @Query("SELECT * FROM calendars WHERE is_visible = 1 ORDER BY sort_order ASC")
    fun getVisibleCalendars(): Flow<List<Calendar>>

    /** Returns the visible calendars, read-only ones included. */
    @Query("SELECT * FROM calendars WHERE is_visible = 1")
    suspend fun getEnabledCalendars(): List<Calendar>

    @Query("SELECT * FROM calendars WHERE account_id = :accountId AND is_default = 1 LIMIT 1")
    suspend fun getDefaultCalendar(accountId: Long): Calendar?

    /** Returns the default calendar of an arbitrary account, or null if no calendar is default. */
    @Query("SELECT * FROM calendars WHERE is_default = 1 LIMIT 1")
    suspend fun getAnyDefaultCalendar(): Calendar?

    // ========== Write Operations ==========

    /** Inserts [calendar] and returns its row id; throws on an id or `caldav_url` conflict. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(calendar: Calendar): Long

    /** Inserts [calendar], replacing any row that conflicts on id or `caldav_url`. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(calendar: Calendar): Long

    @Update
    suspend fun update(calendar: Calendar)

    @Delete
    suspend fun delete(calendar: Calendar)

    @Query("DELETE FROM calendars WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM calendars WHERE account_id = :accountId")
    suspend fun deleteByAccountId(accountId: Long)

    // ========== Sync Metadata Updates ==========

    /** Stores the sync-token and ctag after a sync. */
    @Query("UPDATE calendars SET sync_token = :syncToken, ctag = :ctag WHERE id = :id")
    suspend fun updateSyncToken(id: Long, syncToken: String?, ctag: String?)

    /** Stores the ctag only, for servers without sync-token support. */
    @Query("UPDATE calendars SET ctag = :ctag WHERE id = :id")
    suspend fun updateCtag(id: Long, ctag: String?)

    /**
     * Stores [Calendar.autoScheduleSupported] (RFC 6638 §2), whose tri-state is documented there.
     * A single-column UPDATE, so it can't race a read-modify-write of the row.
     */
    @Query("UPDATE calendars SET auto_schedule_supported = :supported WHERE id = :id")
    suspend fun updateAutoScheduleSupported(id: Long, supported: Boolean?)

    /**
     * Sets [Calendar.initialPullDone]. Writes only this column, so it can't overwrite others with a
     * stale copy; a whole-row update from a copy read before it can still reset the flag.
     */
    @Query("UPDATE calendars SET initial_pull_done = 1 WHERE id = :id")
    suspend fun markInitialPullDone(id: Long)

    // ========== Display Settings ==========

    @Query("UPDATE calendars SET is_visible = :visible WHERE id = :id")
    suspend fun setVisible(id: Long, visible: Boolean)

    /** Sets the visibility of every calendar in the account. */
    @Query("UPDATE calendars SET is_visible = :visible WHERE account_id = :accountId")
    suspend fun setVisibleForAccount(accountId: Long, visible: Boolean)

    /** Makes [calendarId] the account's default and clears the flag on its other calendars. */
    @Query("UPDATE calendars SET is_default = (id = :calendarId) WHERE account_id = :accountId")
    suspend fun setDefaultCalendar(accountId: Long, calendarId: Long)

    @Query("UPDATE calendars SET color = :color WHERE id = :id")
    suspend fun updateColor(id: Long, color: Int)

    @Query("UPDATE calendars SET display_name = :displayName WHERE id = :id")
    suspend fun updateDisplayName(id: Long, displayName: String)

    /**
     * Refreshes calendar metadata from a server probe. A null argument leaves that column
     * unchanged, so a field the server didn't return keeps its local value.
     */
    @Query(
        """
        UPDATE calendars SET
            color = COALESCE(:color, color),
            display_name = COALESCE(:displayName, display_name),
            is_read_only = COALESCE(:isReadOnly, is_read_only)
        WHERE id = :id
        """
    )
    suspend fun updateMetadata(
        id: Long,
        color: Int?,
        displayName: String?,
        isReadOnly: Boolean?
    )

    @Query("UPDATE calendars SET sort_order = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    /** Rewrites the CalDAV URL; the iCloud regional-URL normalization uses it. */
    @Query("UPDATE calendars SET caldav_url = :caldavUrl WHERE id = :id")
    suspend fun updateCaldavUrl(id: Long, caldavUrl: String)

    // ========== Provider-based Queries ==========

    /**
     * Emits the calendars of accounts whose provider is [provider], the stored lowercase name
     * (for example "icloud", "local").
     */
    @Query("""
        SELECT c.* FROM calendars c
        INNER JOIN accounts a ON c.account_id = a.id
        WHERE a.provider = :provider
        ORDER BY c.sort_order ASC
    """)
    fun getCalendarsByProvider(provider: String): Flow<List<Calendar>>

    /** Emits the calendar count for [provider], matched like [getCalendarsByProvider]. */
    @Query("""
        SELECT COUNT(*) FROM calendars c
        INNER JOIN accounts a ON c.account_id = a.id
        WHERE a.provider = :provider
    """)
    fun getCalendarCountByProvider(provider: String): Flow<Int>
}
