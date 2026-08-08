package org.onekash.kashcal.ui.components.weekview

import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.DisplayEvent
import java.time.LocalDate
import java.time.ZoneId

/**
 * Shared factory for a one-hour timed [DisplayEvent.Room] used by the week-view
 * gesture tests. One builder so the Event/Occurrence/Calendar boilerplate lives
 * in a single place and tracks entity-constructor changes.
 */
internal fun roomDisplayEvent(
    id: Long,
    title: String,
    date: LocalDate,
    hour: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): DisplayEvent.Room {
    val start = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    val end = start + 3_600_000L
    val dayCode = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
    val event = Event(
        id = id,
        uid = "test-uid-$id",
        calendarId = 1L,
        title = title,
        startTs = start,
        endTs = end,
        isAllDay = false,
        timezone = zone.id,
        syncStatus = SyncStatus.SYNCED,
        createdAt = start,
        updatedAt = start,
        dtstamp = start
    )
    val occ = Occurrence(
        eventId = id,
        calendarId = 1L,
        startTs = start,
        endTs = end,
        startDay = dayCode,
        endDay = dayCode,
        isCancelled = false,
        exceptionEventId = null
    )
    val cal = Calendar(
        id = 1L,
        accountId = 1L,
        caldavUrl = "https://example.invalid/cal/",
        displayName = "Test",
        color = 0xFF2196F3.toInt(),
        isReadOnly = false
    )
    return DisplayEvent.Room(event = event, occurrence = occ, calendar = cal)
}

/**
 * Shared factory for an all-day [DisplayEvent.Room] on a single day. Used by
 * layout tests that need the all-day strip populated.
 */
internal fun allDayDisplayEvent(
    id: Long,
    title: String,
    date: LocalDate,
): DisplayEvent.Room {
    val dayCode = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
    val start = date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    val end = start + 86_400_000L - 1
    val event = Event(
        id = id,
        uid = "test-allday-$id",
        calendarId = 1L,
        title = title,
        startTs = start,
        endTs = end,
        isAllDay = true,
        timezone = "UTC",
        syncStatus = SyncStatus.SYNCED,
        createdAt = start,
        updatedAt = start,
        dtstamp = start
    )
    val occ = Occurrence(
        eventId = id,
        calendarId = 1L,
        startTs = start,
        endTs = end,
        startDay = dayCode,
        endDay = dayCode,
        isCancelled = false,
        exceptionEventId = null
    )
    val cal = Calendar(
        id = 1L,
        accountId = 1L,
        caldavUrl = "https://example.invalid/cal/",
        displayName = "Test",
        color = 0xFF2196F3.toInt(),
        isReadOnly = false
    )
    return DisplayEvent.Room(event = event, occurrence = occ, calendar = cal)
}
