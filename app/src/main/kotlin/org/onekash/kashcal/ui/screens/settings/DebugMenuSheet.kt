package org.onekash.kashcal.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onekash.kashcal.ui.shared.SYNC_OPTIONS
import org.onekash.kashcal.util.DateTimeUtils.formatSyncInterval

/**
 * Debug menu bottom sheet.
 *
 * Accessed via long-press on version footer.
 * Contains developer options not shown to regular users:
 * - Force Full Sync
 * - Sync Log viewer
 * - Sync Frequency selector (moved from main UI)
 *
 * @param sheetState Material3 sheet state for controlling visibility
 * @param syncIntervalMs Current sync interval in milliseconds
 * @param onSyncIntervalChange Callback when sync interval changes
 * @param onForceFullSync Callback to trigger full sync
 * @param onShowSyncLogs Callback to navigate to sync logs
 * @param onDismiss Callback when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugMenuSheet(
    sheetState: SheetState,
    syncIntervalMs: Long,
    onSyncIntervalChange: (Long) -> Unit,
    onForceFullSync: () -> Unit,
    onShowSyncLogs: () -> Unit,
    onDismiss: () -> Unit
) {
    var showSyncOptions by remember { mutableStateOf(false) }
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
            // Header
            Text(
                "Developer Options",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Force Full Sync
            DebugMenuItem(
                icon = Icons.Default.Refresh,
                label = "Force Full Sync",
                subtitle = "Re-download all calendar data",
                onClick = { showForceFullSyncDialog = true }
            )

            HorizontalDivider(
                modifier = Modifier.padding(start = 52.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Sync History
            DebugMenuItem(
                emoji = "📊",
                label = "Sync History",
                subtitle = "View sync sessions (last 48 hours)",
                onClick = {
                    onShowSyncLogs()
                    onDismiss()
                }
            )

            HorizontalDivider(
                modifier = Modifier.padding(start = 52.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Sync Frequency (expandable)
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSyncOptions = !showSyncOptions }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                "Sync Frequency",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                formatSyncInterval(syncIntervalMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Icon(
                        if (showSyncOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Expandable sync options
                AnimatedVisibility(
                    visible = showSyncOptions,
                    enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(150)),
                    exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(animationSpec = tween(100))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 52.dp, end = 16.dp)
                    ) {
                        SYNC_OPTIONS.forEach { option ->
                            SyncOptionRow(
                                label = option.label,
                                isSelected = option.intervalMs == syncIntervalMs,
                                onClick = {
                                    onSyncIntervalChange(option.intervalMs)
                                    showSyncOptions = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Force Full Sync confirmation dialog
    if (showForceFullSyncDialog) {
        AlertDialog(
            onDismissRequest = { showForceFullSyncDialog = false },
            title = { Text("Force Full Sync?") },
            text = { Text("This will re-download all calendar data from the server. Local changes will be preserved.") },
            confirmButton = {
                TextButton(onClick = {
                    showForceFullSyncDialog = false
                    onForceFullSync()
                    onDismiss()
                }) {
                    Text("Sync Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showForceFullSyncDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Single menu item in the debug menu.
 */
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

/**
 * Single sync option row in the expandable list.
 */
@Composable
private fun SyncOptionRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onClick() }
            .background(
                if (isSelected)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else Color.Transparent
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium
        )
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
