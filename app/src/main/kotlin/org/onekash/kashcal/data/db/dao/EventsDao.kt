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

/** Pairs an event with the occurrence start its query picked as next_occurrence_ts. */
data class EventWithNextOccurrence(
    @Embedded val event: Event,
    @ColumnInfo(name = "next_occurrence_ts") val nextOccurrenceTs: Long?
)

/**
 * Holds one occurrence row of [EventsDao.getEventsWithRemindersInRange], read by
 * ReminderScheduler.
 *
 * [event] carries the reminders to use: the master's when an exception inherits them.
 * [targetEventId] is the event the reminder opens: the exception if there is one, otherwise the
 * master.
 */
data class EventWithOccurrenceAndColor(
    @Embedded val event: Event,
    @ColumnInfo(name = "occurrence_start_ts") val occurrenceStartTs: Long,
    @ColumnInfo(name = "occurrence_end_ts") val occurrenceEndTs: Long,
    @ColumnInfo(name = "calendar_color") val calendarColor: Int,
    @ColumnInfo(name = "target_event_id") val targetEventId: Long? = null
)

/**
 * Holds a local (caldav_url, etag) pair for comparing against the server's etags without
 * loading whole events.
 */
data class EtagEntry(
    @ColumnInfo(name = "caldav_url") val caldavUrl: String,
    @ColumnInfo(name = "etag") val etag: String?
)

/** Holds an event's id and caldav_url for the iCloud URL normalization migration. */
data class EventUrlProjection(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "caldav_url") val caldavUrl: String?
)

/** Holds one event-form title suggestion, aggregated from past events. */
data class TitleSuggestion(
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "freq") val freq: Int,
    @ColumnInfo(name = "last_used") val lastUsed: Long
)

/**
 * Reads and writes Room events: masters, exceptions and one-off events.
 *
 * A master's start_ts and end_ts are its first occurrence's, so a time-range read that must see
 * every occurrence joins the occurrences table.
 */
@Dao
interface EventsDao {

