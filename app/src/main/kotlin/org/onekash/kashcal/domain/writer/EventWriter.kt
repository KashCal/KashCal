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
 * Writes Room events and keeps their occurrences, attendees and sync queue in step.
 *
 * Covers creating events and imported series, updates, deletes, single-occurrence edits
 * (exceptions) and cancels (EXDATE), "this and all future" splits and deletes, calendar moves,
 * tag renames, RSVPs, attendee reminders and lookback cleanup.
 *
 * Multi-step event writes run in one database transaction, and a change the server must see
 * queues a pending operation (none for a local calendar). [saveAttendeeReminders],
 * [recordCategoryUsage] and [cleanupEventsOutsideLookback] queue nothing and open no transaction.
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
    private val recentChangesDao by lazy { database.recentChangesDao() }

    /**
     * Records each of an event's [categories] as used at [now], for the recency-ordered tag
     * suggestions and the management screen. Called inside the save transaction. The upsert
     * keeps a tag's color: re-saving an event with a recolored tag must never reset it to null.
     * Blank names and empty or null lists are a no-op.
     */
    private suspend fun recordCategoryUsage(categories: List<String>?, now: Long) {
        categories?.forEach { name ->
            if (name.isNotBlank()) categoryDao.touch(name, now)
        }
    }

    /**
     * Records [categories] in the tag registry as used now. Tags on device events have no
     * registry of their own, so this is what gives a new one a suggestion entry and makes it
     * colorable. Same color-keeping upsert as the in-transaction path.
     */
    suspend fun recordCategoryUsage(categories: List<String>) {
        recordCategoryUsage(categories, System.currentTimeMillis())
    }

    /**
     * Result of [createImportedSeries]: the saved master and exceptions with their row ids, so
     * callers can schedule reminders against the real events.
     */
    data class ImportedSeries(
        val master: Event,
        val exceptions: List<Event>
    )

    /**
     * Creates [event] (its id is ignored), expands its occurrences and, unless [isLocal],
     * queues a CREATE. A blank UID gets a generated one.
     *
     * @param attendees null leaves the attendee set alone; a list, even empty, replaces it.
     * @return the created event with its row id
     */
    suspend fun createEvent(
        event: Event,
        isLocal: Boolean = false,
        attendees: List<org.onekash.kashcal.data.db.entity.Attendee>? = null
    ): Event {
        return database.withTransaction {
            val eventWithUid = if (event.uid.isBlank()) {
                event.copy(uid = generateUid())
            } else {
                event
            }

            val now = System.currentTimeMillis()
            val eventToInsert = eventWithUid.copy(
                syncStatus = if (isLocal) SyncStatus.SYNCED else SyncStatus.PENDING_CREATE,
                dtstamp = now,
                createdAt = now,
                updatedAt = now,
                localModifiedAt = now
            )

            val eventId = eventsDao.insert(eventToInsert)
            val createdEvent = eventToInsert.copy(id = eventId)

            recordCategoryUsage(createdEvent.categories, now)

            if (attendees != null) {
                attendeesDao.replaceForEvent(eventId, attendees.map { it.copy(eventId = eventId) })
            }

            // Round startTs down to seconds and subtract 1 second so DTSTART is included
            // (OccurrenceGenerator works in seconds internally).
            val rangeStartSeconds = (createdEvent.startTs / 1000) - 1
            val rangeStart = rangeStartSeconds * 1000
            // Same forward window as the pull.
            val rangeEnd = now + PullStrategy.OCCURRENCE_EXPANSION_MS
            occurrenceGenerator.generateOccurrences(createdEvent, rangeStart, rangeEnd)

            if (!isLocal) {
                queueOperation(eventId, PendingOperation.OPERATION_CREATE)
            }

            createdEvent
        }
    }

    /**
     * Saves an ICS-imported recurring event as one linked series: a master plus its
     * RECURRENCE-ID exceptions, all sharing one UID, in one transaction.
     *
     * The caller (EventCoordinator) has already set a fresh shared UID, never the file's (a
     * fresh UID avoids duplicate-UID PUT collisions on servers like iCloud/Nextcloud), and
     * resolved default reminders. This method only saves and links:
     *
     * - The master is created like any event ([createEvent]): occurrences expanded and, for a
     *   synced calendar, one CREATE queued. The push serializes an event whose
     *   [Event.originalEventId] is set inside its master's resource, so no exception gets an
     *   operation of its own.
     * - Each exception is inserted with the master's UID and row id as [Event.originalEventId],
     *   its [Event.originalInstanceTime] kept, recurrence fields cleared, and marked
     *   [SyncStatus.SYNCED]. [OccurrenceGenerator.linkException] attaches it to the master's
     *   occurrence for that instant, so the day shows only the exception, not both.
     *
     * @param master the recurring master (id ignored; must have an RRULE)
     * @param exceptions the exceptions (ids ignored; each must carry a non-null
     *   [Event.originalInstanceTime])
     * @param isLocal true when the target calendar is local-only (no sync)
     * @return the saved master and exceptions with their row ids
     */
    suspend fun createImportedSeries(
        master: Event,
        exceptions: List<Event>,
        isLocal: Boolean = false
    ): ImportedSeries {
        require(master.rrule != null) { "createImportedSeries master must be recurring" }
        return database.withTransaction {
            val now = System.currentTimeMillis()

            // createEvent stamps timestamps and sync status and joins this transaction.
            val savedMaster = createEvent(master, isLocal)
            val masterId = savedMaster.id

            val savedExceptions = exceptions.map { exception ->
                // The caller routes only real RECURRENCE-ID exceptions here.
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
                    // Pushed inside the master's resource, so never queued on its own.
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

                occurrenceGenerator.linkException(masterId, originalInstanceTime, savedException)
                savedException
            }

            ImportedSeries(savedMaster, savedExceptions)
        }
    }

    /**
     * Updates an existing event, bumps SEQUENCE for a scheduling change ([SequenceBumper]) and
     * regenerates occurrences when its recurrence or times changed.
     *
     * Queues an UPDATE unless [isLocal] or the event is still PENDING_CREATE (its CREATE carries
     * the edit).
     *
     * @param attendees null leaves the attendee set alone (for example a reschedule); a list,
     *   even empty, replaces it. Guests dropped from a synced event get a queued CANCEL, and on
     *   a recurring master the change cascades to exceptions ([cascadeAttendeesToExceptions]).
     * @return the updated event
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

            // These drive occurrence regeneration and compare bytes, while the SEQUENCE
            // decision compares the RRULE by meaning. A cosmetic rewrite here only
            // regenerates identical occurrences; a spurious SEQUENCE bump re-notifies
            // attendees. Don't unify the two: they answer different questions.
            val rruleChanged = existingEvent.rrule != event.rrule ||
                    existingEvent.exdate != event.exdate ||
                    existingEvent.rdate != event.rdate
            val timingChanged = existingEvent.startTs != event.startTs ||
                    existingEvent.endTs != event.endTs ||
                    existingEvent.isAllDay != event.isAllDay

            // SequenceBumper is the source of truth; the wire serializer must not bump
            // again on top of this.
            val newSequence = SequenceBumper.nextSequence(existingEvent, event)

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

            eventsDao.update(eventToUpdate)

            recordCategoryUsage(eventToUpdate.categories, now)

            if (attendees != null) {
                // Read the pre-edit rows before the replace: the cascade needs their
                // addresses, and each CANCEL needs its dropped row's delivery context.
                val preEditRows = attendeesDao.getForEventOnce(event.id)
                val preEditMasterAddresses =
                    if (existingEvent.rrule != null && existingEvent.originalEventId == null) {
                        attendeeAddressSet(preEditRows)
                    } else {
                        emptySet()
                    }
                attendeesDao.replaceForEvent(event.id, attendees.map { it.copy(eventId = event.id) })
                // A guest dropped from an event already on the server owes an iTIP
                // CANCEL for the whole series (recurrenceId = null). A never-synced
                // event has nothing on the server to cancel.
                if (existingEvent.caldavUrl != null) {
                    enqueueRemovedAttendeeCancels(
                        eventId = event.id,
                        preEditRows = preEditRows,
                        survivors = attendees,
                        recurrenceId = null,
                        sequence = newSequence,
                    )
                }
                // Only a recurring master has exceptions to bring into line.
                if (existingEvent.rrule != null && existingEvent.originalEventId == null) {
                    cascadeAttendeesToExceptions(event.id, attendees, preEditMasterAddresses)
                }
            }

            if (rruleChanged || timingChanged) {
                occurrenceGenerator.regenerateOccurrences(eventToUpdate)
            }

            if (!isLocal && existingEvent.syncStatus != SyncStatus.PENDING_CREATE) {
                queueOperation(event.id, PendingOperation.OPERATION_UPDATE)
            }

            eventToUpdate
        }
    }

    /**
     * Renames tag [from] to [to] everywhere and queues each affected syncable event for UPDATE,
     * so the rename reaches the server and the user's other devices like a normal edit.
     *
     * The tag rewrite ([org.onekash.kashcal.data.db.dao.CategoryDao.renameTag]) and the
     * per-event mark-and-queue run in one transaction, so no event is left rewritten but
     * unqueued.
     *
     * A tag is cosmetic, so SEQUENCE is never bumped and the iTIP outbox sends no fresh invite.
     * Events on no calendar, the local calendar or a read-only calendar, and PENDING_CREATE
     * events, are rewritten but not queued; a PENDING_DELETE event isn't re-stamped, so its
     * queued delete stands. An exception's update goes to its master (shared UID, one PUT),
     * once per master.
     *
     * @return the number of events queued for UPDATE; 0 means the caller can skip the sync.
     */
    suspend fun renameCategory(from: String, to: String): Int {
        return database.withTransaction {
            val changedIds = categoryDao.renameTag(from, to)
            if (changedIds.isEmpty()) return@withTransaction 0

            val now = System.currentTimeMillis()
            val changedEvents = getEventsByIdsChunked(changedIds)
            // An exception is pushed inside its master's PUT, so the master carries the
            // update, queued once even when it and its exceptions all carry the tag.
            val targetIds = changedEvents.map { it.originalEventId ?: it.id }.distinct()

            // A master that doesn't carry the tag itself isn't in changedEvents, so load
            // those in one batch rather than a read per target inside the transaction.
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
                // Re-stamping a soft-deleted event PENDING_UPDATE would show it again
                // while its queued DELETE removes it from the server.
                if (target.syncStatus == SyncStatus.PENDING_DELETE) continue
                // A never-synced event already carries the new categories in its
                // pending CREATE; don't downgrade it to PENDING_UPDATE.
                if (target.syncStatus == SyncStatus.PENDING_CREATE) continue
                // Skip what can't be pushed: no calendar, the local calendar, or a
                // read-only calendar.
                val calendar = calendarsById[target.calendarId] ?: continue
                if (calendar.caldavUrl == LocalCalendarInitializer.LOCAL_CALENDAR_URL) continue
                if (calendar.isReadOnly) continue

                // updateSyncStatus restamps local_modified_at and updated_at, so the
                // NEWEST_WINS resolver doesn't let a server edit with the same SEQUENCE
                // revert the rename. SEQUENCE stays as is.
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
     * Writes the user's RSVP for an event they're attending.
     *
     * Sets the matching attendee row's PARTSTAT at once so the chips show it, then queues a
     * PARTSTAT-only operation that the push turns into a PUT changing only that PARTSTAT
     * (`IcsPatcher.patchAttendeeReply`); every other ATTENDEE, ORGANIZER, SUMMARY and so on
     * goes back as the server holds it. [partstat] may be any case; it is stored uppercase
     * (RFC 5545 §3.2.12).
     *
     * @return false when no attendee row matches [account], so the caller can show that the
     *   user isn't on the attendee list
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

            // Replace the set with the same rows, only the match's PARTSTAT changed,
            // so the chips' Flow sees one emission.
            val updatedRows = rows.map { row ->
                if (row.id == matching.id) row.copy(partstat = canonical) else row
            }
            attendeesDao.replaceForEvent(eventId, updatedRows)

            // Capture the URL at queue time so the operation doesn't depend on the event
            // row still holding it when the push runs.
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
     * Sets the user's own reminders on an event they attend (RFC 5545 §3.6.6: a VALARM is
     * per attendee, not part of the organizer's event).
     *
     * Local only: writes `reminders` and `alarm_count`, leaves `sync_status` alone and queues
     * nothing. A full-event PUT from the attendee side would rewrite the server's ATTENDEE list
     * (servers route by ORGANIZER mailto), the problem the PARTSTAT-only RSVP path avoids; no
     * VALARM-only patcher exists yet. The device alarms fire either way.
     *
     * @param reminders ISO-8601 durations such as `-PT15M`, in the user's order; empty clears
     *   them. `EventCoordinator.saveAttendeeReminders` converts minutes to this form.
     */
    suspend fun saveAttendeeReminders(eventId: Long, reminders: List<String>) {
        val now = System.currentTimeMillis()
        // Same JSON shape as `Converters.fromStringList`, so Room reads it back.
        val remindersJson = if (reminders.isEmpty()) null else Json.encodeToString(reminders)
        eventsDao.updateRemindersAndAlarmCount(
            id = eventId,
            remindersJson = remindersJson,
            alarmCount = reminders.size,
            now = now
        )
    }

    /**
     * Deletes an event.
     *
     * A local or never-synced (PENDING_CREATE) event is removed at once. Any other is marked
     * PENDING_DELETE, loses its occurrences and queues a DELETE; the row stays until the push
     * deletes it on the server.
     */
    suspend fun deleteEvent(eventId: Long, isLocal: Boolean = false) {
        database.withTransaction {
            val event = requireNotNull(eventsDao.getById(eventId)) {
                "Event not found: $eventId"
            }

            if (isLocal || event.syncStatus == SyncStatus.PENDING_CREATE) {
                // The FK cascade removes its occurrences, exceptions, attendees, scheduled
                // reminders and queued CANCELs.
                eventsDao.deleteById(eventId)
            } else {
                val now = System.currentTimeMillis()
                eventsDao.markForDeletion(eventId, now)

                occurrencesDao.deleteForEvent(eventId)

                queueOperation(eventId, PendingOperation.OPERATION_DELETE)
            }
        }
    }

    /**
     * Edits one occurrence of a recurring event by creating its exception, or updating the
     * existing one, linked to the master through `originalEventId`.
     *
     * The occurrence row is pointed at the exception, and the master (not the exception) is
     * queued for UPDATE when it is on the server.
     *
     * @param occurrenceTimeMs the occurrence's original start time
     * @param modifiedEvent the edited fields (title, time and so on)
     * @param attendees this occurrence's edited guest set, saved on the exception's own rows
     *   and free to differ from the series. null leaves attendees alone: a new exception gets
     *   the master's set, a re-edited one keeps its rows.
     * @return the exception
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

            val existingException = eventsDao.getExceptionForOccurrence(masterEventId, occurrenceTimeMs)

            val (exceptionId, createdException) = if (existingException != null) {
                // Re-edit of an occurrence already changed. modifiedEvent carries the
                // master's sequence (the coordinator derives it from the master), so bump
                // from the exception's own SEQUENCE, keeping its counter monotonic.
                val newSequence = if (SequenceBumper.shouldBump(existingException, modifiedEvent)) {
                    existingException.sequence + 1
                } else {
                    existingException.sequence
                }
                val updatedEvent = modifiedEvent.copy(
                    id = existingException.id,
                    uid = existingException.uid, // Should equal the master's UID
                    calendarId = masterEvent.calendarId,
                    originalEventId = masterEventId,
                    originalInstanceTime = occurrenceTimeMs,
                    rrule = null, // An exception has no RRULE
                    exdate = null,
                    rdate = null,
                    sequence = newSequence,
                    // Pushed inside the master's resource, never on its own.
                    syncStatus = SyncStatus.SYNCED,
                    dtstamp = now,
                    createdAt = existingException.createdAt,
                    updatedAt = now,
                    localModifiedAt = now
                )
                eventsDao.update(updatedEvent)
                Pair(existingException.id, updatedEvent)
            } else {
                // RFC 5545: the exception has the master's UID and is told apart by
                // RECURRENCE-ID. A scheduling change to one occurrence advances its
                // SEQUENCE (RFC 5546 §2.1.4) as on the other edit paths. The baseline is
                // the unedited occurrence ([Event.projectOntoOccurrence]), so the
                // master-to-exception difference in shape doesn't read as an edit and a
                // cosmetic change doesn't re-notify attendees.
                val pristineOccurrence = masterEvent.projectOntoOccurrence(occurrenceTimeMs)
                val newSequence = SequenceBumper.nextSequence(pristineOccurrence, modifiedEvent)
                val exceptionEvent = modifiedEvent.copy(
                    id = 0,
                    uid = masterEvent.uid, // RFC 5545: same UID as the master
                    calendarId = masterEvent.calendarId,
                    originalEventId = masterEventId,
                    originalInstanceTime = occurrenceTimeMs,
                    rrule = null, // An exception has no RRULE
                    exdate = null,
                    rdate = null,
                    sequence = newSequence,
                    // Pushed inside the master's resource; the master is queued below.
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

            // The Event overload also moves the occurrence's start_ts, end_ts, start_day
            // and end_day to the exception's times; without that the day shows the old time.
            occurrenceGenerator.linkException(masterEventId, occurrenceTimeMs, createdException)

            // With attendees null, a new exception gets the master's set so its VEVENT
            // pushes the series' guests (as splitSeries does); a re-edited exception keeps
            // rows that may already differ from the series.
            if (attendees != null) {
                // A guest dropped from this occurrence owes a CANCEL for this occurrence
                // only (RECURRENCE-ID = occurrenceTimeMs); the series keeps them. The
                // pre-edit set is the existing exception's, or the master's for a new
                // exception. Only a master on the server has anything to cancel.
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

            // Queue the master, not the exception: the push serializes the master with all
            // its exceptions (IcsPatcher.serializeWithExceptions) as one .ics resource. A
            // master not yet on the server carries the exception in its CREATE.
            if (!isLocal && masterEvent.caldavUrl != null) {
                if (masterEvent.syncStatus == SyncStatus.SYNCED) {
                    eventsDao.updateSyncStatus(masterEventId, SyncStatus.PENDING_UPDATE, now)
                }
                queueOperation(masterEventId, PendingOperation.OPERATION_UPDATE)
            }

            createdException
        }
    }

    /**
     * Deletes one occurrence of a recurring event by adding it to the master's EXDATE.
     *
     * Deletes that occurrence's exception if it has one, cancels the occurrence row, and
     * queues an UPDATE on the master unless [isLocal] or the master is PENDING_CREATE.
     *
     * @param occurrenceTimeMs the occurrence's original start time
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

            val newExdate = addToExdate(masterEvent.exdate, occurrenceTimeMs, masterEvent.isAllDay)
            val now = System.currentTimeMillis()

            eventsDao.updateExdate(masterEventId, newExdate, now)

            // An exception for an excluded occurrence would be left orphaned.
            val exception = eventsDao.getExceptionForOccurrence(masterEventId, occurrenceTimeMs)

            // linkException moved an exception's occurrence row to the exception's time,
            // so a match on occurrenceTimeMs (the original time) would miss it; match by
            // exception_event_id instead.
            if (exception != null) {
                occurrenceGenerator.cancelOccurrenceByException(exception.id)
                eventsDao.deleteById(exception.id)
            } else {
                occurrenceGenerator.cancelOccurrence(masterEventId, occurrenceTimeMs)
            }

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
     * Splits a recurring series for "edit this and all future".
     *
     * Ends the master before [splitTimeMs] (COUNT cut to the past occurrences, else UNTIL),
     * deletes its later occurrences and exceptions, and creates a new series with a fresh UID
     * from [modifiedEvent]. A split at or before the first occurrence, or a COUNT split that
     * would leave COUNT=0 on either side, edits the master in place instead
     * ([updateMasterInPlace]).
     *
     * @param splitTimeMs the occurrence time to split from
     * @param attendees the edited guest set for the new series; null copies the master's
     * @return the new series, or the master when edited in place
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

            // A split at or before the master's start is an "edit all events".
            if (splitTimeMs <= masterEvent.startTs) {
                return@withTransaction updateMasterInPlace(masterEvent, modifiedEvent, isLocal, attendees)
            }

            // pastCount matters only for a COUNT rule, so UNTIL and unbounded rules skip
            // the expansion.
            //
            // RFC 5545 §3.3.10: COUNT counts rule recurrences, not what survives EXDATE,
            // so the expansion passes no exdates. Otherwise a past EXDATE would shrink the
            // master's new COUNT and drop a visible past occurrence on re-expansion (EXDATE
            // applies after the COUNT cap).
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

            // pastCount <= 0 or >= total would give COUNT=0 on one side.
            if (RruleUtils.isDegenerateCountSplit(rrule, pastCount)) {
                return@withTransaction updateMasterInPlace(masterEvent, modifiedEvent, isLocal, attendees)
            }

            // Splits so the total occurrence count is kept. modifiedEvent.rrule == null
            // means the user picked "Does not repeat".
            val (truncatedRrule, splitNewSeriesRrule) = RruleUtils.splitRruleAtTime(
                masterRrule = rrule,
                userRrule = modifiedEvent.rrule,
                untilMs = splitTimeMs - 1L,
                pastCount = pastCount,
                isAllDay = masterEvent.isAllDay,
            )

            val now = System.currentTimeMillis()
            eventsDao.updateRrule(masterEventId, truncatedRrule, now)

            occurrencesDao.deleteForEventAfter(masterEventId, splitTimeMs)

            deleteFutureExceptions(masterEventId, splitTimeMs)

            if (!isLocal && masterEvent.syncStatus != SyncStatus.PENDING_CREATE) {
                eventsDao.updateSyncStatus(masterEventId, SyncStatus.PENDING_UPDATE, now)
                queueOperation(masterEventId, PendingOperation.OPERATION_UPDATE)
            }

            // null only when the user picked "Does not repeat", which makes the new row
            // non-recurring.
            val newSeriesRrule = splitNewSeriesRrule
            val newEvent = modifiedEvent.copy(
                id = 0,
                uid = generateUid(),
                calendarId = masterEvent.calendarId,
                // The form sends modifiedEvent.startTs as the first occurrence's time on
                // the split day ("Jun 02 08:00" when editing the Jun 02 occurrence). Adding
                // splitTimeMs would shift the series by the master-to-split-day gap.
                startTs = modifiedEvent.startTs,
                rrule = newSeriesRrule,
                originalEventId = null, // A new series, not an exception
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

            // Attendees live in their own table and eventsDao.insert doesn't copy them;
            // without this the new series PUTs with no attendees and the next pull
            // drops them. An edited set wins; null copies the master's.
            val newSeriesAttendees = attendees ?: attendeesDao.getForEventOnce(masterEventId)
            if (newSeriesAttendees.isNotEmpty()) {
                attendeesDao.replaceForEvent(
                    newEventId,
                    newSeriesAttendees.map { it.copy(id = 0, eventId = newEventId) }
                )
            }

            occurrenceGenerator.regenerateOccurrences(createdEvent)

            if (!isLocal) {
                queueOperation(newEventId, PendingOperation.OPERATION_CREATE)
            }

            createdEvent
        }
    }

    /**
     * Applies [modifiedEvent] onto the master row in place, keeping its id, UID and calendar.
     *
     * [splitSeries] uses it when the split is at or before the first occurrence, or a COUNT
     * rule would leave COUNT=0 on either side: both are "edit all events". SEQUENCE follows
     * [SequenceBumper] as in [updateEvent], so it only increases (RFC 5545 §3.8.7.4). The
     * caller must already be inside `database.withTransaction`.
     *
     * @param attendees the edited guest set, cascaded to exceptions; null leaves attendees
     *   alone
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
        if (attendees != null) {
            // Read the pre-edit addresses before the replace so the cascade can skip a
            // customized exception.
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
     * Copies an all-events attendee change onto the series' existing exceptions.
     *
     * A new exception gets the master's attendees, so without this a moved occurrence would
     * keep the old guest list. An exception whose guests were edited per occurrence must not
     * be overwritten: one whose addresses still equal [preEditMasterAddresses], the series'
     * set before this edit, was only seeded and takes the change; any other is skipped. Only
     * canonical addresses are compared, ignoring order and PARTSTAT, so a seeded exception
     * with server-set PARTSTAT or receipt fields still takes it. The caller must already be
     * inside `database.withTransaction`.
     */
    private suspend fun cascadeAttendeesToExceptions(
        masterEventId: Long,
        attendees: List<Attendee>,
        preEditMasterAddresses: Set<String>,
    ) {
        val exceptions = eventsDao.getExceptionsForMaster(masterEventId)
        for (exception in exceptions) {
            val exceptionAddresses = attendeeAddressSet(attendeesDao.getForEventOnce(exception.id))
            if (exceptionAddresses != preEditMasterAddresses) continue
            attendeesDao.replaceForEvent(
                exception.id,
                attendees.map { it.copy(id = 0, eventId = exception.id) }
            )
        }
    }

    /** Returns the canonical addresses of [attendees], ignoring order and PARTSTAT. */
    private fun attendeeAddressSet(attendees: List<Attendee>): Set<String> =
        attendees.map { org.onekash.kashcal.util.AddressNormalizer.canonical(it.address) }.toSet()

    /**
     * Queues an iTIP CANCEL for each [preEditRows] guest missing from [survivors].
     *
     * The replace removes a dropped guest's row, so each one is captured with its delivery
     * context (schedule_agent and schedule_status) into pending_cancels, which the push drains
     * after a successful PUT. The upsert is idempotent per event, recurrence and address, so
     * re-saving the same removal queues one cancel.
     *
     * [sequence] is the event SEQUENCE the CANCEL goes out at (the iTIP builder increments it
     * on the wire per RFC 5546 §2.1.4). [recurrenceId] is null for the series, or the one
     * occurrence cancelled. The caller must already be inside `database.withTransaction` and
     * call this only for an event on the server; a never-synced event has nothing to cancel.
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
     * Deletes "this and all future" occurrences from [fromTimeMs] on.
     *
     * Ends the master's RRULE with an UNTIL just before [fromTimeMs] and deletes the later
     * occurrences and exceptions. From the first occurrence it deletes the whole event
     * ([deleteEvent]).
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

            if (fromTimeMs <= masterEvent.startTs) {
                deleteEvent(masterEventId, isLocal)
                return@withTransaction
            }

            val rrule = checkNotNull(masterEvent.rrule) {
                "Recurring event has no RRULE: ${masterEvent.id}"
            }
            val truncatedRrule = addUntilToRrule(rrule, fromTimeMs - 1, masterEvent.isAllDay)
            eventsDao.updateRrule(masterEventId, truncatedRrule, now)

            occurrencesDao.deleteForEventAfter(masterEventId, fromTimeMs)

            deleteFutureExceptions(masterEventId, fromTimeMs)

            if (!isLocal && masterEvent.syncStatus != SyncStatus.PENDING_CREATE) {
                eventsDao.updateSyncStatus(masterEventId, SyncStatus.PENDING_UPDATE, now)
                queueOperation(masterEventId, PendingOperation.OPERATION_UPDATE)
            }
        }
    }

    /**
     * Moves an event and its exceptions to [newCalendarId], replacing its queued operations.
     *
     * What is queued depends on the source and target accounts:
     * - Same account, SYNCED with a server URL: one MOVE (the push tries WebDAV MOVE, then
     *   CREATE in the target and DELETE from the source).
     * - Other synced account, SYNCED with a server URL: a CREATE and a DELETE linked by one id.
     * - Synced to local: a DELETE when it was SYNCED with a server URL, else nothing.
     * - Local to synced, or synced to synced in any other status: a CREATE only.
     * - Local to local: nothing.
     *
     * A DELETE or MOVE carries the source calendar id, because `event.calendarId` already
     * names the target when the push runs. Refuses an exception (move its master), a missing
     * or read-only target, and a move to another account of an event with attendees.
     */
    suspend fun moveEventToCalendar(
        eventId: Long,
        newCalendarId: Long
    ) {
        database.withTransaction {
            val event = requireNotNull(eventsDao.getById(eventId)) {
                "Event not found: $eventId"
            }

            require(event.originalEventId == null) {
                "Cannot move exception event directly. Move the master event instead (originalEventId: ${event.originalEventId})"
            }

            if (event.calendarId == newCalendarId) {
                return@withTransaction
            }

            val calendarsDao = database.calendarsDao()
            val accountsDao = database.accountsDao()

            val sourceCalendar = calendarsDao.getById(event.calendarId)
            val targetCalendar = requireNotNull(calendarsDao.getById(newCalendarId)) {
                "Target calendar not found: $newCalendarId"
            }

            // The event form also leaves read-only calendars out of its picker.
            require(!targetCalendar.isReadOnly) {
                "Cannot move event to read-only calendar"
            }

            val sourceAccountId = sourceCalendar?.accountId
            val targetAccountId = targetCalendar.accountId

            val sourceAccount = sourceAccountId?.let { accountsDao.getById(it) }
            val targetAccount = accountsDao.getById(targetAccountId)

            // Local means the account's provider needs no sync.
            val sourceIsLocal = sourceAccount?.provider?.requiresSync == false
            val targetIsLocal = targetAccount?.provider?.requiresSync == false
            val isSameAccount = sourceAccountId == targetAccountId && sourceAccountId != null

            // A cross-account move of an event with attendees would carry the source
            // account's ORGANIZER onto a CREATE in the target account. Scheduling servers
            // reject or rewrite a foreign organizer and either re-invite everyone under a
            // new identity or strip the guests (RFC 6638 / iTIP). The move doesn't rewrite
            // the organizer, so the user duplicates the event instead (fresh UID, the new
            // account organizes). A same-account move keeps its attendees on the unchanged
            // event id. The event form also disables its calendar picker when editing a
            // Room event with attendees.
            // Exceptions count too: a per-occurrence guest may be only on an exception's
            // rows, which the cross-account CREATE also serializes.
            val hasAttendees = attendeesDao.countForEvent(eventId) > 0 ||
                eventsDao.getExceptionsForMaster(eventId)
                    .any { attendeesDao.countForEvent(it.id) > 0 }
            val crossAccountWithAttendees = !isSameAccount && hasAttendees
            require(!crossAccountWithAttendees) {
                "Cannot move an event with attendees to a different account " +
                    "(would misdeliver invitations); duplicate it instead"
            }

            // Captured before it is cleared: the DELETE and MOVE need the old URL.
            val oldCaldavUrl = event.caldavUrl
            val wasSynced = event.syncStatus == SyncStatus.SYNCED && oldCaldavUrl != null
            val now = System.currentTimeMillis()

            val newSyncStatus = when {
                targetIsLocal -> SyncStatus.SYNCED
                wasSynced -> SyncStatus.PENDING_CREATE
                else -> SyncStatus.PENDING_CREATE
            }

            val movedEvent = event.copy(
                calendarId = newCalendarId,
                caldavUrl = null, // Set by the push in the target calendar
                etag = null,
                syncStatus = newSyncStatus,
                updatedAt = now,
                localModifiedAt = now
            )
            eventsDao.update(movedEvent)

            if (event.rrule != null) {
                eventsDao.updateCalendarIdForExceptions(eventId, newCalendarId, now)
            }

            occurrencesDao.updateCalendarIdForEvent(eventId, newCalendarId)

            // Queued operations target the old calendar.
            pendingOpsDao.deleteForEvent(eventId)

            when {
                sourceIsLocal && targetIsLocal -> {
                    // Nothing to sync.
                }

                sourceIsLocal && !targetIsLocal -> {
                    pendingOpsDao.insert(
                        PendingOperation(
                            eventId = eventId,
                            operation = PendingOperation.OPERATION_CREATE
                        )
                    )
                }

                !sourceIsLocal && targetIsLocal && wasSynced -> {
                    pendingOpsDao.insert(
                        PendingOperation(
                            eventId = eventId,
                            operation = PendingOperation.OPERATION_DELETE,
                            targetUrl = oldCaldavUrl,
                            sourceCalendarId = event.calendarId
                        )
                    )
                }

                !sourceIsLocal && !targetIsLocal && isSameAccount && wasSynced -> {
                    pendingOpsDao.insert(
                        PendingOperation(
                            eventId = eventId,
                            operation = PendingOperation.OPERATION_MOVE,
                            targetUrl = oldCaldavUrl,
                            targetCalendarId = newCalendarId,
                            sourceCalendarId = event.calendarId
                        )
                    )
                }

                // The DELETE waits while its CREATE is pending
                // (PendingOperationsDao.getReadyOperations). If the CREATE fails for good the
                // push drops the DELETE and the event stays in the source; a failed DELETE
                // leaves a duplicate, which is recoverable.
                !sourceIsLocal && !targetIsLocal && !isSameAccount && wasSynced -> {
                    val linkedMoveId = UUID.randomUUID().toString()

                    pendingOpsDao.insert(
                        PendingOperation(
                            eventId = eventId,
                            operation = PendingOperation.OPERATION_CREATE,
                            linkedMoveId = linkedMoveId
                        )
                    )
                    pendingOpsDao.insert(
                        PendingOperation(
                            eventId = eventId,
                            operation = PendingOperation.OPERATION_DELETE,
                            targetUrl = oldCaldavUrl,
                            sourceCalendarId = event.calendarId,
                            linkedMoveId = linkedMoveId
                        )
                    )
                }

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
     * Deletes SYNCED one-off server events that ended before [cutoffTs] (epoch ms) and returns
     * how many; the rules are on [org.onekash.kashcal.data.db.dao.EventsDao.deleteOutsideLookback].
     */
    suspend fun cleanupEventsOutsideLookback(cutoffTs: Long): Int {
        return eventsDao.deleteOutsideLookback(cutoffTs)
    }

    // ========== Helper Functions ==========

    private fun generateUid(): String {
        return "${UUID.randomUUID()}@kashcal.onekash.org"
    }

    /**
     * Queues [operation] for [eventId], or folds it into the event's PENDING operation: the
     * event's non-FAILED operations restart their lifetime, and the pending one becomes a
     * DELETE when [operation] is one.
     */
    private suspend fun queueOperation(eventId: Long, operation: String) {
        val now = System.currentTimeMillis()

        val existingList = pendingOpsDao.getForEvent(eventId)
        val existing = existingList.firstOrNull { it.status == PendingOperation.STATUS_PENDING }
        if (existing != null) {
            // The user still cares about this event, so its operation shouldn't expire.
            pendingOpsDao.refreshOperationLifetime(eventId, now)

            // Any other operation folds into the pending one unchanged.
            if (operation == PendingOperation.OPERATION_DELETE) {
                pendingOpsDao.update(existing.copy(operation = operation))
            }
            return
        }

        // lifetimeResetAt defaults to now.
        val pendingOp = PendingOperation(
            eventId = eventId,
            operation = operation
        )
        pendingOpsDao.insert(pendingOp)
    }

    /**
     * Appends [timestampMs] to an EXDATE value of comma-separated epoch milliseconds.
     *
     * The pull ([org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper]) stores and the push
     * reads the same format; expansion also accepts legacy YYYYMMDD day codes.
     *
     * @param isAllDay unused
     */
    @Suppress("UNUSED_PARAMETER")
    private fun addToExdate(currentExdate: String?, timestampMs: Long, isAllDay: Boolean): String {
        return if (currentExdate.isNullOrBlank()) {
            timestampMs.toString()
        } else {
            "$currentExdate,$timestampMs"
        }
    }

    /** Adds an UNTIL to [rrule]; [RruleUtils.addUntilToRrule] is shared with device events. */
    private fun addUntilToRrule(rrule: String, untilMs: Long, isAllDay: Boolean = false): String {
        return org.onekash.kashcal.util.RruleUtils.addUntilToRrule(rrule, untilMs, isAllDay)
    }

    /**
     * Deletes the master's exceptions whose original time is at or after [fromTimeMs].
     *
     * [splitSeries] and [deleteThisAndFuture] use it: those exceptions belong to occurrences
     * the ended master no longer produces. The caller must already be inside
     * `database.withTransaction`.
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

    /** Hides exactly the Recent changes rows [ids], in one transaction. */
    suspend fun dismissRecentChanges(ids: List<Long>, at: Long = System.currentTimeMillis()) {
        if (ids.isEmpty()) return
        database.runInTransaction {
            ids.chunked(SQL_IN_CHUNK).forEach { recentChangesDao.dismiss(it, at) }
        }
    }

    /** Shows the Recent changes rows [ids] again, for Clear all's undo. */
    suspend fun restoreRecentChanges(ids: List<Long>) {
        if (ids.isEmpty()) return
        database.runInTransaction {
            ids.chunked(SQL_IN_CHUNK).forEach { recentChangesDao.restore(it) }
        }
    }

    private companion object {
        // SQLite caps a statement at 999 bind variables; IN (:ids) queries are chunked
        // below that so a rename touching thousands of events can't overflow it.
        const val SQL_IN_CHUNK = 500
    }
}
