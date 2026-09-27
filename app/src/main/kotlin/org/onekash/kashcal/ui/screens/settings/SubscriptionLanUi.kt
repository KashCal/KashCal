package org.onekash.kashcal.ui.screens.settings

import org.onekash.kashcal.ui.permission.LocalNetworkPermissionState
import org.onekash.kashcal.ui.permission.failureIndicatesBlockedLan
import org.onekash.kashcal.ui.permission.shouldShowLanBanner
import org.onekash.kashcal.util.isLanHost

/**
 * Describes what the add-subscription dialog surfaces for Android 17+ local-network access.
 *
 * @property showBanner render the inline Allow-access banner.
 * @property appendLanHint wrap the fetch error with the local-network hint.
 */
data class SubscriptionLanUi(
    val showBanner: Boolean,
    val appendLanHint: Boolean,
)

/**
 * Decides the local-network UI for the add-subscription dialog from the CalDAV helpers; no new
 * policy lives here.
 *
 * It follows the CalDAV sign-in flow (SettingsRoute and AccountSettingsViewModel):
 * - The banner shows for a recognizably local URL, or after a connection failure that looks
 *   like a blocked LAN socket. That failure is fed as the `isLan` input to
 *   [shouldShowLanBanner], as CalDAV feeds its `lanHintActive` flag, so the banner hides itself
 *   when the permission is granted, permanently denied or not required.
 * - The hint is appended on a connection failure whenever the permission is required but not
 *   granted. It is deliberately not gated on [isLanHost]: on Android 17 only local-network
 *   sockets are permission-blocked, so that failure is itself the signal, and it covers
 *   bare-hostname and custom-domain LAN servers that string classification can't detect.
 *
 * @param connectionFailed [FetchCalendarState.Error.connectionFailed]. Only this arms the
 *   failure signal: an HTTP error, an empty body or a non-calendar response proves the socket
 *   connected, so it must not.
 * @param bannerDismissed the user dismissed the banner for this dialog session.
 */
fun resolveSubscriptionLanUi(
    url: String,
    connectionFailed: Boolean,
    state: LocalNetworkPermissionState,
    bannerDismissed: Boolean,
): SubscriptionLanUi {
    val blockedLanFailure = connectionFailed && state.failureIndicatesBlockedLan()

    val showBanner = !bannerDismissed &&
        shouldShowLanBanner(isLanHost(url) || blockedLanFailure, state)

    return SubscriptionLanUi(
        showBanner = showBanner,
        appendLanHint = blockedLanFailure,
    )
}
