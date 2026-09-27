package org.onekash.kashcal.sync.parser.icaldav

import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.model.Classification
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalCalendar
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.model.Transparency
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.icaldav.parser.ICalParser
import org.onekash.icaldav.util.DurationUtils
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.identity.matchesAttendee
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper.exclusiveEndTs
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper.formatGeo
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper.iCalColorFor
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper.parseTimestampCsv
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper.resolveZone

/**
 * Patches a stored server body with an Event's fields, or generates fresh ICS when there is none.
 *
 * Patching keeps everything from the original that the user didn't change:
 * - alarms the form never showed: END-relative ones, and START-relative ones past
 *   [MAX_DISPLAYED_ALARMS] unless the user clears every reminder ([mergeAlarms])
 * - attendees and organizer, unless an organizer push passes its own set ([patchToICalEvent])
 * - unknown X-* properties and server-specific extensions
 *
 * Fresh ICS (no rawIcal) carries only the fields KashCal models.
 */
object IcsPatcher {

    /**
     * Number of reminders the event form shows. START-relative alarms at original positions
     * below it are the user-visible set, reconciled against the stored reminder list so
     * deletions stick; alarms at or beyond it were never shown and are kept verbatim, unless
     * the user clears every reminder.
     *
     * Must track the UI's `MAX_REMINDERS` (ui/shared/FormConstants.kt). Defined here to avoid a
     * sync -> UI dependency.
     */
    private const val MAX_DISPLAYED_ALARMS = 5

    private val parser = ICalParser()
    private val generator = ICalGenerator(
        prodId = "-//KashCal//KashCal 2.0//EN",
        includeAppleExtensions = true
    )

    /**
     * The series VEVENT of a resource, else its first VEVENT.
     *
     * A series with changed occurrences is one resource holding several
     * VEVENTs, and RFC 5545 puts no order on them, so the changed occurrence
     * (RECURRENCE-ID) may come first. Patching that one with the series'
     * fields would keep its RECURRENCE-ID and drop the repeat rule, turning
     * the series into a single event on the server. A resource holding only
     * changed occurrences (RFC 4791 §4.1) has no series VEVENT and keeps the
     * first one.
     */
    private fun List<ICalEvent>.seriesOrFirst(): ICalEvent? =
        firstOrNull { !it.isModifiedInstance() } ?: firstOrNull()

    /**
     * A changed occurrence's instance time: its RECURRENCE-ID normalized
     * against the series DTSTART the way pull stores it in
     * [Event.originalInstanceTime], or the raw RECURRENCE-ID instant when
     * [seriesStart] is null (a file with no series VEVENT, as pull keys it).
     */
    private fun ICalEvent.instanceKey(seriesStart: ICalDateTime?): Long? =
        ICalEventMapper.normalizeRecurrenceId(recurrenceId, seriesStart)?.timestamp

