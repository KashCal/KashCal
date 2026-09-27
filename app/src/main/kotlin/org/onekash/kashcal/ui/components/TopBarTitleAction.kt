package org.onekash.kashcal.ui.components

import org.onekash.kashcal.ui.viewmodels.ViewMode

/**
 * Maps the current [ViewMode] to what tapping the top-app-bar title does.
 *
 * AGENDA and DAY toggle their inline week bar, the other time-grid views open the modal date
 * picker, and every other view takes the month-header action, which toggles the year overlay.
 * A pure mapping so `TopBarTitleActionTest` can stop a DAY tap from silently regressing into
 * opening the date picker.
 */
enum class TopBarTitleAction {
    TOGGLE_AGENDA_WEEK_BAR,
    TOGGLE_DAY_WEEK_BAR,
    OPEN_DATE_PICKER,
    MONTH_HEADER;

    companion object {
        fun forViewMode(viewMode: ViewMode): TopBarTitleAction = when {
            viewMode == ViewMode.AGENDA -> TOGGLE_AGENDA_WEEK_BAR
            viewMode == ViewMode.DAY -> TOGGLE_DAY_WEEK_BAR
            viewMode.isTimeGrid -> OPEN_DATE_PICKER
            else -> MONTH_HEADER
        }
    }
}
