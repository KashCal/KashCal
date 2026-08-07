package org.onekash.kashcal.ui.permission

/**
 * Decision helpers for the settings per-account contact-sync toggle.
 *
 * Contact sync mirrors CardDAV server contacts onto the device, which needs
 * READ + WRITE_CONTACTS. These pure functions capture the two gate decisions so
 * the settings route can delegate to them and they can be unit-tested without a
 * Compose harness — the inline lambda that used to hold this logic was
 * unexercised and a refactor could have dropped the gate, silently enabling
 * sync (and its immediate pull) without the permission.
 */

/**
 * Whether toggling the contact-sync switch must request the runtime permission
 * before the enable takes effect.
 *
 * Only an *enable* while the permission is not yet held requires a request; the
 * caller defers the actual enable until the request returns granted. Disabling
 * needs no permission, and enabling when already granted goes straight through.
 */
fun contactSyncToggleRequiresPermissionRequest(
    enabled: Boolean,
    hasContactsPermission: Boolean,
): Boolean = enabled && !hasContactsPermission

/**
 * Whether a contacts permission request result should enable sync. Both
 * READ and WRITE must be granted: contact sync reads server contacts and writes
 * them to the device, so a partial grant can't perform the mirror and must not
 * flip the toggle on.
 */
fun contactSyncPermissionGranted(
    readGranted: Boolean,
    writeGranted: Boolean,
): Boolean = readGranted && writeGranted
