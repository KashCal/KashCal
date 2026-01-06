package org.onekash.kashcal.sync.model

import androidx.compose.runtime.Immutable

/**
 * Represents a single change detected during calendar sync.
 * Used for snackbar notifications and bottom sheet display.
 */
@Immutable
data class SyncChange(
    /** Type of change (NEW, MODIFIED, DELETED) */
    val type: ChangeType,
    /** Event ID (null for deleted events where we only have the href) */
    val eventId: Long?,
    /** Event title for display */
    val eventTitle: String,
    /** Event start timestamp for display */
    val eventStartTs: Long,
    /** Whether this is an all-day event (affects date display - uses UTC, no time) */
    val isAllDay: Boolean,
    /** Whether this is a recurring event (shows repeat icon) */
    val isRecurring: Boolean,
    /** Calendar name for context */
    val calendarName: String,
    /** Calendar color for visual indicator */
    val calendarColor: Int
)

/**
 * Type of sync change.
 */
enum class ChangeType {
    /** New event added from server */
    NEW,
    /** Existing event modified on server */
    MODIFIED,
    /** Event deleted from server */
    DELETED
}
