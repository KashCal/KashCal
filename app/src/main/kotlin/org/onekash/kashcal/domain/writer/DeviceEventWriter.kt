package org.onekash.kashcal.domain.writer

import android.provider.CalendarContract
import android.util.Log
import kotlinx.coroutines.flow.first
import org.onekash.kashcal.data.calendar_provider.CalendarProviderManager
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.DeviceAttendee
import org.onekash.kashcal.data.calendar_provider.DeviceEvent
import org.onekash.kashcal.data.calendar_provider.canonicalAttendeeEmail
import org.onekash.kashcal.data.calendar_provider.cleanCategoryNames
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.util.computeDurationString
import org.onekash.kashcal.util.importEventsToDeviceCalendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The fields of a device event to write, built by the caller from the form (or
 * from a dragged instance). Plain values only, so the writer never depends on
 * UI state.
 *
 * [endTs] is always the occurrence end; the writer decides whether the provider
 * row stores it as DTEND or as a DURATION. [attendees] and [categories] are null
 * when the caller isn't managing them, which leaves the stored rows alone (a new
 * exception or the future half of a split copies the series' guests and tags).
 * [reminders] null likewise keeps the event's reminders exactly as stored,
 * types included (an email or SMS reminder stays one): an update leaves the
 * rows untouched, and a new exception or the future half of a split gets a
 * copy of the series' rows. A reschedule passes null; the form passes the
 * minutes the user chose, which are written as pop-up alerts. A new event has
 * nothing to keep, so [createEvent] and [moveEventToCalendar] treat null as
 * no reminders.
 * [eventColor] null clears the per-event colour, so callers that aren't
 * changing it pass the current value.
 */
data class DeviceEventDraft(
    val calendarId: Long,
    val title: String,
    val description: String?,
    val location: String?,
    val startTs: Long,
    val endTs: Long,
    val isAllDay: Boolean,
    val rrule: String?,
    val timezone: String,
    val reminders: List<Int>?,
    val availability: Int,
    val eventColor: Int?,
    val attendees: List<DeviceAttendee>? = null,
    val categories: List<String>? = null,
)

/**
 * A successful device save: the row that now holds the event, and the cleaned
 * tag names that were written with it (empty when the save wrote no tags), so
 * the caller can record them in the tag registry.
 */
data class DeviceEventSaveResult(
    val eventId: Long,
    val savedTags: List<String>,
)

/**
 * Writes to device-calendar (Android CalendarProvider) events for the UI layer,
 * the device counterpart of [EventWriter].
 *
 * Device events don't go through EventCoordinator or the pending-operations
 * queue: they are not in Room, the owning sync adapter syncs them, and edit
 * scopes resolve against the provider's own exception model (ORIGINAL_ID +
 * ORIGINAL_INSTANCE_TIME). Callers pick the method for
 * the edit scope, the same way they pick EventCoordinator's methods for Room
 * events.
 *
 * What each method returns and whether it signals the device change (so the
 * calendar views re-query at once):
 * - The event writes ([createEvent], [updateEvent], [editSingleOccurrence],
 *   [editThisAndFuture], [moveEventToCalendar], [deleteEvent],
 *   [deleteSingleOccurrence], [deleteThisAndFuture]) return the repository's
 *   [Result] and signal once when it succeeds. Failures come back in the
 *   Result; nothing is thrown.
 * - [replyRsvp] returns `Result<Boolean>` and signals only when a row was
 *   written (true).
 * - [importIcsEvents] returns the number of events created and signals when
 *   that is more than zero.
 * - [pruneStaleCalendarIds] and [ensureCalendarVisible] return Unit and never
 *   signal. They are calendar settings, not event writes: pruning only tidies
 *   the stored selection, and after [ensureCalendarVisible] the Settings caller
 *   re-queries through [CalendarProviderManager.onDeviceCalendarSettingsChanged].
 *
 * The writer does not refresh widgets or reschedule device reminders. While
 * device calendars are on, the provider ContentObserver in
 * [CalendarProviderManager] does both for every provider change, including the
 * app's own; while they are off nothing needs refreshing.
 *
 * The repository moves provider work off the main thread itself, so this class
 * injects no dispatcher.
 */