    /**
     * Answers an invitation: changes only this account's PARTSTAT on one
     * VEVENT of the server's file and sends everything else back exactly as
     * the server sent it.
     *
     * The file is edited as text, not rebuilt. A PUT replaces the whole
     * resource, so the reply must carry every VEVENT: the series and each
     * changed occurrence, with the organizer's times, titles, attendees and
     * properties KashCal doesn't model. Only the one ATTENDEE property is
     * rewritten (its PARTSTAT parameter replaced, or added) and refolded;
     * every other line, VTIMEZONE and VALARM included, is untouched.
     *
     * [occurrence] picks the VEVENT: null answers the series (wherever it
     * sits in the file, else the first VEVENT); otherwise the changed
     * occurrence whose instance time, keyed as pull stores
     * [Event.originalInstanceTime], equals it. Answering the series leaves
     * the changed occurrences' own answers as they are.
     *
     * SEQUENCE is not bumped: an attendee's PARTSTAT-only PUT must not bump
     * it (RFC 5546 §2.1.4). Some servers (iCloud) bump it on the wire; the
     * next pull accepts that, but the client never sends a higher SEQUENCE.
     *
     * @return Patched ICS string, or null if:
     *   - `rawIcal` is null or fails to parse,
     *   - the requested changed occurrence isn't in the file,
     *   - the account's address does not appear as an ATTENDEE on that VEVENT,
     *   - the text's VEVENTs can't be lined up with the parsed ones.
     *   The caller then shows an error; adding a new ATTENDEE instead would
     *   make the server route it through iTIP.
     */
    fun patchAttendeeReply(
        rawIcal: String?,
        account: org.onekash.kashcal.data.db.entity.Account,
        partstat: String,
        occurrence: Long? = null
    ): String? {
        if (rawIcal == null) return null
        val parsed = parser.parseAllEvents(rawIcal).getOrNull() ?: return null
        val seriesIndex = parsed.indexOfFirst { !it.isModifiedInstance() }
        val index = if (occurrence == null) {
            // The series wherever it sits, else the first VEVENT (as [seriesOrFirst]).
            seriesIndex.takeIf { it >= 0 } ?: 0
        } else {
            val seriesStart = parsed.getOrNull(seriesIndex)?.dtStart
            parsed.indexOfFirst { it.isModifiedInstance() && it.instanceKey(seriesStart) == occurrence }
        }
        if (parsed.getOrNull(index) == null) return null

        return ReplyText.setPartstat(
            ics = rawIcal,
            veventIndex = index,
            veventCount = parsed.size,
            partstat = org.onekash.icaldav.model.PartStat.fromString(partstat.trim()).toICalString(),
            isSelf = account::matchesAttendee
        )
    }

    /**
     * Patches [rawIcal] with [event]'s fields, or generates fresh ICS when [rawIcal] is null or
     * fails to parse. [attendees] follows [patchToICalEvent].
     */
    fun patch(
        rawIcal: String?,
        event: Event,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee>? = null
    ): String {
        val icalEvent = patchToICalEvent(rawIcal, event, attendees)
            ?: return generateFresh(event, attendees ?: emptyList())
        return generator.generate(icalEvent, method = null, includeVTimezone = true)
    }

    /**
     * Merges [event]'s fields into the VEVENT parsed from [rawIcal], keeping its unknown
     * properties, and returns null when there's no rawIcal or it fails to parse so the caller
     * falls back to fresh generation.
     *
     * Attendees: a null [attendees] keeps the original's attendees as parsed, in the server's shape
     * (mailto:/urn:uuid: addresses, server additions); the generator re-emits them, so they aren't
     * byte-for-byte. Export and a push whose attendees table is empty rely on this. A non-null
     * [attendees] is the set the user edited (organizer push) and replaces the block for an
     * organized event; an empty list clears every ATTENDEE. Without it an attendee added to or
     * removed from a synced event would never reach the server.
     *
     * For a series row the VEVENT patched is the series one, even when the
     * file lists a changed occurrence first ([seriesOrFirst]).
     */
    private fun patchToICalEvent(
        rawIcal: String?,
        event: Event,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee>? = null
    ): ICalEvent? {
        if (rawIcal == null) return null
        val parsed = parser.parseAllEvents(rawIcal).getOrNull() ?: return null
        return patchToICalEvent(parsed, event, attendees)
    }

