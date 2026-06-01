package org.onekash.kashcal.domain.share

import org.onekash.kashcal.data.db.entity.Event
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Synthesize a standalone single-occurrence [Event] for share-card .ics
 * export.
 *
 * The share-card flow attaches a .ics next to the rendered PNG so recipients
 * can tap to add. The .ics should describe ONE event — the specific
 * occurrence the user is sharing — not the entire recurring series. This
 * helper takes a master (or exception) event plus the user-tapped occurrence
 * timestamps and returns a non-recurring, non-exception, fresh-UID event
 * stripped of any data that should not be shared with a recipient.
 *
 * What's stripped, and why:
 *  - `rawIcal`: forces IcsExporter / IcsPatcher down the `generateFresh`
 *    code path. The patch path preserves ATTENDEE, ORGANIZER, RECURRENCE-ID,
 *    and X-* properties verbatim from the original CalDAV body. Without
 *    this clearing, a recurring event synced from iCloud / Nextcloud /
 *    Radicale would leak every attendee's email and RSVP into the
 *    share-card .ics.
 *  - `originalEventId` / `originalInstanceTime` / `rrule`: clears
 *    series-membership state so the emitted VEVENT has no RRULE or
 *    RECURRENCE-ID.
 *  - `organizerEmail` / `organizerName` / `organizerSentBy` /
 *    `organizerScheduleStatus`: defensive — the recipient should not
 *    receive an ORGANIZER property pointing at the sender's email; some
 *    receiving calendars treat ORGANIZER as an iTIP-routing trigger.
 *  - `extraProperties`: sender's X-* extensions stay with the sender.
 *  - `etag` / `caldavUrl`: server-bound state that has no meaning after
 *    a copy.
 *
 * What's normalized:
 *  - **All-day timestamps**: snapped to UTC midnight of the local calendar
 *    day in the event's timezone. KashCal stores all-day events as "local
 *    midnight in event TZ" (e.g. May 31 00:00:00 PDT = ms epoch
 *    1748674800000). The CalDAV-side ICS DATE serializer reads these
 *    timestamps via UTC, which can shift the displayed date by a day
 *    when the event TZ is east of UTC. For the share-card flow we
 *    convert the timestamp into a LocalDate in the event's TZ first,
 *    then re-anchor to UTC midnight (the canonical RFC 5545 DATE
 *    storage). This guarantees DTSTART/DTEND on a 4-day event in
 *    Australia/Sydney emit `20260531`/`20260604` instead of
 *    `20260530`/`20260603`. Non-all-day timestamps are left alone —
 *    DATE-TIME values use TZID and are TZ-correct already.
 *
 * What's set fresh:
 *  - `uid`: random UUID so the recipient's calendar treats this as a
 *    brand-new insert, not an update to the sender's master.
 *  - `startTs` / `endTs`: from the occurrence the user tapped (then
 *    normalized for all-day, see above).
 *  - `dtstamp` / `createdAt` / `updatedAt`: set to [nowMs] per RFC 5545
 *    §3.8.7.2 — DTSTAMP is the time the iCalendar object was created.
 *  - `id`: 0 (this Event is never persisted to Room).
 *
 * Display fields preserved: title, description, location, timezone,
 * isAllDay, color, etc.
 *
 * Pure: no side effects, no I/O. [nowMs] is a parameter so tests can pin
 * the timestamp.
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
 * For all-day events, snap [startTs] / [endTs] to UTC midnight of the
 * local calendar date in the event's [tzid]. Returns a (start, end) pair
 * where `end` is the inclusive end (last day's UTC midnight + 23:59:59.999)
 * — the exporter applies its standard +1 ms exclusive-end transform on
 * top of that.
 *
 * For non-all-day events, returns the input unchanged.
 *
 * Visible for testing.
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
    // Inclusive end timestamp is the LAST DAY's UTC midnight + 23:59:59.999.
    // The mapper's exclusiveEndTs(event) adds 1 ms to make it RFC 5545
    // exclusive (next day 00:00 UTC) for serialization.
    //
    // Example: a 4-day event May 31 → Jun 3 (inclusive) yields
    //   startDate = May 31; newStart = May 31 00:00 UTC
    //   endDate   = Jun 3;  newEnd   = Jun 3 23:59:59.999 UTC
    //   exclusive (mapper adds +1 ms) = Jun 4 00:00 UTC → DTEND=20260604
    val newEnd = endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() +
        (24L * 60 * 60 * 1000 - 1)
    return newStart to newEnd
}

/**
 * Pick the [ZoneId] the share-card preview should read [Event.startTs] /
 * [Event.endTs] in.
 *
 * For all-day events, all three storage paths (locally-created, ICS /
 * CalDAV-imported, device CalendarProvider) anchor `startTs` to UTC
 * midnight — but `Event.timezone` is inconsistent across them
 * (null for ICS DATE values, the user's IANA zone for locally-created,
 * "UTC" or whatever the sync adapter wrote for device events). Reading
 * a UTC-anchored timestamp through any non-UTC zone day-shifts the
 * displayed calendar date for users west of UTC. Always read all-day
 * timestamps as UTC.
 *
 * For timed events, the zone is the event's own IANA zone — falling
 * back to system default for null / blank / non-IANA values so the
 * share flow doesn't crash on legacy Outlook-style timezone names
 * like "Pacific Standard Time".
 *
 * Pure: no side effects, no I/O.
 */
fun shareCardZone(timezone: String?, isAllDay: Boolean): ZoneId {
    if (isAllDay) return UTC_ZONE
    return timezone?.takeIf { it.isNotBlank() }
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.systemDefault()
}

private val UTC_ZONE: ZoneId = ZoneId.of("UTC")
