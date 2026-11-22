package org.onekash.kashcal.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import java.time.LocalDate
import java.util.Calendar

/** Tests [plannerShownWeekStart]: the week the Planner shows for an anchor and a page. */
class PlannerWeekPagingTest {

    private val day = LocalDate.of(2026, 10, 15)

    @Test
    fun `the shown week counts pages from the anchor`() {
        // What Back after a day-header tap or Jump to date restores, and what the title shows.
        assertEquals(
            LocalDate.of(2026, 10, 26),
            plannerShownWeekStart(20261012, WeekViewUtils.CENTER_WEEK_PAGE + 2, Calendar.MONDAY),
        )
    }

    @Test
    fun `without an anchor the shown week is this week`() {
        assertEquals(
            LocalDate.of(2026, 10, 11),
            plannerShownWeekStart(null, WeekViewUtils.CENTER_WEEK_PAGE, Calendar.SUNDAY, today = day),
        )
    }
}