    /** [patchToICalEvent] over an already-parsed stored body. */
    private fun patchToICalEvent(
        parsed: List<ICalEvent>,
        event: Event,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee>?
    ): ICalEvent? {
        // A series row patches the series VEVENT. An exception row keeps the
        // first VEVENT, as it always has: pull stores the whole resource on
        // every row, and exception rows only reach this path through export.
        val original = (if (event.originalEventId == null) parsed.seriesOrFirst() else parsed.firstOrNull())
            ?: return null
        val zone = resolveZone(event.timezone)
        val endZone = resolveZone(event.endTimezone) ?: zone
        val mergedAlarms = mergeAlarms(original.alarms, event.reminders)
        // Replace the ATTENDEE block with the caller's set only for an organized
        // event (an organizer on the row or the original), like the fresh path's
        // organizer guard, so a push without an organizer never rewrites the
        // server's attendee list. A null set or no organizer keeps the original.
        val isOrganized = !event.organizerEmail.isNullOrBlank() || original.organizer != null
        val mergedAttendees = if (attendees != null && isOrganized) {
            attendees.map { with(EventToICalEventMapper) { it.toICalAttendee() } }
        } else {
            original.attendees
        }
        // Same gate as mergedAttendees. The original's ORGANIZER always wins, in
        // the server's mailto:/urn:uuid:/CN shape. When it has none (an event
        // first synced without invitees), the row's organizer is added so an
        // attendee added later ships with an ORGANIZER the server can schedule
        // (RFC 6638 §3); without it no invite is delivered. Without an attendee
        // set (export, or a push with an empty attendees table) the original is
        // kept: never synthesize an ORGANIZER into a body that had none.
        val mergedOrganizer = if (attendees != null && isOrganized) {
            original.organizer
                ?: EventToICalEventMapper.organizerFor(event.organizerEmail, event.organizerName)
        } else {
            original.organizer
        }

        return original.copy(
            uid = event.uid,
            summary = event.title,
            description = event.description,
            location = event.location,
            dtStart = ICalDateTime.fromTimestamp(event.startTs, zone, event.isAllDay),
            dtEnd = ICalDateTime.fromTimestamp(exclusiveEndTs(event), endZone, event.isAllDay),
            isAllDay = event.isAllDay,
            status = EventStatus.fromString(event.status),
            transparency = Transparency.fromString(event.transp),
            classification = Classification.fromString(event.classification),
            // The stored SEQUENCE, verbatim. EventWriter decides the bump
            // (SequenceBumper) so a non-scheduling edit doesn't re-notify
            // attendees; bumping here too would double-count it.
            sequence = event.sequence,
            rrule = event.rrule?.let { RRule.parse(it) },
            exdates = parseTimestampCsv(event.exdate, zone, event.isAllDay),
            rdates = parseTimestampCsv(event.rdate, zone, event.isAllDay),
            alarms = mergedAlarms,
            priority = event.priority,
            geo = formatGeo(event.geoLat, event.geoLon),
            color = iCalColorFor(event.color),
            url = event.url,
            categories = event.categories.orEmpty(),
            attendees = mergedAttendees,
            organizer = mergedOrganizer,
            lastModified = ICalDateTime.fromTimestamp(event.updatedAt, null, false)
            // Kept from the original: unknownPropertyLines (written verbatim),
            // rawProperties, created, dtstamp. Organizer and attendees follow
            // mergedOrganizer and mergedAttendees above.
        )
    }

    /**
     * Generates ICS for an event with no stored body (created locally, or the body is missing
     * or unparseable).
     *
     * @param attendees ATTENDEE rows to emit (organizer push). The default emits none; the
     *   share-card path relies on this so a shared .ics never leaks an attendee list.
     */
    fun generateFresh(
        event: Event,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee> = emptyList()
    ): String {
        return generator.generate(
            EventToICalEventMapper.toICalEvent(event, attendees),
            method = null,
            includeVTimezone = true
        )
    }

    /**
     * Serializes [event], patching its rawIcal when it has one and generating fresh otherwise.
     *
     * @param attendees the edited ATTENDEE rows (organizer push), or null to keep the original's
     *   attendees (export, or a push with an empty attendees table). A non-null list, empty
     *   included, replaces the set on both paths for an organized event.
     */
    fun serialize(
        event: Event,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee>? = null
    ): String {
        return patch(event.rawIcal, event, attendees)
    }

    /**
     * Serializes a recurring master and its exceptions into one VCALENDAR without attendee
     * rows; the export path uses it.
     *
     * RFC 5545 bundles exceptions with the master in the same VCALENDAR, sharing its UID and
     * distinguished by RECURRENCE-ID. Passing null attendees keeps the master's original
     * ATTENDEE block instead of clearing it; exceptions are regenerated with none.
     */
    fun serializeWithExceptions(master: Event, exceptions: List<Event>): String =
        serializeWithExceptions(
            master = master,
            masterAttendees = null,
            exceptionsWithAttendees = exceptions.map { it to null }
        )

