package org.onekash.kashcal.domain.model

import org.onekash.kashcal.data.db.entity.Event

/**
 * Builds the source [Event] the duplicate form copies from, for the occurrence the user
 * tapped at [occurrenceTs] (null for the event's own start).
 *
 * A series occurrence starts the copy on that occurrence with the series' RRULE and zone.
 * Two kinds of occurrence give a one-off at their own time instead:
 * - a changed occurrence (an exception row), a single instance with no rule of its own;
 * - an occurrence listed in RDATE, since starting the rule there would put DTSTART on a date
 *   the rule may not produce, which leaves the recurrence set undefined (RFC 5545 section
 *   3.8.5.3). An RDATE entry that repeats a rule instance is treated the same way.
 *
 * RDATE and EXDATE belong to the source series and are never copied. The receiver must be the
 * row the occurrence shows: a caller holding a series row and a changed occurrence's time
 * passes the exception row instead. Device events take [DisplayEvent.Device.toEventForDuplicate].
 */
fun Event.toEventForDuplicate(occurrenceTs: Long?): Event {
    val start = occurrenceTs?.takeIf { rrule != null && !isException } ?: startTs
    val isExtraDate = start != startTs &&
        rdate?.split(",")?.any { it.trim().toLongOrNull() == start } == true
    return copy(
        startTs = start,
        endTs = start + (endTs - startTs),
        rrule = rrule.takeUnless { isException || isExtraDate },
        rdate = null,
        exdate = null,
    )
}
