package org.onekash.kashcal.ui.screens.settings

import androidx.compose.runtime.Immutable
import org.onekash.kashcal.ui.util.UiMessage

/**
 * Holds the CalDAV sign-in sheet's state.
 *
 * 1. [NotConnected]: the user enters server, display name, username and password.
 * 2. [Discovering]: the server is queried for calendars, then the account is added with all of
 *    them.
 * 3. On success the state resets to [NotConnected], the sign-in sheet closes and
 *    [AccountConnectedSheet] shows. On failure it returns to [NotConnected] with an error.
 */
@Immutable
sealed class CalDavConnectionState {

    /** Waits for the user's input; [error] and [errorField] hold the last failure. */
    data class NotConnected(
        val serverUrl: String = "",
        val displayName: String = "",
        val username: String = "",
        val password: String = "",
        val trustInsecure: Boolean = false,
        val error: UiMessage? = null,
        val errorField: ErrorField? = null
    ) : CalDavConnectionState()

    /** Discovers the server's calendars, then adds the account with all of them. */
    data class Discovering(
        val serverUrl: String,
        val username: String
    ) : CalDavConnectionState()

    /** Names the field an error belongs to. */
    enum class ErrorField {
        SERVER,
        CREDENTIALS,
        PASSWORD,
        DISPLAY_NAME
    }
}

/** Holds one connected CalDAV account for its row in Settings. */
@Immutable
data class CalDavAccountUiModel(
    val id: Long,
    val email: String,
    val displayName: String,
    val calendarCount: Int,
    val consecutiveSyncFailures: Int = 0,
    val lastSuccessfulSyncAt: Long? = null
)
