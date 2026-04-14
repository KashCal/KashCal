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
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.ui.viewmodels.ViewMode

private data class ViewOption(
    val mode: ViewMode,
    val label: String,
    val icon: ImageVector
)

private val viewOptions = listOf(
    ViewOption(ViewMode.MONTH, "Month", Icons.Default.CalendarMonth),
    ViewOption(ViewMode.AGENDA, "Agenda", Icons.Default.ViewAgenda),
    ViewOption(ViewMode.MONTH_FULL, "Month (Full)", Icons.Default.GridView),
    ViewOption(ViewMode.THREE_DAYS, "3 Days", Icons.Default.ViewWeek),
    ViewOption(ViewMode.WEEK, "Week", Icons.Default.DateRange),
    ViewOption(ViewMode.YEAR, "Year", Icons.Default.CalendarViewMonth)
)

private fun iconForMode(mode: ViewMode): ImageVector =
    viewOptions.first { it.mode == mode }.icon

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
                    contentDescription = "Calendar view",
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
                    text = { Text(option.label) },
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
                                contentDescription = "Active view",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null
                )
            }
        }
    }
}
