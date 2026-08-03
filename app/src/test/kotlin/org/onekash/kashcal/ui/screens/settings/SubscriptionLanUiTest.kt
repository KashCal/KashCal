package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.ui.permission.LocalNetworkPermissionState

/**
 * Pure-logic tests for the add-subscription dialog's local-network UI decision,
 * which composes the shipped CalDAV helpers (isLanHost / shouldShowLanBanner /
 * shouldShowLanHintOnFailure) for the ICS "Add subscription" context.
 *
 * The reactive hint deliberately is NOT gated on isLanHost — a connection
 * failure while the permission is required-but-ungranted is itself the signal,
 * so a LAN server addressed by a bare hostname (which isLanHost can't classify)
 * still gets the Allow-access affordance. This mirrors CalDAV's
 * shouldShowLanHintOnFailure contract.
 */
class SubscriptionLanUiTest {

    private val lanUrl = "http://192.168.178.82:8989/feed/v3/calendar/sonarr.ics?apikey=abc"
    private val publicUrl = "https://example.com/holidays.ics"
    private val bareHostUrl = "http://sonarr.mylan/feed.ics"

    // (a) Literal LAN URL, permission not yet requested -> proactive banner, no hint.
    @Test fun `literal LAN url with NotRequested shows banner without hint`() {
        val ui = resolveSubscriptionLanUi(
            url = lanUrl,
            connectionFailed = false,
            state = LocalNetworkPermissionState.NotRequested,
            bannerDismissed = false,
        )
        assertTrue("proactive banner for a literal LAN url", ui.showBanner)
        assertFalse("no hint until a fetch has failed", ui.appendLanHint)
    }

    // (b) Public URL, nothing failed -> neither.
    @Test fun `public url with no failure shows nothing`() {
        val ui = resolveSubscriptionLanUi(
            url = publicUrl,
            connectionFailed = false,
            state = LocalNetworkPermissionState.NotRequested,
            bannerDismissed = false,
        )
        assertFalse(ui.showBanner)
        assertFalse(ui.appendLanHint)
    }

    // A server-responded failure (HTTP error, empty body, non-calendar) proves the
    // socket connected, so it must NOT arm the LAN signal even for a LAN-looking url
    // while ungranted. Only a connection-level failure does.
    @Test fun `non-connection failure does not arm banner or hint`() {
        val ui = resolveSubscriptionLanUi(
            url = lanUrl,
            connectionFailed = false,
            state = LocalNetworkPermissionState.NotRequested,
            bannerDismissed = false,
        )
        // The literal LAN url still shows the proactive banner (that is url-driven,
        // not failure-driven), but the reactive hint must stay off.
        assertTrue("proactive banner is url-driven, unaffected", ui.showBanner)
        assertFalse("a server-responded failure must not append the LAN hint", ui.appendLanHint)
    }

    @Test fun `non-connection failure on bare host arms neither`() {
        val ui = resolveSubscriptionLanUi(
            url = bareHostUrl,
            connectionFailed = false,
            state = LocalNetworkPermissionState.NotRequested,
            bannerDismissed = false,
        )
        assertFalse("no reactive banner without a connection-level failure", ui.showBanner)
        assertFalse("no hint without a connection-level failure", ui.appendLanHint)
    }

    // (c) Public/bare-host URL that FAILED while required-but-ungranted -> reactive
    // banner + hint, proving the hint is NOT gated on isLanHost.
    @Test fun `bare-host url failure while ungranted arms banner and hint`() {
        val ui = resolveSubscriptionLanUi(
            url = bareHostUrl,
            connectionFailed = true,
            state = LocalNetworkPermissionState.NotRequested,
            bannerDismissed = false,
        )
        assertTrue("reactive banner after a blocked-LAN-looking failure", ui.showBanner)
        assertTrue("hint appended after a blocked-LAN-looking failure", ui.appendLanHint)
    }

    @Test fun `public url failure while ungranted still arms hint (not gated on isLanHost)`() {
        val ui = resolveSubscriptionLanUi(
            url = publicUrl,
            connectionFailed = true,
            state = LocalNetworkPermissionState.ShouldShowRationale,
            bannerDismissed = false,
        )
        assertTrue(ui.showBanner)
        assertTrue(ui.appendLanHint)
    }

    // (d) Pre-Android-17: NotRequired short-circuits everything, even on failure.
    @Test fun `NotRequired shows nothing even for LAN url on failure`() {
        val ui = resolveSubscriptionLanUi(
            url = lanUrl,
            connectionFailed = true,
            state = LocalNetworkPermissionState.NotRequired,
            bannerDismissed = false,
        )
        assertFalse("pre-37 never shows the banner", ui.showBanner)
        assertFalse("pre-37 never appends the hint", ui.appendLanHint)
    }

    // (e) Already granted -> nothing to ask for.
    @Test fun `Granted shows nothing for LAN url`() {
        val ui = resolveSubscriptionLanUi(
            url = lanUrl,
            connectionFailed = true,
            state = LocalNetworkPermissionState.Granted,
            bannerDismissed = false,
        )
        assertFalse(ui.showBanner)
        assertFalse(ui.appendLanHint)
    }

    // (f) PermanentlyDenied: no proactive banner (nagging adds nothing), but the
    // hint still explains a failure since the permission is required-and-ungranted.
    @Test fun `PermanentlyDenied suppresses banner but still hints on failure`() {
        val proactive = resolveSubscriptionLanUi(
            url = lanUrl,
            connectionFailed = false,
            state = LocalNetworkPermissionState.PermanentlyDenied,
            bannerDismissed = false,
        )
        assertFalse("no nagging banner when permanently denied", proactive.showBanner)
        assertFalse(proactive.appendLanHint)

        val onFailure = resolveSubscriptionLanUi(
            url = lanUrl,
            connectionFailed = true,
            state = LocalNetworkPermissionState.PermanentlyDenied,
            bannerDismissed = false,
        )
        assertFalse("still no banner when permanently denied", onFailure.showBanner)
        assertTrue("but the failure is still explained", onFailure.appendLanHint)
    }

    // (g) Dismissing the banner suppresses it, but the hint still reflects failure.
    @Test fun `bannerDismissed hides banner but not the hint`() {
        val ui = resolveSubscriptionLanUi(
            url = lanUrl,
            connectionFailed = true,
            state = LocalNetworkPermissionState.NotRequested,
            bannerDismissed = true,
        )
        assertFalse("user dismissed the banner for this session", ui.showBanner)
        assertTrue("hint is independent of banner dismissal", ui.appendLanHint)
    }
}
