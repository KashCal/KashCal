package org.onekash.kashcal.data.db

import org.onekash.kashcal.data.db.entity.Event

/**
 * Reads and writes the stored ICS of events: the text of the calendar object resource (RFC 4791
 * §4.1) a server or an imported file gave, kept so pushes and exports can patch it instead of
 * rebuilding it and losing what the app doesn't model.
 *
 * A resource's ICS is stored once, on its series row. A changed occurrence has its own only when
 * its text differs; otherwise it reads its series'. Writes run in the caller's transaction when
 * there is one ([KashCalDatabase.runInTransaction]), so an event row and its ICS change together.
 * An empty text counts as none: writing it deletes the ICS.
 */
interface EventIcsSource {

    /**
     * Returns [event]'s own ICS, else its series' (through `originalEventId`), else null.
     *
     * A pull placeholder series reads null: what it stores is an orphan occurrence's resource,
     * which has no series VEVENT, so a series-level patch or export of it generates fresh as it
     * always has. Its occurrences still read it ([readOwn] of the series).
     *
     * @throws IcsPartsCorrupt when the stored pieces have a gap
     */
    suspend fun read(event: Event): String?

    /** Returns the ICS stored on [eventId] itself, ignoring its series; null when none. */
    suspend fun readOwn(eventId: Long): String?

    /**
     * Replaces [eventId]'s ICS with [ics]; null or empty deletes it. A row that no longer exists
     * is left as it is, as an UPDATE of it would be.
     */
    suspend fun write(eventId: Long, ics: String?)

    /**
     * Stores the body of the resource at [resourceUrl] on its series row [seriesId] and deletes
     * the own ICS of that series' occurrences pulled from the same URL, which the new body
     * replaces. Call it after any URL change of those rows, in the same transaction.
     */
    suspend fun writeResource(seriesId: Long, resourceUrl: String?, ics: String?)

    /** Deletes [eventId]'s own ICS. */
    suspend fun delete(eventId: Long)

    /**
     * Runs [block] in one transaction ([KashCalDatabase.runInTransaction]), so the event rows it
     * updates and the ICS it writes commit together: a resource's etag stored without its body
     * would let the next write send an old body under a new If-Match.
     */
    suspend fun <R> inTransaction(block: suspend () -> R): R
}

/** Signals stored ICS pieces that don't run 0..n-1, so joining them would lose text. */
class IcsPartsCorrupt(eventId: Long, indexes: List<Int>) :
    IllegalStateException("Stored ICS of event $eventId has pieces $indexes, expected 0..${indexes.size - 1}")
