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
 * Pure suspend functions that encapsulate each widget's data-fetch step.
 *
 * Extracted from the widget classes so the refactor (moving fetches inside `provideContent`
 * via `produceState`) stays unit-testable without a Glance/Compose test harness. Each
 * function catches exceptions internally and maps to an Error or empty-result state —
 * callers never need to handle throws.
 */

private const val TAG = "WidgetStateFetchers"

/**
 * Upper bound for a widget's data fetch. A fetch that exceeds this is abandoned and the
 * caller falls back to its empty/error state — the widget's Glance session must never be
 * parked inside a fetch for minutes, because actionRunCallback taps (month navigation,
 * refresh) are processed on that same session and would appear frozen. The prime suspect
 * for multi-minute stalls is a CalendarProvider Instances query while the system's sync
 * adapter holds the provider (contentResolver.query has no timeout of its own).
 */
internal const val WIDGET_FETCH_TIMEOUT_MS = 10_000L

/**
 * Thrown when a widget data fetch exceeds [WIDGET_FETCH_TIMEOUT_MS]. Distinct from
 * [kotlinx.coroutines.TimeoutCancellationException] (a [CancellationException] subclass)
 * so the generic `catch (e: Exception)` fallback below handles it — a fetch timeout must
 * degrade to the widget's empty/error state, not cancel the session's producer coroutine.
 */
internal class WidgetFetchTimeoutException(message: String) : Exception(message)

/**
 * Sealed state for [UpcomingWidget]. The scaffold composable branches on this to render
 * loading, error, or the content composable.
 */
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

/**
 * Fetch the Upcoming widget's state for the next [horizonDays] days.
 * Returns [UpcomingState.Error] on any failure so the caller never sees a throw.
 */
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
 * The agenda header's date line, localized for the current locale. Shared with the
 * widget-picker preview so a preview header can't drift from the real one.
 */
internal fun widgetHeaderDate(nowMs: Long = System.currentTimeMillis()): String =
    DateTimeUtils.formatEventDate(
        timestampMs = nowMs,
        isAllDay = false,
        pattern = DateTimeUtils.localizedPattern("EEEEMMMd")
    )

/**
 * Fetch the Agenda widget's data. Returns an empty-events shape with default prefs
 * on any failure so the existing "no events today" UI renders gracefully.
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
 * Fetch the Week widget's data. Returns an empty weekly map with default prefs
 * on any failure so existing empty-state UI renders gracefully.
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
 * Fetch the Month widget's events for the given dayCode range.
 * MonthWidget's other preferences (MONTH_OFFSET, firstDayOfWeek, grid) stay widget-side
 * because they drive the composable's render logic, not just the data fetch.
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
            Log.w(TAG, "fetchMonthEvents timed out after ${WIDGET_FETCH_TIMEOUT_MS}ms; keeping previous content")
            emptyMap()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "fetchMonthEvents failed", e)
        emptyMap()
    }
}
