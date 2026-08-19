package org.onekash.kashcal.widget

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Shared type scale for all KashCal widgets.
 *
 * One source of truth so the agenda, week, upcoming, month, and date widgets
 * render a single consistent scale. Sizes are in sp so they honor the system
 * font-scale accessibility setting.
 *
 * The scale lifts every user-facing role to at least the 11sp label floor and
 * sets body content at 14sp, addressing reports that widget text rendered too
 * small to read at a glance (issue #279).
 *
 * Glance's TextStyle supports fontSize/fontWeight but not lineHeight or
 * letterSpacing, so this is a size/weight scale only — weights are applied at
 * each call site (titles Medium, today/selected Bold).
 */
object WidgetTypography {

    /** Widget header title (date range, month/year, widget name). */
    val headerTitle: TextUnit = 16.sp

    /** Primary content — event titles. */
    val contentTitle: TextUnit = 14.sp

    /** Month-grid day-of-month numbers and the single-letter day-of-week header above them (one shared size so the header reads as part of the grid). Slightly larger than body content for at-a-glance legibility, sized to stay within the fixed day-cell height while leaving room below for an event-title row. */
    val monthDayNumber: TextUnit = 14.sp

    /** Supporting text — event times, day headers, counts, empty/overflow rows. */
    val secondary: TextUnit = 12.sp

    /** Smallest label — week-widget day-of-week header, date-widget day name, "today" pill, month-widget week-number gutter. */
    val label: TextUnit = 11.sp

    /** Navigation chevrons (month widget prev/next). Sized as touch affordances, not body text. */
    val navGlyph: TextUnit = 22.sp

    /** Oversized glanceable number — the date widget's day-of-month. */
    val dateNumber: TextUnit = 24.sp
}
