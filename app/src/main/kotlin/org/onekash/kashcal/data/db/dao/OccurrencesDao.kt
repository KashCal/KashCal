package org.onekash.kashcal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.OccurrenceWithEventData

/**
 * Reads and writes materialized occurrences, so date range queries are index lookups instead of
 * RRULE expansions.
 *
 * An occurrence edited into an exception stays the master's row (`event_id` = master) with
 * `exception_event_id` pointing at the exception. Queries that join events return the exception
 * event for such occurrences.
 */
@Dao
interface OccurrencesDao {

    // ========== Read Operations - Range Queries ==========

    /** Observes the non-cancelled occurrences overlapping [startTs]..[endTs]. */
    @Query("""
        SELECT * FROM occurrences
        WHERE end_ts >= :startTs
        AND start_ts <= :endTs
        AND is_cancelled = 0
        ORDER BY start_ts ASC
    """)
    fun getInRange(startTs: Long, endTs: Long): Flow<List<Occurrence>>

    /** Returns the non-cancelled occurrences overlapping [startTs]..[endTs]. */
    @Query("""
        SELECT * FROM occurrences
        WHERE end_ts >= :startTs
        AND start_ts <= :endTs
        AND is_cancelled = 0
        ORDER BY start_ts ASC
    """)
    suspend fun getInRangeOnce(startTs: Long, endTs: Long): List<Occurrence>

    /** Observes [calendarId]'s non-cancelled occurrences overlapping [startTs]..[endTs]. */
    @Query("""
        SELECT * FROM occurrences
        WHERE calendar_id = :calendarId
        AND end_ts >= :startTs
        AND start_ts <= :endTs
        AND is_cancelled = 0
        ORDER BY start_ts ASC
    """)
    fun getForCalendarInRange(calendarId: Long, startTs: Long, endTs: Long): Flow<List<Occurrence>>

    /** Returns [calendarId]'s non-cancelled occurrences overlapping [startTs]..[endTs]. */
    @Query("""
        SELECT * FROM occurrences
        WHERE calendar_id = :calendarId
        AND end_ts >= :startTs
        AND start_ts <= :endTs
        AND is_cancelled = 0
        ORDER BY start_ts ASC
    """)
    suspend fun getForCalendarInRangeOnce(calendarId: Long, startTs: Long, endTs: Long): List<Occurrence>

    // ========== JOIN Queries (Reactive to Event Changes) ==========

    /**
     * Observes non-cancelled occurrences in range with their event.
     *
     * Room tracks both joined tables, so the Flow emits on an event edit (title, location) as
     * well as an occurrence change. The join picks the exception event when
     * `exception_event_id` is set, else the master. Event columns carry the `e_` prefix of
     * [OccurrenceWithEventData]'s embedded Event; `raw_ical` is selected as NULL.
     */
    @Query("""
        SELECT o.id, o.event_id, o.exception_event_id, o.calendar_id,
               o.start_ts, o.end_ts, o.start_day, o.end_day, o.is_cancelled,
               e.id AS e_id, e.uid AS e_uid, e.import_id AS e_import_id, e.calendar_id AS e_calendar_id,
               e.title AS e_title, e.description AS e_description, e.location AS e_location,
               e.start_ts AS e_start_ts, e.end_ts AS e_end_ts, e.is_all_day AS e_is_all_day,
               e.timezone AS e_timezone, e.end_timezone AS e_end_timezone, e.rrule AS e_rrule, e.exdate AS e_exdate, e.rdate AS e_rdate,
               e.caldav_url AS e_caldav_url, e.etag AS e_etag, e.sync_status AS e_sync_status,
               e.sequence AS e_sequence, e.reminders AS e_reminders, e.alarm_count AS e_alarm_count,
               e.original_event_id AS e_original_event_id, e.original_instance_time AS e_original_instance_time,
               e.status AS e_status, e.transp AS e_transp, e.classification AS e_classification,
               e.organizer_email AS e_organizer_email, e.organizer_name AS e_organizer_name,
               e.organizer_sent_by AS e_organizer_sent_by, e.organizer_schedule_status AS e_organizer_schedule_status,
               e.duration AS e_duration, e.original_sync_id AS e_original_sync_id,
               e.extra_properties AS e_extra_properties, NULL AS e_raw_ical, e.dtstamp AS e_dtstamp,
               e.last_sync_error AS e_last_sync_error, e.sync_retry_count AS e_sync_retry_count,
               e.server_modified_at AS e_server_modified_at,
               e.priority AS e_priority, e.geo_lat AS e_geo_lat, e.geo_lon AS e_geo_lon,
               e.color AS e_color, e.url AS e_url, e.categories AS e_categories,
               e.created_at AS e_created_at, e.updated_at AS e_updated_at, e.local_modified_at AS e_local_modified_at
        FROM occurrences o
        JOIN events e ON (o.exception_event_id IS NOT NULL AND o.exception_event_id = e.id)
                      OR (o.exception_event_id IS NULL AND o.event_id = e.id)
        WHERE o.end_ts >= :startTs AND o.start_ts <= :endTs
        AND o.is_cancelled = 0
        ORDER BY o.start_ts ASC
    """)
    fun getOccurrencesWithEventsInRange(startTs: Long, endTs: Long): Flow<List<OccurrenceWithEventData>>

