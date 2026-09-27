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
import org.onekash.icaldav.util.DurationUtils
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper.toICalEvent
import org.onekash.kashcal.ui.shared.EventColorPalette
import java.time.ZoneId

/**
 * Maps a Room [Event] to an icaldav [ICalEvent], the inverse of [ICalEventMapper].
 *
 * The push path ([IcsPatcher], `PushStrategy`) and export (`IcsExporter`) all build ICS from
 * events through this mapper, so they serialize the same way.
 */
object EventToICalEventMapper {

    /**
     * Returns the DTSTART a Room [Event] serializes to.
     *
     * The one source of this conversion, so serialization here and RECURRENCE-ID value-type
     * normalization on pull and push agree byte for byte.
     */
    fun dtStartOf(event: Event): ICalDateTime =
        ICalDateTime.fromTimestamp(event.startTs, resolveZone(event.timezone), event.isAllDay)

    /**
     * Maps a standalone or master event, without RECURRENCE-ID.
     *
     * A master keeps its parsed RRULE; its exceptions are mapped separately by the
     * master-context overloads.
     *
     * @param attendees Room rows to emit as ATTENDEE properties; empty emits none.
     */
    fun toICalEvent(event: Event, attendees: List<org.onekash.kashcal.data.db.entity.Attendee> = emptyList()): ICalEvent {
        val zone = resolveZone(event.timezone)
        val endZone = resolveZone(event.endTimezone) ?: zone
        val endTs = exclusiveEndTs(event)
        // RFC 5545 §3.6.1 permits DTEND or DURATION, never both. Always emit DTEND, as the
        // patch path and the exception overload do: at least one major server rejects an
        // EXDATE update on a bounded recurring scheduling object written with
        // DTSTART+DURATION, while DTEND is accepted across servers.
        return ICalEvent(
            uid = event.uid,
            importId = event.importId ?: event.uid,
            summary = event.title,
            description = event.description,
            location = event.location,
            dtStart = dtStartOf(event),
            dtEnd = ICalDateTime.fromTimestamp(endTs, endZone, event.isAllDay),
            duration = null,
            isAllDay = event.isAllDay,
            status = EventStatus.fromString(event.status),
            sequence = event.sequence,
            rrule = parseRruleOrNull(event.rrule),
            exdates = parseTimestampCsv(event.exdate, zone, event.isAllDay),
            rdates = parseTimestampCsv(event.rdate, zone, event.isAllDay),
            classification = Classification.fromString(event.classification),
            recurrenceId = null,
            alarms = remindersToAlarms(event.reminders),
            categories = event.categories.orEmpty(),
            organizer = organizerFor(event.organizerEmail, event.organizerName),
            attendees = attendeesIfOrganized(event.organizerEmail, attendees),
            color = iCalColorFor(event.color),
            dtstamp = ICalDateTime.fromTimestamp(event.dtstamp, null, false),
            lastModified = ICalDateTime.fromTimestamp(event.updatedAt, null, false),
            created = ICalDateTime.fromTimestamp(event.createdAt, null, false),
            transparency = Transparency.fromString(event.transp),
            url = event.url,
            priority = event.priority,
            geo = formatGeo(event.geoLat, event.geoLon),
            rawProperties = event.extraProperties.orEmpty()
        )
    }

    /**
     * Maps an exception to a VEVENT with the master's UID and a RECURRENCE-ID from the
     * exception's `originalInstanceTime`.
     *
     * RRULE, EXDATE and RDATE are cleared: an exception describes one occurrence (RFC 5545).
     * With a null `originalInstanceTime` there is no RECURRENCE-ID and a null importId becomes
     * `<masterUid>:RECID:null`; this is kept deliberately.
     *
     * @param attendees the exception's own ATTENDEE rows; the push path passes them so each
     *   exception keeps its attendee state. Empty emits none.
     */
    fun toICalEvent(
        master: Event,
        exception: Event,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee> = emptyList()
    ): ICalEvent = toICalEvent(masterUid = master.uid, exception = exception, attendees = attendees)

    /** Maps an exception when only the master UID is known; see the overload above. */
    fun toICalEvent(
        masterUid: String,
        exception: Event,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee> = emptyList()
    ): ICalEvent {
        val zone = resolveZone(exception.timezone)
        val endZone = resolveZone(exception.endTimezone) ?: zone
        val recurrenceId = exception.originalInstanceTime?.let {
            ICalDateTime.fromTimestamp(it, zone, exception.isAllDay)
        }
        return ICalEvent(
            uid = masterUid,
            importId = exception.importId ?: "$masterUid:RECID:${exception.originalInstanceTime}",
            summary = exception.title,
            description = exception.description,
            location = exception.location,
            dtStart = ICalDateTime.fromTimestamp(exception.startTs, zone, exception.isAllDay),
            dtEnd = ICalDateTime.fromTimestamp(exclusiveEndTs(exception), endZone, exception.isAllDay),
            duration = null,
            isAllDay = exception.isAllDay,
            status = EventStatus.fromString(exception.status),
            sequence = exception.sequence,
            rrule = null,
            exdates = emptyList(),
            rdates = emptyList(),
            classification = Classification.fromString(exception.classification),
            recurrenceId = recurrenceId,
            alarms = remindersToAlarms(exception.reminders),
            categories = exception.categories.orEmpty(),
            organizer = organizerFor(exception.organizerEmail, exception.organizerName),
            attendees = attendeesIfOrganized(exception.organizerEmail, attendees),
            color = iCalColorFor(exception.color),
            dtstamp = ICalDateTime.fromTimestamp(exception.dtstamp, null, false),
            lastModified = ICalDateTime.fromTimestamp(exception.updatedAt, null, false),
            created = ICalDateTime.fromTimestamp(exception.createdAt, null, false),
            transparency = Transparency.fromString(exception.transp),
            url = exception.url,
            priority = exception.priority,
            geo = formatGeo(exception.geoLat, exception.geoLon),
            rawProperties = exception.extraProperties.orEmpty()
        )
    }

