package org.onekash.kashcal.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.onekash.kashcal.sync.session.SyncSession
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.sync.session.SyncStatus
import org.onekash.kashcal.sync.session.SyncSummaryStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Bottom sheet displaying sync session history.
 * Shows structured session cards with color-coded status indicators.
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
            // Header
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
                        Icon(Icons.Default.ContentCopy, contentDescription = "Export")
                    }
                    IconButton(onClick = {
                        syncSessionStore.clear()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Summary Header
            SyncSummaryHeader(stats = stats)

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
 * Summary header showing aggregate stats.
 */
@Composable
private fun SyncSummaryHeader(stats: SyncSummaryStats) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "LAST 48 HOURS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "${stats.totalSyncs} syncs  •  ${stats.totalEventsProcessed} events",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Status counts
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusChip(
                    color = Color(0xFF4CAF50),
                    count = stats.successCount,
                    label = "ok"
                )
                StatusChip(
                    color = Color(0xFFFFA000),
                    count = stats.partialCount,
                    label = "partial"
                )
                StatusChip(
                    color = Color(0xFFE53935),
                    count = stats.failedCount,
                    label = "failed"
                )
            }

            // Background vs Foreground missing events banner
            if (stats.backgroundMissingCount > 0 || stats.foregroundMissingCount > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFFA000).copy(alpha = 0.15f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (stats.backgroundMissingCount > 0) {
                            Text(
                                text = "⚠️ Background: ${stats.backgroundMissingCount} missing events",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFA000)
                            )
                        }
                        if (stats.foregroundMissingCount > 0) {
                            Text(
                                text = "⚠️ Foreground: ${stats.foregroundMissingCount} missing events",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFA000)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(color: Color, count: Int, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$count $label",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Individual sync session card with expandable details.
 */
@Composable
private fun SyncSessionCard(session: SyncSession) {
    var expanded by remember { mutableStateOf(false) }

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
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Status indicator
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
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
                    if (session.hasIssues) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "⚠️",
                            fontSize = 12.sp
                        )
                    }
                }
                Text(
                    text = formatRelativeTime(session.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Trigger info
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${session.triggerSource.icon} ${session.triggerSource.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = " • ${session.durationMs}ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))

            // Pipeline numbers
            if (session.errorType != null) {
                Text(
                    text = "❌ ${session.errorType}${session.errorStage?.let { " at $it" } ?: ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE53935)
                )
                // Show error message if available
                session.errorMessage?.let { message ->
                    Text(
                        text = message.take(100) + if (message.length > 100) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE53935).copy(alpha = 0.8f),
                        maxLines = 2
                    )
                }
            } else {
                Text(
                    text = "Server: ${session.hrefsReported}  →  Fetched: ${session.eventsFetched}  →  Written: ${session.eventsWritten}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Issue summary (collapsed)
            if (session.fallbackUsed && !expanded) {
                Text(
                    text = "🔄 Fallback used${if (session.fetchFailedCount > 0) " (${session.fetchFailedCount} failed)" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFA000)
                )
            }
            if (session.hasMissingEvents && !expanded) {
                Text(
                    text = "⚠️ ${session.missingCount} missing (will retry)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFA000)
                )
            }
            if (session.totalSkipped > 0 && !expanded) {
                Text(
                    text = "⚠️ ${session.totalSkipped} skipped",
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

                    Text(
                        text = "DETAILS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    DetailRow("Sync type", session.syncType.name)
                    DetailRow("Updated", session.eventsUpdated.toString())
                    DetailRow("Deleted", session.eventsDeleted.toString())

                    if (session.hasMissingEvents) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "MISSING EVENTS",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFA000)
                        )
                        DetailRow("Missing count", session.missingCount.toString())
                        DetailRow("Token advanced", if (session.tokenAdvanced) "Yes" else "No (will retry)")
                    }

                    if (session.totalSkipped > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "SKIPPED BREAKDOWN",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (session.skippedParseError > 0) DetailRow("Parse error", session.skippedParseError.toString())
                        if (session.skippedOrphanedException > 0) DetailRow("No master found", session.skippedOrphanedException.toString())
                        if (session.skippedPendingLocal > 0) DetailRow("Pending local", session.skippedPendingLocal.toString())
                        if (session.skippedEtagUnchanged > 0) DetailRow("ETag unchanged", session.skippedEtagUnchanged.toString())
                    }

                    // Fetch fallback details (v16.8.0)
                    if (session.fallbackUsed) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "FETCH FALLBACK",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFA000)
                        )
                        DetailRow("Batch multiget", "Failed")
                        DetailRow("Individual fallback", "Used")
                        if (session.fetchFailedCount > 0) {
                            DetailRow("Individual failures", session.fetchFailedCount.toString())
                        }
                    }

                    // Error message details (v16.8.0)
                    if (session.errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "ERROR DETAILS",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE53935)
                        )
                        Text(
                            text = session.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
