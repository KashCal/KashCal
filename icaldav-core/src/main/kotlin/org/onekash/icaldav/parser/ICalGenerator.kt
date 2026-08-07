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
import org.onekash.icaldav.util.CalAddress
import org.onekash.icaldav.util.DurationUtils
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// RFC 5545 §3.1: content lines are delimited by CRLF, not the platform line separator.
// Kotlin's StringBuilder.appendLine uses System.lineSeparator() (LF on Linux/Android),
// which is non-conformant. Use these helpers for every emitted iCalendar line.
internal fun StringBuilder.crlfLine(line: String): StringBuilder = append(line).append("\r\n")
internal fun StringBuilder.crlfLine(): StringBuilder = append("\r\n")

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
            crlfLine("BEGIN:VCALENDAR")
            crlfLine("VERSION:${calendar.version.ifBlank { "2.0" }}")
            crlfLine("PRODID:${calendar.prodId ?: prodId}")
            crlfLine("CALSCALE:${calendar.calscale.ifBlank { "GREGORIAN" }}")
            calendar.method?.let { crlfLine("METHOD:$it") }

            calendar.name?.let { appendFoldedLine("NAME:${escapeICalText(it)}") }
            calendar.source?.let { crlfLine("SOURCE:$it") }
            calendar.color?.let { crlfLine("COLOR:$it") }
            calendar.refreshInterval?.let {
                crlfLine("REFRESH-INTERVAL;VALUE=DURATION:${DurationUtils.format(it)}")
            }
            calendar.xWrCalname?.let { appendFoldedLine("X-WR-CALNAME:${escapeICalText(it)}") }
            calendar.xAppleCalendarColor?.let { crlfLine("X-APPLE-CALENDAR-COLOR:$it") }
            calendar.image?.let { appendImageProperty(it) }

            if (includeVTimezone) {
                vtimezoneGenerator.collectTimezones(calendar).forEach { tzid ->
                    append(vtimezoneGenerator.generate(tzid))
                }
            }

            // RFC 6638 §7.1/§7.2: a METHOD line means this is a scheduling
            // message, so SCHEDULE-AGENT / SCHEDULE-FORCE-SEND must be stripped
            // from ORGANIZER and ATTENDEE. Plain storage PUTs (no METHOD) keep them.
            // This is derived from the raw METHOD string, not the parsed enum, so
            // an unrecognized/extension METHOD still counts as a scheduling message.
            val isSchedulingMessage = calendar.method != null
            // The parsed iTIP method drives per-method property constraints
            // (RFC 5546 §3.2): the minimal REFRESH set and the VALARM presence
            // rules. Null for storage PUTs and for unrecognized METHOD strings.
            val itipMethod = calendar.method?.let { ITipMethod.fromString(it) }

            calendar.events.forEach { appendVEvent(it, preserveDtstamp, isSchedulingMessage, itipMethod) }
            calendar.todos.forEach { appendVTodo(it, preserveDtstamp, isSchedulingMessage) }
            calendar.journals.forEach { appendVJournal(it, preserveDtstamp, isSchedulingMessage) }

            crlfLine("END:VCALENDAR")
        }
    }

    private fun StringBuilder.appendVEvent(
        event: ICalEvent,
        preserveDtstamp: Boolean = false,
        isSchedulingMessage: Boolean = false,
        itipMethod: ITipMethod? = null
    ) {
        crlfLine("BEGIN:VEVENT")

        // Required properties
        crlfLine("UID:${event.uid}")

        // DTSTAMP handling: preserve for iTIP messages, regenerate otherwise
        if (preserveDtstamp && event.dtstamp != null) {
            crlfLine("DTSTAMP:${event.dtstamp.toICalString()}")
        } else {
            crlfLine("DTSTAMP:${formatDtStamp()}")
        }

        // RFC 5546 §3.2.6: a METHOD:REFRESH VEVENT is a minimal request from an
        // attendee for the latest version. Only UID, DTSTAMP, ORGANIZER, the
        // requesting ATTENDEE (and RECURRENCE-ID for an instance) are permitted;
        // every other property has presence 0. ICalEvent's non-null DTSTART /
        // STATUS / SEQUENCE would otherwise be emitted unconditionally below, so
        // REFRESH is handled as a dedicated minimal path.
        if (itipMethod == ITipMethod.REFRESH) {
            event.recurrenceId?.let { recid -> appendDateTimeProperty("RECURRENCE-ID", recid) }
            event.organizer?.let { org -> crlfLine(formatOrganizer(org, isSchedulingMessage)) }
            event.attendees.forEach { att -> crlfLine(formatAttendee(att, isSchedulingMessage)) }
            crlfLine("END:VEVENT")
            return
        }

        // DTSTART with timezone
        appendDateTimeProperty("DTSTART", event.dtStart)

        // DTEND or DURATION
        event.dtEnd?.let { dtend ->
            appendDateTimeProperty("DTEND", dtend)
        } ?: event.duration?.let { dur ->
            crlfLine("DURATION:${ICalAlarm.formatDuration(dur)}")
        }

        // RECURRENCE-ID for modified instances
        event.recurrenceId?.let { recid ->
            appendDateTimeProperty("RECURRENCE-ID", recid)
        }

        // RRULE (only for master events, NOT modified instances)
        if (event.recurrenceId == null) {
            event.rrule?.let { rrule ->
                crlfLine("RRULE:${rrule.toICalString()}")
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
        crlfLine("STATUS:${event.status.toICalString()}")

        // Sequence (required for iCloud, increment on updates)
        crlfLine("SEQUENCE:${event.sequence}")

        // Priority (RFC 5545) - only output if non-zero (0 = undefined)
        if (event.priority > 0) {
            crlfLine("PRIORITY:${event.priority}")
        }

        // Transparency
        if (event.transparency != Transparency.OPAQUE) {
            crlfLine("TRANSP:${event.transparency.toICalString()}")
        }

        // Categories
        if (event.categories.isNotEmpty()) {
            crlfLine("CATEGORIES:${event.categories.joinToString(",") { escapeICalText(it) }}")
        }

        // Color (RFC 7986)
        event.color?.let {
            crlfLine("COLOR:$it")
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
            crlfLine(link.toICalString())
        }

        // RELATED-TO properties (RFC 9253)
        event.relations.forEach { relation ->
            crlfLine(relation.toICalString())
        }

        // URL
        event.url?.let {
            crlfLine("URL:$it")
        }

        // GEO (RFC 5545) - geographic coordinates "lat;lon"
        event.geo?.let {
            crlfLine("GEO:$it")
        }

        // CLASS (RFC 5545 Section 3.8.1.3)
        event.classification?.let {
            crlfLine("CLASS:${it.toICalString()}")
        }

        // Organizer (for scheduling)
        event.organizer?.let { org ->
            crlfLine(formatOrganizer(org, isSchedulingMessage))
        }

        // Attendees (for scheduling)
        event.attendees.forEach { att ->
            crlfLine(formatAttendee(att, isSchedulingMessage))
        }

        // VALARMs. RFC 5546 §3.2.3 (REPLY) and §3.2.5 (CANCEL) set VALARM
        // presence to 0; REQUEST/ADD/PUBLISH permit it (0+). Skip alarms only on
        // the two methods that forbid them so the organizer's reminders are not
        // leaked into a reply or cancellation.
        if (itipMethod != ITipMethod.REPLY && itipMethod != ITipMethod.CANCEL) {
            event.alarms.forEach { alarm ->
                appendVAlarm(alarm)
            }
        }

        // Created/Last-Modified
        event.created?.let {
            crlfLine("CREATED:${it.toICalString()}")
        }
        event.lastModified?.let {
            crlfLine("LAST-MODIFIED:${it.toICalString()}")
        }

        // Raw properties (X-*, CLASS, and other unhandled properties for round-trip)
        event.rawProperties.forEach { (key, value) ->
            // Key may contain parameters: "X-APPLE-STRUCTURED-LOCATION;VALUE=URI"
            // In that case, output as-is with the value
            appendFoldedLine("$key:$value")
        }

        crlfLine("END:VEVENT")
    }

    private fun StringBuilder.appendVAlarm(alarm: ICalAlarm) {
        crlfLine("BEGIN:VALARM")

        // RFC 9074: UID for alarm identification
        // Generate a UID if not provided (needed for Apple extensions)
        val alarmUid = alarm.uid ?: java.util.UUID.randomUUID().toString().uppercase()
        crlfLine("UID:$alarmUid")

        // Apple-specific extensions for better iCloud compatibility
        if (includeAppleExtensions) {
            // X-WR-ALARMUID: iCloud alarm identifier (same as UID)
            crlfLine("X-WR-ALARMUID:$alarmUid")
            // X-APPLE-DEFAULT-ALARM: Prevents iPhone from treating this as a
            // "default" alarm that can be merged with calendar defaults
            if (!alarm.defaultAlarm) {
                crlfLine("X-APPLE-DEFAULT-ALARM:FALSE")
            }
        }

        crlfLine("ACTION:${alarm.action.name}")

        // Trigger
        alarm.trigger?.let { dur ->
            val related = if (alarm.triggerRelatedToEnd) ";RELATED=END" else ""
            crlfLine("TRIGGER${related}:${ICalAlarm.formatDuration(dur)}")
        } ?: alarm.triggerAbsolute?.let { dt ->
            crlfLine("TRIGGER;VALUE=DATE-TIME:${dt.toICalString()}")
        }

        // RFC 5545 §3.6.6: DESCRIPTION is required for both DISPLAY (text to
        // show) and EMAIL (message body); AUDIO has none.
        if (alarm.action == AlarmAction.DISPLAY || alarm.action == AlarmAction.EMAIL) {
            crlfLine("DESCRIPTION:${escapeICalText(alarm.description ?: "Reminder")}")
        }

        // RFC 5545 §3.6.6: SUMMARY (message subject) is required for EMAIL.
        // Emit whenever provided, and default it for EMAIL so the required
        // property is never absent.
        (alarm.summary ?: if (alarm.action == AlarmAction.EMAIL) "Reminder" else null)?.let {
            crlfLine("SUMMARY:${escapeICalText(it)}")
        }

        // Repeat
        if (alarm.repeatCount > 0) {
            crlfLine("REPEAT:${alarm.repeatCount}")
            alarm.repeatDuration?.let { dur ->
                crlfLine("DURATION:${ICalAlarm.formatDuration(dur)}")
            }
        }

        // RFC 9074 extensions
        alarm.acknowledged?.let {
            crlfLine("ACKNOWLEDGED:${it.toICalString()}")
        }

        alarm.relatedTo?.let {
            crlfLine("RELATED-TO:${escapeICalText(it)}")
        }

        // RFC 9074: DEFAULT-ALARM
        if (alarm.defaultAlarm) {
            crlfLine("DEFAULT-ALARM:TRUE")
        }

        alarm.proximity?.let {
            crlfLine("PROXIMITY:${it.toICalString()}")
        }

        crlfLine("END:VALARM")
    }

    /**
     * Append datetime property with proper formatting.
     */
    private fun StringBuilder.appendDateTimeProperty(name: String, dt: ICalDateTime) {
        if (dt.isDate) {
            // DATE format for all-day
            crlfLine("$name;VALUE=DATE:${dt.toICalString()}")
        } else if (dt.isUtc) {
            // UTC format
            crlfLine("$name:${dt.toICalString()}")
        } else if (dt.timezone != null) {
            // Local with TZID
            val tzid = dt.timezone.id
            crlfLine("$name;TZID=$tzid:${dt.toICalString()}")
        } else {
            // Floating (no timezone)
            crlfLine("$name:${dt.toICalString()}")
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
            crlfLine(line)
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
            crlfLine()

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
            // Normalize CRLF and lone CR to a single LF first: a bare CR is a
            // control char excluded from VALUE-CHAR (§3.1), and CRLF must not
            // become two line breaks. Do this before backslash-escaping so the
            // resulting LF is escaped to \n like any other newline.
            .replace("\r\n", "\n")
            .replace("\r", "\n")
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
     *
     * @param isSchedulingMessage true when emitting a METHOD-bearing iTIP
     *   message. RFC 6638 §7.1/§7.2 forbids a client from echoing
     *   SCHEDULE-AGENT / SCHEDULE-FORCE-SEND in messages it sends, so they are
     *   dropped in that case and preserved on plain resource-storage PUTs.
     */
    private fun formatOrganizer(organizer: Organizer, isSchedulingMessage: Boolean): String {
        val params = mutableListOf<String>()

        organizer.name?.let { params.add("CN=${escapeParamValue(it)}") }
        organizer.sentBy?.let { params.add("SENT-BY=\"mailto:$it\"") }

        // RFC 6638 scheduling parameters — server routing hints valid on stored
        // resources, but a client MUST NOT echo them in scheduling messages.
        if (!isSchedulingMessage) {
            organizer.scheduleAgent?.let { params.add("SCHEDULE-AGENT=${it.value}") }
            organizer.scheduleForceSend?.let { params.add("SCHEDULE-FORCE-SEND=${it.value}") }
        }
        // Note: SCHEDULE-STATUS is server-generated, typically not output on requests

        val paramStr = if (params.isNotEmpty()) ";${params.joinToString(";")}" else ""
        return "ORGANIZER$paramStr:${CalAddress.format(organizer.email)}"
    }

    /**
     * Format ATTENDEE property with all RFC 5545 and RFC 6638 parameters.
     *
     * @param isSchedulingMessage see [formatOrganizer] — drops SCHEDULE-AGENT /
     *   SCHEDULE-FORCE-SEND when emitting a METHOD-bearing iTIP message.
     */
    private fun formatAttendee(attendee: Attendee, isSchedulingMessage: Boolean): String {
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

        // Emit RSVP=TRUE only when explicitly true. Per RFC 5545 §3.2.17, the
        // parameter is omitted when not requested; null and false both omit.
        if (attendee.rsvp == true) params.add("RSVP=TRUE")

        attendee.dir?.let { params.add("DIR=\"$it\"") }
        // RFC 5545 §3.2.11: MEMBER is multi-value. parseMailtoList strips
        // "mailto:" on parse, so re-prepend on emit to match delegated-to/from
        // convention (stored bare, emitted with mailto: prefix).
        if (attendee.member.isNotEmpty()) {
            params.add("MEMBER=" + attendee.member.joinToString(",") { "\"mailto:$it\"" })
        }

        if (attendee.delegatedTo.isNotEmpty()) {
            params.add("DELEGATED-TO=${attendee.delegatedTo.joinToString(",") { "\"mailto:$it\"" }}")
        }
        if (attendee.delegatedFrom.isNotEmpty()) {
            params.add("DELEGATED-FROM=${attendee.delegatedFrom.joinToString(",") { "\"mailto:$it\"" }}")
        }

        // RFC 6638 scheduling parameters
        attendee.sentBy?.let { params.add("SENT-BY=\"mailto:$it\"") }
        // A client MUST NOT echo SCHEDULE-AGENT / SCHEDULE-FORCE-SEND in a
        // scheduling message it sends; preserve them on resource-storage PUTs.
        if (!isSchedulingMessage) {
            attendee.scheduleAgent?.let { params.add("SCHEDULE-AGENT=${it.value}") }
            attendee.scheduleForceSend?.let { params.add("SCHEDULE-FORCE-SEND=${it.value}") }
        }
        // Note: SCHEDULE-STATUS is server-generated, typically not output on requests

        val paramStr = if (params.isNotEmpty()) ";${params.joinToString(";")}" else ""
        return "ATTENDEE$paramStr:${CalAddress.format(attendee.email)}"
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

        crlfLine("IMAGE;${params.joinToString(";")}:${image.uri}")
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

        crlfLine("CONFERENCE;${params.joinToString(";")}:${conference.uri}")
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

    private fun StringBuilder.appendVTodo(
        todo: ICalTodo,
        preserveDtstamp: Boolean = false,
        isSchedulingMessage: Boolean = false
    ) {
        crlfLine("BEGIN:VTODO")

        // Required properties
        crlfLine("UID:${todo.uid}")

        // DTSTAMP handling
        if (preserveDtstamp && todo.dtstamp != null) {
            crlfLine("DTSTAMP:${todo.dtstamp.toICalString()}")
        } else {
            crlfLine("DTSTAMP:${formatDtStamp()}")
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
            crlfLine("COMPLETED:${completed.toICalString()}")
        }

        // RECURRENCE-ID for modified instances
        todo.recurrenceId?.let { recid ->
            appendDateTimeProperty("RECURRENCE-ID", recid)
        }

        // RRULE (only for master todos, NOT modified instances)
        if (todo.recurrenceId == null) {
            todo.rrule?.let { rrule ->
                crlfLine("RRULE:${rrule.toICalString()}")
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
        crlfLine("STATUS:${todo.status.toICalString()}")

        // Sequence
        crlfLine("SEQUENCE:${todo.sequence}")

        // Priority
        if (todo.priority != 0) {
            crlfLine("PRIORITY:${todo.priority}")
        }

        // Percent complete
        if (todo.percentComplete != 0) {
            crlfLine("PERCENT-COMPLETE:${todo.percentComplete}")
        }

        // Categories
        if (todo.categories.isNotEmpty()) {
            crlfLine("CATEGORIES:${todo.categories.joinToString(",") { escapeICalText(it) }}")
        }

        // URL
        todo.url?.let {
            crlfLine("URL:$it")
        }

        // GEO
        todo.geo?.let {
            crlfLine("GEO:$it")
        }

        // CLASS
        todo.classification?.let {
            crlfLine("CLASS:$it")
        }

        // Organizer (for task assignment)
        todo.organizer?.let { org ->
            crlfLine(formatOrganizer(org, isSchedulingMessage))
        }

        // Attendees (assignees)
        todo.attendees.forEach { att ->
            crlfLine(formatAttendee(att, isSchedulingMessage))
        }

        // VALARMs
        todo.alarms.forEach { alarm ->
            appendVAlarm(alarm)
        }

        // Created/Last-Modified
        todo.created?.let {
            crlfLine("CREATED:${it.toICalString()}")
        }
        todo.lastModified?.let {
            crlfLine("LAST-MODIFIED:${it.toICalString()}")
        }

        // Raw properties for round-trip
        todo.rawProperties.forEach { (key, value) ->
            appendFoldedLine("$key:$value")
        }

        crlfLine("END:VTODO")
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

    private fun StringBuilder.appendVJournal(
        journal: ICalJournal,
        preserveDtstamp: Boolean = false,
        isSchedulingMessage: Boolean = false
    ) {
        crlfLine("BEGIN:VJOURNAL")

        // Required properties
        crlfLine("UID:${journal.uid}")

        // DTSTAMP handling
        if (preserveDtstamp && journal.dtstamp != null) {
            crlfLine("DTSTAMP:${journal.dtstamp.toICalString()}")
        } else {
            crlfLine("DTSTAMP:${formatDtStamp()}")
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
                crlfLine("RRULE:${rrule.toICalString()}")
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
        crlfLine("STATUS:${journal.status.toICalString()}")

        // Sequence
        crlfLine("SEQUENCE:${journal.sequence}")

        // Categories
        if (journal.categories.isNotEmpty()) {
            crlfLine("CATEGORIES:${journal.categories.joinToString(",") { escapeICalText(it) }}")
        }

        // Attachments
        journal.attachments.forEach { attach ->
            crlfLine("ATTACH:$attach")
        }

        // URL
        journal.url?.let {
            crlfLine("URL:$it")
        }

        // CLASS
        journal.classification?.let {
            crlfLine("CLASS:$it")
        }

        // Organizer
        journal.organizer?.let { org ->
            crlfLine(formatOrganizer(org, isSchedulingMessage))
        }

        // Attendees
        journal.attendees.forEach { att ->
            crlfLine(formatAttendee(att, isSchedulingMessage))
        }

        // Created/Last-Modified
        journal.created?.let {
            crlfLine("CREATED:${it.toICalString()}")
        }
        journal.lastModified?.let {
            crlfLine("LAST-MODIFIED:${it.toICalString()}")
        }

        // Raw properties for round-trip
        journal.rawProperties.forEach { (key, value) ->
            appendFoldedLine("$key:$value")
        }

        crlfLine("END:VJOURNAL")
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
                crlfLine("BEGIN:VCALENDAR")
                crlfLine("VERSION:2.0")
                crlfLine("PRODID:-//iCalDAV//EN")
                crlfLine("METHOD:REQUEST")
                crlfLine("BEGIN:VFREEBUSY")
                crlfLine("UID:$uid")
                crlfLine("DTSTAMP:${ICalDateTime.now().toICalString()}")
                crlfLine("DTSTART:${dtstart.toICalString()}")
                crlfLine("DTEND:${dtend.toICalString()}")

                // Organizer
                val orgParams = mutableListOf<String>()
                organizer.name?.let { orgParams.add("CN=$it") }
                organizer.sentBy?.let { orgParams.add("SENT-BY=\"mailto:$it\"") }
                val orgParamStr = if (orgParams.isNotEmpty()) ";${orgParams.joinToString(";")}" else ""
                crlfLine("ORGANIZER$orgParamStr:${CalAddress.format(organizer.email)}")

                // Attendees
                attendees.forEach { att ->
                    val attParams = mutableListOf<String>()
                    att.name?.let { attParams.add("CN=$it") }
                    attParams.add("PARTSTAT=${att.partStat.toICalString()}")
                    val attParamStr = if (attParams.isNotEmpty()) ";${attParams.joinToString(";")}" else ""
                    crlfLine("ATTENDEE$attParamStr:${CalAddress.format(att.email)}")
                }

                crlfLine("END:VFREEBUSY")
                crlfLine("END:VCALENDAR")
            }
        }
    }
}