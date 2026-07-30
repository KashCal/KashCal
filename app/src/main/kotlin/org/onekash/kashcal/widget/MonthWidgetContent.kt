package org.onekash.kashcal.widget

import android.content.Context
import android.content.res.Resources
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import org.onekash.kashcal.MainActivity
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.model.MonthGrid
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.Month
import java.util.Locale
import java.time.format.TextStyle as JavaTextStyle

/** Fixed height of a single month-grid day cell, in dp. */
internal const val MONTH_DAY_CELL_HEIGHT_DP = 40

/**
 * Padding around today's day number that forms the solid accent marker, in dp.
 * The marker wraps the number via padding rather than a fixed size, so it grows
 * with the number at large system font-scale instead of clipping it — the number
 * always fits, and the marker reads as a circle at normal scale and a rounded
 * pill when the text is scaled up. Horizontal padding is a touch wider than
 * vertical so a single digit still looks round.
 *
 * Vertical padding is kept minimal on purpose: today's number-block sits in the
 * same fixed-height cell column as the event dots below it, so any extra height
 * here eats into the dots' space and clips them off the bottom of the cell. At
 * ~1dp the marker stays close to a bare number's height, so today shows its dots
 * just like every other day, and the horizontal padding still carries the round
 * shape.
 */
internal const val TODAY_MARKER_HORIZONTAL_PADDING_DP = 6
internal const val TODAY_MARKER_VERTICAL_PADDING_DP = 1

/**
 * Corner radius of today's accent marker, in dp. Larger than half the marker's
 * height at normal scale, so the marker is fully rounded (a circle/capsule); at
 * large font-scale it degrades gracefully to a rounded rectangle rather than
 * clipping the number.
 */
internal const val TODAY_MARKER_CORNER_RADIUS_DP = 12

/**
 * Gap between the month-navigation cluster (title + next arrow) and the "+"
 * button in the month widget header, in dp — keeps a "next month" tap from
 * landing on "add event".
 */
internal const val MONTH_HEADER_ADD_GAP_DP = 12

/** Number of week rows the month grid always renders (fixed 6x7 grid). */
internal const val MONTH_GRID_WEEK_ROWS = 6

/**
 * Width of the optional leading week-number gutter, in dp. Narrower than the in-app grid's 24dp
 * gutter because the widget is space-constrained and a week number is at most two digits; the
 * day-of-week header row reserves the same width so its columns stay aligned with the grid below.
 */
internal const val WEEK_NUMBER_GUTTER_WIDTH_DP = 18

/**
 * The weeks the widget should actually render: [MonthGrid.compute] always returns 6 rows (fixed
 * for the full-size view's paging), but a month usually spans 5 (sometimes 4 or 6). Drop trailing
 * rows that are entirely next-month padding so the widget shows only the weeks the month needs —
 * no stray empty row, less wasted height. Never drops a row containing a day of this month.
 */
internal fun visibleWeeks(grid: org.onekash.kashcal.ui.model.MonthGrid): List<List<org.onekash.kashcal.ui.model.MonthGrid.DayCell>> {
    val weeks = grid.weeks
    var last = weeks.size - 1
    while (last > 0 && weeks[last].all { it.position == org.onekash.kashcal.ui.model.MonthGrid.DayPosition.OutDate }) {
        last--
    }
    return weeks.subList(0, last + 1)
}

/**
 * Format month header text for the widget.
 * Uses abbreviated month name (SHORT style). Includes year only when different from current year.
 *
 * @param year Calendar year of the displayed month
 * @param month0 0-indexed month (January = 0)
 * @param currentYear Current year, injectable for testability
 * @return Formatted header string, e.g. "Apr" or "Sep 2025"
 */
internal fun formatMonthHeader(
    year: Int,
    month0: Int,
    currentYear: Int = LocalDate.now().year
): String {
    val monthName = Month.of(month0 + 1).getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
    return if (year == currentYear) monthName else "$monthName $year"
}

