package org.onekash.kashcal.ui.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the local-network permission logic: the request classifier, the live resolve, the
 * proactive banner gate, the reactive failure hint and resume reconciliation.
 *
 * Mirrors [ContactsPermissionStateTest]: a denial with no rationale afterwards is "don't ask
 * again". Adds a [LocalNetworkPermissionState.NotRequired] short-circuit below Android 17,
 * where apps with INTERNET keep implicit local-network access and no runtime prompt exists.
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
    // Deliberately not gated on isLanHost: on API 37 only local-network sockets are
    // permission-blocked, so a connection failure while ungranted is the signal, and it must
    // fire for bare-hostname LAN servers isLanHost can't classify from the string alone.

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
    // A live read is never PermanentlyDenied, so resume must not turn a PermanentlyDenied set
    // by the request classifier back into a banner-showing state, or the banner nags on every
    // resume.

    @Test fun `resume does NOT downgrade PermanentlyDenied to a banner state`() {
        // A live read after a permanent denial resolves to NotRequested (not granted, no
        // rationale); PermanentlyDenied must stay.
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
