package org.onekash.kashcal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.changes.RecentChangeItem
import org.onekash.kashcal.domain.changes.RecentChangesRecorder
import org.onekash.kashcal.domain.changes.isSeriesRow
import org.onekash.kashcal.domain.changes.opens
import org.onekash.kashcal.sync.model.ChangeType

/**
 * Lists what CalDAV and iCloud syncs changed within [RecentChangesRecorder.RETENTION_MS], under
 * day headers by when each change arrived, newest first.
 *
 * The sheet always opens at full height and the list fills it, so it never resizes as rows load,
 * go or come back: a sheet sized to its rows can stay at the height it measured before they
 * arrived. The list scrolls when its rows overflow, and a drag down with the list at its top
 * closes the sheet.
 *
 * A swipe end-to-start (or the row's Dismiss accessibility action) hides a row with no undo:
 * it is a log row, not an event. Clear all hides every row shown and offers one Undo in a row
 * under the title.
 *
 * @param clearedIds the rows the last Clear all hid; non-empty shows the Undo row once, then
 *   [onUndoShown] ends the offer.
 * @param onOpen called for a row that opens; deleted rows and rows without an event don't.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentChangesSheet(
    items: List<RecentChangeItem>,
    timePattern: String,
    clearedIds: List<Long>,
    onDismissRow: (Long) -> Unit,
    onClearAll: () -> Unit,
    onUndoClear: () -> Unit,
    onUndoShown: () -> Unit,
    onOpen: (RecentChangeItem) -> Unit,
    onDismiss: () -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val resources = LocalContext.current.resources
    val formatter = remember(resources, timePattern) { RecentChangeFormatter(resources, timePattern) }
    // Fixed while the sheet is open, so headers and rows don't shift between recompositions.
    val openedAt = remember { now }
    // As long as a short snackbar, stretched for users who asked for more time to act.
    val accessibilityManager = LocalAccessibilityManager.current
    val undoTimeoutMs = remember(accessibilityManager) {
        accessibilityManager?.calculateRecommendedTimeoutMillis(
            UNDO_TIMEOUT_MS, containsIcons = false, containsText = true, containsControls = true
        ) ?: UNDO_TIMEOUT_MS
    }

    // Cancelled with the sheet: closing it ends the offer with no callback.
    LaunchedEffect(clearedIds) {
        if (clearedIds.isEmpty()) return@LaunchedEffect
        delay(undoTimeoutMs)
        onUndoShown()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.recent_changes_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClearAll, enabled = items.isNotEmpty()) {
                    Text(stringResource(R.string.recent_changes_clear_all))
                }
            }

            if (clearedIds.isNotEmpty()) {
                // A polite live region, as a snackbar is, so TalkBack reads it out.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    Text(
                        text = stringResource(R.string.recent_changes_cleared),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onUndoClear) {
                        Text(stringResource(R.string.snackbar_action_undo))
                    }
                }
            }

            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.recent_changes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                val groups = remember(items, formatter, openedAt) {
                    items.groupBy { formatter.dayHeader(it.detectedAt, openedAt) }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    groups.forEach { (header, rows) ->
                        item(key = "header_$header") {
                            Text(
                                text = header,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                            )
                        }
                        items(rows, key = { it.id }) { item ->
                            RecentChangeRow(
                                item = item,
                                whenText = formatter.whenText(item, openedAt),
                                detail = formatter.detail(item),
                                onDismissRow = onDismissRow,
                                onOpen = onOpen,
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val UNDO_TIMEOUT_MS = 4_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentChangeRow(
    item: RecentChangeItem,
    whenText: String,
    detail: String?,
    onDismissRow: (Long) -> Unit,
    onOpen: (RecentChangeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    // Switch Access and TalkBack can't swipe, so dismiss is also a custom action.
    val dismissLabel = stringResource(R.string.recent_changes_dismiss)
    val (icon, iconColor, iconDescription) = when (item.changeType) {
        ChangeType.NEW -> Triple(Icons.Default.Add, Color(0xFF4CAF50), stringResource(R.string.cd_recent_change_new))
        ChangeType.MODIFIED -> Triple(Icons.Default.Edit, Color(0xFF2196F3), stringResource(R.string.cd_recent_change_updated))
        ChangeType.DELETED -> Triple(Icons.Default.Delete, Color(0xFFF44336), stringResource(R.string.cd_recent_change_removed))
    }

    SwipeToDismissBox(
        modifier = modifier.semantics {
            customActions = listOf(CustomAccessibilityAction(dismissLabel) { onDismissRow(item.id); true })
        },
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = dismissLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDismissRow(item.id)
            // The row leaves the list when the log re-emits; settle it so a row that comes
            // back (Clear all's undo) isn't stuck swiped away.
            scope.launch { dismissState.snapTo(SwipeToDismissBoxValue.Settled) }
        }
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (item.opens) Modifier.clickable { onOpen(item) } else Modifier),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconDescription,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                // The dot is the row's only calendar cue; TalkBack reads the calendar's name.
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(item.calendarColor))
                        .semantics { contentDescription = item.calendarName }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = whenText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Only a series repeats; a changed occurrence is one date.
                        if (item.isSeriesRow) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Repeat,
                                contentDescription = stringResource(R.string.cd_recurring),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (detail != null) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
