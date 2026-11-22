package org.onekash.kashcal.ui.viewmodels

import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.R
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.mapper.toFormState
import org.onekash.kashcal.domain.model.DisplayEvent
import io.mockk.every
import java.time.LocalDate
import java.util.TimeZone

/**
 * Tests the Undo window of drag-to-reschedule through the ViewModel's public surface: a drop
 * shows the event moved and writes nothing; Undo leaves no write behind; the snackbar ending
 * saves the move once through the same write as before the window existed; a series scope the
 * drop's check blocked, or hasn't cleared, is refused at the drop; and a cleared ViewModel
 * still saves. Room writes are checked on the strict [DeviceHomeViewModelTestFactory] mocks,
 * device writes on [FakeCalendarProviderRepository].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelDragUndoTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var factory: DeviceHomeViewModelTestFactory
    private lateinit var repo: FakeCalendarProviderRepository
    private lateinit var grid: MutableStateFlow<ImmutableList<DisplayEvent>>
    private lateinit var savedZone: TimeZone

    private val occurrenceStartTs = 1_709_650_800_000L // 2024-03-05T15:00:00Z, 10:00 in New York
    private val oneHourMs = 3_600_000L
    private val oneWeekMs = 7 * 24 * oneHourMs
    private val dropDate = LocalDate.of(2024, 3, 5)
    private val dropMinutes = 13 * 60
    private val droppedStartTs = occurrenceStartTs + 3 * oneHourMs

    @Before
    fun setup() {
        // The drops are same-day 13:00 drops in the event's zone, whatever zone the host runs in.
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        Dispatchers.setMain(testDispatcher)
        factory = DeviceHomeViewModelTestFactory()
        repo = FakeCalendarProviderRepository()
        grid = MutableStateFlow(persistentListOf())
        every { factory.displayEventRepository.getDisplayEventsForRange(any(), any()) } returns grid
        every { factory.context.getString(R.string.snackbar_action_undo) } returns UNDO
        // The save re-reads the row it moves; by default it is stored as the grid showed it.
        coEvery { factory.eventReader.getEventById(7L) } returns roomOneOff().second
        coEvery { factory.eventReader.getEventById(8L) } returns roomSeriesOccurrence().event
        coEvery { factory.eventReader.getExceptionsForMaster(8L) } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        TimeZone.setDefault(savedZone)
    }

    private fun TestScope.createViewModel(
        applicationScope: CoroutineScope = CoroutineScope(SupervisorJob()),
    ): HomeViewModel {
        val viewModel = factory.create(
            deviceEventReader = repo.deviceEventReader(),
            deviceEventWriter = repo.deviceEventWriter(factory.dataStore),
            ioDispatcher = testDispatcher,
            applicationScope = applicationScope,
        )
        // weekEvents runs only while collected; the time grid collects it.
        backgroundScope.launch { viewModel.weekEvents.collect {} }
        viewModel.setViewMode(ViewMode.WEEK)
        advanceUntilIdle()
        return viewModel
    }

    private fun roomOneOff(): Pair<DisplayEvent.Room, Event> {
        val event = Event(
            id = 7L, uid = "room-uid", calendarId = 1L, title = "Review",
            startTs = occurrenceStartTs, endTs = occurrenceStartTs + oneHourMs, dtstamp = occurrenceStartTs,
            timezone = "America/New_York",
        )
        val occurrence = Occurrence(
            eventId = 7L, calendarId = 1L, startTs = occurrenceStartTs, endTs = occurrenceStartTs + oneHourMs,
            startDay = 20240305, endDay = 20240305,
        )
        return DisplayEvent.Room(event, occurrence, calendar = null) to event
    }

    private fun roomSeriesOccurrence(): DisplayEvent.Room {
        val master = Event(
            id = 8L, uid = "series-uid", calendarId = 1L, title = "Weekly",
            startTs = occurrenceStartTs - oneWeekMs, endTs = occurrenceStartTs - oneWeekMs + oneHourMs,
            dtstamp = occurrenceStartTs, rrule = "FREQ=WEEKLY;BYDAY=TU", timezone = "America/New_York",
        )
        val occurrence = Occurrence(
            eventId = 8L, calendarId = 1L, startTs = occurrenceStartTs, endTs = occurrenceStartTs + oneHourMs,
            startDay = 20240305, endDay = 20240305,
        )
        return DisplayEvent.Room(master, occurrence, calendar = null)
    }

    private fun deviceInstance(
        eventId: Long = 100L,
        hasRrule: Boolean = false,
        rrule: String? = null,
        eventStartTs: Long = occurrenceStartTs,
    ) = DeviceCalendarInstance(
        instanceId = 900L + eventId,
        eventId = eventId,
        title = "Standup",
        description = "",
        location = "",
        startTs = occurrenceStartTs,
        endTs = occurrenceStartTs + oneHourMs,
        startDay = 20240305,
        endDay = 20240305,
        isAllDay = false,
        hasRrule = hasRrule,
        rrule = rrule,
        reminders = listOf(15),
        calendarId = 42L,
        calendarDisplayName = "Work",
        calendarColor = 0xFF445566.toInt(),
        eventColor = null,
        status = 1,
        availability = 0,
        hasAlarm = true,
        selfAttendeeStatus = 0,
        isWritable = true,
        originalId = null,
        originalInstanceTime = null,
        timezone = "America/New_York",
        eventStartTs = eventStartTs,
    )

    private fun deviceSeries() = deviceInstance(
        hasRrule = true, rrule = "FREQ=WEEKLY;BYDAY=TU", eventStartTs = occurrenceStartTs - oneWeekMs,
    )

    private fun seedRow(instance: DeviceCalendarInstance) {
        repo.seedRowFor(instance)
    }

    private fun showOnGrid(vararg events: DisplayEvent) {
        grid.value = events.toList().toPersistentList()
    }

    private fun assertNoProviderWrites() {
        assertEquals(emptyList<Any>(), repo.updatedEvents)
        assertEquals(emptyList<Any>(), repo.createdExceptions)
        assertEquals(emptyList<Any>(), repo.editedFutureSeries)
    }

    private fun assertNoRoomWrites() {
        coVerify(exactly = 0) { factory.eventCoordinator.updateEvent(any(), any()) }
        coVerify(exactly = 0) { factory.eventCoordinator.editSingleOccurrence(any(), any(), any(), any()) }
        coVerify(exactly = 0) { factory.eventCoordinator.editThisAndFuture(any(), any(), any(), any()) }
    }

    private fun HomeViewModel.tapUndo() = uiState.value.pendingSnackbarAction!!.invoke()

    /** What the snackbar host does when a message ends: report its id. */
    private fun HomeViewModel.endSnackbar() = clearSnackbar(uiState.value.pendingSnackbarId)

    // ==================== A drop shows the move and writes nothing ====================

    @Test
    fun `a Room drop shows the event moved, offers Undo and writes nothing`() = runTest {
        val (room, _) = roomOneOff()
        showOnGrid(room)
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        advanceUntilIdle()

        val shown = viewModel.weekEvents.value.timedEvents.single()
        assertEquals(droppedStartTs, shown.startTs)
        assertEquals(droppedStartTs + oneHourMs, shown.endTs)
        assertEquals(20240305, shown.startDay)
        assertEquals(DeviceHomeViewModelTestFactory.RESCHEDULED_MESSAGE, viewModel.uiState.value.pendingSnackbarMessage)
        assertEquals(UNDO, viewModel.uiState.value.pendingSnackbarActionLabel)
        assertNoRoomWrites()
    }

    @Test
    fun `a device drop shows the event moved and writes nothing`() = runTest {
        val instance = deviceInstance()
        showOnGrid(DisplayEvent.Device(instance))
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(DisplayEvent.Device(instance), dropDate, dropMinutes)
        advanceUntilIdle()

        assertEquals(droppedStartTs, viewModel.weekEvents.value.timedEvents.single().startTs)
        assertEquals(UNDO, viewModel.uiState.value.pendingSnackbarActionLabel)
        assertNoProviderWrites()
    }

    @Test
    fun `a drop on another day moves the day codes with it`() = runTest {
        val (room, _) = roomOneOff()
        showOnGrid(room)
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(room, dropDate.plusDays(1), 23 * 60)
        advanceUntilIdle()

        // 23:00 to midnight: a timed end at midnight belongs to the day before.
        val shown = viewModel.weekEvents.value.timedEvents.single()
        assertEquals(20240306, shown.startDay)
        assertEquals(20240306, shown.endDay)
    }

    // ==================== Undo ====================

    @Test
    fun `Undo then the snackbar ending writes nothing and shows the stored time`() = runTest {
        val (room, _) = roomOneOff()
        val instance = deviceInstance(eventId = 101L)
        showOnGrid(room, DisplayEvent.Device(instance))
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        viewModel.tapUndo()
        viewModel.endSnackbar()
        viewModel.rescheduleEvent(DisplayEvent.Device(instance), dropDate, dropMinutes)
        viewModel.tapUndo()
        viewModel.endSnackbar()
        advanceUntilIdle()

        assertNoRoomWrites()
        assertNoProviderWrites()
        assertNull(viewModel.pendingMoveState.value)
        assertTrue(viewModel.weekEvents.value.timedEvents.all { it.startTs == occurrenceStartTs })
    }

    @Test
    fun `Undo after the save started does nothing`() = runTest {
        val (room, event) = roomOneOff()
        coEvery { factory.eventCoordinator.updateEvent(any(), any()) } returns event
        showOnGrid(room)
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        val undo = viewModel.uiState.value.pendingSnackbarAction!!
        viewModel.endSnackbar()
        undo()
        advanceUntilIdle()

        coVerify(exactly = 1) { factory.eventCoordinator.updateEvent(any(), any()) }
    }

    // ==================== The snackbar ending saves once ====================

    @Test
    fun `the snackbar ending saves a Room move once with the dropped times`() = runTest {
        val (room, event) = roomOneOff()
        val written = mutableListOf<Event>()
        coEvery { factory.eventCoordinator.updateEvent(capture(written), any()) } returns event
        showOnGrid(room)
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        val id = viewModel.uiState.value.pendingSnackbarId
        viewModel.clearSnackbar(id)
        viewModel.clearSnackbar(id)
        viewModel.onClearedForTest()
        advanceUntilIdle()

        assertEquals(droppedStartTs, written.single().startTs)
        assertEquals(droppedStartTs + oneHourMs, written.single().endTs)
        assertNull(viewModel.pendingMoveState.value)
    }

    @Test
    fun `a stale snackbar id saves nothing`() = runTest {
        val instance = deviceInstance()
        showOnGrid(DisplayEvent.Device(instance))
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(DisplayEvent.Device(instance), dropDate, dropMinutes)
        viewModel.clearSnackbar(viewModel.uiState.value.pendingSnackbarId - 1)
        advanceUntilIdle()

        assertNoProviderWrites()
        assertNotNull(viewModel.pendingMoveState.value)
    }

    @Test
    fun `each device scope saves through its own write after the window`() = runTest {
        val series = deviceSeries()
        seedRow(series)
        showOnGrid(DisplayEvent.Device(series))
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(DisplayEvent.Device(series), dropDate, dropMinutes)
        advanceUntilIdle()
        viewModel.confirmReschedule(EditScope.THIS_EVENT)
        advanceUntilIdle()
        assertNoProviderWrites()
        viewModel.endSnackbar()
        advanceUntilIdle()
        assertEquals(1, repo.createdExceptions.size)

        viewModel.rescheduleEvent(DisplayEvent.Device(series), dropDate, dropMinutes)
        advanceUntilIdle()
        viewModel.confirmReschedule(EditScope.THIS_AND_FUTURE)
        advanceUntilIdle()
        assertEquals(0, repo.editedFutureSeries.size)
        viewModel.endSnackbar()
        advanceUntilIdle()
        assertEquals(1, repo.editedFutureSeries.size)
    }

    @Test
    fun `a Room single-occurrence move saves through editSingleOccurrence after the window`() = runTest {
        val room = roomSeriesOccurrence()
        coEvery { factory.eventCoordinator.editSingleOccurrence(any(), any(), any(), any()) } returns room.event
        showOnGrid(room)
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        advanceUntilIdle()
        viewModel.confirmReschedule(EditScope.THIS_EVENT)
        advanceUntilIdle()
        coVerify(exactly = 0) { factory.eventCoordinator.editSingleOccurrence(any(), any(), any(), any()) }

        viewModel.endSnackbar()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            factory.eventCoordinator.editSingleOccurrence(8L, occurrenceStartTs, any(), any())
        }
    }

    // ==================== A saved move stays drawn until the data catches up ====================

    @Test
    fun `a saved move stays drawn until the grid's data drops the original`() = runTest {
        val (room, event) = roomOneOff()
        coEvery { factory.eventCoordinator.updateEvent(any(), any()) } returns event
        showOnGrid(room)
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        viewModel.endSnackbar()
        runCurrent()

        assertEquals(PendingMove.State.SAVED, viewModel.pendingMoveState.value?.state)
        assertEquals(droppedStartTs, viewModel.weekEvents.value.timedEvents.single().startTs)

        val moved = room.copy(occurrence = room.occurrence.copy(startTs = droppedStartTs, endTs = droppedStartTs + oneHourMs))
        showOnGrid(moved)
        runCurrent()

        assertNull(viewModel.pendingMoveState.value)
        assertEquals(droppedStartTs, viewModel.weekEvents.value.timedEvents.single().startTs)
    }

    // ==================== Refused at the drop ====================

    @Test
    fun `a series scope whose check hasn't finished is refused at the drop`() = runTest {
        val room = roomSeriesOccurrence()
        showOnGrid(room)
        val viewModel = createViewModel()

        // Another day: the series scopes wait on the check, which never ran here.
        viewModel.rescheduleEvent(room, dropDate.plusDays(1), dropMinutes)
        assertNull(viewModel.uiState.value.pendingDragReschedule!!.blockedScopes)
        viewModel.confirmReschedule(EditScope.ALL_EVENTS)

        assertEquals(DeviceHomeViewModelTestFactory.RESCHEDULE_FAILED_MESSAGE, viewModel.uiState.value.pendingSnackbarMessage)
        assertNull(viewModel.pendingMoveState.value)
        assertEquals(occurrenceStartTs, viewModel.weekEvents.value.timedEvents.single().startTs)
    }

    @Test
    fun `a blocked series scope is refused at the drop`() = runTest {
        // No stored row: the rule can't be read, so the check blocks the series scopes.
        val series = deviceSeries()
        showOnGrid(DisplayEvent.Device(series))
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(DisplayEvent.Device(series), dropDate.plusDays(1), dropMinutes)
        advanceUntilIdle()
        assertTrue(EditScope.THIS_AND_FUTURE in viewModel.uiState.value.pendingDragReschedule!!.blockedScopes!!)
        viewModel.confirmReschedule(EditScope.THIS_AND_FUTURE)
        advanceUntilIdle()

        assertEquals(DeviceHomeViewModelTestFactory.RESCHEDULE_FAILED_MESSAGE, viewModel.uiState.value.pendingSnackbarMessage)
        assertNull(viewModel.pendingMoveState.value)
        assertNoProviderWrites()
    }

    // ==================== A cleared ViewModel still saves ====================

    @Test
    fun `clearing the ViewModel saves the move on the application scope`() = runTest {
        val instance = deviceInstance()
        seedRow(instance)
        showOnGrid(DisplayEvent.Device(instance))
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(DisplayEvent.Device(instance), dropDate, dropMinutes)
        // A cleared ViewModel's own scope is cancelled before onCleared runs.
        viewModel.viewModelScope.cancel()
        viewModel.onClearedForTest()
        advanceUntilIdle()

        assertEquals(droppedStartTs, repo.updatedEvents.single().startTs)
    }

    // ==================== Saved early ====================

    @Test
    fun `another snackbar saves the pending move first`() = runTest {
        val instance = deviceInstance()
        seedRow(instance)
        showOnGrid(DisplayEvent.Device(instance))
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(DisplayEvent.Device(instance), dropDate, dropMinutes)
        viewModel.showSnackbar("Synced")
        advanceUntilIdle()

        assertEquals(1, repo.updatedEvents.size)
        assertEquals("Synced", viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `dropping another event saves the first and stages the second`() = runTest {
        val first = deviceInstance(eventId = 100L)
        val second = deviceInstance(eventId = 101L)
        seedRow(first)
        seedRow(second)
        showOnGrid(DisplayEvent.Device(first), DisplayEvent.Device(second))
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(DisplayEvent.Device(first), dropDate, dropMinutes)
        viewModel.rescheduleEvent(DisplayEvent.Device(second), dropDate, dropMinutes + 60)
        advanceUntilIdle()

        assertEquals(listOf(100L), repo.updatedEvents.map { it.eventId })
        assertEquals(101L, (viewModel.pendingMoveState.value!!.original as DisplayEvent.Device).instance.eventId)
        assertEquals(PendingMove.State.PENDING, viewModel.pendingMoveState.value!!.state)
    }

    @Test
    fun `dropping the moved copy again moves the pending move and Undo returns it to the start`() = runTest {
        val instance = deviceInstance()
        showOnGrid(DisplayEvent.Device(instance))
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(DisplayEvent.Device(instance), dropDate, dropMinutes)
        val firstId = viewModel.uiState.value.pendingSnackbarId
        runCurrent()
        val shown = viewModel.weekEvents.value.timedEvents.single()
        assertEquals("the grid draws the moved copy", viewModel.pendingMoveState.value!!.shown, shown)
        viewModel.rescheduleEvent(shown, dropDate, dropMinutes + 120)
        advanceUntilIdle()

        assertNoProviderWrites()
        assertTrue(viewModel.uiState.value.pendingSnackbarId > firstId)
        assertEquals(droppedStartTs + 2 * oneHourMs, viewModel.weekEvents.value.timedEvents.single().startTs)
        assertEquals(instance, (viewModel.pendingMoveState.value!!.original as DisplayEvent.Device).instance)

        viewModel.tapUndo()
        viewModel.endSnackbar()
        advanceUntilIdle()

        assertNoProviderWrites()
        assertEquals(occurrenceStartTs, viewModel.weekEvents.value.timedEvents.single().startTs)
    }

    @Test
    fun `a moved series copy dropped on another day waits for its check and keeps the scope`() = runTest {
        val room = roomSeriesOccurrence()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val written = mutableListOf<Event>()
        coEvery { factory.eventReader.getEventById(8L) } returns room.event
        // The first drop's check reads at once; the moved copy's check waits on the gate.
        var reads = 0
        coEvery { factory.eventReader.getExceptionsForMaster(8L) } coAnswers { if (reads++ > 0) gate.await(); emptyList() }
        coEvery { factory.eventCoordinator.updateEvent(capture(written), any()) } returns room.event
        showOnGrid(room)
        val viewModel = createViewModel()
        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        advanceUntilIdle()
        viewModel.confirmReschedule(EditScope.ALL_EVENTS)
        val firstId = viewModel.uiState.value.pendingSnackbarId

        runCurrent()
        viewModel.rescheduleEvent(viewModel.weekEvents.value.timedEvents.single(), dropDate.plusDays(1), dropMinutes)
        runCurrent()
        assertNull("no second scope sheet", viewModel.uiState.value.pendingDragReschedule)
        viewModel.clearSnackbar(firstId)
        runCurrent()
        assertTrue("the first window ending saves nothing while checking", written.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(written.isEmpty())
        assertEquals(EditScope.ALL_EVENTS, viewModel.pendingMoveState.value!!.scope)
        assertEquals(droppedStartTs + 24 * oneHourMs, viewModel.pendingMoveState.value!!.newStartTs)

        viewModel.endSnackbar()
        advanceUntilIdle()
        // The stored rule goes to the writer, which rewrites it for the move (SeriesMove); the
        // series start moved a day, to Wednesday.
        assertEquals(room.event.rrule, written.single().rrule)
        assertEquals(java.time.DayOfWeek.WEDNESDAY, java.time.Instant.ofEpochMilli(written.single().startTs).atZone(java.time.ZoneId.systemDefault()).dayOfWeek)
    }

    @Test
    fun `a moved copy dropped where its scope is blocked saves the first move where it was`() = runTest {
        // A rule fixed to 10 o'clock can't follow a move to 13:00 on another day.
        val series = deviceSeries().copy(rrule = "FREQ=WEEKLY;BYDAY=TU;BYHOUR=10")
        seedRow(series)
        showOnGrid(DisplayEvent.Device(series))
        val viewModel = createViewModel()
        viewModel.rescheduleEvent(DisplayEvent.Device(series), dropDate, dropMinutes)
        advanceUntilIdle()
        viewModel.confirmReschedule(EditScope.THIS_AND_FUTURE)

        runCurrent()
        viewModel.rescheduleEvent(viewModel.weekEvents.value.timedEvents.single(), dropDate.plusDays(1), dropMinutes)
        advanceUntilIdle()

        assertEquals(DeviceHomeViewModelTestFactory.RESCHEDULE_FAILED_MESSAGE, viewModel.uiState.value.pendingSnackbarMessage)
        assertEquals(droppedStartTs, repo.editedFutureSeries.single().startTs)
    }

    @Test
    fun `leaving the screen while a moved copy is checked saves the first move`() = runTest {
        val room = roomSeriesOccurrence()
        val written = mutableListOf<Event>()
        coEvery { factory.eventReader.getEventById(8L) } returns room.event
        // The first drop's check reads at once; the moved copy's check never finishes.
        var reads = 0
        coEvery { factory.eventReader.getExceptionsForMaster(8L) } coAnswers {
            if (reads++ > 0) kotlinx.coroutines.awaitCancellation()
            emptyList()
        }
        coEvery { factory.eventCoordinator.updateEvent(capture(written), any()) } returns room.event
        showOnGrid(room)
        val viewModel = createViewModel()
        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        advanceUntilIdle()
        viewModel.confirmReschedule(EditScope.ALL_EVENTS)
        runCurrent()
        viewModel.rescheduleEvent(viewModel.weekEvents.value.timedEvents.single(), dropDate.plusDays(1), dropMinutes)
        runCurrent()

        viewModel.onHomeStopped()
        advanceUntilIdle()

        assertEquals("the same-day move saved", room.event.startTs + 3 * oneHourMs, written.single().startTs)
    }

    @Test
    fun `dropping the moved copy while it saves is refused`() = runTest {
        val (room, event) = roomOneOff()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { factory.eventCoordinator.updateEvent(any(), any()) } coAnswers { gate.await(); event }
        showOnGrid(room)
        val viewModel = createViewModel()
        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        viewModel.endSnackbar()
        runCurrent()
        assertEquals(PendingMove.State.SAVING, viewModel.pendingMoveState.value!!.state)

        runCurrent()
        viewModel.rescheduleEvent(viewModel.weekEvents.value.timedEvents.single(), dropDate, dropMinutes + 60)

        assertEquals(DeviceHomeViewModelTestFactory.RESCHEDULE_FAILED_MESSAGE, viewModel.uiState.value.pendingSnackbarMessage)
        gate.complete(Unit)
        advanceUntilIdle()
        coVerify(exactly = 1) { factory.eventCoordinator.updateEvent(any(), any()) }
    }

    @Test
    fun `after a single-occurrence move another occurrence of the series can be dragged`() = runTest {
        val room = roomSeriesOccurrence()
        val nextWeek = room.copy(
            occurrence = room.occurrence.copy(startTs = occurrenceStartTs + oneWeekMs, endTs = occurrenceStartTs + oneWeekMs + oneHourMs),
        )
        coEvery { factory.eventCoordinator.editSingleOccurrence(any(), any(), any(), any()) } returns room.event
        showOnGrid(room, nextWeek)
        val viewModel = createViewModel()
        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        advanceUntilIdle()
        viewModel.confirmReschedule(EditScope.THIS_EVENT)

        viewModel.rescheduleEvent(nextWeek, dropDate.plusWeeks(1), dropMinutes)
        advanceUntilIdle()
        coVerify(exactly = 1) { factory.eventCoordinator.editSingleOccurrence(8L, occurrenceStartTs, any(), any()) }
        assertNotNull("the second drop opens its scope sheet", viewModel.uiState.value.pendingDragReschedule)

        viewModel.confirmReschedule(EditScope.THIS_EVENT)
        viewModel.endSnackbar()
        advanceUntilIdle()
        coVerify(exactly = 1) { factory.eventCoordinator.editSingleOccurrence(8L, occurrenceStartTs + oneWeekMs, any(), any()) }
    }

    @Test
    fun `after a series-wide move another occurrence of the series is refused`() = runTest {
        val room = roomSeriesOccurrence()
        val nextWeek = room.copy(
            occurrence = room.occurrence.copy(startTs = occurrenceStartTs + oneWeekMs, endTs = occurrenceStartTs + oneWeekMs + oneHourMs),
        )
        coEvery { factory.eventCoordinator.editThisAndFuture(any(), any(), any(), any()) } returns room.event
        showOnGrid(room, nextWeek)
        val viewModel = createViewModel()
        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        advanceUntilIdle()
        viewModel.confirmReschedule(EditScope.THIS_AND_FUTURE)

        viewModel.rescheduleEvent(nextWeek, dropDate.plusWeeks(1), dropMinutes)
        advanceUntilIdle()

        coVerify(exactly = 1) { factory.eventCoordinator.editThisAndFuture(any(), any(), any(), any()) }
        assertEquals(DeviceHomeViewModelTestFactory.RESCHEDULE_FAILED_MESSAGE, viewModel.uiState.value.pendingSnackbarMessage)
        assertNull(viewModel.uiState.value.pendingDragReschedule)
        assertNull(viewModel.pendingMoveState.value)
    }

    @Test
    fun `a tap saves the move and opens anything but a stale copy`() = runTest {
        val room = roomSeriesOccurrence()
        val nextWeek = room.copy(
            occurrence = room.occurrence.copy(startTs = occurrenceStartTs + oneWeekMs, endTs = occurrenceStartTs + oneWeekMs + oneHourMs),
        )
        val other = DisplayEvent.Device(deviceInstance(eventId = 300L))
        coEvery { factory.eventCoordinator.editSingleOccurrence(any(), any(), any(), any()) } returns room.event
        coEvery { factory.eventCoordinator.updateEvent(any(), any()) } returns room.event
        showOnGrid(room, nextWeek, other)
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        advanceUntilIdle()
        viewModel.confirmReschedule(EditScope.THIS_EVENT)
        val shown = viewModel.pendingMoveState.value!!.shown
        assertEquals("the moved copy opens nothing", false, viewModel.onTimeGridEventTap(shown))
        advanceUntilIdle()
        coVerify(exactly = 1) { factory.eventCoordinator.editSingleOccurrence(any(), any(), any(), any()) }

        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        advanceUntilIdle()
        viewModel.confirmReschedule(EditScope.THIS_EVENT)
        assertEquals("after one occurrence moved, the rest open", true, viewModel.onTimeGridEventTap(nextWeek))

        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        advanceUntilIdle()
        viewModel.confirmReschedule(EditScope.ALL_EVENTS)
        assertEquals("after the series moved, its other copies don't", false, viewModel.onTimeGridEventTap(nextWeek))
        assertEquals(PendingMove.State.SAVING, viewModel.pendingMoveState.value!!.state)
        assertEquals("a stale copy stays closed while saving", false, viewModel.onTimeGridEventTap(nextWeek))
        assertEquals("other events open", true, viewModel.onTimeGridEventTap(other))
        advanceUntilIdle()
        coVerify(exactly = 1) { factory.eventCoordinator.updateEvent(any(), any()) }
    }

    @Test
    fun `leaving the time grid saves the move and switching between grids doesn't`() = runTest {
        val instance = deviceInstance()
        seedRow(instance)
        showOnGrid(DisplayEvent.Device(instance))
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(DisplayEvent.Device(instance), dropDate, dropMinutes)
        viewModel.setViewMode(ViewMode.DAY)
        viewModel.setViewMode(ViewMode.THREE_DAYS)
        advanceUntilIdle()
        assertNoProviderWrites()

        viewModel.setViewMode(ViewMode.MONTH)
        advanceUntilIdle()
        assertEquals(1, repo.updatedEvents.size)
    }

    @Test
    fun `opening Insights saves the move`() = runTest {
        val instance = deviceInstance()
        seedRow(instance)
        showOnGrid(DisplayEvent.Device(instance))
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(DisplayEvent.Device(instance), dropDate, dropMinutes)
        viewModel.setViewMode(ViewMode.INSIGHTS)
        advanceUntilIdle()

        assertEquals(1, repo.updatedEvents.size)
    }

    @Test
    fun `Home stopping saves the move once and takes its Undo down`() = runTest {
        val instance = deviceInstance()
        seedRow(instance)
        showOnGrid(DisplayEvent.Device(instance))
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(DisplayEvent.Device(instance), dropDate, dropMinutes)
        val id = viewModel.uiState.value.pendingSnackbarId
        viewModel.onHomeStopped()
        assertNull(viewModel.uiState.value.pendingSnackbarMessage)
        viewModel.clearSnackbar(id)
        viewModel.onClearedForTest()
        advanceUntilIdle()

        assertEquals(1, repo.updatedEvents.size)
    }

    @Test
    fun `a second drop keeps the first move drawn while it saves`() = runTest {
        val (room, event) = roomOneOff()
        val instance = deviceInstance(eventId = 101L)
        seedRow(instance)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { factory.eventCoordinator.updateEvent(any(), any()) } coAnswers { gate.await(); event }
        showOnGrid(room, DisplayEvent.Device(instance))
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        viewModel.rescheduleEvent(DisplayEvent.Device(instance), dropDate, dropMinutes + 120)
        runCurrent()

        val starts = viewModel.weekEvents.value.timedEvents.associate { it.stableKey.substringBefore(':') to it.startTs }
        assertEquals("the saving move stays drawn", droppedStartTs, starts["room"])
        assertEquals("the new move is drawn", droppedStartTs + 2 * oneHourMs, starts["device"])
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a saved move in the second slot is released when the grid catches up`() = runTest {
        val (room, event) = roomOneOff()
        val instance = deviceInstance(eventId = 101L)
        seedRow(instance)
        coEvery { factory.eventCoordinator.updateEvent(any(), any()) } returns event
        showOnGrid(room, DisplayEvent.Device(instance))
        val viewModel = createViewModel()
        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        viewModel.rescheduleEvent(DisplayEvent.Device(instance), dropDate, dropMinutes + 120)
        runCurrent()
        assertEquals(PendingMove.State.SAVED, viewModel.settlingMoveState.value?.state)

        val movedRoom = room.copy(occurrence = room.occurrence.copy(startTs = droppedStartTs, endTs = droppedStartTs + oneHourMs))
        showOnGrid(movedRoom, DisplayEvent.Device(instance))
        runCurrent()

        assertNull(viewModel.settlingMoveState.value)
        assertEquals(PendingMove.State.PENDING, viewModel.pendingMoveState.value?.state)
        val device = viewModel.weekEvents.value.timedEvents.single { it.stableKey.startsWith("device") }
        assertEquals("the newer move is still drawn", droppedStartTs + 2 * oneHourMs, device.startTs)
    }

    @Test
    fun `a grid refresh during the save releases the move as soon as it is written`() = runTest {
        val (room, event) = roomOneOff()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { factory.eventCoordinator.updateEvent(any(), any()) } coAnswers { gate.await(); event }
        showOnGrid(room)
        val viewModel = createViewModel()
        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        viewModel.endSnackbar()
        runCurrent()
        assertEquals(PendingMove.State.SAVING, viewModel.pendingMoveState.value?.state)

        // The data shows the move before the write returns.
        showOnGrid(room.copy(occurrence = room.occurrence.copy(startTs = droppedStartTs, endTs = droppedStartTs + oneHourMs)))
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertNull("released without waiting for the fallback", viewModel.pendingMoveState.value)
    }

    @Test
    fun `a moved one-off dragged again after its save moves again`() = runTest {
        val (room, event) = roomOneOff()
        val written = mutableListOf<Event>()
        coEvery { factory.eventCoordinator.updateEvent(capture(written), any()) } answers { written.last() }
        showOnGrid(room)
        val viewModel = createViewModel()
        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        runCurrent()
        val shown = viewModel.weekEvents.value.timedEvents.single()
        viewModel.endSnackbar()
        advanceUntilIdle()
        assertNull("the first move is saved and released", viewModel.pendingMoveState.value)
        coEvery { factory.eventReader.getEventById(7L) } returns written.single()

        // The copy still under the finger is the one drawn before the save.
        viewModel.rescheduleEvent(shown, dropDate, dropMinutes + 60)
        viewModel.endSnackbar()
        advanceUntilIdle()

        assertEquals("both moves saved, no changed-elsewhere", listOf(droppedStartTs, droppedStartTs + oneHourMs), written.map { it.startTs })
        assertNull(viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `after a device single-occurrence move another occurrence of the series saves too`() = runTest {
        val series = deviceSeries()
        seedRow(series)
        val nextWeek = series.copy(instanceId = 2_000L, startTs = occurrenceStartTs + oneWeekMs, endTs = occurrenceStartTs + oneWeekMs + oneHourMs)
        showOnGrid(DisplayEvent.Device(series), DisplayEvent.Device(nextWeek))
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(DisplayEvent.Device(series), dropDate, dropMinutes)
        advanceUntilIdle()
        viewModel.confirmReschedule(EditScope.THIS_EVENT)
        viewModel.rescheduleEvent(DisplayEvent.Device(nextWeek), dropDate.plusWeeks(1), dropMinutes)
        advanceUntilIdle()
        viewModel.confirmReschedule(EditScope.THIS_EVENT)
        viewModel.endSnackbar()
        advanceUntilIdle()

        // The first save leaves the series row as the second drop read it.
        assertEquals(listOf(occurrenceStartTs, occurrenceStartTs + oneWeekMs), repo.createdExceptions.map { it.originalInstanceTime })
    }

    // ==================== Re-read at the save ====================

    @Test
    fun `a title a sync pulled in during the window survives the save`() = runTest {
        val (room, event) = roomOneOff()
        val written = mutableListOf<Event>()
        coEvery { factory.eventCoordinator.updateEvent(capture(written), any()) } returns event
        showOnGrid(room)
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        coEvery { factory.eventReader.getEventById(7L) } returns event.copy(title = "Review (from the server)")
        viewModel.endSnackbar()
        advanceUntilIdle()

        assertEquals("Review (from the server)", written.single().title)
        assertEquals(droppedStartTs, written.single().startTs)
    }

    @Test
    fun `a Room event moved elsewhere during the window isn't moved again`() = runTest {
        val (room, event) = roomOneOff()
        showOnGrid(room)
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(room, dropDate, dropMinutes)
        coEvery { factory.eventReader.getEventById(7L) } returns event.copy(startTs = event.startTs + oneHourMs)
        viewModel.endSnackbar()
        advanceUntilIdle()

        assertNoRoomWrites()
        assertEquals("${DeviceHomeViewModelTestFactory.CHANGED_ELSEWHERE_MESSAGE}:Review", viewModel.uiState.value.pendingSnackbarMessage)
        assertNull(viewModel.uiState.value.pendingSnackbarAction)
        assertNull(viewModel.pendingMoveState.value)
    }

    @Test
    fun `a Room event deleted during the window isn't brought back`() = runTest {
        val (room, event) = roomOneOff()
        showOnGrid(room)
        val viewModel = createViewModel()

        listOf(null, event.copy(syncStatus = org.onekash.kashcal.data.db.entity.SyncStatus.PENDING_DELETE)).forEach { stored ->
            viewModel.rescheduleEvent(room, dropDate, dropMinutes)
            coEvery { factory.eventReader.getEventById(7L) } returns stored
            viewModel.endSnackbar()
            advanceUntilIdle()

            assertEquals("${DeviceHomeViewModelTestFactory.MOVE_FAILED_MESSAGE}:Review", viewModel.uiState.value.pendingSnackbarMessage)
            assertNull("no retry for a row that is gone", viewModel.uiState.value.pendingSnackbarAction)
            viewModel.clearSnackbar()
        }
        assertNoRoomWrites()
    }

    @Test
    fun `a device event edited elsewhere during the window isn't overwritten`() = runTest {
        val instance = deviceInstance()
        seedRow(instance)
        showOnGrid(DisplayEvent.Device(instance))
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(DisplayEvent.Device(instance), dropDate, dropMinutes)
        repo.deviceEvents[100L] = repo.deviceEvents.getValue(100L).copy(title = "Standup (renamed)")
        viewModel.endSnackbar()
        advanceUntilIdle()

        assertNoProviderWrites()
        assertEquals("${DeviceHomeViewModelTestFactory.CHANGED_ELSEWHERE_MESSAGE}:Standup", viewModel.uiState.value.pendingSnackbarMessage)
        assertEquals(occurrenceStartTs, viewModel.weekEvents.value.timedEvents.single().startTs)
    }

    @Test
    fun `a device event deleted during the window offers a retry`() = runTest {
        val instance = deviceInstance()
        seedRow(instance)
        showOnGrid(DisplayEvent.Device(instance))
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(DisplayEvent.Device(instance), dropDate, dropMinutes)
        repo.activeEventIds -= 100L
        viewModel.endSnackbar()
        advanceUntilIdle()

        assertNoProviderWrites()
        assertEquals("${DeviceHomeViewModelTestFactory.MOVE_FAILED_MESSAGE}:Standup", viewModel.uiState.value.pendingSnackbarMessage)
        assertEquals(DeviceHomeViewModelTestFactory.RETRY_LABEL, viewModel.uiState.value.pendingSnackbarActionLabel)
    }

    @Test
    fun `a failed write snaps back, names the event and Retry saves it`() = runTest {
        val instance = deviceInstance()
        seedRow(instance)
        showOnGrid(DisplayEvent.Device(instance))
        repo.writeFailure = org.onekash.kashcal.error.CalendarError.DeviceCalendar.WriteFailed("provider rejected")
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(DisplayEvent.Device(instance), dropDate, dropMinutes)
        viewModel.endSnackbar()
        advanceUntilIdle()

        assertEquals(occurrenceStartTs, viewModel.weekEvents.value.timedEvents.single().startTs)
        assertEquals("${DeviceHomeViewModelTestFactory.MOVE_FAILED_MESSAGE}:Standup", viewModel.uiState.value.pendingSnackbarMessage)
        assertEquals(DeviceHomeViewModelTestFactory.RETRY_LABEL, viewModel.uiState.value.pendingSnackbarActionLabel)

        repo.writeFailure = null
        viewModel.uiState.value.pendingSnackbarAction!!.invoke()
        advanceUntilIdle()

        assertEquals(droppedStartTs, repo.updatedEvents.single().startTs)
    }

    @Test
    fun `an untitled event is named in the failure`() = runTest {
        val instance = deviceInstance().copy(title = "")
        seedRow(instance)
        showOnGrid(DisplayEvent.Device(instance))
        repo.writeFailure = org.onekash.kashcal.error.CalendarError.DeviceCalendar.WriteFailed("provider rejected")
        val viewModel = createViewModel()

        viewModel.rescheduleEvent(DisplayEvent.Device(instance), dropDate, dropMinutes)
        viewModel.endSnackbar()
        advanceUntilIdle()

        assertEquals(
            "${DeviceHomeViewModelTestFactory.MOVE_FAILED_MESSAGE}:${DeviceHomeViewModelTestFactory.UNTITLED}",
            viewModel.uiState.value.pendingSnackbarMessage,
        )
    }

    @Test
    fun `a series that can no longer follow the move is refused without a retry`() = runTest {
        val room = roomSeriesOccurrence()
        showOnGrid(room)
        val viewModel = createViewModel()
        viewModel.rescheduleEvent(room, dropDate.plusDays(1), dropMinutes)
        advanceUntilIdle()
        viewModel.confirmReschedule(EditScope.ALL_EVENTS)

        // An occurrence edited during the window: moving the series would strand it.
        val edited = room.event.copy(
            id = 9L, rrule = null, originalEventId = 8L, originalInstanceTime = occurrenceStartTs + oneWeekMs,
            startTs = occurrenceStartTs + oneWeekMs + oneHourMs, endTs = occurrenceStartTs + oneWeekMs + 2 * oneHourMs,
        )
        coEvery { factory.eventReader.getExceptionsForMaster(8L) } returns listOf(edited)
        viewModel.endSnackbar()
        advanceUntilIdle()

        assertNoRoomWrites()
        assertEquals("${DeviceHomeViewModelTestFactory.CHANGED_ELSEWHERE_MESSAGE}:Weekly", viewModel.uiState.value.pendingSnackbarMessage)
        assertNull(viewModel.uiState.value.pendingSnackbarAction)
    }

    // ==================== Only the drag waits ====================

    @Test
    fun `a time edit from the form writes at once`() = runTest {
        val row = deviceInstance(eventId = 200L)
        seedRow(row)
        val viewModel = createViewModel()
        val formState = repo.deviceEvents.getValue(200L).toFormState(
            reminders = emptyList(),
            calendarColor = null,
            calendarName = "Work",
            deviceCalendarGroups = emptyList(),
            occurrenceTs = null,
        )

        val result = viewModel.saveDeviceEvent(formState.copy(startHour = 14, endHour = 15))

        assertTrue(result.isSuccess)
        val written = repo.updatedEvents.single()
        assertEquals(200L, written.eventId)
        assertEquals("the form's time, written with no Undo window", droppedStartTs + oneHourMs, written.startTs)
        assertNull(viewModel.pendingMoveState.value)
    }

    private companion object {
        const val UNDO = "res:snackbar_action_undo"
    }
}
