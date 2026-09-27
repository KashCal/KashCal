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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import org.onekash.kashcal.MainActivity
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.model.MonthGrid
import org.onekash.kashcal.ui.shared.contrastForegroundOn
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.Month
import java.util.Locale
import java.time.format.TextStyle as JavaTextStyle

/**
 * Padding around today's day number that forms the solid accent marker, in dp.
 *
 * The marker wraps the number with padding, not a fixed size, so it grows with the number at a
 * large font scale instead of clipping it: a circle at normal scale, a rounded pill when scaled
 * up. Horizontal padding is wider than vertical so a single digit still looks round.
 *
 * Vertical padding stays at 1dp because today's number shares the fixed-height cell with the
 * event dots below it; any extra height clips the dots off the bottom of the cell.
 */
internal const val TODAY_MARKER_HORIZONTAL_PADDING_DP = 6
internal const val TODAY_MARKER_VERTICAL_PADDING_DP = 1

/**
 * Corner radius of today's accent marker, in dp. Larger than half the marker's height at normal
 * scale, so the marker is fully rounded; at a large font scale it becomes a rounded rectangle
 * instead of clipping the number.
 */
internal const val TODAY_MARKER_CORNER_RADIUS_DP = 12

/**
 * Gap between the month-navigation cluster (title and next arrow) and the "+" button in the
 * header, in dp, so a "next month" tap can't land on "add event".
 */
internal const val MONTH_HEADER_ADD_GAP_DP = 12

/** Number of week rows in the fixed 6x7 [MonthGrid]; the widget renders only [visibleWeeks]. */
internal const val MONTH_GRID_WEEK_ROWS = 6

/**
 * Width of the optional leading week-number gutter, in dp. Narrower than the in-app grid's 24dp
 * gutter because the widget is small and a week number is at most two digits. The day-of-week
 * header reserves the same width so its columns line up with the grid below.
 */
internal const val WEEK_NUMBER_GUTTER_WIDTH_DP = 18

// ==================== Event-title rows (optional month day-cell style) ====================

/**
 * Header height budgeted when sizing event-title rows, in dp. It is below the 48dp of the
 * nav-arrow and "+" touch targets so the grid doesn't under-fill.
 */
internal const val MONTH_HEADER_HEIGHT_DP = 40

/**
 * Vertical space the day-of-week letter row occupies, in dp: [WidgetTypography.monthDayNumber]
 * (14sp ≈ 17dp at font-scale 1.0) plus the row's 4dp vertical padding.
 */
internal const val MONTH_DOW_ROW_HEIGHT_DP = 21

/**
 * Vertical space the day-number block reserves at the top of a day cell, in dp: the 14sp number
 * (about 17dp at font scale 1.0) plus the today marker's vertical padding.
 *
 * It must not under-budget the number's real height. The number and the event rows share the
 * cell's fixed height, so a value too small makes [maxEventRows] report a row that doesn't fit,
 * and the number pushes it off the cell: day numbers show but events don't at small sizes.
 */
internal const val DAY_NUMBER_BLOCK_HEIGHT_DP = 19

/**
 * Rendered height of one event title row at font scale 1.0, in dp: the 11sp text line (about
 * 14dp) plus the pill's vertical padding. [maxEventRows] and [minWidgetHeightForTitlesDp]
 * multiply it by the system font scale, so a larger font yields fewer rows and a higher titles
 * threshold instead of a row pushed off the cell. An under-estimate lets the fitter claim a row
 * that the text then clips mid-glyph; over-estimating costs an occasional row but never clips.
 */
internal const val TIMED_TITLE_ROW_HEIGHT_DP = 16

/** Vertical gap between two event rows in a day cell, in dp. */
internal const val EVENT_ROW_GAP_DP = 1

/**
 * Hard cap on event slot rows per week in titles mode, and the number that keeps the widget inside
 * its view-ID budget. Glance translates each widget size from a fixed pool of 500 view IDs; a
 * composition that needs more throws during translation and the host shows "Can't show content".
 * Each element costs one ID, and each element with a tap action costs three, because Glance wraps
 * it in a box plus a ripple image (a background color costs nothing extra).
 *
 * A titles-mode slot row is therefore bounded at 12 IDs regardless of how many events a month has:
 * one row container, at most seven elements ([slotRowRuns] merges bars and blank runs), and at most
 * two tap actions (the row's first pill and first bar). Each week adds a fixed ~35 (week and
 * content containers, seven day tap targets, seven day numbers, the optional week-number gutter),
 * so a week costs about 35 + rows x 12, and six weeks at three rows plus the header come to about
 * 464. A "+n" heavy row costs at most 10, below the 12-ID pill/bar row. A fourth row would
 * overflow, which is why this stays at three: past the cap, extra widget height stretches the
 * weighted week rows instead of adding rows, so the cost never grows with size.
 */