    /**
     * Serializes a recurring master and its exceptions, emitting each VEVENT's own ATTENDEE set.
     *
     * Each exception carries its own attendee list (RFC 5545 §3.8.4.1); the organizer push
     * path loads these from the attendees table and passes them here so they round-trip.
     *
     * @param masterAttendees ATTENDEE rows for the master VEVENT, as in [serialize]
     * @param exceptionsWithAttendees each exception paired with its ATTENDEE rows, or null
     *   to emit none for it
     */
    fun serializeWithExceptions(
        master: Event,
        masterAttendees: List<org.onekash.kashcal.data.db.entity.Attendee>?,
        exceptionsWithAttendees: List<Pair<Event, List<org.onekash.kashcal.data.db.entity.Attendee>?>>
    ): String {
        if (exceptionsWithAttendees.isEmpty()) {
            return serialize(master, masterAttendees)
        }

        // The stored server body, parsed once for the series patch and for
        // each changed occurrence's unknown properties.
        val stored = master.rawIcal?.let { parser.parseAllEvents(it).getOrNull() }

        // Master: the patch path follows [patchToICalEvent]; the fresh fallback
        // emits the supplied set, or none.
        val masterICalEvent = stored?.let { patchToICalEvent(it, master, masterAttendees) }
            ?: EventToICalEventMapper.toICalEvent(master, masterAttendees ?: emptyList())
        // Stored changed occurrences keyed by instance time, normalized against
        // the stored series DTSTART the way pull did when it stored each row's
        // originalInstanceTime (the series row's own start may have moved since).
        val storedSeries = stored?.firstOrNull { !it.isModifiedInstance() }
        val storedSeriesStart = storedSeries?.dtStart ?: EventToICalEventMapper.dtStartOf(master)
        val storedOccurrences = buildMap<Long?, ICalEvent> {
            stored.orEmpty().filter { it.isModifiedInstance() }.forEach {
                putIfAbsent(it.instanceKey(storedSeriesStart), it)
            }
        }

        // Exceptions are regenerated from their rows; a null attendee set emits
        // none. Their unknown properties come from the stored body when it holds
        // their VEVENT ([unknownPropertySource]).
        val exceptionICalEvents = exceptionsWithAttendees.map { (exception, attendees) ->
            val rebuilt = EventToICalEventMapper.toICalEvent(master, exception, attendees ?: emptyList())
            val source = stored?.let { unknownPropertySource(storedOccurrences, storedSeries, master, exception) }
            source?.let {
                rebuilt.copy(unknownPropertyLines = it.unknownPropertyLines, rawProperties = it.rawProperties)
            } ?: rebuilt
        }

        return generator.generate(
            ICalCalendar(
                prodId = null, // falls back to generator instance prodId
                events = listOf(masterICalEvent) + exceptionICalEvents
            ),
            includeVTimezone = true
        )
    }

    /**
     * The stored VEVENT whose unknown properties a changed occurrence should
     * carry, or null to keep the row's own map.
     *
     * Editing an occurrence builds its row from the series row, so the row's
     * map is the series' map even for an occurrence the server already holds.
     * The occurrence is therefore found in the stored body by RECURRENCE-ID,
     * normalized against the stored series DTSTART as pull did when it
     * stored [Event.originalInstanceTime] (the series row's own start may have
     * been moved locally since). An occurrence the body doesn't hold yet but
     * whose row was copied from the series row starts from the series' lines,
     * as the row's copied map did, only now unchanged.
     *
     * A stored changed occurrence only counts when it has the series' UID: a
     * series split off with "this and future" is a copy of the old row, stored
     * body included, and must not pick up the old series' occurrences.
     */
    private fun unknownPropertySource(
        storedOccurrences: Map<Long?, ICalEvent>,
        series: ICalEvent?,
        master: Event,
        exception: Event
    ): ICalEvent? =
        exception.originalInstanceTime?.let { storedOccurrences[it] }?.takeIf { it.uid == master.uid }
            ?: series?.takeIf { exception.importId == master.importId }

