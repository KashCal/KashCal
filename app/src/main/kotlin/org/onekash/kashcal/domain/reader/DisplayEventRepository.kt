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
 * Composite repository merging Room (EventReader) + device calendar events.
 *
 * Follows NIA's CompositeUserNewsResourceRepository pattern: combine two
 * data sources in a domain-layer class so ViewModels only see [DisplayEvent].
 *
 * SecurityException from CalendarProvider is caught and falls back to Room-only.
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
     * Signal that device calendar data has changed.
     * Exposes [CalendarProviderManager.changeSignal] so ViewModels can invalidate
     * one-shot caches (e.g., month grid event dots) without importing CalendarProviderManager.
     */
    val deviceCalendarChangeSignal: StateFlow<Int> get() = calendarProviderManager.changeSignal

    /**
     * Signal that the app itself just wrote to a device calendar, so the
     * reactive views (day pager, agenda, week, month dots) re-query device
     * events immediately instead of waiting for the debounced ContentObserver.
     * The re-query still runs when no device calendars are visible; it just
     * returns no device events.
     */
    fun notifyDeviceCalendarChanged() {
        calendarProviderManager.notifyLocalChange()
    }

    /**
     * Get display events for a day pager range, grouped by day code.
     *
     * Combines Room Flow + changeSignal. When either emits, re-queries
     * CalendarProvider and merges results.
     *
     * @param centerDateMs Center date of the pager range (ms)
     * @return Flow of day code -> sorted events map
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
     * Get display events for a timestamp range as a flat list.
     *
     * Used by agenda view and 3-day view. Combines Room Flow + changeSignal.
     *
     * @param startMs Start of range in epoch millis (inclusive)
     * @param endMs End of range in epoch millis (inclusive)
     * @return Flow of sorted display events
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
     * Get display events for a day code range, grouped by day code.
     *
     * Used by batch prefetch. Same pattern as [getDisplayEventsForDayRange] but
     * takes day codes instead of a center date.
     *
     * @param startDayCode Start day in YYYYMMDD format (inclusive)
     * @param endDayCode End day in YYYYMMDD format (inclusive)
     * @return Flow of day code -> sorted events map
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
     * Search for display events matching a text query.
     *
     * Merges Room FTS search results + CalendarProvider search results.
     * Returns a flat list of [SearchResult] sorted by displayTs.
     *
     * @param query Search text
     * @param startDayCode Start day in YYYYMMDD format (inclusive), or null for unbounded Room search
     * @param endDayCode End day in YYYYMMDD format (inclusive), or null for unbounded Room search
     * @param roomSearcher Lambda to perform the Room FTS search (injected to keep EventReader flexible)
     * @return Merged search results sorted by displayTs
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
     * One-shot query for display events grouped by day code.
     *
     * Used by widgets and month grid event dots — contexts that need current data
     * but don't need reactive updates.
     *
     * Uses dedicated one-shot path: EventReader.first() + CalendarProvider query,
     * NOT combine().first() which would set up reactive machinery for a single emission.
     *
     * @param startDayCode Start day in YYYYMMDD format (inclusive)
     * @param endDayCode End day in YYYYMMDD format (inclusive)
     * @return Map of day code -> sorted events
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
     * Suggest event titles from user history (Room + device calendar) matching
     * a prefix, for the event-form autocomplete dropdown.
     *
     * Queries both sources in parallel, then merges by case-and-whitespace-
     * normalized title. Frequencies sum across sources; display casing comes
     * from the entry with the most recent [TitleSuggestion.lastUsed].
     *
     * Respects the user's device-calendar visibility settings via the shared
     * [getVisibleDeviceCalendarIds] helper. The device repository catches
     * [SecurityException] internally and returns empty — no extra guard here.
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
     * Apply the "Show declined events" preference to Room occurrences.
     *
     * Resolves which event IDs the current user has declined (matched
     * against the event's owning calendar's account, so the same address
     * can decline in one account without flagging another), then either
     * filters them out (toggle off — default) or maps them to
     * [DisplayEvent.Room] with `isDeclinedByMe = true` (toggle on, so the
     * UI can dim + strike-through). Device-side declined events are
     * handled directly by [DisplayEvent.Device.isDeclinedByMe] reading the
     * instance's `selfAttendeeStatus` — the toggle for the device side is
     * applied at query time via the `hideDeclined` flag passed into
     * [CalendarProviderRepository].
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
     * Query device calendar events for a day code range.
     *
     * Shared helper for all methods that need device events.
     * Checks feature enabled + enabled calendar IDs + SecurityException.
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

    private suspend fun getVisibleDeviceCalendarIds(): Set<Long> {
        val featureEnabled = dataStore.getDeviceCalendarsEnabled()
        val enabledIds = if (featureEnabled) dataStore.getEnabledDeviceCalendarIds() else emptySet()
        val hiddenIds = if (featureEnabled) dataStore.getHiddenDeviceCalendarIds() else emptySet()
        return enabledIds - hiddenIds
    }

    /**
     * Merge Room + device events, expand multi-day events, group by day code, sort.
     *
     * Multi-day events are expanded only across the days they occupy WITHIN the
     * requested `[windowStartDayCode, windowEndDayCode]` window. Expanding across
     * the event's own full span would leak buckets outside the window — e.g. an
     * event that began before the window start would produce day buckets before
     * it, which surfaced in the upcoming widget as a first row dated before today
     * (issue #306).
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
 * Merge two [TitleSuggestion] lists by normalized title.
 *
 * Grouping key: title.trim().lowercase(). For each group:
 * - `title`: original casing from the entry with max `lastUsed`
 * - `freq`: sum of entries' freq
 * - `lastUsed`: max of entries' lastUsed
 *
 * Result is filtered to `freq >= minFreq`, sorted by freq DESC then
 * lastUsed DESC, and truncated to [limit].
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
 * Day-code buckets an event occupies WITHIN a query window.
 *
 * Intersects the event's own `[startDay, endDay]` span with the requested
 * `[windowStartDayCode, windowEndDayCode]` window, then expands the intersection
 * to inclusive day codes. Returns an empty list when the event lies entirely
 * outside the window (its intersection is empty).
 *
 * This is the clamp that keeps a multi-day event from producing day buckets
 * outside the range the caller asked for (issue #306).
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
 * Generate a list of YYYYMMDD day codes for each day from startDayCode to endDayCode (inclusive).
 * Handles month/year boundaries correctly via LocalDate arithmetic.
 *
 * Returns emptyList() for invalid inputs (day codes < 10000101, reversed range, or span > 366 days).
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
