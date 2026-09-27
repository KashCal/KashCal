package org.onekash.kashcal.ui.permission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the contact-sync toggle gates in settings, so a refactor can't silently drop the
 * permission gate:
 *
 *  1. [contactSyncToggleRequiresPermissionRequest]: enabling contact sync without the contacts
 *     permission requests it first, and the caller defers the enable until the result. An
 *     enable with the permission held and any disable don't request.
 *  2. [contactSyncPermissionGranted]: a request counts as granted only when both READ and
 *     WRITE_CONTACTS come back granted. A partial grant can't sync and must not enable it.
 */
class ContactSyncToggleGateTest {

    // ---- contactSyncToggleRequiresPermissionRequest ----

    @Test
    fun `enabling without contacts permission must request first`() {
        assertTrue(
            contactSyncToggleRequiresPermissionRequest(enabled = true, hasContactsPermission = false),
        )
    }

    @Test
    fun `enabling with permission already held does not re-prompt`() {
        assertFalse(
            contactSyncToggleRequiresPermissionRequest(enabled = true, hasContactsPermission = true),
        )
    }

    @Test
    fun `disabling never requests permission`() {
        // Disabling goes straight through even without the permission: turning sync off
        // needs none.
        assertFalse(
            contactSyncToggleRequiresPermissionRequest(enabled = false, hasContactsPermission = false),
        )
        assertFalse(
            contactSyncToggleRequiresPermissionRequest(enabled = false, hasContactsPermission = true),
        )
    }

    // ---- contactSyncPermissionGranted ----

    @Test
    fun `both read and write granted counts as granted`() {
        assertTrue(contactSyncPermissionGranted(readGranted = true, writeGranted = true))
    }

    @Test
    fun `write-only grant is not enough`() {
        assertFalse(contactSyncPermissionGranted(readGranted = false, writeGranted = true))
    }

    @Test
    fun `read-only grant is not enough`() {
        assertFalse(contactSyncPermissionGranted(readGranted = true, writeGranted = false))
    }

    @Test
    fun `neither granted is not granted`() {
        assertFalse(contactSyncPermissionGranted(readGranted = false, writeGranted = false))
    }
}
