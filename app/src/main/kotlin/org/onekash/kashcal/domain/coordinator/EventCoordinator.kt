package org.onekash.kashcal.domain.coordinator

import org.onekash.kashcal.data.ics.IcsImportFile
import org.onekash.kashcal.data.ics.ImportGroup
import org.onekash.kashcal.data.ics.ImportedComponent
import org.onekash.kashcal.data.ics.ImportedCopy
import org.onekash.kashcal.domain.ics.GroupPlan
import org.onekash.kashcal.domain.ics.IcsImportPlanner
import org.onekash.kashcal.domain.ics.IcsImportResult
import org.onekash.kashcal.domain.ics.IgnoreReason
import org.onekash.kashcal.domain.ics.ImportAction
import org.onekash.kashcal.domain.ics.ImportPlanInput
import org.onekash.kashcal.domain.ics.ImportTarget
import org.onekash.kashcal.domain.ics.StoredComponent
import org.onekash.kashcal.domain.ics.StoredUid
import org.onekash.kashcal.domain.ics.isUpdate
import org.onekash.kashcal.domain.identity.matchesAttendee
import org.onekash.kashcal.util.AddressNormalizer
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarException
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.model.EventExport
import org.onekash.kashcal.domain.model.LocalCalendarSummary
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.reader.EventReader.OccurrenceWithEvent
import org.onekash.kashcal.domain.scheduling.SequenceBumper
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.scheduler.IcsRefreshScheduleReconciler
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.strategy.isPullSyntheticMaster
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
 * offline calendars ([LocalCalendarInitializer]), ICS subscriptions and file import, and the
 * contact birthday and anniversary calendars. Device-calendar events don't come here; they go
 * through `DeviceEventWriter`.
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
    /** Serializes [setCalendarMuted]'s alarm step. */
    private val muteStepMutex = Mutex()

    // ========== Initialization ==========

    /** Creates the built-in local calendar if missing and returns its id; call on app startup. */
    suspend fun ensureLocalCalendarExists(): Long {
        return localCalendarInitializer.ensureLocalCalendarExists()
    }

    suspend fun getLocalCalendarId(): Long {
        return localCalendarInitializer.getLocalCalendarId()
    }

    /** Returns true if [calendar] is local ([LocalCalendarInitializer.isLocalAccount]). */
    suspend fun isLocalCalendar(calendar: Calendar): Boolean {
        return localCalendarInitializer.isLocalCalendar(calendar)
    }

    // ========== Offline Calendars ==========
    //
    // Each returns Result.failure(LocalCalendarException) for a refused change, and a failure
    // for any other error. builtInName is the built-in calendar's translated label (the UI's
    // R.string.calendar_local), which names are checked against.

    /**
     * Adds an offline calendar ([LocalCalendarInitializer.createCalendar]) and refreshes the
     * widgets. Returns its id.
     */
    suspend fun createLocalCalendar(name: String, color: Int, builtInName: String): Result<Long> =
        offlineCalendarChange { localCalendarInitializer.createCalendar(name, color, builtInName) }

    /**
     * Renames an offline calendar other than the built-in
     * ([LocalCalendarInitializer.renameCalendar]) and refreshes the widgets.
     */
    suspend fun renameLocalCalendar(calendarId: Long, name: String, builtInName: String): Result<Unit> =
        offlineCalendarChange { localCalendarInitializer.renameCalendar(calendarId, name, builtInName) }

    /**
     * Recolors an offline calendar, the built-in included
     * ([LocalCalendarInitializer.recolorCalendar]), and refreshes the widgets, which paint the
     * calendar color.
     */
    suspend fun recolorLocalCalendar(calendarId: Long, color: Int): Result<Unit> =
        offlineCalendarChange { localCalendarInitializer.recolorCalendar(calendarId, color) }

    /**
     * Deletes an offline calendar other than the built-in, with its events, and returns how many
     * events it had ([LocalCalendarInitializer.deleteCalendar]). Don't call inside a database
     * transaction.
     *
     * After the refusal checks it deletes the row and then cancels the calendar's alarms
     * ([ReminderScheduler.cancelCalendarAlarmsAfter]), so a failed delete changes nothing. If the
     * stored default was this calendar, the default becomes the built-in; a device default stays.
     * Then the widgets refresh. After the refusal checks the work is not cancelled with its
     * caller, which would otherwise leave alarms armed and the default pointing at a deleted
     * calendar. A failure of the default move or the refresh, after the row is gone, is logged and
     * the delete still succeeds.
     */
    suspend fun deleteLocalCalendar(calendarId: Long): Result<Int> {
        offlineCalendarResult { localCalendarInitializer.checkDeletable(calendarId) }
            .onFailure { return Result.failure(it) }
        return withContext(NonCancellable) {
            offlineCalendarResult {
                reminderScheduler.cancelCalendarAlarmsAfter(calendarId) {
                    localCalendarInitializer.deleteCalendar(calendarId)
                }
            }.onSuccess {
                moveDefaultOffDeletedCalendar(calendarId)
                triggerWidgetUpdate()
            }
        }
    }

    /** Sets the default calendar to the built-in when it is the Room calendar [calendarId]. */
    private suspend fun moveDefaultOffDeletedCalendar(calendarId: Long) {
        try {
            if (dataStore.getDefaultCalendar() == DefaultCalendar.Room(calendarId)) {
                dataStore.setDefaultCalendar(DefaultCalendar.Room(localCalendarInitializer.getLocalCalendarId()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Moving the default off deleted calendar $calendarId failed", e)
        }
    }

    /** Runs [change] and refreshes the widgets if it succeeded ([offlineCalendarResult]). */
    private suspend fun <T> offlineCalendarChange(change: suspend () -> T): Result<T> =
        offlineCalendarResult(change).onSuccess { triggerWidgetUpdate() }

    /**
     * Returns [block]'s outcome as a [Result]; logs a failure other than a refusal
     * ([LocalCalendarException]). Cancellation is rethrown.
     */
    private suspend fun <T> offlineCalendarResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e !is LocalCalendarException) Log.e(TAG, "Offline calendar change failed", e)
        Result.failure(e)
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
                    event.id,
                    ReminderScheduler.OCCURRENCE_LOOKAHEAD_DAYS,
                    ReminderScheduler.STARTED_LOOKBACK_MS
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
     * Brings [event]'s reminders in line with its edit: keeps the ones still valid
     * ([ReminderScheduler.reconcileRemindersForEvent]) and arms the missing ones.
     *
     * Catches failures so the write that called it still succeeds; the daily
     * `ReminderRefreshWorker` re-arms whatever is missing.
     */
    private suspend fun rescheduleRemindersForEvent(event: Event) {
        try {
            reminderScheduler.reconcileRemindersForEvent(event)
            scheduleRemindersForEvent(event)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reschedule reminders for event ${event.id}, " +
                "ReminderRefreshWorker will recover", e)
        }
    }

    // ========== Create Operations ==========

    /**
     * Creates [event] in [calendarId], or in the built-in local calendar when null.
     *
     * @param attendees the invite's attendees; a non-empty list may also set the organizer
     *   ([resolveOrganizer]).
     * @throws IllegalArgumentException when the event ends before it starts, or the calendar
     *   is missing or read-only when the create starts (a sign-out or refresh that removes it
     *   during the write still fails the insert).
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
        // A calendar removed by sign-out or a refresh would otherwise fail the insert on the
        // events.calendar_id foreign key.
        val calendar = requireNotNull(eventReader.getCalendarById(targetCalendarId)) {
            "Calendar not found: $targetCalendarId"
        }

        // Defense in depth: the UI also hides read-only calendars.
        require(!calendar.isReadOnly) {
            "Cannot create event on read-only calendar"
        }

        val eventWithCalendar = resolveOrganizer(
            event.copy(calendarId = targetCalendarId),
            calendar,
            attendees
        )
        val isLocal = isLocalCalendar(calendar)

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

    // ========== Recent changes ==========

    /** Hides the Recent changes rows [ids] (a swipe, or the rows Clear all takes). */
    suspend fun dismissRecentChanges(ids: List<Long>) = eventWriter.dismissRecentChanges(ids)

    /** Shows the Recent changes rows [ids] again (Clear all's undo). */
    suspend fun restoreRecentChanges(ids: List<Long>) = eventWriter.restoreRecentChanges(ids)

    // ========== Read Operations (Delegated to EventReader) ==========

    suspend fun getEventById(eventId: Long): Event? {
        return eventReader.getEventById(eventId)
    }

    fun getAllCalendars(): Flow<List<Calendar>> {
        return eventReader.getAllCalendars()
    }

    /**
     * Emits the offline calendars with their event counts
     * ([EventReader.getLocalCalendarSummaries]).
     */
    fun getLocalCalendarSummaries(
        localizeName: (Calendar) -> String
    ): Flow<List<LocalCalendarSummary>> = eventReader.getLocalCalendarSummaries(localizeName)

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

    /** Emits each calendar's event count by id ([EventReader.getCalendarEventCounts]). */
    fun getCalendarEventCounts(): Flow<Map<Long, Int>> = eventReader.getCalendarEventCounts()

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

    /**
     * Mutes or unmutes [calendarId]'s notifications ([Calendar.isNotificationMuted]).
     *
     * The flag is written first and is the authority; the alarm step after it is best effort.
     * Muting cancels the calendar's armed reminders and keeps their rows
     * ([ReminderScheduler.silenceRemindersForCalendar]); unmuting re-arms the kept rows
     * ([ReminderScheduler.restoreRemindersForCalendar]) and then arms the rest of its next
     * [ReminderScheduler.SCHEDULE_WINDOW_DAYS] days, even if the restore fails. The step follows
     * the flag as read back under [muteStepMutex], not [muted], so taps in quick succession end
     * with alarms that match the last one whatever order the steps run in. Once the flag write
     * returns, neither waiting for the lock nor the step is cancelled with its caller. A failure is
     * logged, not thrown; the window scan and the fire-time check in ReminderAlarmReceiver catch
     * most of what it missed, but a failed restore can lose a reminder the scan can't rebuild
     * (see [ReminderScheduler.silenceRemindersForCalendar]).
     */
    suspend fun setCalendarMuted(calendarId: Long, muted: Boolean) {
        eventReader.setCalendarNotificationMuted(calendarId, muted)
        withContext(NonCancellable) {
            muteStepMutex.withLock {
                try {
                    val calendar = eventReader.getCalendarById(calendarId) ?: return@withLock
                    if (calendar.isNotificationMuted) {
                        reminderScheduler.silenceRemindersForCalendar(calendarId)
                    } else {
                        try {
                            reminderScheduler.restoreRemindersForCalendar(calendarId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Restoring calendar $calendarId's kept reminders failed", e)
                        }
                        reminderScheduler.scheduleUpcomingReminders()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Reminder update after muting calendar $calendarId failed", e)
                }
            }
        }
    }

    fun getOccurrencesInRange(startTs: Long, endTs: Long): Flow<List<Occurrence>> {
        return eventReader.getOccurrencesInRange(startTs, endTs)
    }

    fun getVisibleOccurrencesInRange(startTs: Long, endTs: Long): Flow<List<Occurrence>> {
        return eventReader.getVisibleOccurrencesInRange(startTs, endTs)
    }

    /**
     * Emits the occurrences whose stored codes span [day]; see
     * [EventReader.getOccurrencesForDay].
     */
    fun getOccurrencesForDay(day: Int): Flow<List<Occurrence>> {
        return eventReader.getOccurrencesForDay(day)
    }

    /** Returns the occurrences whose stored codes span [dayCode]; see [EventReader]. */
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

    /** Returns a month's stored start days; see [EventReader.getDaysWithEventsInMonth]. */
    suspend fun getDaysWithEventsInMonth(year: Int, month: Int): Set<Int> {
        return eventReader.getDaysWithEventsInMonth(year, month)
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


    /**
     * Keeps every repeating series expanded ahead of [nowMs], forward only, for the readers that
     * work near today (reminders, Insights, share availability): a series whose rows end within
     * HORIZON_RENEW_MS is extended to [OccurrenceGenerator.EXPANSION_WINDOW_MS] ahead, so a
     * series written in the last year needs no work. The daily reminder refresh and each
     * process start call it.
     */
    suspend fun ensureOccurrenceHorizon(nowMs: Long) {
        occurrenceGenerator.ensureCovered(
            startMs = nowMs,
            endMs = nowMs + HORIZON_RENEW_MS,
            backward = false,
            extendToMs = nowMs + OccurrenceGenerator.EXPANSION_WINDOW_MS,
        )
    }

    /** Regenerates every recurring event that has no occurrences; returns how many got rows. */
    suspend fun repairMissingOccurrences(): Int {
        val eventIds = eventReader.getRecurringEventsWithNoOccurrences()
        var repaired = 0
        for (eventId in eventIds) {
            try {
                // A rule with nothing in the window writes no rows: not a repair, or the month
                // swipe would retry it on every swipe.
                if (regenerateOccurrences(eventId) > 0) repaired++
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

    /**
     * Enables a subscription (refreshing it) or disables it, which only stops it syncing
     * ([IcsSubscriptionRepository.setSubscriptionEnabled]).
     */
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
     * Imports a parsed ICS file into Room calendar [calendarId], applying its iTIP METHOD the way
     * [IcsImportPlanner] decides (RFC 5546): a new UID is created with the file's UID, organizer
     * and guests; a UID the calendar holds is updated, left alone or cancelled; a REPLY or
     * another unsupported method writes nothing. On a synced calendar an update is uploaded: one
     * UPDATE for the series per group ([EventWriter.queueImportedUpdate]), planned inside the
     * group's transaction. For one-shot file imports; subscriptions refresh through
     * [IcsSubscriptionRepository].
     *
     * Creates keep [Event.uid] unless another calendar of the account holds it (a plain event
     * then gets a fresh UID) or the group is malformed (two masters with one UID, or exceptions
     * without a recurring master), which imports each event standalone under a fresh UID. An
     * invitation's VALARMs are the organizer's, so it gets the user's default reminders. A
     * group that fails is counted by its components and skipped.
     *
     * @throws IllegalArgumentException when the calendar doesn't exist.
     */
    suspend fun importIcsFile(file: IcsImportFile, calendarId: Long): IcsImportResult {
        val context = importContext(calendarId)
        var result = IcsImportResult()
        for (group in file.groups) {
            val effects = ReminderEffects()
            result += try {
                // One transaction per group, so a failure leaves none of its components written,
                // and the plan reads the stored event as the writes find it.
                eventWriter.inTransaction {
                    val input = planInput(context, file, group)
                    val actions = IcsImportPlanner.plan(input).actions
                    // On a synced calendar a group that updates the series uploads it once, its
                    // changed and cancelled occurrences inside that PUT; a cancel's own PUT first
                    // would leave the series PUT a stale etag. A cancel-only group keeps its own.
                    val uploadSeries = !context.isLocal && actions.any { it.isUpdate }
                    val sum = actions.fold(IcsImportResult()) { sum, action ->
                        sum + applyImportAction(context, file, action, effects, queueCancel = !uploadSeries)
                    }
                    if (uploadSeries) input.stored?.master?.eventId?.let { eventWriter.queueImportedUpdate(it) }
                    sum
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to import ${group.uid}", e)
                effects.clear()
                IcsImportResult(failed = group.components.size)
            }
            effects.cancelled.forEach { cancelRemindersQuietly(it) }
            effects.cancelledOccurrences.forEach { (masterId, instanceTime) ->
                try {
                    reminderScheduler.cancelReminderForOccurrence(masterId, instanceTime)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Reminder cancel failed for event $masterId", e)
                }
            }
            effects.armed.forEach { rescheduleRemindersForEvent(it) }
        }
        if (result.changedAnything) {
            if (!context.isLocal) triggerImmediatePushIfNeeded(isLocal = false)
            triggerWidgetUpdate()
        }
        Log.d(TAG, "Imported to calendar $calendarId: $result")
        return result
    }

    /**
     * Returns what [importIcsFile] would do with each group of [file] in calendar [calendarId],
     * without writing anything; the import sheet shows it before the tap.
     */
    suspend fun previewIcsImport(file: IcsImportFile, calendarId: Long): List<GroupPlan> {
        val context = importContext(calendarId)
        return file.groups.map { IcsImportPlanner.plan(planInput(context, file, it)) }
    }

    private class ImportContext(
        val calendar: Calendar,
        val isLocal: Boolean,
        val account: Account?,
        val userAddresses: Set<String>,
        val defaultTimedReminder: Int,
        val defaultAllDayReminder: Int,
        val accountCalendarIds: Set<Long>,
    )

    private suspend fun importContext(calendarId: Long): ImportContext {
        val calendar = requireNotNull(eventReader.getCalendarById(calendarId)) { "Calendar not found: $calendarId" }
        val isLocal = isLocalCalendar(calendar)
        val account = accountRepository.getAccountById(calendar.accountId)
        return ImportContext(
            calendar = calendar,
            isLocal = isLocal,
            account = account,
            // A local calendar has no identity: its account's login is not an address.
            userAddresses = if (isLocal) emptySet()
            else account?.effectiveAddresses()?.map(AddressNormalizer::canonical)?.toSet().orEmpty(),
            defaultTimedReminder = dataStore.defaultReminderMinutes.first(),
            defaultAllDayReminder = dataStore.defaultAllDayReminder.first(),
            accountCalendarIds = eventReader.getCalendarsByAccountIdOnce(calendar.accountId).map { it.id }.toSet(),
        )
    }

    private suspend fun planInput(context: ImportContext, file: IcsImportFile, group: ImportGroup): ImportPlanInput {
        val rows = eventReader.getEventsByUid(group.uid)
        val here = rows.filter { it.calendarId == context.calendar.id }
        val master = here.firstOrNull { it.originalEventId == null }
        val stored = StoredUid(
            master = master?.toStoredComponent(),
            exceptions = here.filter { it.originalEventId != null }.map { it.toStoredComponent() },
        ).takeUnless { it.isEmpty }
        val elsewhere = rows.any { it.calendarId != context.calendar.id && it.calendarId in context.accountCalendarIds }
        return ImportPlanInput(
            group = group,
            method = file.method,
            stored = stored,
            target = if (context.isLocal) ImportTarget.LOCAL else ImportTarget.CALDAV,
            userAddresses = context.userAddresses,
            elsewhereInAccount = elsewhere,
            changeWaiting = !context.isLocal && eventReader.hasChangeWaiting(here.map { it.id }),
        )
    }

    private fun Event.toStoredComponent() = StoredComponent(
        eventId = id,
        instanceTime = if (originalEventId != null) originalInstanceTime else null,
        organizerEmail = organizerEmail,
        sequence = sequence,
        itipDtstamp = itipDtstamp,
        status = status,
        pendingDelete = isPendingDelete,
        isPullPlaceholder = isPullSyntheticMaster,
    )

    /** Reminder work for a group's writes, done once its transaction commits. */
    private class ReminderEffects {
        val armed = mutableListOf<Event>()
        val cancelled = mutableListOf<Long>()
        val cancelledOccurrences = mutableListOf<Pair<Long, Long>>()
        fun clear() {
            armed.clear(); cancelled.clear(); cancelledOccurrences.clear()
        }
    }

    private suspend fun applyImportAction(
        context: ImportContext,
        file: IcsImportFile,
        action: ImportAction,
        effects: ReminderEffects,
        queueCancel: Boolean,
    ): IcsImportResult = when (action) {
        is ImportAction.Create -> createImported(context, file, action, effects)
        is ImportAction.UpdateSeries -> importedUpdate(context, action.stored.eventId, action.component, effects)
        is ImportAction.UpdateInstance -> importedUpdate(context, action.stored.eventId, action.component, effects)
        is ImportAction.CreateInstance -> {
            val instanceTime = requireNotNull(action.component.event.originalInstanceTime)
            val master = requireNotNull(eventReader.getEventById(action.master.eventId)) {
                "Event not found: ${action.master.eventId}"
            }
            val created = eventWriter.createImportedException(
                action.master.eventId,
                instanceTime,
                action.component.event,
                updatedAttendeeRows(context, action.component, master.projectOntoOccurrence(instanceTime)),
                keepUserFields = !context.isLocal,
            )
            effects.armed += created
            IcsImportResult(updated = 1, firstStartTs = created.startTs)
        }
        is ImportAction.CancelSeries -> {
            val cancelled = eventWriter.applyImportedCancel(
                action.master.eventId, null, action.component.event.sequence,
                action.component.event.itipDtstamp, context.isLocal, queueCancel
            )
            effects.cancelled += cancelled.map { it.id }
            IcsImportResult(cancelled = 1, firstStartTs = cancelled.firstOrNull()?.startTs)
        }
        is ImportAction.CancelInstance -> {
            val instanceTime = requireNotNull(action.component.event.originalInstanceTime)
            val cancelled = eventWriter.applyImportedCancel(
                action.master.eventId, instanceTime, action.component.event.sequence,
                action.component.event.itipDtstamp, context.isLocal, queueCancel
            )
            effects.cancelled += cancelled.map { it.id }
            effects.cancelledOccurrences += action.master.eventId to instanceTime
            IcsImportResult(cancelled = 1, firstStartTs = cancelled.firstOrNull()?.startTs)
        }
        is ImportAction.UpToDate -> IcsImportResult(upToDate = 1)
        is ImportAction.Ignored -> IcsImportResult(ignored = listOf(action.reason))
    }

    private suspend fun importedUpdate(
        context: ImportContext,
        eventId: Long,
        component: ImportedComponent,
        effects: ReminderEffects,
    ): IcsImportResult {
        val existing = requireNotNull(eventReader.getEventById(eventId)) { "Event not found: $eventId" }
        val updated = eventWriter.applyImportedUpdate(
            eventId, component.event, updatedAttendeeRows(context, component, existing),
            keepUserFields = !context.isLocal,
        )
        effects.armed += updated
        return IcsImportResult(updated = 1, firstStartTs = updated.startTs)
    }

    private suspend fun cancelRemindersQuietly(eventId: Long) {
        try {
            reminderScheduler.cancelRemindersForEvent(eventId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Reminder cancel failed for event $eventId, ReminderRefreshWorker will recover", e)
        }
    }

    /**
     * Creates [action]'s group. A group with one master (and, for a series, its exceptions)
     * keeps its UID unless [ImportAction.Create.freshUid]. Any other shape (two same-UID masters
     * from a truncated export, an exception whose master is outside the file) imports each event
     * standalone under a fresh UID, cancelled ones left out, so no two masters share a UID.
     *
     * This differs on purpose from the subscription path (#227), which synthesizes an inert
     * master for orphan exceptions: a cancelled placeholder master in a writable calendar would
     * be pushed to the CalDAV server, which is worse than a standalone import.
     */
    private suspend fun createImported(
        context: ImportContext,
        file: IcsImportFile,
        action: ImportAction.Create,
        effects: ReminderEffects,
    ): IcsImportResult {
        val group = action.group
        val calendarId = context.calendar.id
        val master = group.masters.singleOrNull()
        val linked = master != null && (group.exceptions.isEmpty() || master.event.rrule != null)
        fun remindersFor(c: ImportedComponent): List<String>? =
            if (file.isInvitation || c.event.reminders == null) {
                defaultRemindersFor(c.event.isAllDay, context.defaultTimedReminder, context.defaultAllDayReminder)
            } else {
                c.event.reminders
            }

        if (linked && master != null) {
            val uid = if (action.freshUid) "${UUID.randomUUID()}@kashcal.onekash.org" else group.uid
            // The group's copy names the file's UID; a fresh UID is written into it too, so the
            // copy's changed occurrences still match the series.
            val copy = master.event.rawIcal?.let { if (action.freshUid) ImportedCopy.withUid(it, group.uid, uid) else it }
            val masterReminders = remindersFor(master)
            val masterEvent = master.event.asImported(calendarId, uid, masterReminders).copy(
                importSource = Event.IMPORT_SOURCE_ICS_FILE,
                alarmCount = masterReminders?.size ?: 0,
                rawIcal = copy,
            )
            if (group.exceptions.isEmpty()) {
                val created = eventWriter.createEvent(masterEvent, context.isLocal, attendeeRows(context, master))
                effects.armed += created
                return IcsImportResult(created = 1, firstStartTs = created.startTs)
            }
            val exceptions = group.exceptions.map { exception ->
                // Keep originalInstanceTime: createImportedSeries links the exception by it.
                val reminders = if (file.isInvitation) masterReminders else exception.event.reminders ?: masterReminders
                exception.event.copy(
                    id = 0, calendarId = calendarId, uid = uid, caldavUrl = null, etag = null,
                    lastSyncError = null, syncRetryCount = 0, reminders = reminders,
                    alarmCount = reminders?.size ?: 0, originalSyncId = null,
                    importSource = Event.IMPORT_SOURCE_ICS_FILE, rawIcal = copy,
                )
            }
            val series = eventWriter.createImportedSeries(
                masterEvent, exceptions, context.isLocal,
                masterAttendees = attendeeRows(context, master),
                exceptionAttendees = group.exceptions.map { attendeeRows(context, it) },
            )
            effects.armed += series.master
            effects.armed += series.exceptions
            return IcsImportResult(created = 1 + series.exceptions.size, firstStartTs = series.master.startTs)
        }

        // Malformed: each event standalone under a fresh UID; a cancelled one has nothing to
        // stand for on its own. No stored copy: patched from it, a standalone row would keep a
        // changed occurrence's RECURRENCE-ID, or share another series' VEVENT.
        var result = IcsImportResult()
        for (component in group.components.filterNot { it.isCancelled }) {
            val reminders = remindersFor(component)
            val event = component.event.asImported(calendarId, "${UUID.randomUUID()}@kashcal.onekash.org", reminders)
                .copy(importSource = Event.IMPORT_SOURCE_ICS_FILE, alarmCount = reminders?.size ?: 0, rawIcal = null)
            val created = eventWriter.createEvent(event, context.isLocal, attendeeRows(context, component))
            effects.armed += created
            result += IcsImportResult(created = 1, firstStartTs = created.startTs)
        }
        return result
    }

    /**
     * Returns [component]'s attendee rows for a write over [before] on a synced calendar: the
     * stored row it rewrites, or for a new changed occurrence the series projected onto it. The
     * user's own row takes NEEDS-ACTION when the write reschedules ([SequenceBumper.reschedules],
     * RFC 6638 §3.2.8), else the user's answer stored on [before], else the file's; other guests
     * come from the file. An offline calendar takes the file's rows as they are. DURATION is left
     * out of the comparison: start and end already carry a change of length, and a series row
     * stores a DURATION an occurrence change doesn't.
     */
    private suspend fun updatedAttendeeRows(
        context: ImportContext,
        component: ImportedComponent,
        before: Event,
    ): List<Attendee> {
        val rows = attendeeRows(context, component)
        if (context.isLocal) return rows
        val account = context.account ?: return rows
        if (rows.none { account.matchesAttendee(it.address) }) return rows
        val partstat = if (SequenceBumper.reschedules(before.copy(duration = null), component.event.copy(duration = null))) {
            "NEEDS-ACTION"
        } else {
            eventReader.getAttendeesForEventsOnce(listOf(before.id))[before.id].orEmpty()
                .firstOrNull { account.matchesAttendee(it.address) }?.partstat ?: return rows
        }
        return rows.map { if (account.matchesAttendee(it.address)) it.copy(partstat = partstat) else it }
    }

    /**
     * Returns [component]'s attendee rows, the user's own marked as already notified: the user
     * imported the invitation, so nothing should post an "invited" notification for it.
     */
    private fun attendeeRows(context: ImportContext, component: ImportedComponent): List<Attendee> {
        val now = System.currentTimeMillis()
        return component.attendees.mapIndexed { index, a ->
            Attendee(
                eventId = 0,
                address = a.address,
                displayName = a.displayName,
                role = a.role,
                partstat = a.partstat,
                cutype = a.cutype,
                rsvp = a.rsvp,
                delegatedFrom = a.delegatedFrom,
                delegatedTo = a.delegatedTo,
                member = a.member,
                sentBy = a.sentBy,
                sortOrder = index,
                notifiedAt = if (context.account?.matchesAttendee(a.address) == true) now else null,
            )
        }
    }

    // ========== ICS Export ==========

    /**
     * Returns event [eventId] as stored, with its exceptions when it is a recurring master and
     * the attendee rows of each, for a single-event export; null when it doesn't exist. An event
     * shown from an occurrence query lacks its stored copy ([Event.rawIcal]), which the export
     * needs, so the export reads it here by id.
     */
    suspend fun getEventForExport(eventId: Long): EventExport? {
        val (event, exceptions) = eventReader.getEventWithExceptions(eventId) ?: return null
        val attendees = eventReader.getAttendeesForEventsOnce(listOf(event.id) + exceptions.map { it.id })
        return EventExport(event, exceptions, attendees)
    }

    /**
     * Returns every master in calendar [calendarId] with its exceptions and the attendee rows of
     * each, for ICS export to bundle each series into one VCALENDAR (RFC 5545).
     */
    suspend fun getCalendarEventsForExport(calendarId: Long): List<EventExport> {
        val masterEvents = eventReader.getAllMasterEventsForCalendar(calendarId)
        val recurringIds = masterEvents.filter { it.isRecurring }.map { it.id }
        val exceptionsByMaster = if (recurringIds.isNotEmpty()) {
            eventReader.getExceptionsForMasters(recurringIds)
        } else {
            emptyMap()
        }
        val eventIds = masterEvents.map { it.id } + exceptionsByMaster.values.flatten().map { it.id }
        val attendees = eventReader.getAttendeesForEventsOnce(eventIds)
        return masterEvents.map { master ->
            EventExport(master, exceptionsByMaster[master.id].orEmpty(), attendees)
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

        /** How close to running out a series' rows may get before the daily band extends them. */
        private const val HORIZON_RENEW_MS = 365L * 24 * 60 * 60 * 1000
    }
}
