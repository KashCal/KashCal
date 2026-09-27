package org.onekash.kashcal.widget

import android.content.Context
import android.text.format.DateFormat
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.util.DateTimeUtils

/**
 * Each widget's data-fetch step, which the widget runs inside `provideContent` through
 * `produceState`. Kept out of the widget classes so it is unit-testable without a Glance or
 * Compose harness. Every fetch maps a failure to an Error or empty state and throws only
 * cancellation.
 */

private const val TAG = "WidgetStateFetchers"

/**
 * Bounds a widget's data fetch; a slower fetch is abandoned for the empty or error state. The
 * Glance session must never sit in a fetch for minutes: actionRunCallback taps (month
 * navigation, refresh) run on that same session and would look frozen. The prime suspect for
 * multi-minute stalls is a CalendarProvider Instances query while the system's sync adapter
 * holds the provider (contentResolver.query has no timeout of its own).
 */
internal const val WIDGET_FETCH_TIMEOUT_MS = 10_000L

/**
 * Signals a fetch that exceeded [WIDGET_FETCH_TIMEOUT_MS]. It is not a
 * [kotlinx.coroutines.TimeoutCancellationException] (a [CancellationException]), so the fetchers'
 * `catch (e: Exception)` turns it into the empty or error state instead of cancelling the
 * session's producer coroutine.
 */
internal class WidgetFetchTimeoutException(message: String) : Exception(message)

/** Holds [UpcomingWidget]'s state; the scaffold renders loading, error or the content from it. */
internal sealed interface UpcomingState {
    data object Loading : UpcomingState
    data object Error : UpcomingState
    data class Loaded(
        val eventsByDay: Map<Int, List<WidgetDataRepository.WidgetEvent>>,
        val todayDayCode: Int,
        val showEventEmojis: Boolean,
        val timePattern: String,
        val detailedRows: Boolean
    ) : UpcomingState
}

/** Fetches Upcoming's state for the next [horizonDays] days, or [UpcomingState.Error]. */
internal suspend fun fetchUpcomingState(
    repository: WidgetDataRepository,
    dataStore: KashCalDataStore,
    context: Context,
    horizonDays: Int = UPCOMING_HORIZON_DAYS
): UpcomingState {
    return try {
        val (startDayCode, endDayCode) = upcomingWindow(
            nowMs = System.currentTimeMillis(),
            horizonDays = horizonDays
        )
        val eventsByDay = withTimeoutOrNull(WIDGET_FETCH_TIMEOUT_MS) {
            repository.getEventsInRange(startDayCode, endDayCode)
        } ?: throw WidgetFetchTimeoutException("getEventsInRange exceeded ${WIDGET_FETCH_TIMEOUT_MS}ms")
        val showEventEmojis = dataStore.showEventEmojis.first()
        val timeFormatPref = dataStore.getTimeFormat()
        val is24Hour = DateFormat.is24HourFormat(context)
        val timePattern = DateTimeUtils.getTimePattern(timeFormatPref, is24Hour)
        val detailedRows = dataStore.widgetDetailedRows.first()
        UpcomingState.Loaded(
            eventsByDay = eventsByDay,
            todayDayCode = startDayCode,
            showEventEmojis = showEventEmojis,
            timePattern = timePattern,
            detailedRows = detailedRows
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "fetchUpcomingState failed", e)
        UpcomingState.Error
    }
}

/** Resolved data for [AgendaWidget]'s `provideContent` render. */
internal data class AgendaData(
    val events: List<WidgetDataRepository.WidgetEvent>,
    val showEventEmojis: Boolean,
    val maxEventsPerDay: Int,
    val timePattern: String,
    val currentDate: String,
    val detailedRows: Boolean
)

/**
 * Formats the agenda header's date line for the current locale. The widget-picker preview uses
 * it too, so its header can't drift from the real one.
 */
internal fun widgetHeaderDate(nowMs: Long = System.currentTimeMillis()): String =
    DateTimeUtils.formatEventDate(
        timestampMs = nowMs,
        isAllDay = false,
        pattern = DateTimeUtils.localizedPattern("EEEEMMMd")
    )

