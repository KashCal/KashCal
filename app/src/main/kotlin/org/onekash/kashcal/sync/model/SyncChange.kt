package org.onekash.kashcal.sync.model

/**
 * One event change a pull brought in. Feeds reminder scheduling for pulled events and the
 * Recent changes log (`RecentChangesRecorder`), which also drives the sync snackbar.
 */
data class SyncChange(
    val type: ChangeType,
    /** Null for a deleted event, whose row is gone. */
    val eventId: Long?,
    val eventTitle: String,
    val eventStartTs: Long,
    /** All-day dates display in UTC with no time. */
    val isAllDay: Boolean,
    val isRecurring: Boolean,
    /**
     * Set on the event changes (not the deletions) of a full-listing pull of a calendar with no
     * stored sync-token, or of a forced full sync; new events from such a pull get no default
     * reminder.
     */
    val isFromInitialSync: Boolean = false,
    val calendarId: Long,
    val eventEndTs: Long,
    val eventUid: String,
    /** The changed occurrence's original instance time; 0 for a series or a one-off. */
    val instanceTs: Long = 0,
    /** What changed, for a MODIFIED change or a first-time changed occurrence. */
    val changedFields: Set<ChangedField> = emptySet(),
    /** The start before this change, set only when the start moved. */
    val previousStartTs: Long? = null,
    /** The all-day flag that goes with [previousStartTs]. */
    val previousIsAllDay: Boolean? = null,
    /** Occurrences the series' EXDATE newly excludes ([ChangedField.cancelledInstances]). */
    val cancelledInstances: List<Long> = emptyList(),
    /**
     * Set on a first-time changed occurrence whose series is only the pull's placeholder for it
     * (no real series yet); false otherwise.
     */
    val seriesIsPlaceholder: Boolean = false,
    /** A series that replaced the pull's placeholder; to the user it is new. */
    val replacedPlaceholder: Boolean = false,
    /** From the calendar's first successful pull, which lists everything the server has. */
    val isFirstPull: Boolean = false,
    /** From a forced full sync, which also lists events that only came into the sync window. */
    val isForcedPull: Boolean = false
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
