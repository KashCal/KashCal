package org.onekash.kashcal.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import org.onekash.icaldav.model.ICalCalendar
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.model.EventExport
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "IcsExporter"

/**
 * Exports one event, with its exceptions, or a whole calendar to an ICS file. A Room event's
 * guests come from its attendee rows ([EventExport]).
 *
 * Files go to the cache directory, which the system clears when it needs space, and are
 * shared as FileProvider content:// URIs.
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
     * Exports [event] to an ICS file, writing no attendee rows: a stored server copy keeps its
     * guests, and anything else gets none. A recurring event's [exceptions] go into the same
     * VCALENDAR as its master, per RFC 5545. A Room event's export takes an [EventExport] so its
     * guests come from its rows; a device event's uses [exportDeviceEvent], the share card
     * [exportShareCopy].
     */
    fun exportEvent(
        context: Context,
        event: Event,
        exceptions: List<Event> = emptyList()
    ): Result<Uri> = exportEvent(context, EventExport(event, exceptions, emptyMap()))

    /**
     * Exports a device event's rows ([org.onekash.kashcal.domain.mapper.toExportEvent]) as
     * [serializeDeviceEvent] writes them.
     */
    fun exportDeviceEvent(
        context: Context,
        event: Event,
        exceptions: List<Event> = emptyList()
    ): Result<Uri> = writeEventFile(context, event.title) { serializeDeviceEvent(event, exceptions) }

    /**
     * Exports the share card's single occurrence
     * ([org.onekash.kashcal.domain.share.singleOccurrenceForShare]) as [shareCopyText] writes it.
     */
    fun exportShareCopy(context: Context, event: Event, fromDevice: Boolean): Result<Uri> =
        writeEventFile(context, event.title) { shareCopyText(event, fromDevice) }

    /**
     * Returns the share card's file text: a device event's copy as [serializeDeviceEvent] writes
     * it, a Room event's as [exportEvent] does.
     */
    internal fun shareCopyText(event: Event, fromDevice: Boolean): String =
        if (fromDevice) serializeDeviceEvent(event, emptyList()) else IcsPatcher.serializeWithExceptions(event, null, emptyList())

    /**
     * Returns the file text for rows built from a device event, each zone written by
     * [EventToICalEventMapper.writeZone] as for a Room row. Such rows carry no stored
     * copy and no attendee rows, so this is the text [IcsPatcher.serializeWithExceptions] builds
     * from their fields: one VEVENT alone, or the series followed by its exceptions.
     */
    internal fun serializeDeviceEvent(master: Event, exceptions: List<Event>): String {
        val series = EventToICalEventMapper.toICalEvent(master, emptyList())
        if (exceptions.isEmpty()) return generator.generate(series, method = null, includeVTimezone = true)
        return generator.generate(
            ICalCalendar(
                prodId = null,
                events = listOf(series) + exceptions.map {
                    EventToICalEventMapper.toICalEvent(master, it, emptyList())
                },
            ),
            includeVTimezone = true,
        )
    }

    private fun writeEventFile(context: Context, title: String, text: () -> String): Result<Uri> = try {
        val uri = writeToCache(context, generateFileName(title), text())
        Log.i(TAG, "Exported event to: $uri")
        Result.success(uri)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to export event: $title", e)
        Result.failure(e)
    }

    /**
     * Exports [export]'s event to an ICS file, with its exceptions in the same VCALENDAR (RFC
     * 5545) and each VEVENT's guests from its attendee rows ([attendeesOf]).
     */
    fun exportEvent(context: Context, export: EventExport): Result<Uri> {
        val event = export.event
        return try {
            Log.d(TAG, "Exporting event: ${event.title} with ${export.exceptions.size} exceptions")
            val icsContent = IcsPatcher.serializeWithExceptions(
                master = event,
                masterAttendees = export.attendeesOf(event),
                exceptionsWithAttendees = export.exceptions.map { it to export.attendeesOf(it) }
            )
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
     * Exports every event in [events] with its exceptions and guests ([attendeesOf]) to one
     * VCALENDAR file. Exceptions share the master's UID and carry RECURRENCE-ID, per RFC 5545.
     * An empty list fails.
     */
    fun exportCalendar(
        context: Context,
        events: List<EventExport>,
        calendarName: String
    ): Result<Uri> {
        return try {
            Log.d(TAG, "Exporting calendar '$calendarName' with ${events.size} events")
            if (events.isEmpty()) {
                return Result.failure(IllegalArgumentException("No events to export"))
            }
            val icsContent = buildCalendarText(events, calendarName)
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
     * Returns the text [exportCalendar] writes: one VCALENDAR named [calendarName] of every master
     * and exception, each series written as [exportEvent] writes it ([IcsPatcher.toICalEvents]),
     * with a VTIMEZONE for each distinct non-UTC timezone referenced across the bundle. A series
     * whose stored copy can't be patched (an unparseable stored rule) is built from its fields
     * instead, so one row can't fail the whole export. Automatic backup writes the same text.
     */
    fun buildCalendarText(
        events: List<EventExport>,
        calendarName: String
    ): String {
        val icalEvents = events.flatMap { export ->
            val master = export.event
            try {
                IcsPatcher.toICalEvents(
                    master, export.attendeesOf(master), export.exceptions.map { it to export.attendeesOf(it) }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Exporting ${master.id} from its fields: ${e.javaClass.simpleName}")
                listOf(EventToICalEventMapper.toICalEvent(master, export.attendeesOf(master).orEmpty())) +
                    export.exceptions.map {
                        EventToICalEventMapper.toICalEvent(master, it, export.attendeesOf(it).orEmpty())
                    }
            }
        }
        return generator.generate(
            ICalCalendar(
                prodId = null, // falls back to the generator's prodId
                xWrCalname = calendarName,
                events = icalEvents
            ),
            includeVTimezone = true
        )
    }

    /**
     * Returns [row]'s attendee rows to write, or null when it has none, as the push does: an
     * empty table doesn't mean no guests for a row synced before the table existed, whose stored
     * copy keeps them, so null keeps that copy's ATTENDEE block ([IcsPatcher.serialize]). The
     * user's own row goes as stored, with the answer they gave.
     */
    private fun EventExport.attendeesOf(row: Event): List<Attendee>? =
        attendeesByEventId[row.id]?.takeIf { it.isNotEmpty() }

    /** Returns `{sanitized-name}_{YYYYMMDD}.ics`, dated today; see [sanitizeExportBaseName]. */
    private fun generateFileName(baseName: String): String {
        val sanitized = sanitizeExportBaseName(baseName, fallback = "event", maxLength = MAX_FILENAME_LENGTH)
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return "${sanitized}_${dateStr}.ics"
    }

    /** Writes [content] to the cache's shared directory and returns its FileProvider URI. */
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