/**
 * Fetches the Agenda widget's data. On failure returns no events with default settings, so the
 * widget shows its "no events today" state.
 */
internal suspend fun fetchAgendaData(
    repository: WidgetDataRepository,
    dataStore: KashCalDataStore,
    context: Context
): AgendaData {
    return try {
        val events = withTimeoutOrNull(WIDGET_FETCH_TIMEOUT_MS) {
            repository.getTodayEvents()
        } ?: throw WidgetFetchTimeoutException("getTodayEvents exceeded ${WIDGET_FETCH_TIMEOUT_MS}ms")
        val showEventEmojis = dataStore.showEventEmojis.first()
        val maxEventsPerDay = dataStore.widgetMaxEventsPerDay.first()
        val timeFormatPref = dataStore.getTimeFormat()
        val is24Hour = DateFormat.is24HourFormat(context)
        val timePattern = DateTimeUtils.getTimePattern(timeFormatPref, is24Hour)
        val detailedRows = dataStore.widgetDetailedRows.first()
        AgendaData(events, showEventEmojis, maxEventsPerDay, timePattern, widgetHeaderDate(), detailedRows)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "fetchAgendaData failed", e)
        AgendaData(
            events = emptyList(),
            showEventEmojis = true,
            maxEventsPerDay = 5,
            timePattern = "h:mm a",
            currentDate = "",
            detailedRows = false
        )
    }
}

/** Resolved data for [WeekWidget]'s `provideContent` render. */
internal data class WeekData(
    val weekEvents: Map<Int, List<WidgetDataRepository.WidgetEvent>>,
    val showEventEmojis: Boolean,
    val maxEventsPerDay: Int,
    val timePattern: String,
    val detailedRows: Boolean
)

/**
 * Fetches the Week widget's data. On failure returns an empty week with default settings, so the
 * widget shows its empty state.
 */
internal suspend fun fetchWeekData(
    repository: WidgetDataRepository,
    dataStore: KashCalDataStore,
    context: Context
): WeekData {
    return try {
        val weekEvents = withTimeoutOrNull(WIDGET_FETCH_TIMEOUT_MS) {
            repository.getWeekEvents()
        } ?: throw WidgetFetchTimeoutException("getWeekEvents exceeded ${WIDGET_FETCH_TIMEOUT_MS}ms")
        val showEventEmojis = dataStore.showEventEmojis.first()
        val maxEventsPerDay = dataStore.widgetMaxEventsPerDay.first()
        val timeFormatPref = dataStore.getTimeFormat()
        val is24Hour = DateFormat.is24HourFormat(context)
        val timePattern = DateTimeUtils.getTimePattern(timeFormatPref, is24Hour)
        val detailedRows = dataStore.widgetDetailedRows.first()
        WeekData(weekEvents, showEventEmojis, maxEventsPerDay, timePattern, detailedRows)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "fetchWeekData failed", e)
        WeekData(
            weekEvents = emptyMap(),
            showEventEmojis = true,
            maxEventsPerDay = 5,
            timePattern = "h:mm a",
            detailedRows = false
        )
    }
}

/**
 * Fetches the Month widget's events for a dayCode range, or an empty map on failure or timeout.
 * The month offset, first day of week and grid stay in [MonthWidget] because they drive its
 * rendering, not only this fetch.
 */
internal suspend fun fetchMonthEvents(
    repository: WidgetDataRepository,
    startDayCode: Int,
    endDayCode: Int
): Map<Int, List<WidgetDataRepository.WidgetEvent>> {
    return try {
        withTimeoutOrNull(WIDGET_FETCH_TIMEOUT_MS) {
            repository.getEventsInRange(startDayCode, endDayCode)
        } ?: run {
            Log.w(TAG, "fetchMonthEvents timed out after ${WIDGET_FETCH_TIMEOUT_MS}ms; rendering the grid with no events")
            emptyMap()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "fetchMonthEvents failed", e)
        emptyMap()
    }
}
