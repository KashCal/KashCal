package org.onekash.kashcal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.entity.Attendee

/**
 * Room DAO for `attendees` table.
 *
 * Server-authoritative replace-on-update semantics: [replaceForEvent]
 * wipes existing rows for an event and inserts the new server-supplied
 * set in one transaction. Reads return Flows so the chip UI updates
 * reactively as sync writes attendees.
 */
@Dao
interface AttendeesDao {

    /**
     * Replace the attendee set for [eventId] atomically. Existing rows
     * are deleted and [attendees] is inserted in a single transaction.
     * Empty list → all existing rows removed.
     *
     * Caller must set `eventId` on every attendee — either by
     * `attendees.map { it.copy(eventId = id) }` after the parent event
     * upsert returns its ID, or by carrying the eventId forward from
     * the existing event row.
     */
    @Transaction
    suspend fun replaceForEvent(eventId: Long, attendees: List<Attendee>) {
        deleteForEvent(eventId)
        if (attendees.isNotEmpty()) {
            insertAll(attendees)
        }
    }

    /**
     * Reactive read — emits the current attendee list for an event,
     * re-emits when [replaceForEvent] mutates.
     */
    @Query("SELECT * FROM attendees WHERE event_id = :eventId ORDER BY sort_order ASC")
    fun getForEvent(eventId: Long): Flow<List<Attendee>>

    /**
     * Bulk read for day-view-style N+1 avoidance. Returns one list
     * containing all attendees across the requested events; callers
     * (e.g., [org.onekash.kashcal.domain.reader.EventReader]) group by
     * `eventId` in memory.
     *
     * First Flow-on-IN-clause precedent in this codebase. Validated
     * against Room docs: Flow returns work on `IN (:list)` queries.
     */
    @Query("SELECT * FROM attendees WHERE event_id IN (:eventIds) ORDER BY event_id ASC, sort_order ASC")
    fun getForEvents(eventIds: List<Long>): Flow<List<Attendee>>

    @Query("DELETE FROM attendees WHERE event_id = :eventId")
    suspend fun deleteForEvent(eventId: Long)

    @Query("SELECT COUNT(*) FROM attendees WHERE event_id = :eventId")
    suspend fun countForEvent(eventId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attendees: List<Attendee>)
}
