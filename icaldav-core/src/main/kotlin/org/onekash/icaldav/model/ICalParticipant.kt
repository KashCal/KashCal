package org.onekash.icaldav.model

import java.time.Duration

/**
 * PARTICIPANT component per RFC 9073 Section 6.2.
 * Provides richer attendee information than the ATTENDEE property.
 *
 * PARTICIPANT extends the capabilities of ATTENDEE with additional
 * metadata like participant types, multiple roles, location references,
 * and scheduling preferences.
 *
 * Example iCalendar format:
 * ```
 * BEGIN:PARTICIPANT
 * UID:participant-1
 * CALENDAR-ADDRESS:mailto:john@example.com
 * NAME:John Doe
 * PARTICIPANT-TYPE:INDIVIDUAL
 * PARTICIPATION-STATUS:ACCEPTED
 * END:PARTICIPANT
 * ```
 *
 * @see <a href="https://tools.ietf.org/html/rfc9073#section-6.2">RFC 9073 Section 6.2</a>
 */
data class ICalParticipant(
    /** Unique identifier for this participant */
    val uid: String,

    /** Calendar address (typically mailto: URI) */
    val calendarAddress: String,

    /** Display name */
    val name: String? = null,

    /** Participation status */
    val participationStatus: PartStat = PartStat.NEEDS_ACTION,

    /** Types of participant */
    val participantTypes: Set<ParticipantType> = setOf(ParticipantType.INDIVIDUAL),

    /** Roles in the event */
    val roles: Set<ParticipantRole> = setOf(ParticipantRole.ATTENDEE),

    /** Contact information */
    val contact: String? = null,

    /** Reference to a VLOCATION uid */
    val locationId: String? = null,

    /** Expected participation duration */
    val expectedDuration: Duration? = null,

    /** Scheduling agent mode */
    val schedulingAgent: SchedulingAgent = SchedulingAgent.SERVER,

    /** Whether RSVP is requested */
    val rsvp: Boolean = false,

    /** Language preference */
    val language: String? = null
) {
    /**
     * Check if this participant has accepted.
     */
    fun hasAccepted(): Boolean = participationStatus == PartStat.ACCEPTED

    /**
     * Check if this participant is the chair/organizer.
     */
    fun isChair(): Boolean = roles.contains(ParticipantRole.CHAIR)

    /**
     * Extract email from calendar address.
     */
    fun email(): String {
        return calendarAddress
            .removePrefix("mailto:")
            .removePrefix("MAILTO:")
            .trim()
    }

    companion object {
        /**
         * Create a simple participant from email.
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
 * Participant type per RFC 9073.
 */
enum class ParticipantType {
    /** An individual person */
    INDIVIDUAL,

    /** A group of people */
    GROUP,

    /** A bookable resource (projector, etc.) */
    RESOURCE,

    /** A physical room */
    ROOM,

    /** Unknown participant type */
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
 * Participant role per RFC 9073.
 * Extended from RFC 5545 attendee roles.
 */
enum class ParticipantRole {
    /** Meeting chair/organizer */
    CHAIR,

    /** Required attendee */
    ATTENDEE,

    /** Optional participant */
    OPT_PARTICIPANT,

    /** Non-participating (FYI) */
    NON_PARTICIPANT,

    /** Contact person for the event */
    CONTACT,

    /** Informational recipient only */
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
 * Scheduling agent mode per RFC 9073.
 * Determines how scheduling operations are handled.
 */
enum class SchedulingAgent {
    /** Server handles all scheduling */
    SERVER,

    /** Client handles scheduling */
    CLIENT,

    /** No automatic scheduling */
    NONE;

    fun toICalString(): String = name

    companion object {
        fun fromString(value: String?): SchedulingAgent {
            if (value.isNullOrBlank()) return SERVER
            return entries.find { it.name.equals(value.trim(), ignoreCase = true) } ?: SERVER
        }
    }
}
