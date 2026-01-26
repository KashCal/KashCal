package org.onekash.kashcal.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "IcsExporter"

/**
 * Utility for exporting events to ICS files.
 *
 * Uses FileProvider for secure sharing via content:// URIs.
 * Files are written to cache directory and cleaned up by system when needed.
 *
 * Supports:
 * - Single event export (with exceptions for recurring)
 * - Full calendar export (all events bundled)
 *
 * @see IcsPatcher for RFC 5545 serialization
 */
@Singleton
class IcsExporter @Inject constructor() {
    companion object {
        private const val AUTHORITY_SUFFIX = ".fileprovider"
        private const val SHARED_DIR = "shared"
        private const val MAX_FILENAME_LENGTH = 50

        // ICS file header/footer
        private const val ICS_HEADER = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//KashCal 2.0//EN
CALSCALE:GREGORIAN"""
        private const val ICS_FOOTER = "END:VCALENDAR"
    }

    /**
     * Export a single event to an ICS file.
     *
     * For recurring events with exceptions, all VEVENTs are bundled
     * into a single VCALENDAR per RFC 5545.
     *
     * @param context Application context for FileProvider
     * @param event Event to export
     * @param exceptions Exception events for recurring (empty for non-recurring)
     * @return Result containing content:// URI for sharing, or failure with exception
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
     * Creates a single VCALENDAR containing all events. Each event's
     * exceptions are included as additional VEVENTs with the same UID.
     *
     * @param context Application context for FileProvider
     * @param events List of (master event, exceptions) pairs
     * @param calendarName Calendar name for filename and X-WR-CALNAME
     * @return Result containing content:// URI for sharing, or failure with exception
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
     * Build a combined ICS file for multiple events.
     *
     * Each event is serialized individually, then VEVENTs are extracted
     * and combined into a single VCALENDAR.
     */
    private fun buildCalendarIcs(
        events: List<Pair<Event, List<Event>>>,
        calendarName: String
    ): String {
        return buildString {
            // Header
            appendLine(ICS_HEADER)
            appendLine("X-WR-CALNAME:${escapeIcsText(calendarName)}")

            // Collect all timezones used
            val timezones = mutableSetOf<String>()
            events.forEach { (master, exceptions) ->
                master.timezone?.let { timezones.add(it) }
                exceptions.forEach { ex -> ex.timezone?.let { timezones.add(it) } }
            }

            // Note: VTIMEZONE components would go here if needed
            // Most calendar apps resolve TZID references automatically

            // Add all VEVENTs
            events.forEach { (master, exceptions) ->
                val eventIcs = if (exceptions.isNotEmpty()) {
                    IcsPatcher.serializeWithExceptions(master, exceptions)
                } else {
                    IcsPatcher.serialize(master)
                }

                // Extract VEVENT blocks from individual serialization
                extractVEventBlocks(eventIcs).forEach { veventBlock ->
                    append(veventBlock)
                }
            }

            // Footer
            appendLine(ICS_FOOTER)
        }
    }

    /**
     * Extract all VEVENT blocks from an ICS string.
     *
     * Returns the raw VEVENT content including BEGIN/END tags.
     */
    private fun extractVEventBlocks(icsContent: String): List<String> {
        val blocks = mutableListOf<String>()
        var inEvent = false
        val currentBlock = StringBuilder()

        icsContent.lines().forEach { line ->
            when {
                line.trim() == "BEGIN:VEVENT" -> {
                    inEvent = true
                    currentBlock.clear()
                    currentBlock.appendLine(line)
                }
                line.trim() == "END:VEVENT" && inEvent -> {
                    currentBlock.appendLine(line)
                    blocks.add(currentBlock.toString())
                    inEvent = false
                }
                inEvent -> {
                    currentBlock.appendLine(line)
                }
            }
        }

        return blocks
    }

    /**
     * Generate a sanitized filename for the ICS export.
     *
     * Format: {sanitized-name}_{YYYYMMDD}.ics
     * - Removes special characters
     * - Replaces spaces with hyphens
     * - Limits length to MAX_FILENAME_LENGTH
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
     * Escape text for ICS property values.
     */
    private fun escapeIcsText(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\n", "\\n")
    }

    /**
     * Write ICS content to cache directory and return FileProvider URI.
     *
     * @param context Application context
     * @param fileName Filename (must end with .ics)
     * @param content ICS file content
     * @return content:// URI for sharing via FileProvider
     */
    private fun writeToCache(context: Context, fileName: String, content: String): Uri {
        // Create shared directory in cache if needed
        val cacheDir = File(context.cacheDir, SHARED_DIR)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        // Write file
        val file = File(cacheDir, fileName)
        file.writeText(content, Charsets.UTF_8)

        Log.d(TAG, "Wrote ${content.length} bytes to ${file.absolutePath}")

        // Generate FileProvider URI
        val authority = "${context.packageName}$AUTHORITY_SUFFIX"
        return FileProvider.getUriForFile(context, authority, file)
    }
}
