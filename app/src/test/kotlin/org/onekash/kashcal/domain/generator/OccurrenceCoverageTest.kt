package org.onekash.kashcal.domain.generator

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.OccurrencesDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.rrule.RruleBuilder
import org.onekash.kashcal.domain.search.SearchQuery
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [OccurrenceGenerator.ensureCovered]: a covered range costs no database work, a rewritten
 * series is rechecked alone, a failed expansion doesn't throw, a call inside a transaction is
 * refused instead of deadlocking, and the pass writes only occurrence rows. Also
 * [OccurrenceGenerator.ensureCoveredFor], which expands only the series a search matched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class OccurrenceCoverageTest {

    private lateinit var database: KashCalDatabase
    private lateinit var generator: OccurrenceGenerator
    private val calls = mutableListOf<String>()

    /** Runs before each occurrence insert, to fail or interleave with a pass. */
    private var onInsertAll: (suspend (List<Occurrence>) -> Unit)? = null
    private var calendarId = 0L

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val year = 365 * day
    private val now = System.currentTimeMillis() / 1000 * 1000

    @Before
    fun setUp(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val real = database.occurrencesDao()
        // Counts the selection queries a pass runs.
        val counting = object : OccurrencesDao by real {
            override suspend fun getRecurringEventsNeedingExtension(targetTs: Long): List<Long> {
                calls += "forward"; return real.getRecurringEventsNeedingExtension(targetTs)
            }
            override suspend fun getRecurringEventsNeedingPastExtension(targetTs: Long): List<Long> {
                calls += "past"; return real.getRecurringEventsNeedingPastExtension(targetTs)
            }
            override suspend fun getRecurringEventsWithNoOccurrences(): List<Long> {
                calls += "noRows"; return real.getRecurringEventsWithNoOccurrences()
            }
            override suspend fun insertAll(occurrences: List<Occurrence>) {
                onInsertAll?.invoke(occurrences)
                real.insertAll(occurrences)
            }
        }
        generator = OccurrenceGenerator(
            database, counting, database.eventsDao(),
            TestDataStoreFactory.createWithSyncLookback(KashCalDataStore.DEFAULT_SYNC_PAST_DAYS),
        )
        val accountId = database.accountsDao().insert(Account(provider = AccountProvider.LOCAL, email = "local@example.test"))
        calendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = "local://cal", displayName = "Local", color = 1)
        )
        Unit
    }

    @After
    fun tearDown() = database.close()

    private suspend fun weekly(title: String, rowsTo: Long = now + 720 * day): Event {
        val id = database.eventsDao().insert(
            Event(
                uid = "uid-$title", calendarId = calendarId, title = title, startTs = now + day,
                endTs = now + day + hour, dtstamp = 0L, rrule = "FREQ=WEEKLY", timezone = "UTC",
                syncStatus = SyncStatus.SYNCED,
            )
        )
        val event = database.eventsDao().getById(id)!!
        generator.generateOccurrences(event, now, rowsTo)
        return event
    }

    @Test
    fun `a read between two covered ranges still shows a series that ended years ago`(): Unit = runBlocking {
        // Weekly for 5 years, ended 3 years ago, saved the way a pull, a feed refresh or a file
        // import saves it: regenerateOccurrences over the 365-day lookback to 720 days ahead
        // holds none of its instances, so it has no rows.
        val start = now - 8 * year
        val until = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(now - 3 * year))
        val id = database.eventsDao().insert(
            Event(
                uid = "uid-ended", calendarId = calendarId, title = "Ended", startTs = start, endTs = start + hour,
                dtstamp = 0L, rrule = "FREQ=WEEKLY;UNTIL=$until", timezone = "UTC", syncStatus = SyncStatus.SYNCED,
            )
        )
        val series = database.eventsDao().getById(id)!!
        generator.regenerateOccurrences(series)
        assertEquals("no rows after saving", 0, database.occurrencesDao().getForEvent(series.id).size)

        generator.ensureCovered(now, now + 7 * day)
        val far = now - 6 * year
        generator.ensureCovered(far, far + 7 * day)
        // Between the two reads and past what the far read filled.
        val between = far + 300 * day
        generator.ensureCovered(between, between + 7 * day)

        val rows = database.occurrencesDao().getForEvent(series.id).filter { it.startTs in between until between + 7 * day }
        assertEquals("one row that week", 1, rows.size)
    }

    private suspend fun rowsAfter(eventId: Long, ts: Long) =
        database.occurrencesDao().getForEvent(eventId).count { it.startTs >= ts }

    @Test
    fun `a second read inside a covered range runs no selection`(): Unit = runBlocking {
        weekly("A")
        generator.ensureCovered(now + 5 * year, now + 5 * year + 30 * day)
        calls.clear()

        generator.ensureCovered(now + 5 * year + 7 * day, now + 5 * year + 14 * day)
        generator.ensureCovered(now + 5 * year, now + 5 * year + 30 * day)

        assertEquals("no selection query: $calls", emptyList<String>(), calls)
    }

    @Test
    fun `a rewritten series is rechecked and extended again, the others are not`(): Unit = runBlocking {
        val a = weekly("A")
        val b = weekly("B")
        generator.ensureCovered(now + 5 * year, now + 5 * year + 7 * day)
        assertTrue(rowsAfter(a.id, now + 5 * year) > 0)
        val bRows = database.occurrencesDao().getForEvent(b.id).size

        // A sync rewrites A to its default window: its far rows go.
        generator.generateOccurrences(a, now, now + 720 * day)
        assertEquals(0, rowsAfter(a.id, now + 5 * year))
        calls.clear()

        generator.ensureCovered(now + 5 * year, now + 5 * year + 7 * day)

        assertTrue("A's far rows are back", rowsAfter(a.id, now + 5 * year) > 0)
        assertEquals("B unchanged", bRows, database.occurrencesDao().getForEvent(b.id).size)
        assertTrue("one pass ran: $calls", calls.contains("forward"))
        calls.clear()
        generator.ensureCovered(now + 5 * year, now + 5 * year + 7 * day)
        assertEquals("then the range is covered again: $calls", emptyList<String>(), calls)
    }

    @Test
    fun `a failed expansion returns without throwing`(): Unit = runBlocking {
        weekly("A")
        mockkObject(IcalDavRRuleEngine)
        try {
            every { IcalDavRRuleEngine.expandToTimestamps(any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("boom")
            generator.ensureCovered(now + 5 * year, now + 5 * year + 7 * day)
        } finally {
            unmockkObject(IcalDavRRuleEngine)
        }
    }

    @Test
    fun `a call inside a transaction is refused and writes nothing`(): Unit = runBlocking {
        val a = weekly("A")
        database.withTransaction { generator.ensureCovered(now + 5 * year, now + 5 * year + 7 * day) }
        assertEquals(0, rowsAfter(a.id, now + 5 * year))
        assertEquals(emptyList<String>(), calls.filter { it == "forward" })
    }

    @Test
    fun `a pass writes occurrence rows only`(): Unit = runBlocking {
        weekly("A")
        generator.ensureCovered(now + 5 * year, now + 5 * year + 7 * day)
        assertEquals("no pending operation", 0, database.pendingOperationsDao().getAll().size)
        val reminderRows = database.query("SELECT COUNT(*) FROM scheduled_reminders", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals("no reminder row", 0, reminderRows)
    }

    @Test
    fun `the forward-only horizon does not write rows before now`(): Unit = runBlocking {
        val id = database.eventsDao().insert(
            Event(
                uid = "uid-old", calendarId = calendarId, title = "Old", startTs = now - 5 * year,
                endTs = now - 5 * year + hour, dtstamp = 0L, rrule = "FREQ=WEEKLY", timezone = "UTC",
                syncStatus = SyncStatus.SYNCED,
            )
        )
        val old = database.eventsDao().getById(id)!!
        generator.generateOccurrences(old, now - 30 * day, now + 30 * day)

        generator.ensureCovered(now, now + OccurrenceGenerator.EXPANSION_WINDOW_MS, backward = false)

        val rows = database.occurrencesDao().getForEvent(old.id)
        assertTrue("nothing before the rows it had", rows.none { it.startTs < now - 30 * day })
        assertTrue("extended ahead", rows.any { it.startTs > now + 700 * day })
    }

    @Test
    fun `after the horizon pass the Insights and share-availability query returns a stale series this week`(): Unit = runBlocking {
        // Weekly since 3 years ago, stored rows ending a month ago.
        val id = database.eventsDao().insert(
            Event(
                uid = "uid-stale", calendarId = calendarId, title = "Stale", startTs = now - 3 * year,
                endTs = now - 3 * year + hour, dtstamp = 0L, rrule = "FREQ=WEEKLY", timezone = "UTC",
                syncStatus = SyncStatus.SYNCED,
            )
        )
        generator.generateOccurrences(database.eventsDao().getById(id)!!, now - 3 * year - 1000, now - 30 * day)
        suspend fun week() = database.occurrencesDao().getOccurrencesWithEventsForInsights(now, now + 7 * day).filter { it.event.id == id }
        assertTrue("nothing this week before the pass", week().isEmpty())

        generator.ensureCovered(now, now + OccurrenceGenerator.EXPANSION_WINDOW_MS, backward = false)

        assertEquals("one this week after it", 1, week().size)
    }

    @Test
    fun `a batch that fails rolls back its rows and the next read extends them again`(): Unit = runBlocking {
        val a = weekly("A")
        val b = weekly("B")
        var failOn: Long? = b.id
        onInsertAll = { rows -> if (rows.firstOrNull()?.eventId == failOn) throw IllegalStateException("disk full") }

        generator.ensureCovered(now + 5 * year, now + 5 * year + 7 * day)
        assertEquals("A's rows rolled back with the batch", 0, rowsAfter(a.id, now + 5 * year))

        failOn = null
        generator.ensureCovered(now + 5 * year, now + 5 * year + 7 * day)
        assertTrue("A extended on the next read", rowsAfter(a.id, now + 5 * year) > 0)
        assertTrue("B too", rowsAfter(b.id, now + 5 * year) > 0)
    }

    @Test
    fun `a bounded series past the engine's limit keeps extending to the range`(): Unit = runBlocking {
        // Hourly until 3 years ahead: more than 10,000 instances in the band.
        val until = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(now + 3 * year))
        val id = database.eventsDao().insert(
            Event(
                uid = "uid-hourly", calendarId = calendarId, title = "Hourly", startTs = now + hour,
                endTs = now + hour + 60_000, dtstamp = 0L, rrule = "FREQ=HOURLY;UNTIL=$until", timezone = "UTC",
                syncStatus = SyncStatus.SYNCED,
            )
        )
        generator.generateOccurrences(database.eventsDao().getById(id)!!, now, now + 30 * day)
        // A later process: its generator starts with no memory of that write.
        val later = OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(),
            TestDataStoreFactory.createWithSyncLookback(KashCalDataStore.DEFAULT_SYNC_PAST_DAYS),
        )

        later.ensureCovered(now, now + OccurrenceGenerator.EXPANSION_WINDOW_MS, backward = false)
        later.ensureCovered(now + 600 * day, now + 601 * day)

        assertEquals("24 hourly rows on day 600", 24, database.occurrencesDao().getForEvent(id).count { it.startTs in (now + 600 * day) until (now + 601 * day) })
    }

    @Test
    fun `a regeneration during a pass leaves the range to be checked again`(): Unit = runBlocking {
        val a = weekly("A")
        val b = weekly("B")
        var once = true
        // While the pass writes A's rows, a sync rewrites B to its default window.
        onInsertAll = { rows ->
            if (once && rows.firstOrNull()?.eventId == a.id) {
                once = false
                generator.generateOccurrences(database.eventsDao().getById(b.id)!!, now, now + 720 * day)
            }
        }
        generator.ensureCovered(now + 5 * year, now + 5 * year + 7 * day)
        onInsertAll = null
        calls.clear()

        generator.ensureCovered(now + 5 * year, now + 5 * year + 7 * day)

        assertTrue("the next read ran a pass: $calls", calls.contains("forward"))
        assertTrue("and B has its far rows", rowsAfter(b.id, now + 5 * year) > 0)
    }

    @Test
    fun `a far date-range search finds a series once its range is covered`(): Unit = runBlocking {
        val a = weekly("Standup")
        val reader = org.onekash.kashcal.domain.reader.EventReader(database)
        val from = now + 5 * year
        val to = from + 14 * day
        suspend fun inRange() = reader.observeSearchMatches(SearchQuery.of("Standup"), now, 0, from..to).first()
            .single { it.event.id == a.id }.inRange
        assertNull("not found before", inRange())

        generator.ensureCovered(from, to)

        assertNotNull("found after", inRange())
    }

    // ---- ensureCoveredFor: the series a search matched, from their DTSTART or forward only ----

    /** A weekly series for 5 years that ended 3 years ago, saved with no rows (see above). */
    private suspend fun endedSeries(title: String): Event {
        val start = now - 8 * year
        val id = database.eventsDao().insert(
            Event(
                uid = "uid-$title", calendarId = calendarId, title = title, startTs = start, endTs = start + hour,
                dtstamp = 0L, rrule = RruleBuilder.withUntil("FREQ=WEEKLY", now - 3 * year), timezone = "UTC",
                syncStatus = SyncStatus.SYNCED,
            )
        )
        val series = database.eventsDao().getById(id)!!
        generator.regenerateOccurrences(series)
        return series
    }

    @Test
    fun `covering a matched series fills it from its DTSTART to the end date`(): Unit = runBlocking {
        val ended = endedSeries("Ended")
        val ongoing = weekly("Ongoing")
        assertEquals(0, database.occurrencesDao().getForEvent(ended.id).size)

        assertEquals(2, generator.ensureCoveredFor(listOf(ended.id, ongoing.id), now + 2 * year))

        val endedRows = database.occurrencesDao().getForEvent(ended.id)
        assertEquals(ended.startTs, endedRows.first().startTs)
        assertTrue("about five years of weeks: ${endedRows.size}", endedRows.size in 250..262)
        assertTrue("ongoing reaches the end date", rowsAfter(ongoing.id, now + 2 * year - 8 * day) > 0)
    }

    /**
     * Saves a series started 5 years ago whose stored rows ran from [rowsFrom] to [rowsTo], both
     * in the past.
     */
    private suspend fun seriesWithPastRows(
        title: String,
        rrule: String = "FREQ=WEEKLY",
        rowsFrom: Long = now - year,
        rowsTo: Long = now - 30 * day,
    ): Event {
        val start = now - 5 * year
        val id = database.eventsDao().insert(
            Event(
                uid = "uid-$title", calendarId = calendarId, title = title, startTs = start, endTs = start + hour,
                dtstamp = 0L, rrule = rrule, timezone = "UTC", syncStatus = SyncStatus.SYNCED,
            )
        )
        val series = database.eventsDao().getById(id)!!
        generator.generateOccurrences(series, rowsFrom, rowsTo)
        return series
    }

    @Test
    fun `a forward-only cover adds rows only after the latest stored row`(): Unit = runBlocking {
        val series = seriesWithPastRows("Choir")
        val before = database.occurrencesDao().getForEvent(series.id)
        val earliest = before.minOf { it.startTs }
        val latest = before.maxOf { it.startTs }

        assertEquals(1, generator.ensureCoveredFor(emptyList(), now + 2 * year, forwardOnly = listOf(series.id)))

        val after = database.occurrencesDao().getForEvent(series.id)
        assertEquals("nothing written before the stored rows", earliest, after.minOf { it.startTs })
        assertTrue("rows after the latest stored one", after.any { it.startTs > latest })
        assertTrue("reaches the end date", rowsAfter(series.id, now + 2 * year - 8 * day) > 0)
    }

    @Test
    fun `a forward-only cover does not stop a later fill back to DTSTART`(): Unit = runBlocking {
        val series = seriesWithPastRows("Choir")
        generator.ensureCoveredFor(emptyList(), now + 2 * year, forwardOnly = listOf(series.id))

        assertEquals(1, generator.ensureCoveredFor(listOf(series.id), now + 2 * year))

        assertEquals(series.startTs, database.occurrencesDao().getForEvent(series.id).minOf { it.startTs })
    }

    @Test
    fun `a delete of this and future from the earliest stored row leaves the rest to fill back`(): Unit = runBlocking {
        val series = seriesWithPastRows("Choir")
        generator.ensureCoveredFor(emptyList(), now + 2 * year, forwardOnly = listOf(series.id))
        val earliest = database.occurrencesDao().getForEvent(series.id).minOf { it.startTs }
        EventWriter(database, generator).deleteThisAndFuture(series.id, earliest, isLocal = true)
        assertEquals("the delete removed every stored row", 0, database.occurrencesDao().getForEvent(series.id).size)

        generator.ensureCoveredFor(listOf(series.id), now + 2 * year)

        val rows = database.occurrencesDao().getForEvent(series.id)
        assertTrue("rows refilled from DTSTART: ${rows.size}", rows.isNotEmpty())
        assertEquals(series.startTs, rows.minOf { it.startTs })
        assertTrue("none at or after the delete", rows.all { it.startTs < earliest })
    }

    @Test
    fun `a capped forward-only cover is not repeated`(): Unit = runBlocking {
        // Every minute for two years is far past the row limit, so the step stops early.
        val series = seriesWithPastRows("Ticker", rrule = "FREQ=MINUTELY", rowsFrom = now - 2 * day, rowsTo = now - day)
        generator.ensureCoveredFor(emptyList(), now + 2 * year, forwardOnly = listOf(series.id))
        val rows = database.occurrencesDao().getForEvent(series.id).size
        var inserts = 0
        onInsertAll = { inserts++ }

        assertEquals(0, generator.ensureCoveredFor(emptyList(), now + 2 * year, forwardOnly = listOf(series.id)))

        assertEquals(0, inserts)
        assertEquals(rows, database.occurrencesDao().getForEvent(series.id).size)
    }

    @Test
    fun `covering the same series to the same date again does no work`(): Unit = runBlocking {
        val ended = endedSeries("Ended")
        generator.ensureCoveredFor(listOf(ended.id), now + 2 * year)
        val rows = database.occurrencesDao().getForEvent(ended.id).size
        var inserts = 0
        onInsertAll = { inserts++ }

        assertEquals(0, generator.ensureCoveredFor(listOf(ended.id), now + 2 * year))
        assertEquals(0, inserts)
        assertEquals(rows, database.occurrencesDao().getForEvent(ended.id).size)
    }

    @Test
    fun `covering matched series leaves every other series alone`(): Unit = runBlocking {
        val matched = endedSeries("Matched")
        val dailyStart = now - 10 * year
        val dailyId = database.eventsDao().insert(
            Event(
                uid = "uid-daily", calendarId = calendarId, title = "Daily", startTs = dailyStart,
                endTs = dailyStart + hour, dtstamp = 0L, rrule = "FREQ=DAILY", timezone = "UTC",
                syncStatus = SyncStatus.SYNCED,
            )
        )
        generator.generateOccurrences(database.eventsDao().getById(dailyId)!!, now - day, now + 30 * day)
        val dailyRows = database.occurrencesDao().getForEvent(dailyId).size

        generator.ensureCoveredFor(listOf(matched.id), now + 2 * year)

        assertEquals(dailyRows, database.occurrencesDao().getForEvent(dailyId).size)
        assertTrue("the matched series gained rows", database.occurrencesDao().getForEvent(matched.id).isNotEmpty())
    }

    @Test
    fun `covering a one-off or nothing writes no rows`(): Unit = runBlocking {
        val oneOff = database.eventsDao().insert(
            Event(uid = "uid-one", calendarId = calendarId, title = "One", startTs = now, endTs = now + hour, dtstamp = 0L)
        )
        var inserts = 0
        onInsertAll = { inserts++ }

        generator.ensureCoveredFor(emptyList(), now + 2 * year)
        generator.ensureCoveredFor(listOf(oneOff), now + 2 * year)

        assertEquals(0, inserts)
    }

    @Test
    fun `covering matched series records no range for ensureCovered`(): Unit = runBlocking {
        val ended = endedSeries("Ended")
        generator.ensureCoveredFor(listOf(ended.id), now + 2 * year)
        calls.clear()

        generator.ensureCovered(now, now + 7 * day)

        assertTrue("a later read still runs its own pass: $calls", calls.contains("forward"))
    }

    @Test
    fun `a series created on a far date inside a covered range shows on the next read`(): Unit = runBlocking {
        weekly("A")
        val from = now + 6 * year
        val to = from + 14 * day
        generator.ensureCovered(from, to)

        // Created there, as Quick Add does: its first window ends before its DTSTART, so no rows.
        val id = database.eventsDao().insert(
            Event(
                uid = "uid-far", calendarId = calendarId, title = "Far", startTs = from + day,
                endTs = from + day + hour, dtstamp = 0L, rrule = "FREQ=WEEKLY", timezone = "UTC",
                syncStatus = SyncStatus.SYNCED,
            )
        )
        generator.generateOccurrences(database.eventsDao().getById(id)!!, from + day - 1000, now + 730 * day)
        assertEquals(0, database.occurrencesDao().getForEvent(id).size)

        generator.ensureCovered(from, to)

        assertTrue("its rows in the range", rowsAfter(id, from) > 0)
    }
}
