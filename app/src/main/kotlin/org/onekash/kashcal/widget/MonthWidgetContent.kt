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
import androidx.glance.unit.ColorProvider
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
 * (14sp ≈ 17dp at font-scale 1.0) plus the row's 4dp vertical padding.
 */
internal const val MONTH_DOW_ROW_HEIGHT_DP = 21

/**
 * Vertical space the day-number block reserves at the top of a day cell, in dp — the 14sp
 * number (≈17dp at font-scale 1.0) plus the today marker's vertical padding.
 *
 * This must not under-budget the number's real height: the day-number row and the event
 * rows below it share the cell's fixed height, so if this value is too small, [maxEventRows]
 * reports a row that fits when it does not, and the number ends up shoving that row off the
 * cell's bottom — the "numbers show but events don't" failure at small widget sizes.
 */
internal const val DAY_NUMBER_BLOCK_HEIGHT_DP = 19

/**
 * Real rendered height of one event title row at font-scale 1.0, in dp: the 11sp text line
 * (≈14dp) plus the pill's vertical padding. This is a font-scale-1.0 baseline — [maxEventRows]
 * and [minWidgetHeightForTitlesDp] multiply it by the system font scale so a scaled-up font
 * counts each row taller and the layout backs off (fewer rows / a higher titles threshold)
 * instead of shoving a row off the cell bottom. An earlier under-estimate (13dp) let the fitter
 * claim a row that the scaled text then clipped mid-glyph — the cramped, cut-off single line at
 * small sizes. Being honest here trades an occasional extra row for never clipping.
 */
internal const val TIMED_TITLE_ROW_HEIGHT_DP = 16

/** Vertical gap between two event rows in a day cell, in dp. */
internal const val EVENT_ROW_GAP_DP = 1

/**
 * Hard cap on event slot rows per week in titles mode. Deliberately small: a widget is rendered as
 * RemoteViews, and each widget can allocate at most 500 views total (Glance's fixed view-ID pool).
 * Every element in every one of the 7 day columns across all 6 week rows draws from that one pool,
 * so the row count is the dominant multiplier — an unbounded count lets a busy month overrun the
 * pool and the host shows "Can't show content" instead of the grid.
 *
 * The value that fits depends on how many views each event costs: while each row cell was two views
 * (a Box wrapping a Text) three rows overran the pool on large widgets; collapsing every event to a
 * single Text (see [EventTitleRow]) roughly halved that, so a fully-booked six-week month at three
 * rows now measures well inside the pool at any size. Crucially the pool is spent per element, not
 * per pixel, so this cap — not the widget's size — bounds the view count: a large widget at the cap
 * costs the same as a medium one. Row count grows with widget height only up to this cap; height
 * beyond what the capped rows need is left as empty space at the bottom of the grid (titles-mode
 * rows are content-height, not stretched), so the view count never climbs with size past the cap.
 */
internal const val MAX_EVENT_ROWS = 3

/**
 * Weeks a month can span in the worst case (a 31-day month whose first day lands late in the
 * week). The titles-vs-dots threshold ([minWidgetHeightForTitlesDp]) is derived against this
 * fixed count, never the current month's actual week count, so the SAME widget shows the same
 * mode every month. Deriving against the variable count instead makes a widget sized in the
 * narrow band around the threshold flip between dots and titles as the month rolls from 5 to 6
 * weeks — the cell height, and with it the fitter's answer, changes underneath a fixed widget.
 */
internal const val WORST_CASE_MONTH_WEEKS = 6

