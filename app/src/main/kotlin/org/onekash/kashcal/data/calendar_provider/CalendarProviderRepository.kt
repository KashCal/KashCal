package org.onekash.kashcal.data.calendar_provider


/**
 * Reads and writes device calendars and events in Android's CalendarProvider.
 *
 * Reads return an empty result (empty list or map, null, or false) when permission is denied;
 * [getMaxReminders] falls back to 5.
 * Writes that return [Result] fail with a
 * [org.onekash.kashcal.error.CalendarError.DeviceCalendar] error: `PermissionDenied`,
 * `EventNotFound` or `WriteFailed`. [pruneStaleCalendarIds] and [ensureCalendarVisible] return
 * Unit.
 */
interface CalendarProviderRepository {

    /** Returns every device calendar, visible or not; see [DeviceCalendar.visible]. */
    suspend fun getDeviceCalendars(): List<DeviceCalendar>

    /**
     * Returns one device calendar by id, or null if it doesn't exist. Reads only that row, for
     * lookups (owner email, invite delivery) that would otherwise scan [getDeviceCalendars].
     */
    suspend fun getDeviceCalendar(id: Long): DeviceCalendar?

    /**
     * Returns the Instances rows (expanded occurrences) of [enabledCalendarIds] for a day range,
     * with reminders and tags filled in. Only calendars with `VISIBLE = 1` are read.
     *
     * @param startDayCode first day, YYYYMMDD, inclusive
     * @param endDayCode last day, YYYYMMDD, inclusive
     * @param hideDeclined drops occurrences the user declined
     */
    suspend fun getInstancesForDayRange(
        startDayCode: Int,
        endDayCode: Int,
        enabledCalendarIds: Set<Long>,
        hideDeclined: Boolean = false
    ): List<DeviceCalendarInstance>

    /**
     * Searches the occurrences of [enabledCalendarIds] in a day range through the provider's
     * Instances search, which matches title, description, location and guest names and emails.
     * A blank [query] returns an empty list.
     *
     * @param startDayCode first day, YYYYMMDD, inclusive
     * @param endDayCode last day, YYYYMMDD, inclusive
     * @param hideDeclined drops occurrences the user declined
     */
    suspend fun searchInstances(
        query: String,
        startDayCode: Int,
        endDayCode: Int,
        enabledCalendarIds: Set<Long>,
        hideDeclined: Boolean = false
    ): List<DeviceCalendarInstance>

    /**
     * Suggests device-event titles starting with [prefix], grouped case-insensitively with use
     * count and last-used time, for the event-form autocomplete.
     *
     * Returns an empty list when [visibleCalendarIds] is empty or [prefix] is blank. Exceptions
     * and deleted rows don't count. A series (non-empty RRULE) counts whatever its DTSTART;
     * a one-off counts only with DTSTART in [sinceMs, untilMs], both inclusive. One title and
     * DTSTART seen on several calendars, such as an invite on a personal and a work account,
     * counts as one use.
     *
     * @param prefix text the user typed, without wildcards
     * @param minFreq minimum use count for a title to be suggested
     * @param limit maximum number of suggestions
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
     * Removes enabled device-calendar ids in [dataStore] that [getDeviceCalendars] no longer
     * returns: an uninstalled sync adapter, a removed account or a deleted calendar.
     */
    suspend fun pruneStaleCalendarIds(dataStore: org.onekash.kashcal.data.preferences.KashCalDataStore)

    /**
     * Makes the calendar's events download and show: writes `SYNC_EVENTS = 1` and `VISIBLE = 1`
     * on its Calendars row, then requests a manual sync on its account.
     *
     * On Xiaomi/MIUI, Google calendars start with `VISIBLE = 0` and `SYNC_EVENTS = 0`, not as a
     * user choice. The Instances reads filter on `VISIBLE = 1` and nothing downloads without
     * `SYNC_EVENTS = 1`, so the view stays blank after the user ticks the calendar (#170;
     * flipping the flags through CalendarContract restores the events).
     *
     * The sync request lets events arrive within a minute instead of on the next idle cycle. It
     * isn't expedited, so metered-connection preferences hold, and a local account, which has
     * no sync adapter, gets none. Failures (a missing row, a SecurityException, an account the
     * sync request rejects) are logged and never propagated, so the UI stays usable on devices
     * that block the write.
     *
     * Called only when the user ticks a calendar in settings. Unticking doesn't write
     * `VISIBLE = 0`: it means "hide from KashCal" (`hiddenDeviceCalendarIds`), not "hide
     * system-wide".
     */
    suspend fun ensureCalendarVisible(calendarId: Long)

