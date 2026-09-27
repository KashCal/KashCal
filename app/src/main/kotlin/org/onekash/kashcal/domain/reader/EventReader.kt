package org.onekash.kashcal.domain.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.EventWithNextOccurrence
import org.onekash.kashcal.data.db.dao.EventWithOccurrenceAndColor
import org.onekash.kashcal.data.db.dao.TitleSuggestion
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads Room events, occurrences, calendars, attendees and tags for the domain and UI layers.
 *
 * ViewModels read through this class instead of the DAOs. Queries the UI observes return a
 * Room [Flow], so views update while a sync writes. Its one write is [setCalendarVisibility].
 */
@Singleton
class EventReader @Inject constructor(
    private val database: KashCalDatabase
) {
    private val eventsDao by lazy { database.eventsDao() }
    private val occurrencesDao by lazy { database.occurrencesDao() }
    private val calendarsDao by lazy { database.calendarsDao() }
    private val accountsDao by lazy { database.accountsDao() }
    private val attendeesDao by lazy { database.attendeesDao() }

    private val categoryDao by lazy { database.categoryDao() }

    companion object {
        /** Cap on tag suggestions so the autocomplete popup stays scannable. */
        const val MAX_CATEGORY_SUGGESTIONS = 20
    }

    // ========== Event Lookups ==========

    /**
     * Suggests Room event titles matching [prefix] for the form autocomplete; filtering is
     * documented on [org.onekash.kashcal.data.db.dao.EventsDao.suggestTitlesByPrefix].
     */
    suspend fun suggestTitles(
        prefix: String,
        sinceMs: Long,
        untilMs: Long,
        minFreq: Int,
        limit: Int
    ): List<TitleSuggestion> =
        eventsDao.suggestTitlesByPrefix(prefix, sinceMs, untilMs, minFreq, limit)

    /**
     * Emits up to [MAX_CATEGORY_SUGGESTIONS] tag names for the tag chip row and inline `#`
     * autocomplete, most recently used first, ties by name.
     *
     * The tag table is the source of truth for which tags exist, so renames and deletes show at
     * once. `distinctUntilChanged` keeps a bulk sync pull from re-emitting an unchanged list.
     */
    fun getRecentCategories(): Flow<List<String>> =
        categoryDao.observeSuggestions(MAX_CATEGORY_SUGGESTIONS)
            .distinctUntilChanged()

    /**
     * Emits each tag's custom color by name, null for none, so a recolor repaints chips without
     * touching the event and occurrence streams.
     */
    fun observeTagColors(): Flow<Map<String, Int?>> =
        categoryDao.observeAll()
            .map { rows -> rows.associate { it.name to it.color } }
            .distinctUntilChanged()

    suspend fun getEventById(eventId: Long): Event? {
        return eventsDao.getById(eventId)
    }

    /**
     * Observes one event, re-emitting when its row changes, so the UI shows the persisted event
     * and not the one captured at tap time. Emits null when the event doesn't exist.
     */
    fun getEventByIdFlow(eventId: Long): Flow<Event?> =
        eventsDao.getByIdFlow(eventId).distinctUntilChanged()

    /** Returns every event with [uid]: a master and its exceptions share it. */
    suspend fun getEventsByUid(uid: String): List<Event> {
        return eventsDao.getByUid(uid)
    }

    suspend fun getEventByCaldavUrl(url: String): Event? {
        return eventsDao.getByCaldavUrl(url)
    }

    /** Loads [ids] in one query, keyed by event ID. */
    suspend fun getEventsByIds(ids: List<Long>): Map<Long, Event> {
        if (ids.isEmpty()) return emptyMap()
        return eventsDao.getByIds(ids).associateBy { it.id }
    }

    // ========== Attendee Lookups ==========

    /**
     * Observes one event's attendee rows; empty when none are stored. [AttendeeBackfill] fills
     * the rows from [Event.rawIcal] for an event the pull skipped on an unchanged etag.
     */
    fun getAttendeesForEvent(eventId: Long): Flow<List<Attendee>> =
        attendeesDao.getForEvent(eventId).distinctUntilChanged()

    /**
     * Observes the attendees of [eventIds] in one Flow, keyed by event ID, so a list of cards
     * needs no Flow per event. An empty [eventIds] emits an empty map without a query.
     */
    fun getAttendeesForEvents(eventIds: List<Long>): Flow<Map<Long, List<Attendee>>> {
        if (eventIds.isEmpty()) return flowOf(emptyMap())
        return attendeesDao.getForEvents(eventIds)
            .map { rows -> rows.groupBy { it.eventId } }
            .distinctUntilChanged()
    }

    /**
     * Observes the pending CalDAV invitations for the inbox; the filter is documented on
     * [buildPendingInvitations].
     *
     * The NEEDS-ACTION attendee Flow re-emits when a row's partstat changes, so a card leaves the
     * inbox as soon as the user's RSVP is stored. [now] is fixed at subscription: a card whose
     * last occurrence ends while the inbox is open stays until the next subscription.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getPendingInvitations(now: Long = System.currentTimeMillis()): Flow<List<PendingInvitation>> {
        return combine(
            eventsDao.getMasterEventsWithFutureOccurrenceFlow(now),
            accountsDao.getAll(),
            calendarsDao.getAll()
        ) { eventsWithNext, accounts, calendars ->
            Triple(eventsWithNext, accounts, calendars)
        }.flatMapLatest { (eventsWithNext, accounts, calendars) ->
            val eventIds = eventsWithNext.map { it.event.id }
            val attendeesFlow: Flow<List<Attendee>> = if (eventIds.isEmpty()) {
                flowOf(emptyList())
            } else {
                attendeesDao.getNeedsActionAttendeesForEventsFlow(eventIds)
            }
            attendeesFlow.map { needsActionRows ->
                buildPendingInvitations(
                    eventsWithNext = eventsWithNext,
                    needsActionAttendees = needsActionRows,
                    accountsById = accounts.associateBy { it.id },
                    calendarsById = calendars.associateBy { it.id }
                )
            }
        }.distinctUntilChanged()
    }

    suspend fun getExceptionsForMaster(masterEventId: Long): List<Event> {
        return eventsDao.getExceptionsForMaster(masterEventId)
    }

    /**
     * Returns the exceptions of [masterIds] keyed by master ID. Queries in chunks of 500 to stay
     * within SQLite's limit of 999 bound variables.
     */
    suspend fun getExceptionsForMasters(masterIds: List<Long>): Map<Long, List<Event>> {
        if (masterIds.isEmpty()) return emptyMap()
        return masterIds.chunked(500)
            .flatMap { chunk -> eventsDao.getExceptionsForMasters(chunk) }
            .groupBy { it.originalEventId!! }
    }

    suspend fun getMasterForException(exceptionEvent: Event): Event? {
        val masterId = exceptionEvent.originalEventId ?: return null
        return eventsDao.getById(masterId)
    }

    // ========== Export Queries ==========

    /**
     * Returns [calendarId]'s masters and one-off events by start time, without exceptions or
     * PENDING_DELETE rows. ICS export loads the exceptions separately
     * ([getExceptionsForMasters]) to bundle each under its master's UID (RFC 5545).
     */
    suspend fun getAllMasterEventsForCalendar(calendarId: Long): List<Event> {
        return eventsDao.getAllMasterEventsForCalendar(calendarId)
    }

    /**
     * Returns [eventId] and its exceptions, for one VCALENDAR whose VEVENTs share the UID.
     * The list is empty for a non-recurring event; returns null if the event doesn't exist.
     */
    suspend fun getEventWithExceptions(eventId: Long): Pair<Event, List<Event>>? {
        val event = eventsDao.getById(eventId) ?: return null
        val exceptions = if (event.isRecurring) {
            eventsDao.getExceptionsForMaster(eventId)
        } else {
            emptyList()
        }
        return Pair(event, exceptions)
    }

    // ========== Calendar Queries ==========

    fun getAllCalendars(): Flow<List<Calendar>> {
        return calendarsDao.getAll()
    }

    fun getVisibleCalendars(): Flow<List<Calendar>> {
        return calendarsDao.getAll().map { calendars ->
            calendars.filter { it.isVisible }
        }
    }

    fun getCalendarsForAccount(accountId: Long): Flow<List<Calendar>> {
        return calendarsDao.getByAccountId(accountId)
    }

    suspend fun getCalendarById(calendarId: Long): Calendar? {
        return calendarsDao.getById(calendarId)
    }

    /** Emits the calendars of accounts whose provider is [provider], e.g. "icloud", "local". */
    fun getCalendarsByProvider(provider: String): Flow<List<Calendar>> {
        return calendarsDao.getCalendarsByProvider(provider)
    }

    fun getICloudCalendarCount(): Flow<Int> {
        return calendarsDao.getCalendarCountByProvider("icloud")
    }

    suspend fun getCalendarCountForAccount(accountId: Long): Int {
        return calendarsDao.getByAccountIdOnce(accountId).size
    }

    /** Stores [visible] in the calendar's `is_visible`, the source of truth for visibility. */
    suspend fun setCalendarVisibility(calendarId: Long, visible: Boolean) {
        calendarsDao.setVisible(calendarId, visible)
    }

    // ========== Occurrence Queries (for Calendar Views) ==========

    /** Observes the non-cancelled occurrences overlapping [startTs]..[endTs]. */
    fun getOccurrencesInRange(startTs: Long, endTs: Long): Flow<List<Occurrence>> {
        return occurrencesDao.getInRange(startTs, endTs)
    }

    suspend fun getOccurrencesInRangeOnce(startTs: Long, endTs: Long): List<Occurrence> {
        return occurrencesDao.getInRangeOnce(startTs, endTs)
    }

    /**
     * Returns the IDs of recurring masters whose latest occurrence starts before [targetTs], for
     * on-demand expansion when the user navigates far into the future.
     */
    suspend fun getRecurringEventsNeedingExtension(targetTs: Long): List<Long> {
        return occurrencesDao.getRecurringEventsNeedingExtension(targetTs)
    }

    /**
     * Returns the IDs of recurring masters whose earliest occurrence starts after [targetTs] and
     * after their DTSTART, for on-demand expansion when the user navigates far into the past.
     */
    suspend fun getRecurringEventsNeedingPastExtension(targetTs: Long): List<Long> {
        return occurrencesDao.getRecurringEventsNeedingPastExtension(targetTs)
    }

    /** Returns the IDs of recurring masters, not PENDING_DELETE, with no occurrence rows. */
    suspend fun getRecurringEventsWithNoOccurrences(): List<Long> {
        return occurrencesDao.getRecurringEventsWithNoOccurrences()
    }

    fun getOccurrencesForCalendar(
        calendarId: Long,
        startTs: Long,
        endTs: Long
    ): Flow<List<Occurrence>> {
        return occurrencesDao.getForCalendarInRange(calendarId, startTs, endTs)
    }

    /** Observes the non-cancelled occurrences spanning [day] (YYYYMMDD). */
    fun getOccurrencesForDay(day: Int): Flow<List<Occurrence>> {
        return occurrencesDao.getForDay(day)
    }

    suspend fun getOccurrencesForDayOnce(day: Int): List<Occurrence> {
        return occurrencesDao.getForDayOnce(day)
    }

    /** Observes [getOccurrencesInRange] limited to visible calendars. */
    fun getVisibleOccurrencesInRange(startTs: Long, endTs: Long): Flow<List<Occurrence>> {
        return combine(
            getVisibleCalendars(),
            getOccurrencesInRange(startTs, endTs)
        ) { calendars, occurrences ->
            val visibleCalendarIds = calendars.map { it.id }.toSet()
            occurrences.filter { it.calendarId in visibleCalendarIds }
        }.distinctUntilChanged()
    }

    /** Observes [getOccurrencesForDay] limited to visible calendars. */
    fun getVisibleOccurrencesForDay(day: Int): Flow<List<Occurrence>> {
        return combine(
            getVisibleCalendars(),
            getOccurrencesForDay(day)
        ) { calendars, occurrences ->
            val visibleCalendarIds = calendars.map { it.id }.toSet()
            occurrences.filter { it.calendarId in visibleCalendarIds }
        }.distinctUntilChanged()
    }

    /**
     * Observes the occurrences on [day] (YYYYMMDD, e.g. 20241225) in visible calendars, each
     * with its event and calendar.
     *
     * The query joins events, so Room re-emits when an event's title or location changes, not
     * only its occurrences. `debounce(50)` batches the burst of updates during a sync.
     */
    @Suppress("OPT_IN_USAGE")
    fun getVisibleOccurrencesWithEventsForDay(day: Int): Flow<List<OccurrenceWithEvent>> {
        return combine(
            getVisibleCalendars(),
            occurrencesDao.getOccurrencesWithEventsForDay(day)
        ) { calendars, data ->
            val visibleCalendarIds = calendars.map { it.id }.toSet()
            val calendarsMap = calendars.associateBy { it.id }

            data.filter { it.calendarId in visibleCalendarIds }
                .map { item ->
                    OccurrenceWithEvent(
                        occurrence = item.toOccurrence(),
                        event = item.event,
                        calendar = calendarsMap[item.calendarId]
                    )
                }
        }.debounce(50)
    }

    // ========== Event with Occurrence Data ==========

    /**
     * Pairs an occurrence with an event and its calendar. The range and day readers load the
     * exception for a changed occurrence; [searchEventsWithOccurrences] pairs each occurrence
     * with the matched event.
     */
    data class OccurrenceWithEvent(
        val occurrence: Occurrence,
        val event: Event,
        val calendar: Calendar?
    )

    /**
     * Returns the non-cancelled occurrences overlapping [startTs]..[endTs] with their events and
     * calendars, in three queries.
     */
    suspend fun getOccurrencesWithEventsInRange(
        startTs: Long,
        endTs: Long
    ): List<OccurrenceWithEvent> {
        val occurrences = occurrencesDao.getInRangeOnce(startTs, endTs)
        if (occurrences.isEmpty()) return emptyList()

        val eventIds = occurrences.map { it.exceptionEventId ?: it.eventId }.distinct()
        val eventsMap = eventsDao.getByIds(eventIds).associateBy { it.id }

        val calendarIds = occurrences.map { it.calendarId }.distinct()
        val calendarsMap = calendarsDao.getByIds(calendarIds).associateBy { it.id }

        return occurrences.mapNotNull { occ ->
            val eventId = occ.exceptionEventId ?: occ.eventId
            val event = eventsMap[eventId] ?: return@mapNotNull null
            val calendar = calendarsMap[occ.calendarId]
            OccurrenceWithEvent(occ, event, calendar)
        }
    }

    /**
     * Observes the non-cancelled occurrences overlapping [startTs]..[endTs] with their events
     * and calendars, so events appear while a sync writes them.
     *
     * The query joins events, so Room re-emits when an event changes, not only its occurrences.
     * `debounce(50)` keeps a bulk sync of 500 events from emitting 500 times.
     */
    @Suppress("OPT_IN_USAGE")
    fun getOccurrencesWithEventsInRangeFlow(
        startTs: Long,
        endTs: Long
    ): Flow<List<OccurrenceWithEvent>> {
        return occurrencesDao.getOccurrencesWithEventsInRange(startTs, endTs)
            .debounce(50)  // Batch rapid updates during sync
            .map { data ->
                if (data.isEmpty()) return@map emptyList()

                // Calendars aren't in the join.
                val calendarIds = data.map { it.calendarId }.distinct()
                val calendarsMap = calendarsDao.getByIds(calendarIds).associateBy { it.id }

                data.map { item ->
                    OccurrenceWithEvent(
                        occurrence = item.toOccurrence(),
                        event = item.event,
                        calendar = calendarsMap[item.calendarId]
                    )
                }
            }
    }

    /**
     * Observes [getOccurrencesWithEventsInRangeFlow] (range inclusive) limited to visible
     * calendars.
     *
     * Combining with [getVisibleCalendars] re-emits when a calendar's visibility is toggled, so
     * the week view and agenda follow it. `debounce(50)` batches updates during a bulk sync.
     */
    @Suppress("OPT_IN_USAGE")
    fun getVisibleOccurrencesWithEventsInRangeFlow(
        startTs: Long,
        endTs: Long
    ): Flow<List<OccurrenceWithEvent>> {
        return combine(
            getVisibleCalendars(),
            occurrencesDao.getOccurrencesWithEventsInRange(startTs, endTs)
        ) { calendars, data ->
            if (data.isEmpty()) return@combine emptyList()

            val visibleCalendarIds = calendars.map { it.id }.toSet()
            val calendarsMap = calendars.associateBy { it.id }

            data.filter { it.calendarId in visibleCalendarIds }
                .map { item ->
                    OccurrenceWithEvent(
                        occurrence = item.toOccurrence(),
                        event = item.event,
                        calendar = calendarsMap[item.calendarId]
                    )
                }
        }.debounce(50)
    }

    /**
     * Returns [eventId]'s occurrence at [occurrenceTimeMs] with the event it shows (the
     * exception for a changed occurrence), or null if either is missing.
     */
    suspend fun getOccurrenceWithEvent(
        eventId: Long,
        occurrenceTimeMs: Long
    ): OccurrenceWithEvent? {
        val occurrence = occurrencesDao.getOccurrenceAtTime(eventId, occurrenceTimeMs)
            ?: return null
        val displayEventId = occurrence.exceptionEventId ?: occurrence.eventId
        val event = eventsDao.getById(displayEventId) ?: return null
        val calendar = calendarsDao.getById(occurrence.calendarId)
        return OccurrenceWithEvent(occurrence, event, calendar)
    }

    // ========== Day/Week/Month View Helpers ==========

    /**
     * Returns the non-cancelled occurrences spanning [dayCode] with their events and calendars,
     * by start time, in three queries.
     */
    suspend fun getEventsForDay(dayCode: Int): List<OccurrenceWithEvent> {
        val occurrences = occurrencesDao.getForDayOnce(dayCode)
        if (occurrences.isEmpty()) return emptyList()

        val eventIds = occurrences.map { it.exceptionEventId ?: it.eventId }.distinct()
        val eventsMap = eventsDao.getByIds(eventIds).associateBy { it.id }

        val calendarIds = occurrences.map { it.calendarId }.distinct()
        val calendarsMap = calendarsDao.getByIds(calendarIds).associateBy { it.id }

        return occurrences.mapNotNull { occ ->
            val eventId = occ.exceptionEventId ?: occ.eventId
            val event = eventsMap[eventId] ?: return@mapNotNull null
            val calendar = calendarsMap[occ.calendarId]
            OccurrenceWithEvent(occ, event, calendar)
        }.sortedBy { it.occurrence.startTs }
    }

    /** Returns true if a non-cancelled occurrence spans [dayCode]. */
    suspend fun hasEventsOnDay(dayCode: Int): Boolean {
        val occurrences = occurrencesDao.getForDayOnce(dayCode)
        return occurrences.isNotEmpty()
    }

    /**
     * Returns the start day (YYYYMMDD) of each non-cancelled occurrence overlapping [month] of
     * [year] (1-based), in the device's default zone.
     */
    suspend fun getDaysWithEventsInMonth(year: Int, month: Int): Set<Int> {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(year, month - 1, 1, 0, 0, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val monthStart = calendar.timeInMillis

        calendar.add(java.util.Calendar.MONTH, 1)
        val monthEnd = calendar.timeInMillis

        val occurrences = occurrencesDao.getInRangeOnce(monthStart, monthEnd)
        return occurrences.map { it.startDay }.toSet()
    }

    // ========== Search ==========

    /**
     * Searches events with FTS4, returning up to 1000 by start time, without PENDING_DELETE
     * rows or synthetic masters.
     *
     * Every search method here turns each whitespace-separated word of [query] into a prefix
     * term (`meet` matches "meeting"), and an event must match every word.
     */
    suspend fun searchEvents(query: String): List<Event> {
        if (query.isBlank()) return emptyList()
        val ftsQuery = query.trim().split("\\s+".toRegex())
            .joinToString(" ") { "$it*" }
        return eventsDao.search(ftsQuery)
    }

    /**
     * Searches with [searchEvents]' terms, keeping events with a non-cancelled occurrence that
     * hasn't ended. The occurrences table decides this, since `Event.endTs` is only the first
     * occurrence's end.
     */
    suspend fun searchEventsExcludingPast(query: String): List<Event> {
        if (query.isBlank()) return emptyList()
        val ftsQuery = query.trim().split("\\s+".toRegex())
            .joinToString(" ") { "$it*" }
        return eventsDao.searchFuture(ftsQuery, System.currentTimeMillis())
    }

    /**
     * Searches with [searchEvents]' terms, keeping events with a non-cancelled occurrence
     * overlapping [rangeStart]..[rangeEnd] (inclusive).
     */
    suspend fun searchEventsInRange(query: String, rangeStart: Long, rangeEnd: Long): List<Event> {
        if (query.isBlank()) return emptyList()
        val ftsQuery = query.trim().split("\\s+".toRegex())
            .joinToString(" ") { "$it*" }
        return eventsDao.searchInRange(ftsQuery, rangeStart, rangeEnd)
    }

    // ========== Search with Next Occurrence ==========

    /**
     * Searches masters and one-off events, past ones included, for the "All" filter.
     * `nextOccurrenceTs` is the event's own start; results are ordered by distance from now.
     */
    suspend fun searchEventsWithNextOccurrence(query: String): List<EventWithNextOccurrence> {
        if (query.isBlank()) return emptyList()
        val ftsQuery = query.trim().split("\\s+".toRegex())
            .joinToString(" ") { "$it*" }
        return eventsDao.searchWithOccurrence(ftsQuery, System.currentTimeMillis())
    }

    /**
     * Searches events with a non-cancelled occurrence not yet ended, for the default search.
     * `nextOccurrenceTs` is the earliest such occurrence's start, and results are ordered by it.
     */
    suspend fun searchEventsExcludingPastWithNextOccurrence(query: String): List<EventWithNextOccurrence> {
        if (query.isBlank()) return emptyList()
        val ftsQuery = query.trim().split("\\s+".toRegex())
            .joinToString(" ") { "$it*" }
        return eventsDao.searchFutureWithOccurrence(ftsQuery, System.currentTimeMillis())
    }

    /**
     * Searches events with a non-cancelled occurrence overlapping [rangeStart]..[rangeEnd]
     * (inclusive), for the Week, Month and custom-date filters. `nextOccurrenceTs` is the
     * earliest such occurrence's start.
     */
    suspend fun searchEventsInRangeWithNextOccurrence(
        query: String,
        rangeStart: Long,
        rangeEnd: Long
    ): List<EventWithNextOccurrence> {
        if (query.isBlank()) return emptyList()
        val ftsQuery = query.trim().split("\\s+".toRegex())
            .joinToString(" ") { "$it*" }
        return eventsDao.searchInRangeWithOccurrence(ftsQuery, rangeStart, rangeEnd)
    }

    /**
     * Searches like [searchEvents] and returns up to 5 non-cancelled occurrences per event, by
     * start time, in three queries. With [futureOnly], only occurrences starting at or after now.
     */
    suspend fun searchEventsWithOccurrences(
        query: String,
        futureOnly: Boolean = true
    ): List<OccurrenceWithEvent> {
        val events = searchEvents(query)
        if (events.isEmpty()) return emptyList()

        val now = if (futureOnly) System.currentTimeMillis() else 0L

        val eventIds = events.map { it.id }
        val allOccurrences = occurrencesDao.getForEvents(eventIds)
            .filter { it.startTs >= now && !it.isCancelled }

        val occurrencesByEvent = allOccurrences.groupBy { it.eventId }
        val limitedOccurrences = occurrencesByEvent.flatMap { (_, occs) -> occs.take(5) }

        if (limitedOccurrences.isEmpty()) return emptyList()

        val eventsMap = events.associateBy { it.id }

        val calendarIds = limitedOccurrences.map { it.calendarId }.distinct()
        val calendarsMap = calendarsDao.getByIds(calendarIds).associateBy { it.id }

        return limitedOccurrences.mapNotNull { occ ->
            val event = eventsMap[occ.eventId] ?: return@mapNotNull null
            val calendar = calendarsMap[occ.calendarId]
            OccurrenceWithEvent(occ, event, calendar)
        }.sortedBy { it.occurrence.startTs }
    }

    // ========== Sync-Related Queries ==========

    /** Returns every event whose sync status isn't SYNCED. */
    suspend fun getPendingSyncEvents(): List<Event> {
        return eventsDao.getPendingSyncEvents()
    }

    /** Returns [calendarId]'s events whose sync status isn't SYNCED. */
    suspend fun getPendingSyncEventsForCalendar(calendarId: Long): List<Event> {
        return eventsDao.getPendingForCalendar(calendarId)
    }

    /** Returns events with a stored `last_sync_error`. */
    suspend fun getEventsWithSyncErrors(): List<Event> {
        return eventsDao.getEventsWithSyncErrors()
    }

    fun getPendingOperationCount(): Flow<Int> {
        return database.pendingOperationsDao().getPendingCount()
    }

    // ========== Statistics ==========

    suspend fun getTotalEventCount(): Int {
        return eventsDao.getTotalCount()
    }

    suspend fun getEventCountForCalendar(calendarId: Long): Int {
        return eventsDao.getCountByCalendar(calendarId)
    }

    suspend fun getTotalOccurrenceCount(): Int {
        return occurrencesDao.getTotalCount()
    }

    suspend fun hasOccurrencesInRange(startTs: Long, endTs: Long): Boolean {
        return occurrencesDao.hasOccurrencesInRange(startTs, endTs)
    }

    // ========== Reminder Scheduling ==========

    /**
     * Returns one row per non-cancelled occurrence starting in [fromTime]..[toTime] (epoch ms,
     * inclusive) in a visible calendar whose event has reminders. An exception without its own
     * reminders inherits the master's ([EventWithOccurrenceAndColor]).
     */
    suspend fun getEventsWithRemindersInRange(
        fromTime: Long,
        toTime: Long
    ): List<EventWithOccurrenceAndColor> {
        return eventsDao.getEventsWithRemindersInRange(fromTime, toTime)
    }

    /**
     * Returns [eventId]'s non-cancelled occurrences starting between now and [windowDays] days
     * ahead, for scheduling reminder alarms. Callers pass the reminder scheduler's occurrence
     * look-ahead, and the scheduler arms only the reminders due soon.
     */
    suspend fun getOccurrencesForEventInScheduleWindow(
        eventId: Long,
        windowDays: Int
    ): List<Occurrence> {
        val now = System.currentTimeMillis()
        val windowEnd = now + (windowDays.toLong() * 24 * 60 * 60 * 1000)

        return occurrencesDao.getForEvent(eventId)
            .filter { !it.isCancelled && it.startTs >= now && it.startTs <= windowEnd }
    }

    /**
     * Returns the occurrence [exceptionEventId] replaces, or null if none is linked; used to
     * schedule an exception's reminders.
     */
    suspend fun getOccurrenceByExceptionEventId(exceptionEventId: Long): Occurrence? {
        return occurrencesDao.getByExceptionEventId(exceptionEventId)
    }

    // ========== Calendar Lookups for Reminder Cleanup ==========

    suspend fun getCalendarsByAccountIdOnce(accountId: Long): List<Calendar> {
        return calendarsDao.getByAccountIdOnce(accountId)
    }

    /** Returns the same rows as [getAllMasterEventsForCalendar]. */
    suspend fun getEventsForCalendar(calendarId: Long): List<Event> {
        return eventsDao.getAllMasterEventsForCalendar(calendarId)
    }
}
