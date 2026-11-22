package org.onekash.kashcal.ui.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.calendar_provider.DeviceEvent
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import java.util.TimeZone

/**
 * Tests the save-time check of a drag: the stored event against the copy the grid showed at the
 * drop. Changes the save would overwrite (or, for Room, apply on top of a different series)
 * refuse it; bookkeeping a sync touches, and the null-versus-empty and default-zone mapping
 * differences between an instance and its row, don't. A row that is gone, or deleted but not yet
 * purged, is gone.
 */
class PendingMoveStaleCheckTest {

    private val start = 1_709_650_800_000L
    private val hour = 3_600_000L

    private val dropped = Event(
        id = 7L, uid = "u", calendarId = 1L, title = "Review", startTs = start, endTs = start + hour,
        dtstamp = 1L, timezone = "America/New_York", rrule = "FREQ=WEEKLY", syncStatus = SyncStatus.SYNCED,
    )

    // ==================== Room ====================

    @Test
    fun `an unchanged Room row is fresh`() {
        assertEquals(DragSaveCheck.FRESH, checkRoomMove(dropped, dropped))
    }

    @Test
    fun `a Room row changed in a way the move depends on is changed`() {
        listOf(
            dropped.copy(startTs = start + 1),
            dropped.copy(endTs = start + 2 * hour),
            dropped.copy(rrule = "FREQ=DAILY"),
            dropped.copy(exdate = "20240312T150000Z"),
            dropped.copy(rdate = "20240313T150000Z"),
            dropped.copy(isAllDay = true),
            dropped.copy(timezone = "Europe/Berlin"),
        ).forEach { stored -> assertEquals(stored.toString(), DragSaveCheck.CHANGED, checkRoomMove(dropped, stored)) }
    }

    @Test
    fun `sync bookkeeping and fields the move keeps from the fresh row stay fresh`() {
        listOf(
            dropped.copy(etag = "\"e2\""),
            dropped.copy(syncStatus = SyncStatus.PENDING_UPDATE),
            dropped.copy(updatedAt = 99L),
            dropped.copy(title = "Review (moved room)"),
        ).forEach { stored -> assertEquals(stored.toString(), DragSaveCheck.FRESH, checkRoomMove(dropped, stored)) }
    }

    @Test
    fun `a missing or deleted Room row is gone`() {
        assertEquals(DragSaveCheck.GONE, checkRoomMove(dropped, null))
        assertEquals(DragSaveCheck.GONE, checkRoomMove(dropped, dropped.copy(syncStatus = SyncStatus.PENDING_DELETE)))
    }

    // ==================== Device ====================

    private val instance = DeviceCalendarInstance(
        instanceId = 900L, eventId = 100L, title = "Standup", description = "", location = "",
        startTs = start, endTs = start + hour, startDay = 20240305, endDay = 20240305, isAllDay = false,
        hasRrule = false, rrule = null, reminders = listOf(15), calendarId = 42L, calendarDisplayName = "Work",
        calendarColor = 0xFF445566.toInt(), eventColor = null, status = 1, availability = 0, hasAlarm = true,
        selfAttendeeStatus = 0, isWritable = true, originalId = null, originalInstanceTime = null,
        timezone = "America/New_York", eventStartTs = start,
    )

    private val row = DeviceEvent(
        id = 100L, calendarId = 42L, title = "Standup", description = null, location = null,
        startTs = start, endTs = start + hour, duration = null, isAllDay = false, rrule = null, rdate = null,
        exdate = null, exrule = null, timezone = "America/New_York", originalId = null, originalInstanceTime = null,
        status = 1, availability = 0, accessLevel = 0, calendarColor = 0xFF445566.toInt(), eventColor = 0,
    )

    @Test
    fun `a device row that maps to the dropped instance is fresh`() {
        // Null text against the instance's empty text, and a stored colour of 0 against none.
        assertEquals(DragSaveCheck.FRESH, checkDeviceMove(instance, row, isActive = true))
    }

    @Test
    fun `a row with no zone of its own matches an instance without one`() {
        val zone = TimeZone.getDefault().id
        assertEquals(
            DragSaveCheck.FRESH,
            checkDeviceMove(instance.copy(timezone = null), row.copy(timezone = zone), isActive = true),
        )
    }

    @Test
    fun `a device row changed in a field the drag writes is changed`() {
        listOf(
            row.copy(title = "Standup v2"),
            row.copy(description = "agenda"),
            row.copy(location = "Room 1"),
            row.copy(availability = 1),
            row.copy(eventColor = 0xFF00FF00.toInt()),
            row.copy(isAllDay = true),
            row.copy(timezone = "Europe/Berlin"),
            row.copy(rrule = "FREQ=DAILY"),
            row.copy(startTs = start + hour),
            row.copy(endTs = start + 2 * hour),
        ).forEach { stored -> assertEquals(stored.toString(), DragSaveCheck.CHANGED, checkDeviceMove(instance, stored, isActive = true)) }
    }

    @Test
    fun `a one-off row with no end of its own isn't compared on the end`() {
        assertEquals(
            DragSaveCheck.FRESH,
            checkDeviceMove(instance, row.copy(endTs = null, duration = "PT1H"), isActive = true),
        )
    }

    @Test
    fun `a series row is compared on its own start, not the occurrence's`() {
        val series = instance.copy(hasRrule = true, rrule = "FREQ=WEEKLY;BYDAY=TU", eventStartTs = start - 7 * 24 * hour)
        val seriesRow = row.copy(rrule = "FREQ=WEEKLY;BYDAY=TU", startTs = start - 7 * 24 * hour, endTs = null, duration = "PT1H")

        assertEquals(DragSaveCheck.FRESH, checkDeviceMove(series, seriesRow, isActive = true))
        assertEquals(DragSaveCheck.CHANGED, checkDeviceMove(series, seriesRow.copy(startTs = start), isActive = true))
    }

    @Test
    fun `an empty stored rule is no rule`() {
        assertEquals(DragSaveCheck.FRESH, checkDeviceMove(instance, row.copy(rrule = ""), isActive = true))
    }

    @Test
    fun `a missing, deleted or unreadable device row is gone`() {
        assertEquals(DragSaveCheck.GONE, checkDeviceMove(instance, null, isActive = false))
        assertEquals(DragSaveCheck.GONE, checkDeviceMove(instance, row, isActive = false))
    }
}
