package org.onekash.kashcal.data.calendar_provider

import org.onekash.kashcal.error.CalendarError

/**
 * Implements [CalendarProviderRepository] over in-memory maps and lists that tests seed and read.
 *
 * With [shouldThrowSecurityException] set, the calendar list, day-range, search and
 * title-suggestion reads throw `SecurityException`, most other reads return null, false or empty,
 * and every write returning a `Result` fails with a `SecurityException`. [ensureCalendarVisible]
 * ignores both failure switches and only records the call.
 *
 * For writes:
 * - set [writeFailure] to fail every write returning a `Result`
 * - [createdEventId] is the next id handed out; each create, new exception and split row
 *   takes one
 * - calls are recorded, for example in [createdEvents], [updatedEvents] and [deletedEventIds]
 */
class FakeCalendarProviderRepository : CalendarProviderRepository {

    private companion object {
        const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
    }

    var calendars: List<DeviceCalendar> = emptyList()
    var instances: List<DeviceCalendarInstance> = emptyList()
    var shouldThrowSecurityException: Boolean = false

    // Write operation configuration
    var writeFailure: CalendarError.DeviceCalendar? = null
    var createdEventId: Long = 100L

    /**
     * 1-based index of the createEvent call that fails with a generic
     * write error; the calls before and after it succeed. -1 disables it.
     * Exercises "one event in a batch fails, the rest continue" without
     * failing every write the way [writeFailure] does.
     */
    var failCreateOnCall: Int = -1
    private var createCallCount = 0

    /**
     * When true, deleteEvent fails while other writes succeed. Exercises the
     * create-then-delete move where the target copy is created but the
     * source delete fails, which must not be a hard save failure.
     */
    var failDelete: Boolean = false

    // Operation tracking
    val createdEvents = mutableListOf<CreatedEvent>()
    val updatedEventIds = mutableListOf<Long>()
    val updatedEvents = mutableListOf<UpdatedEvent>()
    val deletedEventIds = mutableListOf<Long>()
    val createdExceptions = mutableListOf<CreatedException>()
    val deletedOccurrences = mutableListOf<DeletedOccurrence>()
    val deletedFutureOccurrences = mutableListOf<DeletedFutureOccurrence>()
    val editedFutureSeries = mutableListOf<EditedFutureSeries>()
    val movedEvents = mutableListOf<MovedEvent>()

    // Read operation data
    var deviceEvents: MutableMap<Long, DeviceEvent> = mutableMapOf()
    var eventReminders: MutableMap<Long, List<Int>> = mutableMapOf()

    /** One provider Reminders row: minutes before the event and its METHOD. */
    data class ReminderRow(val minutes: Int, val method: Int)

    /**
     * Reminder rows with their methods, per event: the one reminder store the
     * fake writes. A create, or an update given minutes, writes them as
     * METHOD_ALERT rows; an update given null leaves them; a new exception or
     * split row given null copies the master's rows. Minutes seeded only in
     * [eventReminders] read as METHOD_ALERT rows, so tests seeding minutes
     * there still work; [getReminders] and [getRemindersForEvents] read here
     * first.
     */
    val reminderRows: MutableMap<Long, List<ReminderRow>> = mutableMapOf()

    /**
     * When true, copying the master's reminders onto a new exception or split
     * fails (the provider read threw or returned no cursor): nothing is
     * written and the call returns a failure.
     */
    var failReminderCopy: Boolean = false

    /**
     * When true, copying the master's guests and tags onto a new row (a new
     * exception, or the future half of a split) fails the way a failed
     * provider read does: nothing is written and the call returns a failure.
     */
    var failAttendeeAndTagCopy: Boolean = false

    private fun storedReminderRows(eventId: Long): List<ReminderRow> =
        reminderRows[eventId]
            ?: eventReminders[eventId].orEmpty()
                .map { ReminderRow(it, android.provider.CalendarContract.Reminders.METHOD_ALERT) }

    private fun alertRows(minutes: List<Int>): List<ReminderRow> =
        minutes.map { ReminderRow(it, android.provider.CalendarContract.Reminders.METHOD_ALERT) }

