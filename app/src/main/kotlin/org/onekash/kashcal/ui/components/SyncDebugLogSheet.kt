package org.onekash.kashcal.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.onekash.kashcal.sync.debug.SyncDebugLog

/**
 * Bottom sheet displaying sync debug logs.
 * Shows timestamped log entries with copy and clear functionality.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncDebugLogSheet(
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entries by SyncDebugLog.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new entries arrive
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
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
            // Header with actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "iCloud Sync Log (${entries.size})",
                    style = MaterialTheme.typography.titleLarge
                )

                Row {
                    IconButton(onClick = {
                        val text = SyncDebugLog.getAllAsText()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Sync Logs", text))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy all")
                    }
                    IconButton(onClick = {
                        SyncDebugLog.clear()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear logs")
                    }
                }
            }

            Text(
                text = "Trigger a sync to see log entries here",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Log entries
            if (entries.isEmpty()) {
                Text(
                    text = "No log entries yet.\n\nGo back and pull-to-refresh to trigger a sync.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(entries, key = { "${it.timestamp}_${it.message.hashCode()}" }) { entry ->
                        LogEntryItem(entry)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun LogEntryItem(entry: SyncDebugLog.LogEntry) {
    val color = when (entry.level) {
        SyncDebugLog.Level.ERROR -> Color(0xFFE53935)
        SyncDebugLog.Level.WARN -> Color(0xFFFFA000)
        SyncDebugLog.Level.INFO -> MaterialTheme.colorScheme.onSurface
        SyncDebugLog.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = entry.formatted(),
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp
        ),
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    )
}
