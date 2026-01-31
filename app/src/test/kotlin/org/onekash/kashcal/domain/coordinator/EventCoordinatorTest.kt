package org.onekash.kashcal.domain.coordinator

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.contacts.ContactBirthdayRepository
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.ics.IcsSubscriptionRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.scheduler.SyncScheduler

/**
 * Comprehensive unit tests for EventCoordinator.
 *
 * Tests cover:
 * - Initialization (local calendar)
 * - Event CRUD operations
 * - Recurring event operations (edit single, edit future, delete single, delete future)
 * - Immediate sync triggers
 * - Reminder scheduling
 * - Occurrence generation delegation
 * - ICS subscription operations
 */
class EventCoordinatorTest {

    // Mocks
    private lateinit var eventWriter: EventWriter
    private lateinit var eventReader: EventReader
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var localCalendarInitializer: LocalCalendarInitializer
    private lateinit var icsSubscriptionRepository: IcsSubscriptionRepository
    private lateinit var contactBirthdayRepository: ContactBirthdayRepository
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var widgetUpdateManager: org.onekash.kashcal.widget.WidgetUpdateManager

    // System under test
    private lateinit var coordinator: EventCoordinator

    // Test data
    private val localCalendarId = 1L
    private val iCloudCalendarId = 2L
    private val localCalendar = Calendar(
        id = localCalendarId,
        accountId = 1L,
        caldavUrl = "local://calendar/1", // Local calendars use local:// scheme
        displayName = "Local",
        color = 0xFF4CAF50.toInt()
    )
    private val iCloudCalendar = Calendar(
        id = iCloudCalendarId,
        accountId = 2L,
        caldavUrl = "https://caldav.icloud.com/123/calendars/personal/",
        displayName = "Personal",
        color = 0xFF2196F3.toInt()
    )
    private val readOnlyCalendarId = 3L
    private val readOnlyCalendar = Calendar(
        id = readOnlyCalendarId,
        accountId = 3L,
        caldavUrl = "https://example.com/ics/holidays.ics",
        displayName = "Holidays (Read-Only)",
        color = 0xFFFF5722.toInt(),
        isReadOnly = true  // ICS subscription calendars are read-only
    )
    private val testEvent = Event(
        id = 100L,
        uid = "test-event@kashcal.test",
        calendarId = localCalendarId,
        title = "Test Event",
        startTs = 1704067200000L, // Jan 1, 2024 00:00 UTC
        endTs = 1704070800000L,   // Jan 1, 2024 01:00 UTC
        dtstamp = System.currentTimeMillis(),
        syncStatus = SyncStatus.SYNCED
    )
    private val recurringEvent = Event(
        id = 101L,
        uid = "recurring@kashcal.test",
        calendarId = iCloudCalendarId,
        title = "Weekly Meeting",
        startTs = 1704067200000L,
        endTs = 1704070800000L,
        dtstamp = System.currentTimeMillis(),
        rrule = "FREQ=WEEKLY;BYDAY=MO",
        syncStatus = SyncStatus.SYNCED
    )

    @Before
    fun setup() {
        eventWriter = mockk(relaxed = true)
        eventReader = mockk(relaxed = true)
        occurrenceGenerator = mockk(relaxed = true)
        localCalendarInitializer = mockk(relaxed = true)
        icsSubscriptionRepository = mockk(relaxed = true)
        contactBirthdayRepository = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)
        widgetUpdateManager = mockk(relaxed = true)

        // Default local calendar setup
        coEvery { localCalendarInitializer.ensureLocalCalendarExists() } returns localCalendarId
        coEvery { localCalendarInitializer.getLocalCalendarId() } returns localCalendarId
        every { localCalendarInitializer.isLocalCalendar(localCalendar) } returns true
        every { localCalendarInitializer.isLocalCalendar(iCloudCalendar) } returns false

        // Default calendar lookups
        coEvery { eventReader.getCalendarById(localCalendarId) } returns localCalendar
        coEvery { eventReader.getCalendarById(iCloudCalendarId) } returns iCloudCalendar
        coEvery { eventReader.getCalendarById(readOnlyCalendarId) } returns readOnlyCalendar