    private fun seriesCopyFailure(): Result<Nothing> =
        Result.failure(CalendarError.DeviceCalendar.WriteFailed("series read failed").toException())
    var deviceAttendees: MutableMap<Long, List<DeviceAttendee>> = mutableMapOf()
    var maxReminders: Int = 5

    /**
     * Event ids present in CalendarProvider and not soft-deleted
     * (`DELETED = 0`). A positive set independent of [deviceEvents], so a
     * test can express "Events row exists but DELETED = 1" by seeding
     * [deviceEvents] without adding the id here.
     */
    var activeEventIds: MutableSet<Long> = mutableSetOf()

    /**
     * Event IDs whose Events row is soft-deleted (`DELETED = 1`, waiting for
     * the sync adapter to purge it). The negative counterpart of
     * [activeEventIds], for tests that seed rows through [deviceEvents] or
     * [exceptionEvents] and need one of them to be dead: such a row is never
     * returned by [findExceptionEventId] and is reported inactive by
     * [isEventActive] even if it is also in [activeEventIds].
     */
    val softDeletedEventIds: MutableSet<Long> = mutableSetOf()

    data class CreatedEvent(
        val calendarId: Long,
        val title: String,
        val resultId: Long,
        val description: String? = null,
        val location: String? = null,
        val startTs: Long = 0L,
        val endTs: Long? = null,
        val isAllDay: Boolean = false,
        val rrule: String? = null,
        val duration: String? = null,
        val timezone: String = "",
        val reminders: List<Int> = emptyList(),
        val availability: Int = 0,
        val eventColor: Int? = null,
        val attendees: List<DeviceAttendee>? = null,
        val categories: List<String>? = null,
    )

    /** Records the arguments of one updateEvent call. */
    data class UpdatedEvent(
        val eventId: Long,
        val attendees: List<DeviceAttendee>? = null,
        val categories: List<String>? = null,
        val availability: Int = 0,
        val eventColor: Int? = null,
        val startTs: Long = 0L,
        val endTs: Long? = null,
        val timezone: String = "",
        val title: String = "",
        val description: String? = null,
        val location: String? = null,
        val isAllDay: Boolean = false,
        val rrule: String? = null,
        val duration: String? = null,
        val reminders: List<Int>? = emptyList(),
    )

    data class DeviceTitleRow(
        val title: String,
        val dtstart: Long,
        val calendarId: Long,
        val rrule: String? = null
    )

    data class CreatedException(
        val calendarId: Long,
        val masterEventId: Long,
        val originalInstanceTime: Long,
        val resultId: Long,
        val availability: Int = 0,
        val eventColor: Int? = null,
        val startTs: Long = 0L,
        val endTs: Long = 0L,
        val title: String = "",
        val description: String? = null,
        val location: String? = null,
        val isAllDay: Boolean = false,
        val timezone: String = "",
        val reminders: List<Int>? = emptyList(),
    ) {
        // The occurrence the new row names, resolved by [slotOf]. Kept out of
        // equality so tests comparing whole records ignore it.
        var slot: Pair<Long, Boolean>? = null
    }

    /** [originalInstanceTime] and [isAllDay] are the caller's arguments. */
    data class DeletedOccurrence(
        val masterEventId: Long,
        val originalInstanceTime: Long,
        val isAllDay: Boolean = false,
    ) {
        // The occurrence resolved by [slotOf], kept out of equality.
        var slot: Pair<Long, Boolean>? = null
    }

    data class DeletedFutureOccurrence(
        val masterEventId: Long,
        val fromTimeMs: Long,
        val isAllDay: Boolean = false
    )

    data class EditedFutureSeries(
        val masterEventId: Long,
        val fromTimeMs: Long,
        val isAllDay: Boolean,
        val calendarId: Long,
        val title: String,
        val rrule: String?,
        val newEventId: Long,
        val availability: Int = 0,
        val eventColor: Int? = null,
        val startTs: Long = 0L,
        val endTs: Long? = null,
        val duration: String? = null,
        val description: String? = null,
        val location: String? = null,
        val timezone: String = "",
        val reminders: List<Int>? = emptyList(),
        val categories: List<String>? = null,
    )

