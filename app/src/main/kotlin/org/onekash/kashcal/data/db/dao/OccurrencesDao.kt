package org.onekash.kashcal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.entity.Occurrence

/**
 * Data Access Object for Occurrence operations.
 *
 * Manages materialized RRULE expansions for efficient date range queries.
 * Occurrences are pre-computed and stored for O(1) lookup by date.
 */
@Dao
interface OccurrencesDao {

    // ========== Read Operations - Range Queries ==========

    /**
     * Get occurrences in time range (primary query for calendar views).
     * Excludes cancelled occurrences.
     */
    @Query("""
        SELECT * FROM occurrences
        WHERE end_ts >= :startTs
        AND start_ts <= :endTs
        AND is_cancelled = 0
        ORDER BY start_ts ASC
    """)
    fun getInRange(startTs: Long, endTs: Long): Flow<List<Occurrence>>

    /**
     * Get occurrences in range (one-shot).
     */
    @Query("""
        SELECT * FROM occurrences
        WHERE end_ts >= :startTs
        AND start_ts <= :endTs
        AND is_cancelled = 0
        ORDER BY start_ts ASC
    """)
    suspend fun getInRangeOnce(startTs: Long, endTs: Long): List<Occurrence>

    /**
     * Get occurrences for specific calendar in range.
     */
    @Query("""
        SELECT * FROM occurrences
        WHERE calendar_id = :calendarId
        AND end_ts >= :startTs
        AND start_ts <= :endTs
        AND is_cancelled = 0
        ORDER BY start_ts ASC
    """)
    fun getForCalendarInRange(calendarId: Long, startTs: Long, endTs: Long): Flow<List<Occurrence>>

    /**
     * Get occurrences for specific calendar in range (one-shot).
     */
    @Query("""
        SELECT * FROM occurrences
        WHERE calendar_id = :calendarId
        AND end_ts >= :startTs
        AND start_ts <= :endTs
        AND is_cancelled = 0
        ORDER BY start_ts ASC
    """)
    suspend fun getForCalendarInRangeOnce(calendarId: Long, startTs: Long, endTs: Long): List<Occurrence>

    /**
     * Get occurrences for specific day (YYYYMMDD format).
     * Fast lookup using start_day index.
     */
    @Query("""
        SELECT * FROM occurrences
        WHERE start_day <= :day AND end_day >= :day
        AND is_cancelled = 0
        ORDER BY start_ts ASC
    """)
    fun getForDay(day: Int): Flow<List<Occurrence>>

    /**
     * Get occurrences for day (one-shot).
     */
    @Query("""
        SELECT * FROM occurrences
        WHERE start_day <= :day AND end_day >= :day
        AND is_cancelled = 0
        ORDER BY start_ts ASC
    """)
    suspend fun getForDayOnce(day: Int): List<Occurrence>

    /**
     * Get occurrences for calendar on specific day.
     */
    @Query("""
        SELECT * FROM occurrences
        WHERE calendar_id = :calendarId
        AND start_day <= :day AND end_day >= :day
        AND is_cancelled = 0
        ORDER BY start_ts ASC
    """)
    suspend fun getForCalendarOnDay(calendarId: Long, day: Int): List<Occurrence>

    // ========== Read Operations - By Event ==========

    /**
     * Get all occurrences for an event.
     */
    @Query("SELECT * FROM occurrences WHERE event_id = :eventId ORDER BY start_ts ASC")
    suspend fun getForEvent(eventId: Long): List<Occurrence>

    /**
     * Get occurrences for multiple events in a single query.
     * Used to avoid N+1 queries when loading occurrences for search results.
     *
     * @param eventIds List of event IDs to fetch occurrences for
     * @return All occurrences for the given events, sorted by start time
     */
    @Query("SELECT * FROM occurrences WHERE event_id IN (:eventIds) ORDER BY start_ts ASC")
    suspend fun getForEvents(eventIds: List<Long>): List<Occurrence>

    /**
     * Get occurrence count for an event.
     */
    @Query("SELECT COUNT(*) FROM occurrences WHERE event_id = :eventId")
    suspend fun getCountForEvent(eventId: Long): Int

    /**
     * Get latest occurrence time for event (for expansion boundary).
     */
    @Query("SELECT MAX(start_ts) FROM occurrences WHERE event_id = :eventId")
    suspend fun getMaxStartTs(eventId: Long): Long?

    /**
     * Find recurring master events whose occurrences don't extend to target date.
     * Used for on-demand occurrence extension when user navigates far into the future.
     *
     * @param targetTs Target timestamp - events with max occurrence before this need extension
     * @return List of event IDs that need occurrence extension
     */
    @Query("""
        SELECT DISTINCT o.event_id FROM occurrences o
        INNER JOIN events e ON o.event_id = e.id
        WHERE e.rrule IS NOT NULL
        AND e.original_event_id IS NULL
        GROUP BY o.event_id
        HAVING MAX(o.start_ts) < :targetTs
    """)
    suspend fun getRecurringEventsNeedingExtension(targetTs: Long): List<Long>

    /**
     * Get occurrence at specific time for event.
     */
    @Query("SELECT * FROM occurrences WHERE event_id = :eventId AND start_ts = :startTs")
    suspend fun getOccurrenceAtTime(eventId: Long, startTs: Long): Occurrence?

    // ========== Write Operations ==========

