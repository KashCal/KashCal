package org.onekash.kashcal.data.ics

import org.onekash.kashcal.data.db.entity.Event

/** The METHOD of an imported iCalendar file (RFC 5546 §1.4). */
enum class ImportMethod {
    PUBLISH, REQUEST, REPLY, ADD, CANCEL, REFRESH, COUNTER, DECLINECOUNTER;

    /** True for an iTIP REQUEST or CANCEL, the methods that carry an invitation. */
    val isInvitation: Boolean
        get() = this == REQUEST || this == CANCEL

    companion object {
        /** Returns the method named by a METHOD value, or null for none or an unknown one. */
        fun fromValue(value: String?): ImportMethod? =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * An ATTENDEE of an imported component, without the server receipts a file may carry
 * (SCHEDULE-STATUS and the like). Holds no Room or sync type, so the device path can read it.
 *
 * @param address as the Room attendee row stores it: `mailto:` plus the address when
 *   email-shaped, otherwise the CAL-ADDRESS as written.
 */
data class ImportedAttendee(
    val address: String,
    val displayName: String?,
    val role: String?,
    val partstat: String?,
    val cutype: String?,
    val rsvp: Boolean?,
    val delegatedFrom: List<String> = emptyList(),
    val delegatedTo: List<String> = emptyList(),
    val member: List<String> = emptyList(),
    val sentBy: String? = null,
)

/**
 * One VEVENT of an imported file: the event as the pull would map it, with [Event.itipDtstamp]
 * set to the file's DTSTAMP, and its attendees.
 *
 * @param thisAndFuture true when its RECURRENCE-ID carries RANGE=THISANDFUTURE, which import
 *   doesn't apply.
 */
data class ImportedComponent(
    val event: Event,
    val attendees: List<ImportedAttendee>,
    val thisAndFuture: Boolean = false,
) {
    val isCancelled: Boolean get() = event.status == STATUS_CANCELLED
    val isInstance: Boolean get() = event.originalInstanceTime != null

    companion object {
        const val STATUS_CANCELLED = "CANCELLED"
    }
}

/**
 * The components of one UID in an imported file: its masters (normally one; a truncated export
 * can repeat it) and its RECURRENCE-ID components, in file order.
 */
data class ImportGroup(
    val uid: String,
    val masters: List<ImportedComponent>,
    val exceptions: List<ImportedComponent>,
) {
    val components: List<ImportedComponent> get() = masters + exceptions
}

/** An imported iCalendar file: its METHOD and one [ImportGroup] per UID, in file order. */
data class IcsImportFile(
    val method: ImportMethod?,
    val groups: List<ImportGroup>,
) {
    /** True when the file's METHOD carries an invitation ([ImportMethod.isInvitation]). */
    val isInvitation: Boolean
        get() = method?.isInvitation == true

    /** Every component's event, in file order. */
    val events: List<Event> get() = groups.flatMap { group -> group.components.map { it.event } }
}
