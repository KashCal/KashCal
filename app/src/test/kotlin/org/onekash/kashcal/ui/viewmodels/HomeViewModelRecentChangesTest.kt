package org.onekash.kashcal.ui.viewmodels

import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.changes.RecentChangeItem
import org.onekash.kashcal.sync.model.ChangeType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the Recent changes sheet's state in [HomeViewModel]: opening it shows the log, a swipe
 * dismisses a row with no undo, Clear all dismisses the rows shown and offers one undo, and
 * [HomeViewModel.resolveRecentChangeTarget] picks what a tapped row opens (a series at its next
 * occurrence). The tap's wiring to the quick view isn't driven here. Collaborators are the
 * strict mocks of [DeviceHomeViewModelTestFactory], with each Recent changes call stubbed here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HomeViewModelRecentChangesTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var factory: DeviceHomeViewModelTestFactory
    private lateinit var log: MutableStateFlow<List<RecentChangeItem>>

    private val series = Event(
        id = 10, uid = "series", calendarId = 1, title = "Standup", startTs = 1_000, endTs = 2_000,
        rrule = "FREQ=WEEKLY", dtstamp = 0
    )
    private val oneOff = Event(id = 11, uid = "one", calendarId = 1, title = "Dentist", startTs = 5_000, endTs = 6_000, dtstamp = 0)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        factory = DeviceHomeViewModelTestFactory()
        log = MutableStateFlow(listOf(item(1, "series", eventId = 10, recurring = true), item(2, "one", eventId = 11)))
        every { factory.eventReader.observeRecentChanges(any()) } returns log
        coJustRun { factory.eventCoordinator.dismissRecentChanges(any()) }
        coJustRun { factory.eventCoordinator.restoreRecentChanges(any()) }
        coEvery { factory.eventCoordinator.getEventById(10) } returns series
        coEvery { factory.eventCoordinator.getEventById(11) } returns oneOff
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun item(
        id: Long,
        uid: String,
        eventId: Long?,
        recurring: Boolean = false,
        instanceTs: Long = 0,
        type: ChangeType = ChangeType.MODIFIED,
    ) = RecentChangeItem(
        id = id, calendarId = 1, eventUid = uid, instanceTs = instanceTs, eventId = eventId,
        changeType = type, title = uid, startTs = 1_000, endTs = 2_000, isAllDay = false,
        isRecurring = recurring, detectedAt = 0, changedFields = emptySet(), previousStartTs = null,
        previousIsAllDay = null, calendarName = "Work", calendarColor = 0
    )

    private fun occurrence(startTs: Long, exceptionEventId: Long? = null) = Occurrence(
        eventId = 10, calendarId = 1, startTs = startTs, endTs = startTs + 1_000,
        startDay = 20261012, endDay = 20261012, exceptionEventId = exceptionEventId
    )

    private fun createViewModel(): HomeViewModel {
        val device = FakeCalendarProviderRepository()
        return factory.create(device.deviceEventReader(), device.deviceEventWriter(factory.dataStore), dispatcher)
    }

    @Test
    fun `opening the sheet shows the log`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.openRecentChanges()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isRecentChangesOpen)
        assertEquals(listOf(1L, 2L), viewModel.uiState.value.recentChanges.map { it.id })
    }

    @Test
    fun `the log updates while the sheet is open and stops being read once closed`() = runTest {
        val viewModel = createViewModel()
        viewModel.openRecentChanges()
        advanceUntilIdle()

        log.value = listOf(item(3, "fresh", eventId = null))
        advanceUntilIdle()
        assertEquals(listOf(3L), viewModel.uiState.value.recentChanges.map { it.id })

        viewModel.closeRecentChanges()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isRecentChangesOpen)
        assertEquals("no collector is left on the log", 0, log.subscriptionCount.value)
    }

    @Test
    fun `swiping a row dismisses it with no undo`() = runTest {
        val viewModel = createViewModel()
        viewModel.openRecentChanges()
        advanceUntilIdle()

        viewModel.dismissRecentChange(2)
        advanceUntilIdle()

        coVerify(exactly = 1) { factory.eventCoordinator.dismissRecentChanges(listOf(2L)) }
        assertTrue(viewModel.uiState.value.recentChangesClearedIds.isEmpty())
        assertNull(viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `clear all dismisses the rows shown and undo restores exactly them`() = runTest {
        val viewModel = createViewModel()
        viewModel.openRecentChanges()
        advanceUntilIdle()

        viewModel.clearAllRecentChanges()
        advanceUntilIdle()

        coVerify(exactly = 1) { factory.eventCoordinator.dismissRecentChanges(listOf(1L, 2L)) }
        assertEquals(listOf(1L, 2L), viewModel.uiState.value.recentChangesClearedIds)

        viewModel.undoClearRecentChanges()
        advanceUntilIdle()

        coVerify(exactly = 1) { factory.eventCoordinator.restoreRecentChanges(listOf(1L, 2L)) }
        assertTrue(viewModel.uiState.value.recentChangesClearedIds.isEmpty())
    }

    @Test
    fun `the undo offer ends once shown without restoring`() = runTest {
        val viewModel = createViewModel()
        viewModel.openRecentChanges()
        advanceUntilIdle()
        viewModel.clearAllRecentChanges()
        advanceUntilIdle()

        viewModel.onRecentChangesUndoShown()
        viewModel.undoClearRecentChanges()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.recentChangesClearedIds.isEmpty())
        coVerify(exactly = 0) { factory.eventCoordinator.restoreRecentChanges(any()) }
    }

    @Test
    fun `clear all with nothing shown does nothing`() = runTest {
        log.value = emptyList()
        val viewModel = createViewModel()
        viewModel.openRecentChanges()
        advanceUntilIdle()

        viewModel.clearAllRecentChanges()
        advanceUntilIdle()

        coVerify(exactly = 0) { factory.eventCoordinator.dismissRecentChanges(any()) }
        assertTrue(viewModel.uiState.value.recentChangesClearedIds.isEmpty())
    }

    @Test
    fun `a series row resolves to its next occurrence`() = runTest {
        coEvery { factory.eventReader.getNextOccurrence(10, any()) } returns occurrence(99_000)
        val viewModel = createViewModel()

        val target = viewModel.resolveRecentChangeTarget(item(1, "series", eventId = 10, recurring = true))

        assertEquals(series, target!!.event)
        assertEquals(99_000L, target.occurrenceTs)
    }

    @Test
    fun `a series whose next occurrence was changed resolves to that changed occurrence`() = runTest {
        val moved = Event(
            id = 12, uid = "series", calendarId = 1, title = "Standup (moved)", startTs = 99_000,
            endTs = 100_000, originalEventId = 10, originalInstanceTime = 90_000, dtstamp = 0
        )
        coEvery { factory.eventReader.getNextOccurrence(10, any()) } returns occurrence(99_000, exceptionEventId = 12)
        coEvery { factory.eventCoordinator.getEventById(12) } returns moved
        val viewModel = createViewModel()

        val target = viewModel.resolveRecentChangeTarget(item(1, "series", eventId = 10, recurring = true))

        assertEquals(moved, target!!.event)
        assertNull("the changed occurrence opens at its own start", target.occurrenceTs)
    }

    @Test
    fun `a row whose event waits to be deleted resolves to nothing`() = runTest {
        coEvery { factory.eventCoordinator.getEventById(11) } returns
            oneOff.copy(syncStatus = org.onekash.kashcal.data.db.entity.SyncStatus.PENDING_DELETE)
        val viewModel = createViewModel()

        assertNull(viewModel.resolveRecentChangeTarget(item(2, "one", eventId = 11)))
    }

    @Test
    fun `a series with no occurrence left resolves to its start`() = runTest {
        coEvery { factory.eventReader.getNextOccurrence(10, any()) } returns null
        val viewModel = createViewModel()

        val target = viewModel.resolveRecentChangeTarget(item(1, "series", eventId = 10, recurring = true))

        assertEquals(series, target!!.event)
        assertNull(target.occurrenceTs)
    }

    @Test
    fun `a one-off or changed occurrence resolves to itself`() = runTest {
        val viewModel = createViewModel()

        val target = viewModel.resolveRecentChangeTarget(item(2, "one", eventId = 11))
        val occurrence = viewModel.resolveRecentChangeTarget(
            item(3, "series", eventId = 11, recurring = true, instanceTs = 7_000)
        )

        assertEquals(oneOff, target!!.event)
        assertNull(target.occurrenceTs)
        assertNull(occurrence!!.occurrenceTs)
        coVerify(exactly = 0) { factory.eventReader.getNextOccurrence(any(), any()) }
    }

    @Test
    fun `a deleted row or one without an event resolves to nothing`() = runTest {
        val viewModel = createViewModel()

        assertNull(viewModel.resolveRecentChangeTarget(item(4, "gone", eventId = null, type = ChangeType.DELETED)))
        assertNull(viewModel.resolveRecentChangeTarget(item(5, "gone2", eventId = 11, type = ChangeType.DELETED)))
    }
}
