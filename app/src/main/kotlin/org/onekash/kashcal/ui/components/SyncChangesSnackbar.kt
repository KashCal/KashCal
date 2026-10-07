package org.onekash.kashcal.ui.components

import android.content.res.Resources
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.changes.RecentChangeEntry
import org.onekash.kashcal.domain.changes.isOccurrence
import org.onekash.kashcal.sync.model.ChangeType

/**
 * Returns the sync snackbar message for the entries a sync recorded in Recent changes, or null
 * if there are none.
 *
 * One kind of change gets its own message ("New event: Team Meeting", with the title cut at 30
 * characters, "3 new events", "1 event updated", "2 events removed", "1 occurrence cancelled");
 * a mix gets "5 calendar updates". A cancelled occurrence (a deleted row with an occurrence key)
 * is told apart from a removed event, as the sheet words it.
 */
fun generateSnackbarMessage(changes: List<RecentChangeEntry>, resources: Resources): String? {
    if (changes.isEmpty()) return null

    val newCount = changes.count { it.type == ChangeType.NEW }
    val modCount = changes.count { it.type == ChangeType.MODIFIED }
    val delCount = changes.count { it.type == ChangeType.DELETED && !it.isOccurrence }
    val cancelCount = changes.count { it.type == ChangeType.DELETED && it.isOccurrence }
    val kinds = listOf(newCount, modCount, delCount, cancelCount).count { it > 0 }

    return when {
        kinds > 1 ->
            resources.getQuantityString(R.plurals.sync_snackbar_calendar_updates, changes.size, changes.size)
        newCount == 1 -> {
            val event = changes.first { it.type == ChangeType.NEW }
            val truncatedTitle = event.title.take(30)
            val displayTitle = if (event.title.length > 30) "$truncatedTitle..." else truncatedTitle
            resources.getString(R.string.sync_snackbar_new_event, displayTitle)
        }
        newCount > 0 -> resources.getQuantityString(R.plurals.sync_snackbar_new_events, newCount, newCount)
        modCount > 0 -> resources.getQuantityString(R.plurals.sync_snackbar_events_updated, modCount, modCount)
        delCount > 0 -> resources.getQuantityString(R.plurals.sync_snackbar_events_removed, delCount, delCount)
        else -> resources.getQuantityString(R.plurals.sync_snackbar_occurrences_cancelled, cancelCount, cancelCount)
    }
}
