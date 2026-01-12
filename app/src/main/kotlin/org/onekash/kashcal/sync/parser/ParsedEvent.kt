package org.onekash.kashcal.sync.parser

/**
 * Represents a parsed VEVENT from iCal data.
 *
 * This is the intermediate representation between raw iCal text and our
 * database Event entity. Both regex and ical4j parsers produce this same
 * output for comparison testing.
 */
data class ParsedEvent(
    /** RFC 5545 UID */
    val uid: String,

    /** RECURRENCE-ID value for modified instances, null for master events */
    val recurrenceId: String?,

    /** SUMMARY property */
    val summary: String,

    /** DESCRIPTION property (unescaped) */
    val description: String,

    /** LOCATION property */
    val location: String,

    /** Start timestamp in epoch seconds */
    val startTs: Long,

    /** End timestamp in epoch seconds */
    val endTs: Long,

    /** True if DATE (not DATETIME) - all-day event */
    val isAllDay: Boolean,

    /** Timezone ID from DTSTART (e.g., "America/New_York") */
    val timezone: String?,

    /** Raw RRULE string if present */
    val rrule: String?,

    /** EXDATE values as day codes (YYYYMMDD format) */
    val exdates: List<String>,

    /** RDATE values as day codes (YYYYMMDD format) */
    val rdates: List<String>,

    /** VALARM trigger minutes before event (positive = before) */
    val reminderMinutes: List<Int>,

    /** SEQUENCE number for conflict detection */
    val sequence: Int,

    /** STATUS property (CONFIRMED, TENTATIVE, CANCELLED) */
    val status: String?,

    /** DTSTAMP timestamp in epoch seconds */
    val dtstamp: Long,

    /** ORGANIZER email if present */
    val organizerEmail: String?,

    /** ORGANIZER CN (display name) if present */
    val organizerName: String?,

    /** RFC 5545 TRANSP - time transparency (OPAQUE/TRANSPARENT) */
    val transp: String? = null,

    /** RFC 5545 CLASS - access classification (PUBLIC/PRIVATE/CONFIDENTIAL) */
    val classification: String? = null,

    /** Extra properties (X-APPLE-*, X-GOOGLE-*, etc.) for round-trip preservation */
    val extraProperties: Map<String, String>? = null,

    /** Raw iCal data for this event (for debugging/round-trip) */
    val rawIcal: String?
) {
    /**
     * Generate a unique import ID for this event.
     * Master events: just UID
     * Exception events: UID:RECURRENCE-ID:datetime
     */
    fun toImportId(): String {
        return if (recurrenceId != null) {
            "$uid:RECURRENCE-ID:$recurrenceId"
        } else {
            uid
        }
    }

    /**
     * Check if this is a recurring event (has RRULE).
     */
    fun isRecurring(): Boolean = rrule != null

    /**
     * Check if this is an exception to a recurring event.
     */
    fun isException(): Boolean = recurrenceId != null

    companion object {
        /** Day code format YYYYMMDD */
        fun formatDayCode(year: Int, month: Int, day: Int): String {
            return "%04d%02d%02d".format(year, month, day)
        }
    }
}
