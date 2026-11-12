package org.onekash.kashcal.ui.viewmodels

import android.util.Log
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.domain.search.SearchOutcome
import org.onekash.kashcal.domain.search.SearchQuery
import org.onekash.kashcal.domain.search.SearchTiming
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.ZoneId

/**
 * The search state in [HomeViewModel] over a strictly stubbed
 * [org.onekash.kashcal.domain.reader.DisplayEventRepository]:
 * the default state, one observed search per query, list switches that don't search again, a
 * date range, the hidden-calendar choice, the timing log line and a search with no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class HomeViewModelSearchTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var factory: DeviceHomeViewModelTestFactory

    private data class Call(val words: List<String>, val zone: ZoneId, val range: LongRange?)

    private val calls = mutableListOf<Call>()
    private val results = mutableListOf<MutableSharedFlow<SearchOutcome>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        ShadowLog.clear()
        factory = DeviceHomeViewModelTestFactory()
        coEvery { factory.accountRepository.getAllAccounts() } returns emptyList()
        every { factory.displayEventRepository.observeSearch(any(), any(), any(), any()) } answers {
            calls += Call(firstArg<SearchQuery>().words, thirdArg(), arg(3))
            MutableSharedFlow<SearchOutcome>(replay = 1).also { results += it } as Flow<SearchOutcome>
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HomeViewModel {
        val device = FakeCalendarProviderRepository()
        return factory.create(device.deviceEventReader(), device.deviceEventWriter(factory.dataStore), dispatcher)
    }

    private fun outcome(timing: SearchTiming = SearchTiming(12, 7, 30)) = SearchOutcome(timing = timing)

    @Test
    fun `search opens empty, on Upcoming, with no range and hidden calendars left out`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.activateSearch()

        val state = vm.uiState.value
        assertTrue(state.isSearchActive)
        assertEquals("", state.searchQuery)
        assertEquals(SearchResultsState.Idle, state.searchState)
        assertEquals(SearchTab.UPCOMING, state.searchTab)
        assertNull(state.searchDateFilter)
        assertFalse(state.searchIncludeHidden)
        assertFalse(state.showSearchDateMenu)
    }

    @Test
    fun `a query searches after the typing delay, loading first, then showing each result`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.activateSearch()
        vm.updateSearchQuery("dentist")
        advanceTimeBy(299)
        runCurrent()
        assertTrue("nothing before the delay", calls.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(listOf("dentist")), calls.map { it.words })
        assertEquals(SearchResultsState.Loading, vm.uiState.value.searchState)

        val first = outcome()
        results.single().emit(first)
        runCurrent()
        assertEquals(SearchResultsState.Loaded(first), vm.uiState.value.searchState)

        // An edit or a sync while search is open emits again.
        val second = outcome(timing = SearchTiming(1, 1, 1))
        results.single().emit(second)
        runCurrent()
        assertEquals(SearchResultsState.Loaded(second), vm.uiState.value.searchState)
    }

    @Test
    fun `typing again cancels the search before it`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.activateSearch()
        vm.updateSearchQuery("den")
        advanceTimeBy(100)
        vm.updateSearchQuery("dentist")
        advanceUntilIdle()

        assertEquals(listOf(listOf("dentist")), calls.map { it.words })
    }

    @Test
    fun `one Latin letter or only separators is too short and does not search`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.activateSearch()

        for (text in listOf("d", "  ", "...")) {
            vm.updateSearchQuery(text)
            advanceUntilIdle()
            assertEquals(text, SearchResultsState.TooShort, vm.uiState.value.searchState)
        }
        vm.updateSearchQuery("")
        assertEquals(SearchResultsState.Idle, vm.uiState.value.searchState)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `one Japanese character searches`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.activateSearch()
        vm.updateSearchQuery("歯")
        advanceUntilIdle()

        assertEquals(listOf(listOf("歯")), calls.map { it.words })
    }

    @Test
    fun `switching between Upcoming and Past does not search again`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.activateSearch()
        vm.updateSearchQuery("dentist")
        advanceUntilIdle()

        vm.selectSearchTab(SearchTab.PAST)
        vm.selectSearchTab(SearchTab.UPCOMING)
        vm.selectSearchTab(SearchTab.PAST)
        advanceUntilIdle()

        assertEquals(1, calls.size)
        assertEquals(SearchTab.PAST, vm.uiState.value.searchTab)
    }

    @Test
    fun `a picked range searches again with that range and Upcoming clears it without searching`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.activateSearch()
        vm.updateSearchQuery("dentist")
        advanceUntilIdle()
        vm.openSearchDateMenu()
        assertTrue(vm.uiState.value.showSearchDateMenu)

        vm.setSearchDateFilter(DateFilter.ThisWeek)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.showSearchDateMenu)
        assertEquals(DateFilter.ThisWeek, vm.uiState.value.searchDateFilter)
        assertEquals(2, calls.size)
        val zone = calls.last().zone
        val (from, to) = DateFilter.ThisWeek.getTimeRange(zone, vm.uiState.value.firstDayOfWeek)
        assertEquals(from..to, calls.last().range)

        vm.selectSearchTab(SearchTab.UPCOMING)
        advanceUntilIdle()
        assertNull(vm.uiState.value.searchDateFilter)
        assertEquals(2, calls.size)
    }

    @Test
    fun `Pick dates closes the menu and opens the date sheet`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.activateSearch()
        vm.openSearchDateMenu()

        vm.showSearchDatePicker()

        assertFalse(vm.uiState.value.showSearchDateMenu)
        assertTrue(vm.uiState.value.showSearchDatePicker)
    }

    @Test
    fun `hidden calendars stay included across query edits and reset on close`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.activateSearch()
        vm.updateSearchQuery("dentist")
        advanceUntilIdle()
        vm.setSearchIncludeHidden(true)
        vm.selectSearchTab(SearchTab.PAST)

        vm.updateSearchQuery("dentist appointment")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.searchIncludeHidden)
        assertEquals(SearchTab.PAST, vm.uiState.value.searchTab)

        vm.deactivateSearch()
        vm.activateSearch()
        assertFalse(vm.uiState.value.searchIncludeHidden)
        assertEquals(SearchTab.UPCOMING, vm.uiState.value.searchTab)
    }

    @Test
    fun `Show and Leave out change no calendar's visibility`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.activateSearch()
        vm.updateSearchQuery("dentist")
        advanceUntilIdle()

        vm.setSearchIncludeHidden(true)
        assertTrue(vm.uiState.value.searchIncludeHidden)
        vm.setSearchIncludeHidden(false)
        assertFalse(vm.uiState.value.searchIncludeHidden)
        advanceUntilIdle()

        coVerify(exactly = 0) { factory.eventCoordinator.setCalendarVisibility(any(), any()) }
        coVerify(exactly = 0) { factory.dataStore.setHiddenDeviceCalendarIds(any()) }
        assertEquals("the lists are split already, so no new search", 1, calls.size)
    }

    @Test
    fun `closing search stops the observed search`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.activateSearch()
        vm.updateSearchQuery("dentist")
        advanceUntilIdle()

        vm.deactivateSearch()
        results.single().emit(outcome())
        runCurrent()

        assertEquals(SearchResultsState.Idle, vm.uiState.value.searchState)
    }

    @Test
    fun `each search logs one timing line without the query text`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.activateSearch()
        vm.updateSearchQuery("dermatology clinic")
        advanceUntilIdle()
        results.single().emit(outcome(timing = SearchTiming(roomMs = 12, deviceMs = 7, totalMs = 30, coverageMs = 40)))
        results.single().emit(outcome())
        runCurrent()

        val lines = ShadowLog.getLogsForTag("HomeViewModel").filter { it.msg.startsWith("Search timing") }
        assertEquals(1, lines.size)
        val line = lines.single()
        assertEquals(Log.INFO, line.type)
        assertTrue(line.msg, line.msg.contains("words=2"))
        assertTrue(line.msg, line.msg.contains("room=12ms"))
        assertTrue(line.msg, line.msg.contains("device=7ms"))
        assertTrue(line.msg, Regex("total=\\d+ms").containsMatchIn(line.msg))
        assertTrue(line.msg, line.msg.contains("coverage=40ms"))
        assertTrue(line.msg, line.msg.contains("upcoming=0") && line.msg.contains("past=0"))
        assertFalse(line.msg, line.msg.contains("dermatology"))
    }

    @Test
    fun `search runs with the network offline and triggers no sync`() = runTest(dispatcher) {
        every { factory.networkMonitor.isOnline } returns MutableStateFlow(false)
        val vm = createViewModel()
        advanceUntilIdle()
        // Only what search does counts; startup's own sync scheduling is not under test.
        clearMocks(factory.syncScheduler, answers = false, recordedCalls = true)
        vm.activateSearch()
        vm.updateSearchQuery("dentist")
        advanceUntilIdle()
        results.single().emit(outcome())
        runCurrent()

        assertEquals(1, calls.size)
        assertTrue(vm.uiState.value.searchState is SearchResultsState.Loaded)
        verify(exactly = 0) { factory.syncScheduler.requestImmediateSync(any(), any(), any()) }
    }
}
