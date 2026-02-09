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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.ui.components.pickers.MonthYearWheelPicker

/**
 * Year overlay - month/year wheel picker modal for quick navigation.
 * Tap month header (e.g., "January 2025") to open, scroll wheels to pick
 * month/year, then tap "Done" to navigate.
 *
 * @param visible Whether the overlay is visible
 * @param currentYear The year currently being viewed in the calendar
 * @param currentMonth The month currently being viewed (0-indexed, January = 0)
 * @param onMonthSelected Callback when a month is confirmed (year, month)
 * @param onDismiss Callback when the overlay should be dismissed
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

    // Track wheel selection internally — only fire onMonthSelected on "Done"
    var pickedYear by remember(currentYear) { mutableIntStateOf(currentYear) }
    var pickedMonth by remember(currentMonth) { mutableIntStateOf(currentMonth) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .semantics { contentDescription = "Month and year picker" },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                text = "Go to month",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Month and year wheel pickers — update internal state only
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
                Text("Done")
            }
        }
    }
}
