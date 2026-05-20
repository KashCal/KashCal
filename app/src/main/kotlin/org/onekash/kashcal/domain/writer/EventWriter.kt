package org.onekash.kashcal.domain.writer

import androidx.room.withTransaction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.identity.matchesAttendee
import org.onekash.kashcal.sync.strategy.PullStrategy
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles all event write operations with proper occurrence management.
 *
 * Responsibilities:
 * - Create single and recurring events
 * - Update events (with RRULE change detection)
 * - Soft delete events for sync
 * - Create exceptions (edit single occurrence)
 * - Cancel occurrences (delete single occurrence via EXDATE)
 * - Split recurring series ("this and all future")
 *
 * All operations:
 * - Use database transactions for atomicity
 * - Update sync status for offline-first
 * - Queue pending operations for CalDAV sync
 * - Regenerate occurrences when needed
 */
@Singleton
class EventWriter @Inject constructor(
    private val database: KashCalDatabase,
    private val occurrenceGenerator: OccurrenceGenerator
) {
    private val eventsDao by lazy { database.eventsDao() }
    private val pendingOpsDao by lazy { database.pendingOperationsDao() }
    private val occurrencesDao by lazy { database.occurrencesDao() }
    private val attendeesDao by lazy { database.attendeesDao() }

    /**
     * Create a new event.
     *
     * @param event The event to create (id will be ignored)
     * @param isLocal True if this is a local-only event (no sync)
     * @return The created event with assigned ID
     */
    suspend fun createEvent(event: Event, isLocal: Boolean = false): Event {
        return database.withTransaction {
            // Generate UID if not provided
            val eventWithUid = if (event.uid.isBlank()) {
                event.copy(uid = generateUid())
            } else {
                event
            }

            // Set timestamps
            val now = System.currentTimeMillis()
            val eventToInsert = eventWithUid.copy(
                syncStatus = if (isLocal) SyncStatus.SYNCED else SyncStatus.PENDING_CREATE,
                dtstamp = now,
                createdAt = now,
                updatedAt = now,
                localModifiedAt = now
            )

            // Insert event
            val eventId = eventsDao.insert(eventToInsert)
            val createdEvent = eventToInsert.copy(id = eventId)

            // Generate occurrences
            // Round startTs down to seconds and subtract 1 second to ensure DTSTART is included
            // (OccurrenceGenerator uses seconds precision internally)
            val rangeStartSeconds = (createdEvent.startTs / 1000) - 1
            val rangeStart = rangeStartSeconds * 1000
            // Sync window: 2 years forward (consistent with PullStrategy.OCCURRENCE_EXPANSION_MS)
            val rangeEnd = now + PullStrategy.OCCURRENCE_EXPANSION_MS
            occurrenceGenerator.generateOccurrences(createdEvent, rangeStart, rangeEnd)

            // Queue sync operation (unless local-only)
            if (!isLocal) {
                queueOperation(eventId, PendingOperation.OPERATION_CREATE)
            }

            createdEvent
        }
    }

    /**
     * Update an existing event.
     *
     * Detects RRULE changes and regenerates occurrences as needed.
     *
     * @param event The event with updated fields
     * @param isLocal True if this is a local-only event
     * @return The updated event
     */
    suspend fun updateEvent(event: Event, isLocal: Boolean = false): Event {
        return database.withTransaction {
            val existingEvent = requireNotNull(eventsDao.getById(event.id)) {
                "Event not found: ${event.id}"
            }

            val now = System.currentTimeMillis()

            // Check if RRULE changed
            val rruleChanged = existingEvent.rrule != event.rrule ||
                    existingEvent.exdate != event.exdate ||
                    existingEvent.rdate != event.rdate

            // Check if timing changed
            val timingChanged = existingEvent.startTs != event.startTs ||
                    existingEvent.endTs != event.endTs ||
                    existingEvent.isAllDay != event.isAllDay

            // Increment sequence for significant changes (CalDAV requirement)
            val newSequence = if (rruleChanged || timingChanged) {
                event.sequence + 1
            } else {
                event.sequence
            }

            // Determine sync status
            val newSyncStatus = when {
                isLocal -> SyncStatus.SYNCED
                existingEvent.syncStatus == SyncStatus.PENDING_CREATE -> SyncStatus.PENDING_CREATE
                else -> SyncStatus.PENDING_UPDATE
            }

            val eventToUpdate = event.copy(
                syncStatus = newSyncStatus,
                sequence = newSequence,
                updatedAt = now,
                localModifiedAt = now
            )

            // Update event
            eventsDao.update(eventToUpdate)

            // Regenerate occurrences if RRULE or timing changed
            if (rruleChanged || timingChanged) {
                occurrenceGenerator.regenerateOccurrences(eventToUpdate)
            }

            // Queue sync operation (unless local-only or already pending create)
            if (!isLocal && existingEvent.syncStatus != SyncStatus.PENDING_CREATE) {
                queueOperation(event.id, PendingOperation.OPERATION_UPDATE)
            }

            eventToUpdate
        }
    }

    /**
     * Write the user's RSVP for an event they're attending.
     *
     * Updates the local attendee row's PARTSTAT (so the chip row reflects
     * the choice immediately — optimistic UI), then queues a PARTSTAT-only
     * pending operation that PushStrategy turns into a surgical CalDAV PUT
     * via `IcsPatcher.patchAttendeeReply`. Every other ATTENDEE row,
     * ORGANIZER, SUMMARY, etc. on the event survives verbatim.
     *
     * Canonicalizes [partstat] to uppercase per RFC 5545 §3.2.12. Caller
     * may pass any-case ("accepted", "ACCEPTED", "Accepted").
     *
     * @return true when the local attendee row matched and was updated,
     *   false when no attendee row matches the account (caller surfaces
     *   "you're not on this event's attendee list" error).
     */
    suspend fun replyRsvp(
        eventId: Long,
        account: Account,
        partstat: String
    ): Boolean {
        val canonical = partstat.uppercase()
        return database.withTransaction {
            val rows = attendeesDao.getForEventOnce(eventId)
            val matching = rows.firstOrNull { account.matchesAttendee(it.address) }
                ?: return@withTransaction false

            // Optimistic local write: replace the row set with the same rows,
            // mutating only matching's PARTSTAT. Reuses existing
            // replaceForEvent transaction semantics so the chip row's Flow
            // observes a single emission.
            val updatedRows = rows.map { row ->
                if (row.id == matching.id) row.copy(partstat = canonical) else row
            }
            attendeesDao.replaceForEvent(eventId, updatedRows)

            // Capture caldavUrl at queue time so the drain stays self-contained
            // even if a future code path clears Event.caldavUrl before drain.
            val capturedUrl = eventsDao.getById(eventId)?.caldavUrl
            val pendingOp = PendingOperation(
                eventId = eventId,
                operation = PendingOperation.OPERATION_UPDATE,
                partstatOnly = true,
                partstatTarget = canonical,
                targetUrl = capturedUrl
            )
            pendingOpsDao.insert(pendingOp)
            true
        }
    }

    /**
     * Update only the user's local reminder set on an event they're an
     * attendee of (RFC 5545 §3.6.6 — VALARM is a per-attendee property,
     * not part of the organizer's authoritative event state).
     *
     * Local-only: writes the `reminders` and `alarm_count` columns
     * directly, leaves `sync_status` as-is, and does NOT queue a
     * PendingOperation. The reason is the same one T2's PARTSTAT-only
     * path solved: a full-event PUT on the attendee side rewrites the
     * server's ATTENDEE list (servers route by ORGANIZER mailto). A
     * VALARM-only patcher would close the loop server-side, but it's
     * not in this iteration's scope. The on-device AlarmManager fires
     * regardless of server state, which is the user-visible win.
     *
     * Caller must pass an ISO-8601 list (e.g., `["-PT15M", "-PT1H"]`).
     * UI callers can use the existing `buildRemindersList` helper to
     * convert minute integers to ISO-8601 strings.
     *
     * @param eventId The event whose reminders to update.
     * @param reminders ISO-8601 duration strings, ordered as the user
     *   chose them. May be empty to clear all reminders.
     */
    suspend fun saveAttendeeReminders(eventId: Long, reminders: List<String>) {
        val now = System.currentTimeMillis()
        // Mirrors Converters.fromStringList JSON shape so the entity round-
        // trips through Room's @TypeConverter on subsequent reads.
        val remindersJson = if (reminders.isEmpty()) null else Json.encodeToString(reminders)
        eventsDao.updateRemindersAndAlarmCount(
            id = eventId,
            remindersJson = remindersJson,
            alarmCount = reminders.size,
            now = now
        )
    }

    /**
     * Soft delete an event (marks for deletion, doesn't remove from DB).
     *
     * For CalDAV sync, the event is kept until successfully deleted from server.
     * For local-only events, immediately removes from DB.
     *
     * @param eventId The event ID to delete
     * @param isLocal True if this is a local-only event
     */
    suspend fun deleteEvent(eventId: Long, isLocal: Boolean = false) {
        database.withTransaction {
            val event = requireNotNull(eventsDao.getById(eventId)) {
                "Event not found: $eventId"
            }

            if (isLocal || event.syncStatus == SyncStatus.PENDING_CREATE) {
                // Local or never synced - hard delete
                eventsDao.deleteById(eventId)
                // Cascade delete handles occurrences and exceptions
            } else {
                // CalDAV event - soft delete
                val now = System.currentTimeMillis()
                eventsDao.markForDeletion(eventId, now)

                // Clear occurrences (won't show in UI)
                occurrencesDao.deleteForEvent(eventId)

                // Queue delete operation
                queueOperation(eventId, PendingOperation.OPERATION_DELETE)
            }
        }
    }

    /**
     * Edit a single occurrence of a recurring event (creates exception).
     *
     * Creates a new exception event linked to the master via originalEventId.
     * The occurrence is updated to reference the exception.
     *
     * @param masterEventId The master recurring event ID
     * @param occurrenceTimeMs The original occurrence start time
     * @param modifiedEvent Event with modified fields (title, time, etc.)
     * @param isLocal True if master is local-only
     * @return The created exception event
     */
    suspend fun editSingleOccurrence(
        masterEventId: Long,
        occurrenceTimeMs: Long,
        modifiedEvent: Event,
        isLocal: Boolean = false
    ): Event {
        return database.withTransaction {
            val masterEvent = requireNotNull(eventsDao.getById(masterEventId)) {
                "Master event not found: $masterEventId"
            }

            require(masterEvent.isRecurring) { "Event is not recurring: $masterEventId" }

            val now = System.currentTimeMillis()

            // Check if exception already exists for this occurrence
            val existingException = eventsDao.getExceptionForOccurrence(masterEventId, occurrenceTimeMs)

            val (exceptionId, createdException) = if (existingException != null) {
                // Update existing exception (re-editing a previously modified occurrence)
                // Exception is bundled with master for sync, so mark as SYNCED locally
                val updatedEvent = modifiedEvent.copy(
                    id = existingException.id,
                    uid = existingException.uid, // Preserve UID (should equal master UID)
                    calendarId = masterEvent.calendarId,
                    originalEventId = masterEventId,
                    originalInstanceTime = occurrenceTimeMs,
                    rrule = null, // Exception cannot have RRULE
                    exdate = null,
                    rdate = null,
                    // Exception is bundled with master for sync, so mark as SYNCED locally
                    syncStatus = SyncStatus.SYNCED,
                    dtstamp = now,
                    createdAt = existingException.createdAt, // Preserve original creation time
                    updatedAt = now,
                    localModifiedAt = now
                )
                eventsDao.update(updatedEvent)
                Pair(existingException.id, updatedEvent)
            } else {
                // Create new exception event
                // RFC 5545: Exception MUST have same UID as master, distinguished by RECURRENCE-ID
                val exceptionEvent = modifiedEvent.copy(
                    id = 0, // New event
                    uid = masterEvent.uid, // Same UID as master (RFC 5545 requirement)
                    calendarId = masterEvent.calendarId,
                    originalEventId = masterEventId,
                    originalInstanceTime = occurrenceTimeMs,
                    rrule = null, // Exception cannot have RRULE
                    exdate = null,
                    rdate = null,
                    // Exception is bundled with master for sync, so mark as SYNCED locally
                    // Master will be marked PENDING_UPDATE to trigger the bundled push
                    syncStatus = SyncStatus.SYNCED,
                    dtstamp = now,
                    createdAt = now,
                    updatedAt = now,
                    localModifiedAt = now
                )
                val newId = eventsDao.insert(exceptionEvent)
                Pair(newId, exceptionEvent.copy(id = newId))
            }

            // Link occurrence to exception AND update occurrence times
            // Using the Event overload updates start_ts, end_ts, start_day, end_day
            // to match the exception's modified times (critical for correct display)
            occurrenceGenerator.linkException(masterEventId, occurrenceTimeMs, createdException)

            // Queue sync on MASTER event (not exception)
            // Exception is bundled with master when serialized via serializeWithExceptions()
            // This ensures the server receives master + all exceptions as one atomic .ics file
            if (!isLocal && masterEvent.caldavUrl != null) {
                // Mark master as pending update if it was previously synced
                if (masterEvent.syncStatus == SyncStatus.SYNCED) {
                    eventsDao.updateSyncStatus(masterEventId, SyncStatus.PENDING_UPDATE, now)
                }
                queueOperation(masterEventId, PendingOperation.OPERATION_UPDATE)
            }

            createdException
        }
    }

    /**
     * Delete a single occurrence of a recurring event (adds EXDATE).
     *
     * Does not create an exception - simply excludes the occurrence.
     * Updates the master event's EXDATE field.
     *
     * @param masterEventId The master recurring event ID
     * @param occurrenceTimeMs The occurrence start time to cancel
     * @param isLocal True if master is local-only
     */
    suspend fun deleteSingleOccurrence(
        masterEventId: Long,
        occurrenceTimeMs: Long,
        isLocal: Boolean = false
    ) {
        database.withTransaction {
            val masterEvent = requireNotNull(eventsDao.getById(masterEventId)) {
                "Master event not found: $masterEventId"
            }

            require(masterEvent.isRecurring) { "Event is not recurring: $masterEventId" }

            // Add to EXDATE
            val newExdate = addToExdate(masterEvent.exdate, occurrenceTimeMs, masterEvent.isAllDay)
            val now = System.currentTimeMillis()

            eventsDao.updateExdate(masterEventId, newExdate, now)

            // Delete exception event if one exists for this occurrence
            // (prevents orphaned exception events in database)
            val exception = eventsDao.getExceptionForOccurrence(masterEventId, occurrenceTimeMs)
            exception?.let { eventsDao.deleteById(it.id) }

            // Mark occurrence as cancelled
            occurrenceGenerator.cancelOccurrence(masterEventId, occurrenceTimeMs)

            // Queue sync for master (EXDATE changed)
            if (!isLocal) {
                val newSyncStatus = when (masterEvent.syncStatus) {
                    SyncStatus.PENDING_CREATE -> SyncStatus.PENDING_CREATE
                    else -> SyncStatus.PENDING_UPDATE
                }
                eventsDao.updateSyncStatus(masterEventId, newSyncStatus, now)

                if (masterEvent.syncStatus != SyncStatus.PENDING_CREATE) {
                    queueOperation(masterEventId, PendingOperation.OPERATION_UPDATE)
                }
            }
        }
    }

    /**
     * Split a recurring series ("edit this and all future").
     *
     * 1. Truncates the master event (UNTIL set to before split point)
     * 2. Creates a new event with the modifications starting from split point
     *
     * @param masterEventId The master recurring event ID
     * @param splitTimeMs The occurrence time to split from
     * @param modifiedEvent Event with modifications for the new series
     * @param isLocal True if master is local-only
     * @return The new event for "this and all future"
     */
    suspend fun splitSeries(
        masterEventId: Long,
        splitTimeMs: Long,
        modifiedEvent: Event,
        isLocal: Boolean = false
    ): Event {
        return database.withTransaction {
            val masterEvent = requireNotNull(eventsDao.getById(masterEventId)) {
                "Master event not found: $masterEventId"
            }

            require(masterEvent.isRecurring) { "Event is not recurring: $masterEventId" }

            val now = System.currentTimeMillis()

            // Step 1: Truncate master event's RRULE with UNTIL
            val rrule = checkNotNull(masterEvent.rrule) {
                "Recurring event has no RRULE: ${masterEvent.id}"
            }
            val truncatedRrule = addUntilToRrule(rrule, splitTimeMs - 1, masterEvent.isAllDay)
            eventsDao.updateRrule(masterEventId, truncatedRrule, now)

            // Delete occurrences at/after split point
            occurrencesDao.deleteForEventAfter(masterEventId, splitTimeMs)

            // Update master sync status
            if (!isLocal && masterEvent.syncStatus != SyncStatus.PENDING_CREATE) {
                eventsDao.updateSyncStatus(masterEventId, SyncStatus.PENDING_UPDATE, now)
                queueOperation(masterEventId, PendingOperation.OPERATION_UPDATE)
            }

            // Step 2: Create new event for "this and all future"
            val newEvent = modifiedEvent.copy(
                id = 0,
                uid = generateUid(),
                calendarId = masterEvent.calendarId,
                startTs = splitTimeMs + (modifiedEvent.startTs - masterEvent.startTs),
                originalEventId = null, // Not an exception - new series
                originalInstanceTime = null,
                syncStatus = if (isLocal) SyncStatus.SYNCED else SyncStatus.PENDING_CREATE,
                dtstamp = now,
                createdAt = now,
                updatedAt = now,
                localModifiedAt = now
            )

            val newEventId = eventsDao.insert(newEvent)
            val createdEvent = newEvent.copy(id = newEventId)

            // Generate occurrences for new event
            occurrenceGenerator.regenerateOccurrences(createdEvent)

            // Queue sync for new event
            if (!isLocal) {
                queueOperation(newEventId, PendingOperation.OPERATION_CREATE)
            }

            createdEvent
        }
    }

    /**
     * Delete "this and all future" occurrences.
     *
     * Truncates the master event's RRULE with UNTIL before the split point.
     *
     * @param masterEventId The master recurring event ID
     * @param fromTimeMs Delete occurrences from this time onwards
     * @param isLocal True if master is local-only
     */
    suspend fun deleteThisAndFuture(
        masterEventId: Long,
        fromTimeMs: Long,
        isLocal: Boolean = false
    ) {
        database.withTransaction {
            val masterEvent = requireNotNull(eventsDao.getById(masterEventId)) {
                "Master event not found: $masterEventId"
            }

            require(masterEvent.isRecurring) { "Event is not recurring: $masterEventId" }

            val now = System.currentTimeMillis()

            // If deleting from the first occurrence, delete entire event
            if (fromTimeMs <= masterEvent.startTs) {
                deleteEvent(masterEventId, isLocal)
                return@withTransaction
            }

            // Truncate RRULE with UNTIL
            val rrule = checkNotNull(masterEvent.rrule) {
                "Recurring event has no RRULE: ${masterEvent.id}"
            }
            val truncatedRrule = addUntilToRrule(rrule, fromTimeMs - 1, masterEvent.isAllDay)
            eventsDao.updateRrule(masterEventId, truncatedRrule, now)

            // Delete occurrences at/after point
            occurrencesDao.deleteForEventAfter(masterEventId, fromTimeMs)

            // Delete any exception events for deleted occurrences
            val exceptions = eventsDao.getExceptionsForMaster(masterEventId)
            for (exception in exceptions) {
                if (exception.originalInstanceTime != null && exception.originalInstanceTime >= fromTimeMs) {
                    eventsDao.deleteById(exception.id)
                }
            }

            // Update sync status
            if (!isLocal && masterEvent.syncStatus != SyncStatus.PENDING_CREATE) {
                eventsDao.updateSyncStatus(masterEventId, SyncStatus.PENDING_UPDATE, now)
                queueOperation(masterEventId, PendingOperation.OPERATION_UPDATE)
            }
        }
    }

    /**
     * Move event to a different calendar.
     *
     * Hybrid approach based on source/target account types:
     * - Same account: MOVE operation (WebDAV MOVE or DELETE+CREATE fallback)
     * - Cross account: Separate CREATE + DELETE operations (different sync cycles)
     * - Synced → Local: DELETE only (remove from server)
     * - Local → Synced: CREATE only (add to server)
     * - Local → Local: No-op for sync
     *
     * Key fix (v21.6.0): Uses sourceCalendarId for DELETE filtering since
     * event.calendarId is updated to target before push completes.
     *
     * @param eventId The event to move
     * @param newCalendarId The destination calendar ID
     */
    suspend fun moveEventToCalendar(
        eventId: Long,
        newCalendarId: Long
    ) {
        database.withTransaction {
            val event = requireNotNull(eventsDao.getById(eventId)) {
                "Event not found: $eventId"
            }

            // Guard: Exception events cannot be moved directly
            require(event.originalEventId == null) {
                "Cannot move exception event directly. Move the master event instead (originalEventId: ${event.originalEventId})"
            }

            if (event.calendarId == newCalendarId) {
                return@withTransaction // No-op
            }

            val calendarsDao = database.calendarsDao()
            val accountsDao = database.accountsDao()

            // Detect source and target context
            val sourceCalendar = calendarsDao.getById(event.calendarId)
            val targetCalendar = requireNotNull(calendarsDao.getById(newCalendarId)) {
                "Target calendar not found: $newCalendarId"
            }

            // Check target calendar isn't read-only (defense in depth - UI also filters these)
            require(!targetCalendar.isReadOnly) {
                "Cannot move event to read-only calendar"
            }

            val sourceAccountId = sourceCalendar?.accountId
            val targetAccountId = targetCalendar.accountId

            val sourceAccount = sourceAccountId?.let { accountsDao.getById(it) }
            val targetAccount = accountsDao.getById(targetAccountId)

            // Determine local status from AccountProvider (not isLocal parameter)
            val sourceIsLocal = sourceAccount?.provider?.requiresSync == false
            val targetIsLocal = targetAccount?.provider?.requiresSync == false
            val isSameAccount = sourceAccountId == targetAccountId && sourceAccountId != null

            // Capture old URL BEFORE clearing (critical for sync)
            val oldCaldavUrl = event.caldavUrl
            val wasSynced = event.syncStatus == SyncStatus.SYNCED && oldCaldavUrl != null
            val now = System.currentTimeMillis()

            // Determine new sync status based on target
            val newSyncStatus = when {
                targetIsLocal -> SyncStatus.SYNCED
                wasSynced -> SyncStatus.PENDING_CREATE // Will need CREATE on server
                else -> SyncStatus.PENDING_CREATE
            }

            // Update master event
            val movedEvent = event.copy(
                calendarId = newCalendarId,
                caldavUrl = null, // Will get new URL on sync
                etag = null,
                syncStatus = newSyncStatus,
                updatedAt = now,
                localModifiedAt = now
            )
            eventsDao.update(movedEvent)

            // Update exception events (cascade within transaction)
            if (event.rrule != null) {
                eventsDao.updateCalendarIdForExceptions(eventId, newCalendarId, now)
            }

            // Update occurrences with new calendar ID (single UPDATE query)
            occurrencesDao.updateCalendarIdForEvent(eventId, newCalendarId)

            // Cancel any existing pending operations (they're for old calendar)
            pendingOpsDao.deleteForEvent(eventId)

            // Queue sync operations based on scenario
            when {
                // Local → Local: No sync needed
                sourceIsLocal && targetIsLocal -> {
                    // No-op for sync
                }

                // Local → Synced: CREATE only
                sourceIsLocal && !targetIsLocal -> {
                    pendingOpsDao.insert(
                        PendingOperation(
                            eventId = eventId,
                            operation = PendingOperation.OPERATION_CREATE
                        )
                    )
                }

                // Synced → Local: DELETE only (with sourceCalendarId for filtering)
                !sourceIsLocal && targetIsLocal && wasSynced -> {
                    pendingOpsDao.insert(
                        PendingOperation(
                            eventId = eventId,
                            operation = PendingOperation.OPERATION_DELETE,
                            targetUrl = oldCaldavUrl,
                            sourceCalendarId = event.calendarId // Source for filtering
                        )
                    )
                }

                // Same account (synced): MOVE operation
                !sourceIsLocal && !targetIsLocal && isSameAccount && wasSynced -> {
                    pendingOpsDao.insert(
                        PendingOperation(
                            eventId = eventId,
                            operation = PendingOperation.OPERATION_MOVE,
                            targetUrl = oldCaldavUrl,
                            targetCalendarId = newCalendarId,
                            sourceCalendarId = event.calendarId // Source for DELETE phase filtering
                        )
                    )
                }

                // Cross account (synced): Linked CREATE + DELETE
                // DELETE is blocked by guard query until CREATE completes (success or permanent failure).
                // If CREATE fails, event stays in source (safe). If DELETE fails, event is duplicated (recoverable).
                !sourceIsLocal && !targetIsLocal && !isSameAccount && wasSynced -> {
                    val linkedMoveId = UUID.randomUUID().toString()

                    // CREATE on target account (runs first due to guard query)
                    pendingOpsDao.insert(
                        PendingOperation(
                            eventId = eventId,
                            operation = PendingOperation.OPERATION_CREATE,
                            linkedMoveId = linkedMoveId
                        )
                    )
                    // DELETE on source account - blocked until CREATE completes
                    pendingOpsDao.insert(
                        PendingOperation(
                            eventId = eventId,
                            operation = PendingOperation.OPERATION_DELETE,
                            targetUrl = oldCaldavUrl,
                            sourceCalendarId = event.calendarId, // Source for filtering
                            linkedMoveId = linkedMoveId
                        )
                    )
                }

                // Synced → Synced but never uploaded (PENDING_CREATE): Just CREATE
                !sourceIsLocal && !targetIsLocal && !wasSynced -> {
                    pendingOpsDao.insert(
                        PendingOperation(
                            eventId = eventId,
                            operation = PendingOperation.OPERATION_CREATE
                        )
                    )
                }
            }
        }
    }

    // ========== Lookback Cleanup ==========

    /**
     * Delete CalDAV events outside the sync lookback window.
     * Called when user shrinks the lookback setting.
     *
     * @param cutoffTs Timestamp cutoff - events ending before this are deleted (epoch ms)
     * @return Number of events deleted
     */
    suspend fun cleanupEventsOutsideLookback(cutoffTs: Long): Int {
        return eventsDao.deleteOutsideLookback(cutoffTs)
    }

    /**
     * Full cleanup when shrinking sync lookback window.
     * Deletes:
     * 1. Non-recurring CalDAV events outside window (via deleteOutsideLookback)
     * 2. Old occurrences of recurring events
     * 3. Old exception events (modified single occurrences)
     *
     * @param cutoffTs Timestamp cutoff
     * @return CleanupResult with counts of deleted items
     */
    suspend fun cleanupForShrinkingLookback(cutoffTs: Long): CleanupResult {
        val deletedEvents = eventsDao.deleteOutsideLookback(cutoffTs)
        val deletedOccurrences = occurrencesDao.deleteBeforeCutoff(cutoffTs)
        val deletedExceptions = eventsDao.deleteExceptionEventsBeforeCutoff(cutoffTs)
        return CleanupResult(deletedEvents, deletedOccurrences, deletedExceptions)
    }

    data class CleanupResult(
        val deletedEvents: Int,
        val deletedOccurrences: Int,
        val deletedExceptions: Int
    ) {
        val totalDeleted: Int get() = deletedEvents + deletedOccurrences + deletedExceptions
    }

    // ========== Helper Functions ==========

    private fun generateUid(): String {
        return "${UUID.randomUUID()}@kashcal.onekash.org"
    }

    private suspend fun queueOperation(eventId: Long, operation: String) {
        val now = System.currentTimeMillis()

        // Check if operation already pending for this event
        val existingList = pendingOpsDao.getForEvent(eventId)
        val existing = existingList.firstOrNull { it.status == PendingOperation.STATUS_PENDING }
        if (existing != null) {
            // Refresh lifetime - user still cares about this event (v21.5.3)
            pendingOpsDao.refreshOperationLifetime(eventId, now)

            // Update existing operation if upgrading (e.g., UPDATE -> DELETE)
            if (operation == PendingOperation.OPERATION_DELETE) {
                pendingOpsDao.update(existing.copy(operation = operation))
            }
            return
        }

        // Insert new operation (lifetimeResetAt defaults to now via entity default)
        val pendingOp = PendingOperation(
            eventId = eventId,
            operation = operation
        )
        pendingOpsDao.insert(pendingOp)
    }

    /**
     * Add a timestamp to EXDATE field.
     * Format: Comma-separated millisecond timestamps.
     *
     * Matches ICalEventMapper (server→DB) and IcsPatcher (DB→server) format.
     * OccurrenceGenerator handles both milliseconds and legacy day codes for backward compat.
     *
     * @param timestampMs The occurrence start time in milliseconds to exclude
     * @param isAllDay Unused - kept for API compatibility
     */
    @Suppress("UNUSED_PARAMETER")
    private fun addToExdate(currentExdate: String?, timestampMs: Long, isAllDay: Boolean): String {
        return if (currentExdate.isNullOrBlank()) {
            timestampMs.toString()
        } else {
            "$currentExdate,$timestampMs"
        }
    }

    /**
     * Add UNTIL parameter to RRULE.
     * Delegates to [RruleUtils] for shared logic between Room and CalendarProvider layers.
     */
    private fun addUntilToRrule(rrule: String, untilMs: Long, isAllDay: Boolean = false): String {
        return org.onekash.kashcal.util.RruleUtils.addUntilToRrule(rrule, untilMs, isAllDay)
    }
}
