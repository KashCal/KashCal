package org.onekash.kashcal.ui.permission

/**
 * Decision helpers for the per-account contact-sync toggle in settings.
 *
 * Contact sync writes CardDAV contacts to the device and reads device edits back, so it needs
 * READ_CONTACTS and WRITE_CONTACTS. The gates are pure functions so `ContactSyncToggleGateTest`
 * covers them without a Compose harness; losing a gate would silently enable sync, and its
 * immediate pull, without the permission.
 */

/**
 * Returns whether toggling contact sync must request the permission before the enable applies.
 *
 * Only an enable without the permission needs a request; the caller defers the enable until the
 * request returns granted. Disabling needs no permission, and an enable with it goes straight
 * through.
 */
fun contactSyncToggleRequiresPermissionRequest(
    enabled: Boolean,
    hasContactsPermission: Boolean,
): Boolean = enabled && !hasContactsPermission

/**
 * Returns whether a contacts permission result allows sync: both READ and WRITE must be granted.
 * A partial grant can't sync and must not turn the toggle on.
 */
fun contactSyncPermissionGranted(
    readGranted: Boolean,
    writeGranted: Boolean,
): Boolean = readGranted && writeGranted
