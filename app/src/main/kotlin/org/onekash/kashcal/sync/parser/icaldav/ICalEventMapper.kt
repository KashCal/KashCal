package org.onekash.kashcal.sync.parser.icaldav

import android.graphics.Color
import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.util.DurationUtils
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.ui.shared.EventColorPalette
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Maps icaldav ICalEvent to KashCal Event entity.
 *
 * This mapper is the bridge between the icaldav library's data model
 * and KashCal's Room database entity. It handles:
 * - Timestamp conversion (icaldav uses milliseconds)
 * - RECURRENCE-ID → importId mapping for exception events
 * - Alarm conversion (Duration → RFC 5545 trigger string)
 * - Property preservation for round-trip fidelity
 *
 * Note: KashCal Event stores timestamps in MILLISECONDS.
 */
/**
 * Inbound mapping result: the [Event] plus any [Attendee] rows extracted
 * from the same ICalEvent. Attendees carry `eventId = 0L` because the
 * parent event hasn't been upserted yet; callers must
 * `attendees.map { it.copy(eventId = savedId) }` after the parent
 * upsert returns its ID.
 */
data class MappedEntity(
    val event: Event,
    val attendees: List<Attendee>
)

object ICalEventMapper {

    /**
     * Convert icaldav ICalEvent to KashCal Event entity plus the parsed
     * ATTENDEE rows. Returns [MappedEntity] so callers destructure cleanly.
     *
     * The Attendee rows carry `eventId = 0L` — callers must rewrite
     * that after the parent event upsert with
     * `attendees.map { it.copy(eventId = savedEventId) }`.
     *
     * @param icalEvent The parsed icaldav event
     * @param rawIcal The original ICS data for round-trip preservation
     * @param calendarId The target calendar ID
     * @param caldavUrl The CalDAV URL for this event resource
     * @param etag The HTTP ETag from server
     * @param masterDtStart When [icalEvent] is an exception (recurrenceId
     *   != null), pass the master's DTSTART so the mapper can normalize a
     *   value-type-mismatched RECURRENCE-ID before storing
     *   originalInstanceTime. Pass null for masters or when the master
     *   isn't available — the mapper falls back to the recurrenceId
     *   timestamp verbatim.
     */
    fun toEntity(
        icalEvent: ICalEvent,
        rawIcal: String?,
        calendarId: Long,
        caldavUrl: String?,
        etag: String?,
        masterDtStart: ICalDateTime? = null,
    ): MappedEntity {
        val now = System.currentTimeMillis()

        // Calculate effective end time
        val effectiveEnd = icalEvent.effectiveEnd()

        // For all-day events, adjust endTs to be inclusive (last second of last day)
        // RFC 5545: DTEND is exclusive for all-day events
        val endTs = if (icalEvent.isAllDay && effectiveEnd.timestamp > icalEvent.dtStart.timestamp) {
            // Subtract 1ms to convert exclusive end to inclusive
            effectiveEnd.timestamp - 1
        } else {
            effectiveEnd.timestamp
        }

        // Convert alarms to reminder triggers (closest 5 by duration).
        // START-relative alarms keep their (signed) duration; absolute (DATE-TIME)
        // triggers are converted to a duration from DTSTART rather than dropped, so
        // an absolute "9 AM day of" all-day alarm round-trips like a relative one.
        // Sort by absolute duration so closest reminders appear first in UI.
        val alarmDurations = icalEvent.alarms
            .filter { !it.triggerRelatedToEnd }
            // RFC 9074 ACTION:NONE is a "no action" sentinel (Apple emits one with a
            // 1976 absolute trigger to suppress default alarms). Excluding it here keeps
            // it out of both reminders and alarmCount, so its absolute trigger never
            // becomes a phantom multi-day reminder offset.
            .filter { it.action != AlarmAction.NONE }
            .mapNotNull { alarm -> alarmToStartDuration(alarm, icalEvent.dtStart.timestamp) }

        val reminders = alarmDurations
            .sortedBy { it.abs() }
            .take(5)
            .map { formatTriggerDuration(it) }
            .takeIf { it.isNotEmpty() }

        // Total alarm count for optimization (when >5, use RawIcsParser)
        val alarmCount = alarmDurations.size

        // Flatten EXDATE/RDATE to a comma-separated epoch-ms string. Normalize a
        // value-type mismatch against the master's own DTSTART first: a DATE-form
        // EXDATE/RDATE on a TIMED master (RFC 5545 §3.8.5.1 says the value type MUST
        // match DTSTART, but peer clients break this and most servers preserve it)
        // would otherwise flatten to UTC midnight. In a negative-offset zone that
        // instant rolls back to the previous calendar day, so the wrong day is
        // excluded/added. Promoting it to the master's local time-of-day lands the
        // stored ms on the intended day. Same core helper as RECURRENCE-ID below.
        val masterDtStart = icalEvent.dtStart
        val exdate = icalEvent.exdates
            .map { normalizeToMasterValueType(it, masterDtStart).timestamp.toString() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")

        val rdate = icalEvent.rdates
            .map { normalizeToMasterValueType(it, masterDtStart).timestamp.toString() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")

        // Get timezone ID
        val timezone = icalEvent.dtStart.timezone?.id

        // RFC 5545 §3.8.2.2 permits DTEND TZID to differ from DTSTART TZID (e.g., flights).
        val endTimezone = icalEvent.dtEnd?.timezone?.id?.takeIf { it != timezone }

        // Get original instance time for exception events. Normalize
        // value-type mismatches against the master's DTSTART so a
        // date-form RECURRENCE-ID against a timed master lands at the
        // master's local time-of-day instead of midnight UTC. RFC 5545
        // §3.8.4.4: types MUST match, but real-world clients break this
        // and most CalDAV servers preserve the mismatch.
        val originalInstanceTime = normalizeRecurrenceId(
            recurrenceId = icalEvent.recurrenceId,
            masterDtStart = masterDtStart,
        )?.timestamp

        // Get importId for unique database lookup
        val importId = icalEvent.importId

        val event = Event(
            uid = icalEvent.uid,
            importId = importId,
            calendarId = calendarId,
            title = icalEvent.summary?.ifEmpty { null } ?: "Untitled",
            location = icalEvent.location,
            description = icalEvent.description,
            startTs = icalEvent.dtStart.timestamp,
            endTs = endTs,
            timezone = timezone,
            endTimezone = endTimezone,
            isAllDay = icalEvent.isAllDay,
            status = icalEvent.status.toICalString(),
            transp = icalEvent.transparency.toICalString(),
            classification = icalEvent.classification?.toICalString() ?: "PUBLIC",
            organizerEmail = icalEvent.organizer?.email,
            organizerName = icalEvent.organizer?.name,
            // RFC 6638 §7.3 ORGANIZER SCHEDULE-STATUS: the server-written
            // receipt for an attendee reply delivered back to the organizer.
            // First code only (multi-value is collapsed like the attendee
            // mapping at toRoomEntity). SENT-BY (RFC 5545 §3.2.18) rides along.
            organizerScheduleStatus = icalEvent.organizer?.scheduleStatus?.firstOrNull()?.code,
            organizerSentBy = icalEvent.organizer?.sentBy,
            rrule = icalEvent.rrule?.toICalString(),
            rdate = rdate,
            exdate = exdate,
            duration = icalEvent.duration?.let { DurationUtils.format(it) },
            originalEventId = null, // Set by caller after master lookup
            originalInstanceTime = originalInstanceTime,
            originalSyncId = null,
            reminders = reminders,
            alarmCount = alarmCount,
            extraProperties = icalEvent.rawProperties.takeIf { it.isNotEmpty() },
            rawIcal = rawIcal,
            // RFC 5545/7986 extended properties
            priority = icalEvent.priority,
            geoLat = parseGeoLat(icalEvent.geo),
            geoLon = parseGeoLon(icalEvent.geo),
            color = parseColorToArgb(icalEvent.color),
            url = icalEvent.url,
            categories = icalEvent.categories.takeIf { it.isNotEmpty() },
            dtstamp = icalEvent.dtstamp?.timestamp ?: now,
            caldavUrl = caldavUrl,
            etag = etag,
            sequence = icalEvent.sequence,
            syncStatus = SyncStatus.SYNCED,
            lastSyncError = null,
            syncRetryCount = 0,
            localModifiedAt = null,
            serverModifiedAt = icalEvent.lastModified?.timestamp ?: now,
            createdAt = icalEvent.created?.timestamp ?: now,
            updatedAt = now
        )

        // Translate icaldav-core attendee model → Room entity. eventId = 0L
        // here; caller copies in the real ID after upsert.
        return MappedEntity(event, toAttendeeRows(icalEvent, eventId = 0L))
    }

    /**
     * Translate the icaldav-core attendee list into Room rows for the
     * given [eventId]. Callers that already have a parsed [ICalEvent]
     * but only need the attendee rows (e.g. on-demand backfill from
     * `Event.rawIcal`) can use this without paying for the full
     * [toEntity] mapping.
     */
    fun toAttendeeRows(icalEvent: ICalEvent, eventId: Long): List<Attendee> =
        icalEvent.attendees.mapIndexed { index, a ->
            a.toRoomEntity(eventId = eventId, sortOrder = index)
        }

    /**
     * Translate icaldav-core's [org.onekash.icaldav.model.Attendee] into
     * the Room [Attendee]. Enums map to RFC string forms (TEXT-lenient
     * column accepts X-extensions); `email` becomes `address`; `member`
     * and delegation lists pass through; nullable fields map cleanly.
     *
     * Asymmetry: the primary `address` field re-prepends `mailto:` ONLY for a
     * mailbox-shaped value, because the icaldav-core parser strips that prefix
     * from `email`. A non-mailto CAL-ADDRESS (RFC 5545 §3.3.3 permits
     * `urn:uuid:` and principal hrefs) is stored verbatim — re-prepending
     * `mailto:` there would yield `mailto:urn:uuid:...` and break display,
     * matchesAttendee, and avatar canonicalization. This mirrors the
     * email-shape guard used when the picker builds an Attendee row. Multi-value
     * list columns (`member`, `delegatedFrom`, `delegatedTo`) and `sentBy`
     * stay bare — same convention as how those fields are stored
     * elsewhere in the codebase. T3's outbound emit re-prepends `mailto:`
     * for those when serializing back to the wire.
     *
     * Lossy mapping: `scheduleStatus: List<ScheduleStatus>?` → first code
     * only. RFC 5545 §3.8.8.3 permits multi-value SCHEDULE-STATUS but
     * KashCal collapses to single-TEXT until T4 surfaces a need for
     * delivery-status arrays.
     */
    private fun org.onekash.icaldav.model.Attendee.toRoomEntity(
        eventId: Long,
        sortOrder: Int
    ): Attendee = Attendee(
        eventId = eventId,
        address = if (org.onekash.kashcal.util.AddressNormalizer.isEmailShaped(email)) {
            "mailto:$email"
        } else {
            email
        },
        displayName = name,
        role = role.toICalString(),
        partstat = partStat.toICalString(),
        cutype = cutype.name,
        rsvp = rsvp,
        delegatedFrom = delegatedFrom,
        delegatedTo = delegatedTo,
        member = member,
        sentBy = sentBy,
        scheduleAgent = scheduleAgent?.name,
        scheduleStatus = scheduleStatus?.firstOrNull()?.code,
        scheduleForceSend = scheduleForceSend?.name,
        sortOrder = sortOrder
    )

    /**
     * Format Duration as RFC 5545 trigger string (e.g., "-PT15M", "-P1D").
     */
    private fun formatTriggerDuration(duration: Duration): String {
        return DurationUtils.format(duration)
    }

    /**
     * Resolve a START-relative alarm to its trigger duration relative to DTSTART.
     *
     * Relative triggers are returned verbatim (sign preserved). Absolute
     * (DATE-TIME) triggers are converted to `triggerInstant - dtStart` so they
     * become equivalent relative offsets instead of being dropped. Returns null
     * when the alarm carries neither form.
     */
    private fun alarmToStartDuration(alarm: ICalAlarm, dtStartMs: Long): Duration? {
        alarm.trigger?.let { return it }
        return alarm.triggerAbsolute?.let { Duration.ofMillis(it.timestamp - dtStartMs) }
    }

    /**
     * Check if this ICalEvent is an exception (modified occurrence).
     */
    fun isException(icalEvent: ICalEvent): Boolean {
        return icalEvent.recurrenceId != null
    }

    /**
     * Normalize an exception's RECURRENCE-ID against the master's DTSTART
     * value type. RFC 5545 §3.8.4.4 says they MUST match, but some clients
     * emit a mismatched form (e.g. `RECURRENCE-ID;VALUE=DATE` against a
     * timed master). Live multi-server tests show 7 of 10 CalDAV servers
     * preserve the mismatched form on PUT/GET round-trip, so KashCal's
     * pull path has to defend against it.
     *
     * Without normalization: a date-form RECURRENCE-ID lands at midnight
     * UTC, but the master's RRULE expansion at the same calendar day puts
     * the instance at the master's local time-of-day. The 60-second
     * `linkException` tolerance can't bridge the gap, leaving two
     * occurrence rows on the day card.
     *
     * Two cases:
     * - Master timed, RECURRENCE-ID date-form → promote DATE to master's
     *   time-of-day in master's timezone.
     * - Master all-day, RECURRENCE-ID date-time form → demote to DATE,
     *   keeping the calendar date.
     *
     * Pass-through when [recurrenceId] is null, when [masterDtStart] is
     * null (we can't normalize without it), or when value types already
     * match.
     */
    fun normalizeRecurrenceId(
        recurrenceId: ICalDateTime?,
        masterDtStart: ICalDateTime?,
    ): ICalDateTime? {
        if (recurrenceId == null) return null
        if (masterDtStart == null) return recurrenceId
        return normalizeToMasterValueType(recurrenceId, masterDtStart)
    }

    /**
     * Reconcile a recurrence-related date/time ([value]) against the value
     * type of the master's DTSTART, returning [value] unchanged when the
     * types already match. Shared by RECURRENCE-ID normalization (above) and
     * EXDATE/RDATE flattening (in [toEntity]) — both face the same RFC 5545
     * value-type mismatch (a DATE form emitted against a timed master, or a
     * date-time emitted against an all-day master) that peer clients produce
     * and most CalDAV servers preserve.
     *
     * Two cases:
     * - Master timed, [value] date-form → promote DATE to the master's
     *   time-of-day in the master's timezone. That's the instant the master's
     *   RRULE expansion produces for that calendar day; flattening the bare
     *   UTC-midnight form instead would roll back a day in negative-offset
     *   zones and exclude/add the wrong occurrence.
     * - Master all-day, [value] date-time form → demote to DATE, keeping the
     *   calendar date as observed in [value]'s own zone.
     */
    fun normalizeToMasterValueType(
        value: ICalDateTime,
        masterDtStart: ICalDateTime,
    ): ICalDateTime {
        if (value.isDate == masterDtStart.isDate) return value

        return if (masterDtStart.isDate) {
            // Master is all-day, value is date-time. Demote to DATE.
            // Keep the calendar date as observed in the value's own zone.
            val zone = value.timezone ?: ZoneId.systemDefault()
            val date = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(value.timestamp),
                zone,
            ).toLocalDate()
            ICalDateTime.fromLocalDate(date)
        } else {
            // Master is timed, value is date-form. Promote DATE to
            // master's time-of-day in master's timezone — that's the time
            // the master's RRULE expansion would have produced for this day.
            val masterZone = masterDtStart.timezone ?: ZoneOffset.UTC
            val masterLocalTime = ZonedDateTime
                .ofInstant(java.time.Instant.ofEpochMilli(masterDtStart.timestamp), masterZone)
                .toLocalTime()
            // LocalDate.ofInstant requires API 34. ZonedDateTime.ofInstant has been
            // available since API 26 and produces the same result via .toLocalDate().
            val valueDate = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(value.timestamp),
                ZoneOffset.UTC, // DATE values are stored as UTC midnight per ICalDateTime
            ).toLocalDate()
            val zoned = ZonedDateTime.of(valueDate, masterLocalTime, masterZone)
            ICalDateTime.fromZonedDateTime(zoned, isDate = false)
        }
    }

