package org.onekash.kashcal.sync.notification

import android.util.Log
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.identity.matchesAttendee
import org.onekash.kashcal.reminder.notification.InviteNotificationManager
import org.onekash.kashcal.util.AddressNormalizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fires the per-invite system notification when the sync engine pulls
 * an event with the user's PARTSTAT=NEEDS-ACTION and the dedup
 * timestamp on the attendee row hasn't yet been set.
 *
 * The dedup contract is owned by `AttendeesDao.replaceForEvent`, which
 * preserves `notified_at` from the prior row keyed on canonical address.
 * The notifier writes `notified_at` after firing so subsequent pulls
 * see the row as "already notified."
 */
@Singleton
class InviteNotifier @Inject constructor(
    private val attendeesDao: AttendeesDao,
    private val notificationManager: InviteNotificationManager
) {
    companion object {
        private const val TAG = "InviteNotifier"
    }

    /**
     * Inspect attendee rows for [event] and fire a notification for any
     * row that has `partstat = NEEDS-ACTION`, matches [account] via
     * [Account.matchesAttendee], and has `notified_at IS NULL`.
     *
     * Callers in the pull path already have `event` and `account` in
     * scope, so passing them in avoids redundant DAO reads on the hot
     * path. [cancelForEvent] is the entry point for callers that only
     * have an event ID.
     */
    suspend fun notifyNew(event: Event, account: Account) {
        val rows = attendeesDao.getForEventOnce(event.id)
        for (row in rows) {
            if (row.notifiedAt != null) continue
            if (row.partstat?.uppercase() != "NEEDS-ACTION") continue
            if (!account.matchesAttendee(row.address)) continue

            val organizerLabel = resolveOrganizerLabel(event) ?: continue

            try {
                notificationManager.showInvite(
                    event = event,
                    attendeeRowId = row.id,
                    organizerLabel = organizerLabel
                )
                attendeesDao.markNotified(row.id, System.currentTimeMillis())
                Log.d(TAG, "Fired invite notification for event ${event.id}")
            } catch (e: Exception) {
                // Notification failure must not break attendee persistence.
                Log.w(TAG, "Failed to notify invite for event ${event.id}: ${e.message}")
            }
        }
    }

    /**
     * Cancel any invite notifications associated with [eventId]. Called
     * when the user responds from any in-app surface so the system
     * notification clears.
     */
    suspend fun cancelForEvent(eventId: Long) {
        val rows = attendeesDao.getForEventOnce(eventId)
        for (row in rows) {
            notificationManager.cancelForEvent(eventId, row.id)
        }
    }

    private fun resolveOrganizerLabel(event: Event): String? {
        event.organizerName?.takeIf { it.isNotBlank() }?.let { return it }
        return event.organizerEmail?.let { AddressNormalizer.stripMailto(it) }
            ?.takeIf { it.isNotBlank() }
    }
}
