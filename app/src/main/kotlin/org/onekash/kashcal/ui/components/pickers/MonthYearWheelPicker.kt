package org.onekash.kashcal.ui.components.pickers

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.VerticalWheelPicker
import java.text.DateFormatSymbols

/**
 * Shows side-by-side circular month and year wheels. [InlineDatePickerContent] swaps it in for
 * the calendar grid when the month/year header is tapped; [YearOverlay] also uses it.
 *
 * @param selectedMonth 0-based (`Calendar.JANUARY` = 0); anything outside 0..11 throws
 * @param onMonthYearSelected called when either wheel settles on a new value
 * @param visibleItems items shown per wheel; should be odd
 * @param itemHeight 44dp times 5 visible items fills the date picker's 220dp grid box
 */
@Composable
fun MonthYearWheelPicker(
    selectedYear: Int,
    selectedMonth: Int,
    onMonthYearSelected: (year: Int, month: Int) -> Unit,
    modifier: Modifier = Modifier,
    yearRange: IntRange = 1900..2200,
    visibleItems: Int = 5,
    itemHeight: Dp = 44.dp
) {
    require(yearRange.first <= yearRange.last) { "yearRange must not be empty" }
    require(selectedMonth in 0..11) { "selectedMonth must be 0..11, was $selectedMonth" }

    val monthNames = remember { getLocalizedMonthNames() }
    val yearList = remember(yearRange) { yearRange.toList() }

    var currentMonth by remember(selectedMonth) { mutableIntStateOf(selectedMonth) }
    var currentYear by remember(selectedYear) { mutableIntStateOf(selectedYear) }

    val cdMonthYearPicker = stringResource(R.string.cd_month_year_picker)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = cdMonthYearPicker },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Circular: the 12 months are cyclic.
        VerticalWheelPicker(
            items = monthNames,
            selectedItem = monthIndexToName(currentMonth, monthNames),
            onItemSelected = { name ->
                val newMonth = monthNames.indexOf(name).coerceAtLeast(0)
                if (newMonth != currentMonth) {
                    currentMonth = newMonth
                    onMonthYearSelected(currentYear, currentMonth)
                }
            },
            modifier = Modifier.weight(0.55f),
            visibleItems = visibleItems,
            itemHeight = itemHeight,
            isCircular = true
        ) { month, isSelected ->
            Text(
                text = month,
                fontSize = if (isSelected) 18.sp else 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }

        // Circular too: with the default range the wrap point is more than a century from any
        // current year (126 years back, 174 ahead from 2026).
        VerticalWheelPicker(
            items = yearList,
            selectedItem = currentYear.coerceIn(yearRange),
            onItemSelected = { year ->
                if (year != currentYear) {
                    currentYear = year
                    onMonthYearSelected(currentYear, currentMonth)
                }
            },
            modifier = Modifier.weight(0.45f),
            visibleItems = visibleItems,
            itemHeight = itemHeight,
            isCircular = true
        ) { year, isSelected ->
            Text(
                text = year.toString(),
                fontSize = if (isSelected) 18.sp else 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Returns the default locale's month names, dropping the empty 13th entry that
 * [DateFormatSymbols.getMonths] can include.
 */
internal fun getLocalizedMonthNames(): List<String> =
    DateFormatSymbols.getInstance().months.filter { it.isNotBlank() }

/** Returns the name for a 0-based month index, clamping an out-of-range index into [names]. */
internal fun monthIndexToName(index: Int, names: List<String>): String =
    names.getOrElse(index.coerceIn(0, names.lastIndex)) { names.first() }

/** Returns [year]'s index within [range], clamped to the range. */
internal fun yearToIndex(year: Int, range: IntRange): Int =
    (year - range.first).coerceIn(0, range.last - range.first)

@Preview(showBackground = true, name = "MonthYearWheelPicker - Light")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "MonthYearWheelPicker - Dark")
@Composable
private fun MonthYearWheelPickerPreview() {
    MaterialTheme {
        MonthYearWheelPicker(
            selectedYear = 2025,
            selectedMonth = 1, // February
            onMonthYearSelected = { _, _ -> }
        )
    }
}
