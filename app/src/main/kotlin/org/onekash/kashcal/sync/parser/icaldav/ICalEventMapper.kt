package org.onekash.kashcal.sync.parser.icaldav

import android.graphics.Color
import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.util.DurationUtils
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.ui.shared.EventColorPalette
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Holds a pulled [Event] and the [Attendee] rows from the same VEVENT.
 *
 * The attendees carry `eventId = 0L` because the event isn't upserted yet; callers must
 * `attendees.map { it.copy(eventId = savedId) }` once the upsert returns its ID.
 */
data class MappedEntity(
    val event: Event,
    val attendees: List<Attendee>
)

/**
 * Maps an icaldav [ICalEvent] to a Room [Event] and its attendees, the inverse of
 * [EventToICalEventMapper].
 *
 * Both models hold epoch milliseconds. Exceptions keep their `importId`
 * (`uid:RECID:datetime`), alarms become RFC 5545 trigger strings, and unknown properties and
 * the raw ICS are kept for round trips.
 */
object ICalEventMapper {

    /**
     * Maps [icalEvent] to a synced Room event plus its ATTENDEE rows; see [MappedEntity] for
     * the attendee `eventId` rule.
     *
     * @param rawIcal the original ICS, kept for round trips.
     * @param masterDtStart the master's DTSTART when [icalEvent] is an exception, used to
     *   normalize a value-type-mismatched RECURRENCE-ID ([normalizeRecurrenceId]). Null for
     *   masters or when the master isn't available; the RECURRENCE-ID is then stored as is.
     */
    fun toEntity(
        icalEvent: ICalEvent,
        rawIcal: String?,
        calendarId: Long,
        caldavUrl: String?,
        etag: String?,
        masterDtStart: ICalDateTime? = null,
    ): MappedEntity {
        val now = System.currentTimeMillis()

        val effectiveEnd = icalEvent.effectiveEnd()

        // RFC 5545 all-day DTEND is exclusive; Room stores the inclusive end, 1 ms earlier
        // ([EventToICalEventMapper.exclusiveEndTs] adds it back).
        val endTs = if (icalEvent.isAllDay && effectiveEnd.timestamp > icalEvent.dtStart.timestamp) {
            effectiveEnd.timestamp - 1
        } else {
            effectiveEnd.timestamp
        }

        // Keep the 5 alarms closest to DTSTART, closest first. END-relative alarms are
        // skipped; an absolute trigger becomes an offset from DTSTART rather than being
        // dropped, so an absolute "9 AM day of" all-day alarm round-trips like a relative one.
        val alarmDurations = icalEvent.alarms
            .filter { !it.triggerRelatedToEnd }
            // RFC 9074 ACTION:NONE is a "no action" sentinel (Apple emits one with a
            // 1976 absolute trigger to suppress default alarms). Excluded from reminders
            // and alarmCount, or its absolute trigger becomes a phantom multi-day offset.
            .filter { it.action != AlarmAction.NONE }
            .mapNotNull { alarm -> alarmToStartDuration(alarm, icalEvent.dtStart.timestamp) }

        val reminders = alarmDurations
            .sortedBy { it.abs() }
            .take(5)
            .map { formatTriggerDuration(it) }
            .takeIf { it.isNotEmpty() }

        // Counts every alarm, not only the 5 stored: ReminderScheduler re-reads rawIcal when
        // this exceeds 3, and the event form reports the alarms beyond 5.
        val alarmCount = alarmDurations.size

        // Flatten EXDATE/RDATE to comma-separated epoch ms, first normalizing a value-type
        // mismatch ([normalizeToMasterValueType]). RFC 5545 §3.8.5.1 requires the type to
        // match DTSTART, but peer clients break this and most servers preserve it.
        // EXDATE/RDATE are reconciled against this component's own DTSTART, which defines
        // the recurrence set, not the [masterDtStart] parameter (another component's DTSTART,
        // given only for exceptions). A local named `masterDtStart` here would shadow that
        // parameter and cut it off from the RECURRENCE-ID normalization below.
        val ownDtStart = icalEvent.dtStart
        val exdate = icalEvent.exdates
            .map { normalizeToMasterValueType(it, ownDtStart).timestamp.toString() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")

        val rdate = icalEvent.rdates
            .map { normalizeToMasterValueType(it, ownDtStart).timestamp.toString() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")

        val timezone = icalEvent.dtStart.timezone?.id

        // RFC 5545 §3.8.2.2 permits DTEND TZID to differ from DTSTART TZID (e.g., flights).
        val endTimezone = icalEvent.dtEnd?.timezone?.id?.takeIf { it != timezone }

        val originalInstanceTime = normalizeRecurrenceId(
            recurrenceId = icalEvent.recurrenceId,
            masterDtStart = masterDtStart,
        )?.timestamp

        val importId = icalEvent.importId

        val event = Event(
            uid = icalEvent.uid,
            importId = importId,
            calendarId = calendarId,
            title = icalEvent.summary?.ifEmpty { null } ?: "Untitled",
            location = icalEvent.location,
            description = icalEvent.description,
            startTs = icalEvent.dtStart.timestamp,
            endTs = endTs,
            timezone = timezone,
            endTimezone = endTimezone,
            isAllDay = icalEvent.isAllDay,
            status = icalEvent.status.toICalString(),
            transp = icalEvent.transparency.toICalString(),
            classification = icalEvent.classification?.toICalString() ?: "PUBLIC",
            organizerEmail = icalEvent.organizer?.email,
            organizerName = icalEvent.organizer?.name,
            // RFC 6638 §7.3 ORGANIZER SCHEDULE-STATUS: the server-written receipt for an
            // attendee reply delivered to the organizer. First code only, as for attendees.
            // SENT-BY is RFC 5545 §3.2.18.
            organizerScheduleStatus = icalEvent.organizer?.scheduleStatus?.firstOrNull()?.code,
            organizerSentBy = icalEvent.organizer?.sentBy,
            rrule = icalEvent.rrule?.toICalString(),
            rdate = rdate,
            exdate = exdate,
            duration = icalEvent.duration?.let { DurationUtils.format(it) },
            originalEventId = null, // Set by caller after master lookup
            originalInstanceTime = originalInstanceTime,
            originalSyncId = null,
            reminders = reminders,
            alarmCount = alarmCount,
            extraProperties = icalEvent.rawProperties.takeIf { it.isNotEmpty() },
            rawIcal = rawIcal,
            // RFC 5545/7986 extended properties
            priority = icalEvent.priority,
            geoLat = parseGeoLat(icalEvent.geo),
            geoLon = parseGeoLon(icalEvent.geo),
            color = parseColorToArgb(icalEvent.color),
            url = icalEvent.url,
            categories = icalEvent.categories.takeIf { it.isNotEmpty() },
            dtstamp = icalEvent.dtstamp?.timestamp ?: now,
            caldavUrl = caldavUrl,
            etag = etag,
            sequence = icalEvent.sequence,
            syncStatus = SyncStatus.SYNCED,
            lastSyncError = null,
            syncRetryCount = 0,
            localModifiedAt = null,
            serverModifiedAt = icalEvent.lastModified?.timestamp ?: now,
            createdAt = icalEvent.created?.timestamp ?: now,
            updatedAt = now
        )

        return MappedEntity(event, toAttendeeRows(icalEvent, eventId = 0L))
    }

    /**
     * Maps [icalEvent]'s attendees to Room rows for [eventId], without the full [toEntity]
     * mapping. Used by attendee backfill from `Event.rawIcal` and by push.
     */
    fun toAttendeeRows(icalEvent: ICalEvent, eventId: Long): List<Attendee> =
        icalEvent.attendees.mapIndexed { index, a ->
            a.toRoomEntity(eventId = eventId, sortOrder = index)
        }

    /**
     * Converts an icaldav [org.onekash.icaldav.model.Attendee] to a Room [Attendee]. Enums
     * map to their RFC strings; the TEXT columns also accept X- extensions.
     *
     * The icaldav parser strips `mailto:` from `email`, so `address` gets it back only for an
     * email-shaped value, as the attendee picker does. A non-mailto CAL-ADDRESS
     * (`urn:uuid:` or a principal href, RFC 5545 §3.3.3) is stored as is: `mailto:urn:uuid:...`
     * would break display, matchesAttendee and avatar canonicalization. `member`,
     * `delegatedFrom`, `delegatedTo` and `sentBy` stay bare; the generator adds `mailto:` on
     * emit.
     *
     * Only the first SCHEDULE-STATUS code is kept, though RFC 5545 §3.8.8.3 allows several.
     */
    private fun org.onekash.icaldav.model.Attendee.toRoomEntity(
        eventId: Long,
        sortOrder: Int
    ): Attendee = Attendee(
        eventId = eventId,
        address = if (org.onekash.kashcal.util.AddressNormalizer.isEmailShaped(email)) {
            "mailto:$email"
        } else {
            email
        },
        displayName = name,
        role = role.toICalString(),
        partstat = partStat.toICalString(),
        cutype = cutype.name,
        rsvp = rsvp,
        delegatedFrom = delegatedFrom,
        delegatedTo = delegatedTo,
        member = member,
        sentBy = sentBy,
        scheduleAgent = scheduleAgent?.name,
        scheduleStatus = scheduleStatus?.firstOrNull()?.code,
        scheduleForceSend = scheduleForceSend?.name,
        sortOrder = sortOrder
    )

    /** Formats an RFC 5545 trigger duration, such as "-PT15M" or "-P1D". */
    private fun formatTriggerDuration(duration: Duration): String {
        return DurationUtils.format(duration)
    }

    /**
     * Returns a START-relative alarm's offset from DTSTART: a relative trigger as is, an
     * absolute one as `triggerInstant - dtStart`. Null when the alarm has neither.
     */
    private fun alarmToStartDuration(alarm: ICalAlarm, dtStartMs: Long): Duration? {
        alarm.trigger?.let { return it }
        return alarm.triggerAbsolute?.let { Duration.ofMillis(it.timestamp - dtStartMs) }
    }

    /** Returns whether [icalEvent] is an exception (has a RECURRENCE-ID). */
    fun isException(icalEvent: ICalEvent): Boolean {
        return icalEvent.recurrenceId != null
    }

    /**
     * Normalizes an exception's RECURRENCE-ID to the value type of the master's DTSTART
     * ([normalizeToMasterValueType]).
     *
     * RFC 5545 §3.8.4.4 requires the types to match, but some clients emit e.g.
     * `RECURRENCE-ID;VALUE=DATE` against a timed master, and live multi-server tests show 7 of
     * 10 CalDAV servers preserve it on a PUT/GET round trip. Unnormalized, a date-form
     * RECURRENCE-ID lands at UTC midnight while the expanded occurrence sits at the master's
     * time of day; the 60-second `linkException` tolerance can't bridge that, so the day shows
     * two occurrences.
     *
     * Returns null for a null [recurrenceId], and [recurrenceId] unchanged when [masterDtStart]
     * is null.
     */
    fun normalizeRecurrenceId(
        recurrenceId: ICalDateTime?,
        masterDtStart: ICalDateTime?,
    ): ICalDateTime? {
        if (recurrenceId == null) return null
        if (masterDtStart == null) return recurrenceId
        return normalizeToMasterValueType(recurrenceId, masterDtStart)
    }

    /**
     * Converts a recurrence date ([value]) to the value type of [masterDtStart], or returns it
     * unchanged when the types match. Used for RECURRENCE-ID and for EXDATE/RDATE in
     * [toEntity].
     *
     * - Timed master, DATE value: promoted to the master's time of day in the master's zone,
     *   the instant its RRULE expansion gives that day. Left at UTC midnight it would fall on
     *   the previous day in a negative-offset zone and exclude or add the wrong occurrence.
     * - All-day master, DATE-TIME value: demoted to DATE, keeping the calendar date in
     *   [value]'s own zone.
     */
    fun normalizeToMasterValueType(
        value: ICalDateTime,
        masterDtStart: ICalDateTime,
    ): ICalDateTime {
        if (value.isDate == masterDtStart.isDate) return value

        return if (masterDtStart.isDate) {
            val zone = value.timezone ?: ZoneId.systemDefault()
            val date = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(value.timestamp),
                zone,
            ).toLocalDate()
            ICalDateTime.fromLocalDate(date)
        } else {
            val masterZone = masterDtStart.timezone ?: ZoneOffset.UTC
            val masterLocalTime = ZonedDateTime
                .ofInstant(java.time.Instant.ofEpochMilli(masterDtStart.timestamp), masterZone)
                .toLocalTime()
            // LocalDate.ofInstant requires API 34; ZonedDateTime.ofInstant gives the same date.
            val valueDate = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(value.timestamp),
                ZoneOffset.UTC, // DATE values are stored as UTC midnight per ICalDateTime
            ).toLocalDate()
            val zoned = ZonedDateTime.of(valueDate, masterLocalTime, masterZone)
            ICalDateTime.fromZonedDateTime(zoned, isDate = false)
        }
    }

