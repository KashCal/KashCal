package org.onekash.kashcal.ui.components

/**
 * Single source of truth for badge text shown on count overlays (the
 * top-bar overflow IconButton and the overflow sheet's Invites row).
 * Keeping formatting here lets sibling badge sites stay aligned without
 * each re-implementing the cap-at-99 rule.
 *
 * Returns the rendered string when the badge should be visible, or
 * `null` when the caller should suppress the [androidx.compose.material3.Badge]
 * entirely (non-positive counts).
 */
internal fun formatBadgeCount(count: Int): String? = when {
    count <= 0 -> null
    count <= 99 -> count.toString()
    else -> "99+"
}
