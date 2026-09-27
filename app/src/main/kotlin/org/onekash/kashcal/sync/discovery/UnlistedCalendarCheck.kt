package org.onekash.kashcal.sync.discovery

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult

/**
 * Calendars a refresh listing left out, split into the ones the server confirmed
 * are gone (safe to remove locally) and the ones to keep.
 */
internal data class UnlistedCalendarVerdict(
    val gone: List<Calendar>,
    val kept: List<Calendar>
)

/**
 * Returns whether a probe of one calendar URL shows the calendar was removed on the server: a
 * status in [CalDavResult.RESOURCE_GONE_CODES], or a readable answer that it is not a calendar
 * the app would list. Everything else keeps the calendar: 401, network failures, timeouts,
 * refused connections, 5xx, and replies the app couldn't read.
 */
internal fun isCalendarGone(result: CalDavResult<Boolean>): Boolean = when (result) {
    is CalDavResult.Success -> !result.data
    is CalDavResult.Error -> result.code in CalDavResult.RESOURCE_GONE_CODES
}

/** Probe answers that say the network, not the calendar, is the problem. */
private val NETWORK_UNUSABLE_CODES = setOf(
    0,
    CalDavResult.CODE_TIMEOUT,
    CalDavResult.CODE_TRANSPORT_REFUSED,
    // A hotspot login page answers every probe the same way.
    CalDavResult.CODE_NOT_MULTISTATUS,
)

/**
 * Probes each calendar missing from a refresh listing to confirm it is gone. A listing can come
 * back short for reasons that say nothing about the calendars (a hotspot login page, a garbled
 * reply, one home set failing), and removing a calendar takes its events and any edits not yet
 * sent to the server with it. A probe that throws keeps its calendar.
 *
 * Once a probe hits a network error, a timeout, a refused connection or a reply
 * that isn't a WebDAV answer (a hotspot page), the rest are kept without probing,
 * so a network that stalls mid-refresh can't hold the user up once per calendar.
 */
internal suspend fun confirmUnlistedCalendars(
    client: CalDavClient,
    unlisted: List<Calendar>,
    tag: String
): UnlistedCalendarVerdict {
    val gone = mutableListOf<Calendar>()
    val kept = mutableListOf<Calendar>()
    var networkDown = false

    for (calendar in unlisted) {
        if (networkDown) {
            kept.add(calendar)
            continue
        }
        val result = try {
            client.probeCalendarCollection(calendar.caldavUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "Probe of unlisted calendar ${calendar.id} failed: ${e.javaClass.simpleName}")
            kept.add(calendar)
            continue
        }

        if (isCalendarGone(result)) {
            gone.add(calendar)
        } else {
            val answer = when (result) {
                is CalDavResult.Success -> "still a calendar"
                is CalDavResult.Error -> "code ${result.code}"
            }
            Log.w(tag, "Keeping calendar ${calendar.id}: missing from the listing, but probe answered $answer")
            kept.add(calendar)
            if (result is CalDavResult.Error && result.code in NETWORK_UNUSABLE_CODES) {
                networkDown = true
            }
        }
    }
    return UnlistedCalendarVerdict(gone = gone, kept = kept)
}