    /** Returns the importId: `{uid}`, or `{uid}:RECID:{datetime}` for an exception. */
    fun getImportId(icalEvent: ICalEvent): String {
        return icalEvent.importId
    }

    // ========== RFC 5545/7986 Parsing Helpers ==========

    /** Parses the latitude of an RFC 5545 GEO value ("37.386013;-122.082932"), or null. */
    private fun parseGeoLat(geo: String?): Double? {
        if (geo.isNullOrBlank()) return null
        val parts = geo.split(";")
        return parts.getOrNull(0)?.toDoubleOrNull()
    }

    /** Parses the longitude of an RFC 5545 GEO value ("37.386013;-122.082932"), or null. */
    private fun parseGeoLon(geo: String?): Double? {
        if (geo.isNullOrBlank()) return null
        val parts = geo.split(";")
        return parts.getOrNull(1)?.toDoubleOrNull()
    }

    /**
     * Parses an RFC 7986 COLOR value to ARGB: a CSS3 name ("red"), `#RRGGBB`, `#RGB` or
     * `#AARRGGBB`. Null for anything else, such as `rgb()` notation.
     */
    private fun parseColorToArgb(color: String?): Int? {
        if (color.isNullOrBlank()) return null
        // RFC 7986 §5.9 COLOR uses CSS3 names ("mediumorchid"), which Color.parseColor
        // doesn't know, so resolve them through EventColorPalette first.
        EventColorPalette.hexForName(color)?.let { return it }
        // Color.parseColor doesn't accept #RGB, so expand it to #RRGGBB.
        val expanded = if (color.length == 4 && color.startsWith("#")) {
            val r = color[1]; val g = color[2]; val b = color[3]
            "#$r$r$g$g$b$b"
        } else color
        return try {
            Color.parseColor(expanded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
