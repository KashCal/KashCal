package org.onekash.kashcal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import org.onekash.kashcal.data.db.entity.PendingCancel
import org.onekash.kashcal.util.AddressNormalizer

/**
 * Queues attendees removed from a synced event until their iTIP CANCEL is resolved.
 *
 * EventWriter inserts a row per removed guest; the push drains them after a successful PUT.
 * `PushStrategy.drainPendingCancels` documents when a row is deleted; a transient failure keeps
 * it for the next push, up to the attempt cap.
 */
@Dao
interface PendingCancelsDao {

    /**
     * Enqueues or refreshes a pending cancel, idempotent on (event_id, recurrence_id, canonical
     * address), so re-removing a guest keeps one row with the latest sequence.
     *
     * Dedup compares [AddressNormalizer.canonical], the form EventWriter's removal diff uses. A
     * raw match would miss a re-removal after a server reformed `mailto:Bob@x` to bare `bob@x`
     * on a pull in between (servers legitimately do this), leaving two rows and sending a
     * duplicate CANCEL. The stored `address` stays raw: the wire emit re-derives the form, and
     * non-email CAL-ADDRESS values (urn:uuid:, principal paths) must not be canonicalised.
     *
     * A unique index with REPLACE can't do this: SQLite treats NULLs as distinct in a UNIQUE
     * index, so two all-events rows (recurrence_id IS NULL) would never collide.
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

    /** Returns [eventId]'s pending cancels, series-level and per-occurrence. */
    @Query("SELECT * FROM pending_cancels WHERE event_id = :eventId")
    suspend fun getForEvent(eventId: Long): List<PendingCancel>

    /** Deletes the row with [id]. */
    @Query("DELETE FROM pending_cancels WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Counts one failed delivery attempt toward the retry cap. */
    @Query("UPDATE pending_cancels SET attempt_count = attempt_count + 1 WHERE id = :id")
    suspend fun incrementAttempt(id: Long)
}
