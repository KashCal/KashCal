package org.onekash.kashcal.data.ics

import android.util.Log
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper

private const val TAG = "IcsParserService"

/**
 * Parses ICS subscription feeds and imported ICS files with the icaldav [ICalParser], which
 * includes exception events (RECURRENCE-ID).
 *
 * CANCELLED events are dropped: ICS subscriptions are read-only, so cancelled events should not
 * appear.
 */
object IcsParserService {

    private val parser = ICalParser()

    /**
     * Returns true if [content] has a VCALENDAR wrapper and at least one VEVENT or VTODO.
     *
     * A substring check only; it doesn't parse.
     */
    fun isValidIcs(content: String): Boolean {
        return content.contains("BEGIN:VCALENDAR") &&
            content.contains("END:VCALENDAR") &&
            (content.contains("BEGIN:VEVENT") || content.contains("BEGIN:VTODO"))
    }

    /**
     * Parses [content] into events, without CANCELLED ones; returns an empty list if parsing fails.
     *
     * @param calendarId assigned to every parsed event.
     * @param subscriptionId builds each event's `caldavUrl` source key
     *   ([IcsSubscription.eventSourcePrefix] plus the event's import id).
     */
    fun parseIcsContent(
        content: String,
        calendarId: Long,
        subscriptionId: Long
    ): List<Event> {
        val result = parser.parseAllEvents(content)
        return when (result) {
            is ParseResult.Success -> {
                val events = result.value
                    .filter { it.status.toICalString() != "CANCELLED" }
                    .map { icalEvent ->
                        // Attendees are dropped: the callers (ICS subscriptions and the
                        // file imports in SettingsRoute and MainActivity) persist Event
                        // only, so keeping attendees means changing those pipelines too.
                        ICalEventMapper.toEntity(
                            icalEvent = icalEvent,
                            rawIcal = null,
                            calendarId = calendarId,
                            caldavUrl = "${IcsSubscription.eventSourcePrefix(subscriptionId)}${icalEvent.importId}",
                            etag = null
                        ).event
                    }
                Log.d(TAG, "Parsed ${events.size} events from ICS (filtered ${result.value.size - events.size} cancelled)")
                events
            }
            is ParseResult.Error -> {
                Log.e(TAG, "Failed to parse ICS content: ${result.error.message}")
                emptyList()
            }
        }
    }

    /**
     * Returns the calendar name from [content], or null if it doesn't parse or has none.
     *
     * Prefers RFC 7986 NAME over X-WR-CALNAME (`ICalCalendar.effectiveName`), falling back to
     * PRODID when neither is set. Only tests call it.
     */
    fun getCalendarName(content: String): String? {
        val calendar = parser.parse(content).getOrNull() ?: return null
        calendar.effectiveName?.let { return it }
        return calendar.prodId?.takeIf { it.isNotBlank() }
    }
}