internal const val MAX_EVENT_ROWS = 3

/**
 * Weeks a month can span in the worst case (a 31-day month whose first day lands late in the
 * week). The titles-vs-dots threshold ([minWidgetHeightForTitlesDp]) uses this fixed count, never
 * the current month's week count, so one widget shows the same mode every month. With the
 * month's own count, a widget sized near the threshold flips between dots and titles as the
 * month rolls from 5 to 6 weeks.
 */
internal const val WORST_CASE_MONTH_WEEKS = 6

/**
 * Event rows a day cell must fit, in the worst-case 6-week month, before the widget renders
 * titles instead of the compact dots. Dots are the small-widget floor; titles take over as soon
 * as a cell fits this many rows. A cell with one row shows the top event's title and hides the
 * rest, like the dots cap, which tells more than three anonymous dots.
 *
 * A single row doesn't clip: the threshold and [maxEventRows] use the same font-scaled row height
 * ([TIMED_TITLE_ROW_HEIGHT_DP]), so a widget at the threshold fits its row. Two rows would only
 * add room for a "+n" marker beside the title, and would hold titles back until the widget is
 * dragged much larger, though a placed 4x4 widget already has room for one.
 */
internal const val TITLES_MIN_ROWS = 1

/** Tint alpha for a timed multi-day span bar's background (in-app TimedSpan style). */
internal const val TIMED_SPAN_TINT_ALPHA = 0.18f

/**
 * Tint alpha for an all-day free event's background, matching the in-app month view's
 * AllDayFree style: the event's hue, quiet enough to read as "not busy".
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
 * Estimated average advance width per character at [WidgetTypography.label] (11sp) and font scale
 * 1.0, in dp. [maxTitleChars] uses it to size pre-truncated titles, because Glance's Text clips
 * overflow mid-glyph instead of ellipsizing. Slightly generous: a touch too short beats a clipped
 * glyph.
 */
internal const val TITLE_CHAR_WIDTH_DP = 6

/**
 * Returns the weeks the widget renders. [MonthGrid.compute] always returns 6 rows (fixed for the
 * full-size view's paging), but a month spans 4 to 6; trailing rows that are all next-month
 * padding are dropped. Never drops a row containing a day of this month.
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
 * Formats the month header: the full month name in the current year, else the abbreviated name
 * plus the year so it fits, e.g. "April" or "Sep 2025".
 *
 * @param month0 0-indexed month (January = 0)
 * @param currentYear injectable for tests
 */
internal fun formatMonthHeader(
    year: Int,
    month0: Int,
    currentYear: Int = LocalDate.now().year
): String {
    val month = Month.of(month0 + 1)
    return if (year == currentYear) {
        month.getDisplayName(JavaTextStyle.FULL, Locale.getDefault())
    } else {
        "${month.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())} $year"
    }
}

