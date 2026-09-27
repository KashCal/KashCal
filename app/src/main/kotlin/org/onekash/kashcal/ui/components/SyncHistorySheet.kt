package org.onekash.kashcal.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.onekash.kashcal.R
import org.onekash.kashcal.sync.session.SyncSession
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.sync.session.SyncStatus

/**
 * Shows the sync session history as a bottom sheet: a summary line, then one expandable card per
 * session, with actions to copy the history to the clipboard or clear it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncHistorySheet(
    syncSessionStore: SyncSessionStore,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sessions by syncSessionStore.sessions.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val stats = remember(sessions) {
        syncSessionStore.getSummaryStats(sessions)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.status_sync_history),
                    style = MaterialTheme.typography.titleLarge
                )

                Row {
                    IconButton(onClick = {
                        val text = syncSessionStore.getExportText()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Sync History", text))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.cd_copy_clipboard))
                    }
                    IconButton(onClick = {
                        syncSessionStore.clear()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_clear_history))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            SyncSummaryLine(
                totalSyncs = stats.totalSyncs,
                totalPushed = stats.totalPushed,
                totalPulled = stats.totalPulled,
                issueCount = stats.issueCount
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (sessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.status_no_sync_sessions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        Column(modifier = Modifier.animateItem()) {
                            SyncSessionCard(session = session)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Shows totals across all sessions ("X syncs   ↑Y pushed   ↓Z pulled", plus an issue count when
 * there are issues), tinted orange for issues, green for changes, neutral otherwise.
 */
@Composable
private fun SyncSummaryLine(
    totalSyncs: Int,
    totalPushed: Int,
    totalPulled: Int,
    issueCount: Int
) {
    val hasChanges = totalPushed > 0 || totalPulled > 0
    val backgroundColor = when {
        issueCount > 0 -> Color(0xFFFFA000).copy(alpha = 0.1f)
        hasChanges -> Color(0xFF4CAF50).copy(alpha = 0.1f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor
    ) {
        Text(
            text = if (issueCount > 0) {
                stringResource(R.string.sync_history_stats_issues, totalSyncs, totalPushed, totalPulled, issueCount)
            } else {
                stringResource(R.string.sync_history_stats, totalSyncs, totalPushed, totalPulled)
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp)
        )
    }
}

/**
 * Shows one sync session; a tap expands its duration, parse failures, skips, warnings and error.
 */
@Composable
private fun SyncSessionCard(session: SyncSession) {
    var expanded by remember { mutableStateOf(false) }

    val statusIcon = when (session.status) {
        SyncStatus.SUCCESS -> "✓"
        SyncStatus.PARTIAL -> "⚠"
        SyncStatus.FAILED -> "✗"
    }

    val statusColor = when (session.status) {
        SyncStatus.SUCCESS -> Color(0xFF4CAF50)
        SyncStatus.PARTIAL -> Color(0xFFFFA000)
        SyncStatus.FAILED -> Color(0xFFE53935)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Status icon, calendar name, trigger icon and sync type, relative time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = statusIcon,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = session.calendarName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${session.triggerSource.icon} ${session.syncType.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatRelativeTime(session.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Push and pull counts, or the error when the sync failed
            Text(
                text = buildChangeSummary(session, stringResource(R.string.sync_history_failed)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Collapsed only; the expanded details repeat these counts
            if (session.hasParseFailures && !expanded) {
                Text(
                    text = stringResource(R.string.status_failed_to_parse, session.skippedParseError),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFA000)
                )
            }

            if (session.hasAlreadySynced && !expanded) {
                Text(
                    text = stringResource(R.string.sync_history_already_synced_count, session.skippedAlreadySynced),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(8.dp))

                    DetailRow(stringResource(R.string.sync_history_duration), "${session.durationMs}ms")

                    if (session.hasParseFailures) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.status_parse_failures),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFA000)
                        )
                        DetailRow(stringResource(R.string.sync_history_events_failed), session.skippedParseError.toString())
                        if (session.abandonedParseErrors > 0) {
                            DetailRow(stringResource(R.string.sync_history_abandoned_max_retries), session.abandonedParseErrors.toString())
                        }
                    }

                    if (session.hasAlreadySynced) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.status_already_synced),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        DetailRow(stringResource(R.string.sync_history_events_skipped), session.skippedAlreadySynced.toString())
                    }

                    // Issues handled without failing the sync
                    if (session.hasWarnings) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.status_warnings),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFA000)
                        )
                        session.warnings?.forEach { warning ->
                            Text(
                                text = "• $warning",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }

                    if (session.errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.status_error),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE53935)
                        )
                        Text(
                            text = session.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                val expandCollapseDescription = if (expanded) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand)
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = expandCollapseDescription,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Builds a session's change line: "↑ N   ↓ +Y ~Z -W" (zero pull parts omitted), "↑ 0   ↓ 0"
 * with no changes, or for a failed sync its error message, error type or [failedLabel].
 */
private fun buildChangeSummary(session: SyncSession, failedLabel: String = "Failed"): String {
    return when {
        session.status == SyncStatus.FAILED -> {
            session.errorMessage ?: session.errorType?.name ?: failedLabel
        }
        !session.hasAnyChanges -> {
            "↑ 0   ↓ 0"
        }
        else -> {
            val parts = mutableListOf<String>()

            parts.add("↑ ${session.totalPushed}")

            val pullParts = mutableListOf<String>()
            if (session.eventsWritten > 0) pullParts.add("+${session.eventsWritten}")
            if (session.eventsUpdated > 0) pullParts.add("~${session.eventsUpdated}")
            if (session.eventsDeleted > 0) pullParts.add("-${session.eventsDeleted}")

            parts.add("↓ ${if (pullParts.isEmpty()) "0" else pullParts.joinToString(" ")}")

            parts.joinToString("   ")
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    return org.onekash.kashcal.util.DateTimeUtils.formatRelativeTime(timestamp)
}
