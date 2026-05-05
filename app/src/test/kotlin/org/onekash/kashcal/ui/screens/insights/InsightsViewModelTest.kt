package org.onekash.kashcal.ui.screens.insights

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.onekash.kashcal.domain.insights.AnalysisPeriod
import org.onekash.kashcal.domain.insights.CalendarHours
import org.onekash.kashcal.domain.insights.DayHours
import org.onekash.kashcal.domain.insights.Insight
import org.onekash.kashcal.domain.insights.InsightEngine
import org.onekash.kashcal.domain.insights.InsightIcon
import org.onekash.kashcal.domain.insights.InsightId
import org.onekash.kashcal.domain.insights.InsightsRepository
import org.onekash.kashcal.domain.insights.PeriodStats
import org.onekash.kashcal.domain.insights.TemporalClass

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var insightsRepository: InsightsRepository
    private lateinit var insightEngine: InsightEngine
    private lateinit var context: Context

    private val sampleStats = PeriodStats(
        totalMinutes = 300,
        allDayCount = 2,
        calendarBreakdown = listOf(CalendarHours(1L, "Work", 0xFF0000FF.toInt(), 300)),
        dailyBreakdown = (0..6).map { DayHours(dayCode = 20260413 + it, minutes = if (it < 5) 60L else 0L) },
        periodStart = 0L,
        periodEnd = 1L
    )

    private val sampleInsights = listOf(
        Insight(InsightId.BUSIEST_DAY, "Wed is busiest", InsightIcon.CHART_BAR, 0.8f),
        Insight(InsightId.BACK_TO_BACK, "3 back-to-back", InsightIcon.LINK, 0.6f)
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        insightsRepository = mockk()
        insightEngine = mockk()
        context = mockk(relaxed = true)
        every { context.getString(any()) } returns "test"
        every { context.getString(any(), *anyVararg()) } returns "test"

        coEvery { insightsRepository.getStatsWithOccurrences(any(), any()) } returns (sampleStats to emptyList())
        coEvery { insightsRepository.getDelta(any(), any(), any()) } returns null
        coEvery { insightsRepository.classifyPeriod(any(), any()) } returns TemporalClass.IN_PROGRESS
        every { insightEngine.computeInsights(any(), any(), any(), any()) } returns sampleInsights
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = InsightsViewModel(insightsRepository, insightEngine, context)

    @Test
    fun `initial load populates stats and insights`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(300L, state.stats.totalMinutes)
        assertEquals(2, state.insights.size)
        assertEquals(AnalysisPeriod.THIS_WEEK, state.period)
        assertEquals(TemporalClass.IN_PROGRESS, state.temporalClass)
    }

    @Test
    fun `onPeriodChange updates period and reloads`() = runTest {
        val monthStats = sampleStats.copy(totalMinutes = 900)
        coEvery { insightsRepository.getStatsWithOccurrences(AnalysisPeriod.THIS_MONTH, any()) } returns (monthStats to emptyList())
        coEvery { insightsRepository.classifyPeriod(AnalysisPeriod.THIS_MONTH, any()) } returns TemporalClass.IN_PROGRESS

        val vm = createViewModel()
        advanceUntilIdle()

        vm.onPeriodChange(AnalysisPeriod.THIS_MONTH)
        advanceUntilIdle()

        assertEquals(AnalysisPeriod.THIS_MONTH, vm.uiState.value.period)
        assertEquals(900L, vm.uiState.value.stats.totalMinutes)
    }

    @Test
    fun `onPeriodChange same period is no-op`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val stateBefore = vm.uiState.value
        vm.onPeriodChange(AnalysisPeriod.THIS_WEEK)
        advanceUntilIdle()

        assertEquals(stateBefore.stats, vm.uiState.value.stats)
    }

    @Test
    fun `empty state when no events`() = runTest {
        coEvery { insightsRepository.getStatsWithOccurrences(any(), any()) } returns (PeriodStats.EMPTY to emptyList())
        every { insightEngine.computeInsights(any(), any(), any(), any()) } returns emptyList()

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(0L, vm.uiState.value.stats.totalMinutes)
        assertTrue(vm.uiState.value.insights.isEmpty())
    }

    @Test
    fun `resetToThisWeek resets period`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onPeriodChange(AnalysisPeriod.THIS_MONTH)
        advanceUntilIdle()
        assertEquals(AnalysisPeriod.THIS_MONTH, vm.uiState.value.period)

        vm.resetToThisWeek()
        advanceUntilIdle()
        assertEquals(AnalysisPeriod.THIS_WEEK, vm.uiState.value.period)
    }

    @Test
    fun `delta text populated when available`() = runTest {
        coEvery { insightsRepository.getDelta(any(), any(), any()) } returns "+2h 30m"

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("+2h 30m", vm.uiState.value.deltaText)
    }
}
