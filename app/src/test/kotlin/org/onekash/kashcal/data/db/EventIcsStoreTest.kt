package org.onekash.kashcal.data.db

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.EventIcsPart
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the [EventIcsSource] contract on [EventIcsStore] over Room, plus the storage rules. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class EventIcsStoreTest : EventIcsSourceContractTest() {

    private val database: KashCalDatabase =
        TestDatabases.inMemory(ApplicationProvider.getApplicationContext()).build()
    private val store = EventIcsStore(database)
    override val source: EventIcsSource = store

    private val calendarId: Long = runBlocking {
        val accountId = database.accountsDao().insert(Account(provider = AccountProvider.LOCAL, email = "a@example.test"))
        database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = "https://dav.example.test/cal/", displayName = "C", color = 0)
        )
    }
    private var nextUid = 1

    @After
    fun tearDown() = database.close()

    override fun series(): Event = insert(null, null)

    override fun placeholder(): Event = insert(null, null, PLACEHOLDER_EXTRA)

    override fun occurrence(series: Event, caldavUrl: String?): Event = insert(series.id, caldavUrl)

    private fun insert(seriesId: Long?, url: String?, extra: Map<String, String>? = null): Event = runBlocking {
        val n = nextUid++
        val event = Event(
            uid = if (seriesId == null) "uid-$n" else "uid-series", calendarId = calendarId, title = "t",
            startTs = n * 1000L, endTs = n * 1000L + 1, dtstamp = 0, originalEventId = seriesId,
            originalInstanceTime = seriesId?.let { n * 1000L }, caldavUrl = url, extraProperties = extra,
        )
        event.copy(id = database.eventsDao().insert(event))
    }

    private fun parts(eventId: Long) = runBlocking { database.eventIcsDao().parts(eventId) }

    @Test
    fun `texts at and around the piece size round trip`() = runBlocking {
        for (length in listOf(1, EventIcsStore.PART_CHARS, EventIcsStore.PART_CHARS + 1, 2_500_000)) {
            val s = series()
            val text = buildString { while (this.length < length) append("DESCRIPTION:äöü x\r\n") }.take(length)
            store.write(s.id, text)
            assertEquals("length $length", text, store.read(s))
            val expectedParts = (length + EventIcsStore.PART_CHARS - 1) / EventIcsStore.PART_CHARS
            assertEquals("length $length", expectedParts, parts(s.id).size)
            assertTrue(parts(s.id).all { it.content.codePointCount(0, it.content.length) <= EventIcsStore.PART_CHARS })
        }
    }

    @Test
    fun `four-byte characters across every cut stay whole`() = runBlocking {
        val s = series()
        val emoji = "😀" // one code point, two UTF-16 units, four UTF-8 bytes
        val text = "a" + emoji.repeat(EventIcsStore.PART_CHARS * 2 + 5)
        store.write(s.id, text)
        assertEquals(text, store.read(s))
        val stored = parts(s.id)
        assertEquals(3, stored.size)
        stored.forEach { part ->
            assertTrue(!part.content.first().isLowSurrogate() && !part.content.last().isHighSurrogate())
        }
    }

    @Test
    fun `a gap in the pieces throws instead of returning a shorter text`() = runBlocking {
        val s = series()
        database.eventIcsDao().insertAll(listOf(EventIcsPart(s.id, 0, "a"), EventIcsPart(s.id, 2, "c")))
        assertThrows(IcsPartsCorrupt::class.java) { runBlocking { store.read(s) } }
        Unit
    }

    @Test
    fun `a shorter write leaves none of the old pieces`() = runBlocking {
        val s = series()
        store.write(s.id, "x".repeat(EventIcsStore.PART_CHARS * 2 + 1))
        assertEquals(3, parts(s.id).size)
        store.write(s.id, "short")
        assertEquals(listOf(EventIcsPart(s.id, 0, "short")), parts(s.id))
    }

    @Test
    fun `a write for a row deleted meanwhile does nothing and keeps the caller's transaction`() = runBlocking {
        val s = series()
        database.eventsDao().deleteById(s.id)
        database.runInTransaction {
            store.writeResource(s.id, URL, "late body")
        }
        assertTrue(parts(s.id).isEmpty())
    }

    @Test
    fun `deleting the event deletes its pieces`() = runBlocking {
        val s = series()
        store.write(s.id, "x")
        database.eventsDao().deleteById(s.id)
        assertTrue(parts(s.id).isEmpty())
    }

    @Test
    fun `a write inside a transaction that fails is rolled back`() = runBlocking {
        val s = series()
        store.write(s.id, "before")
        runCatching {
            database.runInTransaction {
                store.write(s.id, "after")
                error("boom")
            }
        }
        assertEquals("before", store.read(s))
        assertNull(store.readOwn(s.id + 1000))
    }
}
