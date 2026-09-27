package org.onekash.icaldav.model

/**
 * Holds one VJOURNAL component (RFC 5545 §3.6.3): descriptive text notes tied to a calendar
 * date, such as a daily record of activities. Every property except [uid] has a default.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5545#section-3.6.3">RFC 5545 Section 3.6.3</a>
 */
data class ICalJournal(
    /** UID property; the parser generates a random one when it's missing or blank. */
    val uid: String,

    /** "{uid}", or "{uid}:RECID:{datetime}" for an exception; built by [generateImportId]. */
    val importId: String = "",

    /** SUMMARY property. */
    val summary: String? = null,

    /** DESCRIPTION property (the entry's text). */
    val description: String? = null,

    /** DTSTART property: the date the entry is associated with. */
    val dtStart: ICalDateTime? = null,

    /** STATUS property; absent or unknown values read as DRAFT. */
    val status: JournalStatus = JournalStatus.DRAFT,

    /** SEQUENCE property (revision number), 0 when absent. */
    val sequence: Int = 0,

    /** DTSTAMP property; meaning as on [ICalEvent.dtstamp]. */
    val dtstamp: ICalDateTime? = null,

    /** CREATED property. */
    val created: ICalDateTime? = null,

    /** LAST-MODIFIED property. */
    val lastModified: ICalDateTime? = null,

    /** CATEGORIES values, blank entries dropped. */
    val categories: List<String> = emptyList(),

    /** ORGANIZER property. */
    val organizer: Organizer? = null,

    /** ATTENDEE properties. */
    val attendees: List<Attendee> = emptyList(),

    /** ATTACH values as text: a URI or the inline data. */
    val attachments: List<String> = emptyList(),

    /** RRULE property; the parser leaves it null on an exception. */
    val rrule: RRule? = null,

    /** RECURRENCE-ID property; non-null only on an exception. */
    val recurrenceId: ICalDateTime? = null,

    /** URL property. */
    val url: String? = null,

    /** CLASS property as its raw value. */
    val classification: String? = null,

    /**
     * Unknown properties as name to value, kept for round trips. The generator writes them only
     * when [unknownPropertyLines] is empty.
     */
    val rawProperties: Map<String, String> = emptyMap(),

    /**
     * Unknown properties as the original, unfolded content lines, in document order. When
     * non-empty the generator writes these and ignores [rawProperties].
     */
    val unknownPropertyLines: List<String> = emptyList()
) {
    /** Builds importIds in the same format as [ICalEvent.generateImportId]. */
    companion object {
        fun generateImportId(uid: String, recurrenceId: ICalDateTime?): String {
            return if (recurrenceId != null) {
                "$uid:RECID:${recurrenceId.toICalString()}"
            } else {
                uid
            }
        }
    }

    /** Returns whether this journal has an RRULE. */
    fun isRecurring(): Boolean = rrule != null

    /** Returns whether this is an exception (has a RECURRENCE-ID). */
    fun isModifiedInstance(): Boolean = recurrenceId != null
}

/** VJOURNAL STATUS values (RFC 5545 §3.8.1.11); [fromString] maps absent or unknown to DRAFT. */
enum class JournalStatus {
    /** Not yet final. */
    DRAFT,
    /** Final. */
    FINAL,
    /** Cancelled. */
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
