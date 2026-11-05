package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import org.onekash.kashcal.ui.model.MonthGrid
import org.onekash.kashcal.R

/** Offer only shortened grids; the complete month has one unambiguous option (0). */
internal fun monthWidgetWeekChoices(month: YearMonth, firstDayOfWeek: Int): List<Int> {
    val grid = MonthGrid.compute(month.year, month.monthValue - 1, firstDayOfWeek)
    val monthRows = grid.weeks.count { row -> row.any { it.position == MonthGrid.DayPosition.MonthDate } }
    return listOf(0) + (1 until monthRows)
}

@Composable
fun monthWidgetWeeksLabel(weeks: Int, firstDayOfWeek: Int = 0): String =
    if (weeks !in monthWidgetWeekChoices(YearMonth.now(), firstDayOfWeek).drop(1)) {
        stringResource(R.string.month_widget_weeks_auto)
    } else pluralStringResource(R.plurals.month_widget_weeks_count, weeks, weeks)

/** Same one-tap, highlighted-checkmark menu as the widget event-limit picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthWidgetWeeksSheet(
    currentWeeks: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    firstDayOfWeek: Int = 0,
    month: YearMonth = YearMonth.now(),
) {
    val choices = monthWidgetWeekChoices(month, firstDayOfWeek)
    val selected = currentWeeks.takeIf { it in choices } ?: 0
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp).selectableGroup()) {
            Text(stringResource(R.string.settings_month_widget_weeks),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp))
            Text(stringResource(R.string.month_widget_weeks_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            choices.forEach { weeks ->
                OptionRow(
                    label = if (weeks == 0) stringResource(R.string.month_widget_weeks_auto)
                    else pluralStringResource(R.plurals.month_widget_weeks_count, weeks, weeks),
                    isSelected = selected == weeks,
                    onSelect = { onSelect(weeks); onDismiss() },
                )
            }
        }
    }
}
