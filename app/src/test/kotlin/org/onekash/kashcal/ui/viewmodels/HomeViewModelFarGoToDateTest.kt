package org.onekash.kashcal.ui.viewmodels

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.every
import kotlinx.collections.immutable.persistentMapOf
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.ui.util.MonthPagerUtils
import org.onekash.kashcal.ui.util.plannerWeekStart
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

/**
 * The real [HomeViewModel] sends every far date to a page its pager has, and holds the same
 * month, week and day the pager then shows: Week view and Planner Go to date 12 years ahead
 * (past the old 1000-week pager), a Quick Add or link date 400 years out, and the Year view's
 * January of the span's first year.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class HomeViewModelFarGoToDateTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var factory: DeviceHomeViewModelTestFactory
    private val firstDay = MutableStateFlow(Calendar.SUNDAY)
    private val today = LocalDate.now()

    /** 12 years ahead: beyond the 1000-week pager (about 9.6 years each way) the bug hit. */
    private val far = today.plusYears(12)

    /** Every (start, end) day-code range the Planner asked for, in order. */
    private val requested = mutableListOf<Pair<Int, Int>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        factory = DeviceHomeViewModelTestFactory()
        coEvery { factory.accountRepository.getAllAccounts() } returns emptyList()
        every { factory.dataStore.firstDayOfWeek } returns firstDay
        every { factory.dataStore.showMultiDayTimedInAllDayStrip } returns MutableStateFlow(false)
        every { factory.displayEventRepository.getDisplayEventsForDateRange(any(), any()) } answers {
            requested += firstArg<Int>() to secondArg<Int>()
            MutableStateFlow(persistentMapOf())
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun newViewModel(): HomeViewModel {
        val device = FakeCalendarProviderRepository()
        return factory.create(device.deviceEventReader(), device.deviceEventWriter(factory.dataStore), dispatcher)
    }

    private fun msOf(date: LocalDate) = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `Week view Go to date 12 years ahead targets a page of the pager holding that date`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.WEEK, persist = false)
        vm.onWeekViewDateSelected(msOf(far))

        val state = vm.uiState.value
        val page = state.pendingWeekViewPagerPosition!!
        assertTrue("page $page of ${WeekViewUtils.TOTAL_WEEK_PAGES}", page in 0 until WeekViewUtils.TOTAL_WEEK_PAGES)
        val start = WeekViewUtils.weekPageToStartDate(page, Calendar.SUNDAY)
        assertTrue("week of $start holds $far", !far.isBefore(start) && far.isBefore(start.plusDays(7)))
        assertEquals("the data loads for the same page", page, state.weekViewPagerPosition)
    }

    @Test
    fun `Planner Go to date 12 years ahead targets a page of the pager and loads that week`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.PLANNER, persist = false)
        vm.plannerState.test {
            advanceUntilIdle()
            vm.onWeekViewDateSelected(msOf(far))
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        val state = vm.uiState.value
        val page = state.pendingPlannerPagerPosition!!
        assertTrue("page $page of ${WeekViewUtils.TOTAL_WEEK_PAGES}", page in 0 until WeekViewUtils.TOTAL_WEEK_PAGES)
        assertEquals(page, state.plannerWeekPagerPosition)
        val start = plannerWeekStart(state.plannerAnchorDayCode!!, page, Calendar.SUNDAY)
        assertTrue("week of $start holds $far", !far.isBefore(start) && far.isBefore(start.plusDays(7)))
        assertEquals("the Planner loads that week", DayPagerUtils.localDateToDayCode(start.minusDays(7)), requested.last().first)
    }

    @Test
    fun `navigateToDate 400 years ahead lands on the span's last day`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.navigateToDate(today.plusYears(400))

        val state = vm.uiState.value
        val lastDay = LocalDate.of(today.year + 150, 12, 31)
        assertEquals(lastDay.year, state.viewingYear)
        assertEquals(11, state.viewingMonth)
        assertEquals(lastDay.year to 11, state.pendingNavigateToMonth)
        assertEquals(DayPagerUtils.localDateToDayCode(lastDay), DayPagerUtils.msToDayCode(state.selectedDate))
        assertMonthPageInRange(state.viewingYear, state.viewingMonth)
    }

    @Test
    fun `navigateToDate 400 years back lands on the span's first day`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.navigateToDate(today.minusYears(400))

        val state = vm.uiState.value
        val firstDay = LocalDate.of(today.year - 150, 1, 1)
        assertEquals(firstDay.year, state.viewingYear)
        assertEquals(0, state.viewingMonth)
        assertEquals(DayPagerUtils.localDateToDayCode(firstDay), DayPagerUtils.msToDayCode(state.selectedDate))
        assertMonthPageInRange(state.viewingYear, state.viewingMonth)
    }

    @Test
    fun `navigateToMonth 400 years out lands on the span's end month`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.navigateToMonth(today.year + 400, 5)
        assertEquals(today.year + 150 to 11, vm.uiState.value.let { it.viewingYear to it.viewingMonth })

        vm.navigateToMonth(today.year - 400, 5)
        assertEquals(today.year - 150 to 0, vm.uiState.value.let { it.viewingYear to it.viewingMonth })
    }

    @Test
    fun `the Year view's January of the span's first year opens on a page of the month pager`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        // The Year view month tap: select the month's first day, then switch to Month.
        vm.selectDate(msOf(LocalDate.of(today.year - 150, 1, 1)))
        vm.setViewMode(ViewMode.YEAR, persist = false)
        vm.setViewMode(ViewMode.MONTH, persist = false)

        val state = vm.uiState.value
        assertEquals(today.year - 150 to 0, state.viewingYear to state.viewingMonth)
        assertMonthPageInRange(state.viewingYear, state.viewingMonth)
        val dayPage = DayPagerUtils.dateToPage(state.selectedDate, DayPagerUtils.getTodayMidnightMs())
        assertTrue("day page $dayPage", dayPage in 0 until DayPagerUtils.TOTAL_PAGES)
    }

    @Test
    fun `the Year view's December of the span's last year opens on a page of the month pager`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.selectDate(msOf(LocalDate.of(today.year + 150, 12, 1)))
        vm.setViewMode(ViewMode.YEAR, persist = false)
        vm.setViewMode(ViewMode.MONTH, persist = false)

        val state = vm.uiState.value
        assertEquals(today.year + 150 to 11, state.viewingYear to state.viewingMonth)
        assertMonthPageInRange(state.viewingYear, state.viewingMonth)
    }

    @Test
    fun `Week view Go to date 400 years ahead lands on the span's last week`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.WEEK, persist = false)
        vm.onWeekViewDateSelected(msOf(today.plusYears(400)))

        val page = vm.uiState.value.pendingWeekViewPagerPosition!!
        assertTrue("page $page", page in 0 until WeekViewUtils.TOTAL_WEEK_PAGES)
        val start = WeekViewUtils.weekPageToStartDate(page, Calendar.SUNDAY)
        val lastDay = LocalDate.of(today.year + 150, 12, 31)
        assertTrue("week of $start holds $lastDay", !lastDay.isBefore(start) && lastDay.isBefore(start.plusDays(7)))
    }

    @Test
    fun `Planner Go to date 400 years back lands on the span's first week`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.PLANNER, persist = false)
        vm.onWeekViewDateSelected(msOf(today.minusYears(400)))

        val state = vm.uiState.value
        val page = state.pendingPlannerPagerPosition!!
        assertTrue("page $page", page in 0 until WeekViewUtils.TOTAL_WEEK_PAGES)
        val start = plannerWeekStart(state.plannerAnchorDayCode!!, page, Calendar.SUNDAY)
        val firstDay = LocalDate.of(today.year - 150, 1, 1)
        assertTrue("week of $start holds $firstDay", !firstDay.isBefore(start) && firstDay.isBefore(start.plusDays(7)))
    }

    @Test
    fun `Day view Go to date keeps a date outside the span, its pager has no end`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.DAY, persist = false)
        val outside = today.plusYears(400)
        vm.onWeekViewDateSelected(msOf(outside))

        assertEquals(WeekViewUtils.dateToPage(outside), vm.uiState.value.pendingWeekViewPagerPosition)
    }

    @Test
    fun `selecting a day outside the span, as an ICS import does, selects the span's nearest end`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.selectDate(msOf(today.plusYears(274)))
        assertEquals(
            DayPagerUtils.localDateToDayCode(LocalDate.of(today.year + 150, 12, 31)),
            DayPagerUtils.msToDayCode(vm.uiState.value.selectedDate),
        )

        vm.selectDate(msOf(today.minusYears(176)))
        assertEquals(
            DayPagerUtils.localDateToDayCode(LocalDate.of(today.year - 150, 1, 1)),
            DayPagerUtils.msToDayCode(vm.uiState.value.selectedDate),
        )
    }

    private fun assertMonthPageInRange(year: Int, month0: Int) {
        val page = MonthPagerUtils.yearMonthToPage(year, month0, today.year, today.monthValue - 1)
        assertTrue("month page $page of ${MonthPagerUtils.TOTAL_PAGES}", page in 0 until MonthPagerUtils.TOTAL_PAGES)
    }
}
