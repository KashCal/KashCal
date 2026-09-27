package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onekash.kashcal.R

/**
 * Shows the developer options, opened by a long press on the settings version footer:
 * - Force Full Sync, which asks for confirmation before calling [onForceFullSync]
 * - Sync History, which calls [onShowSyncLogs]
 *
 * Each closes the sheet through [onDismiss] once it acts; cancelling the confirmation doesn't.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugMenuSheet(
    sheetState: SheetState,
    onForceFullSync: () -> Unit,
    onShowSyncLogs: () -> Unit,
    onDismiss: () -> Unit
) {
    var showForceFullSyncDialog by remember { mutableStateOf(false) }

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
                stringResource(R.string.settings_developer_options),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            DebugMenuItem(
                icon = Icons.Default.Refresh,
                label = stringResource(R.string.settings_force_sync),
                subtitle = stringResource(R.string.settings_force_sync_subtitle),
                onClick = { showForceFullSyncDialog = true }
            )

            HorizontalDivider(
                modifier = Modifier.padding(start = 52.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            DebugMenuItem(
                emoji = "📊",
                label = stringResource(R.string.settings_sync_history),
                subtitle = stringResource(R.string.settings_sync_history_subtitle),
                onClick = {
                    onShowSyncLogs()
                    onDismiss()
                }
            )
        }
    }

    if (showForceFullSyncDialog) {
        AlertDialog(
            onDismissRequest = { showForceFullSyncDialog = false },
            title = { Text(stringResource(R.string.dialog_force_sync_title)) },
            text = { Text(stringResource(R.string.dialog_force_sync_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showForceFullSyncDialog = false
                    onForceFullSync()
                    onDismiss()
                }) {
                    Text(stringResource(R.string.action_sync_now))
                }
            },
            dismissButton = {
                TextButton(onClick = { showForceFullSyncDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/** Draws one item of the debug menu. */
@Composable
private fun DebugMenuItem(
    label: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    emoji: String? = null,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                icon != null -> {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
                emoji != null -> {
                    Text(emoji, fontSize = 20.sp)
                }
            }
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}
