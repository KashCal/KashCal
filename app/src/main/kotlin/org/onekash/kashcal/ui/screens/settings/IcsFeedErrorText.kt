package org.onekash.kashcal.ui.screens.settings

import org.onekash.kashcal.R
import org.onekash.kashcal.data.ics.IcsFeedErrors
import org.onekash.kashcal.ui.util.UiMessage

/**
 * Returns the feed row's text for a stored refresh error ([IcsFeedErrors]), in the user's
 * language; any text it doesn't know, such as an exception message, comes back as stored.
 */
internal fun icsFeedErrorMessage(stored: String): UiMessage = when {
    stored == IcsFeedErrors.INVALID_FORMAT -> UiMessage.ResId(R.string.ics_fetch_error_not_calendar)
    stored == IcsFeedErrors.EMPTY_RESPONSE -> UiMessage.ResId(R.string.ics_feed_error_empty_response)
    stored == IcsFeedErrors.ACCESS_DENIED -> UiMessage.ResId(R.string.ics_feed_error_access_denied)
    stored == IcsFeedErrors.NOT_FOUND -> UiMessage.ResId(R.string.ics_fetch_error_http, listOf(404))
    stored == IcsFeedErrors.TOO_LARGE || stored == IcsFeedErrors.TOO_LARGE_413 ->
        UiMessage.ResId(R.string.ics_feed_error_too_large)
    stored == IcsFeedErrors.NO_EVENTS_PARSED -> UiMessage.ResId(R.string.ics_feed_error_unreadable)
    IcsFeedErrors.isNetwork(stored) -> UiMessage.ResId(R.string.error_network_connection_failed)
    IcsFeedErrors.isSsl(stored) -> UiMessage.ResId(R.string.error_network_ssl)
    else -> IcsFeedErrors.notSavedOf(stored)?.let(::notSavedMessage)
        ?: IcsFeedErrors.httpStatusOf(stored)?.let { UiMessage.ResId(R.string.ics_fetch_error_http, listOf(it)) }
        ?: UiMessage.Literal(stored)
}

/** One named event, several with the first title, or a count when none has a title. */
private fun notSavedMessage(notSaved: Pair<Int, String?>): UiMessage {
    val (count, title) = notSaved
    return when {
        title == null -> UiMessage.Plural(R.plurals.ics_feed_error_events_not_saved, count, listOf(count))
        count == 1 -> UiMessage.ResId(R.string.ics_feed_error_event_not_saved, listOf(title))
        else -> UiMessage.Plural(R.plurals.ics_feed_error_events_not_saved_including, count, listOf(count, title))
    }
}
