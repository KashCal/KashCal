package org.onekash.kashcal.domain.coordinator

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.onekash.kashcal.data.contacts.ContactAnniversaryRepository
import org.onekash.kashcal.data.contacts.ContactBirthdayRepository
import org.onekash.kashcal.data.contacts.ContactEventSyncResult
import org.onekash.kashcal.data.contacts.ContactEventUtils
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.identity.effectiveAddresses
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.ics.IcsSubscriptionRepository
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.reader.EventReader.OccurrenceWithEvent
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.scheduler.IcsRefreshScheduleReconciler
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.widget.WidgetUpdateManager
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fronts Room event writes and reads for the UI layer, so ViewModels never touch a DAO.
 *
 * Writes go through [EventWriter] and reads through [EventReader]. After an event create, edit,
 * move or delete it requests an expedited sync for a synced calendar, arms or cancels the affected
 * reminders and refreshes the widgets. It also fronts RRULE expansion ([OccurrenceGenerator]), the
 * local calendar ([LocalCalendarInitializer]), ICS subscriptions and file import, and the contact
 * birthday and anniversary calendars. Device-calendar events don't come here; they go through
 * `DeviceEventWriter`.
 *
 * ```
 * UI Layer (ViewModels)
 *         ↓
 * EventCoordinator ← entry point
 *         ↓
 * ┌───────┼───────┐
 * ↓       ↓       ↓
 * EventWriter  EventReader  OccurrenceGenerator
 *         ↓
 *    Room Database
 * ```
 */
