package org.onekash.kashcal.data.calendar_provider


/**
 * Repository interface for device calendars from Android's CalendarProvider.
 *
 * Read operations return empty results on SecurityException.
 * Write operations return Result with CalendarError.DeviceCalendar on failure.
 */
interface CalendarProviderRepository {

    /**
     * Get all visible device calendars.
     *
     * @return List of device calendars, or empty list if permission denied
     */
    suspend fun getDeviceCalendars(): List<DeviceCalendar>

    /**
     * Get calendar instances (pre-expanded occurrences) for a day range.
     *
     * @param startDayCode Start day in YYYYMMDD format (inclusive)
     * @param endDayCode End day in YYYYMMDD format (inclusive)
     * @param enabledCalendarIds Set of calendar IDs to include
     * @param hideDeclined Whether to hide declined events
     * @return List of instances, or empty list if permission denied
     */
    suspend fun getInstancesForDayRange(
        startDayCode: Int,
        endDayCode: Int,
        enabledCalendarIds: Set<Long>,
        hideDeclined: Boolean = false
    ): List<DeviceCalendarInstance>

    /**
     * Search calendar instances by text query within a day range.
     *
     * Uses Instances.CONTENT_SEARCH_URI for CalendarProvider text search.
     *
     * @param query Search text (matched against title, description, location)
     * @param startDayCode Start day in YYYYMMDD format (inclusive)
     * @param endDayCode End day in YYYYMMDD format (inclusive)
     * @param enabledCalendarIds Set of calendar IDs to include
     * @param hideDeclined Whether to hide declined events
     * @return List of matching instances, or empty list if permission denied
     */
    suspend fun searchInstances(
        query: String,
        startDayCode: Int,
        endDayCode: Int,
        enabledCalendarIds: Set<Long>,
        hideDeclined: Boolean = false
    ): List<DeviceCalendarInstance>

    /**
     * Suggest device-calendar event titles matching a prefix, aggregated by
     * normalized title with frequency and last-used timestamp. Used by the
     * event-form autocomplete dropdown.
     *
     * Returns empty list when [visibleCalendarIds] is empty, permission is
     * denied, or no events match.
     *
     * Recency: recurring events (RRULE non-null and non-empty) bypass the
     * window. Non-recurring events require DTSTART in [sinceMs, untilMs].
     *
     * Cross-calendar dedup: a (title, dtstart) pair visible on multiple
     * calendars is counted as ONE use. This prevents dual-account Google
     * invites (same event on personal + work) from inflating frequency.
     *
     * @param prefix Text the user has typed (no wildcards)
     * @param sinceMs Lower-bound of the non-recurring window (epoch ms, inclusive)
     * @param untilMs Upper-bound of the non-recurring window (epoch ms, inclusive)
     * @param visibleCalendarIds Calendar IDs to include
     * @param minFreq Minimum use count for a title to appear in results
     * @param limit Max suggestions to return
     */
    suspend fun suggestTitlesByPrefix(
        prefix: String,
        sinceMs: Long,
        untilMs: Long,
        visibleCalendarIds: Set<Long>,
        minFreq: Int = 2,
        limit: Int = 5
    ): List<org.onekash.kashcal.data.db.dao.TitleSuggestion>

    /**
     * Remove stored enabled calendar IDs that no longer exist in CalendarProvider.
     *
     * Handles uninstalled sync adapters, removed accounts, and deleted calendars.
     * Compares stored enabledDeviceCalendarIds against actual calendars from
     * [getDeviceCalendars] and removes stale IDs.
     *
     * @param dataStore KashCalDataStore to read/write enabled calendar IDs
     */
    suspend fun pruneStaleCalendarIds(dataStore: org.onekash.kashcal.data.preferences.KashCalDataStore)

    /**
     * Ensure the given calendar's events are downloaded AND visible.
     *
     * On Xiaomi/MIUI, Google calendars ship with both `VISIBLE = 0` and
     * `SYNC_EVENTS = 0` by default — not as a user preference, but as the
     * initial state. Our `Instances` query filters on `VISIBLE = 1`, and
     * events are never downloaded unless `SYNC_EVENTS = 1`. The result is a
     * blank view even after the user ticks the calendar in KashCal. See
     * issue #170 — independently verified by toggling the per-calendar
     * visibility flag through CalendarContract directly, which restores
     * the events.
     *
     * This method:
     *  1. Writes `SYNC_EVENTS = 1` and `VISIBLE = 1` on the Calendars row,
     *     normalizing the Xiaomi/MIUI default-off state.
     *  2. Requests a manual sync on the owning account so events populate
     *     within a minute rather than on the next idle cycle. Honors metered
     *     connection preferences (no expedited flag).
     *
     * Failures (SecurityException, IllegalArgumentException from bad account,
     * missing row, etc.) are logged but never propagated — the UI remains
     * usable on devices that block the write.
     *
     * Only called when the user ticks a calendar in KashCal's settings.
     * Unticking does NOT flip `VISIBLE = 0` back — the user's untick only
     * means "hide from KashCal" (governed by `hiddenDeviceCalendarIds`), not
     * "hide system-wide".
     *
     * @param calendarId Calendar ID to enable sync and visibility for
     */
    suspend fun ensureCalendarVisible(calendarId: Long)

