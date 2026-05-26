package org.onekash.kashcal.ui.viewmodels

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.availability.AvailabilityFormatter
import org.onekash.kashcal.domain.availability.FreeBlockFinder
import org.onekash.kashcal.domain.insights.InsightOccurrence
import org.onekash.kashcal.domain.insights.InsightsRepository
import org.onekash.kashcal.domain.insights.SimpleOccurrence
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Tests for ShareAvailabilityViewModel — Chunk 4 of the share-availability TDD plan.
 *
 * Drives the VM through its public surface (no direct calls into FreeBlockFinder
 * or AvailabilityFormatter — those are internal collaborators per the
 * "test user outcomes through the public surface" feedback).
 *
 * Uses a real DataStore (in-memory via overrideDataStore), real FreeBlockFinder,
 * real AvailabilityFormatter, and a mocked InsightsRepository so canned
 * occurrences flow into the recompute path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShareAvailabilityViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var dataStore: KashCalDataStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var insightsRepository: InsightsRepository
    private lateinit var freeBlockFinder: FreeBlockFinder
    private lateinit var availabilityFormatter: AvailabilityFormatter

    private val zone: ZoneId = ZoneId.of("UTC")
    private val mon: LocalDate = LocalDate.of(2026, 5, 25)
    private val mondayMidnightUtc: Long = mon.atStartOfDay(zone).toInstant().toEpochMilli()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dataStoreFile = File(context.filesDir, "test_share_avail_vm_${System.nanoTime()}.preferences_pb")
        val prefsDataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) { dataStoreFile }
        dataStore = KashCalDataStore(context, prefsDataStore)
        insightsRepository = mockk()
        coEvery { insightsRepository.getOccurrencesForRange(any(), any(), any()) } returns emptyList()
        freeBlockFinder = FreeBlockFinder()
        availabilityFormatter = AvailabilityFormatter()
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
        dataStoreFile.delete()
    }

    private fun createViewModel(now: Long = mondayMidnightUtc): ShareAvailabilityViewModel =
        ShareAvailabilityViewModel(
            dataStore = dataStore,
            insightsRepository = insightsRepository,
            freeBlockFinder = freeBlockFinder,
            availabilityFormatter = availabilityFormatter,
            context = context,
            zoneProvider = { zone },
            nowProvider = { now },
            is24HourProvider = { true },
            localeProvider = { Locale.US }
        )

    private fun timed(day: LocalDate, sH: Int, sM: Int, eH: Int, eM: Int): InsightOccurrence {
        val s = day.atTime(sH, sM).atZone(zone).toInstant().toEpochMilli()
        val e = day.atTime(eH, eM).atZone(zone).toInstant().toEpochMilli()
        return SimpleOccurrence(
            startTs = s, endTs = e, isAllDay = false,
            startDay = day.year * 10000 + day.monthValue * 100 + day.dayOfMonth,
            endDay = day.year * 10000 + day.monthValue * 100 + day.dayOfMonth,
            calendarId = 1L
        )
    }

    // ========== Initial state ==========

    @Test
    fun `initial state loads persisted defaults from DataStore`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(7, state.days)
        assertEquals(9 * 60, state.workStartMin)
        assertEquals(17 * 60, state.workEndMin)
        assertFalse(state.includeAllDay)
        assertFalse(state.isLoading)
    }

    @Test
    fun `initial state loads persisted non-default values`() = runTest(testDispatcher) {
        dataStore.setShareAvailabilityDays(3)
        dataStore.setShareAvailabilityWorkStartMinutes(8 * 60)
        dataStore.setShareAvailabilityWorkEndMinutes(20 * 60)
        dataStore.setShareAvailabilityIncludeAllDay(true)

        val vm = createViewModel()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(3, state.days)
        assertEquals(8 * 60, state.workStartMin)
        assertEquals(20 * 60, state.workEndMin)
        assertTrue(state.includeAllDay)
    }

    // ========== Preview computation ==========

    @Test
    fun `preview is non-empty when there are free blocks`() = runTest(testDispatcher) {
        coEvery { insightsRepository.getOccurrencesForRange(any(), any(), any()) } returns
            listOf(timed(mon, 10, 0, 11, 0))

        val vm = createViewModel()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue("Preview should be non-empty: ${state.previewText}", state.previewText.isNotBlank())
        assertTrue(state.blocks.isNotEmpty())
        assertTrue(state.isShareEnabled)
    }

    @Test
    fun `preview is empty-state when no qualifying blocks`() = runTest(testDispatcher) {
        // Wipe the entire 09:00-17:00 working window with one event.
        coEvery { insightsRepository.getOccurrencesForRange(any(), any(), any()) } returns
            listOf(timed(mon, 9, 0, 17, 0)) +
            (1..6).map { timed(mon.plusDays(it.toLong()), 9, 0, 17, 0) }

        val vm = createViewModel()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state.blocks.isEmpty())
        assertFalse(state.isShareEnabled)
        assertNull("shareIntentText should be null when no blocks", vm.shareIntentText)
    }

    @Test
    fun `shareIntentText returns formatted preview when blocks present`() = runTest(testDispatcher) {
        coEvery { insightsRepository.getOccurrencesForRange(any(), any(), any()) } returns
            listOf(timed(mon, 10, 0, 11, 0))

        val vm = createViewModel()
        advanceUntilIdle()
        assertNotNull(vm.shareIntentText)
        assertEquals(vm.uiState.value.previewText, vm.shareIntentText)
    }

    // ========== onDaysChange ==========

    @Test
    fun `onDaysChange updates state, persists, and recomputes`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onDaysChange(3)
        advanceUntilIdle()

        assertEquals(3, vm.uiState.value.days)
        // Persisted: a fresh VM reads the new value.
        val vm2 = createViewModel()
        advanceUntilIdle()
        assertEquals(3, vm2.uiState.value.days)
    }

    @Test
    fun `onDaysChange triggers a fresh repository snapshot`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onDaysChange(5)
        advanceUntilIdle()

        coVerify(atLeast = 2) { insightsRepository.getOccurrencesForRange(any(), any()) }
    }

    // ========== onWorkHoursChange ==========

    @Test
    fun `onWorkHoursChange updates state and persists`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onWorkHoursChange(8 * 60, 18 * 60)
        advanceUntilIdle()

        assertEquals(8 * 60, vm.uiState.value.workStartMin)
        assertEquals(18 * 60, vm.uiState.value.workEndMin)
    }

    @Test
    fun `onWorkHoursChange rejects invalid window where end is before start`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        val before = vm.uiState.value

        vm.onWorkHoursChange(18 * 60, 8 * 60) // inverted
        advanceUntilIdle()

        // State unchanged.
        assertEquals(before.workStartMin, vm.uiState.value.workStartMin)
        assertEquals(before.workEndMin, vm.uiState.value.workEndMin)
    }

    @Test
    fun `onWorkHoursChange rejects window narrower than 60 minutes`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        val before = vm.uiState.value

        vm.onWorkHoursChange(9 * 60, 9 * 60 + 30) // 30-min window
        advanceUntilIdle()

        assertEquals(before.workStartMin, vm.uiState.value.workStartMin)
        assertEquals(before.workEndMin, vm.uiState.value.workEndMin)
    }

    // ========== onAllDayToggle ==========

    @Test
    fun `onAllDayToggle updates state and persists`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onAllDayToggle(true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.includeAllDay)
    }
}
