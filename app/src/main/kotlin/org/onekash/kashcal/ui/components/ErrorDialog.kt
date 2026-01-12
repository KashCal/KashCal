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
import org.onekash.kashcal.error.ErrorActionCallback
import org.onekash.kashcal.error.ErrorPresentation

/**
 * Error dialog for critical errors requiring user action.
 *
 * Usage:
 * ```
 * if (uiState.currentError is ErrorPresentation.Dialog) {
 *     ErrorDialog(
 *         presentation = uiState.currentError as ErrorPresentation.Dialog,
 *         onAction = { callback -> viewModel.handleErrorAction(callback) },
 *         onDismiss = { viewModel.clearError() }
 *     )
 * }
 * ```
 *
 * Design:
 * - Uses Material 3 AlertDialog
 * - Primary action is always shown (confirm button)
 * - Secondary action is optional (dismiss button)
 * - Non-dismissible dialogs block outside taps
 *
 * Use cases:
 * - Authentication errors (Sign In / Cancel)
 * - Session expired (must re-authenticate)
 * - Storage full (Open Settings / Close)
 * - Sync conflicts (Force Sync / Cancel)
 */
@Composable
fun ErrorDialog(
    presentation: ErrorPresentation.Dialog,
    onAction: (ErrorActionCallback) -> Unit,
    onDismiss: () -> Unit
) {
    val message = if (presentation.messageArgs.isNotEmpty()) {
        stringResource(presentation.messageResId, *presentation.messageArgs)
    } else {
        stringResource(presentation.messageResId)
    }

    AlertDialog(
        onDismissRequest = {
            if (presentation.dismissible) {
                onDismiss()
            }
            // Non-dismissible dialogs ignore outside taps
        },
        title = {
            Text(
                text = stringResource(presentation.titleResId),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium
                )
                // Add hint about dismissibility for non-dismissible dialogs
                if (!presentation.dismissible) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "This action is required to continue.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAction(presentation.primaryAction.callback)
                    if (presentation.primaryAction.isDismissAction) {
                        onDismiss()
                    }
                }
            ) {
                Text(stringResource(presentation.primaryAction.labelResId))
            }
        },
        dismissButton = if (presentation.secondaryAction != null) {
            {
                TextButton(
                    onClick = {
                        onAction(presentation.secondaryAction.callback)
                        if (presentation.secondaryAction.isDismissAction) {
                            onDismiss()
                        }
                    }
                ) {
                    Text(stringResource(presentation.secondaryAction.labelResId))
                }
            }
        } else null
    )
}

/**
 * Simplified error dialog for quick one-off dialogs.
 *
 * For complex dialogs with multiple actions, use ErrorDialog with ErrorPresentation.Dialog.
 */
@Composable
fun SimpleErrorDialog(
    title: String,
    message: String,
    confirmLabel: String = "OK",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit = onConfirm,
    dismissLabel: String? = null,
    dismissible: Boolean = true
) {
    AlertDialog(
        onDismissRequest = {
            if (dismissible) onDismiss()
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel)
            }
        },
        dismissButton = if (dismissLabel != null) {
            {
                TextButton(onClick = onDismiss) {
                    Text(dismissLabel)
                }
            }
        } else null
    )
}