@Singleton
class EventCoordinator @Inject constructor(
    private val eventWriter: EventWriter,
    private val eventReader: EventReader,
    private val occurrenceGenerator: OccurrenceGenerator,
    private val localCalendarInitializer: LocalCalendarInitializer,
    private val icsSubscriptionRepository: IcsSubscriptionRepository,
    private val contactBirthdayRepository: ContactBirthdayRepository,
    private val contactAnniversaryRepository: ContactAnniversaryRepository,
    private val accountRepository: AccountRepository,
    private val syncScheduler: SyncScheduler,
    private val reminderScheduler: ReminderScheduler,
    private val widgetUpdateManager: WidgetUpdateManager,
    private val inviteNotifier: org.onekash.kashcal.sync.notification.InviteNotifier,
    private val icsRefreshScheduleReconciler: IcsRefreshScheduleReconciler,
    private val dataStore: KashCalDataStore
) {
    // ========== Initialization ==========

    /** Creates the local calendar if missing and returns its id; call on app startup. */
    suspend fun ensureLocalCalendarExists(): Long {
        return localCalendarInitializer.ensureLocalCalendarExists()
    }

    suspend fun getLocalCalendarId(): Long {
        return localCalendarInitializer.getLocalCalendarId()
    }

    fun isLocalCalendar(calendar: Calendar): Boolean {
        return localCalendarInitializer.isLocalCalendar(calendar)
    }

    // ========== Sync Trigger ==========

    /**
     * Requests an expedited sync after a write to a synced calendar; a no-op when [isLocal].
     *
     * Enqueues and returns without waiting. [SyncScheduler.requestExpeditedSync] runs it as
     * non-expedited work when the expedited quota is spent, waits for a network, and replaces a
     * queued or running sync, so rapid consecutive edits share one.
     */
    private fun triggerImmediatePushIfNeeded(isLocal: Boolean) {
        if (!isLocal) {
            syncScheduler.requestExpeditedSync(forceFullSync = false)
        }
    }

    // ========== Widget Updates ==========

    /**
     * Refreshes the event widgets (not the date-only DateWidget), suspending until they have
     * updated.
     *
     * [WidgetUpdateManager.updateAllWidgets] logs a failure other than cancellation instead of
     * throwing, and schedules a retry when the failure is transient.
     */
    private suspend fun triggerWidgetUpdate() {
        widgetUpdateManager.updateAllWidgets()
    }

    // ========== Reminder Scheduling ==========

    /**
     * Cancels the reminders of every calendar of the iCloud account with [accountEmail].
     *
     * Call before cascade-deleting the account, or its AlarmManager alarms outlive the rows.
     * A no-op when no iCloud account has that email.
     */
    suspend fun cancelRemindersForAccount(accountEmail: String) {
        val account = accountRepository.getAccountByProviderAndEmail(AccountProvider.ICLOUD, accountEmail) ?: return
        val calendars = eventReader.getCalendarsByAccountIdOnce(account.id)

        for (calendar in calendars) {
            cancelRemindersForCalendar(calendar.id)
        }
        Log.i(TAG, "Cancelled reminders for account: ${accountEmail.take(3)}***")
    }

    /**
     * Cancels the reminders of every calendar of account [accountId].
     *
     * Call before cascade-deleting the account, or its AlarmManager alarms outlive the rows.
     */
    suspend fun cancelRemindersForCalDavAccount(accountId: Long) {
        val calendars = eventReader.getCalendarsByAccountIdOnce(accountId)
        for (calendar in calendars) {
            cancelRemindersForCalendar(calendar.id)
        }
        Log.i(TAG, "Cancelled reminders for CalDAV account: $accountId")
    }

    private suspend fun cancelRemindersForCalendar(calendarId: Long) {
        reminderScheduler.cancelRemindersForCalendar(calendarId)
    }

    /**
     * Arms [event]'s reminders for its occurrences in the scheduling window.
     *
     * An exception has no occurrences of its own: occurrence rows belong to the master, so it
     * takes the one occurrence linked to it.
     */
    private suspend fun scheduleRemindersForEvent(event: Event) {
        if (event.reminders.isNullOrEmpty()) return

        // The event is already saved when this runs, so a reminder failure must
        // not fail the caller: reporting failure invites a retry that creates a
        // duplicate event.
        try {
            val calendar = eventReader.getCalendarById(event.calendarId) ?: return

            val occurrences = if (event.originalEventId != null) {
                listOfNotNull(eventReader.getOccurrenceByExceptionEventId(event.id))
            } else {
                eventReader.getOccurrencesForEventInScheduleWindow(
                    event.id, ReminderScheduler.OCCURRENCE_LOOKAHEAD_DAYS
                )
            }

            reminderScheduler.scheduleRemindersForEvent(
                event = event,
                occurrences = occurrences,
                calendarColor = calendar.color
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to schedule reminders for event ${event.id}", e)
        }
    }

    /**
     * Cancels and re-arms [event]'s reminders.
     *
     * Catches failures so the write that called it still succeeds; the daily
     * `ReminderRefreshWorker` re-arms whatever is missing.
     */
    private suspend fun rescheduleRemindersForEvent(event: Event) {
        try {
            reminderScheduler.cancelRemindersForEvent(event.id)
            scheduleRemindersForEvent(event)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reschedule reminders for event ${event.id}, " +
                "ReminderRefreshWorker will recover", e)
        }
    }

    // ========== Create Operations ==========

    /**
     * Creates [event] in [calendarId], or in the local calendar when null.
     *
     * @param attendees the invite's attendees; a non-empty list may also set the organizer
     *   ([resolveOrganizer]).
     * @throws IllegalArgumentException when the event ends before it starts or the calendar is
     *   read-only.
     */
    suspend fun createEvent(
        event: Event,
        calendarId: Long? = null,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee>? = null
    ): Event {
        require(event.endTs >= event.startTs) {
            "End time (${event.endTs}) must be >= start time (${event.startTs})"
        }

        val targetCalendarId = calendarId ?: getLocalCalendarId()
        val calendar = eventReader.getCalendarById(targetCalendarId)

        // Defense in depth: the UI also hides read-only calendars.
        require(calendar?.isReadOnly != true) {
            "Cannot create event on read-only calendar"
        }

        val eventWithCalendar = resolveOrganizer(
            event.copy(calendarId = targetCalendarId),
            calendar,
            attendees
        )
        val isLocal = calendar?.let { isLocalCalendar(it) } ?: false

        val result = eventWriter.createEvent(eventWithCalendar, isLocal, attendees)
        triggerImmediatePushIfNeeded(isLocal)

        scheduleRemindersForEvent(result)

        triggerWidgetUpdate()

        return result
    }

    /**
     * Sets the organizer on a locally authored event that has attendees.
     *
     * The organizer is the account's first email-shaped calendar-user-address
     * (RFC 6638 §2.4.1, [effectiveAddresses]), stored without `mailto:`. It is set only
     * when [attendees] is non-empty and the event names no organizer yet (one kept from a
     * pull stays). With no email-shaped address the event is returned unchanged: an
     * organizer is never synthesized, since a bogus one makes the server misroute
     * scheduling. Setting it doesn't change SEQUENCE; `SequenceBumper` ignores the organizer.
     */
    private suspend fun resolveOrganizer(
        event: Event,
        calendar: Calendar?,
        attendees: List<Attendee>?
    ): Event {
        if (attendees.isNullOrEmpty()) return event
        if (!event.organizerEmail.isNullOrBlank()) return event
        val accountId = calendar?.accountId ?: return event
        val account = accountRepository.getAccountById(accountId) ?: return event
        // Discovery puts a mailto at index 0 when there is one. Store it bare: the pull
        // stores addresses bare and the generator prepends "mailto:" on emit, so a stored
        // mailto: would become "ORGANIZER:mailto:mailto:…". A principal-path or
        // urn:uuid-only account gets no organizer: the generator would mangle a non-mailto
        // ORGANIZER, and such an account isn't mailto-schedulable.
        val bare = account.effectiveAddresses()
            .firstOrNull { org.onekash.kashcal.util.AddressNormalizer.isEmailShaped(it) }
            ?.let { org.onekash.kashcal.util.AddressNormalizer.stripMailto(it) }
            ?: return event
        return event.copy(
            organizerEmail = bare,
            organizerName = account.displayName?.ifBlank { null }
        )
    }

    /**
     * Creates a recurring event through [createEvent]; throws when [event] has no RRULE.
     *
     * @param attendees forwarded to [createEvent], so a recurring invite stores its attendees
     *   and gets an organizer like any other event. null writes no attendee rows.
     */
    suspend fun createRecurringEvent(
        event: Event,
        calendarId: Long? = null,
        attendees: List<Attendee>? = null
    ): Event {
        require(!event.rrule.isNullOrBlank()) { "RRULE must be set for recurring event" }
        return createEvent(event, calendarId, attendees)
    }

    // ========== Update Operations ==========

    /**
     * Updates [event] and re-arms its reminders.
     *
     * @param attendees the edited attendee set, or null to leave the stored set as it is.
     * @throws IllegalArgumentException when the event ends before it starts or doesn't exist.
     */
    suspend fun updateEvent(
        event: Event,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee>? = null
    ): Event {
        require(event.endTs >= event.startTs) {
            "End time (${event.endTs}) must be >= start time (${event.startTs})"
        }

        val calendar = eventReader.getCalendarById(event.calendarId)
        val isLocal = calendar?.let { isLocalCalendar(it) } ?: false
        val eventToWrite = resolveOrganizer(event, calendar, attendees)
        val result = eventWriter.updateEvent(eventToWrite, isLocal, attendees)
        triggerImmediatePushIfNeeded(isLocal)

        // Time or reminders may have changed.
        rescheduleRemindersForEvent(result)

        triggerWidgetUpdate()

        return result
    }

    /**
     * Edits the occurrence of [masterEventId] at [occurrenceTimeMs] as an exception.
     *
     * @param changes applied to the master projected onto that occurrence
     *   ([Event.projectOntoOccurrence]), with its recurrence cleared.
     * @param attendees this occurrence's attendee set, or null to leave attendees alone
     *   ([EventWriter.editSingleOccurrence]).
     * @return the exception event.
     */
    suspend fun editSingleOccurrence(
        masterEventId: Long,
        occurrenceTimeMs: Long,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee>? = null,
        changes: (Event) -> Event
    ): Event {
        val masterEvent = requireNotNull(eventReader.getEventById(masterEventId)) {
            "Master event not found: $masterEventId"
        }

        val calendar = eventReader.getCalendarById(masterEvent.calendarId)
        val isLocal = calendar?.let { isLocalCalendar(it) } ?: false

        // The lambda edits a single occurrence, not the series.
        val modifiedEvent = changes(masterEvent.projectOntoOccurrence(occurrenceTimeMs))

        val result = eventWriter.editSingleOccurrence(
            masterEventId = masterEventId,
            occurrenceTimeMs = occurrenceTimeMs,
            modifiedEvent = modifiedEvent,
            isLocal = isLocal,
            attendees = attendees
        )
        triggerImmediatePushIfNeeded(isLocal)

        // The exception replaces this occurrence. Cancel its reminders before arming the
        // exception's, or both fire.
        reminderScheduler.cancelReminderForOccurrence(masterEventId, occurrenceTimeMs)

        scheduleRemindersForEvent(result)

        triggerWidgetUpdate()

        return result
    }

    /**
     * Edits the occurrences of [masterEventId] from [splitTimeMs] on by splitting the series.
     *
     * @param changes applied to the master to form the new series.
     * @return the new series, or the master updated in place when [EventWriter.splitSeries]
     *   takes that path (a split at or before the first occurrence, or a COUNT split that
     *   would leave one side empty).
     */
    suspend fun editThisAndFuture(
        masterEventId: Long,
        splitTimeMs: Long,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee>? = null,
        changes: (Event) -> Event
    ): Event {
        val masterEvent = requireNotNull(eventReader.getEventById(masterEventId)) {
            "Master event not found: $masterEventId"
        }

        val calendar = eventReader.getCalendarById(masterEvent.calendarId)
        val isLocal = calendar?.let { isLocalCalendar(it) } ?: false

        val modifiedEvent = changes(masterEvent)

        val result = eventWriter.splitSeries(
            masterEventId = masterEventId,
            splitTimeMs = splitTimeMs,
            modifiedEvent = modifiedEvent,
            isLocal = isLocal,
            attendees = attendees
        )
        triggerImmediatePushIfNeeded(isLocal)

        // The master's reminders at or after splitTimeMs belong to the new series now.
        // Cancel them only after splitSeries succeeds: it runs in one transaction, so a
        // failure rolls back and the master's reminders must still fire. deleteThisAndFuture
        // cancels before its write, which is safe because deletion is the user's intent.
        reminderScheduler.cancelRemindersForOccurrencesAfter(masterEventId, splitTimeMs)

        scheduleRemindersForEvent(result)

        triggerWidgetUpdate()

        return result
    }

    // ========== Delete Operations ==========

    /**
     * Deletes event [eventId] and cancels its reminders.
     *
     * @throws IllegalArgumentException when the event doesn't exist or is an exception; delete
     *   an exception with [deleteSingleOccurrence].
     */
    suspend fun deleteEvent(eventId: Long) {
        val event = requireNotNull(eventReader.getEventById(eventId)) {
            "Event not found: $eventId"
        }

        // Deleting an exception row alone adds no EXDATE to the master and leaves orphaned
        // data; deleteSingleOccurrence adds the EXDATE and cancels the occurrence.
        require(event.originalEventId == null) {
            "Cannot delete exception event directly. Use deleteSingleOccurrence() to delete the occurrence."
        }

        val calendar = eventReader.getCalendarById(event.calendarId)
        val isLocal = calendar?.let { isLocalCalendar(it) } ?: false

        reminderScheduler.cancelRemindersForEvent(eventId)

        eventWriter.deleteEvent(eventId, isLocal)
        triggerImmediatePushIfNeeded(isLocal)

        triggerWidgetUpdate()
    }

    /** Deletes the occurrence of [masterEventId] at [occurrenceTimeMs] by adding an EXDATE. */
    suspend fun deleteSingleOccurrence(masterEventId: Long, occurrenceTimeMs: Long) {
        val masterEvent = requireNotNull(eventReader.getEventById(masterEventId)) {
            "Master event not found: $masterEventId"
        }

        val calendar = eventReader.getCalendarById(masterEvent.calendarId)
        val isLocal = calendar?.let { isLocalCalendar(it) } ?: false

        reminderScheduler.cancelReminderForOccurrence(masterEventId, occurrenceTimeMs)

        eventWriter.deleteSingleOccurrence(masterEventId, occurrenceTimeMs, isLocal)
        triggerImmediatePushIfNeeded(isLocal)

        triggerWidgetUpdate()
    }

    /** Deletes the occurrences of [masterEventId] from [fromTimeMs] on by truncating the series. */
    suspend fun deleteThisAndFuture(masterEventId: Long, fromTimeMs: Long) {
        val masterEvent = requireNotNull(eventReader.getEventById(masterEventId)) {
            "Master event not found: $masterEventId"
        }

        val calendar = eventReader.getCalendarById(masterEvent.calendarId)
        val isLocal = calendar?.let { isLocalCalendar(it) } ?: false

        reminderScheduler.cancelRemindersForOccurrencesAfter(masterEventId, fromTimeMs)

        eventWriter.deleteThisAndFuture(masterEventId, fromTimeMs, isLocal)
        triggerImmediatePushIfNeeded(isLocal)

        triggerWidgetUpdate()
    }

    // ========== Move Operations ==========

    /**
     * Moves event [eventId] to calendar [newCalendarId] and re-arms its reminders.
     *
     * @throws IllegalArgumentException when [EventWriter.moveEventToCalendar] refuses the move,
     *   for example for an exception or a read-only target.
     */
    suspend fun moveEventToCalendar(eventId: Long, newCalendarId: Long) {
        // The writer validates the move and picks the sync operations for the account pair.
        eventWriter.moveEventToCalendar(eventId, newCalendarId)

        val calendar = eventReader.getCalendarById(newCalendarId)
        val isLocal = calendar?.let { isLocalCalendar(it) } ?: false
        triggerImmediatePushIfNeeded(isLocal)

        // The notification icon is tinted with the calendar color.
        val movedEvent = eventReader.getEventById(eventId)
        if (movedEvent != null) {
            rescheduleRemindersForEvent(movedEvent)
        }

        triggerWidgetUpdate()
    }

    // ========== Tag Rename ==========

    /**
     * Renames a tag on every event and sends the change to the server.
     *
     * [EventWriter.renameCategory] rewrites and queues in one transaction. After it commits,
     * this requests one expedited sync, only when at least one event was queued. One sync
     * covers the whole batch: a rename touching hundreds of events must not request hundreds.
     */
    suspend fun renameTag(from: String, to: String) {
        val queued = eventWriter.renameCategory(from, to)
        if (queued > 0) {
            // renameCategory queues no local or read-only event, so this always syncs.
            triggerImmediatePushIfNeeded(isLocal = false)
        }
    }

    /**
     * Records [tags] in the shared tag registry so new names gain a suggestion entry and
     * become colorable.
     *
     * For tags saved on device-calendar events: CalendarProvider has no tag registry, so the
     * registry write can't ride along with the save as it does for Room events.
     */
    suspend fun recordTagUsage(tags: List<String>) {
        eventWriter.recordCategoryUsage(tags)
    }

    // ========== Read Operations (Delegated to EventReader) ==========

    suspend fun getEventById(eventId: Long): Event? {
        return eventReader.getEventById(eventId)
    }

    fun getAllCalendars(): Flow<List<Calendar>> {
        return eventReader.getAllCalendars()
    }

    /** Emits the number of iCloud calendars, again whenever calendars change. */
    fun getICloudCalendarCount(): Flow<Int> {
        return eventReader.getICloudCalendarCount()
    }

    /** Emits the number of CalDAV accounts, again whenever accounts change. */
    fun getCalDavAccountCount(): Flow<Int> {
        return accountRepository.getAccountCountByProviderFlow(AccountProvider.CALDAV)
    }

    fun getCalDavAccounts(): Flow<List<Account>> {
        return accountRepository.getAccountsByProviderFlow(AccountProvider.CALDAV)
    }

    fun getAllAccounts(): Flow<List<Account>> {
        return accountRepository.getAllAccountsFlow()
    }

    suspend fun getCalendarCountForAccount(accountId: Long): Int {
        return eventReader.getCalendarCountForAccount(accountId)
    }

    fun getVisibleCalendars(): Flow<List<Calendar>> {
        return eventReader.getVisibleCalendars()
    }

    suspend fun getCalendarById(calendarId: Long): Calendar? {
        return eventReader.getCalendarById(calendarId)
    }

    /** Writes the calendar's visible flag, the source of truth for which calendars show. */
    suspend fun setCalendarVisibility(calendarId: Long, visible: Boolean) {
        eventReader.setCalendarVisibility(calendarId, visible)
    }

    fun getOccurrencesInRange(startTs: Long, endTs: Long): Flow<List<Occurrence>> {
        return eventReader.getOccurrencesInRange(startTs, endTs)
    }

    fun getVisibleOccurrencesInRange(startTs: Long, endTs: Long): Flow<List<Occurrence>> {
        return eventReader.getVisibleOccurrencesInRange(startTs, endTs)
    }

    /** Emits the occurrences on [day], a YYYYMMDD code. */
    fun getOccurrencesForDay(day: Int): Flow<List<Occurrence>> {
        return eventReader.getOccurrencesForDay(day)
    }

    suspend fun getEventsForDay(dayCode: Int): List<OccurrenceWithEvent> {
        return eventReader.getEventsForDay(dayCode)
    }

    suspend fun getOccurrencesWithEventsInRange(
        startTs: Long,
        endTs: Long
    ): List<OccurrenceWithEvent> {
        return eventReader.getOccurrencesWithEventsInRange(startTs, endTs)
    }

    /** Emits the occurrences with their events in a range, again whenever occurrences change. */
    fun getOccurrencesWithEventsInRangeFlow(
        startTs: Long,
        endTs: Long
    ): Flow<List<OccurrenceWithEvent>> {
        return eventReader.getOccurrencesWithEventsInRangeFlow(startTs, endTs)
    }

    suspend fun getOccurrenceWithEvent(
        eventId: Long,
        occurrenceTimeMs: Long
    ): OccurrenceWithEvent? {
        return eventReader.getOccurrenceWithEvent(eventId, occurrenceTimeMs)
    }

    suspend fun getDaysWithEventsInMonth(year: Int, month: Int): Set<Int> {
        return eventReader.getDaysWithEventsInMonth(year, month)
    }

    suspend fun searchEvents(query: String): List<Event> {
        return eventReader.searchEvents(query)
    }

    suspend fun searchEventsWithOccurrences(
        query: String,
        futureOnly: Boolean = true
    ): List<OccurrenceWithEvent> {
        return eventReader.searchEventsWithOccurrences(query, futureOnly)
    }

    fun getPendingOperationCount(): Flow<Int> {
        return eventReader.getPendingOperationCount()
    }

    suspend fun getPendingSyncEvents(): List<Event> {
        return eventReader.getPendingSyncEvents()
    }

    // ========== Occurrence Generation ==========

    /**
     * Rebuilds event [eventId]'s occurrences, for example after an RRULE change.
     *
     * @throws IllegalArgumentException when the event doesn't exist.
     */
    suspend fun regenerateOccurrences(eventId: Long): Int {
        val event = requireNotNull(eventReader.getEventById(eventId)) {
            "Event not found: $eventId"
        }
        return occurrenceGenerator.regenerateOccurrences(event)
    }

    /** Expands event [eventId]'s occurrences forward to [extendToMs]; returns the count added. */
    suspend fun extendOccurrences(eventId: Long, extendToMs: Long): Int {
        val event = requireNotNull(eventReader.getEventById(eventId)) {
            "Event not found: $eventId"
        }
        return occurrenceGenerator.extendOccurrences(event, extendToMs)
    }

    /**
     * Expands every recurring event whose last occurrence starts before [targetDateMs] plus
     * [bufferMonths] 30-day months; called on month navigation.
     *
     * @return the number of occurrences added across all events.
     */
    suspend fun extendOccurrencesIfNeeded(targetDateMs: Long, bufferMonths: Int = 6): Int {
        val extendToMs = targetDateMs + (bufferMonths * 30L * 24 * 60 * 60 * 1000)
        val eventIds = eventReader.getRecurringEventsNeedingExtension(extendToMs)

        var totalExtended = 0
        for (eventId in eventIds) {
            totalExtended += extendOccurrences(eventId, extendToMs)
        }
        return totalExtended
    }

    /** Expands event [eventId]'s occurrences back to [extendToMs]; returns the count added. */
    suspend fun extendPastOccurrences(eventId: Long, extendToMs: Long): Int {
        val event = requireNotNull(eventReader.getEventById(eventId)) {
            "Event not found: $eventId"
        }
        return occurrenceGenerator.extendPastOccurrences(event, extendToMs)
    }

    /**
     * Expands back every recurring event whose first occurrence starts after both its DTSTART
     * and [targetDateMs] minus [bufferMonths] 30-day months; called on month navigation.
     *
     * @return the number of occurrences added across all events.
     */
    suspend fun extendPastOccurrencesIfNeeded(targetDateMs: Long, bufferMonths: Int = 6): Int {
        val extendToMs = targetDateMs - (bufferMonths * 30L * 24 * 60 * 60 * 1000)
        val eventIds = eventReader.getRecurringEventsNeedingPastExtension(extendToMs)

        var totalExtended = 0
        for (eventId in eventIds) {
            totalExtended += extendPastOccurrences(eventId, extendToMs)
        }
        return totalExtended
    }

    /** Regenerates every recurring event that has no occurrences; returns the count repaired. */
    suspend fun repairMissingOccurrences(): Int {
        val eventIds = eventReader.getRecurringEventsWithNoOccurrences()
        var repaired = 0
        for (eventId in eventIds) {
            try {
                regenerateOccurrences(eventId)
                repaired++
            } catch (_: IllegalArgumentException) {
                // Deleted between the query and the regeneration.
            }
        }
        return repaired
    }

    fun previewOccurrences(
        rrule: String,
        dtstartMs: Long,
        rangeStartMs: Long,
        rangeEndMs: Long
    ): List<Long> {
        return occurrenceGenerator.expandForPreview(
            rrule = rrule,
            dtstartMs = dtstartMs,
            rangeStartMs = rangeStartMs,
            rangeEndMs = rangeEndMs
        )
    }

    // ========== Statistics ==========

    suspend fun getTotalEventCount(): Int {
        return eventReader.getTotalEventCount()
    }

    suspend fun getEventCountForCalendar(calendarId: Long): Int {
        return eventReader.getEventCountForCalendar(calendarId)
    }

    // ========== Sync Lookback ==========

    /**
     * Deletes the SYNCED one-off server events that ended before [cutoffTs] (epoch ms), for a
     * shrunk sync lookback, and returns how many; the rules are on
     * [org.onekash.kashcal.data.db.dao.EventsDao.deleteOutsideLookback].
     */
    suspend fun cleanupEventsOutsideLookback(cutoffTs: Long): Int {
        return eventWriter.cleanupEventsOutsideLookback(cutoffTs)
    }

    // ========== ICS Subscriptions ==========
    //
    // Every mutation below reconciles the periodic refresh schedule, because each can
    // change which feeds are enabled or how often one refreshes. Keep it here, not in the
    // callers: a caller that arms the job only on add never passes a changed interval to
    // WorkManager.

    /** Emits every ICS subscription, again whenever one changes. */
    fun getAllIcsSubscriptions(): Flow<List<IcsSubscription>> {
        return icsSubscriptionRepository.getAllSubscriptions()
    }

    suspend fun getIcsSubscriptionById(subscriptionId: Long): IcsSubscription? {
        return icsSubscriptionRepository.getSubscriptionById(subscriptionId)
    }

    /**
     * Adds a feed subscription through [IcsSubscriptionRepository.addSubscription], which
     * creates its calendar (and the ICS account on the first one) and fetches the feed.
     *
     * The refresh schedule is reconciled only on success.
     *
     * @param url the feed URL; webcal:// and webcals:// are rewritten to https://.
     * @param color calendar color (ARGB).
     */
    suspend fun addIcsSubscription(
        url: String,
        name: String,
        color: Int
    ): IcsSubscriptionRepository.SubscriptionResult {
        val result = icsSubscriptionRepository.addSubscription(url, name, color)
        if (result is IcsSubscriptionRepository.SubscriptionResult.Success) {
            icsRefreshScheduleReconciler.reconcile()
        }
        return result
    }

    /** Deletes a subscription, its calendar and its events, cancelling their reminders first. */
    suspend fun removeIcsSubscription(subscriptionId: Long) {
        icsSubscriptionRepository.removeSubscription(subscriptionId)
        icsRefreshScheduleReconciler.reconcile()
    }

    /** Updates a subscription's name, color and interval, and its calendar's name and color. */
    suspend fun updateIcsSubscriptionSettings(
        subscriptionId: Long,
        name: String,
        color: Int,
        syncIntervalHours: Int
    ) {
        icsSubscriptionRepository.updateSubscriptionSettings(
            subscriptionId, name, color, syncIntervalHours
        )
        icsRefreshScheduleReconciler.reconcile()
    }

    /** Enables a subscription (refreshing it) or disables it (cancelling its reminders). */
    suspend fun setIcsSubscriptionEnabled(subscriptionId: Long, enabled: Boolean) {
        icsSubscriptionRepository.setSubscriptionEnabled(subscriptionId, enabled)
        icsRefreshScheduleReconciler.reconcile()
    }

    /** Fetches one subscription's feed and writes its events; a disabled one is skipped. */
    suspend fun refreshIcsSubscription(
        subscriptionId: Long
    ): IcsSubscriptionRepository.SyncResult {
        return icsSubscriptionRepository.refreshSubscription(subscriptionId)
    }

    /** Refreshes the enabled subscriptions that are due; returns one result per refreshed feed. */
    suspend fun refreshDueIcsSubscriptions(): List<IcsSubscriptionRepository.SyncResult> {
        return icsSubscriptionRepository.refreshAllDueSubscriptions()
    }

    /** Refreshes every enabled subscription, due or not; returns one result per feed. */
    suspend fun forceRefreshAllIcsSubscriptions(): List<IcsSubscriptionRepository.SyncResult> {
        return icsSubscriptionRepository.forceRefreshAll()
    }

    // ========== ICS File Import ==========

    /**
     * Imports events parsed from an ICS file into calendar [calendarId], each group under a
     * fresh UID. For one-shot file imports; subscriptions refresh through
     * [IcsSubscriptionRepository].
     *
     * An event or series that fails to import is logged and skipped.
     *
     * @return the number of events imported, counting a series' master and exceptions.
     * @throws IllegalArgumentException when the calendar doesn't exist.
     */
    suspend fun importIcsEvents(events: List<Event>, calendarId: Long): Int {
        val calendar = requireNotNull(eventReader.getCalendarById(calendarId)) {
            "Calendar not found: $calendarId"
        }

        val isLocal = isLocalCalendar(calendar)
        var importCount = 0

        // An event with no VALARM the parser keeps (reminders null) gets the user's default
        // reminder unless it is off, as a Quick Add create does. Parsed reminders are kept.
        val defaultTimedReminder = dataStore.defaultReminderMinutes.first()
        val defaultAllDayReminder = dataStore.defaultAllDayReminder.first()

        // Group by source UID so a master and its RECURRENCE-ID exceptions (one UID per
        // RFC 5545) import as one linked series. Exceptions have a non-null
        // originalInstanceTime; masters don't. A series is exactly one recurring master plus
        // at least one exception. Any other shape (two same-UID masters from a truncated
        // export, an exception whose master is outside the file) imports each event
        // standalone, so nothing is dropped.
        //
        // This differs on purpose from the subscription path (#227), which synthesizes an
        // inert master for orphan exceptions and disambiguates duplicate-UID masters. A feed
        // must keep the source UID to match rows across refreshes, so colliding UIDs would
        // trip the master-uniqueness trigger and be dropped. File import gives each group a
        // fresh UID, so it doesn't collide. Don't align the two: a CANCELLED placeholder
        // master in a writable calendar would be pushed to the CalDAV server, which is worse
        // than a standalone import.
        events.groupBy { it.uid }.values.forEach { group ->
            val masters = group.filter { it.originalInstanceTime == null }
            val exceptions = group.filter { it.originalInstanceTime != null }
            val isSeries = masters.size == 1 &&
                masters.first().rrule != null &&
                exceptions.isNotEmpty()

            if (isSeries) {
                val master = masters.first()
                try {
                    val masterReminders = master.reminders ?: defaultRemindersFor(
                        isAllDay = master.isAllDay,
                        timedDefault = defaultTimedReminder,
                        allDayDefault = defaultAllDayReminder
                    )
                    val newUid = "${UUID.randomUUID()}@kashcal.onekash.org"
                    val masterToImport = master.asImported(calendarId, newUid, masterReminders)
                    val exceptionsToImport = exceptions.map { exception ->
                        // An exception with no VALARM alarms like its sibling occurrences: it
                        // takes the master's reminders after the default is applied, not the
                        // per-type default.
                        val exceptionReminders = exception.reminders ?: masterReminders
                        // Keep originalInstanceTime: createImportedSeries links the exception
                        // by it. The writer stamps timestamps and syncStatus.
                        exception.copy(
                            id = 0,
                            calendarId = calendarId,
                            uid = newUid,
                            caldavUrl = null,
                            etag = null,
                            lastSyncError = null,
                            syncRetryCount = 0,
                            reminders = exceptionReminders,
                            originalSyncId = null
                        )
                    }

                    val series = eventWriter.createImportedSeries(
                        masterToImport,
                        exceptionsToImport,
                        isLocal
                    )
                    scheduleRemindersForEvent(series.master)
                    series.exceptions.forEach { scheduleRemindersForEvent(it) }
                    importCount += 1 + series.exceptions.size
                    Log.d(TAG, "Imported series: ${master.title} (${series.exceptions.size} exceptions)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to import series: ${master.title}", e)
                }
            } else {
                group.forEach { event ->
                    try {
                        val effectiveReminders = event.reminders ?: defaultRemindersFor(
                            isAllDay = event.isAllDay,
                            timedDefault = defaultTimedReminder,
                            allDayDefault = defaultAllDayReminder
                        )
                        // Standalone: fresh UID, recurrence links cleared. createEvent
                        // stamps timestamps and syncStatus.
                        val importEvent = event.asImported(
                            calendarId,
                            "${UUID.randomUUID()}@kashcal.onekash.org",
                            effectiveReminders
                        )

                        // Handles recurring and one-off events alike.
                        val result = eventWriter.createEvent(importEvent, isLocal)

                        scheduleRemindersForEvent(result)

                        importCount++
                        Log.d(TAG, "Imported event: ${event.title}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to import event: ${event.title}", e)
                    }
                }
            }
        }

        if (importCount > 0 && !isLocal) {
            triggerImmediatePushIfNeeded(isLocal)
        }

        if (importCount > 0) {
            triggerWidgetUpdate()
        }

        Log.d(TAG, "Imported $importCount of ${events.size} events to calendar $calendarId")
        return importCount
    }

    // ========== ICS Export ==========

    /**
     * Returns the exceptions of master [masterEventId], empty for a non-recurring event.
     *
     * ICS export bundles them with the master in one VCALENDAR.
     */
    suspend fun getExceptionsForMaster(masterEventId: Long): List<Event> {
        return eventReader.getExceptionsForMaster(masterEventId)
    }

    /**
     * Returns every master in calendar [calendarId] paired with its exceptions, for ICS
     * export to bundle each series into one VCALENDAR (RFC 5545).
     */
    suspend fun getCalendarEventsForExport(calendarId: Long): List<Pair<Event, List<Event>>> {
        val masterEvents = eventReader.getAllMasterEventsForCalendar(calendarId)
        val recurringIds = masterEvents.filter { it.isRecurring }.map { it.id }
        val exceptionsByMaster = if (recurringIds.isNotEmpty()) {
            eventReader.getExceptionsForMasters(recurringIds)
        } else {
            emptyMap()
        }
        return masterEvents.map { master ->
            Pair(master, exceptionsByMaster[master.id].orEmpty())
        }
    }

    // ========== Contact Birthdays ==========

    suspend fun birthdayCalendarExists(): Boolean {
        return contactBirthdayRepository.calendarExists()
    }

    /**
     * Creates the contact birthdays calendar if missing and returns its id; [color] applies
     * only to a newly created calendar.
     */
    suspend fun enableContactBirthdays(color: Int): Long {
        return contactBirthdayRepository.ensureCalendarExists(color)
    }

    /** Removes the contact birthdays calendar, its events and its account. */
    suspend fun disableContactBirthdays() {
        contactBirthdayRepository.removeCalendar()
        triggerWidgetUpdate()
    }

    /**
     * Syncs the birthdays calendar from the phone's contacts; returns an error result rather
     * than throwing.
     */
    suspend fun syncContactBirthdays(): ContactEventSyncResult {
        val result = contactBirthdayRepository.syncEvents()
        if (result is ContactEventSyncResult.Success) {
            triggerWidgetUpdate()
        }
        return result
    }

    suspend fun updateContactBirthdaysColor(color: Int) {
        contactBirthdayRepository.updateCalendarColor(color)
    }

    suspend fun getContactBirthdaysColor(): Int? {
        return contactBirthdayRepository.getCalendarColor()
    }

    // ========== Contact Anniversaries ==========

    /**
     * Creates the contact anniversaries calendar if missing and returns its id; [color]
     * applies only to a newly created calendar.
     */
    suspend fun enableContactAnniversaries(color: Int): Long {
        return contactAnniversaryRepository.ensureCalendarExists(color)
    }

    /** Removes the contact anniversaries calendar, its events and its account. */
    suspend fun disableContactAnniversaries() {
        contactAnniversaryRepository.removeCalendar()
        triggerWidgetUpdate()
    }

    /**
     * Syncs the anniversaries calendar from the phone's contacts; returns an error result
     * rather than throwing.
     */
    suspend fun syncContactAnniversaries(): ContactEventSyncResult {
        val result = contactAnniversaryRepository.syncEvents()
        if (result is ContactEventSyncResult.Success) {
            triggerWidgetUpdate()
        }
        return result
    }

    suspend fun updateContactAnniversariesColor(color: Int) {
        contactAnniversaryRepository.updateCalendarColor(color)
    }

    suspend fun getContactAnniversariesColor(): Int? {
        return contactAnniversaryRepository.getCalendarColor()
    }

    // ========== RSVP ==========

    /**
     * Writes the user's RSVP for an event they're attending.
     *
     * 1. [EventWriter.replyRsvp] sets the account's attendee row's PARTSTAT, so the chip row
     *    flips at once, and queues a PARTSTAT-only operation that the next sync sends as a
     *    PUT keeping every other ATTENDEE, the ORGANIZER and the rest of the event.
     * 2. Cancels the event's reminders on DECLINED, else re-arms them.
     * 3. Clears the event's invite notification.
     * 4. Requests an expedited sync so the reply reaches the organizer promptly, and
     *    refreshes the widgets.
     *
     * Steps 2 to 4 run only when step 1 matched a row.
     *
     * @param status a PARTSTAT value (RFC 5545 §3.2.12) in any case; the writer upper-cases it.
     * @return true when the account's attendee row was updated. false when the event, its
     *   calendar or its account is missing, or no attendee row matches the account; the
     *   caller should show "you're not on this event's attendee list".
     */
    suspend fun replyRsvp(eventId: Long, status: String): Boolean {
        val event = eventReader.getEventById(eventId) ?: return false
        val calendar = eventReader.getCalendarById(event.calendarId) ?: return false
        val account = accountRepository.getAccountById(calendar.accountId) ?: return false

        val ok = eventWriter.replyRsvp(eventId, account, status)
        if (ok) {
            if (status.trim().uppercase() == "DECLINED") {
                try {
                    reminderScheduler.cancelRemindersForEvent(eventId)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Reminder cancel failed for event $eventId, " +
                        "ReminderRefreshWorker will recover", e)
                }
            } else {
                rescheduleRemindersForEvent(event)
            }

            try {
                inviteNotifier.cancelForEvent(eventId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Invite cancel failed for event $eventId: ${e.message}")
            }
            triggerImmediatePushIfNeeded(isLocal = false)
            triggerWidgetUpdate()
        }
        return ok
    }

    /**
     * Saves the user's reminders on an event they're an attendee of, and re-arms its alarms.
     *
     * Local-only: the per-attendee VALARMs (RFC 5545 §3.6.6) go to the Room row and
     * AlarmManager fires from them. No server PUT is queued; [EventWriter.saveAttendeeReminders]
     * says why.
     *
     * @param reminders signed minutes before the start; converted here to the ISO-8601 durations
     *   the writer stores.
     * @return [Result.success] with the updated [Event], or [Result.failure] when the event
     *   doesn't exist.
     */
    suspend fun saveAttendeeReminders(eventId: Long, reminders: List<Int>): Result<Event> {
        val event = eventReader.getEventById(eventId)
            ?: return Result.failure(IllegalStateException("Event not found: $eventId"))

        // The same encoder the event form and Quick Add use.
        val isoStrings = reminders.map(ContactEventUtils::minutesToIsoDuration)
        eventWriter.saveAttendeeReminders(eventId, isoStrings)

        val updated = event.copy(reminders = isoStrings, alarmCount = isoStrings.size)
        rescheduleRemindersForEvent(updated)
        triggerWidgetUpdate()
        return Result.success(updated)
    }

    // ========== Contact Event Counts ==========

    suspend fun getContactBirthdayEventCount(): Int {
        val calendarId = contactBirthdayRepository.getCalendarId() ?: return 0
        return eventReader.getEventCountForCalendar(calendarId)
    }

    suspend fun getContactAnniversaryEventCount(): Int {
        val calendarId = contactAnniversaryRepository.getCalendarId() ?: return 0
        return eventReader.getEventCountForCalendar(calendarId)
    }

    private fun defaultRemindersFor(
        isAllDay: Boolean,
        timedDefault: Int,
        allDayDefault: Int
    ): List<String>? {
        val minutes = if (isAllDay) allDayDefault else timedDefault
        if (minutes == KashCalDataStore.REMINDER_OFF) return null
        return listOf(ContactEventUtils.minutesToIsoDuration(minutes))
    }

    /**
     * Returns this parsed ICS event re-based onto [calendarId] for import: [newUid], server
     * fields cleared, [reminders] applied and recurrence links dropped, so it enters as a
     * standalone master. The writer ([EventWriter.createEvent] or
     * [EventWriter.createImportedSeries]) stamps timestamps and syncStatus in its transaction.
     */
    private fun Event.asImported(
        calendarId: Long,
        newUid: String,
        reminders: List<String>?
    ): Event = copy(
        id = 0,
        calendarId = calendarId,
        uid = newUid,
        caldavUrl = null,
        etag = null,
        lastSyncError = null,
        syncRetryCount = 0,
        reminders = reminders,
        originalEventId = null,
        originalInstanceTime = null,
        originalSyncId = null
    )

    companion object {
        private const val TAG = "EventCoordinator"
    }
}