/**
 * Renders the month widget: header, day-of-week row and the month's [visibleWeeks], each week as
 * event titles or dots depending on the widget's size.
 *
 * @param monthEvents events keyed by day code
 * @param monthOffset months from the current month (0 = current)
 * @param targetMonth0 0-indexed month of the displayed month
 * @param firstDayOfWeek java.util.Calendar constant for the first day of the week
 * @param showWeekNumbers whether to render the leading week-of-year gutter
 * @param forcedDark the widget's light/dark pin (null = follow system), for the static
 *   adjacent-month text color, which lives outside the Glance scheme and can't see a pinned face
 * @param today the current date, for the today marker, past-day dimming and the header's year
 *   suffix; injectable so tests are date-independent
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
    forcedDark: Boolean? = null,
    today: LocalDate = LocalDate.now()
) {
    val headerText = formatMonthHeader(targetYear, targetMonth0, currentYear = today.year)
    val todayDayCode = DayPagerUtils.localDateToDayCode(today)

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

        // Each week row takes equal vertical weight, so the rows fill the widget height evenly
        // however many weeks the month spans.
        val weeks = visibleWeeks(monthGrid)
        val gutterLabels = weekNumberGutterLabels(monthGrid, showWeekNumbers)

        // Titles vs. dots follows the widget size (SizeMode.Exact), not a setting: titles appear
        // once a worst-case 6-week month fits TITLES_MIN_ROWS rows per cell at the current font
        // scale ([minWidgetHeightForTitlesDp]). Keying it to widget height, not this month's cell
        // height, keeps a widget in one mode as the month rolls from 5 to 6 weeks. Row count and
        // character width track the actual cell, so a taller widget shows more rows.
        val widgetSize = LocalSize.current
        val fontScale = LocalContext.current.resources.configuration.fontScale
        val widgetHeightDp = widgetSize.height.value
        val cellHeightDp = (widgetHeightDp - MONTH_HEADER_HEIGHT_DP - MONTH_DOW_ROW_HEIGHT_DP) / weeks.size
        val cellWidthDp = weekColumnWidthDp(widgetSize.width.value, showWeekNumbers)
        val showTitles = widgetHeightDp >= minWidgetHeightForTitlesDp(TITLES_MIN_ROWS, fontScale)
        // Per-week day codes, computed once for both render branches.
        val weekDayCodesList = weeks.map { wk -> wk.map { MonthGrid.computeDayCodeForCell(it, targetYear, targetMonth0) } }
        // Rows that fit by height, capped at MAX_EVENT_ROWS. The floor of 1 guards the rounding
        // edge so titles mode never renders bare day numbers. No density check is needed: the
        // titles layout's view-ID cost is bounded by construction ([MAX_EVENT_ROWS]).
        val eventRowCount = if (showTitles) maxEventRows(cellHeightDp, fontScale).coerceAtLeast(1) else 0
        val titleChars = maxTitleChars(cellWidthDp)

        weeks.forEachIndexed { weekIndex, week ->
            val weekDayCodes = weekDayCodesList[weekIndex]
            // A week row is 7 strictly increasing day codes. If a grid/cell mismatch breaks
            // that, fall back to dots instead of drawing bars on the wrong columns.
            val dayCodesValid = weekDayCodes.size == 7 && weekDayCodes.zipWithNext().all { (a, b) -> b > a }
            if (eventRowCount > 0 && dayCodesValid) {
                val weekRender = computeMonthWidgetWeekRender(weekDayCodes, monthEvents, eventRowCount)
                TitlesWeekRow(
                    // Weighted like the dots rows, so a week without events still fills its
                    // share. The slot content stays top-aligned in the stretched cell
                    // ([TitlesWeekRow]), so spare height pads the bottom of each week.
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
                    week.forEachIndexed { col, cell ->
                        val dayCode = weekDayCodes[col]
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
 * Renders one week in titles mode: a day-number row, then the week's slot rows (bars for
 * multi-day events, pills for single-day events, "+n" where a day's events didn't fit).
 *
 * The week is a Box whose first child is a row of seven transparent tap targets, one per day,
 * drawn under the content. Content without its own tap action (day numbers, blank space, "+n"
 * markers, pills and bars other than a row's first) lets the touch fall through, so a tap anywhere
 * in a day opens that day. One target per day, not per slot, keeps the week inside the view-ID
 * budget ([MAX_EVENT_ROWS]).
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
    val resources = LocalContext.current.resources
    // Adjacent-month days announce no events, as in dots mode, though titles mode still draws
    // their events.
    val dayDescriptions = week.mapIndexed { col, cell ->
        val count = if (cell.position != MonthGrid.DayPosition.MonthDate) 0 else monthEvents[weekDayCodes[col]].orEmpty().size
        buildAccessibilityDescription(resources, weekDayCodes[col], count)
    }
    Row(modifier = modifier) {
        if (gutterLabel != null) {
            WeekNumberGutterCell(gutterLabel)
        }
        Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
            // Underneath: the week's day tap targets.
            Row(modifier = GlanceModifier.fillMaxSize()) {
                weekDayCodes.forEachIndexed { col, dayCode ->
                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight()
                            .clickable(dayClickAction(dayCode))
                            .semantics { contentDescription = dayDescriptions[col] }
                    ) {}
                }
            }
            // On top: day numbers and event rows, packed at the top of the stretched week cell so
            // spare height gathers below them.
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.Top
            ) {
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    week.forEachIndexed { col, cell ->
                        val dayCode = weekDayCodes[col]
                        DayNumberCell(
                            modifier = GlanceModifier.defaultWeight(),
                            cell = cell,
                            isToday = dayCode == todayDayCode,
                            isPast = dayCode < todayDayCode,
                            forcedDark = forcedDark
                        )
                    }
                }
                // weekRender holds only the rows that fit the cell height ([maxEventRows]).
                weekRender.slots.forEach { slotRow ->
                    SlotRow(
                        runs = slotRowRuns(slotRow),
                        cellWidthDp = cellWidthDp,
                        maxTitleChars = maxTitleChars
                    )
                }
            }
        }
    }
}

/**
 * Renders a titles-mode day number: centered, with the today marker and past or adjacent-month
 * dimming. It has no tap action and no description: the day's target underneath takes the tap
 * and carries the description, so TalkBack reads it once and the number as a plain number.
 */