@Singleton
class DeviceEventWriter @Inject constructor(
    private val repository: CalendarProviderRepository,
    private val calendarProviderManager: CalendarProviderManager,
    private val dataStore: KashCalDataStore,
) {
    companion object {
        private const val TAG = "DeviceEventWriter"
    }

    /** Creates an event in [DeviceEventDraft.calendarId]. */
    suspend fun createEvent(draft: DeviceEventDraft): Result<DeviceEventSaveResult> =
        repository.createEvent(
            calendarId = draft.calendarId,
            title = draft.title,
            description = draft.description,
            location = draft.location,
            startTs = draft.startTs,
            endTs = draft.providerEndTs(),
            isAllDay = draft.isAllDay,
            rrule = draft.rrule,
            duration = draft.providerDuration(),
            timezone = draft.timezone,
            reminders = draft.reminders.orEmpty(),
            availability = draft.availability,
            eventColor = draft.eventColor,
            attendees = draft.attendees,
            categories = draft.categories,
        ).map { DeviceEventSaveResult(it, savedTags(draft.categories)) }.signalOnSuccess()

    /** Rewrites an event row in place: a one-off event, or a whole series. */
    suspend fun updateEvent(eventId: Long, draft: DeviceEventDraft): Result<DeviceEventSaveResult> =
        repository.updateEvent(
            eventId = eventId,
            title = draft.title,
            description = draft.description,
            location = draft.location,
            startTs = draft.startTs,
            endTs = draft.providerEndTs(),
            isAllDay = draft.isAllDay,
            rrule = draft.rrule,
            duration = draft.providerDuration(),
            timezone = draft.timezone,
            reminders = draft.reminders,
            availability = draft.availability,
            eventColor = draft.eventColor,
            attendees = draft.attendees,
            categories = draft.categories,
        ).map { DeviceEventSaveResult(eventId, savedTags(draft.categories)) }.signalOnSuccess()

    /**
     * Changes one occurrence of a series. Updates the occurrence's existing
     * exception row if there is one, otherwise creates it. A new exception row
     * gets a copy of the series' guests and tags (the provider stores them per
     * event row, so it would otherwise have none); an existing one keeps its
     * own. The draft's guests and tags are not written for one occurrence.
     */
    suspend fun editSingleOccurrence(
        masterEventId: Long,
        originalInstanceTime: Long,
        draft: DeviceEventDraft,
    ): Result<DeviceEventSaveResult> {
        val existingExceptionId =
            repository.findExceptionEventId(masterEventId, originalInstanceTime, draft.isAllDay)
        val result = if (existingExceptionId != null) {
            repository.updateEvent(
                eventId = existingExceptionId,
                title = draft.title,
                description = draft.description,
                location = draft.location,
                startTs = draft.startTs,
                endTs = draft.endTs,
                isAllDay = draft.isAllDay,
                rrule = null, // An exception row carries no recurrence of its own.
                duration = null,
                timezone = draft.timezone,
                reminders = draft.reminders,
                availability = draft.availability,
                eventColor = draft.eventColor,
            ).map { existingExceptionId }
        } else {
            repository.createException(
                calendarId = draft.calendarId,
                masterEventId = masterEventId,
                originalInstanceTime = originalInstanceTime,
                title = draft.title,
                description = draft.description,
                location = draft.location,
                startTs = draft.startTs,
                endTs = draft.endTs,
                isAllDay = draft.isAllDay,
                timezone = draft.timezone,
                reminders = draft.reminders,
                availability = draft.availability,
                eventColor = draft.eventColor,
            )
        }
        return result.map { DeviceEventSaveResult(it, emptyList()) }.signalOnSuccess()
    }

    /**
     * Splits a series at [fromTimeMs]: earlier occurrences stay on the master,
     * and a new row carries the draft from that occurrence on (or the master is
     * edited in place; cases in [CalendarProviderRepository.editThisAndFuture]).
     * The new row gets a copy of the series' guests and organizer, and of its
     * tags unless the draft carries edited ones, which it gets instead. The
     * draft's guests are not written (they aren't editable for this scope).
     */
    suspend fun editThisAndFuture(
        masterEventId: Long,
        fromTimeMs: Long,
        draft: DeviceEventDraft,
    ): Result<DeviceEventSaveResult> =
        repository.editThisAndFuture(
            masterEventId = masterEventId,
            fromTimeMs = fromTimeMs,
            isAllDay = draft.isAllDay,
            calendarId = draft.calendarId,
            title = draft.title,
            description = draft.description,
            location = draft.location,
            startTs = draft.startTs,
            endTs = draft.providerEndTs(),
            rrule = draft.rrule,
            duration = draft.providerDuration(),
            timezone = draft.timezone,
            reminders = draft.reminders,
            availability = draft.availability,
            eventColor = draft.eventColor,
            categories = draft.categories,
        ).map { DeviceEventSaveResult(it, savedTags(draft.categories)) }.signalOnSuccess()

    /**
     * Moves a non-recurring [source] event into [DeviceEventDraft.calendarId].
     *
     * Android treats CALENDAR_ID as effectively fixed at creation (changing it
     * in place misbehaves on synced calendars), so a move creates the event in
     * the target and then deletes the source. Creating first means a failed
     * create leaves the event safe where it was. Deleting the source is
     * best-effort: once the copy exists the move has succeeded, and failing it
     * would invite a retry that duplicates the event.
     *
     * Unmanaged guests and tags (null in the draft) are carried over from the
     * source so the move neither uninvites anyone nor drops tags. The source's
     * organizer row is left out: the target calendar writes its own organizer,
     * and on a cross-account move the old one would otherwise become a guest.
     */
    suspend fun moveEventToCalendar(
        source: DeviceEvent,
        draft: DeviceEventDraft,
    ): Result<DeviceEventSaveResult> {
        val attendees = (draft.attendees ?: repository.getAttendees(source.id))
            .filter { it.relationship != CalendarContract.Attendees.RELATIONSHIP_ORGANIZER }
            .takeIf { it.isNotEmpty() }
        val categories = draft.categories ?: source.categories
        return repository.createEvent(
            calendarId = draft.calendarId,
            title = draft.title,
            description = draft.description,
            location = draft.location,
            startTs = draft.startTs,
            endTs = draft.providerEndTs(),
            isAllDay = draft.isAllDay,
            rrule = draft.rrule,
            duration = draft.providerDuration(),
            timezone = draft.timezone,
            reminders = draft.reminders.orEmpty(),
            availability = draft.availability,
            eventColor = draft.eventColor,
            attendees = attendees,
            categories = categories,
        ).map { newId ->
            repository.deleteEvent(source.id).onFailure { e ->
                Log.w(TAG, "Device move: created in target but source delete failed", e)
            }
            DeviceEventSaveResult(newId, savedTags(categories))
        }.signalOnSuccess()
    }

    /** Deletes an event row: a one-off event, or a whole series. */
    suspend fun deleteEvent(eventId: Long): Result<Unit> =
        repository.deleteEvent(eventId).signalOnSuccess()

    /** Deletes one occurrence of a series (a cancelled exception row). */
    suspend fun deleteSingleOccurrence(
        masterEventId: Long,
        originalInstanceTime: Long,
        isAllDay: Boolean,
    ): Result<Unit> =
        repository.deleteSingleOccurrence(masterEventId, originalInstanceTime, isAllDay).signalOnSuccess()

    /** Deletes an occurrence and every later one, by ending the series before it. */
    suspend fun deleteThisAndFuture(
        masterEventId: Long,
        fromTimeMs: Long,
        isAllDay: Boolean,
    ): Result<Unit> =
        repository.deleteThisAndFuture(masterEventId, fromTimeMs, isAllDay).signalOnSuccess()

    /**
     * Writes the user's response on their own attendee row, found by matching
     * the calendar owner's address; no other guest's row is touched. Returns
     * true when a row was written, and false (with nothing written) when the
     * calendar has no owner address or the user isn't on the guest list.
     */
    suspend fun replyRsvp(eventId: Long, calendarId: Long, deviceStatus: Int): Result<Boolean> {
        val ownerEmail = repository.getDeviceCalendar(calendarId)
            ?.ownerAccount
            ?.takeUnless { it.isBlank() }
            ?: return Result.success(false)
        val canonicalOwner = canonicalAttendeeEmail(ownerEmail)
        val selfRow = repository.getAttendees(eventId).firstOrNull { a ->
            !a.email.isNullOrBlank() && canonicalAttendeeEmail(a.email) == canonicalOwner
        } ?: return Result.success(false)
        return repository.updateSelfAttendeeStatus(
            eventId = eventId,
            attendeeId = selfRow.id,
            status = deviceStatus,
        ).map { true }.signalOnSuccess()
    }

    /**
     * Imports events parsed from an ICS file into a device calendar. Events
     * without reminders get the user's default reminder. Returns how many were
     * created; failed events are skipped.
     */
    suspend fun importIcsEvents(events: List<Event>, calendarId: Long): Int {
        val count = importEventsToDeviceCalendar(
            events = events,
            calendarId = calendarId,
            repo = repository,
            defaultTimedReminderMinutes = dataStore.defaultReminderMinutes.first(),
            defaultAllDayReminderMinutes = dataStore.defaultAllDayReminder.first(),
        )
        if (count > 0) calendarProviderManager.notifyDeviceCalendarChanged()
        return count
    }

    /** Forgets ticked device calendars that no longer exist in the provider. */
    suspend fun pruneStaleCalendarIds() {
        repository.pruneStaleCalendarIds(dataStore)
    }

    /**
     * Makes sure a ticked calendar's events are downloaded and visible to the
     * provider's instance queries (some devices ship calendars with both off).
     */
    suspend fun ensureCalendarVisible(calendarId: Long) {
        repository.ensureCalendarVisible(calendarId)
    }

    // An empty RRULE is non-recurring to the provider, which then needs DTEND;
    // test it the same way the repository does, not with a plain null check.
    private fun DeviceEventDraft.isRecurring(): Boolean = !rrule.isNullOrEmpty()

    private fun DeviceEventDraft.providerEndTs(): Long? = if (isRecurring()) null else endTs

    private fun DeviceEventDraft.providerDuration(): String? =
        if (isRecurring()) computeDurationString(startTs, endTs, isAllDay) else null

    private fun savedTags(categories: List<String>?): List<String> =
        categories?.let(::cleanCategoryNames).orEmpty()

    private fun <T> Result<T>.signalOnSuccess(): Result<T> =
        onSuccess { calendarProviderManager.notifyDeviceCalendarChanged() }
}