    /**
     * Get the importId for database lookup.
     * Format: "{uid}" or "{uid}:RECID:{datetime}" for exceptions.
     */
    fun getImportId(icalEvent: ICalEvent): String {
        return icalEvent.importId
    }

    // ========== RFC 5545/7986 Parsing Helpers ==========

    /**
     * Parse latitude from RFC 5545 GEO "latitude;longitude" format.
     *
     * @param geo GEO property value (e.g., "37.386013;-122.082932")
     * @return Latitude as Double, or null if invalid/missing
     */
    private fun parseGeoLat(geo: String?): Double? {
        if (geo.isNullOrBlank()) return null
        val parts = geo.split(";")
        return parts.getOrNull(0)?.toDoubleOrNull()
    }

    /**
     * Parse longitude from RFC 5545 GEO "latitude;longitude" format.
     *
     * @param geo GEO property value (e.g., "37.386013;-122.082932")
     * @return Longitude as Double, or null if invalid/missing
     */
    private fun parseGeoLon(geo: String?): Double? {
        if (geo.isNullOrBlank()) return null
        val parts = geo.split(";")
        return parts.getOrNull(1)?.toDoubleOrNull()
    }

    /**
     * Parse CSS color string to ARGB int.
     *
     * Supports: named colors ("red"), hex ("#FF0000", "#F00", "#AARRGGBB")
     * Returns null if parsing fails (e.g., unsupported rgb() notation).
     *
     * @param color CSS color string from RFC 7986 COLOR property
     * @return ARGB integer, or null if invalid/unsupported format
     */
    private fun parseColorToArgb(color: String?): Int? {
        if (color.isNullOrBlank()) return null
        // RFC 7986 §5.9: CSS3 extended color names (e.g., "mediumorchid") aren't supported
        // by Android's Color.parseColor — resolve them via EventColorPalette first.
        EventColorPalette.hexForName(color)?.let { return it }
        // Expand 3-digit CSS hex (#RGB → #RRGGBB) since Color.parseColor doesn't support it
        val expanded = if (color.length == 4 && color.startsWith("#")) {
            val r = color[1]; val g = color[2]; val b = color[3]
            "#$r$r$g$g$b$b"
        } else color
        return try {
            Color.parseColor(expanded)
        } catch (_: IllegalArgumentException) {
            null // Unsupported format, ignore
        }
    }
}
