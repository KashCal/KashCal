package org.onekash.icaldav.model

/**
 * VJOURNAL component representing a journal entry per RFC 5545 Section 3.6.3.
 *
 * VJOURNAL is used to represent one or more descriptive text notes associated
 * with a particular calendar date. Examples include a daily record of activities,
 * accomplishments, or thoughts.
 *
 * All properties except uid have defaults for easy construction.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5545#section-3.6.3">RFC 5545 Section 3.6.3 - Journal Component</a>
 */
data class ICalJournal(
    /** Unique identifier from UID property (required) */
    val uid: String,

    /**
     * Unique import ID for database storage.
     * Format: "{uid}" or "{uid}:RECID:{datetime}" for modified instances.
     */
    val importId: String = "",

    /** Journal summary/title from SUMMARY property */
    val summary: String? = null,

    /** Journal description/content from DESCRIPTION property */
    val description: String? = null,

    /** Start date/time the journal entry is associated with from DTSTART property */
    val dtStart: ICalDateTime? = null,

    /** Journal status from STATUS property */
    val status: JournalStatus = JournalStatus.DRAFT,

    /** Sequence number for conflict detection from SEQUENCE property */
    val sequence: Int = 0,

    /** Creation timestamp from DTSTAMP property */
    val dtstamp: ICalDateTime? = null,

    /** Created timestamp from CREATED property */
    val created: ICalDateTime? = null,

    /** Last modified timestamp from LAST-MODIFIED property */
    val lastModified: ICalDateTime? = null,

    /** Categories/tags from CATEGORIES property */
    val categories: List<String> = emptyList(),

    /** Journal organizer from ORGANIZER property */
    val organizer: Organizer? = null,

    /** Attendees who should receive the journal from ATTENDEE properties */
    val attendees: List<Attendee> = emptyList(),

    /** Attachments from ATTACH properties (URIs or inline data) */
    val attachments: List<String> = emptyList(),

    /** Recurrence rule from RRULE property (for recurring journal entries) */
    val rrule: RRule? = null,

    /**
     * RECURRENCE-ID for modified instances of recurring journals.
     * Non-null indicates this is a modified occurrence.
     */
    val recurrenceId: ICalDateTime? = null,

    /** URL associated with the journal from URL property */
    val url: String? = null,

    /** Class/access classification from CLASS property */
    val classification: String? = null,

    /** Preserve unknown properties for round-trip fidelity */
    val rawProperties: Map<String, String> = emptyMap()
) {
    /**
     * Generate importId for a journal.
     */
    companion object {
        fun generateImportId(uid: String, recurrenceId: ICalDateTime?): String {
            return if (recurrenceId != null) {
                "$uid:RECID:${recurrenceId.toICalString()}"
            } else {
                uid
            }
        }
    }

    /**
     * Check if this is a recurring journal.
     */
    fun isRecurring(): Boolean = rrule != null

    /**
     * Check if this is a modified instance of a recurring journal.
     */
    fun isModifiedInstance(): Boolean = recurrenceId != null
}

/**
 * VJOURNAL status values per RFC 5545.
 */
enum class JournalStatus {
    /** Journal is a draft, not yet finalized */
    DRAFT,
    /** Journal is finalized and complete */
    FINAL,
    /** Journal has been cancelled */
    CANCELLED;

    fun toICalString(): String = name

    companion object {
        fun fromString(value: String?): JournalStatus {
            return when (value?.uppercase()) {
                "FINAL" -> FINAL
                "CANCELLED" -> CANCELLED
                else -> DRAFT
            }
        }
    }
}
