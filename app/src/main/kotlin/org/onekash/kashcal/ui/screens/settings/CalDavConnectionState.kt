package org.onekash.kashcal.ui.screens.settings

/**
 * State for CalDAV sign-in flow.
 *
 * Simplified flow (v21.5.0):
 * 1. NotConnected: User enters server/username/password
 * 2. Discovering: Querying server for calendars, then auto-adds all
 * 3. Success: Account added, success sheet shown, sign-in sheet dismissed
 */
sealed class CalDavConnectionState {

    /**
     * Initial state - waiting for user to enter credentials.
     */
    data class NotConnected(
        val serverUrl: String = "",
        val displayName: String = "",
        val username: String = "",
        val password: String = "",
        val trustInsecure: Boolean = false,
        val error: String? = null,
        val errorField: ErrorField? = null
    ) : CalDavConnectionState()

    /**
     * Discovering calendars from server, then auto-adding account.
     */
    data class Discovering(
        val serverUrl: String,
        val username: String
    ) : CalDavConnectionState()

    /**
     * Which field has an error.
     */
    enum class ErrorField {
        SERVER,
        CREDENTIALS,
        PASSWORD,
        DISPLAY_NAME
    }
}

/**
 * UI model for displaying connected CalDAV accounts in Settings.
 */
data class CalDavAccountUiModel(
    val id: Long,
    val email: String,
    val displayName: String,
    val calendarCount: Int
)
