package org.onekash.kashcal.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.coroutines.launch
import org.onekash.kashcal.ui.model.MonthGrid
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

private const val YEAR_PAGER_TOTAL = 200
private const val YEAR_PAGER_CENTER = 100

private fun getLocalizedMonthNames(): List<String> {
    val locale = Locale.getDefault()
    return (1..12).map { month ->
        java.time.Month.of(month).getDisplayName(TextStyle.SHORT, locale)
    }
}

/** Shows a year as 12 mini-months laid out by [yearLayout], paged horizontally by year. */
@Composable
fun YearViewContent(
    eventDots: ImmutableMap<String, ImmutableMap<Int, ImmutableList<Int>>>,
    firstDayOfWeek: Int,
    pendingNavigateToToday: Boolean,
    onNavigateToTodayConsumed: () -> Unit,
    onMonthClick: (year: Int, month: Int) -> Unit,
    onYearChanged: (year: Int) -> Unit,
    onBackToMonth: () -> Unit,
    modifier: Modifier = Modifier
) {
    val today = remember { Calendar.getInstance() }
    val currentYear = remember { today.get(Calendar.YEAR) }

    val pagerState = rememberPagerState(
        initialPage = YEAR_PAGER_CENTER,
        pageCount = { YEAR_PAGER_TOTAL }
    )
    val scope = rememberCoroutineScope()

    // Page YEAR_PAGER_CENTER is the current year.
    val displayedYear by remember {
        derivedStateOf {
            val page = pagerState.currentPage
            currentYear + (page - YEAR_PAGER_CENTER)
        }
    }

    // Reports each current page's year so the host loads that year's event dots.
    LaunchedEffect(Unit) {
        snapshotFlow { pagerState.currentPage }
            .collect { page ->
                val year = currentYear + (page - YEAR_PAGER_CENTER)
                onYearChanged(year)
            }
    }

    // Scrolls back to the current year, then consumes the request.
    LaunchedEffect(pendingNavigateToToday) {
        if (pendingNavigateToToday) {
            pagerState.animateScrollToPage(YEAR_PAGER_CENTER)
            onNavigateToTodayConsumed()
        }
    }

    // Back returns to the month view.
    BackHandler { onBackToMonth() }

    Column(modifier = modifier.fillMaxSize()) {
        YearHeaderStrip(
            displayedYear = displayedYear,
            startYear = currentYear - YEAR_PAGER_CENTER,
            totalYears = YEAR_PAGER_TOTAL,
            onYearClick = { year ->
                val page = YEAR_PAGER_CENTER + (year - currentYear)
                scope.launch { pagerState.animateScrollToPage(page) }
            }
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Top
        ) { page ->
            val pageYear = currentYear + (page - YEAR_PAGER_CENTER)
            YearPage(
                year = pageYear,
                eventDots = eventDots,
                firstDayOfWeek = firstDayOfWeek,
                today = today,
                onMonthClick = onMonthClick
            )
        }
    }
}

