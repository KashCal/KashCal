package org.onekash.kashcal.domain.planner

import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Builders for the Planner's pure-logic tests: Room and device events, series and exceptions. */
internal object PlannerTestEvents {

    const val HOUR = 3_600_000L
    const val DAY = 86_400_000L

    fun dayCode(date: LocalDate): Int = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

    fun at(date: LocalDate, hour: Int, minute: Int = 0, zone: ZoneId): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    fun utcMidnight(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** Puts each event in every one of [dayCodes] it covers, as the repository's day buckets do. */
    fun bucketByDay(dayCodes: List<Int>, events: List<DisplayEvent>): Map<Int, List<DisplayEvent>> =
        dayCodes.associateWith { day -> events.filter { day in it.startDay..it.endDay } }

    private val calendar = Calendar(
        id = 1L,
        accountId = 1L,
        caldavUrl = "https://example.invalid/cal/",
        displayName = "Family",
        color = 0xFF2F7BEA.toInt(),
        isReadOnly = false,
    )

    /**
     * A Room occurrence. [seriesId] is the series row (Occurrence.eventId); a non-null
     * [originalInstanceTime] makes it an exception row with its own id [id].
     */
    fun room(
        id: Long,
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean = false,
        zone: ZoneId,
        title: String = "Event $id",
        rrule: String? = null,
        seriesId: Long? = null,
        originalInstanceTime: Long? = null,
        status: String = "CONFIRMED",
        declined: Boolean = false,
        transp: String = "OPAQUE",
    ): DisplayEvent.Room {
        val isException = originalInstanceTime != null
        val event = Event(
            id = id,
            uid = "uid-${seriesId ?: id}",
            calendarId = 1L,
            title = title,
            startTs = startTs,
            endTs = endTs,
            isAllDay = isAllDay,
            timezone = if (isAllDay) "UTC" else zone.id,
            status = status,
            transp = transp,
            rrule = if (isException) null else rrule,
            originalEventId = if (isException) seriesId else null,
            originalInstanceTime = originalInstanceTime,
            syncStatus = SyncStatus.SYNCED,
            dtstamp = startTs,
            createdAt = startTs,
            updatedAt = startTs,
        )
        val occ = Occurrence(
            eventId = if (isException) seriesId!! else id,
            calendarId = 1L,
            startTs = startTs,
            endTs = endTs,
            startDay = DateTimeUtils.eventTsToDayCode(startTs, isAllDay, zone),
            endDay = DateTimeUtils.eventTsToEndDayCode(endTs, startTs, isAllDay, zone),
            isCancelled = false,
            exceptionEventId = if (isException) id else null,
        )
        return DisplayEvent.Room(
            event = event, occurrence = occ, calendar = calendar, isDeclinedByMe = declined, zone = zone,
        )
    }

    /** A device instance; non-null [originalInstanceTime] makes it an exception of [originalId]. */
    fun device(
        instanceId: Long,
        eventId: Long,
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean = false,
        zone: ZoneId,
        rrule: String? = null,
        originalId: Long? = null,
        originalInstanceTime: Long? = null,
        originalAllDay: Boolean? = null,
        status: Int = 1,
        selfAttendeeStatus: Int = 0,
    ): DisplayEvent.Device = DisplayEvent.Device(
        DeviceCalendarInstance(
            instanceId = instanceId,
            eventId = eventId,
            title = "Device $eventId",
            description = "",
            location = "",
            startTs = startTs,
            endTs = endTs,
            startDay = DateTimeUtils.eventTsToDayCode(startTs, isAllDay, zone),
            endDay = DateTimeUtils.eventTsToEndDayCode(endTs, startTs, isAllDay, zone),
            isAllDay = isAllDay,
            hasRrule = rrule != null,
            rrule = rrule,
            reminders = emptyList(),
            calendarId = 1L,
            calendarDisplayName = "Device",
            calendarColor = 0xFF4CAF50.toInt(),
            eventColor = null,
            status = status,
            availability = 0,
            hasAlarm = false,
            selfAttendeeStatus = selfAttendeeStatus,
            isWritable = true,
            originalId = originalId,
            originalInstanceTime = originalInstanceTime,
            timezone = if (isAllDay) "UTC" else zone.id,
            eventStartTs = startTs,
            originalAllDay = originalAllDay,
        )
    )

    /** A one-day all-day Room event on [date]. */
    fun roomAllDay(
        id: Long,
        date: LocalDate,
        zone: ZoneId,
        days: Int = 1,
        rrule: String? = null,
        seriesId: Long? = null,
        originalInstanceTime: Long? = null,
        status: String = "CONFIRMED",
        declined: Boolean = false,
    ): DisplayEvent.Room = room(
        id = id,
        startTs = utcMidnight(date),
        endTs = utcMidnight(date.plusDays(days.toLong())) - 1,
        isAllDay = true,
        zone = zone,
        rrule = rrule,
        seriesId = seriesId,
        originalInstanceTime = originalInstanceTime,
        status = status,
        declined = declined,
    )
}
