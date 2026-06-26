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
     * row. This prevents the self-RSVP race — the optimistic UI
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
            val prior = priorByAddress[canonical]
            // Honor an explicit value on the incoming row; otherwise fall back
            // to the prior row's value when the incoming row leaves it null.
            //
            // notified_at: prevents the self-RSVP notification re-firing race.
            //
            // schedule_status / schedule_agent: server-written delivery
            // receipts (RFC 6638 §7.3). A client never echoes SCHEDULE-STATUS
            // on its own PUT, so a cosmetic re-push whose read-back races an
            // async-stamping server returns the attendee with no receipt.
            // RFC 6638 §7.3 says a client SHOULD NOT remove a server-provided
            // parameter — so a null incoming preserves the prior receipt, while
            // a non-null incoming (the server spoke again) is authoritative and
            // overwrites.
            //
            // itip_request_sequence / itip_request_status: the client-outbox
            // send marker. It exists ONLY locally (the server never echoes it),
            // so every server-parsed incoming row carries it null. Preserving it
            // here is what keeps the idempotency marker alive across the
            // read-back's replace — without this, the marker would be wiped each
            // cycle and the client would re-POST (spam) the same invitation.
            incoming.copy(
                notifiedAt = incoming.notifiedAt ?: prior?.notifiedAt,
                scheduleStatus = incoming.scheduleStatus ?: prior?.scheduleStatus,
                scheduleAgent = incoming.scheduleAgent ?: prior?.scheduleAgent,
                itipRequestSequence = incoming.itipRequestSequence ?: prior?.itipRequestSequence,
                itipRequestStatus = incoming.itipRequestStatus ?: prior?.itipRequestStatus,
            )
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

    /**
     * Record that a client-side `METHOD:REQUEST` was POSTed to this attendee's
     * scheduling outbox at the given event SEQUENCE (RFC 6638 §6), and store the
     * raw per-recipient request-status the outbox returned. Advancing
     * `itip_request_sequence` is what suppresses a duplicate re-send on the next
     * push cycle (the idempotency marker).
     */
    @Query("UPDATE attendees SET itip_request_sequence = :sequence, itip_request_status = :status WHERE id = :id")
    suspend fun markItipRequestSent(id: Long, sequence: Int, status: String?)

    @Query("SELECT COUNT(*) FROM attendees WHERE event_id = :eventId")
    suspend fun countForEvent(eventId: Long): Int

    /**
     * Reactive attendees-table change signal. Emits a fresh value whenever
     * any attendee row is inserted/updated/deleted. Used by display-event
     * Flows that depend on attendee state (e.g. "did I decline this?")
     * but key on the events table for their main payload — without this
     * signal, an RSVP write that only touches `attendees` wouldn't re-run
     * the visible-events query and the strikethrough would wait for the
     * next sync that touches `events`.
     *
     * The exact value isn't meaningful — Room re-emits on any write to
     * the table, which is the only behavior we care about.
     */
    @Query("SELECT COUNT(*) FROM attendees")
    fun attendeesChangeSignal(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attendees: List<Attendee>)
}
