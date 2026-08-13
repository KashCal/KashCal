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
import androidx.glance.LocalSize
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
import org.onekash.kashcal.ui.shared.contrastForegroundOn
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

// ==================== Event-title rows (optional month day-cell style) ====================

/**
 * Vertical space the month-widget header occupies, in dp. The nav-arrow / "+" boxes are
 * 48dp touch targets, but the header's visual row is shorter; for sizing event-title rows
 * we budget the visual row, not the touch target, so the grid doesn't under-fill.
 */
internal const val MONTH_HEADER_HEIGHT_DP = 40

/**
 * Vertical space the day-of-week letter row occupies, in dp: [WidgetTypography.monthDayNumber]
 * (16sp ≈ 21dp at font-scale 1.0) plus the row's vertical padding.
 */
internal const val MONTH_DOW_ROW_HEIGHT_DP = 23

/**
 * Vertical space the day-number block reserves at the top of a day cell, in dp — the 16sp
 * number plus the today marker's vertical padding.
 */
internal const val DAY_NUMBER_BLOCK_HEIGHT_DP = 18

/** Height of one all-day event chip in a day cell, in dp (11sp label + breathing room). */
internal const val EVENT_CHIP_HEIGHT_DP = 13

/**
 * Height of one event title row in a day cell, in dp. Sized to the chip, not to a timed
 * row's theoretical line height: at 11sp the single-line title fits a 14dp slot, and the
 * 3dp timed stripe centers within it. Budgeting 16dp here cost the standard-size widget
 * its second title row (304dp height → 46dp cell → only one row), hiding "+n" markers.
 */
internal const val TIMED_TITLE_ROW_HEIGHT_DP = 13

/** Vertical gap between two event rows in a day cell, in dp. */
internal const val EVENT_ROW_GAP_DP = 1

/**
 * Hard cap on event slot rows per week in titles mode. The row budget itself comes from
 * the actual cell height (grow the widget, get more rows); this cap only guards the
 * RemoteViews transaction size — every slot row costs views in every day column, and an
 * oversized widget bundle is dropped by the launcher, freezing the widget frame.
 */
internal const val MAX_EVENT_ROWS = 6

/** Tint alpha for a timed multi-day span bar's background (in-app TimedSpan style). */
internal const val TIMED_SPAN_TINT_ALPHA = 0.18f

/**
 * Tint alpha for an all-day FREE event chip's background, mirroring the in-app month view's
 * AllDayFree style (same hue as the event, quiet enough to read as "not busy").
 */
internal const val ALL_DAY_FREE_TINT_ALPHA = 0.15f

/** Width of the color stripe leading a timed-event title row, in dp (in-app Stripe style). */
internal const val TIMED_STRIPE_WIDTH_DP = 3

/** Corner radius of all-day event chips and the timed stripe, in dp. */
internal const val EVENT_CHIP_CORNER_RADIUS_DP = 3

/**
 * Horizontal chrome around the title text inside a day cell, in dp: chip padding (3dp) or
 * stripe + gap (3+2dp) on the left, ~3dp on the right. Subtracted from the cell width before
 * estimating how many title characters fit.
 */
internal const val EVENT_ROW_TEXT_CHROME_DP = 8

/**
 * Estimated average advance width per character at [WidgetTypography.label] (11sp) and
 * font-scale 1.0, in dp. Used to pre-truncate titles with an ellipsis because Glance's Text
 * clips overflow mid-glyph instead of ellipsizing. Slightly generous on purpose: a touch too
 * short beats a clipped glyph.
 */
