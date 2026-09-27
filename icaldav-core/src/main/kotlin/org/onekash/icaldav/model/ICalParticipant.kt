package org.onekash.icaldav.model

import java.time.Duration

/**
 * Holds one PARTICIPANT component (RFC 9073 §7.1): attendee details beyond the ATTENDEE
 * property, such as types, roles and a location reference. The parser and generator don't read
 * or write it.
 *
 * Name, participation status, roles, RSVP, scheduling agent, expected duration and language are
 * not RFC 9073 PARTICIPANT properties, and [participantTypes] holds CUTYPE-style values, not the
 * RFC's PARTICIPANT-TYPE values (§6.2: ACTIVE, SPEAKER and others).
 *
 * @see <a href="https://tools.ietf.org/html/rfc9073#section-7.1">RFC 9073 Section 7.1</a>
 */
data class ICalParticipant(
    /** UID property. */
    val uid: String,

    /** CALENDAR-ADDRESS, usually a mailto: URI. */
    val calendarAddress: String,

    /** Display name. */
    val name: String? = null,

    /** Participation status. */
    val participationStatus: PartStat = PartStat.NEEDS_ACTION,

    /** Participant types. */
    val participantTypes: Set<ParticipantType> = setOf(ParticipantType.INDIVIDUAL),

    /** Roles in the event. */
    val roles: Set<ParticipantRole> = setOf(ParticipantRole.ATTENDEE),

    /** Contact information. */
    val contact: String? = null,

    /** UID of a VLOCATION ([ICalLocation.uid]). */
    val locationId: String? = null,

    /** Expected participation duration. */
    val expectedDuration: Duration? = null,

    /** Who handles scheduling for this participant. */
    val schedulingAgent: SchedulingAgent = SchedulingAgent.SERVER,

    /** Whether a reply is requested. */
    val rsvp: Boolean = false,

    /** Language preference. */
    val language: String? = null
) {
    /** Returns whether [participationStatus] is ACCEPTED. */
    fun hasAccepted(): Boolean = participationStatus == PartStat.ACCEPTED

    /** Returns whether [roles] contains CHAIR. */
    fun isChair(): Boolean = roles.contains(ParticipantRole.CHAIR)

    /** Returns [calendarAddress] without a leading "mailto:" or "MAILTO:", trimmed. */
    fun email(): String {
        return calendarAddress
            .removePrefix("mailto:")
            .removePrefix("MAILTO:")
            .trim()
    }

    companion object {
        /**
         * Creates a participant from [email], adding "mailto:" unless it already contains a
         * `:`, with a random UID by default.
         */
        fun fromEmail(
            email: String,
            name: String? = null,
            uid: String = java.util.UUID.randomUUID().toString()
        ): ICalParticipant {
            val address = if (email.contains(":")) email else "mailto:$email"
            return ICalParticipant(
                uid = uid,
                calendarAddress = address,
                name = name
            )
        }
    }
}

/**
 * Participant types, the same set as the ATTENDEE CUTYPE values ([CUType]), not RFC 9073's
 * PARTICIPANT-TYPE values. [fromString] maps null, blank or unknown to UNKNOWN.
 */
enum class ParticipantType {
    /** An individual person. */
    INDIVIDUAL,

    /** A group of people. */
    GROUP,

    /** A bookable resource, for example a projector. */
    RESOURCE,

    /** A physical room. */
    ROOM,

    /** Unknown type. */
    UNKNOWN;

    fun toICalString(): String = name

    companion object {
        fun fromString(value: String?): ParticipantType {
            if (value.isNullOrBlank()) return UNKNOWN
            return entries.find { it.name.equals(value.trim(), ignoreCase = true) } ?: UNKNOWN
        }
    }
}

/**
 * Participant roles: the RFC 5545 ATTENDEE roles, with REQ-PARTICIPANT named ATTENDEE, plus
 * CONTACT and INFORMATIONAL. [fromString] maps null, blank or unknown to ATTENDEE.
 */
enum class ParticipantRole {
    /** Meeting chair. */
    CHAIR,

    /** Required attendee. */
    ATTENDEE,

    /** Optional participant. */
    OPT_PARTICIPANT,

    /** Copied for information, not participating. */
    NON_PARTICIPANT,

    /** Contact person for the event. */
    CONTACT,

    /** Informational recipient only. */
    INFORMATIONAL;

    fun toICalString(): String = name.replace("_", "-")

    companion object {
        fun fromString(value: String?): ParticipantRole {
            if (value.isNullOrBlank()) return ATTENDEE
            val normalized = value.uppercase().replace("-", "_")
            return entries.find { it.name == normalized } ?: ATTENDEE
        }
    }
}

/**
 * Who handles scheduling, with the same values as the RFC 6638 SCHEDULE-AGENT parameter.
 * [fromString] maps null, blank or unknown to SERVER.
 */
enum class SchedulingAgent {
    /** The server handles scheduling. */
    SERVER,

    /** The client handles scheduling. */
    CLIENT,

    /** No automatic scheduling. */
    NONE;

    fun toICalString(): String = name

    companion object {
        fun fromString(value: String?): SchedulingAgent {
            if (value.isNullOrBlank()) return SERVER
            return entries.find { it.name.equals(value.trim(), ignoreCase = true) } ?: SERVER
        }
    }
}
