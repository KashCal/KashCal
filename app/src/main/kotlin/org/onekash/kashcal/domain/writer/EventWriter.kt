package org.onekash.kashcal.domain.writer

import androidx.room.withTransaction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.identity.matchesAttendee
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.scheduling.SequenceBumper
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.util.RruleUtils
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
    private val pendingCancelsDao by lazy { database.pendingCancelsDao() }
    private val categoryDao by lazy { database.categoryDao() }
    private val calendarsDao by lazy { database.calendarsDao() }

    /**
     * Record that each of an event's [categories] was used at [now] so the tag
     * suggestion ranking (recency-ordered) and the management screen stay
     * current. Called inside the save transaction. Uses a color-preserving
     * upsert — a re-save of an event tagged with a recolored tag must never
     * reset that tag's chosen color to null. Blank names and empty/null lists
     * are no-ops.
     */
    private suspend fun recordCategoryUsage(categories: List<String>?, now: Long) {
        categories?.forEach { name ->
            if (name.isNotBlank()) categoryDao.touch(name, now)
        }
    }

    /**
     * Reconcile a set of tag names into the shared tag registry, stamping each
     * as used now. For tags that write straight to the platform calendar store
     * (which has no registry of its own), this is what makes a freshly-created
     * tag gain a suggestion-ranking entry and become colorable. Reuses the same
     * color-preserving upsert as the in-transaction path, so re-recording an
     * existing recolored tag bumps its recency without clearing its color.
     */
    suspend fun recordCategoryUsage(categories: List<String>) {
        recordCategoryUsage(categories, System.currentTimeMillis())
    }

    /**
     * Result of [createImportedSeries]: the persisted master and its exception
     * overrides, each with its assigned row id so callers can schedule
     * reminders against the real events.
     */
    data class ImportedSeries(
        val master: Event,
        val exceptions: List<Event>
    )

    /**
     * Create a new event.
     *
     * @param event The event to create (id will be ignored)
     * @param isLocal True if this is a local-only event (no sync)
     * @return The created event with assigned ID
     */
    suspend fun createEvent(
        event: Event,
        isLocal: Boolean = false,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee>? = null
    ): Event {
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

            recordCategoryUsage(createdEvent.categories, now)

            // Persist attendees when supplied. null = caller isn't touching the
            // attendee set (leave it alone); a list (incl. empty) replaces it.
            if (attendees != null) {
                attendeesDao.replaceForEvent(eventId, attendees.map { it.copy(eventId = eventId) })
            }

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
     * Persist an ICS-imported recurring event as one linked series: a master
     * plus its RECURRENCE-ID exception overrides, all sharing a single UID.
     *
     * The caller (EventCoordinator) has already regenerated the shared UID
     * (never the source file's UID — a fresh UID avoids duplicate-UID PUT
     * collisions on servers like iCloud/Nextcloud) and resolved reminder
     * defaults. This method only handles persistence + linkage:
     *
     * - Master: inserted, occurrences expanded from its RRULE, and (for a
     *   synced calendar) a single CREATE queued. On push the exceptions ride
     *   along in the master's resource — PushStrategy bundles events whose
     *   [Event.originalEventId] is set rather than pushing them separately, so
     *   no per-exception operation is queued here.
     * - Each exception: inserted with the master's UID and row id as
     *   [Event.originalEventId], its [Event.originalInstanceTime] preserved,
     *   recurrence fields cleared, and marked [SyncStatus.SYNCED] locally
     *   (bundled, not independently synced). [OccurrenceGenerator.linkException]
     *   attaches it to the master's occurrence for that instant so the day
     *   only renders the override, not both the RRULE instance and the
     *   exception.
     *
     * All work runs in a single transaction.
     *
     * @param master The recurring master event (id ignored; must have a RRULE)
     * @param exceptions The override events (id ignored; each must carry a
     *   non-null [Event.originalInstanceTime])
     * @param isLocal True if the target calendar is local-only (no sync)
     * @return The persisted master and exceptions with assigned row ids
     */
    suspend fun createImportedSeries(
        master: Event,
        exceptions: List<Event>,
        isLocal: Boolean = false
    ): ImportedSeries {
        require(master.rrule != null) { "createImportedSeries master must be recurring" }
        return database.withTransaction {
            val now = System.currentTimeMillis()

            // The master persists exactly like any created event — insert, RRULE
            // occurrence expansion, and (for a synced calendar) a single CREATE
            // queued. createEvent stamps timestamps + syncStatus and joins this
            // transaction, so there's nothing to re-implement here.
            val savedMaster = createEvent(master, isLocal)
            val masterId = savedMaster.id

            val savedExceptions = exceptions.map { exception ->
                // The caller only routes real RECURRENCE-ID overrides here.
                val originalInstanceTime = requireNotNull(exception.originalInstanceTime) {
                    "createImportedSeries exception must carry originalInstanceTime"
                }
                val exceptionToInsert = exception.copy(
                    id = 0,
                    uid = savedMaster.uid, // RFC 5545: exception shares master UID
                    calendarId = savedMaster.calendarId,
                    originalEventId = masterId,
                    // originalInstanceTime preserved from the parsed RECURRENCE-ID.
                    rrule = null,
                    rdate = null,
                    exdate = null,
                    // Bundled with the master for sync — synced locally either way.
                    syncStatus = SyncStatus.SYNCED,
                    caldavUrl = null,
                    etag = null,
                    dtstamp = now,
                    createdAt = now,
                    updatedAt = now,
                    localModifiedAt = if (isLocal) null else now
                )
                val exceptionId = eventsDao.insert(exceptionToInsert)
                val savedException = exceptionToInsert.copy(id = exceptionId)

                // Attach to the master's occurrence so the overridden instant
                // renders once (the override), not both the RRULE instance and it.
                occurrenceGenerator.linkException(masterId, originalInstanceTime, savedException)
                savedException
            }

            ImportedSeries(savedMaster, savedExceptions)
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
    suspend fun updateEvent(
        event: Event,
        isLocal: Boolean = false,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee>? = null
    ): Event {
        return database.withTransaction {
            val existingEvent = requireNotNull(eventsDao.getById(event.id)) {
                "Event not found: ${event.id}"
            }

            val now = System.currentTimeMillis()

            // RRULE/timing changes also drive occurrence regeneration below.
            // This deliberately uses byte comparison, unlike the SEQUENCE-bump
            // decision which compares the RRULE by meaning: a cosmetic rewrite
            // would regenerate to identical occurrences (wasteful but harmless),
            // whereas a spurious SEQUENCE bump re-notifies attendees. Don't
            // unify the two — they answer different questions.
            val rruleChanged = existingEvent.rrule != event.rrule ||
                    existingEvent.exdate != event.exdate ||
                    existingEvent.rdate != event.rdate
            val timingChanged = existingEvent.startTs != event.startTs ||
                    existingEvent.endTs != event.endTs ||
                    existingEvent.isAllDay != event.isAllDay

            // Bump SEQUENCE only for scheduling-significant changes so
            // attendees aren't re-notified for cosmetic edits. SequenceBumper
            // is the single source of truth; the wire serializer must not bump
            // again on top of this.
            val newSequence = SequenceBumper.nextSequence(existingEvent, event)

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

            recordCategoryUsage(eventToUpdate.categories, now)

            // Persist attendees when supplied. null = caller isn't touching the
            // attendee set (leave it alone — e.g. a reschedule); a list (incl.
            // empty) replaces it.
            if (attendees != null) {
                // Snapshot the pre-edit rows BEFORE the replace overwrites them:
                // the cascade guard needs their addresses, and removal needs the
                // dropped rows' captured delivery context to enqueue a CANCEL.
                val preEditRows = attendeesDao.getForEventOnce(event.id)
                val preEditMasterAddresses =
                    if (existingEvent.rrule != null && existingEvent.originalEventId == null) {
                        attendeeAddressSet(preEditRows)
                    } else {
                        emptySet()
                    }
                attendeesDao.replaceForEvent(event.id, attendees.map { it.copy(eventId = event.id) })
                // Uninvite: a guest dropped from a SYNCED event (on the wire)
                // owes an iTIP CANCEL. Enqueue each removed-and-synced row,
                // capturing its delivery context, so the push can deliver after
                // the attendee row is gone. recurrenceId = null (all-events /
                // series scope). A never-synced event has nothing on the wire.
                if (existingEvent.caldavUrl != null) {
                    enqueueRemovedAttendeeCancels(
                        eventId = event.id,
                        preEditRows = preEditRows,
                        survivors = attendees,
                        recurrenceId = null,
                        sequence = newSequence,
                    )
                }
                // ALL_EVENTS attendee edit on a recurring master: bring the
                // series' existing override rows into line with the new set.
                // Gated on recurring-master so a plain non-recurring edit
                // never runs the exception query.
                if (existingEvent.rrule != null && existingEvent.originalEventId == null) {
                    cascadeAttendeesToExceptions(event.id, attendees, preEditMasterAddresses)
                }
            }

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
     * Rename tag [from] to [to] everywhere and re-upload each affected syncable
     * event with its new tag, so the rename reaches the CalDAV server and the
     * user's other devices — exactly as a normal single-event edit already does.
     *
     * The tag string rewrite (in [org.onekash.kashcal.data.db.dao.CategoryDao])
     * and the per-event mark-and-queue run in ONE transaction so a partial
     * cascade can't leave an event rewritten-but-unqueued (a silent divergence).
     *
     * A tag is a cosmetic property, so SEQUENCE is never bumped (the wire
     * serializer and the iTIP outbox gate both key on SEQUENCE, so an unchanged
     * SEQUENCE fires no fresh attendee invite). Only events that can actually be
     * pushed are queued: local-only, read-only-calendar, and never-synced
     * (PENDING_CREATE) events are rewritten locally but not queued, and a
     * soft-deleted (PENDING_DELETE) event is left alone so its queued delete
     * isn't overwritten. An exception's update routes to its master (shared UID;
     * the push bundles the exception into the master's PUT), deduped so a master
     * and its exception queue the master once.
     *
     * @return the number of syncable events queued for UPDATE (0 if none — the
     *   caller can skip requesting a sync).
     */
    suspend fun renameCategory(from: String, to: String): Int {
        return database.withTransaction {
            val changedIds = categoryDao.renameTag(from, to)
            if (changedIds.isEmpty()) return@withTransaction 0

            val now = System.currentTimeMillis()
            // A heavily-used tag can carry thousands of events; chunk every
            // IN (:ids) query to stay under SQLite's 999-variable limit.
            val changedEvents = getEventsByIdsChunked(changedIds)
            // Route each changed event to its sync target: an exception shares
            // its master's UID and is bundled into the master's PUT, so the
            // master carries the update. Dedup so a master + its exception (both
            // carrying the tag) queue the master exactly once.
            val targetIds = changedEvents.map { it.originalEventId ?: it.id }.distinct()

            // Resolve targets and their calendars from the rows already in hand
            // plus one batch query for the extras — a master that doesn't itself
            // carry the tag isn't in changedEvents. Avoids per-target getById /
            // getById calendar reads inside the open write transaction.
            val eventsById = changedEvents.associateBy { it.id }.toMutableMap()
            val missingIds = targetIds.filter { it !in eventsById }
            if (missingIds.isNotEmpty()) {
                getEventsByIdsChunked(missingIds).forEach { eventsById[it.id] = it }
            }
            val targets = targetIds.mapNotNull { eventsById[it] }
            val calendarsById = targets.map { it.calendarId }.distinct()
                .chunked(SQL_IN_CHUNK)
                .flatMap { calendarsDao.getByIds(it) }
                .associateBy { it.id }

            var queued = 0
            for (target in targets) {
                // Never touch a soft-deleted event: re-stamping it PENDING_UPDATE
                // would resurrect it in the UI while its queued DELETE still
                // drains (server deletes it, device shows it active).
                if (target.syncStatus == SyncStatus.PENDING_DELETE) continue
                // A never-synced event already carries the new categories in its
                // pending CREATE; don't downgrade it to PENDING_UPDATE.
                if (target.syncStatus == SyncStatus.PENDING_CREATE) continue
                // Skip anything that can't be pushed: no calendar (orphaned),
                // the on-device local calendar, or a read-only subscription.
                val calendar = calendarsById[target.calendarId] ?: continue
                if (calendar.caldavUrl == LocalCalendarInitializer.LOCAL_CALENDAR_URL) continue
                if (calendar.isReadOnly) continue

                // Mark dirty (restamps local_modified_at AND updated_at so the
                // NEWEST_WINS resolver doesn't let a tied-SEQUENCE server edit
                // revert the rename) and queue the same UPDATE a cosmetic edit
                // would. SEQUENCE is deliberately untouched.
                eventsDao.updateSyncStatus(target.id, SyncStatus.PENDING_UPDATE, now)
                queueOperation(target.id, PendingOperation.OPERATION_UPDATE)
                queued++
            }
            queued
        }
    }

    /** Batch-load events by id, chunked under SQLite's IN-clause variable limit. */
    private suspend fun getEventsByIdsChunked(ids: List<Long>): List<Event> =
        ids.chunked(SQL_IN_CHUNK).flatMap { eventsDao.getByIds(it) }

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
     * @param attendees The user-edited attendee set for THIS occurrence, or
     *   null when the caller isn't touching attendees. A non-null list is
     *   persisted to the exception's own rows (a per-occurrence guest set
     *   that may diverge from the series). null preserves the prior behavior:
     *   a new exception is seeded with the master's set, a re-edited exception
     *   keeps its existing rows.
     * @return The created exception event
     */
    suspend fun editSingleOccurrence(
        masterEventId: Long,
        occurrenceTimeMs: Long,
        modifiedEvent: Event,
        isLocal: Boolean = false,
        attendees: List<Attendee>? = null
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
                // Bump SEQUENCE when this re-edit is iTIP-relevant, relative to
                // the exception's own prior revision so its counter climbs
                // monotonically across successive edits. modifiedEvent carries
                // the master's sequence (the coordinator derives it from the
                // master), so anchor the floor on the existing exception rather
                // than letting nextSequence read modifiedEvent.sequence.
                val newSequence = if (SequenceBumper.shouldBump(existingException, modifiedEvent)) {
                    existingException.sequence + 1
                } else {
                    existingException.sequence
                }
                val updatedEvent = modifiedEvent.copy(
                    id = existingException.id,
                    uid = existingException.uid, // Preserve UID (should equal master UID)
                    calendarId = masterEvent.calendarId,
                    originalEventId = masterEventId,
                    originalInstanceTime = occurrenceTimeMs,
                    rrule = null, // Exception cannot have RRULE
                    exdate = null,
                    rdate = null,
                    sequence = newSequence,
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
                // Rescheduling one occurrence is an organizer timing change, so
                // the override must advance SEQUENCE (RFC 5546 §2.1.4) just like
                // the master-edit and this-and-future paths. The baseline is the
                // PRISTINE occurrence — the master projected onto this
                // occurrence's start/end with recurrence fields cleared to match
                // the exception shape — so the structural master→exception
                // difference isn't mistaken for an edit (which would otherwise
                // bump on a cosmetic-only change and re-notify attendees).
                val pristineOccurrence = masterEvent.projectOntoOccurrence(occurrenceTimeMs)
                val newSequence = SequenceBumper.nextSequence(pristineOccurrence, modifiedEvent)
                val exceptionEvent = modifiedEvent.copy(
                    id = 0, // New event
                    uid = masterEvent.uid, // Same UID as master (RFC 5545 requirement)
                    calendarId = masterEvent.calendarId,
                    originalEventId = masterEventId,
                    originalInstanceTime = occurrenceTimeMs,
                    rrule = null, // Exception cannot have RRULE
                    exdate = null,
                    rdate = null,
                    sequence = newSequence,
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

            recordCategoryUsage(createdException.categories, now)

            // Link occurrence to exception AND update occurrence times
            // Using the Event overload updates start_ts, end_ts, start_day, end_day
            // to match the exception's modified times (critical for correct display)
            occurrenceGenerator.linkException(masterEventId, occurrenceTimeMs, createdException)

            // Attendee rows for this occurrence's override VEVENT.
            // - A non-null [attendees] is the user's per-occurrence edit (the
            //   guest set for THIS instance, which may diverge from the
            //   series); persist it verbatim on either branch.
            // - null means the caller isn't touching attendees: a NEW
            //   exception is seeded with the master's set so the bundled
            //   override pushes the series' invitee list (mirrors splitSeries);
            //   a re-edited existing exception keeps its own rows (which may
            //   already differ per-instance) — don't clobber them.
            if (attendees != null) {
                // Per-occurrence uninvite: a guest dropped from THIS instance's
                // set (vs whatever it carried before) owes a CANCEL scoped to
                // the occurrence (RECURRENCE-ID = occurrenceTimeMs), so the
                // cancel reaches them for this instance only — the series keeps
                // them. Diff against the pre-edit rows (the existing exception's
                // set, or the master's set this exception was seeded from for a
                // brand-new override). Gated on the master being synced.
                if (masterEvent.caldavUrl != null) {
                    val preEditOccurrenceRows = if (existingException != null) {
                        attendeesDao.getForEventOnce(existingException.id)
                    } else {
                        attendeesDao.getForEventOnce(masterEventId)
                    }
                    enqueueRemovedAttendeeCancels(
                        eventId = masterEventId,
                        preEditRows = preEditOccurrenceRows,
                        survivors = attendees,
                        recurrenceId = occurrenceTimeMs,
                        sequence = createdException.sequence,
                    )
                }
                attendeesDao.replaceForEvent(
                    exceptionId,
                    attendees.map { it.copy(id = 0, eventId = exceptionId) }
                )
            } else if (existingException == null) {
                val masterAttendees = attendeesDao.getForEventOnce(masterEventId)
                if (masterAttendees.isNotEmpty()) {
                    attendeesDao.replaceForEvent(
                        exceptionId,
                        masterAttendees.map { it.copy(id = 0, eventId = exceptionId) }
                    )
                }
            }

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

            // Cancel the occurrence row. When an exception exists, the
            // row's start_ts has already been moved to the exception's
            // modified time by linkException, so a tolerance-time match
            // on occurrenceTimeMs (the ORIGINAL instance time) would
            // miss it. Match by exception_event_id instead.
            if (exception != null) {
                occurrenceGenerator.cancelOccurrenceByException(exception.id)
                eventsDao.deleteById(exception.id)
            } else {
                occurrenceGenerator.cancelOccurrence(masterEventId, occurrenceTimeMs)
            }

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
        isLocal: Boolean = false,
        attendees: List<Attendee>? = null
    ): Event {
        return database.withTransaction {
            val masterEvent = requireNotNull(eventsDao.getById(masterEventId)) {
                "Master event not found: $masterEventId"
            }

            require(masterEvent.isRecurring) { "Event is not recurring: $masterEventId" }

            val rrule = checkNotNull(masterEvent.rrule) {
                "Recurring event has no RRULE: ${masterEvent.id}"
            }

            // First-occurrence shortcut: a split at-or-before the master's
            // own start is just an "edit all events" with no rrule
            // truncation needed.
            if (splitTimeMs <= masterEvent.startTs) {
                return@withTransaction updateMasterInPlace(masterEvent, modifiedEvent, isLocal, attendees)
            }

            // pastCount only matters for the COUNT branch of
            // splitRruleAtTime. Skip the engine call entirely on
            // UNTIL/unbounded RRULEs to avoid materializing 365+ Date
            // objects we'd then discard.
            //
            // RFC 5545 §3.3.10: COUNT counts *rule recurrences*, not
            // post-EXDATE survivors. We pass exdates=emptyList() to
            // expandForPreview so the count reflects the rule alone;
            // otherwise an EXDATE in the past range would silently
            // shrink master's new COUNT and drop a visible past
            // occurrence on re-expansion (the EXDATE filter is applied
            // after the COUNT cap).
            val isCountRule = rrule.contains("COUNT=")
            val pastCount = if (isCountRule) {
                occurrenceGenerator.expandForPreview(
                    rrule = rrule,
                    dtstartMs = masterEvent.startTs,
                    rangeStartMs = masterEvent.startTs - 1_000L,
                    rangeEndMs = splitTimeMs - 1L,
                    exdates = emptyList(),
                    timezone = masterEvent.timezone,
                    isAllDay = masterEvent.isAllDay,
                ).size
            } else {
                0
            }

            // Degenerate COUNT split (pastCount==0 or pastCount>=total)
            // would yield invalid COUNT=0 on master or new series.
            // Fall back to in-place ALL_EVENTS update on the master.
            if (RruleUtils.isDegenerateCountSplit(rrule, pastCount)) {
                return@withTransaction updateMasterInPlace(masterEvent, modifiedEvent, isLocal, attendees)
            }

            // Split the RRULE so the total instance count is preserved
            // across the split. modifiedEvent.rrule == null means the
            // user picked "Does not repeat" on the form — the new row
            // becomes non-recurring.
            val (truncatedRrule, splitNewSeriesRrule) = RruleUtils.splitRruleAtTime(
                masterRrule = rrule,
                userRrule = modifiedEvent.rrule,
                untilMs = splitTimeMs - 1L,
                pastCount = pastCount,
                isAllDay = masterEvent.isAllDay,
            )

            val now = System.currentTimeMillis()
            eventsDao.updateRrule(masterEventId, truncatedRrule, now)

            // Delete occurrences at/after split point
            occurrencesDao.deleteForEventAfter(masterEventId, splitTimeMs)

            deleteFutureExceptions(masterEventId, splitTimeMs)

            // Update master sync status
            if (!isLocal && masterEvent.syncStatus != SyncStatus.PENDING_CREATE) {
                eventsDao.updateSyncStatus(masterEventId, SyncStatus.PENDING_UPDATE, now)
                queueOperation(masterEventId, PendingOperation.OPERATION_UPDATE)
            }

            // The new event for "this and all future" carries the
            // helper's emitted rrule. null is intentional — it means
            // either the user dropped recurrence ("Does not repeat")
            // or the master was unbounded with no user edit, both of
            // which leave the new row non-recurring.
            val newSeriesRrule = splitNewSeriesRrule
            val newEvent = modifiedEvent.copy(
                id = 0,
                uid = generateUid(),
                calendarId = masterEvent.calendarId,
                // The form's lambda emits modifiedEvent.startTs as the user's
                // intended first-occurrence time on the split day (e.g.,
                // "Jun 02 08:00" when editing the Jun 02 occurrence). Use it
                // verbatim — adding splitTimeMs would shift the whole series
                // by the master-to-split-day delta and land it days later.
                startTs = modifiedEvent.startTs,
                rrule = newSeriesRrule,
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

            recordCategoryUsage(createdEvent.categories, now)

            // Carry attendees forward to the new series. Attendees live
            // in their own Room table; eventsDao.insert(Event) doesn't
            // touch them, so without this the new series PUTs to the
            // server with no attendees and the next pull drops them.
            //
            // A non-null [attendees] is the user's edited set (the
            // this-and-future attendee-edit path) and takes precedence;
            // null means the caller isn't touching attendees, so copy the
            // master's set verbatim.
            val newSeriesAttendees = attendees ?: attendeesDao.getForEventOnce(masterEventId)
            if (newSeriesAttendees.isNotEmpty()) {
                attendeesDao.replaceForEvent(
                    newEventId,
                    newSeriesAttendees.map { it.copy(id = 0, eventId = newEventId) }
                )
            }

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
     * Apply [modifiedEvent]'s fields onto the master row in place,
     * preserving id/uid/calendar. Used by [splitSeries] when the split
     * point lies at-or-before the first occurrence, or when a
     * COUNT-based RRULE would yield COUNT=0 on either side — both
     * collapse to "edit all events in this series."
     *
     * SEQUENCE bumps on RRULE/timing changes match the public
     * [updateEvent] path so iTIP recipients see a monotonically
     * increasing SEQUENCE per RFC 5545 §3.8.7.4.
     *
     * Caller must already be inside a `database.withTransaction { … }`.
     */
    private suspend fun updateMasterInPlace(
        masterEvent: Event,
        modifiedEvent: Event,
        isLocal: Boolean,
        attendees: List<Attendee>? = null,
    ): Event {
        val now = System.currentTimeMillis()
        val newSyncStatus = when {
            isLocal -> SyncStatus.SYNCED
            masterEvent.syncStatus == SyncStatus.PENDING_CREATE -> SyncStatus.PENDING_CREATE
            else -> SyncStatus.PENDING_UPDATE
        }
        // Bump SEQUENCE only when the change is iTIP-relevant. Title/notes/etc.
        // don't require a bump. SequenceBumper is the shared predicate with
        // updateEvent so iTIP recipients see a monotonically increasing
        // SEQUENCE only on scheduling changes.
        val newSequence = SequenceBumper.nextSequence(masterEvent, modifiedEvent)
        val updated = modifiedEvent.copy(
            id = masterEvent.id,
            uid = masterEvent.uid,
            calendarId = masterEvent.calendarId,
            originalEventId = null,
            originalInstanceTime = null,
            syncStatus = newSyncStatus,
            sequence = newSequence,
            dtstamp = now,
            createdAt = masterEvent.createdAt,
            updatedAt = now,
            localModifiedAt = now,
        )
        eventsDao.update(updated)
        // A non-null [attendees] is the user's edited set (the collapsed
        // this-and-future / first-occurrence path); persist it. null leaves
        // the existing attendee rows alone.
        if (attendees != null) {
            // Snapshot the series' pre-edit addresses before the replace so the
            // cascade can skip a deliberately-customized override.
            val preEditMasterAddresses = attendeeAddressSet(attendeesDao.getForEventOnce(masterEvent.id))
            attendeesDao.replaceForEvent(masterEvent.id, attendees.map { it.copy(id = 0, eventId = masterEvent.id) })
            cascadeAttendeesToExceptions(masterEvent.id, attendees, preEditMasterAddresses)
        }
        occurrenceGenerator.regenerateOccurrences(updated)
        if (!isLocal && masterEvent.syncStatus != SyncStatus.PENDING_CREATE) {
            queueOperation(masterEvent.id, PendingOperation.OPERATION_UPDATE)
        }
        return updated
    }

    /**
     * Cascade an all-events attendee change onto the series' existing
     * exception (override) rows. Time-only exceptions are seeded with the
     * master's attendee list at creation; when the organizer edits the
     * series-wide guest list, those overrides must be brought into line so a
     * shifted occurrence doesn't keep advertising a stale invitee set.
     *
     * An override whose own attendee set was deliberately customized
     * (per-occurrence guest editing) must NOT be clobbered. We tell the two
     * apart with [preEditMasterAddresses] — the series' attendee addresses
     * BEFORE this edit: an override still matching that set was merely seeded
     * and is safe to cascade; one that diverges is a deliberate customization
     * and is skipped. Comparison is the canonical-address SET only (order- and
     * PARTSTAT-insensitive) so a seeded override carrying server-stamped
     * PARTSTAT/receipt differences still cascades.
     *
     * Caller must already be inside a `database.withTransaction { … }`.
     */
    private suspend fun cascadeAttendeesToExceptions(
        masterEventId: Long,
        attendees: List<Attendee>,
        preEditMasterAddresses: Set<String>,
    ) {
        val exceptions = eventsDao.getExceptionsForMaster(masterEventId)
        for (exception in exceptions) {
            val exceptionAddresses = attendeeAddressSet(attendeesDao.getForEventOnce(exception.id))
            // Skip a deliberately-customized override: its guest set diverges
            // from what the series carried before this edit.
            if (exceptionAddresses != preEditMasterAddresses) continue
            attendeesDao.replaceForEvent(
                exception.id,
                attendees.map { it.copy(id = 0, eventId = exception.id) }
            )
        }
    }

    /** Canonical address set of an attendee list (order- and PARTSTAT-insensitive). */
    private fun attendeeAddressSet(attendees: List<Attendee>): Set<String> =
        attendees.map { org.onekash.kashcal.util.AddressNormalizer.canonical(it.address) }.toSet()

    /**
     * Enqueue an iTIP CANCEL for each attendee dropped from a synced event.
     *
     * A removed guest's row is replaced out of the attendee set, so the dropped
     * row no longer exists to carry a client-side CANCEL. This captures each
     * removed recipient — and the delivery context (schedule_agent/status) read
     * from the pre-edit row — into the pending_cancels queue, which the push
     * drains after a successful PUT. The capture is idempotent (upsert keyed on
     * event+recurrence+address), so re-saving the same removal doesn't duplicate
     * the cancel.
     *
     * [sequence] is the event SEQUENCE the CANCEL goes out at (the iTIP builder
     * increments it on the wire per RFC 5546 §2.1.4). [recurrenceId] scopes the
     * cancel: null = series/all-events, set = a single occurrence.
     *
     * Caller must already be inside a `database.withTransaction { … }` and must
     * gate on the event being synced (a never-synced event has nothing on the
     * wire to cancel).
     */
    private suspend fun enqueueRemovedAttendeeCancels(
        eventId: Long,
        preEditRows: List<Attendee>,
        survivors: List<Attendee>,
        recurrenceId: Long?,
        sequence: Int,
    ) {
        val survivorAddresses = attendeeAddressSet(survivors)
        for (row in preEditRows) {
            if (org.onekash.kashcal.util.AddressNormalizer.canonical(row.address) in survivorAddresses) continue
            pendingCancelsDao.upsert(
                org.onekash.kashcal.data.db.entity.PendingCancel(
                    eventId = eventId,
                    recurrenceId = recurrenceId,
                    address = row.address,
                    scheduleAgent = row.scheduleAgent,
                    scheduleStatus = row.scheduleStatus,
                    sequence = sequence,
                )
            )
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

            deleteFutureExceptions(masterEventId, fromTimeMs)

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

            // Block a cross-account move of an event that has attendees. The move
            // would carry the SOURCE account's ORGANIZER onto a CREATE against the
            // TARGET account; scheduling servers reject/rewrite a foreign
            // organizer and either re-invite everyone under a new identity or
            // strip the guests (RFC 6638 / iTIP). We don't rewrite organizer
            // identity on move, so this can only mis-schedule. Users who want the
            // event on another account can duplicate it there (fresh UID, the new
            // account becomes organizer, guests re-invited cleanly). Same-account
            // moves are safe (attendees ride along on the unchanged eventId) and
            // are not blocked. Defense in depth: the UI also disables the
            // different-account picker options for attendee events.
            // Count attendees on the master AND any exception rows: a recurring
            // event can carry a per-occurrence guest only on an exception (its
            // own eventId), which the cross-account CREATE serializes too — so
            // the master's count alone would miss it and let the move through.
            val hasAttendees = attendeesDao.countForEvent(eventId) > 0 ||
                eventsDao.getExceptionsForMaster(eventId)
                    .any { attendeesDao.countForEvent(it.id) > 0 }
            val crossAccountWithAttendees = !isSameAccount && hasAttendees
            require(!crossAccountWithAttendees) {
                "Cannot move an event with attendees to a different account " +
                    "(would misdeliver invitations); duplicate it instead"
            }

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

    /**
     * Delete exception events whose original instance time falls at or
     * after [fromTimeMs]. Used by both `splitSeries` (truncate-for-edit)
     * and `deleteThisAndFuture` (truncate-for-delete) — exceptions in
     * the truncated half belong to a series the master no longer
     * expands, so they must go.
     *
     * Caller must already be inside `database.withTransaction { … }`.
     */
    private suspend fun deleteFutureExceptions(masterEventId: Long, fromTimeMs: Long) {
        val exceptions = eventsDao.getExceptionsForMaster(masterEventId)
        for (exception in exceptions) {
            if (exception.originalInstanceTime != null &&
                exception.originalInstanceTime >= fromTimeMs
            ) {
                eventsDao.deleteById(exception.id)
            }
        }
    }

    private companion object {
        // SQLite caps a statement at 999 bind variables; chunk IN (:ids) queries
        // below that so a rename touching thousands of events can't overflow it.
        const val SQL_IN_CHUNK = 500
    }
}