internal const val TITLE_CHAR_WIDTH_DP = 6

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
 * @param showEventTitles whether day cells render event title rows (mirroring the in-app
 *   month view) instead of the colored indicator dots
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
    showEventTitles: Boolean = false,
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

        // Titles mode sizes its rows from the ACTUAL widget size (SizeMode.Exact) because the
        // week rows stretch via defaultWeight: how many title rows a cell can show and how many
        // title characters fit depend on the stretched cell height/width, which only LocalSize
        // knows. Dots mode needs none of this — a dot row fits any cell that fits the number.
        val widgetSize = LocalSize.current
        val cellHeightDp = (widgetSize.height.value - MONTH_HEADER_HEIGHT_DP - MONTH_DOW_ROW_HEIGHT_DP) / weeks.size
        val cellWidthDp = (widgetSize.width.value - gridHorizontalPaddingDp(showWeekNumbers)) / 7f
        val eventRowCount = if (showEventTitles) maxEventRows(cellHeightDp) else 0
        val titleChars = if (showEventTitles) maxTitleChars(cellWidthDp) else 0

        weeks.forEachIndexed { weekIndex, week ->
            if (showEventTitles && eventRowCount > 0) {
                // Titles mode: week slot layout — multi-day events span their columns as
                // continuous bars, single-day events fill the remaining per-cell slots.
                val weekDayCodes = week.map { MonthGrid.computeDayCodeForCell(it, targetYear, targetMonth0) }
                // Sanity: a week row is always 7 strictly-increasing day codes. If that
                // ever breaks (grid/cell mismatch), fall back to dots rather than render
                // bars anchored on wrong columns.
                check(weekDayCodes.size == 7 && weekDayCodes.zipWithNext().all { (a, b) -> b > a }) {
                    "week day codes not strictly increasing: $weekDayCodes"
                }
                val weekRender = computeMonthWidgetWeekRender(weekDayCodes, monthEvents, eventRowCount)
                TitlesWeekRow(
                    // The weight comes from the caller: defaultWeight() only resolves inside
                    // the parent Column's scope, not at this function's own top level.
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    week = week,
                    weekDayCodes = weekDayCodes,
                    weekRender = weekRender,
                    todayDayCode = todayDayCode,
                    monthEvents = monthEvents,
                    cellWidthDp = cellWidthDp,
                    maxTitleChars = titleChars,
                    gutterLabel = if (showWeekNumbers) gutterLabels[weekIndex] else null,
                    forcedDark = forcedDark
                )
            } else {
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
}

/**
 * One week in titles mode: a day-number row (same treatment as the dots-mode cells, minus
 * the dots), then the week's slot rows — spanning bars for multi-day events, snippets for
 * single-day events, "+n" overflow markers where a cell's events did not fit.
 */
@Composable
private fun TitlesWeekRow(
    modifier: GlanceModifier,
    week: List<MonthGrid.DayCell>,
    weekDayCodes: List<Int>,
    weekRender: MonthWidgetWeekRender,
    todayDayCode: Int,
    monthEvents: Map<Int, List<WidgetDataRepository.WidgetEvent>>,
    cellWidthDp: Float,
    maxTitleChars: Int,
    gutterLabel: String?,
    forcedDark: Boolean?
) {
    Row(modifier = modifier) {
        if (gutterLabel != null) {
            WeekNumberGutterCell(gutterLabel)
        }
        Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
            // Day-number row, identical in look to the dots-mode cells.
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                week.forEachIndexed { col, cell ->
                    val dayCode = weekDayCodes[col]
                    DayNumberCell(
                        modifier = GlanceModifier.defaultWeight(),
                        cell = cell,
                        dayCode = dayCode,
                        isToday = dayCode == todayDayCode,
                        isPast = dayCode < todayDayCode,
                        eventCount = monthEvents[dayCode].orEmpty().size,
                        forcedDark = forcedDark
                    )
                }
            }
            // Slot rows (bars + snippets + overflow). Only as many rows as fit the cell
            // height were computed, so nothing clips off the week row's bottom.
            weekRender.slots.forEach { slotRow ->
                SlotRow(
                    slotRow = slotRow,
                    weekDayCodes = weekDayCodes,
                    cellWidthDp = cellWidthDp,
                    maxTitleChars = maxTitleChars
                )
            }
        }
    }
}

/**
 * The day-number cell of a titles-mode week row: centered number with the today marker and
 * past/adjacent-month dimming, tappable to open the day — the dots-mode cell minus the dots.
 */
@Composable
private fun DayNumberCell(
    modifier: GlanceModifier,
    cell: MonthGrid.DayCell,
    dayCode: Int,
    isToday: Boolean,
    isPast: Boolean,
    eventCount: Int,
    forcedDark: Boolean?
) {
    val isAdjacentMonth = cell.position != MonthGrid.DayPosition.MonthDate
    val accessibilityDesc = buildAccessibilityDescription(
        LocalContext.current.resources, dayCode, if (isAdjacentMonth) 0 else eventCount
    )
    val textColor = when {
        isAdjacentMonth -> WidgetTheme.adjacentMonthText(forcedDark)
        isToday -> WidgetTheme.onTodayMarker
        isPast -> WidgetTheme.pastEventText
        else -> WidgetTheme.primaryText
    }
    Box(
        modifier = modifier
            .clickable(dayClickAction(dayCode))
            .semantics { contentDescription = accessibilityDesc },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = if (isToday && !isAdjacentMonth) {
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
                    fontWeight = if (isToday && !isAdjacentMonth) FontWeight.Bold else FontWeight.Medium
                )
            )
        }
    }
}

