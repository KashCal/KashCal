package org.onekash.kashcal.domain.generator

import kotlinx.coroutines.flow.first
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.OccurrencesDao
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.preferences.KashCalDataStore
import javax.inject.Inject
import javax.inject.Singleton
import androidx.room.withTransaction

/**
 * Generates and manages materialized occurrences for recurring events.
 *
 * Uses ical4j (via icaldav-core's [IcalDavRRuleEngine]) for RFC 5545 compliant
 * RRULE expansion:
 *   RecurrenceSet = (DTSTART ∪ RRULE ∪ RDATE) - EXDATE
 *
 * Occurrences are stored in the database for O(1) range queries.
 * This class handles:
 * - Initial generation when event is created
 * - Regeneration when RRULE/EXDATE changes
 * - Incremental extension for lazy loading
 * - Single occurrence cancellation (EXDATE)
 */
@Singleton
class OccurrenceGenerator @Inject constructor(
    private val database: KashCalDatabase,
    private val occurrencesDao: OccurrencesDao,
    private val eventsDao: EventsDao,
    private val dataStore: KashCalDataStore
) {
    companion object {
        private const val DEFAULT_EXPANSION_MONTHS = 24 // 2 years - consistent with PullStrategy
        private const val MILLISECONDS_PER_SECOND = 1000L
        private const val SECONDS_PER_DAY = 86400L
    }

    /**
     * Generate occurrences for an event within a date range.
     *
     * For non-recurring events, generates a single occurrence.
     * For recurring events, expands RRULE and stores all occurrences in range.
     *
     * IMPORTANT: Preserves exception_event_id links when regenerating.
     * This ensures modified occurrences (RECURRENCE-ID events) maintain their
     * link to exception events after RRULE re-expansion.
     *
     * @param event The event to generate occurrences for
     * @param rangeStartMs Start of range in milliseconds (epoch)
     * @param rangeEndMs End of range in milliseconds (epoch)
     * @return Number of occurrences generated
     */
    suspend fun generateOccurrences(
        event: Event,
        rangeStartMs: Long,
        rangeEndMs: Long
    ): Int {
        return database.withTransaction {
            // PRESERVE EXCEPTION LINKS: Query existing links before modification
            // These are occurrences modified via RECURRENCE-ID (edited single occurrences)
            val existingOccurrences = occurrencesDao.getForEvent(event.id)
            val exceptionLinks = existingOccurrences
                .filter { it.exceptionEventId != null }
                .associate { it.startTs to ExceptionLinkData(it.exceptionEventId!!, it.isCancelled) }

            // EXPAND FIRST: Calculate new occurrences BEFORE deleting existing ones
            // This prevents data loss if expansion fails (e.g., malformed RRULE, timezone issue)
            val occurrences = if (event.rrule.isNullOrBlank()) {
                // Non-recurring: single occurrence
                listOf(createSingleOccurrence(event))
            } else {
                // Recurring: expand RRULE
                expandRRule(event, rangeStartMs, rangeEndMs)
            }

            // Only delete and replace if expansion succeeded
            if (occurrences.isNotEmpty()) {
                // Clear existing occurrences AFTER successful expansion
                occurrencesDao.deleteForEvent(event.id)

                occurrencesDao.insertAll(occurrences)

                // RESTORE EXCEPTION LINKS: Reapply after insert
                // Uses tolerance matching because timestamps may shift slightly on re-expand
                for ((originalStartTs, linkData) in exceptionLinks) {
                    restoreExceptionLink(event.id, originalStartTs, linkData)
                }
            } else if (!event.rrule.isNullOrBlank()) {
                // RRULE expansion returned empty - log warning but preserve existing occurrences
                android.util.Log.w("OccurrenceGenerator",
                    "RRULE expansion returned empty for event ${event.id}, preserving existing ${existingOccurrences.size} occurrences")
            }

            occurrences.size
        }  // Transaction commits here - all or nothing
    }

    /**
     * Data class to hold exception link information during regeneration.
     */
    private data class ExceptionLinkData(
        val exceptionEventId: Long,
        val isCancelled: Boolean
    )

    /**
     * Restore an exception link after occurrence regeneration.
     * Uses 60-second tolerance for timestamp matching (DST/timezone edge cases).
     *
     * CRITICAL: Also restores occurrence times from the exception event!
     * After regeneration, occurrences have master event times (from RRULE).
     * This method updates them to match the exception event's modified times.
     */
    private suspend fun restoreExceptionLink(
        eventId: Long,
        originalStartTs: Long,
        linkData: ExceptionLinkData
    ) {
        // Fetch exception event to get its current times
        val exceptionEvent = eventsDao.getById(linkData.exceptionEventId)
        if (exceptionEvent != null) {
            // CRITICAL: Use originalInstanceTime (RECURRENCE-ID) to find the RRULE-generated occurrence.
            // The originalStartTs parameter is the occurrence's CURRENT startTs (after previous linking),
            // which is the exception's modified time. We need the ORIGINAL time from RECURRENCE-ID
            // to find the newly regenerated occurrence at the rule's time.
            val recurrenceIdTime = exceptionEvent.originalInstanceTime ?: originalStartTs
            linkException(eventId, recurrenceIdTime, exceptionEvent)
            // Restore cancelled status using exception's time (occurrence now has exception times)
            if (linkData.isCancelled) {
                occurrencesDao.markCancelled(eventId, exceptionEvent.startTs)
            }
        } else {
            // Fallback: just link if exception not found (edge case - orphaned link)
            android.util.Log.w("OccurrenceGenerator",
                "Exception event ${linkData.exceptionEventId} not found during link restoration")
            occurrencesDao.linkException(eventId, originalStartTs, linkData.exceptionEventId)
            // Use original time since we didn't update times
            if (linkData.isCancelled) {
                occurrencesDao.markCancelled(eventId, originalStartTs)
            }
        }
    }

    /**
     * Regenerate occurrences for an event (e.g., after RRULE change).
     *
     * Past window: Respects user's sync lookback setting (via DataStore).
     * Future window: Always DEFAULT_EXPANSION_MONTHS (24 = 2 years).
     *
     * When sync lookback is "All events" (Int.MAX_VALUE), uses unbounded past window
     * (back to event start) and DEFAULT_EXPANSION_MONTHS for future window.
     *
     * For old events, bounds rangeStart to (now - window) so FastForwarded
     * activates instead of iterating from DTSTART.
     */
    suspend fun regenerateOccurrences(event: Event): Int {
        val now = System.currentTimeMillis()

        // Past window: use sync lookback setting, unbounded for "All events"
        val syncPastDays = dataStore.syncPastDays.first()
        val pastWindowMs = if (syncPastDays == Int.MAX_VALUE) {
            // Unbounded: now - Long.MAX_VALUE underflows to large negative,
            // then coerceAtLeast(eventStartAligned) picks event start
            Long.MAX_VALUE
        } else {
            // User-specified lookback in days
            syncPastDays.toLong() * SECONDS_PER_DAY * MILLISECONDS_PER_SECOND
        }

        // Future window: always 2 years regardless of sync lookback
        val futureWindowMs = DEFAULT_EXPANSION_MONTHS * 30L * SECONDS_PER_DAY * MILLISECONDS_PER_SECOND

        // Truncate to second boundary to match lib-recur's precision (avoids off-by-ms skip
        // where first occurrence at floor(startTs/1000)*1000 falls before rangeStart)
        val eventStartAligned = (event.startTs / MILLISECONDS_PER_SECOND) * MILLISECONDS_PER_SECOND
        val rangeStart = (now - pastWindowMs).coerceAtLeast(eventStartAligned)
        val rangeEnd = now + futureWindowMs
        return generateOccurrences(event, rangeStart, rangeEnd)
    }

    /**
     * Extend occurrences for an event beyond current range.
     * Used for lazy loading when user scrolls far into future.
     *
     * @param event The event to extend
     * @param extendToMs New end date in milliseconds
     * @return Number of new occurrences added
     */
    suspend fun extendOccurrences(
        event: Event,
        extendToMs: Long
    ): Int {
        if (event.rrule.isNullOrBlank()) {
            return 0 // Non-recurring events don't need extension
        }

        return database.withTransaction {
            // Find current max occurrence
            val currentMaxTs = occurrencesDao.getMaxStartTs(event.id) ?: return@withTransaction 0

            // Expand from current max to new end
            val newOccurrences = expandRRule(
                event,
                currentMaxTs + 1, // Start after current max
                extendToMs
            )

            if (newOccurrences.isNotEmpty()) {
                occurrencesDao.insertAll(newOccurrences)
            }

            newOccurrences.size
        }
    }

    /**
     * Extend occurrences for an event into the past.
     * Used for lazy loading when user scrolls far into the past.
     *
     * @param event The event to extend backwards
     * @param extendToMs Target start date in milliseconds (how far back to extend)
     * @return Number of new occurrences added
     */
    suspend fun extendPastOccurrences(
        event: Event,
        extendToMs: Long
    ): Int {
        if (event.rrule.isNullOrBlank()) {
            return 0 // Non-recurring events don't need extension
        }

        return database.withTransaction {
            // Find current min occurrence
            val currentMinTs = occurrencesDao.getMinStartTs(event.id) ?: return@withTransaction 0

            // Clamp to event start — can't go before DTSTART
            // Second-align to match lib-recur precision (same as regenerateOccurrences)
            val effectiveExtendTo = extendToMs
                .coerceAtLeast((event.startTs / MILLISECONDS_PER_SECOND) * MILLISECONDS_PER_SECOND)

            // Already extended far enough
            if (currentMinTs <= effectiveExtendTo) {
                return@withTransaction 0
            }

            // expandRRule uses exclusive end (>= rangeEndMs breaks),
            // so currentMinTs won't duplicate the existing boundary occurrence
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
     * Cancel a single occurrence (applies EXDATE).
     * Does not modify the event - caller should update event.exdate separately.
     *
     * @param eventId The event ID
     * @param occurrenceTimeMs The occurrence start time to cancel
     */
    suspend fun cancelOccurrence(eventId: Long, occurrenceTimeMs: Long) {
        occurrencesDao.markCancelled(eventId, occurrenceTimeMs)
    }

    /**
     * Link an exception event to an occurrence.
     * Called when an exception event is created for a modified occurrence.
     *
     * @param masterEventId The master event ID
     * @param occurrenceTimeMs The original occurrence time
     * @param exceptionEventId The exception event ID
     */
    suspend fun linkException(
        masterEventId: Long,
        occurrenceTimeMs: Long,
        exceptionEventId: Long
    ) {
        occurrencesDao.linkException(masterEventId, occurrenceTimeMs, exceptionEventId)
    }

    /**
     * Link exception to occurrence AND update occurrence times to match exception.
     *
     * This is the preferred method when you have the exception Event object,
     * as it also updates the occurrence's start_ts, end_ts, start_day, end_day
     * to match the exception event's modified times.
     *
     * CRITICAL: This method normalizes Model A (PullStrategy) to Model B:
     * - Step 1: Delete Model A occurrence (event_id = exception.id) if exists
     * - Step 2: Update master's occurrence with exception link and times
     * - Step 3: Fallback - insert new occurrence if master occurrence didn't exist
     *
     * The underlying DAO query uses OR condition to handle re-editing:
     *   WHERE (ABS(start_ts - occurrenceTime) < 60000 OR exception_event_id = exceptionEventId)
     *
     * @param masterEventId The master event ID
     * @param occurrenceTimeMs The ORIGINAL occurrence time (from event.originalInstanceTime)
     * @param exceptionEvent The exception event with modified times
     */
    suspend fun linkException(
        masterEventId: Long,
        occurrenceTimeMs: Long,
        exceptionEvent: Event
    ) {
        database.withTransaction {
            // Step 1: Delete Model A occurrence (if exists)
            // PullStrategy creates occurrence with event_id = exception.id
            // This normalizes Model A to Model B (single linked occurrence)
            occurrencesDao.deleteForEvent(exceptionEvent.id)

            val newStartDay = Occurrence.toDayFormat(exceptionEvent.startTs, exceptionEvent.isAllDay)
            val newEndDay = Occurrence.toDayFormat(exceptionEvent.endTs, exceptionEvent.isAllDay)

            // Step 2: Check if exception's new time conflicts with another occurrence
            // (e.g., user moves Jan 6 occurrence to Jan 13, but Jan 13 already exists)
            if (exceptionEvent.startTs != occurrenceTimeMs) {
                val conflictingOccurrence = occurrencesDao.getByEventIdAndStartTs(
                    masterEventId,
                    exceptionEvent.startTs
                )
                if (conflictingOccurrence != null) {
                    // Delete the conflicting occurrence - it will be replaced by the moved exception
                    occurrencesDao.deleteById(conflictingOccurrence.id)
                }
            }

            // Step 3: Update master's occurrence with exception link and times
            val rowsUpdated = occurrencesDao.updateOccurrenceForException(
                masterEventId,
                occurrenceTimeMs,
                exceptionEvent.id,
                exceptionEvent.startTs,
                exceptionEvent.endTs,
                newStartDay,
                newEndDay
            )

            // Step 4: Fallback - if no master occurrence existed, create one
            // This handles edge case where exception is outside sync window
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
     * Expand RRULE to list of Occurrence entities.
     *
     * Delegates to [IcalDavRRuleEngine.expandToTimestamps] for the RFC 5545 expansion:
     *   RecurrenceSet = (DTSTART ∪ RRULE ∪ RDATE) - EXDATE
     * then maps each timestamp to an Occurrence entity with the event's duration.
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
                endDay = Occurrence.toDayFormat(ts + eventDurationMs, event.isAllDay)
            )
        }
    }

    /**
     * Create a single occurrence for a non-recurring event.
     */
    private fun createSingleOccurrence(event: Event): Occurrence {
        val startDay = Occurrence.toDayFormat(event.startTs, event.isAllDay)
        val endDay = Occurrence.toDayFormat(event.endTs, event.isAllDay)

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
     * Expand RRULE without storing - for preview/validation.
     *
     * Delegates to [IcalDavRRuleEngine.expandToTimestamps]. Accepts `exdates` as
     * pre-parsed YYYYMMDD codes for convenience at the preview call site; internally
     * joined to a CSV and passed through.
     *
     * @param rrule The RRULE string
     * @param dtstartMs Event start time in milliseconds
     * @param rangeStartMs Range start in milliseconds
     * @param rangeEndMs Range end in milliseconds
     * @param exdates List of excluded dates in YYYYMMDD format
     * @param timezone Optional timezone ID
     * @param isAllDay Whether this is an all-day event (forces UTC for date calculations)
     * @return List of occurrence start times in milliseconds
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