    /**
     * Merges the user's reminders ([Event.reminders]) with the original alarms from rawIcal.
     *
     * - ACTION:NONE sentinels (RFC 9074) are dropped first: they are never reminders and must
     *   never be re-emitted or turned into a DISPLAY alarm.
     * - END-relative alarms (TRIGGER;RELATED=END) are kept verbatim. The form's reminder list
     *   holds only START-relative offsets, so user edits must never reconcile or erase them.
     * - Null or empty reminders clear every START-relative alarm, hidden ones included;
     *   END-relative ones survive.
     * - The displayed window (START-relative positions below [MAX_DISPLAYED_ALARMS], after
     *   NONE removal) is what the form showed, so the reminder list is the source of truth for
     *   it: a displayed alarm whose offset matches a reminder is kept with its action,
     *   description and uid; one no reminder matches was deleted and is not re-added.
     * - Otherwise START-relative alarms at positions at or beyond [MAX_DISPLAYED_ALARMS] were
     *   never shown and are kept verbatim.
     * - A reminder matching no displayed or hidden alarm becomes a new DISPLAY alarm.
     */
    private fun mergeAlarms(
        originalAlarms: List<ICalAlarm>,
        userReminders: List<String>?
    ): List<ICalAlarm> {
        // Drop NONE sentinels before splitting by position: a NONE often lands at a
        // high index, and filtering after the split would keep it as a hidden alarm.
        // Then split off the END-relative alarms, which are always kept.
        val (endRelative, startRelative) = originalAlarms
            .filter { it.action != AlarmAction.NONE }
            .partition { it.triggerRelatedToEnd }

        // No reminders: clear every START-relative alarm, hidden ones included, but keep
        // the END-relative ones, which the form can't list and so can't have deleted.
        if (userReminders.isNullOrEmpty()) {
            return endRelative
        }

        // Hidden alarms are kept verbatim; the displayed window is reconciled.
        val displayed = startRelative.take(MAX_DISPLAYED_ALARMS).toMutableList()
        val hidden = startRelative.drop(MAX_DISPLAYED_ALARMS)

        val result = mutableListOf<ICalAlarm>()

        for (reminderStr in userReminders) {
            val userDuration = try {
                DurationUtils.parse(reminderStr)
            } catch (_: Exception) {
                null
            }

            if (userDuration == null) continue

            // Reconcile by trigger, not position: the stored reminder list is
            // sorted by magnitude while the original alarms are in document
            // order, so pairing by position would attach one alarm's
            // action/description/uid to another reminder's time (an EMAIL alarm
            // firing at a DISPLAY alarm's offset). The matched alarm is consumed
            // so a duplicate user offset falls through to a new alarm.
            val matchIndex = displayed.indexOfFirst { it.trigger == userDuration }
            if (matchIndex >= 0) {
                val match = displayed.removeAt(matchIndex)
                result.add(match.copy(trigger = userDuration, triggerAbsolute = null))
            } else if (hidden.any { it.trigger == userDuration }) {
                // A hidden alarm already covers this offset and is appended
                // below; a new DISPLAY alarm would fire the same time twice.
                continue
            } else {
                // A new or changed reminder: no alarm has this offset.
                result.add(ICalAlarm(
                    action = AlarmAction.DISPLAY,
                    trigger = userDuration,
                    triggerAbsolute = null,
                    description = "Reminder",
                    summary = null
                ))
            }
        }

        // Displayed alarms no reminder matched were deleted in the form and are not
        // re-added. Hidden and END-relative alarms are kept.
        result.addAll(hidden)
        result.addAll(endRelative)

        return result
    }

}
