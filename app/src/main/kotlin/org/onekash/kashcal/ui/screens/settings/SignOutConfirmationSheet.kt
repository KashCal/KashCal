package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Confirmation bottom sheet for signing out of iCloud.
 *
 * Shows before disconnecting to prevent accidental sign out.
 *
 * @param sheetState Material3 sheet state
 * @param email Masked email to display (e.g., "j***@icloud.com")
 * @param onConfirm Callback when user confirms sign out
 * @param onDismiss Callback when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignOutConfirmationSheet(
    sheetState: SheetState,
    email: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    GenericSignOutConfirmationSheet(
        sheetState = sheetState,
        providerName = "iCloud",
        email = email,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

/**
 * Generic sign-out confirmation sheet that works for any provider.
 *
 * @param sheetState Material3 sheet state
 * @param providerName Display name of the provider (e.g., "iCloud", "Nextcloud")
 * @param email Masked email to display
 * @param onConfirm Callback when user confirms sign out
 * @param onDismiss Callback when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenericSignOutConfirmationSheet(
    sheetState: SheetState,
    providerName: String,
    email: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Title
            Text(
                "Sign Out of $providerName?",
                style = MaterialTheme.typography.titleLarge
            )

            // Description
            Text(
                "You are signed in as $email. Signing out will remove synced calendars from this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Cancel button
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }

                // Sign Out button (destructive)
                Button(
                    onClick = {
                        onConfirm()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Sign Out")
                }
            }
        }
    }
}
