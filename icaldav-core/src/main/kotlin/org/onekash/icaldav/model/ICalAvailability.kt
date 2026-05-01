package org.onekash.icaldav.model

import java.time.Duration

/**
 * VAVAILABILITY component per RFC 7953.
 * Defines when a user is available for scheduling.
 *
 * VAVAILABILITY is used for Calendly-style booking pages, allowing users
 * to publish their available time slots for others to schedule against.
 *
 * Example iCalendar format:
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
    /** Unique identifier */
    val uid: String,

    /** Start of availability period */
    val dtStart: ICalDateTime? = null,

    /** End of availability period */
    val dtEnd: ICalDateTime? = null,

    /** Summary/title for this availability */
    val summary: String? = null,

    /** Calendar address this availability applies to */
    val organizer: String? = null,

    /** Priority (0 = undefined, 1 = highest, 9 = lowest) */
    val priority: Int = 0,

    /** Available time slots within this period */
    val available: List<AvailableSlot> = emptyList(),

    /** Default busy type when not in available slots */
    val busyType: BusyType = BusyType.BUSY_UNAVAILABLE,

    /** Categories for filtering */
    val categories: List<String> = emptyList(),

    /** Last modified timestamp */
    val lastModified: ICalDateTime? = null,

    /** Sequence number for versioning */
    val sequence: Int = 0
) {
    /**
     * Check if there are any available slots.
     */
    fun hasAvailableSlots(): Boolean = available.isNotEmpty()

    companion object {
        /**
         * Create a simple availability with working hours.
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

/**
 * AVAILABLE sub-component defining specific available time slots.
 *
 * Each AVAILABLE defines a window of time when the user can accept
 * meetings. Can include recurrence rules for repeating availability.
 */
data class AvailableSlot(
    /** Start of availability window */
    val dtStart: ICalDateTime,

    /** End of availability window (mutually exclusive with duration) */
    val dtEnd: ICalDateTime? = null,

    /** Duration of availability (mutually exclusive with dtEnd) */
    val duration: Duration? = null,

    /** Recurrence rule for repeating availability */
    val rrule: RRule? = null,

    /** Exception dates when this slot is not available */
    val exdates: List<ICalDateTime> = emptyList(),

    /** Summary/label for this slot */
    val summary: String? = null,

    /** Location for this availability */
    val location: String? = null,

    /** Categories for this slot */
    val categories: List<String> = emptyList()
) {
    /**
     * Calculate the effective end time.
     */
    fun effectiveEnd(): ICalDateTime {
        return dtEnd ?: duration?.let { dur ->
            ICalDateTime.fromTimestamp(
                timestamp = dtStart.timestamp + dur.toMillis(),
                timezone = dtStart.timezone,
                isDate = dtStart.isDate
            )
        } ?: dtStart
    }

    /**
     * Check if this slot recurs.
     */
    fun isRecurring(): Boolean = rrule != null

    companion object {
        /**
         * Create a simple one-time slot.
         */
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

/**
 * Busy type per RFC 7953.
 * Defines the default busy status when not in available slots.
 */
enum class BusyType {
    /** Generic busy status */
    BUSY,

    /** Definitely unavailable (default) */
    BUSY_UNAVAILABLE,

    /** Tentatively busy, might be available */
    BUSY_TENTATIVE;

    fun toICalString(): String = name.replace("_", "-")

    companion object {
        fun fromString(value: String?): BusyType {
            if (value.isNullOrBlank()) return BUSY_UNAVAILABLE
            val normalized = value.uppercase().replace("-", "_")
            return entries.find { it.name == normalized } ?: BUSY_UNAVAILABLE
        }
    }
}
