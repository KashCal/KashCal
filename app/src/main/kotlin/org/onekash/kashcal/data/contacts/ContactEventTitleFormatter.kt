package org.onekash.kashcal.data.contacts

import org.onekash.kashcal.data.db.entity.Event

/**
 * Formats contact event titles (birthdays and anniversaries) with age/year info.
 *
 * Birthday events get formatted as "Name's 30th Birthday".
 * Anniversary events get formatted as "Name's 10th Anniversary".
 * Non-contact events are returned unchanged.
 */
object ContactEventTitleFormatter {

    /**
     * Format an event title, adding age/year info for contact events.
     *
     * @param event The event to format
     * @param occurrenceTs Occurrence timestamp for year calculation (null returns raw title)
     * @return Formatted title
     */
    fun format(event: Event, occurrenceTs: Long?): String {
        val eventType = ContactEventType.fromCaldavUrl(event.caldavUrl) ?: return event.title
        if (occurrenceTs == null) return event.title
        val year = ContactEventUtils.decodeEventYear(event.description)
        return eventType.formatTitle(event.title, year, occurrenceTs)
    }
}
