package org.onekash.kashcal.data.calendar_provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Edge cases around device-calendar data: `DateTimeUtils.isEventPast` for all-day and timed
 * events, `eventTsToDayCode` zones, `spansMultipleDays` and `calculateTotalDays` boundaries,
 * [DeviceCalendarInstance] field preservation, and the [DeviceCalendar.isWritable] access-level
 * boundary.
 *
 * Complements [AndroidCalendarProviderRepositoryTest], [DeviceCalendarTest] and
 * [CalendarProviderManagerTest].
 */
class CalendarProviderEdgeCaseTest {

    // ========== DateTimeUtils.isEventPast() ==========

    @Test
    fun `isEventPast all-day event ending today is not past`() {
        val todayDayCode = 20260215
        val isPast = DateTimeUtils.isEventPast(
            endTs = 0L, // ignored for all-day
            endDay = 20260215,
            isAllDay = true,
            nowMs = System.currentTimeMillis(),
            todayDayCode = todayDayCode
        )
        assertFalse("All-day event ending today should not be past", isPast)
    }

    @Test
    fun `isEventPast all-day event ending yesterday is past`() {
        val todayDayCode = 20260216
        val isPast = DateTimeUtils.isEventPast(
            endTs = 0L,
            endDay = 20260215,
            isAllDay = true,
            nowMs = System.currentTimeMillis(),
            todayDayCode = todayDayCode
        )
        assertTrue("All-day event ending yesterday should be past", isPast)
    }

    @Test
    fun `isEventPast all-day event ending tomorrow is not past`() {
        val todayDayCode = 20260215
        val isPast = DateTimeUtils.isEventPast(
            endTs = 0L,
            endDay = 20260216,
            isAllDay = true,
            nowMs = System.currentTimeMillis(),
            todayDayCode = todayDayCode
        )
        assertFalse("All-day event ending tomorrow should not be past", isPast)
    }

