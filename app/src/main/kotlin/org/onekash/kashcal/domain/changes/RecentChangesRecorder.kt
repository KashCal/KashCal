package org.onekash.kashcal.domain.changes

import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.RecentChange
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.SyncChange
import javax.inject.Inject
import javax.inject.Singleton

/** Writes a sync's changes to the Recent changes log (`recent_changes`). */
@Singleton
class RecentChangesRecorder @Inject constructor(
    private val database: KashCalDatabase
) {
    private val dao by lazy { database.recentChangesDao() }

    /**
     * Classifies [changes] ([RecentChangeClassifier]), prunes rows older than [RETENTION_MS],
     * and merges each entry into its row ([RecentChangeMerge]), all in one transaction.
     * Returns the entries recorded, empty when the sync had nothing to show.
     *
     * A deleted series takes its stored occurrence rows with it; that cleanup runs before this
     * sync's entries are written. A refresh ([RecentChangeClassifier.Classified.refreshes])
     * updates only the title, times, all-day flag and event id of an occurrence row already
     * stored, and writes nothing when those are unchanged: its type, fields, previous start,
     * arrival time and dismissal stay, and it isn't returned.
     *
     * A row keeps its event id only while that event exists: the pull may delete an event it
     * reported (a duplicate master cleaned up after the batch), and the foreign key would fail
     * the whole batch.
     */
    suspend fun record(changes: List<SyncChange>, now: Long = System.currentTimeMillis()): List<RecentChangeEntry> {
        val (entries, refreshes) = RecentChangeClassifier.classify(changes)
        database.runInTransaction {
            dao.deleteOlderThan(now - RETENTION_MS)
            if (entries.isEmpty() && refreshes.isEmpty()) return@runInTransaction
            val liveEventIds = (entries + refreshes).mapNotNull { it.eventId }.distinct()
                .chunked(MAX_QUERY_ARGS)
                .flatMap { database.eventsDao().existingIds(it) }
                .toSet()
            for (entry in entries) {
                if (!entry.isOccurrence && entry.type == ChangeType.DELETED) {
                    dao.deleteOccurrencesOf(entry.calendarId, entry.eventUid)
                }
            }
            for (entry in entries) {
                val current = dao.getByKey(entry.calendarId, entry.eventUid, entry.instanceTs)
                val merged = RecentChangeMerge.merge(current?.takeIf { it.dismissedAt == null }?.toEntry(), entry)
                val row = merged.toRow(
                    id = current?.id ?: 0,
                    detectedAt = now,
                    eventId = merged.eventId?.takeIf { it in liveEventIds }
                )
                if (current == null) dao.insert(row) else dao.update(row)
            }
            for (refresh in refreshes) {
                val current = dao.getByKey(refresh.calendarId, refresh.eventUid, refresh.instanceTs) ?: continue
                val refreshed = current.copy(
                    title = refresh.title, startTs = refresh.startTs, endTs = refresh.endTs,
                    isAllDay = refresh.isAllDay, eventId = refresh.eventId?.takeIf { it in liveEventIds }
                )
                // An unchanged write would still make an open sheet re-read the log.
                if (refreshed != current) dao.update(refreshed)
            }
        }
        return entries
    }

    private fun RecentChange.toEntry() = RecentChangeEntry(
        calendarId = calendarId, eventUid = eventUid, instanceTs = instanceTs, eventId = eventId,
        type = changeType, title = title, startTs = startTs, endTs = endTs,
        isAllDay = isAllDay, isRecurring = isRecurring, changedFields = changedFields,
        previousStartTs = previousStartTs, previousIsAllDay = previousIsAllDay
    )

    private fun RecentChangeEntry.toRow(id: Long, detectedAt: Long, eventId: Long?) = RecentChange(
        id = id, calendarId = calendarId, eventUid = eventUid, instanceTs = instanceTs, eventId = eventId,
        changeType = type, title = title, startTs = startTs, endTs = endTs, isAllDay = isAllDay,
        isRecurring = isRecurring, detectedAt = detectedAt, changedFields = changedFields,
        previousStartTs = previousStartTs, previousIsAllDay = previousIsAllDay, dismissedAt = null
    )

    companion object {
        /** How long a row stays in Recent changes after it arrived. */
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        // SQLite's default limit on bound arguments per statement is 999.
        private const val MAX_QUERY_ARGS = 500
    }
}