@Composable
private fun DayNumberCell(
    modifier: GlanceModifier,
    cell: MonthGrid.DayCell,
    isToday: Boolean,
    isPast: Boolean,
    forcedDark: Boolean?
) {
    val isAdjacentMonth = cell.position != MonthGrid.DayPosition.MonthDate
    val textColor = when {
        isAdjacentMonth -> WidgetTheme.adjacentMonthText(forcedDark)
        isToday -> WidgetTheme.onTodayMarker
        isPast -> WidgetTheme.pastEventText
        else -> WidgetTheme.primaryText
    }
    if (isToday && !isAdjacentMonth) {
        // The marker sits on the number itself, centered in the cell, so it stays a circle
        // instead of stretching to the cell's width.
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            DayNumberText(
                cell.dayOfMonth, textColor, bold = true,
                modifier = GlanceModifier
                    .cornerRadius(TODAY_MARKER_CORNER_RADIUS_DP.dp)
                    .background(WidgetTheme.todayMarkerBackground)
                    .padding(horizontal = TODAY_MARKER_HORIZONTAL_PADDING_DP.dp, vertical = TODAY_MARKER_VERTICAL_PADDING_DP.dp)
            )
        }
    } else {
        DayNumberText(cell.dayOfMonth, textColor, bold = false, modifier = modifier)
    }
}

/** The day-of-month number as a single Text. */
@Composable
private fun DayNumberText(
    dayOfMonth: Int,
    color: ColorProvider,
    bold: Boolean,
    modifier: GlanceModifier = GlanceModifier
) {
    Text(
        text = "$dayOfMonth",
        style = TextStyle(
            color = color,
            fontSize = WidgetTypography.monthDayNumber,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center
        ),
        modifier = modifier
    )
}

/** Opens the app at [dayCode]; the tap action of every day-cell surface. */
private fun dayClickAction(dayCode: Int) = actionStartActivity<MainActivity>(
    parameters = actionParametersOf(
        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_DATE,
        ActionParameters.Key<Int>(EXTRA_DAY_CODE) to dayCode
    )
)

/**
 * Returns the extras a pill or bar tap sends to open the event's Quick View: the same
 * [ACTION_SHOW_EVENT] payload the agenda, week and upcoming widgets send. A separate function,
 * like [footerActionParameters], so `MonthWidgetContentTest` can test the click wiring.
 */
internal fun eventActionParameters(event: WidgetDataRepository.WidgetEvent): ActionParameters =
    actionParametersOf(
        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_SHOW_EVENT,
        ActionParameters.Key<Long>(EXTRA_EVENT_ID) to event.eventId,
        ActionParameters.Key<Long>(EXTRA_OCCURRENCE_TS) to event.occurrenceStartTs,
        ActionParameters.Key<Boolean>(EXTRA_IS_DEVICE_EVENT) to event.isDeviceEvent
    )

/** Opens [event]'s Quick View; the tap action of a row's first pill or bar. */
private fun eventClickAction(event: WidgetDataRepository.WidgetEvent) = actionStartActivity<MainActivity>(
    parameters = eventActionParameters(event)
)

/**
 * One rendered element of a titles-mode slot row, spanning [width] day columns from [startCol].
 * [slotRowRuns] produces them so a row renders at most seven elements with at most two tap
 * targets, which bounds the widget's view-ID cost ([MAX_EVENT_ROWS]).
 */
internal sealed interface SlotRun {
    val startCol: Int
    val width: Int