    @Test
    fun `isEventPast all-day uses day code not timestamp`() {
        // 6 PM on Jan 15 in UTC-6 is Jan 16 00:00 UTC. An all-day event ending Jan 15
        // (endTs = Jan 15 23:59:59.999 UTC) would read as past by timestamp
        // (endTs < now), but endDay 20260115 < todayDayCode 20260115 is false: not past.

        val jan15EndTs = ZonedDateTime.of(2026, 1, 15, 23, 59, 59, 999_000_000, ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val nowUtcJan16 = ZonedDateTime.of(2026, 1, 16, 0, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        // A timestamp comparison would call the event past.
        assertTrue("Timestamp comparison would incorrectly say past", jan15EndTs < nowUtcJan16)

        // isEventPast compares day codes for all-day events.
        val isPast = DateTimeUtils.isEventPast(
            endTs = jan15EndTs,
            endDay = 20260115,
            isAllDay = true,
            nowMs = nowUtcJan16,
            todayDayCode = 20260115 // Local date is still Jan 15 in UTC-6
        )
        assertFalse("Should use day code, not timestamp for all-day events", isPast)
    }

    @Test
    fun `isEventPast timed event just ended is past`() {
        val endTs = 1000L
        val isPast = DateTimeUtils.isEventPast(
            endTs = endTs,
            endDay = 20260215,
            isAllDay = false,
            nowMs = 1001L,
            todayDayCode = 20260215
        )
        assertTrue("Timed event that just ended should be past", isPast)
    }

    @Test
    fun `isEventPast timed event still ongoing is not past`() {
        val endTs = 2000L
        val isPast = DateTimeUtils.isEventPast(
            endTs = endTs,
            endDay = 20260215,
            isAllDay = false,
            nowMs = 1000L,
            todayDayCode = 20260215
        )
        assertFalse("Timed event still ongoing should not be past", isPast)
    }

    @Test
    fun `isEventPast timed event ending exactly now is not past`() {
        val endTs = 1000L
        val isPast = DateTimeUtils.isEventPast(
            endTs = endTs,
            endDay = 20260215,
            isAllDay = false,
            nowMs = 1000L,
            todayDayCode = 20260215
        )
        // endTs < nowMs: 1000 < 1000 is false.
        assertFalse("Timed event ending exactly now should not be past", isPast)
    }

    // ========== DateTimeUtils.eventTsToDayCode() Timezone Handling ==========

    @Test
    fun `eventTsToDayCode all-day event uses UTC zone`() {
        // Feb 15 00:00 UTC is day code 20260215 in any local zone.
        val feb15Utc = LocalDate.of(2026, 2, 15).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        val dayCode = DateTimeUtils.eventTsToDayCode(feb15Utc, isAllDay = true)
        assertEquals("All-day events should use UTC", 20260215, dayCode)
    }

    @Test
    fun `eventTsToDayCode all-day end of day still same day in UTC`() {
        // Feb 15 23:59:59.999 UTC is still day code 20260215.
        val feb15EndUtc = ZonedDateTime.of(2026, 2, 15, 23, 59, 59, 999_000_000, ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        val dayCode = DateTimeUtils.eventTsToDayCode(feb15EndUtc, isAllDay = true)
        assertEquals("End of day in UTC should still be same day", 20260215, dayCode)
    }

    @Test
    fun `eventTsToDayCode timed event uses specified zone`() {
        // Midnight UTC on Feb 15 is still Feb 14 in UTC-8.
        val feb15MidnightUtc = LocalDate.of(2026, 2, 15).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        val dayCodePacific = DateTimeUtils.eventTsToDayCode(
            feb15MidnightUtc,
            isAllDay = false,
            localZone = ZoneId.of("US/Pacific") // UTC-8
        )
        // Still Feb 14 in UTC-8.
        assertEquals("Timed event should use local zone", 20260214, dayCodePacific)
    }

    @Test
    fun `eventTsToDayCode year boundary`() {
        // Dec 31 23:59:59.999 UTC is day code 20251231.
        val dec31End = ZonedDateTime.of(2025, 12, 31, 23, 59, 59, 999_000_000, ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val dayCode = DateTimeUtils.eventTsToDayCode(dec31End, isAllDay = true)
        assertEquals(20251231, dayCode)

        // Jan 1 00:00:00 UTC is day code 20260101.
        val jan1Start = LocalDate.of(2026, 1, 1).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val dayCode2 = DateTimeUtils.eventTsToDayCode(jan1Start, isAllDay = true)
        assertEquals(20260101, dayCode2)
    }

    @Test
    fun `eventTsToDayCode leap year Feb 29`() {
        val feb29 = LocalDate.of(2024, 2, 29).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val dayCode = DateTimeUtils.eventTsToDayCode(feb29, isAllDay = true)
        assertEquals(20240229, dayCode)
    }

    // ========== DateTimeUtils.spansMultipleDays() / calculateTotalDays() ==========

    @Test
    fun `spansMultipleDays single-day event returns false`() {
        val start = LocalDate.of(2026, 2, 15).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val end = ZonedDateTime.of(2026, 2, 15, 23, 59, 59, 999_000_000, ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        assertFalse(
            "Single-day event should not span multiple days",
            DateTimeUtils.spansMultipleDays(start, end, isAllDay = true)
        )
    }

    @Test
    fun `spansMultipleDays multi-day event returns true`() {
        val start = LocalDate.of(2026, 2, 15).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 2, 16).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        assertTrue(
            "Multi-day event should span multiple days",
            DateTimeUtils.spansMultipleDays(start, end, isAllDay = true)
        )
    }

    @Test
    fun `calculateTotalDays single-day returns 1`() {
        val start = LocalDate.of(2026, 2, 15).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val end = ZonedDateTime.of(2026, 2, 15, 23, 59, 59, 999_000_000, ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        assertEquals(
            "Single-day event should be 1 day",
            1,
            DateTimeUtils.calculateTotalDays(start, end, isAllDay = true)
        )
    }

    @Test
    fun `calculateTotalDays 3-day event returns 3`() {
        // All-day Feb 15-17 (3 days).
        val start = LocalDate.of(2026, 2, 15).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        // Inclusive end: Feb 17 23:59:59.999.
        val end = ZonedDateTime.of(2026, 2, 17, 23, 59, 59, 999_000_000, ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        assertEquals(
            "3-day event should return 3",
            3,
            DateTimeUtils.calculateTotalDays(start, end, isAllDay = true)
        )
    }

    @Test
    fun `calculateTotalDays crossing month boundary`() {
        // Jan 30 to Feb 2 is 4 days.
        val start = LocalDate.of(2026, 1, 30).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val end = ZonedDateTime.of(2026, 2, 2, 23, 59, 59, 999_000_000, ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        assertEquals(
            "Event crossing month boundary should count correctly",
            4,
            DateTimeUtils.calculateTotalDays(start, end, isAllDay = true)
        )
    }

    // ========== DeviceCalendarInstance Field Preservation ==========

    @Test
    fun `DeviceCalendarInstance preserves all fields`() {
        val instance = DeviceCalendarInstance(
            instanceId = 42L,
            eventId = 100L,
            title = "Team Meeting",
            description = "Weekly standup",
            location = "Room 101",
            startTs = 1000000L,
            endTs = 2000000L,
            startDay = 20260215,
            endDay = 20260215,
            isAllDay = false,
            hasRrule = true,
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            reminders = listOf(15, 60),
            calendarId = 5L,
            calendarDisplayName = "Work Calendar",
            calendarColor = -16711936, // Green
            eventColor = null,
            status = 1,
            availability = 0,
            hasAlarm = true,
            selfAttendeeStatus = 1,
            isWritable = true,
            originalId = 99L,
            originalInstanceTime = 500000L,
            timezone = "America/Los_Angeles",
            eventStartTs = 1000000L,
        )

        assertEquals(42L, instance.instanceId)
        assertEquals(100L, instance.eventId)
        assertEquals("Team Meeting", instance.title)
        assertEquals("Weekly standup", instance.description)
        assertEquals("Room 101", instance.location)
        assertEquals(1000000L, instance.startTs)
        assertEquals(2000000L, instance.endTs)
        assertEquals(20260215, instance.startDay)
        assertEquals(20260215, instance.endDay)
        assertFalse(instance.isAllDay)
        assertTrue(instance.hasRrule)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", instance.rrule)
        assertEquals(listOf(15, 60), instance.reminders)
        assertEquals(5L, instance.calendarId)
        assertEquals("Work Calendar", instance.calendarDisplayName)
        assertEquals(-16711936, instance.calendarColor)
        assertNull(instance.eventColor)
        assertEquals(1, instance.status)
        assertEquals(0, instance.availability)
        assertTrue(instance.hasAlarm)
        assertEquals(1, instance.selfAttendeeStatus)
        assertTrue(instance.isWritable)
        assertEquals(99L, instance.originalId)
        assertEquals(500000L, instance.originalInstanceTime)
        assertEquals("America/Los_Angeles", instance.timezone)
    }

    @Test
    fun `DeviceCalendarInstance copy preserves unmodified fields`() {
        val original = DeviceCalendarInstance(
            instanceId = 1L,
            eventId = 2L,
            title = "Original",
            description = "Desc",
            location = "Loc",
            startTs = 1000L,
            endTs = 2000L,
            startDay = 20260215,
            endDay = 20260215,
            isAllDay = false,
            hasRrule = false,
            rrule = null,
            reminders = emptyList(),
            calendarId = 1L,
            calendarDisplayName = "Cal",
            calendarColor = 0,
            eventColor = null,
            status = 0,
            availability = 0,
            hasAlarm = false,
            selfAttendeeStatus = 0,
            isWritable = false,
            originalId = null,
            originalInstanceTime = null,
            timezone = null,
            eventStartTs = 1000L,
        )

        val modified = original.copy(title = "Modified", isWritable = true)

        assertEquals("Modified", modified.title)
        assertTrue(modified.isWritable)
        // The other fields are unchanged.
        assertEquals(original.instanceId, modified.instanceId)
        assertEquals(original.eventId, modified.eventId)
        assertEquals(original.description, modified.description)
        assertEquals(original.location, modified.location)
        assertEquals(original.startTs, modified.startTs)
        assertEquals(original.endTs, modified.endTs)
        assertEquals(original.calendarId, modified.calendarId)
    }

    // ========== DeviceCalendar.isWritable Boundary ==========

    @Test
    fun `DeviceCalendar access level 499 is not writable`() {
        val cal = DeviceCalendar(
            id = 1L,
            displayName = "Cal",
            color = 0,
            accountName = "user",
            accountType = "com.google",
            visible = true,
            accessLevel = 499
        )
        assertFalse("Access level 499 should not be writable", cal.isWritable)
    }

    @Test
    fun `DeviceCalendar access level 500 is writable`() {
        val cal = DeviceCalendar(
            id = 1L,
            displayName = "Cal",
            color = 0,
            accountName = "user",
            accountType = "com.google",
            visible = true,
            accessLevel = 500
        )
        assertTrue("Access level 500 (CONTRIBUTOR) should be writable", cal.isWritable)
    }
}
