package org.onekash.kashcal.widget

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Sets the type scale of the agenda, week, upcoming, month and date widgets; the single source of
 * truth for widget text sizes. Sizes are in sp, so they follow the system font scale.
 *
 * Every user-facing role is at least the 11sp label floor and body content is 14sp, so text
 * reads at a glance (#279).
 *
 * Glance's TextStyle has no lineHeight or letterSpacing, so this is a size scale only; call sites
 * set the weights (titles Medium, today or selected Bold).
 */
object WidgetTypography {

    /** Widget header title (date range, month/year, widget name). */
    val headerTitle: TextUnit = 16.sp

    /**
     * Primary content, for example event titles, the week and upcoming day headers, and the agenda
     * and upcoming empty states.
     */
    val contentTitle: TextUnit = 14.sp

    /**
     * Month-grid day numbers and the day-of-week letters above them, one size so the letters read
     * as part of the grid. Sized to fit the fixed day-cell height with room below for an
     * event-title row.
     */
    val monthDayNumber: TextUnit = 14.sp

    /**
     * Supporting text: event times, empty and overflow rows, Upcoming's footer and the date card's
     * weekday.
     */
    val secondary: TextUnit = 12.sp

    /**
     * Smallest label, for example month-grid event titles and week numbers, day-header event
     * counts, the week widget's "today" tag and the date widget's short day name.
     */
    val label: TextUnit = 11.sp

    /** Month widget prev/next chevrons, sized as touch affordances, not body text. */
    val navGlyph: TextUnit = 22.sp

    /** The date widget's oversized day-of-month number. */
    val dateNumber: TextUnit = 24.sp
}
