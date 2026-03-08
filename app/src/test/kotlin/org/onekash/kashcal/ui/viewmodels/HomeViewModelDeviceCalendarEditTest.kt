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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.calendar_provider.DeviceEvent
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.ui.viewmodels.DeviceEventEditData

/**
 * Tests for HomeViewModel device calendar edit methods.
 *
 * Covers:
 * - Chunk 0: canEditDeviceEvent() pre-edit validation
 * - Chunk 1: getDeviceEventForEdit() loading
 * - Chunk 5: saveDeviceEvent() routing
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelDeviceCalendarEditTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeCalendarProviderRepo: FakeCalendarProviderRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeCalendarProviderRepo = FakeCalendarProviderRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        fakeCalendarProviderRepo.reset()
    }

    // ==================== Chunk 0: canEditDeviceEvent Tests ====================

    @Test
    fun `canEditDeviceEvent returns true with calendar name for writable calendar`() = runTest {
        // Given: A writable device calendar
        val calendarId = 1L
        fakeCalendarProviderRepo.calendars = listOf(
            createDeviceCalendar(id = calendarId, name = "Work Calendar", isReadOnly = false)
        )

        // When: Check if can edit
        val result = canEditDeviceEvent(calendarId, fakeCalendarProviderRepo)

        // Then: Returns true with calendar name
        assertTrue(result.first)
        assertEquals("Work Calendar", result.second)
    }

    @Test
    fun `canEditDeviceEvent returns false for read-only calendar`() = runTest {
        // Given: A read-only device calendar
        val calendarId = 1L
        fakeCalendarProviderRepo.calendars = listOf(
            createDeviceCalendar(id = calendarId, name = "Holidays", isReadOnly = true)
        )

        // When: Check if can edit
        val result = canEditDeviceEvent(calendarId, fakeCalendarProviderRepo)

        // Then: Returns false
        assertFalse(result.first)
        assertNull(result.second)
    }

    @Test
    fun `canEditDeviceEvent returns false when calendar not found`() = runTest {
        // Given: Empty calendars list
        fakeCalendarProviderRepo.calendars = emptyList()

        // When: Check if can edit non-existent calendar
        val result = canEditDeviceEvent(999L, fakeCalendarProviderRepo)

        // Then: Returns false
        assertFalse(result.first)
        assertNull(result.second)
    }

    // ==================== Chunk 1: getDeviceEventForEdit Tests ====================

    @Test
    fun `getDeviceEventForEdit returns event with reminders and calendar info`() = runTest {
        // Given: A device event with reminders
        val eventId = 42L
        val calendarId = 1L
        fakeCalendarProviderRepo.calendars = listOf(
            createDeviceCalendar(id = calendarId, name = "Personal", color = 0xFF0000, isReadOnly = false)
        )
        fakeCalendarProviderRepo.deviceEvents[eventId] = createDeviceEvent(
            id = eventId,
            calendarId = calendarId,
            title = "Team Meeting"
        )
        fakeCalendarProviderRepo.eventReminders[eventId] = listOf(15, 60)

        // When: Load event for edit
        val result = getDeviceEventForEdit(eventId, fakeCalendarProviderRepo)

        // Then: Returns event with all data
        assertNotNull(result)
        assertEquals("Team Meeting", result!!.event.title)
        assertEquals(listOf(15, 60), result.reminders)
        assertEquals("Personal", result.calendarName)
        assertEquals(0xFF0000, result.calendarColor)
        assertTrue(result.isWritable)
    }

    @Test
    fun `getDeviceEventForEdit returns null when event not found`() = runTest {
        // Given: No events
        fakeCalendarProviderRepo.deviceEvents.clear()

        // When: Load non-existent event
        val result = getDeviceEventForEdit(999L, fakeCalendarProviderRepo)

        // Then: Returns null
        assertNull(result)
    }

    @Test
    fun `getDeviceEventForEdit returns null when calendar not found`() = runTest {
        // Given: Event exists but calendar doesn't
        val eventId = 42L
        fakeCalendarProviderRepo.deviceEvents[eventId] = createDeviceEvent(
            id = eventId,
            calendarId = 999L, // Non-existent calendar
            title = "Orphan Event"
        )
        fakeCalendarProviderRepo.calendars = emptyList()

        // When: Load event
        val result = getDeviceEventForEdit(eventId, fakeCalendarProviderRepo)

        // Then: Returns null
        assertNull(result)
    }

    @Test
    fun `getDeviceEventForEdit returns empty reminders when none exist`() = runTest {
        // Given: Event with no reminders
        val eventId = 42L
        val calendarId = 1L
        fakeCalendarProviderRepo.calendars = listOf(
            createDeviceCalendar(id = calendarId, name = "Work", isReadOnly = false)
        )
        fakeCalendarProviderRepo.deviceEvents[eventId] = createDeviceEvent(
            id = eventId,
            calendarId = calendarId,
            title = "No Reminders Event"
        )
        // No reminders set

        // When: Load event
        val result = getDeviceEventForEdit(eventId, fakeCalendarProviderRepo)

        // Then: Returns event with empty reminders
        assertNotNull(result)
        assertTrue(result!!.reminders.isEmpty())
    }

    // ==================== Chunk 1: findExceptionEventId Tests ====================

    @Test
    fun `findExceptionEventId returns exception ID when exists`() = runTest {
        // Given: An existing exception event
        val masterEventId = 100L
        val originalInstanceTime = 1700000000000L
        val exceptionEventId = 200L
        fakeCalendarProviderRepo.exceptionEvents[masterEventId to originalInstanceTime] = exceptionEventId

        // When: Find exception
        val result = fakeCalendarProviderRepo.findExceptionEventId(masterEventId, originalInstanceTime)

        // Then: Returns exception ID
        assertEquals(exceptionEventId, result)
    }

    @Test
    fun `findExceptionEventId returns null when no exception`() = runTest {
        // Given: No exception events
        fakeCalendarProviderRepo.exceptionEvents.clear()

        // When: Find exception
        val result = fakeCalendarProviderRepo.findExceptionEventId(100L, 1700000000000L)

        // Then: Returns null
        assertNull(result)
    }

    // ==================== Exception-Aware Loading Tests ====================

    @Test
    fun `getDeviceEventForEdit loads exception event when occurrenceTs matches existing exception`() = runTest {
        val masterEventId = 100L
        val exceptionEventId = 200L
        val occTs = 1700000000000L
        val calendarId = 1L

        fakeCalendarProviderRepo.calendars = listOf(
            createDeviceCalendar(id = calendarId, name = "Work", isReadOnly = false)
        )
        fakeCalendarProviderRepo.exceptionEvents[masterEventId to occTs] = exceptionEventId
        fakeCalendarProviderRepo.deviceEvents[masterEventId] = createDeviceEvent(
            id = masterEventId, calendarId = calendarId, title = "Master Event"
        )
        fakeCalendarProviderRepo.deviceEvents[exceptionEventId] = createDeviceEvent(
            id = exceptionEventId, calendarId = calendarId, title = "Modified Occurrence"
        )
        fakeCalendarProviderRepo.eventReminders[exceptionEventId] = listOf(30)

        val result = getDeviceEventForEditExceptionAware(masterEventId, occTs, false, fakeCalendarProviderRepo)

        assertNotNull(result)
        assertEquals(exceptionEventId, result!!.event.id)
        assertEquals("Modified Occurrence", result.event.title)
        assertEquals(listOf(30), result.reminders)
    }

    @Test
    fun `getDeviceEventForEdit loads master when occurrenceTs is null`() = runTest {
        val masterEventId = 100L
        val calendarId = 1L

        fakeCalendarProviderRepo.calendars = listOf(
            createDeviceCalendar(id = calendarId, name = "Work", isReadOnly = false)
        )
        fakeCalendarProviderRepo.deviceEvents[masterEventId] = createDeviceEvent(
            id = masterEventId, calendarId = calendarId, title = "Master Event"
        )

        val result = getDeviceEventForEditExceptionAware(masterEventId, null, false, fakeCalendarProviderRepo)

        assertNotNull(result)
        assertEquals(masterEventId, result!!.event.id)
        assertEquals("Master Event", result.event.title)
    }

    @Test
    fun `getDeviceEventForEdit loads master when no exception exists for occurrenceTs`() = runTest {
        val masterEventId = 100L
        val occTs = 1700000000000L
        val calendarId = 1L

        fakeCalendarProviderRepo.calendars = listOf(
            createDeviceCalendar(id = calendarId, name = "Work", isReadOnly = false)
        )
        fakeCalendarProviderRepo.deviceEvents[masterEventId] = createDeviceEvent(
            id = masterEventId, calendarId = calendarId, title = "Master Event"
        )
        // No exception events configured

        val result = getDeviceEventForEditExceptionAware(masterEventId, occTs, false, fakeCalendarProviderRepo)

        assertNotNull(result)
        assertEquals(masterEventId, result!!.event.id)
    }

    @Test
    fun `getDeviceEventForEdit loads reminders for exception event not master`() = runTest {
        val masterEventId = 100L
        val exceptionEventId = 200L
        val occTs = 1700000000000L
        val calendarId = 1L

        fakeCalendarProviderRepo.calendars = listOf(
            createDeviceCalendar(id = calendarId, name = "Work", isReadOnly = false)
        )
        fakeCalendarProviderRepo.exceptionEvents[masterEventId to occTs] = exceptionEventId
        fakeCalendarProviderRepo.deviceEvents[masterEventId] = createDeviceEvent(
            id = masterEventId, calendarId = calendarId, title = "Master"
        )
        fakeCalendarProviderRepo.deviceEvents[exceptionEventId] = createDeviceEvent(
            id = exceptionEventId, calendarId = calendarId, title = "Exception"
        )
        // Reminders on exception, NOT on master
        fakeCalendarProviderRepo.eventReminders[exceptionEventId] = listOf(10, 30)
        fakeCalendarProviderRepo.eventReminders[masterEventId] = listOf(15)

        val result = getDeviceEventForEditExceptionAware(masterEventId, occTs, false, fakeCalendarProviderRepo)

        assertNotNull(result)
        assertEquals(listOf(10, 30), result!!.reminders) // exception's reminders, not master's
    }

    @Test
    fun `getDeviceEventForEdit returns null when exception event not in Events table`() = runTest {
        val masterEventId = 100L
        val exceptionEventId = 200L
        val occTs = 1700000000000L
        val calendarId = 1L

        fakeCalendarProviderRepo.calendars = listOf(
            createDeviceCalendar(id = calendarId, name = "Work", isReadOnly = false)
        )
        // findExceptionEventId returns 200, but no DeviceEvent for 200 in deviceEvents
        fakeCalendarProviderRepo.exceptionEvents[masterEventId to occTs] = exceptionEventId
        fakeCalendarProviderRepo.deviceEvents[masterEventId] = createDeviceEvent(
            id = masterEventId, calendarId = calendarId, title = "Master"
        )
        // Deliberately NOT adding deviceEvents[200L]

        val result = getDeviceEventForEditExceptionAware(masterEventId, occTs, false, fakeCalendarProviderRepo)

        assertNull(result)
    }

    // ==================== deleteDeviceThisAndFuture Tests ====================

    @Test
    fun `deleteDeviceThisAndFuture calls repository with correct params`() = runTest {
        val masterEventId = 100L
        val fromTimeMs = 1700000000000L

        val result = deleteDeviceThisAndFuture(
            masterEventId = masterEventId,
            fromTimeMs = fromTimeMs,
            isAllDay = false,
            repo = fakeCalendarProviderRepo
        )

        assertTrue(result.isSuccess)
        assertEquals(1, fakeCalendarProviderRepo.deletedFutureOccurrences.size)
        val deleted = fakeCalendarProviderRepo.deletedFutureOccurrences[0]
        assertEquals(masterEventId, deleted.masterEventId)
        assertEquals(fromTimeMs, deleted.fromTimeMs)
        assertFalse(deleted.isAllDay)
    }

    @Test
    fun `deleteDeviceThisAndFuture passes isAllDay correctly`() = runTest {
        val result = deleteDeviceThisAndFuture(
            masterEventId = 100L,
            fromTimeMs = 1700000000000L,
            isAllDay = true,
            repo = fakeCalendarProviderRepo
        )

        assertTrue(result.isSuccess)
        assertTrue(fakeCalendarProviderRepo.deletedFutureOccurrences[0].isAllDay)
    }

    @Test
    fun `deleteDeviceThisAndFuture returns failure on write error`() = runTest {
        fakeCalendarProviderRepo.writeFailure =
            org.onekash.kashcal.error.CalendarError.DeviceCalendar.WriteFailed("Test error")

        val result = deleteDeviceThisAndFuture(
            masterEventId = 100L,
            fromTimeMs = 1700000000000L,
            isAllDay = false,
            repo = fakeCalendarProviderRepo
        )

        assertTrue(result.isFailure)
        assertTrue(fakeCalendarProviderRepo.deletedFutureOccurrences.isEmpty())
    }

    // ==================== Helper Functions ====================

    /**
     * Simulate canEditDeviceEvent logic (will be moved to HomeViewModel).
     */
    private suspend fun canEditDeviceEvent(
        calendarId: Long,
        repo: FakeCalendarProviderRepository
    ): Pair<Boolean, String?> {
        val calendars = repo.getDeviceCalendars()
        val calendar = calendars.find { it.id == calendarId }
        return if (calendar != null && calendar.isWritable) {
            true to calendar.displayName
        } else {
            false to null
        }
    }

    /**
     * Simulate getDeviceEventForEdit logic (will be moved to HomeViewModel).
     */
    private suspend fun getDeviceEventForEdit(
        eventId: Long,
        repo: FakeCalendarProviderRepository
    ): DeviceEventEditData? {
        val event = repo.getDeviceEvent(eventId) ?: return null
        val calendars = repo.getDeviceCalendars()
        val calendar = calendars.find { it.id == event.calendarId } ?: return null
        val reminders = repo.getReminders(eventId)

        return DeviceEventEditData(
            event = event,
            reminders = reminders,
            calendarName = calendar.displayName,
            calendarColor = calendar.color,
            isWritable = calendar.isWritable
        )
    }

    /**
     * Simulate getDeviceEventForEdit with exception-aware loading.
     * Mirrors the updated HomeViewModel.getDeviceEventForEdit logic.
     */
    private suspend fun getDeviceEventForEditExceptionAware(
        eventId: Long,
        occurrenceTs: Long?,
        isAllDay: Boolean,
        repo: FakeCalendarProviderRepository
    ): DeviceEventEditData? {
        val effectiveEventId = if (occurrenceTs != null) {
            repo.findExceptionEventId(eventId, occurrenceTs, isAllDay) ?: eventId
        } else eventId
        val event = repo.getDeviceEvent(effectiveEventId) ?: return null
        val calendars = repo.getDeviceCalendars()
        val calendar = calendars.find { it.id == event.calendarId } ?: return null
        val reminders = repo.getReminders(effectiveEventId)

        return DeviceEventEditData(
            event = event,
            reminders = reminders,
            calendarName = calendar.displayName,
            calendarColor = calendar.color,
            isWritable = calendar.isWritable
        )
    }

    /**
     * Simulate deleteDeviceThisAndFuture logic (mirrors HomeViewModel).
     */
    private suspend fun deleteDeviceThisAndFuture(
        masterEventId: Long,
        fromTimeMs: Long,
        isAllDay: Boolean,
        repo: FakeCalendarProviderRepository
    ): Result<Unit> {
        return repo.deleteThisAndFuture(
            masterEventId = masterEventId,
            fromTimeMs = fromTimeMs,
            isAllDay = isAllDay
        )
    }

    private fun createDeviceCalendar(
        id: Long,
        name: String,
        color: Int = 0x0000FF,
        isReadOnly: Boolean = false
    ): DeviceCalendar = DeviceCalendar(
        id = id,
        accountName = "test@example.com",
        accountType = "com.google",
        displayName = name,
        color = color,
        visible = true,
        accessLevel = if (isReadOnly) 200 else 700  // READ=200, OWNER=700
    )

    private fun createDeviceEvent(
        id: Long,
        calendarId: Long,
        title: String
    ): DeviceEvent = DeviceEvent(
        id = id,
        calendarId = calendarId,
        title = title,
        description = null,
        location = null,
        startTs = System.currentTimeMillis(),
        endTs = System.currentTimeMillis() + 3600000,
        duration = null,
        isAllDay = false,
        rrule = null,
        rdate = null,
        exdate = null,
        exrule = null,
        timezone = "America/New_York",
        originalId = null,
        originalInstanceTime = null,
        status = 1,
        availability = 0,
        accessLevel = 700,
        calendarColor = null,
        eventColor = null
    )
}
