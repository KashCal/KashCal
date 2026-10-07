package org.onekash.kashcal.domain.changes

import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.ChangedField

/**
 * One Recent changes row as the recorder writes it: one event or changed occurrence, keyed by
 * ([calendarId], [eventUid], [instanceTs]) like `recent_changes`.
 *
 * Built from a sync's [org.onekash.kashcal.sync.model.SyncChange]s by [RecentChangeClassifier]
 * and returned by [RecentChangesRecorder.record], which the sync snackbar summarizes.
 */
data class RecentChangeEntry(
    val calendarId: Long,
    val eventUid: String,
    /** The changed occurrence's original instance time; 0 for a series or a one-off. */
    val instanceTs: Long,
    val eventId: Long?,
    val type: ChangeType,
    val title: String,
    val startTs: Long,
    val endTs: Long,
    val isAllDay: Boolean,
    val isRecurring: Boolean,
    val changedFields: Set<ChangedField>,
    val previousStartTs: Long?,
    val previousIsAllDay: Boolean?
)

/** True for a changed occurrence's row; false for a series or a one-off. */
val RecentChangeEntry.isOccurrence: Boolean get() = instanceTs != 0L
