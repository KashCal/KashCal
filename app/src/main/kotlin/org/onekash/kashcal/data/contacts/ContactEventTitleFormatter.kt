package org.onekash.kashcal.data.contacts

import android.content.res.Resources
import org.onekash.kashcal.data.db.entity.Event

/**
 * Formats birthday and anniversary titles with the year count, such as "Name's 30th Birthday".
 *
 * Returns the stored title unchanged for a non-contact event or a null occurrence time.
 */
object ContactEventTitleFormatter {

    /** Returns the title built from hardcoded English text. */
    fun format(event: Event, occurrenceTs: Long?): String {
        val eventType = ContactEventType.fromCaldavUrl(event.caldavUrl) ?: return event.title
        if (occurrenceTs == null) return event.title
        val year = ContactEventUtils.decodeEventYear(event.description)
        return eventType.formatTitle(event.title, year, occurrenceTs)
    }

    /** Returns the title from localized string resources. */
    fun format(event: Event, occurrenceTs: Long?, resources: Resources): String {
        val eventType = ContactEventType.fromCaldavUrl(event.caldavUrl) ?: return event.title
        if (occurrenceTs == null) return event.title
        val year = ContactEventUtils.decodeEventYear(event.description)
        return eventType.formatTitleI18n(event.title, year, occurrenceTs, resources)
    }
}