/**
 * Main content composable for the month widget.
 * Shows a 6x7 calendar grid with day numbers and event indicator dots.
 *
 * @param monthGrid The computed 6x7 month grid
 * @param monthEvents Map of day code to events for that day
 * @param monthOffset Current month offset (0 = current month)
 * @param targetYear Year of the displayed month
 * @param targetMonth0 0-indexed month of the displayed month
 * @param firstDayOfWeek java.util.Calendar constant for first day of week
 * @param showWeekNumbers whether to render the leading week-of-year gutter column
 * @param forcedDark the widget's light/dark pin (null = follow system) — used for the static
 *   adjacent-month text color, which lives outside the Glance scheme and can't see a pinned face
 */
@Composable
fun MonthWidgetContent(
    monthGrid: MonthGrid,
    monthEvents: Map<Int, List<WidgetDataRepository.WidgetEvent>>,
    monthOffset: Int,
    targetYear: Int,
    targetMonth0: Int,
    firstDayOfWeek: Int,
    showWeekNumbers: Boolean = false,
    forcedDark: Boolean? = null
) {
    val headerText = formatMonthHeader(targetYear, targetMonth0)
    val todayDayCode = run {
        val today = LocalDate.now()
        today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetTheme.contentBackground)
            .cornerRadius(16.dp)
    ) {
        // Header: nav arrows + month/year + "+"
        MonthWidgetHeader(headerText, monthOffset)

        // Day-of-week headers
        DayOfWeekRow(firstDayOfWeek, showWeekNumbers)

        // Only the weeks this month spans (drops trailing all-next-month padding rows). Each week
        // Row takes equal vertical weight so the rows fill the widget height evenly regardless of
        // how many weeks the month spans — consistent look at any widget size, no dead space.
        val weeks = visibleWeeks(monthGrid)
        val gutterLabels = weekNumberGutterLabels(monthGrid, showWeekNumbers)
        weeks.forEachIndexed { weekIndex, week ->
            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (showWeekNumbers) {
                    WeekNumberGutterCell(gutterLabels[weekIndex])
                }
                week.forEach { cell ->
                    val dayCode = MonthGrid.computeDayCodeForCell(cell, targetYear, targetMonth0)
                    val events = monthEvents[dayCode].orEmpty()
                    val isToday = dayCode == todayDayCode
                    val isPast = dayCode < todayDayCode

                    DayCell(
                        modifier = GlanceModifier.defaultWeight(),
                        cell = cell,
                        dayCode = dayCode,
                        events = events,
                        isToday = isToday,
                        isPast = isPast,
                        forcedDark = forcedDark
                    )
                }
            }
        }
    }
}

/**
 * Month widget header with navigation arrows, month/year title, and "+" button.
 */