    /**
     * Resolves an IANA TZID to a [ZoneId], or null for blank input or a non-IANA value
     * (Windows IDs, legacy offsets).
     */
    fun resolveZone(tzid: String?): ZoneId? {
        if (tzid.isNullOrBlank()) return null
        return try {
            ZoneId.of(tzid)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses a stored RRULE, or null if malformed. `RRule.parse` throws on a missing FREQ,
     * and a corrupt stored value must not crash push or export.
     */
    internal fun parseRruleOrNull(rrule: String?): RRule? {
        if (rrule.isNullOrBlank()) return null
        return runCatching { RRule.parse(rrule) }.getOrNull()
    }

    /**
     * Returns the exclusive end: all-day events store `endTs` inclusive (23:59:59.999 of the
     * last day), and RFC 5545 DTEND is exclusive (next day 00:00), so add 1 ms.
     */
    internal fun exclusiveEndTs(event: Event): Long {
        return if (event.isAllDay && event.endTs >= event.startTs) event.endTs + 1 else event.endTs
    }

    internal fun parseTimestampCsv(csv: String?, zone: ZoneId?, isDate: Boolean): List<ICalDateTime> {
        if (csv.isNullOrBlank()) return emptyList()
        return csv.split(",").mapNotNull { ts ->
            ts.trim().toLongOrNull()?.let { ICalDateTime.fromTimestamp(it, zone, isDate) }
        }
    }

    internal fun formatGeo(lat: Double?, lon: Double?): String? {
        if (lat == null || lon == null) return null
        return "$lat;$lon"
    }

    internal fun iCalColorFor(argb: Int?): String? {
        if (argb == null) return null
        return EventColorPalette.nameForHex(argb)
            ?: String.format(java.util.Locale.ROOT, "#%06X", argb and 0xFFFFFF)
    }

    internal fun organizerFor(email: String?, name: String?): Organizer? {
        if (email.isNullOrBlank()) return null
        return Organizer(email = email, name = name, sentBy = null)
    }

    /**
     * Returns the attendees to emit, or none without an organizer: RFC 6638 §3.1 requires
     * ORGANIZER alongside ATTENDEE, and conformant servers reject a PUT with ATTENDEE alone.
     *
     * An account that can't schedule (non-email login) resolves no organizer. The attendee
     * picker is hidden for such accounts; this is the data-layer backstop for other callers.
     */
    private fun attendeesIfOrganized(
        organizerEmail: String?,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee>
    ): List<org.onekash.icaldav.model.Attendee> {
        if (organizerEmail.isNullOrBlank()) return emptyList()
        return attendees.map { it.toICalAttendee() }
    }

    /**
     * Converts a Room [org.onekash.kashcal.data.db.entity.Attendee] to an icaldav
     * [org.onekash.icaldav.model.Attendee], the inverse of the pull-side mapping in
     * [ICalEventMapper].
     *
     * The Room `address` keeps its `mailto:` prefix (servers also emit `urn:uuid:` or
     * principal-relative paths), while icaldav's `email` is bare. Strip `mailto:` here; the
     * generator adds it back on emit.
     */
    internal fun org.onekash.kashcal.data.db.entity.Attendee.toICalAttendee():
        org.onekash.icaldav.model.Attendee {
        val bareEmail = org.onekash.kashcal.util.AddressNormalizer.stripMailto(address)
        return org.onekash.icaldav.model.Attendee(
            email = bareEmail,
            name = displayName,
            partStat = org.onekash.icaldav.model.PartStat.fromString(partstat),
            role = org.onekash.icaldav.model.AttendeeRole.fromString(role),
            rsvp = rsvp,
            cutype = org.onekash.icaldav.model.CUType.fromString(cutype),
            member = member,
            delegatedTo = delegatedTo,
            delegatedFrom = delegatedFrom,
            sentBy = sentBy,
            scheduleAgent = scheduleAgent?.let {
                org.onekash.icaldav.model.ScheduleAgent.fromString(it)
            },
            scheduleStatus = scheduleStatus?.let {
                listOf(org.onekash.icaldav.model.ScheduleStatus.fromString(it))
            },
            scheduleForceSend = scheduleForceSend?.let {
                org.onekash.icaldav.model.ScheduleForceSend.fromString(it)
            }
        )
    }

    private fun remindersToAlarms(reminders: List<String>?): List<ICalAlarm> {
        if (reminders.isNullOrEmpty()) return emptyList()
        return reminders.mapNotNull { reminderStr ->
            try {
                DurationUtils.parse(reminderStr)?.let { duration ->
                    ICalAlarm(
                        action = AlarmAction.DISPLAY,
                        trigger = duration,
                        triggerAbsolute = null,
                        description = "Reminder",
                        summary = null
                    )
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
