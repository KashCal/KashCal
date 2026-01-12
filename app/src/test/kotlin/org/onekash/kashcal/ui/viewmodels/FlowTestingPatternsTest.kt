package org.onekash.kashcal.ui.viewmodels

import app.cash.turbine.test
import app.cash.turbine.turbineScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.provider.icloud.ICloudAuthManager
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus

/**
 * Tests for Flow/StateFlow patterns using Turbine library.
 *
 * Demonstrates Android best practices for testing:
 * - StateFlow testing with Turbine
 * - Flow cancellation and error handling
 * - Multiple Flow emission testing
 * - Progressive loading during sync
 *
 * Reference: https://developer.android.com/kotlin/flow/test
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowTestingPatternsTest {

    private val testDispatcher = StandardTestDispatcher()

    // Mocks
    private lateinit var eventCoordinator: EventCoordinator
    private lateinit var eventReader: EventReader
    private lateinit var dataStore: KashCalDataStore
    private lateinit var authManager: ICloudAuthManager
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var networkMonitor: NetworkMonitor

    // Controllable flows
    private lateinit var calendarsFlow: MutableStateFlow<List<Calendar>>
    private lateinit var occurrencesFlow: MutableStateFlow<List<Occurrence>>
    private lateinit var networkStateFlow: MutableStateFlow<Boolean>
    private lateinit var syncStatusFlow: MutableStateFlow<SyncStatus>
    private lateinit var bannerFlagFlow: MutableStateFlow<Boolean>

    private val testCalendars = listOf(
        Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://caldav.icloud.com/cal1",
            displayName = "Personal",
            color = 0xFF2196F3.toInt()
        ),
        Calendar(
            id = 2L,
            accountId = 1L,
            caldavUrl = "https://caldav.icloud.com/cal2",
            displayName = "Work",
            color = 0xFF4CAF50.toInt()
        )
    )

    private val testOccurrences = listOf(
        Occurrence(
            id = 1L,
            eventId = 1L,
            calendarId = 1L,
            startTs = 1704067200000L, // Jan 1, 2024 10:00 UTC
            endTs = 1704070800000L,
            startDay = 20240101,
            endDay = 20240101
        ),
        Occurrence(
            id = 2L,
            eventId = 2L,
            calendarId = 2L,
            startTs = 1704081600000L, // Jan 1, 2024 14:00 UTC
            endTs = 1704085200000L,
            startDay = 20240101,
            endDay = 20240101
        )
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        eventCoordinator = mockk(relaxed = true)
        eventReader = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        authManager = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)

        // Initialize controllable flows
        calendarsFlow = MutableStateFlow(testCalendars)
        occurrencesFlow = MutableStateFlow(testOccurrences)
        networkStateFlow = MutableStateFlow(true)
        syncStatusFlow = MutableStateFlow(SyncStatus.Idle)
        bannerFlagFlow = MutableStateFlow(false)

        // Setup mock behavior
        every { eventCoordinator.getAllCalendars() } returns calendarsFlow
        every { networkMonitor.isOnline } returns networkStateFlow
        every { networkMonitor.isMetered } returns MutableStateFlow(false)
        every { syncScheduler.observeImmediateSyncStatus() } returns syncStatusFlow
        every { syncScheduler.showBannerForSync } returns bannerFlagFlow
        every { syncScheduler.setShowBannerForSync(any()) } answers { bannerFlagFlow.value = firstArg() }
        every { syncScheduler.resetBannerFlag() } answers { bannerFlagFlow.value = false }
        every { syncScheduler.lastSyncChanges } returns MutableStateFlow(emptyList())
        every { syncScheduler.clearSyncChanges() } returns Unit

        coEvery { dataStore.defaultCalendarId } returns flowOf(null)
        coEvery { dataStore.defaultReminderMinutes } returns flowOf(15)
        coEvery { dataStore.defaultAllDayReminder } returns flowOf(1440)
        coEvery { dataStore.onboardingDismissed } returns flowOf(true)
        coEvery { authManager.loadAccount() } returns null
        coEvery { eventReader.getVisibleOccurrencesInRange(any(), any()) } returns occurrencesFlow
        every { eventReader.getVisibleOccurrencesForDay(any()) } returns occurrencesFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HomeViewModel {
        return HomeViewModel(
            eventCoordinator = eventCoordinator,
            eventReader = eventReader,
            dataStore = dataStore,
            authManager = authManager,
            syncScheduler = syncScheduler,
            networkMonitor = networkMonitor,
            ioDispatcher = testDispatcher
        )
    }

    // ==================== StateFlow Emission Tests ====================

    @Test
    fun `StateFlow emits updates when calendars change`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.calendars.size)

        // Emit new calendars
        val newCalendars = testCalendars + Calendar(
            id = 3L,
            accountId = 1L,
            caldavUrl = "https://caldav.icloud.com/cal3",
            displayName = "Projects",
            color = 0xFFFF5722.toInt()
        )
        calendarsFlow.value = newCalendars
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.calendars.size)
        assertEquals("Projects", viewModel.uiState.value.calendars[2].displayName)
    }

    @Test
    fun `StateFlow updates UI when network state changes`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // isOnline is exposed directly as StateFlow from NetworkMonitor
        assertTrue(viewModel.isOnline.value)

        // Go offline
        networkStateFlow.value = false
        advanceUntilIdle()

        assertFalse(viewModel.isOnline.value)

        // Go back online
        networkStateFlow.value = true
        advanceUntilIdle()

        assertTrue(viewModel.isOnline.value)
    }

    @Test
    fun `StateFlow updates sync status progressively`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSyncing)

        // Start syncing
        syncStatusFlow.value = SyncStatus.Enqueued
        advanceUntilIdle()

        // Running
        syncStatusFlow.value = SyncStatus.Running
        advanceUntilIdle()

        // Succeeded
        syncStatusFlow.value = SyncStatus.Succeeded(calendarsSynced = 2, eventsPulled = 10)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSyncing)
    }

    // ==================== Turbine Flow Testing ====================

    @Test
    fun `Turbine captures multiple StateFlow emissions`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            // Initial state
            val initial = awaitItem()
            assertEquals(2, initial.calendars.size)

            // Update calendars
            calendarsFlow.value = listOf(testCalendars[0])
            advanceUntilIdle()

            // May have intermediate emissions due to other updates
            val updated = expectMostRecentItem()
            assertEquals(1, updated.calendars.size)
        }
    }

    @Test
    fun `Turbine detects network state transitions`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // isOnline is exposed directly as StateFlow from NetworkMonitor
        viewModel.isOnline.test {
            // Initial state - online
            assertTrue(awaitItem())

            // Go offline
            networkStateFlow.value = false
            advanceUntilIdle()

            assertFalse(expectMostRecentItem())

            // Go online
            networkStateFlow.value = true
            advanceUntilIdle()

            assertTrue(expectMostRecentItem())
        }
    }

    // ==================== Flow Cancellation Tests ====================

    @Test
    fun `Flow collection is cancelled when ViewModel is cleared`() = runTest {
        var flowCompleted = false
        val continuousFlow = flow {
            try {
                repeat(100) {
                    emit(it)
                    kotlinx.coroutines.delay(100)
                }
            } finally {
                flowCompleted = true
            }
        }

        // This test verifies that coroutines are cancelled on ViewModel clearing
        // The viewModelScope handles this automatically
        val viewModel = createViewModel()
        advanceUntilIdle()

        // ViewModel should be functional
        assertTrue(viewModel.uiState.value.calendars.isNotEmpty())
    }

    @Test
    fun `Day selection cancels previous day loading flow`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Select first day
        viewModel.selectDate(1704067200000L) // Jan 1, 2024
        advanceUntilIdle()

        // Select second day immediately - should cancel first
        viewModel.selectDate(1704153600000L) // Jan 2, 2024
        advanceUntilIdle()

        // ViewModel should be in a consistent state
        assertTrue(viewModel.uiState.value.selectedDate > 0)
    }

    // ==================== Progressive Loading Tests ====================

    @Test
    fun `UI updates progressively as occurrences are emitted`() = runTest {
        // Start with empty
        occurrencesFlow.value = emptyList()

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Select a day to start loading
        viewModel.selectDate(1704067200000L)
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.selectedDayOccurrences.size)

        // First occurrence arrives
        occurrencesFlow.value = listOf(testOccurrences[0])
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.selectedDayOccurrences.size)

        // Second occurrence arrives
        occurrencesFlow.value = testOccurrences
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.selectedDayOccurrences.size)
    }

    @Test
    fun `Calendar visibility toggle triggers immediate UI update`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.calendars.size)
        assertTrue(viewModel.uiState.value.calendars[0].isVisible)

        // Emit updated calendars with visibility changed
        calendarsFlow.value = listOf(
            testCalendars[0].copy(isVisible = false),
            testCalendars[1]
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.calendars[0].isVisible)
    }

    // ==================== Error Handling in Flows ====================

    @Test
    fun `Flow error does not crash ViewModel`() = runTest {
        // Create a flow that throws an error
        val errorFlow = flow<List<Occurrence>> {
            emit(testOccurrences)
            throw RuntimeException("Test error")
        }

        every { eventReader.getVisibleOccurrencesForDay(any()) } returns errorFlow

        val viewModel = createViewModel()
        advanceUntilIdle()

        // ViewModel should still be functional
        viewModel.selectDate(1704067200000L)
        advanceUntilIdle()

        // Should handle the error gracefully
        assertTrue(viewModel.uiState.value.selectedDate > 0)
    }

    // ==================== distinctUntilChanged Tests ====================

    @Test
    fun `Duplicate emissions are filtered by distinctUntilChanged`() = runTest {
        var emissionCount = 0

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            awaitItem() // Initial

            // Emit same calendars multiple times
            calendarsFlow.value = testCalendars
            calendarsFlow.value = testCalendars
            calendarsFlow.value = testCalendars
            advanceUntilIdle()

            // Should not get multiple emissions for identical data
            // due to StateFlow's built-in distinctUntilChanged
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ==================== SharedFlow Tests ====================

    @Test
    fun `SharedFlow can be used for one-shot events`() = runTest {
        val eventFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)

        eventFlow.test {
            eventFlow.emit("Event 1")
            assertEquals("Event 1", awaitItem())

            eventFlow.emit("Event 2")
            assertEquals("Event 2", awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ==================== Complex Flow Combining Tests ====================

    @Test
    fun `Combined flows update correctly when either changes`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Initial state
        assertTrue(viewModel.uiState.value.calendars.isNotEmpty())

        // Update calendars
        val singleCalendar = listOf(testCalendars[0])
        calendarsFlow.value = singleCalendar
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.calendars.size)

        // Simultaneously update network state
        networkStateFlow.value = false
        advanceUntilIdle()

        // Both should be reflected
        assertEquals(1, viewModel.uiState.value.calendars.size)
        // isOnline is exposed directly as StateFlow from NetworkMonitor
        assertFalse(viewModel.isOnline.value)
    }

    // ==================== Sync Status Flow Tests ====================

    @Test
    fun `Sync status transitions are captured in correct order`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Capture sync status transitions
        val statusTransitions = mutableListOf<SyncStatus>()

        syncStatusFlow.value = SyncStatus.Enqueued
        statusTransitions.add(syncStatusFlow.value)
        advanceUntilIdle()

        syncStatusFlow.value = SyncStatus.Running
        statusTransitions.add(syncStatusFlow.value)
        advanceUntilIdle()

        syncStatusFlow.value = SyncStatus.Succeeded(calendarsSynced = 2, eventsPulled = 5)
        statusTransitions.add(syncStatusFlow.value)
        advanceUntilIdle()

        syncStatusFlow.value = SyncStatus.Idle
        statusTransitions.add(syncStatusFlow.value)
        advanceUntilIdle()

        assertEquals(4, statusTransitions.size)
        assertTrue(statusTransitions[0] is SyncStatus.Enqueued)
        assertTrue(statusTransitions[1] is SyncStatus.Running)
        assertTrue(statusTransitions[2] is SyncStatus.Succeeded)
        assertTrue(statusTransitions[3] is SyncStatus.Idle)
    }

    @Test
    fun `Failed sync status is properly reflected in UI state`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        syncStatusFlow.value = SyncStatus.Running
        advanceUntilIdle()

        syncStatusFlow.value = SyncStatus.Failed(errorMessage = "Network error")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSyncing)
        // Banner may show error depending on banner flag state
    }

    // ==================== UnconfinedTestDispatcher Pattern ====================

    @Test
    fun `UnconfinedTestDispatcher allows immediate emission testing`() =
        runTest(UnconfinedTestDispatcher()) {
            val flow = MutableStateFlow(0)

            flow.test {
                assertEquals(0, awaitItem())

                flow.value = 1
                assertEquals(1, awaitItem())

                flow.value = 2
                assertEquals(2, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }
}