package org.onekash.kashcal.domain.generator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.testutil.PropertyRuns
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Extending a series in steps, forward and back, stores exactly the rows one full
 * [OccurrenceGenerator.generateOccurrences] over the same range stores (RFC 5545: DTSTART +
 * RRULE + RDATE - EXDATE, each override replacing the instance at its RECURRENCE-ID), for
 * generated daily, weekly and monthly series, open-ended or ended by UNTIL or COUNT, with
 * EXDATEs, RDATEs, and occurrences moved by a few hours, moved onto the next instance's time, or
 * cancelled. For about half the series without a COUNT the range sits 25 to 45 years after
 * DTSTART, where an open-ended series still has its instances. Ranges stay under the engine's
 * per-call limit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class OccurrenceExtensionPropertiesTest {

    private lateinit var database: KashCalDatabase
    private lateinit var generator: OccurrenceGenerator
    private lateinit var writer: EventWriter
    private var calendarId = 0L

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = System.currentTimeMillis() / day * day

    @Before
    fun setUp(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        generator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())
        writer = EventWriter(database, generator)
        val accountId = database.accountsDao().insert(Account(provider = AccountProvider.LOCAL, email = "local@example.test"))
        calendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = "local://cal", displayName = "Local", color = 0xFF2196F3.toInt())
        )
        Unit
    }

    @After
    fun tearDown() = database.close()

    private enum class Bound { NONE, COUNT, UNTIL }
    private enum class Move { HOURS, ONTO_NEXT, CANCELLED }

    private data class Case(
        val freq: String,
        val startDay: Int,
        val farYears: Int,
        val bound: Bound,
        val boundSize: Int,
        val rangeDays: Int,
        val windowStartPct: Int,
        val windowEndPct: Int,
        val exdatePicks: List<Int>,
        val rdateDays: List<Int>,
        val moves: List<Pair<Int, Move>>,
        val steps: List<Int>,
    )

    private val cases = arbitrary {
        val freq = Arb.element("DAILY", "WEEKLY", "MONTHLY").bind()
        val bound = Arb.element(Bound.entries).bind()
        Case(
            freq = freq,
            startDay = Arb.int(-400..400).bind(),
            // A COUNT ends the series long before a far range.
            farYears = Arb.int(0..45).bind().let { if (it < 25 || bound == Bound.COUNT) 0 else it },
            bound = bound,
            boundSize = Arb.int(3..200).bind(),
            rangeDays = Arb.int(60..(if (freq == "DAILY") 1500 else 4000)).bind(),
            windowStartPct = Arb.int(0..60).bind(),
            windowEndPct = Arb.int(61..100).bind(),
            exdatePicks = Arb.list(Arb.int(0..999), 0..3).bind(),
            rdateDays = Arb.list(Arb.int(1..999), 0..2).bind(),
            moves = Arb.list(Arb.int(0..999), 0..3).bind().map { it to Arb.element(Move.entries).bind() },
            steps = Arb.list(Arb.int(1..99), 1..5).bind(),
        )
    }

    private fun stepSizeFor(freq: String) = when (freq) {
        "DAILY" -> day
        "WEEKLY" -> 7 * day
        else -> 31 * day
    }

    private data class Row(val start: Long, val end: Long, val exceptionId: Long?, val cancelled: Boolean)

    private suspend fun rows(id: Long) = database.occurrencesDao().getForEvent(id)
        .map { Row(it.startTs, it.endTs, it.exceptionEventId, it.isCancelled) }.toSet()

    @Test
    fun `stepwise extension stores the rows of one full expansion`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(60), cases) { c ->
            val dtstart = now + c.startDay * day + 9 * hour
            val rangeStart = dtstart + c.farYears * 365 * day - 10 * day
            val rangeEnd = rangeStart + 10 * day + c.rangeDays * day
            val untilMs = rangeStart + 10 * day + c.boundSize * stepSizeFor(c.freq)
            val rrule = when (c.bound) {
                Bound.NONE -> "FREQ=${c.freq}"
                Bound.COUNT -> "FREQ=${c.freq};COUNT=${c.boundSize}"
                Bound.UNTIL -> "FREQ=${c.freq};UNTIL=" +
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                        .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(untilMs))
            }
            val instants = IcalDavRRuleEngine.expandToTimestamps(rrule, dtstart, rangeStart, rangeEnd, "UTC", false, null, null)
            if (c.bound == Bound.NONE) assertTrue("an open-ended series has instances in $c", instants.isNotEmpty())
            if (instants.size < 4) return@checkAll

            // EXDATEs and moved occurrences come from the middle of the range, apart.
            val middle = instants.subList(1, instants.size - 1)
            val exdates = c.exdatePicks.map { middle[it % middle.size] }.distinct()
            val movable = middle.filter { it !in exdates }.dropLast(1)
            val moves = c.moves.map { (pick, how) -> movable.getOrNull(pick % maxOf(movable.size, 1)) to how }
                .filter { it.first != null }.distinctBy { it.first }
            val rdates = c.rdateDays.map { rangeStart + it * (rangeEnd - rangeStart) / 1000 / day * day + 15 * hour }
                // RFC 5545: DTSTART is the set's first instance, so no RDATE before it.
                .filter { it in dtstart until rangeEnd }.distinct()

            val master = writer.createEvent(
                Event(
                    uid = "",
                    calendarId = calendarId,
                    title = "Series",
                    startTs = dtstart,
                    endTs = dtstart + hour,
                    dtstamp = now,
                    rrule = rrule,
                    timezone = "UTC",
                    exdate = exdates.takeIf { it.isNotEmpty() }?.joinToString(","),
                    rdate = rdates.takeIf { it.isNotEmpty() }?.joinToString(","),
                    syncStatus = SyncStatus.SYNCED,
                ),
                isLocal = true,
            )

            // Start from a window inside the range holding at least one instance (a series with
            // no rows at all is left to the coverage pass), then move occurrences. createEvent's
            // own rows are cleared first: an empty window expansion would keep them.
            val anchor = middle.firstOrNull { it !in exdates } ?: return@checkAll
            val windowStart = minOf(rangeStart + (rangeEnd - rangeStart) * c.windowStartPct / 100, anchor)
            val windowEnd = maxOf(rangeStart + (rangeEnd - rangeStart) * c.windowEndPct / 100, anchor + 1)
            database.occurrencesDao().deleteForEvent(master.id)
            generator.generateOccurrences(master, windowStart, windowEnd)
            val exceptionIds = mutableMapOf<Long, Long>()
            // Each moved occurrence gets its own time: two on one start can't both be stored
            // (one row per series and start), so a full regeneration is no reference there.
            val usedTargets = mutableSetOf<Long>()
            for ((original, how) in moves) {
                val o = original!!
                val next = instants.firstOrNull {
                    it > o && it !in exdates && it !in moves.map { m -> m.first } && it !in usedTargets
                }
                val newStart = if (how == Move.ONTO_NEXT && next != null) next else o + 2 * hour
                usedTargets += newStart
                val ex = writer.editSingleOccurrence(
                    masterEventId = master.id,
                    occurrenceTimeMs = o,
                    modifiedEvent = master.copy(id = 0, title = "Moved", startTs = newStart, endTs = newStart + hour, rrule = null, exdate = null, rdate = null),
                    isLocal = true,
                )
                exceptionIds[o] = ex.id
                // A cancelled override, as a pulled STATUS:CANCELLED occurrence is stored.
                if (how == Move.CANCELLED) generator.cancelOccurrenceByException(ex.id)
            }

            // Extend in steps to the range's ends.
            val forwardTargets = c.steps.sorted().map { windowEnd + (rangeEnd - windowEnd) * it / 100 } + rangeEnd
            val backTargets = c.steps.sorted().map { windowStart - (windowStart - rangeStart) * it / 100 } + rangeStart
            for ((f, b) in forwardTargets.zip(backTargets)) {
                generator.extendOccurrences(master, f)
                generator.extendPastOccurrences(master, b)
            }
            val stepped = rows(master.id)

            generator.generateOccurrences(database.eventsDao().getById(master.id)!!, rangeStart, rangeEnd)
            val full = rows(master.id)

            assertEquals("rows of $c", full.sortedBy { it.start }, stepped.sortedBy { it.start })
            for ((original, _) in moves) {
                val atOriginal = stepped.filter { it.exceptionId == null && kotlin.math.abs(it.start - original!!) < 60_000 }
                assertTrue("a moved occurrence's original time has no rule row: $c", atOriginal.isEmpty())
            }
        }
    }
}
