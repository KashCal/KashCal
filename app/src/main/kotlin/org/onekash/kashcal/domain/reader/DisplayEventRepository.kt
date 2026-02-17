package org.onekash.kashcal.domain.reader

import android.util.Log
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.onekash.kashcal.data.calendar_provider.CalendarProviderManager
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.dayCodeToStartOfDayMs
import org.onekash.kashcal.data.calendar_provider.dayCodeToEndOfDayMs
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.SearchResult
import org.onekash.kashcal.ui.util.DayPagerUtils
import java.time.LocalDate
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
    private val dataStore: KashCalDataStore
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
            calendarProviderManager.changeSignal
        ) { roomOccurrences, _ ->
            val roomEvents = roomOccurrences.map {
                DisplayEvent.Room(it.event, it.occurrence, it.calendar)
            }

            val deviceEvents = queryDeviceEvents(startDayCode, endDayCode)

            mergeAndGroupByDay(roomEvents, deviceEvents)
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
            calendarProviderManager.changeSignal
        ) { roomOccurrences, _ ->
            val roomEvents = roomOccurrences.map {
                DisplayEvent.Room(it.event, it.occurrence, it.calendar)
            }

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
            calendarProviderManager.changeSignal
        ) { roomOccurrences, _ ->
            val roomEvents = roomOccurrences.map {
                DisplayEvent.Room(it.event, it.occurrence, it.calendar)
            }

            val deviceEvents = queryDeviceEvents(startDayCode, endDayCode)

            mergeAndGroupByDay(roomEvents, deviceEvents)
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
            val featureEnabled = dataStore.getDeviceCalendarsEnabled()
            val enabledIds = if (featureEnabled) dataStore.getEnabledDeviceCalendarIds() else emptySet()
            if (enabledIds.isNotEmpty()) {
                val hideDeclined = !dataStore.getShowDeclinedEvents()
                calendarProviderRepository.searchInstances(
                    query, startDayCode, endDayCode, enabledIds, hideDeclined
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
        val roomEvents = roomOccurrences.map {
            DisplayEvent.Room(it.event, it.occurrence, it.calendar)
        }

        val deviceEvents = queryDeviceEvents(startDayCode, endDayCode)

        return mergeAndGroupByDay(roomEvents, deviceEvents)
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
            val featureEnabled = dataStore.getDeviceCalendarsEnabled()
            val enabledIds = if (featureEnabled) dataStore.getEnabledDeviceCalendarIds() else emptySet()
            if (enabledIds.isNotEmpty()) {
                val hideDeclined = !dataStore.getShowDeclinedEvents()
                calendarProviderRepository.getInstancesForDayRange(
                    startDayCode, endDayCode, enabledIds, hideDeclined
                ).map { DisplayEvent.Device(it) }
            } else {
                emptyList()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar permission revoked, falling back to Room-only", e)
            emptyList()
        }
    }

    /**
     * Merge Room + device events, expand multi-day events, group by day code, sort.
     */
    private fun mergeAndGroupByDay(
        roomEvents: List<DisplayEvent>,
        deviceEvents: List<DisplayEvent>
    ): ImmutableMap<Int, ImmutableList<DisplayEvent>> {
        return (roomEvents + deviceEvents)
            .flatMap { event ->
                if (event.startDay == event.endDay) {
                    listOf(event.startDay to event)
                } else {
                    generateDayCodesInRange(event.startDay, event.endDay)
                        .map { dayCode -> dayCode to event }
                }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.sortedBy { it.startTs }.toPersistentList() }
            .toPersistentMap()
    }
}

/**
 * Generate a list of YYYYMMDD day codes for each day from startDayCode to endDayCode (inclusive).
 * Handles month/year boundaries correctly via LocalDate arithmetic.
 */
internal fun generateDayCodesInRange(startDayCode: Int, endDayCode: Int): List<Int> {
    val startDate = dayCodeToLocalDate(startDayCode)
    val endDate = dayCodeToLocalDate(endDayCode)

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
