package org.onekash.kashcal.domain.reader

import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.DeviceAttendee
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.calendar_provider.DeviceEvent
import org.onekash.kashcal.util.DateTimeUtils
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only access to device-calendar (Android CalendarProvider) events for the
 * UI layer, the device counterpart of [EventReader].
 *
 * Device events are not in Room, so they never go through EventCoordinator or
 * the pending-operations queue; this reader only wraps the provider repository
 * so ViewModels and activities don't reach for it directly. It returns plain
 * provider values ([DeviceEvent], [DeviceCalendar], [DeviceAttendee]); mapping
 * them to UI models stays in the ViewModels. Range, search and title-suggestion
 * reads live in [DisplayEventRepository], which merges device and Room events.
 *
 * The repository moves provider work off the main thread itself, so this class
 * injects no dispatcher.
 */
@Singleton
class DeviceEventReader @Inject constructor(
    private val repository: CalendarProviderRepository,
) {

    /** One provider event row, or null when it doesn't exist. */
    suspend fun getDeviceEvent(eventId: Long): DeviceEvent? = repository.getDeviceEvent(eventId)

    /** Every calendar the provider exposes. */
    suspend fun getDeviceCalendars(): List<DeviceCalendar> = repository.getDeviceCalendars()

    /**
     * The device calendars the user has turned on in KashCal: empty unless the
     * feature is [enabled] and at least one calendar is ticked. Any provider
     * failure (permission revoked mid-read and the like) yields an empty list
     * so the calendar surfaces keep working.
     */
    suspend fun getEnabledDeviceCalendars(enabled: Boolean, enabledIds: Set<Long>): List<DeviceCalendar> {
        if (!enabled || enabledIds.isEmpty()) return emptyList()
        return try {
            repository.getDeviceCalendars().filter { it.id in enabledIds }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Day code (YYYYMMDD) of an event's start, honouring all-day events so they
     * resolve to the right local day in negative UTC offsets. Null when the
     * event is missing or can't be read.
     */
    suspend fun getEventStartDayCode(eventId: Long): Int? {
        val event = repository.getDeviceEvent(eventId) ?: return null
        return DateTimeUtils.eventTsToDayCode(event.startTs, event.isAllDay)
    }

    /**
     * An event's attendee rows plus the calendar owner's address (the "you"
     * identity). An event with no rows skips the calendar lookup; a blank
     * owner or a null [calendarId] gives no owner address.
     */
    suspend fun getAttendeesWithOwner(eventId: Long, calendarId: Long?): DeviceAttendeeRows {
        val attendees = repository.getAttendees(eventId)
        if (attendees.isEmpty()) return DeviceAttendeeRows(emptyList(), ownerEmail = null)
        val ownerEmail = calendarId?.let { id ->
            repository.getDeviceCalendar(id)?.ownerAccount?.takeUnless { it.isBlank() }
        }
        return DeviceAttendeeRows(attendees, ownerEmail)
    }

    /**
     * Everything the edit form needs for one event. When [occurrenceTs] names
     * an occurrence that already has an exception row, that row (with its own
     * reminders and guests) is loaded instead of the series master. Null when
     * the event or its calendar is missing.
     */
    suspend fun getEventForEdit(eventId: Long, occurrenceTs: Long?, isAllDay: Boolean): DeviceEventForEdit? {
        val effectiveEventId = if (occurrenceTs != null) {
            repository.findExceptionEventId(eventId, occurrenceTs, isAllDay) ?: eventId
        } else {
            eventId
        }
        val event = repository.getDeviceEvent(effectiveEventId) ?: return null
        val calendar = repository.getDeviceCalendar(event.calendarId) ?: return null
        return DeviceEventForEdit(
            event = event,
            reminders = repository.getReminders(effectiveEventId),
            calendar = calendar,
            attendees = repository.getAttendees(effectiveEventId),
        )
    }

    /**
     * Returns the occurrence start to open when only an event id is known (an external
     * VIEW intent). A recurring event opens at its first instance from a day before
     * [nowMs], so today's is kept (null once the series has ended); anything else opens at
     * its own start. Null when the event is missing or a read fails.
     */
    suspend fun resolveQuickViewOccurrenceStart(eventId: Long, nowMs: Long): Long? {
        val event = repository.getDeviceEvent(eventId) ?: return null
        return if (!event.rrule.isNullOrEmpty()) {
            repository.getNextOccurrenceStart(eventId, nowMs)
        } else {
            event.startTs
        }
    }

    /**
     * Returns a series master, its exception rows (cancelled ones included) and every
     * row's reminders, for ICS export. Null when the master is missing or a read fails.
     */
    suspend fun getEventWithExceptionsForExport(masterEventId: Long): DeviceEventExport? {
        val (master, exceptions) = repository.getDeviceEventWithExceptions(masterEventId) ?: return null
        val ids = (listOf(master.id) + exceptions.map { it.id }).toSet()
        return DeviceEventExport(master, exceptions, repository.getRemindersForEvents(ids))
    }
}

/** An event's attendee rows and the calendar owner's address, if known. */
data class DeviceAttendeeRows(
    val attendees: List<DeviceAttendee>,
    val ownerEmail: String?,
)

/** The provider rows behind the device edit form. */
data class DeviceEventForEdit(
    val event: DeviceEvent,
    val reminders: List<Int>,
    val calendar: DeviceCalendar,
    val attendees: List<DeviceAttendee>,
)

/** A series master, its exception rows, and reminder minutes keyed by row id. */
data class DeviceEventExport(
    val master: DeviceEvent,
    val exceptions: List<DeviceEvent>,
    val remindersById: Map<Long, List<Int>>,
)
