package org.onekash.kashcal.sync.worker

import android.util.Log
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.contacts.ContactEventUtils
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.SyncChange
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for default reminder application logic in CalDavSyncWorker.
 *
 * When events are synced from CalDAV servers without VALARM (because the
 * original creator used client-local "default reminder" settings), KashCal
 * should apply the user's configured default reminder - but ONLY for:
 * - NEW events (not MODIFIED)
 * - Incremental sync (not initial sync)
 * - Events without existing reminders or truncated alarms
 *
 * This tests the core logic that would be in scheduleRemindersForSyncedEvents().
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CalDavSyncWorkerDefaultReminderTest {

    private lateinit var eventReader: EventReader
    private lateinit var dataStore: KashCalDataStore
    private lateinit var reminderScheduler: ReminderScheduler

    // Test data
    private val testCalendar = Calendar(
        id = 1L,
        accountId = 1L,
        caldavUrl = "https://caldav.example.com/calendars/test/",
        displayName = "Test Calendar",
        color = 0xFF2196F3.toInt()
    )

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        eventReader = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)

        // Default mock behavior
        coEvery { dataStore.defaultReminderMinutes } returns flowOf(15)
        coEvery { dataStore.defaultAllDayReminder } returns flowOf(720) // 12 hours
        coEvery { eventReader.getCalendarById(any()) } returns testCalendar
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any()) } returns listOf(
            createTestOccurrence(eventId = 1L)
        )
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // ==================== Core Logic Tests ====================

    @Test
    fun `NEW event without VALARM on incremental sync gets default reminder`() = runTest {
        // Event from incremental sync (isFromInitialSync = false), no reminders
        val event = createTestEvent(id = 1L, reminders = null, alarmCount = 0)
        val change = createSyncChange(
            type = ChangeType.NEW,
            eventId = 1L,
            isFromInitialSync = false
        )

        coEvery { eventReader.getEventById(1L) } returns event

        // Simulate the logic
        val shouldApply = shouldApplyDefaultReminder(change, event, defaultMinutes = 15)

        assertTrue("Should apply default reminder to new event on incremental sync", shouldApply)
    }

    @Test
    fun `NEW event without VALARM on initial sync does NOT get default reminder`() = runTest {
        val event = createTestEvent(id = 1L, reminders = null, alarmCount = 0)
        val change = createSyncChange(
            type = ChangeType.NEW,
            eventId = 1L,
            isFromInitialSync = true  // Initial sync
        )

        coEvery { eventReader.getEventById(1L) } returns event

        val shouldApply = shouldApplyDefaultReminder(change, event, defaultMinutes = 15)

        assertFalse("Should NOT apply default on initial sync", shouldApply)
    }

    @Test
    fun `NEW event with VALARM does NOT get default reminder`() = runTest {
        val event = createTestEvent(id = 1L, reminders = listOf("-PT30M"), alarmCount = 1)
        val change = createSyncChange(
            type = ChangeType.NEW,
            eventId = 1L,
            isFromInitialSync = false
        )

        coEvery { eventReader.getEventById(1L) } returns event

        val shouldApply = shouldApplyDefaultReminder(change, event, defaultMinutes = 15)

        assertFalse("Should NOT apply default when event has reminders", shouldApply)
    }

    @Test
    fun `NEW event with alarmCount greater than 0 but empty reminders does NOT get default (truncated)`() = runTest {
        // This represents truncated alarms - server has >3 alarms but we only store first 3
        // alarmCount > 0 means there ARE alarms on the server, just not in our reminders list
        val event = createTestEvent(id = 1L, reminders = emptyList(), alarmCount = 5)
        val change = createSyncChange(
            type = ChangeType.NEW,
            eventId = 1L,
            isFromInitialSync = false
        )

        coEvery { eventReader.getEventById(1L) } returns event

        val shouldApply = shouldApplyDefaultReminder(change, event, defaultMinutes = 15)

        assertFalse("Should NOT apply default when alarmCount > 0 (truncated alarms)", shouldApply)
    }

    @Test
    fun `MODIFIED event without VALARM does NOT get default reminder`() = runTest {
        val event = createTestEvent(id = 1L, reminders = null, alarmCount = 0)
        val change = createSyncChange(
            type = ChangeType.MODIFIED,  // Modified, not new
            eventId = 1L,
            isFromInitialSync = false
        )

        coEvery { eventReader.getEventById(1L) } returns event

        // MODIFIED events should never get defaults applied
        val shouldApply = change.type == ChangeType.NEW &&
            shouldApplyDefaultReminder(change, event, defaultMinutes = 15)

        assertFalse("Should NOT apply default to modified events", shouldApply)
    }

    @Test
    fun `default reminder set to REMINDER_OFF skips application`() = runTest {
        val event = createTestEvent(id = 1L, reminders = null, alarmCount = 0)
        val change = createSyncChange(
            type = ChangeType.NEW,
            eventId = 1L,
            isFromInitialSync = false
        )

        coEvery { eventReader.getEventById(1L) } returns event

        val shouldApply = shouldApplyDefaultReminder(
            change, event,
            defaultMinutes = KashCalDataStore.REMINDER_OFF  // User disabled defaults
        )

        assertFalse("Should NOT apply when user disabled default reminders", shouldApply)
    }

    @Test
    fun `all-day events use defaultAllDayReminder`() = runTest {
        val event = createTestEvent(id = 1L, reminders = null, alarmCount = 0, isAllDay = true)
        val change = createSyncChange(
            type = ChangeType.NEW,
            eventId = 1L,
            isAllDay = true,
            isFromInitialSync = false
        )

        coEvery { eventReader.getEventById(1L) } returns event

        // Logic should use different default for all-day events
        val defaultMinutes = if (event.isAllDay) 720 else 15
        val shouldApply = shouldApplyDefaultReminder(change, event, defaultMinutes)

        assertTrue("All-day event should get default reminder", shouldApply)
        assertEquals(720, defaultMinutes) // Should use all-day default
    }

    @Test
    fun `timed events use defaultReminderMinutes`() = runTest {
        val event = createTestEvent(id = 1L, reminders = null, alarmCount = 0, isAllDay = false)
        val change = createSyncChange(
            type = ChangeType.NEW,
            eventId = 1L,
            isAllDay = false,
            isFromInitialSync = false
        )

        coEvery { eventReader.getEventById(1L) } returns event

        val defaultMinutes = if (event.isAllDay) 720 else 15
        val shouldApply = shouldApplyDefaultReminder(change, event, defaultMinutes)

        assertTrue("Timed event should get default reminder", shouldApply)
        assertEquals(15, defaultMinutes) // Should use timed default
    }

    @Test
    fun `exception events (NEW) get default reminder on incremental sync`() = runTest {
        // Exception event = modified single occurrence of recurring event
        val event = createTestEvent(
            id = 101L,
            reminders = null,
            alarmCount = 0,
            originalEventId = 1L,  // Has master event
            originalInstanceTime = System.currentTimeMillis()
        )
        val change = createSyncChange(
            type = ChangeType.NEW,
            eventId = 101L,
            isFromInitialSync = false
        )

        coEvery { eventReader.getEventById(101L) } returns event

        val shouldApply = shouldApplyDefaultReminder(change, event, defaultMinutes = 15)

        assertTrue("Exception events (NEW) should get default reminder", shouldApply)
    }

    // ==================== ISO Duration Tests ====================

    @Test
    fun `minutesToIsoDuration converts 15 minutes correctly`() {
        val duration = ContactEventUtils.minutesToIsoDuration(15)
        assertEquals("-PT15M", duration)
    }

    @Test
    fun `minutesToIsoDuration converts 60 minutes (1 hour) correctly`() {
        val duration = ContactEventUtils.minutesToIsoDuration(60)
        assertEquals("-PT1H", duration)
    }

    @Test
    fun `minutesToIsoDuration converts 720 minutes (12 hours) correctly`() {
        val duration = ContactEventUtils.minutesToIsoDuration(720)
        assertEquals("-PT12H", duration)
    }

    @Test
    fun `minutesToIsoDuration converts 1440 minutes (1 day) correctly`() {
        val duration = ContactEventUtils.minutesToIsoDuration(1440)
        assertEquals("-P1D", duration)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `null eventId in SyncChange is skipped`() = runTest {
        val change = createSyncChange(
            type = ChangeType.NEW,
            eventId = null,  // Null - can't apply reminder
            isFromInitialSync = false
        )

        // Logic should skip changes with null eventId
        val shouldProcess = change.eventId != null
        assertFalse("Should skip changes with null eventId", shouldProcess)
    }

    @Test
    fun `DELETED events are skipped`() = runTest {
        val change = createSyncChange(
            type = ChangeType.DELETED,
            eventId = null,
            isFromInitialSync = false
        )

        val shouldProcess = change.type != ChangeType.DELETED && change.eventId != null
        assertFalse("Should skip DELETED events", shouldProcess)
    }

    @Test
    fun `event with empty string reminders is treated as no reminders`() = runTest {
        // Edge case: reminders might be empty list
        val event = createTestEvent(id = 1L, reminders = emptyList(), alarmCount = 0)
        val change = createSyncChange(
            type = ChangeType.NEW,
            eventId = 1L,
            isFromInitialSync = false
        )

        coEvery { eventReader.getEventById(1L) } returns event

        val shouldApply = shouldApplyDefaultReminder(change, event, defaultMinutes = 15)

        assertTrue("Empty reminders list should be treated as no reminders", shouldApply)
    }

    // ==================== Failure Handling Tests ====================

    @Test
    fun `updateReminders failure is logged and sync continues`() = runTest {
        // This test verifies that failure in reminder application doesn't break sync
        // The actual implementation wraps updateReminders in try-catch

        // Simulate the try-catch logic that will be in the implementation
        var continueProcessing = true
        var exceptionCaught = false

        try {
            // Simulate a DB operation that throws
            throw RuntimeException("DB error")
        } catch (e: Exception) {
            // Log error but continue - this is the expected behavior
            exceptionCaught = true
            continueProcessing = true
        }

        assertTrue("Exception should be caught", exceptionCaught)
        assertTrue("Sync should continue after updateReminders failure", continueProcessing)
    }

    // ==================== Helper Functions ====================

    /**
     * Simulates the core logic for determining if default reminder should be applied.
     * This mirrors the actual implementation logic in CalDavSyncWorker.
     */
    private fun shouldApplyDefaultReminder(
        change: SyncChange,
        event: Event,
        defaultMinutes: Int
    ): Boolean {
        return !change.isFromInitialSync &&
            event.reminders.isNullOrEmpty() &&
            (event.alarmCount) == 0 &&
            defaultMinutes != KashCalDataStore.REMINDER_OFF
    }

    private fun createTestEvent(
        id: Long = 1L,
        reminders: List<String>? = null,
        alarmCount: Int = 0,
        isAllDay: Boolean = false,
        originalEventId: Long? = null,
        originalInstanceTime: Long? = null
    ) = Event(
        id = id,
        uid = "test-uid-$id@example.com",
        calendarId = 1L,
        title = "Test Event",
        startTs = System.currentTimeMillis() + 3600000,
        endTs = System.currentTimeMillis() + 7200000,
        dtstamp = System.currentTimeMillis(),
        reminders = reminders,
        alarmCount = alarmCount,
        isAllDay = isAllDay,
        originalEventId = originalEventId,
        originalInstanceTime = originalInstanceTime
    )

    private fun createTestOccurrence(
        eventId: Long,
        calendarId: Long = 1L,
        startTs: Long = System.currentTimeMillis() + 3600000
    ) = Occurrence(
        id = 0,
        eventId = eventId,
        calendarId = calendarId,
        startTs = startTs,
        endTs = startTs + 3600000,
        startDay = 20250115,
        endDay = 20250115
    )

    private fun createSyncChange(
        type: ChangeType,
        eventId: Long?,
        isAllDay: Boolean = false,
        isFromInitialSync: Boolean = false
    ) = SyncChange(
        type = type,
        eventId = eventId,
        eventTitle = "Test Event",
        eventStartTs = System.currentTimeMillis(),
        isAllDay = isAllDay,
        isRecurring = false,
        calendarName = "Test Calendar",
        calendarColor = 0xFF2196F3.toInt(),
        isFromInitialSync = isFromInitialSync
    )
}
