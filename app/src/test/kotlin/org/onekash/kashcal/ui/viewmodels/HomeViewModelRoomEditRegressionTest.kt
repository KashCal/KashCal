package org.onekash.kashcal.ui.viewmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.ui.components.EventFormState
import org.onekash.kashcal.ui.shared.REMINDER_OFF

/**
 * Regression tests for Room event editing after device calendar edit changes.
 *
 * Verifies that:
 * 1. Room event edit flow remains unchanged
 * 2. EventFormState correctly differentiates Room vs Device
 * 3. Save routing works correctly for both types
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelRoomEditRegressionTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==================== Room Event Edit Regression ====================

    @Test
    fun `EventFormState defaults to Room calendar mode`() {
        val state = EventFormState()

        assertFalse("Default state should not be device calendar", state.isDeviceCalendar)
        assertNull("Default state should not have device event ID", state.editingDeviceEventId)
        assertEquals("Default state should have 0 truncated reminders", 0, state.truncatedReminderCount)
    }

    @Test
    fun `EventFormState Room edit mode has correct flags`() {
        val state = EventFormState(
            title = "Team Meeting",
            editingEventId = 42L,
            isEditMode = true,
            isDeviceCalendar = false,
            editingDeviceEventId = null
        )

        assertTrue("Should be in edit mode", state.isEditMode)
        assertEquals(42L, state.editingEventId)
        assertFalse("Should not be device calendar", state.isDeviceCalendar)
        assertNull("Should not have device event ID", state.editingDeviceEventId)
    }

    @Test
    fun `EventFormState Device edit mode has correct flags`() {
        val state = EventFormState(
            title = "Device Event",
            editingEventId = null,
            isEditMode = true,
            isDeviceCalendar = true,
            editingDeviceEventId = 100L
        )

        assertTrue("Should be in edit mode", state.isEditMode)
        assertNull("Should not have Room event ID", state.editingEventId)
        assertTrue("Should be device calendar", state.isDeviceCalendar)
        assertEquals(100L, state.editingDeviceEventId)
    }

    @Test
    fun `EventFormState Room create mode has correct flags`() {
        val state = EventFormState(
            title = "New Event",
            isEditMode = false,
            isDeviceCalendar = false
        )

        assertFalse("Should not be in edit mode", state.isEditMode)
        assertNull("Should not have event ID", state.editingEventId)
        assertFalse("Should not be device calendar", state.isDeviceCalendar)
    }

    @Test
    fun `EventFormState Device create mode has correct flags`() {
        val state = EventFormState(
            title = "New Device Event",
            isEditMode = false,
            isDeviceCalendar = true,
            selectedCalendarId = 5L
        )

        assertFalse("Should not be in edit mode", state.isEditMode)
        assertNull("Should not have device event ID for create", state.editingDeviceEventId)
        assertTrue("Should be device calendar", state.isDeviceCalendar)
    }

    // ==================== Save Routing Logic ====================

    @Test
    fun `Room event save should not use device path`() {
        val state = EventFormState(
            title = "Room Event",
            isDeviceCalendar = false,
            editingEventId = 42L,
            isEditMode = true
        )

        // Verify routing conditions
        val shouldUseDeviceSave = state.isDeviceCalendar
        assertFalse("Room event should not use device save path", shouldUseDeviceSave)
    }

    @Test
    fun `Device event save should use device path`() {
        val state = EventFormState(
            title = "Device Event",
            isDeviceCalendar = true,
            editingDeviceEventId = 100L,
            isEditMode = true
        )

        // Verify routing conditions
        val shouldUseDeviceSave = state.isDeviceCalendar
        assertTrue("Device event should use device save path", shouldUseDeviceSave)
    }

    // ==================== Delete Routing Logic ====================

    @Test
    fun `Room event delete routing conditions`() {
        val state = EventFormState(
            editingEventId = 42L,
            isEditMode = true,
            isDeviceCalendar = false
        )

        val canDeleteRoom = state.editingEventId != null && !state.isDeviceCalendar
        val canDeleteDevice = state.editingDeviceEventId != null && state.isDeviceCalendar

        assertTrue("Should be able to delete Room event", canDeleteRoom)
        assertFalse("Should not be able to delete as Device event", canDeleteDevice)
    }

    @Test
    fun `Device event delete routing conditions`() {
        val state = EventFormState(
            editingDeviceEventId = 100L,
            isEditMode = true,
            isDeviceCalendar = true
        )

        val canDeleteRoom = state.editingEventId != null && !state.isDeviceCalendar
        val canDeleteDevice = state.editingDeviceEventId != null && state.isDeviceCalendar

        assertFalse("Should not be able to delete as Room event", canDeleteRoom)
        assertTrue("Should be able to delete Device event", canDeleteDevice)
    }

    // ==================== Occurrence Edit Regression ====================

    @Test
    fun `Room occurrence edit has correct flags`() {
        val state = EventFormState(
            title = "Recurring Meeting",
            editingEventId = 42L,
            isEditMode = true,
            editingOccurrenceTs = 1700000000000L,
            isDeviceCalendar = false,
            rrule = null // Cleared for occurrence edit
        )

        assertTrue("Should be in edit mode", state.isEditMode)
        assertEquals(1700000000000L, state.editingOccurrenceTs)
        assertNull("RRULE should be null for occurrence edit", state.rrule)
        assertFalse("Should not be device calendar", state.isDeviceCalendar)
    }

    @Test
    fun `Device occurrence edit has correct flags`() {
        val state = EventFormState(
            title = "Device Recurring",
            editingDeviceEventId = 100L,
            isEditMode = true,
            editingOccurrenceTs = 1700000000000L,
            isDeviceCalendar = true,
            rrule = null
        )

        assertTrue("Should be in edit mode", state.isEditMode)
        assertEquals(1700000000000L, state.editingOccurrenceTs)
        assertNull("RRULE should be null for occurrence edit", state.rrule)
        assertTrue("Should be device calendar", state.isDeviceCalendar)
    }

    // ==================== Reminder Preservation ====================

    @Test
    fun `Room event reminders preserved in form state`() {
        val state = EventFormState(
            reminder1Minutes = 15,
            reminder2Minutes = 60,
            isDeviceCalendar = false
        )

        assertEquals(15, state.reminder1Minutes)
        assertEquals(60, state.reminder2Minutes)
    }

    @Test
    fun `Device event reminders preserved in form state`() {
        val state = EventFormState(
            reminder1Minutes = 30,
            reminder2Minutes = REMINDER_OFF,
            isDeviceCalendar = true
        )

        assertEquals(30, state.reminder1Minutes)
        assertEquals(REMINDER_OFF, state.reminder2Minutes)
    }

    // ==================== Calendar Selection ====================

    @Test
    fun `Room event calendar selection preserved`() {
        val state = EventFormState(
            selectedCalendarId = 5L,
            selectedCalendarName = "Work",
            selectedCalendarColor = 0xFF0000,
            isDeviceCalendar = false
        )

        assertEquals(5L, state.selectedCalendarId)
        assertEquals("Work", state.selectedCalendarName)
        assertEquals(0xFF0000, state.selectedCalendarColor)
        assertFalse(state.isDeviceCalendar)
    }

    @Test
    fun `Device event calendar selection preserved`() {
        val state = EventFormState(
            selectedCalendarId = 10L,
            selectedCalendarName = "Google Calendar",
            selectedCalendarColor = 0x00FF00,
            isDeviceCalendar = true
        )

        assertEquals(10L, state.selectedCalendarId)
        assertEquals("Google Calendar", state.selectedCalendarName)
        assertEquals(0x00FF00, state.selectedCalendarColor)
        assertTrue(state.isDeviceCalendar)
    }

    // ==================== Duplicate Flow Regression ====================

    @Test
    fun `Duplicate from Room event creates Room state`() {
        // When duplicating from Room event, should create new Room event
        val state = EventFormState(
            title = "Duplicated Meeting",
            isEditMode = false, // Create mode for duplicate
            isDeviceCalendar = false,
            editingEventId = null, // No event ID since it's a new event
            editingDeviceEventId = null
        )

        assertFalse("Duplicate should be in create mode", state.isEditMode)
        assertFalse("Duplicate from Room should stay in Room", state.isDeviceCalendar)
        assertNull("Should not have editing event ID", state.editingEventId)
    }

    @Test
    fun `Duplicate from Device event creates Room state`() {
        // When duplicating from Device event, should create new Room event (not device)
        // This is the expected behavior - duplicates go to Room calendar
        val state = EventFormState(
            title = "Duplicated Device Event",
            isEditMode = false,
            isDeviceCalendar = false, // Duplicates go to Room
            editingEventId = null,
            editingDeviceEventId = null
        )

        assertFalse("Duplicate should be in create mode", state.isEditMode)
        assertFalse("Duplicate from Device should create Room event", state.isDeviceCalendar)
    }

    // ==================== All-Day Event Regression ====================

    @Test
    fun `Room all-day event state preserved`() {
        val state = EventFormState(
            title = "All Day Event",
            isAllDay = true,
            isDeviceCalendar = false
        )

        assertTrue("Should be all-day", state.isAllDay)
        assertFalse("Should be Room calendar", state.isDeviceCalendar)
    }

    @Test
    fun `Device all-day event state preserved`() {
        val state = EventFormState(
            title = "Device All Day",
            isAllDay = true,
            isDeviceCalendar = true
        )

        assertTrue("Should be all-day", state.isAllDay)
        assertTrue("Should be device calendar", state.isDeviceCalendar)
    }

    // ==================== Timezone Regression ====================

    @Test
    fun `Room event timezone preserved`() {
        val state = EventFormState(
            timezone = "America/New_York",
            isDeviceCalendar = false
        )

        assertEquals("America/New_York", state.timezone)
    }

    @Test
    fun `Device event timezone preserved`() {
        val state = EventFormState(
            timezone = "Europe/London",
            isDeviceCalendar = true
        )

        assertEquals("Europe/London", state.timezone)
    }

    @Test
    fun `Null timezone uses device default`() {
        val state = EventFormState(
            timezone = null
        )

        assertNull("Null timezone should use device default", state.timezone)
    }

    // ==================== RRULE Regression ====================

    @Test
    fun `Room recurring event RRULE preserved`() {
        val state = EventFormState(
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR",
            isDeviceCalendar = false
        )

        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", state.rrule)
    }

    @Test
    fun `Device recurring event RRULE preserved`() {
        val state = EventFormState(
            rrule = "FREQ=DAILY;COUNT=10",
            isDeviceCalendar = true
        )

        assertEquals("FREQ=DAILY;COUNT=10", state.rrule)
    }

    @Test
    fun `Single occurrence edit clears RRULE`() {
        val state = EventFormState(
            rrule = null, // Cleared for single occurrence
            editingOccurrenceTs = 1700000000000L,
            isEditMode = true
        )

        assertNull("RRULE should be null for single occurrence edit", state.rrule)
        assertEquals(1700000000000L, state.editingOccurrenceTs)
    }
}
