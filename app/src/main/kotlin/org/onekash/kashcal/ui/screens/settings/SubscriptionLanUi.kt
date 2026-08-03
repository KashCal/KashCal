package org.onekash.kashcal.ui.screens.settings

import org.onekash.kashcal.ui.permission.LocalNetworkPermissionState
import org.onekash.kashcal.ui.permission.failureIndicatesBlockedLan
import org.onekash.kashcal.ui.permission.shouldShowLanBanner
import org.onekash.kashcal.util.isLanHost

/**
 * What the add-subscription dialog should surface for Android 17+ local-network
 * access, given the entered URL, whether the validation fetch just failed, and
 * the current permission state.
 *
 * @property showBanner render the inline Allow-access banner.
 * @property appendLanHint wrap the fetch error with the local-network hint.
 */
data class SubscriptionLanUi(
    val showBanner: Boolean,
    val appendLanHint: Boolean,
)

/**
 * Decide the local-network UI for the add-subscription dialog by composing the
 * shipped CalDAV helpers — no new policy lives here.
 *
 * Mirrors the CalDAV sign-in flow (SettingsRoute + AccountSettingsViewModel):
 * - The banner shows proactively for a recognizably-local URL, OR reactively
 *   after a connection-level fetch failure that looks like a blocked LAN socket.
 *   The reactive arm is fed as the `isLan` input to [shouldShowLanBanner] exactly
 *   as CalDAV feeds its `lanHintActive` flag, so it self-suppresses when already
 *   granted / permanently denied / not required.
 * - The hint is appended on a connection-level failure whenever the permission
 *   is required-but-ungranted. Deliberately NOT gated on [isLanHost]: on Android
 *   17 only local-network sockets are permission-blocked, so a connection failure
 *   while the permission is required and ungranted is itself the signal — this
 *   serves bare-hostname / custom-domain LAN servers string classification can't
 *   detect.
 *
 * @param connectionFailed the validation fetch failed at the socket layer (never
 *   reached the server). Only this arms the reactive signal — an HTTP error,
 *   empty body, or non-calendar response proves the socket connected and must
 *   not, mirroring CalDAV's DiscoveryResult.Error vs AuthError split.
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
