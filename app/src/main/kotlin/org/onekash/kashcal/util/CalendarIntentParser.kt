package org.onekash.kashcal.util

import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.runtime.Immutable
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.util.CalendarIntentParser.parse
import org.onekash.kashcal.util.CalendarIntentParser.parseCalendarContractUri

/**
 * Event fields that pre-fill a new event in the event form.
 *
 * Built from another app's calendar intent ([CalendarIntentParser]), from long shared text
 * ([ShareIntentRouter]) or from Quick Add (`QuickAddViewModel.toCalendarIntentData`). Any field
 * may be missing.
 */
@Immutable
data class CalendarIntentData(
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val startTimeMillis: Long? = null,
    val endTimeMillis: Long? = null,
    val isAllDay: Boolean = false,
    val rrule: String? = null,
    val categories: List<String> = emptyList()
) {
    /**
     * Returns the description with an "Invitees:" line appended, or the description alone
     * when [invitees] is empty. Called by the event form's pre-fill.
     *
     * @param invitees email addresses from `Intent.EXTRA_EMAIL`
     */
    fun getDescriptionWithInvitees(invitees: List<String>): String {
        val base = description.orEmpty()
        if (invitees.isEmpty()) return base
        val inviteeLine = "Invitees: ${invitees.joinToString(", ")}"
        return if (base.isEmpty()) inviteeLine else "$base\n\n$inviteeLine"
    }
}

/**
 * Actions parsed from CalendarContract content URIs (content://com.android.calendar/...),
 * which launchers, clock widgets and other apps fire with ACTION_VIEW or ACTION_EDIT.
 */
sealed class CalendarContractAction {
    /** Navigates to a date. From VIEW content://com.android.calendar/time/{millis}. */
    data class GoToDate(val dayCode: Int) : CalendarContractAction()

    /** Creates an event from the extras. From EDIT content://com.android.calendar/events. */
    data class CreateEvent(val data: CalendarIntentData, val invitees: List<String>) : CalendarContractAction()

    /**
     * Opens a device event. From VIEW content://com.android.calendar/events/{id} (transit
     * apps, notification taps, launchers).
     *
     * @param eventId CalendarProvider event ID from the URI path.
     * @param beginTimeMillis the occurrence start (EXTRA_EVENT_BEGIN_TIME) when the sender
     *   supplied a positive value, else null. Non-null opens that occurrence's quick view;
     *   null navigates to the event's start date.
     */
    data class OpenDeviceEvent(val eventId: Long, val beginTimeMillis: Long?) : CalendarContractAction()

    /** Opens the app with no action; the fallback for paths it can't resolve. */
    data object OpenApp : CalendarContractAction()
}

/**
 * Parses CalendarContract intents from other apps:
 * - ACTION_INSERT, or ACTION_EDIT with an event MIME type ("Add to Calendar" from email
 *   clients and browsers)
 * - ACTION_VIEW or ACTION_EDIT on content://com.android.calendar URIs (launchers, clock
 *   widgets)
 *
 * @see CalendarContract
 */
object CalendarIntentParser {

    /** Authority for Android's CalendarContract content provider. */
    private const val CALENDAR_AUTHORITY = "com.android.calendar"

    /**
     * Upper bound (year 2200) on millis from intent URIs, so an extreme value can't yield a
     * nonsense day code.
     */
    private const val MAX_REASONABLE_MILLIS = 7258118400000L

