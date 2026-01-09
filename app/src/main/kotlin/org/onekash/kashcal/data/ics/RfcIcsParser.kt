package org.onekash.kashcal.data.ics

import android.util.Log
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.util.TimezoneUtils
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.db.entity.SyncStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.regex.Pattern

private const val TAG = "RfcIcsParser"

/**
 * RFC 5545 compliant ICS parser.
 *
 * Parses ICS/iCal feed content into Event entities.
 * Handles line folding, datetime parsing, timezones, and VEVENT extraction.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc5545">RFC 5545</a>
 */
object RfcIcsParser {

    /**
     * Parse ICS content into a list of events.
     *
     * @param content Raw ICS file content
     * @param calendarId Calendar ID to assign to parsed events
     * @param subscriptionId ICS subscription ID (for source tracking)
     * @return List of parsed events
     */
    fun parseIcsContent(
        content: String,
        calendarId: Long,
        subscriptionId: Long
    ): List<Event> {
        val events = mutableListOf<Event>()

        try {
            // Unfold line continuations per RFC 5545
            val unfolded = unfoldICalData(content)

            // Extract all VEVENT blocks
            val veventBlocks = extractAllVeventBlocks(unfolded)
            if (veventBlocks.isEmpty()) {
                Log.w(TAG, "No VEVENT blocks found in ICS content")
                return events
            }

            Log.d(TAG, "Found ${veventBlocks.size} VEVENT blocks")

            for (vevent in veventBlocks) {
                try {
                    val event = parseVEvent(vevent, calendarId, subscriptionId)
                    if (event != null) {
                        events.add(event)
                    }
                } catch (e: Exception) {
                    val summary = extractProperty(vevent, "SUMMARY") ?: "Unknown"
                    Log.e(TAG, "Error parsing event: $summary", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ICS content", e)
        }

        Log.d(TAG, "Parsed ${events.size} events from ICS")
        return events
    }

    /**
     * Get the calendar name from ICS content.
     * Looks for X-WR-CALNAME or PRODID properties.
     */
    fun getCalendarName(content: String): String? {
        return try {
            val unfolded = unfoldICalData(content)
            // Try X-WR-CALNAME first (common extension)
            extractProperty(unfolded, "X-WR-CALNAME")
                ?: extractProperty(unfolded, "PRODID")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting calendar name", e)
            null
        }
    }

    /**
     * Validate ICS content structure.
     * Returns true if content appears to be valid ICS format.
     */
    fun isValidIcs(content: String): Boolean {
        return content.contains("BEGIN:VCALENDAR") &&
                content.contains("END:VCALENDAR") &&
                (content.contains("BEGIN:VEVENT") || content.contains("BEGIN:VTODO"))
    }

    /**
     * Parse a single VEVENT block into an Event object.
     */
    private fun parseVEvent(
        veventData: String,
        calendarId: Long,
        subscriptionId: Long
    ): Event? {
        val summary = extractProperty(veventData, "SUMMARY") ?: return null
        val uid = extractProperty(veventData, "UID") ?: UUID.randomUUID().toString()

        // Skip CANCELLED events
        val status = extractProperty(veventData, "STATUS")
        if (status == "CANCELLED") {
            Log.d(TAG, "Skipping cancelled event: $summary")
            return null
        }

        // Parse DTSTART
        val dtstart = extractProperty(veventData, "DTSTART") ?: return null
        val startTzid = extractTzid(veventData, "DTSTART")
        val startTs = parseICalDateTime(dtstart, startTzid)

        // Check if all-day event
        val isAllDay = dtstart.length == 8 || !dtstart.contains("T")

        // Parse DTEND or DURATION
        val dtend = extractProperty(veventData, "DTEND")
        val endTzid = extractTzid(veventData, "DTEND")
        val duration = extractProperty(veventData, "DURATION")
        val endTs = when {
            dtend != null -> {
                val parsedEnd = parseICalDateTime(dtend, endTzid ?: startTzid)
                // RFC 5545: For all-day events, DTEND is exclusive (the day after)
                // Subtract 1 second (1000ms) so endTS falls on the actual last day
                if (isAllDay) parsedEnd - 1000 else parsedEnd
            }
            duration != null -> startTs + parseDuration(duration)
            else -> startTs + 3600000 // Default to 1 hour (in milliseconds)
        }

        // Parse other properties
        val location = unescapeICalText(extractProperty(veventData, "LOCATION") ?: "")
        val description = unescapeICalText(extractProperty(veventData, "DESCRIPTION") ?: "")

        // Parse RRULE
        val rrule = extractProperty(veventData, "RRULE")

        // Parse EXDATE
        val exdate = extractProperty(veventData, "EXDATE")

        // Parse TRANSP (time transparency)
        val transp = extractProperty(veventData, "TRANSP") ?: "OPAQUE"

        // Parse CLASS (access classification)
        val classification = extractProperty(veventData, "CLASS") ?: "PUBLIC"

        // Parse SEQUENCE
        val sequence = extractProperty(veventData, "SEQUENCE")?.toIntOrNull() ?: 0

        // Parse VALARM for reminders
        val reminders = parseValarms(veventData)

        // Parse DTSTAMP
        val dtstampStr = extractProperty(veventData, "DTSTAMP")
        val dtstamp = if (dtstampStr != null) {
            parseICalDateTime(dtstampStr, null)
        } else {
            System.currentTimeMillis()
        }

        // Determine timezone and canonicalize to standard IANA form
        val rawTimezone = startTzid ?: if (dtstart.endsWith("Z")) "UTC" else null
        val timezone = TimezoneUtils.canonicalizeTimezone(rawTimezone)

        // Create unique source identifier for this subscription's event
        val source = "${IcsSubscription.SOURCE_PREFIX}:${subscriptionId}:${uid}"

        return Event(
            uid = uid,
            calendarId = calendarId,
            title = unescapeICalText(summary),
            location = location.takeIf { it.isNotBlank() },
            description = description.takeIf { it.isNotBlank() },
            startTs = startTs,
            endTs = endTs,
            timezone = timezone,
            isAllDay = isAllDay,
            status = status?.uppercase() ?: "CONFIRMED",
            transp = transp,
            classification = classification,
            rrule = rrule,
            exdate = exdate,
            reminders = reminders.takeIf { it.isNotEmpty() },
            dtstamp = dtstamp,
            sequence = sequence,
            syncStatus = SyncStatus.SYNCED, // ICS subscriptions are read-only
            caldavUrl = source // Use caldavUrl to store source for later identification
        )
    }

    /**
     * Unfold iCal line continuations per RFC 5545.
     * Lines >75 chars are folded with CRLF + SPACE or TAB.
     */
    private fun unfoldICalData(icalData: String): String {
        return icalData
            .replace("\r\n ", "")   // CRLF + SPACE (RFC 5545 standard)
            .replace("\r\n\t", "")  // CRLF + TAB (RFC 5545 standard)
            .replace("\n ", "")     // LF + SPACE (common variant)
            .replace("\n\t", "")    // LF + TAB (common variant)
    }

    /**
     * Extract all VEVENT blocks from iCal data.
     */
    private fun extractAllVeventBlocks(icalData: String): List<String> {
        val blocks = mutableListOf<String>()
        val startMarker = "BEGIN:VEVENT"
        val endMarker = "END:VEVENT"

        var searchStart = 0
        while (true) {
            val startIndex = icalData.indexOf(startMarker, searchStart)
            if (startIndex == -1) break

            val endIndex = icalData.indexOf(endMarker, startIndex)
            if (endIndex == -1) break

            blocks.add(icalData.substring(startIndex, endIndex + endMarker.length))
            searchStart = endIndex + endMarker.length
        }
        return blocks
    }

    /**
     * Extract a property value from iCal data.
     * Handles properties with parameters (e.g., DTSTART;VALUE=DATE:20231225)
     */
    private fun extractProperty(icalData: String, property: String): String? {
        // Use MULTILINE flag so ^ matches start of each line
        val pattern = Pattern.compile("^$property[^:]*:([^\r\n]+)", Pattern.MULTILINE)
        val matcher = pattern.matcher(icalData)
        return if (matcher.find()) matcher.group(1)?.trim() else null
    }

    /**
     * Extract TZID from a datetime property line.
     * Example: DTSTART;TZID=America/New_York:20231215T140000 → "America/New_York"
     */
    private fun extractTzid(icalData: String, property: String): String? {
        val pattern = Pattern.compile("$property;TZID=([^:;]+)")
        val matcher = pattern.matcher(icalData)
        return if (matcher.find()) matcher.group(1)?.trim() else null
    }

    /**
     * Parse iCal datetime formats into Unix timestamp (milliseconds).
     * Handles: UTC (Z suffix), local with TZID, DATE-only (all-day)
     *
     * Uses java.time APIs per Android recommendation (preferred over java.util.Date).
     */
    private fun parseICalDateTime(value: String, tzid: String? = null): Long {
        return try {
            val isUtc = value.endsWith("Z")
            val cleanValue = value.replace("Z", "").replace("T", "")

            val year = cleanValue.substring(0, 4).toInt()
            val month = cleanValue.substring(4, 6).toInt()
            val day = cleanValue.substring(6, 8).toInt()

            val isAllDay = cleanValue.length == 8

            // RFC 5545: VALUE=DATE (all-day) represents a calendar date, not a moment in time.
            // Store as midnight UTC to avoid timezone shifts (e.g., Dec 25 staying Dec 25).
            if (isAllDay) {
                // Use LocalDate for all-day (no time component) per Android recommendation
                val localDate = LocalDate.of(year, month, day)
                return localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }

            // Timed event: parse time components
            val hour = if (cleanValue.length >= 10) cleanValue.substring(8, 10).toInt() else 0
            val minute = if (cleanValue.length >= 12) cleanValue.substring(10, 12).toInt() else 0
            val second = if (cleanValue.length >= 14) cleanValue.substring(12, 14).toInt() else 0

            val localDateTime = LocalDateTime.of(year, month, day, hour, minute, second)

            // Determine timezone
            val zoneId = when {
                isUtc -> ZoneOffset.UTC
                tzid != null -> {
                    try {
                        ZoneId.of(tzid)
                    } catch (e: Exception) {
                        // Unknown timezone, fall back to system default
                        ZoneId.systemDefault()
                    }
                }
                else -> ZoneId.systemDefault()
            }

            localDateTime.atZone(zoneId).toInstant().toEpochMilli()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse datetime '$value' with tzid=$tzid: ${e.message}")
            System.currentTimeMillis()
        }
    }

    /**
     * Parse ISO 8601 duration format (PT1H, PT30M, P1D, P1W, etc.)
     * Returns duration in milliseconds.
     */
    private fun parseDuration(duration: String): Long {
        val DEFAULT_DURATION = 3600000L // 1 hour in ms
        var millis = 0L
        val matcher = Pattern.compile("P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?")
            .matcher(duration)
        if (matcher.matches()) {
            val weeks = matcher.group(1)?.toLongOrNull() ?: 0
            val days = matcher.group(2)?.toLongOrNull() ?: 0
            val hours = matcher.group(3)?.toLongOrNull() ?: 0
            val minutes = matcher.group(4)?.toLongOrNull() ?: 0
            val secs = matcher.group(5)?.toLongOrNull() ?: 0
            millis = (weeks * 7 * 24 * 3600 + days * 24 * 3600 + hours * 3600 + minutes * 60 + secs) * 1000
        }
        if (millis == 0L) {
            millis = DEFAULT_DURATION
        }
        return millis.coerceIn(0L, 31536000000L) // Max 1 year
    }

    /**
     * Unescape iCal text values.
     */
    private fun unescapeICalText(text: String): String {
        val placeholder = "\u0000BACKSLASH\u0000"
        return text
            .replace("\\\\", placeholder)
            .replace("\\n", "\n")
            .replace("\\N", "\n")
            .replace("\\,", ",")
            .replace("\\;", ";")
            .replace(placeholder, "\\")
    }

    /**
     * Parse VALARM blocks for reminders.
     * Returns list of reminder triggers as ISO duration strings.
     */
    private fun parseValarms(icalData: String): List<String> {
        val reminders = mutableListOf<String>()

        val valarmPattern = Regex("BEGIN:VALARM.*?END:VALARM", RegexOption.DOT_MATCHES_ALL)
        valarmPattern.findAll(icalData).forEach { match ->
            val valarm = match.value
            // Look for TRIGGER property
            val triggerPattern = Regex("TRIGGER[^:]*:(-?P[^\r\n]+)")
            val triggerMatch = triggerPattern.find(valarm)
            if (triggerMatch != null) {
                val trigger = triggerMatch.groupValues[1].trim()
                if (trigger.isNotBlank()) {
                    reminders.add(trigger)
                }
            }
        }

        return reminders.take(2) // Only support 2 reminders
    }
}