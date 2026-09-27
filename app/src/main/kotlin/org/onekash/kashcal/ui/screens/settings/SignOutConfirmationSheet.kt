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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R

/**
 * Shows [GenericSignOutConfirmationSheet] for iCloud.
 *
 * @param email the masked email to display, for example "j***@icloud.com".
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
 * Asks the user to confirm signing out of an account, so a sign-out isn't accidental. Sign Out
 * calls [onConfirm] then [onDismiss].
 *
 * @param providerName the name shown in the title; the accounts screen passes the account's
 *   display name.
 * @param email the masked email to display.
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
            Text(
                stringResource(R.string.dialog_sign_out_title, providerName),
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                stringResource(R.string.dialog_sign_out_message, email),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }

                // Destructive action.
                Button(
                    onClick = {
                        onConfirm()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.action_sign_out))
                }
            }
        }
    }
}
