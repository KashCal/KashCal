package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.ui.viewmodels.ViewMode

/**
 * Unit tests for the top-bar title-tap action dispatch.
 *
 * The title tap means different things per view: AGENDA and DAY toggle their
 * inline week bar, the remaining time-grid views (THREE_DAYS, WEEK) open the
 * modal date picker, and month-family views jump to the month header. This is a
 * behavioral branch (a silent regression would route DAY to the date picker
 * instead of the toggle), so it is resolved by a pure function and tested here.
 */
class TopBarTitleActionTest {

    @Test
    fun `AGENDA toggles the agenda week bar`() {
        assertEquals(TopBarTitleAction.TOGGLE_AGENDA_WEEK_BAR, TopBarTitleAction.forViewMode(ViewMode.AGENDA))
    }

    @Test
    fun `DAY toggles the day week bar`() {
        assertEquals(TopBarTitleAction.TOGGLE_DAY_WEEK_BAR, TopBarTitleAction.forViewMode(ViewMode.DAY))
    }

    @Test
    fun `THREE_DAYS opens the date picker`() {
        assertEquals(TopBarTitleAction.OPEN_DATE_PICKER, TopBarTitleAction.forViewMode(ViewMode.THREE_DAYS))
    }

    @Test
    fun `WEEK opens the date picker`() {
        assertEquals(TopBarTitleAction.OPEN_DATE_PICKER, TopBarTitleAction.forViewMode(ViewMode.WEEK))
    }

    @Test
    fun `DAY does not open the date picker (regression guard)`() {
        // DAY is a time-grid view; without the explicit DAY branch it would fall
        // through to OPEN_DATE_PICKER. Guard that it routes to the toggle instead.
        val action = TopBarTitleAction.forViewMode(ViewMode.DAY)
        assertEquals(TopBarTitleAction.TOGGLE_DAY_WEEK_BAR, action)
    }

    @Test
    fun `month-family views jump to the month header`() {
        assertEquals(TopBarTitleAction.MONTH_HEADER, TopBarTitleAction.forViewMode(ViewMode.MONTH))
        assertEquals(TopBarTitleAction.MONTH_HEADER, TopBarTitleAction.forViewMode(ViewMode.MONTH_FULL))
    }
}
