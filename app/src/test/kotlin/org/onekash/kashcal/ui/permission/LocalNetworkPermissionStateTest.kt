package org.onekash.kashcal.ui.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the local-network-permission classifier.
 *
 * Mirrors [ContactsPermissionStateTest]: permanent denial is detected via the
 * rationale-flip signal (rationale offered before the request but not after a
 * denial => "don't ask again"). Adds a [LocalNetworkPermissionState.NotRequired]
 * short-circuit for OS versions below Android 17, where apps with INTERNET
 * implicitly retain local-network access and no runtime prompt exists.
 */
class LocalNetworkPermissionStateTest {

    // ===== classifyAfterRequest =====

    @Test fun `grant short-circuits to Granted`() {
        assertEquals(
            LocalNetworkPermissionState.Granted,
            classifyLocalNetworkAfterRequest(granted = true, rationaleBefore = true, rationaleAfter = false),
        )
    }

    @Test fun `denial with rationale still true is ShouldShowRationale`() {
        assertEquals(
            LocalNetworkPermissionState.ShouldShowRationale,
            classifyLocalNetworkAfterRequest(granted = false, rationaleBefore = false, rationaleAfter = true),
        )
    }

    @Test fun `denial flipping rationale true to false is PermanentlyDenied`() {
        assertEquals(
            LocalNetworkPermissionState.PermanentlyDenied,
            classifyLocalNetworkAfterRequest(granted = false, rationaleBefore = true, rationaleAfter = false),
        )
    }

    @Test fun `denial with no rationale before or after is PermanentlyDenied`() {
        assertEquals(
            LocalNetworkPermissionState.PermanentlyDenied,
            classifyLocalNetworkAfterRequest(granted = false, rationaleBefore = false, rationaleAfter = false),
        )
    }

    // ===== resolveLocalNetworkPermissionState (live read) =====

    @Test fun `resolve - not required on old OS`() {
        assertEquals(
            LocalNetworkPermissionState.NotRequired,
            resolveLocalNetworkPermissionState(permissionRequired = false, granted = false, shouldShowRationale = false),
        )
    }

    @Test fun `resolve - required and granted is Granted`() {
        assertEquals(
            LocalNetworkPermissionState.Granted,
            resolveLocalNetworkPermissionState(permissionRequired = true, granted = true, shouldShowRationale = false),
        )
    }

    @Test fun `resolve - required not granted rationale-askable is ShouldShowRationale`() {
        assertEquals(
            LocalNetworkPermissionState.ShouldShowRationale,
            resolveLocalNetworkPermissionState(permissionRequired = true, granted = false, shouldShowRationale = true),
        )
    }

    @Test fun `resolve - required not granted no rationale is NotRequested`() {
        assertEquals(
            LocalNetworkPermissionState.NotRequested,
            resolveLocalNetworkPermissionState(permissionRequired = true, granted = false, shouldShowRationale = false),
        )
    }

    @Test fun `resolve - revoked-in-settings never stays Granted`() {
        val revoked = resolveLocalNetworkPermissionState(permissionRequired = true, granted = false, shouldShowRationale = true)
        assertFalse(revoked == LocalNetworkPermissionState.Granted)
    }

    // ===== shouldShowLanBanner: proactive banner gate =====

    @Test fun `banner shows for LAN host when not requested`() {
        assertTrue(shouldShowLanBanner(isLan = true, state = LocalNetworkPermissionState.NotRequested))
    }

    @Test fun `banner shows for LAN host when rationale`() {
        assertTrue(shouldShowLanBanner(isLan = true, state = LocalNetworkPermissionState.ShouldShowRationale))
    }

    @Test fun `banner hidden for public host regardless of state`() {
        assertFalse(shouldShowLanBanner(isLan = false, state = LocalNetworkPermissionState.NotRequested))
        assertFalse(shouldShowLanBanner(isLan = false, state = LocalNetworkPermissionState.ShouldShowRationale))
    }

    @Test fun `banner hidden when already granted`() {
        assertFalse(shouldShowLanBanner(isLan = true, state = LocalNetworkPermissionState.Granted))
    }

    @Test fun `banner hidden when not required (old OS)`() {
        assertFalse(shouldShowLanBanner(isLan = true, state = LocalNetworkPermissionState.NotRequired))
    }

    @Test fun `banner hidden when permanently denied - manual entry unaffected`() {
        assertFalse(shouldShowLanBanner(isLan = true, state = LocalNetworkPermissionState.PermanentlyDenied))
    }

    // ===== shouldShowLanHintOnFailure: reactive hint after a connection failure =====
    // Deliberately NOT gated on isLanHost: on API 37 only local-network sockets
    // are permission-blocked, so a connection failure while ungranted IS the
    // signal — and it must fire for bare-hostname LAN servers that isLanHost
    // cannot classify from the string alone.

    @Test fun `reactive hint fires when required and ungranted`() {
        assertTrue(shouldShowLanHintOnFailure(permissionRequired = true, granted = false))
    }

    @Test fun `reactive hint suppressed when already granted`() {
        assertFalse(shouldShowLanHintOnFailure(permissionRequired = true, granted = true))
    }

    @Test fun `reactive hint suppressed on old OS (not required)`() {
        assertFalse(shouldShowLanHintOnFailure(permissionRequired = false, granted = false))
    }

    // ===== reconcileOnResume: upgrade-only reconciliation on resume =====
    // A live read can never be PermanentlyDenied, so resume must not downgrade
    // a PermanentlyDenied (set by the request classifier) back to a
    // banner-showing state — else the banner nags on every app resume.

    @Test fun `resume does NOT downgrade PermanentlyDenied to a banner state`() {
        // Live read after a permanent denial resolves to NotRequested (not
        // granted, no rationale). Must keep PermanentlyDenied.
        assertEquals(
            LocalNetworkPermissionState.PermanentlyDenied,
            reconcileOnResume(
                current = LocalNetworkPermissionState.PermanentlyDenied,
                resolved = LocalNetworkPermissionState.NotRequested,
            ),
        )
    }

    @Test fun `resume applies a grant made in system Settings`() {
        assertEquals(
            LocalNetworkPermissionState.Granted,
            reconcileOnResume(
                current = LocalNetworkPermissionState.PermanentlyDenied,
                resolved = LocalNetworkPermissionState.Granted,
            ),
        )
    }

    @Test fun `resume clears a now-stale Granted when permission was revoked`() {
        assertEquals(
            LocalNetworkPermissionState.ShouldShowRationale,
            reconcileOnResume(
                current = LocalNetworkPermissionState.Granted,
                resolved = LocalNetworkPermissionState.ShouldShowRationale,
            ),
        )
    }

    @Test fun `resume on old OS is NotRequired`() {
        assertEquals(
            LocalNetworkPermissionState.NotRequired,
            reconcileOnResume(
                current = LocalNetworkPermissionState.NotRequested,
                resolved = LocalNetworkPermissionState.NotRequired,
            ),
        )
    }

    @Test fun `resume keeps NotRequested stable (no churn)`() {
        assertEquals(
            LocalNetworkPermissionState.NotRequested,
            reconcileOnResume(
                current = LocalNetworkPermissionState.NotRequested,
                resolved = LocalNetworkPermissionState.NotRequested,
            ),
        )
    }
}
