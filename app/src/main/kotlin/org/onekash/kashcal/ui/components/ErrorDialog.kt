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
import org.onekash.kashcal.error.ErrorActionCallback
import org.onekash.kashcal.error.ErrorPresentation

/**
 * Shows an [ErrorPresentation.Dialog] for an error the user must act on.
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
 * The primary action is always the confirm button; the secondary action, when present, is the
 * dismiss button. A button also calls [onDismiss] only when its action is a dismiss action. A
 * non-dismissible dialog ignores outside taps and says an action is required.
 *
 * Examples from [org.onekash.kashcal.error.ErrorMapper]:
 * - invalid credentials (Try again / Cancel)
 * - session expired (Sign in, non-dismissible)
 * - storage full (Open settings / Close)
 * - sync conflict (Force sync / Cancel)
 */
@Composable
fun ErrorDialog(
    presentation: ErrorPresentation.Dialog,
    onAction: (ErrorActionCallback) -> Unit,
    onDismiss: () -> Unit
) {
    val message = if (presentation.messageArgs.isNotEmpty()) {
        stringResource(presentation.messageResId, *presentation.messageArgs.toTypedArray())
    } else {
        stringResource(presentation.messageResId)
    }

    AlertDialog(
        onDismissRequest = {
            if (presentation.dismissible) {
                onDismiss()
            }
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
                if (!presentation.dismissible) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.dialog_action_required),
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
 * Shows a one-off error dialog from plain strings; use [ErrorDialog] for an
 * [ErrorPresentation.Dialog].
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
