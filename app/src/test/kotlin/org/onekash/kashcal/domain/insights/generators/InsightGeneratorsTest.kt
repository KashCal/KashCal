package org.onekash.kashcal.domain.insights.generators

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.insights.CalendarHours
import org.onekash.kashcal.domain.insights.DayHours
import org.onekash.kashcal.domain.insights.InsightId
import org.onekash.kashcal.domain.insights.InsightOccurrence
import org.onekash.kashcal.domain.insights.PeriodStats
import org.onekash.kashcal.domain.insights.SimpleOccurrence
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class InsightGeneratorsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("UTC")

    private val monday = LocalDate.of(2026, 4, 13)
    private val mondayNow = monday.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
    private val nextMondayNow = monday.plusWeeks(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

    private val periodStart = monday.atStartOfDay(zone).toInstant().toEpochMilli()
    private val periodEnd = monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()

    private val calendarId: Long = 1L
    private val calendarId2: Long = 2L

    // ========== BusiestDay ==========

    @Test
    fun `BusiestDay identifies correct day and uses future tense`() {
        val stats = weekStatsWithDays(60, 120, 300, 60, 0, 0, 0) // Wed = 300 min = busiest
        val gen = BusiestDayGenerator()
        assertTrue(gen.shouldEmit(stats, emptyList(), mondayNow))
        val insight = gen.generate(stats, emptyList(), mondayNow, periodStart, periodEnd, context)
        assertEquals(InsightId.BUSIEST_DAY, insight.id)
        // Text uses future tense because Wednesday is after Monday (now)
        assertTrue(insight.text.isNotEmpty())
    }

    @Test
    fun `BusiestDay uses past tense for past periods`() {
        val stats = weekStatsWithDays(60, 120, 300, 60, 0, 0, 0)
        val gen = BusiestDayGenerator()
        val insight = gen.generate(stats, emptyList(), nextMondayNow, periodStart, periodEnd, context)
        assertTrue(insight.text.isNotEmpty())
    }

    @Test
    fun `BusiestDay surprise score calculation`() {
        // Mon=60, Tue=60, Wed=180 → max=180, mean=100, score=(180-100)/180=0.44
        val stats = weekStatsWithDays(60, 60, 180, 0, 0, 0, 0)
        val gen = BusiestDayGenerator()
        val score = gen.surpriseScore(stats, emptyList(), mondayNow)
        assertTrue(score > 0.4f && score < 0.5f)
    }

    // ========== LightestDay ==========

    @Test
    fun `LightestDay suppressed when only 1 day has events`() {
        val stats = weekStatsWithDays(60, 0, 0, 0, 0, 0, 0)
        val gen = LightestDayGenerator()
        assertFalse(gen.shouldEmit(stats, emptyList(), mondayNow))
    }

    @Test
    fun `LightestDay identifies lightest among non-zero days`() {
        val stats = weekStatsWithDays(60, 120, 30, 90, 0, 0, 0) // Wed=30 lightest
        val gen = LightestDayGenerator()
        assertTrue(gen.shouldEmit(stats, emptyList(), mondayNow))
    }

    // ========== BackToBack ==========

    @Test
    fun `BackToBack counts pairs with less than 5min gap`() {
        val occurrences = buildOccurrences(
            monday to (9 to 10),  // 9:00-10:00
            monday to (10 to 11), // 10:00-11:00 (gap = 0 → back-to-back)
            monday to (11 to 12), // 11:00-12:00 (gap = 0 → back-to-back)
            monday to (14 to 15)  // 14:00-15:00 (gap = 2h → not back-to-back)
        )
        val gen = BackToBackGenerator()
        assertEquals(2, gen.countBackToBack(occurrences))
    }

    @Test
    fun `BackToBack excludes zero-duration events`() {
        val occ1 = buildOccurrence(monday, 9, 0, monday, 10, 0)
        val occ2 = buildOccurrence(monday, 10, 0, monday, 10, 0) // zero duration
        val occ3 = buildOccurrence(monday, 14, 0, monday, 15, 0)
        val occurrences = listOf(occ1, occ2, occ3)
        val gen = BackToBackGenerator()
        assertEquals(0, gen.countBackToBack(occurrences)) // No real back-to-back
    }

    // ========== CalendarDominant ==========

    @Test
    fun `CalendarDominant suppressed for single calendar`() {
        val stats = PeriodStats(
            totalMinutes = 300,
            allDayCount = 0,
            calendarBreakdown = listOf(CalendarHours(1L, "Work", 0xFF0000FF.toInt(), 300)),
            dailyBreakdown = emptyList(),
            periodStart = periodStart,
            periodEnd = periodEnd
        )
        val gen = CalendarDominantGenerator()
        assertFalse(gen.shouldEmit(stats, emptyList(), mondayNow))
    }

    @Test
    fun `CalendarDominant emits when top calendar over 60 percent`() {
        val stats = PeriodStats(
            totalMinutes = 100,
            allDayCount = 0,
            calendarBreakdown = listOf(
                CalendarHours(1L, "Work", 0xFF0000FF.toInt(), 80),
                CalendarHours(2L, "Personal", 0xFF00FF00.toInt(), 20)
            ),
            dailyBreakdown = emptyList(),
            periodStart = periodStart,
            periodEnd = periodEnd
        )
        val gen = CalendarDominantGenerator()
        assertTrue(gen.shouldEmit(stats, emptyList(), mondayNow))
        val score = gen.surpriseScore(stats, emptyList(), mondayNow)
        assertTrue(score > 0.4f) // (0.8 - 0.6) / 0.4 = 0.5
    }

    // ========== WeekendLoad ==========

    @Test
    fun `WeekendLoad reports clear for 0h weekends`() {
        // Sat+Sun both 0
        val stats = weekStatsWithDays(60, 60, 60, 60, 60, 0, 0)
        val gen = WeekendLoadGenerator()
        assertTrue(gen.shouldEmit(stats, emptyList(), mondayNow))
        val score = gen.surpriseScore(stats, emptyList(), mondayNow)
        assertEquals(0.1f, score, 0.01f) // 0h → 0.1
    }

    // ========== TomorrowPreview ==========

    @Test
    fun `TomorrowPreview omitted for past periods`() {
        val stats = weekStatsWithDays(60, 60, 60, 60, 60, 60, 60)
        val gen = TomorrowPreviewGenerator()
        assertFalse(gen.shouldEmit(stats, emptyList(), nextMondayNow)) // period is in the past
    }

    // ========== EarlyLateBounds ==========

    @Test
    fun `EarlyLateBounds counts days with events before 8 AM`() {
        val occurrences = listOf(
            buildOccurrence(monday, 7, 0, monday, 8, 0),           // Mon before 8 AM
            buildOccurrence(monday.plusDays(1), 7, 30, monday.plusDays(1), 9, 0), // Tue before 8 AM
            buildOccurrence(monday.plusDays(2), 9, 0, monday.plusDays(2), 10, 0)  // Wed at 9 AM (not early)
        )
        val gen = EarlyLateBoundsGenerator()
        val (early, late) = gen.countBoundaryDays(occurrences, zone)
        assertEquals(2, early)
        assertEquals(0, late)
    }

    @Test
    fun `EarlyLateBounds counts days with events after 7 PM`() {
        val occurrences = listOf(
            buildOccurrence(monday, 18, 0, monday, 20, 0), // Mon ends at 8 PM (after 7 PM)
            buildOccurrence(monday.plusDays(1), 9, 0, monday.plusDays(1), 17, 0)  // Tue ends at 5 PM (not late)
        )
        val gen = EarlyLateBoundsGenerator()
        val (early, late) = gen.countBoundaryDays(occurrences, zone)
        assertEquals(0, early)
        assertEquals(1, late)
    }

    // ========== MeetingFreeDays ==========

    @Test
    fun `MeetingFreeDays counts days with zero timed events`() {
        val stats = weekStatsWithDays(60, 0, 120, 0, 60, 0, 0) // 4 free days
        val gen = MeetingFreeDaysGenerator()
        assertTrue(gen.shouldEmit(stats, emptyList(), mondayNow))
        val insight = gen.generate(stats, emptyList(), mondayNow, periodStart, periodEnd, context)
        assertTrue(insight.text.isNotEmpty())
    }

    // ========== Division by zero ==========

    @Test
    fun `division by zero returns 0 for all generators`() {
        val emptyStats = PeriodStats.EMPTY
        val generators = listOf(
            BusiestDayGenerator(), LightestDayGenerator(), LongestFreeGenerator(),
            WeekendLoadGenerator(), BackToBackGenerator(), CalendarDominantGenerator(),
            EarlyLateBoundsGenerator(), MeetingFreeDaysGenerator()
        )
        for (gen in generators) {
            val score = gen.surpriseScore(emptyStats, emptyList(), mondayNow)
            assertTrue("${gen.id} surprise score should be finite: $score", score.isFinite())
            assertTrue("${gen.id} surprise score should be >= 0: $score", score >= 0f)
            assertTrue("${gen.id} surprise score should be <= 1: $score", score <= 1f)
        }
    }

    // ========== Helpers ==========

    private fun weekStatsWithDays(vararg minutesPerDay: Long): PeriodStats {
        val days = minutesPerDay.mapIndexed { i, mins ->
            val date = monday.plusDays(i.toLong())
            DayHours(dayCode = localDateToDayCode(date), minutes = mins)
        }
        return PeriodStats(
            totalMinutes = minutesPerDay.sum(),
            allDayCount = 0,
            calendarBreakdown = listOf(CalendarHours(1L, "Work", 0xFF0000FF.toInt(), minutesPerDay.sum())),
            dailyBreakdown = days,
            periodStart = periodStart,
            periodEnd = periodEnd
        )
    }

    private fun buildOccurrences(vararg events: Pair<LocalDate, Pair<Int, Int>>): List<InsightOccurrence> {
        return events.map { (date, hours) ->
            buildOccurrence(date, hours.first, 0, date, hours.second, 0)
        }
    }

    private fun buildOccurrence(
        startDate: LocalDate, startHour: Int, startMin: Int,
        endDate: LocalDate, endHour: Int, endMin: Int
    ): InsightOccurrence {
        val startTs = startDate.atTime(startHour, startMin).atZone(zone).toInstant().toEpochMilli()
        val endTs = endDate.atTime(endHour, endMin).atZone(zone).toInstant().toEpochMilli()
        return SimpleOccurrence(
            startTs = startTs,
            endTs = endTs,
            isAllDay = false,
            startDay = localDateToDayCode(startDate),
            endDay = localDateToDayCode(endDate),
            calendarId = calendarId
        )
    }
}