    /** A multi-day bar; one run covers all of its columns in this row. */
    data class Bar(val span: MonthWidgetSpan, override val startCol: Int, override val width: Int, val deepLink: Boolean) : SlotRun

    /** A single-day event pill. */
    data class Pill(val event: WidgetDataRepository.WidgetEvent, override val startCol: Int, val deepLink: Boolean) : SlotRun {
        override val width: Int get() = 1
    }

    /** A "+n more" marker; never a tap target, so taps reach the day underneath. */
    data class Overflow(val count: Int, override val startCol: Int) : SlotRun {
        override val width: Int get() = 1
    }

    /** A run of empty columns, drawn as one spacer. */
    data class Blank(override val startCol: Int, override val width: Int) : SlotRun
}

/**
 * Turns a seven-column slot row into ordered [SlotRun]s that cover columns 0..6 once each.
 * Consecutive empty columns merge into one [SlotRun.Blank], and consecutive segments of one bar
 * into one [SlotRun.Bar]. Merging follows span identity: [computeMonthWidgetWeekRender] fills
 * every column of a bar with the same [MonthWidgetSpan] instance, so two bars with equal fields
 * are never joined.
 *
 * Only the row's first pill and first bar deep-link to their event; a tap on any other element
 * falls through to the day's target underneath. Each tap action costs three view IDs, so this cap
 * keeps a row bounded.
 */
internal fun slotRowRuns(slotRow: List<MonthWidgetSlot>): List<SlotRun> {
    require(slotRow.size == 7) { "a slot row must have 7 columns, got ${slotRow.size}" }
    val runs = mutableListOf<SlotRun>()
    var pillLinked = false
    var barLinked = false
    var col = 0
    while (col < 7) {
        when (val slot = slotRow[col]) {
            is MonthWidgetSlot.BarSegment -> {
                var end = col
                while (end + 1 < 7 && (slotRow[end + 1] as? MonthWidgetSlot.BarSegment)?.span === slot.span) end++
                runs += SlotRun.Bar(slot.span, col, end - col + 1, deepLink = !barLinked)
                barLinked = true
                col = end + 1
            }
            is MonthWidgetSlot.CellEvent -> {
                runs += SlotRun.Pill(slot.event, col, deepLink = !pillLinked)
                pillLinked = true
                col++
            }
            is MonthWidgetSlot.Overflow -> {
                runs += SlotRun.Overflow(slot.count, col)
                col++
            }
            MonthWidgetSlot.Empty -> {
                var end = col
                while (end + 1 < 7 && slotRow[end + 1] === MonthWidgetSlot.Empty) end++
                runs += SlotRun.Blank(col, end - col + 1)
                col = end + 1
            }
        }
    }
    return runs
}

/**
 * Renders one titles-mode slot row from its [SlotRun]s. Blank runs are one spacer and "+n"
 * markers plain text; taps on either fall through to the day target underneath ([TitlesWeekRow]).
 */
@Composable
private fun SlotRow(
    runs: List<SlotRun>,
    cellWidthDp: Float,
    maxTitleChars: Int
) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = EVENT_ROW_GAP_DP.dp)) {
        runs.forEachIndexed { index, run ->
            // Fixed widths, not defaultWeight(): Glance's defaultWeight() is always weight(1f), so
            // a weighted multi-column run would collapse to one column. The last run stretches to
            // absorb the fixed widths' rounding, so the row spans the full grid.
            val runModifier = if (index == runs.lastIndex) {
                GlanceModifier.defaultWeight()
            } else {
                GlanceModifier.width((cellWidthDp * run.width).dp)
            }
            when (run) {
                is SlotRun.Bar -> SpanBar(
                    span = run.span,
                    width = run.width,
                    maxTitleChars = maxTitleChars,
                    deepLink = run.deepLink,
                    modifier = runModifier
                )
                is SlotRun.Pill -> EventTitleRow(run.event, maxTitleChars, runModifier, deepLink = run.deepLink)
                is SlotRun.Overflow -> Text(
                    text = LocalContext.current.getString(R.string.status_more_events_compact, run.count),
                    style = TextStyle(
                        color = WidgetTheme.secondaryText,
                        fontSize = WidgetTypography.label
                    ),
                    maxLines = 1,
                    modifier = runModifier.padding(start = 3.dp)
                )
                is SlotRun.Blank -> Spacer(modifier = runModifier)
            }
        }
    }
}

