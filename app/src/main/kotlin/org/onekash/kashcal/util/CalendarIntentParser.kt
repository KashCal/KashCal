package org.onekash.kashcal.util

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import org.onekash.kashcal.ui.util.DayPagerUtils

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
 * Actions parsed from CalendarContract content URIs (content://com.android.calendar/...).
 *
 * Used by launchers (Kvaesitso), clock widgets, and other apps that fire
 * ACTION_VIEW/ACTION_EDIT on standard CalendarContract URIs.
 */
sealed class CalendarContractAction {
    /** Navigate to a specific date. From VIEW content://com.android.calendar/time/{millis}. */
    data class GoToDate(val dayCode: Int) : CalendarContractAction()

    /** Create event with pre-filled data. From EDIT content://com.android.calendar/events with extras. */
    data class CreateEvent(val data: CalendarIntentData, val invitees: List<String>) : CalendarContractAction()

    /** Open the app normally (fallback for unresolvable paths like /events/{id}). */
    data object OpenApp : CalendarContractAction()
}

/**
 * Parser for CalendarContract intents.
 *
 * Handles:
 * - ACTION_INSERT intents from other apps ("Add to Calendar" from Gmail, Chrome, etc.)
 * - ACTION_VIEW/EDIT on content://com.android.calendar URIs (launchers, clock widgets)
 *
 * @see [CalendarContract](https://developer.android.com/reference/android/provider/CalendarContract)
 */
object CalendarIntentParser {

    /** Authority for Android's CalendarContract content provider. */
    private const val CALENDAR_AUTHORITY = "com.android.calendar"

    /**
     * Upper bound for millis values from intent URIs (~year 2200).
     * Prevents nonsensical dayCode from extreme timestamps (CLAUDE.md pattern #10).
     */
    private const val MAX_REASONABLE_MILLIS = 7258118400000L

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
     * Check if intent is a CalendarContract content URI intent (VIEW/EDIT).
     *
     * Matches intents from launchers and clock widgets that fire:
     * - ACTION_VIEW content://com.android.calendar/time/{millis}
     * - ACTION_EDIT content://com.android.calendar/events
     *
     * @param intent The incoming intent to check
     * @return true if this is a VIEW/EDIT intent targeting com.android.calendar
     */
    fun isCalendarContractIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val action = intent.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_EDIT) return false
        val uri = intent.data ?: return false
        return uri.scheme == "content" && uri.authority == CALENDAR_AUTHORITY
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
        return extractCalendarExtras(intent!!)
    }

    /**
     * Parse a CalendarContract content URI into an action.
     *
     * Handles standard CalendarContract URI patterns:
     * - /time/{millis} → [CalendarContractAction.GoToDate]
     * - /events (EDIT + extras) → [CalendarContractAction.CreateEvent]
     * - /events/{id}, unknown paths → [CalendarContractAction.OpenApp]
     *
     * Design note: EDIT on /events is treated as "create" because KashCal doesn't use
     * the system CalendarProvider, so provider event IDs are meaningless. This matches
     * what other third-party calendar apps (Simple Calendar, Etar) do.
     *
     * @param intent The incoming CalendarContract intent
     * @return Parsed action, or null if not a CalendarContract intent
     */
    fun parseCalendarContractUri(intent: Intent?): CalendarContractAction? {
        if (!isCalendarContractIntent(intent)) return null
        // intent is guaranteed non-null after isCalendarContractIntent check
        val safeIntent = intent!!
        val uri = safeIntent.data ?: return CalendarContractAction.OpenApp
        val pathSegments = uri.pathSegments

        return when {
            // content://com.android.calendar/time/{millis}
            pathSegments.size == 2 && pathSegments[0] == "time" -> {
                val millis = pathSegments[1].toLongOrNull()
                if (millis != null && millis > 0) {
                    // Bound millis to reasonable range (CLAUDE.md pattern #10)
                    val boundedMillis = millis.coerceIn(1, MAX_REASONABLE_MILLIS)
                    val dayCode = DayPagerUtils.msToDayCode(boundedMillis)
                    CalendarContractAction.GoToDate(dayCode)
                } else {
                    CalendarContractAction.OpenApp
                }
            }

            // content://com.android.calendar/events (no ID) + ACTION_EDIT + time extras
            pathSegments.size == 1 && pathSegments[0] == "events" &&
                safeIntent.action == Intent.ACTION_EDIT -> {
                val beginTime = safeIntent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1)
                if (beginTime > 0) {
                    val (data, invitees) = extractCalendarExtras(safeIntent)
                    CalendarContractAction.CreateEvent(data, invitees)
                } else {
                    CalendarContractAction.OpenApp
                }
            }

            // All other paths: /events/{id}, /calendars/{id}, /time (no millis), unknown
            else -> CalendarContractAction.OpenApp
        }
    }

    /**
     * Extract CalendarContract extras from an intent.
     *
     * Shared helper used by both [parse] (ACTION_INSERT) and
     * [parseCalendarContractUri] (ACTION_EDIT on /events).
     *
     * Note: getLongExtra returns -1 (default) when the extra is stored as Int
     * instead of Long. This is a known Android limitation — some apps bundle
     * EXTRA_EVENT_BEGIN_TIME as Int. No workaround without type-checking Bundle.
     */
    private fun extractCalendarExtras(intent: Intent): Pair<CalendarIntentData, List<String>> {
        val invitees = intent.getStringExtra(Intent.EXTRA_EMAIL)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val data = CalendarIntentData(
            title = intent.getStringExtra(CalendarContract.Events.TITLE),
            description = intent.getStringExtra(CalendarContract.Events.DESCRIPTION),
            location = intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION),
            startTimeMillis = intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1)
                .takeIf { it > 0 },
            endTimeMillis = intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1)
                .takeIf { it > 0 },
            isAllDay = intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false),
            rrule = intent.getStringExtra(CalendarContract.Events.RRULE)
        )

        return Pair(data, invitees)
    }
}
