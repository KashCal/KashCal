package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.screens.planner.PlannerView
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.ui.util.plannerPageForDate
import org.onekash.kashcal.ui.util.plannerWeekStart
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.util.Calendar

/**
 * The real Week view and Planner pagers settle on a Go to date page 12 years ahead, the week the
 * user picked; the old 1000-week pager stopped at its last page, about 9.6 years out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class WeekViewFarGoToDateTest {

    @get:Rule
    val rule = createComposeRule()

    private val today = LocalDate.now()
    private val far = today.plusYears(12)

    @Test
    fun `the Week view pager settles on the picked week 12 years ahead`() {
        val target = WeekViewUtils.dateToWeekPage(far, Calendar.SUNDAY)
        val settled = mutableListOf<Int>()
        rule.setContent {
            var pending by remember { mutableStateOf<Int?>(target) }
            MaterialTheme {
                WeekViewContent(
                    timedEvents = persistentListOf(),
                    allDayEvents = persistentListOf(),
                    isLoading = false,
                    error = null,
                    scrollPosition = 0,
                    visibleDays = 7,
                    firstDayOfWeek = Calendar.SUNDAY,
                    onDatePickerRequest = {},
                    onEventClick = {},
                    onScrollPositionChange = {},
                    onPageChanged = { settled += it },
                    pendingNavigateToPage = pending,
                    onNavigationConsumed = { pending = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.waitForIdle()

        val page = settled.last()
        assertEquals("settled page", target, page)
        assertEquals(WeekViewUtils.getWeekStart(far, Calendar.SUNDAY), WeekViewUtils.weekPageToStartDate(page, Calendar.SUNDAY))
    }

    @Test
    fun `the Planner pager settles on the picked week 12 years ahead`() {
        val anchor = DayPagerUtils.localDateToDayCode(WeekViewUtils.getWeekStart(today, Calendar.MONDAY))
        val target = plannerPageForDate(anchor, far, Calendar.MONDAY)
        val settled = mutableListOf<Int>()
        rule.setContent {
            var pending by remember { mutableStateOf<Int?>(target) }
            MaterialTheme {
                PlannerView(
                    plannerState = null,
                    anchorDayCode = anchor,
                    pagerPosition = WeekViewUtils.CENTER_WEEK_PAGE,
                    pendingNavigateToPage = pending,
                    firstDayOfWeek = Calendar.MONDAY,
                    todayDayCode = DayPagerUtils.localDateToDayCode(today),
                    nowMs = System.currentTimeMillis(),
                    timePattern = "h:mm a",
                    showEventEmojis = false,
                    zoom = 1f,
                    widthDp = 360,
                    isCompactHeight = false,
                    onPageChanged = { settled += it },
                    onNavigationConsumed = { pending = null },
                    onEmptyDayTap = {},
                    onDayClick = {},
                    onDayHeaderClick = {},
                    onZoomChange = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.waitForIdle()

        val page = settled.last()
        assertEquals("settled page", target, page)
        assertEquals(WeekViewUtils.getWeekStart(far, Calendar.MONDAY), plannerWeekStart(anchor, page, Calendar.MONDAY))
    }
}