    /**
     * Returns the [getOccurrencesWithEventsInRange] rows for insights, minus PENDING_DELETE
     * events and events in hidden calendars.
     */
    @Query("""
        SELECT o.id, o.event_id, o.exception_event_id, o.calendar_id,
               o.start_ts, o.end_ts, o.start_day, o.end_day, o.is_cancelled,
               e.id AS e_id, e.uid AS e_uid, e.import_id AS e_import_id, e.calendar_id AS e_calendar_id,
               e.title AS e_title, e.description AS e_description, e.location AS e_location,
               e.start_ts AS e_start_ts, e.end_ts AS e_end_ts, e.is_all_day AS e_is_all_day,
               e.timezone AS e_timezone, e.end_timezone AS e_end_timezone, e.rrule AS e_rrule, e.exdate AS e_exdate, e.rdate AS e_rdate,
               e.caldav_url AS e_caldav_url, e.etag AS e_etag, e.sync_status AS e_sync_status,
               e.sequence AS e_sequence, e.reminders AS e_reminders, e.alarm_count AS e_alarm_count,
               e.original_event_id AS e_original_event_id, e.original_instance_time AS e_original_instance_time,
               e.status AS e_status, e.transp AS e_transp, e.classification AS e_classification,
               e.organizer_email AS e_organizer_email, e.organizer_name AS e_organizer_name,
               e.organizer_sent_by AS e_organizer_sent_by, e.organizer_schedule_status AS e_organizer_schedule_status,
               e.duration AS e_duration, e.original_sync_id AS e_original_sync_id,
               e.extra_properties AS e_extra_properties, NULL AS e_raw_ical, e.dtstamp AS e_dtstamp,
               e.last_sync_error AS e_last_sync_error, e.sync_retry_count AS e_sync_retry_count,
               e.server_modified_at AS e_server_modified_at,
               e.priority AS e_priority, e.geo_lat AS e_geo_lat, e.geo_lon AS e_geo_lon,
               e.color AS e_color, e.url AS e_url, e.categories AS e_categories,
               e.created_at AS e_created_at, e.updated_at AS e_updated_at, e.local_modified_at AS e_local_modified_at
        FROM occurrences o
        JOIN events e ON (o.exception_event_id IS NOT NULL AND o.exception_event_id = e.id)
                      OR (o.exception_event_id IS NULL AND o.event_id = e.id)
        JOIN calendars c ON o.calendar_id = c.id
        WHERE o.end_ts >= :startTs AND o.start_ts <= :endTs
        AND o.is_cancelled = 0
        AND e.sync_status != 'PENDING_DELETE'
        AND c.is_visible = 1
        ORDER BY o.start_ts ASC
    """)
    suspend fun getOccurrencesWithEventsForInsights(startTs: Long, endTs: Long): List<OccurrenceWithEventData>

