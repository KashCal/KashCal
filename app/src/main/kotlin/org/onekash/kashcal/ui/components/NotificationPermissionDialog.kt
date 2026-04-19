package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R

/**
 * Rationale dialog shown after user denies notification permission once.
 *
 * Follows Android best practices:
 * - Shows clear explanation of why permission is needed
 * - Offers path to enable (system permission dialog)
 * - Allows user to skip without blocking the action
 *
 * Dialog flow:
 * ```
 * First denial → shouldShowRequestPermissionRationale() returns true
 *                              │
 *                              ▼
 *                 NotificationPermissionDialog
 *                              │
 *           ┌──────────────────┴────────────────────┐
 *           ▼                                       ▼
 *       "Enable"                               "Not Now"
 *           │                                       │
 *           ▼                                       ▼
 *   Launch permission dialog                 Continue without
 *                                            notifications
 * ```
 */
@Composable
fun NotificationPermissionDialog(
    onEnable: () -> Unit,
    onNotNow: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.dialog_enable_notifications))
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.dialog_notification_permission_message),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.dialog_notification_permission_settings_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onEnable) {
                Text(stringResource(R.string.action_enable))
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow) {
                Text(stringResource(R.string.action_not_now))
            }
        }
    )
}
