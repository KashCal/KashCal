package org.onekash.kashcal.domain.coordinator

import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.contacts.ContactBirthdayRepository
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.ics.IcsSubscriptionRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for immediate push sync functionality in EventCoordinator.
 *
 * Tests verify that:
 * - CalDAV calendar operations trigger expedited sync
 * - Local calendar operations do NOT trigger sync
 * - All 8 write operations correctly call sync
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class EventCoordinatorImmediatePushTest {

    @MockK
    private lateinit var eventWriter: EventWriter

    @MockK
    private lateinit var eventReader: EventReader

    @MockK
    private lateinit var occurrenceGenerator: OccurrenceGenerator

    @MockK
    private lateinit var localCalendarInitializer: LocalCalendarInitializer

    @MockK
    private lateinit var icsSubscriptionRepository: IcsSubscriptionRepository

    @MockK
    private lateinit var contactBirthdayRepository: ContactBirthdayRepository

    @MockK
    private lateinit var syncScheduler: SyncScheduler

    @MockK
    private lateinit var reminderScheduler: ReminderScheduler

    @MockK
    private lateinit var widgetUpdateManager: org.onekash.kashcal.widget.WidgetUpdateManager

    private lateinit var coordinator: EventCoordinator

    // Test fixtures
    private val caldavCalendar = Calendar(
        id = 1,
        accountId = 1,
        caldavUrl = "https://caldav.icloud.com/calendar/",
        displayName = "iCloud Calendar",
        color = 0xFF0000FF.toInt()
    )

    private val localCalendar = Calendar(
        id = 2,
        accountId = 2,
        caldavUrl = "local://default",
        displayName = "Local",
        color = 0xFF4CAF50.toInt()
    )

    private fun createTestEvent(
        id: Long = 0,
        calendarId: Long = 1,
        title: String = "Test Event",
        rrule: String? = null
    ) = Event(
        id = id,
        uid = if (id > 0) "test-uid-$id" else "",
        calendarId = calendarId,
        title = title,
        startTs = System.currentTimeMillis() + 86400000,
        endTs = System.currentTimeMillis() + 86400000 + 3600000,
        isAllDay = false,
        rrule = rrule,
        dtstamp = System.currentTimeMillis(),
        syncStatus = SyncStatus.SYNCED
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxed = true)
        coordinator = EventCoordinator(
            eventWriter,
            eventReader,
            occurrenceGenerator,
            localCalendarInitializer,
            icsSubscriptionRepository,
            contactBirthdayRepository,
            syncScheduler,
            reminderScheduler,
            widgetUpdateManager
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== createEvent Tests ====================

    @Test
    fun `createEvent on CalDAV calendar triggers expedited sync`() = runTest {
        // Given
        val event = createTestEvent(calendarId = 1)
        val createdEvent = event.copy(id = 1)

        coEvery { localCalendarInitializer.getLocalCalendarId() } returns 2
        coEvery { eventReader.getCalendarById(1) } returns caldavCalendar
        coEvery { localCalendarInitializer.isLocalCalendar(caldavCalendar) } returns false
        coEvery { eventWriter.createEvent(any(), false) } returns createdEvent

        // When
        coordinator.createEvent(event, calendarId = 1)

        // Then
        verify(exactly = 1) { syncScheduler.requestExpeditedSync(forceFullSync = false) }
    }

    @Test
    fun `createEvent on local calendar does NOT trigger sync`() = runTest {
        // Given
        val event = createTestEvent(calendarId = 2)
        val createdEvent = event.copy(id = 1)

        coEvery { localCalendarInitializer.getLocalCalendarId() } returns 2
        coEvery { eventReader.getCalendarById(2) } returns localCalendar
        coEvery { localCalendarInitializer.isLocalCalendar(localCalendar) } returns true
        coEvery { eventWriter.createEvent(any(), true) } returns createdEvent

        // When
        coordinator.createEvent(event, calendarId = 2)

        // Then
        verify(exactly = 0) { syncScheduler.requestExpeditedSync(any()) }
    }

    @Test
    fun `createEvent without calendarId uses local calendar and does NOT trigger sync`() = runTest {
        // Given
        val event = createTestEvent(calendarId = 0)
        val createdEvent = event.copy(id = 1, calendarId = 2)

        coEvery { localCalendarInitializer.getLocalCalendarId() } returns 2
        coEvery { eventReader.getCalendarById(2) } returns localCalendar
        coEvery { localCalendarInitializer.isLocalCalendar(localCalendar) } returns true
        coEvery { eventWriter.createEvent(any(), true) } returns createdEvent

        // When
        coordinator.createEvent(event) // No calendarId specified

        // Then
        verify(exactly = 0) { syncScheduler.requestExpeditedSync(any()) }
    }

    // ==================== updateEvent Tests ====================

    @Test
    fun `updateEvent on CalDAV calendar triggers expedited sync`() = runTest {
        // Given
        val event = createTestEvent(id = 1, calendarId = 1)

        coEvery { eventReader.getCalendarById(1) } returns caldavCalendar
        coEvery { localCalendarInitializer.isLocalCalendar(caldavCalendar) } returns false
        coEvery { eventWriter.updateEvent(any(), false) } returns event

        // When
        coordinator.updateEvent(event)

        // Then
        verify(exactly = 1) { syncScheduler.requestExpeditedSync(forceFullSync = false) }
    }

    @Test
    fun `updateEvent on local calendar does NOT trigger sync`() = runTest {
        // Given
        val event = createTestEvent(id = 1, calendarId = 2)

        coEvery { eventReader.getCalendarById(2) } returns localCalendar
        coEvery { localCalendarInitializer.isLocalCalendar(localCalendar) } returns true
        coEvery { eventWriter.updateEvent(any(), true) } returns event

        // When
        coordinator.updateEvent(event)

        // Then
        verify(exactly = 0) { syncScheduler.requestExpeditedSync(any()) }
    }

    // ==================== deleteEvent Tests ====================

    @Test
    fun `deleteEvent on CalDAV calendar triggers expedited sync`() = runTest {
        // Given
        val event = createTestEvent(id = 1, calendarId = 1)

        coEvery { eventReader.getEventById(1) } returns event
        coEvery { eventReader.getCalendarById(1) } returns caldavCalendar
        coEvery { localCalendarInitializer.isLocalCalendar(caldavCalendar) } returns false
        coJustRun { eventWriter.deleteEvent(1, false) }

        // When
        coordinator.deleteEvent(1)

        // Then
        verify(exactly = 1) { syncScheduler.requestExpeditedSync(forceFullSync = false) }
    }

    @Test
    fun `deleteEvent on local calendar does NOT trigger sync`() = runTest {
        // Given
        val event = createTestEvent(id = 1, calendarId = 2)

        coEvery { eventReader.getEventById(1) } returns event
        coEvery { eventReader.getCalendarById(2) } returns localCalendar
        coEvery { localCalendarInitializer.isLocalCalendar(localCalendar) } returns true
        coJustRun { eventWriter.deleteEvent(1, true) }

        // When
        coordinator.deleteEvent(1)

        // Then
        verify(exactly = 0) { syncScheduler.requestExpeditedSync(any()) }
    }

    // ==================== editSingleOccurrence Tests ====================

    @Test
    fun `editSingleOccurrence on CalDAV calendar triggers expedited sync`() = runTest {
        // Given
        val masterEvent = createTestEvent(id = 1, calendarId = 1, rrule = "FREQ=DAILY")
        val exceptionEvent = createTestEvent(id = 2, calendarId = 1)
        val occurrenceTimeMs = masterEvent.startTs

        coEvery { eventReader.getEventById(1) } returns masterEvent
        coEvery { eventReader.getCalendarById(1) } returns caldavCalendar
        coEvery { localCalendarInitializer.isLocalCalendar(caldavCalendar) } returns false
        coEvery { eventWriter.editSingleOccurrence(any(), any(), any(), false) } returns exceptionEvent

        // When
        coordinator.editSingleOccurrence(
            masterEventId = 1,
            occurrenceTimeMs = occurrenceTimeMs,
            changes = { it.copy(title = "Modified") }
        )

        // Then
        verify(exactly = 1) { syncScheduler.requestExpeditedSync(forceFullSync = false) }
    }

    @Test
    fun `editSingleOccurrence on local calendar does NOT trigger sync`() = runTest {
        // Given
        val masterEvent = createTestEvent(id = 1, calendarId = 2, rrule = "FREQ=DAILY")
        val exceptionEvent = createTestEvent(id = 2, calendarId = 2)
        val occurrenceTimeMs = masterEvent.startTs

        coEvery { eventReader.getEventById(1) } returns masterEvent
        coEvery { eventReader.getCalendarById(2) } returns localCalendar
        coEvery { localCalendarInitializer.isLocalCalendar(localCalendar) } returns true
        coEvery { eventWriter.editSingleOccurrence(any(), any(), any(), true) } returns exceptionEvent

        // When
        coordinator.editSingleOccurrence(
            masterEventId = 1,
            occurrenceTimeMs = occurrenceTimeMs,
            changes = { it.copy(title = "Modified") }
        )

        // Then
        verify(exactly = 0) { syncScheduler.requestExpeditedSync(any()) }
    }

    // ==================== editThisAndFuture Tests ====================

    @Test
    fun `editThisAndFuture on CalDAV calendar triggers expedited sync`() = runTest {
        // Given
        val masterEvent = createTestEvent(id = 1, calendarId = 1, rrule = "FREQ=DAILY")
        val newSeriesEvent = createTestEvent(id = 2, calendarId = 1, rrule = "FREQ=DAILY")
        val splitTimeMs = masterEvent.startTs + 86400000

        coEvery { eventReader.getEventById(1) } returns masterEvent
        coEvery { eventReader.getCalendarById(1) } returns caldavCalendar
        coEvery { localCalendarInitializer.isLocalCalendar(caldavCalendar) } returns false
        coEvery { eventWriter.splitSeries(any(), any(), any(), false) } returns newSeriesEvent

        // When
        coordinator.editThisAndFuture(
            masterEventId = 1,
            splitTimeMs = splitTimeMs,
            changes = { it.copy(title = "Future Series") }
        )

        // Then
        verify(exactly = 1) { syncScheduler.requestExpeditedSync(forceFullSync = false) }
    }

    // ==================== deleteSingleOccurrence Tests ====================

    @Test
    fun `deleteSingleOccurrence on CalDAV calendar triggers expedited sync`() = runTest {
        // Given
        val masterEvent = createTestEvent(id = 1, calendarId = 1, rrule = "FREQ=DAILY")
        val occurrenceTimeMs = masterEvent.startTs + 86400000

        coEvery { eventReader.getEventById(1) } returns masterEvent
        coEvery { eventReader.getCalendarById(1) } returns caldavCalendar
        coEvery { localCalendarInitializer.isLocalCalendar(caldavCalendar) } returns false
        coJustRun { eventWriter.deleteSingleOccurrence(1, occurrenceTimeMs, false) }

        // When
        coordinator.deleteSingleOccurrence(
            masterEventId = 1,
            occurrenceTimeMs = occurrenceTimeMs
        )

        // Then
        verify(exactly = 1) { syncScheduler.requestExpeditedSync(forceFullSync = false) }
    }

    // ==================== deleteThisAndFuture Tests ====================

    @Test
    fun `deleteThisAndFuture on CalDAV calendar triggers expedited sync`() = runTest {
        // Given
        val masterEvent = createTestEvent(id = 1, calendarId = 1, rrule = "FREQ=DAILY")
        val fromTimeMs = masterEvent.startTs + 86400000

        coEvery { eventReader.getEventById(1) } returns masterEvent
        coEvery { eventReader.getCalendarById(1) } returns caldavCalendar
        coEvery { localCalendarInitializer.isLocalCalendar(caldavCalendar) } returns false
        coJustRun { eventWriter.deleteThisAndFuture(1, fromTimeMs, false) }

        // When
        coordinator.deleteThisAndFuture(
            masterEventId = 1,
            fromTimeMs = fromTimeMs
        )

        // Then
        verify(exactly = 1) { syncScheduler.requestExpeditedSync(forceFullSync = false) }
    }

    // ==================== moveEventToCalendar Tests ====================

    @Test
    fun `moveEventToCalendar to CalDAV calendar triggers expedited sync`() = runTest {
        // Given
        coEvery { eventReader.getEventById(1) } returns createTestEvent(1)  // v14.2.23: exception guard check
        coEvery { eventReader.getCalendarById(1) } returns caldavCalendar
        coEvery { localCalendarInitializer.isLocalCalendar(caldavCalendar) } returns false
        coJustRun { eventWriter.moveEventToCalendar(1, 1, false) }

        // When
        coordinator.moveEventToCalendar(eventId = 1, newCalendarId = 1)

        // Then
        verify(exactly = 1) { syncScheduler.requestExpeditedSync(forceFullSync = false) }
    }

    @Test
    fun `moveEventToCalendar to local calendar does NOT trigger sync`() = runTest {
        // Given
        coEvery { eventReader.getEventById(1) } returns createTestEvent(1)  // v14.2.23: exception guard check
        coEvery { eventReader.getCalendarById(2) } returns localCalendar
        coEvery { localCalendarInitializer.isLocalCalendar(localCalendar) } returns true
        coJustRun { eventWriter.moveEventToCalendar(1, 2, true) }

        // When
        coordinator.moveEventToCalendar(eventId = 1, newCalendarId = 2)

        // Then
        verify(exactly = 0) { syncScheduler.requestExpeditedSync(any()) }
    }
}