    // ==================== Write Operations (Phase 3) ====================

    /**
     * Create a new event in CalendarProvider.
     *
     * Uses ContentProviderOperation batch for atomicity (event + reminders).
     *
     * @param calendarId Target calendar ID
     * @param title Event title
     * @param description Event description (nullable)
     * @param location Event location (nullable)
     * @param startTs Start timestamp in epoch millis
     * @param endTs End timestamp in epoch millis (for single events)
     * @param isAllDay Whether this is an all-day event
     * @param rrule RFC 5545 RRULE string (nullable for non-recurring)
     * @param duration RFC 5545 duration string for recurring events (nullable)
     * @param timezone Event timezone ID (e.g., "America/New_York")
     * @param reminders List of reminder minutes before event
     * @return Result containing created event ID or CalendarError.DeviceCalendar
     */
    suspend fun createEvent(
        calendarId: Long,
        title: String,
        description: String?,
        location: String?,
        startTs: Long,
        endTs: Long?,
        isAllDay: Boolean,
        rrule: String?,
        duration: String?,
        timezone: String,
        reminders: List<Int>,
        availability: Int = 0,
        eventColor: Int? = null
    ): Result<Long>

    /**
     * Update an existing event in CalendarProvider.
     *
     * Sequential operation: update event, then clear-and-rewrite reminders.
     *
     * @param eventId Event ID to update
     * @param title Event title
     * @param description Event description (nullable)
     * @param location Event location (nullable)
     * @param startTs Start timestamp in epoch millis
     * @param endTs End timestamp in epoch millis (for single events)
     * @param isAllDay Whether this is an all-day event
     * @param rrule RFC 5545 RRULE string (nullable for non-recurring)
     * @param duration RFC 5545 duration string for recurring events (nullable)
     * @param timezone Event timezone ID
     * @param reminders List of reminder minutes before event
     * @return Result.success or CalendarError.DeviceCalendar
     */
    suspend fun updateEvent(
        eventId: Long,
        title: String,
        description: String?,
        location: String?,
        startTs: Long,
        endTs: Long?,
        isAllDay: Boolean,
        rrule: String?,
        duration: String?,
        timezone: String,
        reminders: List<Int>,
        availability: Int = 0,
        eventColor: Int? = null
    ): Result<Unit>

    /**
     * Delete an event from CalendarProvider.
     *
     * Sets deleted=1 for sync adapter cleanup.
     *
     * @param eventId Event ID to delete
     * @return Result.success or CalendarError.DeviceCalendar
     */
    suspend fun deleteEvent(eventId: Long): Result<Unit>

    /**
     * Create an exception event (modified occurrence of a recurring event).
     *
     * Inserts a new event with ORIGINAL_ID + ORIGINAL_INSTANCE_TIME pointing to the master.
     *
     * @param calendarId Target calendar ID
     * @param masterEventId Master event ID
     * @param originalInstanceTime Original occurrence timestamp
     * @param title Event title
     * @param description Event description (nullable)
     * @param location Event location (nullable)
     * @param startTs New start timestamp for this occurrence
     * @param endTs New end timestamp for this occurrence
     * @param isAllDay Whether this is an all-day event
     * @param timezone Event timezone ID
     * @param reminders List of reminder minutes before event
     * @return Result containing created exception event ID or CalendarError.DeviceCalendar
     */
    suspend fun createException(
        calendarId: Long,
        masterEventId: Long,
        originalInstanceTime: Long,
        title: String,
        description: String?,
        location: String?,
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean,
        timezone: String,
        reminders: List<Int>,
        availability: Int = 0,
        eventColor: Int? = null
    ): Result<Long>

    /**
     * Delete a single occurrence of a recurring event.
     *
     * Creates a STATUS_CANCELED exception event.
     *
     * @param masterEventId Master event ID
     * @param originalInstanceTime Original occurrence timestamp to cancel
     * @return Result.success or CalendarError.DeviceCalendar
     */
    suspend fun deleteSingleOccurrence(
        masterEventId: Long,
        originalInstanceTime: Long,
        isAllDay: Boolean
    ): Result<Unit>