    data class MovedEvent(
        val eventId: Long,
        val newCalendarId: Long
    )

    /** Number of getDeviceCalendars calls, counted before any configured throw. */
    var getDeviceCalendarsCallCount: Int = 0

    override suspend fun getDeviceCalendars(): List<DeviceCalendar> {
        getDeviceCalendarsCallCount++
        if (shouldThrowSecurityException) throw SecurityException("Calendar permission revoked")
        return calendars
    }

    override suspend fun getDeviceCalendar(id: Long): DeviceCalendar? {
        if (shouldThrowSecurityException) return null
        return calendars.firstOrNull { it.id == id }
    }

    override suspend fun getInstancesForDayRange(
        startDayCode: Int,
        endDayCode: Int,
        enabledCalendarIds: Set<Long>,
        hideDeclined: Boolean
    ): List<DeviceCalendarInstance> {
        if (shouldThrowSecurityException) throw SecurityException("Calendar permission revoked")
        return instances.filter { it.calendarId in enabledCalendarIds }
    }

    override suspend fun searchInstances(
        query: String,
        startDayCode: Int,
        endDayCode: Int,
        enabledCalendarIds: Set<Long>,
        hideDeclined: Boolean
    ): List<DeviceCalendarInstance> {
        if (shouldThrowSecurityException) throw SecurityException("Calendar permission revoked")
        if (query.isBlank()) return emptyList()
        val lowerQuery = query.lowercase()
        return instances.filter { instance ->
            instance.calendarId in enabledCalendarIds &&
                (instance.title.lowercase().contains(lowerQuery) ||
                    instance.description.lowercase().contains(lowerQuery) ||
                    instance.location.lowercase().contains(lowerQuery))
        }
    }

    /**
     * Backing store for suggestTitlesByPrefix: one row per event, with the
     * columns the real query filters on and reads (title, DTSTART, calendar id,
     * RRULE). Tests seed this, then assert the aggregated
     * [org.onekash.kashcal.data.db.dao.TitleSuggestion] output.
     */
    var deviceTitleRows: List<DeviceTitleRow> = emptyList()

    override suspend fun suggestTitlesByPrefix(
        prefix: String,
        sinceMs: Long,
        untilMs: Long,
        visibleCalendarIds: Set<Long>,
        minFreq: Int,
        limit: Int
    ): List<org.onekash.kashcal.data.db.dao.TitleSuggestion> {
        if (shouldThrowSecurityException) throw SecurityException("Calendar permission revoked")
        if (visibleCalendarIds.isEmpty() || prefix.isBlank()) return emptyList()
        val lowerPrefix = prefix.trim().lowercase()

        // Prefix, visible calendar, non-blank title, and the DTSTART window,
        // which a series (non-null, non-empty rrule) bypasses.
        val matching = deviceTitleRows.filter { row ->
            row.calendarId in visibleCalendarIds &&
                row.title.isNotBlank() &&
                row.title.trim().lowercase().startsWith(lowerPrefix) &&
                (
                    (!row.rrule.isNullOrEmpty()) ||
                        (row.dtstart in sinceMs..untilMs)
                )
        }

        // The same (trimmed, lower-cased title, dtstart) on two calendars, such
        // as one invite on a personal and a work account, counts once.
        val deduped = matching.distinctBy {
            it.title.trim().lowercase() to it.dtstart
        }

        return deduped
            .groupBy { it.title.trim().lowercase() }
            .map { (_, entries) ->
                val latest = entries.maxByOrNull { it.dtstart }!!
                org.onekash.kashcal.data.db.dao.TitleSuggestion(
                    title = latest.title.trim(),
                    freq = entries.size,
                    lastUsed = latest.dtstart
                )
            }
            .filter { it.freq >= minFreq }
            .sortedWith(
                compareByDescending<org.onekash.kashcal.data.db.dao.TitleSuggestion> { it.freq }
                    .thenByDescending { it.lastUsed }
            )
            .take(limit)
    }

