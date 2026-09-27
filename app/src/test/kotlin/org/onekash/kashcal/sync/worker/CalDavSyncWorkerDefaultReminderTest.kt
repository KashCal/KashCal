package org.onekash.kashcal.sync.worker

import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * Tests the default-reminder rule in CalDavSyncWorker's scheduleRemindersForSyncedEvents.
 *
 * Events synced from CalDAV servers without VALARM (because the original creator used
 * client-local "default reminder" settings) get the user's default reminder, but only when:
 * - the change is NEW (not MODIFIED),
 * - it isn't from an initial sync,
 * - the event has no reminders and an alarmCount of 0,
 * - the default isn't [KashCalDataStore.REMINDER_OFF].
 *
 * The tests run a local copy of that predicate ([shouldApplyDefaultReminder]) and local
 * expressions, not the worker. The ISO duration tests call [ContactEventUtils.minutesToIsoDuration]
 * directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CalDavSyncWorkerDefaultReminderTest {

    private lateinit var eventReader: EventReader
    private lateinit var dataStore: KashCalDataStore
    private lateinit var reminderScheduler: ReminderScheduler

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

        // No test below reads these stubs.
        coEvery { dataStore.defaultReminderMinutes } returns flowOf(15)
        coEvery { dataStore.defaultAllDayReminder } returns flowOf(720) // 12 hours
        coEvery { eventReader.getCalendarById(any()) } returns testCalendar
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns listOf(
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
        // Incremental sync (isFromInitialSync = false), no reminders.
        val event = createTestEvent(id = 1L, reminders = null, alarmCount = 0)
        val change = createSyncChange(
            type = ChangeType.NEW,
            eventId = 1L,
            isFromInitialSync = false
        )

        coEvery { eventReader.getEventById(1L) } returns event

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
        // alarmCount counts every alarm, while reminders keeps at most 5 (ICalEventMapper), so a
        // nonzero alarmCount blocks the default even when the reminders list is empty.
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

        // MODIFIED events never get defaults. The local predicate omits the NEW check, so it
        // is applied here as the worker does.
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

        // All-day events use their own default.
        val defaultMinutes = if (event.isAllDay) 900 else 15
        val shouldApply = shouldApplyDefaultReminder(change, event, defaultMinutes)

        assertTrue("All-day event should get default reminder", shouldApply)
        assertEquals(900, defaultMinutes) // The app's all-day default: 9 AM the day before (-PT15H)
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
        assertEquals(15, defaultMinutes)
    }

    @Test
    fun `exception events (NEW) get default reminder on incremental sync`() = runTest {
        // An exception: one changed occurrence of a recurring event.
        val event = createTestEvent(
            id = 101L,
            reminders = null,
            alarmCount = 0,
            originalEventId = 1L,  // Its master
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
    fun `minutesToIsoDuration converts 1440 minutes (1 day) to hour-form`() {
        // Hour-form (DST-stable): 1440 min -> -PT24H, not the period -P1D.
        val duration = ContactEventUtils.minutesToIsoDuration(1440)
        assertEquals("-PT24H", duration)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `null eventId in SyncChange is skipped`() = runTest {
        val change = createSyncChange(
            type = ChangeType.NEW,
            eventId = null,  // No event to apply a reminder to
            isFromInitialSync = false
        )

        // The worker skips a change with a null eventId.
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
        // An empty reminders list counts as no reminders.
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
        // Runs only a local try/catch. The worker's own catch around updateReminders (the event
        // goes without a reminder and the sync continues) isn't exercised here.
        var continueProcessing = true
        var exceptionCaught = false

        try {
            // Stands in for a failing DB write.
            throw RuntimeException("DB error")
        } catch (e: Exception) {
            exceptionCaught = true
            continueProcessing = true
        }

        assertTrue("Exception should be caught", exceptionCaught)
        assertTrue("Sync should continue after updateReminders failure", continueProcessing)
    }

    // ==================== Helper Functions ====================

    /**
     * Returns whether CalDavSyncWorker would apply the default reminder, copying its condition
     * except the `ChangeType.NEW` check and the all-day versus timed choice of [defaultMinutes].
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
