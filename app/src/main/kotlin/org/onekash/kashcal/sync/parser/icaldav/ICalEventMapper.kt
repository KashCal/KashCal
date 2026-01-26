package org.onekash.kashcal.sync.parser.icaldav

import android.graphics.Color
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.util.DurationUtils
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
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
object ICalEventMapper {

    /**
     * Convert icaldav ICalEvent to KashCal Event entity.
     *
     * @param icalEvent The parsed icaldav event
     * @param rawIcal The original ICS data for round-trip preservation
     * @param calendarId The target calendar ID
     * @param caldavUrl The CalDAV URL for this event resource
     * @param etag The HTTP ETag from server
     * @return KashCal Event entity ready for database insertion
     */
    fun toEntity(
        icalEvent: ICalEvent,
        rawIcal: String?,
        calendarId: Long,
        caldavUrl: String?,
        etag: String?
    ): Event {
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

        // Convert alarms to reminder strings (first 3 for compatibility)
        val reminders = icalEvent.alarms
            .filter { it.trigger != null && !it.triggerRelatedToEnd }
            .take(3)
            .mapNotNull { alarm ->
                alarm.trigger?.let { formatTriggerDuration(it) }
            }
            .takeIf { it.isNotEmpty() }

        // Total alarm count for optimization (when >3, use RawIcsParser)
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

        // Get original instance time for exception events
        val originalInstanceTime = icalEvent.recurrenceId?.timestamp

        // Get importId for unique database lookup
        val importId = icalEvent.importId

        return Event(
            uid = icalEvent.uid,
            importId = importId,
            calendarId = calendarId,
            title = icalEvent.summary ?: "Untitled",
            location = icalEvent.location,
            description = icalEvent.description,
            startTs = icalEvent.dtStart.timestamp,
            endTs = endTs,
            timezone = timezone,
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
            serverModifiedAt = now,
            createdAt = now,
            updatedAt = now
        )
    }

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
        return try {
            Color.parseColor(color)
        } catch (e: IllegalArgumentException) {
            null // Unsupported format, ignore
        }
    }
}
