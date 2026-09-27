package org.onekash.icaldav.parser

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.data.CalendarParserFactory
import net.fortuna.ical4j.data.ContentHandlerContext
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.TimeZoneRegistry
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VFreeBusy
import net.fortuna.ical4j.model.component.VJournal
import net.fortuna.ical4j.model.component.VToDo
import org.onekash.icaldav.compat.getAllProperties
import org.onekash.icaldav.compat.getParameterIgnoreCase
import org.onekash.icaldav.compat.getParameterOrNull
import org.onekash.icaldav.compat.getPropertyOrNull
import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.model.AlarmProximity
import org.onekash.icaldav.model.Attendee
import org.onekash.icaldav.model.AttendeeRole
import org.onekash.icaldav.model.CUType
import org.onekash.icaldav.model.Classification
import org.onekash.icaldav.model.ConferenceFeature
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.FreeBusyPeriod
import org.onekash.icaldav.model.FreeBusyType
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalCalendar
import org.onekash.icaldav.model.ICalConference
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ICalFreeBusy
import org.onekash.icaldav.model.ICalImage
import org.onekash.icaldav.model.ICalJournal
import org.onekash.icaldav.model.ICalLink
import org.onekash.icaldav.model.ICalRelation
import org.onekash.icaldav.model.ICalTodo
import org.onekash.icaldav.model.ITipMethod
import org.onekash.icaldav.model.ImageDisplay
import org.onekash.icaldav.model.JournalStatus
import org.onekash.icaldav.model.Organizer
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.model.PartStat
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.model.ScheduleAgent
import org.onekash.icaldav.model.ScheduleForceSend
import org.onekash.icaldav.model.ScheduleStatus
import org.onekash.icaldav.model.TodoStatus
import org.onekash.icaldav.model.Transparency
import org.onekash.icaldav.parser.ICalParser.Companion.createWithFullRegistry
import java.io.StringReader
import java.time.Duration
import java.util.UUID

/**
 * Parses iCalendar text into the icaldav model types, on top of ical4j.
 *
 * Each VEVENT with a RECURRENCE-ID, which ical4j reads but doesn't group with its
 * master, becomes its own [ICalEvent], and every component gets an importId from its
 * UID and RECURRENCE-ID for database storage. In production use against iCloud and
 * other CalDAV servers.
 *
 * Thread-safe: ical4j's JVM-global configuration runs once, under double-checked
 * locking, when the first parser is created ([ensureConfigured]).
 *
 * ## TimeZoneRegistry
 *
 * The no-argument constructor uses [SimpleTimeZoneRegistry], which is Android-safe.
 * JVM servers that need richer timezone handling use [createWithFullRegistry].
 *
 * ```kotlin
 * // Android (default - safe)
 * val parser = ICalParser()
 *
 * // JVM Server (full features)
 * val parser = ICalParser.createWithFullRegistry()
 *
 * // Custom registry (advanced)
 * val parser = ICalParser(customRegistry)
 * ```
 *
 * @param registry TimeZoneRegistry to use for timezone resolution.
 */