    override suspend fun pruneStaleCalendarIds(
        dataStore: org.onekash.kashcal.data.preferences.KashCalDataStore
    ) {
        val storedIds = dataStore.getEnabledDeviceCalendarIds()
        if (storedIds.isEmpty()) return

        val actualCalendarIds = calendars.map { it.id }.toSet()
        val staleIds = storedIds - actualCalendarIds
        if (staleIds.isNotEmpty()) {
            dataStore.setEnabledDeviceCalendarIds(storedIds - staleIds)
        }
    }

    val ensureCalendarVisibleCalls = mutableListOf<Long>()

    override suspend fun ensureCalendarVisible(calendarId: Long) {
        ensureCalendarVisibleCalls.add(calendarId)
    }

    // ==================== Write Operations ====================

    override suspend fun createEvent(
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
        availability: Int,
        eventColor: Int?,
        attendees: List<DeviceAttendee>?,
        categories: List<String>?
    ): Result<Long> {
        createCallCount++
        if (createCallCount == failCreateOnCall) {
            return Result.failure(RuntimeException("Write failed"))
        }
        writeFailure?.let { return Result.failure(it.toException()) }
        if (shouldThrowSecurityException) {
            return Result.failure(CalendarError.DeviceCalendar.PermissionDenied.toException())
        }

        val eventId = createdEventId++
        reminderRows[eventId] = alertRows(reminders)
        createdEvents.add(
            CreatedEvent(
                calendarId = calendarId,
                title = title,
                resultId = eventId,
                description = description,
                location = location,
                startTs = startTs,
                endTs = endTs,
                isAllDay = isAllDay,
                rrule = rrule,
                duration = duration,
                timezone = timezone,
                reminders = reminders,
                availability = availability,
                eventColor = eventColor,
                attendees = attendees,
                categories = categories,
            )
        )
        return Result.success(eventId)
    }

    override suspend fun updateEvent(
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
        availability: Int,
        eventColor: Int?,
        attendees: List<DeviceAttendee>?,
        categories: List<String>?
    ): Result<Unit> {
        writeFailure?.let { return Result.failure(it.toException()) }
        if (shouldThrowSecurityException) {
            return Result.failure(CalendarError.DeviceCalendar.PermissionDenied.toException())
        }

        if (reminders != null) reminderRows[eventId] = alertRows(reminders)
        updatedEventIds.add(eventId)
        updatedEvents.add(
            UpdatedEvent(
                eventId = eventId,
                attendees = attendees,
                categories = categories,
                availability = availability,
                eventColor = eventColor,
                startTs = startTs,
                endTs = endTs,
                timezone = timezone,
                title = title,
                description = description,
                location = location,
                isAllDay = isAllDay,
                rrule = rrule,
                duration = duration,
                reminders = reminders,
            )
        )
        return Result.success(Unit)
    }

    override suspend fun deleteEvent(eventId: Long): Result<Unit> {
        writeFailure?.let { return Result.failure(it.toException()) }
        if (failDelete) {
            return Result.failure(CalendarError.DeviceCalendar.WriteFailed("delete failed").toException())
        }
        if (shouldThrowSecurityException) {
            return Result.failure(CalendarError.DeviceCalendar.PermissionDenied.toException())
        }

        deletedEventIds.add(eventId)
        return Result.success(Unit)
    }

    override suspend fun createException(
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
        availability: Int,
        eventColor: Int?
    ): Result<Long> {
        writeFailure?.let { return Result.failure(it.toException()) }
        if (shouldThrowSecurityException) {
            return Result.failure(CalendarError.DeviceCalendar.PermissionDenied.toException())
        }

        val rows = if (reminders != null) {
            alertRows(reminders)
        } else {
            if (failReminderCopy) return seriesCopyFailure()
            storedReminderRows(masterEventId)
        }
        if (failAttendeeAndTagCopy) return seriesCopyFailure()
        val exceptionId = createdEventId++
        val slot = slotOf(masterEventId, originalInstanceTime, isAllDay)
        reminderRows[exceptionId] = rows
        // Like the real repository, a new exception row gets its own copy of
        // the series' guests and tags (they are stored per event row).
        deviceAttendees[masterEventId]?.takeIf { it.isNotEmpty() }?.let { deviceAttendees[exceptionId] = it }
        eventCategories[masterEventId]?.takeIf { it.isNotEmpty() }?.let { eventCategories[exceptionId] = it }
        createdExceptions.add(
            CreatedException(
                calendarId,
                masterEventId,
                originalInstanceTime,
                exceptionId,
                availability = availability,
                eventColor = eventColor,
                startTs = startTs,
                endTs = endTs,
                title = title,
                description = description,
                location = location,
                isAllDay = isAllDay,
                timezone = timezone,
                reminders = reminders,
            ).apply {
                this.slot = slot
            }
        )
        return Result.success(exceptionId)
    }

