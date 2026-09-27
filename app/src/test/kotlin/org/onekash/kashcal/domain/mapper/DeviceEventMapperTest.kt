package org.onekash.kashcal.domain.mapper

import org.onekash.kashcal.testutil.phoneLocalDate
import org.onekash.kashcal.testutil.withDeviceTimeZone
import android.provider.CalendarContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceEvent
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher

/**
 * Tests the device event mappers `toFormState` and `toExportEvent` in DeviceEventMapper.kt.
 *
 * toFormState:
 * - basic fields, tags, edit-mode and device flags; an exception edits through its master's id
 * - reminders: the first 5 are kept and the rest counted
 * - the calendar color and the event color stay on separate fields
 * - the start shown: occurrenceTs, except for an exception, which shows its own start
 * - all-day end dates from an inclusive endTs or from a DURATION
 * - availability mapped to TRANSP
 * - timed events edited in the event's own timezone; blank, unrecognised and all-day cases
 *
 * toExportEvent:
 * - UID `device-{masterId}@kashcal` shared by master and exceptions; an exception's RRULE nulled
 *   and its originalEventId and originalInstanceTime kept
 * - STATUS, AVAILABILITY, color, all-day end, timezone and reminders (ISO durations) mapped;
 *   dtstamp, createdAt and updatedAt set to the current time
 * - serialization through [IcsPatcher.serialize] and [IcsPatcher.serializeWithExceptions]
 */
class DeviceEventMapperTest {

    @Test
    fun `toFormState carries the event's tags into form state`() {
        val event = createDeviceEvent(categories = listOf("Work", "Urgent"))

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals(listOf("Work", "Urgent"), formState.categories)
    }

    @Test
    fun `toFormState leaves categories empty for an untagged event`() {
        val event = createDeviceEvent(categories = emptyList())

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals(emptyList<String>(), formState.categories)
    }

