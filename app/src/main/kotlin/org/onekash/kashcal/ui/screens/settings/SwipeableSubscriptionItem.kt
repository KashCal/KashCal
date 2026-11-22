package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.SwipeRevealRow
import org.onekash.kashcal.ui.util.asString

/**
 * Shows one subscription row: color dot, name, status line, refresh button and enable switch.
 * A swipe from the end reveals a Delete button ([SwipeRevealRow]); tapping it deletes. A tap on
 * the row calls [onEdit], and a warning icon marks a failed sync. A row not yet saved (no id) has
 * no swipe and no Delete.
 *
 * @param open whether this row is the list's one revealed row.
 * @param onOpenChange called when the row settles open or closed, or leaves the list while open.
 * @param onToggle called with (subscriptionId, enabled).
 * @param onDelete called with the subscription id from the Delete button or the accessibility
 *   action.
 */
@Composable
fun SwipeableSubscriptionItem(
    subscription: IcsSubscriptionUiModel,
    onToggle: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onRefresh: (Long) -> Unit,
    onEdit: (IcsSubscriptionUiModel) -> Unit,
    open: Boolean = false,
    onOpenChange: (Boolean) -> Unit = {},
) {
    val hasError = subscription.hasError()
    val id = subscription.id

    SwipeRevealRow(
        enabled = id != null,
        open = open,
        onOpenChange = onOpenChange,
        deleteLabel = stringResource(R.string.cd_delete),
        onDelete = { id?.let(onDelete) },
    ) { deleteAction ->
        Surface(
            modifier = deleteAction
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
                                    contentDescription = stringResource(R.string.cd_sync_error),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        SubscriptionStatusText(subscription, hasError)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { id?.let { onRefresh(it) } },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Switch(
                        checked = subscription.enabled,
                        onCheckedChange = { enabled ->
                            id?.let { onToggle(it, enabled) }
                        },
                        modifier = Modifier.scale(0.8f)
                    )
                }
            }
        }
    }
}

/**
 * Shows the subscription's status line: the last error in red ([icsFeedErrorMessage]), "Sync
 * paused" when disabled, else "Not synced" or the time since the last sync.
 */
@Composable
private fun SubscriptionStatusText(
    subscription: IcsSubscriptionUiModel,
    hasError: Boolean
) {
    if (hasError) {
        Text(
            subscription.lastError?.let { icsFeedErrorMessage(it).asString() }
                ?: stringResource(R.string.status_sync_error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    } else if (!subscription.enabled) {
        Text(
            stringResource(R.string.status_sync_paused),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        val lastSyncText = if (subscription.lastSync <= 0) {
            stringResource(R.string.status_not_synced)
        } else {
            val ago = ((System.currentTimeMillis() - subscription.lastSync) / 60000).toInt()
            if (ago < 60) stringResource(R.string.status_minutes_ago, ago)
            else stringResource(R.string.status_hours_ago, ago / 60)
        }
        Text(
            lastSyncText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