    override suspend fun deleteSingleOccurrence(
        masterEventId: Long,
        originalInstanceTime: Long,
        isAllDay: Boolean
    ): Result<Unit> {
        writeFailure?.let { return Result.failure(it.toException()) }
        if (shouldThrowSecurityException) {
            return Result.failure(CalendarError.DeviceCalendar.PermissionDenied.toException())
        }

        val slot = slotOf(masterEventId, originalInstanceTime, isAllDay)
        deletedOccurrences.add(
            DeletedOccurrence(masterEventId, originalInstanceTime, isAllDay).apply {
                this.slot = slot
            }
        )
        return Result.success(Unit)
    }

    override suspend fun deleteThisAndFuture(
        masterEventId: Long,
        fromTimeMs: Long,
        isAllDay: Boolean
    ): Result<Unit> {
        writeFailure?.let { return Result.failure(it.toException()) }
        if (shouldThrowSecurityException) {
            return Result.failure(CalendarError.DeviceCalendar.PermissionDenied.toException())
        }

        deletedFutureOccurrences.add(DeletedFutureOccurrence(masterEventId, fromTimeMs, isAllDay))
        return Result.success(Unit)
    }

    override suspend fun editThisAndFuture(
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
        availability: Int,
        eventColor: Int?,
        categories: List<String>?,
    ): Result<Long> {
        writeFailure?.let { return Result.failure(it.toException()) }
        if (shouldThrowSecurityException) {
            return Result.failure(CalendarError.DeviceCalendar.PermissionDenied.toException())
        }

        // As in the real implementation, a split at or before the master's
        // start is an in-place update; otherwise it makes a new event id.
        val targetEvent = deviceEvents[masterEventId]
        val newEventId = if (targetEvent != null && fromTimeMs <= targetEvent.startTs) {
            masterEventId
        } else {
            // A new row, allocated like createException so copied guests and
            // tags can't land on an id a test seeded.
            createdEventId++
        }
        if (newEventId == masterEventId) {
            // In-place edit of the master: reminders as updateEvent writes
            // them, guests untouched, and edited tags replace the master's
            // (stored cleaned, as the provider stores them).
            if (reminders != null) reminderRows[masterEventId] = alertRows(reminders)
            categories?.let { eventCategories[masterEventId] = cleanCategoryNames(it) }
        } else {
            val rows = if (reminders != null) {
                alertRows(reminders)
            } else {
                if (failReminderCopy) return seriesCopyFailure()
                storedReminderRows(masterEventId)
            }
            if (failAttendeeAndTagCopy) return seriesCopyFailure()
            reminderRows[newEventId] = rows
            // Like the real repository, the future half gets its own copy of
            // the series' guests and of its tags, or the caller's edited tags.
            deviceAttendees[masterEventId]?.takeIf { it.isNotEmpty() }?.let { deviceAttendees[newEventId] = it }
            (categories?.let(::cleanCategoryNames) ?: eventCategories[masterEventId])?.takeIf { it.isNotEmpty() }
                ?.let { eventCategories[newEventId] = it }
        }
        editedFutureSeries.add(
            EditedFutureSeries(
                masterEventId = masterEventId,
                fromTimeMs = fromTimeMs,
                isAllDay = isAllDay,
                calendarId = calendarId,
                title = title,
                rrule = rrule,
                newEventId = newEventId,
                availability = availability,
                eventColor = eventColor,
                startTs = startTs,
                endTs = endTs,
                duration = duration,
                description = description,
                location = location,
                timezone = timezone,
                reminders = reminders,
                categories = categories,
            )
        )
        return Result.success(newEventId)
    }

