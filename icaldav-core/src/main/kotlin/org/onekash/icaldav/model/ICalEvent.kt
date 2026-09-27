package org.onekash.icaldav.model

import java.time.Duration

/**
 * Holds one parsed VEVENT: a master, a one-off event, or an exception.
 *
 * An exception shares its master's UID (RFC 5545), so [importId] tells them apart:
 * - master or one-off: "{uid}"
 * - exception: "{uid}:RECID:{recurrence-id}" ([generateImportId])
 */
data class ICalEvent(
    /** UID property; an exception carries its master's UID. */
    val uid: String,

    /** "{uid}", or "{uid}:RECID:{datetime}" for an exception; built by [generateImportId]. */
    val importId: String,

    /** SUMMARY property (the title). */
    val summary: String?,

    /** DESCRIPTION property. */
    val description: String?,

    /** LOCATION property. */
    val location: String?,

    /** DTSTART property; the parser uses DTEND when DTSTART is missing. */
    val dtStart: ICalDateTime,

    /** DTEND property; mutually exclusive with [duration]. */
    val dtEnd: ICalDateTime?,

    /** DURATION property; mutually exclusive with [dtEnd]. */
    val duration: Duration?,

    /** True when DTSTART is a DATE value, not a DATE-TIME. */
    val isAllDay: Boolean,

    /** STATUS property; absent or unknown values read as CONFIRMED ([EventStatus.fromString]). */
    val status: EventStatus,

    /** SEQUENCE property (revision number), 0 when absent; conflict resolution compares it. */
    val sequence: Int,

    /** RRULE property; the parser leaves it null on an exception. */
    val rrule: RRule?,

    /** EXDATE values, each comma-separated date its own entry. */
    val exdates: List<ICalDateTime>,

    /** RDATE values (RFC 5545 §3.8.5.2); the parser skips VALUE=PERIOD. */
    val rdates: List<ICalDateTime> = emptyList(),

    /** CLASS property (RFC 5545 §3.8.1.3), or null when absent or unrecognized. */
    val classification: Classification? = null,

    /** RECURRENCE-ID property; non-null only on an exception. */
    val recurrenceId: ICalDateTime?,

    /** VALARM components. */
    val alarms: List<ICalAlarm>,

    /** CATEGORIES values, blank entries dropped. */
    val categories: List<String>,

    /** ORGANIZER property. */
    val organizer: Organizer?,

    /** ATTENDEE properties. */
    val attendees: List<Attendee>,

    /** COLOR property (RFC 7986). */
    val color: String?,

    /**
     * DTSTAMP property (RFC 5545 §3.8.7.2): when the object was created if it has a METHOD,
     * otherwise when the component was last revised in the store.
     */
    val dtstamp: ICalDateTime?,

    /** LAST-MODIFIED property. */
    val lastModified: ICalDateTime?,

    /** CREATED property. */
    val created: ICalDateTime?,

    /** TRANSP property; absent or unknown values read as OPAQUE. */
    val transparency: Transparency,

    /** URL property. */
    val url: String?,

    /** PRIORITY (RFC 5545 §3.8.1.9): 0 undefined, 1 highest, 9 lowest. */
    val priority: Int = 0,

    /** GEO property (RFC 5545 §3.8.1.6) as its raw "latitude;longitude" value. */
    val geo: String? = null,

    // RFC 7986 Modern Properties

    /** IMAGE properties (RFC 7986). */
    val images: List<ICalImage> = emptyList(),

    /** CONFERENCE properties (RFC 7986). */
    val conferences: List<ICalConference> = emptyList(),

    // RFC 9073 Rich Event Properties

    /** VLOCATION components (RFC 9073); the parser and generator don't read or write them. */
    val locations: List<ICalLocation> = emptyList(),

    /** PARTICIPANT components (RFC 9073); the parser and generator don't read or write them. */
    val participants: List<ICalParticipant> = emptyList(),

    // RFC 9253 Relationships

    /** LINK properties (RFC 9253). */
    val links: List<ICalLink> = emptyList(),

    /** RELATED-TO properties with RFC 9253 parameters. */
    val relations: List<ICalRelation> = emptyList(),

    /**
     * Unknown properties as name to value, kept for round trips. The generator writes them only
     * when [unknownPropertyLines] is empty.
     */
    val rawProperties: Map<String, String>,

    /**
     * Unknown properties as the original, unfolded content lines, in document order. Filled by
     * the parser; written back unchanged by the generator. When this is non-empty the generator
     * writes these lines and ignores [rawProperties], so add to the map only on events without
     * lines.
     */
    val unknownPropertyLines: List<String> = emptyList()
) {
    /**
     * Returns [dtEnd], else [dtStart] plus [duration]. With neither, an all-day event ends 24
     * hours after its start and a timed event ends at its start.
     */
    fun effectiveEnd(): ICalDateTime {
        return dtEnd ?: duration?.let { dur ->
            ICalDateTime.fromTimestamp(
                timestamp = dtStart.timestamp + dur.toMillis(),
                timezone = dtStart.timezone,
                isDate = dtStart.isDate
            )
        } ?: if (isAllDay) {
            // RFC 5545 §3.6.1: a DATE DTSTART with no DTEND or DURATION lasts one day
            ICalDateTime.fromTimestamp(
                timestamp = dtStart.timestamp + 86400000L, // +24 hours
                timezone = dtStart.timezone,
                isDate = true
            )
        } else {
            // RFC 5545 §3.6.1: a DATE-TIME DTSTART alone ends at its start
            dtStart
        }
    }

    /** Returns whether this event has an RRULE or at least one RDATE. */
    fun isRecurring(): Boolean = rrule != null || rdates.isNotEmpty()

    /** Returns whether this is an exception (has a RECURRENCE-ID). */
    fun isModifiedInstance(): Boolean = recurrenceId != null

    /** Returns [uid], which an exception shares with its master. */
    fun masterUid(): String = uid

    companion object {
        /**
         * Builds the importId: "{uid}", or "{uid}:RECID:{datetime}" when [recurrenceId] is set,
         * with the datetime in its iCal string form.
         */
        fun generateImportId(uid: String, recurrenceId: ICalDateTime?): String {
            return if (recurrenceId != null) {
                "$uid:RECID:${recurrenceId.toICalString()}"
            } else {
                uid
            }
        }

        /**
         * Splits an importId at the first ":RECID:" into the UID and the RECURRENCE-ID string,
         * which is null when the marker is absent.
         */
        fun parseImportId(importId: String): Pair<String, String?> {
            val recidIndex = importId.indexOf(":RECID:")
            return if (recidIndex != -1) {
                val uid = importId.substring(0, recidIndex)
                val recid = importId.substring(recidIndex + 7)
                uid to recid
            } else {
                importId to null
            }
        }
    }
}

