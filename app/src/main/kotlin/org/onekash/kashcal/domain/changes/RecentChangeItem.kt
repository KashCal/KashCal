package org.onekash.kashcal.domain.changes

import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.ChangedField

/** One row of the Recent changes sheet, with its calendar's current name and color. */
data class RecentChangeItem(
    val id: Long,
    val calendarId: Long,
    val eventUid: String,
    /** The changed occurrence's original instance time; 0 for a series or a one-off. */
    val instanceTs: Long,
    /** The local event a tap opens; null for a deleted or cancelled row, or once it is gone. */
    val eventId: Long?,
    val changeType: ChangeType,
    val title: String,
    val startTs: Long,
    val endTs: Long,
    val isAllDay: Boolean,
    val isRecurring: Boolean,
    /** When the change arrived; the sheet groups rows by its day. */
    val detectedAt: Long,
    val changedFields: Set<ChangedField>,
    val previousStartTs: Long?,
    val previousIsAllDay: Boolean?,
    val calendarName: String,
    val calendarColor: Int
)

/** A series row: no occurrence key and a repeat rule. Its stored start is the first occurrence. */
val RecentChangeItem.isSeriesRow: Boolean
    get() = instanceTs == 0L && isRecurring

/** Whether a tap opens the row: a deleted or cancelled row, or one whose event is gone, can't. */
val RecentChangeItem.opens: Boolean
    get() = changeType != ChangeType.DELETED && eventId != null