/**
 * Renders a multi-day event's bar across [width] day columns: all-day busy is a solid fill with a
 * contrasting title, all-day free a quiet tint with a colored title, timed a quiet tint with the
 * title in the cell's text color. A bar continuing from the previous week shows no title, like
 * the app's flush span caps.
 */
@Composable
private fun SpanBar(
    span: MonthWidgetSpan,
    width: Int,
    maxTitleChars: Int,
    deepLink: Boolean,
    modifier: GlanceModifier
) {
    val event = span.event
    val color = Color(event.calendarColor)
    // The title runs across the bar's full span, so it gets `width` times the per-cell character
    // budget, which already has the per-cell chrome deducted.
    val spanChars = (maxTitleChars * width).coerceAtLeast(maxTitleChars)
    val title = truncateTitle(event.title, spanChars)

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

    // Glance's cornerRadius applies one radius to all corners, and nested boxes can't give
    // per-edge radii. So the bar is rounded only when both ends cap in this week, and square
    // when either edge continues into an adjacent week, so it reads as one unbroken band.
    val radiusModifier = if (!span.leftFlush && !span.rightFlush) {
        GlanceModifier.cornerRadius(capRadius)
    } else {
        GlanceModifier
    }

    // A single Text is the whole bar (fill, corners, padding and title), so a bar costs one view
    // ID, three for the row's first bar with its tap action ([MAX_EVENT_ROWS]). A bar continuing
    // from the previous week renders a single space so its height matches a titled bar. Height
    // comes from the text line plus vertical padding, not a fixed chip height, so a tall line is
    // never clipped vertically.
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
            // Only the row's first bar opens its Quick View; a tap on another falls through to
            // the day target underneath.
            .let { m -> if (deepLink) m.clickable(eventClickAction(event)) else m }
            .padding(horizontal = 3.dp, vertical = 1.dp)
    )
}

/** Renders the header: previous and next arrows, the month title and the "+" button. */
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
        // Back arrow, 48dp minimum touch target
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

        // The title returns to the current month when navigated away, else opens the app at today
        val headerAction = if (monthOffset != 0) {
            actionRunCallback<MonthNavResetAction>()
        } else {
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
                ),
                maxLines = 1
            )
        }

        val nextMonthDesc = LocalContext.current.getString(R.string.cd_next_month)
        // Forward arrow, 48dp minimum touch target
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

        // Keeps a "next month" tap from landing on "+". The header has ample width.
        Spacer(modifier = GlanceModifier.width(MONTH_HEADER_ADD_GAP_DP.dp))

        // Plain "+" glyph with a 48dp touch target, the nav arrows' size
        WidgetAddButton()
    }
}

/**
 * Renders the single-letter (CLDR NARROW) day-of-week headers, with a leading gutter spacer when
 * [showWeekNumbers] is on so the columns line up with the grid below. Each letter carries the full
 * day name as its accessibility label, so TalkBack announces "Monday", not an ambiguous letter.
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
 * Renders the leading week-of-year cell, [WEEK_NUMBER_GUTTER_WIDTH_DP] wide like the space the
 * day-of-week header reserves, in the day-of-week letters' muted text so it reads as an index,
 * not a day.
 *
 * The cell sizes to its text and top-aligns, so the number sits on the line of the day numbers
 * beside it, in both modes; in dots mode the day cells set the row height. It doesn't
 * `fillMaxHeight`: in a row without weight, that measures the row against the whole remaining
 * widget height, so the first week balloons and the rest clip off the bottom.
 */
