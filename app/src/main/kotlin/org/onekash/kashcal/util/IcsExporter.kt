package org.onekash.kashcal.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import org.onekash.icaldav.model.Classification
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalCalendar
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.model.Transparency
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "IcsExporter"

private val deviceExportGenerator = ICalGenerator(
    prodId = "-//KashCal//Device Export//EN",
    includeAppleExtensions = false
)

/**
 * Build ICS content string for a device calendar event.
 *
 * Honors the device event's stored timezone by emitting `DTSTART;TZID=...` +
 * VTIMEZONE block. Falls back to floating time when the timezone string is
 * not a valid IANA zone ID (e.g., Windows TZID surfaced by older Android
 * devices).
 */
fun buildIcsFromDeviceEvent(event: DisplayEvent.Device): String {
    val instance = event.instance
    val zone = EventToICalEventMapper.resolveZone(instance.timezone)
    val dtStart = ICalDateTime.fromTimestamp(event.startTs, zone, event.isAllDay)
    // All-day inclusive -> exclusive per RFC 5545.
    val endTs = if (event.isAllDay) event.endTs + 1 else event.endTs
    val dtEnd = ICalDateTime.fromTimestamp(endTs, zone, event.isAllDay)

    val icalEvent = ICalEvent(
        uid = "device-${instance.eventId}@kashcal",
        importId = "device-${instance.eventId}",
        summary = event.title,
        description = event.description.ifBlank { null },
        location = event.location.ifBlank { null },
        dtStart = dtStart,
        dtEnd = dtEnd,
        duration = null,
        isAllDay = event.isAllDay,
        status = EventStatus.CONFIRMED,
        sequence = 0,
        rrule = EventToICalEventMapper.parseRruleOrNull(event.rrule),
        exdates = emptyList(),
        recurrenceId = null,
        alarms = emptyList(),
        categories = emptyList(),
        organizer = null,
        attendees = emptyList(),
        color = null,
        dtstamp = null,
        lastModified = null,
        created = null,
        transparency = Transparency.OPAQUE,
        url = null,
        classification = Classification.PUBLIC,
        rawProperties = emptyMap()
    )

    return deviceExportGenerator.generate(
        ICalCalendar(prodId = null, events = listOf(icalEvent)),
        includeVTimezone = true
    )
}

/**
 * Utility for exporting events to ICS files.
 *
 * Uses FileProvider for secure sharing via content:// URIs.
 * Files are written to cache directory and cleaned up by system when needed.
 *
 * Supports:
 * - Single event export (with exceptions for recurring)
 * - Full calendar export (all events bundled)
 */
@Singleton
class IcsExporter @Inject constructor() {
    companion object {
        private const val AUTHORITY_SUFFIX = ".fileprovider"
        private const val SHARED_DIR = "shared"
        private const val MAX_FILENAME_LENGTH = 50
    }

    private val generator = ICalGenerator(
        prodId = "-//KashCal//KashCal 2.0//EN",
        includeAppleExtensions = true
    )

    /**
     * Export a single event to an ICS file.
     *
     * For recurring events with exceptions, all VEVENTs are bundled
     * into a single VCALENDAR per RFC 5545.
     */
    fun exportEvent(
        context: Context,
        event: Event,
        exceptions: List<Event> = emptyList()
    ): Result<Uri> {
        return try {
            Log.d(TAG, "Exporting event: ${event.title} with ${exceptions.size} exceptions")
            val icsContent = if (exceptions.isNotEmpty()) {
                IcsPatcher.serializeWithExceptions(event, exceptions)
            } else {
                IcsPatcher.serialize(event)
            }
            val fileName = generateFileName(event.title)
            val uri = writeToCache(context, fileName, icsContent)
            Log.i(TAG, "Exported event to: $uri")
            Result.success(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export event: ${event.title}", e)
            Result.failure(e)
        }
    }

    /**
     * Export multiple events to a single ICS file.
     *
     * Creates a single VCALENDAR containing all master events and their
     * exceptions. Exceptions share the master's UID and carry RECURRENCE-ID
     * per RFC 5545.
     */
    fun exportCalendar(
        context: Context,
        events: List<Pair<Event, List<Event>>>,
        calendarName: String
    ): Result<Uri> {
        return try {
            Log.d(TAG, "Exporting calendar '$calendarName' with ${events.size} events")
            if (events.isEmpty()) {
                return Result.failure(IllegalArgumentException("No events to export"))
            }
            val icsContent = buildCalendarIcs(events, calendarName)
            val fileName = generateFileName(calendarName)
            val uri = writeToCache(context, fileName, icsContent)
            Log.i(TAG, "Exported ${events.size} events to: $uri")
            Result.success(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export calendar: $calendarName", e)
            Result.failure(e)
        }
    }

    /**
     * Build a single VCALENDAR containing all master events and their exceptions
     * via `ICalGenerator.generate(ICalCalendar)`. VTIMEZONE blocks are emitted
     * for every distinct non-UTC timezone referenced across the bundle.
     */
    private fun buildCalendarIcs(
        events: List<Pair<Event, List<Event>>>,
        calendarName: String
    ): String {
        val icalEvents = events.flatMap { (master, exceptions) ->
            listOf(EventToICalEventMapper.toICalEvent(master)) +
                exceptions.map { EventToICalEventMapper.toICalEvent(master, it) }
        }
        return generator.generate(
            ICalCalendar(
                prodId = null, // falls back to instance prodId
                xWrCalname = calendarName,
                events = icalEvents
            ),
            includeVTimezone = true
        )
    }

    /**
     * Generate a sanitized filename for the ICS export.
     *
     * Format: {sanitized-name}_{YYYYMMDD}.ics
     */
    private fun generateFileName(baseName: String): String {
        val sanitized = baseName
            .replace(Regex("[^a-zA-Z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')
            .take(MAX_FILENAME_LENGTH)
            .ifEmpty { "event" }

        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return "${sanitized}_${dateStr}.ics"
    }

    /**
     * Write ICS content to cache directory and return FileProvider URI.
     */
    private fun writeToCache(context: Context, fileName: String, content: String): Uri {
        val cacheDir = File(context.cacheDir, SHARED_DIR)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val file = File(cacheDir, fileName)
        file.writeText(content, Charsets.UTF_8)
        Log.d(TAG, "Wrote ${content.length} bytes to ${file.absolutePath}")
        val authority = "${context.packageName}$AUTHORITY_SUFFIX"
        return FileProvider.getUriForFile(context, authority, file)
    }
}
