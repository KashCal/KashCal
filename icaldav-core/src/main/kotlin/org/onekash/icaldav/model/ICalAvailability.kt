package org.onekash.icaldav.model

import java.time.Duration

/**
 * Holds a VAVAILABILITY component (RFC 7953): a period in which the user is [busyType] except
 * during the [available] slots, published for free-busy lookups (RFC 7953 §1).
 *
 * Example:
 * ```
 * BEGIN:VAVAILABILITY
 * UID:availability-1
 * DTSTART:20231201T000000Z
 * DTEND:20231231T235959Z
 * BUSYTYPE:BUSY-UNAVAILABLE
 * BEGIN:AVAILABLE
 * DTSTART:20231201T090000
 * DTEND:20231201T170000
 * RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR
 * SUMMARY:Office Hours
 * END:AVAILABLE
 * END:VAVAILABILITY
 * ```
 *
 * @see <a href="https://tools.ietf.org/html/rfc7953">RFC 7953 - VAVAILABILITY</a>
 */
data class ICalAvailability(
    val uid: String,

    /** Start of the period; null means unbounded (RFC 7953 §3.1). */
    val dtStart: ICalDateTime? = null,

    /** End of the period; null means unbounded. */
    val dtEnd: ICalDateTime? = null,

    val summary: String? = null,

    /** ORGANIZER: the calendar user whose available time this publishes. */
    val organizer: String? = null,

    /**
     * PRIORITY for combining overlapping components: 1 is highest, 9 lowest, and 0 (the
     * default) ranks below 9 (RFC 7953 §4).
     */
    val priority: Int = 0,

    /** AVAILABLE subcomponents: the free time within the period. */
    val available: List<AvailableSlot> = emptyList(),

    /** BUSYTYPE of the period outside [available]; RFC 7953 defaults it to BUSY-UNAVAILABLE. */
    val busyType: BusyType = BusyType.BUSY_UNAVAILABLE,

    val categories: List<String> = emptyList(),

    val lastModified: ICalDateTime? = null,

    val sequence: Int = 0
) {
    fun hasAvailableSlots(): Boolean = available.isNotEmpty()

    companion object {
        /**
         * Creates an unbounded BUSY-UNAVAILABLE availability titled [summary], with no
         * [available] slots; add the hours with `copy`.
         */
        fun workingHours(
            uid: String = java.util.UUID.randomUUID().toString(),
            summary: String = "Working Hours"
        ): ICalAvailability {
            return ICalAvailability(
                uid = uid,
                summary = summary,
                busyType = BusyType.BUSY_UNAVAILABLE
            )
        }
    }
}

/** Holds one AVAILABLE subcomponent: a window of free time, repeating when [rrule] is set. */
data class AvailableSlot(
    val dtStart: ICalDateTime,

    /** End of the window; mutually exclusive with [duration]. */
    val dtEnd: ICalDateTime? = null,

    /** Length of the window; mutually exclusive with [dtEnd]. */
    val duration: Duration? = null,

    val rrule: RRule? = null,

    /** EXDATEs: occurrences of [rrule] that aren't available. */
    val exdates: List<ICalDateTime> = emptyList(),

    val summary: String? = null,

    val location: String? = null,

    val categories: List<String> = emptyList()
) {
    /** Returns [dtEnd], else [dtStart] plus [duration], else [dtStart]. */
    fun effectiveEnd(): ICalDateTime {
        return dtEnd ?: duration?.let { dur ->
            ICalDateTime.fromTimestamp(
                timestamp = dtStart.timestamp + dur.toMillis(),
                timezone = dtStart.timezone,
                isDate = dtStart.isDate
            )
        } ?: dtStart
    }

    fun isRecurring(): Boolean = rrule != null

    companion object {
        /** Creates a non-repeating slot from [start] to [end]. */
        fun oneTime(
            start: ICalDateTime,
            end: ICalDateTime,
            summary: String? = null
        ): AvailableSlot {
            return AvailableSlot(
                dtStart = start,
                dtEnd = end,
                summary = summary
            )
        }
    }
}

/** BUSYTYPE values (RFC 7953 §3.2): the busy status of time outside the AVAILABLE slots. */
enum class BusyType {
    BUSY,

    /** The default. */
    BUSY_UNAVAILABLE,

    BUSY_TENTATIVE;

    fun toICalString(): String = name.replace("_", "-")

    companion object {
        /** Maps [value] ignoring case; null, blank or unknown values map to BUSY_UNAVAILABLE. */
        fun fromString(value: String?): BusyType {
            if (value.isNullOrBlank()) return BUSY_UNAVAILABLE
            val normalized = value.uppercase().replace("-", "_")
            return entries.find { it.name == normalized } ?: BUSY_UNAVAILABLE
        }
    }
}