/**
 * Event rows a day cell must have room for — in the worst-case 6-week month — before the widget
 * renders titles instead of the compact dots. The dots are the small-widget floor: only the
 * smallest resizes show them, and titles take over as soon as a cell fits this many honest rows.
 * One row is the floor: dots are the compact minimum, and any extra height becomes a title. A cell
 * that fits a single row shows the top event's title (a day with more collapses the rest, like the
 * dots cap) rather than three anonymous dots — more informative, and the widget is lossy by design.
 *
 * A single row does NOT clip: the threshold and the row-fitter ([maxEventRows]) share the same
 * honest row-height baseline ([TIMED_TITLE_ROW_HEIGHT_DP], font-scale-multiplied), so a widget at
 * the threshold fits its row with none shoved off the cell. Requiring two rows only buys room for a
 * "+n" marker beside the title, at the cost of holding the whole titles mode back until the widget
 * is dragged much larger — a poor trade when a placed 4x4 widget already has room for one.
 */
internal const val TITLES_MIN_ROWS = 1

/** Tint alpha for a timed multi-day span bar's background (in-app TimedSpan style). */
internal const val TIMED_SPAN_TINT_ALPHA = 0.18f

/**
 * Tint alpha for an all-day FREE event chip's background, mirroring the in-app month view's
 * AllDayFree style (same hue as the event, quiet enough to read as "not busy").
 */
internal const val ALL_DAY_FREE_TINT_ALPHA = 0.2f

/** Corner radius of event pills in a day cell, in dp. */
internal const val EVENT_CHIP_CORNER_RADIUS_DP = 3

/**
 * Horizontal chrome around the title text inside a day cell, in dp: the pill's 3dp padding on
 * each side, rounded up. Subtracted from the cell width before estimating how many title
 * characters fit.
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

        // Titles vs. dots is driven purely by the ACTUAL widget size (SizeMode.Exact), no setting:
        // the dots are the small-widget floor, and titles appear once the widget is tall enough
        // that a worst-case 6-week month fits TITLES_MIN_ROWS rows per cell. The threshold is
        // derived from the real element heights AND the system font scale (a bigger font grows the
        // text, so the threshold rises with it), so a widget that shows titles always has room for
        // them — never the cramped, cut-off single line. Keying the threshold to widget height (not
        // the per-month cell height) keeps the same widget in the same mode as the month rolls from
        // 5 to 6 weeks. Row COUNT and character width still track the actual stretched cell, so a
        // taller widget shows more rows; only the dots/titles decision is month-stable.
        val widgetSize = LocalSize.current
        val fontScale = LocalContext.current.resources.configuration.fontScale
        val widgetHeightDp = widgetSize.height.value
        val cellHeightDp = (widgetHeightDp - MONTH_HEADER_HEIGHT_DP - MONTH_DOW_ROW_HEIGHT_DP) / weeks.size
        val cellWidthDp = (widgetSize.width.value - gridHorizontalPaddingDp(showWeekNumbers)) / 7f
        val showTitles = widgetHeightDp >= minWidgetHeightForTitlesDp(TITLES_MIN_ROWS, fontScale)
        // At or above the threshold a 6-week cell fits >= TITLES_MIN_ROWS rows and any month with
        // fewer weeks fits at least as many; the floor of 1 is a rounding-edge guard so titles mode
        // never renders bare day numbers with no room claimed for events.
        val eventRowCount = if (showTitles) maxEventRows(cellHeightDp, fontScale).coerceAtLeast(1) else 0
        val titleChars = maxTitleChars(cellWidthDp)

        weeks.forEachIndexed { weekIndex, week ->
            val weekDayCodes = week.map { MonthGrid.computeDayCodeForCell(it, targetYear, targetMonth0) }
            // A week row is always 7 strictly-increasing day codes. If that ever breaks
            // (grid/cell mismatch), fall back to dots rather than render bars anchored on
            // wrong columns — degrade gracefully instead of blanking the widget.
            val dayCodesValid = weekDayCodes.size == 7 && weekDayCodes.zipWithNext().all { (a, b) -> b > a }
            if (showTitles && dayCodesValid) {
                // Titles mode: week slot layout — multi-day events span their columns as
                // continuous bars, single-day events fill the remaining per-cell slots.
                val weekRender = computeMonthWidgetWeekRender(weekDayCodes, monthEvents, eventRowCount)
                TitlesWeekRow(
                    // Weighted like the dots rows: every week shares the grid height evenly, so
                    // a week without events still fills its cell — no "stacked from the top,
                    // cramped empty days" look. The slot CONTENT stays top-aligned inside the
                    // stretched cell (see TitlesWeekRow's Column), so the spare height pads the
                    // bottom of each week rather than pulling the event rows apart.
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
        // Top-aligned content inside the (possibly stretched) week cell: the day numbers and
        // event rows pack at the top, spare height gathers below them — matching how the
        // dots-mode DayCell pins its number+dot column to the top of its weighted cell.
        Column(
            modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
            verticalAlignment = Alignment.Top
        ) {
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
    val isTodayMarker = isToday && !isAdjacentMonth
    Box(
        modifier = modifier
            .clickable(dayClickAction(dayCode))
            .semantics { contentDescription = accessibilityDesc },
        contentAlignment = Alignment.Center
    ) {
        // Only today wraps the number in a marker Box; every other day puts the number straight
        // into the cell. Skipping the wrapper on the other 6 cells per row keeps each week's
        // day-number row light on the widget's shared view-ID pool (see [MAX_EVENT_ROWS]).
        if (isTodayMarker) {
            Box(
                modifier = GlanceModifier
                    .cornerRadius(TODAY_MARKER_CORNER_RADIUS_DP.dp)
                    .background(WidgetTheme.todayMarkerBackground)
                    .padding(
                        horizontal = TODAY_MARKER_HORIZONTAL_PADDING_DP.dp,
                        vertical = TODAY_MARKER_VERTICAL_PADDING_DP.dp
                    ),
                contentAlignment = Alignment.Center
            ) {
                DayNumberText(cell.dayOfMonth, textColor, bold = true)
            }
        } else {
            DayNumberText(cell.dayOfMonth, textColor, bold = false)
        }
    }
}

/** The day-of-month number as a single Text — the sole view a non-today day cell needs. */
@Composable
private fun DayNumberText(dayOfMonth: Int, color: ColorProvider, bold: Boolean) {
    Text(
        text = "$dayOfMonth",
        style = TextStyle(
            color = color,
            fontSize = WidgetTypography.monthDayNumber,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium
        )
    )
}

