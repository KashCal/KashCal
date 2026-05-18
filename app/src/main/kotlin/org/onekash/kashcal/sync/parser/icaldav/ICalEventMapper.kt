package org.onekash.kashcal.sync.parser.icaldav

import android.graphics.Color
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.util.DurationUtils
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.ui.shared.EventColorPalette
import java.time.Duration

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
     */
    fun toEntity(
        icalEvent: ICalEvent,
        rawIcal: String?,
        calendarId: Long,
        caldavUrl: String?,
        etag: String?
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

        // Convert alarms to reminder strings (closest 5 by duration)
        // Sort by absolute duration so closest reminders appear first in UI
        val reminders = icalEvent.alarms
            .filter { it.trigger != null && !it.triggerRelatedToEnd }
            .sortedBy { it.trigger?.abs() }
            .take(5)
            .mapNotNull { alarm ->
                alarm.trigger?.let { formatTriggerDuration(it) }
            }
            .takeIf { it.isNotEmpty() }

        // Total alarm count for optimization (when >5, use RawIcsParser)
        val alarmCount = icalEvent.alarms.count { it.trigger != null && !it.triggerRelatedToEnd }

        // Parse EXDATE timestamps to comma-separated string
        val exdate = icalEvent.exdates
            .map { it.timestamp.toString() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")

        // Parse RDATE timestamps to comma-separated string
        val rdate = icalEvent.rdates
            .map { it.timestamp.toString() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")

        // Get timezone ID
        val timezone = icalEvent.dtStart.timezone?.id

        // RFC 5545 §3.8.2.2 permits DTEND TZID to differ from DTSTART TZID (e.g., flights).
        val endTimezone = icalEvent.dtEnd?.timezone?.id?.takeIf { it != timezone }

        // Get original instance time for exception events
        val originalInstanceTime = icalEvent.recurrenceId?.timestamp

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
     * Asymmetry: the primary `address` field re-prepends `mailto:` because
     * the icaldav-core parser strips that prefix from `email`. Multi-value
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
        address = "mailto:$email",
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
     * Check if this ICalEvent is an exception (modified occurrence).
     */
    fun isException(icalEvent: ICalEvent): Boolean {
        return icalEvent.recurrenceId != null
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