@Composable
private fun WeekNumberGutterCell(label: String) {
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
 * Renders a dots-mode day cell: the centered day number, with the today marker, and up to 3
 * event dots. Titles mode renders [TitlesWeekRow] instead.
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
        // Centered, not top-aligned: top alignment puts all vertical overflow at the bottom, so a
        // larger font or display scale shaves the dots off the cell's edge first. Centered, the
        // overflow shares the number's line-box slack and the dots stay visible.
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Today's number sits in a solid accent circle and flips to the on-accent color
            // (the Material "today" treatment). Only today wraps the number in a marker Box;
            // other days put it straight into the Column, which keeps each cell light on the
            // shared view-ID pool ([MAX_EVENT_ROWS]) that a busy month's dots would exhaust.
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

            // Adjacent-month cells stay bare: a faded number only.
            if (!isAdjacentMonth && dotColors.isNotEmpty()) {
                Spacer(modifier = GlanceModifier.height(1.dp))
                Row(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = GlanceModifier.fillMaxWidth()
                ) {
                    dotColors.forEachIndexed { index, color ->
                        // The 2dp gap is start padding on the dot, not a Spacer: one fewer view
                        // per dot, which across 7 columns and 6 weeks keeps a busy month's dots
                        // inside the shared view-ID pool ([MAX_EVENT_ROWS]).
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
 * Renders a single-day event as a filled pill, styled like [SpanBar] so bars and pills read as
 * one family:
 * - timed: quiet tint, title in the cell's text color
 * - all-day busy: solid fill in the event color, WCAG-contrasting title
 * - all-day free: quiet tint, title in the event color
 *
 * A single Text carrying the background, corner and padding, with no wrapping Box, stripe or
 * spacer. Each extra element multiplies across 7 columns, [MAX_EVENT_ROWS] rows and 6 weeks, so a
 * pill stays one view ID, three for the row's first pill with its tap action (Glance wraps a
 * tappable element in a box plus a ripple image). Height comes from the text line plus vertical
 * padding, not a fixed chip height, so a tall line is never clipped vertically.
 */
@Composable
private fun EventTitleRow(
    event: WidgetDataRepository.WidgetEvent,
    maxTitleChars: Int,
    modifier: GlanceModifier,
    deepLink: Boolean
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
    // Only the row's first pill (deepLink) has a tap action; others fall through to the day
    // target underneath.
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
            .let { m -> if (deepLink) m.clickable(eventClickAction(event)) else m }
            .padding(horizontal = 3.dp, vertical = 1.dp)
    )
}

// ==================== Action Callbacks for Month Navigation ====================

/** Moves the widget to the previous month. */
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

/** Moves the widget to the next month. */
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

/** Returns the widget to the current month; the header tap while navigated away. */
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

// ==================== Pure helper functions ====================

/** Returns the events' distinct colors in order of first appearance, capped at [maxDots]. */
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
 * Returns the width of one day column in a week row, in dp: the widget width less the optional
 * week-number gutter, split seven ways. Week rows have no side padding (only the day-of-week
 * header does), so none is subtracted.
 *
 * Event runs use this as a fixed width while day numbers and tap targets use equal weights; they
 * agree only when seven columns fill the row exactly. Any extra inset would shift runs left of
 * their day, so a tap near a run's edge would open the neighbour. A host that draws the widget at a
 * width other than the one it reports causes a small drift, which fixed-width runs can't avoid
 * because Glance weights are always 1.
 */
internal fun weekColumnWidthDp(widgetWidthDp: Float, showWeekNumbers: Boolean): Float =
    (widgetWidthDp - (if (showWeekNumbers) WEEK_NUMBER_GUTTER_WIDTH_DP else 0)) / 7f

/**
 * Returns how many event slot rows fit a week row of [cellHeightDp] below the day-number block,
 * capped at [MAX_EVENT_ROWS], or 0 when not even one fits.
 *
 * Every slot row, the first included, costs [TIMED_TITLE_ROW_HEIGHT_DP] plus one
 * [EVENT_ROW_GAP_DP] of top padding. The row height and [DAY_NUMBER_BLOCK_HEIGHT_DP] scale with
 * [fontScale], so a larger font returns fewer rows instead of clipping. Pass the current
 * `Configuration.fontScale`; 1f is the unscaled baseline.
 *
 * The count must not over-estimate: each titles-mode week is a fixed weighted share of the grid
 * height, so a row the cell can't hold is clipped, not absorbed.
 */
internal fun maxEventRows(cellHeightDp: Float, fontScale: Float = 1f): Int {
    val perRow = TIMED_TITLE_ROW_HEIGHT_DP * fontScale + EVENT_ROW_GAP_DP
    val numberBlock = DAY_NUMBER_BLOCK_HEIGHT_DP * fontScale
    val usable = cellHeightDp - numberBlock
    if (usable < perRow) return 0
    return (usable / perRow).toInt().coerceAtMost(MAX_EVENT_ROWS)
}

/**
 * Returns the smallest widget height, in dp, at which the month renders titles instead of dots:
 * the height a [WORST_CASE_MONTH_WEEKS]-week month needs for every cell to fit [titleRows] event
 * rows below the day number.
 *
 * It adds [MONTH_HEADER_HEIGHT_DP] and [MONTH_DOW_ROW_HEIGHT_DP] to, per week, the day-number
 * block and [titleRows] rows with their gaps: the same per-row cost [maxEventRows] fits against,
 * so a widget at or above the threshold fits [titleRows] rows. The fixed week count keeps the
 * decision month-stable ([WORST_CASE_MONTH_WEEKS]).
 */
internal fun minWidgetHeightForTitlesDp(titleRows: Int, fontScale: Float = 1f): Float {
    val perRow = TIMED_TITLE_ROW_HEIGHT_DP * fontScale + EVENT_ROW_GAP_DP
    val numberBlock = DAY_NUMBER_BLOCK_HEIGHT_DP * fontScale
    val perWeek = numberBlock + titleRows * perRow
    return MONTH_HEADER_HEIGHT_DP + MONTH_DOW_ROW_HEIGHT_DP + WORST_CASE_MONTH_WEEKS * perWeek
}

/**
 * Returns how many title characters fit a day cell of [cellWidthDp] after the pill padding
 * ([EVENT_ROW_TEXT_CHROME_DP]), at [TITLE_CHAR_WIDTH_DP] per character. At least 4, so a clipped
 * title still leaves something readable.
 */
internal fun maxTitleChars(cellWidthDp: Float): Int =
    ((cellWidthDp - EVENT_ROW_TEXT_CHROME_DP) / TITLE_CHAR_WIDTH_DP)
        .toInt()
        .coerceAtLeast(4)

/**
 * Cuts [title] to its first [maxChars] chars, trimming a trailing space at the cut. Glance's Text
 * clips overflow mid-glyph, so titles are pre-shortened to what [maxTitleChars] estimates fits.
 * There is no trailing "…" so the narrow cell spends every character on the title; the cell edge
 * already signals there is more.
 */
internal fun truncateTitle(title: String, maxChars: Int): String {
    if (maxChars <= 0 || title.length <= maxChars) return title
    return title.take(maxChars).trimEnd()
}

/**
 * Returns the 7 localized single-letter (CLDR NARROW) day-of-week headers starting from
 * [firstDayOfWeek], e.g. English "S M T W T F S".
 *
 * Repeated letters (Sun/Sat "S", Tue/Thu "T") are told apart by column position and, for screen
 * readers, by [dayOfWeekAccessibilityLabels]. The order comes from
 * [DateTimeUtils.getOrderedDaysOfWeek], the helper [MonthGrid.compute] orders the grid rows with,
 * so the header can't drift from the grid.
 *
 * @param firstDayOfWeek java.util.Calendar constant (1=Sun, 2=Mon, ..., 7=Sat) or 0 for the
 *   system default
 */
internal fun getDayOfWeekHeaders(firstDayOfWeek: Int): List<String> {
    val locale = Locale.getDefault()
    return DateTimeUtils.getOrderedDaysOfWeek(firstDayOfWeek).map { it.getDisplayName(JavaTextStyle.NARROW, locale) }
}

/**
 * Returns the 7 full localized day names (e.g. "Sunday") in [getDayOfWeekHeaders]' order, the
 * accessibility labels of the single-letter headers.
 *
 * @param firstDayOfWeek as in [getDayOfWeekHeaders]
 */
internal fun dayOfWeekAccessibilityLabels(firstDayOfWeek: Int): List<String> {
    val locale = Locale.getDefault()
    return DateTimeUtils.getOrderedDaysOfWeek(firstDayOfWeek).map { it.getDisplayName(JavaTextStyle.FULL, locale) }
}

/**
 * Returns the week-of-year gutter labels, one per [visibleWeeks] row, or empty when "show week
 * numbers" is off. Each is the row's [MonthGrid.DayCell.weekNumber], locale-aware, as in the
 * in-app month grid's week-number column.
 */
internal fun weekNumberGutterLabels(grid: MonthGrid, showWeekNumbers: Boolean): List<String> {
    if (!showWeekNumbers) return emptyList()
    return visibleWeeks(grid).map { it.first().weekNumber.toString() }
}

/**
 * Builds a day cell's accessibility description from its YYYYMMDD [dayCode], such as
 * "March 15, 2 events". The month comes from the day code, so adjacent-month cells get theirs.
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
 * Builds a day cell's accessibility description, "March 15, 2 events" or "March 15, no events".
 *
 * @param month0 0-indexed month (January = 0)
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
