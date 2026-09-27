package org.onekash.kashcal.ui.components.attendees

/** One status group of the [AttendeeListSheet], in the order [buildAttendeeListSections] gives. */
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

/**
 * Groups [attendees] into [AttendeeListSheet] sections, outside the composable so the rules are
 * unit-testable.
 *
 * One section per [AttendeeStatus] with at least one match, in [SECTION_ORDER]: Accepted
 * (Going), Tentative (Maybe), NeedsAction (Pending), Declined, Delegated. Within a section the
 * first row with [AttendeeUiModel.isYou] comes first, then the rows that aren't You by
 * [AttendeeUiModel.sortOrder]; any further You row is left out. A non-blank [query] keeps rows
 * whose display name or bare address contains it, ignoring case; a group it empties has no
 * section.
 */
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
