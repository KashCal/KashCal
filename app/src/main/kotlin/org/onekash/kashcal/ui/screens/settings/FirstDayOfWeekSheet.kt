package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.util.DateTimeUtils
import java.time.format.TextStyle
import java.util.Calendar

/** Pairs a first-day-of-week preference value with its label. */
private data class FirstDayOption(
    val value: Int,
    val label: String
)

/**
 * Shows the first-day-of-week picker: System default, which follows the locale and names the
 * resolved day in its label, then Sunday, Monday and Saturday. Tapping one calls [onSelect],
 * then [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirstDayOfWeekSheet(
    sheetState: SheetState,
    currentValue: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // The locale's first day, for the System default label. Reading LocalLocale.current
    // recomposes on a locale change, which re-runs getLocaleFirstDayOfWeek() against the new
    // Locale.getDefault().
    val locale = LocalLocale.current.platformLocale
    val localeFirstDay = DateTimeUtils.getLocaleFirstDayOfWeek()
    val localeFirstDayName = localeFirstDay.getDisplayName(TextStyle.FULL, locale)

    // Resolved outside remember, which can't call stringResource.
    val labelSystemDefault = stringResource(R.string.settings_system_default_with_value, localeFirstDayName ?: "")
    val labelSunday = stringResource(R.string.option_sunday)
    val labelMonday = stringResource(R.string.option_monday)
    val labelSaturday = stringResource(R.string.option_saturday)

    val options = remember(localeFirstDayName, labelSystemDefault, labelSunday, labelMonday, labelSaturday) {
        listOf(
            FirstDayOption(
                value = KashCalDataStore.FIRST_DAY_SYSTEM,
                label = labelSystemDefault
            ),
            FirstDayOption(
                value = Calendar.SUNDAY,
                label = labelSunday
            ),
            FirstDayOption(
                value = Calendar.MONDAY,
                label = labelMonday
            ),
            FirstDayOption(
                value = Calendar.SATURDAY,
                label = labelSaturday
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
                .selectableGroup()
        ) {
            Text(
                text = stringResource(R.string.settings_start_week_on),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

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
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onSelect)
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
                // Decorative: the row's radio-button selected state already announces selection.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
