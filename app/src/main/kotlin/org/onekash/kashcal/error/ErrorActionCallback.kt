package org.onekash.kashcal.error

import androidx.compose.runtime.Stable

/**
 * Names the action an error button asks for; `HomeViewModel.handleErrorAction` dispatches it.
 * There [Retry] runs a sync whatever failed, and [OpenSettings], [OpenAppSettings],
 * [OpenAppleIdWebsite] and [ReAuthenticate] only clear the error.
 *
 * `@Stable`, not `@Immutable`, because [Custom] holds a lambda.
 */
@Stable
sealed class ErrorActionCallback {
    /** Retries the failed operation. */
    data object Retry : ErrorActionCallback()

    /** Opens the app's settings screen. */
    data object OpenSettings : ErrorActionCallback()

    /** Opens Android's system settings for this app. */
    data object OpenAppSettings : ErrorActionCallback()

    /** Opens the Apple ID website, where app-specific passwords are made. */
    data object OpenAppleIdWebsite : ErrorActionCallback()

    /** Shows the sign-in flow. */
    data object ReAuthenticate : ErrorActionCallback()

    /** Forces a full sync. */
    data object ForceFullSync : ErrorActionCallback()

    /** Shows the sync details. */
    data object ViewSyncDetails : ErrorActionCallback()

    /** Dismisses the error. */
    data object Dismiss : ErrorActionCallback()

    /** Opens [url] in the browser. */
    data class OpenUrl(val url: String) : ErrorActionCallback()

    /** Runs [action]. */
    data class Custom(val action: () -> Unit) : ErrorActionCallback()
}
