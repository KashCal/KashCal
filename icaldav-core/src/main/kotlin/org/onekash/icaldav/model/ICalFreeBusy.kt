package org.onekash.icaldav.model

/**
 * VFREEBUSY component per RFC 5545.
 * Used for free/busy time queries in scheduling.
 */
data class ICalFreeBusy(
    val uid: String,
    val dtstamp: ICalDateTime,
    val dtstart: ICalDateTime,
    val dtend: ICalDateTime,
    val organizer: Organizer? = null,
    val attendees: List<Attendee> = emptyList(),
    val freeBusyPeriods: List<FreeBusyPeriod> = emptyList()
)

/**
 * A single free/busy time period.
 */
data class FreeBusyPeriod(
    val start: ICalDateTime,
    val end: ICalDateTime,
    val type: FreeBusyType = FreeBusyType.BUSY
)

/**
 * FBTYPE parameter values per RFC 5545.
 */
enum class FreeBusyType(val value: String) {
    FREE("FREE"),
    BUSY("BUSY"),
    BUSY_UNAVAILABLE("BUSY-UNAVAILABLE"),
    BUSY_TENTATIVE("BUSY-TENTATIVE");

    companion object {
        fun fromString(value: String): FreeBusyType =
            entries.find { it.value.equals(value, ignoreCase = true) } ?: BUSY
    }
}
