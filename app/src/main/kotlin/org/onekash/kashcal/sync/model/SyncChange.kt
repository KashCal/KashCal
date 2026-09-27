package org.onekash.kashcal.sync.model

import androidx.compose.runtime.Immutable

/**
 * One event change a pull brought in. Feeds the sync snackbar and bottom sheet, and the
 * reminder scheduling for pulled events.
 */
@Immutable
data class SyncChange(
    val type: ChangeType,
    /** Null for a deleted event, whose row is gone. */
    val eventId: Long?,
    val eventTitle: String,
    val eventStartTs: Long,
    /** All-day dates display in UTC with no time. */
    val isAllDay: Boolean,
    /** Shows the repeat icon. */
    val isRecurring: Boolean,
    val calendarName: String,
    val calendarColor: Int,
    /** Set on a calendar's first sync; such new events get no default reminder. */
    val isFromInitialSync: Boolean = false
)

/** What a pull did to an event. */
enum class ChangeType {
    /** Added on the server. */
    NEW,
    /** Changed on the server. */
    MODIFIED,
    /** Deleted on the server. */
    DELETED
}
