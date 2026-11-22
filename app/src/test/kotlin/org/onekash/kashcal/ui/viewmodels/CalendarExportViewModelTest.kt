package org.onekash.kashcal.ui.viewmodels

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.model.EventExport
import org.onekash.kashcal.util.IcsExporter
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarExportViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val coordinator = mockk<EventCoordinator>()
    private val exporter = mockk<IcsExporter>()
    private val resources = mockk<Resources>()
    private val context = mockk<Context> { every { resources } returns this@CalendarExportViewModelTest.resources }
    private val uri = mockk<Uri>()

    private val calendar = Calendar(
        id = 7, accountId = 3, caldavUrl = "https://dav.example.test/cal/home/", displayName = "Home", color = 0
    )
    private val event = Event(
        id = 11, uid = "e@example.test", calendarId = 7, title = "Lunch", startTs = 0, endTs = 3_600_000, dtstamp = 0
    )
    private val guests = mapOf(11L to listOf(Attendee(eventId = 11, address = "mailto:ann@example.test")))
    private val exports = listOf(EventExport(event, emptyList(), guests))
    private val counts = MutableStateFlow(mapOf(7L to 1))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { coordinator.getCalendarEventCounts() } returns counts
        every { coordinator.getAllAccounts() } returns flowOf(
            listOf(Account(id = 3, provider = AccountProvider.CALDAV, email = "user@example.test"))
        )
        coEvery { coordinator.getCalendarById(7) } returns calendar
        coEvery { coordinator.getCalendarEventsForExport(7) } returns exports
        every { exporter.exportCalendar(context, any(), "Home") } returns Result.success(uri)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = CalendarExportViewModel(coordinator, exporter, context, dispatcher)

    @Test
    fun `an export shares the file and passes every row's guests to the exporter`() = runTest(dispatcher) {
        val written = slot<List<EventExport>>()
        every { exporter.exportCalendar(context, capture(written), "Home") } returns Result.success(uri)
        val viewModel = viewModel()

        viewModel.export(7, CalendarExportOrigin.EXPORT_SHEET)
        advanceUntilIdle()

        assertEquals(listOf(CalendarExportEvent.Share(uri)), viewModel.events.take(1).toList())
        assertEquals(guests, written.captured.single().attendeesByEventId)
        assertEquals(CalendarExportState(), viewModel.state.value)
    }

    @Test
    fun `a second export while one runs is ignored`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { coordinator.getCalendarEventsForExport(7) } coAnswers { gate.await(); exports }
        val viewModel = viewModel()

        viewModel.export(7, CalendarExportOrigin.EXPORT_SHEET)
        runCurrent()
        viewModel.export(8, CalendarExportOrigin.EXPORT_SHEET)
        assertEquals(7L, viewModel.state.value.busyCalendarId)
        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 0) { coordinator.getCalendarById(8) }
        verify(exactly = 1) { exporter.exportCalendar(any(), any(), any()) }
    }

    @Test
    fun `Preparing shows only once the export has run past the delay`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { coordinator.getCalendarEventsForExport(7) } coAnswers { gate.await(); exports }
        val viewModel = viewModel()

        viewModel.export(7, CalendarExportOrigin.EXPORT_SHEET)
        advanceTimeBy(CalendarExportViewModel.PREPARING_DELAY_MS - 1)
        runCurrent()
        assertEquals(7L, viewModel.state.value.busyCalendarId)
        assertFalse("not yet", viewModel.state.value.showPreparing)

        advanceTimeBy(2)
        runCurrent()
        assertTrue("past the delay", viewModel.state.value.showPreparing)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(CalendarExportState(), viewModel.state.value)
    }

    @Test
    fun `a quick export never shows Preparing`() = runTest(dispatcher) {
        val viewModel = viewModel()
        val seen = mutableListOf<CalendarExportState>()
        val watch = launch { viewModel.state.collect { seen += it } }

        viewModel.export(7, CalendarExportOrigin.EXPORT_SHEET)
        advanceUntilIdle()
        watch.cancel()

        assertTrue("states: $seen", seen.none { it.showPreparing })
    }

    @Test
    fun `a file failure in the export sheet stays with its row until cleared`() = runTest(dispatcher) {
        every { exporter.exportCalendar(context, any(), "Home") } returns Result.failure(IllegalStateException("disk"))
        val viewModel = viewModel()

        viewModel.export(7, CalendarExportOrigin.EXPORT_SHEET)
        advanceUntilIdle()
        assertEquals(CalendarExportState(failedCalendarId = 7), viewModel.state.value)

        viewModel.clearFailure()
        assertNull(viewModel.state.value.failedCalendarId)
    }

    @Test
    fun `an exception while building is a file failure, not its message`() = runTest(dispatcher) {
        every { exporter.exportCalendar(context, any(), "Home") } throws IllegalStateException("boom")
        val viewModel = viewModel()

        viewModel.export(7, CalendarExportOrigin.CALENDAR_SHEET)
        advanceUntilIdle()

        assertEquals(
            listOf(CalendarExportEvent.Failed(CalendarExportFailure.FILE_FAILED)),
            viewModel.events.take(1).toList(),
        )
        assertEquals(CalendarExportState(), viewModel.state.value)
    }

    @Test
    fun `another export clears the failure, and a retry that works leaves none`() = runTest(dispatcher) {
        every { exporter.exportCalendar(context, any(), "Home") } returns Result.failure(IllegalStateException())
        val viewModel = viewModel()
        viewModel.export(7, CalendarExportOrigin.EXPORT_SHEET)
        advanceUntilIdle()

        every { exporter.exportCalendar(context, any(), "Home") } returns Result.success(uri)
        viewModel.export(7, CalendarExportOrigin.EXPORT_SHEET)
        runCurrent()
        assertNull("cleared once the retry starts", viewModel.state.value.failedCalendarId)
        advanceUntilIdle()

        assertEquals(CalendarExportState(), viewModel.state.value)
    }

    @Test
    fun `an empty or missing calendar leaves no error in the export sheet`() = runTest(dispatcher) {
        coEvery { coordinator.getCalendarEventsForExport(7) } returns emptyList()
        coEvery { coordinator.getCalendarById(9) } returns null
        val viewModel = viewModel()

        viewModel.export(7, CalendarExportOrigin.EXPORT_SHEET)
        advanceUntilIdle()
        viewModel.export(9, CalendarExportOrigin.EXPORT_SHEET)
        advanceUntilIdle()

        assertEquals(CalendarExportState(), viewModel.state.value)
        verify(exactly = 0) { exporter.exportCalendar(any(), any(), any()) }
    }

    @Test
    fun `from a calendar's own sheet every failure becomes a typed event`() = runTest(dispatcher) {
        coEvery { coordinator.getCalendarEventsForExport(7) } returns emptyList()
        coEvery { coordinator.getCalendarById(9) } returns null
        val viewModel = viewModel()

        viewModel.export(7, CalendarExportOrigin.CALENDAR_SHEET)
        advanceUntilIdle()
        viewModel.export(9, CalendarExportOrigin.CALENDAR_SHEET)
        advanceUntilIdle()

        assertEquals(
            listOf(
                CalendarExportEvent.Failed(CalendarExportFailure.NO_EVENTS),
                CalendarExportEvent.Failed(CalendarExportFailure.CALENDAR_NOT_FOUND),
            ),
            viewModel.events.take(2).toList(),
        )
        assertEquals(CalendarExportState(), viewModel.state.value)
    }

    @Test
    fun `counts start unknown, then follow the reader, and logins map each account`() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertNull("not loaded yet", viewModel.eventCounts.value)

        val seenCounts = launch { viewModel.eventCounts.collect {} }
        val seenLogins = launch { viewModel.accountLogins.collect {} }
        runCurrent()
        assertEquals(mapOf(7L to 1), viewModel.eventCounts.value)
        assertEquals(mapOf(3L to "user@example.test"), viewModel.accountLogins.value)

        counts.value = mapOf(7L to 0)
        runCurrent()
        assertEquals(mapOf(7L to 0), viewModel.eventCounts.value)
        seenCounts.cancel()
        seenLogins.cancel()
    }

    @Test
    fun `the file is built on the IO dispatcher`() = runTest(dispatcher) {
        val io = MarkingDispatcher(StandardTestDispatcher(testScheduler))
        var builtOnIo: Boolean? = null
        every { exporter.exportCalendar(context, any(), "Home") } answers {
            builtOnIo = io.running
            Result.success(uri)
        }
        val viewModel = CalendarExportViewModel(coordinator, exporter, context, io)

        viewModel.export(7, CalendarExportOrigin.EXPORT_SHEET)
        advanceUntilIdle()

        assertEquals(true, builtOnIo)
    }

    @Test
    fun `a failure that lands after the sheet closed is dropped`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { coordinator.getCalendarEventsForExport(7) } coAnswers { gate.await(); exports }
        every { exporter.exportCalendar(context, any(), "Home") } returns Result.failure(IllegalStateException())
        val viewModel = viewModel()

        viewModel.export(7, CalendarExportOrigin.EXPORT_SHEET)
        runCurrent()
        viewModel.clearFailure()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(CalendarExportState(), viewModel.state.value)
    }

    @Test
    fun `a read that throws is a file failure`() = runTest(dispatcher) {
        coEvery { coordinator.getCalendarEventsForExport(7) } throws IllegalStateException("db")
        val viewModel = viewModel()

        viewModel.export(7, CalendarExportOrigin.EXPORT_SHEET)
        advanceUntilIdle()

        assertEquals(CalendarExportState(failedCalendarId = 7), viewModel.state.value)
    }

    /** Runs [delegate]'s work and tells whether a block is running on it right now. */
    private class MarkingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var running = false
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) =
            delegate.dispatch(context, Runnable {
                running = true
                try { block.run() } finally { running = false }
            })
    }
}
