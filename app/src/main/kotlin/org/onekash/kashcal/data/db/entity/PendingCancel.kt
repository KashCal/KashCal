package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A guest removed from an event, awaiting an iTIP CANCEL (RFC 5546 §3.2.2.6).
 *
 * When the organizer uninvites a guest, the attendee row is dropped from the
 * event's set (so the shrunk PUT no longer lists them, and an implicit-
 * scheduling server cancels them automatically per RFC 6638 §3.2.1.2). But the
 * dropped row no longer exists to carry a client-side CANCEL for servers that
 * decline to self-schedule (SCHEDULE-AGENT=CLIENT). This table preserves the
 * removed recipient — and the delivery context captured at removal time — so
 * the push can deliver the CANCEL after the attendee row is gone.
 *
 * Deliberately a SEPARATE table, not a column on `attendees`: a removed guest
 * is by definition absent from the attendee set, and `AttendeesDao.replaceForEvent`
 * deletes any row absent from the incoming set (and runs again on every pull),
 * so an attendee-column marker would be destroyed before its CANCEL fired.
 *
 * Lifecycle: inserted on removal (organizer write path), drained on the next
 * successful push (one METHOD:CANCEL per row for outbox-class servers; skipped
 * for the implicit fleet whose shrunk PUT already cancelled), then deleted. A
 * transient send failure leaves the row to retry; [attemptCount] bounds retries
 * so a permanently-undeliverable row cannot leak forever.
 */
@Entity(
    tableName = "pending_cancels",
    foreignKeys = [
        ForeignKey(
            entity = Event::class,
            parentColumns = ["id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["event_id"]),
        Index(value = ["event_id", "recurrence_id", "address"], unique = true)
    ]
)
data class PendingCancel(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Parent event ID. CASCADE delete: a whole-event delete removes the row —
     * correct, because deleting the event triggers the server's whole-event
     * cancellation, which notifies all attendees including this one.
     */
    @ColumnInfo(name = "event_id")
    val eventId: Long,

    /**
     * The occurrence this cancel scopes to. NULL = master / all-events (the
     * guest is uninvited from the series). Set = a per-occurrence uninvite; the
     * CANCEL carries RECURRENCE-ID for this instance only. Matches the
     * `originalInstanceTime` convention used for exceptions.
     */
    @ColumnInfo(name = "recurrence_id")
    val recurrenceId: Long? = null,

    /**
     * The removed attendee's CAL-ADDRESS, stored verbatim as it was on the
     * attendee row (mailto:, urn:uuid:, principal path, …).
     */
    @ColumnInfo(name = "address")
    val address: String,

    /**
     * The removed attendee's last-known SCHEDULE-AGENT (RFC 6638 §7.1), captured
     * at removal time. Feeds the delivery classifier so the drain knows whether
     * the server will cancel implicitly or the client must POST.
     */
    @ColumnInfo(name = "schedule_agent")
    val scheduleAgent: String? = null,

    /**
     * The removed attendee's last-known SCHEDULE-STATUS (RFC 6638 §7.3),
     * captured at removal time. Feeds the same classifier.
     */
    @ColumnInfo(name = "schedule_status")
    val scheduleStatus: String? = null,

    /**
     * The event SEQUENCE at removal time. The CANCEL goes out at this value + 1
     * (the iTIP builder increments per RFC 5546 §2.1.4), so the cancelled guest
     * sees a higher SEQUENCE than the last REQUEST they received.
     */
    @ColumnInfo(name = "sequence")
    val sequence: Int = 0,

    /**
     * Number of delivery attempts so far. Bounds retries for a row that can
     * never be delivered (e.g. a declined server with no usable outbox), so it
     * is eventually abandoned rather than retried forever.
     */
    @ColumnInfo(name = "attempt_count", defaultValue = "0")
    val attemptCount: Int = 0
) {
    /**
     * Project this queued cancel back into an [Attendee] for the CANCEL body —
     * the single recipient the per-attendee METHOD:CANCEL targets. Carries the
     * captured delivery context so the body reflects what was on the wire.
     */
    fun toAttendee(eventId: Long): Attendee = Attendee(
        eventId = eventId,
        address = address,
        scheduleAgent = scheduleAgent,
        scheduleStatus = scheduleStatus,
    )
}
