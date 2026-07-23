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
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.widget.WidgetUpdateManager
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The central coordinator for all event operations.
 *
 * This is THE entry point for ViewModels. All event operations should go through
 * EventCoordinator, never directly to DAOs.
 *
 * Coordinates:
 * - EventWriter: Create, update, delete events
 * - EventReader: Query events and occurrences
 * - OccurrenceGenerator: RRULE expansion
 * - LocalCalendarInitializer: Ensure local calendar exists
 *
 * Architecture:
 * ```
 * UI Layer (ViewModels)
 *         ↓
 * EventCoordinator ← THE single entry point
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
    private val dataStore: KashCalDataStore
) {
    // ========== Initialization ==========

    /**
     * Ensure local calendar exists.
     * Should be called on app startup.
     *
     * @return The local calendar ID
     */
    suspend fun ensureLocalCalendarExists(): Long {
        return localCalendarInitializer.ensureLocalCalendarExists()
    }

    /**
     * Get the local calendar ID.
     */
    suspend fun getLocalCalendarId(): Long {
        return localCalendarInitializer.getLocalCalendarId()
    }

    /**
     * Check if a calendar is the local calendar.
     */
    fun isLocalCalendar(calendar: Calendar): Boolean {
        return localCalendarInitializer.isLocalCalendar(calendar)
    }

    // ========== Sync Trigger ==========

    /**
     * Trigger expedited sync after local changes (non-blocking).
     * Only triggers for non-local calendars that need server sync.
     *
     * Following Android WorkManager best practices:
     * - Uses setExpedited() for user-initiated, time-sensitive tasks
     * - Falls back to regular work if quota exceeded (OutOfQuotaPolicy)
     * - Network constraint ensures no wasted attempts when offline
     * - ExistingWorkPolicy.REPLACE coalesces rapid consecutive changes
     *
     * This is the industry standard pattern used by major calendar applications.
     */
    private fun triggerImmediatePushIfNeeded(isLocal: Boolean) {
        if (!isLocal) {
            // Non-blocking: WorkManager handles scheduling and constraints
            syncScheduler.requestExpeditedSync(forceFullSync = false)
        }
    }

    // ========== Widget Updates ==========

    /**
     * Update home screen widgets after event changes.
     * Non-blocking - launches coroutine for widget update.
     */
    private suspend fun triggerWidgetUpdate() {
        widgetUpdateManager.updateAllWidgets()
    }

    // ========== Reminder Scheduling ==========

    /**
     * Cancel all reminders for an account's calendars.
     * Call BEFORE cascade-deleting account to prevent orphaned AlarmManager alarms.
     *
     * @param accountEmail The account email (e.g., Apple ID for iCloud)
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
     * Cancel all reminders for a CalDAV account's calendars.
     * Call BEFORE cascade-deleting account to prevent orphaned AlarmManager alarms.
     *
     * @param accountId The CalDAV account ID
     */
    suspend fun cancelRemindersForCalDavAccount(accountId: Long) {
        val calendars = eventReader.getCalendarsByAccountIdOnce(accountId)
        for (calendar in calendars) {
            cancelRemindersForCalendar(calendar.id)
        }
        Log.i(TAG, "Cancelled reminders for CalDAV account: $accountId")
    }

    /**
     * Cancel all reminders for a calendar's events.
     * Used when deleting a calendar or account.
     *
     * @param calendarId The calendar ID
     */
    private suspend fun cancelRemindersForCalendar(calendarId: Long) {
        reminderScheduler.cancelRemindersForCalendar(calendarId)
    }

    /**
     * Schedule reminders for an event.
     * Gets occurrences and schedules alarms for each reminder offset.
     *
     * For exception events, gets the linked occurrence directly since
     * occurrences are keyed by master event ID, not exception event ID.
     */
    private suspend fun scheduleRemindersForEvent(event: Event) {
        if (event.reminders.isNullOrEmpty()) return

        val calendar = eventReader.getCalendarById(event.calendarId) ?: return

        // For exception events, get the linked occurrence directly
        // (occurrences use master event ID, not exception event ID)
        val occurrences = if (event.originalEventId != null) {
            // Exception event - get the linked occurrence
            listOfNotNull(eventReader.getOccurrenceByExceptionEventId(event.id))
        } else {
            // Regular/master event - get all occurrences in schedule window
            eventReader.getOccurrencesForEventInScheduleWindow(event.id)
        }

        reminderScheduler.scheduleRemindersForEvent(
            event = event,
            occurrences = occurrences,
            calendarColor = calendar.color
        )
    }

    /**
     * Cancel and reschedule reminders for an event.
     * Called after event update or calendar move.
     *
     * Catches exceptions so the parent operation (updateEvent, moveEventToCalendar)
     * succeeds even if reminder scheduling fails. ReminderRefreshWorker provides
     * eventual recovery (daily scan recreates missing reminders).
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
     * Create a new event.
     *
     * @param event The event to create
     * @param calendarId The calendar to create in (uses local if not specified)
     * @return The created event
     */
    suspend fun createEvent(
        event: Event,
        calendarId: Long? = null,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee>? = null
    ): Event {
        // Validate time range
        require(event.endTs >= event.startTs) {
            "End time (${event.endTs}) must be >= start time (${event.startTs})"
        }

        val targetCalendarId = calendarId ?: getLocalCalendarId()
        val calendar = eventReader.getCalendarById(targetCalendarId)

        // Validate calendar is writable (defense-in-depth - UI also filters read-only calendars)
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

        // Schedule reminders for the new event
        scheduleRemindersForEvent(result)

        // Update home screen widgets
        triggerWidgetUpdate()

        return result
    }

    /**
     * Set the organizer on a locally authored event that carries attendees.
     *
     * The organizer is the account's own preferred calendar-user-address
     * (RFC 6638 §2.4.1) — a mailto:, urn:uuid:, or principal path, stored
     * verbatim. We only set it when attendees are present and the event
     * doesn't already name an organizer (e.g. one preserved from a prior
     * pull). When the account has no usable address the organizer is left
     * null rather than synthesized — emitting a bogus organizer would make
     * the server misroute scheduling.
     *
     * No-op when [attendees] is null/empty so non-scheduling edits and
     * existing callers are untouched. Resolving the organizer does not
     * change SEQUENCE (the bump predicate ignores organizer).
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
        // Prefer an email-shaped address (discovery hoists a mailto to index 0
        // when present). Store it BARE — the pull side stores organizer/attendee
        // addresses bare and the generator re-prepends "mailto:" on emit, so a
        // verbatim mailto: would double-prefix into "ORGANIZER:mailto:mailto:…".
        // If no address is email-shaped (e.g. a principal-path / urn:uuid-only
        // account), leave the organizer null: the generator would mangle a
        // non-mailto ORGANIZER, and such an account isn't mailto-schedulable.
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
     * Create a recurring event.
     *
     * @param event The event with RRULE set
     * @param calendarId The calendar to create in
     * @param attendees Optional attendee set; forwarded to [createEvent] so a
     *   recurring invite persists its attendees + resolves the organizer like
     *   any other event. null leaves the attendee table untouched.
     * @return The created event
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
     * Update an event.
     *
     * @param event The event with updated fields
     * @return The updated event
     */
    suspend fun updateEvent(
        event: Event,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee>? = null
    ): Event {
        // Validate time range
        require(event.endTs >= event.startTs) {
            "End time (${event.endTs}) must be >= start time (${event.startTs})"
        }

        val calendar = eventReader.getCalendarById(event.calendarId)
        val isLocal = calendar?.let { isLocalCalendar(it) } ?: false
        val eventToWrite = resolveOrganizer(event, calendar, attendees)
        val result = eventWriter.updateEvent(eventToWrite, isLocal, attendees)
        triggerImmediatePushIfNeeded(isLocal)

        // Reschedule reminders after update (time/reminders may have changed)
        rescheduleRemindersForEvent(result)

        // Update home screen widgets
        triggerWidgetUpdate()

        return result
    }

    /**
     * Edit a single occurrence of a recurring event.
     * Creates an exception event for the modified occurrence.
     *
     * @param masterEventId The master recurring event ID
     * @param occurrenceTimeMs The occurrence start time to modify
     * @param changes A lambda to apply changes to the occurrence
     * @return The created exception event
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

        // Apply changes to create exception. Seed from the occurrence
        // projection (master shifted onto this instance, recurrence cleared)
        // so the lambda edits a single-instance skeleton, not a series.
        val modifiedEvent = changes(masterEvent.projectOntoOccurrence(occurrenceTimeMs))

        val result = eventWriter.editSingleOccurrence(
            masterEventId = masterEventId,
            occurrenceTimeMs = occurrenceTimeMs,
            modifiedEvent = modifiedEvent,
            isLocal = isLocal,
            attendees = attendees
        )
        triggerImmediatePushIfNeeded(isLocal)

        // Cancel reminders for the ORIGINAL occurrence (being replaced by exception)
        // Must cancel before scheduling new to prevent duplicate reminders
        reminderScheduler.cancelReminderForOccurrence(masterEventId, occurrenceTimeMs)

        // Schedule reminders for the newly created exception event
        scheduleRemindersForEvent(result)

        // Update home screen widgets
        triggerWidgetUpdate()

        return result
    }

    /**
     * Edit "this and all future" occurrences.
     * Splits the series at the given occurrence.
     *
     * @param masterEventId The master recurring event ID
     * @param splitTimeMs The occurrence time to split from
     * @param changes A lambda to apply changes to the new series
     * @return The new event for future occurrences
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

        // Master-side reminders for occurrences at/after splitTimeMs are
        // now stale (those occurrences live on the new series). Cancel
        // AFTER splitSeries succeeds — splitSeries is fully transactional,
        // so a failure rolls back and the master's reminders should
        // still fire. (deleteThisAndFuture cancels before its writer call,
        // but that's safe because deletion is the user's intent there.)
        reminderScheduler.cancelRemindersForOccurrencesAfter(masterEventId, splitTimeMs)

        // Schedule reminders for the new series
        scheduleRemindersForEvent(result)

        // Update home screen widgets
        triggerWidgetUpdate()

        return result
    }

    // ========== Delete Operations ==========

    /**
     * Delete an event.
     *
     * @param eventId The event ID to delete
     * @throws IllegalArgumentException if trying to delete an exception event directly
     */
    suspend fun deleteEvent(eventId: Long) {
        val event = requireNotNull(eventReader.getEventById(eventId)) {
            "Event not found: $eventId"
        }

        // Prevent deleting exception events directly - would create orphan data
        // Use deleteSingleOccurrence() to properly add EXDATE and clean up
        require(event.originalEventId == null) {
            "Cannot delete exception event directly. Use deleteSingleOccurrence() to delete the occurrence."
        }

        val calendar = eventReader.getCalendarById(event.calendarId)
        val isLocal = calendar?.let { isLocalCalendar(it) } ?: false

        // Cancel reminders before deleting event
        reminderScheduler.cancelRemindersForEvent(eventId)

        eventWriter.deleteEvent(eventId, isLocal)
        triggerImmediatePushIfNeeded(isLocal)

        // Update home screen widgets
        triggerWidgetUpdate()
    }

    /**
     * Delete a single occurrence of a recurring event.
     * Adds EXDATE to the master event.
     *
     * @param masterEventId The master recurring event ID
     * @param occurrenceTimeMs The occurrence start time to delete
     */
    suspend fun deleteSingleOccurrence(masterEventId: Long, occurrenceTimeMs: Long) {
        val masterEvent = requireNotNull(eventReader.getEventById(masterEventId)) {
            "Master event not found: $masterEventId"
        }

        val calendar = eventReader.getCalendarById(masterEvent.calendarId)
        val isLocal = calendar?.let { isLocalCalendar(it) } ?: false

        // Cancel reminders for this specific occurrence
        reminderScheduler.cancelReminderForOccurrence(masterEventId, occurrenceTimeMs)

        eventWriter.deleteSingleOccurrence(masterEventId, occurrenceTimeMs, isLocal)
        triggerImmediatePushIfNeeded(isLocal)

        // Update home screen widgets
        triggerWidgetUpdate()
    }

    /**
     * Delete "this and all future" occurrences.
     * Truncates the series at the given occurrence.
     *
     * @param masterEventId The master recurring event ID
     * @param fromTimeMs Delete occurrences from this time onwards
     */
    suspend fun deleteThisAndFuture(masterEventId: Long, fromTimeMs: Long) {
        val masterEvent = requireNotNull(eventReader.getEventById(masterEventId)) {
            "Master event not found: $masterEventId"
        }

        val calendar = eventReader.getCalendarById(masterEvent.calendarId)
        val isLocal = calendar?.let { isLocalCalendar(it) } ?: false

        // Cancel reminders for deleted future occurrences
        reminderScheduler.cancelRemindersForOccurrencesAfter(masterEventId, fromTimeMs)

        eventWriter.deleteThisAndFuture(masterEventId, fromTimeMs, isLocal)
        triggerImmediatePushIfNeeded(isLocal)

        // Update home screen widgets
        triggerWidgetUpdate()
    }

    // ========== Move Operations ==========

    /**
     * Move event to a different calendar.
     *
     * @param eventId The event to move
     * @param newCalendarId The destination calendar
     * @throws IllegalArgumentException if trying to move an exception event
     */
    suspend fun moveEventToCalendar(eventId: Long, newCalendarId: Long) {
        // EventWriter now handles all validation and account detection
        eventWriter.moveEventToCalendar(eventId, newCalendarId)

        // Determine if target is local for immediate push decision
        val calendar = eventReader.getCalendarById(newCalendarId)
        val isLocal = calendar?.let { isLocalCalendar(it) } ?: false
        triggerImmediatePushIfNeeded(isLocal)

        // Reschedule reminders with new calendar color
        // Calendar color is used for notification icon tint
        val movedEvent = eventReader.getEventById(eventId)
        if (movedEvent != null) {
            rescheduleRemindersForEvent(movedEvent)
        }

        // Update home screen widgets
        triggerWidgetUpdate()
    }

    // ========== Read Operations (Delegated to EventReader) ==========

    /**
     * Get event by ID.
     */
    suspend fun getEventById(eventId: Long): Event? {
        return eventReader.getEventById(eventId)
    }

    /**
     * Get all calendars.
     */
    fun getAllCalendars(): Flow<List<Calendar>> {
        return eventReader.getAllCalendars()
    }

    /**
     * Get iCloud calendar count.
     * Returns Flow that updates when calendars change.
     */
    fun getICloudCalendarCount(): Flow<Int> {
        return eventReader.getICloudCalendarCount()
    }

    /**
     * Get CalDAV account count.
     * Returns Flow that updates when accounts change.
     */
    fun getCalDavAccountCount(): Flow<Int> {
        return accountRepository.getAccountCountByProviderFlow(AccountProvider.CALDAV)
    }

    /**
     * Get CalDAV accounts as Flow.
     * Returns accounts with provider = CALDAV.
     */
    fun getCalDavAccounts(): Flow<List<Account>> {
        return accountRepository.getAccountsByProviderFlow(AccountProvider.CALDAV)
    }

    /**
     * Get all accounts as Flow.
     * Used for grouping calendars by account in UI.
     */
    fun getAllAccounts(): Flow<List<Account>> {
        return accountRepository.getAllAccountsFlow()
    }

    /**
     * Get calendar count for an account.
     */
    suspend fun getCalendarCountForAccount(accountId: Long): Int {
        return eventReader.getCalendarCountForAccount(accountId)
    }

    /**
     * Get visible calendars.
     */
    fun getVisibleCalendars(): Flow<List<Calendar>> {
        return eventReader.getVisibleCalendars()
    }

    /**
     * Get calendar by ID.
     */
    suspend fun getCalendarById(calendarId: Long): Calendar? {
        return eventReader.getCalendarById(calendarId)
    }

    /**
     * Set calendar visibility.
     * This is the source of truth for which calendars are visible.
     */
    suspend fun setCalendarVisibility(calendarId: Long, visible: Boolean) {
        eventReader.setCalendarVisibility(calendarId, visible)
    }

    /**
     * Get occurrences in date range.
     */
    fun getOccurrencesInRange(startTs: Long, endTs: Long): Flow<List<Occurrence>> {
        return eventReader.getOccurrencesInRange(startTs, endTs)
    }

    /**
     * Get visible occurrences in date range.
     */
    fun getVisibleOccurrencesInRange(startTs: Long, endTs: Long): Flow<List<Occurrence>> {
        return eventReader.getVisibleOccurrencesInRange(startTs, endTs)
    }

    /**
     * Get occurrences for day (YYYYMMDD).
     */
    fun getOccurrencesForDay(day: Int): Flow<List<Occurrence>> {
        return eventReader.getOccurrencesForDay(day)
    }

    /**
     * Get events for day with full details.
     */
    suspend fun getEventsForDay(dayCode: Int): List<OccurrenceWithEvent> {
        return eventReader.getEventsForDay(dayCode)
    }

    /**
     * Get occurrences with event details in range.
     */
    suspend fun getOccurrencesWithEventsInRange(
        startTs: Long,
        endTs: Long
    ): List<OccurrenceWithEvent> {
        return eventReader.getOccurrencesWithEventsInRange(startTs, endTs)
    }

    /**
     * Get occurrences with full event details for date range (reactive Flow).
     *
     * Returns a Flow that automatically emits updates when occurrences change.
     * Used for progressive UI updates during sync.
     */
    fun getOccurrencesWithEventsInRangeFlow(
        startTs: Long,
        endTs: Long
    ): Flow<List<OccurrenceWithEvent>> {
        return eventReader.getOccurrencesWithEventsInRangeFlow(startTs, endTs)
    }

    /**
     * Get single occurrence with event details.
     */
    suspend fun getOccurrenceWithEvent(
        eventId: Long,
        occurrenceTimeMs: Long
    ): OccurrenceWithEvent? {
        return eventReader.getOccurrenceWithEvent(eventId, occurrenceTimeMs)
    }

    /**
     * Get days with events in a month.
     */
    suspend fun getDaysWithEventsInMonth(year: Int, month: Int): Set<Int> {
        return eventReader.getDaysWithEventsInMonth(year, month)
    }

    /**
     * Search events.
     */
    suspend fun searchEvents(query: String): List<Event> {
        return eventReader.searchEvents(query)
    }

    /**
     * Search events with occurrences.
     */
    suspend fun searchEventsWithOccurrences(
        query: String,
        futureOnly: Boolean = true
    ): List<OccurrenceWithEvent> {
        return eventReader.searchEventsWithOccurrences(query, futureOnly)
    }

    /**
     * Get pending operation count.
     */
    fun getPendingOperationCount(): Flow<Int> {
        return eventReader.getPendingOperationCount()
    }

    /**
     * Get events pending sync.
     */
    suspend fun getPendingSyncEvents(): List<Event> {
        return eventReader.getPendingSyncEvents()
    }

    // ========== Occurrence Generation ==========

    /**
     * Regenerate occurrences for an event.
     * Use after RRULE changes or to extend range.
     */
    suspend fun regenerateOccurrences(eventId: Long): Int {
        val event = requireNotNull(eventReader.getEventById(eventId)) {
            "Event not found: $eventId"
        }
        return occurrenceGenerator.regenerateOccurrences(event)
    }

    /**
     * Extend occurrences into the future.
     * Called when user scrolls far into future.
     */
    suspend fun extendOccurrences(eventId: Long, extendToMs: Long): Int {
        val event = requireNotNull(eventReader.getEventById(eventId)) {
            "Event not found: $eventId"
        }
        return occurrenceGenerator.extendOccurrences(event, extendToMs)
    }

    /**
     * Extend occurrences for all recurring events that need it.
     * Called when user navigates far into the future.
     *
     * @param targetDateMs The date user is navigating to
     * @param bufferMonths How far beyond target to extend (default 6)
     * @return Number of events that were extended
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

    /**
     * Extend occurrences into the past.
     * Called when user scrolls far into the past.
     */
    suspend fun extendPastOccurrences(eventId: Long, extendToMs: Long): Int {
        val event = requireNotNull(eventReader.getEventById(eventId)) {
            "Event not found: $eventId"
        }
        return occurrenceGenerator.extendPastOccurrences(event, extendToMs)
    }

    /**
     * Extend past occurrences for all recurring events that need it.
     * Called when user navigates far into the past.
     *
     * @param targetDateMs The date user is navigating to
     * @param bufferMonths How far beyond target to extend (default 6)
     * @return Number of events that were extended
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

    /**
     * Find recurring events with zero materialized occurrences and regenerate them.
     * @return Number of events repaired
     */
    suspend fun repairMissingOccurrences(): Int {
        val eventIds = eventReader.getRecurringEventsWithNoOccurrences()
        var repaired = 0
        for (eventId in eventIds) {
            try {
                regenerateOccurrences(eventId)
                repaired++
            } catch (_: IllegalArgumentException) {
                // Event deleted between query and regeneration
            }
        }
        return repaired
    }

    /**
     * Preview RRULE expansion without storing.
     */
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

    /**
     * Get total event count.
     */
    suspend fun getTotalEventCount(): Int {
        return eventReader.getTotalEventCount()
    }

    /**
     * Get event count for calendar.
     */
    suspend fun getEventCountForCalendar(calendarId: Long): Int {
        return eventReader.getEventCountForCalendar(calendarId)
    }

    // ========== ICS Subscriptions ==========

    /**
     * Get all ICS subscriptions as reactive Flow.
     * Emits new list when subscriptions change.
     */
    fun getAllIcsSubscriptions(): Flow<List<IcsSubscription>> {
        return icsSubscriptionRepository.getAllSubscriptions()
    }

    /**
     * Get ICS subscription by ID.
     */
    suspend fun getIcsSubscriptionById(subscriptionId: Long): IcsSubscription? {
        return icsSubscriptionRepository.getSubscriptionById(subscriptionId)
    }

    /**
     * Add a new ICS subscription.
     *
     * Creates an ICS account and calendar automatically on first subscription.
     * Fetches and parses the ICS feed to populate events.
     *
     * @param url The ICS feed URL (supports webcal:// and https://)
     * @param name Display name for the subscription
     * @param color Calendar color (ARGB integer)
     * @return Result containing the subscription or error message
     */
    suspend fun addIcsSubscription(
        url: String,
        name: String,
        color: Int
    ): IcsSubscriptionRepository.SubscriptionResult {
        return icsSubscriptionRepository.addSubscription(url, name, color)
    }

    /**
     * Remove an ICS subscription.
     *
     * Deletes the subscription, its calendar, and all associated events.
     */
    suspend fun removeIcsSubscription(subscriptionId: Long) {
        icsSubscriptionRepository.removeSubscription(subscriptionId)
    }

    /**
     * Update ICS subscription settings.
     *
     * @param subscriptionId The subscription to update
     * @param name New display name
     * @param color New calendar color
     * @param syncIntervalHours New sync interval in hours
     */
    suspend fun updateIcsSubscriptionSettings(
        subscriptionId: Long,
        name: String,
        color: Int,
        syncIntervalHours: Int
    ) {
        icsSubscriptionRepository.updateSubscriptionSettings(
            subscriptionId, name, color, syncIntervalHours
        )
    }

    /**
     * Enable or disable an ICS subscription.
     */
    suspend fun setIcsSubscriptionEnabled(subscriptionId: Long, enabled: Boolean) {
        icsSubscriptionRepository.setSubscriptionEnabled(subscriptionId, enabled)
    }

    /**
     * Refresh a single ICS subscription.
     *
     * Fetches the ICS feed and updates events in the database.
     *
     * @param subscriptionId The subscription to refresh
     * @return Sync result with counts or error
     */
    suspend fun refreshIcsSubscription(
        subscriptionId: Long
    ): IcsSubscriptionRepository.SyncResult {
        return icsSubscriptionRepository.refreshSubscription(subscriptionId)
    }

    /**
     * Refresh all ICS subscriptions that are due for sync.
     *
     * @return List of sync results for each subscription
     */
    suspend fun refreshDueIcsSubscriptions(): List<IcsSubscriptionRepository.SyncResult> {
        return icsSubscriptionRepository.refreshAllDueSubscriptions()
    }

    /**
     * Force refresh all enabled ICS subscriptions.
     *
     * @return List of sync results for each subscription
     */
    suspend fun forceRefreshAllIcsSubscriptions(): List<IcsSubscriptionRepository.SyncResult> {
        return icsSubscriptionRepository.forceRefreshAll()
    }

    // ========== ICS File Import ==========

    /**
     * Import events from parsed ICS content into a calendar.
     *
     * Used for one-time file imports (not subscriptions).
     * Creates new events with fresh UIDs and appropriate sync status.
     *
     * @param events Parsed events from ICS file
     * @param calendarId Target calendar for import
     * @return Number of events successfully imported
     */
    suspend fun importIcsEvents(events: List<Event>, calendarId: Long): Int {
        val calendar = requireNotNull(eventReader.getCalendarById(calendarId)) {
            "Calendar not found: $calendarId"
        }

        val isLocal = isLocalCalendar(calendar)
        var importCount = 0

        // Apply the user's default reminder when the source ICS had no
        // VALARM (parser leaves reminders=null in that case). Mirrors the
        // Quick Add path so file-imported events behave like in-app creates.
        // Reminders parsed from the ICS file are preserved as-is.
        val defaultTimedReminder = dataStore.defaultReminderMinutes.first()
        val defaultAllDayReminder = dataStore.defaultAllDayReminder.first()

        // Group by the source UID so a recurring master and its RECURRENCE-ID
        // overrides (which share one UID per RFC 5545) reunite into a single
        // linked series instead of importing as unrelated standalone events.
        // Exceptions carry a non-null originalInstanceTime (from RECURRENCE-ID);
        // masters do not. A valid series is exactly one recurring master plus
        // at least one exception — any other shape (two same-UID masters from a
        // truncated Google export, an orphan override whose master fell outside
        // the file) imports each event standalone, so nothing is dropped.
        //
        // Deliberately NOT symmetric with the subscription-feed path (issue
        // #227), which synthesizes an inert placeholder master for orphan
        // exceptions and disambiguates duplicate-UID masters. That machinery
        // exists because a live feed must preserve the source UID to match rows
        // across re-syncs, so colliding UIDs trip the master-uniqueness trigger
        // and would be dropped. File import is a one-shot: it regenerates a
        // fresh UID per group, so it can never collide, and a standalone import
        // is the honest outcome. Do not "fix" this into parity — fabricating a
        // CANCELLED phantom master in a writable calendar would then push that
        // phantom to the CalDAV server, which is worse than a clean standalone.
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
                        // An override with no VALARM should alarm consistently
                        // with its sibling occurrences, so inherit the master's
                        // effective (post-default) reminders rather than the raw
                        // per-type default.
                        val exceptionReminders = exception.reminders ?: masterReminders
                        // Keep originalInstanceTime — createImportedSeries needs it
                        // to link the override. Its own timestamps/syncStatus are
                        // stamped by the writer, so they're not set here.
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
                        // Fresh UID, linkage cleared — a standalone import.
                        // Timestamps + syncStatus are stamped by createEvent.
                        val importEvent = event.asImported(
                            calendarId,
                            "${UUID.randomUUID()}@kashcal.onekash.org",
                            effectiveReminders
                        )

                        // Use eventWriter.createEvent which handles both regular and recurring events
                        val result = eventWriter.createEvent(importEvent, isLocal)

                        // Schedule reminders for imported event
                        scheduleRemindersForEvent(result)

                        importCount++
                        Log.d(TAG, "Imported event: ${event.title}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to import event: ${event.title}", e)
                    }
                }
            }
        }

        // Trigger sync if any events were imported to a non-local calendar
        if (importCount > 0 && !isLocal) {
            triggerImmediatePushIfNeeded(isLocal)
        }

        // Update home screen widgets if any events were imported
        if (importCount > 0) {
            triggerWidgetUpdate()
        }

        Log.d(TAG, "Imported $importCount of ${events.size} events to calendar $calendarId")
        return importCount
    }

    // ========== ICS Export ==========

    /**
     * Get exception events for a master recurring event.
     *
     * Used for ICS export to bundle master + exceptions into a single VCALENDAR.
     *
     * @param masterEventId The master event ID
     * @return List of exception events (may be empty for non-recurring events)
     */
    suspend fun getExceptionsForMaster(masterEventId: Long): List<Event> {
        return eventReader.getExceptionsForMaster(masterEventId)
    }

    /**
     * Get all events for a calendar with their exceptions (for ICS export).
     *
     * Returns pairs of (master event, exception events) for bundling into
     * a single VCALENDAR per RFC 5545.
     *
     * @param calendarId Calendar to export
     * @return List of (master event, exceptions) pairs
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

    /**
     * Check if contact birthday calendar exists.
     */
    suspend fun birthdayCalendarExists(): Boolean {
        return contactBirthdayRepository.calendarExists()
    }

    /**
     * Enable contact birthdays calendar.
     *
     * Creates the calendar if it doesn't exist.
     *
     * @param color Calendar color
     * @return Calendar ID
     */
    suspend fun enableContactBirthdays(color: Int): Long {
        return contactBirthdayRepository.ensureCalendarExists(color)
    }

    /**
     * Disable contact birthdays calendar.
     *
     * Removes the calendar and all birthday events.
     */
    suspend fun disableContactBirthdays() {
        contactBirthdayRepository.removeCalendar()
        triggerWidgetUpdate()
    }

    /**
     * Sync contact birthdays.
     *
     * Reads birthdays from phone contacts and syncs to calendar.
     *
     * @return Sync result
     */
    suspend fun syncContactBirthdays(): ContactEventSyncResult {
        val result = contactBirthdayRepository.syncEvents()
        if (result is ContactEventSyncResult.Success) {
            triggerWidgetUpdate()
        }
        return result
    }

    /**
     * Update contact birthdays calendar color.
     */
    suspend fun updateContactBirthdaysColor(color: Int) {
        contactBirthdayRepository.updateCalendarColor(color)
    }

    /**
     * Get contact birthdays calendar color.
     */
    suspend fun getContactBirthdaysColor(): Int? {
        return contactBirthdayRepository.getCalendarColor()
    }

    // ========== Contact Anniversaries ==========

    /**
     * Enable contact anniversaries calendar.
     *
     * Creates the calendar if it doesn't exist.
     *
     * @param color Calendar color
     * @return Calendar ID
     */
    suspend fun enableContactAnniversaries(color: Int): Long {
        return contactAnniversaryRepository.ensureCalendarExists(color)
    }

    /**
     * Disable contact anniversaries calendar.
     *
     * Removes the calendar and all anniversary events.
     */
    suspend fun disableContactAnniversaries() {
        contactAnniversaryRepository.removeCalendar()
        triggerWidgetUpdate()
    }

    /**
     * Sync contact anniversaries.
     *
     * Reads anniversaries from phone contacts and syncs to calendar.
     *
     * @return Sync result
     */
    suspend fun syncContactAnniversaries(): ContactEventSyncResult {
        val result = contactAnniversaryRepository.syncEvents()
        if (result is ContactEventSyncResult.Success) {
            triggerWidgetUpdate()
        }
        return result
    }

    /**
     * Update contact anniversaries calendar color.
     */
    suspend fun updateContactAnniversariesColor(color: Int) {
        contactAnniversaryRepository.updateCalendarColor(color)
    }

    /**
     * Get contact anniversaries calendar color.
     */
    suspend fun getContactAnniversariesColor(): Int? {
        return contactAnniversaryRepository.getCalendarColor()
    }

    // ========== Contact Event Counts ==========

    // ========== RSVP ==========

    /**
     * Write the user's RSVP for an event they're attending.
     *
     * 1. Updates the local attendee row's PARTSTAT (optimistic UI — chip row
     *    flips immediately).
     * 2. Queues a PARTSTAT-only PendingOperation; the next sync turns it into
     *    a surgical CalDAV PUT that preserves every other ATTENDEE row,
     *    ORGANIZER, SUMMARY, etc.
     * 3. Triggers expedited sync so the response reaches the organizer
     *    promptly (matching the create/update path).
     *
     * @param status A PARTSTAT value (RFC 5545 §3.2.12). Canonicalized to
     *   uppercase by the underlying writer; caller may pass any case.
     * @return true when the local attendee row matched and was updated.
     *   false when no attendee row matches the account — caller should
     *   surface "you're not on this event's attendee list" feedback.
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
     * Save the user's reminder set on an event they're an attendee of.
     *
     * Local-only: per-attendee VALARMs (RFC 5545 §3.6.6) are written to
     * the local event row; on-device AlarmManager fires from the new
     * list. No server PUT is queued — see [EventWriter.saveAttendeeReminders]
     * for the rationale.
     *
     * Reminders pass through to [EventWriter] as a `List<Int>` of
     * minutes-before for ergonomic call-site simplicity; the writer
     * converts to ISO-8601 list at the storage boundary.
     *
     * @return [Result.success] with the updated [Event] for caller-side
     *   reminder rescheduling, or [Result.failure] when the event
     *   doesn't exist.
     */
    suspend fun saveAttendeeReminders(eventId: Long, reminders: List<Int>): Result<Event> {
        val event = eventReader.getEventById(eventId)
            ?: return Result.failure(IllegalStateException("Event not found: $eventId"))

        // Reuse the shared formatter — same one ContactEventUtils,
        // QuickAddViewModel, and the contact-event repository call.
        val isoStrings = reminders.map(ContactEventUtils::minutesToIsoDuration)
        eventWriter.saveAttendeeReminders(eventId, isoStrings)

        // Reschedule alarms so the new set fires from AlarmManager.
        val updated = event.copy(reminders = isoStrings, alarmCount = isoStrings.size)
        rescheduleRemindersForEvent(updated)
        triggerWidgetUpdate()
        return Result.success(updated)
    }

    /**
     * Get the number of birthday events.
     */
    suspend fun getContactBirthdayEventCount(): Int {
        val calendarId = contactBirthdayRepository.getCalendarId() ?: return 0
        return eventReader.getEventCountForCalendar(calendarId)
    }

    /**
     * Get the number of anniversary events.
     */
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
     * Re-base a parsed ICS event onto the target calendar for import: fresh UID,
     * server-side fields cleared, resolved reminders applied, and recurrence-link
     * fields dropped so it enters as a standalone master. Timestamps and
     * syncStatus are intentionally left for the writer (createEvent /
     * createImportedSeries) to stamp inside its transaction.
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
