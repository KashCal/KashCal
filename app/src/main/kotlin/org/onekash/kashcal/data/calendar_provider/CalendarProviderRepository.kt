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
     * Remove stored enabled calendar IDs that no longer exist in CalendarProvider.
     *
     * Handles uninstalled sync adapters, removed accounts, and deleted calendars.
     * Compares stored enabledDeviceCalendarIds against actual calendars from
     * [getDeviceCalendars] and removes stale IDs.
     *
     * @param dataStore KashCalDataStore to read/write enabled calendar IDs
     */
    suspend fun pruneStaleCalendarIds(dataStore: org.onekash.kashcal.data.preferences.KashCalDataStore)

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
     * Get reminders for an event.
     *
     * @param eventId Event ID
     * @return List of reminder minutes before event
     */
    suspend fun getReminders(eventId: Long): List<Int>

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
}
