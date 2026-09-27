package org.onekash.kashcal.error

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

/** Says how the UI shows an error; [ErrorMapper.toPresentation] picks one per [CalendarError]. */
@Immutable
sealed class ErrorPresentation {

    /** A transient snackbar, for recoverable errors that need no immediate action. */
    @Immutable
    data class Snackbar(
        @StringRes val messageResId: Int,
        val messageArgs: List<Any> = emptyList(),
        val action: SnackbarAction? = null,
        val duration: SnackbarDuration = SnackbarDuration.Short
    ) : ErrorPresentation() {

        enum class SnackbarDuration { Short, Long, Indefinite }
    }

    /** A blocking dialog, for errors the user must act on. */
    @Immutable
    data class Dialog(
        @StringRes val titleResId: Int,
        @StringRes val messageResId: Int,
        val messageArgs: List<Any> = emptyList(),
        val primaryAction: DialogAction,
        val secondaryAction: DialogAction? = null,
        val dismissible: Boolean = true
    ) : ErrorPresentation()

    /** A persistent banner at the top of the screen, for an ongoing condition. */
    @Immutable
    data class Banner(
        @StringRes val messageResId: Int,
        val messageArgs: List<Any> = emptyList(),
        val type: BannerType = BannerType.Warning,
        val action: BannerAction? = null
    ) : ErrorPresentation() {

        enum class BannerType { Info, Warning, Error }
    }

    /** Logged only, not shown, for expected errors that don't affect the user. */
    @Immutable
    data class Silent(
        val logMessage: String,
        val logLevel: LogLevel = LogLevel.Warning
    ) : ErrorPresentation() {
        enum class LogLevel { Debug, Info, Warning, Error }
    }
}

/** An action button on a [ErrorPresentation.Snackbar]. */
@Immutable
data class SnackbarAction(
    @StringRes val labelResId: Int,
    val callback: ErrorActionCallback
)

/** An action button on a [ErrorPresentation.Dialog]. */
@Immutable
data class DialogAction(
    @StringRes val labelResId: Int,
    val callback: ErrorActionCallback,
    val isDismissAction: Boolean = false
)

/** An action button on a [ErrorPresentation.Banner]. */
@Immutable
data class BannerAction(
    @StringRes val labelResId: Int,
    val callback: ErrorActionCallback
)
