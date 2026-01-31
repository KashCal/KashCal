package org.onekash.kashcal.data.ics

import android.util.Log
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper

private const val TAG = "IcsParserService"

/**
 * ICS subscription parser service using the icaldav library.
 *
 * Replaces the custom RfcIcsParser with the production-tested ICalParser
 * from the icaldav library. This provides:
 * - Better RFC 5545 compliance
 * - Exception event (RECURRENCE-ID) support
 * - Reduced maintenance burden
 *
 * Note: CANCELLED events are filtered to match previous RfcIcsParser behavior.
 * ICS subscriptions are read-only, so cancelled events should not appear.
 */
object IcsParserService {

    private val parser = ICalParser()

    /**
     * Validate ICS content structure.
     * Returns true if content appears to be valid ICS format.
     */
    fun isValidIcs(content: String): Boolean {
        return content.contains("BEGIN:VCALENDAR") &&
            content.contains("END:VCALENDAR") &&
            (content.contains("BEGIN:VEVENT") || content.contains("BEGIN:VTODO"))
    }

    /**
     * Parse ICS content into a list of events.
     *
     * @param content Raw ICS file content
     * @param calendarId Calendar ID to assign to parsed events
     * @param subscriptionId ICS subscription ID (for source tracking)
     * @return List of parsed events (CANCELLED events are filtered out)
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
                    .filter { it.status?.toICalString() != "CANCELLED" }
                    .map { icalEvent ->
                        ICalEventMapper.toEntity(
                            icalEvent = icalEvent,
                            rawIcal = null,
                            calendarId = calendarId,
                            caldavUrl = "${IcsSubscription.SOURCE_PREFIX}:${subscriptionId}:${icalEvent.uid}",
                            etag = null
                        )
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
     * Get the calendar name from ICS content.
     * Looks for X-WR-CALNAME or PRODID properties.
     *
     * Note: This method is only used in tests, not in production code.
     */
    fun getCalendarName(content: String): String? {
        // Simple extraction - only used in tests
        val xwrCalname = Regex("X-WR-CALNAME:([^\r\n]+)").find(content)?.groupValues?.get(1)?.trim()
        if (xwrCalname != null) return xwrCalname

        val prodid = Regex("PRODID:([^\r\n]+)").find(content)?.groupValues?.get(1)?.trim()
        return prodid?.takeIf { it.isNotBlank() }
    }
}
