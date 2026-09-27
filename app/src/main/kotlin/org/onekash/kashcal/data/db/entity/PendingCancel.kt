package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Records a guest removed from an event who is awaiting an iTIP CANCEL (RFC 5546 §3.2.2.6).
 *
 * Uninviting a guest drops their attendee row, so the shrunk PUT no longer lists them and an
 * implicit-scheduling server cancels them itself (RFC 6638 §3.2.1.2). A server that leaves
 * delivery to the client (SCHEDULE-AGENT=CLIENT) needs a client-side CANCEL, and the dropped
 * row can't carry it. This table keeps the removed recipient and the delivery context
 * captured at removal, so the push can send the CANCEL after the attendee row is gone.
 *
 * It is a separate table, not a column on `attendees`: `AttendeesDao.replaceForEvent` deletes
 * every row absent from the incoming set, and runs on every pull, so a marker on the removed
 * guest's row would be deleted before its CANCEL went out.
 *
 * Lifecycle: the organizer write path inserts a row on removal. After a successful push,
 * `PushStrategy.drainPendingCancels` routes each row (one METHOD:CANCEL per row through the
 * outbox, or nothing when the shrunk PUT already cancelled) and deletes it once resolved. A
 * row not yet deliverable stays for a later push; [attemptCount] bounds the retries so an
 * undeliverable row doesn't stay forever.
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
     * The event. CASCADE delete: deleting the event deletes the row, since the server's
     * whole-event cancellation notifies every attendee, this one included.
     */
    @ColumnInfo(name = "event_id")
    val eventId: Long,

    /**
     * The occurrence the cancel applies to, as an `originalInstanceTime`. Null uninvites the
     * guest from the whole series; set, the CANCEL carries RECURRENCE-ID for that occurrence
     * only.
     */
    @ColumnInfo(name = "recurrence_id")
    val recurrenceId: Long? = null,

    /**
     * The removed attendee's CAL-ADDRESS, verbatim from the attendee row (mailto:, urn:uuid:,
     * principal path).
     */
    @ColumnInfo(name = "address")
    val address: String,

    /**
     * The attendee's last-known SCHEDULE-AGENT (RFC 6638 §7.1) at removal. With
     * [scheduleStatus] it tells the delivery classifier whether the server cancels implicitly
     * or the client must POST.
     */
    @ColumnInfo(name = "schedule_agent")
    val scheduleAgent: String? = null,

    /** The attendee's last-known SCHEDULE-STATUS (RFC 6638 §7.3) at removal. */
    @ColumnInfo(name = "schedule_status")
    val scheduleStatus: String? = null,

    /**
     * The event SEQUENCE at removal. The iTIP builder sends the CANCEL at this value + 1
     * (RFC 5546 §2.1.4), so the guest sees a higher SEQUENCE than their last REQUEST.
     */
    @ColumnInfo(name = "sequence")
    val sequence: Int = 0,

    /**
     * Delivery attempts so far. A row that can never be delivered (for example, a server
     * that leaves delivery to the client but has no outbox) is dropped at the cap instead of
     * retried forever.
     */
    @ColumnInfo(name = "attempt_count", defaultValue = "0")
    val attemptCount: Int = 0
) {
    /**
     * Returns the removed guest as an [Attendee], the one recipient of the per-attendee
     * METHOD:CANCEL, carrying the delivery context captured at removal.
     */
    fun toAttendee(eventId: Long): Attendee = Attendee(
        eventId = eventId,
        address = address,
        scheduleAgent = scheduleAgent,
        scheduleStatus = scheduleStatus,
    )
}