@Composable
private fun MonthWidgetHeader(headerText: String, monthOffset: Int) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(WidgetTheme.headerBackground)
            .padding(end = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val prevMonthDesc = LocalContext.current.getString(R.string.cd_previous_month)
        // Back arrow — 48dp minimum touch target
        Box(
            modifier = GlanceModifier
                .size(48.dp)
                .clickable(
                    actionRunCallback<MonthNavPreviousAction>()
                )
                .semantics { contentDescription = prevMonthDesc },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "\u2039",
                style = TextStyle(
                    color = WidgetTheme.onHeaderBackground,
                    fontSize = WidgetTypography.navGlyph,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        // Month/Year title — conditional tap behavior
        val headerAction = if (monthOffset != 0) {
            // Return to current month (stay in widget)
            actionRunCallback<MonthNavResetAction>()
        } else {
            // Open app at today
            actionStartActivity<MainActivity>(
                parameters = actionParametersOf(
                    ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_TODAY
                )
            )
        }
        Row(
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(headerAction),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = headerText,
                style = TextStyle(
                    color = WidgetTheme.onHeaderBackground,
                    fontSize = WidgetTypography.headerTitle,
                    fontWeight = FontWeight.Medium
                )
            )
        }

        val nextMonthDesc = LocalContext.current.getString(R.string.cd_next_month)
        // Forward arrow — 48dp minimum touch target
        Box(
            modifier = GlanceModifier
                .size(48.dp)
                .clickable(
                    actionRunCallback<MonthNavNextAction>()
                )
                .semantics { contentDescription = nextMonthDesc },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "\u203A",
                style = TextStyle(
                    color = WidgetTheme.onHeaderBackground,
                    fontSize = WidgetTypography.navGlyph,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        // Separate the "add" action from the month-navigation cluster so a tap
        // meant for "next month" can't land on "+". The header has ample width.
        Spacer(modifier = GlanceModifier.width(MONTH_HEADER_ADD_GAP_DP.dp))

        // Plain "+" glyph, 48dp touch target — matches the nav arrows' size
        WidgetAddButton()
    }
}

/**
 * Row of single-letter (CLDR NARROW) day-of-week headers, with a leading gutter spacer when
 * [showWeekNumbers] is on so the columns line up with the week-numbered grid below. Each letter
 * carries the full day name as its accessibility label so TalkBack announces "Monday" rather than
 * the ambiguous bare letter.
 */
@Composable
private fun DayOfWeekRow(firstDayOfWeek: Int, showWeekNumbers: Boolean) {
    val headers = getDayOfWeekHeaders(firstDayOfWeek)
    val labels = dayOfWeekAccessibilityLabels(firstDayOfWeek)
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        if (showWeekNumbers) {
            Spacer(modifier = GlanceModifier.width(WEEK_NUMBER_GUTTER_WIDTH_DP.dp))
        }
        headers.forEachIndexed { index, name ->
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .semantics { contentDescription = labels[index] },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name,
                    style = TextStyle(
                        color = WidgetTheme.secondaryText,
                        fontSize = WidgetTypography.monthDayNumber,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
}

/**
 * Leading gutter cell showing a week-of-year number, matching the fixed [WEEK_NUMBER_GUTTER_WIDTH_DP]
 * width reserved in the day-of-week header. Rendered in the same muted secondary text as the
 * day-of-week letters so it reads as a quiet index, not a day.
 */
@Composable
private fun WeekNumberGutterCell(label: String) {
    Box(
        modifier = GlanceModifier
            .width(WEEK_NUMBER_GUTTER_WIDTH_DP.dp)
            .fillMaxHeight(),
        contentAlignment = Alignment.TopCenter
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = WidgetTheme.secondaryText,
                fontSize = WidgetTypography.label,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

/**
 * Single day cell showing day number and up to 3 colored event indicator dots.
 */
@Composable
private fun DayCell(
    modifier: GlanceModifier,
    cell: MonthGrid.DayCell,
    dayCode: Int,
    events: List<WidgetDataRepository.WidgetEvent>,
    isToday: Boolean,
    isPast: Boolean,
    forcedDark: Boolean? = null
) {
    val isAdjacentMonth = cell.position != MonthGrid.DayPosition.MonthDate
    val resources = LocalContext.current.resources
    val accessibilityDesc = buildAccessibilityDescription(resources, dayCode, if (isAdjacentMonth) 0 else events.size)

    // Adjacent-month cells: faded day number, tappable, no dots or today highlight
    if (isAdjacentMonth) {
        Box(
            modifier = modifier
                .fillMaxHeight()
                .clickable(
                    actionStartActivity<MainActivity>(
                        parameters = actionParametersOf(
                            ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_DATE,
                            ActionParameters.Key<Int>(EXTRA_DAY_CODE) to dayCode
                        )
                    )
                )
                .semantics { contentDescription = accessibilityDesc },
            contentAlignment = Alignment.TopCenter
        ) {
            Text(
                text = "${cell.dayOfMonth}",
                style = TextStyle(
                    color = WidgetTheme.adjacentMonthText(forcedDark),
                    fontSize = WidgetTypography.monthDayNumber
                )
            )
        }
        return
    }

    val dotColors = extractDotColors(events)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                actionStartActivity<MainActivity>(
                    parameters = actionParametersOf(
                        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_DATE,
                        ActionParameters.Key<Int>(EXTRA_DAY_CODE) to dayCode
                    )
                )
            )
            .semantics { contentDescription = accessibilityDesc },
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Day number. Today is marked with a solid accent circle around the
            // number (the number flips to the on-accent color) — the Material /
            // Google Calendar "today" treatment; other days show a bare number.
            val textColor = when {
                isToday -> WidgetTheme.onTodayMarker
                isPast -> WidgetTheme.pastEventText
                else -> WidgetTheme.primaryText
            }
            Box(
                modifier = if (isToday) {
                    GlanceModifier
                        .cornerRadius(TODAY_MARKER_CORNER_RADIUS_DP.dp)
                        .background(WidgetTheme.todayMarkerBackground)
                        .padding(
                            horizontal = TODAY_MARKER_HORIZONTAL_PADDING_DP.dp,
                            vertical = TODAY_MARKER_VERTICAL_PADDING_DP.dp
                        )
                } else {
                    GlanceModifier
                },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${cell.dayOfMonth}",
                    style = TextStyle(
                        color = textColor,
                        fontSize = WidgetTypography.monthDayNumber,
                        // Medium (vs Normal) gives the numbers more presence against
                        // the dynamic Material You surface, which renders softer than
                        // a fixed high-contrast palette. Today stays Bold.
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium
                    )
                )
            }

            // Event indicator dots (up to 3)
            if (dotColors.isNotEmpty()) {
                Spacer(modifier = GlanceModifier.height(1.dp))
                Row(horizontalAlignment = Alignment.CenterHorizontally) {
                    dotColors.forEachIndexed { index, color ->
                        if (index > 0) {
                            Spacer(modifier = GlanceModifier.width(2.dp))
                        }
                        Box(
                            modifier = GlanceModifier
                                .size(4.dp)
                                .cornerRadius(2.dp)
                                .background(ColorProvider(day = Color(color), night = Color(color)))
                        ) {}
                    }
                }
            }
        }
    }
}

// ==================== Action Callbacks for Month Navigation ====================

/**
 * Navigate to previous month (decrement offset).
 */
class MonthNavPreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        try {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                val current = prefs[MonthWidgetStateKeys.MONTH_OFFSET] ?: 0
                prefs.toMutablePreferences().apply {
                    this[MonthWidgetStateKeys.MONTH_OFFSET] = current - 1
                }
            }
            MonthWidget().update(context, glanceId)
        } catch (e: Exception) {
            Log.e(TAG, "MonthNavPreviousAction failed", e)
        }
    }
}

