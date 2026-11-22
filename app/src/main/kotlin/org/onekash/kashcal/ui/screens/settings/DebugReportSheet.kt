package org.onekash.kashcal.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R

/**
 * Lists what the debug report holds and what it doesn't mask, before the user shares it.
 * Share report calls [onShare] and Cancel calls [onDismiss]; the caller closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugReportSheet(
    sheetState: SheetState,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.debug_report_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = stringResource(R.string.debug_report_sheet_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            ReportItem(Icons.Default.Warning, R.string.debug_report_item_exits, R.string.debug_report_item_exits_detail)
            ReportItem(Icons.AutoMirrored.Filled.List, R.string.debug_report_item_log, R.string.debug_report_item_log_detail)
            ReportItem(Icons.Default.Sync, R.string.settings_sync_history, R.string.debug_report_item_sync_detail)
            ReportItem(Icons.Default.Schedule, R.string.debug_report_item_pending, R.string.debug_report_item_pending_detail)
            ReportItem(Icons.Default.Build, R.string.debug_report_item_setup, R.string.debug_report_item_setup_detail)
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.debug_report_privacy_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.debug_report_share))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

/** Draws one line of what the report holds: an icon, a label and its detail. */
@Composable
private fun ReportItem(icon: ImageVector, @StringRes labelRes: Int, @StringRes detailRes: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Column {
            Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(detailRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