    /**
     * Observes the [getOccurrencesWithEventsInRange] rows that span [day] (YYYYMMDD).
     *
     * Matches on the precomputed `start_day`/`end_day` codes, which already carry the
     * all-day timezone handling, instead of timestamps.
     */
    @Query("""
        SELECT o.id, o.event_id, o.exception_event_id, o.calendar_id,
               o.start_ts, o.end_ts, o.start_day, o.end_day, o.is_cancelled,
               e.id AS e_id, e.uid AS e_uid, e.import_id AS e_import_id, e.calendar_id AS e_calendar_id,
               e.title AS e_title, e.description AS e_description, e.location AS e_location,
               e.start_ts AS e_start_ts, e.end_ts AS e_end_ts, e.is_all_day AS e_is_all_day,
               e.timezone AS e_timezone, e.end_timezone AS e_end_timezone, e.rrule AS e_rrule, e.exdate AS e_exdate, e.rdate AS e_rdate,
               e.caldav_url AS e_caldav_url, e.etag AS e_etag, e.sync_status AS e_sync_status,
               e.sequence AS e_sequence, e.reminders AS e_reminders, e.alarm_count AS e_alarm_count,
               e.original_event_id AS e_original_event_id, e.original_instance_time AS e_original_instance_time,
               e.status AS e_status, e.transp AS e_transp, e.classification AS e_classification,
               e.organizer_email AS e_organizer_email, e.organizer_name AS e_organizer_name,
               e.organizer_sent_by AS e_organizer_sent_by, e.organizer_schedule_status AS e_organizer_schedule_status,
               e.duration AS e_duration, e.original_sync_id AS e_original_sync_id,
               e.extra_properties AS e_extra_properties, NULL AS e_raw_ical, e.dtstamp AS e_dtstamp,
               e.last_sync_error AS e_last_sync_error, e.sync_retry_count AS e_sync_retry_count,
               e.server_modified_at AS e_server_modified_at,
               e.priority AS e_priority, e.geo_lat AS e_geo_lat, e.geo_lon AS e_geo_lon,
               e.color AS e_color, e.url AS e_url, e.categories AS e_categories,
               e.created_at AS e_created_at, e.updated_at AS e_updated_at, e.local_modified_at AS e_local_modified_at
        FROM occurrences o
        JOIN events e ON (o.exception_event_id IS NOT NULL AND o.exception_event_id = e.id)
                      OR (o.exception_event_id IS NULL AND o.event_id = e.id)
        WHERE o.start_day <= :day AND o.end_day >= :day
        AND o.is_cancelled = 0
        ORDER BY o.start_ts ASC
    """)
    fun getOccurrencesWithEventsForDay(day: Int): Flow<List<OccurrenceWithEventData>>

    /** Observes the non-cancelled occurrences spanning [day] (YYYYMMDD). */
    @Query("""
        SELECT * FROM occurrences
        WHERE start_day <= :day AND end_day >= :day
        AND is_cancelled = 0
        ORDER BY start_ts ASC
    """)
    fun getForDay(day: Int): Flow<List<Occurrence>>

    /** Returns the non-cancelled occurrences spanning [day] (YYYYMMDD). */
    @Query("""
        SELECT * FROM occurrences
        WHERE start_day <= :day AND end_day >= :day
        AND is_cancelled = 0
        ORDER BY start_ts ASC
    """)
    suspend fun getForDayOnce(day: Int): List<Occurrence>

    /** Returns [calendarId]'s non-cancelled occurrences spanning [day] (YYYYMMDD). */
    @Query("""
        SELECT * FROM occurrences
        WHERE calendar_id = :calendarId
        AND start_day <= :day AND end_day >= :day
        AND is_cancelled = 0
        ORDER BY start_ts ASC
    """)
    suspend fun getForCalendarOnDay(calendarId: Long, day: Int): List<Occurrence>

    // ========== Read Operations - By Event ==========

    /** Returns every occurrence row of [eventId], cancelled ones included. */
    @Query("SELECT * FROM occurrences WHERE event_id = :eventId ORDER BY start_ts ASC")
    suspend fun getForEvent(eventId: Long): List<Occurrence>

    /**
     * Returns every occurrence of [eventIds] by start time, cancelled ones included, in one
     * query; search loads its results' occurrences this way instead of one query per event.
     */
    @Query("SELECT * FROM occurrences WHERE event_id IN (:eventIds) ORDER BY start_ts ASC")
    suspend fun getForEvents(eventIds: List<Long>): List<Occurrence>

    /** Returns how many occurrence rows [eventId] has. */
    @Query("SELECT COUNT(*) FROM occurrences WHERE event_id = :eventId")
    suspend fun getCountForEvent(eventId: Long): Int

