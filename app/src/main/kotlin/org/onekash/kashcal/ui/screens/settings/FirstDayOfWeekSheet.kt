package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.util.DateTimeUtils
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

/**
 * First day of week option with value and label.
 */
private data class FirstDayOption(
    val value: Int,
    val label: String
)

/**
 * Bottom sheet for selecting first day of week preference.
 *
 * Shows four options:
 * - System default: Follows device locale (shows resolved day in label)
 * - Sunday: Week starts on Sunday
 * - Monday: Week starts on Monday
 * - Saturday: Week starts on Saturday
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirstDayOfWeekSheet(
    sheetState: SheetState,
    currentValue: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // Get locale's default first day for "System default" description
    // NOTE: Not wrapped in remember{} to respond to system locale changes (per CLAUDE.md pattern)
    val localeFirstDay = DateTimeUtils.getLocaleFirstDayOfWeek()
    val localeFirstDayName = localeFirstDay.getDisplayName(TextStyle.FULL, Locale.getDefault())

    // Build options with dynamic locale label
    val options = remember(localeFirstDayName) {
        listOf(
            FirstDayOption(
                value = KashCalDataStore.FIRST_DAY_SYSTEM,
                label = "System default ($localeFirstDayName)"
            ),
            FirstDayOption(
                value = Calendar.SUNDAY,
                label = "Sunday"
            ),
            FirstDayOption(
                value = Calendar.MONDAY,
                label = "Monday"
            ),
            FirstDayOption(
                value = Calendar.SATURDAY,
                label = "Saturday"
            )
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                text = "Start Week On",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Options
            options.forEach { option ->
                FirstDayOptionRow(
                    option = option,
                    isSelected = currentValue == option.value,
                    onSelect = {
                        onSelect(option.value)
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun FirstDayOptionRow(
    option: FirstDayOption,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = option.label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
