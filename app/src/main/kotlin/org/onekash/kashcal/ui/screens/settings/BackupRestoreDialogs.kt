package org.onekash.kashcal.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.backup.BackupImportError
import org.onekash.kashcal.domain.backup.BackupSummary
import org.onekash.kashcal.domain.backup.ImportResult
import org.onekash.kashcal.ui.components.SimpleErrorDialog

/**
 * Pre-restore confirmation dialog. Shows what will be imported; user must tap Restore to apply.
 */
@Composable
fun RestoreConfirmationDialog(
    summary: BackupSummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.restore_confirm_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(
                        R.string.restore_confirm_from,
                        summary.appVersion,
                        summary.exportedAt,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = restoreMessage(
                        subscriptions = summary.subscriptions,
                        withSubsPlural = R.plurals.restore_confirm_message_with_subs,
                        prefsOnly = R.string.restore_confirm_message_prefs_only,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_restore))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * Post-restore success dialog. Adds the device-calendars note when applicable.
 */
@Composable
fun RestoreSuccessDialog(
    result: ImportResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.restore_success_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column {
                Text(
                    text = restoreMessage(
                        subscriptions = result.subscriptionsCreated + result.subscriptionsUpdated,
                        withSubsPlural = R.plurals.restore_success_message_with_subs,
                        prefsOnly = R.string.restore_success_message_prefs_only,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (result.deviceCalendarsNoteNeeded) {
                    RestoreNote(R.string.restore_success_device_calendars_note)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        },
    )
}

@Composable
private fun restoreMessage(
    subscriptions: Int,
    @StringRes withSubsPlural: Int,
    @StringRes prefsOnly: Int,
): String = if (subscriptions == 0) {
    stringResource(prefsOnly)
} else {
    pluralStringResource(withSubsPlural, subscriptions, subscriptions)
}

@Composable
private fun RestoreNote(@StringRes stringResId: Int) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(stringResId),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Restore error dialog. The `when` is exhaustive over [BackupImportError] subtypes so new
 * variants force a compile-time update here.
 */
@Composable
fun RestoreErrorDialog(
    error: BackupImportError,
    onDismiss: () -> Unit,
) {
    val messageResId = when (error) {
        is BackupImportError.VersionTooNew -> R.string.restore_error_version_too_new
        is BackupImportError.MalformedJson -> R.string.restore_error_malformed_json
        is BackupImportError.InvalidValue -> R.string.restore_error_invalid_value
        is BackupImportError.ApplyFailed -> R.string.restore_error_apply_failure
    }
    SimpleErrorDialog(
        title = stringResource(R.string.restore_error_title),
        message = stringResource(messageResId),
        confirmLabel = stringResource(R.string.action_ok),
        onConfirm = onDismiss,
    )
}