    /** Returns [eventId]'s latest occurrence start, the boundary for forward extension. */
    @Query("SELECT MAX(start_ts) FROM occurrences WHERE event_id = :eventId")
    suspend fun getMaxStartTs(eventId: Long): Long?

    /** Returns [eventId]'s earliest occurrence start, the boundary for past extension. */
    @Query("SELECT MIN(start_ts) FROM occurrences WHERE event_id = :eventId")
    suspend fun getMinStartTs(eventId: Long): Long?

    /**
     * Returns the recurring masters whose latest occurrence starts before [targetTs], for
     * on-demand extension when the user navigates far into the future.
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
     * Returns the recurring masters whose earliest occurrence starts after [targetTs] and
     * after their DTSTART, for on-demand extension when the user navigates far into the past.
     *
     * A master whose DTSTART is already materialized has nothing earlier to expand.
     */
    @Query("""
        SELECT DISTINCT o.event_id FROM occurrences o
        INNER JOIN events e ON o.event_id = e.id
        WHERE e.rrule IS NOT NULL
        AND e.original_event_id IS NULL
        GROUP BY o.event_id
        HAVING MIN(o.start_ts) > :targetTs
        AND e.start_ts < MIN(o.start_ts)
    """)
    suspend fun getRecurringEventsNeedingPastExtension(targetTs: Long): List<Long>

    /** Returns the recurring masters, not PENDING_DELETE, with no occurrence rows. */
    @Query("""
        SELECT e.id FROM events e
        LEFT JOIN occurrences o ON e.id = o.event_id
        WHERE e.rrule IS NOT NULL
        AND e.original_event_id IS NULL
        AND e.sync_status != 'PENDING_DELETE'
        GROUP BY e.id
        HAVING COUNT(o.id) = 0
    """)
    suspend fun getRecurringEventsWithNoOccurrences(): List<Long>

    /** Returns [eventId]'s occurrence starting exactly at [startTs], or null. */
    @Query("SELECT * FROM occurrences WHERE event_id = :eventId AND start_ts = :startTs")
    suspend fun getOccurrenceAtTime(eventId: Long, startTs: Long): Occurrence?

    /**
     * Returns [eventId]'s occurrence within 60 seconds of [occurrenceTime], cancelled or not.
     *
     * The tolerance matches [markCancelled] and [linkException], so a reminder's stored
     * occurrence time still resolves to its row after an RRULE re-expansion shifts `start_ts`
     * by sub-second amounts. Cancelled rows are kept because the fire-time guard needs to see
     * one to suppress its reminder.
     */
    @Query("""
        SELECT * FROM occurrences
        WHERE event_id = :eventId
          AND ABS(start_ts - :occurrenceTime) < 60000
        LIMIT 1
    """)
    suspend fun getOccurrenceNearTime(eventId: Long, occurrenceTime: Long): Occurrence?

    // ========== Write Operations ==========

