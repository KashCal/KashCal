package org.onekash.kashcal.sync.parser.icaldav

import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.model.Classification
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.Organizer
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.model.Transparency
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.icaldav.parser.ICalParser
import org.onekash.icaldav.util.DurationUtils
import org.onekash.kashcal.data.db.entity.Event
import java.time.ZoneId

/**
 * Patches existing ICS data with updated Event fields, or generates fresh ICS.
 *
 * Key design principle: When patching, preserve everything from the original
 * that wasn't explicitly changed. This ensures:
 * - Alarms beyond the first 3 are preserved
 * - Attendees are preserved (KashCal doesn't edit these)
 * - Unknown X-* properties are preserved
 * - Server-specific extensions are preserved
 *
 * When generating fresh ICS (no rawIcal), only KashCal's known fields are written.
 */
object IcsPatcher {

    private val parser = ICalParser()
    private val generator = ICalGenerator(
        prodId = "-//KashCal//KashCal 2.0//EN",
        includeAppleExtensions = true
    )

    /**
     * Patch existing ICS data with Event changes.
     *
     * If rawIcal is null or parsing fails, generates fresh ICS.
     *
     * @param rawIcal Original ICS data from server
     * @param event Event with updated values
     * @return Patched or fresh ICS string
     */
    fun patch(rawIcal: String?, event: Event): String {
        if (rawIcal == null) {
            return generateFresh(event)
        }

        val original = parser.parseAllEvents(rawIcal)
            .getOrNull()
            ?.firstOrNull()
            ?: return generateFresh(event)

        val zone = event.timezone?.let {
            try { ZoneId.of(it) } catch (e: Exception) { null }
        }

        // Merge user's reminder edits with original alarms
        val mergedAlarms = mergeAlarms(original.alarms, event.reminders)

        // Build updated event, preserving original's attendees/rawProperties
        val updated = original.copy(
            uid = event.uid,
            summary = event.title,
            description = event.description,
            location = event.location,
            dtStart = ICalDateTime.fromTimestamp(event.startTs, zone, event.isAllDay),
            dtEnd = ICalDateTime.fromTimestamp(event.endTs, zone, event.isAllDay),
            isAllDay = event.isAllDay,
            status = EventStatus.fromString(event.status),
            transparency = Transparency.fromString(event.transp),
            classification = Classification.fromString(event.classification),
            sequence = event.sequence + 1,
            rrule = event.rrule?.let { RRule.parse(it) },
            exdates = parseExdates(event.exdate, zone, event.isAllDay),
            alarms = mergedAlarms,
            // RFC 5545/7986 extended properties
            priority = event.priority,
            geo = formatGeo(event.geoLat, event.geoLon),
            color = event.color?.let { formatColorFromArgb(it) },
            url = event.url,
            categories = event.categories ?: emptyList()
            // The following are PRESERVED from original:
            // attendees, organizer, rawProperties, rdates
        )

        return generator.generate(updated, method = null, includeVTimezone = true)
    }

