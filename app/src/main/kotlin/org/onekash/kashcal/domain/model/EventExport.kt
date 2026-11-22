package org.onekash.kashcal.domain.model

import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Event

/**
 * One Room event read for ICS export: the row, its changed occurrences when it is a recurring
 * series, and a map holding at least their attendee rows, keyed by event id (a whole-calendar
 * read shares one map across the calendar).
 *
 * The attendee rows are what an export writes as the event's guests, the user's own answer
 * included; a row with none is handled as [org.onekash.kashcal.util.IcsExporter] describes.
 * [icsByEventId] holds the stored ICS the export patches
 * ([org.onekash.kashcal.domain.reader.EventReader.getIcsForExport]); a row without one is
 * generated from its fields.
 */
data class EventExport(
    val event: Event,
    val exceptions: List<Event>,
    val attendeesByEventId: Map<Long, List<Attendee>>,
    val icsByEventId: Map<Long, String> = emptyMap(),
)
