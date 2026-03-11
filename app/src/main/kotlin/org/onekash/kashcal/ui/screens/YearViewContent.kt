package org.onekash.kashcal.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

private const val YEAR_PAGER_TOTAL = 200
private const val YEAR_PAGER_CENTER = 100

private val MONTH_NAMES_SHORT = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
)

/**
 * Year view: 12 mini-months in 4x3 grid with horizontal paging by year.
 */
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

    // Sync pager → year changes
    val displayedYear by remember {
        derivedStateOf {
            val page = pagerState.currentPage
            currentYear + (page - YEAR_PAGER_CENTER)
        }
    }

    // Notify ViewModel of year changes for dots loading
    LaunchedEffect(Unit) {
        snapshotFlow { pagerState.currentPage }
            .collect { page ->
                val year = currentYear + (page - YEAR_PAGER_CENTER)
                onYearChanged(year)
            }
    }

    // Handle navigate to today
    LaunchedEffect(pendingNavigateToToday) {
        if (pendingNavigateToToday) {
            pagerState.animateScrollToPage(YEAR_PAGER_CENTER)
            onNavigateToTodayConsumed()
        }
    }

    // Back press → MONTH view
    BackHandler { onBackToMonth() }

    Column(modifier = modifier.fillMaxSize()) {
        // Year header strip
        YearHeaderStrip(
            displayedYear = displayedYear,
            startYear = currentYear - YEAR_PAGER_CENTER,
            totalYears = YEAR_PAGER_TOTAL,
            onYearClick = { year ->
                val page = YEAR_PAGER_CENTER + (year - currentYear)
                scope.launch { pagerState.animateScrollToPage(page) }
            }
        )

        // Year pager
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

/**
 * Scrollable row of year numbers synced with the pager.
 */
@Composable
private fun YearHeaderStrip(
    displayedYear: Int,
    startYear: Int,
    totalYears: Int,
    onYearClick: (Int) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll header to keep displayed year visible
    LaunchedEffect(displayedYear) {
        val index = displayedYear - startYear
        if (index in 0 until totalYears) {
            // Scroll to center the year in the strip
            listState.animateScrollToItem(
                index = maxOf(0, index - 2)
            )
        }
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
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
                    .clickable { onYearClick(year) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * Single year page: 4 rows x 3 columns of MiniMonth.
 */
@Composable
private fun YearPage(
    year: Int,
    eventDots: ImmutableMap<String, ImmutableMap<Int, ImmutableList<Int>>>,
    firstDayOfWeek: Int,
    today: Calendar,
    onMonthClick: (year: Int, month: Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (row in 0..3) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (col in 0..2) {
                    val month = row * 3 + col // 0-indexed
                    Box(modifier = Modifier.weight(1f)) {
                        MiniMonth(
                            year = year,
                            month = month,
                            eventDots = eventDots,
                            firstDayOfWeek = firstDayOfWeek,
                            today = today,
                            onClick = { onMonthClick(year, month) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Mini-month: compact calendar grid for year overview.
 * Fixed 6 rows (no animateContentSize) for uniform grid height.
 */
@Composable
private fun MiniMonth(
    year: Int,
    month: Int,
    eventDots: ImmutableMap<String, ImmutableMap<Int, ImmutableList<Int>>>,
    firstDayOfWeek: Int,
    today: Calendar,
    onClick: () -> Unit
) {
    val monthGrid = remember(year, month, firstDayOfWeek) {
        MonthGrid.compute(year, month, firstDayOfWeek)
    }
    val monthKey = remember(year, month) { String.format("%04d-%02d", year, month + 1) }
    val monthDots = remember(eventDots, monthKey) { eventDots[monthKey] ?: emptyMap() }

    val isCurrentMonth = year == today.get(Calendar.YEAR) && month == today.get(Calendar.MONTH)
    val todayDay = today.get(Calendar.DAY_OF_MONTH)
    val todayYear = today.get(Calendar.YEAR)
    val todayMonth = today.get(Calendar.MONTH)

    val monthLabel = MONTH_NAMES_SHORT[month]
    val accessibilityLabel = "${MONTH_NAMES_SHORT[month]} $year"

    // Day-of-week header letters
    val dayHeaders = remember(firstDayOfWeek) {
        getDayOfWeekHeaders(firstDayOfWeek)
    }

    Column(
        modifier = Modifier
            .semantics { contentDescription = accessibilityLabel }
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        // Month name header
        Text(
            text = monthLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (isCurrentMonth) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp)
        )

        // Day-of-week headers
        Row(modifier = Modifier.fillMaxWidth()) {
            dayHeaders.forEach { letter ->
                Text(
                    text = letter,
                    fontSize = 7.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 6-row grid (always all 6 rows — uniform height)
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
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Single day cell in a mini-month.
 * No aspectRatio — cells are narrow (~16dp) so square constraint clips content.
 */
@Composable
private fun MiniDayCell(
    cell: MonthGrid.DayCell,
    isToday: Boolean,
    dotColor: Int?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = if (isToday) {
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            } else {
                Modifier.size(14.dp)
            }
        ) {
            Text(
                text = cell.dayOfMonth.toString(),
                fontSize = 8.sp,
                lineHeight = 8.sp,
                textAlign = TextAlign.Center,
                color = when {
                    isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                    cell.position == MonthGrid.DayPosition.MonthDate ->
                        MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                }
            )
        }

        // Event dot
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(2.dp)
                    .clip(CircleShape)
                    .background(Color(dotColor))
            )
        } else {
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}

/**
 * Get single-letter day-of-week headers respecting firstDayOfWeek.
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
