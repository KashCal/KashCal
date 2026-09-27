package org.onekash.kashcal.domain.reader

import android.text.format.DateUtils
import android.util.Log
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.onekash.kashcal.data.calendar_provider.CalendarProviderManager
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.dayCodeToEndOfDayMs
import org.onekash.kashcal.data.calendar_provider.dayCodeToStartOfDayMs
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.TitleSuggestion
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.SearchResult
import org.onekash.kashcal.ui.util.DayPagerUtils
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Merges Room events ([EventReader]) and device-calendar events so ViewModels and widgets
 * only see [DisplayEvent].
 *
 * A CalendarProvider [SecurityException] is caught and falls back to Room-only results.
 */
@Singleton
class DisplayEventRepository @Inject constructor(
    private val eventReader: EventReader,
    private val calendarProviderRepository: CalendarProviderRepository,
    private val calendarProviderManager: CalendarProviderManager,
    private val dataStore: KashCalDataStore,
    private val attendeesDao: AttendeesDao,
    private val accountsDao: AccountsDao
) {
    companion object {
        private const val TAG = "DisplayEventRepo"
    }

    /**
     * Emits when device calendar data changes ([CalendarProviderManager.changeSignal]), so
     * ViewModels can invalidate one-shot caches (e.g. month grid event dots) without importing
     * CalendarProviderManager.
     */
    val deviceCalendarChangeSignal: StateFlow<Int> get() = calendarProviderManager.changeSignal

    /**
     * Emits the day pager's events, from 3 days before [centerDateMs] to 4 days after, grouped
     * by day code and sorted by start.
     *
     * Re-emits, re-querying CalendarProvider, when the Room flow
     * ([EventReader.getVisibleOccurrencesWithEventsInRangeFlow]), the device change signal, the
     * show-declined preference or any attendee row changes.
     */
    fun getDisplayEventsForDayRange(
        centerDateMs: Long
    ): Flow<ImmutableMap<Int, ImmutableList<DisplayEvent>>> {
        val rangeStart = centerDateMs - (3 * DayPagerUtils.DAY_MS)
        val rangeEnd = centerDateMs + (4 * DayPagerUtils.DAY_MS)
        val startDayCode = DayPagerUtils.msToDayCode(rangeStart)
        val endDayCode = DayPagerUtils.msToDayCode(rangeEnd)

        return combine(
            eventReader.getVisibleOccurrencesWithEventsInRangeFlow(rangeStart, rangeEnd),
            calendarProviderManager.changeSignal,
            dataStore.showDeclinedEvents,
            attendeesDao.attendeesChangeSignal()
        ) { roomOccurrences, _, showDeclined, _ ->
            val roomEvents = applyDeclinedPolicy(roomOccurrences, showDeclined)
            val deviceEvents = queryDeviceEvents(startDayCode, endDayCode)
            mergeAndGroupByDay(roomEvents, deviceEvents, startDayCode, endDayCode)
        }
    }

    /**
     * Emits the events in [startMs]..[endMs] (inclusive) as one list sorted by start, for the
     * agenda and the week, 3-day and day grids. Re-emits on the same sources as
     * [getDisplayEventsForDayRange].
     */
    fun getDisplayEventsForRange(
        startMs: Long,
        endMs: Long
    ): Flow<ImmutableList<DisplayEvent>> {
        val startDayCode = DayPagerUtils.msToDayCode(startMs)
        val endDayCode = DayPagerUtils.msToDayCode(endMs)

        return combine(
            eventReader.getVisibleOccurrencesWithEventsInRangeFlow(startMs, endMs),
            calendarProviderManager.changeSignal,
            dataStore.showDeclinedEvents,
            attendeesDao.attendeesChangeSignal()
        ) { roomOccurrences, _, showDeclined, _ ->
            val roomEvents = applyDeclinedPolicy(roomOccurrences, showDeclined)
            val deviceEvents = queryDeviceEvents(startDayCode, endDayCode)
            (roomEvents + deviceEvents)
                .sortedBy { it.startTs }
                .toPersistentList()
        }
    }

    /**
     * Emits the events from [startDayCode] to [endDayCode] (YYYYMMDD, inclusive) grouped by
     * day code, for the full-height month grid. Same shape and sources as
     * [getDisplayEventsForDayRange].
     */
    fun getDisplayEventsForDateRange(
        startDayCode: Int,
        endDayCode: Int
    ): Flow<ImmutableMap<Int, ImmutableList<DisplayEvent>>> {
        val startMs = dayCodeToStartOfDayMs(startDayCode)
        val endMs = dayCodeToEndOfDayMs(endDayCode)

        return combine(
            eventReader.getVisibleOccurrencesWithEventsInRangeFlow(startMs, endMs),
            calendarProviderManager.changeSignal,
            dataStore.showDeclinedEvents,
            attendeesDao.attendeesChangeSignal()
        ) { roomOccurrences, _, showDeclined, _ ->
            val roomEvents = applyDeclinedPolicy(roomOccurrences, showDeclined)
            val deviceEvents = queryDeviceEvents(startDayCode, endDayCode)
            mergeAndGroupByDay(roomEvents, deviceEvents, startDayCode, endDayCode)
        }
    }

    /**
     * Returns the Room and CalendarProvider matches for [query], sorted by `displayTs`; empty
     * for a blank query.
     *
     * @param startDayCode first day (YYYYMMDD, inclusive) of the device search; the Room search
     *   gets only [query].
     * @param endDayCode last day (YYYYMMDD, inclusive) of the device search.
     * @param roomSearcher runs the Room FTS search.
     */
    suspend fun searchDisplayEvents(
        query: String,
        startDayCode: Int,
        endDayCode: Int,
        roomSearcher: suspend (String) -> List<SearchResult>
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        val roomResults = roomSearcher(query)

        val deviceResults = try {
            val visibleIds = getVisibleDeviceCalendarIds()
            if (visibleIds.isNotEmpty()) {
                val hideDeclined = !dataStore.getShowDeclinedEvents()
                calendarProviderRepository.searchInstances(
                    query, startDayCode, endDayCode, visibleIds, hideDeclined
                ).map { SearchResult(DisplayEvent.Device(it), it.startTs) }
            } else {
                emptyList()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar permission revoked during search, falling back to Room-only", e)
            emptyList()
        }

        return (roomResults + deviceResults).sortedBy { it.displayTs }
    }

    /**
     * Returns the events from [startDayCode] to [endDayCode] (YYYYMMDD, inclusive) grouped by
     * day code, once, for callers that don't need updates (for example widgets and month and
     * year dots).
     *
     * Takes the Room Flow's first value and queries the provider directly; `combine().first()`
     * would set up the reactive machinery for a single emission.
     */
    suspend fun getDisplayEventsGroupedByDayOnce(
        startDayCode: Int,
        endDayCode: Int
    ): Map<Int, List<DisplayEvent>> {
        val startMs = dayCodeToStartOfDayMs(startDayCode)
        val endMs = dayCodeToEndOfDayMs(endDayCode)

        val roomOccurrences = eventReader
            .getVisibleOccurrencesWithEventsInRangeFlow(startMs, endMs)
            .first()
        val showDeclined = dataStore.getShowDeclinedEvents()
        val roomEvents = applyDeclinedPolicy(roomOccurrences, showDeclined)
        val deviceEvents = queryDeviceEvents(startDayCode, endDayCode)

        return mergeAndGroupByDay(roomEvents, deviceEvents, startDayCode, endDayCode)
    }

    /**
     * Suggests event titles from Room and device-calendar history that match [prefix], for the
     * event-form autocomplete. Empty for a prefix shorter than [TITLE_SUGGESTION_MIN_PREFIX].
     *
     * Queries both sources in parallel and merges them with [mergeTitleSuggestions]. Only
     * visible device calendars count ([getVisibleDeviceCalendarIds]). The device repository
     * returns empty on a [SecurityException], so there is no guard here.
     */
    suspend fun suggestTitles(
        prefix: String,
        windowDays: Int = TITLE_SUGGESTION_WINDOW_DAYS,
        futureWindowDays: Int = TITLE_SUGGESTION_WINDOW_FUTURE_DAYS,
        minFreq: Int = TITLE_SUGGESTION_MIN_FREQ,
        limit: Int = TITLE_SUGGESTION_LIMIT
    ): List<TitleSuggestion> {
        if (prefix.length < TITLE_SUGGESTION_MIN_PREFIX) return emptyList()

        val nowMs = System.currentTimeMillis()
        val sinceMs = nowMs - windowDays * DateUtils.DAY_IN_MILLIS
        val untilMs = nowMs + futureWindowDays * DateUtils.DAY_IN_MILLIS

        val (roomResults, deviceResults) = coroutineScope {
            val roomAsync = async {
                eventReader.suggestTitles(prefix, sinceMs, untilMs, minFreq = minFreq, limit = limit)
            }
            val deviceAsync = async {
                val visibleIds = getVisibleDeviceCalendarIds()
                if (visibleIds.isEmpty()) emptyList()
                else calendarProviderRepository.suggestTitlesByPrefix(
                    prefix, sinceMs, untilMs, visibleIds, minFreq = minFreq, limit = limit
                )
            }
            roomAsync.await() to deviceAsync.await()
        }

        return mergeTitleSuggestions(roomResults, deviceResults, minFreq, limit)
    }

    /**
     * Applies the "Show declined events" preference to Room occurrences.
     *
     * Finds the events the user declined ([selfDeclinedEventIds], matched per owning account),
     * then drops them when [showDeclined] is off (the default) or marks them
     * `isDeclinedByMe = true` when on, so the UI dims and strikes them through. Device events
     * carry [DisplayEvent.Device.isDeclinedByMe] from the instance's `selfAttendeeStatus`; the
     * preference reaches them as the `hideDeclined` flag of the [CalendarProviderRepository]
     * query.
     */
    private suspend fun applyDeclinedPolicy(
        roomOccurrences: List<EventReader.OccurrenceWithEvent>,
        showDeclined: Boolean
    ): List<DisplayEvent.Room> {
        if (roomOccurrences.isEmpty()) return emptyList()

        val eventIds = roomOccurrences.map { it.event.id }.distinct()
        val declinedAttendees = attendeesDao.getDeclinedAttendeesForEvents(eventIds)
        if (declinedAttendees.isEmpty()) {
            return roomOccurrences.map {
                DisplayEvent.Room(it.event, it.occurrence, it.calendar)
            }
        }

        val accountsById = accountsDao.getAllOnce().associateBy { it.id }
        val calendarsById: Map<Long, Calendar> = roomOccurrences
            .mapNotNull { it.calendar }
            .associateBy { it.id }
        val eventIdToCalendarId = roomOccurrences.associate { it.event.id to it.event.calendarId }

        val declinedByMeIds = selfDeclinedEventIds(
            declinedAttendees = declinedAttendees,
            accountsById = accountsById,
            eventIdToCalendarId = eventIdToCalendarId,
            calendarsById = calendarsById
        )

        return if (showDeclined) {
            roomOccurrences.map {
                DisplayEvent.Room(
                    event = it.event,
                    occurrence = it.occurrence,
                    calendar = it.calendar,
                    isDeclinedByMe = it.event.id in declinedByMeIds
                )
            }
        } else {
            roomOccurrences
                .filter { it.event.id !in declinedByMeIds }
                .map { DisplayEvent.Room(it.event, it.occurrence, it.calendar) }
        }
    }

    /**
     * Returns the visible device calendars' instances for a day code range
     * ([getVisibleDeviceCalendarIds]); empty when none is visible or on a [SecurityException].
     */
    private suspend fun queryDeviceEvents(
        startDayCode: Int,
        endDayCode: Int
    ): List<DisplayEvent> {
        return try {
            val visibleIds = getVisibleDeviceCalendarIds()
            if (visibleIds.isNotEmpty()) {
                val hideDeclined = !dataStore.getShowDeclinedEvents()
                calendarProviderRepository.getInstancesForDayRange(
                    startDayCode, endDayCode, visibleIds, hideDeclined
                ).map { DisplayEvent.Device(it) }
            } else {
                emptyList()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar permission revoked, falling back to Room-only", e)
            emptyList()
        }
    }

    /** Returns the enabled, not hidden device calendar IDs; empty when the feature is off. */
    private suspend fun getVisibleDeviceCalendarIds(): Set<Long> {
        val featureEnabled = dataStore.getDeviceCalendarsEnabled()
        val enabledIds = if (featureEnabled) dataStore.getEnabledDeviceCalendarIds() else emptySet()
        val hiddenIds = if (featureEnabled) dataStore.getHiddenDeviceCalendarIds() else emptySet()
        return enabledIds - hiddenIds
    }

    /**
     * Merges Room and device events into day code buckets sorted by start. A multi-day event
     * goes into each day it occupies within the window ([spannedDayCodesWithinWindow]).
     */
    private fun mergeAndGroupByDay(
        roomEvents: List<DisplayEvent>,
        deviceEvents: List<DisplayEvent>,
        windowStartDayCode: Int,
        windowEndDayCode: Int
    ): ImmutableMap<Int, ImmutableList<DisplayEvent>> {
        return (roomEvents + deviceEvents)
            .flatMap { event ->
                spannedDayCodesWithinWindow(
                    startDay = event.startDay,
                    endDay = event.endDay,
                    windowStartDayCode = windowStartDayCode,
                    windowEndDayCode = windowEndDayCode
                ).map { dayCode -> dayCode to event }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.sortedBy { it.startTs }.toPersistentList() }
            .toPersistentMap()
    }
}

private const val LOG_TAG = "DisplayEventRepo"
private const val MAX_RANGE_DAYS = 366L

/** Defaults for [DisplayEventRepository.suggestTitles]. */
const val TITLE_SUGGESTION_MIN_PREFIX = 3
const val TITLE_SUGGESTION_WINDOW_DAYS = 90
const val TITLE_SUGGESTION_WINDOW_FUTURE_DAYS = 7
const val TITLE_SUGGESTION_MIN_FREQ = 2
const val TITLE_SUGGESTION_LIMIT = 5

/**
 * Merges two [TitleSuggestion] lists by `title.trim().lowercase()`.
 *
 * Each group takes the trimmed title of its latest `lastUsed` entry, the sum of `freq` and
 * the max `lastUsed`. The result keeps `freq >= minFreq`, sorts by freq then lastUsed, both
 * descending, and takes [limit].
 */
internal fun mergeTitleSuggestions(
    room: List<TitleSuggestion>,
    device: List<TitleSuggestion>,
    minFreq: Int,
    limit: Int
): List<TitleSuggestion> {
    return (room + device)
        .groupBy { it.title.trim().lowercase() }
        .map { (_, entries) ->
            val latest = entries.maxByOrNull { it.lastUsed }!!
            TitleSuggestion(
                title = latest.title.trim(),
                freq = entries.sumOf { it.freq },
                lastUsed = entries.maxOf { it.lastUsed }
            )
        }
        .filter { it.freq >= minFreq }
        .sortedWith(
            compareByDescending<TitleSuggestion> { it.freq }
                .thenByDescending { it.lastUsed }
        )
        .take(limit)
}

/**
 * Returns the day codes of the event's `[startDay, endDay]` span that fall within
 * `[windowStartDayCode, windowEndDayCode]`, both inclusive; empty when they don't overlap.
 *
 * Without this clamp a multi-day event that began before the window adds buckets before it,
 * and the upcoming widget shows a first row dated before today (issue #306).
 */
internal fun spannedDayCodesWithinWindow(
    startDay: Int,
    endDay: Int,
    windowStartDayCode: Int,
    windowEndDayCode: Int
): List<Int> {
    val clampedStart = maxOf(startDay, windowStartDayCode)
    val clampedEnd = minOf(endDay, windowEndDayCode)
    if (clampedStart > clampedEnd) return emptyList()
    return generateDayCodesInRange(clampedStart, clampedEnd)
}

/**
 * Returns the YYYYMMDD day code of each day from [startDayCode] to [endDayCode], inclusive,
 * crossing month and year boundaries with [LocalDate].
 *
 * Returns an empty list, with a log line, for a day code below 10000101, a reversed range or a
 * span over 366 days.
 */
internal fun generateDayCodesInRange(startDayCode: Int, endDayCode: Int): List<Int> {
    if (startDayCode < 10000101 || endDayCode < 10000101) {
        Log.w(LOG_TAG, "Invalid day codes: start=$startDayCode, end=$endDayCode")
        return emptyList()
    }
    if (startDayCode > endDayCode) {
        Log.w(LOG_TAG, "Reversed day code range: start=$startDayCode, end=$endDayCode")
        return emptyList()
    }
    val startDate = dayCodeToLocalDate(startDayCode)
    val endDate = dayCodeToLocalDate(endDayCode)
    if (ChronoUnit.DAYS.between(startDate, endDate) > MAX_RANGE_DAYS) {
        Log.w(LOG_TAG, "Day range too large: start=$startDayCode, end=$endDayCode")
        return emptyList()
    }

    val result = mutableListOf<Int>()
    var current = startDate
    while (!current.isAfter(endDate)) {
        result.add(current.year * 10000 + current.monthValue * 100 + current.dayOfMonth)
        current = current.plusDays(1)
    }
    return result
}

private fun dayCodeToLocalDate(dayCode: Int): LocalDate {
    val year = dayCode / 10000
    val month = (dayCode % 10000) / 100
    val day = dayCode % 100
    return LocalDate.of(year, month, day)
}