/** Tap action shared by every day-cell surface: open the app at that day. */
private fun dayClickAction(dayCode: Int) = actionStartActivity<MainActivity>(
    parameters = actionParametersOf(
        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_DATE,
        ActionParameters.Key<Int>(EXTRA_DAY_CODE) to dayCode
    )
)

/**
 * One slot row of a titles-mode week: consecutive [MonthWidgetSlot.BarSegment]s of the same
 * span merge into one continuous bar across their columns; single-day snippets, overflow
 * markers, and empty cells take one column each.
 */
@Composable
private fun SlotRow(
    slotRow: List<MonthWidgetSlot>,
    weekDayCodes: List<Int>,
    cellWidthDp: Float,
    maxTitleChars: Int
) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = EVENT_ROW_GAP_DP.dp)) {
        var col = 0
        while (col < 7) {
            // The last item in the row stretches to fill whatever rounding the fixed cell
            // widths left over, so the 7 columns always span the full grid width exactly.
            val isLastItem = run {
                var next = col + 1
                if (slotRow[col] is MonthWidgetSlot.BarSegment) {
                    val span = (slotRow[col] as MonthWidgetSlot.BarSegment).span
                    while (next < 7 && (slotRow[next] as? MonthWidgetSlot.BarSegment)?.span == span) next++
                }
                next >= 7
            }
            val cellModifier = if (isLastItem) {
                GlanceModifier.defaultWeight()
            } else {
                GlanceModifier.width(cellWidthDp.dp)
            }
            when (val content = slotRow[col]) {
                is MonthWidgetSlot.BarSegment -> {
                    // Merge the whole run of this span's segments into one bar.
                    var endCol = col
                    while (endCol + 1 < 7 &&
                        (slotRow[endCol + 1] as? MonthWidgetSlot.BarSegment)?.span == content.span
                    ) {
                        endCol++
                    }
                    val width = endCol - col + 1
                    SpanBar(
                        span = content.span,
                        width = width,
                        maxTitleChars = maxTitleChars,
                        dayCode = weekDayCodes[col],
                        // Fixed width, not defaultWeight(): Glance's defaultWeight() is always
                        // weight(1f) — there is no weight(n) — so a weighted bar collapses to a
                        // single column no matter how many days it spans. The widget knows the
                        // real cell width from LocalSize, so the bar takes width × cellWidth.
                        modifier = if (isLastItem) cellModifier else GlanceModifier.width((cellWidthDp * width).dp)
                    )
                    col = endCol + 1
                }
                is MonthWidgetSlot.CellEvent -> {
                    Box(modifier = cellModifier) {
                        EventTitleRow(content.event, maxTitleChars)
                    }
                    col++
                }
                is MonthWidgetSlot.Overflow -> {
                    Box(modifier = cellModifier) {
                        Text(
                            text = LocalContext.current.getString(R.string.status_more_events, content.count),
                            style = TextStyle(
                                color = WidgetTheme.secondaryText,
                                fontSize = WidgetTypography.label
                            ),
                            maxLines = 1,
                            modifier = GlanceModifier.padding(start = 3.dp)
                        )
                    }
                    col++
                }
                MonthWidgetSlot.Empty -> {
                    // Transparent tap surface so empty parts of a day column still open the day.
                    Box(
                        modifier = cellModifier
                            .clickable(dayClickAction(weekDayCodes[col]))
                    ) {}
                    col++
                }
            }
        }
    }
}

/**
 * A multi-day event's continuous bar across [width] day columns, styled like the in-app
 * month view's spans: all-day busy = solid fill with contrasting title, all-day free =
 * quiet tint with colored title, timed multi-day = tint with a leading color stripe.
 * The title shows only at the bar's first segment of this week row; continuation segments
 * (flush edges) keep the bar bare, like the app's flush span caps.
 */