/** Shows a scrollable row of year numbers; tapping one calls [onYearClick]. */
@Composable
private fun YearHeaderStrip(
    displayedYear: Int,
    startYear: Int,
    totalYears: Int,
    onYearClick: (Int) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Keeps the displayed year visible, with up to two earlier years before it.
    LaunchedEffect(displayedYear) {
        val index = displayedYear - startYear
        if (index in 0 until totalYears) {
            listState.animateScrollToItem(
                index = maxOf(0, index - 2)
            )
        }
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(totalYears) { index ->
            val year = startYear + index
            val isDisplayed = year == displayedYear
            Text(
                text = year.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isDisplayed) FontWeight.Bold else FontWeight.Normal,
                color = if (isDisplayed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    // 8dp above and below a 24dp line: a 40dp tap target.
                    .clickable { onYearClick(year) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

private val PAGE_PADDING = 8.dp
private val COLUMN_GAP = 4.dp
private val ROW_GAP = 8.dp
private val MONTH_PADDING = 4.dp
private val LABEL_GAP = 2.dp
/** Space above and below the weekday letters, on top of their line. */
private val WEEKDAY_PADDING = 3.dp
private val WEEKDAY_TEXT_SIZE = 7.sp
/** Padding above and below each day in a mini-month. */
private val DAY_ROW_PADDING = 1.dp
private val DOT_SIZE = 2.dp
private val MIN_CELL = 8.dp
private val MAX_CELL = 24.dp
/** Day number font size as a share of its cell: 8sp in a 14dp cell. */
private const val DAY_NUMBER_SCALE = 8f / 14f

/** How [YearPage] lays out a year: [columns] mini-months a row, day cells [cell] square. */
internal data class YearLayout(val columns: Int, val cell: Dp, val scrolls: Boolean)

/**
 * Returns the layout that shows all 12 months in [width] by [height] with the largest day cells:
 * 3, 4 or 6 columns, each cell as large as the width and height allow, at most [MAX_CELL] at the
 * normal font size. Each
 * month also holds its name, [labelHeight] tall, and the weekday letters, [weekdayHeight] tall.
 * When no choice reaches the smallest cell, the page scrolls, so only the width limits the cells:
 * the columns are the ones that give the widest cell, and the cell is at least the smallest one.
 *
 * At a font scale of 1.0 or less the smallest cell is [MIN_CELL]. With a larger font, day numbers
 * must not shrink: the smallest cell is the cell the same room gets at font scale 1.0 (laid out
 * with [baseLabelHeight] and [baseWeekdayHeight], the rows' heights at that scale) times the
 * font scale, capped by the widest cell the width allows, and the largest cell grows to it. So a
 * larger font scrolls the page instead of shrinking the days, keeping the most columns whose
 * cells reach that size, at that size; on a narrow phone at a very large font the width still
 * caps them.
 *
 * Sizes are counted in whole pixels as layout rounds each padding and size, so six rows of
 * rounded cells can't add up to more than the page.
 */
internal fun yearLayout(
    width: Dp,
    height: Dp,
    density: Density,
    labelHeight: Dp,
    weekdayHeight: Dp,
    baseLabelHeight: Dp = labelHeight,
    baseWeekdayHeight: Dp = weekdayHeight,
): YearLayout {
    if (density.fontScale <= 1f) {
        return fitYear(width, height, density, labelHeight, weekdayHeight, minCellPx = null)
    }
    val normal = Density(density.density, 1f)
    val base = fitYear(width, height, normal, baseLabelHeight, baseWeekdayHeight, minCellPx = null)
    // The cell back in whole pixels first, so an exact product (22px x 1.5) isn't rounded up.
    val basePx = with(normal) { base.cell.roundToPx() }
    val floorPx = ceil(basePx * density.fontScale - 0.001f).toInt()
    return fitYear(width, height, density, labelHeight, weekdayHeight, minCellPx = floorPx)
}

/**
 * Lays out the year for [yearLayout] with the smallest cell [minCellPx], capped by the widest
 * cell the width allows; null is [MIN_CELL].
 */
private fun fitYear(
    width: Dp,
    height: Dp,
    density: Density,
    labelHeight: Dp,
    weekdayHeight: Dp,
    minCellPx: Int?,
): YearLayout = with(density) {
    val dayRowExtra = DAY_ROW_PADDING.roundToPx() * 2 + DOT_SIZE.roundToPx()
    val monthOverhead = MONTH_PADDING.roundToPx() * 2 + labelHeight.roundToPx() +
        LABEL_GAP.roundToPx() + weekdayHeight.roundToPx()
    val cellByWidth = intArrayOf(3, 4, 6).associateWith { columns ->
        val monthWidth = (width.toPx() - PAGE_PADDING.roundToPx() * 2 -
            COLUMN_GAP.roundToPx() * (columns - 1)) / columns
        floor((monthWidth - MONTH_PADDING.roundToPx() * 2) / 7).toInt()
    }
    val minCell = if (minCellPx == null) MIN_CELL.roundToPx() else {
        maxOf(MIN_CELL.roundToPx(), minOf(minCellPx, cellByWidth.values.max()))
    }
    val maxCell = maxOf(MAX_CELL.roundToPx(), minCell)
    val fitted = cellByWidth.map { (columns, byWidth) ->
        val rows = 12 / columns
        val monthHeight = floor((height.toPx() - ROW_GAP.roundToPx() * (rows - 1)) / rows)
        val cellByHeight = floor((monthHeight - monthOverhead) / 6).toInt() - dayRowExtra
        columns to minOf(byWidth, cellByHeight, maxCell)
    }.maxBy { it.second }
    if (fitted.second >= minCell) {
        YearLayout(columns = fitted.first, cell = fitted.second.toDp(), scrolls = false)
    } else if (minCellPx != null) {
        // A larger font: the most columns whose cells reach the smallest cell, at that size.
        val columns = cellByWidth.filter { it.value >= minCell }.keys.max()
        YearLayout(columns = columns, cell = minCell.toDp(), scrolls = true)
    } else {
        val (columns, byWidth) = cellByWidth.maxBy { it.value }
        YearLayout(columns = columns, cell = minOf(maxOf(byWidth, minCell), maxCell).toDp(), scrolls = true)
    }
}

/** Lays out one year of [MiniMonth]s to fit the page ([yearLayout]). */
@Composable
private fun YearPage(
    year: Int,
    eventDots: ImmutableMap<String, ImmutableMap<Int, ImmutableList<Int>>>,
    firstDayOfWeek: Int,
    today: Calendar,
    onMonthClick: (year: Int, month: Int) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // Both rows take the height their text measures at the current font size, so month
        // names and weekday letters are never cut.
        val textMeasurer = rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
        val weekdayStyle = LocalTextStyle.current.copy(
            fontSize = WEEKDAY_TEXT_SIZE,
            lineHeight = WEEKDAY_TEXT_SIZE,
        )
        val labelHeight = with(density) {
            textMeasurer.measure("Ag", labelStyle, maxLines = 1).size.height.toDp()
        }
        val weekdayHeight = with(density) {
            textMeasurer.measure("Ag", weekdayStyle, maxLines = 1).size.height.toDp()
        } + WEEKDAY_PADDING * 2
        // The same rows at font scale 1.0, so a larger font never gets smaller days (yearLayout).
        val normalDensity = Density(density.density, 1f)
        val baseLabelHeight = with(density) {
            textMeasurer.measure("Ag", labelStyle, maxLines = 1, density = normalDensity).size.height.toDp()
        }
        val baseWeekdayHeight = with(density) {
            textMeasurer.measure("Ag", weekdayStyle, maxLines = 1, density = normalDensity).size.height.toDp()
        } + WEEKDAY_PADDING * 2
        val layout = remember(maxWidth, maxHeight, density, labelHeight, weekdayHeight, baseLabelHeight, baseWeekdayHeight) {
            yearLayout(maxWidth, maxHeight, density, labelHeight, weekdayHeight, baseLabelHeight, baseWeekdayHeight)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (layout.scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier
                )
                .padding(horizontal = PAGE_PADDING),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP)
        ) {
            for (row in 0 until 12 / layout.columns) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)
                ) {
                    for (col in 0 until layout.columns) {
                        val month = row * layout.columns + col // 0-based, like Calendar.MONTH
                        Box(modifier = Modifier.weight(1f)) {
                            MiniMonth(
                                year = year,
                                month = month,
                                eventDots = eventDots,
                                firstDayOfWeek = firstDayOfWeek,
                                today = today,
                                cellSize = layout.cell,
                                labelHeight = labelHeight,
                                weekdayHeight = weekdayHeight,
                                onClick = { onMonthClick(year, month) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Draws one compact month grid. It always has 6 rows ([MonthGrid.compute]) and no
 * animateContentSize, so every mini-month in the year has the same height.
 */
@Composable
private fun MiniMonth(
    year: Int,
    month: Int,
    eventDots: ImmutableMap<String, ImmutableMap<Int, ImmutableList<Int>>>,
    firstDayOfWeek: Int,
    today: Calendar,
    cellSize: Dp,
    labelHeight: Dp,
    weekdayHeight: Dp,
    onClick: () -> Unit
) {
    val monthGrid = remember(year, month, firstDayOfWeek) {
        MonthGrid.compute(year, month, firstDayOfWeek)
    }
    val monthKey = remember(year, month) { String.format(java.util.Locale.ROOT, "%04d-%02d", year, month + 1) }
    val monthDots = remember(eventDots, monthKey) { eventDots[monthKey].orEmpty() }

    val isCurrentMonth = year == today.get(Calendar.YEAR) && month == today.get(Calendar.MONTH)
    val todayDay = today.get(Calendar.DAY_OF_MONTH)
    val todayYear = today.get(Calendar.YEAR)
    val todayMonth = today.get(Calendar.MONTH)

    val monthNames = remember { getLocalizedMonthNames() }
    val monthLabel = monthNames[month]
    val accessibilityLabel = "${monthNames[month]} $year"

    val dayHeaders = remember(firstDayOfWeek) {
        getDayOfWeekHeaders(firstDayOfWeek)
    }

    Column(
        modifier = Modifier
            .semantics { contentDescription = accessibilityLabel }
            .clickable(onClick = onClick)
            .padding(MONTH_PADDING)
    ) {
        Text(
            text = monthLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (isCurrentMonth) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .height(labelHeight)
        )
        Spacer(modifier = Modifier.height(LABEL_GAP))

        Row(
            modifier = Modifier.fillMaxWidth().height(weekdayHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            dayHeaders.forEach { letter ->
                Text(
                    text = letter,
                    fontSize = WEEKDAY_TEXT_SIZE,
                    lineHeight = WEEKDAY_TEXT_SIZE,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        monthGrid.weeks.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { cell ->
                    MiniDayCell(
                        cell = cell,
                        isToday = cell.position == MonthGrid.DayPosition.MonthDate &&
                            cell.dayOfMonth == todayDay &&
                            year == todayYear && month == todayMonth,
                        dotColor = if (cell.position == MonthGrid.DayPosition.MonthDate)
                            monthDots[cell.dayOfMonth]?.firstOrNull() else null,
                        size = cellSize,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Draws one day of a mini-month in a [size] square, the number sized to the square so it fits at
 * any font scale. [yearLayout] keeps the square from shrinking at a larger font, so the number
 * grows with the font size. No aspectRatio: the row's weight already gives each day its width.
 */
@Composable
private fun MiniDayCell(
    cell: MonthGrid.DayCell,
    isToday: Boolean,
    dotColor: Int?,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val fontSize = with(LocalDensity.current) { (size * DAY_NUMBER_SCALE).toSp() }
    Column(
        modifier = modifier.padding(vertical = DAY_ROW_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = if (isToday) {
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    // The tonal fill alone can wash out against the surface for pale
                    // accent seeds; a hairline outline keeps the today ring visible.
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            } else {
                Modifier.size(size)
            }
        ) {
            Text(
                text = cell.dayOfMonth.toString(),
                fontSize = fontSize,
                lineHeight = fontSize,
                maxLines = 1,
                textAlign = TextAlign.Center,
                color = when {
                    isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                    cell.position == MonthGrid.DayPosition.MonthDate ->
                        MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                }
            )
        }

        // The Spacer keeps the row height when the day has no event dot.
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(DOT_SIZE)
                    .clip(CircleShape)
                    .background(Color(dotColor))
            )
        } else {
            Spacer(modifier = Modifier.height(DOT_SIZE))
        }
    }
}

/**
 * Returns narrow day-of-week names in display order, starting at [firstDayOfWeek] (a Calendar
 * constant; 0 means the locale's first day).
 */
private fun getDayOfWeekHeaders(firstDayOfWeek: Int): List<String> {
    val resolved = if (firstDayOfWeek == 0) {
        Calendar.getInstance().firstDayOfWeek
    } else {
        firstDayOfWeek
    }
    // Map Calendar constants (1=Sun..7=Sat) to DayOfWeek
    val startDow = when (resolved) {
        Calendar.SUNDAY -> DayOfWeek.SUNDAY
        Calendar.MONDAY -> DayOfWeek.MONDAY
        Calendar.TUESDAY -> DayOfWeek.TUESDAY
        Calendar.WEDNESDAY -> DayOfWeek.WEDNESDAY
        Calendar.THURSDAY -> DayOfWeek.THURSDAY
        Calendar.FRIDAY -> DayOfWeek.FRIDAY
        Calendar.SATURDAY -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }
    return (0..6).map { offset ->
        val dow = DayOfWeek.of(((startDow.value - 1 + offset) % 7) + 1)
        dow.getDisplayName(TextStyle.NARROW, Locale.getDefault())
    }
}
