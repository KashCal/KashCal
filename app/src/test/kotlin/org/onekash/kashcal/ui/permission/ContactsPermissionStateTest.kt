package org.onekash.kashcal.ui.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests [classifyAfterRequest] and [resolveContactsPermissionState], pure functions testable
 * without an Activity.
 *
 * Permanent denial is detected from the rationale signal recommended by the Android docs: a
 * denial with no rationale afterwards means the user chose "don't ask again", whether the
 * rationale flipped from true or was false before too. This is more precise than a
 * denial-count threshold. The classifier keys on the grant and the post-request rationale;
 * rationaleBefore doesn't change the result.
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
        // The user denied without "don't ask again", so can be asked again.
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
        // No rationale before and none after a denial is "don't ask again", for example a
        // denial on the first ask with the checkbox ticked.
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
    // Maps (granted, shouldShowRationale) to a state. The event form's open path uses it so a
    // grant or revoke made in system Settings always shows.

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
        // A user who granted revokes in system Settings, then reopens the form, so
        // checkSelfPermission reports false. The resolved state must not be Granted, or the
        // form queries a revoked permission and the re-request banner never returns.
        val revoked = resolveContactsPermissionState(granted = false, shouldShowRationale = true)
        assertNotEquals(ContactsPermissionState.Granted, revoked)
    }
}
