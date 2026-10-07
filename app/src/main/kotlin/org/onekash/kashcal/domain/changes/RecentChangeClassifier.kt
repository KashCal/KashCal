package org.onekash.kashcal.domain.changes

import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.ChangedField
import org.onekash.kashcal.sync.model.SyncChange

/**
 * Turns one sync's [SyncChange]s into Recent changes rows: one row per event or changed
 * occurrence, worded the way the user sees the change. The rules run in this order:
 * 1. A calendar's first pull records nothing: it lists everything the server has.
 * 2. Each newly cancelled instance of a series becomes a cancelled (DELETED) occurrence row.
 * 3. A first-time changed occurrence of an existing series is a change to that occurrence, not
 *    a new event; a series replacing the pull's placeholder is new.
 * 4. Folds: occurrences of a series new in this sync, occurrences whose change repeats the
 *    series' change without moving (a client may copy a series edit into each override), and
 *    occurrence deletions of a deleted series all go into the series row. An occurrence that
 *    only repeats the series' change still refreshes its stored row ([Classified.refreshes]).
 * 5. A change naming no visible field is dropped (an alarm or busy/free edit).
 * 6. A forced full sync records nothing new: it also lists events that only came into the
 *    sync window.
 * 7. Rows on the same key merge ([RecentChangeMerge]); for a cancelled changed occurrence, the
 *    pruned occurrence's snapshot (what the user last saw) wins over the bare slot.
 *
 * Batch rules match on (calendar, UID): one UID can live in two calendars.
 */
object RecentChangeClassifier {

    private data class Candidate(val entry: RecentChangeEntry, val forced: Boolean, val fromExdate: Boolean)

    private data class Key(val calendarId: Long, val uid: String, val instanceTs: Long)

    private val RecentChangeEntry.key get() = Key(calendarId, eventUid, instanceTs)
    private val RecentChangeEntry.seriesKey get() = Key(calendarId, eventUid, 0)

    /**
     * The rows a sync records ([entries]), plus [refreshes]: occurrences folded into their series
     * because they only repeat the series' change. A refresh adds no row; what it updates is
     * [RecentChangesRecorder.record]'s rule.
     */
    data class Classified(val entries: List<RecentChangeEntry>, val refreshes: List<RecentChangeEntry>)

    fun classify(changes: List<SyncChange>): Classified {
        val candidates = changes.filterNot { it.isFirstPull }.flatMap(::expand)

        val series = candidates.filter { !it.entry.isOccurrence }.associateBy { it.entry.key }
        fun parentOf(c: Candidate) = c.entry.takeIf { it.isOccurrence }?.let { series[it.seriesKey]?.entry }
        val refreshes = candidates.filter { c -> parentOf(c)?.let { repeatsSeriesChange(c.entry, it) } == true }
            .map { it.entry }
        val kept = candidates.filterNot { c ->
            val e = c.entry
            val parent = parentOf(c) ?: return@filterNot false
            when {
                parent.type == ChangeType.NEW -> true
                parent.type == ChangeType.DELETED && e.type == ChangeType.DELETED && !c.fromExdate -> true
                repeatsSeriesChange(e, parent) -> true
                else -> false
            }
        }

        val visible = kept.filterNot { c ->
            val e = c.entry
            (e.type == ChangeType.MODIFIED && e.changedFields.isEmpty() && e.previousStartTs == null) ||
                (c.forced && e.type == ChangeType.NEW)
        }

        val merged = LinkedHashMap<Key, Candidate>()
        for (c in visible) {
            val prior = merged[c.entry.key]
            merged[c.entry.key] = when {
                prior == null -> c
                prior.entry.type == ChangeType.DELETED && c.entry.type == ChangeType.DELETED ->
                    if (c.fromExdate) prior else c
                else -> c.copy(entry = RecentChangeMerge.merge(prior.entry, c.entry))
            }
        }
        return Classified(merged.values.map { it.entry }, refreshes)
    }

    /** An occurrence change that only repeats its series' change, with no move of its own. */
    private fun repeatsSeriesChange(e: RecentChangeEntry, parent: RecentChangeEntry): Boolean =
        parent.type == ChangeType.MODIFIED && e.type == ChangeType.MODIFIED &&
            ChangedField.TIME !in e.changedFields && parent.changedFields.containsAll(e.changedFields)

    /** Builds the change's own row plus a cancelled row per newly cancelled instance. */
    private fun expand(change: SyncChange): List<Candidate> {
        val base = RecentChangeEntry(
            calendarId = change.calendarId,
            eventUid = change.eventUid,
            instanceTs = change.instanceTs,
            eventId = change.eventId,
            type = change.type,
            title = change.eventTitle,
            startTs = change.eventStartTs,
            endTs = change.eventEndTs,
            isAllDay = change.isAllDay,
            isRecurring = change.isRecurring,
            changedFields = change.changedFields,
            previousStartTs = change.previousStartTs,
            previousIsAllDay = change.previousIsAllDay
        )
        val own = when {
            change.replacedPlaceholder ->
                base.copy(type = ChangeType.NEW, changedFields = emptySet(), previousStartTs = null, previousIsAllDay = null)
            change.type == ChangeType.NEW && change.instanceTs != 0L && !change.seriesIsPlaceholder ->
                base.copy(type = ChangeType.MODIFIED)
            else -> base
        }
        val duration = change.eventEndTs - change.eventStartTs
        val cancelled = change.cancelledInstances.map { instance ->
            Candidate(
                base.copy(
                    instanceTs = instance, eventId = null, type = ChangeType.DELETED,
                    startTs = instance, endTs = instance + duration, isRecurring = true,
                    changedFields = emptySet(), previousStartTs = null, previousIsAllDay = null
                ),
                forced = change.isForcedPull, fromExdate = true
            )
        }
        return listOf(Candidate(own, change.isForcedPull, fromExdate = false)) + cancelled
    }
}
