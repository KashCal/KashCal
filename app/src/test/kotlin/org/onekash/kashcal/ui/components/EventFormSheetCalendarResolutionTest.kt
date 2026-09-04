package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.model.PickerCalendar

/**
 * Unit tests for resolveDefaultCalendar() — pure function extracted from
 * EventFormSheet's LaunchedEffect for default calendar resolution.
 */
class EventFormSheetCalendarResolutionTest {

    // ========== Test fixtures ==========

    private fun makeCalendar(
        id: Long,
        name: String = "Calendar $id",
        color: Int = 0xFF0000,
        isReadOnly: Boolean = false
    ) = Calendar(
        id = id,
        accountId = 1L,
        caldavUrl = "https://example.com/cal/$id",
        displayName = name,
        color = color,
        isReadOnly = isReadOnly
    )

    private fun makeDeviceCalendar(
        id: Long,
        name: String = "Device Calendar $id",
        color: Int = 0x00FF00,
        accessLevel: Int = 700
    ) = DeviceCalendar(
        id = id,
        displayName = name,
        color = color,
        accountName = "test@example.com",
        accountType = "com.google",
        visible = true,
        accessLevel = accessLevel
    )

    private fun makeSourceEvent(calendarId: Long) = Event(
        id = 1L,
        uid = "dup-source@test",
        calendarId = calendarId,
        title = "Team Lunch",
        startTs = 0L,
        endTs = 0L,
        dtstamp = 0L,
    )

    private fun makeDeviceCalendarGroup(vararg calendars: DeviceCalendar) = CalendarGroup(
        accountName = "Device Account",
        accountId = -1,
        calendars = emptyList(),
        pickerCalendars = calendars.map { PickerCalendar.Device(it) },
        isDeviceSection = true
    )

    // ========== DefaultCalendar.Room tests ==========

    @Test
    fun `Room default with valid ID returns that calendar`() {
        val cal1 = makeCalendar(1L, "Work", 0xFF0000)
        val cal2 = makeCalendar(2L, "Personal", 0x00FF00)

        val result = resolveDefaultCalendar(
            DefaultCalendar.Room(1L),
            listOf(cal1, cal2),
            emptyList()
        )

        assertEquals(1L, result.id)
        assertEquals("Work", result.name)
        assertEquals(0xFF0000, result.color)
        assertFalse(result.isDevice)
    }

    @Test
    fun `Room default with invalid ID falls back to first writable`() {
        val cal1 = makeCalendar(1L, "Work", 0xFF0000)

        val result = resolveDefaultCalendar(
            DefaultCalendar.Room(999L),
            listOf(cal1),
            emptyList()
        )

        assertEquals(1L, result.id)
        assertEquals("Work", result.name)
        assertEquals(0xFF0000, result.color)
        assertFalse(result.isDevice)
    }

    @Test
    fun `Room default with invalid ID and empty calendars returns null`() {
        val result = resolveDefaultCalendar(
            DefaultCalendar.Room(999L),
            emptyList(),
            emptyList()
        )

        assertNull(result.id)
        assertEquals("", result.name)
        assertNull(result.color)
        assertFalse(result.isDevice)
    }

    // ========== DefaultCalendar.Device tests ==========

    @Test
    fun `Device default with valid ID returns that device calendar`() {
        val deviceCal = makeDeviceCalendar(10L, "Google Cal", 0x0000FF)
        val deviceGroup = makeDeviceCalendarGroup(deviceCal)

        val result = resolveDefaultCalendar(
            DefaultCalendar.Device(10L),
            listOf(makeCalendar(1L)),
            listOf(deviceGroup)
        )

        assertEquals(10L, result.id)
        assertEquals("Google Cal", result.name)
        assertEquals(0x0000FF, result.color)
        assertTrue(result.isDevice)
    }

    @Test
    fun `Device default with invalid ID falls back to first writable Room calendar`() {
        val cal1 = makeCalendar(1L, "Work", 0xFF0000)
        val deviceGroup = makeDeviceCalendarGroup(makeDeviceCalendar(10L))

        val result = resolveDefaultCalendar(
            DefaultCalendar.Device(999L),
            listOf(cal1),
            listOf(deviceGroup)
        )

        assertEquals(1L, result.id)
        assertEquals("Work", result.name)
        assertEquals(0xFF0000, result.color)
        assertFalse(result.isDevice)
    }

    @Test
    fun `Device default with invalid ID and empty calendars returns null`() {
        val result = resolveDefaultCalendar(
            DefaultCalendar.Device(999L),
            emptyList(),
            emptyList()
        )

        assertNull(result.id)
        assertEquals("", result.name)
        assertNull(result.color)
        assertFalse(result.isDevice)
    }

    // ========== null default tests ==========

    @Test
    fun `null default with non-empty calendars returns first writable`() {
        val cal1 = makeCalendar(1L, "First", 0xAAAAAA)
        val cal2 = makeCalendar(2L, "Second", 0xBBBBBB)

        val result = resolveDefaultCalendar(
            null,
            listOf(cal1, cal2),
            emptyList()
        )

        assertEquals(1L, result.id)
        assertEquals("First", result.name)
        assertEquals(0xAAAAAA, result.color)
        assertFalse(result.isDevice)
    }

    @Test
    fun `null default with empty calendars returns null ID`() {
        val result = resolveDefaultCalendar(
            null,
            emptyList(),
            emptyList()
        )

        assertNull(result.id)
        assertEquals("", result.name)
        assertNull(result.color)
        assertFalse(result.isDevice)
    }

    // ========== isDevice flag invariant ==========
    //
    // The isDevice flag is the single switch that routes a save to the Room
    // (scheduling/iTIP) path vs. the device (CalendarProvider) path. A flag
    // inversion would send device attendees into the CalDAV scheduling stack
    // (or vice versa), so pin the invariant: resolving a Room selection is
    // never isDevice=true, and resolving a Device selection is never
    // isDevice=false (when the calendar exists).