    // ==================== Writes ====================

    /**
     * Creates an event and returns its id. The event, its reminders and its guests are written
     * in one batch; the tags are written after it.
     *
     * @param endTs end in epoch ms for a one-off; null for a series, which uses [duration]
     * @param rrule RFC 5545 RRULE, or null for a one-off
     * @param duration RFC 5545 duration for a series, or null
     * @param timezone zone id, e.g. "America/New_York"
     * @param reminders minutes before the start, written as pop-up alerts
     * @param attendees guests to write as `Attendees` rows, or null to write none. A non-empty
     *   list also sets `HAS_ATTENDEE_DATA = 1` and adds an organizer row when the calendar's
     *   owner address is a valid organizer; guests without an email are skipped.
     * @param categories tag names, stored as one extended-property row; null or a list with no
     *   usable name writes no row. A failed tag write leaves the event saved without tags.
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
        eventColor: Int? = null,
        attendees: List<DeviceAttendee>? = null,
        categories: List<String>? = null
    ): Result<Long>

    /**
     * Updates an event row, then its reminders, guests and tags as given; these steps aren't one
     * batch. Parameters are as in [createEvent], except as noted.
     *
     * @param eventColor the color override, or null to clear it
     * @param reminders minutes before the start, written as pop-up alerts in place of the
     *   existing rows; or null to leave the rows untouched, types included (a reschedule changes
     *   only the time, and an email or SMS reminder must stay one)
     * @param attendees the full guest set, or null to leave the rows untouched. A non-null set is
     *   applied as a diff: only added guests are inserted and only removed ones deleted, so
     *   unchanged guests keep their synced status. An empty list removes every guest; the
     *   organizer row stays ([computeAttendeeDiff]).
     * @param categories the full tag set, or null to leave the tag row untouched, so a
     *   reschedule or an exception edit keeps tags the user didn't touch. An empty list clears
     *   the row.
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
        reminders: List<Int>?,
        availability: Int = 0,
        eventColor: Int? = null,
        attendees: List<DeviceAttendee>? = null,
        categories: List<String>? = null
    ): Result<Unit>

    /**
     * Deletes an event as an app: the provider marks a synced row `DELETED = 1` for its sync
     * adapter to purge and removes an unsynced row.
     */
    suspend fun deleteEvent(eventId: Long): Result<Unit>

    /**
     * Creates an exception row for one occurrence of a series and returns its id. The row points
     * to the master through ORIGINAL_ID and ORIGINAL_INSTANCE_TIME.
     *
     * Guests, organizer, tags and reminders are stored per event row, so the new row gets a copy
     * of the master's guest rows, ORGANIZER and tag value (and, with [reminders] null, its
     * reminder rows) in the same batch. If any of them can't be read, nothing is written and a
     * failure is returned.
     *
     * The occurrence it replaces is named by the master's all-day flag, not [isAllDay]: a timed
     * occurrence saved as all-day still replaces the timed slot. If the master can't be found,
     * nothing is written.
     *
     * @param originalInstanceTime the start of the occurrence being replaced
     * @param isAllDay whether the changed occurrence is all-day
     * @param reminders minutes before the occurrence, written as pop-up alerts; or null to copy
     *   the master's reminder rows with their types (email, SMS and so on), so the occurrence
     *   never silently loses them
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
        reminders: List<Int>?,
        availability: Int = 0,
        eventColor: Int? = null
    ): Result<Long>

    /**
     * Deletes one occurrence of a series: inserts a STATUS_CANCELED exception, or cancels the
     * occurrence's existing exception row.
     *
     * @param isAllDay used only when the master can't be read; otherwise the master's all-day
     *   flag names the occurrence (a changed occurrence can show with a different flag than its
     *   series)
     */
    suspend fun deleteSingleOccurrence(
        masterEventId: Long,
        originalInstanceTime: Long,
        isAllDay: Boolean
    ): Result<Unit>

