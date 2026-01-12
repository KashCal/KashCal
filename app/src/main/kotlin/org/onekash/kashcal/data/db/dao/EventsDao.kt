package org.onekash.kashcal.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus

/**
 * Search result combining Event with its next occurrence timestamp.
 * Used by search queries to show recurring events with their next upcoming occurrence.
 */
data class EventWithNextOccurrence(
    @Embedded val event: Event,
    @ColumnInfo(name = "next_occurrence_ts") val nextOccurrenceTs: Long?
)

/**
 * Result from getEventsWithRemindersInRange query.
 * Contains embedded Event with occurrence timing and calendar color.
 * Used by ReminderScheduler to find events that may need reminders scheduled.
 */
data class EventWithOccurrenceAndColor(
    @Embedded val event: Event,
    @ColumnInfo(name = "occurrence_start_ts") val occurrenceStartTs: Long,
    @ColumnInfo(name = "occurrence_end_ts") val occurrenceEndTs: Long,
    @ColumnInfo(name = "calendar_color") val calendarColor: Int
)

/**
 * Lightweight etag entry for etag-based sync comparison.
 * Used by pullWithEtagComparison() to compare local vs server etags
 * without loading full Event objects (saves memory for large calendars).
 */
data class EtagEntry(
    @ColumnInfo(name = "caldav_url") val caldavUrl: String,
    @ColumnInfo(name = "etag") val etag: String?
)

/**
 * Data Access Object for Event operations.
 *
 * Provides comprehensive CRUD and sync operations for calendar events.
 * Handles both master recurring events and exception instances.
 */
@Dao
interface EventsDao {

    // ========== Read Operations - By ID ==========

