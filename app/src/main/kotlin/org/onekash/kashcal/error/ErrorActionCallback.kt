package org.onekash.kashcal.error

/**
 * Callbacks for error action buttons.
 *
 * ViewModel handles these callbacks and performs appropriate actions.
 * Using sealed class ensures exhaustive handling.
 */
sealed class ErrorActionCallback {
    /** Retry the failed operation */
    data object Retry : ErrorActionCallback()

    /** Navigate to app settings screen */
    data object OpenSettings : ErrorActionCallback()

    /** Open Android system settings for this app */
    data object OpenAppSettings : ErrorActionCallback()

    /** Open Apple ID website for app-specific password */
    data object OpenAppleIdWebsite : ErrorActionCallback()

    /** Show sign-in/authentication flow */
    data object ReAuthenticate : ErrorActionCallback()

    /** Trigger force full sync */
    data object ForceFullSync : ErrorActionCallback()

    /** Show sync debug/details view */
    data object ViewSyncDetails : ErrorActionCallback()

    /** Dismiss the error UI */
    data object Dismiss : ErrorActionCallback()

    /** Custom action with lambda */
    data class Custom(val action: () -> Unit) : ErrorActionCallback()
}
