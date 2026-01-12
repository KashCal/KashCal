package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Swipeable subscription item with swipe-left-to-delete gesture.
 *
 * Features:
 * - Swipe left to delete
 * - Error indicator when sync fails
 * - Refresh button for manual sync
 * - Clickable to open edit dialog
 *
 * @param subscription The subscription to display
 * @param onToggle Callback when enabled/disabled toggle changes (subscriptionId, enabled)
 * @param onDelete Callback when item is swiped to delete (subscriptionId)
 * @param onRefresh Callback when refresh button is clicked (subscriptionId)
 * @param onEdit Callback when item is clicked to edit
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableSubscriptionItem(
    subscription: IcsSubscriptionUiModel,
    onToggle: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onRefresh: (Long) -> Unit,
    onEdit: (IcsSubscriptionUiModel) -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val hasError = subscription.hasError()

    // Trigger delete when swiped to EndToStart position
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            subscription.id?.let { onDelete(it) }
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEdit(subscription) },
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side: color dot + name/status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color(subscription.color))
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        // Name row with optional error icon
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                subscription.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (hasError) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Sync error",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        // Status text: error message (red) or last sync time
                        SubscriptionStatusText(subscription, hasError)
                    }
                }

                // Right side: refresh button + toggle switch
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { subscription.id?.let { onRefresh(it) } },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Switch(
                        checked = subscription.enabled,
                        onCheckedChange = { enabled ->
                            subscription.id?.let { onToggle(it, enabled) }
                        },
                        modifier = Modifier.scale(0.8f)
                    )
                }
            }
        }
    }
}

/**
 * Status text for subscription item.
 * Shows error message (red) or last sync time.
 */
@Composable
private fun SubscriptionStatusText(
    subscription: IcsSubscriptionUiModel,
    hasError: Boolean
) {
    if (hasError) {
        Text(
            subscription.lastError ?: "Sync error",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    } else if (!subscription.enabled) {
        Text(
            "Sync paused",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Text(
            formatLastSyncTime(subscription.lastSync),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Format last sync time as a human-readable string.
 *
 * @param lastSyncMs Last sync timestamp in milliseconds, 0 if never synced
 * @return Formatted string like "5m ago", "2h ago", or "Not synced"
 */
fun formatLastSyncTime(lastSyncMs: Long): String {
    if (lastSyncMs <= 0) return "Not synced"

    val ago = (System.currentTimeMillis() - lastSyncMs) / 60000
    return if (ago < 60) "${ago}m ago" else "${ago / 60}h ago"
}