    /**
     * Get event by local ID.
     */
    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: Long): Event?

    /**
     * Get multiple events by IDs.
     * Used by PushStrategy to batch load events for pending operations.
     */
    @Query("SELECT * FROM events WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Event>

    /**
     * Get event by local ID as Flow.
     */
    @Query("SELECT * FROM events WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Event?>

    /**
     * Get events by RFC 5545 UID (may return multiple for master + exceptions).
     */
    @Query("SELECT * FROM events WHERE uid = :uid")
    suspend fun getByUid(uid: String): List<Event>

    /**
     * Get event by CalDAV URL (unique per event).
     */
    @Query("SELECT * FROM events WHERE caldav_url = :caldavUrl")
    suspend fun getByCaldavUrl(caldavUrl: String): Event?

    /**
     * Find master event by UID within a calendar.
     * Master events have original_event_id = NULL.
     * Used as primary lookup since caldavUrl can vary by server (p180 vs p181).
     */
    @Query("""
        SELECT * FROM events
        WHERE uid = :uid
        AND calendar_id = :calendarId
        AND original_event_id IS NULL
        LIMIT 1
    """)
    suspend fun getMasterByUidAndCalendar(uid: String, calendarId: Long): Event?

    // ========== Read Operations - By Calendar ==========

    /**
     * Get all events for a calendar.
     */
    @Query("SELECT * FROM events WHERE calendar_id = :calendarId ORDER BY start_ts ASC")
    fun getByCalendarId(calendarId: Long): Flow<List<Event>>

    /**
     * Get events for calendar in time range.
     */
    @Query("""
        SELECT * FROM events
        WHERE calendar_id = :calendarId
        AND end_ts >= :startTs
        AND start_ts <= :endTs
        AND sync_status != 'PENDING_DELETE'
        ORDER BY start_ts ASC
    """)
    suspend fun getByCalendarIdInRange(calendarId: Long, startTs: Long, endTs: Long): List<Event>

    /**
     * Get all events in time range across all calendars.
     */
    @Query("""
        SELECT * FROM events
        WHERE end_ts >= :startTs
        AND start_ts <= :endTs
        AND sync_status != 'PENDING_DELETE'
        ORDER BY start_ts ASC
    """)
    suspend fun getInRange(startTs: Long, endTs: Long): List<Event>

    // ========== Read Operations - Recurring Events ==========

    /**
     * Get all master recurring events (have RRULE).
     */
    @Query("SELECT * FROM events WHERE rrule IS NOT NULL AND original_event_id IS NULL")
    suspend fun getMasterRecurringEvents(): List<Event>

    /**
     * Get master recurring events for a calendar.
     */
    @Query("""
        SELECT * FROM events
        WHERE calendar_id = :calendarId
        AND rrule IS NOT NULL
        AND original_event_id IS NULL
    """)
    suspend fun getMasterRecurringEventsByCalendar(calendarId: Long): List<Event>

    /**
     * Get all exception events for a master recurring event.
     */
    @Query("SELECT * FROM events WHERE original_event_id = :masterEventId ORDER BY original_instance_time ASC")
    suspend fun getExceptionsForMaster(masterEventId: Long): List<Event>

    /**
     * Get exception for specific occurrence time.
     */
    @Query("""
        SELECT * FROM events
        WHERE original_event_id = :masterEventId
        AND original_instance_time = :instanceTime
    """)
    suspend fun getExceptionForOccurrence(masterEventId: Long, instanceTime: Long): Event?

    // ========== Read Operations - Sync ==========

    /**
     * Get all events pending sync (not SYNCED status).
     */
    @Query("SELECT * FROM events WHERE sync_status != 'SYNCED'")
    suspend fun getPendingSyncEvents(): List<Event>

    /**
     * Get pending sync events for a specific calendar.
     */
    @Query("SELECT * FROM events WHERE calendar_id = :calendarId AND sync_status != 'SYNCED'")
    suspend fun getPendingForCalendar(calendarId: Long): List<Event>

    /**
     * Get events pending creation.
     */
    @Query("SELECT * FROM events WHERE sync_status = 'PENDING_CREATE'")
    suspend fun getPendingCreateEvents(): List<Event>

    /**
     * Get events pending update.
     */
    @Query("SELECT * FROM events WHERE sync_status = 'PENDING_UPDATE'")
    suspend fun getPendingUpdateEvents(): List<Event>

    /**
     * Get events pending deletion.
     */
    @Query("SELECT * FROM events WHERE sync_status = 'PENDING_DELETE'")
    suspend fun getPendingDeleteEvents(): List<Event>

    /**
     * Get events with sync errors.
     */
    @Query("SELECT * FROM events WHERE last_sync_error IS NOT NULL")
    suspend fun getEventsWithSyncErrors(): List<Event>

    /**
     * Count pending operations.
     */
    @Query("SELECT COUNT(*) FROM events WHERE sync_status != 'SYNCED'")
    fun getPendingSyncCount(): Flow<Int>

    /**
     * Get etags for all synced events in a calendar.
     *
     * Used by etag-based fallback sync when sync-token expires (403/410).
     * Returns only caldav_url + etag pairs (lightweight, no full Event load).
     *
     * Filters:
     * - Only events with caldav_url (synced to server)
     * - Excludes PENDING_DELETE (will be deleted, not compared)
     *
     * @param calendarId Calendar to get etags for
     * @return List of (caldavUrl, etag) pairs
     */
    @Query("""
        SELECT caldav_url, etag FROM events
        WHERE calendar_id = :calendarId
        AND caldav_url IS NOT NULL
        AND sync_status != 'PENDING_DELETE'
    """)
    suspend fun getEtagsByCalendarId(calendarId: Long): List<EtagEntry>

    // ========== Write Operations ==========

    /**
     * Insert new event. Returns row ID.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: Event): Long

    /**
     * Insert or update event (by primary key).
     */
    @Upsert
    suspend fun upsert(event: Event): Long

    /**
     * Update existing event.
     */
    @Update
    suspend fun update(event: Event)

    /**
     * Delete event.
     * CASCADE: Occurrences and exception events will be deleted.
     */
    @Delete
    suspend fun delete(event: Event)

    /**
     * Delete event by ID.
     */
    @Query("DELETE FROM events WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Delete all events for a calendar.
     */
    @Query("DELETE FROM events WHERE calendar_id = :calendarId")
    suspend fun deleteByCalendarId(calendarId: Long)

    /**
     * Delete events that are synced (for refresh).
     */
    @Query("DELETE FROM events WHERE calendar_id = :calendarId AND sync_status = 'SYNCED'")
    suspend fun deleteSyncedByCalendarId(calendarId: Long)

    // ========== Sync Status Updates ==========

    /**
     * Soft delete - mark for deletion on server.
     */
    @Query("""
        UPDATE events
        SET sync_status = 'PENDING_DELETE',
            local_modified_at = :now,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun markForDeletion(id: Long, now: Long)

    /**
     * Mark event as synced after successful push.
     */
    @Query("""
        UPDATE events
        SET sync_status = 'SYNCED',
            etag = :etag,
            last_sync_error = NULL,
            sync_retry_count = 0,
            server_modified_at = :now,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun markSynced(id: Long, etag: String?, now: Long)

    /**
     * Mark event as synced with CalDAV URL after successful CREATE.
     * Sets caldav_url, etag, and sync_status = SYNCED.
     */
    @Query("""
        UPDATE events
        SET sync_status = 'SYNCED',
            caldav_url = :caldavUrl,
            etag = :etag,
            last_sync_error = NULL,
            sync_retry_count = 0,
            server_modified_at = :now,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun markCreatedOnServer(id: Long, caldavUrl: String, etag: String?, now: Long)

    /**
     * Record sync error for an event.
     */
    @Query("""
        UPDATE events
        SET last_sync_error = :error,
            sync_retry_count = sync_retry_count + 1,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun recordSyncError(id: Long, error: String, now: Long)

    /**
     * Clear sync error after user acknowledges.
     */
    @Query("""
        UPDATE events
        SET last_sync_error = NULL,
            sync_retry_count = 0,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun clearSyncError(id: Long, now: Long)

    /**
     * Mark event as pending update.
     */
    @Query("""
        UPDATE events
        SET sync_status = 'PENDING_UPDATE',
            sequence = sequence + 1,
            local_modified_at = :now,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun markPendingUpdate(id: Long, now: Long)

    /**
     * Update sync status directly.
     */
    @Query("""
        UPDATE events
        SET sync_status = :syncStatus,
            local_modified_at = :now,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun updateSyncStatus(id: Long, syncStatus: SyncStatus, now: Long)

    // ========== Recurrence Updates ==========

    /**
     * Update EXDATE (excluded dates) for a recurring event.
     */
    @Query("""
        UPDATE events
        SET exdate = :exdate,
            sync_status = 'PENDING_UPDATE',
            sequence = sequence + 1,
            local_modified_at = :now,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun updateExdate(id: Long, exdate: String?, now: Long)

    /**
     * Update RRULE for a recurring event.
     */
    @Query("""
        UPDATE events
        SET rrule = :rrule,
            sync_status = 'PENDING_UPDATE',
            sequence = sequence + 1,
            local_modified_at = :now,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun updateRrule(id: Long, rrule: String?, now: Long)

    // ========== Utility Queries ==========

    /**
     * Check if UID exists in calendar.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM events WHERE calendar_id = :calendarId AND uid = :uid)")
    suspend fun uidExistsInCalendar(calendarId: Long, uid: String): Boolean

    /**
     * Get count of events in calendar.
     */
    @Query("SELECT COUNT(*) FROM events WHERE calendar_id = :calendarId")
    suspend fun getCountByCalendar(calendarId: Long): Int

    /**
     * Get all master events for a calendar (for export).
     *
     * Returns master recurring events and standalone events (not exceptions).
     * Excludes events pending deletion.
     * Used by ICS export feature to get all exportable events.
     *
     * @param calendarId Calendar to export
     * @return List of master events, ordered by start time
     */
    @Query("""
        SELECT * FROM events
        WHERE calendar_id = :calendarId
        AND sync_status != 'PENDING_DELETE'
        AND original_event_id IS NULL
        ORDER BY start_ts ASC
    """)
    suspend fun getAllMasterEventsForCalendar(calendarId: Long): List<Event>

    /**
     * Get total event count.
     */
    @Query("SELECT COUNT(*) FROM events")
    suspend fun getTotalCount(): Int

    /**
     * Search events by title, location, or description using FTS4 full-text search.
     *
     * Much faster than LIKE queries for large datasets (10-100x improvement).
     *
     * Query syntax supports:
     * - Simple terms: "meeting" (matches word "meeting")
     * - Prefix search: "meet*" (matches "meeting", "meetup", etc.)
     * - Multiple words: "team meeting" (matches events with both words)
     * - Phrases: '"team meeting"' (matches exact phrase)
     * - Boolean: "meeting OR standup" (matches either)
     *
     * @param query FTS search query (caller should format with * for prefix matching)
     * @return Events matching the search, ordered by start time, max 100 results
     */
    @Query("""
        SELECT events.* FROM events
        JOIN events_fts ON events.id = events_fts.rowid
        WHERE events_fts MATCH :query
        AND events.sync_status != 'PENDING_DELETE'
        ORDER BY events.start_ts ASC
        LIMIT 100
    """)
    suspend fun search(query: String): List<Event>

    /**
     * Search events that have at least one current/future occurrence.
     *
     * Uses FTS for text matching, then joins with occurrences to filter
     * to only events where at least one occurrence hasn't ended yet.
     *
     * This follows Android CalendarProvider's pattern of using the Instances
     * table for time-based queries (occurrences = Instances).
     *
     * Correctly handles:
     * - Single events that haven't ended
     * - Multi-day events in progress (started but not ended)
     * - Recurring events with future occurrences
     * - Excludes recurring events that have completely ended (UNTIL in past)
     *
     * @param query FTS search query (caller should format with * for prefix matching)
     * @param now Current timestamp for filtering
     * @return Events with at least one non-ended, non-cancelled occurrence
     */
    @Query("""
        SELECT DISTINCT events.* FROM events
        JOIN events_fts ON events.id = events_fts.rowid
        JOIN occurrences ON events.id = occurrences.event_id
        WHERE events_fts MATCH :query
        AND events.sync_status != 'PENDING_DELETE'
        AND occurrences.end_ts >= :now
        AND occurrences.is_cancelled = 0
        ORDER BY events.start_ts ASC
        LIMIT 100
    """)
    suspend fun searchFuture(query: String, now: Long): List<Event>

    /**
     * Search events with occurrences in a specific date range.
     *
     * Combines FTS text matching with occurrence time range filtering.
     * Returns events that have at least one non-cancelled occurrence
     * within the specified range.
     *
     * @param query FTS search query (caller should format with * for prefix matching)
     * @param rangeStart Start of range (inclusive)
     * @param rangeEnd End of range (inclusive)
     * @return Events with at least one occurrence in range
     */
    @Query("""
        SELECT DISTINCT events.* FROM events
        JOIN events_fts ON events.id = events_fts.rowid
        JOIN occurrences ON events.id = occurrences.event_id
        WHERE events_fts MATCH :query
        AND events.sync_status != 'PENDING_DELETE'
        AND occurrences.start_ts <= :rangeEnd
        AND occurrences.end_ts >= :rangeStart
        AND occurrences.is_cancelled = 0
        ORDER BY events.start_ts ASC
        LIMIT 100
    """)
    suspend fun searchInRange(query: String, rangeStart: Long, rangeEnd: Long): List<Event>

    // ========== FTS Search with Next Occurrence ==========

    /**
     * Search events and return with next occurrence timestamp.
     * For "All" filter - includes past events, nextOccurrenceTs = event.startTs.
     *
     * @param query FTS search query
     * @return Events with their start timestamp as next_occurrence_ts
     */
    @Query("""
        SELECT events.*, events.start_ts as next_occurrence_ts
        FROM events
        JOIN events_fts ON events.id = events_fts.rowid
        WHERE events_fts MATCH :query
        AND events.sync_status != 'PENDING_DELETE'
        ORDER BY events.start_ts ASC
        LIMIT 100
    """)
    suspend fun searchWithOccurrence(query: String): List<EventWithNextOccurrence>

    /**
     * Search events with future occurrences and return next occurrence timestamp.
     * For default search (excludes past).
     *
     * @param query FTS search query
     * @param now Current timestamp for filtering
     * @return Events with their next future occurrence timestamp
     */
    @Query("""
        SELECT DISTINCT events.*,
               (SELECT MIN(o.start_ts) FROM occurrences o
                WHERE o.event_id = events.id
                AND o.end_ts >= :now
                AND o.is_cancelled = 0) as next_occurrence_ts
        FROM events
        JOIN events_fts ON events.id = events_fts.rowid
        JOIN occurrences ON events.id = occurrences.event_id
        WHERE events_fts MATCH :query
        AND events.sync_status != 'PENDING_DELETE'
        AND occurrences.end_ts >= :now
        AND occurrences.is_cancelled = 0
        GROUP BY events.id
        ORDER BY next_occurrence_ts ASC
        LIMIT 100
    """)
    suspend fun searchFutureWithOccurrence(query: String, now: Long): List<EventWithNextOccurrence>

    /**
     * Search events in date range and return next occurrence timestamp within range.
     * For date-filtered search (Week, Month, custom date).
     *
     * @param query FTS search query
     * @param rangeStart Start of range (inclusive)
     * @param rangeEnd End of range (inclusive)
     * @return Events with their next occurrence timestamp within the range
     */
    @Query("""
        SELECT DISTINCT events.*,
               (SELECT MIN(o.start_ts) FROM occurrences o
                WHERE o.event_id = events.id
                AND o.end_ts >= :rangeStart
                AND o.start_ts <= :rangeEnd
                AND o.is_cancelled = 0) as next_occurrence_ts
        FROM events
        JOIN events_fts ON events.id = events_fts.rowid
        JOIN occurrences ON events.id = occurrences.event_id
        WHERE events_fts MATCH :query
        AND events.sync_status != 'PENDING_DELETE'
        AND occurrences.start_ts <= :rangeEnd
        AND occurrences.end_ts >= :rangeStart
        AND occurrences.is_cancelled = 0
        GROUP BY events.id
        ORDER BY next_occurrence_ts ASC
        LIMIT 100
    """)
    suspend fun searchInRangeWithOccurrence(
        query: String,
        rangeStart: Long,
        rangeEnd: Long
    ): List<EventWithNextOccurrence>

    // ========== Reminder Queries ==========

    /**
     * Get events with reminders that have occurrences in the given time window.
     * Used by ReminderRefreshWorker to scan for missing reminders.
     *
     * Returns events where:
     * - Event has non-empty reminders list
     * - Event has occurrences in [fromTime, toTime] range
     * - Calendar is visible (user hasn't hidden it)
     * - Occurrence is not cancelled
     *
     * @param fromTime Start of window (epoch ms)
     * @param toTime End of window (epoch ms)
     * @return List of events with their occurrence times and calendar colors
     */
    @Query("""
        SELECT e.*, o.start_ts as occurrence_start_ts, o.end_ts as occurrence_end_ts,
               c.color as calendar_color
        FROM events e
        JOIN occurrences o ON e.id = o.event_id
        JOIN calendars c ON e.calendar_id = c.id
        WHERE e.reminders IS NOT NULL
          AND e.reminders != '[]'
          AND o.start_ts >= :fromTime
          AND o.start_ts <= :toTime
          AND o.is_cancelled = 0
          AND c.is_visible = 1
        ORDER BY o.start_ts ASC
    """)
    suspend fun getEventsWithRemindersInRange(
        fromTime: Long,
        toTime: Long
    ): List<EventWithOccurrenceAndColor>

    // ========== Deduplication ==========

    /**
     * Delete duplicate master events, keeping the oldest one per (uid, calendar_id).
     *
     * This is a defensive cleanup for edge cases where duplicate events might have
     * been created due to race conditions during sync (e.g., iCloud returning the
     * same event from multiple servers with different caldavUrls).
     *
     * Only affects master events (original_event_id IS NULL).
     * Exception events share their master's UID and are not affected.
     *
     * @return Number of duplicate events deleted
     */
    @Query("""
        DELETE FROM events
        WHERE original_event_id IS NULL
        AND id NOT IN (
            SELECT MIN(id) FROM events
            WHERE original_event_id IS NULL
            GROUP BY uid, calendar_id
        )
    """)
    suspend fun deleteDuplicateMasterEvents(): Int
}
