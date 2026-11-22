package org.onekash.kashcal.testutil

import org.onekash.kashcal.data.db.EventIcsSource
import org.onekash.kashcal.data.db.IcsPartsCorrupt
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.sync.strategy.isPullSyntheticMaster

/**
 * Holds events' ICS in memory for tests whose collaborators are mocks, with the contract of
 * [org.onekash.kashcal.data.db.EventIcsStore] (`EventIcsSourceContractTest` holds both to it).
 *
 * [writeResource] needs to know an occurrence's series and URL, which the fake can't read from
 * Room: register each occurrence a test relies on with [occurrence]. [failReadsOf] makes reads of
 * an event throw as corrupt stored pieces would.
 */
class FakeEventIcsSource : EventIcsSource {

    private data class Occurrence(val seriesId: Long, val caldavUrl: String?)

    private val stored = mutableMapOf<Long, String>()
    private val occurrences = mutableMapOf<Long, Occurrence>()
    private val failing = mutableSetOf<Long>()

    /** Lists every write in order as (event id, text), null for a delete. */
    val writes = mutableListOf<Pair<Long, String?>>()

    /** Registers [id] as an occurrence of [seriesId] stored at [caldavUrl]. */
    fun occurrence(id: Long, seriesId: Long, caldavUrl: String?) {
        occurrences[id] = Occurrence(seriesId, caldavUrl)
    }

    /** Makes every read of [eventId]'s own ICS throw [IcsPartsCorrupt]. */
    fun failReadsOf(eventId: Long) {
        failing += eventId
    }

    /** Returns the stored text of [eventId] without the read checks, for assertions. */
    fun stored(eventId: Long): String? = stored[eventId]

    override suspend fun read(event: Event): String? {
        if (event.isPullSyntheticMaster) return null
        return readOwn(event.id) ?: event.originalEventId?.let { readOwn(it) }
    }

    override suspend fun readOwn(eventId: Long): String? {
        if (eventId in failing) throw IcsPartsCorrupt(eventId, listOf(0, 2))
        return stored[eventId]
    }

    override suspend fun write(eventId: Long, ics: String?) {
        writes += eventId to ics?.ifEmpty { null }
        if (ics.isNullOrEmpty()) stored.remove(eventId) else stored[eventId] = ics
    }

    override suspend fun writeResource(seriesId: Long, resourceUrl: String?, ics: String?) {
        write(seriesId, ics)
        if (resourceUrl == null) return
        occurrences.filter { (_, o) -> o.seriesId == seriesId && o.caldavUrl == resourceUrl }
            .keys.forEach { stored.remove(it) }
    }

    override suspend fun delete(eventId: Long) {
        writes += eventId to null
        stored.remove(eventId)
    }

    /** Runs [block] directly: the fake has no transaction to roll back. */
    override suspend fun <R> inTransaction(block: suspend () -> R): R = block()
}
