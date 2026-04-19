package org.onekash.kashcal.ui.components

import android.content.res.Resources
import org.onekash.kashcal.R
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.SyncChange

/**
 * Generate human-readable snackbar message from sync changes.
 *
 * Examples:
 * - "New event: Team Meeting"
 * - "3 new events"
 * - "1 event updated"
 * - "5 calendar updates"
 *
 * @param changes List of sync changes
 * @return Human-readable message, or null if no changes
 */
fun generateSnackbarMessage(changes: List<SyncChange>): String? {
    if (changes.isEmpty()) return null

    val newCount = changes.count { it.type == ChangeType.NEW }
    val modCount = changes.count { it.type == ChangeType.MODIFIED }
    val delCount = changes.count { it.type == ChangeType.DELETED }

    return when {
        // Single new event - show title
        newCount == 1 && modCount == 0 && delCount == 0 -> {
            val event = changes.first { it.type == ChangeType.NEW }
            val truncatedTitle = event.eventTitle.take(30)
            if (event.eventTitle.length > 30) {
                "New event: $truncatedTitle..."
            } else {
                "New event: $truncatedTitle"
            }
        }
        // Multiple new events only
        newCount > 0 && modCount == 0 && delCount == 0 ->
            "$newCount new events"
        // Single update only
        modCount == 1 && newCount == 0 && delCount == 0 ->
            "1 event updated"
        // Multiple updates only
        modCount > 0 && newCount == 0 && delCount == 0 ->
            "$modCount events updated"
        // Single deletion only
        delCount == 1 && newCount == 0 && modCount == 0 ->
            "1 event removed"
        // Multiple deletions only
        delCount > 0 && newCount == 0 && modCount == 0 ->
            "$delCount events removed"
        // Mixed changes
        else -> "${changes.size} calendar updates"
    }
}

fun generateSnackbarMessage(changes: List<SyncChange>, resources: Resources): String? {
    if (changes.isEmpty()) return null

    val newCount = changes.count { it.type == ChangeType.NEW }
    val modCount = changes.count { it.type == ChangeType.MODIFIED }
    val delCount = changes.count { it.type == ChangeType.DELETED }

    return when {
        newCount == 1 && modCount == 0 && delCount == 0 -> {
            val event = changes.first { it.type == ChangeType.NEW }
            val truncatedTitle = event.eventTitle.take(30)
            val displayTitle = if (event.eventTitle.length > 30) "$truncatedTitle..." else truncatedTitle
            resources.getString(R.string.sync_snackbar_new_event, displayTitle)
        }
        newCount > 0 && modCount == 0 && delCount == 0 ->
            resources.getQuantityString(R.plurals.sync_snackbar_new_events, newCount, newCount)
        modCount > 0 && newCount == 0 && delCount == 0 ->
            resources.getQuantityString(R.plurals.sync_snackbar_events_updated, modCount, modCount)
        delCount > 0 && newCount == 0 && modCount == 0 ->
            resources.getQuantityString(R.plurals.sync_snackbar_events_removed, delCount, delCount)
        else ->
            resources.getQuantityString(R.plurals.sync_snackbar_calendar_updates, changes.size, changes.size)
    }
}