@Composable
private fun SpanBar(
    span: MonthWidgetSpan,
    width: Int,
    maxTitleChars: Int,
    dayCode: Int,
    modifier: GlanceModifier
) {
    val event = span.event
    val color = Color(event.calendarColor)
    // The title only fits across the bar's full span, so it earns roughly `width` times the
    // per-cell character budget (minus the chrome the single-cell rows already deduct).
    val spanChars = (maxTitleChars * width).coerceAtLeast(maxTitleChars)
    val title = ellipsizeTitle(event.title, spanChars)

    // Corner radius per edge: flush (continuing) edges stay square so the bar reads as one
    // unbroken band across week boundaries; capped edges round off.
    val capRadius = EVENT_CHIP_CORNER_RADIUS_DP.dp

    val isTimed = !event.isAllDay
    val fill = when {
        isTimed -> color.copy(alpha = TIMED_SPAN_TINT_ALPHA)
        event.isFree -> color.copy(alpha = ALL_DAY_FREE_TINT_ALPHA)
        else -> color
    }
    // Timed spans tint the surface only slightly, so the title keeps the cell's text color
    // (like the in-app TimedSpan); all-day chips carry their own contrast logic.
    val textProvider = when {
        isTimed -> WidgetTheme.primaryText
        event.isFree -> ColorProvider(day = color, night = color)
        else -> ColorProvider(day = contrastForegroundOn(color), night = contrastForegroundOn(color))
    }

    // Corner radii per edge via two nested boxes is not possible in Glance — the modifier
    // applies one radius to all corners. The bar therefore uses the full radius when both
    // ends cap here, and none while either edge continues into an adjacent week.
    val radiusModifier = if (!span.leftFlush && !span.rightFlush) {
        GlanceModifier.cornerRadius(capRadius)
    } else {
        GlanceModifier
    }

    Row(
        modifier = modifier
            .height(EVENT_CHIP_HEIGHT_DP.dp)
            .then(radiusModifier)
            .background(ColorProvider(day = fill, night = fill))
            .clickable(dayClickAction(dayCode)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isTimed && !span.leftFlush) {
            Box(
                modifier = GlanceModifier
                    .width(TIMED_STRIPE_WIDTH_DP.dp)
                    .fillMaxHeight()
                    .background(ColorProvider(day = color, night = color))
            ) {}
        }
        if (!span.leftFlush) {
            Text(
                text = title,
                style = TextStyle(
                    color = textProvider,
                    fontSize = WidgetTypography.label
                ),
                maxLines = 1,
                modifier = GlanceModifier.padding(horizontal = 3.dp)
            )
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
 * Single day cell in dots mode: centered day number (with today marker) plus up to 3
 * colored event indicator dots. Titles mode renders week-based slot rows instead — see
 * [TitlesWeekRow] — and never reaches this composable.
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

    val dotColors = extractDotColors(events)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(dayClickAction(dayCode))
            .semantics { contentDescription = accessibilityDesc },
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = GlanceModifier.fillMaxWidth()
        ) {
            // Day number, centered. Today is marked with a solid accent circle around the
            // number (the number flips to the on-accent color) — the Material / Google
            // Calendar "today" treatment; other days show a bare number.
            val textColor = when {
                isAdjacentMonth -> WidgetTheme.adjacentMonthText(forcedDark)
                isToday -> WidgetTheme.onTodayMarker
                isPast -> WidgetTheme.pastEventText
                else -> WidgetTheme.primaryText
            }
            Box(
                modifier = GlanceModifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = if (isToday && !isAdjacentMonth) {
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
                            fontWeight = if (isToday && !isAdjacentMonth) FontWeight.Bold else FontWeight.Medium
                        )
                    )
                }
            }

            // Adjacent-month cells stay bare — a faded number only.
            if (!isAdjacentMonth && dotColors.isNotEmpty()) {
                // Event indicator dots (up to 3)
                Spacer(modifier = GlanceModifier.height(1.dp))
                Row(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = GlanceModifier.fillMaxWidth()
                ) {
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

/**
 * One event row inside a day cell in titles mode, mirroring the in-app month view's
 * snippet styles ([org.onekash.kashcal.ui.screens.monthfull.snippetStyleFor]):
 * - timed event: leading 3dp color stripe + title in the cell's text color
 * - all-day busy: filled chip in the event color + WCAG-contrasting title
 * - all-day free: quiet tinted chip + title in the event color
 */
@Composable
private fun EventTitleRow(
    event: WidgetDataRepository.WidgetEvent,
    maxTitleChars: Int
) {
    val color = Color(event.calendarColor)
    val title = ellipsizeTitle(event.title, maxTitleChars)
    if (!event.isAllDay) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = GlanceModifier.fillMaxWidth().height(TIMED_TITLE_ROW_HEIGHT_DP.dp)
        ) {
            Box(
                modifier = GlanceModifier
                    .width(TIMED_STRIPE_WIDTH_DP.dp)
                    .height(EVENT_CHIP_HEIGHT_DP.dp)
                    .cornerRadius(1.dp)
                    .background(ColorProvider(day = color, night = color))
            ) {}
            Spacer(modifier = GlanceModifier.width(2.dp))
            Text(
                text = title,
                style = TextStyle(
                    color = WidgetTheme.primaryText,
                    fontSize = WidgetTypography.label
                ),
                maxLines = 1
            )
        }
    } else {
        val fill = if (event.isFree) color.copy(alpha = ALL_DAY_FREE_TINT_ALPHA) else color
        val textColor = if (event.isFree) color else contrastForegroundOn(color)
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(EVENT_CHIP_HEIGHT_DP.dp)
                .cornerRadius(EVENT_CHIP_CORNER_RADIUS_DP.dp)
                .background(ColorProvider(day = fill, night = fill)),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = title,
                style = TextStyle(
                    color = ColorProvider(day = textColor, night = textColor),
                    fontSize = WidgetTypography.label
                ),
                maxLines = 1,
                modifier = GlanceModifier.padding(horizontal = 3.dp)
            )
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
 * Horizontal padding the month grid keeps on each side (the day-of-week header row pads by
 * 2dp on both ends; the week-number gutter takes its fixed width off the grid). Subtracted
 * from the widget width before dividing into the 7 day columns.
 */
internal fun gridHorizontalPaddingDp(showWeekNumbers: Boolean): Int =
    (if (showWeekNumbers) WEEK_NUMBER_GUTTER_WIDTH_DP else 0) + 4

/**
 * How many event slot rows fit a week row of [cellHeightDp] below the day-number block —
 * the budget the titles mode fills (grow the widget, get more rows), capped only by
 * [MAX_EVENT_ROWS]. A row costs [TIMED_TITLE_ROW_HEIGHT_DP] plus one [EVENT_ROW_GAP_DP] per
 * stacked row. Returns 0 when not even one row fits — the caller then falls back to dots.
 */
internal fun maxEventRows(cellHeightDp: Float): Int {
    // A day cell holds the day-number block plus as many event rows as fit. Each row costs
    // its height; rows after the first also pay the inter-row gap. The standard-size widget
    // (304dp, 5 weeks → ~48dp cell) must reach 2 rows so a "+n" marker can share a cell
    // with one visible title instead of going blank.
    val usable = cellHeightDp - DAY_NUMBER_BLOCK_HEIGHT_DP
    if (usable < TIMED_TITLE_ROW_HEIGHT_DP) return 0
    val rows = 1 + ((usable - TIMED_TITLE_ROW_HEIGHT_DP) / (TIMED_TITLE_ROW_HEIGHT_DP + EVENT_ROW_GAP_DP)).toInt()
    return rows.coerceAtMost(MAX_EVENT_ROWS)
}

/**
 * How many title characters fit a day cell of [cellWidthDp] after the row chrome (stripe or
 * chip padding), at [TITLE_CHAR_WIDTH_DP] per character. Minimum 4 so an ellipsis still
 * leaves something readable.
 */
internal fun maxTitleChars(cellWidthDp: Float): Int =
    ((cellWidthDp - EVENT_ROW_TEXT_CHROME_DP) / TITLE_CHAR_WIDTH_DP)
        .toInt()
        .coerceAtLeast(4)

/**
 * Truncate [title] to [maxChars] with a trailing ellipsis. Glance's Text clips overflow
 * mid-glyph rather than ellipsizing, so titles are pre-shortened; [maxTitleChars] estimates
 * how much actually fits the cell.
 */
internal fun ellipsizeTitle(title: String, maxChars: Int): String {
    if (maxChars <= 1 || title.length <= maxChars) return title
    return title.take(maxChars - 1).trimEnd() + "…"
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
