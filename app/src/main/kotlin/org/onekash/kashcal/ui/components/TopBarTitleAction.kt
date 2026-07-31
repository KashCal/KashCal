package org.onekash.kashcal.ui.components

import org.onekash.kashcal.ui.viewmodels.ViewMode

/**
 * What tapping the top-app-bar title does, resolved from the current [ViewMode].
 *
 * Kept as a pure mapping (not inlined in the composable) because the title tap
 * is a genuine behavioral branch: AGENDA and DAY toggle their inline week bar,
 * the other time-grid views open the modal date picker, and month-family views
 * jump to the month header. Extracting it keeps the routing unit-testable so a
 * DAY tap can't silently regress into opening the date picker.
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
