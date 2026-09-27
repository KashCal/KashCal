package org.onekash.kashcal.ui.screens.insights

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.insights.AnalysisPeriod
import org.onekash.kashcal.domain.insights.CalendarHours
import org.onekash.kashcal.domain.insights.DayHours
import org.onekash.kashcal.ui.theme.ColorSource
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.onekash.kashcal.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Checks that each Insights day bar is drawn as calendar-colored segments sized by minutes, in
 * the order of the day's split. Native graphics so the drawn pixel colors can be read back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class InsightsDailyChartTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val workColor = 0xFF1565C0.toInt()
    private val personalColor = 0xFF2E7D32.toInt()
    private val work = CalendarHours(1L, "Work", workColor, 0)
    private val personal = CalendarHours(2L, "Personal", personalColor, 0)

    private val mon = 20260413
    private val tue = 20260414
    private val wed = 20260415
    private val thu = 20260416

    // Mon: Work 120 + Personal 60 (busiest). Tue: minutes without a split. Wed: free.
    // Thu: a split carrying a 0-minute entry. Fri..Sun: free.
    private val week = listOf(
        DayHours(mon, 180, calendars = listOf(work.copy(minutes = 120), personal.copy(minutes = 60))),
        DayHours(tue, 60),
        DayHours(wed, 0),
        DayHours(thu, 60, calendars = listOf(work.copy(minutes = 60), personal.copy(minutes = 0))),
        DayHours(20260417, 0),
        DayHours(20260418, 0),
        DayHours(20260419, 0)
    )

    private var primaryColor = Color.Unspecified

    private fun render(
        period: AnalysisPeriod,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        days: List<DayHours> = week
    ) {
        composeTestRule.setContent {
            KashCalTheme(themeMode = ThemeMode.LIGHT, colorSource = ColorSource.SEED) {
                primaryColor = MaterialTheme.colorScheme.primary
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    DailyDistributionChart(days = days, period = period)
                }
            }
        }
    }

    private fun bounds(tag: String): Rect =
        composeTestRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun centerColor(tag: String): Color {
        val pixels = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    private fun assertAbsent(tag: String) {
        composeTestRule.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun week_bar_is_split_into_segments_sized_by_minutes_in_split_order() {
        render(AnalysisPeriod.THIS_WEEK)

        val workBounds = bounds(insightsDaySegmentTag(mon, work.calendarId))
        val personalBounds = bounds(insightsDaySegmentTag(mon, personal.calendarId))

        assertEquals(workBounds.width, personalBounds.width * 2, 1.5f)
        assertTrue(workBounds.left < personalBounds.left)
        assertEquals(workBounds.right, personalBounds.left, 1f)
    }

    @Test
    fun week_segments_are_drawn_in_their_calendar_colors() {
        render(AnalysisPeriod.THIS_WEEK)

        assertEquals(Color(workColor), centerColor(insightsDaySegmentTag(mon, work.calendarId)))
        assertEquals(Color(personalColor), centerColor(insightsDaySegmentTag(mon, personal.calendarId)))
    }

    @Test
    fun week_bar_starts_from_the_right_in_rtl() {
        render(AnalysisPeriod.THIS_WEEK, LayoutDirection.Rtl)

        val workBounds = bounds(insightsDaySegmentTag(mon, work.calendarId))
        val personalBounds = bounds(insightsDaySegmentTag(mon, personal.calendarId))

        assertTrue(workBounds.left > personalBounds.left)
    }

    @Test
    fun month_column_stacks_segments_with_the_first_calendar_at_the_bottom() {
        render(AnalysisPeriod.THIS_MONTH)

        val workBounds = bounds(insightsDaySegmentTag(mon, work.calendarId))
        val personalBounds = bounds(insightsDaySegmentTag(mon, personal.calendarId))

        assertTrue(workBounds.top > personalBounds.top)
        assertEquals(workBounds.height, personalBounds.height * 2, 1.5f)
        assertEquals(Color(workColor), centerColor(insightsDaySegmentTag(mon, work.calendarId)))
        assertEquals(Color(personalColor), centerColor(insightsDaySegmentTag(mon, personal.calendarId)))
    }

    @Test
    fun free_day_has_no_segments() {
        render(AnalysisPeriod.THIS_WEEK)

        assertAbsent(insightsDaySegmentTag(wed, work.calendarId))
        assertAbsent(insightsDaySegmentTag(wed, personal.calendarId))
        assertAbsent(insightsDayFallbackTag(wed))
    }

    @Test
    fun day_with_minutes_but_no_split_draws_one_primary_bar() {
        render(AnalysisPeriod.THIS_WEEK)

        assertEquals(primaryColor, centerColor(insightsDayFallbackTag(tue)))
        assertAbsent(insightsDaySegmentTag(tue, work.calendarId))
    }

    @Test
    fun month_day_with_minutes_but_no_split_draws_one_primary_bar() {
        render(AnalysisPeriod.THIS_MONTH)

        assertEquals(primaryColor, centerColor(insightsDayFallbackTag(tue)))
        assertAbsent(insightsDaySegmentTag(tue, work.calendarId))
    }

    @Test
    fun month_free_and_out_of_month_days_draw_no_segments() {
        val outOfMonth = DayHours(20260420, 90, isInMonth = false, calendars = listOf(work.copy(minutes = 90)))
        render(AnalysisPeriod.THIS_MONTH, days = week + outOfMonth)

        assertAbsent(insightsDaySegmentTag(wed, work.calendarId))
        assertAbsent(insightsDayFallbackTag(wed))
        assertAbsent(insightsDaySegmentTag(outOfMonth.dayCode, work.calendarId))
        assertAbsent(insightsDayFallbackTag(outOfMonth.dayCode))
    }

    @Test
    fun zero_minute_split_entry_is_skipped() {
        render(AnalysisPeriod.THIS_WEEK)

        assertEquals(Color(workColor), centerColor(insightsDaySegmentTag(thu, work.calendarId)))
        assertAbsent(insightsDaySegmentTag(thu, personal.calendarId))
        assertAbsent(insightsDayFallbackTag(thu))
    }
}