    /**
     * Insert single occurrence.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(occurrence: Occurrence): Long

    /**
     * Insert multiple occurrences (batch for RRULE expansion).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(occurrences: List<Occurrence>)

    /**
     * Delete all occurrences for an event (before regeneration).
     */
    @Query("DELETE FROM occurrences WHERE event_id = :eventId")
    suspend fun deleteForEvent(eventId: Long)

    /**
     * Delete occurrences for event after a certain time (for series split).
     */
    @Query("DELETE FROM occurrences WHERE event_id = :eventId AND start_ts >= :afterTs")
    suspend fun deleteForEventAfter(eventId: Long, afterTs: Long)

    /**
     * Delete all occurrences for a calendar.
     */
    @Query("DELETE FROM occurrences WHERE calendar_id = :calendarId")
    suspend fun deleteForCalendar(calendarId: Long)

    // ========== Exception Handling ==========

    /**
     * Link an exception event to an occurrence.
     * Called when creating an exception for a specific occurrence.
     *
     * Uses 60-second (60000ms) tolerance for timezone/DST edge cases where
     * RECURRENCE-ID timestamp may not exactly match RRULE-generated time.
     */
    @Query("""
        UPDATE occurrences
        SET exception_event_id = :exceptionEventId
        WHERE event_id = :masterEventId
          AND ABS(start_ts - :occurrenceTime) < 60000
    """)
    suspend fun linkException(masterEventId: Long, occurrenceTime: Long, exceptionEventId: Long)

    /**
     * Link exception to occurrence AND update occurrence times to match exception.
     *
     * CRITICAL: Uses OR condition to handle re-editing case:
     * - First edit: No existing link, finds by tolerance (original time)
     * - Re-edit: Has existing link, finds by exception_event_id (already linked)
     *
     * When re-editing an exception, occurrenceTime is the ORIGINAL time (from
     * event.originalInstanceTime), but the occurrence's start_ts has already
     * been modified. The OR condition ensures we still find the occurrence.
     *
     * IMPORTANT: Also sets is_cancelled = 0 to uncancel the occurrence.
     * PullStrategy cancels the master occurrence when creating Model A.
     * When normalizing to Model B via local edit, we must uncancel it.
     *
     * @param masterEventId The master recurring event ID
     * @param occurrenceTime The original occurrence time (before any modifications)
     * @param exceptionEventId The exception event ID
     * @param newStartTs Exception event's start time
     * @param newEndTs Exception event's end time
     * @param newStartDay Exception event's start day code (YYYYMMDD)
     * @param newEndDay Exception event's end day code (YYYYMMDD)
     * @return Number of rows updated (0 if no occurrence found)
     */
    @Query("""
        UPDATE occurrences
        SET exception_event_id = :exceptionEventId,
            start_ts = :newStartTs,
            end_ts = :newEndTs,
            start_day = :newStartDay,
            end_day = :newEndDay,
            is_cancelled = 0
        WHERE event_id = :masterEventId
          AND (
            ABS(start_ts - :occurrenceTime) < 60000
            OR exception_event_id = :exceptionEventId
          )
    """)
    suspend fun updateOccurrenceForException(
        masterEventId: Long,
        occurrenceTime: Long,
        exceptionEventId: Long,
        newStartTs: Long,
        newEndTs: Long,
        newStartDay: Int,
        newEndDay: Int
    ): Int

    /**
     * Unlink exception from occurrence (when exception is deleted).
     */
    @Query("""
        UPDATE occurrences
        SET exception_event_id = NULL
        WHERE exception_event_id = :exceptionEventId
    """)
    suspend fun unlinkException(exceptionEventId: Long)

    /**
     * Get the occurrence linked to an exception event.
     * Used for scheduling reminders for exception events.
     */
    @Query("SELECT * FROM occurrences WHERE exception_event_id = :exceptionEventId LIMIT 1")
    suspend fun getByExceptionEventId(exceptionEventId: Long): Occurrence?

    /**
     * Mark occurrence as cancelled (EXDATE applied).
     *
     * Uses 60-second (60000ms) tolerance for timezone/DST edge cases where
     * EXDATE timestamp may not exactly match RRULE-generated time.
     */
    @Query("""
        UPDATE occurrences
        SET is_cancelled = 1
        WHERE event_id = :eventId
          AND ABS(start_ts - :occurrenceTime) < 60000
    """)
    suspend fun markCancelled(eventId: Long, occurrenceTime: Long)

    /**
     * Unmark occurrence as cancelled (EXDATE removed).
     *
     * Uses 60-second (60000ms) tolerance for consistency with markCancelled.
     */
    @Query("""
        UPDATE occurrences
        SET is_cancelled = 0
        WHERE event_id = :eventId
          AND ABS(start_ts - :occurrenceTime) < 60000
    """)
    suspend fun unmarkCancelled(eventId: Long, occurrenceTime: Long)

    // ========== Calendar Move ==========

    /**
     * Update calendar ID for all occurrences of an event.
     * Used when moving an event to a different calendar.
     */
    @Query("UPDATE occurrences SET calendar_id = :newCalendarId WHERE event_id = :eventId")
    suspend fun updateCalendarIdForEvent(eventId: Long, newCalendarId: Long)

    // ========== Utility Queries ==========

    /**
     * Get total occurrence count (for diagnostics).
     */
    @Query("SELECT COUNT(*) FROM occurrences")
    suspend fun getTotalCount(): Int

    /**
     * Check if any occurrences exist in range (for UI hints).
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM occurrences
            WHERE end_ts >= :startTs AND start_ts <= :endTs
            AND is_cancelled = 0
        )
    """)
    suspend fun hasOccurrencesInRange(startTs: Long, endTs: Long): Boolean
}
