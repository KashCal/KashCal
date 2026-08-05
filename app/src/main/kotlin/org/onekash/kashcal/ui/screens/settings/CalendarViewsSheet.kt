package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.viewModeLabel
import org.onekash.kashcal.ui.components.viewOptions

/**
 * Bottom sheet for choosing which calendar views appear in the navigation
 * drawer's view picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarViewsSheet(
    sheetState: SheetState,
    enabledViews: Set<String>,
    onToggle: (viewKey: String, enabled: Boolean) -> Unit,
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
            Text(
                text = stringResource(R.string.settings_visible_views),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )
            Text(
                text = stringResource(R.string.settings_visible_views_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            HorizontalDivider(
                modifier = Modifier.padding(top = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            viewOptions.forEach { option ->
                val isEnabled = option.mode.key in enabledViews
                // Locked, so the picker can't be emptied.
                val isLastEnabled = isEnabled && enabledViews.size <= 1

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isLastEnabled) {
                            onToggle(option.mode.key, !isEnabled)
                        }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = option.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = viewModeLabel(option.mode),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { onToggle(option.mode.key, it) },
                        enabled = !isLastEnabled
                    )
                }
            }
        }
    }
}
