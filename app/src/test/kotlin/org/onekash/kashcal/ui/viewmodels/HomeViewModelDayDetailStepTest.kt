package org.onekash.kashcal.ui.viewmodels

import io.mockk.coEvery
import io.mockk.every
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.planner.PlannerTestEvents
import org.onekash.kashcal.domain.search.SearchOutcome
import org.onekash.kashcal.ui.util.CalendarSpan
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.util.ZoneSource
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * Tests the day sheet's own one-day stream and its one-day step: a step moves only the sheet's
 * day (keeping the tapped time of day across DST), the stream pairs every day with its own events
 * and never empties between days, and the grid, the selected day and the Planner week stay where
 * the user left them. Collaborators are the strict mocks of [DeviceHomeViewModelTestFactory], with
 * the single-day reads served per day and recorded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HomeViewModelDayDetailStepTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var factory: DeviceHomeViewModelTestFactory
    private lateinit var savedZone: TimeZone
    private lateinit var zoneSource: ZoneSource

    private val dateRangeCalls = mutableListOf<Pair<Int, Int>>()

    /** Events served for a single-day read, by day code; a day missing here reads as empty. */
    private val eventsByDay = mutableMapOf<Int, ImmutableList<DisplayEvent>>()

    /** Day codes whose single-day read throws. */
    private val failingDays = mutableSetOf<Int>()

    /** Day codes whose reads after the first never emit, like a slow read. */
    private val stallingDays = mutableSetOf<Int>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        savedZone = TimeZone.getDefault()
        setZone(LA)
        zoneSource = ZoneSource()
        factory = DeviceHomeViewModelTestFactory()
        coEvery { factory.accountRepository.getAllAccounts() } returns emptyList()
        val repo = factory.displayEventRepository
        every { repo.getDisplayEventsForRange(any(), any()) } returns MutableStateFlow(persistentListOf())
        every { repo.getDisplayEventsForDayRange(any()) } returns MutableStateFlow(persistentMapOf())
        every { repo.getDisplayEventsForDateRange(any(), any()) } answers {
            val start = firstArg<Int>()
            val end = secondArg<Int>()
            dateRangeCalls += start to end
            serve(start, end)
        }
        every { repo.observeSearch(any(), any(), any(), any()) } returns flowOf(SearchOutcome())
        coEvery { repo.getDisplayEventsGroupedByDayOnce(any(), any()) } returns emptyMap()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(savedZone)
        Dispatchers.resetMain()
    }

    private fun serve(start: Int, end: Int): Flow<ImmutableMap<Int, ImmutableList<DisplayEvent>>> {
        if (start != end) return MutableStateFlow(persistentMapOf())
        if (start in failingDays) return flow { throw IllegalStateException("read failed") }
        if (start in stallingDays && dateRangeCalls.count { it == start to end } > 1) return flow { awaitCancellation() }
        val events = eventsByDay[start]
        return MutableStateFlow(if (events == null) persistentMapOf() else persistentMapOf(start to events))
    }

    private fun setZone(zone: ZoneId) = TimeZone.setDefault(TimeZone.getTimeZone(zone))

    private fun createViewModel(): HomeViewModel {
        val device = FakeCalendarProviderRepository()
        return factory.create(
            device.deviceEventReader(), device.deviceEventWriter(factory.dataStore), dispatcher,
            zoneSource = zoneSource,
        )
    }

    private fun at(date: LocalDate, hour: Int, minute: Int = 0, zone: ZoneId = LA): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun code(date: LocalDate): Int = DayPagerUtils.localDateToDayCode(date)

    private fun eventOn(id: Long, date: LocalDate): DisplayEvent =
        PlannerTestEvents.room(id = id, startTs = at(date, 9), endTs = at(date, 10), zone = LA)

    /**
     * Collects every [HomeViewModel.dayDetail] value, as the open sheet would. The collector is
     * unconfined: advanceUntilIdle leaves background-scope work queued, so a standard one would
     * trail the state by one value.
     */
    private fun TestScope.collectDayDetail(vm: HomeViewModel): MutableList<DayDetailState?> {
        val seen = mutableListOf<DayDetailState?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.dayDetail.collect { seen += it } }
        return seen
    }

    private fun TestScope.openFullMonth(vm: HomeViewModel, tapMs: Long) {
        vm.setViewMode(ViewMode.MONTH_FULL, persist = false)
        advanceUntilIdle()
        vm.selectDate(tapMs)
        vm.showDayDetail(tapMs)
        advanceUntilIdle()
    }

    @Test
    fun `a step forward shows the next day at the same time of day`() = runTest {
        val vm = createViewModel()
        collectDayDetail(vm)
        openFullMonth(vm, at(NOV_11, 15, 30))

        vm.stepDayDetail(1)
        advanceUntilIdle()

        assertEquals(at(NOV_12, 15, 30), vm.uiState.value.dayDetailDate)
        assertEquals(code(NOV_12), vm.dayDetail.value?.dayCode)
        assertEquals(at(NOV_12, 15, 30), vm.dayDetail.value?.dateMs)
    }

    @Test
    fun `a step back shows the previous day`() = runTest {
        val vm = createViewModel()
        collectDayDetail(vm)
        openFullMonth(vm, at(NOV_11, 8))

        vm.stepDayDetail(-1)
        advanceUntilIdle()

        assertEquals(at(NOV_11.minusDays(1), 8), vm.uiState.value.dayDetailDate)
        assertEquals(code(NOV_11.minusDays(1)), vm.dayDetail.value?.dayCode)
    }

    @Test
    fun `steps across the spring and fall clock changes keep the time of day`() = runTest {
        val vm = createViewModel()
        collectDayDetail(vm)
        val springEve = LocalDate.of(2026, 3, 7)
        openFullMonth(vm, at(springEve, 15, 30))

        vm.stepDayDetail(1)
        vm.stepDayDetail(1)
        advanceUntilIdle()
        assertEquals(at(LocalDate.of(2026, 3, 9), 15, 30), vm.uiState.value.dayDetailDate)

        vm.dismissDayDetail()
        val fallEve = LocalDate.of(2026, 10, 31)
        vm.showDayDetail(at(fallEve, 15, 30))
        vm.stepDayDetail(1)
        vm.stepDayDetail(1)
        advanceUntilIdle()
        assertEquals(at(LocalDate.of(2026, 11, 2), 15, 30), vm.uiState.value.dayDetailDate)
    }

    @Test
    fun `a step leaves the selected day, the viewing month and the grid where they were`() = runTest {
        val vm = createViewModel()
        collectDayDetail(vm)
        val nov30 = LocalDate.of(2026, 11, 30)
        openFullMonth(vm, at(nov30, 12))
        val before = vm.uiState.value

        vm.stepDayDetail(1)
        vm.stepDayDetail(1)
        advanceUntilIdle()

        val after = vm.uiState.value
        assertEquals(LocalDate.of(2026, 12, 2), dateOf(after.dayDetailDate))
        assertEquals(before.selectedDate, after.selectedDate)
        assertEquals(before.viewingYear to before.viewingMonth, after.viewingYear to after.viewingMonth)
        assertNull(after.pendingNavigateToMonth)
    }

    @Test
    fun `in the Planner a step leaves the Planner week where it was`() = runTest {
        val vm = createViewModel()
        collectDayDetail(vm)
        vm.setViewMode(ViewMode.PLANNER, persist = false)
        advanceUntilIdle()
        val before = vm.uiState.value
        vm.showDayDetail(at(NOV_11, 10))
        advanceUntilIdle()

        repeat(9) { vm.stepDayDetail(1) }
        advanceUntilIdle()

        val after = vm.uiState.value
        assertEquals(NOV_11.plusDays(9), dateOf(after.dayDetailDate))
        assertEquals(code(NOV_11.plusDays(9)), vm.dayDetail.value?.dayCode)
        assertEquals(before.plannerWeekPagerPosition, after.plannerWeekPagerPosition)
        assertEquals(before.plannerAnchorDayCode, after.plannerAnchorDayCode)
        assertEquals(before.pendingPlannerPagerPosition, after.pendingPlannerPagerPosition)
        assertEquals(before.selectedDate, after.selectedDate)
    }

    @Test
    fun `the sheet reads each shown day on its own and pairs it with that day's events`() = runTest {
        val nov12 = eventOn(2, NOV_12)
        eventsByDay[code(NOV_11)] = persistentListOf(eventOn(1, NOV_11))
        eventsByDay[code(NOV_12)] = persistentListOf(nov12)
        val vm = createViewModel()
        val seen = collectDayDetail(vm)
        openFullMonth(vm, at(NOV_11, 12))

        vm.stepDayDetail(1)
        advanceUntilIdle()

        assertTrue(dateRangeCalls.contains(code(NOV_12) to code(NOV_12)))
        assertEquals(listOf(nov12), vm.dayDetail.value?.events)
        assertTrue(seen.filterNotNull().map { it.dayCode }.containsAll(listOf(code(NOV_11), code(NOV_12))))
        seen.filterNotNull().forEach { state ->
            assertEquals("events of ${state.dayCode}", eventsByDay[state.dayCode].orEmpty(), state.events)
        }
    }

    @Test
    fun `no empty state shows between two days that have events`() = runTest {
        eventsByDay[code(NOV_11)] = persistentListOf(eventOn(1, NOV_11))
        eventsByDay[code(NOV_12)] = persistentListOf(eventOn(2, NOV_12))
        val vm = createViewModel()
        val seen = collectDayDetail(vm)
        openFullMonth(vm, at(NOV_11, 12))
        val opened = seen.size

        vm.stepDayDetail(1)
        advanceUntilIdle()

        val afterOpen = seen.drop(opened)
        assertTrue("states after the step: $afterOpen", afterOpen.isNotEmpty())
        afterOpen.forEach { state ->
            assertTrue("empty or missing state $state", state != null && state.events.isNotEmpty())
        }
    }

    @Test
    fun `the sheet opens with its day's events, never an empty state first`() = runTest {
        eventsByDay[code(NOV_11)] = persistentListOf(eventOn(1, NOV_11))
        val vm = createViewModel()
        val seen = collectDayDetail(vm)

        openFullMonth(vm, at(NOV_11, 12))

        val first = seen.firstOrNull { it != null }
        assertEquals(code(NOV_11), first?.dayCode)
        assertEquals(eventsByDay[code(NOV_11)], first?.events)
    }

    @Test
    fun `Nov 30 to Dec 1 reads Dec 1 without moving the month`() = runTest {
        val dec1 = LocalDate.of(2026, 12, 1)
        eventsByDay[code(dec1)] = persistentListOf(eventOn(3, dec1))
        val vm = createViewModel()
        collectDayDetail(vm)
        openFullMonth(vm, at(LocalDate.of(2026, 11, 30), 12))
        val month = vm.uiState.value.viewingYear to vm.uiState.value.viewingMonth

        vm.stepDayDetail(1)
        advanceUntilIdle()

        assertEquals(eventsByDay[code(dec1)], vm.dayDetail.value?.events)
        assertEquals(month, vm.uiState.value.viewingYear to vm.uiState.value.viewingMonth)
    }

    @Test
    fun `a failed read still moves the sheet to the stepped day, with no events`() = runTest {
        eventsByDay[code(NOV_11)] = persistentListOf(eventOn(1, NOV_11))
        failingDays += code(NOV_12)
        val vm = createViewModel()
        collectDayDetail(vm)
        openFullMonth(vm, at(NOV_11, 12))

        vm.stepDayDetail(1)
        advanceUntilIdle()

        assertEquals(code(NOV_12), vm.dayDetail.value?.dayCode)
        assertTrue(vm.dayDetail.value!!.events.isEmpty())
    }

    @Test
    fun `no step past the ends of the calendar span`() = runTest {
        val vm = createViewModel()
        collectDayDetail(vm)
        val last = CalendarSpan.lastDay()
        openFullMonth(vm, at(last, 12))

        vm.stepDayDetail(1)
        advanceUntilIdle()
        assertEquals(at(last, 12), vm.uiState.value.dayDetailDate)

        vm.dismissDayDetail()
        val first = CalendarSpan.firstDay()
        vm.showDayDetail(at(first, 12))
        vm.stepDayDetail(-1)
        advanceUntilIdle()
        assertEquals(at(first, 12), vm.uiState.value.dayDetailDate)
    }

    @Test
    fun `a step does nothing while the sheet is closed`() = runTest {
        val vm = createViewModel()
        collectDayDetail(vm)
        vm.setViewMode(ViewMode.MONTH_FULL, persist = false)
        advanceUntilIdle()

        vm.stepDayDetail(1)
        advanceUntilIdle()

        assertEquals(0L, vm.uiState.value.dayDetailDate)
        assertNull(vm.dayDetail.value)
    }

    @Test
    fun `closing the sheet clears its state`() = runTest {
        val vm = createViewModel()
        collectDayDetail(vm)
        openFullMonth(vm, at(NOV_11, 12))
        assertEquals(code(NOV_11), vm.dayDetail.value?.dayCode)

        vm.dismissDayDetail()
        advanceUntilIdle()

        assertNull(vm.dayDetail.value)
    }

    @Test
    fun `after a zone change the sheet re-reads the same local day`() = runTest {
        val vm = createViewModel()
        collectDayDetail(vm)
        openFullMonth(vm, at(NOV_11, 15, 30))
        val readsBefore = dateRangeCalls.count { it == code(NOV_11) to code(NOV_11) }

        setZone(TOKYO)
        zoneSource.refresh()
        advanceUntilIdle()

        assertTrue(dateRangeCalls.count { it == code(NOV_11) to code(NOV_11) } > readsBefore)
        assertEquals(code(NOV_11), vm.dayDetail.value?.dayCode)
        assertEquals(at(NOV_11, 15, 30, TOKYO), vm.dayDetail.value?.dateMs)
    }

    @Test
    fun `after a zone change the sheet takes the new zone's time at once, before its re-read`() = runTest {
        eventsByDay[code(NOV_11)] = persistentListOf(eventOn(1, NOV_11))
        stallingDays += code(NOV_11)
        val vm = createViewModel()
        collectDayDetail(vm)
        openFullMonth(vm, at(NOV_11, 15, 30))

        setZone(TOKYO)
        zoneSource.refresh()
        advanceUntilIdle()

        // The re-read never lands, so New event's start comes from the rezoned day sheet date.
        assertEquals(at(NOV_11, 15, 30, TOKYO), vm.dayDetail.value?.dateMs)
        assertEquals(code(NOV_11), vm.dayDetail.value?.dayCode)
        assertEquals(eventsByDay[code(NOV_11)], vm.dayDetail.value?.events)
    }

    @Test
    fun `the arrows stop at the span ends`() = runTest {
        val vm = createViewModel()
        collectDayDetail(vm)
        openFullMonth(vm, at(CalendarSpan.lastDay(), 12))
        assertEquals(false, vm.dayDetail.value?.canGoForward)
        assertEquals(true, vm.dayDetail.value?.canGoBack)

        vm.dismissDayDetail()
        vm.showDayDetail(at(NOV_11, 12))
        advanceUntilIdle()
        assertEquals(true, vm.dayDetail.value?.canGoForward)
        assertEquals(true, vm.dayDetail.value?.canGoBack)
    }

    private fun dateOf(ms: Long): LocalDate =
        java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

    private companion object {
        val LA: ZoneId = ZoneId.of("America/Los_Angeles")
        val TOKYO: ZoneId = ZoneId.of("Asia/Tokyo")
        val NOV_11: LocalDate = LocalDate.of(2026, 11, 11)
        val NOV_12: LocalDate = LocalDate.of(2026, 11, 12)
    }
}
