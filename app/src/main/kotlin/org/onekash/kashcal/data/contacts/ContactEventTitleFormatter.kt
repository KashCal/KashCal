package org.onekash.kashcal.data.contacts

import org.onekash.kashcal.data.db.entity.Event

/**
 * Formats contact event titles (birthdays and anniversaries) with age/year info.
 *
 * Birthday events (caldavUrl starts with "contact_birthday:") get formatted as
 * "Name's 30th Birthday". Anniversary events (caldavUrl starts with "contact_anniversary:")
 * get formatted as "Name's 10th Anniversary".
 *
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
        val isBirthday = event.caldavUrl?.startsWith("${ContactBirthdayRepository.SOURCE_PREFIX}:") == true
        val isAnniversary = event.caldavUrl?.startsWith("${ContactAnniversaryRepository.SOURCE_PREFIX}:") == true
        return when {
            isBirthday && occurrenceTs != null -> {
                val year = ContactEventUtils.decodeEventYear(event.description)
                ContactEventUtils.formatBirthdayTitle(event.title, year, occurrenceTs)
            }
            isAnniversary && occurrenceTs != null -> {
                val year = ContactEventUtils.decodeEventYear(event.description)
                ContactEventUtils.formatAnniversaryTitle(event.title, year, occurrenceTs)
            }
            else -> event.title
        }
    }
}
