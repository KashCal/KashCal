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
 * Reads and writes the `attendees` table. Every write of an event's attendee set (for example
 * pull, push read-back, conflict resolution and local edits) goes through [replaceForEvent],
 * which replaces the whole set. The Flow reads let the attendee chips update as writes land.
 */
@Dao
interface AttendeesDao {

    /**
     * Replaces [eventId]'s attendees with [attendees] in one transaction; an empty list removes
     * them all.
     *
     * A new row whose address matches a prior row's by [AddressNormalizer.canonical] takes
     * `notified_at`, `schedule_status`, `schedule_agent`, `itip_request_sequence` and
     * `itip_request_status` from the prior row when its own is null (reasons inline).
     * Without `notified_at`, an optimistic ACCEPTED RSVP followed by a pull that races the
     * server's REPLY queue and returns NEEDS-ACTION would re-fire the invite notification.
     *
     * The caller must set `eventId` on every attendee, either with
     * `attendees.map { it.copy(eventId = id) }` after the event upsert returns its ID or by
     * carrying it from the existing event row.
     */
    @Transaction
    suspend fun replaceForEvent(eventId: Long, attendees: List<Attendee>) {
        // Canonical addresses match across mailto-vs-bare-address servers and case.
        val priorByAddress: Map<String, Attendee> = getForEventOnce(eventId)
            .associateBy { AddressNormalizer.canonical(it.address) }

        val merged = attendees.map { incoming ->
            val canonical = AddressNormalizer.canonical(incoming.address)
            val prior = priorByAddress[canonical]
            // A non-null incoming value wins; a null falls back to the prior row's.
            //
            // notified_at: keeps the self-RSVP race above from re-firing the notification.
            //
            // schedule_status / schedule_agent: server-written delivery receipts
            // (RFC 6638 §7.3). A client never echoes SCHEDULE-STATUS on its own PUT, so a
            // re-push whose read-back races an async-stamping server returns the attendee with
            // no receipt. RFC 6638 §7.3 says a client SHOULD NOT remove a server-provided
            // parameter, so a null keeps the prior receipt and a non-null (the server spoke
            // again) overwrites it.
            //
            // itip_request_sequence / itip_request_status: the client-outbox send marker. It
            // exists only locally (the server never echoes it), so every server-parsed row
            // carries it null. Without this the read-back's replace would wipe it each cycle
            // and the client would re-POST the same invitation.
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

    /** Emits [eventId]'s attendees and re-emits on each write to the table. */
    @Query("SELECT * FROM attendees WHERE event_id = :eventId ORDER BY sort_order ASC")
    fun getForEvent(eventId: Long): Flow<List<Attendee>>

    /** Reads the attendees once, for write paths (e.g. RSVP) that read-modify-write the set. */
    @Query("SELECT * FROM attendees WHERE event_id = :eventId ORDER BY sort_order ASC")
    suspend fun getForEventOnce(eventId: Long): List<Attendee>

    /**
     * Emits the attendees of all [eventIds] in one list, avoiding a query per event; callers
     * (e.g. [org.onekash.kashcal.domain.reader.EventReader]) group by `eventId` in memory.
     */
    @Query("SELECT * FROM attendees WHERE event_id IN (:eventIds) ORDER BY event_id ASC, sort_order ASC")
    fun getForEvents(eventIds: List<Long>): Flow<List<Attendee>>

    /**
     * Reads the DECLINED attendee rows of [eventIds]. The SQL filter keeps the result small even
     * for a month's range; whether a decline is the user's own is decided in Kotlin by
     * [org.onekash.kashcal.domain.reader.selfDeclinedEventIds].
     */
    @Query("SELECT * FROM attendees WHERE event_id IN (:eventIds) AND partstat = 'DECLINED'")
    suspend fun getDeclinedAttendeesForEvents(eventIds: List<Long>): List<Attendee>

    /**
     * Emits the NEEDS-ACTION attendee rows of [eventIds]. The SQL filter keeps the inbox Flow
     * cheap with many answered events; which rows are the user's is decided in Kotlin by
     * [org.onekash.kashcal.domain.identity.matchesAttendee].
     */
    @Query("SELECT * FROM attendees WHERE event_id IN (:eventIds) AND partstat = 'NEEDS-ACTION'")
    fun getNeedsActionAttendeesForEventsFlow(eventIds: List<Long>): Flow<List<Attendee>>

    /**
     * Reads the NEEDS-ACTION rows once, for the DAO unit test; production code should use
     * [getNeedsActionAttendeesForEventsFlow].
     */
    @Query("SELECT * FROM attendees WHERE event_id IN (:eventIds) AND partstat = 'NEEDS-ACTION'")
    suspend fun getNeedsActionAttendeesForEvents(eventIds: List<Long>): List<Attendee>

    @Query("DELETE FROM attendees WHERE event_id = :eventId")
    suspend fun deleteForEvent(eventId: Long)

    /** Marks one attendee row notified, so the invite notification fires once per row. */
    @Query("UPDATE attendees SET notified_at = :ts WHERE id = :id")
    suspend fun markNotified(id: Long, ts: Long)

    /**
     * Records that a client `METHOD:REQUEST` was POSTed to this attendee's scheduling outbox at
     * event SEQUENCE [sequence] (RFC 6638 §6), with the outbox's raw per-recipient
     * request-status. Advancing `itip_request_sequence` stops the next push from re-sending it.
     */
    @Query("UPDATE attendees SET itip_request_sequence = :sequence, itip_request_status = :status WHERE id = :id")
    suspend fun markItipRequestSent(id: Long, sequence: Int, status: String?)

    @Query("SELECT COUNT(*) FROM attendees WHERE event_id = :eventId")
    suspend fun countForEvent(eventId: Long): Int

    /**
     * Emits on every write to the attendees table; the value means nothing. Display-event Flows
     * keyed on the events table combine it so an RSVP write that touches only `attendees`
     * re-runs them; otherwise the decline strikethrough would wait for the next sync that touches
     * `events`.
     */
    @Query("SELECT COUNT(*) FROM attendees")
    fun attendeesChangeSignal(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attendees: List<Attendee>)
}
