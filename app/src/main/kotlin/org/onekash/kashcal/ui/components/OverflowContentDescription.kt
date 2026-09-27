package org.onekash.kashcal.ui.components

/**
 * Returns the top-bar hub trigger's content description for a pending-invitation [count].
 *
 * At zero or below it is [baseLabel], the announcement with no mention of invitations;
 * otherwise [withInvitesLabel], a plural the caller resolves with the count in it. Taking both
 * labels keeps the helper Context-free and unit-testable.
 */
internal fun overflowContentDescription(
    count: Int,
    baseLabel: String,
    withInvitesLabel: String
): String = if (count <= 0) baseLabel else withInvitesLabel
