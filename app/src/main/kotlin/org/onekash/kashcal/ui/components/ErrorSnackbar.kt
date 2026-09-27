package org.onekash.kashcal.ui.components

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.onekash.kashcal.R
import org.onekash.kashcal.error.ErrorActionCallback
import org.onekash.kashcal.error.ErrorPresentation

/**
 * Shows [errorPresentation] in a snackbar each time it changes to a non-null value.
 *
 * Usage:
 * ```
 * val snackbarHostState = remember { SnackbarHostState() }
 *
 * ErrorSnackbarHost(
 *     hostState = snackbarHostState,
 *     errorPresentation = uiState.currentError as? ErrorPresentation.Snackbar,
 *     onAction = { callback -> viewModel.handleErrorAction(callback) },
 *     onDismiss = { viewModel.clearError() }
 * )
 * ```
 *
 * The action button calls [onAction]; a snackbar dismissed without its action, on timeout for
 * example, calls [onDismiss].
 */
@Composable
fun ErrorSnackbarHost(
    hostState: SnackbarHostState,
    errorPresentation: ErrorPresentation.Snackbar?,
    onAction: (ErrorActionCallback) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(errorPresentation) {
        if (errorPresentation != null) {
            val result = hostState.showSnackbar(
                message = "", // ErrorSnackbarContent resolves the message from resources
                actionLabel = if (errorPresentation.action != null) "" else null,
                duration = when (errorPresentation.duration) {
                    ErrorPresentation.Snackbar.SnackbarDuration.Short -> SnackbarDuration.Short
                    ErrorPresentation.Snackbar.SnackbarDuration.Long -> SnackbarDuration.Long
                    ErrorPresentation.Snackbar.SnackbarDuration.Indefinite -> SnackbarDuration.Indefinite
                }
            )

            when (result) {
                SnackbarResult.ActionPerformed -> {
                    errorPresentation.action?.let { action ->
                        onAction(action.callback)
                    }
                }
                SnackbarResult.Dismissed -> {
                    onDismiss()
                }
            }
        }
    }

    SnackbarHost(
        hostState = hostState,
        modifier = modifier
    ) { snackbarData ->
        if (errorPresentation != null) {
            ErrorSnackbarContent(
                presentation = errorPresentation,
                snackbarData = snackbarData,
                onAction = onAction
            )
        }
    }
}

/** Draws the snackbar with its message and action label resolved from resources. */
@Composable
private fun ErrorSnackbarContent(
    presentation: ErrorPresentation.Snackbar,
    snackbarData: SnackbarData,
    onAction: (ErrorActionCallback) -> Unit
) {
    val message = if (presentation.messageArgs.isNotEmpty()) {
        stringResource(presentation.messageResId, *presentation.messageArgs.toTypedArray())
    } else {
        stringResource(presentation.messageResId)
    }

    Snackbar(
        action = if (presentation.action != null) {
            {
                TextButton(
                    onClick = {
                        onAction(presentation.action.callback)
                        snackbarData.performAction()
                    }
                ) {
                    Text(stringResource(presentation.action.labelResId))
                }
            }
        } else null
    ) {
        Text(message)
    }
}

/**
 * Shows [presentation] as a snackbar with a Dismiss button, for callers that control when it
 * shows. [ErrorSnackbarHost] shows and dismisses it from state.
 */
@Composable
fun ErrorSnackbar(
    presentation: ErrorPresentation.Snackbar,
    onAction: (ErrorActionCallback) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val message = if (presentation.messageArgs.isNotEmpty()) {
        stringResource(presentation.messageResId, *presentation.messageArgs.toTypedArray())
    } else {
        stringResource(presentation.messageResId)
    }

    Snackbar(
        modifier = modifier,
        action = if (presentation.action != null) {
            {
                TextButton(
                    onClick = {
                        onAction(presentation.action.callback)
                        onDismiss()
                    }
                ) {
                    Text(stringResource(presentation.action.labelResId))
                }
            }
        } else null,
        dismissAction = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_dismiss))
            }
        }
    ) {
        Text(message)
    }
}
