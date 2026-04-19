package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarViewMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.viewmodels.ViewMode

internal data class ViewOption(
    val mode: ViewMode,
    val icon: ImageVector
)

internal val viewOptions = listOf(
    ViewOption(ViewMode.MONTH, Icons.Default.CalendarMonth),
    ViewOption(ViewMode.AGENDA, Icons.Default.ViewAgenda),
    ViewOption(ViewMode.MONTH_FULL, Icons.Default.GridView),
    ViewOption(ViewMode.THREE_DAYS, Icons.Default.ViewWeek),
    ViewOption(ViewMode.WEEK, Icons.Default.DateRange),
    ViewOption(ViewMode.YEAR, Icons.Default.CalendarViewMonth)
)

internal fun iconForMode(mode: ViewMode): ImageVector =
    viewOptions.first { it.mode == mode }.icon

@Composable
internal fun viewModeLabel(mode: ViewMode): String = when (mode) {
    ViewMode.MONTH -> stringResource(R.string.view_month)
    ViewMode.AGENDA -> stringResource(R.string.view_agenda)
    ViewMode.MONTH_FULL -> stringResource(R.string.view_month_full)
    ViewMode.THREE_DAYS -> stringResource(R.string.view_three_days)
    ViewMode.WEEK -> stringResource(R.string.view_week)
    ViewMode.YEAR -> stringResource(R.string.view_year)
}

/**
 * View mode button with dropdown menu.
 *
 * Shows the current view's icon with a small dropdown chevron.
 * Tap opens a dropdown menu to switch views.
 */
@Composable
fun ViewPickerButton(
    currentView: ViewMode,
    onViewSelect: (ViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = iconForMode(currentView),
                    contentDescription = stringResource(R.string.cd_calendar_view),
                    modifier = Modifier.size(24.dp)
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(12.dp)
                        .align(Alignment.BottomEnd)
                        .offset(x = 4.dp, y = 4.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            viewOptions.forEach { option ->
                val isActive = currentView == option.mode
                DropdownMenuItem(
                    text = { Text(viewModeLabel(option.mode)) },
                    onClick = {
                        expanded = false
                        onViewSelect(option.mode)
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = option.icon,
                            contentDescription = null,
                            tint = if (isActive) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = if (isActive) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.cd_active_view),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null
                )
            }
        }
    }
}