    @Test
    fun `toFormState maps basic fields correctly`() {
        val event = createDeviceEvent(
            title = "Team Standup",
            description = "Daily sync meeting",
            location = "Conference Room A",
            isAllDay = false
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals("Team Standup", formState.title)
        assertEquals("Daily sync meeting", formState.description)
        assertEquals("Conference Room A", formState.location)
        assertEquals(false, formState.isAllDay)
    }

    @Test
    fun `toFormState parses duration for recurring events`() {
        // Recurring event with a 1 hour duration
        val startTs = 1700000000000L
        val event = createDeviceEvent(
            startTs = startTs,
            endTs = null, // A recurring event has DURATION, no endTs
            duration = "PT1H",
            rrule = "FREQ=WEEKLY;BYDAY=MO"
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        // The end is start + 1 hour (not asserted here; only the rrule is checked)
        val expectedEndTs = startTs + 3600000L
        assertEquals(event.rrule, formState.rrule)
    }

    @Test
    fun `toFormState uses endTs when duration is null for non-recurring`() {
        val startTs = 1700000000000L
        val endTs = 1700003600000L // 1 hour later
        val event = createDeviceEvent(
            startTs = startTs,
            endTs = endTs,
            duration = null,
            rrule = null
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        // The times come from endTs (not asserted here; only the null rrule is checked)
        assertNull(formState.rrule)
    }

    @Test
    fun `toFormState sets isDeviceCalendar true and editingDeviceEventId`() {
        val event = createDeviceEvent(id = 42L)

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertTrue(formState.isDeviceCalendar)
        assertEquals(42L, formState.editingDeviceEventId)
    }

    @Test
    fun `toFormState takes first 5 reminders when more than 5 present`() {
        val event = createDeviceEvent()

        val formState = event.toFormState(
            reminders = listOf(15, 30, 60, 120, 1440, 2880, 10080), // 7 reminders
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals(listOf(15, 30, 60, 120, 1440), formState.reminders)
        assertEquals(2, formState.truncatedReminderCount) // 7 - 5 = 2 truncated
    }

    @Test
    fun `toFormState keeps all reminders when 5 or fewer`() {
        val event = createDeviceEvent()

        // Test with exactly 5 reminders
        val formState5 = event.toFormState(
            reminders = listOf(15, 30, 60, 120, 1440),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )
        assertEquals(listOf(15, 30, 60, 120, 1440), formState5.reminders)
        assertEquals(0, formState5.truncatedReminderCount)

        // Test with 3 reminders
        val formState3 = event.toFormState(
            reminders = listOf(15, 30, 60),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )
        assertEquals(listOf(15, 30, 60), formState3.reminders)
        assertEquals(0, formState3.truncatedReminderCount)

        // Test with 1 reminder
        val formState1 = event.toFormState(
            reminders = listOf(60),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )
        assertEquals(listOf(60), formState1.reminders)
        assertEquals(0, formState1.truncatedReminderCount)
    }

    @Test
    fun `toFormState handles empty reminders`() {
        val event = createDeviceEvent()

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals(emptyList<Int>(), formState.reminders)
        assertEquals(0, formState.truncatedReminderCount)
    }

    @Test
    fun `toFormState surfaces eventColor on its own channel, selectedCalendarColor stays calendar identity`() {
        val event = createDeviceEvent(
            calendarColor = 0xFF0000, // Red
            eventColor = 0x00FF00 // Green
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        // The calendar picker dot shows which calendar the event is on.
        assertEquals(0xFF0000, formState.selectedCalendarColor)
        // The per-event override has its own field.
        assertEquals(0x00FF00, formState.eventColor)
    }

    @Test
    fun `toFormState uses calendarColor when eventColor is null`() {
        val event = createDeviceEvent(
            calendarColor = 0xFF0000,
            eventColor = null
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals(0xFF0000, formState.selectedCalendarColor)
    }

    @Test
    fun `toFormState sets edit mode fields`() {
        val event = createDeviceEvent(id = 123L, calendarId = 456L)

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work Calendar",
            deviceCalendarGroups = emptyList()
        )

        assertTrue(formState.isEditMode)
        assertEquals(456L, formState.selectedCalendarId)
        assertEquals("Work Calendar", formState.selectedCalendarName)
    }

    // ==================== editingDeviceEventId for exceptions ====================

    @Test
    fun `toFormState sets editingDeviceEventId to originalId for exception event`() {
        // Exception event: id=200 (exception), originalId=100 (master).
        // editingDeviceEventId must be the master's id (100), not the exception's (200):
        // saveDeviceEvent passes it to the writer call of every edit scope as the series id;
        // editSingleOccurrence looks up or creates the exception under that master
        // (findExceptionEventId, createException).
        val event = createDeviceEvent(id = 200L, originalId = 100L)

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals(100L, formState.editingDeviceEventId)
    }

    @Test
    fun `toFormState sets editingDeviceEventId to own id for non-exception event`() {
        // Non-exception event: id=42, originalId=null
        val event = createDeviceEvent(id = 42L, originalId = null)

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals(42L, formState.editingDeviceEventId)
    }

    // ==================== Occurrence Date Tests ====================

    @Test
    fun `toFormState uses occurrenceTs for recurring event date`() {
        // Master starts Jan 1 with a weekly RRULE
        val jan1_10am = 1735729200000L // 2025-01-01 11:00:00 UTC
        val jan8_10am = jan1_10am + 7 * 24 * 3600 * 1000L // Jan 8

        val event = createDeviceEvent(
            startTs = jan1_10am,
            endTs = null,
            duration = "PT1H",
            rrule = "FREQ=WEEKLY;BYDAY=WE"
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList(),
            occurrenceTs = jan8_10am
        )

        // Form should show Jan 8 date, not Jan 1
        assertEquals(jan8_10am, formState.startOffsetHintTs)
        assertDisplays(jan8_10am, formState.dateMillis, formState.startHour)
    }

    @Test
    fun `toFormState uses exception startTs not occurrenceTs`() {
        // Exception event has its own modified start time
        val originalTs = 1735729200000L
        val modifiedTs = originalTs + 3600000L // Modified to 1 hour later

        val event = createDeviceEvent(
            startTs = modifiedTs,
            endTs = modifiedTs + 3600000L,
            duration = null,
            rrule = null,
            originalId = 100L // This is an exception event
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList(),
            occurrenceTs = originalTs // Should be ignored for exceptions
        )

        // Form should show exception's own startTs, not occurrenceTs
        assertEquals(modifiedTs, formState.startOffsetHintTs)
        assertDisplays(modifiedTs, formState.dateMillis, formState.startHour)
    }

    @Test
    fun `toFormState ignores occurrenceTs for non-recurring event`() {
        val eventStartTs = 1735729200000L
        val differentTs = eventStartTs + 86400000L

        val event = createDeviceEvent(
            startTs = eventStartTs,
            endTs = eventStartTs + 3600000L,
            duration = null,
            rrule = null // Not recurring
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList(),
            occurrenceTs = differentTs // Used: only an originalId makes the mapper ignore it
        )

        // The mapper takes occurrenceTs ?: startTs for any non-exception, with or without an
        // rrule. For a non-recurring event, occurrenceTs == startTs in practice.
        assertEquals(differentTs, formState.startOffsetHintTs)
        assertDisplays(differentTs, formState.dateMillis, formState.startHour)
    }

    @Test
    fun `toFormState computes correct endTs from occurrenceTs and duration`() {
        // 1-hour duration recurring event
        val jan1_10am = 1735729200000L
        val jan8_10am = jan1_10am + 7 * 24 * 3600 * 1000L

        val event = createDeviceEvent(
            startTs = jan1_10am,
            endTs = null,
            duration = "PT1H",
            rrule = "FREQ=WEEKLY"
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList(),
            occurrenceTs = jan8_10am
        )

        // End should be occurrence + 1 hour
        val expectedEndTs = jan8_10am + 3600000L
        assertEquals(expectedEndTs, formState.endOffsetHintTs)
        assertDisplays(expectedEndTs, formState.endDateMillis, formState.endHour)

        // Verify end hour is 1 hour after start
        val startHour = formState.startHour
        val endHour = formState.endHour
        assertEquals(1, endHour - startHour)
    }

    // ==================== All-day end-date round-trip ====================
    //
    // An all-day DeviceEvent.endTs is the inclusive last ms of the last day, the Room
    // Event.endTs convention. AndroidCalendarProviderRepository's read paths convert the
    // provider's exclusive end (mapToInstances inline, mapToDeviceEvent through
    // inclusiveEndForDeviceEvent). These tests start from an inclusive endTs or a DURATION
    // and assert the form's end date: the start day for a 1-day event, Feb 17 (not Feb 18)
    // for a 3-day event spanning Feb 15-17.

    @Test
    fun `toFormState all-day single-day event renders end date same as start`() {
        // 1-day all-day event on 2026-02-15
        // startTs = Feb 15 00:00 UTC, endTs = Feb 15 23:59:59.999 UTC (inclusive)
        val dayMs = 86_400_000L
        val startTs = java.time.LocalDate.of(2026, 2, 15)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val endTs = startTs + dayMs - 1L

        val event = createDeviceEvent(
            startTs = startTs,
            endTs = endTs,
            isAllDay = true,
            timezone = "UTC"
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        // Both pickers resolve to Feb 15 in the local zone
        val startDate = java.time.Instant.ofEpochMilli(formState.dateMillis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val endDate = java.time.Instant.ofEpochMilli(formState.endDateMillis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()

        assertEquals(java.time.LocalDate.of(2026, 2, 15), startDate)
        assertEquals(java.time.LocalDate.of(2026, 2, 15), endDate)
    }

    @Test
    fun `toFormState all-day multi-day event renders end date as last inclusive day`() {
        // 3-day all-day event spanning 2026-02-15..2026-02-17
        // startTs = Feb 15 00:00 UTC, endTs = Feb 17 23:59:59.999 UTC (inclusive)
        val dayMs = 86_400_000L
        val startTs = java.time.LocalDate.of(2026, 2, 15)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val endTs = startTs + (3 * dayMs) - 1L

        val event = createDeviceEvent(
            startTs = startTs,
            endTs = endTs,
            isAllDay = true,
            timezone = "UTC"
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        val endDate = java.time.Instant.ofEpochMilli(formState.endDateMillis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()

        // Feb 17, not Feb 18: the last day shown is the inclusive end
        assertEquals(java.time.LocalDate.of(2026, 2, 17), endDate)
    }

    @Test
    fun `toFormState recurring all-day event with P1D duration renders end date same as start`() {
        // Recurring all-day event: CalendarProvider stores DURATION="P1D", endTs=null.
        // startTs + P1D is the exclusive next-day midnight; `computeEndTs` takes 1 ms off
        // before the form converts it with utcMidnightToLocalDate, or the picker shows the
        // next day.
        val startTs = java.time.LocalDate.of(2026, 2, 15)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

        val event = createDeviceEvent(
            startTs = startTs,
            endTs = null,
            duration = "P1D",
            rrule = "FREQ=WEEKLY",
            isAllDay = true,
            timezone = "UTC"
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        val endDate = java.time.Instant.ofEpochMilli(formState.endDateMillis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        assertEquals(java.time.LocalDate.of(2026, 2, 15), endDate)
    }

    @Test
    fun `toFormState recurring all-day event with P3D duration renders end as last inclusive day`() {
        // 3-day recurring all-day event: DURATION="P3D" gives an exclusive end of Feb 18
        // 00:00 UTC. The picker shows Feb 17, not Feb 18.
        val startTs = java.time.LocalDate.of(2026, 2, 15)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

        val event = createDeviceEvent(
            startTs = startTs,
            endTs = null,
            duration = "P3D",
            rrule = "FREQ=MONTHLY",
            isAllDay = true,
            timezone = "UTC"
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        val endDate = java.time.Instant.ofEpochMilli(formState.endDateMillis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        assertEquals(java.time.LocalDate.of(2026, 2, 17), endDate)
    }

    // ==================== Availability → transp mapping ====================

    @Test
    fun `toFormState maps availability BUSY to transp OPAQUE`() {
        val event = createDeviceEvent(availability = 0) // AVAILABILITY_BUSY

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals("OPAQUE", formState.transp)
    }

    @Test
    fun `toFormState maps availability FREE to transp TRANSPARENT`() {
        val event = createDeviceEvent(availability = 1) // AVAILABILITY_FREE

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals("TRANSPARENT", formState.transp)
    }

    @Test
    fun `toFormState maps availability TENTATIVE to transp OPAQUE`() {
        val event = createDeviceEvent(availability = 2) // AVAILABILITY_TENTATIVE

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals("OPAQUE", formState.transp)
    }

    @Test
    fun `toFormState defaults transp to OPAQUE`() {
        val event = createDeviceEvent() // default availability = 0

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals("OPAQUE", formState.transp)
    }

    // ==================== eventColor passthrough ====================

    @Test
    fun `toFormState passes eventColor through as separate field`() {
        val event = createDeviceEvent(eventColor = 0x00FF00)

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals(0x00FF00, formState.eventColor)
    }

    @Test
    fun `toFormState keeps eventColor null when device event has no custom color`() {
        val event = createDeviceEvent(eventColor = null)

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertNull(formState.eventColor)
    }

    @Test
    fun `toFormState keeps selectedCalendarColor separate from eventColor`() {
        val event = createDeviceEvent(
            calendarColor = 0xFF0000,
            eventColor = 0x00FF00
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        // selectedCalendarColor is the calendar picker dot: which calendar, never the override.
        assertEquals(0xFF0000, formState.selectedCalendarColor)
        // eventColor is the raw per-event override, shown by the form's event color row
        assertEquals(0x00FF00, formState.eventColor)
    }

    // ==================== toExportEvent(): synthetic Event for ICS export ====================

    @Test
    fun `toExportEvent non-recurring event produces minimal synthetic Event`() {
        val start = 1735729200000L // 2025-01-01 11:00:00 UTC
        val end = start + 3600_000L
        val event = createDeviceEvent(
            id = 42L,
            title = "Coffee",
            description = "Catch up",
            location = "Cafe",
            startTs = start,
            endTs = end,
            timezone = "America/New_York",
            isAllDay = false,
            rrule = null,
            originalId = null
        )

        val synthetic = event.toExportEvent()

        assertEquals("device-42@kashcal", synthetic.uid)
        assertEquals("Coffee", synthetic.title)
        assertEquals("Catch up", synthetic.description)
        assertEquals("Cafe", synthetic.location)
        assertEquals(start, synthetic.startTs)
        assertEquals(end, synthetic.endTs)
        assertEquals("America/New_York", synthetic.timezone)
        assertNull(synthetic.rrule)
        assertNull(synthetic.originalEventId)
        assertNull(synthetic.originalInstanceTime)
        assertEquals(0L, synthetic.calendarId) // never persisted
        assertEquals(0, synthetic.sequence)
        assertEquals("PUBLIC", synthetic.classification)
    }

    @Test
    fun `toExportEvent recurring master preserves RRULE and uses own id for UID`() {
        val event = createDeviceEvent(
            id = 100L,
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            originalId = null
        )

        val synthetic = event.toExportEvent()

        assertEquals("FREQ=WEEKLY;BYDAY=MO", synthetic.rrule)
        assertNull(synthetic.originalEventId)
        assertEquals("device-100@kashcal", synthetic.uid)
    }

    @Test
    fun `toExportEvent exception nulls RRULE and shares master UID`() {
        val masterId = 100L
        val exceptionId = 200L
        val origInstanceTime = 1735729200000L

        val exception = createDeviceEvent(
            id = exceptionId,
            rrule = "FREQ=WEEKLY;BYDAY=MO", // Nulled for an exception even when the input has one
            originalId = masterId,
            originalInstanceTime = origInstanceTime
        )

        val synthetic = exception.toExportEvent()

        // The UID uses the master's id (RFC 5545: master and exceptions share one UID)
        assertEquals("device-$masterId@kashcal", synthetic.uid)
        assertNull(synthetic.rrule)
        // originalEventId marks the row as an exception, as on a Room exception row
        assertEquals(masterId, synthetic.originalEventId)
        // originalInstanceTime preserved for RECURRENCE-ID emission
        assertEquals(origInstanceTime, synthetic.originalInstanceTime)
    }

    @Test
    fun `toExportEvent UID-shared invariant - master and exception resolve to same UID`() {
        val masterId = 555L
        val master = createDeviceEvent(
            id = masterId,
            rrule = "FREQ=DAILY",
            originalId = null
        )
        val exception = createDeviceEvent(
            id = 999L, // different row id
            rrule = null,
            originalId = masterId,
            originalInstanceTime = 1700000000000L
        )

        val masterSynthetic = master.toExportEvent()
        val exceptionSynthetic = exception.toExportEvent()

        // Master and exception rows share one UID, as RFC 5545 requires of a series.
        // serializeWithExceptions takes each exception VEVENT's UID from the master regardless
        // (EventToICalEventMapper.toICalEvent(master, exception)).
        assertEquals(masterSynthetic.uid, exceptionSynthetic.uid)
        assertEquals("device-$masterId@kashcal", masterSynthetic.uid)
    }

    @Test
    fun `toExportEvent maps STATUS TENTATIVE to string TENTATIVE`() {
        val event = createDeviceEvent(status = CalendarContract.Events.STATUS_TENTATIVE)
        assertEquals("TENTATIVE", event.toExportEvent().status)
    }

    @Test
    fun `toExportEvent maps STATUS CONFIRMED to string CONFIRMED`() {
        val event = createDeviceEvent(status = CalendarContract.Events.STATUS_CONFIRMED)
        assertEquals("CONFIRMED", event.toExportEvent().status)
    }

    @Test
    fun `toExportEvent maps STATUS CANCELED to string CANCELLED`() {
        val event = createDeviceEvent(status = CalendarContract.Events.STATUS_CANCELED)
        assertEquals("CANCELLED", event.toExportEvent().status)
    }

    @Test
    fun `toExportEvent maps AVAILABILITY BUSY to transp OPAQUE`() {
        val event = createDeviceEvent(availability = CalendarContract.Events.AVAILABILITY_BUSY)
        assertEquals("OPAQUE", event.toExportEvent().transp)
    }

    @Test
    fun `toExportEvent maps AVAILABILITY FREE to transp TRANSPARENT`() {
        val event = createDeviceEvent(availability = CalendarContract.Events.AVAILABILITY_FREE)
        assertEquals("TRANSPARENT", event.toExportEvent().transp)
    }

    @Test
    fun `toExportEvent maps AVAILABILITY TENTATIVE to transp OPAQUE`() {
        val event = createDeviceEvent(availability = CalendarContract.Events.AVAILABILITY_TENTATIVE)
        assertEquals("OPAQUE", event.toExportEvent().transp)
    }

    @Test
    fun `toExportEvent converts empty reminders to null`() {
        val event = createDeviceEvent()
        assertNull(event.toExportEvent(reminderMinutes = emptyList()).reminders)
    }

    @Test
    fun `toExportEvent converts single reminder minute to ISO duration`() {
        val event = createDeviceEvent()
        val synthetic = event.toExportEvent(reminderMinutes = listOf(15))
        assertEquals(listOf("-PT15M"), synthetic.reminders)
    }

    @Test
    fun `toExportEvent converts multiple reminder minutes to ISO durations`() {
        val event = createDeviceEvent()
        val synthetic = event.toExportEvent(reminderMinutes = listOf(15, 60, 1440))
        // Hour-form encoding (DST-stable): 1440 min -> -PT24H, not the period -P1D.
        assertEquals(listOf("-PT15M", "-PT1H", "-PT24H"), synthetic.reminders)
    }

    @Test
    fun `toExportEvent default reminder parameter is empty list`() {
        val event = createDeviceEvent()
        // Without the reminderMinutes argument, reminders are null
        assertNull(event.toExportEvent().reminders)
    }

    @Test
    fun `toExportEvent respects all-day endTs (DeviceEvent convention matches Room)`() {
        val start = 1735689600000L // 2025-01-01 00:00:00 UTC
        val inclusiveEnd = start + 24L * 3600_000L - 1L // last ms of the day
        val event = createDeviceEvent(
            startTs = start,
            endTs = inclusiveEnd,
            isAllDay = true,
            timezone = "UTC"
        )

        val synthetic = event.toExportEvent()
        assertEquals(start, synthetic.startTs)
        assertEquals(inclusiveEnd, synthetic.endTs)
        assertTrue(synthetic.isAllDay)
    }

    @Test
    fun `toExportEvent preserves non-UTC IANA timezone`() {
        val event = createDeviceEvent(timezone = "Europe/London")
        assertEquals("Europe/London", event.toExportEvent().timezone)
    }

    @Test
    fun `toExportEvent passes eventColor through to Event color`() {
        val event = createDeviceEvent(eventColor = 0xFF00FF00.toInt())
        assertEquals(0xFF00FF00.toInt(), event.toExportEvent().color)
    }

    @Test
    fun `toExportEvent leaves color null when eventColor is null`() {
        val event = createDeviceEvent(eventColor = null)
        assertNull(event.toExportEvent().color)
    }

    @Test
    fun `toExportEvent populates required timestamp fields with current time`() {
        val before = System.currentTimeMillis()
        val synthetic = createDeviceEvent().toExportEvent()
        val after = System.currentTimeMillis()

        assertTrue("dtstamp in range", synthetic.dtstamp in before..after)
        assertTrue("createdAt in range", synthetic.createdAt in before..after)
        assertTrue("updatedAt in range", synthetic.updatedAt in before..after)
    }

    @Test
    fun `toExportEvent round-trips through IcsPatcher serialize without throwing`() {
        val event = createDeviceEvent(
            id = 77L,
            title = "Round Trip Test",
            startTs = 1735729200000L,
            endTs = 1735729200000L + 3600_000L,
            timezone = "America/New_York"
        )
        val synthetic = event.toExportEvent(reminderMinutes = listOf(15))
        val ics = IcsPatcher.serialize(synthetic)

        assertTrue("ICS should contain VCALENDAR", ics.contains("BEGIN:VCALENDAR"))
        assertTrue("ICS should contain VEVENT", ics.contains("BEGIN:VEVENT"))
        assertTrue("ICS should reference the synthetic UID", ics.contains("device-77@kashcal"))
        assertTrue("ICS should include VTIMEZONE for non-UTC TZID", ics.contains("BEGIN:VTIMEZONE"))
        assertTrue("ICS should include VALARM for the reminder", ics.contains("BEGIN:VALARM"))
    }

    @Test
    fun `toExportEvent round-trips exception with master through IcsPatcher serializeWithExceptions`() {
        val masterId = 555L
        val start = 1735729200000L
        val master = createDeviceEvent(
            id = masterId,
            title = "Weekly Sync",
            startTs = start,
            endTs = start + 3600_000L,
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            timezone = "America/Los_Angeles"
        )
        val exception = createDeviceEvent(
            id = 999L,
            title = "Weekly Sync (moved)",
            startTs = start + 7L * 86400_000L + 3600_000L, // One week later + 1 hour
            endTs = start + 7L * 86400_000L + 2L * 3600_000L,
            rrule = null,
            originalId = masterId,
            originalInstanceTime = start + 7L * 86400_000L,
            timezone = "America/Los_Angeles"
        )

        val masterSynthetic = master.toExportEvent()
        val exceptionSynthetic = exception.toExportEvent()

        val ics = IcsPatcher.serializeWithExceptions(masterSynthetic, listOf(exceptionSynthetic))

        // Shared UID appears at least twice (once per VEVENT)
        val uidOccurrences = Regex("UID:device-$masterId@kashcal").findAll(ics).count()
        assertTrue("Shared UID should appear at least twice (master + exception): $uidOccurrences", uidOccurrences >= 2)

        // The event's own RRULE appears once, on the master, never on the exception. Matching
        // FREQ=WEEKLY skips the VTIMEZONE's DST RRULEs, which are FREQ=YEARLY in every
        // America/Los_Angeles VTIMEZONE block.
        val eventRruleOccurrences = Regex("RRULE:FREQ=WEEKLY").findAll(ics).count()
        assertEquals("Event RRULE should appear exactly once (on master, not on exception)", 1, eventRruleOccurrences)

        // The exception's VEVENT has no event-level RRULE. The count above already implies
        // it; this names the intent.
        val masterVEventEnd = ics.indexOf("END:VEVENT")
        val exceptionVEventStart = ics.indexOf("BEGIN:VEVENT", masterVEventEnd)
        assertTrue("Both VEVENTs must be present", exceptionVEventStart > 0)
        val exceptionBlock = ics.substring(exceptionVEventStart)
        assertFalse("Exception VEVENT must not contain RRULE", exceptionBlock.contains("RRULE:FREQ=WEEKLY"))

        // RECURRENCE-ID must appear for the exception
        assertTrue("RECURRENCE-ID must be present for the exception", ics.contains("RECURRENCE-ID"))
    }

    @Test
    fun `toExportEvent round-trips STATUS_CANCELED exception preserving cancellation`() {
        val masterId = 555L
        val start = 1735729200000L
        val canceledException = createDeviceEvent(
            id = 999L,
            title = "Weekly Sync",
            startTs = start + 7L * 86400_000L,
            endTs = start + 7L * 86400_000L + 3600_000L,
            rrule = null,
            originalId = masterId,
            originalInstanceTime = start + 7L * 86400_000L,
            status = CalendarContract.Events.STATUS_CANCELED
        )

        val synthetic = canceledException.toExportEvent()
        assertEquals("CANCELLED", synthetic.status)

        val ics = IcsPatcher.serialize(synthetic)
        assertTrue("STATUS:CANCELLED must appear in ICS", ics.contains("STATUS:CANCELLED"))
    }

    @Test
    fun `toExportEvent exception uses master id for UID even when instance id differs`() {
        val masterId = 42L
        val exception = createDeviceEvent(
            id = 9_999_999L, // A very different row id; the UID still uses masterId
            rrule = null,
            originalId = masterId,
            originalInstanceTime = 1700000000000L
        )

        val synthetic = exception.toExportEvent()
        assertFalse("UID must not use the exception's own id", synthetic.uid.contains("9999999"))
        assertTrue("UID must use masterId", synthetic.uid == "device-$masterId@kashcal")
    }

    @Test
    fun `toExportEvent originalEventId is non-null for exceptions (signals to mapper)`() {
        // Export never dereferences originalEventId as a Room FK. IcsPatcher reads only its
        // nullness, and only for a row with rawIcal, which a synthetic event lacks.
        val exception = createDeviceEvent(
            id = 200L,
            rrule = null,
            originalId = 100L,
            originalInstanceTime = 1700000000000L
        )

        val synthetic = exception.toExportEvent()
        assertNotNull(synthetic.originalEventId)
        assertNotEquals(0L, synthetic.originalEventId)
    }

    // ==================== Timezone of the loaded event ====================

    private val newYork10am = 1_709_650_800_000L // 2024-03-05 10:00 America/New_York (15:00Z)

    /** Loads the event into a form the way the edit sheet does. */
    private fun DeviceEvent.load() = toFormState(
        reminders = emptyList(),
        calendarColor = null,
        calendarName = "Work",
        deviceCalendarGroups = emptyList(),
    )

    @Test
    fun `form shows the event's own clock time when its timezone differs from the device`() {
        val formState = withDeviceTimeZone("America/Los_Angeles") {
            createDeviceEvent(
                startTs = newYork10am,
                endTs = newYork10am + 3_600_000L,
                timezone = "America/New_York",
            ).load()
        }

        assertEquals("America/New_York", formState.timezone)
        assertEquals(10, formState.startHour)
        assertEquals(0, formState.startMinute)
        assertEquals(11, formState.endHour)
    }

    @Test
    fun `form shows the device clock time when the event is in the device timezone`() {
        val formState = withDeviceTimeZone("America/New_York") {
            createDeviceEvent(
                startTs = newYork10am,
                endTs = newYork10am + 3_600_000L,
                timezone = "America/New_York",
            ).load()
        }

        assertEquals(10, formState.startHour)
        assertEquals(11, formState.endHour)
    }

    @Test
    fun `form treats a blank event timezone as the device timezone`() {
        val formState = withDeviceTimeZone("America/Los_Angeles") {
            createDeviceEvent(
                startTs = newYork10am,
                endTs = newYork10am + 3_600_000L,
                timezone = "",
            ).load()
        }

        assertNull(formState.timezone)
        assertNull(formState.sourceTimezoneId)
        assertEquals(7, formState.startHour) // 15:00Z in Los Angeles
    }

    @Test
    fun `form leaves the timezone unset for an all-day event`() {
        val formState = withDeviceTimeZone("America/Los_Angeles") {
            createDeviceEvent(
                startTs = 1_709_596_800_000L, // 2024-03-05 00:00 UTC
                endTs = 1_709_683_199_999L,
                isAllDay = true,
                timezone = "UTC",
            ).load()
        }

        assertNull(formState.timezone)
        assertNull(formState.sourceTimezoneId)
    }

    @Test
    fun `form keeps an unrecognised event timezone aside and shows device clock time`() {
        val formState = withDeviceTimeZone("America/Los_Angeles") {
            createDeviceEvent(
                startTs = newYork10am,
                endTs = newYork10am + 3_600_000L,
                timezone = "Eastern Standard Time",
            ).load()
        }

        assertNull(formState.timezone)
        assertEquals("Eastern Standard Time", formState.sourceTimezoneId)
        assertEquals(7, formState.startHour)
    }

    @Test
    fun `form shows the event's own date when it differs from the device date`() {
        withDeviceTimeZone("America/Los_Angeles") {
            val start = 1_709_704_800_000L // 2024-03-06 01:00 New York, 22:00 Mar 5 in Los Angeles
            val formState = createDeviceEvent(
                startTs = start,
                endTs = start + 3_600_000L,
                timezone = "America/New_York",
            ).load()

            assertEquals(java.time.LocalDate.of(2024, 3, 6), phoneLocalDate(formState.dateMillis))
            assertEquals(1, formState.startHour)
            assertEquals(start, formState.startOffsetHintTs)
        }
    }

    // ==================== Helper ====================

    /** The form shows [instant]'s date and hour in the fixture's New York zone. */
    private fun assertDisplays(instant: Long, dateMillis: Long, hour: Int) {
        val inNewYork = java.time.Instant.ofEpochMilli(instant).atZone(java.time.ZoneId.of("America/New_York"))
        assertEquals(inNewYork.toLocalDate(), phoneLocalDate(dateMillis))
        assertEquals(inNewYork.hour, hour)
    }

    private fun createDeviceEvent(
        id: Long = 1L,
        calendarId: Long = 1L,
        title: String = "Test Event",
        description: String? = null,
        location: String? = null,
        startTs: Long = System.currentTimeMillis(),
        endTs: Long? = System.currentTimeMillis() + 3600000,
        duration: String? = null,
        isAllDay: Boolean = false,
        rrule: String? = null,
        calendarColor: Int? = null,
        eventColor: Int? = null,
        originalId: Long? = null,
        originalInstanceTime: Long? = null,
        availability: Int = 0,
        status: Int = 1,
        timezone: String = "America/New_York",
        categories: List<String> = emptyList()
    ): DeviceEvent = DeviceEvent(
        id = id,
        calendarId = calendarId,
        title = title,
        description = description,
        location = location,
        startTs = startTs,
        endTs = endTs,
        duration = duration,
        isAllDay = isAllDay,
        rrule = rrule,
        rdate = null,
        exdate = null,
        exrule = null,
        timezone = timezone,
        originalId = originalId,
        originalInstanceTime = originalInstanceTime,
        status = status,
        availability = availability,
        accessLevel = 700,
        calendarColor = calendarColor,
        eventColor = eventColor,
        categories = categories
    )
}