    /**
     * Delete this and all future occurrences of a recurring event.
     *
     * Truncates the master event's RRULE with an UNTIL clause.
     * If fromTimeMs <= master event's startTs, deletes the entire event.
     * CalendarProvider handles instance cleanup automatically when RRULE is modified.
     *
     * @param masterEventId Master recurring event ID
     * @param fromTimeMs Occurrence timestamp from which to delete (inclusive)
     * @param isAllDay Whether the event is all-day (affects UNTIL date format)
     * @return Result.success or CalendarError.DeviceCalendar
     */
    suspend fun deleteThisAndFuture(
        masterEventId: Long,
        fromTimeMs: Long,
        isAllDay: Boolean = false
    ): Result<Unit>

    /**
     * Move an event to a different calendar.
     *
     * @param eventId Event ID to move
     * @param newCalendarId Target calendar ID
     * @return Result.success or CalendarError.DeviceCalendar
     */
    suspend fun moveEventToCalendar(eventId: Long, newCalendarId: Long): Result<Unit>

    /**
     * Get maximum number of reminders allowed for a calendar.
     *
     * @param calendarId Calendar ID
     * @return Maximum reminders, or 5 as default fallback
     */
    suspend fun getMaxReminders(calendarId: Long): Int

    /**
     * Get full event data from Events table (not Instances).
     *
     * Used for editing: provides RRULE string, timezone, etc.
     *
     * @param eventId Event ID
     * @return DeviceEvent with full data, or null if not found
     */
    suspend fun getDeviceEvent(eventId: Long): DeviceEvent?

    /**
     * Get a master event together with all its exception rows, read directly from
     * the Events table (NOT the Instances view).
     *
     * Why Events and not Instances: STATUS_CANCELED exception rows represent
     * deleted occurrences of a recurring series. The Instances view filters them
     * out; exporting must preserve them as cancelled VEVENTs for RFC 5545
     * round-trip fidelity. Reading Events directly surfaces every ORIGINAL_ID
     * row regardless of status.
     *
     * Exceptions are returned sorted by ORIGINAL_INSTANCE_TIME ascending.
     *
     * @param masterEventId Master event ID
     * @return (master, exceptions) pair, or null if master not found /
     *         permission revoked / provider error
     */
    suspend fun getDeviceEventWithExceptions(masterEventId: Long): Pair<DeviceEvent, List<DeviceEvent>>?

    /**
     * Get reminders for an event.
     *
     * @param eventId Event ID
     * @return List of reminder minutes before event
     */
    suspend fun getReminders(eventId: Long): List<Int>

    /**
     * Get reminders for a batch of events in a single query.
     *
     * Used to avoid N+1 cursors when an operation needs reminders across a set
     * of events (e.g. series export fetching both master + every exception).
     * Impl must chunk to respect SQLite variable limits.
     *
     * @param eventIds Set of event IDs to fetch reminders for
     * @return Map of eventId to list of reminder minutes before event
     */
    suspend fun getRemindersForEvents(eventIds: Set<Long>): Map<Long, List<Int>>

    /**
     * Find an existing exception event by master event ID and original instance time.
     *
     * Used to detect if an occurrence has already been modified (exception exists).
     * If so, we should update the existing exception rather than creating a new one.
     *
     * @param masterEventId Master recurring event ID
     * @param originalInstanceTime Original occurrence timestamp
     * @return Exception event ID if found, null otherwise
     */
    suspend fun findExceptionEventId(masterEventId: Long, originalInstanceTime: Long, isAllDay: Boolean = false): Long?

    // ==================== Reminder Operations (Phase 4) ====================

    /**
     * Get the next upcoming device calendar reminder.
     *
     * Queries CalendarProvider for events with alarms, calculates trigger times,
     * and returns the earliest upcoming reminder where triggerTime > afterMs.
     *
     * Uses (eventId, occurrenceStartTs) as stable composite key - NOT instanceId.
     *
     * @param enabledCalendarIds Set of calendar IDs to include
     * @param afterMs Only return reminders with triggerTime after this (default: now)
     * @return The next upcoming reminder, or null if none found
     */
    suspend fun getNextUpcomingReminder(
        enabledCalendarIds: Set<Long>,
        afterMs: Long = System.currentTimeMillis()
    ): UpcomingDeviceReminder?

    /**
     * Is the event present and not soft-deleted?
     *
     * CalendarProvider marks a user-deleted event as `DELETED = 1` and leaves
     * the row in place until the sync adapter purges it. Queries by primary
     * key (e.g. [getDeviceEvent]) do NOT filter on deletion state, so the
     * reminder-fire path — which must not notify for events the user has
     * already deleted — needs this dedicated predicate.
     *
     * @param eventId Event ID
     * @return true iff the Events row exists with DELETED = 0 and the caller
     *         holds READ_CALENDAR; false on any provider error or missing row
     */
    suspend fun isEventActive(eventId: Long): Boolean
}
