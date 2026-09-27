package org.onekash.kashcal.ui.components

/**
 * Formats the text of a count badge, capped at "99+". The one home of the rule for
 * both badge sites: the top-bar overflow IconButton and the account hub's Invites row.
 *
 * Returns null for a non-positive count; the caller then shows no
 * [androidx.compose.material3.Badge].
 */
internal fun formatBadgeCount(count: Int): String? = when {
    count <= 0 -> null
    count <= 99 -> count.toString()
    else -> "99+"
}
