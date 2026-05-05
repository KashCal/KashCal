package org.onekash.kashcal.widget

import android.content.Context
import android.content.res.Resources
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import java.time.LocalDate
import java.time.Month
import java.util.Calendar
import java.util.Locale
import java.time.format.TextStyle as JavaTextStyle

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
 */
@Composable
fun MonthWidgetContent(
    monthGrid: MonthGrid,
    monthEvents: Map<Int, List<WidgetDataRepository.WidgetEvent>>,
    monthOffset: Int,
    targetYear: Int,
    targetMonth0: Int,
    firstDayOfWeek: Int
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
        DayOfWeekRow(firstDayOfWeek)

        // 6 rows of day cells
        monthGrid.weeks.forEach { week ->
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
                        isPast = isPast
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
                    color = WidgetTheme.accentColor,
                    fontSize = 22.sp,
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
                    color = WidgetTheme.accentColor,
                    fontSize = 15.sp,
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
                    color = WidgetTheme.accentColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        // "+" button — 48dp touch target
        Box(
            modifier = GlanceModifier
                .size(48.dp)
                .clickable(
                    actionStartActivity<MainActivity>(
                        parameters = actionParametersOf(
                            ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_CREATE_EVENT
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_add),
                contentDescription = LocalContext.current.getString(R.string.cd_widget_add_event),
                modifier = GlanceModifier.size(18.dp)
            )
        }
    }
}

/**
 * Row of abbreviated day-of-week headers.
 */
@Composable
private fun DayOfWeekRow(firstDayOfWeek: Int) {
    val headers = getDayOfWeekHeaders(firstDayOfWeek)
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        headers.forEach { name ->
            Box(
                modifier = GlanceModifier.defaultWeight(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name,
                    style = TextStyle(
                        color = WidgetTheme.secondaryText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
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
    isPast: Boolean
) {
    val isAdjacentMonth = cell.position != MonthGrid.DayPosition.MonthDate
    val resources = LocalContext.current.resources
    val accessibilityDesc = buildAccessibilityDescription(resources, dayCode, if (isAdjacentMonth) 0 else events.size)

    // Adjacent-month cells: faded day number, tappable, no dots or today highlight
    if (isAdjacentMonth) {
        Box(
            modifier = modifier
                .height(36.dp)
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
                    color = WidgetTheme.adjacentMonthText,
                    fontSize = 12.sp
                )
            )
        }
        return
    }

    val dotColors = extractDotColors(events)

    val bgModifier = if (isToday) {
        GlanceModifier.background(WidgetTheme.todayHighlightBackground).cornerRadius(6.dp)
    } else {
        GlanceModifier
    }

    Box(
        modifier = modifier
            .height(36.dp)
            .then(bgModifier)
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
            // Day number
            val textColor = when {
                isToday -> WidgetTheme.accentColor
                isPast -> WidgetTheme.pastEventText
                else -> WidgetTheme.primaryText
            }
            Text(
                text = "${cell.dayOfMonth}",
                style = TextStyle(
                    color = textColor,
                    fontSize = 12.sp,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                )
            )

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

// ==================== Action Callbacks for US4 Month Navigation ====================

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
 * Get localized abbreviated day-of-week headers starting from [firstDayOfWeek].
 *
 * @param firstDayOfWeek java.util.Calendar constant (1=Sun, 2=Mon, ..., 7=Sat) or 0=system default
 * @return List of 7 abbreviated day names
 */
internal fun getDayOfWeekHeaders(firstDayOfWeek: Int): List<String> {
    val resolvedFirst = if (firstDayOfWeek == 0) {
        Calendar.getInstance().firstDayOfWeek
    } else {
        firstDayOfWeek
    }

    val locale = Locale.getDefault()
    return (0 until 7).map { offset ->
        val calDay = ((resolvedFirst - 1 + offset) % 7) + 1 // Calendar days are 1-7
        val javaDow = java.time.DayOfWeek.of(if (calDay == 1) 7 else calDay - 1) // Calendar.SUNDAY=1 → DayOfWeek.SUNDAY=7
        javaDow.getDisplayName(JavaTextStyle.SHORT, locale)
    }
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
