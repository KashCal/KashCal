package org.onekash.kashcal.ui.viewmodels

import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.testutil.phoneMidnight
import org.onekash.kashcal.testutil.withDeviceTimeZone
import org.onekash.kashcal.ui.components.withAllDay
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
import org.onekash.kashcal.domain.mapper.toFormState
import org.onekash.kashcal.ui.components.withTimezone
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

/**
 * Tests [HomeViewModel]'s device-event writes over [FakeCalendarProviderRepository]:
 * - a delete and a single-occurrence delete reach the repository through the device writer;
 * - a failed delete or this-and-future save sets the UI state's current error;
 * - the form's Delete routes an exception to its occurrence (master id and all-day flag passed
 *   through) and a one-off to a whole-event delete, and fails with no device event id;
 * - a save stores the form's clock time in the event's timezone whatever the device zone, across
 *   a timezone change, all-day toggles, one occurrence, the repeated DST hour and an
 *   unrecognised zone name.
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
            deviceEventReader = fakeCalendarProviderRepository.deviceEventReader(),
            deviceEventWriter = fakeCalendarProviderRepository.deviceEventWriter(dataStore),
            attendeeBackfill = io.mockk.mockk(relaxed = true),
            contactEmailReader = io.mockk.mockk(relaxed = true),
            context = io.mockk.mockk(relaxed = true),
            ioDispatcher = testDispatcher
        )
    }

    // ==================== Delete Event Tests ====================

    @Test
    fun `deleting a device event removes that row`() = runTest {
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

    @Test
    fun `this-and-future save failure surfaces error to UI state`() = runTest {
        // The scoped-save caller keeps the form open on failure and relies on
        // the ViewModel to tell the user why, so the split failing must not be
        // silent.
        fakeCalendarProviderRepository.writeFailure = CalendarError.DeviceCalendar.PermissionDenied

        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Standup",
            selectedCalendarId = 42L,
            editingDeviceEventId = 100L,
            editingOccurrenceTs = 1709280000000L,
        )

        val result = viewModel.saveDeviceEvent(formState, scope = EditScope.THIS_AND_FUTURE)
        advanceUntilIdle()

        assertTrue(result.isFailure)
        assertTrue(fakeCalendarProviderRepository.editedFutureSeries.isEmpty())
        assertNotNull(viewModel.uiState.value.currentError)
    }

    // ==================== handleDeviceEventFormDelete Routing ====================
    //
    // Routing branches on the loaded device event's shape, not the form state:
    //   originalId != null: exception, deleteDeviceSingleOccurrence
    //   rrule != null:      series, the scope sheet (not tested here)
    //   otherwise:          one-off, deleteDeviceEvent

    @Test
    fun `handleDeviceEventFormDelete on exception routes to deleteDeviceSingleOccurrence`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // An exception: originalId points at the master and originalInstanceTime is the
        // recurrence id; handleDeviceEventFormDelete reads both off the loaded event.
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
        // masterEventId comes from event.originalId, not the exception's own id.
        assertEquals(100L, fakeCalendarProviderRepository.deletedOccurrences[0].masterEventId)
        assertEquals(1709280000000L, fakeCalendarProviderRepository.deletedOccurrences[0].originalInstanceTime)
        assertTrue("Should NOT have called deleteEvent", fakeCalendarProviderRepository.deletedEventIds.isEmpty())
    }

    @Test
    fun `handleDeviceEventFormDelete on non-recurring event routes to deleteDeviceEvent`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // No rrule and no originalId, so the whole event is deleted.
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
        startTs: Long = 0L,
        endTs: Long? = 0L,
        duration: String? = null,
        isAllDay: Boolean = false,
        timezone: String = "UTC",
    ) = org.onekash.kashcal.data.calendar_provider.DeviceEvent(
        id = id,
        calendarId = calendarId,
        title = "Event $id",
        description = null,
        location = null,
        startTs = startTs,
        endTs = endTs,
        duration = duration,
        isAllDay = isAllDay,
        rrule = rrule,
        rdate = null,
        exdate = null,
        exrule = null,
        timezone = timezone,
        originalId = originalId,
        originalInstanceTime = originalInstanceTime,
        status = 1,
        availability = 0,
        accessLevel = 700,
        calendarColor = null,
        eventColor = null,
    )

    // ==================== Event timezone on save ====================
    //
    // The form holds a wall-clock time plus the event's timezone. The stored
    // instant must be that wall-clock time in that timezone, whatever the
    // device's own zone is.

    private val newYork10am = 1_709_650_800_000L // 2024-03-05 10:00 America/New_York (15:00Z)
    private val newYork1amNextDay = 1_709_704_800_000L // 2024-03-06 01:00 New York, 22:00 LA Mar 5
    private val oneHourMs = 3_600_000L

    private fun newYorkDeviceEvent(startTs: Long, timezone: String = "America/New_York") =
        deviceEvent(id = 100L, calendarId = 42L, startTs = startTs, endTs = startTs + oneHourMs, timezone = timezone)

    /** Loads [event] into the form the way the edit sheet does, then saves it. */
    private suspend fun openAndSave(
        viewModel: HomeViewModel,
        event: org.onekash.kashcal.data.calendar_provider.DeviceEvent,
        occurrenceTs: Long? = null,
        edit: (EventFormState) -> EventFormState = { it },
    ) {
        fakeCalendarProviderRepository.deviceEvents[event.id] = event
        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = null,
            calendarName = "Work",
            deviceCalendarGroups = emptyList(),
            occurrenceTs = occurrenceTs,
        ).copy(editingOccurrenceTs = occurrenceTs)
        val result = viewModel.saveDeviceEvent(edit(formState))
        assertTrue(result.isSuccess)
    }

    @Test
    fun `new device event in a non-device timezone is stored at the chosen clock time`() = withDeviceTimeZone("America/Los_Angeles") {
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            val formState = EventFormState(
                title = "Standup",
                selectedCalendarId = 42L,
                dateMillis = phoneMidnight(java.time.LocalDate.of(2024, 3, 5)),
                endDateMillis = phoneMidnight(java.time.LocalDate.of(2024, 3, 5)),
                startHour = 10,
                startMinute = 0,
                endHour = 11,
                endMinute = 0,
                timezone = "America/New_York",
            )
            val result = viewModel.saveDeviceEvent(formState)
            advanceUntilIdle()

            assertTrue(result.isSuccess)
            val created = fakeCalendarProviderRepository.createdEvents.single()
            assertEquals("America/New_York", created.timezone)
            assertEquals(newYork10am, created.startTs)
            assertEquals(newYork10am + oneHourMs, created.endTs)
        }
    }

    @Test
    fun `saving an unchanged device event from another timezone keeps its time`() = withDeviceTimeZone("America/Los_Angeles") {
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            openAndSave(viewModel, newYorkDeviceEvent(newYork10am))
            advanceUntilIdle()

            val update = fakeCalendarProviderRepository.updatedEvents.single()
            assertEquals("America/New_York", update.timezone)
            assertEquals(newYork10am, update.startTs)
            assertEquals(newYork10am + oneHourMs, update.endTs)
        }
    }

    @Test
    fun `saving an unchanged device event whose local date differs from the device date keeps its time`() = withDeviceTimeZone("America/Los_Angeles") {
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            openAndSave(viewModel, newYorkDeviceEvent(newYork1amNextDay))
            advanceUntilIdle()

            val update = fakeCalendarProviderRepository.updatedEvents.single()
            assertEquals(newYork1amNextDay, update.startTs)
            assertEquals(newYork1amNextDay + oneHourMs, update.endTs)
        }
    }

    @Test
    fun `picking a new timezone for a device event keeps the event at the same moment`() = withDeviceTimeZone("America/Los_Angeles") {
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            openAndSave(viewModel, newYorkDeviceEvent(newYork10am)) { it.withTimezone("America/Chicago") }
            advanceUntilIdle()

            val update = fakeCalendarProviderRepository.updatedEvents.single()
            assertEquals("America/Chicago", update.timezone)
            assertEquals(newYork10am, update.startTs) // shown as 09:00 Chicago
            assertEquals(newYork10am + oneHourMs, update.endTs)
        }
    }

    @Test
    fun `switching an all-day device event to timed saves the entered time in the device timezone`() = withDeviceTimeZone("America/Los_Angeles") {
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            val allDay = newYorkDeviceEvent(1_709_596_800_000L).copy( // 2024-03-05 00:00 UTC
                endTs = 1_709_683_199_999L,
                isAllDay = true,
                timezone = "UTC",
            )
            openAndSave(viewModel, allDay) {
                it.copy(isAllDay = false, startHour = 10, startMinute = 0, endHour = 11, endMinute = 0)
            }
            advanceUntilIdle()

            val update = fakeCalendarProviderRepository.updatedEvents.single()
            val losAngeles = java.util.TimeZone.getTimeZone("America/Los_Angeles")
            val start = java.util.Calendar.getInstance(losAngeles).apply { timeInMillis = update.startTs }
            assertEquals(10, start.get(java.util.Calendar.HOUR_OF_DAY))
            assertEquals(5, start.get(java.util.Calendar.DAY_OF_MONTH))
            assertEquals(oneHourMs, update.endTs!! - update.startTs)
        }
    }

    @Test
    fun `saving one unchanged occurrence of a recurring device event from another timezone keeps its time`() = withDeviceTimeZone("America/Los_Angeles") {
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            val series = newYorkDeviceEvent(newYork10am).copy(endTs = null, duration = "PT1H", rrule = "FREQ=WEEKLY")
            val occurrence = 1_710_252_000_000L // 2024-03-12 10:00 New York, 14:00Z, after DST
            openAndSave(viewModel, series, occurrenceTs = occurrence)
            advanceUntilIdle()

            val exception = fakeCalendarProviderRepository.createdExceptions.single()
            assertEquals(occurrence, exception.originalInstanceTime)
            assertEquals(occurrence, exception.startTs)
        }
    }

    @Test
    fun `turning all-day on and off on a device event from another timezone keeps its time`() = withDeviceTimeZone("America/Los_Angeles") {
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            openAndSave(viewModel, newYorkDeviceEvent(newYork1amNextDay)) {
                it.withAllDay(true, defaultReminderTimed = 15, defaultReminderAllDay = 1440)
                    .withAllDay(false, defaultReminderTimed = 15, defaultReminderAllDay = 1440)
            }
            advanceUntilIdle()

            val update = fakeCalendarProviderRepository.updatedEvents.single()
            assertEquals(newYork1amNextDay, update.startTs)
            assertEquals(newYork1amNextDay + oneHourMs, update.endTs)
        }
    }

    @Test
    fun `saving an unchanged device event in the repeated hour keeps both times`() = withDeviceTimeZone("America/Los_Angeles") {
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            // 2024-11-03 01:00 EDT (05:00Z) to 01:00 EST (06:00Z): the same clock time twice.
            val event = newYorkDeviceEvent(1_730_610_000_000L)
            openAndSave(viewModel, event)
            advanceUntilIdle()

            val update = fakeCalendarProviderRepository.updatedEvents.single()
            assertEquals(1_730_610_000_000L, update.startTs)
            assertEquals(1_730_613_600_000L, update.endTs)
        }
    }

    @Test
    fun `saving an unchanged device event keeps an unrecognised timezone name`() = withDeviceTimeZone("America/Los_Angeles") {
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            openAndSave(viewModel, newYorkDeviceEvent(newYork10am, timezone = "Eastern Standard Time"))
            advanceUntilIdle()

            val update = fakeCalendarProviderRepository.updatedEvents.single()
            assertEquals("Eastern Standard Time", update.timezone)
            assertEquals(newYork10am, update.startTs)
        }
    }

    @Test
    fun `choosing the device default timezone replaces an unrecognised one`() = withDeviceTimeZone("America/Los_Angeles") {
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            openAndSave(viewModel, newYorkDeviceEvent(newYork10am, timezone = "Eastern Standard Time")) {
                it.withTimezone(null)
            }
            advanceUntilIdle()

            assertEquals("America/Los_Angeles", fakeCalendarProviderRepository.updatedEvents.single().timezone)
        }
    }
}
