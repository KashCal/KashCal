package org.onekash.kashcal.ui.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.platform.LocalContext

/**
 * Holds a user-facing message as a string resource or literal text, turned into text by
 * [asString] in composition or [resolve] outside it.
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
 * Resolves the message with [context], for callers outside composition such as
 * `AccountSettingsViewModel` building an error message.
 */
fun UiMessage.resolve(context: Context): String = when (this) {
    is UiMessage.ResId ->
        if (args.isEmpty()) context.getString(id)
        else context.getString(id, *args.toTypedArray())
    is UiMessage.Literal -> text
}

@Composable
fun UiMessage.asString(): String = resolve(LocalContext.current)
