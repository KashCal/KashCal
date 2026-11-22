package org.onekash.kashcal.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Shows the pending snackbar [message] on [hostState], with a button labelled [actionLabel] when
 * [onAction] is set. Reports a tap through [onAction], then the message's end (a tap or the
 * timeout) through [onDone] with its [id].
 *
 * Keyed on [id] and on whether a message is pending: a newer id, or the message being cleared,
 * cancels the shown snackbar, which takes it off screen and reports nothing for it. So a result
 * never reaches the state holder for a message it has already replaced or withdrawn.
 */
@Composable
fun PendingSnackbarEffect(
    hostState: SnackbarHostState,
    message: String?,
    id: Long,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    onDone: (Long) -> Unit,
) {
    LaunchedEffect(id, message != null) {
        if (message == null) return@LaunchedEffect
        val result = hostState.showSnackbar(
            message = message,
            actionLabel = if (onAction != null) actionLabel else null,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        onDone(id)
    }
}
