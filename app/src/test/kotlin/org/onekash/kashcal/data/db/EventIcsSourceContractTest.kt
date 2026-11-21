package org.onekash.kashcal.data.db

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.testutil.FakeEventIcsSource
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Holds [EventIcsStore] and [FakeEventIcsSource] to the one [EventIcsSource] contract, so tests
 * built on the fake can't pass on behaviour the store doesn't have. Each subclass supplies the
 * source and the rows it needs.
 */
abstract class EventIcsSourceContractTest {

    protected abstract val source: EventIcsSource

    /** Creates a series row and returns it. */
    protected abstract fun series(): Event

    /** Creates an occurrence of [series] stored at [caldavUrl] and returns it. */
    protected abstract fun occurrence(series: Event, caldavUrl: String?): Event

    @Test
    fun `an event without ICS reads null`() = runBlocking {
        assertNull(source.read(series()))
    }

    @Test
    fun `a written ICS reads back whole`() = runBlocking {
        val s = series()
        source.write(s.id, "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n")
        assertEquals("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n", source.read(s))
    }

    @Test
    fun `an occurrence without its own reads its series' and readOwn ignores the series`() = runBlocking {
        val s = series()
        val o = occurrence(s, "https://dav.example.test/cal/a.ics")
        source.write(s.id, "series")
        assertEquals("series", source.read(o))
        assertNull(source.readOwn(o.id))
    }

    @Test
    fun `an occurrence with its own reads its own`() = runBlocking {
        val s = series()
        val o = occurrence(s, null)
        source.write(s.id, "series")
        source.write(o.id, "own")
        assertEquals("own", source.read(o))
    }

    @Test
    fun `writing empty or null deletes`() = runBlocking {
        val s = series()
        source.write(s.id, "x")
        source.write(s.id, "")
        assertNull(source.read(s))
        source.write(s.id, "x")
        source.write(s.id, null)
        assertNull(source.read(s))
        source.write(s.id, "x")
        source.delete(s.id)
        assertNull(source.read(s))
    }

    @Test
    fun `writeResource replaces the copies of occurrences at its URL and keeps the others`() = runBlocking {
        val s = series()
        val sameUrl = occurrence(s, URL)
        val otherUrl = occurrence(s, "https://dav.example.test/cal/other.ics")
        val unsynced = occurrence(s, null)
        listOf(sameUrl, otherUrl, unsynced).forEach { source.write(it.id, "own ${it.id}") }

        source.writeResource(s.id, URL, "resource")

        assertEquals("resource", source.readOwn(s.id))
        assertNull(source.readOwn(sameUrl.id))
        assertEquals("resource", source.read(sameUrl))
        assertEquals("own ${otherUrl.id}", source.readOwn(otherUrl.id))
        assertEquals("own ${unsynced.id}", source.readOwn(unsynced.id))
    }

    @Test
    fun `writeResource without a URL only writes the series`() = runBlocking {
        val s = series()
        val o = occurrence(s, null)
        source.write(o.id, "own")
        source.writeResource(s.id, null, "resource")
        assertEquals("resource", source.readOwn(s.id))
        assertEquals("own", source.readOwn(o.id))
    }

    @Test
    fun `a pull placeholder reads null for itself and its occurrences read what it stores`() = runBlocking {
        val placeholder = placeholder()
        val orphan = occurrence(placeholder, URL)
        source.writeResource(placeholder.id, URL, "orphan resource")
        assertNull(source.read(placeholder))
        assertEquals("orphan resource", source.readOwn(placeholder.id))
        assertEquals("orphan resource", source.read(orphan))
    }

    /** Creates a pull placeholder series (no URL, the synthetic marker) and returns it. */
    protected abstract fun placeholder(): Event

    protected companion object {
        val PLACEHOLDER_EXTRA = mapOf("X-KASHCAL-SYNTHETIC-MASTER" to "true")

        const val URL = "https://dav.example.test/cal/a.ics"
    }
}

/** Runs the contract on [FakeEventIcsSource]. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class FakeEventIcsSourceContractTest : EventIcsSourceContractTest() {
    private val fake = FakeEventIcsSource()
    private var nextId = 1L
    override val source: EventIcsSource = fake

    override fun series(): Event = event(nextId++, null)

    override fun placeholder(): Event = event(nextId++, null).copy(extraProperties = PLACEHOLDER_EXTRA)

    override fun occurrence(series: Event, caldavUrl: String?): Event =
        event(nextId++, series.id, caldavUrl).also { fake.occurrence(it.id, series.id, caldavUrl) }

    private fun event(id: Long, seriesId: Long?, url: String? = null) = Event(
        id = id, uid = "uid-$id", calendarId = 1, title = "t", startTs = 0, endTs = 1, dtstamp = 0,
        originalEventId = seriesId, originalInstanceTime = seriesId?.let { id }, caldavUrl = url,
    )
}
