package org.onekash.kashcal.ui.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.platform.LocalContext

/**
 * Localizable user-facing message resolved at render time in the UI layer.
 *
 * Use [Literal] for server-provided text that is not an app resource.
 */
@Stable
sealed class UiMessage {
    data class ResId(
        @StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiMessage()

    data class Literal(val text: String) : UiMessage()
}

/**
 * Non-Composable resolver for callers outside composition (e.g. `semantics { }`,
 * snackbar workers, notifications).
 */
fun UiMessage.resolve(context: Context): String = when (this) {
    is UiMessage.ResId ->
        if (args.isEmpty()) context.getString(id)
        else context.getString(id, *args.toTypedArray())
    is UiMessage.Literal -> text
}

@Composable
fun UiMessage.asString(): String = resolve(LocalContext.current)
