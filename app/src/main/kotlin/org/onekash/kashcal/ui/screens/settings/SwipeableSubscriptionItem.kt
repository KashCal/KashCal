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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R

/**
 * Shows one subscription row: color dot, name, status line, refresh button and enable switch.
 * A swipe from the end deletes it, a tap calls [onEdit], and a warning icon marks a failed sync.
 *
 * @param onToggle called with (subscriptionId, enabled).
 * @param onDelete called with the subscription id from the swipe or the accessibility action.
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
    // Call onDelete from confirmValueChange and reject the transition (return false). The row
    // stays Settled; the ViewModel's pending-deletion filter removes it from the list. A
    // stale EndToStart state would survive an undo (#133): the row would reappear stuck
    // mid-swipe.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                subscription.id?.let { onDelete(it) }
            }
            false
        }
    )
    val hasError = subscription.hasError()

    // Switch Access and TalkBack can't perform the swipe, so delete is also a custom action.
    val deleteLabel = stringResource(R.string.cd_delete)
    val deleteActions = subscription.id?.let { id ->
        listOf(CustomAccessibilityAction(deleteLabel) { onDelete(id); true })
    }

    SwipeToDismissBox(
        modifier = deleteActions?.let {
            Modifier.semantics { customActions = it }
        } ?: Modifier,
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
                    contentDescription = stringResource(R.string.cd_delete),
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
                        onClick = { subscription.id?.let { onRefresh(it) } },
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
 * Shows the subscription's status line: the error in red, "Sync paused" when disabled, else
 * "Not synced" or the time since the last sync.
 */
@Composable
private fun SubscriptionStatusText(
    subscription: IcsSubscriptionUiModel,
    hasError: Boolean
) {
    if (hasError) {
        Text(
            subscription.lastError ?: stringResource(R.string.status_sync_error),
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
