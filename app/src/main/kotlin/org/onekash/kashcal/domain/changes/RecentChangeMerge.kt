package org.onekash.kashcal.domain.changes

import org.onekash.kashcal.sync.model.ChangeType

/** Merges a later change to an event into its earlier Recent changes row. */
object RecentChangeMerge {

    /**
     * Returns the row after [incoming] lands on [existing] (null for no live row):
     * - NEW then MODIFIED stays NEW, with nothing shown as changed;
     * - anything then DELETED is DELETED; DELETED then NEW or MODIFIED takes the later type;
     * - changed fields accumulate, and the previous start keeps the oldest one, the time the
     *   user last knew;
     * - title, times, flags and event id come from [incoming].
     */
    fun merge(existing: RecentChangeEntry?, incoming: RecentChangeEntry): RecentChangeEntry {
        if (existing == null) return incoming
        val type = when {
            incoming.type == ChangeType.DELETED -> ChangeType.DELETED
            existing.type == ChangeType.NEW && incoming.type == ChangeType.MODIFIED -> ChangeType.NEW
            else -> incoming.type
        }
        if (type == ChangeType.NEW) {
            return incoming.copy(type = type, changedFields = emptySet(), previousStartTs = null, previousIsAllDay = null)
        }
        val keepsOldPrevious = existing.previousStartTs != null
        return incoming.copy(
            type = type,
            changedFields = existing.changedFields + incoming.changedFields,
            previousStartTs = if (keepsOldPrevious) existing.previousStartTs else incoming.previousStartTs,
            previousIsAllDay = if (keepsOldPrevious) existing.previousIsAllDay else incoming.previousIsAllDay
        )
    }
}