class ICalParser(
    private val registry: TimeZoneRegistry
) {

    /**
     * Creates a parser with the Android-safe [SimpleTimeZoneRegistry], the choice for most
     * callers and for Android. That registry resolves IDs with ZoneId.of, supported on Android
     * via desugaring, relies on the data's embedded VTIMEZONE definitions, and has no external
     * dependencies.
     */
    constructor() : this(SimpleTimeZoneRegistry())

    init {
        // ical4j must be configured before any parse
        ensureConfigured()
    }

    companion object {
        @Volatile
        private var configured = false
        private val configLock = Any()

        /**
         * Matches an uppercase newline escape `\N` whose leading backslash is
         * unescaped: an even-length run of `\\` pairs (captured, re-emitted)
         * followed by `\N`. `(?<!\\)` anchors the run to a clean boundary so a
         * `\\N` (escaped backslash + literal N) is never matched.
         */
        private val UPPERCASE_NEWLINE_ESCAPE = Regex("""(?<!\\)((?:\\\\)*)\\N""")

        /** Captures the body of each embedded VTIMEZONE, for [rewriteUnresolvableVTimezones]. */
        private val VTIMEZONE_BLOCK =
            Regex("""BEGIN:VTIMEZONE\r?\n(.*?)END:VTIMEZONE""", RegexOption.DOT_MATCHES_ALL)

        /** The TZID a VTIMEZONE declares, for example `TZID:TZsfv`. */
        private val VTIMEZONE_TZID =
            Regex("""^TZID:(.+)$""", RegexOption.MULTILINE)

        /** The X-LIC-LOCATION hint naming the intended IANA zone. */
        private val VTIMEZONE_XLIC =
            Regex("""^X-LIC-LOCATION:(.+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

        /**
         * Creates a parser with ical4j's `TimeZoneRegistryImpl`, for JVM servers where
         * ZoneRulesProvider is available and that need pre-built TimeZone objects from the
         * registry or the complete Windows timezone name mapping.
         *
         * Never use it on Android: it crashes at runtime because ZoneRulesProvider isn't
         * available via desugaring.
         */
        fun createWithFullRegistry(): ICalParser {
            return ICalParser(net.fortuna.ical4j.model.TimeZoneRegistryImpl())
        }

        /** Configures ical4j once per process, under double-checked locking; safe to repeat. */
        fun ensureConfigured() {
            if (!configured) {
                synchronized(configLock) {
                    if (!configured) {
                        configureIcal4j()
                        configured = true
                    }
                }
            }
        }

        /**
         * Sets ical4j's system properties for Android and server use. They are JVM-global, so
         * call [ensureConfigured] at application startup if configuration must precede the
         * first ICalParser.
         */
        private fun configureIcal4j() {
            // Use MapTimeZoneCache (no file system cache, no network dependency)
            System.setProperty("net.fortuna.ical4j.timezone.cache.impl",
                "net.fortuna.ical4j.util.MapTimeZoneCache")

            // Disable timezone updates (requires network access)
            System.setProperty("net.fortuna.ical4j.timezone.update.enabled", "false")

            // Relaxed unfolding and parsing, for the malformed data servers send
            System.setProperty("ical4j.unfolding.relaxed", "true")
            System.setProperty("ical4j.parsing.relaxed", "true")

            // Relaxed validation: never drop an otherwise-usable VEVENT because of a
            // validation failure. The case that matters is a VTIMEZONE defined in the data
            // (RFC 5545 §3.2.19) whose TZID is a non-IANA name ical4j cannot resolve with
            // java.time.ZoneId.of when a date is read: strict validation makes the per-event
            // parse throw, which would silently drop every event in the feed. With this hint
            // the event is kept and its time falls back to the device zone (floating) when
            // the zone is unresolvable. rewriteUnresolvableVTimezones first maps the custom
            // zones it can to their IANA zone, so this only catches the rest.
            System.setProperty("ical4j.validation.relaxed", "true")
        }
    }

    /** Creates a CalendarBuilder over [registry], with invalid-property suppression on. */
    private fun createCalendarBuilder(): CalendarBuilder {
        return CalendarBuilder(
            CalendarParserFactory.getInstance().get(),
            ContentHandlerContext().withSuppressInvalidProperties(true),
            registry
        )
    }

    /**
     * Parses every VEVENT in [icalData]; a VEVENT that fails to parse is left out.
     *
     * This matters for iCloud sync: one .ics file may hold more than one VEVENT with the same UID
     * and different RECURRENCE-IDs (a master and its exceptions). Each becomes its own
     * [ICalEvent] with a unique importId.
     */
    fun parseAllEvents(icalData: String): ParseResult<List<ICalEvent>> {
        return try {
            val unfolded = prepareForParsing(icalData)
            val builder = createCalendarBuilder()
            val calendar = builder.build(StringReader(unfolded))

            ParseResult.success(parseEvents(unfolded, calendar))
        } catch (e: Exception) {
            ParseResult.error("Failed to parse iCalendar data: ${e.message}", e)
        }
    }

    private fun parseEvents(prepared: String, calendar: net.fortuna.ical4j.model.Calendar): List<ICalEvent> =
        parseWithLines<VEvent, ICalEvent>(prepared, calendar, Component.VEVENT, handledProperties,
            { parseVEvent(it).getOrNull() }) { event, lines -> event.copy(unknownPropertyLines = lines) }

    private fun parseTodos(prepared: String, calendar: net.fortuna.ical4j.model.Calendar): List<ICalTodo> =
        parseWithLines<VToDo, ICalTodo>(prepared, calendar, Component.VTODO, handledTodoProperties,
            { parseVTodo(it).getOrNull() }) { todo, lines -> todo.copy(unknownPropertyLines = lines) }

    private fun parseJournals(prepared: String, calendar: net.fortuna.ical4j.model.Calendar): List<ICalJournal> =
        parseWithLines<VJournal, ICalJournal>(prepared, calendar, Component.VJOURNAL, handledJournalProperties,
            { parseVJournal(it).getOrNull() }) { journal, lines -> journal.copy(unknownPropertyLines = lines) }

    /**
     * Parses every [name] component and attaches its unknown properties as
     * original lines. Lines are matched by position before failed components
     * are dropped, so a skipped component can't shift the lines onto its
     * neighbour.
     */
    private inline fun <C : net.fortuna.ical4j.model.component.CalendarComponent, T : Any> parseWithLines(
        prepared: String,
        calendar: net.fortuna.ical4j.model.Calendar,
        name: String,
        modelled: Set<String>,
        parse: (C) -> T?,
        attach: (T, List<String>) -> T
    ): List<T> {
        val components = calendar.getComponents<C>(name)
        if (components.isEmpty()) return emptyList()
        val lines = UnknownPropertyLines.alignedLines(
            UnknownPropertyLines.scan(prepared, name, modelled), components
        ) { it.getProperty<Property>(Property.UID).orElse(null)?.value }
        return components.mapIndexedNotNull { index, component ->
            val parsed = parse(component) ?: return@mapIndexedNotNull null
            lines?.get(index)?.takeIf { it.isNotEmpty() }?.let { attach(parsed, it) } ?: parsed
        }
    }

    /** Parses every VTODO in [icalData]; a VTODO that fails to parse is left out. */
    fun parseAllTodos(icalData: String): ParseResult<List<ICalTodo>> {
        return try {
            val unfolded = prepareForParsing(icalData)
            val builder = createCalendarBuilder()
            val calendar = builder.build(StringReader(unfolded))

            ParseResult.success(parseTodos(unfolded, calendar))
        } catch (e: Exception) {
            ParseResult.error("Failed to parse VTODO data: ${e.message}", e)
        }
    }

    /** Converts one VTODO to an [ICalTodo]. */
    fun parseVTodo(vtodo: VToDo): ParseResult<ICalTodo> {
        return try {
            // A missing or blank UID (non-compliant servers) gets a random UUID
            val uid = vtodo.getPropertyOrNull<Property>("UID")?.value?.ifBlank { null }
                ?: UUID.randomUUID().toString()

            // A RECURRENCE-ID marks an exception
            val recurrenceId = vtodo.getPropertyOrNull<Property>("RECURRENCE-ID")
                ?.let { parseDateTimeFromProperty(it) }

            val importId = ICalTodo.generateImportId(uid, recurrenceId)

            val dtStart = vtodo.getPropertyOrNull<Property>("DTSTART")
                ?.let { parseDateTimeFromProperty(it) }
            val due = vtodo.getPropertyOrNull<Property>("DUE")
                ?.let { parseDateTimeFromProperty(it) }
            val completed = vtodo.getPropertyOrNull<Property>("COMPLETED")
                ?.let { parseDateTimeFromProperty(it) }
            val dtstamp = vtodo.getPropertyOrNull<Property>("DTSTAMP")
                ?.let { parseDateTimeFromProperty(it) }
            val created = vtodo.getPropertyOrNull<Property>("CREATED")
                ?.let { parseDateTimeFromProperty(it) }
            val lastModified = vtodo.getPropertyOrNull<Property>("LAST-MODIFIED")
                ?.let { parseDateTimeFromProperty(it) }

            val summary = vtodo.getPropertyOrNull<Property>("SUMMARY")
                ?.value
            val description = vtodo.getPropertyOrNull<Property>("DESCRIPTION")
                ?.value
            val location = vtodo.getPropertyOrNull<Property>("LOCATION")
                ?.value
            val url = vtodo.getPropertyOrNull<Property>("URL")?.value
            val geo = vtodo.getPropertyOrNull<Property>("GEO")?.value
            val classification = vtodo.getPropertyOrNull<Property>("CLASS")?.value

            val statusValue = vtodo.getPropertyOrNull<Property>("STATUS")?.value
            val sequenceValue = vtodo.getPropertyOrNull<Property>("SEQUENCE")
                ?.value?.toIntOrNull() ?: 0
            val priority = vtodo.getPropertyOrNull<Property>("PRIORITY")
                ?.value?.toIntOrNull() ?: 0
            val percentComplete = vtodo.getPropertyOrNull<Property>("PERCENT-COMPLETE")
                ?.value?.toIntOrNull() ?: 0

            // Only a master's RRULE is read; an exception's is ignored
            val rrule = if (recurrenceId == null) {
                vtodo.getPropertyOrNull<Property>("RRULE")
                    ?.let { RRule.parse(it.value) }
            } else null

            val categoriesProps = vtodo.getProperties<Property>("CATEGORIES")
            val categories = categoriesProps.flatMap { cat ->
                // Blank elements are dropped, for the reason in parseVEvent
                cat.value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }

            val organizer = parseTodoOrganizer(vtodo)

            val attendees = parseTodoAttendees(vtodo)

            val alarms = vtodo.alarms.mapNotNull { valarm ->
                parseVAlarm(valarm).getOrNull()
            }

            val rawProperties = collectRawTodoProperties(vtodo)

            val todo = ICalTodo(
                uid = uid,
                importId = importId,
                summary = summary,
                description = description,
                due = due,
                percentComplete = percentComplete,
                status = TodoStatus.fromString(statusValue),
                priority = priority,
                dtStart = dtStart,
                completed = completed,
                sequence = sequenceValue,
                dtstamp = dtstamp,
                created = created,
                lastModified = lastModified,
                location = location,
                categories = categories,
                organizer = organizer,
                attendees = attendees,
                alarms = alarms,
                rrule = rrule,
                recurrenceId = recurrenceId,
                url = url,
                geo = geo,
                classification = classification,
                rawProperties = rawProperties
            )

            ParseResult.success(todo)
        } catch (e: Exception) {
            ParseResult.error("Failed to parse VTODO: ${e.message}", e)
        }
    }

    /** Reads the VTODO's ORGANIZER, without the RFC 6638 parameters [parseOrganizer] reads. */
    private fun parseTodoOrganizer(vtodo: VToDo): Organizer? {
        val organizerProp = vtodo.getPropertyOrNull<Property>("ORGANIZER")
            ?: return null

        val email = extractCalAddressEmail(organizerProp)

        val cn = organizerProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("CN")?.value
        val sentBy = organizerProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("SENT-BY")
            ?.value?.let { extractEmailFromCalAddress(it) }

        return Organizer(email = email, name = cn, sentBy = sentBy)
    }

    /** Reads the VTODO's ATTENDEEs (CN, PARTSTAT, ROLE, RSVP), skipping any without an address. */
    private fun parseTodoAttendees(vtodo: VToDo): List<Attendee> {
        val attendeeProps = vtodo.getProperties<Property>("ATTENDEE")

        return attendeeProps.mapNotNull { attendeeProp ->
            val email = extractCalAddressEmail(attendeeProp)
            if (email.isBlank()) return@mapNotNull null

            val cn = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("CN")?.value
            val partStatValue = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("PARTSTAT")?.value
            val roleValue = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("ROLE")?.value
            val rsvpValue = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("RSVP")?.value

            Attendee(
                email = email,
                name = cn,
                partStat = PartStat.fromString(partStatValue),
                role = AttendeeRole.fromString(roleValue),
                rsvp = rsvpValue?.equals("TRUE", ignoreCase = true)
            )
        }
    }

    /**
     * Properties parsed into [ICalTodo] fields, so left out of rawProperties and
     * unknownPropertyLines.
     */
    private val handledTodoProperties = setOf(
        "UID", "DTSTART", "DUE", "COMPLETED", "DTSTAMP",
        "RECURRENCE-ID", "RRULE",
        "SUMMARY", "DESCRIPTION", "LOCATION",
        "STATUS", "SEQUENCE", "PRIORITY", "PERCENT-COMPLETE",
        "ORGANIZER", "ATTENDEE",
        "LAST-MODIFIED", "CREATED",
        "CATEGORIES", "URL", "GEO", "CLASS"
    )

    /** Collects VTODO properties not in [handledTodoProperties], as [collectRawProperties] does. */
    private fun collectRawTodoProperties(vtodo: VToDo): Map<String, String> {
        val raw = mutableMapOf<String, String>()

        for (prop in vtodo.getAllProperties()) {
            val propName = prop.name?.uppercase() ?: continue
            if (propName in handledTodoProperties) continue
            if (propName == "BEGIN" || propName == "END") continue

            val paramList = prop.getParameters()
            val key = if (paramList.isEmpty()) {
                propName
            } else {
                val paramStr = paramList.joinToString(";") { param ->
                    "${param.name}=${param.value}"
                }
                "$propName;$paramStr"
            }

            val value = prop.value
            if (!value.isNullOrBlank()) {
                raw[key] = value
            }
        }

        return raw
    }

    // ============ VJOURNAL Parsing ============

    /** Parses every VJOURNAL in [icalData]; a VJOURNAL that fails to parse is left out. */
    fun parseAllJournals(icalData: String): ParseResult<List<ICalJournal>> {
        return try {
            val unfolded = prepareForParsing(icalData)
            val builder = createCalendarBuilder()
            val calendar = builder.build(StringReader(unfolded))

            ParseResult.success(parseJournals(unfolded, calendar))
        } catch (e: Exception) {
            ParseResult.error("Failed to parse VJOURNAL data: ${e.message}", e)
        }
    }

    /** Converts one VJOURNAL to an [ICalJournal]. */
    fun parseVJournal(vjournal: VJournal): ParseResult<ICalJournal> {
        return try {
            // A missing or blank UID (non-compliant servers) gets a random UUID
            val uid = vjournal.getPropertyOrNull<Property>("UID")?.value?.ifBlank { null }
                ?: UUID.randomUUID().toString()

            // A RECURRENCE-ID marks an exception
            val recurrenceId = vjournal.getPropertyOrNull<Property>("RECURRENCE-ID")
                ?.let { parseDateTimeFromProperty(it) }

            val importId = ICalJournal.generateImportId(uid, recurrenceId)

            val dtStart = vjournal.getPropertyOrNull<Property>("DTSTART")
                ?.let { parseDateTimeFromProperty(it) }
            val dtstamp = vjournal.getPropertyOrNull<Property>("DTSTAMP")
                ?.let { parseDateTimeFromProperty(it) }
            val created = vjournal.getPropertyOrNull<Property>("CREATED")
                ?.let { parseDateTimeFromProperty(it) }
            val lastModified = vjournal.getPropertyOrNull<Property>("LAST-MODIFIED")
                ?.let { parseDateTimeFromProperty(it) }

            val summary = vjournal.getPropertyOrNull<Property>("SUMMARY")
                ?.value
            val description = vjournal.getPropertyOrNull<Property>("DESCRIPTION")
                ?.value
            val url = vjournal.getPropertyOrNull<Property>("URL")?.value
            val classification = vjournal.getPropertyOrNull<Property>("CLASS")?.value

            val statusValue = vjournal.getPropertyOrNull<Property>("STATUS")?.value
            val sequenceValue = vjournal.getPropertyOrNull<Property>("SEQUENCE")
                ?.value?.toIntOrNull() ?: 0

            // Only a master's RRULE is read; an exception's is ignored
            val rrule = if (recurrenceId == null) {
                vjournal.getPropertyOrNull<Property>("RRULE")
                    ?.let { RRule.parse(it.value) }
            } else null

            val categoriesProps = vjournal.getProperties<Property>("CATEGORIES")
            val categories = categoriesProps.flatMap { cat ->
                // Blank elements are dropped, for the reason in parseVEvent
                cat.value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }

            val attachmentProps = vjournal.getProperties<Property>("ATTACH")
            val attachments = attachmentProps.mapNotNull { it.value }

            val organizer = parseJournalOrganizer(vjournal)

            val attendees = parseJournalAttendees(vjournal)

            val rawProperties = collectRawJournalProperties(vjournal)

            val journal = ICalJournal(
                uid = uid,
                importId = importId,
                summary = summary,
                description = description,
                dtStart = dtStart,
                status = JournalStatus.fromString(statusValue),
                sequence = sequenceValue,
                dtstamp = dtstamp,
                created = created,
                lastModified = lastModified,
                categories = categories,
                organizer = organizer,
                attendees = attendees,
                attachments = attachments,
                rrule = rrule,
                recurrenceId = recurrenceId,
                url = url,
                classification = classification,
                rawProperties = rawProperties
            )

            ParseResult.success(journal)
        } catch (e: Exception) {
            ParseResult.error("Failed to parse VJOURNAL: ${e.message}", e)
        }
    }

    /** Reads the VJOURNAL's ORGANIZER, without the RFC 6638 parameters [parseOrganizer] reads. */
    private fun parseJournalOrganizer(vjournal: VJournal): Organizer? {
        val organizerProp = vjournal.getPropertyOrNull<Property>("ORGANIZER")
            ?: return null

        val email = extractCalAddressEmail(organizerProp)

        val cn = organizerProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("CN")?.value
        val sentBy = organizerProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("SENT-BY")
            ?.value?.let { extractEmailFromCalAddress(it) }

        return Organizer(email = email, name = cn, sentBy = sentBy)
    }

    /** Reads the VJOURNAL's ATTENDEEs (CN, PARTSTAT, ROLE, RSVP), skipping any with no address. */
    private fun parseJournalAttendees(vjournal: VJournal): List<Attendee> {
        val attendeeProps = vjournal.getProperties<Property>("ATTENDEE")

        return attendeeProps.mapNotNull { attendeeProp ->
            val email = extractCalAddressEmail(attendeeProp)
            if (email.isBlank()) return@mapNotNull null

            val cn = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("CN")?.value
            val partStatValue = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("PARTSTAT")?.value
            val roleValue = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("ROLE")?.value
            val rsvpValue = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("RSVP")?.value

            Attendee(
                email = email,
                name = cn,
                partStat = PartStat.fromString(partStatValue),
                role = AttendeeRole.fromString(roleValue),
                rsvp = rsvpValue?.equals("TRUE", ignoreCase = true)
            )
        }
    }

    /**
     * Properties parsed into [ICalJournal] fields, so left out of rawProperties and
     * unknownPropertyLines.
     */
    private val handledJournalProperties = setOf(
        "UID", "DTSTART", "DTSTAMP",
        "RECURRENCE-ID", "RRULE",
        "SUMMARY", "DESCRIPTION",
        "STATUS", "SEQUENCE",
        "ORGANIZER", "ATTENDEE",
        "LAST-MODIFIED", "CREATED",
        "CATEGORIES", "ATTACH", "URL", "CLASS"
    )

    /**
     * Collects the VJOURNAL properties not in [handledJournalProperties], as
     * [collectRawProperties] does.
     */
    private fun collectRawJournalProperties(vjournal: VJournal): Map<String, String> {
        val raw = mutableMapOf<String, String>()

        for (prop in vjournal.getAllProperties()) {
            val propName = prop.name?.uppercase() ?: continue
            if (propName in handledJournalProperties) continue
            if (propName == "BEGIN" || propName == "END") continue

            val paramList = prop.getParameters()
            val key = if (paramList.isEmpty()) {
                propName
            } else {
                val paramStr = paramList.joinToString(";") { param ->
                    "${param.name}=${param.value}"
                }
                "$propName;$paramStr"
            }

            val value = prop.value
            if (!value.isNullOrBlank()) {
                raw[key] = value
            }
        }

        return raw
    }

    /** The METHOD and events of an iTIP message, where METHOD names the scheduling action. */
    data class CalendarParseResult(
        val method: ITipMethod?,
        val events: List<ICalEvent>
    )

    /**
     * Parses [icalData] into an [ICalCalendar]: the calendar-level properties (NAME, COLOR,
     * REFRESH-INTERVAL and others) and every VEVENT, VTODO and VJOURNAL. A missing VERSION
     * reads as 2.0 and a missing CALSCALE as GREGORIAN.
     */
    fun parse(icalData: String): ParseResult<ICalCalendar> {
        return try {
            val unfolded = prepareForParsing(icalData)
            val builder = createCalendarBuilder()
            val calendar = builder.build(StringReader(unfolded))

            val prodId = calendar.getPropertyOrNull<Property>("PRODID")?.value
            val version = calendar.getPropertyOrNull<Property>("VERSION")?.value ?: "2.0"
            val calscale = calendar.getPropertyOrNull<Property>("CALSCALE")?.value ?: "GREGORIAN"
            val method = calendar.getPropertyOrNull<Property>("METHOD")?.value
            val name = calendar.getPropertyOrNull<Property>("NAME")?.value
            val source = calendar.getPropertyOrNull<Property>("SOURCE")?.value
            val color = calendar.getPropertyOrNull<Property>("COLOR")?.value
            val xWrCalname = calendar.getPropertyOrNull<Property>("X-WR-CALNAME")?.value
            val xAppleCalendarColor = calendar.getPropertyOrNull<Property>("X-APPLE-CALENDAR-COLOR")?.value

            val refreshInterval = calendar.getPropertyOrNull<Property>("REFRESH-INTERVAL")
                ?.value?.let { ICalAlarm.parseDuration(it) }

            val events = parseEvents(unfolded, calendar)
            val todos = parseTodos(unfolded, calendar)
            val journals = parseJournals(unfolded, calendar)

            val icalCalendar = ICalCalendar(
                prodId = prodId,
                version = version,
                calscale = calscale,
                method = method,
                name = name,
                source = source,
                color = color,
                refreshInterval = refreshInterval,
                xWrCalname = xWrCalname,
                xAppleCalendarColor = xAppleCalendarColor,
                events = events,
                todos = todos,
                journals = journals
            )

            ParseResult.success(icalCalendar)
        } catch (e: Exception) {
            ParseResult.error("Failed to parse iCalendar: ${e.message}", e)
        }
    }

    /**
     * Parses the METHOD and every VEVENT of an iTIP scheduling message (REQUEST, REPLY,
     * CANCEL and others). The method is null when METHOD is absent or unrecognized.
     */
    fun parseWithMethod(icalData: String): ParseResult<CalendarParseResult> {
        return try {
            val unfolded = prepareForParsing(icalData)
            val builder = createCalendarBuilder()
            val calendar = builder.build(StringReader(unfolded))

            val methodProp = calendar.getPropertyOrNull<Property>("METHOD")
            val method = methodProp?.value?.let { ITipMethod.fromString(it) }

            ParseResult.success(CalendarParseResult(method, parseEvents(unfolded, calendar)))
        } catch (e: Exception) {
            ParseResult.error("Failed to parse iCalendar data: ${e.message}", e)
        }
    }

    /**
     * Converts one VEVENT to an [ICalEvent]. Returns a missing-property result when it has
     * neither DTSTART nor DTEND, and an error result when reading it throws.
     */
    fun parseVEvent(vevent: VEvent): ParseResult<ICalEvent> {
        return try {
            // A missing or blank UID (non-compliant servers) gets a random UUID. A blank
            // UID counts as missing so unrelated events can't share an empty-string key
            // downstream, for example in import grouping.
            val uid = vevent.getPropertyOrNull<Property>("UID")?.value?.ifBlank { null }
                ?: UUID.randomUUID().toString()

            // Without DTSTART (non-compliant servers), DTEND stands in
            val dtstartProp = vevent.getPropertyOrNull<Property>("DTSTART")
                ?: vevent.getPropertyOrNull<Property>("DTEND")
                ?: return ParseResult.missingProperty("DTSTART")

            val startDateTime = parseDateTimeFromProperty(dtstartProp)
            // All-day when VALUE=DATE, an 8-digit date, or a value without "T"
            val valueParam = dtstartProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("VALUE")?.value
            val isAllDay = valueParam == "DATE" ||
                (dtstartProp.value.length == 8 && dtstartProp.value.all { it.isDigit() }) ||
                !dtstartProp.value.contains("T")

            // A RECURRENCE-ID marks an exception
            val recurrenceId = vevent.getPropertyOrNull<Property>("RECURRENCE-ID")
                ?.let { parseDateTimeFromProperty(it) }

            val importId = ICalEvent.generateImportId(uid, recurrenceId)

            val dtend = vevent.getPropertyOrNull<Property>("DTEND")
                ?.let { parseDateTimeFromProperty(it) }
            val duration = vevent.getPropertyOrNull<Property>("DURATION")
                ?.let { ICalAlarm.parseDuration(it.value) }

            // Only a master's RRULE is read; an exception's is ignored
            val rrule = if (recurrenceId == null) {
                vevent.getPropertyOrNull<Property>("RRULE")
                    ?.let { RRule.parse(it.value) }
            } else null

            val exdateProps = vevent.getProperties<Property>("EXDATE")
            val exdates = exdateProps.flatMap { exdate ->
                val tzidParam = exdate.getParameterOrNull<net.fortuna.ical4j.model.parameter.TzId>("TZID")
                    ?.value
                // EXDATE can have multiple dates comma-separated
                exdate.value.split(",").map { dateStr ->
                    ICalDateTime.parse(dateStr.trim(), tzidParam)
                }
            }

            // Parse RDATE list (RFC 5545 Section 3.8.5.2)
            val rdateProps = vevent.getProperties<Property>("RDATE")
            val rdates = rdateProps.flatMap { rdate ->
                val tzidParam = rdate.getParameterOrNull<net.fortuna.ical4j.model.parameter.TzId>("TZID")?.value
                val valueParam = rdate.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("VALUE")?.value
                if (valueParam == "PERIOD") {
                    emptyList()  // Skip PERIOD values (not commonly used)
                } else {
                    rdate.value.split(",").mapNotNull { dateStr ->
                        try { ICalDateTime.parse(dateStr.trim(), tzidParam) }
                        catch (e: Exception) { null }
                    }
                }
            }

            val alarms = vevent.alarms.mapNotNull { valarm ->
                parseVAlarm(valarm).getOrNull()
            }

            val categoriesProps = vevent.getProperties<Property>("CATEGORIES")
            val categories = categoriesProps.flatMap { cat ->
                // Drop blank elements here: a malformed value like "foo,,bar" would
                // otherwise carry an empty category into Room and round-trip back to
                // the server.
                cat.value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }

            val summary = vevent.getPropertyOrNull<Property>("SUMMARY")
                ?.value
            val description = vevent.getPropertyOrNull<Property>("DESCRIPTION")
                ?.value
            val location = vevent.getPropertyOrNull<Property>("LOCATION")
                ?.value
            val statusValue = vevent.getPropertyOrNull<Property>("STATUS")
                ?.value
            val sequenceValue = vevent.getPropertyOrNull<Property>("SEQUENCE")
                ?.value?.toIntOrNull() ?: 0
            val transpValue = vevent.getPropertyOrNull<Property>("TRANSP")
                ?.value
            val urlValue = vevent.getPropertyOrNull<Property>("URL")
                ?.value

            // PRIORITY (RFC 5545 §3.8.1.9): 0 = undefined, 1 = highest, 9 = lowest; clamped to 0..9
            val priority = vevent.getPropertyOrNull<Property>("PRIORITY")
                ?.value?.toIntOrNull()?.coerceIn(0, 9) ?: 0

            // GEO (RFC 5545 §3.8.1.6), kept as its "latitude;longitude" text
            val geo = vevent.getPropertyOrNull<Property>("GEO")?.value

            // Parse CLASS property (RFC 5545 Section 3.8.1.3)
            val classValue = vevent.getPropertyOrNull<Property>("CLASS")?.value
            val classification = Classification.fromString(classValue)

            // Parse COLOR property (RFC 7986)
            val color = vevent.getPropertyOrNull<Property>("COLOR")
                ?.value

            // Parse IMAGE properties (RFC 7986)
            val images = vevent.getProperties<Property>("IMAGE").mapNotNull { imageProp ->
                parseImageProperty(imageProp)
            }

            // Parse CONFERENCE properties (RFC 7986)
            val conferences = vevent.getProperties<Property>("CONFERENCE").mapNotNull { confProp ->
                parseConferenceProperty(confProp)
            }

            // Parse LINK properties (RFC 9253)
            val links = vevent.getProperties<Property>("LINK").mapNotNull { linkProp ->
                parseLinkProperty(linkProp)
            }

            // Parse RELATED-TO properties (RFC 9253)
            val relations = vevent.getProperties<Property>("RELATED-TO").mapNotNull { relProp ->
                parseRelatedToProperty(relProp)
            }

            val organizer = parseOrganizer(vevent)

            val attendees = parseAttendees(vevent)

            val dtstamp = vevent.getPropertyOrNull<Property>("DTSTAMP")
                ?.let { parseDateTimeFromProperty(it) }

            val lastModified = vevent.getPropertyOrNull<Property>("LAST-MODIFIED")
                ?.let { parseDateTimeFromProperty(it) }

            val created = vevent.getPropertyOrNull<Property>("CREATED")
                ?.let { parseDateTimeFromProperty(it) }

            val rawProperties = collectRawProperties(vevent)

            val event = ICalEvent(
                uid = uid,
                importId = importId,
                summary = summary,
                description = description,
                location = location,
                dtStart = startDateTime,
                dtEnd = dtend,
                duration = duration,
                isAllDay = isAllDay,
                status = EventStatus.fromString(statusValue),
                sequence = sequenceValue,
                rrule = rrule,
                exdates = exdates,
                rdates = rdates,
                classification = classification,
                recurrenceId = recurrenceId,
                alarms = alarms,
                categories = categories,
                organizer = organizer,
                attendees = attendees,
                color = color,
                dtstamp = dtstamp,
                lastModified = lastModified,
                created = created,
                transparency = Transparency.fromString(transpValue),
                url = urlValue,
                priority = priority,
                geo = geo,
                images = images,
                conferences = conferences,
                links = links,
                relations = relations,
                rawProperties = rawProperties
            )

            // Non-compliant servers can send DTEND before DTSTART; swap them
            val repaired = if (event.dtEnd != null && event.dtEnd!!.timestamp < event.dtStart.timestamp) {
                event.copy(dtStart = event.dtEnd!!, dtEnd = event.dtStart)
            } else {
                event
            }

            ParseResult.success(repaired)
        } catch (e: Exception) {
            ParseResult.error("Failed to parse VEVENT: ${e.message}", e)
        }
    }

    /** Converts one VALARM to an [ICalAlarm]. */
    private fun parseVAlarm(valarm: VAlarm): ParseResult<ICalAlarm> {
        return try {
            val actionValue = valarm.getPropertyOrNull<Property>("ACTION")
                ?.value
            val action = AlarmAction.fromString(actionValue)

            val triggerProp = valarm.getPropertyOrNull<Property>("TRIGGER")
            val trigger: Duration?
            val triggerAbsolute: ICalDateTime?
            val relatedToEnd: Boolean

            val triggerValue = triggerProp?.value
            if (triggerValue != null && (triggerValue.startsWith("-") || triggerValue.startsWith("P"))) {
                // Duration trigger
                trigger = ICalAlarm.parseDuration(triggerValue)
                triggerAbsolute = null
                val relatedParam = triggerProp.getParameterOrNull<net.fortuna.ical4j.model.parameter.Related>("RELATED")
                relatedToEnd = relatedParam?.value == "END"
            } else if (triggerValue != null) {
                // Absolute trigger
                trigger = null
                triggerAbsolute = ICalDateTime.parse(triggerValue)
                relatedToEnd = false
            } else {
                trigger = Duration.ofMinutes(-15) // No TRIGGER: 15 minutes before
                triggerAbsolute = null
                relatedToEnd = false
            }

            val descriptionValue = valarm.getPropertyOrNull<Property>("DESCRIPTION")
                ?.value
            val summaryValue = valarm.getPropertyOrNull<Property>("SUMMARY")
                ?.value
            val repeatValue = valarm.getPropertyOrNull<Property>("REPEAT")
                ?.value?.toIntOrNull() ?: 0
            val durationValue = valarm.getPropertyOrNull<Property>("DURATION")
                ?.value

            // RFC 9074 extensions (UID, ACKNOWLEDGED, RELATED-TO, PROXIMITY), plus
            // DEFAULT-ALARM, which RFC 9074 doesn't define
            val uid = valarm.getPropertyOrNull<Property>("UID")?.value

            val acknowledged = valarm.getPropertyOrNull<Property>("ACKNOWLEDGED")
                ?.let { parseDateTimeFromProperty(it) }

            val relatedTo = valarm.getPropertyOrNull<Property>("RELATED-TO")?.value

            val defaultAlarm = valarm.getPropertyOrNull<Property>("DEFAULT-ALARM")
                ?.value?.equals("TRUE", ignoreCase = true) ?: false

            val proximity = valarm.getPropertyOrNull<Property>("PROXIMITY")
                ?.value?.let { AlarmProximity.fromString(it) }

            val alarm = ICalAlarm(
                action = action,
                trigger = trigger,
                triggerAbsolute = triggerAbsolute,
                triggerRelatedToEnd = relatedToEnd,
                description = descriptionValue,
                summary = summaryValue,
                repeatCount = repeatValue,
                repeatDuration = durationValue?.let { ICalAlarm.parseDuration(it) },
                uid = uid,
                acknowledged = acknowledged,
                relatedTo = relatedTo,
                defaultAlarm = defaultAlarm,
                proximity = proximity
            )

            ParseResult.success(alarm)
        } catch (e: Exception) {
            ParseResult.error("Failed to parse VALARM: ${e.message}", e)
        }
    }

    /**
     * Reads a date or date-time property, applying its TZID parameter.
     *
     * The value is date-only when any of these holds: a VALUE=DATE parameter, ical4j's
     * parsed value is a LocalDate, or the value is 8 digits. A date-only value that arrives
     * with a time part (ical4j 3.x wrote 20231215 as 20231215T000000) is cut to its date.
     */
    private fun parseDateTimeFromProperty(prop: Property): ICalDateTime {
        val value = prop.value
        val tzidParam = prop.getParameterOrNull<net.fortuna.ical4j.model.parameter.TzId>("TZID")
            ?.value

        val valueParam = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("VALUE")?.value
        val hasDateParameter = valueParam == "DATE"

        // ical4j 4.x: a DateProperty's date is a java.time Temporal; LocalDate means
        // date-only, LocalDateTime or ZonedDateTime date-time
        val dateProperty = prop as? net.fortuna.ical4j.model.property.DateProperty<*>
        val dateObj = dateProperty?.date
        val isDateType = dateObj != null && dateObj is java.time.LocalDate

        val looks8DigitDate = value.length == 8 && value.all { it.isDigit() }

        val isDateOnly = hasDateParameter || isDateType || looks8DigitDate

        return if (isDateOnly && value.contains("T")) {
            ICalDateTime.parse(value.substringBefore("T"), tzidParam)
        } else {
            ICalDateTime.parse(value, tzidParam)
        }
    }

    /**
     * Returns the value after the first `UID:` in [icalData], without a full parse. The match
     * isn't anchored to a line start and the data isn't unfolded.
     */
    fun extractUid(icalData: String): String? {
        val uidMatch = Regex("""UID:(.+)""").find(icalData)
        return uidMatch?.groupValues?.get(1)?.trim()
    }

    /**
     * Returns the value after every `UID:` in [icalData], matched as [extractUid] does, so VTODO
     * and VALARM UIDs are included.
     */
    fun extractAllUids(icalData: String): List<String> {
        return Regex("""UID:(.+)""").findAll(icalData)
            .map { it.groupValues[1].trim() }
            .toList()
    }

    /**
     * Unfolds content lines (RFC 5545 §3.1): removes each line break, CRLF or bare LF,
     * followed by a space or tab. Must run before parsing so long lines such as
     * descriptions parse whole.
     */
    private fun unfoldICalData(data: String): String {
        return data
            .replace("\r\n ", "")
            .replace("\r\n\t", "")
            .replace("\n ", "")
            .replace("\n\t", "")
    }

    /**
     * Repairs known server bugs in the raw text before ical4j parses it:
     * - Short UTC offsets: Synology sends `+530` for `+0530`. It also sends `+5730` for
     *   `+005730`, which isn't repaired.
     * - A T in a day duration: some servers send `-PT2D` for `-P2D`.
     * - DATE-typed timestamp metadata: the icalendar-ruby gem and feeds derived from it emit
     *   DTSTAMP, LAST-MODIFIED and CREATED as VALUE=DATE, though RFC 5545 §3.8.7 requires
     *   DATE-TIME. ical4j's DateProperty serializer throws UnsupportedTemporalTypeException
     *   (HourOfDay) on the resulting LocalDate.
     * - A custom VTIMEZONE TZID ical4j can't resolve ([rewriteUnresolvableVTimezones]).
     */
    private fun preprocessICalData(data: String): String {
        // Short UTC offsets: pad a 3-digit offset to HHMM (Synology sends +530 for IST's
        // +0530). RFC 5545: utc-offset = (+/-)HHMM or (+/-)HHMMSS. Only a TZOFFSETFROM or
        // TZOFFSETTO value at the end of a line matches; the match isn't anchored to the
        // line start.
        val offsetFixed = data.replace(
            Regex("""(TZOFFSETFROM|TZOFFSETTO):([+-])(\d{3})\s*$""", RegexOption.MULTILINE)
        ) { match ->
            val prefix = match.groupValues[1]
            val sign = match.groupValues[2]
            val digits = match.groupValues[3]
            "$prefix:$sign${digits.padStart(4, '0')}"   // +530 → +0530
        }

        // Misplaced T in day durations: -PT2D → -P2D. Anchored to TRIGGER and DURATION
        // lines, so free text can't match. Only a pure day duration (PTnD) is repaired;
        // a mixed one like PT2DT3H is left as sent.
        val durationFixed = offsetFixed.replace(
            Regex("""^((?:TRIGGER|DURATION)[^:]*:)(-?)PT(\d+)D\s*$""", RegexOption.MULTILINE)
        ) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}P${match.groupValues[3]}D"
        }

        // DATE-typed DTSTAMP, LAST-MODIFIED or CREATED → DATE-TIME at midnight UTC. ical4j
        // parses `VALUE=DATE:YYYYMMDD` as a LocalDate and then throws when serializing
        // (DateProperty.getValue → TemporalAdapter.toString needs HourOfDay). Converting
        // keeps the publisher's date instead of falling back to "now". Anchored at the line
        // start with only these three names, so free text, folded continuation lines and
        // X- properties can't match.
        val dtstampFixed = durationFixed.replace(
            Regex("""^(DTSTAMP|LAST-MODIFIED|CREATED);VALUE=DATE:(\d{8})\s*$""", RegexOption.MULTILINE)
        ) { match ->
            "${match.groupValues[1]}:${match.groupValues[2]}T000000Z"
        }

        return rewriteUnresolvableVTimezones(dtstampFixed)
    }

    /**
     * Rewrites every `TZID:` and `TZID=` reference to an embedded VTIMEZONE's custom TZID
     * to the IANA zone its X-LIC-LOCATION names, when the TZID doesn't resolve and that
     * zone does. A TZID that already resolves is left untouched.
     *
     * RFC 5545 §3.2.19 lets a TZID with no leading solidus name a zone defined only by an
     * embedded VTIMEZONE. ical4j 4.x resolves a TZID with java.time.ZoneId.of when a date
     * is read, so a non-IANA name such as "TZsfv" throws and every event is dropped. The
     * rewrite gives the right offset instead of floating time. A zone that stays
     * unresolvable is kept at device-local time by relaxed validation ([configureIcal4j]).
     */
    private fun rewriteUnresolvableVTimezones(data: String): String {
        if (!data.contains("X-LIC-LOCATION", ignoreCase = true)) return data

        val mapping = LinkedHashMap<String, String>()
        VTIMEZONE_BLOCK.findAll(data).forEach { block ->
            val body = block.groupValues[1]
            val tzid = VTIMEZONE_TZID.find(body)?.groupValues?.get(1)?.trim() ?: return@forEach
            val iana = VTIMEZONE_XLIC.find(body)?.groupValues?.get(1)?.trim() ?: return@forEach
            if (tzid.isEmpty() || iana.isEmpty() || tzid == iana) return@forEach
            if (isResolvableZone(tzid) || !isResolvableZone(iana)) return@forEach
            mapping.putIfAbsent(tzid, iana)
        }
        if (mapping.isEmpty()) return data

        var result = data
        for ((custom, iana) in mapping) {
            result = Regex("""(TZID[:=])${Regex.escape(custom)}(?=[;:\r\n])""")
                .replace(result) { "${it.groupValues[1]}$iana" }
        }
        return result
    }

    private fun isResolvableZone(id: String): Boolean =
        try { java.time.ZoneId.of(id); true } catch (_: Exception) { false }

    /**
     * Runs the raw-text preparation every parse entry point applies before
     * handing the body to ical4j. The stage order matters: quirk fixes
     * ([preprocessICalData]) match on folded property lines and so must run
     * first; unfolding then joins continuation lines; the uppercase-`\N`
     * normalization runs last so a `\N` split across a fold boundary is seen
     * whole. Kept in one place so the sequence is asserted once, not at each
     * call site.
     */
    private fun prepareForParsing(icalData: String): String =
        normalizeUppercaseNewlineEscape(unfoldICalData(preprocessICalData(icalData)))

    /**
     * Rewrites the uppercase newline escape `\N` to lowercase `\n` in raw ICS
     * text, before ical4j parses it.
     *
     * RFC 5545 §3.3.11 defines `\N` (uppercase) as equivalent to `\n`: both
     * encode a newline in a TEXT value. ical4j's PropertyCodec (checked in 4.3.0)
     * only decodes the lowercase form, so an uppercase `\N` would otherwise
     * survive verbatim into the parsed value.
     *
     * This must run on the raw text, not on `Property.value`: once ical4j has
     * decoded, a source `\N` (newline) and a source `\\N` (an escaped backslash
     * followed by a literal `N`, for example a Windows path `C:\Notes`) both
     * collapse to the same two characters, and no post-decode rule can tell them
     * apart. [UPPERCASE_NEWLINE_ESCAPE] keeps that distinction: `\N` is rewritten
     * only when its backslash is itself unescaped; `\\N` is left for ical4j to
     * decode to `\` + `N`. Its `(?:\\\\)*` consumes complete escaped-backslash
     * pairs so the trailing `\N` is matched against a clean boundary, and the
     * capture group re-emits those pairs.
     */
    private fun normalizeUppercaseNewlineEscape(data: String): String {
        // `\N` is rare in real payloads, so skip the regex scan of the whole body
        // when no backslash-N is present at all.
        if (!data.contains("\\N")) return data
        return UPPERCASE_NEWLINE_ESCAPE.replace(data) { match -> match.groupValues[1] + "\\n" }
    }

    /**
     * Reads the VEVENT's ORGANIZER, including the RFC 6638 parameters SCHEDULE-AGENT,
     * SCHEDULE-STATUS and SCHEDULE-FORCE-SEND. Example:
     * `ORGANIZER;CN=John Doe;SENT-BY="mailto:assistant@example.com":mailto:john@example.com`
     */
    private fun parseOrganizer(vevent: VEvent): Organizer? {
        val organizerProp = vevent.getPropertyOrNull<Property>("ORGANIZER")
            ?: return null

        val email = extractCalAddressEmail(organizerProp)

        val cn = organizerProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("CN")
            ?.value
        val sentBy = organizerProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("SENT-BY")
            ?.value?.let { extractEmailFromCalAddress(it) }

        // RFC 6638 scheduling parameters
        val scheduleAgentValue = organizerProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("SCHEDULE-AGENT")
            ?.value
        val scheduleAgent = scheduleAgentValue?.let { ScheduleAgent.fromString(it) }

        val scheduleStatusValue = organizerProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("SCHEDULE-STATUS")
            ?.value
        val scheduleStatus = scheduleStatusValue?.let { parseScheduleStatuses(it) }

        val scheduleForceSendValue = organizerProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("SCHEDULE-FORCE-SEND")
            ?.value
        val scheduleForceSend = scheduleForceSendValue?.let { ScheduleForceSend.fromString(it) }

        return Organizer(
            email = email,
            name = cn,
            sentBy = sentBy,
            scheduleAgent = scheduleAgent,
            scheduleStatus = scheduleStatus,
            scheduleForceSend = scheduleForceSend
        )
    }

    /** Splits a SCHEDULE-STATUS value, which can list more than one code, comma-separated. */
    private fun parseScheduleStatuses(value: String): List<ScheduleStatus> {
        return value.split(",").map { ScheduleStatus.fromString(it.trim()) }
    }

    /**
     * Reads the VEVENT's ATTENDEEs, skipping any without an address. Example:
     * `ATTENDEE;CN=Jane Doe;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT:mailto:jane@example.com`
     *
     * Besides CN, PARTSTAT, ROLE and RSVP it reads the RFC 5545 parameters CUTYPE, DIR,
     * MEMBER, DELEGATED-TO, DELEGATED-FROM and SENT-BY, and the RFC 6638 parameters
     * SCHEDULE-AGENT, SCHEDULE-STATUS and SCHEDULE-FORCE-SEND.
     */
    private fun parseAttendees(vevent: VEvent): List<Attendee> {
        val attendeeProps = vevent.getProperties<Property>("ATTENDEE")

        return attendeeProps.mapNotNull { attendeeProp ->
            val email = extractCalAddressEmail(attendeeProp)
            if (email.isBlank()) return@mapNotNull null

            val cn = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("CN")
                ?.value

            val partStatValue = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("PARTSTAT")
                ?.value
            val partStat = PartStat.fromString(partStatValue)

            val roleValue = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("ROLE")
                ?.value
            val role = AttendeeRole.fromString(roleValue)

            val rsvpValue = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("RSVP")
                ?.value
            // RFC 5545 §3.2.17: RSVP is optional. Keep null apart from an explicit FALSE:
            // null means the ATTENDEE didn't say whether the organizer asked for a response.
            val rsvp: Boolean? = rsvpValue?.equals("TRUE", ignoreCase = true)

            // RFC 5545 parameters
            val cutypeValue = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("CUTYPE")
                ?.value
            val cutype = CUType.fromString(cutypeValue)

            val dir = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("DIR")
                ?.value?.removeSurrounding("\"")

            // RFC 5545 §3.2.11: MEMBER is multi-value (comma-separated quoted URIs), the
            // same wire form as DELEGATED-TO and DELEGATED-FROM below, so all use parseMailtoList.
            val memberValue = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("MEMBER")
                ?.value
            val member = parseMailtoList(memberValue)

            val delegatedToValue = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("DELEGATED-TO")
                ?.value
            val delegatedTo = parseMailtoList(delegatedToValue)

            val delegatedFromValue = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("DELEGATED-FROM")
                ?.value
            val delegatedFrom = parseMailtoList(delegatedFromValue)

            // SENT-BY (RFC 5545 §3.2.18), then the RFC 6638 scheduling parameters
            val sentBy = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("SENT-BY")
                ?.value?.let { extractEmailFromCalAddress(it) }

            val scheduleAgentValue = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("SCHEDULE-AGENT")
                ?.value
            val scheduleAgent = scheduleAgentValue?.let { ScheduleAgent.fromString(it) }

            val scheduleStatusValue = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("SCHEDULE-STATUS")
                ?.value
            val scheduleStatus = scheduleStatusValue?.let { parseScheduleStatuses(it) }

            val scheduleForceSendValue = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("SCHEDULE-FORCE-SEND")
                ?.value
            val scheduleForceSend = scheduleForceSendValue?.let { ScheduleForceSend.fromString(it) }

            Attendee(
                email = email,
                name = cn,
                partStat = partStat,
                role = role,
                rsvp = rsvp,
                cutype = cutype,
                dir = dir,
                member = member,
                delegatedTo = delegatedTo,
                delegatedFrom = delegatedFrom,
                sentBy = sentBy,
                scheduleAgent = scheduleAgent,
                scheduleStatus = scheduleStatus,
                scheduleForceSend = scheduleForceSend
            )
        }
    }

    /**
     * Splits a comma-separated list of quoted CAL-ADDRESSes (`"mailto:a@b.com","mailto:c@d.com"`)
     * into bare addresses, dropping empty ones.
     */
    private fun parseMailtoList(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(",")
            .map { it.trim().removeSurrounding("\"").let { addr -> extractEmailFromCalAddress(addr) } }
            .filter { it.isNotEmpty() }
    }

    /**
     * Strips surrounding quotes and a `mailto:` prefix in any case:
     * `MAILTO:john@example.com` gives `john@example.com`.
     */
    private fun extractEmailFromCalAddress(calAddress: String): String {
        return calAddress
            .trim()
            .removePrefix("\"")
            .removeSuffix("\"")
            .replace(Regex("^mailto:", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    /**
     * The mailbox shape [extractCalAddressEmail] requires of the value and of the `EMAIL=`
     * fallback. It rejects principal hrefs (`/646691839/principal/`), HTTP and HTTPS
     * principal URIs (`https://caldav.example.com/principals/users/foo/`), `urn:uuid:`
     * forms, and shapes like `@example.com` or `foo@`.
     */
    private val mailtoShape = org.onekash.icaldav.util.CalAddress.mailtoShape

    /**
     * Returns the address of a CAL-ADDRESS property: the value without `mailto:`
     * when it matches [mailtoShape], else the property's `EMAIL=` parameter when
     * that does, else the stripped value, for the caller to handle.
     *
     * iCloud (its iSchedule binding) rewrites ORGANIZER and ATTENDEE values to
     * internal principal hrefs (`/.../principal/`) when the mailto matches the
     * authenticated account, and keeps the original mailto as an `EMAIL=`
     * parameter. Other servers can send `urn:uuid:` or HTTP-principal forms
     * (RFC 5545 §3.3.3 permits non-mailto CAL-ADDRESSes).
     *
     * RFC 5545 §3.1 makes parameter names case-insensitive, so `EMAIL=` is
     * matched in any casing ([getParameterIgnoreCase]).
     */
    private fun extractCalAddressEmail(prop: net.fortuna.ical4j.model.Property): String {
        val primary = extractEmailFromCalAddress(prop.value)
        if (mailtoShape.matches(primary)) return primary

        val emailParamValue = prop.getParameterIgnoreCase("EMAIL")
            ?.value
            ?.takeUnless { it.isBlank() }
            ?: return primary

        val fromParam = extractEmailFromCalAddress(emailParamValue)
        return if (mailtoShape.matches(fromParam)) fromParam else primary
    }

    // ============ RFC 7986 Property Parsing ============

    /**
     * Reads an RFC 7986 IMAGE property, or returns null when its value is blank. DISPLAY
     * defaults to GRAPHIC. Example:
     * `IMAGE;VALUE=URI;DISPLAY=BADGE;FMTTYPE=image/png:https://example.com/logo.png`
     */
    private fun parseImageProperty(prop: Property): ICalImage? {
        val uri = prop.value
        if (uri.isNullOrBlank()) return null

        val display = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("DISPLAY")
            ?.value?.let { ImageDisplay.fromString(it) } ?: ImageDisplay.GRAPHIC

        val mediaType = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("FMTTYPE")
            ?.value

        val altText = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("ALTREP")
            ?.value

        return ICalImage(
            uri = uri,
            display = display,
            mediaType = mediaType,
            altText = altText
        )
    }

    /**
     * Reads an RFC 7986 CONFERENCE property, or returns null when its value is blank. Example:
     * `CONFERENCE;VALUE=URI;FEATURE=VIDEO,AUDIO;LABEL=Join:https://zoom.us/j/123`
     */
    private fun parseConferenceProperty(prop: Property): ICalConference? {
        val uri = prop.value
        if (uri.isNullOrBlank()) return null

        val featuresStr = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("FEATURE")
            ?.value
        val features = ConferenceFeature.parseFeatures(featuresStr)

        val label = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("LABEL")
            ?.value

        val language = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("LANGUAGE")
            ?.value

        return ICalConference(
            uri = uri,
            features = features,
            label = label,
            language = language
        )
    }

    // ============ RFC 9253 Property Parsing ============

    /**
     * Reads an RFC 9253 LINK property, or returns null when its value is blank. Example:
     * `LINK;REL=alternate;FMTTYPE=text/html;TITLE="Details":https://example.com/event`
     */
    private fun parseLinkProperty(prop: Property): ICalLink? {
        val uri = prop.value
        if (uri.isNullOrBlank()) return null

        val rel = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("REL")
            ?.value
        val fmttype = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("FMTTYPE")
            ?.value
        val title = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("TITLE")
            ?.value?.trim('"')
        val label = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("LABEL")
            ?.value
        val language = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("LANGUAGE")
            ?.value
        val gap = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("GAP")
            ?.value

        return ICalLink.fromParameters(
            uri = uri,
            rel = rel,
            fmttype = fmttype,
            title = title,
            label = label,
            language = language,
            gap = gap
        )
    }

    /**
     * Reads a RELATED-TO property with its RFC 9253 parameters, or returns null when its value
     * is blank. Example: `RELATED-TO;RELTYPE=PARENT:parent-event-uid`
     */
    private fun parseRelatedToProperty(prop: Property): ICalRelation? {
        val uid = prop.value
        if (uid.isNullOrBlank()) return null

        val reltype = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("RELTYPE")
            ?.value
        val gap = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("GAP")
            ?.value

        return ICalRelation.fromParameters(
            uid = uid,
            reltype = reltype,
            gap = gap
        )
    }

    // ============ Raw Property Collection ============

    /**
     * Properties parsed into [ICalEvent] fields, so left out of rawProperties and
     * unknownPropertyLines.
     */
    private val handledProperties = setOf(
        "UID", "DTSTART", "DTEND", "DURATION", "DTSTAMP",
        "RECURRENCE-ID", "RRULE", "EXDATE", "RDATE",
        "SUMMARY", "DESCRIPTION", "LOCATION",
        "STATUS", "SEQUENCE", "TRANSP", "URL", "CLASS",
        "PRIORITY", "GEO",  // RFC 5545 additional properties
        "COLOR", "IMAGE", "CONFERENCE",
        "LINK", "RELATED-TO",
        "ORGANIZER", "ATTENDEE",
        "LAST-MODIFIED", "CREATED",
        "CATEGORIES"
        // BEGIN, END and VALARM aren't properties, so they aren't listed
    )

    /**
     * Collects the properties not in [handledProperties] for round trips: X- vendor
     * extensions such as X-APPLE-STRUCTURED-LOCATION, and any other RFC 5545 property.
     *
     * The key is the name plus any parameters, the value the property value:
     * "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-TITLE=Apple Park" -> "geo:37.33...". A blank
     * value is dropped, and repeats with the same key keep only the last value;
     * [UnknownPropertyLines] keeps every line.
     */
    private fun collectRawProperties(vevent: VEvent): Map<String, String> {
        val raw = mutableMapOf<String, String>()

        for (prop in vevent.getAllProperties()) {
            val propName = prop.name?.uppercase() ?: continue

            if (propName in handledProperties) continue

            if (propName == "BEGIN" || propName == "END") continue

            val paramList = prop.getParameters()

            val key = if (paramList.isEmpty()) {
                propName
            } else {
                val paramStr = paramList.joinToString(";") { param ->
                    "${param.name}=${param.value}"
                }
                "$propName;$paramStr"
            }

            val value = prop.value
            if (!value.isNullOrBlank()) {
                raw[key] = value
            }
        }

        return raw
    }

    // ============ VFREEBUSY Parsing ============

    /**
     * Parses the first VFREEBUSY in [icalData], or returns null when there is none or
     * parsing fails.
     */
    fun parseFreeBusy(icalData: String): ICalFreeBusy? {
        return try {
            val unfolded = prepareForParsing(icalData)
            val builder = createCalendarBuilder()
            val calendar = builder.build(StringReader(unfolded))

            val vfb = calendar.getComponents<VFreeBusy>(Component.VFREEBUSY).firstOrNull()
                ?: return null

            parseVFreeBusy(vfb)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Converts one VFREEBUSY. A missing UID gets an uppercase random UUID, and a missing
     * DTSTAMP, DTSTART or DTEND becomes now. Attendees get NEEDS-ACTION, REQ-PARTICIPANT
     * and RSVP false whatever their parameters say.
     */
    private fun parseVFreeBusy(vfb: VFreeBusy): ICalFreeBusy {
        val uid = vfb.getPropertyOrNull<Property>("UID")?.value
            ?: java.util.UUID.randomUUID().toString().uppercase()

        val dtstamp = vfb.getPropertyOrNull<Property>("DTSTAMP")
            ?.let { parseDateTimeFromProperty(it) }
            ?: ICalDateTime.now()

        val dtstart = vfb.getPropertyOrNull<Property>("DTSTART")
            ?.let { parseDateTimeFromProperty(it) }
            ?: ICalDateTime.now()

        val dtend = vfb.getPropertyOrNull<Property>("DTEND")
            ?.let { parseDateTimeFromProperty(it) }
            ?: ICalDateTime.now()

        // ORGANIZER without the RFC 6638 parameters parseOrganizer reads
        val organizerProp = vfb.getPropertyOrNull<Property>("ORGANIZER")
        val organizer = organizerProp?.let { prop ->
            val email = extractCalAddressEmail(prop)
            val cn = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("CN")?.value
            val sentBy = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("SENT-BY")
                ?.value?.let { extractEmailFromCalAddress(it) }
            Organizer(email = email, name = cn, sentBy = sentBy)
        }

        val attendeeProps = vfb.getProperties<Property>("ATTENDEE")
        val attendees = attendeeProps.mapNotNull { attendeeProp ->
            val email = extractCalAddressEmail(attendeeProp)
            if (email.isBlank()) return@mapNotNull null
            val cn = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("CN")?.value
            Attendee(
                email = email,
                name = cn,
                partStat = PartStat.NEEDS_ACTION,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )
        }

        // FBTYPE defaults to BUSY
        val freeBusyProps = vfb.getProperties<Property>("FREEBUSY")
        val freeBusyPeriods = freeBusyProps.flatMap { fbProp ->
            val fbtypeParam = fbProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("FBTYPE")?.value
            val fbType = FreeBusyType.fromString(fbtypeParam ?: "BUSY")
            parseFreeBusyPeriods(fbProp.value, fbType)
        }

        return ICalFreeBusy(
            uid = uid,
            dtstamp = dtstamp,
            dtstart = dtstart,
            dtend = dtend,
            organizer = organizer,
            attendees = attendees,
            freeBusyPeriods = freeBusyPeriods
        )
    }

    /**
     * Splits a FREEBUSY value into periods, skipping malformed ones. Each period is
     * start/end ("20231215T090000Z/20231215T100000Z") or start/duration
     * ("20231215T090000Z/PT1H"), comma-separated.
     */
    private fun parseFreeBusyPeriods(value: String, type: FreeBusyType): List<FreeBusyPeriod> {
        return value.split(",").mapNotNull { periodStr ->
            try {
                val parts = periodStr.trim().split("/")
                if (parts.size != 2) return@mapNotNull null

                val start = ICalDateTime.parse(parts[0])

                val end = if (parts[1].startsWith("P")) {
                    // Duration format
                    val duration = ICalAlarm.parseDuration(parts[1])
                    if (duration != null) {
                        ICalDateTime.fromTimestamp(
                            timestamp = start.timestamp + duration.toMillis(),
                            timezone = start.timezone,
                            isDate = start.isDate
                        )
                    } else {
                        return@mapNotNull null
                    }
                } else {
                    // DateTime format
                    ICalDateTime.parse(parts[1])
                }

                FreeBusyPeriod(start = start, end = end, type = type)
            } catch (e: Exception) {
                null
            }
        }
    }
}
