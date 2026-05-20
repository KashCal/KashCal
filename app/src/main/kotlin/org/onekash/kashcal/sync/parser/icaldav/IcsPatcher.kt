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
    fun patch(rawIcal: String?, event: Event): String {
        val icalEvent = patchToICalEvent(rawIcal, event) ?: return generateFresh(event)
        return generator.generate(icalEvent, method = null, includeVTimezone = true)
    }

    /**
     * Produce a patched [ICalEvent] by merging the `event` fields into the
     * `ICalEvent` parsed from `rawIcal`, preserving attendees, organizer,
     * and raw X-* properties from the original.
     *
     * Returns null when there's no rawIcal or parsing fails, so the caller
     * can fall back to a fresh generation path.
     */
    private fun patchToICalEvent(rawIcal: String?, event: Event): ICalEvent? {
        if (rawIcal == null) return null
        val original = parser.parseAllEvents(rawIcal).getOrNull()?.firstOrNull() ?: return null
        val zone = resolveZone(event.timezone)
        val endZone = resolveZone(event.endTimezone) ?: zone
        val mergedAlarms = mergeAlarms(original.alarms, event.reminders)

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
            sequence = event.sequence + 1,
            rrule = event.rrule?.let { RRule.parse(it) },
            exdates = parseTimestampCsv(event.exdate, zone, event.isAllDay),
            rdates = parseTimestampCsv(event.rdate, zone, event.isAllDay),
            alarms = mergedAlarms,
            priority = event.priority,
            geo = formatGeo(event.geoLat, event.geoLon),
            color = iCalColorFor(event.color),
            url = event.url,
            categories = event.categories.orEmpty(),
            lastModified = ICalDateTime.fromTimestamp(event.updatedAt, null, false)
            // PRESERVED from original: attendees, organizer, rawProperties, created, dtstamp
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
     * @return Complete ICS string
     */
    fun generateFresh(event: Event): String {
        return generator.generate(
            EventToICalEventMapper.toICalEvent(event),
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

        val masterICalEvent = patchToICalEvent(master.rawIcal, master)
            ?: EventToICalEventMapper.toICalEvent(master)
        val exceptionICalEvents = exceptions.map { EventToICalEventMapper.toICalEvent(master, it) }

        return generator.generate(
            ICalCalendar(
                prodId = null, // falls back to generator instance prodId
                events = listOf(masterICalEvent) + exceptionICalEvents
            ),
            includeVTimezone = true
        )
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
            } catch (_: Exception) {
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

}
