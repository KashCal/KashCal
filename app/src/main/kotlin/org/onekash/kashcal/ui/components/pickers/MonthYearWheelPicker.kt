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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onekash.kashcal.ui.components.VerticalWheelPicker
import java.text.DateFormatSymbols

/**
 * Two side-by-side non-circular wheel pickers for month and year selection.
 * Used as an inline alternative to the calendar grid when the user taps the
 * month/year header in [InlineDatePickerContent].
 *
 * @param selectedYear Current year (e.g. 2025)
 * @param selectedMonth Current month, 0-based (Calendar.JANUARY = 0)
 * @param onMonthYearSelected Called when either wheel settles on a new value
 * @param yearRange Range of years to display (default 1900..2200)
 * @param visibleItems Number of visible items per wheel (should be odd)
 * @param itemHeight Height of each item — 44dp fills the 220dp container exactly (5 × 44)
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = "Month and year picker" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Month wheel (circular — 12 months is naturally cyclic)
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

        // Year wheel (circular — wrap point is 150+ years from any reasonable
        // selection, so users won't encounter it in practice)
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
 * Returns locale-aware month names, filtering out the 13th empty string
 * that some locales include in [DateFormatSymbols.getMonths].
 */
internal fun getLocalizedMonthNames(): List<String> =
    DateFormatSymbols.getInstance().months.filter { it.isNotBlank() }

/**
 * Maps a 0-based month index to the corresponding localized name.
 */
internal fun monthIndexToName(index: Int, names: List<String>): String =
    names.getOrElse(index.coerceIn(0, names.lastIndex)) { names.first() }

/**
 * Maps a year to its index within the given range.
 */
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
