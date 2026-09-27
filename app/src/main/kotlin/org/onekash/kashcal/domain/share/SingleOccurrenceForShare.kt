package org.onekash.kashcal.domain.share

import org.onekash.kashcal.data.db.entity.Event
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Builds a standalone, non-recurring copy of one occurrence of [event] for the .ics the share
 * card attaches, so the recipient can add that one event and never the series.
 *
 * Cleared, so none of the sender's data or series state reaches the recipient:
 *  - `rawIcal`, so IcsPatcher takes `generateFresh`. The patch path keeps ATTENDEE,
 *    ORGANIZER, RECURRENCE-ID and X-* properties from the original CalDAV body, so a synced
 *    event would leak every attendee's email and RSVP.
 *  - `rrule`, `originalEventId` and `originalInstanceTime`, so the VEVENT has no RRULE or
 *    RECURRENCE-ID.
 *  - `organizerEmail`, `organizerName`, `organizerSentBy` and `organizerScheduleStatus`: the
 *    recipient shouldn't get an ORGANIZER naming the sender, and some receiving calendars treat
 *    ORGANIZER as an iTIP-routing trigger.
 *  - `extraProperties` (the sender's X-* extensions), `etag` and `caldavUrl`.
 *
 * Set fresh:
 *  - `uid`: a random UUID, so the recipient's calendar inserts a new event instead of updating
 *    the sender's.
 *  - `startTs` and `endTs`: the tapped occurrence, all-day ranges re-anchored by
 *    [normalizeAllDay].
 *  - `dtstamp`, `createdAt` and `updatedAt`: [nowMs]; DTSTAMP is when the iCalendar object was
 *    created (RFC 5545 §3.8.7.2).
 *  - `id`: 0, since the copy is never stored in Room.
 *
 * Every other field (title, description, location, timezone, all-day flag, color and so on) is
 * kept. Does no I/O; [nowMs] is a parameter so tests can pin it.
 */
fun singleOccurrenceForShare(
    event: Event,
    occurrenceStartTs: Long,
    occurrenceEndTs: Long,
    nowMs: Long = System.currentTimeMillis(),
): Event {
    val (normalizedStart, normalizedEnd) = normalizeAllDay(
        occurrenceStartTs, occurrenceEndTs, event.isAllDay, event.timezone
    )
    return event.copy(
        id = 0L,
        uid = UUID.randomUUID().toString(),
        startTs = normalizedStart,
        endTs = normalizedEnd,
        rrule = null,
        originalEventId = null,
        originalInstanceTime = null,
        rawIcal = null,
        organizerEmail = null,
        organizerName = null,
        organizerSentBy = null,
        organizerScheduleStatus = null,
        extraProperties = null,
        etag = null,
        caldavUrl = null,
        dtstamp = nowMs,
        createdAt = nowMs,
        updatedAt = nowMs,
    )
}

/**
 * Re-anchors an all-day range to UTC: returns (start, end) where start is UTC midnight of
 * [startTs]'s date and end is 23:59:59.999 UTC of [endTs]'s date, both dates read in [tzid] (the
 * system zone when it is null or not a valid zone id). The ICS DATE serializer reads all-day
 * timestamps in UTC. A timed range is returned unchanged.
 */
internal fun normalizeAllDay(
    startTs: Long,
    endTs: Long,
    isAllDay: Boolean,
    tzid: String?,
): Pair<Long, Long> {
    if (!isAllDay) return startTs to endTs
    val zone = tzid?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.systemDefault()
    val startDate = Instant.ofEpochMilli(startTs).atZone(zone).toLocalDate()
    val endDate = Instant.ofEpochMilli(endTs).atZone(zone).toLocalDate()
    val newStart = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    // The end is inclusive, like a stored all-day endTs; EventToICalEventMapper.exclusiveEndTs
    // adds 1 ms for the RFC 5545 exclusive DTEND. May 31 to Jun 3 gives an end of
    // Jun 3 23:59:59.999 UTC and DTEND=20260604.
    val newEnd = endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() +
        (24L * 60 * 60 * 1000 - 1)
    return newStart to newEnd
}

/**
 * Returns the zone the share-card preview reads [Event.startTs] and [Event.endTs] in.
 *
 * All-day events always read in UTC. Local, ICS, CalDAV and device events all store an all-day
 * start at UTC midnight, but `Event.timezone` differs between them (null for ICS DATE values,
 * the user's zone for local events, "UTC" or whatever the sync adapter wrote for device
 * events), and reading a UTC midnight in a zone west of UTC shows the previous day.
 *
 * Timed events read in their own zone, falling back to the system zone for a null, blank or
 * invalid id, so a non-IANA name such as "Pacific Standard Time" doesn't crash the share
 * flow.
 */
fun shareCardZone(timezone: String?, isAllDay: Boolean): ZoneId {
    if (isAllDay) return UTC_ZONE
    return timezone?.takeIf { it.isNotBlank() }
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.systemDefault()
}

private val UTC_ZONE: ZoneId = ZoneId.of("UTC")