    override suspend fun moveEventToCalendar(eventId: Long, newCalendarId: Long): Result<Unit> {
        writeFailure?.let { return Result.failure(it.toException()) }
        if (shouldThrowSecurityException) {
            return Result.failure(CalendarError.DeviceCalendar.PermissionDenied.toException())
        }

        movedEvents.add(MovedEvent(eventId, newCalendarId))
        return Result.success(Unit)
    }

    override suspend fun getMaxReminders(calendarId: Long): Int {
        return maxReminders
    }

    override suspend fun getDeviceEvent(eventId: Long): DeviceEvent? {
        if (shouldThrowSecurityException) return null
        return deviceEvents[eventId]
    }

    override suspend fun getNextOccurrenceStart(eventId: Long, afterMs: Long): Long? {
        if (shouldThrowSecurityException) return null
        // The real implementation's lower-bound pad (afterMs - 1 day), so today's all-day
        // or already-started occurrence is still found.
        val lowerBound = afterMs - ONE_DAY_MS
        return instances
            .filter { it.eventId == eventId && it.startTs >= lowerBound }
            .minByOrNull { it.startTs }
            ?.startTs
    }

    override suspend fun isEventActive(eventId: Long): Boolean {
        if (shouldThrowSecurityException) return false
        return eventId in activeEventIds && eventId !in softDeletedEventIds
    }

    override suspend fun getDeviceEventWithExceptions(
        masterEventId: Long
    ): Pair<DeviceEvent, List<DeviceEvent>>? {
        if (shouldThrowSecurityException) return null
        val master = deviceEvents[masterEventId] ?: return null
        val exceptions = deviceEvents.values
            .filter { it.originalId == masterEventId }
            .sortedBy { it.originalInstanceTime ?: Long.MAX_VALUE }
        return master to exceptions
    }

    override suspend fun getAttendees(eventId: Long): List<DeviceAttendee> {
        if (shouldThrowSecurityException) return emptyList()
        return deviceAttendees[eventId] ?: emptyList()
    }

    /** Records each updateSelfAttendeeStatus call for assertions. */
    data class SelfRsvpUpdate(val eventId: Long, val attendeeId: Long, val status: Int)
    val selfRsvpUpdates = mutableListOf<SelfRsvpUpdate>()

    override suspend fun updateSelfAttendeeStatus(
        eventId: Long,
        attendeeId: Long,
        status: Int
    ): Result<Unit> {
        writeFailure?.let { return Result.failure(it.toException()) }
        if (shouldThrowSecurityException) {
            return Result.failure(CalendarError.DeviceCalendar.PermissionDenied.toException())
        }
        selfRsvpUpdates.add(SelfRsvpUpdate(eventId, attendeeId, status))
        return Result.success(Unit)
    }

    override suspend fun getReminders(eventId: Long): List<Int> {
        if (shouldThrowSecurityException) return emptyList()
        return storedReminderRows(eventId).map { it.minutes }
    }

    override suspend fun getRemindersForEvents(eventIds: Set<Long>): Map<Long, List<Int>> {
        if (shouldThrowSecurityException) return emptyMap()
        return eventIds.associateWith { id -> storedReminderRows(id).map { it.minutes } }
            .filterValues { it.isNotEmpty() }
    }

    /** Pre-configured tag categories per event id, for the batch read path. */
    var eventCategories: MutableMap<Long, List<String>> = mutableMapOf()

    override suspend fun getCategoriesForEvents(eventIds: Set<Long>): Map<Long, List<String>> {
        if (shouldThrowSecurityException) return emptyMap()
        return eventIds.associateWith { eventCategories[it] ?: emptyList() }
            .filterValues { it.isNotEmpty() }
    }

    // Exception event lookup data
    var exceptionEvents: MutableMap<Pair<Long, Long>, Long> = mutableMapOf()

