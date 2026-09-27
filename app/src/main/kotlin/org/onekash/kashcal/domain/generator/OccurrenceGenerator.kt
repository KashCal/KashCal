package org.onekash.kashcal.domain.generator

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.OccurrencesDao
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.util.DateTimeUtils
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates and maintains the materialized occurrences table for events.
 *
 * Expands RRULEs through [IcalDavRRuleEngine] (ical4j) per RFC 5545:
 *   RecurrenceSet = (DTSTART ∪ RRULE ∪ RDATE) - EXDATE
 *
 * Occurrences are stored for O(1) range queries. This class covers generation on create,
 * regeneration when the rule or its exclusions change, extension forward and back as the
 * user scrolls, cancelling one occurrence, and linking exceptions to occurrences.
 */
@Singleton
class OccurrenceGenerator @Inject constructor(
    private val database: KashCalDatabase,
    private val occurrencesDao: OccurrencesDao,
    private val eventsDao: EventsDao,
    private val dataStore: KashCalDataStore
) {
    companion object {
        private const val DEFAULT_EXPANSION_MONTHS = 24 // about 2 years, like PullStrategy
        private const val MILLISECONDS_PER_SECOND = 1000L
        private const val SECONDS_PER_DAY = 86400L
    }

    /**
     * Replaces [event]'s occurrences with those in [rangeStartMs] to [rangeEndMs], in one
     * transaction.
     *
     * A non-recurring event gets a single occurrence whatever the range. Existing
     * exception_event_id links are restored after re-expansion, so changed occurrences stay
     * linked to their exceptions. An expansion that returns nothing keeps the existing rows.
     *
     * @return the number of occurrences generated: 0 for a synthetic master or an empty
     *   expansion
     */
    suspend fun generateOccurrences(
        event: Event,
        rangeStartMs: Long,
        rangeEndMs: Long
    ): Int {
        // A synthetic master (for orphan exceptions) has no RRULE and exists only as the FK
        // target of its exceptions; a single occurrence for it would put a phantom CANCELLED
        // row on the day card. ICS sync and CalDAV pull both mark it with
        // X-KASHCAL-SYNTHETIC-MASTER in extraProperties.
        if (event.extraProperties?.get("X-KASHCAL-SYNTHETIC-MASTER") == "true") {
            return 0
        }
        return database.withTransaction {
            // Read the exception links (changed occurrences) before anything is deleted.
            val existingOccurrences = occurrencesDao.getForEvent(event.id)
            val exceptionLinks = existingOccurrences
                .filter { it.exceptionEventId != null }
                .associate { it.startTs to ExceptionLinkData(it.exceptionEventId!!, it.isCancelled) }

            // Expand before deleting, so a failed expansion (malformed RRULE, bad timezone)
            // loses nothing.
            val occurrences = if (event.rrule.isNullOrBlank()) {
                listOf(createSingleOccurrence(event))
            } else {
                expandRRule(event, rangeStartMs, rangeEndMs)
            }

            if (occurrences.isNotEmpty()) {
                occurrencesDao.deleteForEvent(event.id)

                occurrencesDao.insertAll(occurrences)

                // Links match within 60 seconds, since timestamps may shift slightly on
                // re-expansion.
                for ((originalStartTs, linkData) in exceptionLinks) {
                    restoreExceptionLink(event.id, originalStartTs, linkData)
                }
            } else if (!event.rrule.isNullOrBlank()) {
                // Empty expansion: keep the existing occurrences.
                android.util.Log.w("OccurrenceGenerator",
                    "RRULE expansion returned empty for event ${event.id}, preserving existing ${existingOccurrences.size} occurrences")
            }

            occurrences.size
        }
    }

    /** Holds an exception link across regeneration. */
    private data class ExceptionLinkData(
        val exceptionEventId: Long,
        val isCancelled: Boolean
    )

    /**
     * Restores an exception link after regeneration, matching within 60 seconds.
     *
     * Regenerated occurrences carry the master's rule times, so this also moves the
     * occurrence to the exception's times and re-applies a cancelled flag. If the exception
     * row is gone, it links at [originalStartTs] without changing times.
     */
    private suspend fun restoreExceptionLink(
        eventId: Long,
        originalStartTs: Long,
        linkData: ExceptionLinkData
    ) {
        val exceptionEvent = eventsDao.getById(linkData.exceptionEventId)
        if (exceptionEvent != null) {
            // originalStartTs is the old row's start, which holds the exception's changed
            // time. The regenerated occurrence sits at the rule's time, so match on
            // originalInstanceTime (RECURRENCE-ID).
            val recurrenceIdTime = exceptionEvent.originalInstanceTime ?: originalStartTs
            linkException(eventId, recurrenceIdTime, exceptionEvent)
            // The occurrence now has the exception's times.
            if (linkData.isCancelled) {
                occurrencesDao.markCancelled(eventId, exceptionEvent.startTs)
            }
        } else {
            // Orphaned link: the exception row is gone.
            android.util.Log.w("OccurrenceGenerator",
                "Exception event ${linkData.exceptionEventId} not found during link restoration")
            occurrencesDao.linkException(eventId, originalStartTs, linkData.exceptionEventId)
            if (linkData.isCancelled) {
                occurrencesDao.markCancelled(eventId, originalStartTs)
            }
        }
    }

    /**
     * Regenerates [event]'s occurrences over the sync lookback and the next
     * DEFAULT_EXPANSION_MONTHS (24 x 30 days) with [generateOccurrences].
     *
     * The past window is the sync lookback setting; for "All events" (Int.MAX_VALUE) it
     * reaches back to the event start. An old event's range starts at now minus the lookback,
     * so its occurrences before that aren't materialized.
     */
    suspend fun regenerateOccurrences(event: Event): Int {
        val now = System.currentTimeMillis()

        val syncPastDays = dataStore.syncPastDays.first()
        val pastWindowMs = if (syncPastDays == Int.MAX_VALUE) {
            // now - Long.MAX_VALUE is a large negative, so coerceAtLeast picks the event start.
            Long.MAX_VALUE
        } else {
            syncPastDays.toLong() * SECONDS_PER_DAY * MILLISECONDS_PER_SECOND
        }

        val futureWindowMs = DEFAULT_EXPANSION_MONTHS * 30L * SECONDS_PER_DAY * MILLISECONDS_PER_SECOND

        // Second-aligned like the engine's output, so a first occurrence at
        // floor(startTs/1000)*1000 doesn't fall before rangeStart and get skipped.
        val eventStartAligned = (event.startTs / MILLISECONDS_PER_SECOND) * MILLISECONDS_PER_SECOND
        val rangeStart = (now - pastWindowMs).coerceAtLeast(eventStartAligned)
        val rangeEnd = now + futureWindowMs
        return generateOccurrences(event, rangeStart, rangeEnd)
    }

    /**
     * Adds a recurring [event]'s occurrences after its latest one, up to [extendToMs], as the
     * user scrolls forward.
     *
     * @return the number of occurrences added; 0 for a non-recurring event or one with no
     *   occurrences yet
     */
    suspend fun extendOccurrences(
        event: Event,
        extendToMs: Long
    ): Int {
        if (event.rrule.isNullOrBlank()) {
            return 0
        }

        return database.withTransaction {
            val currentMaxTs = occurrencesDao.getMaxStartTs(event.id) ?: return@withTransaction 0

            val newOccurrences = expandRRule(
                event,
                currentMaxTs + 1,
                extendToMs
            )

            if (newOccurrences.isNotEmpty()) {
                occurrencesDao.insertAll(newOccurrences)
            }

            newOccurrences.size
        }
    }

    /**
     * Adds a recurring [event]'s occurrences before its earliest one, back to [extendToMs]
     * but not before DTSTART, as the user scrolls back.
     *
     * @return the number of occurrences added; 0 for a non-recurring event, one with no
     *   occurrences yet, or one already extended that far
     */
    suspend fun extendPastOccurrences(
        event: Event,
        extendToMs: Long
    ): Int {
        if (event.rrule.isNullOrBlank()) {
            return 0
        }

        return database.withTransaction {
            val currentMinTs = occurrencesDao.getMinStartTs(event.id) ?: return@withTransaction 0

            // Not before DTSTART, second-aligned as in regenerateOccurrences.
            val effectiveExtendTo = extendToMs
                .coerceAtLeast((event.startTs / MILLISECONDS_PER_SECOND) * MILLISECONDS_PER_SECOND)

            if (currentMinTs <= effectiveExtendTo) {
                return@withTransaction 0
            }

            // The range end is exclusive, so the existing occurrence at currentMinTs isn't
            // duplicated.
            val newOccurrences = expandRRule(
                event,
                effectiveExtendTo,
                currentMinTs
            )

            if (newOccurrences.isNotEmpty()) {
                occurrencesDao.insertAll(newOccurrences)
            }

            newOccurrences.size
        }
    }

    /**
     * Marks the occurrence of [eventId] within 60 seconds of [occurrenceTimeMs] cancelled.
     * Doesn't touch the event: the caller adds the EXDATE.
     */
    suspend fun cancelOccurrence(eventId: Long, occurrenceTimeMs: Long) {
        occurrencesDao.markCancelled(eventId, occurrenceTimeMs)
    }

    /**
     * Marks the occurrence linked to [exceptionEventId] cancelled. An occurrence edited into
     * an exception no longer starts at its original instance time, so it is found by the
     * link instead of by time.
     */
    suspend fun cancelOccurrenceByException(exceptionEventId: Long) {
        occurrencesDao.markCancelledByException(exceptionEventId)
    }

    /**
     * Links [exceptionEventId] to the master's occurrence within 60 seconds of
     * [occurrenceTimeMs] (its original time), without changing the occurrence's times.
     */
    suspend fun linkException(
        masterEventId: Long,
        occurrenceTimeMs: Long,
        exceptionEventId: Long
    ) {
        occurrencesDao.linkException(masterEventId, occurrenceTimeMs, exceptionEventId)
    }

    /**
     * Links [exceptionEvent] to the master's occurrence and moves that occurrence to the
     * exception's times (start_ts, end_ts, start_day, end_day), in one transaction.
     *
     * Leaves one occurrence row for the changed occurrence, under the master:
     * 1. Deletes any occurrence the exception owns itself (event_id = exception id).
     * 2. When the exception moved, deletes the master's occurrence already at the new start
     *    (for example Jan 6 moved onto an existing Jan 13).
     * 3. Updates the master's occurrence matched by
     *    [OccurrencesDao.updateOccurrenceForException]: within 60 seconds of
     *    [occurrenceTimeMs] or already linked to the exception, which finds a re-edit.
     * 4. Inserts a linked occurrence if none matched, e.g. when the original time is outside
     *    the materialized window.
     *
     * @param occurrenceTimeMs the original occurrence time (the exception's
     *   originalInstanceTime)
     */
    suspend fun linkException(
        masterEventId: Long,
        occurrenceTimeMs: Long,
        exceptionEvent: Event
    ) {
        database.withTransaction {
            occurrencesDao.deleteForEvent(exceptionEvent.id)

            val newStartDay = Occurrence.toDayFormat(exceptionEvent.startTs, exceptionEvent.isAllDay)
            val newEndDay = DateTimeUtils.eventTsToEndDayCode(
                endTs = exceptionEvent.endTs,
                startTs = exceptionEvent.startTs,
                isAllDay = exceptionEvent.isAllDay
            )

            if (exceptionEvent.startTs != occurrenceTimeMs) {
                val conflictingOccurrence = occurrencesDao.getByEventIdAndStartTs(
                    masterEventId,
                    exceptionEvent.startTs
                )
                if (conflictingOccurrence != null) {
                    occurrencesDao.deleteById(conflictingOccurrence.id)
                }
            }

            val rowsUpdated = occurrencesDao.updateOccurrenceForException(
                masterEventId,
                occurrenceTimeMs,
                exceptionEvent.id,
                exceptionEvent.startTs,
                exceptionEvent.endTs,
                newStartDay,
                newEndDay
            )

            if (rowsUpdated == 0) {
                occurrencesDao.insert(Occurrence(
                    eventId = masterEventId,
                    calendarId = exceptionEvent.calendarId,
                    startTs = exceptionEvent.startTs,
                    endTs = exceptionEvent.endTs,
                    startDay = newStartDay,
                    endDay = newEndDay,
                    exceptionEventId = exceptionEvent.id,
                    isCancelled = false
                ))
            }
        }
    }

    /**
     * Expands [event]'s RRULE, RDATE and EXDATE with [IcalDavRRuleEngine.expandToTimestamps]
     * and returns an [Occurrence] per start, each with the event's duration.
     */
    private fun expandRRule(
        event: Event,
        rangeStartMs: Long,
        rangeEndMs: Long
    ): List<Occurrence> {
        val timestamps = IcalDavRRuleEngine.expandToTimestamps(
            rrule = event.rrule,
            dtstartMs = event.startTs,
            rangeStartMs = rangeStartMs,
            rangeEndMs = rangeEndMs,
            timezone = event.timezone,
            isAllDay = event.isAllDay,
            rdateStrings = event.rdate,
            exdateStrings = event.exdate,
        )
        val eventDurationMs = event.endTs - event.startTs
        return timestamps.map { ts ->
            Occurrence(
                eventId = event.id,
                calendarId = event.calendarId,
                startTs = ts,
                endTs = ts + eventDurationMs,
                startDay = Occurrence.toDayFormat(ts, event.isAllDay),
                endDay = DateTimeUtils.eventTsToEndDayCode(
                    endTs = ts + eventDurationMs,
                    startTs = ts,
                    isAllDay = event.isAllDay
                )
            )
        }
    }

    /** Returns the single occurrence of a non-recurring event. */
    private fun createSingleOccurrence(event: Event): Occurrence {
        val startDay = Occurrence.toDayFormat(event.startTs, event.isAllDay)
        val endDay = DateTimeUtils.eventTsToEndDayCode(
            endTs = event.endTs,
            startTs = event.startTs,
            isAllDay = event.isAllDay
        )

        return Occurrence(
            eventId = event.id,
            calendarId = event.calendarId,
            startTs = event.startTs,
            endTs = event.endTs,
            startDay = startDay,
            endDay = endDay
        )
    }

    /**
     * Returns the occurrence start times of [rrule] in the range without storing anything,
     * through [IcalDavRRuleEngine.expandToTimestamps].
     *
     * @param exdates excluded dates as YYYYMMDD codes, joined to the engine's
     *   comma-separated form
     * @param isAllDay forces UTC for date calculations
     */
    fun expandForPreview(
        rrule: String,
        dtstartMs: Long,
        rangeStartMs: Long,
        rangeEndMs: Long,
        exdates: List<String> = emptyList(),
        timezone: String? = null,
        isAllDay: Boolean = false
    ): List<Long> {
        if (rrule.isBlank()) return emptyList()
        val exdateCsv = exdates.takeIf { it.isNotEmpty() }?.joinToString(",")
        return IcalDavRRuleEngine.expandToTimestamps(
            rrule = rrule,
            dtstartMs = dtstartMs,
            rangeStartMs = rangeStartMs,
            rangeEndMs = rangeEndMs,
            timezone = timezone,
            isAllDay = isAllDay,
            rdateStrings = null,
            exdateStrings = exdateCsv,
        )
    }
}