    /**
     * Returns true for an ACTION_INSERT or ACTION_EDIT intent with an event MIME type (dir or
     * item) or, lacking one, a content://com.android.calendar/events data URI.
     *
     * EDIT is treated as a create: editing an existing device event through this intent isn't
     * supported.
     */
    fun isCalendarInsertIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val action = intent.action
        if (action != Intent.ACTION_INSERT && action != Intent.ACTION_EDIT) return false
        val type = intent.type
        if (type == "vnd.android.cursor.dir/event" || type == "vnd.android.cursor.item/event") return true
        // An app that uses setData() leaves the type unset on the intent: the ContentProvider
        // resolves it only during intent-filter matching.
        val uri = intent.data ?: return false
        return uri.authority == CALENDAR_AUTHORITY && uri.lastPathSegment == "events"
    }

    /**
     * Returns true for an ACTION_VIEW or ACTION_EDIT intent on any content://com.android.calendar
     * URI, such as VIEW .../time/{millis} from a clock widget or EDIT .../events.
     */
    fun isCalendarContractIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val action = intent.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_EDIT) return false
        val uri = intent.data ?: return false
        return uri.scheme == "content" && uri.authority == CALENDAR_AUTHORITY
    }

    /**
     * Returns the event fields and invitees of a calendar insert intent, or null when
     * [isCalendarInsertIntent] rejects it. The fields read are listed on [extractCalendarExtras].
     */
    fun parse(intent: Intent?): Pair<CalendarIntentData, List<String>>? {
        if (!isCalendarInsertIntent(intent)) return null
        return extractCalendarExtras(intent!!)
    }

    /**
     * Maps a CalendarContract content URI intent to an action, or returns null when
     * [isCalendarContractIntent] rejects it:
     * - /time/{millis} with positive millis → [CalendarContractAction.GoToDate]
     * - EDIT /events with a positive EXTRA_EVENT_BEGIN_TIME → [CalendarContractAction.CreateEvent]
     * - VIEW /events/{id} with a positive id → [CalendarContractAction.OpenDeviceEvent]
     * - anything else, EDIT /events/{id} included → [CalendarContractAction.OpenApp]
     *
     * EDIT on /events has no event ID to resolve, only extras describing a new event, so it
     * is a create. This is the standard behavior for third-party calendar apps.
     */
    fun parseCalendarContractUri(intent: Intent?): CalendarContractAction? {
        if (!isCalendarContractIntent(intent)) return null
        val safeIntent = intent!!
        val uri = safeIntent.data ?: return CalendarContractAction.OpenApp
        val pathSegments = uri.pathSegments

        return when {
            // content://com.android.calendar/time/{millis}
            pathSegments.size == 2 && pathSegments[0] == "time" -> {
                val millis = pathSegments[1].toLongOrNull()
                if (millis != null && millis > 0) {
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

            // content://com.android.calendar/events/{id} + ACTION_VIEW. EDIT on /events/{id}
            // falls to OpenApp: editing a device event by its CalendarProvider ID is out of
            // scope.
            pathSegments.size == 2 && pathSegments[0] == "events" &&
                safeIntent.action == Intent.ACTION_VIEW -> {
                val eventId = pathSegments[1].toLongOrNull()
                if (eventId != null && eventId > 0) {
                    val beginTime = safeIntent
                        .getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1)
                        .takeIf { it > 0 }
                    CalendarContractAction.OpenDeviceEvent(eventId, beginTime)
                } else {
                    CalendarContractAction.OpenApp
                }
            }

            // All other paths: EDIT /events/{id}, /calendars/{id}, /time (no millis), unknown
            else -> CalendarContractAction.OpenApp
        }
    }

    /**
     * Reads the event extras shared by [parse] and [parseCalendarContractUri]: TITLE,
     * DESCRIPTION, EVENT_LOCATION, EXTRA_EVENT_BEGIN_TIME and EXTRA_EVENT_END_TIME (kept only
     * when positive), EXTRA_EVENT_ALL_DAY, RRULE, and EXTRA_EMAIL split on commas as invitees.
     *
     * getLongExtra returns the -1 default when an app bundles EXTRA_EVENT_BEGIN_TIME as an
     * Int, so that start is lost; reading it would take type-checking the Bundle.
     */
    private fun extractCalendarExtras(intent: Intent): Pair<CalendarIntentData, List<String>> {
        val invitees = intent.getStringExtra(Intent.EXTRA_EMAIL)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

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