        coordinator = EventCoordinator(
            eventWriter = eventWriter,
            eventReader = eventReader,
            occurrenceGenerator = occurrenceGenerator,
            localCalendarInitializer = localCalendarInitializer,
            icsSubscriptionRepository = icsSubscriptionRepository,
            contactBirthdayRepository = contactBirthdayRepository,
            syncScheduler = syncScheduler,
            reminderScheduler = reminderScheduler,
            widgetUpdateManager = widgetUpdateManager
        )
    }

    // ==================== Initialization Tests ====================

    @Test
    fun `ensureLocalCalendarExists delegates to initializer`() = runTest {
        val result = coordinator.ensureLocalCalendarExists()

        assertEquals(localCalendarId, result)
        coVerify { localCalendarInitializer.ensureLocalCalendarExists() }
    }

    @Test
    fun `getLocalCalendarId returns local calendar ID`() = runTest {
        val result = coordinator.getLocalCalendarId()

        assertEquals(localCalendarId, result)
    }

    @Test
    fun `isLocalCalendar correctly identifies local calendar`() {
        assertTrue(coordinator.isLocalCalendar(localCalendar))
        assertFalse(coordinator.isLocalCalendar(iCloudCalendar))
    }

    // ==================== Create Event Tests ====================

    @Test
    fun `createEvent creates event in specified calendar`() = runTest {
        val newEvent = testEvent.copy(id = 0L)
        val createdEvent = testEvent.copy()
        coEvery { eventWriter.createEvent(any(), any()) } returns createdEvent
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any()) } returns emptyList()

        val result = coordinator.createEvent(newEvent, localCalendarId)

        assertEquals(createdEvent, result)
        coVerify { eventWriter.createEvent(match { it.calendarId == localCalendarId }, true) }
    }

    @Test
    fun `createEvent uses local calendar when no calendar specified`() = runTest {
        val newEvent = testEvent.copy(id = 0L, calendarId = 999L) // Wrong calendar
        val createdEvent = testEvent.copy()
        coEvery { eventWriter.createEvent(any(), any()) } returns createdEvent
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any()) } returns emptyList()

        coordinator.createEvent(newEvent, null) // No calendar specified

        coVerify { eventWriter.createEvent(match { it.calendarId == localCalendarId }, any()) }
    }

    @Test
    fun `createEvent in local calendar does not trigger sync`() = runTest {
        val newEvent = testEvent.copy(id = 0L)
        coEvery { eventWriter.createEvent(any(), any()) } returns testEvent
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any()) } returns emptyList()

        coordinator.createEvent(newEvent, localCalendarId)

        verify(exactly = 0) { syncScheduler.requestExpeditedSync(any()) }
    }

    @Test
    fun `createEvent in iCloud calendar triggers sync`() = runTest {
        val newEvent = testEvent.copy(id = 0L, calendarId = iCloudCalendarId)
        coEvery { eventWriter.createEvent(any(), any()) } returns newEvent.copy(id = 100L)
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any()) } returns emptyList()

        coordinator.createEvent(newEvent, iCloudCalendarId)

        verify { syncScheduler.requestExpeditedSync(forceFullSync = false) }
    }

    @Test
    fun `createEvent schedules reminders when event has reminders`() = runTest {
        val eventWithReminders = testEvent.copy(reminders = listOf("-PT15M", "-PT1H"))
        coEvery { eventWriter.createEvent(any(), any()) } returns eventWithReminders
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any()) } returns listOf(
            Occurrence(
                id = 1L,
                eventId = eventWithReminders.id,
                calendarId = localCalendarId,
                startTs = eventWithReminders.startTs,
                endTs = eventWithReminders.endTs,
                startDay = 20240101,
                endDay = 20240101
            )
        )

        coordinator.createEvent(eventWithReminders, localCalendarId)

        coVerify { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) }
    }

    @Test
    fun `createEvent throws for read-only calendar`() = runTest {
        // Attempt to create event on a read-only calendar (e.g., ICS subscription)
        val newEvent = testEvent.copy(id = 0L, calendarId = readOnlyCalendarId)

        try {
            coordinator.createEvent(newEvent, readOnlyCalendarId)
            assertTrue("Should throw IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("read-only"))
        }

        // Verify eventWriter.createEvent was NOT called
        coVerify(exactly = 0) { eventWriter.createEvent(any(), any()) }
    }

    // ==================== Create Recurring Event Tests ====================

    @Test
    fun `createRecurringEvent requires RRULE`() = runTest {
        val eventWithoutRrule = testEvent.copy(rrule = null)

        try {
            coordinator.createRecurringEvent(eventWithoutRrule)
            assertTrue("Should have thrown exception", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("RRULE"))
        }
    }

    @Test
    fun `createRecurringEvent delegates to createEvent`() = runTest {
        coEvery { eventWriter.createEvent(any(), any()) } returns recurringEvent
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any()) } returns emptyList()

        val result = coordinator.createRecurringEvent(recurringEvent.copy(id = 0L))

        assertEquals(recurringEvent, result)
    }

    // ==================== Update Event Tests ====================

    @Test
    fun `updateEvent updates and triggers sync for iCloud`() = runTest {
        val updatedEvent = testEvent.copy(calendarId = iCloudCalendarId, title = "Updated")
        coEvery { eventWriter.updateEvent(any(), any()) } returns updatedEvent
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any()) } returns emptyList()

        val result = coordinator.updateEvent(updatedEvent)

        assertEquals("Updated", result.title)
        verify { syncScheduler.requestExpeditedSync(forceFullSync = false) }
    }

    @Test
    fun `updateEvent reschedules reminders`() = runTest {
        val updatedEvent = testEvent.copy(reminders = listOf("-PT30M"))
        coEvery { eventWriter.updateEvent(any(), any()) } returns updatedEvent
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any()) } returns emptyList()

        coordinator.updateEvent(updatedEvent)

        coVerify { reminderScheduler.cancelRemindersForEvent(updatedEvent.id) }
        coVerify { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) }
    }

    // ==================== Edit Single Occurrence Tests ====================

    @Test
    fun `editSingleOccurrence creates exception event`() = runTest {
        val occurrenceTime = 1704672000000L // Next Monday
        val exceptionEvent = recurringEvent.copy(
            id = 200L,
            title = "Modified Meeting",
            originalEventId = recurringEvent.id,
            originalInstanceTime = occurrenceTime
        )
        coEvery { eventReader.getEventById(recurringEvent.id) } returns recurringEvent
        coEvery { eventWriter.editSingleOccurrence(any(), any(), any(), any()) } returns exceptionEvent
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any()) } returns emptyList()

        val result = coordinator.editSingleOccurrence(
            masterEventId = recurringEvent.id,
            occurrenceTimeMs = occurrenceTime,
            changes = { it.copy(title = "Modified Meeting") }
        )

        assertEquals("Modified Meeting", result.title)
        coVerify { eventWriter.editSingleOccurrence(recurringEvent.id, occurrenceTime, any(), false) }
    }

    @Test
    fun `editSingleOccurrence throws for non-existent event`() = runTest {
        coEvery { eventReader.getEventById(999L) } returns null

        try {
            coordinator.editSingleOccurrence(999L, 0L) { it }
            assertTrue("Should throw", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not found"))
        }
    }

    @Test
    fun `editSingleOccurrence cancels original occurrence reminders before scheduling new`() = runTest {
        // Bug 3 fix: Editing an occurrence should cancel reminders for the original
        // occurrence time BEFORE scheduling new reminders for the exception event
        val occurrenceTime = 1704672000000L // Original occurrence time
        val exceptionEventResult = recurringEvent.copy(
            id = 200L,
            title = "Modified Meeting",
            originalEventId = recurringEvent.id,
            originalInstanceTime = occurrenceTime,
            reminders = listOf("-PT15M")
        )
        val testOccurrence = Occurrence(
            eventId = exceptionEventResult.id,
            calendarId = iCloudCalendarId,
            startTs = exceptionEventResult.startTs,
            endTs = exceptionEventResult.endTs,
            startDay = 20240108,
            endDay = 20240108
        )
        coEvery { eventReader.getEventById(recurringEvent.id) } returns recurringEvent
        coEvery { eventWriter.editSingleOccurrence(any(), any(), any(), any()) } returns exceptionEventResult
        coEvery { eventReader.getOccurrenceByExceptionEventId(exceptionEventResult.id) } returns testOccurrence

        // Act
        coordinator.editSingleOccurrence(
            masterEventId = recurringEvent.id,
            occurrenceTimeMs = occurrenceTime,
            changes = { it.copy(title = "Modified Meeting") }
        )

        // Assert: Cancel is called for the ORIGINAL occurrence (master event ID + occurrence time)
        coVerify { reminderScheduler.cancelReminderForOccurrence(recurringEvent.id, occurrenceTime) }

        // Assert: Schedule is called for the new exception event
        coVerify { reminderScheduler.scheduleRemindersForEvent(exceptionEventResult, any(), any()) }

        // Verify order: cancel should be called before schedule
        // MockK verifyOrder ensures methods are called in the specified order
        io.mockk.coVerifyOrder {
            reminderScheduler.cancelReminderForOccurrence(recurringEvent.id, occurrenceTime)
            reminderScheduler.scheduleRemindersForEvent(exceptionEventResult, any(), any())
        }
    }

    // ==================== Edit This And Future Tests ====================

    @Test
    fun `editThisAndFuture splits series`() = runTest {
        val splitTime = 1704672000000L
        val newSeries = recurringEvent.copy(
            id = 201L,
            startTs = splitTime,
            uid = "split-series@kashcal.test"
        )
        coEvery { eventReader.getEventById(recurringEvent.id) } returns recurringEvent
        coEvery { eventWriter.splitSeries(any(), any(), any(), any()) } returns newSeries

        val result = coordinator.editThisAndFuture(
            masterEventId = recurringEvent.id,
            splitTimeMs = splitTime,
            changes = { it.copy(title = "New Title") }
        )

        assertNotNull(result)
        coVerify { eventWriter.splitSeries(recurringEvent.id, splitTime, any(), false) }
        verify { syncScheduler.requestExpeditedSync(forceFullSync = false) }
    }

    // ==================== Delete Event Tests ====================

    @Test
    fun `deleteEvent deletes and triggers sync`() = runTest {
        coEvery { eventReader.getEventById(testEvent.id) } returns testEvent.copy(calendarId = iCloudCalendarId)

        coordinator.deleteEvent(testEvent.id)

        coVerify { reminderScheduler.cancelRemindersForEvent(testEvent.id) }
        coVerify { eventWriter.deleteEvent(testEvent.id, false) }
        verify { syncScheduler.requestExpeditedSync(forceFullSync = false) }
    }

    @Test
    fun `deleteEvent throws for non-existent event`() = runTest {
        coEvery { eventReader.getEventById(999L) } returns null

        try {
            coordinator.deleteEvent(999L)
            assertTrue("Should throw", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not found"))
        }
    }

    // ==================== Delete Single Occurrence Tests ====================

    @Test
    fun `deleteSingleOccurrence adds EXDATE`() = runTest {
        val occurrenceTime = 1704672000000L
        coEvery { eventReader.getEventById(recurringEvent.id) } returns recurringEvent

        coordinator.deleteSingleOccurrence(recurringEvent.id, occurrenceTime)

        coVerify { reminderScheduler.cancelReminderForOccurrence(recurringEvent.id, occurrenceTime) }
        coVerify { eventWriter.deleteSingleOccurrence(recurringEvent.id, occurrenceTime, false) }
    }

    // ==================== Delete This And Future Tests ====================

    @Test
    fun `deleteThisAndFuture truncates series`() = runTest {
        val fromTime = 1704672000000L
        coEvery { eventReader.getEventById(recurringEvent.id) } returns recurringEvent

        coordinator.deleteThisAndFuture(recurringEvent.id, fromTime)

        coVerify { reminderScheduler.cancelRemindersForOccurrencesAfter(recurringEvent.id, fromTime) }
        coVerify { eventWriter.deleteThisAndFuture(recurringEvent.id, fromTime, false) }
    }

    // ==================== Move Event Tests ====================

    @Test
    fun `moveEventToCalendar moves event and triggers sync`() = runTest {
        // Setup: Return master event (not exception) when queried
        coEvery { eventReader.getEventById(testEvent.id) } returns testEvent

        coordinator.moveEventToCalendar(testEvent.id, iCloudCalendarId)

        coVerify { eventWriter.moveEventToCalendar(testEvent.id, iCloudCalendarId) }
        verify { syncScheduler.requestExpeditedSync(forceFullSync = false) }
    }

    // ==================== Read Operations Tests ====================

    @Test
    fun `getEventById delegates to reader`() = runTest {
        coEvery { eventReader.getEventById(testEvent.id) } returns testEvent

        val result = coordinator.getEventById(testEvent.id)

        assertEquals(testEvent, result)
    }

    @Test
    fun `getAllCalendars returns flow from reader`() = runTest {
        val calendars = listOf(localCalendar, iCloudCalendar)
        every { eventReader.getAllCalendars() } returns flowOf(calendars)

        val flow = coordinator.getAllCalendars()

        verify { eventReader.getAllCalendars() }
    }

    @Test
    fun `getDefaultCalendar delegates to reader`() = runTest {
        coEvery { eventReader.getDefaultCalendar() } returns localCalendar

        val result = coordinator.getDefaultCalendar()

        assertEquals(localCalendar, result)
    }

    @Test
    fun `searchEvents delegates to reader`() = runTest {
        val query = "meeting"
        val results = listOf(testEvent, recurringEvent)
        coEvery { eventReader.searchEvents(query) } returns results

        val result = coordinator.searchEvents(query)

        assertEquals(2, result.size)
    }

    // ==================== Occurrence Generation Tests ====================

    @Test
    fun `regenerateOccurrences regenerates for event`() = runTest {
        coEvery { eventReader.getEventById(recurringEvent.id) } returns recurringEvent
        coEvery { occurrenceGenerator.regenerateOccurrences(recurringEvent) } returns 52

        val count = coordinator.regenerateOccurrences(recurringEvent.id)

        assertEquals(52, count)
    }

    @Test
    fun `extendOccurrences extends range`() = runTest {
        val extendTo = 1735689600000L // Jan 1, 2025
        coEvery { eventReader.getEventById(recurringEvent.id) } returns recurringEvent
        coEvery { occurrenceGenerator.extendOccurrences(recurringEvent, extendTo) } returns 26

        val count = coordinator.extendOccurrences(recurringEvent.id, extendTo)

        assertEquals(26, count)
    }

    @Test
    fun `parseRRule parses RRULE string`() {
        val rruleInfo = OccurrenceGenerator.RRuleInfo(
            freq = "WEEKLY",
            interval = 1,
            count = null,
            until = null,
            byDay = listOf("MO"),
            byMonthDay = emptyList(),
            byMonth = emptyList(),
            bySetPos = emptyList()
        )
        every { occurrenceGenerator.parseRule("FREQ=WEEKLY;BYDAY=MO") } returns rruleInfo

        val result = coordinator.parseRRule("FREQ=WEEKLY;BYDAY=MO")

        assertEquals("WEEKLY", result?.freq)
        assertEquals(listOf("MO"), result?.byDay)
    }

    @Test
    fun `previewOccurrences previews without storing`() {
        val rrule = "FREQ=DAILY;COUNT=5"
        val dtstart = 1704067200000L
        val rangeStart = 1704067200000L
        val rangeEnd = 1735689600000L
        val preview = listOf(dtstart, dtstart + 86400000, dtstart + 172800000)
        every {
            occurrenceGenerator.expandForPreview(rrule, dtstart, rangeStart, rangeEnd)
        } returns preview

        val result = coordinator.previewOccurrences(rrule, dtstart, rangeStart, rangeEnd)

        assertEquals(3, result.size)
    }

    // ==================== Statistics Tests ====================

    @Test
    fun `getTotalEventCount delegates to reader`() = runTest {
        coEvery { eventReader.getTotalEventCount() } returns 100

        val count = coordinator.getTotalEventCount()

        assertEquals(100, count)
    }

    @Test
    fun `getEventCountForCalendar delegates to reader`() = runTest {
        coEvery { eventReader.getEventCountForCalendar(localCalendarId) } returns 50

        val count = coordinator.getEventCountForCalendar(localCalendarId)

        assertEquals(50, count)
    }

    // ==================== ICS Subscription Tests ====================

    @Test
    fun `getAllIcsSubscriptions delegates to repository`() = runTest {
        every { icsSubscriptionRepository.getAllSubscriptions() } returns flowOf(emptyList())

        coordinator.getAllIcsSubscriptions()

        verify { icsSubscriptionRepository.getAllSubscriptions() }
    }

    @Test
    fun `addIcsSubscription delegates to repository`() = runTest {
        val url = "https://example.com/calendar.ics"
        val name = "Test Calendar"
        val color = 0xFF000000.toInt()
        val subscription = org.onekash.kashcal.data.db.entity.IcsSubscription(
            id = 1L,
            url = url,
            name = name,
            color = color,
            calendarId = 100L
        )
        val result = IcsSubscriptionRepository.SubscriptionResult.Success(subscription)
        coEvery { icsSubscriptionRepository.addSubscription(url, name, color) } returns result

        val subscriptionResult = coordinator.addIcsSubscription(url, name, color)

        assertTrue(subscriptionResult is IcsSubscriptionRepository.SubscriptionResult.Success)
        assertEquals(url, (subscriptionResult as IcsSubscriptionRepository.SubscriptionResult.Success).subscription.url)
    }

    @Test
    fun `removeIcsSubscription delegates to repository`() = runTest {
        coordinator.removeIcsSubscription(1L)

        coVerify { icsSubscriptionRepository.removeSubscription(1L) }
    }

    @Test
    fun `refreshIcsSubscription delegates to repository`() = runTest {
        val result = IcsSubscriptionRepository.SyncResult.Success(
            count = IcsSubscriptionRepository.SyncCount(
                added = 5,
                updated = 2,
                deleted = 1
            )
        )
        coEvery { icsSubscriptionRepository.refreshSubscription(1L) } returns result

        val syncResult = coordinator.refreshIcsSubscription(1L)

        assertTrue(syncResult is IcsSubscriptionRepository.SyncResult.Success)
    }

    // ==================== Exception Event Guard Tests (v14.2.23) ====================

    /**
     * Exception event test data.
     * Exception events have originalEventId pointing to master.
     */
    private val exceptionEvent = Event(
        id = 102L,
        uid = "recurring@kashcal.test", // Same UID as master (RFC 5545)
        calendarId = iCloudCalendarId,
        title = "Modified Meeting",
        startTs = 1704672000000L, // Different time from master
        endTs = 1704675600000L,
        dtstamp = System.currentTimeMillis(),
        originalEventId = 101L, // Links to recurringEvent
        originalInstanceTime = 1704067200000L, // Original occurrence time
        syncStatus = SyncStatus.SYNCED
    )

    @Test
    fun `deleteEvent throws for exception event`() = runTest {
        // Setup: Return exception event when queried
        coEvery { eventReader.getEventById(exceptionEvent.id) } returns exceptionEvent

        // Act & Assert: Should throw IllegalArgumentException
        try {
            coordinator.deleteEvent(exceptionEvent.id)
            assertTrue("Should throw IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Cannot delete exception event"))
            assertTrue(e.message!!.contains("deleteSingleOccurrence"))
        }

        // Verify eventWriter.deleteEvent was NOT called
        coVerify(exactly = 0) { eventWriter.deleteEvent(any(), any()) }
    }

    @Test
    fun `deleteEvent succeeds for master event`() = runTest {
        // Setup: Return master event (no originalEventId)
        coEvery { eventReader.getEventById(recurringEvent.id) } returns recurringEvent

        // Act
        coordinator.deleteEvent(recurringEvent.id)

        // Assert: eventWriter.deleteEvent WAS called
        coVerify { eventWriter.deleteEvent(recurringEvent.id, false) }
    }

    @Test
    fun `moveEventToCalendar throws for exception event`() = runTest {
        // EventWriter now handles validation and throws
        coEvery { eventWriter.moveEventToCalendar(exceptionEvent.id, localCalendarId) } throws
            IllegalArgumentException("Cannot move exception event directly. Move the master event instead")

        // Act & Assert: Should throw IllegalArgumentException from EventWriter
        try {
            coordinator.moveEventToCalendar(exceptionEvent.id, localCalendarId)
            assertTrue("Should throw IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Cannot move exception event"))
            assertTrue(e.message!!.contains("master event"))
        }
    }

    @Test
    fun `moveEventToCalendar succeeds for master event`() = runTest {
        // Setup: Return master event (no originalEventId)
        coEvery { eventReader.getEventById(recurringEvent.id) } returns recurringEvent

        // Act
        coordinator.moveEventToCalendar(recurringEvent.id, localCalendarId)

        // Assert: eventWriter.moveEventToCalendar WAS called
        coVerify { eventWriter.moveEventToCalendar(recurringEvent.id, localCalendarId) }
    }

    @Test
    fun `moveEventToCalendar throws for non-existent event`() = runTest {
        // EventWriter now handles validation and throws
        coEvery { eventWriter.moveEventToCalendar(999L, localCalendarId) } throws
            IllegalArgumentException("Event not found: 999")

        // Act & Assert: Should throw IllegalArgumentException from EventWriter
        try {
            coordinator.moveEventToCalendar(999L, localCalendarId)
            assertTrue("Should throw IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Event not found"))
        }
    }

    @Test
    fun `deleteSingleOccurrence succeeds with master ID from exception context`() = runTest {
        // This tests the correct pattern: using masterEventId from exception.originalEventId
        val masterEventId = exceptionEvent.originalEventId!!
        val occurrenceTime = exceptionEvent.originalInstanceTime!!

        coEvery { eventReader.getEventById(masterEventId) } returns recurringEvent

        // Act: Use master ID (correct pattern)
        coordinator.deleteSingleOccurrence(masterEventId, occurrenceTime)

        // Assert
        coVerify { eventWriter.deleteSingleOccurrence(masterEventId, occurrenceTime, false) }
    }

    @Test
    fun `editSingleOccurrence succeeds with master ID from exception context`() = runTest {
        // This tests the correct pattern: using masterEventId from exception.originalEventId
        val masterEventId = exceptionEvent.originalEventId!!
        val occurrenceTime = exceptionEvent.originalInstanceTime!!

        coEvery { eventReader.getEventById(masterEventId) } returns recurringEvent
        coEvery { eventWriter.editSingleOccurrence(any(), any(), any(), any()) } returns exceptionEvent

        // Act: Use master ID (correct pattern)
        val result = coordinator.editSingleOccurrence(masterEventId, occurrenceTime) { event ->
            event.copy(title = "Updated Title")
        }

        // Assert
        coVerify { eventWriter.editSingleOccurrence(masterEventId, occurrenceTime, any(), false) }
        assertNotNull(result)
    }

    // ========== Time Validation Tests (v15.0.8) ==========

    @Test(expected = IllegalArgumentException::class)
    fun `createEvent throws when endTs less than startTs`() = runTest {
        val invalidEvent = testEvent.copy(
            startTs = 1704114000000L,  // 3 PM
            endTs = 1704110400000L     // 2 PM (before start)
        )

        coordinator.createEvent(invalidEvent)
    }

    @Test
    fun `createEvent allows equal startTs and endTs - zero duration`() = runTest {
        val zeroLengthEvent = testEvent.copy(
            startTs = 1704114000000L,
            endTs = 1704114000000L  // Same time - valid for reminders
        )

        coEvery { eventWriter.createEvent(any(), any()) } returns zeroLengthEvent

        val result = coordinator.createEvent(zeroLengthEvent)
        assertNotNull(result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `updateEvent throws when endTs less than startTs`() = runTest {
        val invalidEvent = testEvent.copy(
            startTs = 1704114000000L,
            endTs = 1704110400000L  // Before start
        )

        coordinator.updateEvent(invalidEvent)
    }

    @Test
    fun `updateEvent allows valid time range`() = runTest {
        val validEvent = testEvent.copy(
            startTs = 1704114000000L,  // 3 PM
            endTs = 1704117600000L     // 4 PM
        )

        coEvery { eventReader.getCalendarById(any()) } returns localCalendar
        coEvery { eventWriter.updateEvent(any(), any()) } returns validEvent

        val result = coordinator.updateEvent(validEvent)
        assertNotNull(result)
    }

    // ========== Reminder Scheduling Tests (v16.4.1) ==========

    @Test
    fun `editThisAndFuture schedules reminders for new series`() = runTest {
        val splitTime = 1704672000000L
        val newSeries = recurringEvent.copy(
            id = 201L,
            startTs = splitTime,
            uid = "split-series@kashcal.test",
            reminders = listOf("-PT15M")
        )
        val testOccurrence = Occurrence(
            eventId = newSeries.id,
            calendarId = iCloudCalendarId,
            startTs = splitTime,
            endTs = splitTime + 3600000L,
            startDay = 20240108,
            endDay = 20240108
        )
        coEvery { eventReader.getEventById(recurringEvent.id) } returns recurringEvent
        coEvery { eventWriter.splitSeries(any(), any(), any(), any()) } returns newSeries
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(newSeries.id) } returns listOf(testOccurrence)

        coordinator.editThisAndFuture(
            masterEventId = recurringEvent.id,
            splitTimeMs = splitTime,
            changes = { it.copy(title = "New Title") }
        )

        // Verify reminder scheduling was called for new series
        coVerify { reminderScheduler.scheduleRemindersForEvent(newSeries, any(), any()) }
    }

    @Test
    fun `importIcsEvents schedules reminders for each imported event`() = runTest {
        // Events to import - importIcsEvents will generate new UIDs for these
        val eventsToImport = listOf(
            testEvent.copy(id = 0L, title = "Import Event 1", reminders = listOf("-PT15M")),
            testEvent.copy(id = 0L, title = "Import Event 2", reminders = listOf("-PT30M"))
        )

        // Mock eventWriter to return events with IDs
        val createdEvent1 = eventsToImport[0].copy(id = 301L, uid = "generated-1@kashcal.onekash.org")
        val createdEvent2 = eventsToImport[1].copy(id = 302L, uid = "generated-2@kashcal.onekash.org")
        val testOccurrence1 = Occurrence(
            eventId = createdEvent1.id,
            calendarId = localCalendarId,
            startTs = createdEvent1.startTs,
            endTs = createdEvent1.endTs,
            startDay = 20240101,
            endDay = 20240101
        )
        val testOccurrence2 = Occurrence(
            eventId = createdEvent2.id,
            calendarId = localCalendarId,
            startTs = createdEvent2.startTs,
            endTs = createdEvent2.endTs,
            startDay = 20240101,
            endDay = 20240101
        )

        // Use answers to return different events for each call
        var callCount = 0
        coEvery { eventWriter.createEvent(any(), any()) } answers {
            callCount++
            if (callCount == 1) createdEvent1 else createdEvent2
        }
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(createdEvent1.id) } returns listOf(testOccurrence1)
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(createdEvent2.id) } returns listOf(testOccurrence2)

        val count = coordinator.importIcsEvents(eventsToImport, localCalendarId)

        assertEquals(2, count)
        // Verify reminder scheduling was called for each imported event (exactly 2 times)
        coVerify(exactly = 2) { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) }
    }

    @Test
    fun `importIcsEvents skips reminder scheduling for events without reminders`() = runTest {
        val eventWithoutReminders = testEvent.copy(
            id = 0L,
            uid = "no-reminder@test",
            reminders = null
        )
        val createdEvent = eventWithoutReminders.copy(id = 303L)

        coEvery { eventWriter.createEvent(any(), any()) } returns createdEvent

        val count = coordinator.importIcsEvents(listOf(eventWithoutReminders), localCalendarId)

        assertEquals(1, count)
        // Verify reminder scheduling was NOT called (no reminders on event)
        coVerify(exactly = 0) { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) }
    }

    // ==================== Account Reminder Cleanup Tests (v16.4.1) ====================

    @Test
    fun `cancelRemindersForAccount cancels reminders for all account calendars`() = runTest {
        // Setup: Create test account with two calendars and events
        val testAccount = Account(
            id = 10L,
            provider = AccountProvider.ICLOUD,
            email = "test@icloud.com",
            displayName = "Test Account",
            isEnabled = true
        )
        val calendar1 = iCloudCalendar.copy(id = 20L, accountId = testAccount.id)
        val calendar2 = iCloudCalendar.copy(id = 21L, accountId = testAccount.id, displayName = "Work")
        val event1 = testEvent.copy(id = 200L, calendarId = calendar1.id)
        val event2 = testEvent.copy(id = 201L, calendarId = calendar2.id)

        coEvery { eventReader.getAccountByProviderAndEmail(AccountProvider.ICLOUD, "test@icloud.com") } returns testAccount
        coEvery { eventReader.getCalendarsByAccountIdOnce(testAccount.id) } returns listOf(calendar1, calendar2)
        coEvery { eventReader.getEventsForCalendar(calendar1.id) } returns listOf(event1)
        coEvery { eventReader.getEventsForCalendar(calendar2.id) } returns listOf(event2)

        // Act
        coordinator.cancelRemindersForAccount("test@icloud.com")

        // Assert: Reminders cancelled for both events
        coVerify { reminderScheduler.cancelRemindersForEvent(event1.id) }
        coVerify { reminderScheduler.cancelRemindersForEvent(event2.id) }
    }

    @Test
    fun `cancelRemindersForAccount handles non-existent account gracefully`() = runTest {
        // Setup: No account found
        coEvery { eventReader.getAccountByProviderAndEmail(AccountProvider.ICLOUD, "nonexistent@test.com") } returns null

        // Act: Should not throw
        coordinator.cancelRemindersForAccount("nonexistent@test.com")

        // Assert: No reminders cancelled
        coVerify(exactly = 0) { reminderScheduler.cancelRemindersForEvent(any()) }
    }

    // ==================== Move Event Reminder Reschedule Tests (v16.4.1) ====================

    @Test
    fun `moveEventToCalendar reschedules reminders with new calendar color`() = runTest {
        // Setup: Event with reminders in calendar 1
        val eventWithReminders = testEvent.copy(
            id = 300L,
            calendarId = localCalendarId,
            reminders = listOf("-PT15M")
        )
        val targetCalendar = iCloudCalendar.copy(color = 0xFFFF0000.toInt()) // Different color
        val movedEvent = eventWithReminders.copy(calendarId = targetCalendar.id)

        // First call returns event (for any pre-check), second call returns movedEvent
        coEvery { eventReader.getEventById(eventWithReminders.id) } returns movedEvent
        coEvery { eventReader.getCalendarById(targetCalendar.id) } returns targetCalendar
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any()) } returns emptyList()
        coEvery { eventWriter.moveEventToCalendar(eventWithReminders.id, targetCalendar.id) } returns Unit

        // Act
        coordinator.moveEventToCalendar(eventWithReminders.id, targetCalendar.id)

        // Assert: Move was executed
        coVerify { eventWriter.moveEventToCalendar(eventWithReminders.id, targetCalendar.id) }

        // Assert: Reminders were rescheduled (cancel + schedule)
        coVerify { reminderScheduler.cancelRemindersForEvent(eventWithReminders.id) }
        coVerify { reminderScheduler.scheduleRemindersForEvent(movedEvent, any(), any()) }
    }
}