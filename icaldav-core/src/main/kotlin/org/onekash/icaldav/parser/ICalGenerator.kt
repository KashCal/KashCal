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

// RFC 5545 §3.1 delimits content lines with CRLF. Kotlin's StringBuilder.appendLine uses
// System.lineSeparator() (LF on Linux and Android), so every emitted iCalendar line goes through
// these helpers instead.
internal fun StringBuilder.crlfLine(line: String): StringBuilder = append(line).append("\r\n")
internal fun StringBuilder.crlfLine(): StringBuilder = append("\r\n")

/**
 * Generates RFC 5545 iCalendar text.
 *
 * The instance generators always write CALSCALE, and STATUS and SEQUENCE on every component except
 * a REFRESH VEVENT: iCloud answers HTTP 400 when CALSCALE, STATUS or SEQUENCE is missing. METHOD is
 * written only when the caller passes one, since RFC 4791 §4.1 forbids it in a stored calendar
 * object resource.
 *
 * VTIMEZONE generation is on by default, for clients that don't recognize IANA timezone IDs.
 */
class ICalGenerator(
    private val prodId: String = "-//iCalDAV//EN",
    /**
     * Adds the iCloud VALARM extensions: X-WR-ALARMUID on every alarm, and
     * X-APPLE-DEFAULT-ALARM:FALSE on an alarm that isn't a default alarm.
     */
    private val includeAppleExtensions: Boolean = true
) {
    private val vtimezoneGenerator = VTimezoneGenerator()

    /**
     * Generates a VCALENDAR holding one event.
     *
     * @param includeMethod writes METHOD:PUBLISH. Pass false for a CalDAV PUT: some servers, like
     *   Nextcloud, reject METHOD on a PUT.
     * @param includeVTimezone writes a VTIMEZONE for each referenced timezone
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
     * Generates a VCALENDAR holding one event, with an optional iTIP method.
     *
     * @param method the iTIP method, or null for no METHOD line (a stored CalDAV resource)
     * @param preserveDtstamp writes the event's DTSTAMP when it has one; otherwise DTSTAMP is now
     * @param includeVTimezone writes a VTIMEZONE for each referenced timezone
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
     * Generates one VCALENDAR holding all [events].
     *
     * @param includeMethod writes METHOD:PUBLISH
     * @param includeVTimezone writes one VTIMEZONE per timezone referenced by any of the events
     */
    fun generateBatch(
        events: List<ICalEvent>,
        includeMethod: Boolean = true,
        includeVTimezone: Boolean = true
    ): String = generate(
        calendar = ICalCalendar(
            prodId = null, // falls back to instance prodId
            method = if (includeMethod) "PUBLISH" else null,
            events = events
        ),
        includeVTimezone = includeVTimezone
    )

    /**
     * Generates a full VCALENDAR: the calendar-level metadata and mixed VEVENT, VTODO and
     * VJOURNAL components. The counterpart to [ICalParser.parse].
     *
     * PRODID is [ICalCalendar.prodId] when non-null, else this generator's prodId.
     *
     * VTIMEZONE collection scans dtStart/dtEnd/recurrenceId/exdates/rdates on events,
     * dtStart/due/completed/recurrenceId on todos, and dtStart/recurrenceId on journals, and skips
     * dates, UTC and floating times, and the UTC aliases UTC, Z, Etc/UTC and GMT. Each TZID gets
     * one VTIMEZONE across all component types (RFC 5545 §3.6.5), written before the first
     * component.
     *
     * Emission order: VERSION, PRODID, CALSCALE, METHOD, NAME, SOURCE, COLOR, REFRESH-INTERVAL,
     * X-WR-CALNAME, X-APPLE-CALENDAR-COLOR, IMAGE, VTIMEZONEs, VEVENTs, VTODOs, VJOURNALs,
     * END:VCALENDAR.
     *
     * @param preserveDtstamp writes each component's DTSTAMP when it has one; otherwise DTSTAMP
     *   is now
     * @param includeVTimezone writes a VTIMEZONE for each referenced timezone
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

            // RFC 6638 §7.1/§7.2: a METHOD line makes this a scheduling message, so
            // SCHEDULE-AGENT and SCHEDULE-FORCE-SEND are stripped from ORGANIZER and ATTENDEE.
            // Storage PUTs (no METHOD) keep them. Keyed on the raw METHOD string, not the
            // parsed enum, so an unrecognized or extension METHOD still counts.
            val isSchedulingMessage = calendar.method != null
            // The parsed iTIP method drives the RFC 5546 §3.2 per-method constraints that
            // VEVENTs apply: the minimal REFRESH set and VALARM presence. Null for storage
            // PUTs and for unrecognized METHOD strings.
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

        crlfLine("UID:${event.uid}")

        // The event's own DTSTAMP when the caller asks to preserve it and one exists, else now
        if (preserveDtstamp && event.dtstamp != null) {
            crlfLine("DTSTAMP:${event.dtstamp.toICalString()}")
        } else {
            crlfLine("DTSTAMP:${formatDtStamp()}")
        }

        // RFC 5546 §3.2.6: a METHOD:REFRESH VEVENT is an attendee's minimal request for the
        // latest version. It carries UID, DTSTAMP, ORGANIZER, the requesting ATTENDEE and,
        // for an occurrence, RECURRENCE-ID; DTSTART, STATUS, SEQUENCE, VALARM and most other
        // properties have presence 0. The path below writes ICalEvent's non-null DTSTART,
        // STATUS and SEQUENCE unconditionally, so REFRESH returns early here.
        if (itipMethod == ITipMethod.REFRESH) {
            event.recurrenceId?.let { recid -> appendDateTimeProperty("RECURRENCE-ID", recid) }
            event.organizer?.let { org -> crlfLine(formatOrganizer(org, isSchedulingMessage)) }
            event.attendees.forEach { att -> crlfLine(formatAttendee(att, isSchedulingMessage)) }
            crlfLine("END:VEVENT")
            return
        }

        appendDateTimeProperty("DTSTART", event.dtStart)

        // DTEND when set, else DURATION
        event.dtEnd?.let { dtend ->
            appendDateTimeProperty("DTEND", dtend)
        } ?: event.duration?.let { dur ->
            crlfLine("DURATION:${ICalAlarm.formatDuration(dur)}")
        }

        // RECURRENCE-ID for an exception
        event.recurrenceId?.let { recid ->
            appendDateTimeProperty("RECURRENCE-ID", recid)
        }

        // RRULE only on the master, never on an exception
        if (event.recurrenceId == null) {
            event.rrule?.let { rrule ->
                crlfLine("RRULE:${rrule.toICalString()}")
            }
        }

        event.exdates.forEach { exdate ->
            appendDateTimeProperty("EXDATE", exdate)
        }

        // RFC 5545 §3.8.5.2
        event.rdates.forEach { rdate ->
            appendDateTimeProperty("RDATE", rdate)
        }

        event.summary?.let {
            appendFoldedLine("SUMMARY:${escapeICalText(it)}")
        }

        event.description?.let {
            appendFoldedLine("DESCRIPTION:${escapeICalText(it)}")
        }

        event.location?.let {
            appendFoldedLine("LOCATION:${escapeICalText(it)}")
        }

        // Required by iCloud
        crlfLine("STATUS:${event.status.toICalString()}")

        // Required by iCloud; increment on updates
        crlfLine("SEQUENCE:${event.sequence}")

        // RFC 5545 §3.8.1.9: 0 means undefined, so only a positive priority is written
        if (event.priority > 0) {
            crlfLine("PRIORITY:${event.priority}")
        }

        // RFC 5545 §3.8.2.7: OPAQUE is the default, so only TRANSPARENT is written
        if (event.transparency != Transparency.OPAQUE) {
            crlfLine("TRANSP:${event.transparency.toICalString()}")
        }

        if (event.categories.isNotEmpty()) {
            crlfLine("CATEGORIES:${event.categories.joinToString(",") { escapeICalText(it) }}")
        }

        // RFC 7986
        event.color?.let {
            crlfLine("COLOR:$it")
        }

        // RFC 7986
        event.images.forEach { image ->
            appendImageProperty(image)
        }

        // RFC 7986
        event.conferences.forEach { conference ->
            appendConferenceProperty(conference)
        }

        // RFC 9253
        event.links.forEach { link ->
            crlfLine(link.toICalString())
        }

        // RFC 9253
        event.relations.forEach { relation ->
            crlfLine(relation.toICalString())
        }

        event.url?.let {
            crlfLine("URL:$it")
        }

        // RFC 5545 §3.8.1.6: "latitude;longitude"
        event.geo?.let {
            crlfLine("GEO:$it")
        }

        // RFC 5545 §3.8.1.3
        event.classification?.let {
            crlfLine("CLASS:${it.toICalString()}")
        }

        event.organizer?.let { org ->
            crlfLine(formatOrganizer(org, isSchedulingMessage))
        }

        event.attendees.forEach { att ->
            crlfLine(formatAttendee(att, isSchedulingMessage))
        }

        // RFC 5546 §3.2.3 (REPLY) and §3.2.5 (CANCEL) set VALARM presence to 0, so alarms are
        // skipped there and the organizer's reminders don't leak into a reply or cancellation.
        // PUBLISH, REQUEST, ADD and COUNTER permit them (0+). §3.2.8 DECLINECOUNTER also sets
        // presence 0 but still gets alarms here.
        if (itipMethod != ITipMethod.REPLY && itipMethod != ITipMethod.CANCEL) {
            event.alarms.forEach { alarm ->
                appendVAlarm(alarm)
            }
        }

        event.created?.let {
            crlfLine("CREATED:${it.toICalString()}")
        }
        event.lastModified?.let {
            crlfLine("LAST-MODIFIED:${it.toICalString()}")
        }

        appendUnknownProperties(event.unknownPropertyLines, event.rawProperties)

        crlfLine("END:VEVENT")
    }

    private fun StringBuilder.appendVAlarm(alarm: ICalAlarm) {
        crlfLine("BEGIN:VALARM")

        // RFC 9074 §4 alarm UID, generated when the alarm has none. X-WR-ALARMUID repeats it.
        val alarmUid = alarm.uid ?: java.util.UUID.randomUUID().toString().uppercase()
        crlfLine("UID:$alarmUid")

        if (includeAppleExtensions) {
            // iCloud's alarm identifier, the same value as UID
            crlfLine("X-WR-ALARMUID:$alarmUid")
            // Stops iPhone treating this as a default alarm that can be merged with the
            // calendar's defaults
            if (!alarm.defaultAlarm) {
                crlfLine("X-APPLE-DEFAULT-ALARM:FALSE")
            }
        }

        crlfLine("ACTION:${alarm.action.name}")

        // A relative TRIGGER when set, else an absolute one
        alarm.trigger?.let { dur ->
            val related = if (alarm.triggerRelatedToEnd) ";RELATED=END" else ""
            crlfLine("TRIGGER${related}:${ICalAlarm.formatDuration(dur)}")
        } ?: alarm.triggerAbsolute?.let { dt ->
            crlfLine("TRIGGER;VALUE=DATE-TIME:${dt.toICalString()}")
        }

        // RFC 5545 §3.6.6: DISPLAY (text to show) and EMAIL (message body) require
        // DESCRIPTION, so it defaults to "Reminder". AUDIO and NONE get none.
        if (alarm.action == AlarmAction.DISPLAY || alarm.action == AlarmAction.EMAIL) {
            crlfLine("DESCRIPTION:${escapeICalText(alarm.description ?: "Reminder")}")
        }

        // RFC 5545 §3.6.6: EMAIL requires SUMMARY (the subject). Written whenever set, and
        // defaulted for EMAIL so it is never absent.
        (alarm.summary ?: if (alarm.action == AlarmAction.EMAIL) "Reminder" else null)?.let {
            crlfLine("SUMMARY:${escapeICalText(it)}")
        }

        // DURATION is written only with a positive REPEAT
        if (alarm.repeatCount > 0) {
            crlfLine("REPEAT:${alarm.repeatCount}")
            alarm.repeatDuration?.let { dur ->
                crlfLine("DURATION:${ICalAlarm.formatDuration(dur)}")
            }
        }

        // RFC 9074 §6.1
        alarm.acknowledged?.let {
            crlfLine("ACKNOWLEDGED:${it.toICalString()}")
        }

        // RFC 9074 §5
        alarm.relatedTo?.let {
            crlfLine("RELATED-TO:${escapeICalText(it)}")
        }

        // DEFAULT-ALARM isn't defined in RFC 9074
        if (alarm.defaultAlarm) {
            crlfLine("DEFAULT-ALARM:TRUE")
        }

        // RFC 9074 §8.1
        alarm.proximity?.let {
            crlfLine("PROXIMITY:${it.toICalString()}")
        }

        crlfLine("END:VALARM")
    }

    /**
     * Appends a date-time property as VALUE=DATE for a date, bare for UTC or floating time, and
     * with TZID otherwise.
     */
    private fun StringBuilder.appendDateTimeProperty(name: String, dt: ICalDateTime) {
        if (dt.isDate) {
            // All-day
            crlfLine("$name;VALUE=DATE:${dt.toICalString()}")
        } else if (dt.isUtc) {
            crlfLine("$name:${dt.toICalString()}")
        } else if (dt.timezone != null) {
            val tzid = dt.timezone.id
            crlfLine("$name;TZID=$tzid:${dt.toICalString()}")
        } else {
            // Floating: no timezone
            crlfLine("$name:${dt.toICalString()}")
        }
    }

    /**
     * Appends a line, folded when it exceeds 75 octets.
     *
     * RFC 5545 §3.1: lines SHOULD NOT be longer than 75 octets, excluding the line break. The
     * limit counts UTF-8 bytes, not characters (one character is 1 to 4 bytes), and a fold never
     * splits a code point, so a surrogate pair such as an emoji stays whole.
     */
    private fun StringBuilder.appendFoldedLine(line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8)

        if (bytes.size <= 75) {
            crlfLine(line)
            return
        }

        // Code points, not chars, so a surrogate pair is never split
        val codePoints = line.codePoints().toArray()
        var cpIndex = 0
        var isFirst = true

        while (cpIndex < codePoints.size) {
            val maxBytes = if (isFirst) 75 else 74  // 74 plus a continuation's leading space

            // Take as many code points as fit in maxBytes
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

            // Take at least one code point even if it alone exceeds maxBytes
            if (cpIndex == startCpIndex && cpIndex < codePoints.size) {
                cpIndex++
            }

            if (!isFirst) {
                append(" ")  // RFC 5545: continuation lines start with space or tab
            }

            val segment = codePoints.sliceArray(startCpIndex until cpIndex)
                .map { Character.toString(it) }
                .joinToString("")
            append(segment)
            crlfLine()

            isFirst = false
        }
    }

    /**
     * Writes the properties the model doesn't cover. Original lines from the parser are written
     * unchanged (RFC 5545 §3.2.20), only folded. Components built without them, such as locally
     * created ones, fall back to the name-value map, whose key may carry parameters
     * ("X-APPLE-STRUCTURED-LOCATION;VALUE=URI").
     */
    private fun StringBuilder.appendUnknownProperties(lines: List<String>, map: Map<String, String>) {
        if (lines.isNotEmpty()) {
            lines.forEach { appendFoldedLine(it) }
        } else {
            map.forEach { (key, value) -> appendFoldedLine("$key:$value") }
        }
    }

    /** Formats the current time as a UTC DTSTAMP value. */
    private fun formatDtStamp(): String {
        val now = Instant.now().atZone(ZoneOffset.UTC)
        return DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(now)
    }

    /** Escapes a TEXT value per RFC 5545 §3.3.11. */
    private fun escapeICalText(text: String): String {
        return text
            // CRLF and a lone CR become one LF first: a bare CR is a control character
            // excluded from VALUE-CHAR (§3.1), and CRLF must not become two line breaks.
            // Done before backslash-escaping so the LF is escaped to \n like any other.
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace(",", "\\,")
            .replace(";", "\\;")
    }

    /**
     * Quotes a parameter value that contains ":", ";" or ",". A DQUOTE inside the value is left
     * as is.
     */
    private fun escapeParamValue(value: String): String {
        return if (value.contains(":") || value.contains(";") || value.contains(",")) {
            "\"$value\""
        } else {
            value
        }
    }

    /**
     * Formats an ORGANIZER line with its RFC 6638 scheduling parameters.
     *
     * SCHEDULE-STATUS is never written: the server sets it, and RFC 6638 §7.3 forbids a client
     * from including it in a scheduling message it sends.
     *
     * @param isSchedulingMessage true for a METHOD-bearing iTIP message. RFC 6638 §7.1/§7.2 forbid
     *   a client from including SCHEDULE-AGENT or SCHEDULE-FORCE-SEND in scheduling messages it
     *   sends, so they are dropped then and kept on resource-storage PUTs.
     */
    private fun formatOrganizer(organizer: Organizer, isSchedulingMessage: Boolean): String {
        val params = mutableListOf<String>()

        organizer.name?.let { params.add("CN=${escapeParamValue(it)}") }
        organizer.sentBy?.let { params.add("SENT-BY=\"mailto:$it\"") }

        if (!isSchedulingMessage) {
            organizer.scheduleAgent?.let { params.add("SCHEDULE-AGENT=${it.value}") }
            organizer.scheduleForceSend?.let { params.add("SCHEDULE-FORCE-SEND=${it.value}") }
        }

        val paramStr = if (params.isNotEmpty()) ";${params.joinToString(";")}" else ""
        return "ORGANIZER$paramStr:${CalAddress.format(organizer.email)}"
    }

    /**
     * Formats an ATTENDEE line with its RFC 5545 and RFC 6638 parameters, SCHEDULE-STATUS
     * excluded as in [formatOrganizer].
     *
     * @param isSchedulingMessage drops SCHEDULE-AGENT and SCHEDULE-FORCE-SEND, as in
     *   [formatOrganizer]
     */
    private fun formatAttendee(attendee: Attendee, isSchedulingMessage: Boolean): String {
        val params = mutableListOf<String>()

        attendee.name?.let { params.add("CN=${escapeParamValue(it)}") }

        // Only when not the default, INDIVIDUAL
        if (attendee.cutype != CUType.INDIVIDUAL) {
            params.add("CUTYPE=${attendee.cutype.toICalString()}")
        }

        // Only when not the default, REQ-PARTICIPANT
        if (attendee.role != AttendeeRole.REQ_PARTICIPANT) {
            params.add("ROLE=${attendee.role.toICalString()}")
        }

        params.add("PARTSTAT=${attendee.partStat.toICalString()}")

        // RFC 5545 §3.2.17: the default is FALSE, so null and false both omit RSVP
        if (attendee.rsvp == true) params.add("RSVP=TRUE")

        attendee.dir?.let { params.add("DIR=\"$it\"") }
        // RFC 5545 §3.2.11: MEMBER is multi-value. The parser stores addresses without
        // "mailto:", as for DELEGATED-TO and DELEGATED-FROM, so the prefix is added back here.
        if (attendee.member.isNotEmpty()) {
            params.add("MEMBER=" + attendee.member.joinToString(",") { "\"mailto:$it\"" })
        }

        if (attendee.delegatedTo.isNotEmpty()) {
            params.add("DELEGATED-TO=${attendee.delegatedTo.joinToString(",") { "\"mailto:$it\"" }}")
        }
        if (attendee.delegatedFrom.isNotEmpty()) {
            params.add("DELEGATED-FROM=${attendee.delegatedFrom.joinToString(",") { "\"mailto:$it\"" }}")
        }

        attendee.sentBy?.let { params.add("SENT-BY=\"mailto:$it\"") }
        if (!isSchedulingMessage) {
            attendee.scheduleAgent?.let { params.add("SCHEDULE-AGENT=${it.value}") }
            attendee.scheduleForceSend?.let { params.add("SCHEDULE-FORCE-SEND=${it.value}") }
        }

        val paramStr = if (params.isNotEmpty()) ";${params.joinToString(";")}" else ""
        return "ATTENDEE$paramStr:${CalAddress.format(attendee.email)}"
    }

    // ============ RFC 7986 Property Generation ============

    /**
     * Appends an RFC 7986 IMAGE property. DISPLAY is omitted when it is GRAPHIC.
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
     * Appends an RFC 7986 CONFERENCE property.
     *
     * Format: CONFERENCE;VALUE=URI;FEATURE=VIDEO,AUDIO;LABEL=Join:https://example.com/j/123
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
     * Generates a VCALENDAR holding one VTODO, with an optional iTIP method.
     *
     * @param method the iTIP method, or null for no METHOD line (a stored CalDAV resource)
     * @param preserveDtstamp writes the todo's DTSTAMP when it has one; otherwise DTSTAMP is now
     * @param includeVTimezone writes a VTIMEZONE for each referenced timezone
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

        crlfLine("UID:${todo.uid}")

        if (preserveDtstamp && todo.dtstamp != null) {
            crlfLine("DTSTAMP:${todo.dtstamp.toICalString()}")
        } else {
            crlfLine("DTSTAMP:${formatDtStamp()}")
        }

        todo.dtStart?.let { dt ->
            appendDateTimeProperty("DTSTART", dt)
        }

        todo.due?.let { due ->
            appendDateTimeProperty("DUE", due)
        }

        todo.completed?.let { completed ->
            // RFC 5545 §3.8.2.1: COMPLETED must be a UTC date-time
            crlfLine("COMPLETED:${completed.toICalString()}")
        }

        // RECURRENCE-ID for an exception
        todo.recurrenceId?.let { recid ->
            appendDateTimeProperty("RECURRENCE-ID", recid)
        }

        // RRULE only on the master, never on an exception
        if (todo.recurrenceId == null) {
            todo.rrule?.let { rrule ->
                crlfLine("RRULE:${rrule.toICalString()}")
            }
        }

        todo.summary?.let {
            appendFoldedLine("SUMMARY:${escapeICalText(it)}")
        }

        todo.description?.let {
            appendFoldedLine("DESCRIPTION:${escapeICalText(it)}")
        }

        todo.location?.let {
            appendFoldedLine("LOCATION:${escapeICalText(it)}")
        }

        crlfLine("STATUS:${todo.status.toICalString()}")

        crlfLine("SEQUENCE:${todo.sequence}")

        if (todo.priority != 0) {
            crlfLine("PRIORITY:${todo.priority}")
        }

        if (todo.percentComplete != 0) {
            crlfLine("PERCENT-COMPLETE:${todo.percentComplete}")
        }

        if (todo.categories.isNotEmpty()) {
            crlfLine("CATEGORIES:${todo.categories.joinToString(",") { escapeICalText(it) }}")
        }

        todo.url?.let {
            crlfLine("URL:$it")
        }

        todo.geo?.let {
            crlfLine("GEO:$it")
        }

        todo.classification?.let {
            crlfLine("CLASS:$it")
        }

        // For task assignment
        todo.organizer?.let { org ->
            crlfLine(formatOrganizer(org, isSchedulingMessage))
        }

        // Assignees
        todo.attendees.forEach { att ->
            crlfLine(formatAttendee(att, isSchedulingMessage))
        }

        // Unlike VEVENT, no per-method VALARM rule applies
        todo.alarms.forEach { alarm ->
            appendVAlarm(alarm)
        }

        todo.created?.let {
            crlfLine("CREATED:${it.toICalString()}")
        }
        todo.lastModified?.let {
            crlfLine("LAST-MODIFIED:${it.toICalString()}")
        }

        appendUnknownProperties(todo.unknownPropertyLines, todo.rawProperties)

        crlfLine("END:VTODO")
    }

    // ============ VJOURNAL Generation ============

    /**
     * Generates a VCALENDAR holding one VJOURNAL, with an optional iTIP method.
     *
     * @param method the iTIP method, or null for no METHOD line (a stored CalDAV resource)
     * @param preserveDtstamp writes the journal's DTSTAMP when it has one; otherwise DTSTAMP is
     *   now
     * @param includeVTimezone writes a VTIMEZONE for each referenced timezone
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

        crlfLine("UID:${journal.uid}")

        if (preserveDtstamp && journal.dtstamp != null) {
            crlfLine("DTSTAMP:${journal.dtstamp.toICalString()}")
        } else {
            crlfLine("DTSTAMP:${formatDtStamp()}")
        }

        journal.dtStart?.let { dt ->
            appendDateTimeProperty("DTSTART", dt)
        }

        // RECURRENCE-ID for an exception
        journal.recurrenceId?.let { recid ->
            appendDateTimeProperty("RECURRENCE-ID", recid)
        }

        // RRULE only on the master, never on an exception
        if (journal.recurrenceId == null) {
            journal.rrule?.let { rrule ->
                crlfLine("RRULE:${rrule.toICalString()}")
            }
        }

        journal.summary?.let {
            appendFoldedLine("SUMMARY:${escapeICalText(it)}")
        }

        journal.description?.let {
            appendFoldedLine("DESCRIPTION:${escapeICalText(it)}")
        }

        crlfLine("STATUS:${journal.status.toICalString()}")

        crlfLine("SEQUENCE:${journal.sequence}")

        if (journal.categories.isNotEmpty()) {
            crlfLine("CATEGORIES:${journal.categories.joinToString(",") { escapeICalText(it) }}")
        }

        journal.attachments.forEach { attach ->
            crlfLine("ATTACH:$attach")
        }

        journal.url?.let {
            crlfLine("URL:$it")
        }

        journal.classification?.let {
            crlfLine("CLASS:$it")
        }

        journal.organizer?.let { org ->
            crlfLine(formatOrganizer(org, isSchedulingMessage))
        }

        journal.attendees.forEach { att ->
            crlfLine(formatAttendee(att, isSchedulingMessage))
        }

        journal.created?.let {
            crlfLine("CREATED:${it.toICalString()}")
        }
        journal.lastModified?.let {
            crlfLine("LAST-MODIFIED:${it.toICalString()}")
        }

        appendUnknownProperties(journal.unknownPropertyLines, journal.rawProperties)

        crlfLine("END:VJOURNAL")
    }

    companion object {
        /**
         * Generates a METHOD:REQUEST VFREEBUSY asking [attendees] for their free/busy time
         * between [dtstart] and [dtend].
         *
         * Always uses PRODID "-//iCalDAV//EN", whatever an instance is configured with, and
         * writes no CALSCALE. CN is written unquoted and unescaped.
         *
         * @param organizer the calendar user asking
         * @param uid a random uppercase UUID unless given
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

                val orgParams = mutableListOf<String>()
                organizer.name?.let { orgParams.add("CN=$it") }
                organizer.sentBy?.let { orgParams.add("SENT-BY=\"mailto:$it\"") }
                val orgParamStr = if (orgParams.isNotEmpty()) ";${orgParams.joinToString(";")}" else ""
                crlfLine("ORGANIZER$orgParamStr:${CalAddress.format(organizer.email)}")

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