    /**
     * Deletes the occurrences of a series from [fromTimeMs] on, inclusive, by ending the
     * master's RRULE with an UNTIL. When [fromTimeMs] is at or before the master's start, the
     * whole event is deleted.
     *
     * The provider drops the truncated occurrences itself but keeps their exception rows, so
     * those are deleted too, best effort: a failure there still returns success. UNTIL takes the
     * master's all-day flag; [isAllDay] only appears in the log.
     */
    suspend fun deleteThisAndFuture(
        masterEventId: Long,
        fromTimeMs: Long,
        isAllDay: Boolean = false
    ): Result<Unit>

    /**
     * Splits a series at [fromTimeMs]: the master keeps the earlier occurrences and a new row
     * carries the edited fields for the rest. Returns the new row's id, or the master's id when
     * the master is edited in place.
     *
     * The master is edited in place ([updateEvent], as "edit all events") when [fromTimeMs] is
     * at or before its start, or when a COUNT rule would leave no occurrences on one side. For a
     * COUNT rule the split keeps the total: the new row gets the occurrences the master loses,
     * and a failure to count them fails the call with nothing written.
     *
     * The new row's insert and the master's truncation are one `applyBatch`, so a failed insert
     * leaves the master's RRULE untouched. Exception rows in the truncated half are deleted
     * afterwards, best effort, as in [deleteThisAndFuture].
     *
     * Guests, the organizer and tags are stored per event row, so the new row gets a copy of the
     * master's guest rows, ORGANIZER and tag value in the same batch, as a new exception does. If
     * any of them can't be read, nothing is written and a failure is returned. When the master is
     * edited in place its guest rows are left untouched.
     *
     * @param fromTimeMs start of the first occurrence the edit applies to, inclusive
     * @param isAllDay the edited all-day flag; the truncated master's UNTIL takes the master's
     *   own flag
     * @param calendarId the calendar for the new row
     * @param reminders minutes before the start for the future half, written as pop-up alerts;
     *   or null to keep the series' reminders with their types: the new row gets a copy of the
     *   master's rows (failing, with nothing written, if they can't be read), and when the
     *   master is edited in place its rows are left untouched.
     * @param categories tags for the future half, replacing the series' tags (an empty list
     *   means none); or null to keep the series' tags: the new row gets a copy of the master's
     *   stored value, and when the master is edited in place its tags are untouched.
     */
    suspend fun editThisAndFuture(
        masterEventId: Long,
        fromTimeMs: Long,
        isAllDay: Boolean,
        calendarId: Long,
        title: String,
        description: String?,
        location: String?,
        startTs: Long,
        endTs: Long?,
        rrule: String?,
        duration: String?,
        timezone: String,
        reminders: List<Int>?,
        availability: Int = 0,
        eventColor: Int? = null,
        categories: List<String>? = null,
    ): Result<Long>

    /**
     * Moves an event to [newCalendarId] by rewriting its CALENDAR_ID, then that of its exception
     * rows, best effort, since the provider doesn't cascade the change.
     */
    suspend fun moveEventToCalendar(eventId: Long, newCalendarId: Long): Result<Unit>

    /** Returns the calendar's MAX_REMINDERS, at least 1, or 5 when the row can't be read. */
    suspend fun getMaxReminders(calendarId: Long): Int

    /**
     * Returns an Events row with its tags, or null if it can't be read. Doesn't filter on
     * `DELETED`; see [isEventActive].
     */
    suspend fun getDeviceEvent(eventId: Long): DeviceEvent?

    /**
     * Returns the start of the event's first occurrence around [afterMs], read from the
     * Instances view so RRULE, RDATE and EXDATE all apply. For a series this is an upcoming
     * occurrence, not the master's DTSTART, which may be long past.
     *
     * The window reaches one day before [afterMs], so today's occurrence is found even when it
     * began earlier (an all-day one begins at UTC midnight); the result can therefore be before
     * [afterMs]. It reaches about ten years ahead. Returns null when no occurrence falls in the
     * window (an ended series) or the event doesn't exist.
     */
    suspend fun getNextOccurrenceStart(eventId: Long, afterMs: Long): Long?

