package org.onekash.kashcal.ui.components

/**
 * Resolve the AppBar rail toggle's [contentDescription] from the
 * pending-invitation count.
 *
 * Returns [baseLabel] when [count] is zero so screen-reader users hear
 * the same announcement they'd hear if invitations weren't a feature
 * at all. Returns [withInvitesLabel] (the caller-resolved plural string
 * with the count baked in) otherwise. The helper takes both labels as
 * parameters so it stays Context-free and unit-testable; callers
 * resolve via `pluralStringResource`.
 */
internal fun overflowContentDescription(
    count: Int,
    baseLabel: String,
    withInvitesLabel: String
): String = if (count <= 0) baseLabel else withInvitesLabel