    /** Inserts [occurrence], replacing a row with the same id or (event_id, start_ts). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(occurrence: Occurrence): Long

    /** Inserts an RRULE expansion batch, replacing rows with the same (event_id, start_ts). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(occurrences: List<Occurrence>)

    /**
     * Deletes one occurrence; used to drop the row an exception moves onto when it is linked.
     */
    @Query("DELETE FROM occurrences WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Deletes every occurrence of [eventId], for example before regeneration. */
    @Query("DELETE FROM occurrences WHERE event_id = :eventId")
    suspend fun deleteForEvent(eventId: Long)

    /**
     * Deletes [eventId]'s occurrences starting at or after [afterTs], for a this-and-future
     * split or delete.
     */
    @Query("DELETE FROM occurrences WHERE event_id = :eventId AND start_ts >= :afterTs")
    suspend fun deleteForEventAfter(eventId: Long, afterTs: Long)

    /** Deletes every occurrence in [calendarId]. */
    @Query("DELETE FROM occurrences WHERE calendar_id = :calendarId")
    suspend fun deleteForCalendar(calendarId: Long)

    // ========== Exception Handling ==========

    /**
     * Links [exceptionEventId] to the master's occurrence within 60 seconds of
     * [occurrenceTime], without changing its times ([updateOccurrenceForException] also moves
     * them).
     *
     * The tolerance covers timezone and DST cases where the RECURRENCE-ID doesn't exactly match
     * the RRULE-generated time.
     */
    @Query("""
        UPDATE occurrences
        SET exception_event_id = :exceptionEventId
        WHERE event_id = :masterEventId
          AND ABS(start_ts - :occurrenceTime) < 60000
    """)
    suspend fun linkException(masterEventId: Long, occurrenceTime: Long, exceptionEventId: Long)

    /**
     * Links an exception to the master's occurrence and moves the occurrence to the
     * exception's times; returns the rows updated, 0 if no occurrence matched.
     *
     * The row matches within 60 seconds of [occurrenceTime] (the original instance time) or by
     * an existing link to [exceptionEventId]. The second arm finds a re-edited exception, whose
     * row's `start_ts` already holds the earlier edit's time.
     *
     * Also clears `is_cancelled`, so linking revives a cancelled row; a caller restoring a
     * cancelled link cancels it again afterwards.
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
     * Returns [eventId]'s occurrence starting exactly at [startTs], or null; used to find the
     * row a moved exception would collide with, and an unlinked exception's row on pull.
     */
    @Query("SELECT * FROM occurrences WHERE event_id = :eventId AND start_ts = :startTs LIMIT 1")
    suspend fun getByEventIdAndStartTs(eventId: Long, startTs: Long): Occurrence?

    /** Clears the link to [exceptionEventId] from its occurrence. */
    @Query("""
        UPDATE occurrences
        SET exception_event_id = NULL
        WHERE exception_event_id = :exceptionEventId
    """)
    suspend fun unlinkException(exceptionEventId: Long)

    /**
     * Returns the occurrence linked to [exceptionEventId], or null; reminder scheduling and the
     * reminder fire-time guard read an exception's occurrence this way.
     */
    @Query("SELECT * FROM occurrences WHERE exception_event_id = :exceptionEventId LIMIT 1")
    suspend fun getByExceptionEventId(exceptionEventId: Long): Occurrence?

    /**
     * Cancels [eventId]'s occurrence within 60 seconds of [occurrenceTime], as an EXDATE does.
     *
     * The tolerance covers timezone and DST cases where the EXDATE doesn't exactly match the
     * RRULE-generated time.
     */
    @Query("""
        UPDATE occurrences
        SET is_cancelled = 1
        WHERE event_id = :eventId
          AND ABS(start_ts - :occurrenceTime) < 60000
    """)
    suspend fun markCancelled(eventId: Long, occurrenceTime: Long)

    /**
     * Cancels the occurrence linked to [exceptionEventId].
     *
     * An occurrence edited into an exception has the exception's start_ts (set by
     * [updateOccurrenceForException]), so [markCancelled] with the original instance time misses
     * it whenever the edit moved it more than 60 seconds. Deleting an edited occurrence needs
     * this match by link.
     */
    @Query("""
        UPDATE occurrences
        SET is_cancelled = 1
        WHERE exception_event_id = :exceptionEventId
    """)
    suspend fun markCancelledByException(exceptionEventId: Long)

    /**
     * Un-cancels [eventId]'s occurrence within 60 seconds of [occurrenceTime], as removing an
     * EXDATE does; the tolerance matches [markCancelled].
     */
    @Query("""
        UPDATE occurrences
        SET is_cancelled = 0
        WHERE event_id = :eventId
          AND ABS(start_ts - :occurrenceTime) < 60000
    """)
    suspend fun unmarkCancelled(eventId: Long, occurrenceTime: Long)

    // ========== Calendar Move ==========

    /** Moves every occurrence of [eventId] to [newCalendarId], for an event calendar move. */
    @Query("UPDATE occurrences SET calendar_id = :newCalendarId WHERE event_id = :eventId")
    suspend fun updateCalendarIdForEvent(eventId: Long, newCalendarId: Long)

    // ========== Utility Queries ==========

    /** Returns the number of occurrence rows, for diagnostics. */
    @Query("SELECT COUNT(*) FROM occurrences")
    suspend fun getTotalCount(): Int

    /** Returns whether any non-cancelled occurrence overlaps [startTs]..[endTs]. */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM occurrences
            WHERE end_ts >= :startTs AND start_ts <= :endTs
            AND is_cancelled = 0
        )
    """)
    suspend fun hasOccurrencesInRange(startTs: Long, endTs: Long): Boolean
}
