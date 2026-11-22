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
import org.onekash.kashcal.domain.rrule.SeriesMove
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper.exclusiveEndTs
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper.formatGeo
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper.iCalColorFor
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper.parseTimestampCsv
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper.resolveZone
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper.writeZone

/**
 * Patches an event's stored ICS (a server's body, or an imported file's text; the caller reads
 * it from [org.onekash.kashcal.data.db.EventIcsSource]) with the event's fields, or generates
 * fresh ICS when there is none.
 *
 * Patching keeps everything from the original that the user didn't change:
 * - alarms the form never showed: END-relative ones, and START-relative ones past
 *   [MAX_DISPLAYED_ALARMS] unless the user clears every reminder ([mergeAlarms])
 * - attendees and organizer, unless an organizer push passes its own set ([patchToICalEvent])
 * - unknown X-* properties and server-specific extensions, as the original lines
 *
 * Fresh ICS (no stored ICS) carries only the fields KashCal models.
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
     * Returns [rawIcal] with STATUS:CANCELLED, the attendee's copy of an imported CANCEL
     * (RFC 5546 §3.2.5), edited as text like [patchAttendeeReply]; SEQUENCE is left as is.
     *
     * With [occurrence] null every VEVENT is cancelled: a CANCEL without RECURRENCE-ID cancels
     * the whole series, changed occurrences included. Otherwise only the changed occurrence keyed
     * by [occurrence] (as pull stores [Event.originalInstanceTime]); when the file has none, an
     * override VEVENT built from the series with [override]'s times is added.
     *
     * @return null when the file doesn't parse, its VEVENTs can't be lined up with the text, or
     *   an occurrence with no VEVENT comes without [override]
     */
    fun patchCancelled(rawIcal: String, occurrence: Long?, override: Event? = null): String? {
        val parsed = parser.parseAllEvents(rawIcal).getOrNull() ?: return null
        if (parsed.isEmpty()) return null
        if (occurrence == null) {
            return ReplyText.setStatus(rawIcal, parsed.indices.toSet(), parsed.size, "CANCELLED")
        }
        val seriesIndex = parsed.indexOfFirst { !it.isModifiedInstance() }
        val seriesStart = parsed.getOrNull(seriesIndex)?.dtStart
        val index = parsed.indexOfFirst { it.isModifiedInstance() && it.instanceKey(seriesStart) == occurrence }
        if (index >= 0) return ReplyText.setStatus(rawIcal, setOf(index), parsed.size, "CANCELLED")
        if (seriesIndex < 0 || override == null) return null
        val (recurrenceId, start, end) = if (override.isAllDay) {
            val day = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd").withZone(java.time.ZoneOffset.UTC)
            Triple(
                "RECURRENCE-ID;VALUE=DATE:${day.format(java.time.Instant.ofEpochMilli(occurrence))}",
                "DTSTART;VALUE=DATE:${day.format(java.time.Instant.ofEpochMilli(override.startTs))}",
                // Room keeps an all-day end inclusive (1 ms before midnight); DTEND is exclusive.
                "DTEND;VALUE=DATE:${day.format(java.time.Instant.ofEpochMilli(override.endTs + 1))}"
            )
        } else {
            val utc = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC)
            Triple(
                "RECURRENCE-ID:${utc.format(java.time.Instant.ofEpochMilli(occurrence))}",
                "DTSTART:${utc.format(java.time.Instant.ofEpochMilli(override.startTs))}",
                "DTEND:${utc.format(java.time.Instant.ofEpochMilli(override.endTs))}"
            )
        }
        return ReplyText.appendOverride(
            rawIcal, seriesIndex, parsed.size, listOf(recurrenceId, start, end, "STATUS:CANCELLED")
        )
    }

    /**
     * Returns each VEVENT's STATUS in [rawIcal], keyed by instance as pull stores
     * [Event.originalInstanceTime] (null for the series), CONFIRMED when a VEVENT has none; null
     * when the file doesn't parse.
     */
    fun statusesByInstance(rawIcal: String): Map<Long?, String>? {
        val parsed = parser.parseAllEvents(rawIcal).getOrNull() ?: return null
        val seriesStart = parsed.firstOrNull { !it.isModifiedInstance() }?.dtStart
        return parsed.associate { e ->
            (if (e.isModifiedInstance()) e.instanceKey(seriesStart) else null) to
                e.status.toICalString().ifBlank { "CONFIRMED" }
        }
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
     * Attendees: a null [attendees] keeps a server copy's attendees as parsed, in the server's
     * shape (mailto:/urn:uuid: addresses, server additions); the generator re-emits them, so they
     * aren't byte-for-byte. Push and export pass null for a row whose attendees table is empty (a
     * row synced before the table existed keeps its guests only in the copy) and rely on this. A
     * file's copy ([Event.holdsFileCopy]: the row is an unsynced import) gives none instead, as
     * fresh generation does: its
     * ATTENDEE block is the file's, not the user's current set. A non-null [attendees] is the row's
     * current set (an organizer push, or an export) and replaces the block for an organized event;
     * an empty list clears every ATTENDEE. Without it an attendee added to or removed from a synced
     * event would never reach the server.
     *
     * For a series row the VEVENT patched is the series one, even when the file lists a changed
     * occurrence first ([seriesOrFirst]). For a changed occurrence row see [originalFor].
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
        val original = originalFor(parsed, event) ?: return occurrenceFromSeries(parsed, event, attendees)
        val zone = writeZone(event)
        val endZone = resolveZone(event.endTimezone) ?: zone
        val mergedAlarms = mergeAlarms(original.alarms, event.reminders)
        // Replace the ATTENDEE block with the caller's set only for an organized
        // event (an organizer on the row or the original), like the fresh path's
        // organizer guard, so a push without an organizer never rewrites the
        // server's attendee list. A null set or no organizer keeps the original,
        // except that a file's copy keeps none (see the KDoc above).
        val isOrganized = !event.organizerEmail.isNullOrBlank() || original.organizer != null
        val mergedAttendees = if (attendees != null && isOrganized) {
            attendees.map { with(EventToICalEventMapper) { it.toICalAttendee() } }
        } else if (event.isUnsyncedImport) {
            emptyList()
        } else {
            original.attendees
        }
        // Same gate as mergedAttendees. The original's ORGANIZER always wins, in
        // the server's mailto:/urn:uuid:/CN shape. When it has none (an event
        // first synced without invitees), the row's organizer is added so an
        // attendee added later ships with an ORGANIZER the server can schedule
        // (RFC 6638 §3); without it no invite is delivered. Without an attendee
        // set (a push or export of a row with an empty attendees table) the original is
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
     * Returns the VEVENT of [parsed] that [event] patches. A series row takes the series VEVENT
     * ([seriesOrFirst]). A changed occurrence row only reaches this path on its own (export of
     * that occurrence): with a file's copy it takes the VEVENT for its own instance with its own
     * UID, or none when the copy has no such VEVENT ([occurrenceFromSeries]); a split series
     * keeps the old series' copy, whose occurrences have the old UID. With a server copy it keeps
     * the first VEVENT: the copy is the whole resource, its own or its series'.
     */
    private fun originalFor(parsed: List<ICalEvent>, event: Event): ICalEvent? {
        if (event.originalEventId == null) return parsed.seriesOrFirst()
        if (!event.isUnsyncedImport) return parsed.firstOrNull()
        val seriesStart = parsed.firstOrNull { !it.isModifiedInstance() && it.uid == event.uid }?.dtStart
        return parsed.firstOrNull {
            it.isModifiedInstance() && it.uid == event.uid && it.instanceKey(seriesStart) == event.originalInstanceTime
        }
    }

    /**
     * Builds a changed occurrence exported on its own whose file copy has no VEVENT for it (one the
     * user changed in the form, whose row the form copied from the series row): from its row, with
     * the lines of the copy's series VEVENT, which [unknownPropertySource] gives a form-changed
     * occurrence inside its series too. After a split that VEVENT carries the old series' UID; its
     * lines are still the ones the split copied. Null for any other row, which the caller then
     * generates fresh.
     */
    private fun occurrenceFromSeries(
        parsed: List<ICalEvent>,
        event: Event,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee>?
    ): ICalEvent? {
        if (event.originalEventId == null || !event.isUnsyncedImport) return null
        val series = parsed.firstOrNull { !it.isModifiedInstance() } ?: return null
        return EventToICalEventMapper.toICalEvent(event.uid, event, attendees ?: emptyList())
            .copy(unknownPropertyLines = series.unknownPropertyLines, rawProperties = series.rawProperties)
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
     * Serializes [event], patching [ics] (its stored ICS) when there is one and generating fresh
     * otherwise.
     *
     * @param attendees the row's ATTENDEE rows (organizer push or export), or null for none given
     *   (a row with an empty attendees table, or a caller that writes no rows), which keeps a
     *   server copy's attendees ([patchToICalEvent]). A non-null list, empty included, replaces the
     *   set on both paths for an organized event.
     */
    fun serialize(
        event: Event,
        ics: String?,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee>? = null
    ): String {
        return patch(ics, event, attendees)
    }

    /**
     * Serializes a recurring master and its exceptions into one VCALENDAR without attendee
     * rows.
     *
     * RFC 5545 bundles exceptions with the master in the same VCALENDAR, sharing its UID and
     * distinguished by RECURRENCE-ID. The master gets null attendees ([patchToICalEvent]);
     * exceptions are regenerated with none. [masterIcs] is the series' stored ICS.
     */
    fun serializeWithExceptions(master: Event, masterIcs: String?, exceptions: List<Event>): String =
        serializeWithExceptions(
            master = master,
            masterIcs = masterIcs,
            masterAttendees = null,
            exceptionsWithAttendees = exceptions.map { it to null },
            ownIcs = emptyMap()
        )

    /**
     * Serializes a recurring master and its exceptions, emitting each VEVENT's own ATTENDEE set.
     *
     * Each exception carries its own attendee list (RFC 5545 §3.8.4.1); the organizer push
     * path loads these from the attendees table and passes them here so they round-trip.
     *
     * @param masterIcs the series' stored ICS, or null
     * @param masterAttendees ATTENDEE rows for the master VEVENT, as in [serialize]
     * @param exceptionsWithAttendees each exception paired with its ATTENDEE rows, or null
     *   to emit none for it
     * @param ownIcs the exceptions' own stored ICS by row id ([ownCopySource])
     */
    fun serializeWithExceptions(
        master: Event,
        masterIcs: String?,
        masterAttendees: List<org.onekash.kashcal.data.db.entity.Attendee>?,
        exceptionsWithAttendees: List<Pair<Event, List<org.onekash.kashcal.data.db.entity.Attendee>?>>,
        ownIcs: Map<Long, String>
    ): String {
        if (exceptionsWithAttendees.isEmpty()) {
            return serialize(master, masterIcs, masterAttendees)
        }
        return generator.generate(
            ICalCalendar(
                prodId = null, // falls back to generator instance prodId
                events = toICalEvents(master, masterIcs, masterAttendees, exceptionsWithAttendees, ownIcs)
            ),
            includeVTimezone = true
        )
    }

    /**
     * Returns the VEVENTs [serializeWithExceptions] writes for [master] and its exceptions, the
     * series first, for a caller that puts several series into one VCALENDAR (whole-calendar
     * export). A master without exceptions is patched or generated as [serialize] does.
     */
    fun toICalEvents(
        master: Event,
        masterIcs: String?,
        masterAttendees: List<org.onekash.kashcal.data.db.entity.Attendee>?,
        exceptionsWithAttendees: List<Pair<Event, List<org.onekash.kashcal.data.db.entity.Attendee>?>>,
        ownIcs: Map<Long, String>
    ): List<ICalEvent> {
        // The stored copy, parsed once for the series patch and for each
        // changed occurrence's unknown properties.
        val stored = masterIcs?.let { parser.parseAllEvents(it).getOrNull() }

        // Master: the patch path follows [patchToICalEvent]; the fresh fallback
        // emits the supplied set, or none.
        val masterICalEvent = stored?.let { patchToICalEvent(it, master, masterAttendees) }
            ?: EventToICalEventMapper.toICalEvent(master, masterAttendees ?: emptyList())
        // Stored changed occurrences keyed by instance time, normalized against
        // the stored series DTSTART the way pull did when it stored each row's
        // originalInstanceTime, then carried through a move of the series row since
        // ([storedKeyMove]), which re-keyed the rows.
        val storedSeries = stored?.firstOrNull { !it.isModifiedInstance() }
        val storedSeriesStart = storedSeries?.dtStart ?: EventToICalEventMapper.dtStartOf(master)
        val keyMove by lazy { storedSeries?.let { storedKeyMove(it, master) } }
        val storedOccurrences = buildMap<Long?, ICalEvent> {
            stored.orEmpty().filter { it.isModifiedInstance() }.forEach {
                val key = it.instanceKey(storedSeriesStart)
                val moved = key?.let { k -> (keyMove?.mapKey(k) as? SeriesMove.Key.Moved)?.ts }
                putIfAbsent(moved ?: key, it)
            }
        }

        // Exceptions are regenerated from their rows; a null attendee set emits
        // none. Their unknown properties come from the row's own file copy when a
        // newer file changed that occurrence alone ([ownCopySource]), else from the
        // stored copy when it holds their VEVENT ([unknownPropertySource]).
        val exceptionICalEvents = exceptionsWithAttendees.map { (exception, attendees) ->
            val rebuilt = EventToICalEventMapper.toICalEvent(master, exception, attendees ?: emptyList())
            val source = ownCopySource(exception, ownIcs[exception.id], masterIcs, master, storedSeriesStart)
                ?: stored?.let { unknownPropertySource(storedOccurrences, storedSeries, master, exception) }
            source?.let {
                rebuilt.copy(unknownPropertyLines = it.unknownPropertyLines, rawProperties = it.rawProperties)
            } ?: rebuilt
        }

        return listOf(masterICalEvent) + exceptionICalEvents
    }

    /**
     * Returns the move from the stored [series] (its DTSTART, zone and rule) to [master]'s, which
     * [SeriesMove.planSave] applied to the changed occurrences' keys when the series row moved, or
     * null when the stored copy has no rule or the move can't be planned (keys are then used as
     * stored). A series that hasn't moved maps every key to itself.
     */
    private fun storedKeyMove(series: ICalEvent, master: Event): SeriesMove? {
        val rule = series.rrule?.toICalString() ?: return null
        val masterRule = master.rrule ?: return null
        val start = series.dtStart.timestamp
        return SeriesMove.plan(
            SeriesMove.Inputs(
                rule = rule, dtstartTs = start, anchorTs = start,
                zone = ICalEventMapper.storedZoneOf(series), isAllDay = series.dtStart.isDate,
                newStartTs = master.startTs, newZone = master.timezone, newIsAllDay = master.isAllDay,
                typedRule = masterRule.takeUnless {
                    SeriesMove.sameRuleForMove(rule, ICalEventMapper.storedZoneOf(series), series.dtStart.isDate, it, master.timezone, master.isAllDay)
                },
            )
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
     *
     * The stored body is the series row's copy, a server's or an imported file's;
     * an occurrence row's own file copy is looked at first ([ownCopySource]).
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
     * Returns the VEVENT for [exception]'s instance in [own], the exception row's own stored ICS,
     * when that is an imported file's copy ([Event.holdsFileCopy]) other than [masterIcs]: a newer
     * file that changed only this occurrence leaves the series row's copy as it was
     * (`EventWriter.applyImportedUpdate`, `EventWriter.createImportedException`). Keyed as
     * [unknownPropertySource] keys, against the own copy's series DTSTART or, when it holds none,
     * [seriesStart]; only a VEVENT with the series' UID counts, as there.
     */
    private fun ownCopySource(
        exception: Event,
        own: String?,
        masterIcs: String?,
        master: Event,
        seriesStart: ICalDateTime,
    ): ICalEvent? {
        val file = own?.takeIf { exception.holdsFileCopy(it) && it != masterIcs } ?: return null
        val parsed = parser.parseAllEvents(file).getOrNull() ?: return null
        val start = parsed.firstOrNull { !it.isModifiedInstance() }?.dtStart ?: seriesStart
        return parsed.firstOrNull {
            it.isModifiedInstance() && it.uid == master.uid &&
                it.instanceKey(start) == exception.originalInstanceTime
        }
    }

    /**
     * Merges the user's reminders ([Event.reminders]) with the original alarms from the stored ICS.
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
