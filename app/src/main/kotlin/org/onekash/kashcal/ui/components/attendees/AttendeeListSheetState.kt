package org.onekash.kashcal.ui.components.attendees

/**
 * Pure logic for the [AttendeeListSheet] body. Lives outside the
 * composable so the grouping/sorting/filtering rules can be unit-tested
 * without spinning up Compose.
 *
 * One [AttendeeListSection] per [AttendeeStatus] that has at least one
 * matching attendee, in the canonical display order:
 * Accepted (Going) → Tentative (Maybe) → NeedsAction (Pending) →
 * Declined → Delegated.
 *
 * Within a section, You is pinned to the top, then attendees by their
 * wire-order [AttendeeUiModel.sortOrder]. Filtering matches the query
 * against [AttendeeUiModel.displayName] and [AttendeeUiModel.bareAddress]
 * case-insensitively. Section headers are dropped when the query
 * empties their group.
 */
data class AttendeeListSection(
    val status: AttendeeStatus,
    val rows: List<AttendeeUiModel>,
)

internal val SECTION_ORDER: List<AttendeeStatus> = listOf(
    AttendeeStatus.Accepted,
    AttendeeStatus.Tentative,
    AttendeeStatus.NeedsAction,
    AttendeeStatus.Declined,
    AttendeeStatus.Delegated,
)

internal fun buildAttendeeListSections(
    attendees: List<AttendeeUiModel>,
    query: String = "",
): List<AttendeeListSection> {
    if (attendees.isEmpty()) return emptyList()
    val needle = query.trim().lowercase()
    val filtered = if (needle.isEmpty()) attendees else attendees.filter { row ->
        row.displayName.lowercase().contains(needle) ||
            row.bareAddress.lowercase().contains(needle)
    }
    if (filtered.isEmpty()) return emptyList()
    val byStatus = filtered.groupBy { it.status }
    return SECTION_ORDER.mapNotNull { status ->
        val rows = byStatus[status] ?: return@mapNotNull null
        val you = rows.firstOrNull { it.isYou }
        val others = rows.filter { !it.isYou }.sortedBy { it.sortOrder }
        AttendeeListSection(
            status = status,
            rows = listOfNotNull(you) + others,
        )
    }
}
