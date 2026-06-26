package org.onekash.kashcal.ui.viewmodels

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.error.CalendarError
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus
import org.onekash.kashcal.ui.components.EventFormState
import java.time.ZoneId

/**
 * Tests for HomeViewModel device calendar write operations.
 *
 * TDD tests verifying:
 * - Create routes to CalendarProviderRepository
 * - Delete routes to CalendarProviderRepository
 * - Errors surface to UI state via showError()
 * - Exception creation for recurring events
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelDeviceCalendarWriteTest {

    private val testDispatcher = StandardTestDispatcher()

    // Mocks
    private lateinit var eventCoordinator: EventCoordinator
    private lateinit var eventReader: EventReader
    private lateinit var displayEventRepository: DisplayEventRepository
    private lateinit var dataStore: KashCalDataStore
    private lateinit var accountRepository: AccountRepository
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var networkMonitor: NetworkMonitor

    // Fake for device calendar operations
    private lateinit var fakeCalendarProviderRepository: FakeCalendarProviderRepository

    private lateinit var networkStateFlow: MutableStateFlow<Boolean>
    private lateinit var syncStatusFlow: MutableStateFlow<SyncStatus>
    private lateinit var bannerFlagFlow: MutableStateFlow<Boolean>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        eventCoordinator = mockk(relaxed = true)
        eventReader = mockk(relaxed = true)
        displayEventRepository = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)

        fakeCalendarProviderRepository = FakeCalendarProviderRepository()

        networkStateFlow = MutableStateFlow(true)
        every { networkMonitor.isOnline } returns networkStateFlow
        every { networkMonitor.isMetered } returns MutableStateFlow(false)

        syncStatusFlow = MutableStateFlow(SyncStatus.Idle)
        every { syncScheduler.observeImmediateSyncStatus() } returns syncStatusFlow
        every { syncScheduler.lastSyncChanges } returns MutableStateFlow(emptyList())

        bannerFlagFlow = MutableStateFlow(false)
        every { syncScheduler.showBannerForSync } returns bannerFlagFlow
        every { syncScheduler.setShowBannerForSync(any()) } answers { bannerFlagFlow.value = firstArg() }
        every { syncScheduler.resetBannerFlag() } answers { bannerFlagFlow.value = false }

        // DataStore defaults
        coEvery { dataStore.defaultCalendarId } returns MutableStateFlow(null)
        coEvery { dataStore.defaultReminderMinutes } returns MutableStateFlow(15)
        coEvery { dataStore.defaultAllDayReminder } returns MutableStateFlow(1440)
        coEvery { dataStore.defaultEventDuration } returns MutableStateFlow(20)
        coEvery { dataStore.timeFormat } returns MutableStateFlow("system")
        coEvery { dataStore.showEventEmojis } returns MutableStateFlow(true)
        coEvery { dataStore.onboardingDismissed } returns MutableStateFlow(true)

        // Event coordinator / reader defaults
        every { eventCoordinator.getAllCalendars() } returns MutableStateFlow(emptyList())
        every { eventReader.getVisibleOccurrencesInRange(any(), any()) } returns MutableStateFlow(emptyList())
        every { eventReader.getVisibleOccurrencesForDay(any()) } returns MutableStateFlow(emptyList())
        every { eventReader.getVisibleOccurrencesWithEventsForDay(any()) } returns MutableStateFlow(emptyList())

        // Account repository defaults
        coEvery { accountRepository.getAccountsByProvider(any()) } returns emptyList()
        coEvery { accountRepository.hasCredentials(any()) } returns false

        // Display event repository
        every { displayEventRepository.deviceCalendarChangeSignal } returns MutableStateFlow(0)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HomeViewModel {
        return HomeViewModel(
            eventCoordinator = eventCoordinator,
            eventReader = eventReader,
            displayEventRepository = displayEventRepository,
            dataStore = dataStore,
            accountRepository = accountRepository,
            syncScheduler = syncScheduler,
            networkMonitor = networkMonitor,
            calendarProviderRepository = fakeCalendarProviderRepository,
            attendeeBackfill = io.mockk.mockk(relaxed = true),
            contactEmailReader = io.mockk.mockk(relaxed = true),
            context = io.mockk.mockk(relaxed = true),
            ioDispatcher = testDispatcher
        )
    }

    // ==================== Create Event Tests ====================

    @Test
    fun `createDeviceEvent routes to CalendarProviderRepository`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.createDeviceEvent(
            calendarId = 42L,
            title = "Team Meeting",
            description = "Weekly sync",
            location = "Zoom",
            startTs = 1709280000000L,
            endTs = 1709283600000L,
            isAllDay = false,
            rrule = null,
            timezone = ZoneId.systemDefault().id,
            reminders = listOf(15)
        )
        advanceUntilIdle()

        // Verify event was created through repository
        assertTrue(result.isSuccess)
        assertEquals(1, fakeCalendarProviderRepository.createdEvents.size)
        assertEquals(42L, fakeCalendarProviderRepository.createdEvents[0].calendarId)
        assertEquals("Team Meeting", fakeCalendarProviderRepository.createdEvents[0].title)
    }

    @Test
    fun `createDeviceEvent returns created event ID`() = runTest {
        fakeCalendarProviderRepository.createdEventId = 500L

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.createDeviceEvent(
            calendarId = 42L,
            title = "Event",
            description = null,
            location = null,
            startTs = 1709280000000L,
            endTs = 1709283600000L,
            isAllDay = false,
            rrule = null,
            timezone = ZoneId.systemDefault().id,
            reminders = emptyList()
        )
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertEquals(500L, result.getOrNull())
    }

    @Test
    fun `createDeviceEvent failure surfaces error to UI state`() = runTest {
        fakeCalendarProviderRepository.writeFailure = CalendarError.DeviceCalendar.PermissionDenied

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.createDeviceEvent(
            calendarId = 42L,
            title = "Event",
            description = null,
            location = null,
            startTs = 1709280000000L,
            endTs = 1709283600000L,
            isAllDay = false,
            rrule = null,
            timezone = ZoneId.systemDefault().id,
            reminders = emptyList()
        )
        advanceUntilIdle()

        assertTrue(result.isFailure)
        // Verify error was surfaced to UI
        val currentError = viewModel.uiState.value.currentError
        assertNotNull(currentError)
    }

    // ==================== Delete Event Tests ====================

    @Test
    fun `deleteDeviceEvent routes to CalendarProviderRepository`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.deleteDeviceEvent(eventId = 123L)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertEquals(1, fakeCalendarProviderRepository.deletedEventIds.size)
        assertEquals(123L, fakeCalendarProviderRepository.deletedEventIds[0])
    }

    @Test
    fun `deleteDeviceEvent failure surfaces error to UI state`() = runTest {
        fakeCalendarProviderRepository.writeFailure = CalendarError.DeviceCalendar.EventNotFound

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.deleteDeviceEvent(eventId = 999L)
        advanceUntilIdle()

        assertTrue(result.isFailure)
        val currentError = viewModel.uiState.value.currentError
        assertNotNull(currentError)
    }

    // ==================== Update Event Tests ====================

    @Test
    fun `updateDeviceEvent routes to CalendarProviderRepository`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.updateDeviceEvent(
            eventId = 100L,
            title = "Updated Meeting",
            description = "New description",
            location = "Office",
            startTs = 1709280000000L,
            endTs = 1709283600000L,
            isAllDay = false,
            rrule = null,
            timezone = ZoneId.systemDefault().id,
            reminders = listOf(30)
        )
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertEquals(1, fakeCalendarProviderRepository.updatedEventIds.size)
        assertEquals(100L, fakeCalendarProviderRepository.updatedEventIds[0])
    }

    // ==================== Exception Event Tests (Recurring) ====================

    @Test
    fun `deleteDeviceSingleOccurrence creates canceled exception`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.deleteDeviceSingleOccurrence(
            masterEventId = 200L,
            originalInstanceTime = 1709280000000L
        )
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertEquals(1, fakeCalendarProviderRepository.deletedOccurrences.size)
        assertEquals(200L, fakeCalendarProviderRepository.deletedOccurrences[0].masterEventId)
        assertEquals(1709280000000L, fakeCalendarProviderRepository.deletedOccurrences[0].originalInstanceTime)
    }

    // ==================== handleDeviceEventFormDelete Routing ====================
    //
    // Routing branches on the loaded device event's shape, not formState:
    //   originalId != null    → exception      → deleteDeviceSingleOccurrence
    //   rrule != null         → recurring master → scope sheet (no leaf delete)
    //   else                  → non-recurring  → deleteDeviceEvent

    @Test
    fun `handleDeviceEventFormDelete on exception routes to deleteDeviceSingleOccurrence`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Seed an exception event: originalId points at master, originalInstanceTime
        // is the recurrence id. handleDeviceEventFormDelete reads these off the
        // loaded event and forwards to deleteDeviceSingleOccurrence.
        fakeCalendarProviderRepository.deviceEvents[200L] = deviceEvent(
            id = 200L,
            originalId = 100L,
            originalInstanceTime = 1709280000000L,
        )

        val formState = EventFormState(
            editingDeviceEventId = 200L,
            editingOccurrenceTs = 1709280000000L,
            selectedCalendarId = 42L,
            isAllDay = false
        )

        val result = viewModel.handleDeviceEventFormDelete(formState)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertEquals(1, fakeCalendarProviderRepository.deletedOccurrences.size)
        // masterEventId comes from event.originalId (the master), NOT the
        // exception event's own id.
        assertEquals(100L, fakeCalendarProviderRepository.deletedOccurrences[0].masterEventId)
        assertEquals(1709280000000L, fakeCalendarProviderRepository.deletedOccurrences[0].originalInstanceTime)
        assertTrue("Should NOT have called deleteEvent", fakeCalendarProviderRepository.deletedEventIds.isEmpty())
    }

    @Test
    fun `handleDeviceEventFormDelete on non-recurring event routes to deleteDeviceEvent`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Non-recurring: no rrule, no originalId → straight master delete.
        fakeCalendarProviderRepository.deviceEvents[200L] = deviceEvent(id = 200L)

        val formState = EventFormState(
            editingDeviceEventId = 200L,
            editingOccurrenceTs = null,
            selectedCalendarId = 42L,
            isAllDay = false
        )

        val result = viewModel.handleDeviceEventFormDelete(formState)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertEquals(1, fakeCalendarProviderRepository.deletedEventIds.size)
        assertEquals(200L, fakeCalendarProviderRepository.deletedEventIds[0])
        assertTrue("Should NOT have called deleteSingleOccurrence", fakeCalendarProviderRepository.deletedOccurrences.isEmpty())
    }

    @Test
    fun `handleDeviceEventFormDelete without deviceEventId returns failure`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            editingDeviceEventId = null,
            editingOccurrenceTs = null,
            selectedCalendarId = null
        )

        val result = viewModel.handleDeviceEventFormDelete(formState)
        advanceUntilIdle()

        assertTrue(result.isFailure)
        assertTrue(fakeCalendarProviderRepository.deletedEventIds.isEmpty())
        assertTrue(fakeCalendarProviderRepository.deletedOccurrences.isEmpty())
    }

    @Test
    fun `handleDeviceEventFormDelete passes isAllDay through to occurrence delete`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        fakeCalendarProviderRepository.deviceEvents[300L] = deviceEvent(
            id = 300L,
            originalId = 100L,
            originalInstanceTime = 1709280000000L,
        )

        val formState = EventFormState(
            editingDeviceEventId = 300L,
            editingOccurrenceTs = 1709280000000L,
            selectedCalendarId = 99L,
            isAllDay = true
        )

        val result = viewModel.handleDeviceEventFormDelete(formState)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val deleted = fakeCalendarProviderRepository.deletedOccurrences[0]
        assertEquals(true, deleted.isAllDay)
    }

    private fun deviceEvent(
        id: Long,
        calendarId: Long = 1L,
        rrule: String? = null,
        originalId: Long? = null,
        originalInstanceTime: Long? = null,
    ) = org.onekash.kashcal.data.calendar_provider.DeviceEvent(
        id = id,
        calendarId = calendarId,
        title = "Event $id",
        description = null,
        location = null,
        startTs = 0L,
        endTs = 0L,
        duration = null,
        isAllDay = false,
        rrule = rrule,
        rdate = null,
        exdate = null,
        exrule = null,
        timezone = "UTC",
        originalId = originalId,
        originalInstanceTime = originalInstanceTime,
        status = 1,
        availability = 0,
        accessLevel = 700,
        calendarColor = null,
        eventColor = null,
    )
}