/** VEVENT STATUS values (RFC 5545 §3.8.1.11); [fromString] maps absent or unknown to CONFIRMED. */
enum class EventStatus {
    CONFIRMED,
    TENTATIVE,
    CANCELLED;

    fun toICalString(): String = name

    companion object {
        fun fromString(value: String?): EventStatus {
            return when (value?.uppercase()) {
                "TENTATIVE" -> TENTATIVE
                "CANCELLED" -> CANCELLED
                else -> CONFIRMED
            }
        }
    }
}

/** TRANSP values (RFC 5545 §3.8.2.7); [fromString] maps absent or unknown to OPAQUE. */
enum class Transparency {
    OPAQUE,      // Time is blocked (busy)
    TRANSPARENT; // Time is free

    fun toICalString(): String = name

    companion object {
        fun fromString(value: String?): Transparency {
            return when (value?.uppercase()) {
                "TRANSPARENT" -> TRANSPARENT
                else -> OPAQUE
            }
        }
    }
}

/** CLASS values (RFC 5545 §3.8.1.3); [fromString] returns null for anything else. */
enum class Classification {
    PUBLIC,       // Publicly visible
    PRIVATE,      // Private to the owner
    CONFIDENTIAL; // Confidential/restricted access

    fun toICalString(): String = name

    companion object {
        fun fromString(value: String?): Classification? {
            return when (value?.uppercase()) {
                "PUBLIC" -> PUBLIC
                "PRIVATE" -> PRIVATE
                "CONFIDENTIAL" -> CONFIDENTIAL
                else -> null
            }
        }
    }
}

