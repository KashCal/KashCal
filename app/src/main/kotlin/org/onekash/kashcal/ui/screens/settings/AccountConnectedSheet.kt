package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.shared.maskEmail

/**
 * Success bottom sheet shown after account connection.
 *
 * Displays connection success with provider name, email, and calendar count.
 * Offers "Add Another" to add more accounts or "Done" to finish.
 *
 * @param sheetState Material3 sheet state
 * @param providerName Display name of the provider (e.g., "iCloud", "Nextcloud")
 * @param email User email (will be masked for display)
 * @param calendarCount Number of calendars discovered
 * @param onAddAnother Callback to add another account (stays on AccountsScreen)
 * @param onDone Callback when user is done (returns to HomeScreen)
 * @param onDismiss Callback when sheet is dismissed (same as onAddAnother)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountConnectedSheet(
    sheetState: SheetState,
    providerName: String,
    email: String,
    calendarCount: Int,
    onAddAnother: () -> Unit,
    onDone: () -> Unit,
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Success icon
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = AccentColors.Green,
                modifier = Modifier.size(48.dp)
            )

            // Title
            Text(
                text = stringResource(R.string.signin_connected_to, providerName),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )

            // Description
            Text(
                text = stringResource(R.string.account_connected_found_calendars, calendarCount, maskEmail(email)) +
                    "\n" + stringResource(R.string.account_connected_syncing_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Add Another button
                OutlinedButton(
                    onClick = {
                        onAddAnother()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_add_another))
                }

                // Done button (primary)
                Button(
                    onClick = {
                        onDone()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_done))
                }
            }
        }
    }
}
