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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates and maintains the materialized occurrences table for events.
 *
 * Expands RRULEs through [IcalDavRRuleEngine] (ical4j) per RFC 5545:
 *   RecurrenceSet = (DTSTART ∪ RRULE ∪ RDATE) - EXDATE
 *
 * Occurrences are stored for O(1) range queries. This class covers generation on create,
 * regeneration when the rule or its exclusions change, extension to any range a read asks for
 * and to a band ahead of today ([ensureCovered]), cancelling one occurrence, and linking
 * exceptions to occurrences.
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

        private const val TAG = "OccurrenceGenerator"

        /** Match tolerance (exclusive) for an override's RECURRENCE-ID, as the link queries use. */
        private const val RECURRENCE_ID_TOLERANCE_MS = 60_000L

        /**
         * How far ahead [regenerateOccurrences] expands a series: DEFAULT_EXPANSION_MONTHS of 30
         * days. [EventCoordinator.ensureOccurrenceHorizon] keeps the band to this far.
         */
        const val EXPANSION_WINDOW_MS = DEFAULT_EXPANSION_MONTHS * 30L * SECONDS_PER_DAY * MILLISECONDS_PER_SECOND

        /** How far past a read's range [ensureCovered] expands, so nearby reads need no pass. */
        const val COVERAGE_BUFFER_MS = 182L * SECONDS_PER_DAY * MILLISECONDS_PER_SECOND

        /** A pass writes at most this many series, or roughly this many rows, per transaction. */
        private const val BATCH_SERIES = 50
        private const val BATCH_ROWS = 20_000

        /**
         * About how many rows one series may gain in one step ([fill] stops at the first engine
         * call that reaches it). Forward, a step past it keeps its rows and records how far it
         * got; back, it adds nothing.
         */
        private const val MAX_ROWS_PER_SERIES_STEP = 50_000
    }

    /** What one extension step reached, applied to the coverage record once its rows commit. */
    private class StepResult(val added: Int, val coveredTo: Long?, val coveredFrom: Long?, val complete: Boolean)

    /**
     * The coverage record, all guarded by coverageLock:
     * - forwardCoveredTo / pastCoveredFrom: how far each series is known to be expanded,
     *   including ranges that held no instance (an ended, sparse or capped rule), so the same
     *   range isn't expanded again;
     * - coveredFrom..coveredTo: the range an [ensureCovered] pass proved every series covers,
     *   empty while coveredTo < coveredFrom;
     * - wholeSeriesCoveredTo: how far [ensureCoveredFor] expanded each series from its DTSTART;
     * - forwardOnlyCoveredTo: how far [ensureCoveredFor] extended each series forward only, kept
     *   apart so a later fill back to DTSTART still runs;
     * - rowsVersion: bumped by every [generateOccurrences], which also drops the series' entries
     *   and empties the range. An entry or range is recorded only after its rows commit and only
     *   if rowsVersion is the one read when the pass selected, so a rewrite or a rolled-back
     *   batch never leaves a claim the table doesn't back.
     */
    private val coverageLock = Any()
    private val forwardCoveredTo = HashMap<Long, Long>()
    private val pastCoveredFrom = HashMap<Long, Long>()
    private val wholeSeriesCoveredTo = HashMap<Long, Long>()
    private val forwardOnlyCoveredTo = HashMap<Long, Long>()
    private var coveredFrom = Long.MAX_VALUE
    private var coveredTo = Long.MIN_VALUE
    private var rowsVersion = 0L
    private val coverageMutex = Mutex()

    private fun rowsVersion(): Long = synchronized(coverageLock) { rowsVersion }

    /**
     * Voids the coverage records of [eventId] after its rows were cut outside
     * [generateOccurrences] (a this-and-future split or delete), so the next read or search
     * expands it again instead of trusting a record for rows that are gone.
     */
    fun forgetCoverage(eventId: Long) = rowsRewritten(eventId)

    /** Voids the coverage record for [eventId]'s rewrite; called inside its transaction. */
    private fun rowsRewritten(eventId: Long) = synchronized(coverageLock) {
        rowsVersion++
        forwardCoveredTo.remove(eventId)
        pastCoveredFrom.remove(eventId)
        wholeSeriesCoveredTo.remove(eventId)
        forwardOnlyCoveredTo.remove(eventId)
        coveredFrom = Long.MAX_VALUE
        coveredTo = Long.MIN_VALUE
    }

    /** Applies [step] for [eventId] if nothing was rewritten since [version]. */
    private fun record(eventId: Long, step: StepResult, version: Long) = synchronized(coverageLock) {
        if (rowsVersion != version) return@synchronized
        step.coveredTo?.let { forwardCoveredTo.merge(eventId, it, ::maxOf) }
        step.coveredFrom?.let { pastCoveredFrom.merge(eventId, it, ::minOf) }
    }

    /**
     * Replaces [event]'s occurrences with those in [rangeStartMs] to [rangeEndMs], in one
     * transaction. A series gets one engine call's instances, the first [IcalDavRRuleEngine]
     * returns from [rangeStartMs]; [ensureCovered] adds the rest of a denser range.
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
            // A new or changed series may need rows the coverage record says are there (one
            // created on a far date writes none here), so its record goes even when nothing is
            // written.
            if (!event.rrule.isNullOrBlank()) rowsRewritten(event.id)
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

        val futureWindowMs = EXPANSION_WINDOW_MS

        // Second-aligned like the engine's output, so a first occurrence at
        // floor(startTs/1000)*1000 doesn't fall before rangeStart and get skipped.
        val eventStartAligned = secondAligned(event.startTs)
        val rangeStart = (now - pastWindowMs).coerceAtLeast(eventStartAligned)
        val rangeEnd = now + futureWindowMs
        return generateOccurrences(event, rangeStart, rangeEnd)
    }

    /**
     * Adds a recurring [event]'s occurrences after its latest rule row
     * ([OccurrencesDao.getLatestRuleStartTs]) up to [extendToMs]; with no rule row (only
     * exception-linked rows, or no rows), from DTSTART or [floorMs], whichever is later.
     *
     * Follows RFC 5545 overrides: an instance at an exception's RECURRENCE-ID is left out, since
     * the exception's linked row stands for it, and so is one whose start already has a row (a
     * moved occurrence that took another instance's time), which the insert would overwrite.
     * The instances come from [fill], so the engine's per-call limit doesn't end the step;
     * MAX_ROWS_PER_SERIES_STEP does. The event is
     * read again inside the transaction, so a rule changed meanwhile is the one expanded. A
     * series already recorded as expanded to [extendToMs] is skipped.
     *
     * @param floorMs where a series with no rule rows starts; null leaves a series with no rows
     *   at all alone
     * @return the number of occurrences added
     */
    suspend fun extendOccurrences(event: Event, extendToMs: Long, floorMs: Long? = null): Int {
        val version = rowsVersion()
        val step = extendForward(event.id, extendToMs, floorMs) ?: return 0
        record(event.id, step, version)
        return step.added
    }

    private suspend fun extendForward(eventId: Long, extendToMs: Long, floorMs: Long?): StepResult? {
        if (synchronized(coverageLock) { (forwardCoveredTo[eventId] ?: Long.MIN_VALUE) >= extendToMs }) return null
        return database.withTransaction {
            val current = eventsDao.getById(eventId) ?: return@withTransaction null
            if (current.rrule.isNullOrBlank()) return@withTransaction null
            val edge = occurrencesDao.getLatestRuleStartTs(current.id)
            if (edge == null && floorMs == null && occurrencesDao.getCountForEvent(current.id) == 0) {
                return@withTransaction null
            }
            // Rule rows are whole seconds, so the next instance is at least a second on.
            val fromMs = edge?.let { it + MILLISECONDS_PER_SECOND } ?: maxOf(secondAligned(current.startTs), floorMs ?: Long.MIN_VALUE)
            val fill = fill(current, fromMs, extendToMs)
            if (fill.rows.isNotEmpty()) occurrencesDao.insertAll(fill.rows)
            // A step stopped by the row limit reached only its last instance.
            val reached = if (fill.complete) extendToMs else fill.rows.last().startTs
            // A series with no rule rows that gained nothing may have instances before the
            // floor: record nothing, so a read further back expands it again.
            val coveredTo = if (edge == null && fill.rows.isEmpty()) null else reached
            StepResult(fill.rows.size, coveredTo, null, fill.complete)
        }
    }

    /** What [fill] collected, and whether it reached the end of its range. */
    private class Fill(val rows: List<Occurrence>, val complete: Boolean)

    /**
     * Collects [event]'s instances in [fromMs] to [toMs] that no override stands for: not within
     * RECURRENCE_ID_TOLERANCE_MS of an exception's RECURRENCE-ID, and not at a start that already
     * has a row for the series. It asks the engine again after every call that returned
     * instances, since EXDATEs count toward the per-call limit and a short call can still stop
     * before [toMs]; only an empty call ends the range. Stops, incomplete, once it holds
     * MAX_ROWS_PER_SERIES_STEP rows.
     */
    private suspend fun fill(event: Event, fromMs: Long, toMs: Long): Fill {
        val recurrenceIds = eventsDao.getExceptionsForMaster(event.id).mapNotNull { it.originalInstanceTime }
        val rows = mutableListOf<Occurrence>()
        var from = fromMs
        while (from < toMs) {
            val expanded = expandRRule(event, from, toMs)
            if (expanded.isEmpty()) break
            val taken = occurrencesDao.getStartTsInRange(event.id, from, toMs).toHashSet()
            expanded.filterTo(rows) { occ ->
                occ.startTs !in taken &&
                    recurrenceIds.none { kotlin.math.abs(it - occ.startTs) < RECURRENCE_ID_TOLERANCE_MS }
            }
            if (rows.size >= MAX_ROWS_PER_SERIES_STEP) return Fill(rows, complete = false)
            from = nextCallFrom(expanded)
        }
        return Fill(rows, complete = true)
    }

    private fun secondAligned(ms: Long): Long = (ms / MILLISECONDS_PER_SECOND) * MILLISECONDS_PER_SECOND

    /**
     * Where to ask the engine again after [expanded]: a second past its last start. Starts are
     * cut to whole seconds while the engine picks by the instant before the cut (a DTSTART with
     * milliseconds), so asking from 1 ms past would return the last start again.
     */
    private fun nextCallFrom(expanded: List<Occurrence>): Long = expanded.last().startTs + MILLISECONDS_PER_SECOND

    /**
     * Adds a recurring [event]'s occurrences before its earliest rule row
     * ([OccurrencesDao.getEarliestRuleStartTs]), back to [extendToMs] but not before DTSTART,
     * with the override and re-asking rules of [fill]. It adds all of them or, past
     * MAX_ROWS_PER_SERIES_STEP, none: a part would leave a gap below the earliest row that no
     * later step sees.
     *
     * @return the number of occurrences added; 0 for a non-recurring event, one with no rule
     *   rows, one already extended that far, or one past the row limit
     */
    suspend fun extendPastOccurrences(event: Event, extendToMs: Long): Int {
        val version = rowsVersion()
        val step = extendBack(event.id, extendToMs) ?: return 0
        record(event.id, step, version)
        return step.added
    }

    private suspend fun extendBack(eventId: Long, extendToMs: Long): StepResult? {
        if (synchronized(coverageLock) { (pastCoveredFrom[eventId] ?: Long.MAX_VALUE) <= extendToMs }) return null
        return database.withTransaction {
            val current = eventsDao.getById(eventId) ?: return@withTransaction null
            if (current.rrule.isNullOrBlank()) return@withTransaction null
            val currentMinTs = occurrencesDao.getEarliestRuleStartTs(current.id) ?: return@withTransaction null

            // Not before DTSTART, second-aligned as in regenerateOccurrences.
            val effectiveExtendTo = extendToMs.coerceAtLeast(secondAligned(current.startTs))
            if (currentMinTs <= effectiveExtendTo) return@withTransaction null

            // The range end is exclusive, so the existing occurrence at currentMinTs isn't
            // duplicated.
            val fill = fill(current, effectiveExtendTo, currentMinTs)
            if (!fill.complete) {
                android.util.Log.w(TAG, "Past extension of event ${current.id} passed the row limit; skipped")
                return@withTransaction StepResult(0, null, null, complete = false)
            }
            if (fill.rows.isNotEmpty()) occurrencesDao.insertAll(fill.rows)
            StepResult(fill.rows.size, null, extendToMs, complete = true)
        }
    }

    /**
     * Makes every repeating series' rows cover [startMs] to [endMs] (from [startMs] forward
     * only when [backward] is false), the way the platform calendar provider expands its
     * instances on a read outside the range it already expanded.
     *
     * Returns with no database work when the range last recorded (one pass's, joined with the
     * passes whose ranges met it) holds this one and nothing was rewritten since. Otherwise it
     * selects, inside a transaction (which waits for any open rewrite), the series whose rule
     * rows stop short of the range, those with only exception-linked rows and those with no
     * rows (not PENDING_DELETE); extends each to
     * [extendToMs] (back to [extendFromMs]) in transactions of at most BATCH_SERIES series or
     * about BATCH_ROWS rows; and, once every batch committed with nothing rewritten meanwhile
     * and nothing cut short, records the range (a forward-only pass records none). The work
     * runs on the IO dispatcher and stops between batches when the caller is cancelled. Writes
     * only occurrence rows: no pending operation, sync or reminder. A failure is logged, not
     * thrown. A call inside a Room transaction is skipped: it would wait for its own.
     */
    suspend fun ensureCovered(
        startMs: Long,
        endMs: Long,
        backward: Boolean = true,
        extendToMs: Long = endMs + COVERAGE_BUFFER_MS,
        extendFromMs: Long = startMs - COVERAGE_BUFFER_MS,
    ) {
        if (isCovered(startMs, endMs)) return
        if (database.inTransaction()) {
            android.util.Log.w(TAG, "ensureCovered called inside a transaction; skipped")
            return
        }
        try {
            withContext(Dispatchers.IO) {
                coverageMutex.withLock {
                    if (!isCovered(startMs, endMs)) {
                        coverPass(startMs, endMs, backward, extendToMs, extendFromMs)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "ensureCovered failed: ${e.message}", e)
        }
    }

    /**
     * Expands each repeating series in [fromStart] from its DTSTART to [toMs], and extends each
     * in [forwardOnly] to [toMs] after its latest rule row (from its DTSTART when it has none),
     * so a search can date a series whose stored rows don't hold its last, first or next
     * occurrence; returns how many series it worked on. A series in both sets is expanded from
     * its DTSTART.
     *
     * A series' rows are stored without gaps: forward steps continue after the latest rule row,
     * a backward step fills everything below the earliest one or nothing, and a this-and-future
     * split or delete removes only a tail (then calls [forgetCoverage]). So a series' latest
     * stored ended row is its last occurrence.
     *
     * Only these series are touched, and the range [ensureCovered] recorded stays as it was. A
     * series already covered this way to [toMs] is skipped without database work, and a step
     * stopped by the row limit is recorded too, so the database writes it causes, which make a
     * search emit again, don't each add another step's rows. A forward-only cover keeps its own
     * record, so it never stands in for a fill back to DTSTART. Steps run in the batches and
     * under the lock [ensureCovered] uses, on the IO dispatcher, and like it this writes only
     * occurrence rows, logs a failure instead of throwing and skips a call made inside a Room
     * transaction.
     */
    suspend fun ensureCoveredFor(
        fromStart: Collection<Long>,
        toMs: Long,
        forwardOnly: Collection<Long> = emptyList(),
    ): Int {
        val wholeIds = fromStart.toSet()
        val forwardIds = forwardOnly.toSet() - wholeIds
        fun pending() = synchronized(coverageLock) {
            val whole = wholeIds.filter { (wholeSeriesCoveredTo[it] ?: Long.MIN_VALUE) < toMs }
            val forward = forwardIds.filter { id ->
                (wholeSeriesCoveredTo[id] ?: Long.MIN_VALUE) < toMs && (forwardOnlyCoveredTo[id] ?: Long.MIN_VALUE) < toMs
            }
            whole to forward
        }
        val (wholeNow, forwardNow) = pending()
        if (wholeNow.isEmpty() && forwardNow.isEmpty()) return 0
        if (database.inTransaction()) {
            android.util.Log.w(TAG, "ensureCoveredFor called inside a transaction; skipped")
            return 0
        }
        return try {
            withContext(Dispatchers.IO) {
                coverageMutex.withLock {
                    val (whole, forward) = pending()
                    val forwardSet = forward.toSet()
                    val version = rowsVersion()
                    inBatches(whole + forward, version) { id ->
                        val step = extendForward(id, toMs, floorMs = Long.MIN_VALUE)
                        if (id in forwardSet) return@inBatches step
                        val back = extendBack(id, Long.MIN_VALUE)
                        StepResult(
                            added = (step?.added ?: 0) + (back?.added ?: 0),
                            coveredTo = step?.coveredTo,
                            coveredFrom = back?.coveredFrom,
                            complete = (step?.complete ?: true) && (back?.complete ?: true),
                        )
                    }
                    synchronized(coverageLock) {
                        if (rowsVersion == version) {
                            whole.forEach { wholeSeriesCoveredTo.merge(it, toMs, ::maxOf) }
                            forward.forEach { forwardOnlyCoveredTo.merge(it, toMs, ::maxOf) }
                        }
                    }
                    whole.size + forward.size
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "ensureCoveredFor failed: ${e.message}", e)
            0
        }
    }

    private fun isCovered(startMs: Long, endMs: Long): Boolean = synchronized(coverageLock) {
        coveredTo >= coveredFrom && startMs >= coveredFrom && endMs <= coveredTo
    }

    private suspend fun coverPass(startMs: Long, endMs: Long, backward: Boolean, extendToMs: Long, extendFromMs: Long) {
        data class Selection(val forward: List<Long>, val noRows: List<Long>, val past: List<Long>, val version: Long)
        val selection = database.withTransaction {
            val version = rowsVersion()
            val notForward = { id: Long -> synchronized(coverageLock) { (forwardCoveredTo[id] ?: Long.MIN_VALUE) < endMs } }
            Selection(
                forward = occurrencesDao.getRecurringEventsNeedingExtension(endMs).filter(notForward),
                noRows = occurrencesDao.getRecurringEventsWithNoOccurrences().filter(notForward),
                past = if (backward) {
                    occurrencesDao.getRecurringEventsNeedingPastExtension(startMs)
                        .filter { id -> synchronized(coverageLock) { (pastCoveredFrom[id] ?: Long.MAX_VALUE) > startMs } }
                } else emptyList(),
                version = version,
            )
        }

        val floor = if (backward) extendFromMs else startMs
        var complete = true
        complete = inBatches(selection.forward + selection.noRows, selection.version) { id ->
            extendForward(id, extendToMs, floor)
        } && complete
        complete = inBatches(selection.past, selection.version) { id -> extendBack(id, extendFromMs) } && complete

        // A forward-only pass proves nothing before its start, so it records no range.
        if (!complete || !backward) return
        synchronized(coverageLock) {
            if (rowsVersion != selection.version) return
            // Only a range that meets the recorded one joins it: the days between two separate
            // reads weren't proven, and a series with no rule rows was filled near each read only.
            if (coveredTo < coveredFrom || startMs > coveredTo || endMs < coveredFrom) {
                coveredFrom = startMs
                coveredTo = endMs
            } else {
                coveredTo = maxOf(coveredTo, endMs)
                coveredFrom = minOf(coveredFrom, startMs)
            }
        }
    }

    /**
     * Runs [extend] over [ids] in transactions of BATCH_SERIES series or about BATCH_ROWS rows,
     * recording each step's coverage only after its batch commits; returns whether every step
     * was complete.
     */
    private suspend fun inBatches(ids: List<Long>, version: Long, extend: suspend (Long) -> StepResult?): Boolean {
        var complete = true
        var index = 0
        while (index < ids.size) {
            coroutineContext.ensureActive()
            val committed = mutableListOf<Pair<Long, StepResult>>()
            index = database.withTransaction {
                committed.clear()
                var i = index
                var rows = 0
                while (i < ids.size && i - index < BATCH_SERIES && rows < BATCH_ROWS) {
                    extend(ids[i])?.let { step ->
                        rows += step.added
                        committed += ids[i] to step
                    }
                    i++
                }
                i
            }
            for ((id, step) in committed) {
                record(id, step, version)
                if (!step.complete) complete = false
            }
        }
        return complete
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