/**
 * Navigate to next month (increment offset).
 */
class MonthNavNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        try {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                val current = prefs[MonthWidgetStateKeys.MONTH_OFFSET] ?: 0
                prefs.toMutablePreferences().apply {
                    this[MonthWidgetStateKeys.MONTH_OFFSET] = current + 1
                }
            }
            MonthWidget().update(context, glanceId)
        } catch (e: Exception) {
            Log.e(TAG, "MonthNavNextAction failed", e)
        }
    }
}

/**
 * Reset to current month (offset = 0). Used when tapping header while navigated away.
 */
class MonthNavResetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        try {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                prefs.toMutablePreferences().apply {
                    this[MonthWidgetStateKeys.MONTH_OFFSET] = 0
                }
            }
            MonthWidget().update(context, glanceId)
        } catch (e: Exception) {
            Log.e(TAG, "MonthNavResetAction failed", e)
        }
    }
}

private const val TAG = "MonthWidgetNav"

// ==================== Pure Helper Functions (Tested) ====================

/**
 * Extract unique calendar colors from events, capped at [maxDots].
 * Preserves order of first appearance.
 */
internal fun extractDotColors(
    events: List<WidgetDataRepository.WidgetEvent>,
    maxDots: Int = 3
): List<Int> {
    return events
        .map { it.calendarColor }
        .distinct()
        .take(maxDots)
}

