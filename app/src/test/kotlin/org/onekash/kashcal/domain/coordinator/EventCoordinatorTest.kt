package org.onekash.kashcal.domain.coordinator

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.contacts.ContactAnniversaryRepository
import org.onekash.kashcal.data.contacts.ContactBirthdayRepository
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.ics.IcsSubscriptionRepository
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.scheduler.IcsRefreshScheduleReconciler
import org.onekash.kashcal.sync.scheduler.SyncScheduler

/**
 * Tests [EventCoordinator] over mocked collaborators:
 * - local calendar initialization
 * - event create, update, delete and move, with the read-only, exception and end-before-start
 *   guards
 * - recurring edits and deletes (single occurrence, this and future)
 * - expedited sync requests
 * - reminder scheduling, including import defaults and saves that succeed when scheduling
 *   throws
 * - read, statistics, export and occurrence-generation delegation, and occurrence repair
 * - ICS subscriptions and the refresh-schedule reconcile after each mutation
 * - ICS file import of standalone events and linked series
 * - RSVP replies and their reminder hooks
 * - attendee forwarding and ORGANIZER resolution
 */
class EventCoordinatorTest {

    // Mocks
    private lateinit var eventWriter: EventWriter
    private lateinit var eventReader: EventReader
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var localCalendarInitializer: LocalCalendarInitializer
    private lateinit var icsSubscriptionRepository: IcsSubscriptionRepository
    private lateinit var contactBirthdayRepository: ContactBirthdayRepository
    private lateinit var contactAnniversaryRepository: ContactAnniversaryRepository
    private lateinit var accountRepository: AccountRepository
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var widgetUpdateManager: org.onekash.kashcal.widget.WidgetUpdateManager
    private lateinit var icsRefreshScheduleReconciler: IcsRefreshScheduleReconciler
    private lateinit var dataStore: KashCalDataStore

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
        contactAnniversaryRepository = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)
        widgetUpdateManager = mockk(relaxed = true)
        // The reconciler is a Unit-returning side-effect collaborator, so a relaxed mock has
        // no return value to get wrong.
        icsRefreshScheduleReconciler = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        // The user's default reminders: 15 minutes for timed events, 540 for all-day ones.
        // Production reads whatever the user set.
        every { dataStore.defaultReminderMinutes } returns flowOf(15)
        every { dataStore.defaultAllDayReminder } returns flowOf(540)

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
            contactAnniversaryRepository = contactAnniversaryRepository,
            accountRepository = accountRepository,
            syncScheduler = syncScheduler,
            reminderScheduler = reminderScheduler,
            widgetUpdateManager = widgetUpdateManager,
            inviteNotifier = mockk(relaxed = true),
            icsRefreshScheduleReconciler = icsRefreshScheduleReconciler,
            dataStore = dataStore
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
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()

        val result = coordinator.createEvent(newEvent, localCalendarId)

        assertEquals(createdEvent, result)
        coVerify { eventWriter.createEvent(match { it.calendarId == localCalendarId }, true) }
    }

    @Test
    fun `createEvent uses local calendar when no calendar specified`() = runTest {
        val newEvent = testEvent.copy(id = 0L, calendarId = 999L) // Wrong calendar
        val createdEvent = testEvent.copy()
        coEvery { eventWriter.createEvent(any(), any()) } returns createdEvent
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()

        coordinator.createEvent(newEvent, null) // No calendar specified

        coVerify { eventWriter.createEvent(match { it.calendarId == localCalendarId }, any()) }
    }

    @Test
    fun `createEvent in local calendar does not trigger sync`() = runTest {
        val newEvent = testEvent.copy(id = 0L)
        coEvery { eventWriter.createEvent(any(), any()) } returns testEvent
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()

        coordinator.createEvent(newEvent, localCalendarId)

        verify(exactly = 0) { syncScheduler.requestExpeditedSync(any()) }
    }

    @Test
    fun `createEvent in iCloud calendar triggers sync`() = runTest {
        val newEvent = testEvent.copy(id = 0L, calendarId = iCloudCalendarId)
        coEvery { eventWriter.createEvent(any(), any()) } returns newEvent.copy(id = 100L)
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()

        coordinator.createEvent(newEvent, iCloudCalendarId)

        verify { syncScheduler.requestExpeditedSync(forceFullSync = false) }
    }

    @Test
    fun `createEvent schedules reminders when event has reminders`() = runTest {
        val eventWithReminders = testEvent.copy(reminders = listOf("-PT15M", "-PT1H"))
        coEvery { eventWriter.createEvent(any(), any()) } returns eventWithReminders
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns listOf(
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
        // A read-only calendar, such as an ICS subscription.
        val newEvent = testEvent.copy(id = 0L, calendarId = readOnlyCalendarId)

        try {
            coordinator.createEvent(newEvent, readOnlyCalendarId)
            assertTrue("Should throw IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("read-only"))
        }

        // The writer is never called.
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
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()

        val result = coordinator.createRecurringEvent(recurringEvent.copy(id = 0L))

        assertEquals(recurringEvent, result)
    }

    @Test
    fun `createRecurringEvent forwards attendees to the writer`() = runTest {
        val attSlot = slot<List<Attendee>>()
        coEvery { eventWriter.createEvent(any(), any(), capture(attSlot)) } answers { firstArg<Event>() }
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()

        coordinator.createRecurringEvent(
            recurringEvent.copy(id = 0L, calendarId = iCloudCalendarId),
            iCloudCalendarId,
            attendees = listOf(attendee("bob@example.test"))
        )

        assertEquals(listOf("bob@example.test"), attSlot.captured.map { it.address })
    }

    // ==================== Update Event Tests ====================

    @Test
    fun `updateEvent updates and triggers sync for iCloud`() = runTest {
        val updatedEvent = testEvent.copy(calendarId = iCloudCalendarId, title = "Updated")
        coEvery { eventWriter.updateEvent(any(), any()) } returns updatedEvent
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()

        val result = coordinator.updateEvent(updatedEvent)

        assertEquals("Updated", result.title)
        verify { syncScheduler.requestExpeditedSync(forceFullSync = false) }
    }

    @Test
    fun `updateEvent reschedules reminders`() = runTest {
        val updatedEvent = testEvent.copy(reminders = listOf("-PT30M"))
        coEvery { eventWriter.updateEvent(any(), any()) } returns updatedEvent
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()

        coordinator.updateEvent(updatedEvent)

        coVerify { reminderScheduler.cancelRemindersForEvent(updatedEvent.id) }
        coVerify { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) }
    }

    @Test
    fun `updateEvent succeeds when reminder scheduling throws`() = runTest {
        val updatedEvent = testEvent.copy(title = "Updated", reminders = listOf("-PT15M"))
        coEvery { eventWriter.updateEvent(any(), any()) } returns updatedEvent
        coEvery { reminderScheduler.cancelRemindersForEvent(any()) } throws RuntimeException("DB locked")

        val result = coordinator.updateEvent(updatedEvent)

        assertEquals("Updated", result.title)
        coVerify { eventWriter.updateEvent(any(), any()) }
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
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()

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
        // The original occurrence's reminders are cancelled before the exception's are
        // scheduled, or both fire.
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

        // Cancelled for the original occurrence: master id and occurrence time.
        coVerify { reminderScheduler.cancelReminderForOccurrence(recurringEvent.id, occurrenceTime) }

        // Scheduled for the exception.
        coVerify { reminderScheduler.scheduleRemindersForEvent(exceptionEventResult, any(), any()) }

        // Cancel runs before schedule.
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
        coEvery { eventWriter.splitSeries(any(), any(), any(), any(), any()) } returns newSeries

        val result = coordinator.editThisAndFuture(
            masterEventId = recurringEvent.id,
            splitTimeMs = splitTime,
            changes = { it.copy(title = "New Title") }
        )

        assertNotNull(result)
        coVerify { eventWriter.splitSeries(recurringEvent.id, splitTime, any(), false, any()) }
        verify { syncScheduler.requestExpeditedSync(forceFullSync = false) }
    }

    @Test
    fun `editThisAndFuture forwards the edited attendee set to splitSeries`() = runTest {
        val splitTime = 1704672000000L
        val newSeries = recurringEvent.copy(id = 203L, startTs = splitTime, uid = "split-att@kashcal.test")
        val edited = listOf(
            org.onekash.kashcal.data.db.entity.Attendee(
                eventId = 0, address = "mailto:newguest@example.test", partstat = "NEEDS-ACTION", sortOrder = 0
            )
        )
        coEvery { eventReader.getEventById(recurringEvent.id) } returns recurringEvent
        coEvery { eventWriter.splitSeries(any(), any(), any(), any(), any()) } returns newSeries

        coordinator.editThisAndFuture(
            masterEventId = recurringEvent.id,
            splitTimeMs = splitTime,
            attendees = edited,
            changes = { it.copy(title = "New Title") }
        )

        coVerify { eventWriter.splitSeries(recurringEvent.id, splitTime, any(), any(), edited) }
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
        // A master, not an exception.
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
    fun `extendPastOccurrences extends past range`() = runTest {
        val extendTo = 1672531200000L // Jan 1, 2023
        coEvery { eventReader.getEventById(recurringEvent.id) } returns recurringEvent
        coEvery { occurrenceGenerator.extendPastOccurrences(recurringEvent, extendTo) } returns 15

        val count = coordinator.extendPastOccurrences(recurringEvent.id, extendTo)

        assertEquals(15, count)
        coVerify { occurrenceGenerator.extendPastOccurrences(recurringEvent, extendTo) }
    }

    @Test
    fun `extendPastOccurrencesIfNeeded finds and extends events`() = runTest {
        val targetMs = 1672531200000L // Jan 1, 2023
        val bufferMs = 6 * 30L * 24 * 60 * 60 * 1000
        val extendToMs = targetMs - bufferMs

        coEvery { eventReader.getRecurringEventsNeedingPastExtension(extendToMs) } returns listOf(recurringEvent.id)
        coEvery { eventReader.getEventById(recurringEvent.id) } returns recurringEvent
        coEvery { occurrenceGenerator.extendPastOccurrences(recurringEvent, extendToMs) } returns 20

        val count = coordinator.extendPastOccurrencesIfNeeded(targetMs)

        assertEquals(20, count)
        coVerify { eventReader.getRecurringEventsNeedingPastExtension(extendToMs) }
        coVerify { occurrenceGenerator.extendPastOccurrences(recurringEvent, extendToMs) }
    }

    @Test
    fun `extendPastOccurrencesIfNeeded returns 0 when no events need extension`() = runTest {
        val targetMs = 1672531200000L
        val bufferMs = 6 * 30L * 24 * 60 * 60 * 1000
        val extendToMs = targetMs - bufferMs

        coEvery { eventReader.getRecurringEventsNeedingPastExtension(extendToMs) } returns emptyList()

        val count = coordinator.extendPastOccurrencesIfNeeded(targetMs)

        assertEquals(0, count)
    }

    @Test
    fun `extendOccurrencesIfNeeded finds and extends events`() = runTest {
        val targetMs = 1767225600000L // Jan 1, 2026
        val bufferMs = 6 * 30L * 24 * 60 * 60 * 1000
        val extendToMs = targetMs + bufferMs

        coEvery { eventReader.getRecurringEventsNeedingExtension(extendToMs) } returns listOf(recurringEvent.id)
        coEvery { eventReader.getEventById(recurringEvent.id) } returns recurringEvent
        coEvery { occurrenceGenerator.extendOccurrences(recurringEvent, extendToMs) } returns 30

        val count = coordinator.extendOccurrencesIfNeeded(targetMs)

        assertEquals(30, count)
        coVerify { eventReader.getRecurringEventsNeedingExtension(extendToMs) }
        coVerify { occurrenceGenerator.extendOccurrences(recurringEvent, extendToMs) }
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

    // ==================== Repair Missing Occurrences Tests ====================

    @Test
    fun `repairMissingOccurrences regenerates occurrences for events with none`() = runTest {
        val id1 = 200L
        val id2 = 201L
        val event1 = recurringEvent.copy(id = id1, uid = "orphan1@test")
        val event2 = recurringEvent.copy(id = id2, uid = "orphan2@test")

        coEvery { eventReader.getRecurringEventsWithNoOccurrences() } returns listOf(id1, id2)
        coEvery { eventReader.getEventById(id1) } returns event1
        coEvery { eventReader.getEventById(id2) } returns event2
        coEvery { occurrenceGenerator.regenerateOccurrences(event1) } returns 52
        coEvery { occurrenceGenerator.regenerateOccurrences(event2) } returns 12

        val count = coordinator.repairMissingOccurrences()

        assertEquals(2, count)
        coVerify { eventReader.getRecurringEventsWithNoOccurrences() }
        coVerify { occurrenceGenerator.regenerateOccurrences(event1) }
        coVerify { occurrenceGenerator.regenerateOccurrences(event2) }
    }

    @Test
    fun `repairMissingOccurrences returns zero when no events need repair`() = runTest {
        coEvery { eventReader.getRecurringEventsWithNoOccurrences() } returns emptyList()

        val count = coordinator.repairMissingOccurrences()

        assertEquals(0, count)
        coVerify { eventReader.getRecurringEventsWithNoOccurrences() }
        coVerify(exactly = 0) { occurrenceGenerator.regenerateOccurrences(any()) }
    }

    @Test
    fun `repairMissingOccurrences skips events that no longer exist`() = runTest {
        coEvery { eventReader.getRecurringEventsWithNoOccurrences() } returns listOf(999L)
        coEvery { eventReader.getEventById(999L) } returns null

        val count = coordinator.repairMissingOccurrences()

        assertEquals(0, count)
        coVerify(exactly = 0) { occurrenceGenerator.regenerateOccurrences(any()) }
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
    fun `updateIcsSubscriptionSettings delegates to repository`() = runTest {
        coordinator.updateIcsSubscriptionSettings(1L, "Renamed", 0xFF00FF00.toInt(), 6)

        coVerify {
            icsSubscriptionRepository.updateSubscriptionSettings(
                1L, "Renamed", 0xFF00FF00.toInt(), 6,
            )
        }
    }

    @Test
    fun `setIcsSubscriptionEnabled delegates to repository`() = runTest {
        coordinator.setIcsSubscriptionEnabled(1L, false)

        coVerify { icsSubscriptionRepository.setSubscriptionEnabled(1L, false) }
    }

    // Each of the four mutations below can change the set of enabled feeds or a feed's
    // interval, so each must leave the periodic refresh job in agreement with the database.
    // The coordinator reconciles after the write so no caller can forget to.

    @Test
    fun `adding a subscription reconciles the refresh schedule`() = runTest {
        val subscription = org.onekash.kashcal.data.db.entity.IcsSubscription(
            id = 1L,
            url = "https://example.com/calendar.ics",
            name = "Test Calendar",
            color = 0xFF000000.toInt(),
            calendarId = 100L,
        )
        coEvery { icsSubscriptionRepository.addSubscription(any(), any(), any()) } returns
            IcsSubscriptionRepository.SubscriptionResult.Success(subscription)

        coordinator.addIcsSubscription("https://example.com/calendar.ics", "Test Calendar", 0)

        coVerify(exactly = 1) { icsRefreshScheduleReconciler.reconcile() }
    }

    @Test
    fun `a failed subscription add does not reconcile`() = runTest {
        // Nothing was written, so there is nothing to reconcile; re-arming on a failed add
        // would be a scheduling call on every typo.
        coEvery { icsSubscriptionRepository.addSubscription(any(), any(), any()) } returns
            IcsSubscriptionRepository.SubscriptionResult.Error("unreachable")

        coordinator.addIcsSubscription("https://example.com/bad.ics", "Bad", 0)

        coVerify(exactly = 0) { icsRefreshScheduleReconciler.reconcile() }
    }

    @Test
    fun `removing a subscription reconciles the refresh schedule`() = runTest {
        // Removing the last feed has to stop the job, not leave it waking forever.
        coordinator.removeIcsSubscription(1L)

        coVerify(exactly = 1) { icsRefreshScheduleReconciler.reconcile() }
    }

    @Test
    fun `changing a subscription interval reconciles the refresh schedule`() = runTest {
        // Without the reconcile the new interval reaches the database and the job keeps its
        // old period forever.
        coordinator.updateIcsSubscriptionSettings(1L, "Renamed", 0, syncIntervalHours = 1)

        coVerify(exactly = 1) { icsRefreshScheduleReconciler.reconcile() }
    }

    @Test
    fun `toggling a subscription reconciles the refresh schedule`() = runTest {
        coordinator.setIcsSubscriptionEnabled(1L, false)

        coVerify(exactly = 1) { icsRefreshScheduleReconciler.reconcile() }
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

    /** An exception of [recurringEvent], linked by originalEventId. */
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
        coEvery { eventReader.getEventById(exceptionEvent.id) } returns exceptionEvent

        try {
            coordinator.deleteEvent(exceptionEvent.id)
            assertTrue("Should throw IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Cannot delete exception event"))
            assertTrue(e.message!!.contains("deleteSingleOccurrence"))
        }

        // The writer is never called.
        coVerify(exactly = 0) { eventWriter.deleteEvent(any(), any()) }
    }

    @Test
    fun `deleteEvent succeeds for master event`() = runTest {
        // A master: no originalEventId.
        coEvery { eventReader.getEventById(recurringEvent.id) } returns recurringEvent

        coordinator.deleteEvent(recurringEvent.id)

        coVerify { eventWriter.deleteEvent(recurringEvent.id, false) }
    }

    @Test
    fun `moveEventToCalendar throws for exception event`() = runTest {
        // The writer validates the move and throws.
        coEvery { eventWriter.moveEventToCalendar(exceptionEvent.id, localCalendarId) } throws
            IllegalArgumentException("Cannot move exception event directly. Move the master event instead")

        // The writer's exception propagates.
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
        // A master: no originalEventId.
        coEvery { eventReader.getEventById(recurringEvent.id) } returns recurringEvent

        coordinator.moveEventToCalendar(recurringEvent.id, localCalendarId)

        coVerify { eventWriter.moveEventToCalendar(recurringEvent.id, localCalendarId) }
    }

    @Test
    fun `moveEventToCalendar throws for non-existent event`() = runTest {
        // The writer validates the move and throws.
        coEvery { eventWriter.moveEventToCalendar(999L, localCalendarId) } throws
            IllegalArgumentException("Event not found: 999")

        // The writer's exception propagates.
        try {
            coordinator.moveEventToCalendar(999L, localCalendarId)
            assertTrue("Should throw IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Event not found"))
        }
    }

    @Test
    fun `deleteSingleOccurrence succeeds with master ID from exception context`() = runTest {
        // A caller acting on an exception passes its master's id (exception.originalEventId).
        val masterEventId = exceptionEvent.originalEventId!!
        val occurrenceTime = exceptionEvent.originalInstanceTime!!

        coEvery { eventReader.getEventById(masterEventId) } returns recurringEvent

        coordinator.deleteSingleOccurrence(masterEventId, occurrenceTime)

        coVerify { eventWriter.deleteSingleOccurrence(masterEventId, occurrenceTime, false) }
    }

    @Test
    fun `editSingleOccurrence succeeds with master ID from exception context`() = runTest {
        // A caller acting on an exception passes its master's id (exception.originalEventId).
        val masterEventId = exceptionEvent.originalEventId!!
        val occurrenceTime = exceptionEvent.originalInstanceTime!!

        coEvery { eventReader.getEventById(masterEventId) } returns recurringEvent
        coEvery { eventWriter.editSingleOccurrence(any(), any(), any(), any()) } returns exceptionEvent

        val result = coordinator.editSingleOccurrence(masterEventId, occurrenceTime) { event ->
            event.copy(title = "Updated Title")
        }

        coVerify { eventWriter.editSingleOccurrence(masterEventId, occurrenceTime, any(), false) }
        assertNotNull(result)
    }

    // ========== Time Validation Tests (v15.0.8) ==========

    @Test(expected = IllegalArgumentException::class)
    fun `createEvent throws when endTs less than startTs`() = runTest {
        val invalidEvent = testEvent.copy(
            startTs = 1704114000000L,  // 1 PM UTC
            endTs = 1704110400000L     // Noon UTC, before start
        )

        coordinator.createEvent(invalidEvent)
    }

    @Test
    fun `createEvent allows equal startTs and endTs - zero duration`() = runTest {
        val zeroLengthEvent = testEvent.copy(
            startTs = 1704114000000L,
            // Same time: a zero-duration event, such as a reminder, is valid.
            endTs = 1704114000000L
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
            startTs = 1704114000000L,  // 1 PM UTC
            endTs = 1704117600000L     // 2 PM UTC
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
        coEvery { eventWriter.splitSeries(any(), any(), any(), any(), any()) } returns newSeries
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(newSeries.id, ReminderScheduler.OCCURRENCE_LOOKAHEAD_DAYS) } returns listOf(testOccurrence)

        coordinator.editThisAndFuture(
            masterEventId = recurringEvent.id,
            splitTimeMs = splitTime,
            changes = { it.copy(title = "New Title") }
        )

        // Reminders are scheduled for the new series.
        coVerify { reminderScheduler.scheduleRemindersForEvent(newSeries, any(), any()) }
    }

    @Test
    fun `editThisAndFuture cancels master reminders for occurrences after split point`() = runTest {
        val splitTime = 1704672000000L
        val newSeries = recurringEvent.copy(
            id = 202L,
            startTs = splitTime,
            uid = "split-series-cancel@kashcal.test"
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
        coEvery { eventWriter.splitSeries(any(), any(), any(), any(), any()) } returns newSeries
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(newSeries.id, ReminderScheduler.OCCURRENCE_LOOKAHEAD_DAYS) } returns listOf(testOccurrence)

        coordinator.editThisAndFuture(
            masterEventId = recurringEvent.id,
            splitTimeMs = splitTime,
            changes = { it.copy(title = "Renamed") }
        )

        // The master's reminders at or after splitTime belong to the new series now, so
        // they're cancelled, as deleteThisAndFuture cancels its own.
        coVerify { reminderScheduler.cancelRemindersForOccurrencesAfter(recurringEvent.id, splitTime) }
    }

    @Test
    fun `editThisAndFuture does not cancel reminders if splitSeries throws`() = runTest {
        // Cancellation runs only after splitSeries succeeds: a failed split rolls back, the
        // master is intact and its reminders must still fire.
        val splitTime = 1704672000000L
        coEvery { eventReader.getEventById(recurringEvent.id) } returns recurringEvent
        coEvery { eventWriter.splitSeries(any(), any(), any(), any(), any()) } throws
                IllegalStateException("simulated split failure")

        try {
            coordinator.editThisAndFuture(
                masterEventId = recurringEvent.id,
                splitTimeMs = splitTime,
                changes = { it.copy(title = "Will fail") }
            )
            assert(false) { "splitSeries failure should propagate out of editThisAndFuture" }
        } catch (_: IllegalStateException) {
            // expected
        }

        coVerify(exactly = 0) {
            reminderScheduler.cancelRemindersForOccurrencesAfter(any(), any())
        }
    }

    @Test
    fun `importIcsEvents schedules reminders for each imported event`() = runTest {
        // importIcsEvents gives each imported event a fresh UID.
        val eventsToImport = listOf(
            testEvent.copy(id = 0L, title = "Import Event 1", reminders = listOf("-PT15M")),
            testEvent.copy(id = 0L, title = "Import Event 2", reminders = listOf("-PT30M"))
        )

        // What the writer returns: the events with ids.
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

        // A different event for each call.
        var callCount = 0
        coEvery { eventWriter.createEvent(any(), any()) } answers {
            callCount++
            if (callCount == 1) createdEvent1 else createdEvent2
        }
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(createdEvent1.id, ReminderScheduler.OCCURRENCE_LOOKAHEAD_DAYS) } returns listOf(testOccurrence1)
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(createdEvent2.id, ReminderScheduler.OCCURRENCE_LOOKAHEAD_DAYS) } returns listOf(testOccurrence2)

        val count = coordinator.importIcsEvents(eventsToImport, localCalendarId)

        assertEquals(2, count)
        // Reminders are scheduled once per imported event.
        coVerify(exactly = 2) { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) }
    }

    @Test
    fun `importIcsEvents applies user's default timed reminder when ICS has no VALARM`() = runTest {
        // setup() stubs the user's timed default at 15 minutes; the file has no VALARM, so
        // reminders=null reaches the import. The test is about whatever the user configured,
        // not 15.
        val timedEventNoReminders = testEvent.copy(
            id = 0L,
            uid = "no-reminder@test",
            isAllDay = false,
            reminders = null
        )
        val createdEvent = timedEventNoReminders.copy(id = 303L, reminders = listOf("-PT15M"))
        val testOccurrence = Occurrence(
            eventId = createdEvent.id,
            calendarId = localCalendarId,
            startTs = createdEvent.startTs,
            endTs = createdEvent.endTs,
            startDay = 20240101,
            endDay = 20240101
        )

        coEvery { eventWriter.createEvent(any(), any()) } returns createdEvent
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(createdEvent.id, ReminderScheduler.OCCURRENCE_LOOKAHEAD_DAYS) } returns listOf(testOccurrence)

        val count = coordinator.importIcsEvents(listOf(timedEventNoReminders), localCalendarId)

        assertEquals(1, count)
        // The writer receives the timed default as an ISO duration.
        coVerify {
            eventWriter.createEvent(
                match { it.reminders == listOf("-PT15M") },
                any()
            )
        }
        // Reminders are scheduled because a default was applied.
        coVerify(exactly = 1) { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) }
    }

    @Test
    fun `importIcsEvents applies user's default all-day reminder when ICS has no VALARM`() = runTest {
        // setup() stubs the user's all-day default at 540 minutes (9 hours before).
        val allDayEventNoReminders = testEvent.copy(
            id = 0L,
            uid = "all-day-no-reminder@test",
            isAllDay = true,
            reminders = null
        )
        val createdEvent = allDayEventNoReminders.copy(id = 304L, reminders = listOf("-PT9H"))
        val testOccurrence = Occurrence(
            eventId = createdEvent.id,
            calendarId = localCalendarId,
            startTs = createdEvent.startTs,
            endTs = createdEvent.endTs,
            startDay = 20240101,
            endDay = 20240101
        )

        coEvery { eventWriter.createEvent(any(), any()) } returns createdEvent
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(createdEvent.id, ReminderScheduler.OCCURRENCE_LOOKAHEAD_DAYS) } returns listOf(testOccurrence)

        coordinator.importIcsEvents(listOf(allDayEventNoReminders), localCalendarId)

        coVerify {
            eventWriter.createEvent(
                match { it.reminders == listOf("-PT9H") },
                any()
            )
        }
    }

    @Test
    fun `importIcsEvents preserves ICS VALARM reminders and does not overwrite with default`() = runTest {
        // The file's VALARMs are parsed into reminders; the default must not replace them.
        val eventWithIcsReminders = testEvent.copy(
            id = 0L,
            uid = "ics-with-alarm@test",
            isAllDay = false,
            reminders = listOf("-PT30M", "-PT1H")
        )
        val createdEvent = eventWithIcsReminders.copy(id = 305L)
        val testOccurrence = Occurrence(
            eventId = createdEvent.id,
            calendarId = localCalendarId,
            startTs = createdEvent.startTs,
            endTs = createdEvent.endTs,
            startDay = 20240101,
            endDay = 20240101
        )

        coEvery { eventWriter.createEvent(any(), any()) } returns createdEvent
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(createdEvent.id, ReminderScheduler.OCCURRENCE_LOOKAHEAD_DAYS) } returns listOf(testOccurrence)

        coordinator.importIcsEvents(listOf(eventWithIcsReminders), localCalendarId)

        coVerify {
            eventWriter.createEvent(
                match { it.reminders == listOf("-PT30M", "-PT1H") },
                any()
            )
        }
    }

    @Test
    fun `importIcsEvents skips reminder scheduling when default is REMINDER_OFF`() = runTest {
        // The user turned the default reminder off, so an event with no VALARM gets none.
        every { dataStore.defaultReminderMinutes } returns flowOf(KashCalDataStore.REMINDER_OFF)
        every { dataStore.defaultAllDayReminder } returns flowOf(KashCalDataStore.REMINDER_OFF)

        val eventWithoutReminders = testEvent.copy(
            id = 0L,
            uid = "no-reminder@test",
            isAllDay = false,
            reminders = null
        )
        val createdEvent = eventWithoutReminders.copy(id = 303L)

        coEvery { eventWriter.createEvent(any(), any()) } returns createdEvent

        val count = coordinator.importIcsEvents(listOf(eventWithoutReminders), localCalendarId)

        assertEquals(1, count)
        coVerify {
            eventWriter.createEvent(
                match { it.reminders == null },
                any()
            )
        }
        coVerify(exactly = 0) { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) }
    }

    // ===== Recurring-series import: master + RECURRENCE-ID overrides =====

    @Test
    fun `importIcsEvents groups master and its exceptions into one linked series`() = runTest {
        val sharedUid = "series@source.ics"
        val master = testEvent.copy(
            id = 0L, uid = sharedUid, title = "Weekly", rrule = "FREQ=WEEKLY;COUNT=5",
            reminders = null, originalInstanceTime = null
        )
        val exception1 = testEvent.copy(
            id = 0L, uid = sharedUid, title = "Moved wk2",
            rrule = null, originalInstanceTime = master.startTs + 7 * 86400000L, reminders = null
        )
        val exception2 = testEvent.copy(
            id = 0L, uid = sharedUid, title = "Moved wk3",
            rrule = null, originalInstanceTime = master.startTs + 14 * 86400000L, reminders = null
        )

        val seriesSlot = slot<Event>()
        val exceptionsSlot = slot<List<Event>>()
        coEvery {
            eventWriter.createImportedSeries(capture(seriesSlot), capture(exceptionsSlot), any())
        } answers {
            val m = seriesSlot.captured.copy(id = 500L)
            val exs = exceptionsSlot.captured.mapIndexed { i, e -> e.copy(id = 600L + i, originalEventId = 500L) }
            EventWriter.ImportedSeries(m, exs)
        }
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()
        coEvery { eventReader.getOccurrenceByExceptionEventId(any()) } returns null

        val count = coordinator.importIcsEvents(listOf(master, exception1, exception2), localCalendarId)

        assertEquals("master + 2 exceptions persisted", 3, count)
        coVerify(exactly = 1) { eventWriter.createImportedSeries(any(), any(), any()) }
        coVerify(exactly = 0) { eventWriter.createEvent(any(), any()) }
        // One fresh UID shared by the master and exceptions, never the source UID.
        assertNotEquals(sharedUid, seriesSlot.captured.uid)
        assertTrue(seriesSlot.captured.uid.endsWith("@kashcal.onekash.org"))
        assertTrue(exceptionsSlot.captured.all { it.uid == seriesSlot.captured.uid })
        // The exceptions' instance times are kept.
        assertEquals(
            setOf(master.startTs + 7 * 86400000L, master.startTs + 14 * 86400000L),
            exceptionsSlot.captured.mapNotNull { it.originalInstanceTime }.toSet()
        )
    }

    @Test
    fun `importIcsEvents imports orphan exception (no master) as standalone`() = runTest {
        // An export cut to a date window: an exception whose master fell outside the window.
        // It must still import, not be silently dropped.
        val orphan = testEvent.copy(
            id = 0L, uid = "orphan@source.ics", title = "Orphan override",
            rrule = null, originalInstanceTime = 1704067200000L + 7 * 86400000L, reminders = null
        )
        coEvery { eventWriter.createEvent(any(), any()) } returns orphan.copy(id = 700L, uid = "new@kashcal.onekash.org")
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()

        val count = coordinator.importIcsEvents(listOf(orphan), localCalendarId)

        assertEquals(1, count)
        coVerify(exactly = 0) { eventWriter.createImportedSeries(any(), any(), any()) }
        coVerify(exactly = 1) { eventWriter.createEvent(any(), any()) }
    }

    @Test
    fun `importIcsEvents imports two same-UID masters as separate events with distinct UIDs`() = runTest {
        // An export with two distinct non-exception VEVENTs sharing a UID. Both must import,
        // each with its own fresh UID.
        val uid = "dup@source.ics"
        val masterA = testEvent.copy(id = 0L, uid = uid, title = "A", rrule = null, originalInstanceTime = null, reminders = null)
        val masterB = testEvent.copy(id = 0L, uid = uid, title = "B", rrule = null, originalInstanceTime = null, reminders = null)

        val uidsSeen = mutableListOf<String>()
        coEvery { eventWriter.createEvent(any(), any()) } answers {
            val e = firstArg<Event>()
            uidsSeen += e.uid
            e.copy(id = (800L + uidsSeen.size))
        }
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()

        val count = coordinator.importIcsEvents(listOf(masterA, masterB), localCalendarId)

        assertEquals(2, count)
        coVerify(exactly = 0) { eventWriter.createImportedSeries(any(), any(), any()) }
        coVerify(exactly = 2) { eventWriter.createEvent(any(), any()) }
        assertEquals("two distinct fresh UIDs", 2, uidsSeen.toSet().size)
        assertFalse("source UID never reused", uidsSeen.contains(uid))
    }

    @Test
    fun `importIcsEvents does not form a series from a non-recurring master plus orphan exception`() = runTest {
        // A non-recurring event sharing a UID with an orphan RECURRENCE-ID isn't a series
        // (no RRULE to expand). Both import standalone.
        val uid = "notseries@source.ics"
        val plain = testEvent.copy(id = 0L, uid = uid, title = "Plain", rrule = null, originalInstanceTime = null, reminders = null)
        val orphanEx = testEvent.copy(id = 0L, uid = uid, title = "Orphan", rrule = null, originalInstanceTime = 1704067200000L + 86400000L, reminders = null)

        coEvery { eventWriter.createEvent(any(), any()) } answers { firstArg<Event>().copy(id = 900L) }
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()

        val count = coordinator.importIcsEvents(listOf(plain, orphanEx), localCalendarId)

        assertEquals(2, count)
        coVerify(exactly = 0) { eventWriter.createImportedSeries(any(), any(), any()) }
        coVerify(exactly = 2) { eventWriter.createEvent(any(), any()) }
    }

    @Test
    fun `importIcsEvents series exception with no VALARM inherits master's effective default reminders`() = runTest {
        // setup() stubs the timed default at 15 minutes. The master has no VALARM, so it
        // takes the default; an exception with no VALARM must alarm like its sibling
        // occurrences, so it inherits the master's reminders.
        val sharedUid = "series-rem@source.ics"
        val master = testEvent.copy(id = 0L, uid = sharedUid, isAllDay = false, rrule = "FREQ=DAILY;COUNT=3", reminders = null, originalInstanceTime = null)
        val exception = testEvent.copy(id = 0L, uid = sharedUid, rrule = null, originalInstanceTime = master.startTs + 86400000L, reminders = null)

        val seriesSlot = slot<Event>()
        val exceptionsSlot = slot<List<Event>>()
        coEvery {
            eventWriter.createImportedSeries(capture(seriesSlot), capture(exceptionsSlot), any())
        } answers {
            EventWriter.ImportedSeries(seriesSlot.captured.copy(id = 500L), exceptionsSlot.captured.mapIndexed { i, e -> e.copy(id = 600L + i, originalEventId = 500L) })
        }
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()
        coEvery { eventReader.getOccurrenceByExceptionEventId(any()) } returns null

        coordinator.importIcsEvents(listOf(master, exception), localCalendarId)

        assertEquals("master takes timed default", listOf("-PT15M"), seriesSlot.captured.reminders)
        assertEquals("exception inherits master effective reminders", listOf("-PT15M"), exceptionsSlot.captured[0].reminders)
    }

    @Test
    fun `importIcsEvents series exception keeps its own VALARM reminders`() = runTest {
        val sharedUid = "series-own-rem@source.ics"
        val master = testEvent.copy(id = 0L, uid = sharedUid, rrule = "FREQ=DAILY;COUNT=3", reminders = null, originalInstanceTime = null)
        val exception = testEvent.copy(id = 0L, uid = sharedUid, rrule = null, originalInstanceTime = master.startTs + 86400000L, reminders = listOf("-PT5M"))

        val exceptionsSlot = slot<List<Event>>()
        coEvery {
            eventWriter.createImportedSeries(any(), capture(exceptionsSlot), any())
        } answers {
            EventWriter.ImportedSeries(firstArg<Event>().copy(id = 500L), exceptionsSlot.captured.mapIndexed { i, e -> e.copy(id = 600L + i, originalEventId = 500L) })
        }
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()
        coEvery { eventReader.getOccurrenceByExceptionEventId(any()) } returns null

        coordinator.importIcsEvents(listOf(master, exception), localCalendarId)

        assertEquals("override keeps its own VALARM", listOf("-PT5M"), exceptionsSlot.captured[0].reminders)
    }

    // ==================== Account Reminder Cleanup Tests (v16.4.1) ====================

    @Test
    fun `cancelRemindersForAccount cancels reminders for all account calendars`() = runTest {
        // An account with two calendars.
        val testAccount = Account(
            id = 10L,
            provider = AccountProvider.ICLOUD,
            email = "test@icloud.com",
            displayName = "Test Account",
            isEnabled = true
        )
        val calendar1 = iCloudCalendar.copy(id = 20L, accountId = testAccount.id)
        val calendar2 = iCloudCalendar.copy(id = 21L, accountId = testAccount.id, displayName = "Work")

        coEvery { accountRepository.getAccountByProviderAndEmail(AccountProvider.ICLOUD, "test@icloud.com") } returns testAccount
        coEvery { eventReader.getCalendarsByAccountIdOnce(testAccount.id) } returns listOf(calendar1, calendar2)

        coordinator.cancelRemindersForAccount("test@icloud.com")

        // One batch cancel per calendar, not per event.
        coVerify { reminderScheduler.cancelRemindersForCalendar(calendar1.id) }
        coVerify { reminderScheduler.cancelRemindersForCalendar(calendar2.id) }
        coVerify(exactly = 0) { reminderScheduler.cancelRemindersForEvent(any()) }
    }

    @Test
    fun `cancelRemindersForAccount handles non-existent account gracefully`() = runTest {
        coEvery { accountRepository.getAccountByProviderAndEmail(AccountProvider.ICLOUD, "nonexistent@test.com") } returns null

        // Doesn't throw and cancels nothing.
        coordinator.cancelRemindersForAccount("nonexistent@test.com")

        coVerify(exactly = 0) { reminderScheduler.cancelRemindersForCalendar(any()) }
    }

    // ==================== Move Event Reminder Reschedule Tests (v16.4.1) ====================

    @Test
    fun `moveEventToCalendar reschedules reminders with new calendar color`() = runTest {
        // An event with reminders in the local calendar.
        val eventWithReminders = testEvent.copy(
            id = 300L,
            calendarId = localCalendarId,
            reminders = listOf("-PT15M")
        )
        val targetCalendar = iCloudCalendar.copy(color = 0xFFFF0000.toInt()) // Different color
        val movedEvent = eventWithReminders.copy(calendarId = targetCalendar.id)

        // The coordinator reads the event after the move, so this is the moved event.
        coEvery { eventReader.getEventById(eventWithReminders.id) } returns movedEvent
        coEvery { eventReader.getCalendarById(targetCalendar.id) } returns targetCalendar
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()
        coEvery { eventWriter.moveEventToCalendar(eventWithReminders.id, targetCalendar.id) } returns Unit

        coordinator.moveEventToCalendar(eventWithReminders.id, targetCalendar.id)

        coVerify { eventWriter.moveEventToCalendar(eventWithReminders.id, targetCalendar.id) }

        // Reminders are rescheduled: cancel, then schedule.
        coVerify { reminderScheduler.cancelRemindersForEvent(eventWithReminders.id) }
        coVerify { reminderScheduler.scheduleRemindersForEvent(movedEvent, any(), any()) }
    }

    @Test
    fun `moveEventToCalendar succeeds when reminder scheduling throws`() = runTest {
        val eventToMove = testEvent.copy(id = 300L, calendarId = localCalendarId)
        val movedEvent = eventToMove.copy(calendarId = iCloudCalendarId)

        coEvery { eventWriter.moveEventToCalendar(eventToMove.id, iCloudCalendarId) } returns Unit
        coEvery { eventReader.getEventById(eventToMove.id) } returns movedEvent
        coEvery { reminderScheduler.cancelRemindersForEvent(any()) } throws RuntimeException("DB locked")

        // A reminder failure must not fail the move.
        coordinator.moveEventToCalendar(eventToMove.id, iCloudCalendarId)

        coVerify { eventWriter.moveEventToCalendar(eventToMove.id, iCloudCalendarId) }
    }

    // ==================== Export Tests ====================

    @Test
    fun `getCalendarEventsForExport uses batch query for exceptions`() = runTest {
        // Two recurring masters and one non-recurring event.
        val master1 = recurringEvent.copy(id = 200L, uid = "master1@test")
        val master2 = recurringEvent.copy(id = 201L, uid = "master2@test", rrule = "FREQ=DAILY")
        val standalone = testEvent.copy(id = 202L, uid = "standalone@test", rrule = null)

        val exception1a = testEvent.copy(id = 300L, originalEventId = 200L)
        val exception1b = testEvent.copy(id = 301L, originalEventId = 200L)
        val exception2a = testEvent.copy(id = 302L, originalEventId = 201L)

        coEvery { eventReader.getAllMasterEventsForCalendar(iCloudCalendarId) } returns
            listOf(master1, master2, standalone)
        coEvery { eventReader.getExceptionsForMasters(listOf(200L, 201L)) } returns
            mapOf(
                200L to listOf(exception1a, exception1b),
                201L to listOf(exception2a)
            )

        val result = coordinator.getCalendarEventsForExport(iCloudCalendarId)

        assertEquals(3, result.size)

        // master1 has 2 exceptions
        assertEquals(master1, result[0].first)
        assertEquals(listOf(exception1a, exception1b), result[0].second)

        // master2 has 1 exception
        assertEquals(master2, result[1].first)
        assertEquals(listOf(exception2a), result[1].second)

        // standalone has no exceptions
        assertEquals(standalone, result[2].first)
        assertEquals(emptyList<Event>(), result[2].second)

        // One batch query for the recurring masters' ids, no per-master query.
        coVerify(exactly = 1) { eventReader.getExceptionsForMasters(listOf(200L, 201L)) }
        coVerify(exactly = 0) { eventReader.getExceptionsForMaster(any()) }
    }

    @Test
    fun `getCalendarEventsForExport handles no recurring events`() = runTest {
        val standalone1 = testEvent.copy(id = 200L, rrule = null)
        val standalone2 = testEvent.copy(id = 201L, rrule = null)

        coEvery { eventReader.getAllMasterEventsForCalendar(localCalendarId) } returns
            listOf(standalone1, standalone2)

        val result = coordinator.getCalendarEventsForExport(localCalendarId)

        assertEquals(2, result.size)
        assertEquals(emptyList<Event>(), result[0].second)
        assertEquals(emptyList<Event>(), result[1].second)

        // No recurring events, so no exception query.
        coVerify(exactly = 0) { eventReader.getExceptionsForMasters(any()) }
        coVerify(exactly = 0) { eventReader.getExceptionsForMaster(any()) }
    }

    // ==================== RSVP write path ==========================

    private val rsvpAccount = Account(
        id = 7L,
        provider = AccountProvider.CALDAV,
        email = "self@example.test",
        calendarUserAddresses = listOf("mailto:self@example.test")
    )
    private val rsvpCalendar = Calendar(
        id = 9L,
        accountId = rsvpAccount.id,
        caldavUrl = "https://example.com/cal/",
        displayName = "Work",
        color = -1
    )
    private val rsvpEvent = Event(
        id = 200L,
        uid = "rsvp-uid",
        calendarId = rsvpCalendar.id,
        title = "Quarterly review",
        startTs = 1704067200000L,
        endTs = 1704070800000L,
        dtstamp = System.currentTimeMillis(),
        syncStatus = SyncStatus.SYNCED
    )

    @Test
    fun `replyRsvp delegates to writer with canonical account and triggers sync`() = runTest {
        coEvery { eventReader.getEventById(rsvpEvent.id) } returns rsvpEvent
        coEvery { eventReader.getCalendarById(rsvpCalendar.id) } returns rsvpCalendar
        coEvery { accountRepository.getAccountById(rsvpAccount.id) } returns rsvpAccount
        coEvery { eventWriter.replyRsvp(rsvpEvent.id, rsvpAccount, "ACCEPTED") } returns true

        val ok = coordinator.replyRsvp(rsvpEvent.id, "ACCEPTED")

        assertTrue(ok)
        coVerify { eventWriter.replyRsvp(rsvpEvent.id, rsvpAccount, "ACCEPTED") }
        // replyRsvp always requests an expedited sync after a successful write.
        verify { syncScheduler.requestExpeditedSync(false) }
    }

    @Test
    fun `replyRsvp lowercase input is forwarded as-is and writer canonicalizes`() = runTest {
        // A caller may pass lowercase; the writer canonicalizes, so the coordinator forwards
        // the value exactly as supplied.
        coEvery { eventReader.getEventById(rsvpEvent.id) } returns rsvpEvent
        coEvery { eventReader.getCalendarById(rsvpCalendar.id) } returns rsvpCalendar
        coEvery { accountRepository.getAccountById(rsvpAccount.id) } returns rsvpAccount
        coEvery { eventWriter.replyRsvp(rsvpEvent.id, rsvpAccount, "accepted") } returns true

        val ok = coordinator.replyRsvp(rsvpEvent.id, "accepted")

        assertTrue(ok)
        coVerify { eventWriter.replyRsvp(rsvpEvent.id, rsvpAccount, "accepted") }
    }

    @Test
    fun `replyRsvp returns false when event not found`() = runTest {
        coEvery { eventReader.getEventById(404L) } returns null

        val ok = coordinator.replyRsvp(404L, "ACCEPTED")

        assertFalse(ok)
        coVerify(exactly = 0) { eventWriter.replyRsvp(any(), any(), any()) }
    }

    @Test
    fun `replyRsvp returns false when account not resolvable`() = runTest {
        coEvery { eventReader.getEventById(rsvpEvent.id) } returns rsvpEvent
        coEvery { eventReader.getCalendarById(rsvpCalendar.id) } returns rsvpCalendar
        coEvery { accountRepository.getAccountById(rsvpAccount.id) } returns null

        val ok = coordinator.replyRsvp(rsvpEvent.id, "ACCEPTED")

        assertFalse(ok)
        coVerify(exactly = 0) { eventWriter.replyRsvp(any(), any(), any()) }
    }

    @Test
    fun `replyRsvp returns false when writer cannot match self attendee`() = runTest {
        coEvery { eventReader.getEventById(rsvpEvent.id) } returns rsvpEvent
        coEvery { eventReader.getCalendarById(rsvpCalendar.id) } returns rsvpCalendar
        coEvery { accountRepository.getAccountById(rsvpAccount.id) } returns rsvpAccount
        coEvery { eventWriter.replyRsvp(rsvpEvent.id, rsvpAccount, "ACCEPTED") } returns false

        val ok = coordinator.replyRsvp(rsvpEvent.id, "ACCEPTED")

        assertFalse(ok)
        // No successful local write, so no sync.
        verify(exactly = 0) { syncScheduler.requestExpeditedSync(any()) }
    }

    // ---- reminder hooks on RSVP write ----

    @Test
    fun `replyRsvp DECLINED cancels alarms`() = runTest {
        coEvery { eventReader.getEventById(rsvpEvent.id) } returns rsvpEvent
        coEvery { eventReader.getCalendarById(rsvpCalendar.id) } returns rsvpCalendar
        coEvery { accountRepository.getAccountById(rsvpAccount.id) } returns rsvpAccount
        coEvery { eventWriter.replyRsvp(rsvpEvent.id, rsvpAccount, "DECLINED") } returns true

        coordinator.replyRsvp(rsvpEvent.id, "DECLINED")

        coVerify { reminderScheduler.cancelRemindersForEvent(rsvpEvent.id) }
        // A decline has nothing to schedule.
        coVerify(exactly = 0) { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) }
    }

    @Test
    fun `replyRsvp DECLINED with whitespace and case variation still cancels`() = runTest {
        // The coordinator compares status.trim().uppercase() to DECLINED.
        coEvery { eventReader.getEventById(rsvpEvent.id) } returns rsvpEvent
        coEvery { eventReader.getCalendarById(rsvpCalendar.id) } returns rsvpCalendar
        coEvery { accountRepository.getAccountById(rsvpAccount.id) } returns rsvpAccount
        coEvery { eventWriter.replyRsvp(rsvpEvent.id, rsvpAccount, " Declined ") } returns true

        coordinator.replyRsvp(rsvpEvent.id, " Declined ")

        coVerify { reminderScheduler.cancelRemindersForEvent(rsvpEvent.id) }
    }

    @Test
    fun `replyRsvp ACCEPTED reschedules alarms via cancel + schedule`() = runTest {
        coEvery { eventReader.getEventById(rsvpEvent.id) } returns rsvpEvent
        coEvery { eventReader.getCalendarById(rsvpCalendar.id) } returns rsvpCalendar
        coEvery { accountRepository.getAccountById(rsvpAccount.id) } returns rsvpAccount
        coEvery { eventWriter.replyRsvp(rsvpEvent.id, rsvpAccount, "ACCEPTED") } returns true

        coordinator.replyRsvp(rsvpEvent.id, "ACCEPTED")

        // rescheduleRemindersForEvent cancels, then schedules.
        coVerify { reminderScheduler.cancelRemindersForEvent(rsvpEvent.id) }
        // rsvpEvent has no reminders, so scheduling returns before reaching the scheduler;
        // the cancel alone shows the reschedule path ran.
    }

    @Test
    fun `replyRsvp ACCEPTED with configured reminder arms scheduleRemindersForEvent`() = runTest {
        // With reminders configured, an accept reaches the alarm scheduler, not only the
        // cancel side of rescheduleRemindersForEvent.
        val eventWithReminder = rsvpEvent.copy(reminders = listOf("-PT15M"))
        val occurrence = Occurrence(
            id = 1L,
            eventId = eventWithReminder.id,
            calendarId = rsvpCalendar.id,
            startTs = eventWithReminder.startTs,
            endTs = eventWithReminder.endTs,
            startDay = 20240101,
            endDay = 20240101
        )
        coEvery { eventReader.getEventById(eventWithReminder.id) } returns eventWithReminder
        coEvery { eventReader.getCalendarById(rsvpCalendar.id) } returns rsvpCalendar
        coEvery { accountRepository.getAccountById(rsvpAccount.id) } returns rsvpAccount
        coEvery {
            eventReader.getOccurrencesForEventInScheduleWindow(eventWithReminder.id, ReminderScheduler.OCCURRENCE_LOOKAHEAD_DAYS)
        } returns listOf(occurrence)
        coEvery { eventWriter.replyRsvp(eventWithReminder.id, rsvpAccount, "ACCEPTED") } returns true

        coordinator.replyRsvp(eventWithReminder.id, "ACCEPTED")

        coVerify { reminderScheduler.cancelRemindersForEvent(eventWithReminder.id) }
        coVerify(exactly = 1) {
            reminderScheduler.scheduleRemindersForEvent(
                event = eventWithReminder,
                occurrences = listOf(occurrence),
                calendarColor = rsvpCalendar.color
            )
        }
    }

    @Test
    fun `replyRsvp TENTATIVE reschedules alarms`() = runTest {
        coEvery { eventReader.getEventById(rsvpEvent.id) } returns rsvpEvent
        coEvery { eventReader.getCalendarById(rsvpCalendar.id) } returns rsvpCalendar
        coEvery { accountRepository.getAccountById(rsvpAccount.id) } returns rsvpAccount
        coEvery { eventWriter.replyRsvp(rsvpEvent.id, rsvpAccount, "TENTATIVE") } returns true

        coordinator.replyRsvp(rsvpEvent.id, "TENTATIVE")

        coVerify { reminderScheduler.cancelRemindersForEvent(rsvpEvent.id) }
    }

    @Test
    fun `replyRsvp writer false does not touch reminders`() = runTest {
        coEvery { eventReader.getEventById(rsvpEvent.id) } returns rsvpEvent
        coEvery { eventReader.getCalendarById(rsvpCalendar.id) } returns rsvpCalendar
        coEvery { accountRepository.getAccountById(rsvpAccount.id) } returns rsvpAccount
        coEvery { eventWriter.replyRsvp(rsvpEvent.id, rsvpAccount, "DECLINED") } returns false

        coordinator.replyRsvp(rsvpEvent.id, "DECLINED")

        coVerify(exactly = 0) { reminderScheduler.cancelRemindersForEvent(any()) }
        coVerify(exactly = 0) { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) }
    }

    @Test
    fun `replyRsvp succeeds when reminderScheduler throws - push and widget still fire`() = runTest {
        coEvery { eventReader.getEventById(rsvpEvent.id) } returns rsvpEvent
        coEvery { eventReader.getCalendarById(rsvpCalendar.id) } returns rsvpCalendar
        coEvery { accountRepository.getAccountById(rsvpAccount.id) } returns rsvpAccount
        coEvery { eventWriter.replyRsvp(rsvpEvent.id, rsvpAccount, "DECLINED") } returns true
        coEvery { reminderScheduler.cancelRemindersForEvent(any()) } throws RuntimeException("DB locked")

        val ok = coordinator.replyRsvp(rsvpEvent.id, "DECLINED")

        assertTrue(ok)
        // The expedited sync is still requested.
        verify { syncScheduler.requestExpeditedSync(false) }
    }

    // ========== attendee forwarding + ORGANIZER resolution ==========

    private fun attendee(addr: String) =
        Attendee(eventId = 0, address = addr, displayName = addr.substringBefore('@'), partstat = "NEEDS-ACTION")

    @Test
    fun `createEvent forwards attendees and resolves organizer from account address-set`() = runTest {
        val eventSlot = slot<Event>()
        val attSlot = slot<List<Attendee>>()
        coEvery { eventWriter.createEvent(capture(eventSlot), any(), capture(attSlot)) } answers { firstArg<Event>() }
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()
        coEvery { accountRepository.getAccountById(2L) } returns Account(
            id = 2L, provider = AccountProvider.ICLOUD, email = "alice@icloud.com",
            calendarUserAddresses = listOf("mailto:alice@icloud.com", "/123/principal/")
        )

        coordinator.createEvent(
            testEvent.copy(id = 0L, calendarId = iCloudCalendarId, organizerEmail = null),
            iCloudCalendarId,
            attendees = listOf(attendee("bob@example.test"))
        )

        // Stored without mailto:. The generator prepends mailto: on emit, so a stored one
        // would be doubled on the wire.
        assertEquals("alice@icloud.com", eventSlot.captured.organizerEmail)
        assertFalse(eventSlot.captured.organizerEmail!!.startsWith("mailto:"))
        assertEquals(listOf("bob@example.test"), attSlot.captured.map { it.address })
    }

    @Test
    fun `createEvent with no attendees does not force an organizer`() = runTest {
        val eventSlot = slot<Event>()
        coEvery { eventWriter.createEvent(capture(eventSlot), any(), any()) } answers { firstArg<Event>() }
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()

        coordinator.createEvent(testEvent.copy(id = 0L, calendarId = iCloudCalendarId, organizerEmail = null), iCloudCalendarId)

        assertEquals(null, eventSlot.captured.organizerEmail)
    }

    @Test
    fun `organizer degrades to null when account has no usable address`() = runTest {
        val eventSlot = slot<Event>()
        coEvery { eventWriter.createEvent(capture(eventSlot), any(), any()) } answers { firstArg<Event>() }
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()
        coEvery { accountRepository.getAccountById(2L) } returns Account(
            // The login isn't email-shaped.
            id = 2L, provider = AccountProvider.CALDAV, email = "nextcloud-login",
            calendarUserAddresses = emptyList()
        )

        coordinator.createEvent(
            testEvent.copy(id = 0L, calendarId = iCloudCalendarId, organizerEmail = null),
            iCloudCalendarId,
            attendees = listOf(attendee("bob@example.test"))
        )

        assertEquals(null, eventSlot.captured.organizerEmail)
    }

    @Test
    fun `organizer prefers the mailto over an email-shaped principal path listed first`() = runTest {
        // Some servers (Cyrus/Fastmail, older Nextcloud) return the principal href before the
        // mailto in calendar-user-address-set. When the login is an email the principal path
        // embeds an '@', so a permissive email-shape check would pick the DAV path as
        // ORGANIZER; the server rejects it (SCHEDULE-STATUS 3.7 "Invalid Calendar User") and
        // no invite is delivered.
        val eventSlot = slot<Event>()
        coEvery { eventWriter.createEvent(capture(eventSlot), any(), any()) } answers { firstArg<Event>() }
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()
        coEvery { accountRepository.getAccountById(2L) } returns Account(
            id = 2L, provider = AccountProvider.CALDAV, email = "organizer@example.com",
            calendarUserAddresses = listOf(
                "/remote.php/dav/principals/users/organizer@example.com/",
                "mailto:organizer@example.com"
            )
        )

        coordinator.createEvent(
            testEvent.copy(id = 0L, calendarId = iCloudCalendarId, organizerEmail = null),
            iCloudCalendarId,
            attendees = listOf(attendee("bob@example.test"))
        )

        assertEquals("organizer@example.com", eventSlot.captured.organizerEmail)
        assertFalse(eventSlot.captured.organizerEmail!!.startsWith("/"))
    }

    @Test
    fun `organizer degrades to null when address-set is principal-path only`() = runTest {
        // For example Radicale, or Nextcloud without an email: the generator's mailto: prefix
        // would mangle a non-mailto ORGANIZER, so none is set.
        val eventSlot = slot<Event>()
        coEvery { eventWriter.createEvent(capture(eventSlot), any(), any()) } answers { firstArg<Event>() }
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(any(), any()) } returns emptyList()
        coEvery { accountRepository.getAccountById(2L) } returns Account(
            id = 2L, provider = AccountProvider.CALDAV, email = "alice",
            calendarUserAddresses = listOf("/123/principal/", "urn:uuid:abc")
        )

        coordinator.createEvent(
            testEvent.copy(id = 0L, calendarId = iCloudCalendarId, organizerEmail = null),
            iCloudCalendarId,
            attendees = listOf(attendee("bob@example.test"))
        )

        assertEquals(null, eventSlot.captured.organizerEmail)
    }

    @Test
    fun `setting organizer on update does not bump SEQUENCE`() = runTest {
        // SequenceBumper doesn't compare organizerEmail, so resolving the organizer doesn't
        // re-notify attendees.
        val eventSlot = slot<Event>()
        coEvery { eventWriter.updateEvent(capture(eventSlot), any(), any()) } answers { firstArg<Event>() }
        coEvery { accountRepository.getAccountById(2L) } returns Account(
            id = 2L, provider = AccountProvider.ICLOUD, email = "alice@icloud.com",
            calendarUserAddresses = listOf("mailto:alice@icloud.com")
        )

        val existing = testEvent.copy(calendarId = iCloudCalendarId, sequence = 4, organizerEmail = null)
        coordinator.updateEvent(existing, attendees = listOf(attendee("bob@example.test")))

        assertEquals("organizer resolved (bare)", "alice@icloud.com", eventSlot.captured.organizerEmail)
        assertEquals("sequence unchanged by organizer-set", 4, eventSlot.captured.sequence)
    }

    // ===== Reminder scheduling failures never fail a completed save =====
    //
    // Once the app holds the platform's per-app maximum of pending alarms,
    // AlarmManager refuses every further alarm with IllegalStateException.
    // The event is already written by then, so reporting failure would make
    // the user retry and create a duplicate. Cancellation still propagates.

    private val alarmLimitRefusal =
        IllegalStateException("Maximum limit of concurrent alarms 500 reached")

    @Test
    fun `createEvent returns the saved event when reminder scheduling throws`() = runTest {
        val saved = testEvent.copy(reminders = listOf("-PT15M"))
        coEvery { eventWriter.createEvent(any(), any(), any()) } returns saved
        coEvery { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) } throws alarmLimitRefusal

        val result = coordinator.createEvent(saved, localCalendarId)

        assertEquals(saved, result)
        coVerify(exactly = 1) { eventWriter.createEvent(any(), any(), any()) }
        coVerify(exactly = 1) { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) }
        coVerify { widgetUpdateManager.updateAllWidgets() }
    }

    @Test
    fun `createEvent propagates CancellationException from reminder scheduling`() = runTest {
        val saved = testEvent.copy(reminders = listOf("-PT15M"))
        coEvery { eventWriter.createEvent(any(), any(), any()) } returns saved
        coEvery {
            reminderScheduler.scheduleRemindersForEvent(any(), any(), any())
        } throws CancellationException("scope cancelled")

        val outcome = runCatching { coordinator.createEvent(saved, localCalendarId) }

        assertTrue(outcome.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `editSingleOccurrence returns the exception event when reminder scheduling throws`() = runTest {
        val occurrenceTime = 1704672000000L
        val exceptionEvent = recurringEvent.copy(
            id = 210L,
            rrule = null,
            originalEventId = recurringEvent.id,
            originalInstanceTime = occurrenceTime,
            reminders = listOf("-PT15M")
        )
        coEvery { eventReader.getEventById(recurringEvent.id) } returns recurringEvent
        coEvery { eventWriter.editSingleOccurrence(any(), any(), any(), any()) } returns exceptionEvent
        coEvery { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) } throws alarmLimitRefusal

        val result = coordinator.editSingleOccurrence(
            masterEventId = recurringEvent.id,
            occurrenceTimeMs = occurrenceTime,
            changes = { it.copy(title = "Moved") }
        )

        assertEquals(exceptionEvent, result)
        coVerify(exactly = 1) { reminderScheduler.scheduleRemindersForEvent(exceptionEvent, any(), any()) }
    }

    @Test
    fun `editThisAndFuture returns the new series when reminder scheduling throws`() = runTest {
        val splitTime = 1704672000000L
        val newSeries = recurringEvent.copy(
            id = 220L,
            startTs = splitTime,
            uid = "split-alarm-cap@kashcal.test",
            reminders = listOf("-PT15M")
        )
        coEvery { eventReader.getEventById(recurringEvent.id) } returns recurringEvent
        coEvery { eventWriter.splitSeries(any(), any(), any(), any(), any()) } returns newSeries
        coEvery { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) } throws alarmLimitRefusal

        val result = coordinator.editThisAndFuture(
            masterEventId = recurringEvent.id,
            splitTimeMs = splitTime,
            changes = { it.copy(title = "New Title") }
        )

        assertEquals(newSeries, result)
        coVerify(exactly = 1) { reminderScheduler.scheduleRemindersForEvent(newSeries, any(), any()) }
    }

    @Test
    fun `importIcsEvents counts a saved standalone event when reminder scheduling throws`() = runTest {
        val source = testEvent.copy(id = 0L, uid = "single@source.ics", reminders = listOf("-PT15M"))
        coEvery { eventWriter.createEvent(any(), any()) } answers { firstArg<Event>().copy(id = 310L) }
        coEvery { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) } throws alarmLimitRefusal

        val count = coordinator.importIcsEvents(listOf(source), localCalendarId)

        assertEquals(1, count)
        coVerify(exactly = 1) { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) }
    }

    @Test
    fun `importIcsEvents counts a saved series when reminder scheduling throws`() = runTest {
        val sharedUid = "series-alarm-cap@source.ics"
        val master = testEvent.copy(
            id = 0L, uid = sharedUid, rrule = "FREQ=WEEKLY;COUNT=5",
            reminders = listOf("-PT15M"), originalInstanceTime = null
        )
        val exception = testEvent.copy(
            id = 0L, uid = sharedUid, rrule = null,
            originalInstanceTime = master.startTs + 7 * 86400000L, reminders = listOf("-PT15M")
        )
        val seriesSlot = slot<Event>()
        val exceptionsSlot = slot<List<Event>>()
        coEvery {
            eventWriter.createImportedSeries(capture(seriesSlot), capture(exceptionsSlot), any())
        } answers {
            EventWriter.ImportedSeries(
                seriesSlot.captured.copy(id = 700L),
                exceptionsSlot.captured.map { it.copy(id = 701L, originalEventId = 700L) }
            )
        }
        coEvery { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) } throws alarmLimitRefusal

        val count = coordinator.importIcsEvents(listOf(master, exception), localCalendarId)

        assertEquals("master + exception persisted", 2, count)
        coVerify(exactly = 2) { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) }
    }
}
