package org.onekash.kashcal.ui.components.attendees

/**
 * Pure-logic state for [AttendeeChipRow]. Decides between the three render
 * modes (empty, lavender count-only, inline chips) without any Compose
 * runtime so it's unit-testable in the standard test pool.
 */
sealed interface AttendeeChipRowMode {
    /** No chip row at all — event has no attendees. */
    data object Empty : AttendeeChipRowMode

    /**
     * Single lavender count chip — current user is not on the attendee list
     * AND the row is collapsed. Tapping it flips `expanded`, switching the
     * mode to [Inline] for the same model list.
     */
    data class LavenderCount(val totalCount: Int) : AttendeeChipRowMode

    /**
     * Render attendees inline. [visible] is sorted with the current user
     * at index 0 when present; [hiddenCount] is the disclosure count for
     * the "+N more" affordance. When [hiddenCount] is 0 the disclosure is
     * hidden.
     */
    data class Inline(
        val visible: List<AttendeeUiModel>,
        val hiddenCount: Int
    ) : AttendeeChipRowMode
}

object AttendeeChipRowState {

    private const val COLLAPSED_LIMIT_DEFAULT = 3

    /**
     * @param models all attendees mapped to UI rows
     * @param isCurrentUserOnList whether the active account matches any
     *   attendee — drives the lavender count fallback
     * @param expanded the disclosure state from compose remember
     */
    fun compute(
        models: List<AttendeeUiModel>,
        isCurrentUserOnList: Boolean,
        expanded: Boolean
    ): AttendeeChipRowMode {
        if (models.isEmpty()) return AttendeeChipRowMode.Empty
        // Off-list events show the lavender count pill as the default state
        // and only switch to the inline FlowRow after a tap (expanded=true).
        // On-list events render Inline regardless of expanded; expanded only
        // governs the +N more disclosure inside Inline.
        if (!isCurrentUserOnList && !expanded) {
            return AttendeeChipRowMode.LavenderCount(models.size)
        }

        val sorted = AttendeeUiModel.sortForCollapsedView(models, expanded = expanded)
        val total = models.size
        val hidden = (total - sorted.size).coerceAtLeast(0)
        return AttendeeChipRowMode.Inline(visible = sorted, hiddenCount = hidden)
    }

    @Suppress("unused")
    val collapsedLimit: Int get() = COLLAPSED_LIMIT_DEFAULT
}
