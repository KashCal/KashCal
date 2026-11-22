package org.onekash.kashcal.data.db

import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.EventIcsPart
import org.onekash.kashcal.sync.strategy.isPullSyntheticMaster
import javax.inject.Inject
import javax.inject.Singleton

/** Stores events' ICS in `event_ics`, split into pieces of at most [PART_CHARS] characters. */
@Singleton
class EventIcsStore @Inject constructor(
    private val database: KashCalDatabase,
) : EventIcsSource {

    private val dao get() = database.eventIcsDao()

    override suspend fun read(event: Event): String? {
        if (event.isPullSyntheticMaster) return null
        return readOwn(event.id) ?: event.originalEventId?.let { readOwn(it) }
    }

    override suspend fun readOwn(eventId: Long): String? {
        val parts = dao.parts(eventId)
        if (parts.isEmpty()) return null
        val indexes = parts.map { it.partIndex }
        if (indexes != indexes.indices.toList()) throw IcsPartsCorrupt(eventId, indexes)
        return parts.joinToString("") { it.content }
    }

    override suspend fun write(eventId: Long, ics: String?) {
        database.runInTransaction {
            dao.deleteFor(eventId)
            // A row deleted meanwhile (the user deleted it while a push was in flight) takes no
            // pieces, as an UPDATE of it would change nothing; inserting them would fail the
            // foreign key and the caller's whole transaction.
            if (!ics.isNullOrEmpty() && database.eventsDao().existingIds(listOf(eventId)).isNotEmpty()) {
                dao.insertAll(split(ics).mapIndexed { i, piece -> EventIcsPart(eventId, i, piece) })
            }
        }
    }

    override suspend fun writeResource(seriesId: Long, resourceUrl: String?, ics: String?) {
        database.runInTransaction {
            write(seriesId, ics)
            if (resourceUrl != null) dao.deleteForOccurrencesAt(seriesId, resourceUrl)
        }
    }

    override suspend fun delete(eventId: Long) = dao.deleteFor(eventId)

    override suspend fun <R> inTransaction(block: suspend () -> R): R = database.runInTransaction(block)

    companion object {
        /**
         * Holds the most characters (Unicode code points) one piece holds. At 4 UTF-8 bytes per
         * character at worst, a piece is at most 1 MB, half the CursorWindow. `MIGRATION_28_29`
         * splits with SQLite's `substr`, which counts code points too, so both give the same
         * pieces.
         */
        const val PART_CHARS = 262_144

        /** Splits [text] into pieces of at most [PART_CHARS] code points, never inside a pair. */
        internal fun split(text: String): List<String> {
            val pieces = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                val remaining = text.codePointCount(start, text.length)
                val end = if (remaining <= PART_CHARS) text.length else text.offsetByCodePoints(start, PART_CHARS)
                pieces += text.substring(start, end)
                start = end
            }
            return pieces
        }
    }
}
