package org.onekash.kashcal.ui.viewmodels

import android.provider.CalendarContract
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceEvent
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceChangeNotifier
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.error.CalendarError
import org.onekash.kashcal.error.ErrorMapper
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.phoneMidnight
import org.onekash.kashcal.ui.components.EventFormState
import org.onekash.kashcal.ui.components.toStartEndTs
import org.onekash.kashcal.util.computeDurationString

/**
 * Tests saving and deleting device events per edit scope through the real
 * HomeViewModel over [FakeCalendarProviderRepository].
 *
 * Each scope lands on a different provider write: one occurrence becomes (or
 * updates) an exception row, "all events" rewrites the series master, "this
 * and future" splits the series, and a plain event is created or updated in
 * place. Recurring rows carry a DURATION instead of an end time, as the
 * Calendar Provider requires. An all-events save from a later occurrence keeps
 * the series' own start; one that moves that occurrence's date, or whose
 * series can't be read, is refused and writes nothing. A form save request
 * records whether the opened occurrence's date changed, and a failed write
 * shows a write error. The scope sheet's deletes
 * cancel one occurrence, truncate the series or remove it, and the form's
 * Delete on a series only asks which occurrences.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelDeviceScopeSaveDeleteTest {

    /** Receives the device change signal the writer sends after each successful write. */
    private val deviceManager = deviceChangeNotifier()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var eventCoordinator: EventCoordinator
    private lateinit var eventReader: EventReader
    private lateinit var displayEventRepository: DisplayEventRepository
    private lateinit var dataStore: KashCalDataStore
    private lateinit var accountRepository: AccountRepository
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var repo: FakeCalendarProviderRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        eventCoordinator = mockk(relaxed = true)
        eventReader = mockk(relaxed = true)
        displayEventRepository = mockk(relaxed = true)
        dataStore = TestDataStoreFactory.createStrictForHomeViewModel()
        accountRepository = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        repo = FakeCalendarProviderRepository()

        every { networkMonitor.isOnline } returns MutableStateFlow(true)
        every { networkMonitor.isMetered } returns MutableStateFlow(false)
        every { syncScheduler.observeImmediateSyncStatus() } returns MutableStateFlow(SyncStatus.Idle)
        every { syncScheduler.lastSyncChanges } returns MutableStateFlow(emptyList())
        every { syncScheduler.showBannerForSync } returns MutableStateFlow(false)
        every { eventCoordinator.getAllCalendars() } returns MutableStateFlow(emptyList())
        every { eventReader.getVisibleOccurrencesInRange(any(), any()) } returns MutableStateFlow(emptyList())
        every { eventReader.getVisibleOccurrencesForDay(any()) } returns MutableStateFlow(emptyList())
        every { eventReader.getVisibleOccurrencesWithEventsForDay(any()) } returns MutableStateFlow(emptyList())
        coEvery { accountRepository.getAccountsByProvider(any()) } returns emptyList()
        coEvery { accountRepository.hasCredentials(any()) } returns false
        every { displayEventRepository.deviceCalendarChangeSignal } returns MutableStateFlow(0)

        repo.deviceEvents[MASTER_ID] = master
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HomeViewModel = HomeViewModel(
        eventCoordinator = eventCoordinator,
        eventReader = eventReader,
        displayEventRepository = displayEventRepository,
        dataStore = dataStore,
        accountRepository = accountRepository,
        syncScheduler = syncScheduler,
        networkMonitor = networkMonitor,
        deviceEventReader = repo.deviceEventReader(),
        deviceEventWriter = repo.deviceEventWriter(dataStore, deviceManager),
        attendeeBackfill = mockk(relaxed = true),
        contactEmailReader = mockk(relaxed = true),
        context = mockk(relaxed = true),
        ioDispatcher = testDispatcher,
    )

    /**
     * The occurrence's date (15 Nov 2023, UTC) as the form stores a date: that
     * day's midnight in the device's zone, so the form reads the same day on
     * any host.
     */
    private val formDay = phoneMidnight(java.time.LocalDate.of(2023, 11, 15))

    /** The day after [formDay], as the form stores it. */
    private val nextFormDay = phoneMidnight(java.time.LocalDate.of(2023, 11, 16))

    private fun form(
        rrule: String? = null,
        editingDeviceEventId: Long? = null,
        editingOccurrenceTs: Long? = null,
    ) = EventFormState(
        title = "Planning",
        description = "agenda",
        location = "Room 4",
        dateMillis = formDay,
        endDateMillis = formDay,
        startHour = 9,
        startMinute = 0,
        endHour = 10,
        endMinute = 30,
        timezone = "UTC",
        selectedCalendarId = CAL_ID,
        reminders = listOf(15),
        rrule = rrule,
        transp = "TRANSPARENT",
        eventColor = COLOR,
        isDeviceCalendar = true,
        editingDeviceEventId = editingDeviceEventId,
        editingOccurrenceTs = editingOccurrenceTs,
    )

    // ---- one occurrence ----

    @Test
    fun `editing an occurrence that already has an exception updates that exception row`() = runTest {
        repo.exceptionEvents[MASTER_ID to OCCURRENCE_TS] = EXCEPTION_ID
        val viewModel = createViewModel()
        advanceUntilIdle()
        val f = form(rrule = "FREQ=WEEKLY", editingDeviceEventId = MASTER_ID, editingOccurrenceTs = OCCURRENCE_TS)
        val (start, end) = f.toStartEndTs()

        val result = viewModel.saveDeviceEvent(f)
        advanceUntilIdle()

        assertEquals(EXCEPTION_ID, result.getOrNull())
        assertTrue("no second exception", repo.createdExceptions.isEmpty())
        val update = repo.updatedEvents.single()
        assertEquals(EXCEPTION_ID, update.eventId)
        assertNull("an exception carries no RRULE", update.rrule)
        assertNull(update.duration)
        assertEquals(start, update.startTs)
        assertEquals(end, update.endTs)
        assertEquals(1, update.availability)
        assertEquals(COLOR, update.eventColor)
        assertEquals("Planning", update.title)
    }

    @Test
    fun `editing an occurrence without an exception creates one on the master`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        val f = form(rrule = "FREQ=WEEKLY", editingDeviceEventId = MASTER_ID, editingOccurrenceTs = OCCURRENCE_TS)
        val (start, end) = f.toStartEndTs()

        val result = viewModel.saveDeviceEvent(f, EditScope.THIS_EVENT)
        advanceUntilIdle()

        assertTrue(repo.updatedEvents.isEmpty())
        val created = repo.createdExceptions.single()
        assertEquals(MASTER_ID, created.masterEventId)
        assertEquals(OCCURRENCE_TS, created.originalInstanceTime)
        assertEquals(CAL_ID, created.calendarId)
        assertEquals(start, created.startTs)
        assertEquals(end, created.endTs)
        assertEquals(listOf(15), created.reminders)
        assertEquals(created.resultId, result.getOrNull())
    }

    // ---- all events ----

    @Test
    fun `saving all events from an occurrence rewrites the series master`() = runTest {
        repo.exceptionEvents[MASTER_ID to OCCURRENCE_TS] = EXCEPTION_ID
        val viewModel = createViewModel()
        advanceUntilIdle()
        val f = form(rrule = "FREQ=WEEKLY", editingDeviceEventId = MASTER_ID, editingOccurrenceTs = OCCURRENCE_TS)
        val (start, end) = f.toStartEndTs()

        viewModel.saveDeviceEvent(f, EditScope.ALL_EVENTS)
        advanceUntilIdle()

        assertTrue(repo.createdExceptions.isEmpty())
        val update = repo.updatedEvents.single()
        assertEquals("the master, not the exception", MASTER_ID, update.eventId)
        assertEquals("FREQ=WEEKLY", update.rrule)
        assertNull("a recurring row has no DTEND", update.endTs)
        assertEquals(computeDurationString(start, end, false), update.duration)
    }

    @Test
    fun `saving all events from a later occurrence keeps the series' own start`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        val f = form(rrule = "FREQ=WEEKLY", editingDeviceEventId = MASTER_ID, editingOccurrenceTs = OCCURRENCE_TS)

        assertTrue(viewModel.saveDeviceEvent(f, EditScope.ALL_EVENTS).isSuccess)

        assertEquals(master.startTs, repo.updatedEvents.single().startTs)
    }

    @Test
    fun `saving all events after moving a later occurrence to another day is refused and writes nothing`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        val moved = form(rrule = "FREQ=WEEKLY", editingDeviceEventId = MASTER_ID, editingOccurrenceTs = OCCURRENCE_TS)
            .copy(dateMillis = nextFormDay, endDateMillis = nextFormDay)

        assertTrue(viewModel.saveDeviceEvent(moved, EditScope.ALL_EVENTS).isFailure)
        advanceUntilIdle()

        assertTrue(repo.updatedEvents.isEmpty())
        assertNotNull(viewModel.uiState.value.currentError)
    }

    @Test
    fun `saving all events from an occurrence whose series can't be read writes nothing`() = runTest {
        repo.deviceEvents.remove(MASTER_ID)
        val viewModel = createViewModel()
        advanceUntilIdle()
        val f = form(rrule = "FREQ=WEEKLY", editingDeviceEventId = MASTER_ID, editingOccurrenceTs = OCCURRENCE_TS)

        assertTrue(viewModel.saveDeviceEvent(f, EditScope.ALL_EVENTS).isFailure)
        advanceUntilIdle()

        assertTrue(repo.updatedEvents.isEmpty())
        assertNotNull(viewModel.uiState.value.currentError)
    }

    @Test
    fun `a device form save records whether the opened occurrence's date changed`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        val same = form(rrule = "FREQ=WEEKLY", editingDeviceEventId = MASTER_ID, editingOccurrenceTs = OCCURRENCE_TS)
        val moved = same.copy(dateMillis = nextFormDay, endDateMillis = nextFormDay)

        fun request(f: EventFormState) = viewModel.requestFormSave(
            formState = f, occurrenceTs = OCCURRENCE_TS, originalRrule = "FREQ=WEEKLY",
            masterStartTs = master.startTs, isDetachedException = false,
            isRecurringDevice = true, loadedIsAllDay = false,
        ).let { viewModel.uiState.value.pendingFormSave!!.occurrenceDateChanged }

        assertEquals(false, request(same))
        assertEquals(true, request(moved))
    }

    // ---- this and future ----

    @Test
    fun `saving this and future splits the series at the occurrence`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        val f = form(rrule = "FREQ=WEEKLY", editingDeviceEventId = MASTER_ID, editingOccurrenceTs = OCCURRENCE_TS)
        val (start, end) = f.toStartEndTs()

        val result = viewModel.saveDeviceEvent(f, EditScope.THIS_AND_FUTURE)
        advanceUntilIdle()

        val split = repo.editedFutureSeries.single()
        assertEquals(MASTER_ID, split.masterEventId)
        assertEquals(OCCURRENCE_TS, split.fromTimeMs)
        assertEquals(CAL_ID, split.calendarId)
        assertEquals("FREQ=WEEKLY", split.rrule)
        assertEquals(start, split.startTs)
        assertNull(split.endTs)
        assertEquals(computeDurationString(start, end, false), split.duration)
        assertEquals(split.newEventId, result.getOrNull())
        verify(exactly = 1) { deviceManager.notifyDeviceCalendarChanged() }
    }

    @Test
    fun `a failed this and future save shows a write error`() = runTest {
        repo.writeFailure = CalendarError.DeviceCalendar.WriteFailed("boom")
        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.saveDeviceEvent(
            form(rrule = "FREQ=WEEKLY", editingDeviceEventId = MASTER_ID, editingOccurrenceTs = OCCURRENCE_TS),
            EditScope.THIS_AND_FUTURE,
        )
        advanceUntilIdle()

        assertTrue(result.isFailure)
        assertEquals(writeFailedBoom, viewModel.uiState.value.currentError)
        verify(exactly = 0) { deviceManager.notifyDeviceCalendarChanged() }
    }

    // ---- create and in-place update ----

    @Test
    fun `creating a one-off event stores its end time and no duration`() = runTest {
        repo.createdEventId = 500L
        val viewModel = createViewModel()
        advanceUntilIdle()
        val f = form()
        val (start, end) = f.toStartEndTs()

        val result = viewModel.saveDeviceEvent(f)
        advanceUntilIdle()

        assertEquals(500L, result.getOrNull())
        val created = repo.createdEvents.single()
        assertEquals(CAL_ID, created.calendarId)
        assertEquals(start, created.startTs)
        assertEquals(end, created.endTs)
        assertNull(created.duration)
        assertEquals("agenda", created.description)
        assertEquals("Room 4", created.location)
        assertEquals("UTC", created.timezone)
        assertEquals(listOf(15), created.reminders)
    }

    @Test
    fun `creating a repeating event stores a duration instead of an end time`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        val f = form(rrule = "FREQ=DAILY")
        val (start, end) = f.toStartEndTs()

        viewModel.saveDeviceEvent(f)
        advanceUntilIdle()

        val created = repo.createdEvents.single()
        assertEquals("FREQ=DAILY", created.rrule)
        assertNull(created.endTs)
        assertEquals(computeDurationString(start, end, false), created.duration)
    }

    @Test
    fun `a failed create shows a write error`() = runTest {
        repo.writeFailure = CalendarError.DeviceCalendar.WriteFailed("boom")
        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.saveDeviceEvent(form())
        advanceUntilIdle()

        assertTrue(result.isFailure)
        assertEquals(writeFailedBoom, viewModel.uiState.value.currentError)
    }

    @Test
    fun `editing a one-off event in place stores its end time and no duration`() = runTest {
        repo.deviceEvents[PLAIN_ID] = master.copy(id = PLAIN_ID, rrule = null, duration = null, endTs = 2L)
        val viewModel = createViewModel()
        advanceUntilIdle()
        val f = form(editingDeviceEventId = PLAIN_ID)
        val (start, end) = f.toStartEndTs()

        val result = viewModel.saveDeviceEvent(f)
        advanceUntilIdle()

        assertEquals(PLAIN_ID, result.getOrNull())
        val update = repo.updatedEvents.single()
        assertEquals(PLAIN_ID, update.eventId)
        assertEquals(start, update.startTs)
        assertEquals(end, update.endTs)
        assertNull(update.duration)
        assertNull(update.rrule)
        verify(exactly = 1) { deviceManager.notifyDeviceCalendarChanged() }
    }

    @Test
    fun `editing a repeating series in place stores a duration instead of an end time`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        val f = form(rrule = "FREQ=WEEKLY", editingDeviceEventId = MASTER_ID)
        val (start, end) = f.toStartEndTs()

        viewModel.saveDeviceEvent(f)
        advanceUntilIdle()

        val update = repo.updatedEvents.single()
        assertEquals(MASTER_ID, update.eventId)
        assertNull(update.endTs)
        assertEquals(computeDurationString(start, end, false), update.duration)
    }

    @Test
    fun `saving an event whose stored repeat rule is empty writes its end time`() = runTest {
        // An empty RRULE column is "not recurring" to the provider, which then
        // needs a DTEND. A DURATION would be dropped and leave the old end time
        // on the row.
        repo.deviceEvents[PLAIN_ID] = master.copy(id = PLAIN_ID, rrule = "", duration = null, endTs = 2L)
        val viewModel = createViewModel()
        advanceUntilIdle()
        val f = form(rrule = "", editingDeviceEventId = PLAIN_ID)
        val (_, end) = f.toStartEndTs()

        viewModel.saveDeviceEvent(f)
        advanceUntilIdle()

        val update = repo.updatedEvents.single()
        assertEquals(PLAIN_ID, update.eventId)
        assertEquals(end, update.endTs)
        assertNull(update.duration)
    }

    // ---- deletes via the scope sheet ----

    private suspend fun confirmDeviceDelete(viewModel: HomeViewModel, scope: EditScope, isAllDay: Boolean = false) {
        viewModel.requestDeleteDevice(
            masterEventId = MASTER_ID,
            calendarId = CAL_ID,
            occurrenceTs = OCCURRENCE_TS,
            masterStartTs = master.startTs,
            isDetachedException = false,
            isAllDay = isAllDay,
        )
        viewModel.confirmDelete(scope)
    }

    @Test
    fun `deleting one occurrence cancels just that occurrence`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        confirmDeviceDelete(viewModel, EditScope.THIS_EVENT)
        advanceUntilIdle()

        assertEquals(
            listOf(FakeCalendarProviderRepository.DeletedOccurrence(MASTER_ID, OCCURRENCE_TS, false)),
            repo.deletedOccurrences,
        )
        assertNull(viewModel.uiState.value.pendingDelete)
    }

    @Test
    fun `deleting this and future truncates the series at the occurrence`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        confirmDeviceDelete(viewModel, EditScope.THIS_AND_FUTURE, isAllDay = true)
        advanceUntilIdle()

        assertEquals(
            listOf(FakeCalendarProviderRepository.DeletedFutureOccurrence(MASTER_ID, OCCURRENCE_TS, true)),
            repo.deletedFutureOccurrences,
        )
        assertTrue(repo.deletedEventIds.isEmpty())
        assertNull(viewModel.uiState.value.pendingDelete)
    }

    @Test
    fun `deleting all events removes the series`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        confirmDeviceDelete(viewModel, EditScope.ALL_EVENTS)
        advanceUntilIdle()

        assertEquals(listOf(MASTER_ID), repo.deletedEventIds)
        verify(exactly = 1) { deviceManager.notifyDeviceCalendarChanged() }
    }

    @Test
    fun `a failed delete from the scope sheet shows a write error`() = runTest {
        repo.writeFailure = CalendarError.DeviceCalendar.WriteFailed("boom")
        val viewModel = createViewModel()
        advanceUntilIdle()

        confirmDeviceDelete(viewModel, EditScope.THIS_AND_FUTURE)
        advanceUntilIdle()

        assertEquals(writeFailedBoom, viewModel.uiState.value.currentError)
        assertNull(viewModel.uiState.value.pendingDelete)
    }

    @Test
    fun `deleting a repeating series from the form asks which occurrences`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.handleDeviceEventFormDelete(
            form(rrule = "FREQ=WEEKLY", editingDeviceEventId = MASTER_ID, editingOccurrenceTs = OCCURRENCE_TS)
        )
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertEquals(
            PendingDelete.Device(
                masterEventId = MASTER_ID,
                calendarId = CAL_ID,
                occurrenceTs = OCCURRENCE_TS,
                masterStartTs = master.startTs,
                isDetachedException = false,
                isAllDay = false,
            ),
            viewModel.uiState.value.pendingDelete,
        )
        assertTrue("nothing is written until a scope is chosen", repo.deletedEventIds.isEmpty())
        assertTrue(repo.deletedOccurrences.isEmpty())
        assertTrue(repo.deletedFutureOccurrences.isEmpty())
    }

    private companion object {
        /** What the user sees when a provider write fails with message "boom". */
        val writeFailedBoom = ErrorMapper.toPresentation(CalendarError.DeviceCalendar.WriteFailed("boom"))

        const val MASTER_ID = 10L
        const val EXCEPTION_ID = 11L
        const val PLAIN_ID = 12L
        const val CAL_ID = 5L
        const val COLOR = 0xFF445566.toInt()
        const val OCCURRENCE_TS = 1_700_038_800_000L

        val master = DeviceEvent(
            id = MASTER_ID,
            calendarId = CAL_ID,
            title = "Planning",
            description = null,
            location = null,
            startTs = 1_699_434_000_000L,
            endTs = null,
            duration = "PT1H30M",
            isAllDay = false,
            rrule = "FREQ=WEEKLY",
            rdate = null,
            exdate = null,
            exrule = null,
            timezone = "UTC",
            originalId = null,
            originalInstanceTime = null,
            status = CalendarContract.Events.STATUS_CONFIRMED,
            availability = 0,
            accessLevel = 0,
            calendarColor = null,
            eventColor = null,
        )
    }
}
