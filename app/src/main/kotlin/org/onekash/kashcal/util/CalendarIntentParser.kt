package org.onekash.kashcal.util

import android.content.Intent
import android.provider.CalendarContract

/**
 * Parsed data from a calendar intent (ACTION_INSERT).
 * All fields are nullable - intent may contain partial data.
 *
 * Used when other apps trigger "Add to Calendar" via standard Android intents.
 * Examples: Gmail calendar invites, browser event links, etc.
 */
data class CalendarIntentData(
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val startTimeMillis: Long? = null,
    val endTimeMillis: Long? = null,
    val isAllDay: Boolean = false,
    val rrule: String? = null
) {
    /**
     * Get description with invitees appended (if present in original intent).
     * Called during EventFormSheet pre-fill.
     *
     * @param invitees List of email addresses from Intent.EXTRA_EMAIL
     * @return Description with invitees appended, or original description if no invitees
     */
    fun getDescriptionWithInvitees(invitees: List<String>): String {
        val base = description ?: ""
        if (invitees.isEmpty()) return base
        val inviteeLine = "Invitees: ${invitees.joinToString(", ")}"
        return if (base.isEmpty()) inviteeLine else "$base\n\n$inviteeLine"
    }
}

/**
 * Parser for CalendarContract intents.
 *
 * Handles ACTION_INSERT intents from other apps that want to create calendar events.
 * This is the standard Android pattern for "Add to Calendar" functionality.
 *
 * @see [CalendarContract](https://developer.android.com/reference/android/provider/CalendarContract)
 */
object CalendarIntentParser {

    /**
     * Check if intent is a calendar event creation intent.
     *
     * @param intent The incoming intent to check
     * @return true if this is an ACTION_INSERT intent with calendar MIME type
     */
    fun isCalendarInsertIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        return intent.action == Intent.ACTION_INSERT &&
            intent.type == "vnd.android.cursor.dir/event"
    }

    /**
     * Parse CalendarContract extras from intent.
     *
     * Extracts standard calendar fields:
     * - TITLE, DESCRIPTION, EVENT_LOCATION (strings)
     * - EXTRA_EVENT_BEGIN_TIME, EXTRA_EVENT_END_TIME (millis)
     * - EXTRA_EVENT_ALL_DAY (boolean)
     * - RRULE (recurrence rule string)
     * - EXTRA_EMAIL (comma-separated invitees)
     *
     * @param intent The incoming calendar intent
     * @return Pair of (CalendarIntentData, invitees list), or null if not a valid calendar intent
     */
    fun parse(intent: Intent?): Pair<CalendarIntentData, List<String>>? {
        if (!isCalendarInsertIntent(intent)) return null
        // intent is guaranteed non-null after isCalendarInsertIntent check
        val safeIntent = intent!!

        val invitees = safeIntent.getStringExtra(Intent.EXTRA_EMAIL)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val data = CalendarIntentData(
            title = safeIntent.getStringExtra(CalendarContract.Events.TITLE),
            description = safeIntent.getStringExtra(CalendarContract.Events.DESCRIPTION),
            location = safeIntent.getStringExtra(CalendarContract.Events.EVENT_LOCATION),
            startTimeMillis = safeIntent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1)
                .takeIf { it > 0 },
            endTimeMillis = safeIntent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1)
                .takeIf { it > 0 },
            isAllDay = safeIntent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false),
            rrule = safeIntent.getStringExtra(CalendarContract.Events.RRULE)
        )

        return Pair(data, invitees)
    }
}
