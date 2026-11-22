package org.onekash.kashcal.ui.viewmodels

import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.calendar_provider.DeviceEvent
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import java.util.TimeZone

/** What a drag's save finds when it re-reads the event it moves; see [checkRoomMove]. */
internal enum class DragSaveCheck {
    /** Stored as the grid showed it at the drop, in every field the save depends on. */
    FRESH,

    /** Changed since the drop in a field the save would overwrite or build on. */
    CHANGED,

    /** Missing, deleted, or (device) unreadable. */
    GONE,
}

/**
 * Checks the stored Room row against [dropped], the row the grid showed at the drop. The save
 * applies the new times to the stored row, so only the series' times, rule and exclusions
 * matter; a title or note a sync pulled in meanwhile is kept. A PENDING_DELETE row is gone:
 * updating it would bring the event back.
 */
internal fun checkRoomMove(dropped: Event, stored: Event?): DragSaveCheck = when {
    stored == null || stored.syncStatus == SyncStatus.PENDING_DELETE -> DragSaveCheck.GONE
    stored.startTs != dropped.startTs ||
        stored.endTs != dropped.endTs ||
        stored.rrule != dropped.rrule ||
        stored.exdate != dropped.exdate ||
        stored.rdate != dropped.rdate ||
        stored.isAllDay != dropped.isAllDay ||
        stored.timezone != dropped.timezone -> DragSaveCheck.CHANGED
    else -> DragSaveCheck.FRESH
}

/**
 * Checks the stored provider row against [dropped], the instance the grid showed at the drop.
 * The device save writes the instance's own title, notes, location, free/busy and colour, so a
 * change to any of them is CHANGED rather than overwritten. Each side keeps the mapping its
 * read applies in `AndroidCalendarProviderRepository` (the instance: empty text for null, no
 * colour for 0; the row: the device zone when it has none, an all-day end as its last
 * millisecond), and both are brought to the same form here, with an empty rule counted as
 * none.
 *
 * [isActive] is the row's `DELETED = 0` check: a deleted row the sync adapter hasn't purged is
 * still returned by id. A null row may also be a read the provider refused.
 */
internal fun checkDeviceMove(dropped: DeviceCalendarInstance, stored: DeviceEvent?, isActive: Boolean): DragSaveCheck {
    if (stored == null || !isActive) return DragSaveCheck.GONE
    val sameTimes = if (dropped.hasRrule) {
        stored.startTs == dropped.eventStartTs
    } else {
        stored.startTs == dropped.startTs && (stored.endTs == null || stored.endTs == dropped.endTs)
    }
    val same = sameTimes &&
        stored.title == dropped.title &&
        stored.description.orEmpty() == dropped.description &&
        stored.location.orEmpty() == dropped.location &&
        stored.availability == dropped.availability &&
        stored.eventColor?.takeIf { it != 0 } == dropped.eventColor?.takeIf { it != 0 } &&
        stored.isAllDay == dropped.isAllDay &&
        stored.timezone == (dropped.timezone ?: TimeZone.getDefault().id) &&
        stored.rrule?.takeIf { it.isNotEmpty() } == dropped.rrule?.takeIf { it.isNotEmpty() }
    return if (same) DragSaveCheck.FRESH else DragSaveCheck.CHANGED
}
