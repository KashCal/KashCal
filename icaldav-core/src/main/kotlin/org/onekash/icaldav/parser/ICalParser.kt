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
 * Kotlin-friendly wrapper around ical4j for parsing iCalendar data.
 *
 * Handles RECURRENCE-ID events that ical4j processes but doesn't group,
 * and provides the critical importId generation for database storage.
 *
 * Production-tested with various CalDAV servers including iCloud.
 *
 * Thread Safety: This class is thread-safe. The ical4j configuration is
 * initialized once using double-checked locking before first use.
 *
 * ## TimeZoneRegistry Configuration
 *
 * By default, ICalParser uses [SimpleTimeZoneRegistry] which is Android-safe.
 * For JVM servers that need richer timezone handling, use [createWithFullRegistry].
 *
 * Usage:
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
     * Create an ICalParser with the default Android-safe [SimpleTimeZoneRegistry].
     *
     * This constructor is recommended for most use cases, especially on Android.
     * The SimpleTimeZoneRegistry:
     * - Uses ZoneId.of() which is supported on Android via desugaring
     * - Relies on embedded VTIMEZONE definitions in iCalendar data
     * - Is lightweight and has no external dependencies
     */
    constructor() : this(SimpleTimeZoneRegistry())

    init {
        // Ensure ical4j is configured before any parsing
        ensureConfigured()
    }

    companion object {
        @Volatile
        private var configured = false
        private val configLock = Any()

        /**
         * Create an ICalParser with [TimeZoneRegistryImpl] for full timezone support.
         *
         * This factory method is intended for JVM server environments where:
         * - ZoneRulesProvider is available (not on Android)
         * - You need pre-built TimeZone objects from the registry
         * - You need complete Windows timezone name mapping
         *
         * **Warning**: Do NOT use this on Android - it will crash at runtime
         * because ZoneRulesProvider is not available via desugaring.
         *
         * @return ICalParser configured with TimeZoneRegistryImpl
         */
        fun createWithFullRegistry(): ICalParser {
            return ICalParser(net.fortuna.ical4j.model.TimeZoneRegistryImpl())
        }

        /**
         * Ensure ical4j is configured exactly once, thread-safely.
         *
         * Uses double-checked locking pattern for efficient thread-safe
         * lazy initialization. Safe to call multiple times.
         */
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
         * Configure ical4j system properties for Android/server compatibility.
         *
         * IMPORTANT: These are JVM-global settings. Call ensureConfigured()
         * at application startup if you need to guarantee configuration before
         * any ICalParser instances are created.
         */
        private fun configureIcal4j() {
            // Use MapTimeZoneCache (no file system cache, no network dependency)
            System.setProperty("net.fortuna.ical4j.timezone.cache.impl",
                "net.fortuna.ical4j.util.MapTimeZoneCache")

            // Disable timezone updates (requires network access)
            System.setProperty("net.fortuna.ical4j.timezone.update.enabled", "false")

            // Enable relaxed parsing for malformed iCal data from various servers
            System.setProperty("ical4j.unfolding.relaxed", "true")
            System.setProperty("ical4j.parsing.relaxed", "true")
        }
    }

    /**
     * Create a CalendarBuilder with the configured TimeZoneRegistry.
     */
    private fun createCalendarBuilder(): CalendarBuilder {
        return CalendarBuilder(
            CalendarParserFactory.getInstance().get(),
            ContentHandlerContext().withSuppressInvalidProperties(true),
            registry
        )
    }

    /**
     * Parse iCal string and return all VEVENTs as ICalEvent objects.
     *
     * Critical for iCloud sync: A single .ics file may contain multiple VEVENTs
     * with the same UID but different RECURRENCE-ID values (modified instances).
     *
     * @param icalData Raw iCalendar string
     * @return List of parsed events, each with unique importId
     */
    fun parseAllEvents(icalData: String): ParseResult<List<ICalEvent>> {
        return try {
            val unfolded = unfoldICalData(preprocessICalData(icalData))
            val builder = createCalendarBuilder()
            val calendar = builder.build(StringReader(unfolded))

            val events = calendar.getComponents<VEvent>(Component.VEVENT)
                .mapNotNull { vevent ->
                    parseVEvent(vevent).getOrNull()
                }

            ParseResult.success(events)
        } catch (e: Exception) {
            ParseResult.error("Failed to parse iCalendar data: ${e.message}", e)
        }
    }

    /**
     * Parse iCal string and return all VTODOs as ICalTodo objects.
     *
     * @param icalData Raw iCalendar string
     * @return List of parsed todos, each with unique importId
     */
    fun parseAllTodos(icalData: String): ParseResult<List<ICalTodo>> {
        return try {
            val unfolded = unfoldICalData(preprocessICalData(icalData))
            val builder = createCalendarBuilder()
            val calendar = builder.build(StringReader(unfolded))

            val todos = calendar.getComponents<VToDo>(Component.VTODO)
                .mapNotNull { vtodo ->
                    parseVTodo(vtodo).getOrNull()
                }

            ParseResult.success(todos)
        } catch (e: Exception) {
            ParseResult.error("Failed to parse VTODO data: ${e.message}", e)
        }
    }

    /**
     * Parse a single VTODO component to ICalTodo.
     */
    fun parseVTodo(vtodo: VToDo): ParseResult<ICalTodo> {
        return try {
            // Get UID - generate random UUID if missing (non-compliant servers)
            val uid = vtodo.getPropertyOrNull<Property>("UID")?.value
                ?: UUID.randomUUID().toString()

            // Parse RECURRENCE-ID if present (modified instance)
            val recurrenceId = vtodo.getPropertyOrNull<Property>("RECURRENCE-ID")
                ?.let { parseDateTimeFromProperty(it) }

            // Generate unique importId
            val importId = ICalTodo.generateImportId(uid, recurrenceId)

            // Parse date/time properties
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

            // Parse text properties
            val summary = vtodo.getPropertyOrNull<Property>("SUMMARY")
                ?.value?.let { unescapeICalText(it) }
            val description = vtodo.getPropertyOrNull<Property>("DESCRIPTION")
                ?.value?.let { unescapeICalText(it) }
            val location = vtodo.getPropertyOrNull<Property>("LOCATION")
                ?.value?.let { unescapeICalText(it) }
            val url = vtodo.getPropertyOrNull<Property>("URL")?.value
            val geo = vtodo.getPropertyOrNull<Property>("GEO")?.value
            val classification = vtodo.getPropertyOrNull<Property>("CLASS")?.value

            // Parse numeric properties
            val statusValue = vtodo.getPropertyOrNull<Property>("STATUS")?.value
            val sequenceValue = vtodo.getPropertyOrNull<Property>("SEQUENCE")
                ?.value?.toIntOrNull() ?: 0
            val priority = vtodo.getPropertyOrNull<Property>("PRIORITY")
                ?.value?.toIntOrNull() ?: 0
            val percentComplete = vtodo.getPropertyOrNull<Property>("PERCENT-COMPLETE")
                ?.value?.toIntOrNull() ?: 0

            // Parse RRULE (only for master todos, not modified instances)
            val rrule = if (recurrenceId == null) {
                vtodo.getPropertyOrNull<Property>("RRULE")
                    ?.let { RRule.parse(it.value) }
            } else null

            // Parse categories
            val categoriesProps = vtodo.getProperties<Property>("CATEGORIES")
            val categories = categoriesProps.flatMap { cat ->
                cat.value.split(",").map { it.trim() }
            }

            // Parse ORGANIZER
            val organizer = parseTodoOrganizer(vtodo)

            // Parse ATTENDEE list
            val attendees = parseTodoAttendees(vtodo)

            // Parse VALARMs
            val alarms = vtodo.alarms.mapNotNull { valarm ->
                parseVAlarm(valarm).getOrNull()
            }

            // Collect raw properties
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

    /**
     * Parse ORGANIZER from VTODO.
     */
    private fun parseTodoOrganizer(vtodo: VToDo): Organizer? {
        val organizerProp = vtodo.getPropertyOrNull<Property>("ORGANIZER")
            ?: return null

        val value = organizerProp.value
        val email = extractEmailFromCalAddress(value)

        val cn = organizerProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("CN")?.value
        val sentBy = organizerProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("SENT-BY")
            ?.value?.let { extractEmailFromCalAddress(it) }

        return Organizer(email = email, name = cn, sentBy = sentBy)
    }

    /**
     * Parse ATTENDEE list from VTODO.
     */
    private fun parseTodoAttendees(vtodo: VToDo): List<Attendee> {
        val attendeeProps = vtodo.getProperties<Property>("ATTENDEE")

        return attendeeProps.mapNotNull { attendeeProp ->
            val value = attendeeProp.value
            val email = extractEmailFromCalAddress(value)
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
                rsvp = rsvpValue?.equals("TRUE", ignoreCase = true) ?: false
            )
        }
    }

    /**
     * Properties explicitly handled for VTODO (should NOT be in rawProperties).
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

    /**
     * Collect unhandled properties from VTODO for round-trip fidelity.
     */
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

    /**
     * Parse iCal string and return all VJOURNALs as ICalJournal objects.
     *
     * @param icalData Raw iCalendar string
     * @return List of parsed journals, each with unique importId
     */
    fun parseAllJournals(icalData: String): ParseResult<List<ICalJournal>> {
        return try {
            val unfolded = unfoldICalData(preprocessICalData(icalData))
            val builder = createCalendarBuilder()
            val calendar = builder.build(StringReader(unfolded))

            val journals = calendar.getComponents<VJournal>(Component.VJOURNAL)
                .mapNotNull { vjournal ->
                    parseVJournal(vjournal).getOrNull()
                }

            ParseResult.success(journals)
        } catch (e: Exception) {
            ParseResult.error("Failed to parse VJOURNAL data: ${e.message}", e)
        }
    }

    /**
     * Parse a single VJOURNAL component to ICalJournal.
     */
    fun parseVJournal(vjournal: VJournal): ParseResult<ICalJournal> {
        return try {
            // Get UID - generate random UUID if missing (non-compliant servers)
            val uid = vjournal.getPropertyOrNull<Property>("UID")?.value
                ?: UUID.randomUUID().toString()

            // Parse RECURRENCE-ID if present (modified instance)
            val recurrenceId = vjournal.getPropertyOrNull<Property>("RECURRENCE-ID")
                ?.let { parseDateTimeFromProperty(it) }

            // Generate unique importId
            val importId = ICalJournal.generateImportId(uid, recurrenceId)

            // Parse date/time properties
            val dtStart = vjournal.getPropertyOrNull<Property>("DTSTART")
                ?.let { parseDateTimeFromProperty(it) }
            val dtstamp = vjournal.getPropertyOrNull<Property>("DTSTAMP")
                ?.let { parseDateTimeFromProperty(it) }
            val created = vjournal.getPropertyOrNull<Property>("CREATED")
                ?.let { parseDateTimeFromProperty(it) }
            val lastModified = vjournal.getPropertyOrNull<Property>("LAST-MODIFIED")
                ?.let { parseDateTimeFromProperty(it) }

            // Parse text properties
            val summary = vjournal.getPropertyOrNull<Property>("SUMMARY")
                ?.value?.let { unescapeICalText(it) }
            val description = vjournal.getPropertyOrNull<Property>("DESCRIPTION")
                ?.value?.let { unescapeICalText(it) }
            val url = vjournal.getPropertyOrNull<Property>("URL")?.value
            val classification = vjournal.getPropertyOrNull<Property>("CLASS")?.value

            // Parse numeric properties
            val statusValue = vjournal.getPropertyOrNull<Property>("STATUS")?.value
            val sequenceValue = vjournal.getPropertyOrNull<Property>("SEQUENCE")
                ?.value?.toIntOrNull() ?: 0

            // Parse RRULE (only for master journals, not modified instances)
            val rrule = if (recurrenceId == null) {
                vjournal.getPropertyOrNull<Property>("RRULE")
                    ?.let { RRule.parse(it.value) }
            } else null

            // Parse categories
            val categoriesProps = vjournal.getProperties<Property>("CATEGORIES")
            val categories = categoriesProps.flatMap { cat ->
                cat.value.split(",").map { it.trim() }
            }

            // Parse attachments
            val attachmentProps = vjournal.getProperties<Property>("ATTACH")
            val attachments = attachmentProps.mapNotNull { it.value }

            // Parse ORGANIZER
            val organizer = parseJournalOrganizer(vjournal)

            // Parse ATTENDEE list
            val attendees = parseJournalAttendees(vjournal)

            // Collect raw properties
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

    /**
     * Parse ORGANIZER from VJOURNAL.
     */
    private fun parseJournalOrganizer(vjournal: VJournal): Organizer? {
        val organizerProp = vjournal.getPropertyOrNull<Property>("ORGANIZER")
            ?: return null

        val value = organizerProp.value
        val email = extractEmailFromCalAddress(value)

        val cn = organizerProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("CN")?.value
        val sentBy = organizerProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("SENT-BY")
            ?.value?.let { extractEmailFromCalAddress(it) }

        return Organizer(email = email, name = cn, sentBy = sentBy)
    }

    /**
     * Parse ATTENDEE list from VJOURNAL.
     */
    private fun parseJournalAttendees(vjournal: VJournal): List<Attendee> {
        val attendeeProps = vjournal.getProperties<Property>("ATTENDEE")

        return attendeeProps.mapNotNull { attendeeProp ->
            val value = attendeeProp.value
            val email = extractEmailFromCalAddress(value)
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
                rsvp = rsvpValue?.equals("TRUE", ignoreCase = true) ?: false
            )
        }
    }

    /**
     * Properties explicitly handled for VJOURNAL (should NOT be in rawProperties).
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
     * Collect unhandled properties from VJOURNAL for round-trip fidelity.
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

    /**
     * Parse result with METHOD and events.
     * Used for iTIP message processing where METHOD indicates the scheduling action.
     */
    data class CalendarParseResult(
        val method: ITipMethod?,
        val events: List<ICalEvent>
    )

    /**
     * Parse complete iCalendar data into an ICalCalendar object.
     *
     * This method extracts all components (VEVENT, VTODO, VJOURNAL) and calendar-level
     * properties (NAME, COLOR, etc.) from the iCalendar data.
     *
     * @param icalData Raw iCalendar string
     * @return ICalCalendar containing all parsed components
     */
    fun parse(icalData: String): ParseResult<ICalCalendar> {
        return try {
            val unfolded = unfoldICalData(preprocessICalData(icalData))
            val builder = createCalendarBuilder()
            val calendar = builder.build(StringReader(unfolded))

            // Parse calendar-level properties
            val prodId = calendar.getPropertyOrNull<Property>("PRODID")?.value
            val version = calendar.getPropertyOrNull<Property>("VERSION")?.value ?: "2.0"
            val calscale = calendar.getPropertyOrNull<Property>("CALSCALE")?.value ?: "GREGORIAN"
            val method = calendar.getPropertyOrNull<Property>("METHOD")?.value
            val name = calendar.getPropertyOrNull<Property>("NAME")?.value
            val source = calendar.getPropertyOrNull<Property>("SOURCE")?.value
            val color = calendar.getPropertyOrNull<Property>("COLOR")?.value
            val xWrCalname = calendar.getPropertyOrNull<Property>("X-WR-CALNAME")?.value
            val xAppleCalendarColor = calendar.getPropertyOrNull<Property>("X-APPLE-CALENDAR-COLOR")?.value

            // Parse REFRESH-INTERVAL
            val refreshInterval = calendar.getPropertyOrNull<Property>("REFRESH-INTERVAL")
                ?.value?.let { ICalAlarm.parseDuration(it) }

            // Parse all VEVENT components
            val events = calendar.getComponents<VEvent>(Component.VEVENT)
                .mapNotNull { vevent ->
                    parseVEvent(vevent).getOrNull()
                }

            // Parse all VTODO components
            val todos = calendar.getComponents<VToDo>(Component.VTODO)
                .mapNotNull { vtodo ->
                    parseVTodo(vtodo).getOrNull()
                }

            // Parse all VJOURNAL components
            val journals = calendar.getComponents<VJournal>(Component.VJOURNAL)
                .mapNotNull { vjournal ->
                    parseVJournal(vjournal).getOrNull()
                }

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
     * Parse iCal string and return METHOD along with events.
     * For iTIP scheduling messages (REQUEST, REPLY, CANCEL, etc.).
     *
     * @param icalData Raw iCalendar string
     * @return CalendarParseResult with optional method and events
     */
    fun parseWithMethod(icalData: String): ParseResult<CalendarParseResult> {
        return try {
            val unfolded = unfoldICalData(preprocessICalData(icalData))
            val builder = createCalendarBuilder()
            val calendar = builder.build(StringReader(unfolded))

            // Extract METHOD from VCALENDAR
            val methodProp = calendar.getPropertyOrNull<Property>("METHOD")
            val method = methodProp?.value?.let { ITipMethod.fromString(it) }

            val events = calendar.getComponents<VEvent>(Component.VEVENT)
                .mapNotNull { vevent ->
                    parseVEvent(vevent).getOrNull()
                }

            ParseResult.success(CalendarParseResult(method, events))
        } catch (e: Exception) {
            ParseResult.error("Failed to parse iCalendar data: ${e.message}", e)
        }
    }

    /**
     * Parse a single VEVENT component to ICalEvent.
     */
    fun parseVEvent(vevent: VEvent): ParseResult<ICalEvent> {
        return try {
            // Get UID - generate random UUID if missing (non-compliant servers)
            val uid = vevent.getPropertyOrNull<Property>("UID")?.value
                ?: UUID.randomUUID().toString()

            // Get DTSTART - fall back to DTEND if missing (non-compliant servers)
            val dtstartProp = vevent.getPropertyOrNull<Property>("DTSTART")
                ?: vevent.getPropertyOrNull<Property>("DTEND")
                ?: return ParseResult.missingProperty("DTSTART")

            val startDateTime = parseDateTimeFromProperty(dtstartProp)
            // Detect all-day: check VALUE=DATE parameter, or 8-digit date format, or no "T"
            val valueParam = dtstartProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("VALUE")?.value
            val isAllDay = valueParam == "DATE" ||
                (dtstartProp.value.length == 8 && dtstartProp.value.all { it.isDigit() }) ||
                !dtstartProp.value.contains("T")

            // Parse RECURRENCE-ID if present (modified instance)
            val recurrenceId = vevent.getPropertyOrNull<Property>("RECURRENCE-ID")
                ?.let { parseDateTimeFromProperty(it) }

            // Generate unique importId
            val importId = ICalEvent.generateImportId(uid, recurrenceId)

            // Parse end time or duration
            val dtend = vevent.getPropertyOrNull<Property>("DTEND")
                ?.let { parseDateTimeFromProperty(it) }
            val duration = vevent.getPropertyOrNull<Property>("DURATION")
                ?.let { ICalAlarm.parseDuration(it.value) }

            // Parse RRULE (only for master events, not modified instances)
            val rrule = if (recurrenceId == null) {
                vevent.getPropertyOrNull<Property>("RRULE")
                    ?.let { RRule.parse(it.value) }
            } else null

            // Parse EXDATE list
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

            // Parse VALARMs
            val alarms = vevent.alarms.mapNotNull { valarm ->
                parseVAlarm(valarm).getOrNull()
            }

            // Parse categories
            val categoriesProps = vevent.getProperties<Property>("CATEGORIES")
            val categories = categoriesProps.flatMap { cat ->
                cat.value.split(",").map { it.trim() }
            }

            // Get simple string properties
            val summary = vevent.getPropertyOrNull<Property>("SUMMARY")
                ?.value?.let { unescapeICalText(it) }
            val description = vevent.getPropertyOrNull<Property>("DESCRIPTION")
                ?.value?.let { unescapeICalText(it) }
            val location = vevent.getPropertyOrNull<Property>("LOCATION")
                ?.value?.let { unescapeICalText(it) }
            val statusValue = vevent.getPropertyOrNull<Property>("STATUS")
                ?.value
            val sequenceValue = vevent.getPropertyOrNull<Property>("SEQUENCE")
                ?.value?.toIntOrNull() ?: 0
            val transpValue = vevent.getPropertyOrNull<Property>("TRANSP")
                ?.value
            val urlValue = vevent.getPropertyOrNull<Property>("URL")
                ?.value

            // Parse PRIORITY (RFC 5545) - 0=undefined, 1=highest, 9=lowest
            val priority = vevent.getPropertyOrNull<Property>("PRIORITY")
                ?.value?.toIntOrNull()?.coerceIn(0, 9) ?: 0

            // Parse GEO (RFC 5545) - "latitude;longitude" format
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

            // Parse ORGANIZER
            val organizer = parseOrganizer(vevent)

            // Parse ATTENDEE list
            val attendees = parseAttendees(vevent)

            // Parse DTSTAMP
            val dtstamp = vevent.getPropertyOrNull<Property>("DTSTAMP")
                ?.let { parseDateTimeFromProperty(it) }

            // Parse LAST-MODIFIED
            val lastModified = vevent.getPropertyOrNull<Property>("LAST-MODIFIED")
                ?.let { parseDateTimeFromProperty(it) }

            // Parse CREATED
            val created = vevent.getPropertyOrNull<Property>("CREATED")
                ?.let { parseDateTimeFromProperty(it) }

            // Collect unknown/extra properties for round-trip fidelity
            // This includes X-* vendor extensions and any other unhandled properties
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

            // Repair: swap if DTEND < DTSTART (non-compliant servers)
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

    /**
     * Parse VALARM component.
     */
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
                trigger = Duration.ofMinutes(-15) // Default 15 min before
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

            // RFC 9074 extensions
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
     * Parse datetime from a property, handling TZID parameter.
     * Detects DATE vs DATE-TIME using multiple checks for ical4j 3.x compatibility.
     *
     * ical4j 3.x normalizes date-only values (20231215) to datetime (20231215T000000).
     * We detect DATE type using:
     * 1. VALUE=DATE parameter (if present)
     * 2. Property date object type (Date vs DateTime)
     * 3. Original value format (8-digit date only)
     */
    private fun parseDateTimeFromProperty(prop: Property): ICalDateTime {
        val value = prop.value
        val tzidParam = prop.getParameterOrNull<net.fortuna.ical4j.model.parameter.TzId>("TZID")
            ?.value

        // Check VALUE parameter
        val valueParam = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("VALUE")?.value
        val hasDateParameter = valueParam == "DATE"

        // Check if property's internal date is Date (not DateTime)
        // DtStart, DtEnd etc have getDate() which returns the temporal value
        // ical4j 4.x: DateProperty<T> returns T which is a java.time.temporal.Temporal
        val dateProperty = prop as? net.fortuna.ical4j.model.property.DateProperty<*>
        val dateObj = dateProperty?.date
        // In 4.x, LocalDate = date-only, LocalDateTime/ZonedDateTime = date-time
        val isDateType = dateObj != null && dateObj is java.time.LocalDate

        // Also check if original value was 8-digit date (before ical4j normalization)
        // This is stored in the property value before toString normalization
        val looks8DigitDate = value.length == 8 && value.all { it.isDigit() }

        val isDateOnly = hasDateParameter || isDateType || looks8DigitDate

        // If DATE type but value was normalized to include T, extract just the date
        return if (isDateOnly && value.contains("T")) {
            ICalDateTime.parse(value.substringBefore("T"), tzidParam)
        } else {
            ICalDateTime.parse(value, tzidParam)
        }
    }

    /**
     * Extract just the UID from iCal data (for delete detection during sync).
     * More efficient than full parsing when only UID is needed.
     */
    fun extractUid(icalData: String): String? {
        val uidMatch = Regex("""UID:(.+)""").find(icalData)
        return uidMatch?.groupValues?.get(1)?.trim()
    }

    /**
     * Extract all UIDs from iCal data that may contain multiple VEVENTs.
     */
    fun extractAllUids(icalData: String): List<String> {
        return Regex("""UID:(.+)""").findAll(icalData)
            .map { it.groupValues[1].trim() }
            .toList()
    }

    /**
     * Unfold iCalendar data per RFC 5545 Section 3.1.
     * Long lines are folded with CRLF followed by whitespace.
     *
     * Note: Must unfold before parsing to handle long descriptions.
     */
    private fun unfoldICalData(data: String): String {
        return data
            .replace("\r\n ", "")
            .replace("\r\n\t", "")
            .replace("\n ", "")
            .replace("\n\t", "")
    }

    /**
     * Fix known server bugs in raw iCalendar data before ical4j parsing.
     *
     * - Short UTC offsets: Synology sends +530 (should be +0530) or +5730 (should be +005730)
     * - Misplaced T in day durations: some servers send -PT2D (should be -P2D)
     */
    private fun preprocessICalData(data: String): String {
        // Fix 1: Short UTC offsets — 3-digit offsets with missing leading zero
        // RFC 5545: utc-offset = (+/-)HHMM or (+/-)HHMMSS
        // Synology sends +530 instead of +0530 (IST). Pad 3-digit to 4-digit HHMM.
        // Scoped to TZOFFSETFROM/TZOFFSETTO lines only — cannot corrupt freetext
        val offsetFixed = data.replace(
            Regex("""(TZOFFSETFROM|TZOFFSETTO):([+-])(\d{3})\s*$""", RegexOption.MULTILINE)
        ) { match ->
            val prefix = match.groupValues[1]
            val sign = match.groupValues[2]
            val digits = match.groupValues[3]
            "$prefix:$sign${digits.padStart(4, '0')}"   // +530 → +0530
        }

        // Fix 2: Misplaced T in day durations: -PT2D → -P2D
        // Scoped to TRIGGER and DURATION property lines only — cannot corrupt freetext
        // Note: only fixes pure day durations (PTnD). Mixed day+time like PT2DT3H is not
        // covered — those are rare and can be extended later if reports come in.
        return offsetFixed.replace(
            Regex("""^((?:TRIGGER|DURATION)[^:]*:)(-?)PT(\d+)D\s*$""", RegexOption.MULTILINE)
        ) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}P${match.groupValues[3]}D"
        }
    }

    /**
     * Unescape iCalendar text values per RFC 5545 Section 3.3.11.
     *
     * Important: Order matters!
     * Must unescape backslash BEFORE other escapes.
     */
    private fun unescapeICalText(text: String): String {
        return text
            .replace("\\\\", "\u0000")  // Temp placeholder for literal backslash
            .replace("\\n", "\n")
            .replace("\\N", "\n")
            .replace("\\,", ",")
            .replace("\\;", ";")
            .replace("\u0000", "\\")     // Restore literal backslash
    }

    /**
     * Parse ORGANIZER property.
     *
     * Format: ORGANIZER;CN=John Doe;SENT-BY="mailto:assistant@example.com":mailto:john@example.com
     * Extended to support RFC 6638 scheduling parameters.
     */
    private fun parseOrganizer(vevent: VEvent): Organizer? {
        val organizerProp = vevent.getPropertyOrNull<Property>("ORGANIZER")
            ?: return null

        val value = organizerProp.value
        val email = extractEmailFromCalAddress(value)

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

    /**
     * Parse SCHEDULE-STATUS values (can be comma-separated).
     */
    private fun parseScheduleStatuses(value: String): List<ScheduleStatus> {
        return value.split(",").map { ScheduleStatus.fromString(it.trim()) }
    }

    /**
     * Parse ATTENDEE properties.
     *
     * Format: ATTENDEE;CN=Jane Doe;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT:mailto:jane@example.com
     * Extended to support RFC 5545 parameters: CUTYPE, DIR, MEMBER, DELEGATED-TO, DELEGATED-FROM
     * and RFC 6638 scheduling parameters: SENT-BY, SCHEDULE-AGENT, SCHEDULE-STATUS, SCHEDULE-FORCE-SEND
     */
    private fun parseAttendees(vevent: VEvent): List<Attendee> {
        val attendeeProps = vevent.getProperties<Property>("ATTENDEE")

        return attendeeProps.mapNotNull { attendeeProp ->
            val value = attendeeProp.value
            val email = extractEmailFromCalAddress(value)
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
            val rsvp = rsvpValue?.equals("TRUE", ignoreCase = true) ?: false

            // RFC 5545 parameters
            val cutypeValue = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("CUTYPE")
                ?.value
            val cutype = CUType.fromString(cutypeValue)

            val dir = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("DIR")
                ?.value?.removeSurrounding("\"")

            val member = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("MEMBER")
                ?.value?.removeSurrounding("\"")

            val delegatedToValue = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("DELEGATED-TO")
                ?.value
            val delegatedTo = parseMailtoList(delegatedToValue)

            val delegatedFromValue = attendeeProp.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("DELEGATED-FROM")
                ?.value
            val delegatedFrom = parseMailtoList(delegatedFromValue)

            // RFC 6638 scheduling parameters
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
     * Parse comma-separated mailto: list.
     * Handles format: "mailto:a@b.com","mailto:c@d.com"
     */
    private fun parseMailtoList(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(",")
            .map { it.trim().removeSurrounding("\"").let { addr -> extractEmailFromCalAddress(addr) } }
            .filter { it.isNotEmpty() }
    }

    /**
     * Extract email from CAL-ADDRESS format.
     *
     * Input: "mailto:john@example.com" or "MAILTO:john@example.com"
     * Output: "john@example.com"
     */
    private fun extractEmailFromCalAddress(calAddress: String): String {
        return calAddress
            .trim()
            .removePrefix("\"")
            .removeSuffix("\"")
            .replace(Regex("^mailto:", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    // ============ RFC 7986 Property Parsing ============

    /**
     * Parse IMAGE property (RFC 7986).
     *
     * Format: IMAGE;VALUE=URI;DISPLAY=BADGE;FMTTYPE=image/png:https://example.com/logo.png
     *
     * @param prop The IMAGE property from ical4j
     * @return Parsed ICalImage or null if invalid
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
     * Parse CONFERENCE property (RFC 7986).
     *
     * Format: CONFERENCE;VALUE=URI;FEATURE=VIDEO,AUDIO;LABEL=Join:https://zoom.us/j/123
     *
     * @param prop The CONFERENCE property from ical4j
     * @return Parsed ICalConference or null if invalid
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
     * Parse LINK property (RFC 9253).
     *
     * Format: LINK;REL=alternate;FMTTYPE=text/html;TITLE="Details":https://example.com/event
     *
     * @param prop The LINK property from ical4j
     * @return Parsed ICalLink or null if invalid
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
     * Parse RELATED-TO property (RFC 9253).
     *
     * Format: RELATED-TO;RELTYPE=PARENT:parent-event-uid
     *
     * @param prop The RELATED-TO property from ical4j
     * @return Parsed ICalRelation or null if invalid
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
     * Properties that are explicitly handled and should NOT be in rawProperties.
     * These are parsed into dedicated ICalEvent fields.
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
        // Note: BEGIN, END, and VALARM are components, not properties
    )

    /**
     * Collect unhandled properties for round-trip fidelity.
     *
     * This preserves:
     * - X-* vendor extensions (X-APPLE-STRUCTURED-LOCATION, X-GOOGLE-CONFERENCE, etc.)
     * - Any other RFC 5545 property not explicitly handled
     *
     * Properties with parameters are stored with parameters in the key:
     * "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-TITLE=Apple Park" -> "geo:37.33..."
     *
     * @param vevent The VEvent component to extract properties from
     * @return Map of property name (with params) to value
     */
    private fun collectRawProperties(vevent: VEvent): Map<String, String> {
        val raw = mutableMapOf<String, String>()

        for (prop in vevent.getAllProperties()) {
            val propName = prop.name?.uppercase() ?: continue

            // Skip properties we handle explicitly
            if (propName in handledProperties) continue

            // Skip component markers
            if (propName == "BEGIN" || propName == "END") continue

            // Get parameters list via ical4j API
            val paramList = prop.getParameters()

            // Build property key with parameters for properties that have them
            val key = if (paramList.isEmpty()) {
                propName
            } else {
                // Include parameters in key for X-* properties with parameters
                // e.g., "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-TITLE=Apple Park"
                val paramStr = paramList.joinToString(";") { param ->
                    "${param.name}=${param.value}"
                }
                "$propName;$paramStr"
            }

            // Store the value
            val value = prop.value
            if (!value.isNullOrBlank()) {
                raw[key] = value
            }
        }

        return raw
    }

    // ============ VFREEBUSY Parsing ============

    /**
     * Parse VFREEBUSY component from iCal data.
     *
     * @param icalData Raw iCalendar string containing VFREEBUSY
     * @return Parsed ICalFreeBusy or null if not found/invalid
     */
    fun parseFreeBusy(icalData: String): ICalFreeBusy? {
        return try {
            val unfolded = unfoldICalData(preprocessICalData(icalData))
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
     * Parse a VFreeBusy component.
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

        // Parse ORGANIZER (reuse from VEvent parsing logic)
        val organizerProp = vfb.getPropertyOrNull<Property>("ORGANIZER")
        val organizer = organizerProp?.let { prop ->
            val email = extractEmailFromCalAddress(prop.value)
            val cn = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("CN")?.value
            val sentBy = prop.getParameterOrNull<net.fortuna.ical4j.model.Parameter>("SENT-BY")
                ?.value?.let { extractEmailFromCalAddress(it) }
            Organizer(email = email, name = cn, sentBy = sentBy)
        }

        // Parse ATTENDEEs
        val attendeeProps = vfb.getProperties<Property>("ATTENDEE")
        val attendees = attendeeProps.mapNotNull { attendeeProp ->
            val email = extractEmailFromCalAddress(attendeeProp.value)
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

        // Parse FREEBUSY periods
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
     * Parse FREEBUSY periods from property value.
     * Format: "20231215T090000Z/20231215T100000Z,20231215T140000Z/20231215T150000Z"
     * or with duration: "20231215T090000Z/PT1H"
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
