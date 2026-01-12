package org.onekash.kashcal.ui.components

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.onekash.kashcal.error.ErrorActionCallback
import org.onekash.kashcal.error.ErrorPresentation

/**
 * Error snackbar host that displays ErrorPresentation.Snackbar.
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
 * Design:
 * - Uses Material 3 Snackbar with action button
 * - Supports Short, Long, and Indefinite durations
 * - Action button triggers callback through ViewModel
 * - Dismissal clears error state
 */
@Composable
fun ErrorSnackbarHost(
    hostState: SnackbarHostState,
    errorPresentation: ErrorPresentation.Snackbar?,
    onAction: (ErrorActionCallback) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Show snackbar when error changes
    LaunchedEffect(errorPresentation) {
        if (errorPresentation != null) {
            val result = hostState.showSnackbar(
                message = "", // We use custom snackbar, message is resolved in composable
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
        // Custom snackbar with resolved string resources
        if (errorPresentation != null) {
            ErrorSnackbarContent(
                presentation = errorPresentation,
                snackbarData = snackbarData,
                onAction = onAction
            )
        }
    }
}

/**
 * Custom snackbar content that resolves string resources.
 */
@Composable
private fun ErrorSnackbarContent(
    presentation: ErrorPresentation.Snackbar,
    snackbarData: SnackbarData,
    onAction: (ErrorActionCallback) -> Unit
) {
    val message = if (presentation.messageArgs.isNotEmpty()) {
        stringResource(presentation.messageResId, *presentation.messageArgs)
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
 * Simplified snackbar for showing error message with optional action.
 *
 * Use this when you need more control over when to show/hide.
 * For automatic state management, use ErrorSnackbarHost.
 */
@Composable
fun ErrorSnackbar(
    presentation: ErrorPresentation.Snackbar,
    onAction: (ErrorActionCallback) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val message = if (presentation.messageArgs.isNotEmpty()) {
        stringResource(presentation.messageResId, *presentation.messageArgs)
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
                Text("Dismiss")
            }
        }
    ) {
        Text(message)
    }
}
