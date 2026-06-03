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
import java.time.Duration
import java.time.ZoneId

/**
 * Maps KashCal `Event` entity to icaldav `ICalEvent`.
 *
 * Inverse of `ICalEventMapper` (server -> DB). Used by the sync push path
 * (`IcsPatcher`) and the export path (`IcsExporter`) so both generate ICS
 * from Events through a single, tested code path.
 */
object EventToICalEventMapper {

    /**
     * Map a standalone or master event (no RECURRENCE-ID).
     *
     * For master events with `rrule`, include the parsed RRULE; exceptions
     * should be emitted separately via [toICalEvent] with master context.
     *
     * @param attendees Optional Room rows to emit as ATTENDEE properties.
     *   Defaulted empty so existing callers continue to compile and emit no
     *   attendees. Organizer-side push paths pass the real list.
     */
    fun toICalEvent(event: Event, attendees: List<org.onekash.kashcal.data.db.entity.Attendee> = emptyList()): ICalEvent {
        val zone = resolveZone(event.timezone)
        val endZone = resolveZone(event.endTimezone) ?: zone
        val endTs = exclusiveEndTs(event)
        // RFC 5545 §3.8.5: recurring events use DTSTART+DURATION (DST-safe
        // across occurrences), non-recurring use DTSTART+DTEND. Preserve
        // stored Event.duration when populated; otherwise compute from
        // endTs-startTs.
        val duration: Duration? = if (event.isRecurring) {
            DurationUtils.parse(event.duration) ?: Duration.ofMillis(endTs - event.startTs)
        } else null
        return ICalEvent(
            uid = event.uid,
            importId = event.importId ?: event.uid,
            summary = event.title,
            description = event.description,
            location = event.location,
            dtStart = ICalDateTime.fromTimestamp(event.startTs, zone, event.isAllDay),
            dtEnd = if (event.isRecurring) null else ICalDateTime.fromTimestamp(endTs, endZone, event.isAllDay),
            duration = duration,
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
            attendees = attendees.map { it.toICalAttendee() },
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
     * Map an exception (modified occurrence) to an ICalEvent that carries the
     * master's UID plus a RECURRENCE-ID built from the exception's
     * `originalInstanceTime`. RRULE/EXDATE/RDATE are cleared per RFC 5545:
     * exceptions describe a single instance, not a recurrence.
     *
     * When `exception.originalInstanceTime` is null, the importId falls through
     * to "master.uid:RECID:null" — this is documented pre-existing behavior
     * (IcsPatcher.kt:274 before extraction) and is preserved intentionally here.
     *
     * @param attendees Optional per-exception ATTENDEE rows. Defaulted
     *   empty so existing call sites continue to behave as before; the
     *   push path passes the real list to preserve per-exception
     *   attendee state on recurring-event push.
     */
    fun toICalEvent(
        master: Event,
        exception: Event,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee> = emptyList()
    ): ICalEvent = toICalEvent(masterUid = master.uid, exception = exception, attendees = attendees)

    /**
     * Convenience overload for callers that only have the master UID.
     *
     * @param attendees Optional per-exception ATTENDEE rows. Defaulted empty.
     */
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
            attendees = attendees.map { it.toICalAttendee() },
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
     * Resolve an IANA TZID string to a [ZoneId], returning null for blank input
     * or non-IANA values (Windows IDs, legacy offsets). Shared by every app-side
     * path that maps an `Event.timezone` field into icaldav types.
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
     * Parse an RRULE string, tolerating malformed stored values.
     * `RRule.parse` throws on missing FREQ; corrupt input should not crash
     * push or export paths.
     */
    internal fun parseRruleOrNull(rrule: String?): RRule? {
        if (rrule.isNullOrBlank()) return null
        return runCatching { RRule.parse(rrule) }.getOrNull()
    }

    /**
     * All-day events store `endTs` as inclusive (23:59:59.999 of the last day).
     * RFC 5545 requires exclusive DTEND (next day 00:00:00). Add 1 ms.
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

    private fun organizerFor(email: String?, name: String?): Organizer? {
        if (email == null) return null
        return Organizer(email = email, name = name, sentBy = null)
    }

    /**
     * Translate a Room [org.onekash.kashcal.data.db.entity.Attendee] into the
     * icaldav-core [org.onekash.icaldav.model.Attendee]. Inverse of
     * `ICalEventMapper.toRoomEntity` (the pull-side mapping).
     *
     * Asymmetry: the Room `address` field carries the `mailto:` prefix verbatim
     * (since servers may also emit `urn:uuid:` or principal-relative paths);
     * icaldav-core's `email` is the bare local-part-plus-domain. Strip
     * `mailto:` here so the generator re-prefixes it on emit.
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
