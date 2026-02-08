package org.onekash.kashcal.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.ui.viewmodels.ViewMode

/**
 * View option with display label, description, and icon.
 */
private data class ViewOption(
    val mode: ViewMode,
    val label: String,
    val description: String,
    val icon: ImageVector
)

private val viewOptions = listOf(
    ViewOption(
        mode = ViewMode.MONTH,
        label = "Month",
        description = "Calendar grid with day events",
        icon = Icons.Default.CalendarMonth
    ),
    ViewOption(
        mode = ViewMode.AGENDA,
        label = "Agenda",
        description = "Upcoming events list (30 days)",
        icon = Icons.Default.ViewAgenda
    ),
    ViewOption(
        mode = ViewMode.THREE_DAYS,
        label = "3 Days",
        description = "Scrollable 3-day time grid",
        icon = Icons.Default.ViewWeek
    )
)

/**
 * Bottom sheet for selecting calendar view mode.
 *
 * - Tap selects view and dismisses sheet
 * - Long-press sets as default view (haptic + snackbar via callback)
 * - Radio button marks active view, star marks default view
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewPickerSheet(
    sheetState: SheetState,
    currentView: ViewMode,
    defaultView: ViewMode,
    onViewSelect: (ViewMode) -> Unit,
    onSetDefault: (ViewMode) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Options
            viewOptions.forEach { option ->
                ViewPickerOptionRow(
                    option = option,
                    isActive = currentView == option.mode,
                    isDefault = defaultView == option.mode,
                    onSelect = {
                        onViewSelect(option.mode)
                        onDismiss()
                    },
                    onSetDefault = {
                        onSetDefault(option.mode)
                    }
                )
            }

            // Persistent hint for long-press
            Text(
                text = "Long-press to set as default",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ViewPickerOptionRow(
    option: ViewOption,
    isActive: Boolean,
    isDefault: Boolean,
    onSelect: () -> Unit,
    onSetDefault: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onSelect,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSetDefault()
                },
                onLongClickLabel = "Set as default view"
            )
            .semantics {
                onLongClick(label = "Set as default view") { true }
            }
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isActive,
            onClick = null // Row handles click
        )

        Spacer(modifier = Modifier.width(8.dp))

        Icon(
            imageVector = option.icon,
            contentDescription = null,
            tint = if (isActive) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (isDefault) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Default view",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                text = option.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
