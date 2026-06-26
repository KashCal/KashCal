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

    /**
     * Number of reminders the event form surfaces to the user. Alarms at original
     * positions below this count are part of the user-visible set (deletions are
     * honored against the stored reminder list); alarms at or beyond it were never
     * shown and are preserved verbatim — the same "preserve what the user never saw"
     * rationale that keeps server-authoritative attendees/X-props on the patch path.
     *
     * Must track the UI's MAX_REMINDERS (ui/shared/FormConstants.kt). Defined locally
     * to avoid a UI -> sync layer dependency.
     */
    private const val MAX_DISPLAYED_ALARMS = 5

    private val parser = ICalParser()
    private val generator = ICalGenerator(
        prodId = "-//KashCal//KashCal 2.0//EN",
        includeAppleExtensions = true
    )

    /**
     * Patch only the current user's PARTSTAT in the server's ICS body.
     *
     * Every original ATTENDEE row (including ORGANIZER, SUMMARY,
     * DESCRIPTION, RRULE, X-* extensions, SEQUENCE) is preserved
     * verbatim; only the attendee whose address matches `account` has
     * its PARTSTAT replaced.
     *
     * SEQUENCE is intentionally NOT bumped — RFC 5546 §2.1.4: attendee
     * PARTSTAT-only PUT must not bump SEQUENCE. Some servers (iCloud)
     * auto-bump on the wire; we tolerate that on the next pull, but we
     * never assert a higher SEQUENCE on the client side.
     *
     * @return Patched ICS string, or null if:
     *   - `rawIcal` is null or fails to parse,
     *   - the account's address does not appear as an ATTENDEE on the event.
     *   The caller surfaces an explanatory error rather than fabricating a
     *   new ATTENDEE row that the server would route through iTIP.
     */
    fun patchAttendeeReply(
        rawIcal: String?,
        account: org.onekash.kashcal.data.db.entity.Account,
        partstat: String
    ): String? {
        if (rawIcal == null) return null
        val original = parser.parseAllEvents(rawIcal).getOrNull()?.firstOrNull()
            ?: return null

        // Find the ATTENDEE row that matches this account's identity.
        // Account.matchesAttendee canonicalizes both sides via AddressNormalizer.
        val matchedIndex = original.attendees.indexOfFirst { attendee ->
            account.matchesAttendee(attendee.email)
        }
        if (matchedIndex < 0) return null

        val canonicalPartstat = org.onekash.icaldav.model.PartStat.fromString(partstat)
        val updatedAttendees = original.attendees.toMutableList().also { list ->
            list[matchedIndex] = list[matchedIndex].copy(partStat = canonicalPartstat)
        }

        // SEQUENCE preserved verbatim — see kdoc above.
        val patched = original.copy(attendees = updatedAttendees)
        return generator.generate(patched, method = null, includeVTimezone = true)
    }

    /**
     * Patch existing ICS data with Event changes.
     *
     * If rawIcal is null or parsing fails, generates fresh ICS.
     *
     * @param rawIcal Original ICS data from server
     * @param event Event with updated values
     * @return Patched or fresh ICS string
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
     * Produce a patched [ICalEvent] by merging the `event` fields into the
     * `ICalEvent` parsed from `rawIcal`, preserving attendees, organizer,
     * and raw X-* properties from the original.
     *
     * Attendees: when [attendees] is null the original ICS's ATTENDEE block is
     * preserved verbatim (its authoritative wire form — correct
     * mailto:/urn:uuid: shape, server additions, line folding); this is the
     * RSVP/export contract. When [attendees] is non-null it is the
     * authoritative set the user edited (organizer push) and REPLACES the
     * original block — an empty list clears all ATTENDEEs. Without this, an
     * attendee add/remove on a server-synced event (which has rawIcal) would
     * never reach the wire.
     *
     * Returns null when there's no rawIcal or parsing fails, so the caller
     * can fall back to a fresh generation path.
     */
    private fun patchToICalEvent(
        rawIcal: String?,
        event: Event,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee>? = null
    ): ICalEvent? {
        if (rawIcal == null) return null
        val original = parser.parseAllEvents(rawIcal).getOrNull()?.firstOrNull() ?: return null
        val zone = resolveZone(event.timezone)
        val endZone = resolveZone(event.endTimezone) ?: zone
        val mergedAlarms = mergeAlarms(original.alarms, event.reminders)
        // Replace the ATTENDEE block with the caller's authoritative set only
        // for an organized event (organizer present) — mirrors the fresh path's
        // attendeesIfOrganized guard so a non-organizer push never rewrites the
        // server's attendee list. null caller set, or no organizer, preserves
        // the original verbatim.
        val isOrganized = !event.organizerEmail.isNullOrBlank() || original.organizer != null
        val mergedAttendees = if (attendees != null && isOrganized) {
            attendees.map { with(EventToICalEventMapper) { it.toICalAttendee() } }
        } else {
            original.attendees
        }
        // Mirror the mergedAttendees gate: only on an organizer push (the caller
        // hands an authoritative attendee set for an organized event) do we touch
        // the organizer. The server's ORGANIZER is authoritative (correct
        // mailto:/urn:uuid:/CN shape) so it always wins; only when the original
        // has none — an event first synced without invitees — surface the
        // organizer the coordinator stamped on the entity, so an attendee added
        // later ships with an ORGANIZER the server can auto-schedule (RFC 6638
        // §3). Without this the added ATTENDEE reaches the wire with no ORGANIZER
        // and no invite is delivered. Non-push paths (cosmetic edit, export with
        // attendees == null) preserve the original verbatim — they must not
        // synthesize an ORGANIZER into a body that never had one.
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
            // Serialize the stored SEQUENCE verbatim. The decision to bump
            // lives upstream in EventWriter (via SequenceBumper) so a
            // non-scheduling edit doesn't spuriously re-notify attendees;
            // bumping here too would double-count it on the wire.
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
            // PRESERVED from original: rawProperties, created, dtstamp.
            // Organizer: the original's wins when present; the entity's organizer
            // fills in only when the original had none (see mergedOrganizer above).
            // Attendees: preserved verbatim when caller passed null, else replaced
            // with the caller's authoritative set (see mergedAttendees above).
        )
    }

    /**
     * Generate fresh ICS for an event with no existing rawIcal.
     *
     * This is used for:
     * - Events created locally
     * - Events where original ICS was lost
     *
     * @param event Event to serialize
     * @param attendees Optional ATTENDEE rows to emit (organizer push). Default
     *   empty emits no ATTENDEE — the share-card path relies on this so it never
     *   leaks an attendee list into a shared .ics.
     * @return Complete ICS string
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
     * Serialize an event to ICS format.
     *
     * If the event has rawIcal, patches it. Otherwise generates fresh.
     * This is the main entry point for serialization.
     *
     * @param event Event to serialize
     * @param attendees Authoritative ATTENDEE rows (organizer push), or null to
     *   preserve the original ICS's attendees (RSVP/export). A non-null list
     *   (incl. empty) replaces the set on both the patch and fresh paths.
     * @return Complete ICS string
     */
    fun serialize(
        event: Event,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee>? = null
    ): String {
        return patch(event.rawIcal, event, attendees)
    }

    /**
     * Serialize a master recurring event with its exceptions.
     *
     * RFC 5545 requires exception events to be bundled with the master
     * in the same VCALENDAR, sharing the same UID but with RECURRENCE-ID.
     *
     * No-attendee overload — used by the export/share path. Passes null so the
     * master's and each exception's original ATTENDEE block is preserved
     * verbatim (export never rewrites attendees) rather than cleared.
     *
     * @param master The master recurring event
     * @param exceptions Exception events (modified occurrences)
     * @return Complete ICS string containing master and all exceptions
     */
    fun serializeWithExceptions(master: Event, exceptions: List<Event>): String =
        serializeWithExceptions(
            master = master,
            masterAttendees = null,
            exceptionsWithAttendees = exceptions.map { it to null }
        )

    /**
     * Serialize a master recurring event and its exceptions, emitting each
     * VEVENT's own ATTENDEE set.
     *
     * Each exception carries its own per-instance attendee list (RFC 5545
     * §3.8.4.1); the organizer push path loads these from the attendees table
     * and passes them here so they round-trip — previously exception attendees
     * were silently dropped on push.
     *
     * @param master The master recurring event
     * @param masterAttendees ATTENDEE rows for the master VEVENT
     * @param exceptionsWithAttendees Each exception paired with its ATTENDEE rows
     * @return Complete ICS string containing master and all exceptions
     */
    fun serializeWithExceptions(
        master: Event,
        masterAttendees: List<org.onekash.kashcal.data.db.entity.Attendee>?,
        exceptionsWithAttendees: List<Pair<Event, List<org.onekash.kashcal.data.db.entity.Attendee>?>>
    ): String {
        if (exceptionsWithAttendees.isEmpty()) {
            return serialize(master, masterAttendees)
        }

        // Master: patch path applies the caller's attendee set when non-null
        // (organizer edited the series) and preserves the original verbatim when
        // null (export); fresh fallback uses the supplied set (or none).
        val masterICalEvent = patchToICalEvent(master.rawIcal, master, masterAttendees)
            ?: EventToICalEventMapper.toICalEvent(master, masterAttendees ?: emptyList())
        // Exceptions are always regenerated from the entity; a null exception
        // set means "no per-instance attendees supplied" → emit none.
        val exceptionICalEvents = exceptionsWithAttendees.map { (exception, attendees) ->
            EventToICalEventMapper.toICalEvent(master, exception, attendees ?: emptyList())
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
     * Merge the user's reminder edits with the original alarms from rawIcal.
     *
     * Strategy:
     * - ACTION:NONE sentinels (RFC 9074) are dropped first — they are never reminders
     *   and must never be re-emitted or laundered into a DISPLAY alarm.
     * - END-relative alarms (TRIGGER;RELATED=END) are partitioned out and preserved
     *   verbatim. The pull path doesn't surface them in the form (the reminder list is
     *   START-relative offsets), so they must never be reconciled against — or erased
     *   by — START-relative user edits. Same "preserve what the user never saw"
     *   rationale that keeps hidden alarms/attendees/X-props on the patch path.
     * - null/empty reminders = user wants no DISPLAYED alarms → clear the displayed
     *   set, but END-relative alarms still survive (they were never shown to delete).
     * - The DISPLAYED window (START-relative positions 0 until MAX_DISPLAYED_ALARMS,
     *   after NONE removal) is what the form showed the user, so the stored reminder
     *   list is authoritative for it: user reminders update those alarms' triggers in
     *   order, and any displayed alarm the user dropped is NOT re-added. Original
     *   action/description/uid are preserved for the alarms that survive.
     * - START-relative alarms at positions >= MAX_DISPLAYED_ALARMS were never shown to
     *   the user, so they are preserved verbatim.
     * - Extra user reminders beyond the available displayed originals become new
     *   DISPLAY alarms.
     *
     * @param originalAlarms Alarms parsed from rawIcal
     * @param userReminders User's edited reminders from event.reminders (may be null)
     * @return Merged alarm list
     */
    private fun mergeAlarms(
        originalAlarms: List<ICalAlarm>,
        userReminders: List<String>?
    ): List<ICalAlarm> {
        // Drop RFC 9074 ACTION:NONE sentinels FIRST, so position-based splitting and
        // preservation below operate only on real alarms. (A NONE often lands at a high
        // index; filtering after the split would let it survive as a "hidden" alarm.)
        // Then partition END-relative alarms out: they are never surfaced as reminders,
        // so they are always preserved verbatim and never reconciled/cleared.
        val (endRelative, startRelative) = originalAlarms
            .filter { it.action != AlarmAction.NONE }
            .partition { it.triggerRelatedToEnd }

        // User cleared all reminders → clear all START-relative (displayed) alarms, but
        // keep END-relative alarms the form never showed (so a save can't silently erase
        // them from the server).
        if (userReminders.isNullOrEmpty()) {
            return endRelative
        }

        // Alarms the form never displayed (position >= MAX_DISPLAYED_ALARMS) are
        // preserved verbatim; the displayed window is reconciled against user edits.
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

            // Reconcile by TRIGGER, not position: the stored reminder list is
            // sorted by magnitude while the original alarms are in document
            // order, so position-pairing would attach one alarm's
            // action/description/uid to a different reminder's time (e.g. an
            // EMAIL alarm firing at a DISPLAY alarm's offset). Match the
            // displayed alarm with the same offset to preserve its metadata;
            // consume it so duplicate user offsets fall through to fresh alarms.
            val matchIndex = displayed.indexOfFirst { it.trigger == userDuration }
            if (matchIndex >= 0) {
                val match = displayed.removeAt(matchIndex)
                result.add(match.copy(trigger = userDuration, triggerAbsolute = null))
            } else if (hidden.any { it.trigger == userDuration }) {
                // A genuinely-hidden alarm already covers this offset and is
                // appended verbatim below — don't also synthesize a fresh
                // DISPLAY alarm, or the same time would fire twice.
                continue
            } else {
                // No alarm (displayed or hidden) at this offset (new/changed
                // reminder) → fresh DISPLAY alarm.
                result.add(ICalAlarm(
                    action = AlarmAction.DISPLAY,
                    trigger = userDuration,
                    triggerAbsolute = null,
                    description = "Reminder",
                    summary = null
                ))
            }
        }

        // Displayed alarms past the user's reminder count were deleted in the UI — they
        // are intentionally NOT re-added. Genuinely-hidden alarms are preserved, as are
        // END-relative alarms the form never surfaced.
        result.addAll(hidden)
        result.addAll(endRelative)

        return result
    }

}