    override suspend fun findExceptionEventId(
        masterEventId: Long,
        originalInstanceTime: Long,
        isAllDay: Boolean
    ): Long? {
        if (shouldThrowSecurityException) return null
        val normalizedTime = slotOf(masterEventId, originalInstanceTime, isAllDay).first
        // Exception rows come from [exceptionEvents] or from [deviceEvents] rows
        // carrying the master's id and this instance time. Like the real query,
        // only live rows count: not soft-deleted, not STATUS_CANCELED.
        val candidates = listOfNotNull(exceptionEvents[masterEventId to normalizedTime]) +
            deviceEvents.values
                .filter { it.originalId == masterEventId && it.originalInstanceTime == normalizedTime }
                .sortedBy { it.id }
                .map { it.id }
        return candidates.distinct().firstOrNull { isLiveRow(it) }
    }

    /**
     * Returns the occurrence slot [originalInstanceTime] names, as the real
     * repository resolves it: by the series' all-day flag, falling back to
     * [callerAllDay] when the series isn't seeded in [deviceEvents]. Unlike
     * the real createException, which fails when the series is gone, the
     * fake's still writes, so tests that never seed the series still work.
     */
    private fun slotOf(masterEventId: Long, originalInstanceTime: Long, callerAllDay: Boolean): Pair<Long, Boolean> {
        val allDay = deviceEvents[masterEventId]?.isAllDay ?: callerAllDay
        val time = if (allDay) org.onekash.kashcal.util.DateTimeUtils.normalizeToUtcMidnight(originalInstanceTime) else originalInstanceTime
        return time to allDay
    }

    private fun isLiveRow(eventId: Long): Boolean =
        eventId !in softDeletedEventIds &&
            deviceEvents[eventId]?.status != android.provider.CalendarContract.Events.STATUS_CANCELED

    // ==================== Reminder Operations ====================

    /** The upcoming reminder to return, or null for none. */
    var nextUpcomingReminder: UpcomingDeviceReminder? = null

    override suspend fun getNextUpcomingReminder(
        enabledCalendarIds: Set<Long>,
        afterMs: Long
    ): UpcomingDeviceReminder? {
        if (shouldThrowSecurityException) return null
        if (enabledCalendarIds.isEmpty()) return null
        val reminder = nextUpcomingReminder ?: return null
        return if (reminder.calendarId in enabledCalendarIds && reminder.triggerTime > afterMs) {
            reminder
        } else {
            null
        }
    }

    /**
     * Resets configuration and recorded calls for test isolation, except
     * [editedFutureSeries] and [deviceTitleRows], which it leaves as they are.
     */
    fun reset() {
        calendars = emptyList()
        instances = emptyList()
        shouldThrowSecurityException = false
        writeFailure = null
        failCreateOnCall = -1
        failDelete = false
        createCallCount = 0
        createdEventId = 100L
        createdEvents.clear()
        updatedEventIds.clear()
        updatedEvents.clear()
        deletedEventIds.clear()
        createdExceptions.clear()
        deletedOccurrences.clear()
        deletedFutureOccurrences.clear()
        movedEvents.clear()
        deviceEvents.clear()
        eventReminders.clear()
        reminderRows.clear()
        failReminderCopy = false
        failAttendeeAndTagCopy = false
        eventCategories.clear()
        deviceAttendees.clear()
        activeEventIds.clear()
        exceptionEvents.clear()
        softDeletedEventIds.clear()
        selfRsvpUpdates.clear()
        nextUpcomingReminder = null
        maxReminders = 5
        ensureCalendarVisibleCalls.clear()
        getDeviceCalendarsCallCount = 0
    }
}

/**
 * Converts a CalendarError.DeviceCalendar to the exception the fake's Result.failure carries.
 */
private fun CalendarError.DeviceCalendar.toException(): Exception {
    return when (this) {
        is CalendarError.DeviceCalendar.WriteFailed -> Exception(message)
        CalendarError.DeviceCalendar.PermissionDenied -> SecurityException("WRITE_CALENDAR permission denied")
        CalendarError.DeviceCalendar.CalendarNotFound -> NoSuchElementException("Calendar not found")
        CalendarError.DeviceCalendar.EventNotFound -> NoSuchElementException("Event not found")
        CalendarError.DeviceCalendar.ReadOnlyCalendar -> IllegalStateException("Calendar is read-only")
    }
}