/**
 * Get localized single-letter (CLDR NARROW) day-of-week headers starting from [firstDayOfWeek].
 *
 * NARROW gives one letter per day (e.g. English "S M T W T F S"), sized to match the day-of-month
 * numbers in the grid below — the Material / Google Calendar month-grid treatment. The repeats
 * (Sun/Sat both "S", Tue/Thu both "T") are disambiguated for sighted users by column position and
 * for screen-reader users by [dayOfWeekAccessibilityLabels], which supplies the full day name.
 *
 * Ordering comes from [DateTimeUtils.getOrderedDaysOfWeek] — the same helper the grid rows below
 * use — so the header columns can never drift from the grid's day ordering.
 *
 * @param firstDayOfWeek java.util.Calendar constant (1=Sun, 2=Mon, ..., 7=Sat) or 0=system default
 * @return List of 7 single-letter day names
 */
internal fun getDayOfWeekHeaders(firstDayOfWeek: Int): List<String> {
    val locale = Locale.getDefault()
    return DateTimeUtils.getOrderedDaysOfWeek(firstDayOfWeek).map { it.getDisplayName(JavaTextStyle.NARROW, locale) }
}

/**
 * Full localized day names (e.g. "Sunday") in the same order as [getDayOfWeekHeaders], used as the
 * accessibility label for each single-letter header so TalkBack announces the day rather than a
 * bare, ambiguous letter.
 *
 * @param firstDayOfWeek java.util.Calendar constant (1=Sun, 2=Mon, ..., 7=Sat) or 0=system default
 * @return List of 7 full day names
 */
internal fun dayOfWeekAccessibilityLabels(firstDayOfWeek: Int): List<String> {
    val locale = Locale.getDefault()
    return DateTimeUtils.getOrderedDaysOfWeek(firstDayOfWeek).map { it.getDisplayName(JavaTextStyle.FULL, locale) }
}

/**
 * Week-of-year labels for the gutter column, one per rendered week, or empty when the
 * "show week numbers" setting is off.
 *
 * Mirrors the in-app month grid's optional leading week-number column. Labels come from each
 * [visibleWeeks] row's first cell so they never include a trailing all-next-month padding row,
 * and the number is the locale-aware [MonthGrid.DayCell.weekNumber] the grid already computed.
 */
internal fun weekNumberGutterLabels(grid: MonthGrid, showWeekNumbers: Boolean): List<String> {
    if (!showWeekNumbers) return emptyList()
    return visibleWeeks(grid).map { it.first().weekNumber.toString() }
}

/**
 * Build accessibility description for a day cell using a dayCode.
 * Extracts year/month from the dayCode so adjacent-month cells get the correct month name.
 * Format: "March 15, 2 events" or "March 15, no events"
 *
 * @param resources Android resources for localized strings
 * @param dayCode YYYYMMDD format day code
 * @param eventCount Number of events on this day
 */
internal fun buildAccessibilityDescription(
    resources: Resources,
    dayCode: Int,
    eventCount: Int
): String {
    val year = dayCode / 10000
    val month1 = (dayCode / 100) % 100
    val day = dayCode % 100
    return buildAccessibilityDescription(resources, year, month1 - 1, day, eventCount)
}

/**
 * Build accessibility description for a day cell.
 * Format: "March 15, 2 events" or "March 15, no events"
 *
 * @param resources Android resources for localized strings
 * @param year Calendar year
 * @param month0 0-indexed month (January = 0)
 * @param dayOfMonth Day of month (1-31)
 * @param eventCount Number of events on this day
 */
internal fun buildAccessibilityDescription(
    resources: Resources,
    year: Int,
    month0: Int,
    dayOfMonth: Int,
    eventCount: Int
): String {
    val monthName = Month.of(month0 + 1).getDisplayName(JavaTextStyle.FULL, Locale.getDefault())
    val eventText = if (eventCount == 0) {
        resources.getString(R.string.cd_widget_no_events)
    } else {
        resources.getQuantityString(R.plurals.widget_event_count_plural, eventCount, eventCount)
    }
    return resources.getString(R.string.cd_widget_day_cell, "$monthName $dayOfMonth", eventText)
}
