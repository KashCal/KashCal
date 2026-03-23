package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.db.entity.Calendar
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
        color: Int = 0x00FF00
    ) = DeviceCalendar(
        id = id,
        displayName = name,
        color = color,
        accountName = "test@example.com",
        accountType = "com.google",
        visible = true,
        accessLevel = 700
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
}
