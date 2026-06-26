package org.onekash.kashcal.ui.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure-logic tests for the contacts-permission classifier.
 *
 * Permanent denial is detected via the rationale-flip signal recommended by
 * the Android docs: if the system would show a rationale BEFORE the request
 * but not AFTER a denial, the user checked "don't ask again". This is more
 * precise than a denial-count threshold. The classifier is a pure function of
 * the two rationale booleans + whether the grant succeeded, so it's unit
 * testable without an Activity.
 */
class ContactsPermissionStateTest {

    @Test
    fun `grant short-circuits to Granted regardless of rationale flip`() {
        assertEquals(
            ContactsPermissionState.Granted,
            classifyAfterRequest(granted = true, rationaleBefore = true, rationaleAfter = false),
        )
    }

    @Test
    fun `denial with rationale still true after stays ShouldShowRationale`() {
        // User denied but didn't check "don't ask again" — can be asked again.
        assertEquals(
            ContactsPermissionState.ShouldShowRationale,
            classifyAfterRequest(granted = false, rationaleBefore = false, rationaleAfter = true),
        )
    }

    @Test
    fun `denial flipping rationale true to false is PermanentlyDenied`() {
        assertEquals(
            ContactsPermissionState.PermanentlyDenied,
            classifyAfterRequest(granted = false, rationaleBefore = true, rationaleAfter = false),
        )
    }

    @Test
    fun `denial with rationale false both before and after is PermanentlyDenied`() {
        // No rationale offered before AND none after a denial = "don't ask
        // again" path (e.g. denied on the very first ask with the checkbox).
        assertEquals(
            ContactsPermissionState.PermanentlyDenied,
            classifyAfterRequest(granted = false, rationaleBefore = false, rationaleAfter = false),
        )
    }

    @Test
    fun `denial with rationale true before and true after is ShouldShowRationale`() {
        assertEquals(
            ContactsPermissionState.ShouldShowRationale,
            classifyAfterRequest(granted = false, rationaleBefore = true, rationaleAfter = true),
        )
    }

    // ===== resolveContactsPermissionState: live state recomputed on each open =====
    // (granted, shouldShowRationale) → state. This is what the form-open path
    // uses so a grant/revoke made in system Settings is always reflected.

    @Test
    fun `live resolve - granted is Granted`() {
        assertEquals(
            ContactsPermissionState.Granted,
            resolveContactsPermissionState(granted = true, shouldShowRationale = false),
        )
    }

    @Test
    fun `live resolve - not granted but rationale-askable is ShouldShowRationale`() {
        assertEquals(
            ContactsPermissionState.ShouldShowRationale,
            resolveContactsPermissionState(granted = false, shouldShowRationale = true),
        )
    }

    @Test
    fun `live resolve - not granted and no rationale is NotRequested`() {
        assertEquals(
            ContactsPermissionState.NotRequested,
            resolveContactsPermissionState(granted = false, shouldShowRationale = false),
        )
    }

    @Test
    fun `live resolve - revoked-in-settings never stays Granted`() {
        // The bug: a previously-granted user revokes in system Settings, then
        // reopens the form. checkSelfPermission now reports false — the resolved
        // state must NOT be Granted (else we query a revoked permission and the
        // re-request banner never returns).
        val revoked = resolveContactsPermissionState(granted = false, shouldShowRationale = true)
        assertNotEquals(ContactsPermissionState.Granted, revoked)
    }
}
