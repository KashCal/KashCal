package org.onekash.kashcal.ui.permission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the settings contact-sync toggle gate.
 *
 * These lock the two decisions that used to live as inline lambda logic in the
 * settings route, where nothing exercised them and a refactor could silently
 * drop the permission gate:
 *
 *  1. Enabling contact sync must NOT take effect until READ + WRITE_CONTACTS is
 *     held — the toggle requests the permission first and defers the enable.
 *  2. A permission request only counts as granted when BOTH READ and WRITE come
 *     back granted; a partial grant must not enable sync (a write-only or
 *     read-only outcome can't mirror server contacts onto the device).
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
        // Disable must go straight through even if the permission is missing —
        // turning sync off needs no runtime permission.
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