/** ORGANIZER property with its CN, SENT-BY and RFC 6638 scheduling parameters. */
data class Organizer(
    val email: String,
    val name: String?,       // CN parameter
    val sentBy: String?,     // SENT-BY parameter
    // RFC 6638 scheduling parameters
    val scheduleAgent: ScheduleAgent? = null,
    val scheduleStatus: List<ScheduleStatus>? = null,
    val scheduleForceSend: ScheduleForceSend? = null
)

/** ATTENDEE property with its RFC 5545 and RFC 6638 parameters. */
data class Attendee(
    val email: String,
    val name: String?,           // CN parameter
    val partStat: PartStat,      // PARTSTAT
    val role: AttendeeRole,      // ROLE
    /**
     * RSVP parameter (RFC 5545 §3.2.17): true, false, or null when absent, so an explicit FALSE
     * stays distinct from no parameter. The generator writes RSVP=TRUE only; false and null
     * both omit it.
     */
    val rsvp: Boolean?,
    // RFC 5545 parameters
    val cutype: CUType = CUType.INDIVIDUAL,          // CUTYPE - calendar user type
    val dir: String? = null,                          // DIR - LDAP directory URI
    /**
     * MEMBER parameter (RFC 5545 §3.2.11): the groups or lists this attendee belongs to. Stored
     * as bare addresses; the wire form is comma-separated quoted URIs,
     * `MEMBER="mailto:a","mailto:b"`, and the generator re-adds `mailto:`.
     */
    val member: List<String> = emptyList(),
    val delegatedTo: List<String> = emptyList(),      // DELEGATED-TO - delegation targets
    val delegatedFrom: List<String> = emptyList(),    // DELEGATED-FROM - delegation sources
    // RFC 6638 scheduling parameters
    val sentBy: String? = null,                       // SENT-BY - delegating user
    val scheduleAgent: ScheduleAgent? = null,         // SCHEDULE-AGENT - server/client/none
    val scheduleStatus: List<ScheduleStatus>? = null, // SCHEDULE-STATUS - delivery status
    val scheduleForceSend: ScheduleForceSend? = null  // SCHEDULE-FORCE-SEND - force delivery
)

/** Attendee PARTSTAT values; [fromString] maps absent or unknown to NEEDS_ACTION. */
enum class PartStat {
    NEEDS_ACTION,
    ACCEPTED,
    DECLINED,
    TENTATIVE,
    DELEGATED;

    fun toICalString(): String = name.replace("_", "-")

    companion object {
        fun fromString(value: String?): PartStat {
            return when (value?.uppercase()?.replace("-", "_")) {
                "ACCEPTED" -> ACCEPTED
                "DECLINED" -> DECLINED
                "TENTATIVE" -> TENTATIVE
                "DELEGATED" -> DELEGATED
                else -> NEEDS_ACTION
            }
        }
    }
}

/** Attendee ROLE values (RFC 5545); [fromString] maps absent or unknown to REQ_PARTICIPANT. */
enum class AttendeeRole {
    CHAIR,
    REQ_PARTICIPANT,
    OPT_PARTICIPANT,
    NON_PARTICIPANT;

    fun toICalString(): String = name.replace("_", "-")

    companion object {
        fun fromString(value: String?): AttendeeRole {
            return when (value?.uppercase()?.replace("-", "_")) {
                "CHAIR" -> CHAIR
                "OPT_PARTICIPANT" -> OPT_PARTICIPANT
                "NON_PARTICIPANT" -> NON_PARTICIPANT
                else -> REQ_PARTICIPANT
            }
        }
    }
}

/** Attendee CUTYPE values (RFC 5545 §3.2.3); [fromString] maps absent or unknown to INDIVIDUAL. */
enum class CUType {
    INDIVIDUAL,   // An individual (default)
    GROUP,        // A group of individuals
    RESOURCE,     // A physical resource, for example a projector
    ROOM,         // A room
    UNKNOWN;      // Unknown type

    fun toICalString(): String = name

    companion object {
        fun fromString(value: String?): CUType {
            return when (value?.uppercase()) {
                "GROUP" -> GROUP
                "RESOURCE" -> RESOURCE
                "ROOM" -> ROOM
                "UNKNOWN" -> UNKNOWN
                else -> INDIVIDUAL
            }
        }
    }
}
