package org.onekash.icaldav.model

/** Holds one VFREEBUSY component (RFC 5545), as read by `ICalParser.parseFreeBusy`. */
data class ICalFreeBusy(
    val uid: String,
    val dtstamp: ICalDateTime,
    val dtstart: ICalDateTime,
    val dtend: ICalDateTime,
    val organizer: Organizer? = null,
    val attendees: List<Attendee> = emptyList(),
    val freeBusyPeriods: List<FreeBusyPeriod> = emptyList()
)

/** Holds one FREEBUSY period and its FBTYPE. */
data class FreeBusyPeriod(
    val start: ICalDateTime,
    val end: ICalDateTime,
    val type: FreeBusyType = FreeBusyType.BUSY
)

/**
 * FBTYPE parameter values (RFC 5545 §3.2.9). [fromString] maps an unrecognized value to BUSY,
 * as the RFC requires.
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
