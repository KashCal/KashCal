package org.onekash.kashcal.domain.reader

import android.text.format.DateUtils
import android.util.Log
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import org.onekash.kashcal.data.calendar_provider.CalendarProviderManager
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.calendar_provider.dayCodeToEndOfDayMs
import org.onekash.kashcal.data.calendar_provider.dayCodeToStartOfDayMs
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.TitleSuggestion
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.overlapsWindow
import org.onekash.kashcal.domain.search.RoomSearchMatch
import org.onekash.kashcal.domain.search.SearchFold
import org.onekash.kashcal.domain.search.SearchHit
import org.onekash.kashcal.domain.search.SearchMatchExplainer
import org.onekash.kashcal.domain.search.SearchOutcome
import org.onekash.kashcal.domain.search.SearchQuery
import org.onekash.kashcal.domain.search.SearchSplitter
import org.onekash.kashcal.domain.search.SearchTiming
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Merges Room events ([EventReader]) and device-calendar events so ViewModels and widgets
 * only see [DisplayEvent].
 *
 * Each range read first has the repeating series expanded to its range
 * ([OccurrenceGenerator.ensureCovered]), which writes occurrence rows; [observeSearch] expands
 * only the series it matched.
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
    private val accountsDao: AccountsDao,
    private val occurrenceGenerator: OccurrenceGenerator,
) {
    companion object {
        private const val TAG = "DisplayEventRepo"

        private const val DEVICE_SEARCH_MAX_YEARS_BACK = 10L

        /** How far ahead search looks: the device window and the expansion of matched series. */
        private const val SEARCH_YEARS_AHEAD = 2L
    }

    /**
     * Emits when device calendar data changes ([CalendarProviderManager.changeSignal]), so
     * ViewModels can invalidate one-shot caches (e.g. month grid event dots) without importing
     * CalendarProviderManager.
     */
    val deviceCalendarChangeSignal: StateFlow<Int> get() = calendarProviderManager.changeSignal

    /**
     * Emits the visible Room occurrences in [startMs]..[endMs] at once and, on each emission,
     * expands the repeating series to that range alongside ([OccurrenceGenerator.ensureCovered],
     * in memory when nothing changed), so a series rewritten while the view is open is expanded
     * again; Room emits again when the new rows land.
     */
    private fun coveredRoomRange(startMs: Long, endMs: Long): Flow<List<EventReader.OccurrenceWithEvent>> = channelFlow {
        launch { occurrenceGenerator.ensureCovered(startMs, endMs) }
        eventReader.getVisibleOccurrencesWithEventsInRangeFlow(startMs, endMs).collect { rows ->
            send(rows)
            launch { occurrenceGenerator.ensureCovered(startMs, endMs) }
        }
    }

    /**
     * Emits the day pager's events, from 3 days before [centerDateMs] to 4 days after, grouped
     * by day code and sorted by start.
     *
     * Re-emits, re-querying CalendarProvider, when the Room flow
     * ([EventReader.getVisibleOccurrencesWithEventsInRangeFlow]), the device change signal, the
     * show-declined preference, any attendee row or the device calendars hidden from the drawer
     * ([KashCalDataStore.hiddenDeviceCalendarIds]) change. Hiding one bumps no change signal, so
     * without that last source an open view would keep its events.
     */
    fun getDisplayEventsForDayRange(
        centerDateMs: Long
    ): Flow<ImmutableMap<Int, ImmutableList<DisplayEvent>>> {
        val rangeStart = centerDateMs - (3 * DayPagerUtils.DAY_MS)
        val rangeEnd = centerDateMs + (4 * DayPagerUtils.DAY_MS)
        val startDayCode = DayPagerUtils.msToDayCode(rangeStart)
        val endDayCode = DayPagerUtils.msToDayCode(rangeEnd)

        return combine(
            coveredRoomRange(rangeStart, rangeEnd),
            calendarProviderManager.changeSignal,
            dataStore.showDeclinedEvents,
            attendeesDao.attendeesChangeSignal(),
            dataStore.hiddenDeviceCalendarIds
        ) { roomOccurrences, _, showDeclined, _, _ ->
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
            coveredRoomRange(startMs, endMs),
            calendarProviderManager.changeSignal,
            dataStore.showDeclinedEvents,
            attendeesDao.attendeesChangeSignal(),
            dataStore.hiddenDeviceCalendarIds
        ) { roomOccurrences, _, showDeclined, _, _ ->
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
            coveredRoomRange(startMs, endMs),
            calendarProviderManager.changeSignal,
            dataStore.showDeclinedEvents,
            attendeesDao.attendeesChangeSignal(),
            dataStore.hiddenDeviceCalendarIds
        ) { roomOccurrences, _, showDeclined, _, _ ->
            val roomEvents = applyDeclinedPolicy(roomOccurrences, showDeclined)
            val deviceEvents = queryDeviceEvents(startDayCode, endDayCode)
            mergeAndGroupByDay(roomEvents, deviceEvents, startDayCode, endDayCode)
        }
    }

    /**
     * Holds the device instances a search found, in the default window and in the range, how
     * long it took, and the sync lookback the phone holds events for.
     */
    private class DeviceSearch(
        val all: List<DeviceCalendarInstance>,
        val inRange: List<DeviceCalendarInstance>?,
        val ms: Long,
        val lookbackDays: Int,
    )

    /**
     * Emits one search's results, split by [SearchSplitter] into Upcoming, Past, hidden
     * calendars and, with [range] (inclusive, in ms), the matches in it.
     *
     * Room is searched once per call and emits again when events, occurrences or attendees
     * change ([EventReader.observeSearchMatches]); for a query that [SearchQuery.expandsSeries],
     * each emission also covers the matched repeating series whose stored rows lack a date
     * ([seriesToCover], [OccurrenceGenerator.ensureCoveredFor], not awaited), so such a series is
     * dated once its rows land.
     * With [range] the range is covered first ([OccurrenceGenerator.ensureCovered]), so the
     * first range list is whole. Device calendars are searched by
     * [SearchQuery.toProviderQuery] over every enabled calendar, hidden ones included, from the
     * sync lookback (10 years back at most) to 2 years ahead, and again over [range]'s days;
     * only the device change signal, the hidden-calendar set and the sync lookback (which moves
     * the window and the empty state's lookback line) run that search again. A calendar
     * visibility change splits again without searching. A device [SecurityException]
     * leaves the Room results. [now] and [zone] decide which occurrences have ended. Nothing
     * here touches the network.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSearch(query: SearchQuery, now: Long, zone: ZoneId, range: LongRange? = null): Flow<SearchOutcome> {
        if (query.words.isEmpty()) return flowOf(SearchOutcome())
        val started = System.nanoTime()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val todayCode = DayPagerUtils.localDateToDayCode(today)
        val coverTo = today.plusYears(SEARCH_YEARS_AHEAD).atStartOfDay(zone).toInstant().toEpochMilli()
        val coverageMs = MutableStateFlow<Long?>(null)

        val room = channelFlow {
            // Covered before the first read, so the first range list is whole.
            if (range != null) occurrenceGenerator.ensureCovered(range.first, range.last)
            var firstMs: Long? = null
            eventReader.observeSearchMatches(query, now, todayCode, range).collect { matches ->
                val ms = firstMs ?: elapsedMs(started).also { firstMs = it }
                send(matches to ms)
                val toCover = if (query.expandsSeries) seriesToCover(matches, now) else null
                if (toCover != null && !toCover.isEmpty) {
                    launch {
                        val coverStart = System.nanoTime()
                        if (occurrenceGenerator.ensureCoveredFor(toCover.fromStart, coverTo, toCover.forwardOnly) > 0) {
                            coverageMs.value = elapsedMs(coverStart)
                        }
                    }
                }
            }
        }.distinctUntilChanged()

        val device = combine(
            calendarProviderManager.changeSignal,
            dataStore.hiddenDeviceCalendarIds,
            dataStore.syncPastDays,
        ) { _, _, _ -> }
            .mapLatest { searchDevice(query, today, range, zone) }

        return combine(room, device, eventReader.getAllCalendars(), dataStore.hiddenDeviceCalendarIds, coverageMs) {
                (matches, roomMs), deviceSearch, calendars, hiddenDeviceIds, coverage ->
            val outcome = SearchSplitter.split(
                room = matches,
                calendars = calendars.associateBy { it.id },
                device = deviceSearch.all,
                deviceInRange = deviceSearch.inRange,
                hiddenDeviceCalendarIds = hiddenDeviceIds,
                now = now,
                todayDayCode = todayCode,
                zone = zone,
                range = range,
                timing = SearchTiming(roomMs, deviceSearch.ms, elapsedMs(started), coverage),
            )
            withReasons(outcome.copy(lookbackDays = deviceSearch.lookbackDays), query.words)
        }.flowOn(Dispatchers.IO)
    }

    /** The series a search covers: [fromStart] back to their DTSTART, [forwardOnly] ahead only. */
    private class SeriesToCover(val fromStart: List<Long>, val forwardOnly: List<Long>) {
        val isEmpty: Boolean get() = fromStart.isEmpty() && forwardOnly.isEmpty()
    }

    /**
     * Picks the matched repeating series whose stored rows lack a date. A series is filled back
     * to its DTSTART when it has no ended row though it started before [now] (its last
     * occurrence is missing), or when it has no ended row and its earliest stored row comes
     * after its DTSTART (rows that began at a view's floor, so its first occurrence is missing).
     * Otherwise a series with no row not yet ended is extended forward only (its next occurrence
     * is missing). A series with both is left alone: rows are stored without gaps from their
     * earliest ([OccurrenceGenerator.ensureCoveredFor]), so its latest ended row is its last
     * occurrence. A changed occurrence never asks for expansion, since the master row linked to
     * it dates it.
     */
    private fun seriesToCover(matches: List<RoomSearchMatch>, now: Long): SeriesToCover {
        val fromStart = mutableListOf<Long>()
        val forwardOnly = mutableListOf<Long>()
        for (m in matches) {
            val event = m.event
            if (event.originalEventId != null || event.rrule.isNullOrBlank()) continue
            // Rule rows are whole seconds, so DTSTART is compared at whole seconds too.
            val firstInstance = event.startTs / 1000 * 1000
            when {
                m.last == null && event.startTs < now -> fromStart += event.id
                m.last == null && m.next != null && m.next.startTs > firstInstance -> fromStart += event.id
                m.next == null -> forwardOnly += event.id
            }
        }
        return SeriesToCover(fromStart, forwardOnly)
    }

    /**
     * Adds each hit's reason line ([SearchMatchExplainer]). Guests are read, in one batch per
     * source, only for hits that title, location and notes don't explain. A failed guest read
     * leaves those hits without a reason rather than ending the search.
     */
    private suspend fun withReasons(outcome: SearchOutcome, words: List<String>): SearchOutcome {
        fun explain(hit: SearchHit, guests: List<SearchMatchExplainer.Guest>) = hit.displayEvent.let {
            SearchMatchExplainer.explain(words, it.title, it.location, it.description, guests)
        }
        val lists = listOfNotNull(
            outcome.upcoming, outcome.past, outcome.hiddenUpcoming, outcome.hiddenPast, outcome.range, outcome.hiddenRange,
        )
        val withoutGuests = lists.flatten().associateWith { explain(it, emptyList()) }
        val needGuests = withoutGuests.filter { (hit, reason) ->
            reason == null && words.any { !SearchFold.contains(hit.displayEvent.title, it) }
        }.keys
        val roomIds = needGuests.mapNotNull { (it.displayEvent as? DisplayEvent.Room)?.event?.id }.distinct()
        val deviceIds = needGuests.mapNotNull { (it.displayEvent as? DisplayEvent.Device)?.instance?.eventId }.distinct()
        val (roomGuests, deviceGuests) = try {
            eventReader.getAttendeesForEventsOnce(roomIds)
                .mapValues { (_, rows) -> rows.map { SearchMatchExplainer.Guest(it.displayName, it.address) } } to
                if (deviceIds.isEmpty()) {
                    emptyMap()
                } else {
                    calendarProviderRepository.getAttendeesForEvents(deviceIds)
                        .mapValues { (_, rows) -> rows.map { SearchMatchExplainer.Guest(it.name, it.email) } }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reading guests for search reasons failed", e)
            emptyMap<Long, List<SearchMatchExplainer.Guest>>() to emptyMap()
        }
        fun withReason(hits: ImmutableList<SearchHit>): ImmutableList<SearchHit> = hits.map { hit ->
            val reason = if (hit in needGuests) {
                val guests = when (val e = hit.displayEvent) {
                    is DisplayEvent.Room -> roomGuests[e.event.id]
                    is DisplayEvent.Device -> deviceGuests[e.instance.eventId]
                }.orEmpty()
                if (guests.isEmpty()) null else explain(hit, guests)
            } else {
                withoutGuests[hit]
            }
            hit.copy(reason = reason)
        }.toPersistentList()
        return outcome.copy(
            upcoming = withReason(outcome.upcoming),
            past = withReason(outcome.past),
            hiddenUpcoming = withReason(outcome.hiddenUpcoming),
            hiddenPast = withReason(outcome.hiddenPast),
            range = outcome.range?.let(::withReason),
            hiddenRange = outcome.hiddenRange?.let(::withReason),
        )
    }

    private suspend fun searchDevice(query: SearchQuery, today: LocalDate, range: LongRange?, zone: ZoneId): DeviceSearch {
        val emptyRange = if (range != null) emptyList<DeviceCalendarInstance>() else null
        val pastDays = dataStore.syncPastDays.first()
        return try {
            val calendarIds = if (dataStore.getDeviceCalendarsEnabled()) dataStore.getEnabledDeviceCalendarIds() else emptySet()
            if (calendarIds.isEmpty()) return DeviceSearch(emptyList(), emptyRange, 0, pastDays)
            val from = if (pastDays == Int.MAX_VALUE) today.minusYears(DEVICE_SEARCH_MAX_YEARS_BACK) else today.minusDays(pastDays.toLong())
            val to = today.plusYears(SEARCH_YEARS_AHEAD)
            val hideDeclined = !dataStore.getShowDeclinedEvents()
            val text = query.toProviderQuery()
            val start = System.nanoTime()
            val all = calendarProviderRepository.searchInstances(
                text, DayPagerUtils.localDateToDayCode(from), DayPagerUtils.localDateToDayCode(to), calendarIds, hideDeclined
            )
            val inRange = range?.let {
                calendarProviderRepository.searchInstances(
                    text,
                    DateTimeUtils.eventTsToDayCode(it.first, isAllDay = false, localZone = zone),
                    DateTimeUtils.eventTsToDayCode(it.last, isAllDay = false, localZone = zone),
                    calendarIds,
                    hideDeclined,
                )
            }
            DeviceSearch(all, inRange, elapsedMs(start), pastDays)
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar permission revoked during search, falling back to Room-only", e)
            DeviceSearch(emptyList(), emptyRange, 0, pastDays)
        }
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

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

        occurrenceGenerator.ensureCovered(startMs, endMs)
        val roomOccurrences = eventReader
            .getVisibleOccurrencesWithEventsInRangeFlow(startMs, endMs)
            .first()
        val showDeclined = dataStore.getShowDeclinedEvents()
        val roomEvents = applyDeclinedPolicy(roomOccurrences, showDeclined)
        val deviceEvents = queryDeviceEvents(startDayCode, endDayCode)

        return mergeAndGroupByDay(roomEvents, deviceEvents, startDayCode, endDayCode)
    }

    /**
     * Returns the events of the calendars ticked for printing, once, sorted by start: the Room
     * calendars in [roomCalendarIds] whether visible or hidden, and the device calendars in
     * [deviceCalendarIds] that are enabled while device calendars are on, hidden or not. The
     * show-declined setting applies as on screen ([applyDeclinedPolicy]); cancelled occurrences
     * are left out as [EventReader.getOccurrencesWithEventsInRange] leaves them out.
     *
     * Only events with time in the half-open window [startMs]..[endMs] are returned
     * ([overlapsWindow]). The Room range query includes events that only touch the window and
     * the device query widens it by a day on each side, so both are trimmed here.
     */
    suspend fun getDisplayEventsForPrint(
        startMs: Long,
        endMs: Long,
        roomCalendarIds: Set<Long>,
        deviceCalendarIds: Set<Long>,
    ): List<DisplayEvent> {
        val firstDayCode = DayPagerUtils.msToDayCode(startMs)
        val lastDayCode = DayPagerUtils.msToDayCode(endMs - 1)
        val showDeclined = dataStore.getShowDeclinedEvents()

        val roomEvents = if (roomCalendarIds.isEmpty()) {
            emptyList()
        } else {
            occurrenceGenerator.ensureCovered(startMs, endMs)
            val rows = eventReader.getOccurrencesWithEventsInRange(startMs, endMs)
                .filter { it.occurrence.calendarId in roomCalendarIds }
            applyDeclinedPolicy(rows, showDeclined)
        }
        // Ticked device calendars count only while device calendars are on and they are enabled.
        val deviceEvents = deviceInstances(
            firstDayCode,
            lastDayCode,
            calendarIds = {
                if (deviceCalendarIds.isEmpty() || !dataStore.getDeviceCalendarsEnabled()) {
                    emptySet()
                } else {
                    deviceCalendarIds intersect dataStore.getEnabledDeviceCalendarIds()
                }
            },
            hideDeclined = { !showDeclined },
        )

        return (roomEvents + deviceEvents)
            .filter { it.overlapsWindow(startMs, endMs, firstDayCode, lastDayCode) }
            .sortedBy { it.startTs }
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
    ): List<DisplayEvent> = deviceInstances(
        startDayCode,
        endDayCode,
        calendarIds = { getVisibleDeviceCalendarIds() },
        hideDeclined = { !dataStore.getShowDeclinedEvents() },
    )

    /**
     * Returns the instances of [calendarIds] for a day code range, asking [hideDeclined] only
     * when there is a calendar to read; empty when there is none or on a [SecurityException],
     * so the Room events still show.
     */
    private suspend fun deviceInstances(
        startDayCode: Int,
        endDayCode: Int,
        calendarIds: suspend () -> Set<Long>,
        hideDeclined: suspend () -> Boolean,
    ): List<DisplayEvent> = try {
        val ids = calendarIds()
        if (ids.isEmpty()) {
            emptyList()
        } else {
            calendarProviderRepository.getInstancesForDayRange(startDayCode, endDayCode, ids, hideDeclined())
                .map { DisplayEvent.Device(it) }
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "Calendar permission revoked, falling back to Room-only", e)
        emptyList()
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
