package org.onekash.kashcal.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import org.onekash.icaldav.model.ICalCalendar
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.kashcal.data.db.entity.Event
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
 * Exports one event, with its exceptions, or a whole calendar to an ICS file.
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
     * Exports [event] to an ICS file. A recurring event's [exceptions] go into the same
     * VCALENDAR as its master, per RFC 5545.
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
     * Exports every master in [events] with its exceptions to one VCALENDAR file. Exceptions
     * share the master's UID and carry RECURRENCE-ID, per RFC 5545. An empty list fails.
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
     * Builds one VCALENDAR of every master and exception with `ICalGenerator.generate`, with a
     * VTIMEZONE for each distinct non-UTC timezone referenced across the bundle.
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
                prodId = null, // falls back to the generator's prodId
                xWrCalname = calendarName,
                events = icalEvents
            ),
            includeVTimezone = true
        )
    }

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