    /**
     * Generate fresh ICS for an event with no existing rawIcal.
     *
     * This is used for:
     * - Events created locally
     * - Events where original ICS was lost
     *
     * @param event Event to serialize
     * @return Complete ICS string
     */
    fun generateFresh(event: Event): String {
        val zone = event.timezone?.let {
            try { ZoneId.of(it) } catch (e: Exception) { null }
        }

        // Convert reminder strings to ICalAlarms
        val alarms = event.reminders?.mapNotNull { reminderStr ->
            try {
                val duration = DurationUtils.parse(reminderStr)
                if (duration != null) {
                    ICalAlarm(
                        action = AlarmAction.DISPLAY,
                        trigger = duration,
                        triggerAbsolute = null,
                        description = "Reminder",
                        summary = null
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()

        // Create organizer if email is present
        val organizer = event.organizerEmail?.let { email ->
            Organizer(
                email = email,
                name = event.organizerName,
                sentBy = null
            )
        }

        val icalEvent = ICalEvent(
            uid = event.uid,
            importId = event.importId ?: event.uid, // Use importId if available
            summary = event.title,
            description = event.description,
            location = event.location,
            dtStart = ICalDateTime.fromTimestamp(event.startTs, zone, event.isAllDay),
            dtEnd = ICalDateTime.fromTimestamp(event.endTs, zone, event.isAllDay),
            duration = null, // We use dtEnd, not duration
            isAllDay = event.isAllDay,
            status = EventStatus.fromString(event.status),
            sequence = event.sequence,
            rrule = event.rrule?.let { RRule.parse(it) },
            exdates = parseExdates(event.exdate, zone, event.isAllDay),
            rdates = emptyList(),
            classification = Classification.fromString(event.classification),
            recurrenceId = null,
            alarms = alarms,
            categories = event.categories ?: emptyList(),
            organizer = organizer,
            attendees = emptyList(),
            color = event.color?.let { formatColorFromArgb(it) },
            dtstamp = ICalDateTime.fromTimestamp(event.dtstamp, null, false),
            lastModified = null,
            created = null,
            transparency = Transparency.fromString(event.transp),
            url = event.url,
            priority = event.priority,
            geo = formatGeo(event.geoLat, event.geoLon),
            rawProperties = event.extraProperties ?: emptyMap()
        )

        return generator.generate(icalEvent, method = null, includeVTimezone = true)
    }

    /**
     * Serialize an event to ICS format.
     *
     * If the event has rawIcal, patches it. Otherwise generates fresh.
     * This is the main entry point for serialization.
     *
     * @param event Event to serialize
     * @return Complete ICS string
     */
    fun serialize(event: Event): String {
        return patch(event.rawIcal, event)
    }

    /**
     * Serialize a master recurring event with its exceptions.
     *
     * RFC 5545 requires exception events to be bundled with the master
     * in the same VCALENDAR, sharing the same UID but with RECURRENCE-ID.
     *
     * @param master The master recurring event
     * @param exceptions Exception events (modified occurrences)
     * @return Complete ICS string containing master and all exceptions
     */
    fun serializeWithExceptions(master: Event, exceptions: List<Event>): String {
        if (exceptions.isEmpty()) {
            return serialize(master)
        }

        // Generate master ICS - we'll extract just the VEVENT portion
        val masterIcs = serialize(master)

        // Build combined VCALENDAR
        return buildString {
            // Extract and write header
            val headerEnd = masterIcs.indexOf("BEGIN:VEVENT")
            if (headerEnd > 0) {
                append(masterIcs.substring(0, headerEnd))
            } else {
                // Fallback header if extraction fails
                appendLine("BEGIN:VCALENDAR")
                appendLine("VERSION:2.0")
                appendLine("PRODID:-//KashCal//KashCal 2.0//EN")
                appendLine("CALSCALE:GREGORIAN")
            }

            // Write master VEVENT
            val masterVevent = extractVevent(masterIcs)
            if (masterVevent != null) {
                append(masterVevent)
            }

            // Write exception VEVENTs
            for (exception in exceptions) {
                val exceptionIcs = generateException(master, exception)
                val exceptionVevent = extractVevent(exceptionIcs)
                if (exceptionVevent != null) {
                    append(exceptionVevent)
                }
            }

            // Footer
            appendLine("END:VCALENDAR")
        }
    }

    /**
     * Generate ICS for an exception event.
     *
     * Exception events have:
     * - Same UID as master
     * - RECURRENCE-ID indicating which occurrence is modified
     * - Their own DTSTART/DTEND (the new times)
     */
    private fun generateException(master: Event, exception: Event): String {
        val zone = exception.timezone?.let {
            try { ZoneId.of(it) } catch (e: Exception) { null }
        }

        // Convert reminder strings to ICalAlarms
        val alarms = exception.reminders?.mapNotNull { reminderStr ->
            try {
                val duration = DurationUtils.parse(reminderStr)
                if (duration != null) {
                    ICalAlarm(
                        action = AlarmAction.DISPLAY,
                        trigger = duration,
                        triggerAbsolute = null,
                        description = "Reminder",
                        summary = null
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()

        // Build RECURRENCE-ID from originalInstanceTime
        val recurrenceId = exception.originalInstanceTime?.let { instanceTime ->
            ICalDateTime.fromTimestamp(instanceTime, zone, exception.isAllDay)
        }

        val icalEvent = ICalEvent(
            uid = master.uid, // Same UID as master (RFC 5545 requirement)
            importId = exception.importId ?: "${master.uid}:RECID:${exception.originalInstanceTime}",
            summary = exception.title,
            description = exception.description,
            location = exception.location,
            dtStart = ICalDateTime.fromTimestamp(exception.startTs, zone, exception.isAllDay),
            dtEnd = ICalDateTime.fromTimestamp(exception.endTs, zone, exception.isAllDay),
            duration = null,
            isAllDay = exception.isAllDay,
            status = EventStatus.fromString(exception.status),
            sequence = exception.sequence,
            rrule = null, // Exceptions don't have RRULE
            exdates = emptyList(),
            rdates = emptyList(),
            classification = Classification.fromString(exception.classification),
            recurrenceId = recurrenceId,
            alarms = alarms,
            categories = exception.categories ?: emptyList(),
            organizer = exception.organizerEmail?.let { Organizer(it, exception.organizerName, null) },
            attendees = emptyList(),
            color = exception.color?.let { formatColorFromArgb(it) },
            dtstamp = ICalDateTime.fromTimestamp(exception.dtstamp, null, false),
            lastModified = null,
            created = null,
            transparency = Transparency.fromString(exception.transp),
            url = exception.url,
            priority = exception.priority,
            geo = formatGeo(exception.geoLat, exception.geoLon),
            rawProperties = exception.extraProperties ?: emptyMap()
        )

        return generator.generate(icalEvent, method = null, includeVTimezone = false)
    }

    /**
     * Extract the VEVENT block from an ICS string.
     *
     * @param ics Complete ICS string
     * @return Just the VEVENT block (including BEGIN/END tags), or null if not found
     */
    private fun extractVevent(ics: String): String? {
        val startIdx = ics.indexOf("BEGIN:VEVENT")
        val endIdx = ics.indexOf("END:VEVENT")
        if (startIdx < 0 || endIdx < 0) return null

        // Include END:VEVENT and newline
        return ics.substring(startIdx, endIdx + "END:VEVENT".length) + "\n"
    }

    /**
     * Merge user's reminder edits with original alarms from rawIcal.
     *
     * Strategy:
     * - null/empty reminders = user wants no alarms → clear all
     * - User's reminders update first N alarm triggers
     * - Original alarm properties (action, description, uid, etc.) are PRESERVED
     * - Absolute triggers are passed through unchanged
     * - Alarms beyond user's count are preserved unchanged
     *
     * @param originalAlarms Alarms parsed from rawIcal
     * @param userReminders User's edited reminders from event.reminders (may be null)
     * @return Merged alarm list
     */
    private fun mergeAlarms(
        originalAlarms: List<ICalAlarm>,
        userReminders: List<String>?
    ): List<ICalAlarm> {
        // User cleared all reminders → clear all alarms
        if (userReminders.isNullOrEmpty()) {
            return emptyList()
        }

        val result = mutableListOf<ICalAlarm>()
        var originalIndex = 0

        // Process user's reminders
        for (reminderStr in userReminders) {
            val userDuration = try {
                DurationUtils.parse(reminderStr)
            } catch (e: Exception) {
                null
            }

            if (userDuration == null) continue

            // Find next relative-trigger alarm in original (skip absolute triggers)
            while (originalIndex < originalAlarms.size &&
                   originalAlarms[originalIndex].triggerAbsolute != null) {
                // Preserve absolute triggers in order
                result.add(originalAlarms[originalIndex])
                originalIndex++
            }

            if (originalIndex < originalAlarms.size) {
                // Preserve original's action/description/uid, update trigger
                val original = originalAlarms[originalIndex]
                result.add(original.copy(trigger = userDuration))
                originalIndex++
            } else {
                // No more originals - create new DISPLAY alarm
                result.add(ICalAlarm(
                    action = AlarmAction.DISPLAY,
                    trigger = userDuration,
                    triggerAbsolute = null,
                    description = "Reminder",
                    summary = null
                ))
            }
        }

        // Preserve remaining original alarms unchanged
        while (originalIndex < originalAlarms.size) {
            result.add(originalAlarms[originalIndex])
            originalIndex++
        }

        return result
    }

    /**
     * Parse exdate CSV string to list of ICalDateTime.
     *
     * @param csv Comma-separated timestamps in milliseconds
     * @param zone Timezone for the datetimes
     * @param isDate True if these are date-only values
     * @return List of ICalDateTime
     */
    private fun parseExdates(csv: String?, zone: ZoneId?, isDate: Boolean): List<ICalDateTime> {
        if (csv.isNullOrBlank()) return emptyList()
        return csv.split(",").mapNotNull { ts ->
            ts.trim().toLongOrNull()?.let {
                ICalDateTime.fromTimestamp(it, zone, isDate)
            }
        }
    }

    // ========== RFC 5545/7986 Formatting Helpers ==========

    /**
     * Format latitude/longitude to RFC 5545 GEO "latitude;longitude" format.
     *
     * @param lat Latitude in decimal degrees
     * @param lon Longitude in decimal degrees
     * @return GEO string (e.g., "37.386013;-122.082932"), or null if either is null
     */
    private fun formatGeo(lat: Double?, lon: Double?): String? {
        if (lat == null || lon == null) return null
        return "$lat;$lon"
    }

    /**
     * Format ARGB int to CSS hex color (#RRGGBB) for iCal.
     * Alpha channel is dropped per RFC 7986 COLOR spec.
     *
     * @param argb ARGB color integer
     * @return CSS hex color string (e.g., "#FF5733")
     */
    private fun formatColorFromArgb(argb: Int): String {
        return String.format("#%06X", argb and 0xFFFFFF)
    }
}
