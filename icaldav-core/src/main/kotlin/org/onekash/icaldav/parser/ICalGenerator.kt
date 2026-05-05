package org.onekash.icaldav.parser

import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.model.Attendee
import org.onekash.icaldav.model.AttendeeRole
import org.onekash.icaldav.model.CUType
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalCalendar
import org.onekash.icaldav.model.ICalConference
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ICalImage
import org.onekash.icaldav.model.ICalJournal
import org.onekash.icaldav.model.ICalTodo
import org.onekash.icaldav.model.ITipMethod
import org.onekash.icaldav.model.ImageDisplay
import org.onekash.icaldav.model.Organizer
import org.onekash.icaldav.model.Transparency
import org.onekash.icaldav.util.DurationUtils
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Generates RFC 5545 compliant iCalendar strings.
 *
 * Handles all required fields for iCloud compatibility:
 * Missing CALSCALE, METHOD, STATUS, or SEQUENCE → HTTP 400
 *
 * VTIMEZONE generation is enabled by default for better interoperability
 * with calendar clients that don't recognize IANA timezone IDs.
 */
class ICalGenerator(
    private val prodId: String = "-//iCalDAV//EN",
    /**
     * Include Apple-specific extensions for better iCloud compatibility.
     * When true, adds X-WR-ALARMUID and X-APPLE-DEFAULT-ALARM to VALARM.
     */
    private val includeAppleExtensions: Boolean = true
) {
    private val vtimezoneGenerator = VTimezoneGenerator()

    /**
     * Generate iCal string for a single event.
     *
     * @param event The event to generate
     * @param includeMethod Include METHOD:PUBLISH (some CalDAV servers like Nextcloud
     *                      reject this for PUT operations - set to false for CalDAV)
     * @param includeVTimezone Include VTIMEZONE components for referenced timezones
     *                         (enabled by default for better interoperability)
     * @return Complete VCALENDAR string
     * @deprecated Use generate(event, method, preserveDtstamp, includeVTimezone) for scheduling
     */
    @Deprecated(
        "Use generate(event, method, preserveDtstamp, includeVTimezone) instead",
        ReplaceWith("generate(event, if (includeMethod) ITipMethod.PUBLISH else null, false, includeVTimezone)")
    )
    fun generate(
        event: ICalEvent,
        includeMethod: Boolean = false,
        includeVTimezone: Boolean = true
    ): String = generate(
        event = event,
        method = if (includeMethod) ITipMethod.PUBLISH else null,
        preserveDtstamp = false,
        includeVTimezone = includeVTimezone
    )

    /**
     * Generate iCal string for a single event with iTIP method support.
     *
     * @param event The event to generate
     * @param method iTIP method (null = no METHOD line, for simple calendar storage)
     * @param preserveDtstamp If true, use event's DTSTAMP; if false, use current time
     * @param includeVTimezone Include VTIMEZONE components for referenced timezones
     * @return Complete VCALENDAR string
     */
    fun generate(
        event: ICalEvent,
        method: ITipMethod? = null,
        preserveDtstamp: Boolean = false,
        includeVTimezone: Boolean = true
    ): String = generate(
        calendar = ICalCalendar(
            prodId = null, // falls back to instance prodId
            method = method?.value,
            events = listOf(event)
        ),
        preserveDtstamp = preserveDtstamp,
        includeVTimezone = includeVTimezone
    )

    /**
     * Generate iCal string for multiple events (batch).
     *
     * @param events List of events to generate
     * @param includeMethod Include METHOD:PUBLISH
     * @param includeVTimezone Include VTIMEZONE components for referenced timezones
     *                         (enabled by default, deduplicates across all events)
     * @return Complete VCALENDAR string with all events
     */
    fun generateBatch(
        events: List<ICalEvent>,
        includeMethod: Boolean = true,
        includeVTimezone: Boolean = true
    ): String = generate(
        calendar = ICalCalendar(
            prodId = null, // null => instance prodId is used (preserves original behavior)
            method = if (includeMethod) "PUBLISH" else null,
            events = events
        ),
        includeVTimezone = includeVTimezone
    )

    /**
     * Generate iCal string for a full calendar with all calendar-level metadata
     * (NAME, SOURCE, COLOR, REFRESH-INTERVAL, X-WR-CALNAME, X-APPLE-CALENDAR-COLOR,
     * IMAGE) and mixed VEVENT/VTODO/VJOURNAL components. The symmetric counterpart
     * to [ICalParser.parse].
     *
     * PRODID precedence: [calendar.prodId] wins when non-null, else the
     * generator instance's configured prodId is used.
     *
     * VTIMEZONE collection scans dtStart/dtEnd/recurrenceId/exdates/rdates on
     * events, dtStart/due/completed/recurrenceId on todos, and dtStart/recurrenceId
     * on journals. TZIDs are deduplicated across component types and emitted
     * before the first component per RFC 5545.
     *
     * Emission order: VERSION, PRODID, CALSCALE, METHOD, NAME, SOURCE, COLOR,
     * REFRESH-INTERVAL, X-WR-CALNAME, X-APPLE-CALENDAR-COLOR, IMAGE, VTIMEZONEs,
     * VEVENTs, VTODOs, VJOURNALs, END:VCALENDAR.
     *
     * @param calendar The calendar to generate
     * @param preserveDtstamp If true, preserve each component's DTSTAMP; if false, regenerate
     * @param includeVTimezone Include VTIMEZONE components for referenced timezones
     * @return Complete VCALENDAR string
     */
    fun generate(
        calendar: ICalCalendar,
        preserveDtstamp: Boolean = false,
        includeVTimezone: Boolean = true
    ): String {
        return buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:${calendar.version.ifBlank { "2.0" }}")
            appendLine("PRODID:${calendar.prodId ?: prodId}")
            appendLine("CALSCALE:${calendar.calscale.ifBlank { "GREGORIAN" }}")
            calendar.method?.let { appendLine("METHOD:$it") }

            calendar.name?.let { appendFoldedLine("NAME:${escapeICalText(it)}") }
            calendar.source?.let { appendLine("SOURCE:$it") }
            calendar.color?.let { appendLine("COLOR:$it") }
            calendar.refreshInterval?.let {
                appendLine("REFRESH-INTERVAL;VALUE=DURATION:${DurationUtils.format(it)}")
            }
            calendar.xWrCalname?.let { appendFoldedLine("X-WR-CALNAME:${escapeICalText(it)}") }
            calendar.xAppleCalendarColor?.let { appendLine("X-APPLE-CALENDAR-COLOR:$it") }
            calendar.image?.let { appendImageProperty(it) }

            if (includeVTimezone) {
                vtimezoneGenerator.collectTimezones(calendar).forEach { tzid ->
                    append(vtimezoneGenerator.generate(tzid))
                }
            }

            calendar.events.forEach { appendVEvent(it, preserveDtstamp) }
            calendar.todos.forEach { appendVTodo(it, preserveDtstamp) }
            calendar.journals.forEach { appendVJournal(it, preserveDtstamp) }

            appendLine("END:VCALENDAR")
        }
    }

    private fun StringBuilder.appendVEvent(event: ICalEvent, preserveDtstamp: Boolean = false) {
        appendLine("BEGIN:VEVENT")

        // Required properties
        appendLine("UID:${event.uid}")

        // DTSTAMP handling: preserve for iTIP messages, regenerate otherwise
        if (preserveDtstamp && event.dtstamp != null) {
            appendLine("DTSTAMP:${event.dtstamp.toICalString()}")
        } else {
            appendLine("DTSTAMP:${formatDtStamp()}")
        }

        // DTSTART with timezone
        appendDateTimeProperty("DTSTART", event.dtStart)

        // DTEND or DURATION
        event.dtEnd?.let { dtend ->
            appendDateTimeProperty("DTEND", dtend)
        } ?: event.duration?.let { dur ->
            appendLine("DURATION:${ICalAlarm.formatDuration(dur)}")
        }

        // RECURRENCE-ID for modified instances
        event.recurrenceId?.let { recid ->
            appendDateTimeProperty("RECURRENCE-ID", recid)
        }

        // RRULE (only for master events, NOT modified instances)
        if (event.recurrenceId == null) {
            event.rrule?.let { rrule ->
                appendLine("RRULE:${rrule.toICalString()}")
            }
        }

        // EXDATE list
        event.exdates.forEach { exdate ->
            appendDateTimeProperty("EXDATE", exdate)
        }

        // RDATE list (RFC 5545 Section 3.8.5.2)
        event.rdates.forEach { rdate ->
            appendDateTimeProperty("RDATE", rdate)
        }

        // Summary (title)
        event.summary?.let {
            appendFoldedLine("SUMMARY:${escapeICalText(it)}")
        }

        // Description
        event.description?.let {
            appendFoldedLine("DESCRIPTION:${escapeICalText(it)}")
        }

        // Location
        event.location?.let {
            appendFoldedLine("LOCATION:${escapeICalText(it)}")
        }

        // Status (required for iCloud)
        appendLine("STATUS:${event.status.toICalString()}")

        // Sequence (required for iCloud, increment on updates)
        appendLine("SEQUENCE:${event.sequence}")

        // Priority (RFC 5545) - only output if non-zero (0 = undefined)
        if (event.priority > 0) {
            appendLine("PRIORITY:${event.priority}")
        }

        // Transparency
        if (event.transparency != Transparency.OPAQUE) {
            appendLine("TRANSP:${event.transparency.toICalString()}")
        }

        // Categories
        if (event.categories.isNotEmpty()) {
            appendLine("CATEGORIES:${event.categories.joinToString(",") { escapeICalText(it) }}")
        }

        // Color (RFC 7986)
        event.color?.let {
            appendLine("COLOR:$it")
        }

        // IMAGE properties (RFC 7986)
        event.images.forEach { image ->
            appendImageProperty(image)
        }

        // CONFERENCE properties (RFC 7986)
        event.conferences.forEach { conference ->
            appendConferenceProperty(conference)
        }

        // LINK properties (RFC 9253)
        event.links.forEach { link ->
            appendLine(link.toICalString())
        }

        // RELATED-TO properties (RFC 9253)
        event.relations.forEach { relation ->
            appendLine(relation.toICalString())
        }

        // URL
        event.url?.let {
            appendLine("URL:$it")
        }

        // GEO (RFC 5545) - geographic coordinates "lat;lon"
        event.geo?.let {
            appendLine("GEO:$it")
        }

        // CLASS (RFC 5545 Section 3.8.1.3)
        event.classification?.let {
            appendLine("CLASS:${it.toICalString()}")
        }

        // Organizer (for scheduling)
        event.organizer?.let { org ->
            appendLine(formatOrganizer(org))
        }

        // Attendees (for scheduling)
        event.attendees.forEach { att ->
            appendLine(formatAttendee(att))
        }

        // VALARMs
        event.alarms.forEach { alarm ->
            appendVAlarm(alarm)
        }

        // Created/Last-Modified
        event.created?.let {
            appendLine("CREATED:${it.toICalString()}")
        }
        event.lastModified?.let {
            appendLine("LAST-MODIFIED:${it.toICalString()}")
        }

        // Raw properties (X-*, CLASS, and other unhandled properties for round-trip)
        event.rawProperties.forEach { (key, value) ->
            // Key may contain parameters: "X-APPLE-STRUCTURED-LOCATION;VALUE=URI"
            // In that case, output as-is with the value
            appendFoldedLine("$key:$value")
        }

        appendLine("END:VEVENT")
    }

    private fun StringBuilder.appendVAlarm(alarm: ICalAlarm) {
        appendLine("BEGIN:VALARM")

        // RFC 9074: UID for alarm identification
        // Generate a UID if not provided (needed for Apple extensions)
        val alarmUid = alarm.uid ?: java.util.UUID.randomUUID().toString().uppercase()
        appendLine("UID:$alarmUid")

        // Apple-specific extensions for better iCloud compatibility
        if (includeAppleExtensions) {
            // X-WR-ALARMUID: iCloud alarm identifier (same as UID)
            appendLine("X-WR-ALARMUID:$alarmUid")
            // X-APPLE-DEFAULT-ALARM: Prevents iPhone from treating this as a
            // "default" alarm that can be merged with calendar defaults
            if (!alarm.defaultAlarm) {
                appendLine("X-APPLE-DEFAULT-ALARM:FALSE")
            }
        }

        appendLine("ACTION:${alarm.action.name}")

        // Trigger
        alarm.trigger?.let { dur ->
            val related = if (alarm.triggerRelatedToEnd) ";RELATED=END" else ""
            appendLine("TRIGGER${related}:${ICalAlarm.formatDuration(dur)}")
        } ?: alarm.triggerAbsolute?.let { dt ->
            appendLine("TRIGGER;VALUE=DATE-TIME:${dt.toICalString()}")
        }

        // Description (required for DISPLAY)
        if (alarm.action == AlarmAction.DISPLAY) {
            appendLine("DESCRIPTION:${alarm.description ?: "Reminder"}")
        }

        // Summary (for EMAIL action)
        alarm.summary?.let {
            appendLine("SUMMARY:$it")
        }

        // Repeat
        if (alarm.repeatCount > 0) {
            appendLine("REPEAT:${alarm.repeatCount}")
            alarm.repeatDuration?.let { dur ->
                appendLine("DURATION:${ICalAlarm.formatDuration(dur)}")
            }
        }

        // RFC 9074 extensions
        alarm.acknowledged?.let {
            appendLine("ACKNOWLEDGED:${it.toICalString()}")
        }

        alarm.relatedTo?.let {
            appendLine("RELATED-TO:$it")
        }

        // RFC 9074: DEFAULT-ALARM
        if (alarm.defaultAlarm) {
            appendLine("DEFAULT-ALARM:TRUE")
        }

        alarm.proximity?.let {
            appendLine("PROXIMITY:${it.toICalString()}")
        }

        appendLine("END:VALARM")
    }

    /**
     * Append datetime property with proper formatting.
     */
    private fun StringBuilder.appendDateTimeProperty(name: String, dt: ICalDateTime) {
        if (dt.isDate) {
            // DATE format for all-day
            appendLine("$name;VALUE=DATE:${dt.toICalString()}")
        } else if (dt.isUtc) {
            // UTC format
            appendLine("$name:${dt.toICalString()}")
        } else if (dt.timezone != null) {
            // Local with TZID
            val tzid = dt.timezone.id
            appendLine("$name;TZID=$tzid:${dt.toICalString()}")
        } else {
            // Floating (no timezone)
            appendLine("$name:${dt.toICalString()}")
        }
    }

    /**
     * Append a line with folding if > 75 octets (bytes).
     *
     * RFC 5545 Section 3.1: Lines SHOULD NOT be longer than 75 octets,
     * excluding the line break.
     *
     * IMPORTANT: This counts octets (UTF-8 bytes), not characters.
     * A single character may be 1-4 bytes in UTF-8.
     * Handles surrogate pairs (emoji) correctly by using code points.
     */
    private fun StringBuilder.appendFoldedLine(line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8)

        if (bytes.size <= 75) {
            appendLine(line)
            return
        }

        // Use code points instead of chars to handle surrogate pairs correctly
        val codePoints = line.codePoints().toArray()
        var cpIndex = 0
        var isFirst = true

        while (cpIndex < codePoints.size) {
            val maxBytes = if (isFirst) 75 else 74  // 74 for continuation (after space)

            // Find how many code points fit in maxBytes
            var usedBytes = 0
            val startCpIndex = cpIndex

            while (cpIndex < codePoints.size) {
                val cp = codePoints[cpIndex]
                val cpBytes = Character.toString(cp).toByteArray(Charsets.UTF_8).size

                if (usedBytes + cpBytes > maxBytes) {
                    break
                }

                usedBytes += cpBytes
                cpIndex++
            }

            // Handle edge case: if no code points fit, force at least one
            if (cpIndex == startCpIndex && cpIndex < codePoints.size) {
                cpIndex++
            }

            if (!isFirst) {
                append(" ")  // RFC 5545: continuation lines start with space or tab
            }

            // Convert code points back to string
            val segment = codePoints.sliceArray(startCpIndex until cpIndex)
                .map { Character.toString(it) }
                .joinToString("")
            append(segment)
            appendLine()

            isFirst = false
        }
    }

    /**
     * Format DTSTAMP as UTC timestamp.
     */
    private fun formatDtStamp(): String {
        val now = Instant.now().atZone(ZoneOffset.UTC)
        return DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(now)
    }

    /**
     * Escape text values per RFC 5545 Section 3.3.11.
     */
    private fun escapeICalText(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace(",", "\\,")
            .replace(";", "\\;")
    }

    /**
     * Escape parameter values (may need quoting).
     */
    private fun escapeParamValue(value: String): String {
        return if (value.contains(":") || value.contains(";") || value.contains(",")) {
            "\"$value\""
        } else {
            value
        }
    }

    /**
     * Format ORGANIZER property with RFC 6638 scheduling parameters.
     */
    private fun formatOrganizer(organizer: Organizer): String {
        val params = mutableListOf<String>()

        organizer.name?.let { params.add("CN=${escapeParamValue(it)}") }
        organizer.sentBy?.let { params.add("SENT-BY=\"mailto:$it\"") }

        // RFC 6638 scheduling parameters
        organizer.scheduleAgent?.let { params.add("SCHEDULE-AGENT=${it.value}") }
        organizer.scheduleForceSend?.let { params.add("SCHEDULE-FORCE-SEND=${it.value}") }
        // Note: SCHEDULE-STATUS is server-generated, typically not output on requests

        val paramStr = if (params.isNotEmpty()) ";${params.joinToString(";")}" else ""
        return "ORGANIZER$paramStr:mailto:${organizer.email}"
    }

    /**
     * Format ATTENDEE property with all RFC 5545 and RFC 6638 parameters.
     */
    private fun formatAttendee(attendee: Attendee): String {
        val params = mutableListOf<String>()

        attendee.name?.let { params.add("CN=${escapeParamValue(it)}") }

        // Only output CUTYPE if not default (INDIVIDUAL)
        if (attendee.cutype != CUType.INDIVIDUAL) {
            params.add("CUTYPE=${attendee.cutype.toICalString()}")
        }

        // Only output ROLE if not default (REQ-PARTICIPANT)
        if (attendee.role != AttendeeRole.REQ_PARTICIPANT) {
            params.add("ROLE=${attendee.role.toICalString()}")
        }

        params.add("PARTSTAT=${attendee.partStat.toICalString()}")

        if (attendee.rsvp) params.add("RSVP=TRUE")

        attendee.dir?.let { params.add("DIR=\"$it\"") }
        attendee.member?.let { params.add("MEMBER=\"$it\"") }

        if (attendee.delegatedTo.isNotEmpty()) {
            params.add("DELEGATED-TO=${attendee.delegatedTo.joinToString(",") { "\"mailto:$it\"" }}")
        }
        if (attendee.delegatedFrom.isNotEmpty()) {
            params.add("DELEGATED-FROM=${attendee.delegatedFrom.joinToString(",") { "\"mailto:$it\"" }}")
        }

        // RFC 6638 scheduling parameters
        attendee.sentBy?.let { params.add("SENT-BY=\"mailto:$it\"") }
        attendee.scheduleAgent?.let { params.add("SCHEDULE-AGENT=${it.value}") }
        attendee.scheduleForceSend?.let { params.add("SCHEDULE-FORCE-SEND=${it.value}") }
        // Note: SCHEDULE-STATUS is server-generated, typically not output on requests

        val paramStr = if (params.isNotEmpty()) ";${params.joinToString(";")}" else ""
        return "ATTENDEE$paramStr:mailto:${attendee.email}"
    }

    // ============ RFC 7986 Property Generation ============

    /**
     * Append IMAGE property (RFC 7986).
     *
     * Format: IMAGE;VALUE=URI;DISPLAY=BADGE;FMTTYPE=image/png:https://example.com/logo.png
     */
    private fun StringBuilder.appendImageProperty(image: ICalImage) {
        val params = mutableListOf<String>()
        params.add("VALUE=URI")

        if (image.display != ImageDisplay.GRAPHIC) {
            params.add("DISPLAY=${image.display.name}")
        }
        image.mediaType?.let { params.add("FMTTYPE=$it") }
        image.altText?.let { params.add("ALTREP=\"${escapeICalText(it)}\"") }

        appendLine("IMAGE;${params.joinToString(";")}:${image.uri}")
    }

    /**
     * Append CONFERENCE property (RFC 7986).
     *
     * Format: CONFERENCE;VALUE=URI;FEATURE=VIDEO,AUDIO;LABEL=Join:https://zoom.us/j/123
     */
    private fun StringBuilder.appendConferenceProperty(conference: ICalConference) {
        val params = mutableListOf<String>()
        params.add("VALUE=URI")

        if (conference.features.isNotEmpty()) {
            params.add("FEATURE=${conference.features.joinToString(",") { it.name }}")
        }
        conference.label?.let { params.add("LABEL=${escapeParamValue(it)}") }
        conference.language?.let { params.add("LANGUAGE=$it") }

        appendLine("CONFERENCE;${params.joinToString(";")}:${conference.uri}")
    }

    // ============ VTODO Generation ============

    /**
     * Generate iCal string for a single VTODO.
     *
     * @param todo The todo to generate
     * @param method iTIP method (null = no METHOD line, for simple calendar storage)
     * @param preserveDtstamp If true, use todo's DTSTAMP; if false, use current time
     * @param includeVTimezone Include VTIMEZONE components for referenced timezones
     * @return Complete VCALENDAR string
     */
    fun generate(
        todo: ICalTodo,
        method: ITipMethod? = null,
        preserveDtstamp: Boolean = false,
        includeVTimezone: Boolean = true
    ): String = generate(
        calendar = ICalCalendar(
            prodId = null, // falls back to instance prodId
            method = method?.value,
            todos = listOf(todo)
        ),
        preserveDtstamp = preserveDtstamp,
        includeVTimezone = includeVTimezone
    )

    private fun StringBuilder.appendVTodo(todo: ICalTodo, preserveDtstamp: Boolean = false) {
        appendLine("BEGIN:VTODO")

        // Required properties
        appendLine("UID:${todo.uid}")

        // DTSTAMP handling
        if (preserveDtstamp && todo.dtstamp != null) {
            appendLine("DTSTAMP:${todo.dtstamp.toICalString()}")
        } else {
            appendLine("DTSTAMP:${formatDtStamp()}")
        }

        // DTSTART
        todo.dtStart?.let { dt ->
            appendDateTimeProperty("DTSTART", dt)
        }

        // DUE
        todo.due?.let { due ->
            appendDateTimeProperty("DUE", due)
        }

        // COMPLETED
        todo.completed?.let { completed ->
            // COMPLETED must be in UTC per RFC 5545
            appendLine("COMPLETED:${completed.toICalString()}")
        }

        // RECURRENCE-ID for modified instances
        todo.recurrenceId?.let { recid ->
            appendDateTimeProperty("RECURRENCE-ID", recid)
        }

        // RRULE (only for master todos, NOT modified instances)
        if (todo.recurrenceId == null) {
            todo.rrule?.let { rrule ->
                appendLine("RRULE:${rrule.toICalString()}")
            }
        }

        // Summary (title)
        todo.summary?.let {
            appendFoldedLine("SUMMARY:${escapeICalText(it)}")
        }

        // Description
        todo.description?.let {
            appendFoldedLine("DESCRIPTION:${escapeICalText(it)}")
        }

        // Location
        todo.location?.let {
            appendFoldedLine("LOCATION:${escapeICalText(it)}")
        }

        // Status (required for proper sync)
        appendLine("STATUS:${todo.status.toICalString()}")

        // Sequence
        appendLine("SEQUENCE:${todo.sequence}")

        // Priority
        if (todo.priority != 0) {
            appendLine("PRIORITY:${todo.priority}")
        }

        // Percent complete
        if (todo.percentComplete != 0) {
            appendLine("PERCENT-COMPLETE:${todo.percentComplete}")
        }

        // Categories
        if (todo.categories.isNotEmpty()) {
            appendLine("CATEGORIES:${todo.categories.joinToString(",") { escapeICalText(it) }}")
        }

        // URL
        todo.url?.let {
            appendLine("URL:$it")
        }

        // GEO
        todo.geo?.let {
            appendLine("GEO:$it")
        }

        // CLASS
        todo.classification?.let {
            appendLine("CLASS:$it")
        }

        // Organizer (for task assignment)
        todo.organizer?.let { org ->
            appendLine(formatOrganizer(org))
        }

        // Attendees (assignees)
        todo.attendees.forEach { att ->
            appendLine(formatAttendee(att))
        }

        // VALARMs
        todo.alarms.forEach { alarm ->
            appendVAlarm(alarm)
        }

        // Created/Last-Modified
        todo.created?.let {
            appendLine("CREATED:${it.toICalString()}")
        }
        todo.lastModified?.let {
            appendLine("LAST-MODIFIED:${it.toICalString()}")
        }

        // Raw properties for round-trip
        todo.rawProperties.forEach { (key, value) ->
            appendFoldedLine("$key:$value")
        }

        appendLine("END:VTODO")
    }

    // ============ VJOURNAL Generation ============

    /**
     * Generate iCal string for a single VJOURNAL.
     *
     * @param journal The journal to generate
     * @param method iTIP method (null = no METHOD line, for simple calendar storage)
     * @param preserveDtstamp If true, use journal's DTSTAMP; if false, use current time
     * @param includeVTimezone Include VTIMEZONE components for referenced timezones
     * @return Complete VCALENDAR string
     */
    fun generate(
        journal: ICalJournal,
        method: ITipMethod? = null,
        preserveDtstamp: Boolean = false,
        includeVTimezone: Boolean = true
    ): String = generate(
        calendar = ICalCalendar(
            prodId = null, // falls back to instance prodId
            method = method?.value,
            journals = listOf(journal)
        ),
        preserveDtstamp = preserveDtstamp,
        includeVTimezone = includeVTimezone
    )

    private fun StringBuilder.appendVJournal(journal: ICalJournal, preserveDtstamp: Boolean = false) {
        appendLine("BEGIN:VJOURNAL")

        // Required properties
        appendLine("UID:${journal.uid}")

        // DTSTAMP handling
        if (preserveDtstamp && journal.dtstamp != null) {
            appendLine("DTSTAMP:${journal.dtstamp.toICalString()}")
        } else {
            appendLine("DTSTAMP:${formatDtStamp()}")
        }

        // DTSTART
        journal.dtStart?.let { dt ->
            appendDateTimeProperty("DTSTART", dt)
        }

        // RECURRENCE-ID for modified instances
        journal.recurrenceId?.let { recid ->
            appendDateTimeProperty("RECURRENCE-ID", recid)
        }

        // RRULE (only for master journals, NOT modified instances)
        if (journal.recurrenceId == null) {
            journal.rrule?.let { rrule ->
                appendLine("RRULE:${rrule.toICalString()}")
            }
        }

        // Summary (title)
        journal.summary?.let {
            appendFoldedLine("SUMMARY:${escapeICalText(it)}")
        }

        // Description
        journal.description?.let {
            appendFoldedLine("DESCRIPTION:${escapeICalText(it)}")
        }

        // Status
        appendLine("STATUS:${journal.status.toICalString()}")

        // Sequence
        appendLine("SEQUENCE:${journal.sequence}")

        // Categories
        if (journal.categories.isNotEmpty()) {
            appendLine("CATEGORIES:${journal.categories.joinToString(",") { escapeICalText(it) }}")
        }

        // Attachments
        journal.attachments.forEach { attach ->
            appendLine("ATTACH:$attach")
        }

        // URL
        journal.url?.let {
            appendLine("URL:$it")
        }

        // CLASS
        journal.classification?.let {
            appendLine("CLASS:$it")
        }

        // Organizer
        journal.organizer?.let { org ->
            appendLine(formatOrganizer(org))
        }

        // Attendees
        journal.attendees.forEach { att ->
            appendLine(formatAttendee(att))
        }

        // Created/Last-Modified
        journal.created?.let {
            appendLine("CREATED:${it.toICalString()}")
        }
        journal.lastModified?.let {
            appendLine("LAST-MODIFIED:${it.toICalString()}")
        }

        // Raw properties for round-trip
        journal.rawProperties.forEach { (key, value) ->
            appendFoldedLine("$key:$value")
        }

        appendLine("END:VJOURNAL")
    }

    companion object {
        /**
         * Generate a free/busy request (VFREEBUSY with METHOD:REQUEST).
         * Stateless utility function - can be called without ICalGenerator instance.
         *
         * @param organizer The calendar user requesting free/busy info
         * @param attendees The attendees to query for free/busy
         * @param dtstart Start of the query time range
         * @param dtend End of the query time range
         * @param uid Optional UID (generates random if not provided)
         * @return Complete VCALENDAR string with VFREEBUSY
         */
        fun generateFreeBusyRequest(
            organizer: Organizer,
            attendees: List<Attendee>,
            dtstart: ICalDateTime,
            dtend: ICalDateTime,
            uid: String = java.util.UUID.randomUUID().toString().uppercase()
        ): String {
            return buildString {
                appendLine("BEGIN:VCALENDAR")
                appendLine("VERSION:2.0")
                appendLine("PRODID:-//iCalDAV//EN")
                appendLine("METHOD:REQUEST")
                appendLine("BEGIN:VFREEBUSY")
                appendLine("UID:$uid")
                appendLine("DTSTAMP:${ICalDateTime.now().toICalString()}")
                appendLine("DTSTART:${dtstart.toICalString()}")
                appendLine("DTEND:${dtend.toICalString()}")

                // Organizer
                val orgParams = mutableListOf<String>()
                organizer.name?.let { orgParams.add("CN=$it") }
                organizer.sentBy?.let { orgParams.add("SENT-BY=\"mailto:$it\"") }
                val orgParamStr = if (orgParams.isNotEmpty()) ";${orgParams.joinToString(";")}" else ""
                appendLine("ORGANIZER$orgParamStr:mailto:${organizer.email}")

                // Attendees
                attendees.forEach { att ->
                    val attParams = mutableListOf<String>()
                    att.name?.let { attParams.add("CN=$it") }
                    attParams.add("PARTSTAT=${att.partStat.toICalString()}")
                    val attParamStr = if (attParams.isNotEmpty()) ";${attParams.joinToString(";")}" else ""
                    appendLine("ATTENDEE$attParamStr:mailto:${att.email}")
                }

                appendLine("END:VFREEBUSY")
                appendLine("END:VCALENDAR")
            }
        }
    }
}