    @Test
    fun `every resolvable Room selection is not flagged as device`() {
        val rooms = listOf(makeCalendar(1L), makeCalendar(2L), makeCalendar(3L))
        val deviceGroup = makeDeviceCalendarGroup(makeDeviceCalendar(10L), makeDeviceCalendar(11L))

        rooms.forEach { cal ->
            val result = resolveDefaultCalendar(DefaultCalendar.Room(cal.id), rooms, listOf(deviceGroup))
            assertEquals(cal.id, result.id)
            assertFalse("Room calendar ${cal.id} must resolve isDevice=false", result.isDevice)
        }
    }

    @Test
    fun `every resolvable Device selection is flagged as device`() {
        val rooms = listOf(makeCalendar(1L), makeCalendar(2L))
        val deviceCals = listOf(makeDeviceCalendar(10L), makeDeviceCalendar(11L), makeDeviceCalendar(12L))
        val deviceGroup = makeDeviceCalendarGroup(*deviceCals.toTypedArray())

        deviceCals.forEach { cal ->
            val result = resolveDefaultCalendar(DefaultCalendar.Device(cal.id), rooms, listOf(deviceGroup))
            assertEquals(cal.id, result.id)
            assertTrue("Device calendar ${cal.id} must resolve isDevice=true", result.isDevice)
        }
    }

    // ========== resolveDuplicateSourceCalendar ==========
    //
    // A duplicate keeps its SOURCE calendar. Room-backed events carry the
    // source id on Event.calendarId; device events zero it (device ids live in
    // a separate namespace) and pass the source device calendar id on a
    // dedicated channel. This resolver picks Room source, then device source,
    // then falls back to the already-resolved default. The isDevice flag it
    // returns routes the save to the Room vs. device path, so the same
    // flag-inversion invariant applies here as for resolveDefaultCalendar.

    private val fallbackDefault = ResolvedCalendar(
        id = 7L,
        name = "Default Cal",
        color = 0x123456,
        isDevice = false
    )

    @Test
    fun `duplicate of a Room event keeps the Room source calendar`() {
        val cal1 = makeCalendar(1L, "Work", 0xFF0000)

        val result = resolveDuplicateSourceCalendar(
            duplicateFrom = makeSourceEvent(calendarId = 1L),
            duplicateFromDeviceCalendarId = null,
            writableCalendars = listOf(cal1),
            deviceCalendarGroups = emptyList(),
            resolvedDefault = fallbackDefault
        )

        assertEquals(1L, result.id)
        assertEquals("Work", result.name)
        assertEquals(0xFF0000, result.color)
        assertFalse(result.isDevice)
    }

    @Test
    fun `duplicate of a device event resolves the source device calendar`() {
        val deviceCal = makeDeviceCalendar(10L, "Google Cal", 0x0000FF)
        val deviceGroup = makeDeviceCalendarGroup(deviceCal)

        val result = resolveDuplicateSourceCalendar(
            duplicateFrom = makeSourceEvent(calendarId = 0L),
            duplicateFromDeviceCalendarId = 10L,
            writableCalendars = listOf(makeCalendar(1L)),
            deviceCalendarGroups = listOf(deviceGroup),
            resolvedDefault = fallbackDefault
        )

        assertEquals(10L, result.id)
        assertEquals("Google Cal", result.name)
        assertEquals(0x0000FF, result.color)
        assertTrue(result.isDevice)
    }

    @Test
    fun `duplicate falls back to default when the source device calendar is gone`() {
        val deviceGroup = makeDeviceCalendarGroup(makeDeviceCalendar(10L))

        val result = resolveDuplicateSourceCalendar(
            duplicateFrom = makeSourceEvent(calendarId = 0L),
            duplicateFromDeviceCalendarId = 99L,
            writableCalendars = listOf(makeCalendar(1L)),
            deviceCalendarGroups = listOf(deviceGroup),
            resolvedDefault = fallbackDefault
        )

        assertEquals(fallbackDefault.id, result.id)
        assertEquals(fallbackDefault.name, result.name)
        assertEquals(fallbackDefault.color, result.color)
        assertEquals(fallbackDefault.isDevice, result.isDevice)
    }

    @Test
    fun `duplicate falls back to default when the source device calendar is not writable`() {
        val readOnlyDevice = makeDeviceCalendar(10L, accessLevel = 200) // < CONTRIBUTOR (500)
        val deviceGroup = makeDeviceCalendarGroup(readOnlyDevice)

        val result = resolveDuplicateSourceCalendar(
            duplicateFrom = makeSourceEvent(calendarId = 0L),
            duplicateFromDeviceCalendarId = 10L,
            writableCalendars = listOf(makeCalendar(1L)),
            deviceCalendarGroups = listOf(deviceGroup),
            resolvedDefault = fallbackDefault
        )

        assertEquals(fallbackDefault.id, result.id)
        assertFalse(result.isDevice)
    }

    @Test
    fun `duplicate Room source takes precedence over a device calendar id`() {
        val cal1 = makeCalendar(1L, "Work", 0xFF0000)
        val deviceGroup = makeDeviceCalendarGroup(makeDeviceCalendar(10L))

        val result = resolveDuplicateSourceCalendar(
            duplicateFrom = makeSourceEvent(calendarId = 1L),
            duplicateFromDeviceCalendarId = 10L,
            writableCalendars = listOf(cal1),
            deviceCalendarGroups = listOf(deviceGroup),
            resolvedDefault = fallbackDefault
        )

        assertEquals(1L, result.id)
        assertFalse(result.isDevice)
    }
}
