package org.onekash.kashcal.ui.screens.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.FreeBreakfast
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.insights.AnalysisPeriod
import org.onekash.kashcal.domain.insights.CalendarHours
import org.onekash.kashcal.domain.insights.DayHours
import org.onekash.kashcal.domain.insights.Insight
import org.onekash.kashcal.domain.insights.InsightIcon
import org.onekash.kashcal.domain.insights.InsightsRepository
import org.onekash.kashcal.domain.insights.PeriodStats
import org.onekash.kashcal.ui.util.DayPagerUtils
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun InsightsScreen(
    modifier: Modifier = Modifier,
    viewModel: InsightsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        PeriodSelector(
            selected = uiState.period,
            onSelect = viewModel::onPeriodChange
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.stats.totalMinutes == 0L && uiState.stats.allDayCount == 0 && !uiState.isLoading) {
            EmptyState()
        } else {
            HeadlineStat(stats = uiState.stats, deltaText = uiState.deltaText)

            if (uiState.stats.calendarBreakdown.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                CalendarBreakdownBar(breakdown = uiState.stats.calendarBreakdown)
                Spacer(modifier = Modifier.height(8.dp))
                CalendarLegend(breakdown = uiState.stats.calendarBreakdown)
            }

            if (uiState.stats.dailyBreakdown.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                DailyDistributionChart(
                    days = uiState.stats.dailyBreakdown,
                    period = uiState.period
                )
            }

            if (uiState.insights.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                uiState.insights.forEach { insight ->
                    InsightCard(insight = insight)
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSelector(
    selected: AnalysisPeriod,
    onSelect: (AnalysisPeriod) -> Unit
) {
    val periods = listOf(
        AnalysisPeriod.THIS_WEEK to stringResource(R.string.insights_period_this_week),
        AnalysisPeriod.LAST_WEEK to stringResource(R.string.insights_period_last_week),
        AnalysisPeriod.THIS_MONTH to stringResource(R.string.insights_period_this_month)
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        periods.forEachIndexed { index, (period, label) ->
            SegmentedButton(
                selected = selected == period,
                onClick = { onSelect(period) },
                shape = SegmentedButtonDefaults.itemShape(index, periods.size),
                icon = {}
            ) {
                Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun HeadlineStat(stats: PeriodStats, deltaText: String?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = InsightsRepository.formatMinutesShort(stats.totalMinutes),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold
        )

        if (stats.allDayCount > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.insights_all_day_count,
                    stats.allDayCount,
                    stats.allDayCount
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (deltaText != null) {
            val deltaColor = if (deltaText.startsWith("+"))
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.error
            Text(
                text = deltaText,
                style = MaterialTheme.typography.bodyMedium,
                color = deltaColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun CalendarBreakdownBar(breakdown: List<CalendarHours>) {
    val totalMinutes = breakdown.sumOf { it.minutes }.toFloat()
    if (totalMinutes == 0f) return

    val legendText = breakdown.joinToString(", ") {
        "${it.calendarName} ${InsightsRepository.formatMinutesShort(it.minutes)}"
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .semantics { contentDescription = legendText }
    ) {
        var xOffset = 0f
        breakdown.forEach { cal ->
            val width = (cal.minutes / totalMinutes) * size.width
            drawRoundRect(
                color = Color(cal.color),
                topLeft = Offset(xOffset, 0f),
                size = Size(width, size.height),
                cornerRadius = CornerRadius(0f)
            )
            xOffset += width
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarLegend(breakdown: List<CalendarHours>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        breakdown.forEach { cal ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(cal.color))
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${cal.calendarName} ${InsightsRepository.formatMinutesShort(cal.minutes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DailyDistributionChart(days: List<DayHours>, period: AnalysisPeriod) {
    val maxMinutes = days.maxOfOrNull { it.minutes } ?: return
    if (maxMinutes == 0L) return

    val busiestDayName = days.maxByOrNull { it.minutes }?.let { dayHoursToDayName(it.dayCode) } ?: ""
    val chartDescription = stringResource(R.string.insights_cd_daily_chart, busiestDayName)

    if (period == AnalysisPeriod.THIS_MONTH) {
        MonthDailyChart(days = days, maxMinutes = maxMinutes, chartDescription = chartDescription)
    } else {
        WeekDailyChart(days = days, maxMinutes = maxMinutes, chartDescription = chartDescription)
    }
}

@Composable
private fun WeekDailyChart(days: List<DayHours>, maxMinutes: Long, chartDescription: String) {
    val barColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = chartDescription }
    ) {
        days.forEach { day ->
            val fraction = if (maxMinutes > 0) day.minutes.toFloat() / maxMinutes else 0f
            val dayName = dayHoursToDayName(day.dayCode)
            val hours = InsightsRepository.formatMinutesShort(day.minutes)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dayName,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(36.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(16.dp)
                ) {
                    if (fraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(barColor)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = hours,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(40.dp),
                    textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MonthDailyChart(days: List<DayHours>, maxMinutes: Long, chartDescription: String) {
    val barColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    val weeks = days.chunked(7)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = chartDescription }
    ) {
        weeks.forEachIndexed { weekIdx, weekDays ->
            Text(
                text = "W${weekIdx + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = if (weekIdx > 0) 8.dp else 0.dp, bottom = 2.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                weekDays.forEach { day ->
                    val fraction = if (maxMinutes > 0) day.minutes.toFloat() / maxMinutes else 0f
                    val height = (4 + fraction * 28).dp

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (!day.isInMonth) 2.dp else height)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (!day.isInMonth) emptyColor
                                    else if (day.minutes == 0L) emptyColor.copy(alpha = 0.5f)
                                    else barColor
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightCard(insight: Insight) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = insightIconToVector(insight.icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = insight.text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "0h",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.insights_empty_state),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun insightIconToVector(icon: InsightIcon): ImageVector = when (icon) {
    InsightIcon.CHART_BAR -> Icons.Default.BarChart
    InsightIcon.CHART_LOW -> Icons.AutoMirrored.Filled.TrendingDown
    InsightIcon.FREE_TIME -> Icons.Default.FreeBreakfast
    InsightIcon.WEEKEND -> Icons.Default.WbSunny
    InsightIcon.LINK -> Icons.Default.Link
    InsightIcon.DOMINANT -> Icons.Default.PieChart
    InsightIcon.SCHEDULE_BOUNDS -> Icons.Default.Schedule
    InsightIcon.FREE_DAY -> Icons.Default.EventBusy
    InsightIcon.TOMORROW -> Icons.Default.CalendarToday
    InsightIcon.HEAVY -> Icons.AutoMirrored.Filled.TrendingUp
    InsightIcon.NEXT_FREE -> Icons.Default.Star
}

private fun dayHoursToDayName(dayCode: Int): String {
    val date = DayPagerUtils.dayCodeToLocalDate(dayCode)
    return date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
}
