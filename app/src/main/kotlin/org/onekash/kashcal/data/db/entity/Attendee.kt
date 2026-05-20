package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-event ATTENDEE row (RFC 5545 §3.8.4.1).
 *
 * Child of `events` with FK CASCADE — when an event is deleted, all its
 * attendee rows are removed. One row per ATTENDEE line in the source iCal.
 *
 * Storage policy:
 * - Address fields (`address`, `delegated_from`, `delegated_to`, `sent_by`,
 *   `member` entries) are stored verbatim. CalDAV servers return mixed
 *   forms — `mailto:`, `urn:uuid:`, principal-relative paths
 *   (`/646691839/principal/`), full HTTP principal URIs — and identity
 *   matching canonicalizes only at lookup time.
 * - Enum-shaped fields (`role`, `partstat`, `cutype`, `schedule_agent`,
 *   `schedule_force_send`) are TEXT-lenient — servers emit X-extensions
 *   and the schema absorbs them without a migration. Domain layer maps
 *   to Kotlin enums.
 * - Multi-value fields (`delegated_from`, `delegated_to`, `member`) are
 *   JSON arrays via `Converters.fromStringList`/`toStringList`. RFC 5545
 *   permits multi-value forms and `icaldav-core` already models them this
 *   way (`org.onekash.icaldav.model.ICalEvent.delegatedFrom`).
 *
 * Not to be confused with `org.onekash.icaldav.model.Attendee` — the
 * iCal-layer model in `icaldav-core`. Different package, different role
 * (wire-protocol parsing vs. Room storage).
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

    /**
     * FK → events.id. Indexed.
     */
    @ColumnInfo(name = "event_id")
    val eventId: Long,

    /**
     * Raw CAL-ADDRESS as the server returned it (RFC 5545 §3.3.3). Common
     * forms: `mailto:`, `urn:uuid:`, principal-relative paths, and full
     * HTTP principal URIs. Indexed for identity-scoped lookups.
     */
    @ColumnInfo(name = "address")
    val address: String,

    /**
     * `CN` parameter — the human-readable name for the attendee.
     */
    @ColumnInfo(name = "display_name")
    val displayName: String? = null,

    /**
     * RFC 5545 §3.2.16: `CHAIR`, `REQ-PARTICIPANT`, `OPT-PARTICIPANT`,
     * `NON-PARTICIPANT`. TEXT-lenient — accepts X-extensions.
     */
    @ColumnInfo(name = "role")
    val role: String? = null,

    /**
     * RFC 5545 §3.2.12: `NEEDS-ACTION`, `ACCEPTED`, `DECLINED`,
     * `TENTATIVE`, `DELEGATED`, `COMPLETED`, `IN-PROCESS`. TEXT-lenient.
     */
    @ColumnInfo(name = "partstat")
    val partstat: String? = null,

    /**
     * RFC 5545 §3.2.3: `INDIVIDUAL`, `GROUP`, `RESOURCE`, `ROOM`,
     * `UNKNOWN`. TEXT-lenient.
     */
    @ColumnInfo(name = "cutype")
    val cutype: String? = null,

    /**
     * RFC 5545 §3.2.17: boolean (`TRUE`/`FALSE`) stored as `0`/`1`.
     * NULL = parameter not specified on the wire.
     */
    @ColumnInfo(name = "rsvp")
    val rsvp: Boolean? = null,

    /**
     * RFC 5545 §3.2.4: list of CAL-ADDRESSes from which this attendee was
     * delegated. JSON `List<String>` mirroring
     * `org.onekash.icaldav.model.ICalEvent.delegatedFrom`. Default `[]`.
     */
    @ColumnInfo(name = "delegated_from", defaultValue = "[]")
    val delegatedFrom: List<String> = emptyList(),

    /**
     * RFC 5545 §3.2.5: list of CAL-ADDRESSes to which the attendee
     * delegated. JSON `List<String>`. Default `[]`.
     */
    @ColumnInfo(name = "delegated_to", defaultValue = "[]")
    val delegatedTo: List<String> = emptyList(),

    /**
     * RFC 5545 §3.2.11: group memberships for this attendee. JSON
     * `List<String>`. Default `[]`.
     */
    @ColumnInfo(name = "member", defaultValue = "[]")
    val member: List<String> = emptyList(),

    /**
     * RFC 5545 §3.2.18: assistant scheduling on behalf of the attendee.
     */
    @ColumnInfo(name = "sent_by")
    val sentBy: String? = null,

    /**
     * RFC 6638 §7.1: `SERVER` / `CLIENT` / `NONE`. NULL = use server
     * default. TEXT-lenient.
     */
    @ColumnInfo(name = "schedule_agent")
    val scheduleAgent: String? = null,

    /**
     * RFC 6638 §7.3: server-written delivery status, e.g.
     * `1.2;Delivered`, `5.3;No scheduling support for user`.
     */
    @ColumnInfo(name = "schedule_status")
    val scheduleStatus: String? = null,

    /**
     * RFC 6638 §7.2: forces server to send `REQUEST` or `REPLY` even
     * when normally not required. TEXT-lenient.
     */
    @ColumnInfo(name = "schedule_force_send")
    val scheduleForceSend: String? = null,

    /**
     * Wire-order preservation of ATTENDEE lines per event. Lower values
     * sort first.
     */
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,

    /**
     * Epoch millis when the per-invite system notification fired for this
     * attendee row. NULL = not yet notified. Internal notification-dedup
     * state, NOT an RFC wire-protocol field.
     *
     * The replace-on-pull semantics in `AttendeesDao.replaceForEvent`
     * preserve this field across syncs when the prior row was non-NEEDS-
     * ACTION (i.e., the user already responded), so a server pull that
     * temporarily returns NEEDS-ACTION before its REPLY queue fires won't
     * re-fire a duplicate notification.
     */
    @ColumnInfo(name = "notified_at")
    val notifiedAt: Long? = null
)
