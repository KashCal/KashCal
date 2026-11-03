package org.onekash.kashcal.ui.viewmodels

import io.mockk.coEvery
import io.mockk.every
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.util.ZoneSource
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * Tests that [HomeViewModel] re-reads every view when the device zone changes (#422): a zone
 * change rewrites no Room row, so the reactive reads restart on [HomeViewModel.displayZone],
 * the one-shot dots and the day pager cache are rebuilt, and the dates it keeps as local
 * midnights move to the same date in the new zone. The zone changes the way the app sees it:
 * `TimeZone.setDefault`, then a [ZoneSource] refresh (the broadcast) or
 * [HomeViewModel.onAppResume] (a missed broadcast). Collaborators are the strict mocks of
 * [DeviceHomeViewModelTestFactory], with the repository reads recorded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HomeViewModelZoneChangeTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var factory: DeviceHomeViewModelTestFactory
    private lateinit var savedZone: TimeZone
    private lateinit var zoneSource: ZoneSource

    private val rangeCalls = mutableListOf<Pair<Long, Long>>()
    private val dateRangeCalls = mutableListOf<Pair<Int, Int>>()
    private val dayRangeCalls = mutableListOf<Long>()
    private val dotCalls = mutableListOf<Pair<Int, Int>>()
    private val searchCalls = mutableListOf<Triple<String, Int, Int>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        savedZone = TimeZone.getDefault()
        setZone(LA)
        zoneSource = ZoneSource()
        factory = DeviceHomeViewModelTestFactory()
        coEvery { factory.accountRepository.getAllAccounts() } returns emptyList()
        val repo = factory.displayEventRepository
        every { repo.getDisplayEventsForRange(any(), any()) } answers {
            rangeCalls += firstArg<Long>() to secondArg<Long>()
            MutableStateFlow(persistentListOf())
        }
        every { repo.getDisplayEventsForDateRange(any(), any()) } answers {
            dateRangeCalls += firstArg<Int>() to secondArg<Int>()
            MutableStateFlow(persistentMapOf())
        }
        every { repo.getDisplayEventsForDayRange(any()) } answers {
            dayRangeCalls += firstArg<Long>()
            MutableStateFlow(persistentMapOf())
        }
        coEvery { repo.searchDisplayEvents(any(), any(), any(), any()) } answers {
            searchCalls += Triple(firstArg<String>(), secondArg<Int>(), thirdArg<Int>())
            emptyList()
        }
        coEvery { repo.getDisplayEventsGroupedByDayOnce(any(), any()) } answers {
            dotCalls += firstArg<Int>() to secondArg<Int>()
            emptyMap()
        }
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(savedZone)
        Dispatchers.resetMain()
    }

    private fun setZone(zone: ZoneId) = TimeZone.setDefault(TimeZone.getTimeZone(zone))

    private fun createViewModel(): HomeViewModel {
        val device = FakeCalendarProviderRepository()
        return factory.create(
            device.deviceEventReader(), device.deviceEventWriter(factory.dataStore), dispatcher,
            zoneSource = zoneSource,
        )
    }

    private fun midnight(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun dateOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** Subscribes to every reactive view so their reads run, as the open screens would. */
    private fun TestScope.subscribeAll(vm: HomeViewModel) {
        backgroundScope.launch { vm.weekEvents.collect {} }
        backgroundScope.launch { vm.agendaEvents.collect {} }
        backgroundScope.launch { vm.monthEvents.collect {} }
        backgroundScope.launch { vm.plannerState.collect {} }
    }

    @Test
    fun `the time grid re-queries the same dates at the new zone's midnights`() = runTest {
        val vm = createViewModel()
        subscribeAll(vm)
        advanceUntilIdle()
        vm.setViewMode(ViewMode.WEEK, persist = false)
        vm.onWeekViewDateSelected(midnight(NOV_4, LA))
        advanceUntilIdle()
        val (laStart, laEnd) = rangeCalls.last()
        val startDate = dateOf(laStart, LA)
        val endDate = dateOf(laEnd, LA)

        setZone(TOKYO)
        zoneSource.refresh()
        advanceUntilIdle()

        val (tokyoStart, tokyoEnd) = rangeCalls.last()
        assertEquals(midnight(startDate, TOKYO), tokyoStart)
        assertEquals(midnight(endDate, TOKYO), tokyoEnd)
        assertEquals(TOKYO, vm.displayZone.value)
    }

    @Test
    fun `the time grid keeps its first shown date when today moves`() = runTest {
        val vm = createViewModel()
        subscribeAll(vm)
        advanceUntilIdle()
        vm.setViewMode(ViewMode.WEEK, persist = false)
        vm.onWeekViewDateSelected(midnight(NOV_4, LA))
        advanceUntilIdle()
        val fdow = vm.uiState.value.firstDayOfWeek
        val shownBefore = WeekViewUtils.weekPageToStartDate(
            vm.uiState.value.weekViewPagerPosition, fdow, LocalDate.now(LA)
        )

        setZone(TOKYO)
        zoneSource.refresh()
        advanceUntilIdle()

        val shownAfter = WeekViewUtils.weekPageToStartDate(
            vm.uiState.value.weekViewPagerPosition, fdow, LocalDate.now(TOKYO)
        )
        assertEquals(shownBefore, shownAfter)
    }

    @Test
    fun `the agenda, full month and planner re-run their reads`() = runTest {
        val vm = createViewModel()
        subscribeAll(vm)
        advanceUntilIdle()
        vm.setViewMode(ViewMode.AGENDA, persist = false)
        advanceUntilIdle()
        val agendaBefore = rangeCalls.size
        setZone(TOKYO)
        zoneSource.refresh()
        advanceUntilIdle()
        assertTrue("agenda re-read", rangeCalls.size > agendaBefore)

        vm.setViewMode(ViewMode.MONTH_FULL, persist = false)
        advanceUntilIdle()
        val monthBefore = dateRangeCalls.size
        setZone(LA)
        zoneSource.refresh()
        advanceUntilIdle()
        assertTrue("full month re-read", dateRangeCalls.size > monthBefore)

        vm.setViewMode(ViewMode.PLANNER, persist = false)
        advanceUntilIdle()
        val plannerBefore = dateRangeCalls.size
        setZone(TOKYO)
        zoneSource.refresh()
        advanceUntilIdle()
        assertTrue("planner re-read", dateRangeCalls.size > plannerBefore)
    }

    @Test
    fun `dots cached for another year are read again after a zone change`() = runTest {
        val vm = createViewModel()
        subscribeAll(vm)
        advanceUntilIdle()
        vm.setViewMode(ViewMode.YEAR, persist = false)
        advanceUntilIdle()
        val otherYear = vm.uiState.value.viewingYear - 3
        vm.ensureDotsForYear(otherYear)
        advanceUntilIdle()
        val otherYearReads = dotCalls.filter { it.first / 10000 == otherYear }
        assertTrue("other year loaded", otherYearReads.isNotEmpty())
        // Cached: asking again reads nothing.
        val cachedCount = dotCalls.size
        vm.ensureDotsForYear(otherYear)
        advanceUntilIdle()
        assertEquals(cachedCount, dotCalls.size)

        setZone(TOKYO)
        zoneSource.refresh()
        advanceUntilIdle()
        val afterChange = dotCalls.size
        vm.ensureDotsForYear(otherYear)
        advanceUntilIdle()

        val reread = dotCalls.drop(afterChange).filter { it.first / 10000 == otherYear }
        assertEquals("the zone change drops the cached year", otherYearReads.toSet(), reread.toSet())
    }

    @Test
    fun `the viewed month's dots are read again after a zone change`() = runTest {
        val vm = createViewModel()
        subscribeAll(vm)
        advanceUntilIdle()
        val before = dotCalls.toList()
        assertTrue("month dots loaded at start", before.isNotEmpty())

        setZone(TOKYO)
        zoneSource.refresh()
        advanceUntilIdle()

        assertTrue("same ranges re-read", dotCalls.drop(before.size).toSet().containsAll(before.toSet()))
    }

    @Test
    fun `a selected date stays the same date after moving west`() = runTest {
        setZone(TOKYO)
        zoneSource.refresh()
        val vm = createViewModel()
        subscribeAll(vm)
        advanceUntilIdle()
        vm.selectDate(midnight(NOV_4, TOKYO))
        vm.loadEventsForDayPagerRange(midnight(NOV_4, TOKYO))
        advanceUntilIdle()

        setZone(LA)
        zoneSource.refresh()
        advanceUntilIdle()

        assertEquals(midnight(NOV_4, LA), vm.uiState.value.selectedDate)
        assertEquals(midnight(NOV_4, LA), dayRangeCalls.last())
    }

    @Test
    fun `a selected date stays the same date after moving east`() = runTest {
        val vm = createViewModel()
        subscribeAll(vm)
        advanceUntilIdle()
        vm.selectDate(midnight(NOV_4, LA))
        advanceUntilIdle()

        setZone(TOKYO)
        zoneSource.refresh()
        advanceUntilIdle()

        assertEquals(midnight(NOV_4, TOKYO), vm.uiState.value.selectedDate)
    }

    @Test
    fun `the month day list is re-read after a zone change made in Month full`() = runTest {
        val vm = createViewModel()
        subscribeAll(vm)
        advanceUntilIdle()
        vm.selectDate(midnight(NOV_4, LA))
        vm.loadEventsForDayPagerRange(midnight(NOV_4, LA))
        advanceUntilIdle()
        vm.setViewMode(ViewMode.MONTH_FULL, persist = false)
        advanceUntilIdle()
        val before = dayRangeCalls.size

        setZone(TOKYO)
        zoneSource.refresh()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.MONTH, persist = false)
        advanceUntilIdle()

        assertTrue("day pager cache re-read", dayRangeCalls.size > before)
        assertEquals(midnight(NOV_4, TOKYO), dayRangeCalls.last())
        assertEquals(midnight(NOV_4, TOKYO), vm.uiState.value.cacheRangeCenter)
    }

    @Test
    fun `an applied search date filter keeps its dates`() = runTest {
        val vm = createViewModel()
        subscribeAll(vm)
        advanceUntilIdle()
        vm.setSearchDateFilter(DateFilter.SingleDay(midnight(NOV_4, LA)))
        advanceUntilIdle()

        setZone(TOKYO)
        zoneSource.refresh()
        advanceUntilIdle()

        assertEquals(DateFilter.SingleDay(midnight(NOV_4, TOKYO)), vm.uiState.value.searchDateFilter)

        vm.setSearchDateFilter(DateFilter.CustomRange(midnight(NOV_4, TOKYO), midnight(NOV_4.plusDays(2), TOKYO)))
        advanceUntilIdle()
        setZone(LA)
        zoneSource.refresh()
        advanceUntilIdle()

        assertEquals(
            DateFilter.CustomRange(midnight(NOV_4, LA), midnight(NOV_4.plusDays(2), LA)),
            vm.uiState.value.searchDateFilter,
        )
    }

    @Test
    fun `an open search runs again for the same dates after a zone change`() = runTest {
        val vm = createViewModel()
        subscribeAll(vm)
        advanceUntilIdle()
        vm.activateSearch()
        vm.updateSearchQuery("zsf")
        vm.setSearchDateFilter(DateFilter.SingleDay(midnight(NOV_4, LA)))
        advanceUntilIdle()
        val before = searchCalls.size
        assertTrue("searched before the change", before > 0)

        setZone(TOKYO)
        zoneSource.refresh()
        advanceUntilIdle()

        assertTrue("searched again", searchCalls.size > before)
        assertEquals(Triple("zsf", 20261104, 20261104), searchCalls.last())
    }

    @Test
    fun `the day sheet keeps its day and time of day after a zone change`() = runTest {
        val vm = createViewModel()
        subscribeAll(vm)
        advanceUntilIdle()
        val laAfternoon = NOV_4.atTime(15, 30).atZone(LA).toInstant().toEpochMilli()
        vm.showDayDetail(laAfternoon)
        advanceUntilIdle()

        setZone(TOKYO)
        zoneSource.refresh()
        advanceUntilIdle()

        assertEquals(NOV_4.atTime(15, 30).atZone(TOKYO).toInstant().toEpochMilli(), vm.uiState.value.dayDetailDate)
    }

    @Test
    fun `resume catches a zone change whose broadcast was missed`() = runTest {
        val vm = createViewModel()
        subscribeAll(vm)
        advanceUntilIdle()
        vm.setViewMode(ViewMode.WEEK, persist = false)
        vm.onWeekViewDateSelected(midnight(NOV_4, LA))
        advanceUntilIdle()
        val startDate = dateOf(rangeCalls.last().first, LA)

        setZone(TOKYO)
        vm.onAppResume()
        advanceUntilIdle()

        assertEquals(TOKYO, vm.displayZone.value)
        assertEquals(midnight(startDate, TOKYO), rangeCalls.last().first)
    }

    @Test
    fun `resume after a zone change lands on today in the new zone`() = runTest {
        val vm = createViewModel()
        subscribeAll(vm)
        advanceUntilIdle()
        vm.onAppResume()
        vm.selectDate(midnight(LocalDate.now(LA), LA))
        advanceUntilIdle()

        setZone(TOKYO)
        vm.onAppResume()
        advanceUntilIdle()

        assertEquals(midnight(LocalDate.now(TOKYO), TOKYO), vm.uiState.value.selectedDate)
    }

    // Pager pages count from today, and today differs between the zones for most of the day;
    // these pin the two todays so the mapping is checked whatever the clock reads.

    @Test
    fun `a day page keeps its date when today moves forward`() {
        val oldToday = LocalDate.of(2026, 11, 3)
        val newToday = LocalDate.of(2026, 11, 4)
        val page = WeekViewUtils.dateToPage(NOV_4, oldToday)

        val moved = rezonePagerPosition(page, ViewMode.DAY, java.util.Calendar.SUNDAY, oldToday, newToday)

        assertEquals(NOV_4, WeekViewUtils.pageToDate(moved, newToday))
    }

    @Test
    fun `a week page keeps its week when today crosses a week boundary`() {
        // Sat Nov 7 to Sun Nov 8 with weeks starting on Sunday.
        val oldToday = LocalDate.of(2026, 11, 7)
        val newToday = LocalDate.of(2026, 11, 8)
        val sunday = java.util.Calendar.SUNDAY
        val page = WeekViewUtils.dateToWeekPage(NOV_4, sunday, oldToday)

        val moved = rezonePagerPosition(page, ViewMode.WEEK, sunday, oldToday, newToday)

        assertEquals(LocalDate.of(2026, 11, 1), WeekViewUtils.weekPageToStartDate(moved, sunday, newToday))
    }

    @Test
    fun `a midnight moves to the same date in the new zone, and no date stays no date`() {
        assertEquals(midnight(NOV_4, LA), rezoneMidnight(midnight(NOV_4, TOKYO), TOKYO, LA))
        assertEquals(midnight(NOV_4, TOKYO), rezoneMidnight(midnight(NOV_4, LA), LA, TOKYO))
        assertEquals(0L, rezoneMidnight(0L, LA, TOKYO))
    }

    private companion object {
        val LA: ZoneId = ZoneId.of("America/Los_Angeles")
        val TOKYO: ZoneId = ZoneId.of("Asia/Tokyo")
        val NOV_4: LocalDate = LocalDate.of(2026, 11, 4)
    }
}
