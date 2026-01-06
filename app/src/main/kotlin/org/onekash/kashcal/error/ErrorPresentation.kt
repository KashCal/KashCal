package org.onekash.kashcal.error

import androidx.annotation.StringRes

/**
 * Defines how an error should be presented to the user.
 *
 * Determined by ErrorMapper based on error type and context.
 * UI layer uses this to decide which component to render.
 */
sealed class ErrorPresentation {

    /**
     * Transient snackbar message.
     * Use for recoverable errors that don't require immediate action.
     */
    data class Snackbar(
        @StringRes val messageResId: Int,
        val messageArgs: Array<Any> = emptyArray(),
        val action: SnackbarAction? = null,
        val duration: SnackbarDuration = SnackbarDuration.Short
    ) : ErrorPresentation() {

        enum class SnackbarDuration { Short, Long, Indefinite }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Snackbar) return false
            return messageResId == other.messageResId &&
                   messageArgs.contentEquals(other.messageArgs) &&
                   action == other.action &&
                   duration == other.duration
        }

        override fun hashCode(): Int {
            var result = messageResId
            result = 31 * result + messageArgs.contentHashCode()
            result = 31 * result + (action?.hashCode() ?: 0)
            result = 31 * result + duration.hashCode()
            return result
        }
    }

    /**
     * Blocking dialog requiring user action.
     * Use for auth errors, critical failures, or confirmation.
     */
    data class Dialog(
        @StringRes val titleResId: Int,
        @StringRes val messageResId: Int,
        val messageArgs: Array<Any> = emptyArray(),
        val primaryAction: DialogAction,
        val secondaryAction: DialogAction? = null,
        val dismissible: Boolean = true
    ) : ErrorPresentation() {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Dialog) return false
            return titleResId == other.titleResId &&
                   messageResId == other.messageResId &&
                   messageArgs.contentEquals(other.messageArgs) &&
                   primaryAction == other.primaryAction &&
                   secondaryAction == other.secondaryAction &&
                   dismissible == other.dismissible
        }

        override fun hashCode(): Int {
            var result = titleResId
            result = 31 * result + messageResId
            result = 31 * result + messageArgs.contentHashCode()
            result = 31 * result + primaryAction.hashCode()
            result = 31 * result + (secondaryAction?.hashCode() ?: 0)
            result = 31 * result + dismissible.hashCode()
            return result
        }
    }

    /**
     * Persistent banner shown at top of screen.
     * Use for ongoing conditions like offline mode.
     */
    data class Banner(
        @StringRes val messageResId: Int,
        val messageArgs: Array<Any> = emptyArray(),
        val type: BannerType = BannerType.Warning,
        val action: BannerAction? = null
    ) : ErrorPresentation() {

        enum class BannerType { Info, Warning, Error }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Banner) return false
            return messageResId == other.messageResId &&
                   messageArgs.contentEquals(other.messageArgs) &&
                   type == other.type &&
                   action == other.action
        }

        override fun hashCode(): Int {
            var result = messageResId
            result = 31 * result + messageArgs.contentHashCode()
            result = 31 * result + type.hashCode()
            result = 31 * result + (action?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * Silent logging without user notification.
     * Use for expected/handled errors that don't affect UX.
     */
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
data class SnackbarAction(
    @StringRes val labelResId: Int,
    val callback: ErrorActionCallback
)

/**
 * Action button for dialog.
 */
data class DialogAction(
    @StringRes val labelResId: Int,
    val callback: ErrorActionCallback,
    val isDismissAction: Boolean = false
)

/**
 * Action button for banner.
 */
data class BannerAction(
    @StringRes val labelResId: Int,
    val callback: ErrorActionCallback
)
