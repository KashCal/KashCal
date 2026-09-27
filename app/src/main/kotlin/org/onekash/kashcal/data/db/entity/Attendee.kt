package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores one ATTENDEE line of an event (RFC 5545 §3.8.4.1).
 *
 * Deleting the event cascades to its attendee rows.
 *
 * Storage policy:
 * - Addresses are not canonicalized on store; identity matching canonicalizes only at lookup
 *   time. CalDAV servers return mixed forms (`mailto:`, `urn:uuid:`, principal-relative paths
 *   like `/646691839/principal/`, full HTTP principal URIs). The pull parser strips `mailto:`;
 *   [org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper] adds it back to an
 *   email-shaped [address] and keeps other forms as parsed, while `delegated_from`,
 *   `delegated_to`, `member` and `sent_by` stay bare.
 * - Enum-shaped fields (`role`, `partstat`, `cutype`, `schedule_agent`, `schedule_force_send`) are
 *   lenient TEXT, so a new value needs no migration. The pull parser maps each to an enum first,
 *   though, so an X-extension or unmodelled value arrives as that enum's default (an unknown
 *   PARTSTAT as `NEEDS-ACTION`), or is dropped for SCHEDULE-FORCE-SEND. Readers map the values they
 *   use, for example `AttendeeStatus` for PARTSTAT.
 * - Multi-value fields (`delegated_from`, `delegated_to`, `member`) are JSON arrays via
 *   `Converters.fromStringList`/`toStringList`. RFC 5545 permits multi-value forms, and
 *   `icaldav-core` models them the same way (`org.onekash.icaldav.model.Attendee.delegatedFrom`).
 *
 * Not to be confused with `org.onekash.icaldav.model.Attendee`, the wire-parsing model in
 * `icaldav-core`; this one is Room storage.
 */
@Entity(
    tableName = "attendees",
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
        Index(value = ["address"])
    ]
)
data class Attendee(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "event_id")
    val eventId: Long,

    /**
     * The attendee's CAL-ADDRESS (RFC 5545 §3.3.3), stored as the class doc describes. Indexed
     * for identity-scoped lookups.
     */
    @ColumnInfo(name = "address")
    val address: String,

    /** The `CN` parameter: the attendee's human-readable name. */
    @ColumnInfo(name = "display_name")
    val displayName: String? = null,

    /**
     * RFC 5545 §3.2.16: `CHAIR`, `REQ-PARTICIPANT`, `OPT-PARTICIPANT`, `NON-PARTICIPANT`.
     */
    @ColumnInfo(name = "role")
    val role: String? = null,

    /**
     * RFC 5545 §3.2.12: `NEEDS-ACTION`, `ACCEPTED`, `DECLINED`, `TENTATIVE`, `DELEGATED`,
     * `COMPLETED`, `IN-PROCESS`.
     */
    @ColumnInfo(name = "partstat")
    val partstat: String? = null,

    /** RFC 5545 §3.2.3: `INDIVIDUAL`, `GROUP`, `RESOURCE`, `ROOM`, `UNKNOWN`. */
    @ColumnInfo(name = "cutype")
    val cutype: String? = null,

    /** RFC 5545 §3.2.17 `TRUE`/`FALSE`, stored as 1/0; null when the wire omits it. */
    @ColumnInfo(name = "rsvp")
    val rsvp: Boolean? = null,

    /** RFC 5545 §3.2.4: the CAL-ADDRESSes that delegated to this attendee. */
    @ColumnInfo(name = "delegated_from", defaultValue = "[]")
    val delegatedFrom: List<String> = emptyList(),

    /** RFC 5545 §3.2.5: the CAL-ADDRESSes this attendee delegated to. */
    @ColumnInfo(name = "delegated_to", defaultValue = "[]")
    val delegatedTo: List<String> = emptyList(),

    /** RFC 5545 §3.2.11: the groups this attendee is a member of. */
    @ColumnInfo(name = "member", defaultValue = "[]")
    val member: List<String> = emptyList(),

    /** RFC 5545 §3.2.18: who scheduled on behalf of the attendee. */
    @ColumnInfo(name = "sent_by")
    val sentBy: String? = null,

    /** RFC 6638 §7.1: `SERVER`, `CLIENT` or `NONE`; null means the server default. */
    @ColumnInfo(name = "schedule_agent")
    val scheduleAgent: String? = null,

    /**
     * RFC 6638 §7.3: the server-written delivery status code, e.g. `1.2` or `5.3`, without its
     * description. The pull keeps only the first code.
     */
    @ColumnInfo(name = "schedule_status")
    val scheduleStatus: String? = null,

    /** RFC 6638 §7.2: makes the server send a `REQUEST` or `REPLY` it otherwise wouldn't. */
    @ColumnInfo(name = "schedule_force_send")
    val scheduleForceSend: String? = null,

    /** Position of the ATTENDEE line in the event, so the wire order survives; lower first. */
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,

    /**
     * When the invite notification fired for this row (epoch millis); null if not yet. Local
     * notification-dedup state, not a wire field.
     *
     * [org.onekash.kashcal.data.db.dao.AttendeesDao.replaceForEvent] carries it over from the
     * prior row with the same canonical address whenever the incoming row has none, so a pull
     * that returns NEEDS-ACTION before the server's REPLY queue runs doesn't notify again.
     */
    @ColumnInfo(name = "notified_at")
    val notifiedAt: Long? = null,

    /**
     * The event SEQUENCE at which a client-side `METHOD:REQUEST` was last POSTed to this
     * attendee through the scheduling outbox (RFC 6638 §6); null if none was sent yet.
     *
     * The idempotency marker for the outbox send: a REQUEST is sent only when this is null
     * (never sent, including a late-added invitee) or below the event's current SEQUENCE (a
     * reschedule, RFC 5546 §3.2.2.1). A same-SEQUENCE re-push doesn't re-send (RFC 5546
     * §3.2.2.2: same SEQUENCE is an update, not a reschedule), which stops a duplicate invite
     * on every sync. A permanent send failure also advances it, to stop the loop; a later
     * SEQUENCE bump or address fix recovers.
     *
     * Local send-dedup state, not a wire field. It survives the server wins replace in
     * [org.onekash.kashcal.data.db.dao.AttendeesDao.replaceForEvent] the same way as
     * [notifiedAt].
     */
    @ColumnInfo(name = "itip_request_sequence")
    val itipRequestSequence: Int? = null,

    /**
     * The raw request-status the scheduling outbox returned for the last client-side
     * `METHOD:REQUEST` to this attendee (RFC 6638 §10.4, e.g. `2.0;Success`,
     * `3.7;Invalid calendar user`); null when no send was recorded.
     *
     * Kept apart from [scheduleStatus], the server's delivery receipt from the implicit PUT
     * (RFC 6638 §7.3): the delivery-routing classifier reads `schedule_status` and
     * `schedule_agent`, so the outbox outcome must never overwrite that input. Nothing reads
     * the stored value yet; the send path classifies the reply's status before storing it.
     */
    @ColumnInfo(name = "itip_request_status")
    val itipRequestStatus: String? = null
)
