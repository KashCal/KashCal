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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.onekash.kashcal.sync.session.SyncSession
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.sync.session.SyncStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Simplified bottom sheet displaying sync session history.
 * Shows compact session cards with status icons and expandable details.
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
            // Header row with title and actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sync History",
                    style = MaterialTheme.typography.titleLarge
                )

                Row {
                    IconButton(onClick = {
                        val text = syncSessionStore.getExportText()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Sync History", text))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy to clipboard")
                    }
                    IconButton(onClick = {
                        syncSessionStore.clear()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear history")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Summary line
            SyncSummaryLine(
                totalSyncs = stats.totalSyncs,
                totalPushed = stats.totalPushed,
                totalPulled = stats.totalPulled,
                issueCount = stats.issueCount
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Session List
            if (sessions.isEmpty()) {
                Text(
                    text = "No sync sessions yet.\n\nTrigger a sync by pulling down to refresh on the calendar view.",
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
                        SyncSessionCard(session = session)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Summary line with colored background based on status.
 * Format: "X syncs   ↑Y pushed   ↓Z pulled" or with issues
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
        issueCount > 0 -> Color(0xFFFFA000).copy(alpha = 0.1f)  // Orange tint for issues
        hasChanges -> Color(0xFF4CAF50).copy(alpha = 0.1f)      // Green tint for success with changes
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)  // Neutral for no changes
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor
    ) {
        Text(
            text = buildString {
                append("$totalSyncs syncs")
                append("   ↑$totalPushed pushed")
                append("   ↓$totalPulled pulled")
                if (issueCount > 0) {
                    append("   ⚠ $issueCount issues")
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp)
        )
    }
}

/**
 * Simplified sync session card with expandable details.
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
            // Row 1: Status icon, calendar name, trigger+method, time
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

            // Row 2: Fetched → changes summary
            Text(
                text = buildChangeSummary(session),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Row 3 (optional): Parse failure warning (fallback is now shown inline in buildChangeSummary)
            if (session.hasParseFailures && !expanded) {
                Text(
                    text = "⚠ ${session.skippedParseError} failed to parse",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFA000)
                )
            }

            // Row 4 (optional): Constraint error warning
            if (session.hasConstraintErrors && !expanded) {
                Text(
                    text = "⚠ ${session.skippedConstraintError} skipped (constraint error)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFA000)
                )
            }

            // Expandable details
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(8.dp))

                    DetailRow("Duration", "${session.durationMs}ms")

                    // Parse failure details
                    if (session.hasParseFailures) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Parse Failures",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFA000)
                        )
                        DetailRow("Events failed", session.skippedParseError.toString())
                        if (session.abandonedParseErrors > 0) {
                            DetailRow("Abandoned (max retries)", session.abandonedParseErrors.toString())
                        }
                    }

                    // Constraint error details
                    if (session.hasConstraintErrors) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Constraint Errors",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFA000)
                        )
                        DetailRow("Events skipped", session.skippedConstraintError.toString())
                    }

                    // Fallback details
                    if (session.fallbackUsed) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Fallback Mode",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFA000)
                        )
                        DetailRow("Batch fetch", "Failed → individual fallback")
                        if (session.fetchFailedCount > 0) {
                            DetailRow("Individual failures", session.fetchFailedCount.toString())
                        }
                    }

                    // Error details
                    if (session.errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Error",
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

            // Expand/collapse indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Build the change summary line.
 * Format: "↑N   ↓ +Y ~Z -W" or error message
 */
private fun buildChangeSummary(session: SyncSession): String {
    return when {
        session.status == SyncStatus.FAILED -> {
            session.errorMessage ?: session.errorType?.name ?: "Failed"
        }
        !session.hasAnyChanges -> {
            "↑ 0   ↓ 0"
        }
        else -> {
            val parts = mutableListOf<String>()

            // Push summary
            parts.add("↑ ${session.totalPushed}")

            // Pull summary with breakdown
            val pullParts = mutableListOf<String>()
            if (session.eventsWritten > 0) pullParts.add("+${session.eventsWritten}")
            if (session.eventsUpdated > 0) pullParts.add("~${session.eventsUpdated}")
            if (session.eventsDeleted > 0) pullParts.add("-${session.eventsDeleted}")

            parts.add("↓ ${if (pullParts.isEmpty()) "0" else pullParts.joinToString(" ")}")

            if (session.fallbackUsed) parts.add("⚡")

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
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
        else -> {
            val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.US)
            sdf.format(Date(timestamp))
        }
    }
}
