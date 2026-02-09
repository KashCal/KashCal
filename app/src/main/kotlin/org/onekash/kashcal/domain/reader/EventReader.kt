package org.onekash.kashcal.domain.reader

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.OccurrenceWithEventData
import org.onekash.kashcal.data.db.dao.EventWithNextOccurrence
import org.onekash.kashcal.data.db.dao.EventWithOccurrenceAndColor
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.model.AccountProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles all event read/query operations.
 *
 * Provides:
 * - Event lookups by ID, UID, calendar
 * - Occurrence queries by date range (for calendar views)
 * - Day/week/month view data
 * - Search functionality
 * - Sync-related queries
 *
 * Uses Flow for reactive UI updates.
 */
@Singleton
class EventReader @Inject constructor(
    private val database: KashCalDatabase
) {
    private val eventsDao by lazy { database.eventsDao() }
    private val occurrencesDao by lazy { database.occurrencesDao() }
    private val calendarsDao by lazy { database.calendarsDao() }
    private val accountsDao by lazy { database.accountsDao() }

    // ========== Event Lookups ==========

    /**
     * Get event by ID.
     */
    suspend fun getEventById(eventId: Long): Event? {
        return eventsDao.getById(eventId)
    }

    /**
     * Get event by UID.
     * Returns list because UID may be shared by master and exceptions.
     */
    suspend fun getEventsByUid(uid: String): List<Event> {
        return eventsDao.getByUid(uid)
    }

    /**
     * Get event by CalDAV URL.
     */
    suspend fun getEventByCaldavUrl(url: String): Event? {
        return eventsDao.getByCaldavUrl(url)
    }

    /**
     * Get events by IDs in a single batch query.
     * Returns map for O(1) lookup by event ID.
     *
     * Used to avoid N+1 queries when loading events for multiple occurrences.
     */
    suspend fun getEventsByIds(ids: List<Long>): Map<Long, Event> {
        if (ids.isEmpty()) return emptyMap()
        return eventsDao.getByIds(ids).associateBy { it.id }
    }

    /**
     * Get all exception events for a master recurring event.
     */
    suspend fun getExceptionsForMaster(masterEventId: Long): List<Event> {
        return eventsDao.getExceptionsForMaster(masterEventId)
    }

    /**
     * Get master event for an exception.
     */
    suspend fun getMasterForException(exceptionEvent: Event): Event? {
        val masterId = exceptionEvent.originalEventId ?: return null
        return eventsDao.getById(masterId)
    }

    // ========== Export Queries ==========

    /**
     * Get all master events for a calendar (for ICS export).
     *
     * Returns master recurring events and standalone events (not exceptions).
     * Excludes events pending deletion. Exception events are retrieved
     * separately via getExceptionsForMaster() for RFC 5545 compliant bundling.
     *
     * @param calendarId Calendar to export
     * @return List of master events, ordered by start time
     */
    suspend fun getAllMasterEventsForCalendar(calendarId: Long): List<Event> {
        return eventsDao.getAllMasterEventsForCalendar(calendarId)
    }

    /**
     * Get event with its exceptions (for single event ICS export).
     *
     * Returns the event and all its exception events for bundling
     * into a single VCALENDAR with multiple VEVENTs sharing the same UID.
     *
     * @param eventId The master or standalone event ID
     * @return Pair of event and its exceptions (empty list if not recurring), or null if not found
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

    /**
     * Get all calendars as Flow for reactive updates.
     */
    fun getAllCalendars(): Flow<List<Calendar>> {
        return calendarsDao.getAll()
    }

    /**
     * Get all visible calendars.
     */
    fun getVisibleCalendars(): Flow<List<Calendar>> {
        return calendarsDao.getAll().map { calendars ->
            calendars.filter { it.isVisible }
        }
    }

    /**
     * Get calendars for an account.
     */
    fun getCalendarsForAccount(accountId: Long): Flow<List<Calendar>> {
        return calendarsDao.getByAccountId(accountId)
    }

    /**
     * Get calendar by ID.
     */
    suspend fun getCalendarById(calendarId: Long): Calendar? {
        return calendarsDao.getById(calendarId)
    }

    /**
     * Get default calendar (for new events).
     */
    suspend fun getDefaultCalendar(): Calendar? {
        return calendarsDao.getAnyDefaultCalendar()
    }

    /**
     * Get calendars for a specific provider (e.g., "icloud", "local").
     * Uses JOIN with accounts table to filter by provider type.
     */
    fun getCalendarsByProvider(provider: String): Flow<List<Calendar>> {
        return calendarsDao.getCalendarsByProvider(provider)
    }

    /**
     * Get iCloud calendar count.
     * Returns Flow that updates when calendars change.
     */
    fun getICloudCalendarCount(): Flow<Int> {
        return calendarsDao.getCalendarCountByProvider("icloud")
    }

    /**
     * Get calendar count for an account.
     */
    suspend fun getCalendarCountForAccount(accountId: Long): Int {
        return calendarsDao.getByAccountIdOnce(accountId).size
    }

    /**
     * Set calendar visibility.
     * This is the source of truth for which calendars are visible.
     */
    suspend fun setCalendarVisibility(calendarId: Long, visible: Boolean) {
        calendarsDao.setVisible(calendarId, visible)
    }

    // ========== Occurrence Queries (for Calendar Views) ==========

    /**
     * Get occurrences in date range (reactive).
     * This is the primary query for calendar views.
     * Excludes cancelled occurrences.
     */
    fun getOccurrencesInRange(startTs: Long, endTs: Long): Flow<List<Occurrence>> {
        return occurrencesDao.getInRange(startTs, endTs)
    }

    /**
     * Get occurrences in range (one-shot).
     */
    suspend fun getOccurrencesInRangeOnce(startTs: Long, endTs: Long): List<Occurrence> {
        return occurrencesDao.getInRangeOnce(startTs, endTs)
    }

    /**
     * Find recurring events that need occurrence extension to reach target date.
     * Used for on-demand expansion when user navigates far into the future.
     *
     * @param targetTs Target timestamp - events with max occurrence before this need extension
     * @return List of event IDs that need occurrence extension
     */
    suspend fun getRecurringEventsNeedingExtension(targetTs: Long): List<Long> {
        return occurrencesDao.getRecurringEventsNeedingExtension(targetTs)
    }

    /**
     * Get occurrences for specific calendar in range.
     */
    fun getOccurrencesForCalendar(
        calendarId: Long,
        startTs: Long,
        endTs: Long
    ): Flow<List<Occurrence>> {
        return occurrencesDao.getForCalendarInRange(calendarId, startTs, endTs)
    }

    /**
     * Get occurrences for specific day (YYYYMMDD format).
     */
    fun getOccurrencesForDay(day: Int): Flow<List<Occurrence>> {
        return occurrencesDao.getForDay(day)
    }

    /**
     * Get occurrences for day (one-shot).
     */
    suspend fun getOccurrencesForDayOnce(day: Int): List<Occurrence> {
        return occurrencesDao.getForDayOnce(day)
    }

    /**
     * Get occurrences for multiple visible calendars in range.
     * Filters by calendar visibility.
     */
    fun getVisibleOccurrencesInRange(startTs: Long, endTs: Long): Flow<List<Occurrence>> {
        return combine(
            getVisibleCalendars(),
            getOccurrencesInRange(startTs, endTs)
        ) { calendars, occurrences ->
            val visibleCalendarIds = calendars.map { it.id }.toSet()
            occurrences.filter { it.calendarId in visibleCalendarIds }
        }.distinctUntilChanged()
    }

    /**
     * Get visible occurrences for specific day (YYYYMMDD format).
     * Filters by calendar visibility.
     */
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
     * Get visible occurrences WITH event data for specific day (YYYYMMDD format).
     *
     * Similar to getVisibleOccurrencesForDay but includes event details directly.
     * Uses JOIN query so Room tracks BOTH tables - emits when occurrences OR events change.
     *
     * This fixes the reactivity issue where event metadata changes (location, title, etc.)
     * didn't trigger UI updates in the day events list.
     *
     * Uses debounce(50) to batch rapid updates during sync.
     *
     * @param day Day code in YYYYMMDD format (e.g., 20241225 for Dec 25, 2024)
     * @return Flow of OccurrenceWithEvent list, filtered by visible calendars
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
     * Data class combining occurrence with its event details.
     * Used for displaying event info in calendar views.
     */
    data class OccurrenceWithEvent(
        val occurrence: Occurrence,
        val event: Event,
        val calendar: Calendar?
    )

    /**
     * Get occurrences with full event details for date range.
     * Uses batch loading to avoid N+1 queries (3 queries instead of 2N+1).
     */
    suspend fun getOccurrencesWithEventsInRange(
        startTs: Long,
        endTs: Long
    ): List<OccurrenceWithEvent> {
        val occurrences = occurrencesDao.getInRangeOnce(startTs, endTs)
        if (occurrences.isEmpty()) return emptyList()

        // Batch load events (1 query instead of N)
        val eventIds = occurrences.map { it.exceptionEventId ?: it.eventId }.distinct()
        val eventsMap = eventsDao.getByIds(eventIds).associateBy { it.id }

        // Batch load calendars (1 query instead of N)
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
     * Get occurrences with full event details for date range (reactive Flow).
     *
     * Returns a Flow that automatically emits updates when occurrences OR events change.
     * Used for progressive UI updates during sync - events appear as they're synced.
     *
     * IMPORTANT: Uses JOIN query so Room tracks BOTH tables. This fixes the reactivity
     * issue where event metadata changes (location, title, etc.) didn't trigger UI updates.
     *
     * Uses debounce(50) to batch rapid updates during bulk sync (prevents 500 emissions
     * for 500 event updates).
     *
     * Android best practice: Use Flow for observable data sources.
     * See: https://developer.android.com/topic/architecture/data-layer/offline-first
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

                // Batch load calendars (still needed - not in JOIN)
                val calendarIds = data.map { it.calendarId }.distinct()
                val calendarsMap = calendarsDao.getByIds(calendarIds).associateBy { it.id }

                data.map { item ->
                    OccurrenceWithEvent(
                        occurrence = item.toOccurrence(),
                        event = item.event,  // Already available via @Embedded
                        calendar = calendarsMap[item.calendarId]
                    )
                }
            }
    }

    /**
     * Get VISIBLE occurrences with event data for date range (reactive Flow).
     *
     * Uses combine() with getVisibleCalendars() so UI updates automatically
     * when EITHER visibility changes OR events change. This fixes the bug where
     * toggling calendar visibility didn't update week view, agenda, etc.
     *
     * Uses debounce(50) to batch rapid updates during bulk sync.
     *
     * @param startTs Start timestamp (inclusive)
     * @param endTs End timestamp (inclusive)
     * @return Flow of visible OccurrenceWithEvent list, auto-filtered by calendar visibility
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
     * Get single occurrence with event details.
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
     * Get events for a specific day.
     * Returns OccurrenceWithEvent list sorted by start time.
     * Uses batch loading to avoid N+1 queries (3 queries instead of 2N+1).
     */
    suspend fun getEventsForDay(dayCode: Int): List<OccurrenceWithEvent> {
        val occurrences = occurrencesDao.getForDayOnce(dayCode)
        if (occurrences.isEmpty()) return emptyList()

        // Batch load events (1 query instead of N)
        val eventIds = occurrences.map { it.exceptionEventId ?: it.eventId }.distinct()
        val eventsMap = eventsDao.getByIds(eventIds).associateBy { it.id }

        // Batch load calendars (1 query instead of N)
        val calendarIds = occurrences.map { it.calendarId }.distinct()
        val calendarsMap = calendarsDao.getByIds(calendarIds).associateBy { it.id }

        return occurrences.mapNotNull { occ ->
            val eventId = occ.exceptionEventId ?: occ.eventId
            val event = eventsMap[eventId] ?: return@mapNotNull null
            val calendar = calendarsMap[occ.calendarId]
            OccurrenceWithEvent(occ, event, calendar)
        }.sortedBy { it.occurrence.startTs }
    }

    /**
     * Check if any events exist on a day.
     * Useful for calendar month view dots.
     */
    suspend fun hasEventsOnDay(dayCode: Int): Boolean {
        val occurrences = occurrencesDao.getForDayOnce(dayCode)
        return occurrences.isNotEmpty()
    }

    /**
     * Get day codes that have events in a month.
     * Returns set of YYYYMMDD integers for days with events.
     */
    suspend fun getDaysWithEventsInMonth(year: Int, month: Int): Set<Int> {
        // Calculate month start/end timestamps
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
     * Search events by title, location, or description using FTS4 full-text search.
     *
     * Query syntax supports:
     * - Simple terms: "meeting" (matches word "meeting")
     * - Prefix search: "meet*" (matches "meeting", "meetup", etc.)
     * - Multiple words: "team meeting" (matches events with both words)
     * - Phrases: '"team meeting"' (matches exact phrase)
     *
     * @param query Search query (prefix matching applied automatically)
     * @return Events matching the search, ordered by start time, max 100 results
     */
    suspend fun searchEvents(query: String): List<Event> {
        if (query.isBlank()) return emptyList()
        // Format for FTS: add * suffix for prefix matching on each word
        val ftsQuery = query.trim().split("\\s+".toRegex())
            .joinToString(" ") { "$it*" }
        return eventsDao.search(ftsQuery)
    }

    /**
     * Search events with at least one current/future occurrence.
     *
     * This follows Android CalendarProvider's pattern of using the Instances table
     * for time-based queries. An event is included if it has ANY occurrence that
     * hasn't ended yet (occurrence.endTs >= now).
     *
     * Use this when not including past events.
     *
     * @param query Search query (prefix matching applied automatically)
     * @return Events with at least one non-ended occurrence
     */
    suspend fun searchEventsExcludingPast(query: String): List<Event> {
        if (query.isBlank()) return emptyList()
        val ftsQuery = query.trim().split("\\s+".toRegex())
            .joinToString(" ") { "$it*" }
        return eventsDao.searchFuture(ftsQuery, System.currentTimeMillis())
    }

    /**
     * Search events with occurrences in a specific date range.
     *
     * Combines FTS text matching with occurrence time range filtering.
     * Use this for date-filtered search.
     *
     * @param query Search query (prefix matching applied automatically)
     * @param rangeStart Start of range in millis (inclusive)
     * @param rangeEnd End of range in millis (inclusive)
     * @return Events with at least one occurrence in range
     */
    suspend fun searchEventsInRange(query: String, rangeStart: Long, rangeEnd: Long): List<Event> {
        if (query.isBlank()) return emptyList()
        val ftsQuery = query.trim().split("\\s+".toRegex())
            .joinToString(" ") { "$it*" }
        return eventsDao.searchInRange(ftsQuery, rangeStart, rangeEnd)
    }

    // ========== Search with Next Occurrence ==========

    /**
     * Search events and return with next occurrence timestamp.
     * For "All" filter - includes past events.
     *
     * @param query Search query (plain text, will be formatted for FTS)
     * @return Events with their start timestamp as nextOccurrenceTs
     */
    suspend fun searchEventsWithNextOccurrence(query: String): List<EventWithNextOccurrence> {
        if (query.isBlank()) return emptyList()
        val ftsQuery = query.trim().split("\\s+".toRegex())
            .joinToString(" ") { "$it*" }
        return eventsDao.searchWithOccurrence(ftsQuery)
    }

    /**
     * Search events excluding past, with next occurrence timestamp.
     * For default search (excludes past).
     *
     * @param query Search query (plain text, will be formatted for FTS)
     * @return Events with their next future occurrence timestamp
     */
    suspend fun searchEventsExcludingPastWithNextOccurrence(query: String): List<EventWithNextOccurrence> {
        if (query.isBlank()) return emptyList()
        val ftsQuery = query.trim().split("\\s+".toRegex())
            .joinToString(" ") { "$it*" }
        return eventsDao.searchFutureWithOccurrence(ftsQuery, System.currentTimeMillis())
    }

    /**
     * Search events in date range with next occurrence timestamp.
     * For date-filtered search (Week, Month, custom date).
     *
     * @param query Search query (plain text, will be formatted for FTS)
     * @param rangeStart Start of range (inclusive)
     * @param rangeEnd End of range (inclusive)
     * @return Events with their next occurrence timestamp within the range
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
     * Search events with occurrence info.
     * Uses batch loading to avoid N+1 queries (3 queries instead of 1+N+M).
     */
    suspend fun searchEventsWithOccurrences(
        query: String,
        futureOnly: Boolean = true
    ): List<OccurrenceWithEvent> {
        val events = searchEvents(query)
        if (events.isEmpty()) return emptyList()

        val now = if (futureOnly) System.currentTimeMillis() else 0L

        // Batch load occurrences (1 query instead of N)
        val eventIds = events.map { it.id }
        val allOccurrences = occurrencesDao.getForEvents(eventIds)
            .filter { it.startTs >= now && !it.isCancelled }

        // Group by event and take 5 per event
        val occurrencesByEvent = allOccurrences.groupBy { it.eventId }
        val limitedOccurrences = occurrencesByEvent.flatMap { (_, occs) -> occs.take(5) }

        if (limitedOccurrences.isEmpty()) return emptyList()

        // Create events map for O(1) lookup
        val eventsMap = events.associateBy { it.id }

        // Batch load calendars (1 query instead of M)
        val calendarIds = limitedOccurrences.map { it.calendarId }.distinct()
        val calendarsMap = calendarsDao.getByIds(calendarIds).associateBy { it.id }

        return limitedOccurrences.mapNotNull { occ ->
            val event = eventsMap[occ.eventId] ?: return@mapNotNull null
            val calendar = calendarsMap[occ.calendarId]
            OccurrenceWithEvent(occ, event, calendar)
        }.sortedBy { it.occurrence.startTs }
    }

    // ========== Sync-Related Queries ==========

    /**
     * Get events pending sync.
     */
    suspend fun getPendingSyncEvents(): List<Event> {
        return eventsDao.getPendingSyncEvents()
    }

    /**
     * Get events pending sync for a specific calendar.
     */
    suspend fun getPendingSyncEventsForCalendar(calendarId: Long): List<Event> {
        return eventsDao.getPendingForCalendar(calendarId)
    }

    /**
     * Get events with sync errors.
     */
    suspend fun getEventsWithSyncErrors(): List<Event> {
        return eventsDao.getEventsWithSyncErrors()
    }

    /**
     * Get pending operation count (for UI badge).
     */
    fun getPendingOperationCount(): Flow<Int> {
        return database.pendingOperationsDao().getPendingCount()
    }

    // ========== Statistics ==========

    /**
     * Get total event count.
     */
    suspend fun getTotalEventCount(): Int {
        return eventsDao.getTotalCount()
    }

    /**
     * Get event count for calendar.
     */
    suspend fun getEventCountForCalendar(calendarId: Long): Int {
        return eventsDao.getCountByCalendar(calendarId)
    }

    /**
     * Get total occurrence count (for diagnostics).
     */
    suspend fun getTotalOccurrenceCount(): Int {
        return occurrencesDao.getTotalCount()
    }

    /**
     * Check if occurrences exist in range.
     */
    suspend fun hasOccurrencesInRange(startTs: Long, endTs: Long): Boolean {
        return occurrencesDao.hasOccurrencesInRange(startTs, endTs)
    }

    // ========== Reminder Scheduling ==========

    /**
     * Get events with reminders that have occurrences in the given window.
     * Used by ReminderScheduler to find events that may need reminders scheduled.
     *
     * Returns events where:
     * - Event has non-empty reminders list
     * - Event has occurrences in [fromTime, toTime] range
     * - Calendar is visible (user hasn't hidden it)
     * - Occurrence is not cancelled
     *
     * @param fromTime Start of window (epoch ms)
     * @param toTime End of window (epoch ms)
     * @return List of events with their occurrence times and calendar colors
     */
    suspend fun getEventsWithRemindersInRange(
        fromTime: Long,
        toTime: Long
    ): List<EventWithOccurrenceAndColor> {
        return eventsDao.getEventsWithRemindersInRange(fromTime, toTime)
    }

    /**
     * Get future occurrences for an event within the reminder schedule window.
     * Used for scheduling reminder alarms.
     *
     * @param eventId The event ID
     * @param windowDays Number of days ahead to include (default 7)
     * @return Future occurrences within the window
     */
    suspend fun getOccurrencesForEventInScheduleWindow(
        eventId: Long,
        windowDays: Int = 7
    ): List<Occurrence> {
        val now = System.currentTimeMillis()
        val windowEnd = now + (windowDays.toLong() * 24 * 60 * 60 * 1000)

        return occurrencesDao.getForEvent(eventId)
            .filter { !it.isCancelled && it.startTs >= now && it.startTs <= windowEnd }
    }

    /**
     * Get the occurrence linked to an exception event.
     * Used for scheduling reminders for exception events.
     *
     * @param exceptionEventId The exception event ID
     * @return The linked occurrence, or null if not found
     */
    suspend fun getOccurrenceByExceptionEventId(exceptionEventId: Long): Occurrence? {
        return occurrencesDao.getByExceptionEventId(exceptionEventId)
    }

    // ========== Calendar Lookups for Reminder Cleanup ==========

    /**
     * Get calendars for an account (one-shot).
     * Used for bulk operations like reminder cleanup.
     *
     * @param accountId The account ID
     * @return List of calendars belonging to the account
     */
    suspend fun getCalendarsByAccountIdOnce(accountId: Long): List<Calendar> {
        return calendarsDao.getByAccountIdOnce(accountId)
    }

    /**
     * Get all master events for a calendar (one-shot).
     * Used for bulk operations like reminder cleanup.
     *
     * @param calendarId The calendar ID
     * @return List of master events (excludes exception events)
     */
    suspend fun getEventsForCalendar(calendarId: Long): List<Event> {
        return eventsDao.getAllMasterEventsForCalendar(calendarId)
    }
}