    // ========== Read Operations - By ID ==========

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: Long): Event?

    /** Batch read, for example PushStrategy loading every event of the pending operations. */
    @Query("SELECT * FROM events WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Event>

    /** Returns which of [ids] still exist. */
    @Query("SELECT id FROM events WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<Long>): List<Long>

    @Query("SELECT * FROM events WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Event?>

    /** Returns every row with the UID, across calendars: a master and its exceptions share it. */
    @Query("SELECT * FROM events WHERE uid = :uid")
    suspend fun getByUid(uid: String): List<Event>

    /**
     * Finds an event by import_id (`{uid}` for a master, `{uid}:RECID:{datetime}` for an
     * exception) within a calendar.
     *
     * @deprecated Look exceptions up with [getExceptionByUidAndInstanceTime]: UID plus
     * RECURRENCE-ID is how RFC 5545 identifies them. Nothing in the app calls this.
     */
    @Deprecated(
        message = "Use getExceptionByUidAndInstanceTime for exception lookup",
        replaceWith = ReplaceWith("getExceptionByUidAndInstanceTime(uid, calendarId, originalInstanceTime)")
    )
    @Query("""
        SELECT * FROM events
        WHERE import_id = :importId
        AND calendar_id = :calendarId
        LIMIT 1
    """)
    suspend fun findByImportId(importId: String, calendarId: Long): Event?

    /** Returns a series in [calendarId]: the master first, then its exceptions by instance time. */
    @Query("""
        SELECT * FROM events
        WHERE uid = :uid
        AND calendar_id = :calendarId
        ORDER BY original_event_id IS NOT NULL, original_instance_time ASC
    """)
    suspend fun findAllByUid(uid: String, calendarId: Long): List<Event>

    /**
     * Returns one row at [caldavUrl], in any calendar. caldav_url isn't unique: a master and its
     * exceptions share their resource's URL, so this may return any of them.
     */
    @Query("SELECT * FROM events WHERE caldav_url = :caldavUrl")
    suspend fun getByCaldavUrl(caldavUrl: String): Event?

    /**
     * Returns every event in a calendar that has a caldav_url, for URL matching that tolerates
     * percent-encoding differences. SQLite can't percent-decode, so the caller canonicalizes
     * in Kotlin (CaldavUrlNormalizer) and compares in memory. Read only after [getByCaldavUrl]
     * finds no usable match.
     */
    @Query("SELECT * FROM events WHERE calendar_id = :calendarId AND caldav_url IS NOT NULL")
    suspend fun getEventsWithCaldavUrl(calendarId: Long): List<Event>

    /**
     * Returns the master with [uid] in [calendarId]. Pull looks masters up by UID first, since
     * the caldavUrl can vary by server (p180 vs p181).
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

    @Query("SELECT * FROM events WHERE calendar_id = :calendarId ORDER BY start_ts ASC")
    fun getByCalendarId(calendarId: Long): Flow<List<Event>>

    /** Filters on start_ts and end_ts, so a master counts only by its first occurrence. */
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
     * Returns one ICS subscription's events (caldav_url starting with [urlPrefix]) without
     * loading the rest of the calendar. The prefix, `ics_subscription:{subscriptionId}:`, is
     * built internally; its `_` is a LIKE wildcard, which also matches a literal `_`.
     */
    @Query("""
        SELECT * FROM events
        WHERE calendar_id = :calendarId
        AND caldav_url LIKE :urlPrefix || '%'
        AND sync_status != 'PENDING_DELETE'
        ORDER BY start_ts ASC
    """)
    suspend fun getByCalendarIdAndCaldavUrlPrefix(calendarId: Long, urlPrefix: String): List<Event>

    /** Same match as [getByCalendarIdAndCaldavUrlPrefix], without loading the rows. */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM events
            WHERE calendar_id = :calendarId
            AND caldav_url LIKE :urlPrefix || '%'
            AND sync_status != 'PENDING_DELETE'
        )
    """)
    suspend fun anyByCalendarIdAndCaldavUrlPrefix(calendarId: Long, urlPrefix: String): Boolean

    /** Filters on start_ts and end_ts, so a master counts only by its first occurrence. */
    @Query("""
        SELECT * FROM events
        WHERE end_ts >= :startTs
        AND start_ts <= :endTs
        AND sync_status != 'PENDING_DELETE'
        ORDER BY start_ts ASC
    """)
    suspend fun getInRange(startTs: Long, endTs: Long): List<Event>

    /**
     * Returns the (caldavUrl, etag) pairs a full listing compares against, so unchanged events
     * aren't fetched. Only rows with both values set.
     *
     * Recurring events are included whatever their first occurrence: a server returns a series
     * when any occurrence falls in the range (RFC 4791 time-range filter), so without them a
     * series that started before the lookback window would be downloaded again every sync.
     */
    @Query("""
        SELECT caldav_url, etag FROM events
        WHERE calendar_id = :calendarId
        AND (
            (end_ts >= :startTs AND start_ts <= :endTs)
            OR rrule IS NOT NULL
        )
        AND caldav_url IS NOT NULL
        AND etag IS NOT NULL
        AND sync_status != 'PENDING_DELETE'
    """)
    suspend fun getEtagMapForCalendar(calendarId: Long, startTs: Long, endTs: Long): List<EtagEntry>

    // ========== Read Operations - Recurring Events ==========

    @Query("SELECT * FROM events WHERE rrule IS NOT NULL AND original_event_id IS NULL")
    suspend fun getMasterRecurringEvents(): List<Event>

    @Query("""
        SELECT * FROM events
        WHERE calendar_id = :calendarId
        AND rrule IS NOT NULL
        AND original_event_id IS NULL
    """)
    suspend fun getMasterRecurringEventsByCalendar(calendarId: Long): List<Event>

    @Query("SELECT * FROM events WHERE original_event_id = :masterEventId ORDER BY original_instance_time ASC")
    suspend fun getExceptionsForMaster(masterEventId: Long): List<Event>

    /** Loads the exceptions of many masters in one query, for ICS export. */
    @Query("SELECT * FROM events WHERE original_event_id IN (:masterIds) ORDER BY original_instance_time ASC")
    suspend fun getExceptionsForMasters(masterIds: List<Long>): List<Event>

    @Query("""
        SELECT * FROM events
        WHERE original_event_id = :masterEventId
        AND original_instance_time = :instanceTime
    """)
    suspend fun getExceptionForOccurrence(masterEventId: Long, instanceTime: Long): Event?

    /**
     * Finds an exception by the identifiers the server keeps stable: an exception shares its
     * master's UID and is told apart by RECURRENCE-ID (RFC 5545), stored as
     * [originalInstanceTime]. This is the exception lookup for sync.
     */
    @Query("""
        SELECT * FROM events
        WHERE uid = :uid
        AND calendar_id = :calendarId
        AND original_instance_time = :originalInstanceTime
        AND original_event_id IS NOT NULL
        LIMIT 1
    """)
    suspend fun getExceptionByUidAndInstanceTime(
        uid: String,
        calendarId: Long,
        originalInstanceTime: Long
    ): Event?

    // ========== Read Operations - Sync ==========

    @Query("SELECT * FROM events WHERE sync_status != 'SYNCED'")
    suspend fun getPendingSyncEvents(): List<Event>

    @Query("SELECT * FROM events WHERE calendar_id = :calendarId AND sync_status != 'SYNCED'")
    suspend fun getPendingForCalendar(calendarId: Long): List<Event>

    @Query("SELECT * FROM events WHERE sync_status = 'PENDING_CREATE'")
    suspend fun getPendingCreateEvents(): List<Event>

    @Query("SELECT * FROM events WHERE sync_status = 'PENDING_UPDATE'")
    suspend fun getPendingUpdateEvents(): List<Event>

    @Query("SELECT * FROM events WHERE sync_status = 'PENDING_DELETE'")
    suspend fun getPendingDeleteEvents(): List<Event>

    @Query("SELECT * FROM events WHERE last_sync_error IS NOT NULL")
    suspend fun getEventsWithSyncErrors(): List<Event>

    /** Counts events not SYNCED; it doesn't read pending_operations. */
    @Query("SELECT COUNT(*) FROM events WHERE sync_status != 'SYNCED'")
    fun getPendingSyncCount(): Flow<Int>

    /**
     * Returns the (caldavUrl, etag) pairs of every event in a calendar that has a caldav_url,
     * except PENDING_DELETE ones, for the etag fallback after a rejected sync-token (403/410)
     * when the lookback is "All". The etag may be null.
     */
    @Query("""
        SELECT caldav_url, etag FROM events
        WHERE calendar_id = :calendarId
        AND caldav_url IS NOT NULL
        AND sync_status != 'PENDING_DELETE'
    """)
    suspend fun getEtagsByCalendarId(calendarId: Long): List<EtagEntry>

    /**
     * Same as [getEtagsByCalendarId], limited to the server's time window so events outside it
     * aren't read as deleted on the server.
     *
     * Recurring events are always included: their start_ts and end_ts are the first
     * occurrence's, while servers expand recurrences in their time-range filter.
     */
    @Query("""
        SELECT caldav_url, etag FROM events
        WHERE calendar_id = :calendarId
        AND caldav_url IS NOT NULL
        AND sync_status != 'PENDING_DELETE'
        AND (rrule IS NOT NULL OR (end_ts >= :startTs AND start_ts <= :endTs))
    """)
    suspend fun getEtagsByCalendarIdInRange(calendarId: Long, startTs: Long, endTs: Long): List<EtagEntry>

    // ========== Write Operations ==========

    /** Inserts a new event and returns its row id; throws on a conflict. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: Event): Long

    /** Inserts, or updates the row with the same primary key in place. */
    @Upsert
    suspend fun upsert(event: Event): Long

    @Update
    suspend fun update(event: Event)

    /** Deletes the event; its occurrences and exceptions go with it (FK cascade). */
    @Delete
    suspend fun delete(event: Event)

    /** Cascades like [delete]. */
    @Query("DELETE FROM events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM events WHERE calendar_id = :calendarId")
    suspend fun deleteByCalendarId(calendarId: Long)

    /** Deletes a calendar's SYNCED events and keeps those with pending changes. */
    @Query("DELETE FROM events WHERE calendar_id = :calendarId AND sync_status = 'SYNCED'")
    suspend fun deleteSyncedByCalendarId(calendarId: Long)

    // ========== Sync Status Updates ==========

    /** Soft-deletes: marks the event PENDING_DELETE until its queued server delete is pushed. */
    @Query("""
        UPDATE events
        SET sync_status = 'PENDING_DELETE',
            local_modified_at = :now,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun markForDeletion(id: Long, now: Long)

    /** Marks the event SYNCED after a push and clears its error and retry count. */
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
     * Marks the event SYNCED after a write and stores [rawIcal], the body the write sent, with
     * the [etag] the server returned for it, in one statement. The pair is the row's copy of the
     * server resource: a later reply patches it, and a 412 merge diffs the rows against it. An
     * etag stored without its body would let a later reply re-send text from before this write
     * without meeting a 412.
     */
    @Query("""
        UPDATE events
        SET sync_status = 'SYNCED',
            etag = :etag,
            raw_ical = :rawIcal,
            last_sync_error = NULL,
            sync_retry_count = 0,
            server_modified_at = :now,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun markSyncedWithCopy(id: Long, etag: String?, rawIcal: String, now: Long)

    /**
     * Sets only the etag, leaving sync status alone, so a still-pending event carries the new
     * etag into its next If-Match: for example a recovered missing etag or the etag after a
     * MOVE.
     */
    @Query("UPDATE events SET etag = :etag WHERE id = :id")
    suspend fun updateEtag(id: Long, etag: String?)

    /**
     * Stores the server's copy of a resource on every row pulled from it: the series
     * [seriesId] and its changed occurrences at [oldUrl], moved to [newUrl] (the same URL
     * unless the server redirected the write). Writes URL, etag and raw_ical in one statement,
     * without touching sync status. A pull-made placeholder series has no URL and is never
     * matched.
     */
    @Query("""
        UPDATE events SET caldav_url = :newUrl, etag = :etag, raw_ical = :rawIcal
        WHERE (id = :seriesId OR original_event_id = :seriesId) AND caldav_url = :oldUrl
    """)
    suspend fun updateResourceCopy(seriesId: Long, oldUrl: String, newUrl: String, etag: String, rawIcal: String)

    /**
     * Moves every row of a resource (series [seriesId] and its changed occurrences at
     * [oldUrl]) to the URL the server redirected a write to.
     */
    @Query("""
        UPDATE events SET caldav_url = :newUrl
        WHERE (id = :seriesId OR original_event_id = :seriesId) AND caldav_url = :oldUrl
    """)
    suspend fun updateResourceUrl(seriesId: Long, oldUrl: String, newUrl: String)

    /**
     * Sets only the ORGANIZER SCHEDULE-STATUS receipt (RFC 6638 §7.3) that PushStrategy's
     * read-back captures after an organizer's PUT. Touching one column lets it run mid-push
     * without a read-modify-write of the whole row.
     */
    @Query("UPDATE events SET organizer_schedule_status = :status WHERE id = :id")
    suspend fun updateOrganizerScheduleStatus(id: Long, status: String?)

    /** Marks the event SYNCED at its new [caldavUrl] after a create on the server. */
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
     * [markCreatedOnServer] that also stores [rawIcal], the body the write sent, in the same
     * statement (see [markSyncedWithCopy] for why the body and etag move together).
     */
    @Query("""
        UPDATE events
        SET sync_status = 'SYNCED',
            caldav_url = :caldavUrl,
            etag = :etag,
            raw_ical = :rawIcal,
            last_sync_error = NULL,
            sync_retry_count = 0,
            server_modified_at = :now,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun markCreatedOnServerWithCopy(id: Long, caldavUrl: String, etag: String?, rawIcal: String, now: Long)

    /** Records the error and increments the retry count. */
    @Query("""
        UPDATE events
        SET last_sync_error = :error,
            sync_retry_count = sync_retry_count + 1,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun recordSyncError(id: Long, error: String, now: Long)

    /**
     * Moves every exception of [masterEventId] to [newCalendarId] and returns how many moved.
     * Runs in the master's move transaction so a series never spans two calendars; the
     * original_event_id FK doesn't enforce that.
     */
    @Query("""
        UPDATE events
        SET calendar_id = :newCalendarId,
            updated_at = :now,
            local_modified_at = :now
        WHERE original_event_id = :masterEventId
    """)
    suspend fun updateCalendarIdForExceptions(masterEventId: Long, newCalendarId: Long, now: Long): Int

    /** Clears the error and resets the retry count. */
    @Query("""
        UPDATE events
        SET last_sync_error = NULL,
            sync_retry_count = 0,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun clearSyncError(id: Long, now: Long)

    /** Marks the event PENDING_UPDATE and increments its SEQUENCE. */
    @Query("""
        UPDATE events
        SET sync_status = 'PENDING_UPDATE',
            sequence = sequence + 1,
            local_modified_at = :now,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun markPendingUpdate(id: Long, now: Long)

    /** Sets [syncStatus] and stamps local_modified_at; SEQUENCE is left alone. */
    @Query("""
        UPDATE events
        SET sync_status = :syncStatus,
            local_modified_at = :now,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun updateSyncStatus(id: Long, syncStatus: SyncStatus, now: Long)

    /**
     * Returns only the event's sync status. PullStrategy re-reads it inside its upsert
     * transaction, since a user edit after its earlier pending-changes check would otherwise be
     * overwritten with server data.
     */
    @Query("SELECT sync_status FROM events WHERE id = :eventId")
    suspend fun getSyncStatus(eventId: Long): SyncStatus?

    /**
     * Sets only the reminders, leaving sync status alone, so nothing is pushed.
     *
     * CalDavSyncWorker uses it to give the user's default reminder to new events that arrive
     * without a VALARM (the creator's client kept its default reminders local, as Apple
     * Calendar does).
     *
     * @param remindersJson JSON list of ISO 8601 durations, e.g. `["-PT15M"]`
     */
    @Query("""
        UPDATE events
        SET reminders = :remindersJson,
            updated_at = :now
        WHERE id = :id
    """
    )
    suspend fun updateReminders(id: Long, remindersJson: String?, now: Long)

    /**
     * Sets the reminders and the alarm_count counter in one statement, for an attendee's
     * local-only reminders. Sync status is left alone, so no server PUT is queued.
     */
    @Query("""
        UPDATE events
        SET reminders = :remindersJson,
            alarm_count = :alarmCount,
            updated_at = :now
        WHERE id = :id
    """
    )
    suspend fun updateRemindersAndAlarmCount(
        id: Long,
        remindersJson: String?,
        alarmCount: Int,
        now: Long
    )

    /**
     * Clears every etag so the next sync fetches and re-parses every event, even unchanged ones.
     * Run when [org.onekash.kashcal.data.preferences.KashCalDataStore.CURRENT_PARSER_VERSION]
     * goes up.
     */
    @Query("UPDATE events SET etag = NULL")
    suspend fun clearAllEtags()

    // ========== Recurrence Updates ==========

    /** Sets EXDATE, marks the event PENDING_UPDATE and increments its SEQUENCE. */
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

    /** Sets RRULE, marks the event PENDING_UPDATE and increments its SEQUENCE. */
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

    @Query("SELECT EXISTS(SELECT 1 FROM events WHERE calendar_id = :calendarId AND uid = :uid)")
    suspend fun uidExistsInCalendar(calendarId: Long, uid: String): Boolean

    @Query("SELECT COUNT(*) FROM events WHERE calendar_id = :calendarId")
    suspend fun getCountByCalendar(calendarId: Long): Int

    /**
     * Returns a calendar's masters and one-off events by start time, without exceptions or
     * PENDING_DELETE rows. Readers include ICS export.
     */
    @Query("""
        SELECT * FROM events
        WHERE calendar_id = :calendarId
        AND sync_status != 'PENDING_DELETE'
        AND original_event_id IS NULL
        ORDER BY start_ts ASC
    """)
    suspend fun getAllMasterEventsForCalendar(calendarId: Long): List<Event>

    @Query("SELECT COUNT(*) FROM events")
    suspend fun getTotalCount(): Int

    /**
     * Suggests past event titles starting with [prefix] for the event-form autocomplete.
     *
     * Filters:
     * - Title starts with [prefix], case-insensitive after TRIM, and isn't blank.
     * - A series (non-empty rrule) counts whatever its date; a one-off event needs start_ts in
     *   [sinceMs], [untilMs] inclusive. start_ts, not created_at, so events a sync inserts
     *   today don't count as recent.
     * - No exceptions, so a series rescheduled many times isn't counted many times.
     * - No PENDING_DELETE rows and no synthetic masters.
     *
     * Groups by lower-cased trimmed title and ranks by count, then by last_used, the latest
     * COALESCE(local_modified_at, start_ts). local_modified_at is set by the user's own writes
     * and is null on a pull insert, so recent edits rank up and sync time doesn't. The title
     * shown takes its casing from the row with the latest such value.
     *
     * @param prefix the user's text; the wildcard is added in SQL.
     * @param minFreq minimum use count for a title to appear.
     */
    @Query("""
        SELECT
            (
                SELECT TRIM(inner_e.title) FROM events inner_e
                WHERE LOWER(TRIM(inner_e.title)) = LOWER(TRIM(outer_e.title))
                  AND inner_e.original_event_id IS NULL
                  AND inner_e.sync_status != 'PENDING_DELETE'
                ORDER BY COALESCE(inner_e.local_modified_at, inner_e.start_ts) DESC
                LIMIT 1
            ) AS title,
            COUNT(*) AS freq,
            MAX(COALESCE(outer_e.local_modified_at, outer_e.start_ts)) AS last_used
        FROM events outer_e
        WHERE LOWER(TRIM(outer_e.title)) LIKE LOWER(:prefix) || '%'
          AND LENGTH(TRIM(outer_e.title)) > 0
          AND outer_e.original_event_id IS NULL
          AND outer_e.sync_status != 'PENDING_DELETE'
          AND (outer_e.extra_properties IS NULL
               OR outer_e.extra_properties NOT LIKE '%X-KASHCAL-SYNTHETIC-MASTER%')
          AND (
            (outer_e.rrule IS NOT NULL AND outer_e.rrule != '')
            OR (outer_e.start_ts >= :sinceMs AND outer_e.start_ts <= :untilMs)
          )
        GROUP BY LOWER(TRIM(outer_e.title))
        HAVING COUNT(*) >= :minFreq
        ORDER BY freq DESC, last_used DESC
        LIMIT :limit
    """)
    suspend fun suggestTitlesByPrefix(
        prefix: String,
        sinceMs: Long,
        untilMs: Long,
        minFreq: Int,
        limit: Int
    ): List<TitleSuggestion>

    /**
     * Searches title, location and description with FTS4; returns at most 1000 events by start
     * time, without PENDING_DELETE rows or synthetic masters.
     *
     * [query] is FTS4 MATCH syntax: `meeting`, prefix `meet*`, all words `team meeting`, phrase
     * `"team meeting"`, `meeting OR standup`. The caller adds the `*` for prefix matching.
     */
    @Query("""
        SELECT events.* FROM events
        JOIN events_fts ON events.id = events_fts.rowid
        WHERE events_fts MATCH :query
        AND events.sync_status != 'PENDING_DELETE'
        AND (events.extra_properties IS NULL
             OR events.extra_properties NOT LIKE '%X-KASHCAL-SYNTHETIC-MASTER%')
        ORDER BY events.start_ts ASC
        LIMIT 1000
    """)
    suspend fun search(query: String): List<Event>

    /**
     * Matches [query] as [search] does and returns at most 1000 events, not PENDING_DELETE,
     * with a non-cancelled occurrence not ended at [now]: an event in progress counts, a series
     * whose last occurrence ended doesn't. The occurrences table decides this, since a master's
     * end_ts is its first occurrence's. Synthetic masters aren't filtered out here.
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
        LIMIT 1000
    """)
    suspend fun searchFuture(query: String, now: Long): List<Event>

    /**
     * Matches [query] as [search] does and returns at most 1000 events, not PENDING_DELETE,
     * with a non-cancelled occurrence overlapping [rangeStart]..[rangeEnd], inclusive.
     * Synthetic masters aren't filtered out here.
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
        LIMIT 1000
    """)
    suspend fun searchInRange(query: String, rangeStart: Long, rangeEnd: Long): List<Event>

    // ========== FTS Search with Next Occurrence ==========

    /**
     * Searches for the "All" filter, past events included, with the event's own start_ts as
     * next_occurrence_ts. Ordered by distance from [now].
     *
     * Exceptions are left out so a series with changed occurrences isn't listed once per
     * change (#149); synthetic masters are left out too.
     */
    @Query("""
        SELECT events.*, events.start_ts as next_occurrence_ts
        FROM events
        JOIN events_fts ON events.id = events_fts.rowid
        WHERE events_fts MATCH :query
        AND events.sync_status != 'PENDING_DELETE'
        AND events.original_event_id IS NULL
        AND (events.extra_properties IS NULL
             OR events.extra_properties NOT LIKE '%X-KASHCAL-SYNTHETIC-MASTER%')
        ORDER BY ABS(events.start_ts - :now) ASC
        LIMIT 1000
    """)
    suspend fun searchWithOccurrence(query: String, now: Long): List<EventWithNextOccurrence>

    /**
     * Searches for the default filter: events with a non-cancelled occurrence not ended at
     * [now], each with its earliest such occurrence's start, soonest first.
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
        LIMIT 1000
    """)
    suspend fun searchFutureWithOccurrence(query: String, now: Long): List<EventWithNextOccurrence>

    /**
     * Searches for a date filter (week, month, custom date): events with a non-cancelled
     * occurrence overlapping the inclusive range, each with its earliest such occurrence's
     * start, soonest first.
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
        LIMIT 1000
    """)
    suspend fun searchInRangeWithOccurrence(
        query: String,
        rangeStart: Long,
        rangeEnd: Long
    ): List<EventWithNextOccurrence>

    /**
     * Emits masters and one-off events, not PENDING_DELETE, with a non-cancelled occurrence
     * not ended at [now], each with that earliest occurrence's start, soonest first. The
     * pending-invitations inbox narrows these in Kotlin; an exception's own NEEDS-ACTION
     * attendees aren't read.
     */
    @Query("""
        SELECT DISTINCT events.*,
               (SELECT MIN(o.start_ts) FROM occurrences o
                WHERE o.event_id = events.id
                AND o.end_ts >= :now
                AND o.is_cancelled = 0) as next_occurrence_ts
        FROM events
        JOIN occurrences ON events.id = occurrences.event_id
        WHERE events.sync_status != 'PENDING_DELETE'
        AND events.original_event_id IS NULL
        AND occurrences.end_ts >= :now
        AND occurrences.is_cancelled = 0
        GROUP BY events.id
        ORDER BY next_occurrence_ts ASC
    """)
    fun getMasterEventsWithFutureOccurrenceFlow(now: Long): kotlinx.coroutines.flow.Flow<List<EventWithNextOccurrence>>

    // ========== Reminder Queries ==========

    /**
     * Returns an [EventWithOccurrenceAndColor] for each non-cancelled occurrence starting in
     * [fromTime]..[toTime] in a visible calendar whose event has reminders. ReminderScheduler
     * scans it for reminders not yet scheduled.
     *
     * An occurrence with an exception loads the exception (COALESCE(exception_event_id,
     * event_id)), so the notification shows the exception's title and location. The UNION
     * covers an exception without reminders, which inherits the master's:
     * 1. an exception with its own reminders, or an occurrence with the master's (first
     *    SELECT);
     * 2. an exception without reminders whose master has some, returned as the master's row
     *    with the exception as target (second SELECT).
     */
    @Query("""
        SELECT e.*, o.start_ts as occurrence_start_ts, o.end_ts as occurrence_end_ts,
               c.color as calendar_color,
               COALESCE(o.exception_event_id, o.event_id) as target_event_id
        FROM occurrences o
        JOIN events e ON e.id = COALESCE(o.exception_event_id, o.event_id)
        JOIN calendars c ON e.calendar_id = c.id
        WHERE e.reminders IS NOT NULL
          AND e.reminders != '[]'
          AND o.start_ts >= :fromTime
          AND o.start_ts <= :toTime
          AND o.is_cancelled = 0
          AND c.is_visible = 1
        UNION
        SELECT master.*, o.start_ts as occurrence_start_ts, o.end_ts as occurrence_end_ts,
               c.color as calendar_color,
               o.exception_event_id as target_event_id
        FROM occurrences o
        JOIN events exc ON exc.id = o.exception_event_id
        JOIN events master ON master.id = o.event_id
        JOIN calendars c ON exc.calendar_id = c.id
        WHERE o.exception_event_id IS NOT NULL
          AND (exc.reminders IS NULL OR exc.reminders = '[]')
          AND master.reminders IS NOT NULL
          AND master.reminders != '[]'
          AND o.start_ts >= :fromTime
          AND o.start_ts <= :toTime
          AND o.is_cancelled = 0
          AND c.is_visible = 1
        ORDER BY occurrence_start_ts ASC
    """)
    suspend fun getEventsWithRemindersInRange(
        fromTime: Long,
        toTime: Long
    ): List<EventWithOccurrenceAndColor>

    // ========== Deduplication ==========

    /**
     * Deletes duplicate masters, keeping the lowest id per (uid, calendar_id), and returns how
     * many went. A defensive cleanup for duplicates a sync race might create, e.g. iCloud
     * returning the same event from multiple servers with different caldavUrls. Only masters
     * are matched, though a deleted duplicate's exceptions go with it (FK cascade).
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

    // ========== Lookback Cleanup ==========

    /**
     * Deletes SYNCED one-off events with a caldav_url that ended before [cutoffTs] and returns
     * how many went. That covers CalDAV and ICS subscription events.
     *
     * This is all a shrunk sync lookback removes. A server returns a series together with its
     * changed occurrences (RFC 4791 time-range) and an upload replaces that whole resource with
     * a body rebuilt from the Room rows, so deleting a changed occurrence here would delete it on
     * the server at the next series edit. A kept series keeps its occurrence rows too.
     *
     * An all-day event's end_ts is UTC, so the boundary can be off by up to 24 hours; fine for
     * a lookback of weeks or months.
     *
     * Kept:
     * - events with pending changes, which must sync first;
     * - series masters, whose end_ts is the first occurrence's and says nothing of later ones;
     * - exceptions, unless their master goes: an ICS subscription's synthetic master (no RRULE,
     *   made for changed occurrences whose series isn't in the feed) matches, and deleting it
     *   deletes its exceptions by cascade;
     * - events with no caldav_url;
     * - birthday and anniversary events (caldav_url `contact_birthday:` or
     *   `contact_anniversary:`).
     */
    @Query("""
        DELETE FROM events
        WHERE sync_status = 'SYNCED'
        AND rrule IS NULL
        AND original_event_id IS NULL
        AND caldav_url IS NOT NULL
        AND caldav_url NOT LIKE 'contact_birthday:%'
        AND caldav_url NOT LIKE 'contact_anniversary:%'
        AND end_ts < :cutoffTs
    """)
    suspend fun deleteOutsideLookback(cutoffTs: Long): Int

    // ========== iCloud URL Migration ==========

    /**
     * Returns the id and caldav_url of every iCloud event with a URL, for URL migration.
     * The provider column holds the lower-case name the type converter writes.
     */
    @Query("""
        SELECT e.id, e.caldav_url FROM events e
        INNER JOIN calendars c ON e.calendar_id = c.id
        INNER JOIN accounts a ON c.account_id = a.id
        WHERE a.provider = 'icloud'
        AND e.caldav_url IS NOT NULL
    """)
    suspend fun getICloudEventUrls(): List<EventUrlProjection>

    /** Sets only the caldav_url, for example for iCloud URL normalization or after a MOVE. */
    @Query("UPDATE events SET caldav_url = :caldavUrl WHERE id = :id")
    suspend fun updateCaldavUrl(id: Long, caldavUrl: String)
}
