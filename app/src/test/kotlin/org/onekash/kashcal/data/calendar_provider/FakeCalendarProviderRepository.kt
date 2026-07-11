package org.onekash.kashcal.data.calendar_provider

import org.onekash.kashcal.error.CalendarError

/**
 * Fake CalendarProviderRepository for testing.
 *
 * Returns pre-configured lists of device calendars and instances.
 * Throws SecurityException when configured to simulate permission revocation.
 *
 * For write operations:
 * - Set [writeFailure] to simulate write failures
 * - [createdEventId] auto-increments for each successful create
 * - [createdEvents] / [updatedEventIds] / [deletedEventIds] track operations
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
     * 1-based index of a createEvent call that should fail with a generic
     * write error, leaving earlier/later calls to succeed. -1 disables it.
     * Use this to exercise "one event in a batch fails, the rest continue"
     * without failing every write the way [writeFailure] does.
     */
    var failCreateOnCall: Int = -1
    private var createCallCount = 0

    /**
     * When true, deleteEvent fails while other writes still succeed. Use to
     * exercise the create-then-delete move path where the target copy is
     * created but the source delete fails (must not be a hard save failure).
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
    var deviceAttendees: MutableMap<Long, List<DeviceAttendee>> = mutableMapOf()
    var maxReminders: Int = 5

    /**
     * Event IDs that are present in CalendarProvider AND not soft-deleted
     * (i.e. `DELETED = 0`). Kept as a positive set, independent of
     * [deviceEvents], so tests can express the "Events row exists but
     * DELETED = 1" state by populating [deviceEvents] without adding the id
     * here.
     */
    var activeEventIds: MutableSet<Long> = mutableSetOf()

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
    )

    /** Records an updateEvent call with the attendee set the caller passed. */
    data class UpdatedEvent(
        val eventId: Long,
        val attendees: List<DeviceAttendee>? = null,
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
        val resultId: Long
    )

    data class DeletedOccurrence(
        val masterEventId: Long,
        val originalInstanceTime: Long,
        val isAllDay: Boolean = false
    )

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
    )

    data class MovedEvent(
        val eventId: Long,
        val newCalendarId: Long
    )

    override suspend fun getDeviceCalendars(): List<DeviceCalendar> {
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
     * Backing store for suggestTitlesByPrefix. Mirrors the structure of the real
     * device query: title + DTSTART + calendarId + optional RRULE. Tests populate
     * this, then assert the aggregated [TitleSuggestion] output.
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

        // Filter: prefix, visible calendar, non-blank title, and recurring-aware
        // window. Recurring (rrule non-null, non-empty) bypasses the DTSTART bounds.
        val matching = deviceTitleRows.filter { row ->
            row.calendarId in visibleCalendarIds &&
                row.title.isNotBlank() &&
                row.title.trim().lowercase().startsWith(lowerPrefix) &&
                (
                    (!row.rrule.isNullOrEmpty()) ||
                        (row.dtstart in sinceMs..untilMs)
                )
        }

        // Cross-calendar dedup: same (title.lowercase().trim(), dtstart) on two
        // calendars (e.g., a Google invite visible on personal + work) counts once.
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
        attendees: List<DeviceAttendee>?
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
        reminders: List<Int>,
        availability: Int,
        eventColor: Int?,
        attendees: List<DeviceAttendee>?
    ): Result<Unit> {
        writeFailure?.let { return Result.failure(it.toException()) }
        if (shouldThrowSecurityException) {
            return Result.failure(CalendarError.DeviceCalendar.PermissionDenied.toException())
        }

        updatedEventIds.add(eventId)
        updatedEvents.add(UpdatedEvent(eventId = eventId, attendees = attendees))
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
        reminders: List<Int>,
        availability: Int,
        eventColor: Int?
    ): Result<Long> {
        writeFailure?.let { return Result.failure(it.toException()) }
        if (shouldThrowSecurityException) {
            return Result.failure(CalendarError.DeviceCalendar.PermissionDenied.toException())
        }

        val exceptionId = createdEventId++
        createdExceptions.add(CreatedException(calendarId, masterEventId, originalInstanceTime, exceptionId))
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

        deletedOccurrences.add(DeletedOccurrence(masterEventId, originalInstanceTime, isAllDay))
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
        reminders: List<Int>,
        availability: Int,
        eventColor: Int?,
    ): Result<Long> {
        writeFailure?.let { return Result.failure(it.toException()) }
        if (shouldThrowSecurityException) {
            return Result.failure(CalendarError.DeviceCalendar.PermissionDenied.toException())
        }

        // Match the real impl: at-or-before-start collapses to an
        // in-place update; otherwise produces a new event id.
        val targetEvent = deviceEvents[masterEventId]
        val newEventId = if (targetEvent != null && fromTimeMs <= targetEvent.startTs) {
            masterEventId
        } else {
            // Allocate a synthetic id distinct from the master.
            (deviceEvents.keys.maxOrNull() ?: masterEventId) + 1L
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
        // Mirror the real impl's lower-bound pad (afterMs - 1 day) so today's all-day or
        // already-started occurrence is still surfaced. See AndroidCalendarProviderRepository.
        val lowerBound = afterMs - ONE_DAY_MS
        return instances
            .filter { it.eventId == eventId && it.startTs >= lowerBound }
            .minByOrNull { it.startTs }
            ?.startTs
    }

    override suspend fun isEventActive(eventId: Long): Boolean {
        if (shouldThrowSecurityException) return false
        return eventId in activeEventIds
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
        return eventReminders[eventId] ?: emptyList()
    }

    override suspend fun getRemindersForEvents(eventIds: Set<Long>): Map<Long, List<Int>> {
        if (shouldThrowSecurityException) return emptyMap()
        return eventIds.associateWith { eventReminders[it] ?: emptyList() }
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
        val normalizedTime = if (isAllDay)
            org.onekash.kashcal.util.DateTimeUtils.normalizeToUtcMidnight(originalInstanceTime) else originalInstanceTime
        return exceptionEvents[masterEventId to normalizedTime]
    }

    // ==================== Reminder Operations ====================

    /** Pre-configured upcoming reminder to return, or null */
    var nextUpcomingReminder: UpcomingDeviceReminder? = null

    override suspend fun getNextUpcomingReminder(
        enabledCalendarIds: Set<Long>,
        afterMs: Long
    ): UpcomingDeviceReminder? {
        if (shouldThrowSecurityException) return null
        if (enabledCalendarIds.isEmpty()) return null
        val reminder = nextUpcomingReminder ?: return null
        // Filter by enabled calendars and time
        return if (reminder.calendarId in enabledCalendarIds && reminder.triggerTime > afterMs) {
            reminder
        } else {
            null
        }
    }

    /**
     * Reset all tracked operations for test isolation.
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
        deviceAttendees.clear()
        activeEventIds.clear()
        exceptionEvents.clear()
        selfRsvpUpdates.clear()
        nextUpcomingReminder = null
        maxReminders = 5
        ensureCalendarVisibleCalls.clear()
    }
}

/**
 * Convert CalendarError.DeviceCalendar to Exception for Result.failure.
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
