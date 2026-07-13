package org.onekash.kashcal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import org.onekash.kashcal.data.db.entity.PendingCancel
import org.onekash.kashcal.util.AddressNormalizer

/**
 * Queue of removed attendees awaiting an iTIP CANCEL.
 *
 * Rows are inserted by the organizer write path on removal and drained by the
 * push after a successful PUT: outbox-class servers get a per-attendee
 * METHOD:CANCEL, the implicit fleet is skipped (the shrunk PUT already
 * cancelled), then the row is deleted. A transient failure leaves the row for
 * the next cycle.
 */
@Dao
interface PendingCancelsDao {

    /**
     * Enqueue (or refresh) a pending cancel, idempotent on
     * (event_id, recurrence_id, canonical address). Re-removing the same guest
     * keeps a single row carrying the latest sequence rather than spawning
     * duplicate cancels.
     *
     * Dedup compares the CANONICAL address ([AddressNormalizer.canonical] —
     * `mailto:`-stripped, lower-cased), matching how the removal diff in
     * EventWriter decides "this guest was removed". A raw string match would
     * miss a re-removal when the address form drifted between enqueues — a
     * server reforming `mailto:Bob@x` to bare `bob@x` on a pull between the two
     * (servers legitimately do this) would leave two rows for one guest and
     * send a duplicate CANCEL. The stored `address` stays RAW (verbatim), since
     * the wire emit re-derives the form and non-email CAL-ADDRESS values
     * (urn:uuid:, principal paths) must not be canonicalised.
     *
     * Implemented as delete-matching-then-insert (not unique-index + REPLACE):
     * SQLite treats NULL as distinct in a UNIQUE index, so two all-events rows
     * (recurrence_id IS NULL) would not collide and REPLACE would never fire.
     */
    @Transaction
    suspend fun upsert(cancel: PendingCancel) {
        val incomingCanonical = AddressNormalizer.canonical(cancel.address)
        getForEvent(cancel.eventId)
            .filter {
                it.recurrenceId == cancel.recurrenceId &&
                    AddressNormalizer.canonical(it.address) == incomingCanonical
            }
            .forEach { deleteById(it.id) }
        insert(cancel)
    }

    @Insert
    suspend fun insert(cancel: PendingCancel)

    /** All pending cancels for an event (both series-level and per-occurrence). */
    @Query("SELECT * FROM pending_cancels WHERE event_id = :eventId")
    suspend fun getForEvent(eventId: Long): List<PendingCancel>

    /** Remove a row once its CANCEL is resolved (delivered or abandoned). */
    @Query("DELETE FROM pending_cancels WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Advance the bounded-retry counter after a transient delivery failure. */
    @Query("UPDATE pending_cancels SET attempt_count = attempt_count + 1 WHERE id = :id")
    suspend fun incrementAttempt(id: Long)
}
