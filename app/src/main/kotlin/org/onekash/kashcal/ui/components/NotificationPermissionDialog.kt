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
import androidx.compose.ui.unit.dp

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
            Text("Enable Notifications?")
        },
        text = {
            Column {
                Text(
                    "KashCal needs notification permission to remind you about upcoming events.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "You can change this later in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onEnable) {
                Text("Enable")
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow) {
                Text("Not Now")
            }
        }
    )
}
