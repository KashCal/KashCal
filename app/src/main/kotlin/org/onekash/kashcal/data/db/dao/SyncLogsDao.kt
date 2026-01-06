package org.onekash.kashcal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.entity.SyncLog

/**
 * Data Access Object for SyncLog operations.
 *
 * Manages sync operation logs for debugging and diagnostics.
 * Logs should be periodically cleaned up to manage database size.
 */
@Dao
interface SyncLogsDao {

    // ========== Read Operations ==========

    /**
     * Get recent logs (most recent first).
     */
    @Query("SELECT * FROM sync_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 100): Flow<List<SyncLog>>

    /**
     * Get recent logs (one-shot).
     */
    @Query("SELECT * FROM sync_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogsOnce(limit: Int = 100): List<SyncLog>

    /**
     * Get logs for a specific calendar.
     */
    @Query("SELECT * FROM sync_logs WHERE calendar_id = :calendarId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLogsForCalendar(calendarId: Long, limit: Int = 50): List<SyncLog>

    /**
     * Get logs for a specific event UID.
     */
    @Query("SELECT * FROM sync_logs WHERE event_uid = :eventUid ORDER BY timestamp DESC")
    suspend fun getLogsForEvent(eventUid: String): List<SyncLog>

    /**
     * Get error logs only.
     */
    @Query("SELECT * FROM sync_logs WHERE result != 'SUCCESS' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getErrorLogs(limit: Int = 50): List<SyncLog>

    /**
     * Get logs in time range.
     */
    @Query("""
        SELECT * FROM sync_logs
        WHERE timestamp >= :startTs AND timestamp <= :endTs
        ORDER BY timestamp DESC
    """)
    suspend fun getLogsInRange(startTs: Long, endTs: Long): List<SyncLog>

    /**
     * Get conflict logs (HTTP 412 errors).
     */
    @Query("SELECT * FROM sync_logs WHERE result = 'ERROR_412' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getConflictLogs(limit: Int = 20): List<SyncLog>

    /**
     * Get log count.
     */
    @Query("SELECT COUNT(*) FROM sync_logs")
    suspend fun getCount(): Int

    /**
     * Get error count since timestamp.
     */
    @Query("SELECT COUNT(*) FROM sync_logs WHERE result != 'SUCCESS' AND timestamp >= :since")
    suspend fun getErrorCountSince(since: Long): Int

    // ========== Write Operations ==========

    /**
     * Insert new log entry.
     */
    @Insert
    suspend fun insert(log: SyncLog): Long

    /**
     * Insert multiple log entries.
     */
    @Insert
    suspend fun insertAll(logs: List<SyncLog>)

    // ========== Cleanup ==========

    /**
     * Delete logs older than cutoff timestamp.
     * Call periodically to manage database size.
     */
    @Query("DELETE FROM sync_logs WHERE timestamp < :cutoff")
    suspend fun deleteOldLogs(cutoff: Long): Int

    /**
     * Delete logs for a calendar (when calendar is removed).
     */
    @Query("DELETE FROM sync_logs WHERE calendar_id = :calendarId")
    suspend fun deleteLogsForCalendar(calendarId: Long)

    /**
     * Delete all logs (for testing/reset).
     */
    @Query("DELETE FROM sync_logs")
    suspend fun deleteAll()

    /**
     * Keep only the most recent N logs.
     */
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
