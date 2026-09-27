package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.pickers.MonthYearWheelPicker

/**
 * Shows a month and year wheel picker sheet for jumping to a month. The wheels only move the
 * pick; "Done" reports it through [onMonthSelected].
 *
 * @param currentYear the year the calendar is showing.
 * @param currentMonth the month the calendar is showing, 0-based (January = 0).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YearOverlay(
    visible: Boolean,
    currentYear: Int,
    currentMonth: Int,
    onMonthSelected: (year: Int, month: Int) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var pickedYear by remember(currentYear) { mutableIntStateOf(currentYear) }
    var pickedMonth by remember(currentMonth) { mutableIntStateOf(currentMonth) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val monthYearPickerLabel = stringResource(R.string.cd_month_year_picker)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .semantics { contentDescription = monthYearPickerLabel },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.label_go_to_month),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            MonthYearWheelPicker(
                selectedYear = currentYear,
                selectedMonth = currentMonth,
                onMonthYearSelected = { year, month ->
                    pickedYear = year
                    pickedMonth = month
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { onMonthSelected(pickedYear, pickedMonth) }
            ) {
                Text(stringResource(R.string.action_done))
            }
        }
    }
}
