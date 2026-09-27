package org.onekash.kashcal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.entity.SyncLog

/**
 * Reads and writes the sync diagnostics log.
 *
 * The sync worker prunes it with [deleteOldLogs] after each sync to bound the database size.
 */
@Dao
interface SyncLogsDao {

    // ========== Read Operations ==========

    /** Observes the newest [limit] logs, newest first. */
    @Query("SELECT * FROM sync_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 100): Flow<List<SyncLog>>

    /** Returns the newest [limit] logs, newest first. */
    @Query("SELECT * FROM sync_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogsOnce(limit: Int = 100): List<SyncLog>

    /** Returns [calendarId]'s newest [limit] logs, newest first. */
    @Query("SELECT * FROM sync_logs WHERE calendar_id = :calendarId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLogsForCalendar(calendarId: Long, limit: Int = 50): List<SyncLog>

    /** Returns every log for [eventUid], newest first. */
    @Query("SELECT * FROM sync_logs WHERE event_uid = :eventUid ORDER BY timestamp DESC")
    suspend fun getLogsForEvent(eventUid: String): List<SyncLog>

    /** Returns the newest [limit] logs whose result isn't SUCCESS, including SKIPPED. */
    @Query("SELECT * FROM sync_logs WHERE result != 'SUCCESS' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getErrorLogs(limit: Int = 50): List<SyncLog>

    /** Returns the logs from [startTs] to [endTs] inclusive, newest first. */
    @Query("""
        SELECT * FROM sync_logs
        WHERE timestamp >= :startTs AND timestamp <= :endTs
        ORDER BY timestamp DESC
    """)
    suspend fun getLogsInRange(startTs: Long, endTs: Long): List<SyncLog>

    /** Returns the newest [limit] HTTP 412 conflict logs. */
    @Query("SELECT * FROM sync_logs WHERE result = 'ERROR_412' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getConflictLogs(limit: Int = 20): List<SyncLog>

    @Query("SELECT COUNT(*) FROM sync_logs")
    suspend fun getCount(): Int

    /** Returns how many logs since [since] have a result other than SUCCESS. */
    @Query("SELECT COUNT(*) FROM sync_logs WHERE result != 'SUCCESS' AND timestamp >= :since")
    suspend fun getErrorCountSince(since: Long): Int

    // ========== Write Operations ==========

    @Insert
    suspend fun insert(log: SyncLog): Long

    @Insert
    suspend fun insertAll(logs: List<SyncLog>)

    // ========== Cleanup ==========

    /** Deletes logs older than [cutoff] and returns how many. */
    @Query("DELETE FROM sync_logs WHERE timestamp < :cutoff")
    suspend fun deleteOldLogs(cutoff: Long): Int

    /**
     * Deletes [calendarId]'s logs. Nothing calls it today; a removed calendar's logs age out
     * through [deleteOldLogs].
     */
    @Query("DELETE FROM sync_logs WHERE calendar_id = :calendarId")
    suspend fun deleteLogsForCalendar(calendarId: Long)

    /** Deletes every log. */
    @Query("DELETE FROM sync_logs")
    suspend fun deleteAll()

    /** Deletes all but the newest [keepCount] logs and returns how many. */
    @Query("""
        DELETE FROM sync_logs
        WHERE id NOT IN (
            SELECT id FROM sync_logs
            ORDER BY timestamp DESC
            LIMIT :keepCount
        )
    """)
    suspend fun trimToCount(keepCount: Int): Int
}
