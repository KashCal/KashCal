package org.onekash.kashcal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.util.AddressNormalizer

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
     * **Merge semantics for `notified_at`**: when a row in the new set
     * has the same canonical address (per [AddressNormalizer.canonical])
     * as a prior row, the prior `notified_at` is preserved on the new
     * row. This prevents the self-RSVP race documented in
     * `docs/SCHEDULING_SCOPE.md` T2 design notes — the optimistic UI
     * write writes ACCEPTED locally, but the next pull may race the
     * server's REPLY queue and return NEEDS-ACTION; without this
     * merge, the notification would re-fire for an event the user
     * already responded to.
     *
     * Caller must set `eventId` on every attendee — either by
     * `attendees.map { it.copy(eventId = id) }` after the parent event
     * upsert returns its ID, or by carrying the eventId forward from
     * the existing event row.
     */
    @Transaction
    suspend fun replaceForEvent(eventId: Long, attendees: List<Attendee>) {
        // Index prior rows by canonical address so the merge is robust to
        // mailto-vs-bare-address servers and case differences.
        val priorByAddress: Map<String, Attendee> = getForEventOnce(eventId)
            .associateBy { AddressNormalizer.canonical(it.address) }

        val merged = attendees.map { incoming ->
            val canonical = AddressNormalizer.canonical(incoming.address)
            val priorNotifiedAt = priorByAddress[canonical]?.notifiedAt
            // Honor an explicit notifiedAt on the incoming row (e.g., when a
            // caller is intentionally setting it). Fall back to the prior
            // row's value when the incoming row leaves notifiedAt null.
            if (incoming.notifiedAt == null && priorNotifiedAt != null) {
                incoming.copy(notifiedAt = priorNotifiedAt)
            } else {
                incoming
            }
        }

        deleteForEvent(eventId)
        if (merged.isNotEmpty()) {
            insertAll(merged)
        }
    }

    /**
     * Reactive read — emits the current attendee list for an event,
     * re-emits when [replaceForEvent] mutates.
     */
    @Query("SELECT * FROM attendees WHERE event_id = :eventId ORDER BY sort_order ASC")
    fun getForEvent(eventId: Long): Flow<List<Attendee>>

    /**
     * One-shot suspend read of the attendee list. Used by write paths
     * (e.g., RSVP) that need to read-modify-write the row set inside a
     * transaction without holding a Flow subscription.
     */
    @Query("SELECT * FROM attendees WHERE event_id = :eventId ORDER BY sort_order ASC")
    suspend fun getForEventOnce(eventId: Long): List<Attendee>

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

    /**
     * One-shot read of every DECLINED attendee row across the requested
     * events. SQL filters on `partstat = 'DECLINED'` to keep the result
     * set small even for week/month-full ranges. The "is this MY decline?"
     * resolution is finalized in Kotlin via [Account.matchesAttendee] —
     * see [org.onekash.kashcal.domain.reader.selfDeclinedEventIds].
     */
    @Query("SELECT * FROM attendees WHERE event_id IN (:eventIds) AND partstat = 'DECLINED'")
    suspend fun getDeclinedAttendeesForEvents(eventIds: List<Long>): List<Attendee>

    /**
     * Reactive read of every NEEDS-ACTION attendee row across the
     * requested events. SQL filters on `partstat = 'NEEDS-ACTION'` so the
     * inbox Flow remains cheap even when the database holds many
     * already-responded events. Owning-account identity matching is
     * finalized in Kotlin via [Account.matchesAttendee].
     */
    @Query("SELECT * FROM attendees WHERE event_id IN (:eventIds) AND partstat = 'NEEDS-ACTION'")
    fun getNeedsActionAttendeesForEventsFlow(eventIds: List<Long>): Flow<List<Attendee>>

    /**
     * One-shot read of every NEEDS-ACTION attendee row across the
     * requested events. Useful for the DAO unit test; production code
     * should prefer [getNeedsActionAttendeesForEventsFlow].
     */
    @Query("SELECT * FROM attendees WHERE event_id IN (:eventIds) AND partstat = 'NEEDS-ACTION'")
    suspend fun getNeedsActionAttendeesForEvents(eventIds: List<Long>): List<Attendee>

    @Query("DELETE FROM attendees WHERE event_id = :eventId")
    suspend fun deleteForEvent(eventId: Long)

    /**
     * Mark a single attendee row as notified. Used by the invite
     * notification path to dedupe per-row firing.
     */
    @Query("UPDATE attendees SET notified_at = :ts WHERE id = :id")
    suspend fun markNotified(id: Long, ts: Long)

    @Query("SELECT COUNT(*) FROM attendees WHERE event_id = :eventId")
    suspend fun countForEvent(eventId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attendees: List<Attendee>)
}