/** Tap action shared by every day-cell surface: open the app at that day. */
private fun dayClickAction(dayCode: Int) = actionStartActivity<MainActivity>(
    parameters = actionParametersOf(
        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_DATE,
        ActionParameters.Key<Int>(EXTRA_DAY_CODE) to dayCode
    )
)

/**
 * Tap action for an event title row / span bar: open the event's Quick View in the app —
 * the same deep link the agenda, week, and upcoming widgets use ([ACTION_SHOW_EVENT]).
 */
private fun eventClickAction(event: WidgetDataRepository.WidgetEvent) = actionStartActivity<MainActivity>(
    parameters = actionParametersOf(
        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_SHOW_EVENT,
        ActionParameters.Key<Long>(EXTRA_EVENT_ID) to event.eventId,
        ActionParameters.Key<Long>(EXTRA_OCCURRENCE_TS) to event.occurrenceStartTs,
        ActionParameters.Key<Boolean>(EXTRA_IS_DEVICE_EVENT) to event.isDeviceEvent
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
                        // Fixed width, not defaultWeight(): Glance's defaultWeight() is always
                        // weight(1f) — there is no weight(n) — so a weighted bar collapses to a
                        // single column no matter how many days it spans. The widget knows the
                        // real cell width from LocalSize, so the bar takes width × cellWidth.
                        modifier = if (isLastItem) cellModifier else GlanceModifier.width((cellWidthDp * width).dp)
                    )
                    col = endCol + 1
                }
                is MonthWidgetSlot.CellEvent -> {
                    EventTitleRow(content.event, maxTitleChars, cellModifier)
                    col++
                }
                is MonthWidgetSlot.Overflow -> {
                    // The "+n" marker opens the day so the tap most tied to "show me the ones that
                    // did not fit" lands on the full list. A per-event tap is intentionally not
                    // wired: a Glance clickable wraps each pill in an extra view, and one per pill
                    // across a busy month exhausts the widget's view-ID pool (see [MAX_EVENT_ROWS]).
                    Box(modifier = cellModifier.clickable(dayClickAction(weekDayCodes[col]))) {
                        Text(
                            text = LocalContext.current.getString(R.string.status_more_events_compact, content.count),
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
 * A multi-day event's continuous bar across [width] day columns: all-day busy = solid fill
 * with a contrasting title, all-day free = quiet tint with a colored title, timed multi-day =
 * quiet tint with the title in the cell's own text color. The title shows only at the bar's
 * first segment of this week row; continuation segments (flush edges) keep the bar bare, like
 * the app's flush span caps.
 */
@Composable
private fun SpanBar(
    span: MonthWidgetSpan,
    width: Int,
    maxTitleChars: Int,
    modifier: GlanceModifier
) {
    val event = span.event
    val color = Color(event.calendarColor)
    // The title only fits across the bar's full span, so it earns roughly `width` times the
    // per-cell character budget (minus the chrome the single-cell rows already deduct).
    val spanChars = (maxTitleChars * width).coerceAtLeast(maxTitleChars)
    val title = truncateTitle(event.title, spanChars)

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

    // A single Text is the whole bar: it carries the fill, per-edge corners, tap target, and
    // padding, with the title as its content. One view instead of a Row wrapping a Text halves the
    // per-bar cost against the widget's shared view-ID pool (see [MAX_EVENT_ROWS]). A segment that
    // continues from the previous week shows no title (the flush-cap convention), so it renders a
    // single space to keep the bar's height uniform with titled segments. Height comes from the
    // text line plus vertical padding, not a fixed chip height, so it never clips mid-glyph.
    Text(
        text = if (span.leftFlush) " " else title,
        style = TextStyle(
            color = textProvider,
            fontSize = WidgetTypography.label
        ),
        maxLines = 1,
        modifier = modifier
            .then(radiusModifier)
            .background(ColorProvider(day = fill, night = fill))
            .clickable(eventClickAction(event))
            .padding(horizontal = 3.dp, vertical = 1.dp)
    )
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
    // Top-aligned, NOT fillMaxHeight(): in a content-height titles week row a fillMaxHeight
    // gutter demands the row's full height, which makes the row measure itself against the
    // gutter instead of its content — the first week then expands across the whole grid and
    // collapses every other week to nothing (the "one week row" bug with week numbers on).
    // TopCenter pins the number to the same line as the day numbers beside it without
    // influencing the row's height.
    Box(
        modifier = GlanceModifier
            .width(WEEK_NUMBER_GUTTER_WIDTH_DP.dp),
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
    val textColor = when {
        isAdjacentMonth -> WidgetTheme.adjacentMonthText(forcedDark)
        isToday -> WidgetTheme.onTodayMarker
        isPast -> WidgetTheme.pastEventText
        else -> WidgetTheme.primaryText
    }
    val isTodayMarker = isToday && !isAdjacentMonth

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(dayClickAction(dayCode))
            .semantics { contentDescription = accessibilityDesc },
        // Center the number+dot cluster vertically rather than pinning it to the top. Top-anchoring
        // dumps any vertical overflow onto the bottom, so when a larger font/display scale makes the
        // day number taller the dot below it is the first thing shaved off the cell's edge. Centered,
        // the overflow is shared with the number's own line-box slack and the dot stays visible.
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Day number, centered by the Column. Today is marked with a solid accent circle
            // around the number (the number flips to the on-accent color) — the Material /
            // Google Calendar "today" treatment; other days show a bare number. Only today
            // wraps the number in a marker Box; every other day puts the number straight into
            // the Column. Skipping the wrapper (and the old centering Box) on non-today cells
            // keeps each cell light on the widget's shared view-ID pool (see [MAX_EVENT_ROWS]),
            // which a busy month's dots would otherwise exhaust.
            if (isTodayMarker) {
                Box(
                    modifier = GlanceModifier
                        .cornerRadius(TODAY_MARKER_CORNER_RADIUS_DP.dp)
                        .background(WidgetTheme.todayMarkerBackground)
                        .padding(
                            horizontal = TODAY_MARKER_HORIZONTAL_PADDING_DP.dp,
                            vertical = TODAY_MARKER_VERTICAL_PADDING_DP.dp
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    DayNumberText(cell.dayOfMonth, textColor, bold = true)
                }
            } else {
                DayNumberText(cell.dayOfMonth, textColor, bold = false)
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
                        // The gap between dots is left padding on the dot itself rather than a
                        // separate Spacer view: identical 2dp gap, one fewer view per dot. On a
                        // busy month those saved views add up across all 7 columns and 6 weeks,
                        // keeping dots mode inside the widget's shared view-ID pool (see [MAX_EVENT_ROWS]).
                        Box(
                            modifier = GlanceModifier
                                .padding(start = if (index > 0) 2.dp else 0.dp)
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
 * One single-day event as a filled pill in a day cell, styled to match [SpanBar] so a day's
 * multi-day bars and single-day pills read as one family:
 * - timed event: quiet tint + title in the cell's text color
 * - all-day busy: solid fill in the event color + WCAG-contrasting title
 * - all-day free: quiet tint + title in the event color
 *
 * Deliberately a single Text — no wrapping Box, leading stripe, or inner spacer. Every extra
 * element here is multiplied across up to 7 columns and [MAX_EVENT_ROWS] rows in each of the 6 week
 * rows, and it was that per-event overhead, not the row count alone, that overflowed the widget's
 * view-ID pool and showed "Can't show content" on larger widgets.
 */
@Composable
private fun EventTitleRow(
    event: WidgetDataRepository.WidgetEvent,
    maxTitleChars: Int,
    modifier: GlanceModifier
) {
    val color = Color(event.calendarColor)
    val title = truncateTitle(event.title, maxTitleChars)
    val isTimed = !event.isAllDay
    val fill = when {
        isTimed -> color.copy(alpha = TIMED_SPAN_TINT_ALPHA)
        event.isFree -> color.copy(alpha = ALL_DAY_FREE_TINT_ALPHA)
        else -> color
    }
    val textProvider = when {
        isTimed -> WidgetTheme.primaryText
        event.isFree -> ColorProvider(day = color, night = color)
        else -> contrastForegroundOn(color).let { ColorProvider(day = it, night = it) }
    }
    // A single Text carries the pill background, corner, and padding — no wrapping Box. One view
    // instead of two, multiplied across every cell/row/week, is what lets the grid fit another
    // event row inside the widget's shared view-ID pool (see [MAX_EVENT_ROWS]). The height comes
    // from the text line plus vertical padding rather than a fixed chip height, so the title is
    // never clipped mid-glyph when its line is taller than a fixed slot.
    Text(
        text = title,
        style = TextStyle(
            color = textProvider,
            fontSize = WidgetTypography.label
        ),
        maxLines = 1,
        modifier = modifier
            .cornerRadius(EVENT_CHIP_CORNER_RADIUS_DP.dp)
            .background(ColorProvider(day = fill, night = fill))
            .clickable(eventClickAction(event))
            .padding(horizontal = 3.dp, vertical = 1.dp)
    )
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
 * the budget the titles mode fills, capped by [MAX_EVENT_ROWS]. Each rendered slot row occupies
 * [TIMED_TITLE_ROW_HEIGHT_DP] (scaled by [fontScale]) plus one [EVENT_ROW_GAP_DP] of top padding,
 * so a row costs the same whether it is the first or a later one. Returns 0 when not even one
 * row fits below an honest day-number budget.
 *
 * Both the row height and the day-number block scale with [fontScale]: text grows with the
 * system font setting, so at a larger scale each row and the number take more of the cell and
 * the fitter returns fewer rows — the layout backs off instead of clipping. Pass the current
 * [android.content.res.Configuration.fontScale]; the default of 1f is the unscaled baseline.
 *
 * The count must be exact, not an over-estimate: titles-mode week rows are content-height (they
 * do not stretch), so a row the fitter claims but the cell can't hold is clipped, not absorbed.
 */
internal fun maxEventRows(cellHeightDp: Float, fontScale: Float = 1f): Int {
    // A day cell holds the day-number block plus as many event rows as fit. Every slot row —
    // including the first — carries a leading gap (SlotRow's top padding), so each costs the
    // same (font-scaled) height plus one gap. The day-number budget is sized to the real
    // (font-scaled) number height so a returned row actually clears the number instead of being
    // shoved off the cell — the "numbers show but events don't" failure at small sizes.
    val perRow = TIMED_TITLE_ROW_HEIGHT_DP * fontScale + EVENT_ROW_GAP_DP
    val numberBlock = DAY_NUMBER_BLOCK_HEIGHT_DP * fontScale
    val usable = cellHeightDp - numberBlock
    if (usable < perRow) return 0
    return (usable / perRow).toInt().coerceAtMost(MAX_EVENT_ROWS)
}

/**
 * Smallest widget HEIGHT (dp) at which the month renders titles instead of dots — the height a
 * worst-case [WORST_CASE_MONTH_WEEKS]-week month needs so every cell fits [titleRows] event rows
 * below the day number. At or above this the widget shows titles; below it, dots.
 *
 * Derived from the real chrome ([MONTH_HEADER_HEIGHT_DP] + [MONTH_DOW_ROW_HEIGHT_DP]) plus, per
 * week, the day-number block and [titleRows] rows each with their leading gap — the exact height
 * a titles-mode week row renders at ([maxEventRows] fits against the same per-row cost), so the
 * threshold equals the rendered content height rather than under-counting it. Keying off the
 * fixed 6-week count (not the current month) makes the decision month-stable: the same widget
 * never flips dots<->titles as the month rolls between 5 and 6 weeks. Because it matches the real
 * rendered heights, a widget past the threshold provably fits [titleRows] rows with none clipped.
 */
internal fun minWidgetHeightForTitlesDp(titleRows: Int, fontScale: Float = 1f): Float {
    val perRow = TIMED_TITLE_ROW_HEIGHT_DP * fontScale + EVENT_ROW_GAP_DP
    val numberBlock = DAY_NUMBER_BLOCK_HEIGHT_DP * fontScale
    val perWeek = numberBlock + titleRows * perRow
    return MONTH_HEADER_HEIGHT_DP + MONTH_DOW_ROW_HEIGHT_DP + WORST_CASE_MONTH_WEEKS * perWeek
}

/**
 * How many title characters fit a day cell of [cellWidthDp] after the row chrome (stripe or
 * chip padding), at [TITLE_CHAR_WIDTH_DP] per character. Minimum 4 so a clipped title still
 * leaves something readable.
 */
internal fun maxTitleChars(cellWidthDp: Float): Int =
    ((cellWidthDp - EVENT_ROW_TEXT_CHROME_DP) / TITLE_CHAR_WIDTH_DP)
        .toInt()
        .coerceAtLeast(4)

/**
 * Truncate [title] to [maxChars] whole characters, with no trailing ellipsis. Glance's Text clips
 * overflow mid-glyph, so titles are pre-shortened on a character boundary; [maxTitleChars] estimates
 * how much actually fits the cell. The trailing "…" is deliberately omitted so the narrow widget
 * cell spends every character on the title itself — the cell edge already signals there is more.
 * A trailing space left at the clip boundary is trimmed so the title never ends on a blank glyph.
 */
internal fun truncateTitle(title: String, maxChars: Int): String {
    if (maxChars <= 0 || title.length <= maxChars) return title
    return title.take(maxChars).trimEnd()
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