    /**
     * Returns a master and all its exception rows, sorted by ORIGINAL_INSTANCE_TIME, each with
     * its tags; or null if the master or the exceptions can't be read.
     *
     * Reads the Events table, not the Instances view: a STATUS_CANCELED exception is a deleted
     * occurrence, which Instances leaves out, and export must keep it as a cancelled VEVENT to
     * round-trip (RFC 5545). Every ORIGINAL_ID row is returned whatever its status.
     */
    suspend fun getDeviceEventWithExceptions(masterEventId: Long): Pair<DeviceEvent, List<DeviceEvent>>?

    /**
     * Returns an event's `Attendees` rows in provider order, or an empty list when it has none
     * or the read fails.
     *
     * Read for one event on demand (quick view, edit form); the Instances reads behind the
     * calendar views don't load attendees, which would cost a query per row.
     */
    suspend fun getAttendees(eventId: Long): List<DeviceAttendee>

    /**
     * Sets the user's own RSVP by updating only the `Attendees` row whose `_ID` is
     * [attendeeId], so no guest's synced status is overwritten. Fails with `EventNotFound`
     * when no row matched.
     *
     * On a local account the row is written but no reply is delivered (no sync adapter); the app
     * doesn't promise the organizer is notified.
     *
     * @param eventId the attendee's event, for logging
     * @param status a provider `ATTENDEE_STATUS_*` value
     */
    suspend fun updateSelfAttendeeStatus(
        eventId: Long,
        attendeeId: Long,
        status: Int
    ): Result<Unit>

    /** Returns the minutes before the start of each of the event's reminders. */
    suspend fun getReminders(eventId: Long): List<Int>

    /**
     * Returns reminder minutes per event for [eventIds] in batched queries instead of one per
     * event (range loads, series export). Implementations must chunk [eventIds] to stay under
     * SQLite's bound-variable limit.
     */
    suspend fun getRemindersForEvents(eventIds: Set<Long>): Map<Long, List<Int>>

    /**
     * Returns the tag names per event for [eventIds] in batched queries; events without tags are
     * absent. Tags live in the extended-property store, not on the event row (see
     * [decodeCategories] for what the read accepts).
     */
    suspend fun getCategoriesForEvents(eventIds: Set<Long>): Map<Long, List<String>>

    /**
     * Returns the id of the occurrence's exception row, or null if it has none, so an edit
     * updates that row instead of creating a second one.
     *
     * Only live rows count: a row that is deleted but not yet purged (`DELETED = 1`) or cancelled
     * (`STATUS_CANCELED`) shows as no occurrence at all, so it is never returned. Writing to such
     * a row would change nothing the user can see.
     *
     * @param isAllDay used only when the master can't be read; otherwise the master's all-day
     *   flag names the occurrence
     */
    suspend fun findExceptionEventId(masterEventId: Long, originalInstanceTime: Long, isAllDay: Boolean = false): Long?

    // ==================== Reminders ====================

    /**
     * Returns the reminder with the earliest trigger time after [afterMs] among occurrences of
     * [enabledCalendarIds] with alarms in the next 30 days, or null if there is none.
     * Occurrences the user declined are skipped. See [UpcomingDeviceReminder] for its key.
     */
    suspend fun getNextUpcomingReminder(
        enabledCalendarIds: Set<Long>,
        afterMs: Long = System.currentTimeMillis()
    ): UpcomingDeviceReminder?

    /**
     * Returns true if the Events row exists with `DELETED = 0`; false when it's missing, the
     * read is denied or the provider fails.
     *
     * The provider keeps a user-deleted event as `DELETED = 1` until the sync adapter purges it,
     * and reads by id such as [getDeviceEvent] don't filter on that, so the reminder-fire path,
     * which must not notify for an event the user deleted, checks here.
     */
    suspend fun isEventActive(eventId: Long): Boolean
}
