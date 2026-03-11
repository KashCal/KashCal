package org.onekash.kashcal.error

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

/**
 * Defines how an error should be presented to the user.
 *
 * Determined by ErrorMapper based on error type and context.
 * UI layer uses this to decide which component to render.
 */
@Immutable
sealed class ErrorPresentation {

    /**
     * Transient snackbar message.
     * Use for recoverable errors that don't require immediate action.
     */
    @Immutable
    data class Snackbar(
        @StringRes val messageResId: Int,
        val messageArgs: List<Any> = emptyList(),
        val action: SnackbarAction? = null,
        val duration: SnackbarDuration = SnackbarDuration.Short
    ) : ErrorPresentation() {

        enum class SnackbarDuration { Short, Long, Indefinite }
    }

    /**
     * Blocking dialog requiring user action.
     * Use for auth errors, critical failures, or confirmation.
     */
    @Immutable
    data class Dialog(
        @StringRes val titleResId: Int,
        @StringRes val messageResId: Int,
        val messageArgs: List<Any> = emptyList(),
        val primaryAction: DialogAction,
        val secondaryAction: DialogAction? = null,
        val dismissible: Boolean = true
    ) : ErrorPresentation()

    /**
     * Persistent banner shown at top of screen.
     * Use for ongoing conditions like offline mode.
     */
    @Immutable
    data class Banner(
        @StringRes val messageResId: Int,
        val messageArgs: List<Any> = emptyList(),
        val type: BannerType = BannerType.Warning,
        val action: BannerAction? = null
    ) : ErrorPresentation() {

        enum class BannerType { Info, Warning, Error }
    }

    /**
     * Silent logging without user notification.
     * Use for expected/handled errors that don't affect UX.
     */
    @Immutable
    data class Silent(
        val logMessage: String,
        val logLevel: LogLevel = LogLevel.Warning
    ) : ErrorPresentation() {
        enum class LogLevel { Debug, Info, Warning, Error }
    }
}

/**
 * Action button for snackbar.
 */
@Immutable
data class SnackbarAction(
    @StringRes val labelResId: Int,
    val callback: ErrorActionCallback
)

/**
 * Action button for dialog.
 */
@Immutable
data class DialogAction(
    @StringRes val labelResId: Int,
    val callback: ErrorActionCallback,
    val isDismissAction: Boolean = false
)

/**
 * Action button for banner.
 */
@Immutable
data class BannerAction(
    @StringRes val labelResId: Int,
    val callback: ErrorActionCallback
)
