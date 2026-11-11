package org.onekash.kashcal.domain.generator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account

import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Extending a repeating series keeps RFC 5545's override rules (an overridden instance,
 * identified by RECURRENCE-ID, replaces its original one) along the steps a user takes:
 * browse far ahead, move one occurrence, change the series' times (which rewrites the rows
 * to the default window and links the moved occurrence back), browse again. Runs over the
 * real [EventWriter] and [OccurrenceGenerator] and an in-memory database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class OccurrenceExtensionOverrideTest {

    private lateinit var database: KashCalDatabase
    private lateinit var generator: OccurrenceGenerator
    private lateinit var writer: EventWriter
    private var calendarId = 0L

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val week = 7 * day
    private val year = 365 * day
    private val now = System.currentTimeMillis()

    /** The default window's end, as regenerateOccurrences writes it (24 x 30 days). */
    private val windowEnd = now + 720 * day

    @Before
    fun setUp(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // The app's default lookback, so a rewrite keeps one year back.
        generator = OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(),
            TestDataStoreFactory.createWithSyncLookback(KashCalDataStore.DEFAULT_SYNC_PAST_DAYS),
        )
        writer = EventWriter(database, generator)
        val accountId = database.accountsDao().insert(Account(provider = AccountProvider.LOCAL, email = "local@example.test"))
        calendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = "local://cal", displayName = "Local", color = 0xFF2196F3.toInt())
        )
        Unit
    }

    @After
    fun tearDown() = database.close()

    private suspend fun createSeries(rrule: String, startTs: Long): Event = writer.createEvent(
        Event(
            uid = "",
            calendarId = calendarId,
            title = "Series",
            startTs = startTs,
            endTs = startTs + hour,
            dtstamp = now,
            rrule = rrule,
            timezone = "UTC",
            syncStatus = SyncStatus.SYNCED,
        ),
        isLocal = true,
    )

    /** Reads the week ending at [targetMs] as a view does ([OccurrenceGenerator.ensureCovered]). */
    private suspend fun browseForwardTo(targetMs: Long) = generator.ensureCovered(targetMs - week, targetMs)

    /** Reads the week starting at [targetMs], as a view does. */
    private suspend fun browseBackTo(targetMs: Long) = generator.ensureCovered(targetMs, targetMs + week)

    /** Extends each series once to [targetMs], with no coverage pass, to count expansions. */
    private suspend fun extendEachTo(targetMs: Long) {
        for (id in database.occurrencesDao().getRecurringEventsNeedingExtension(targetMs)) {
            generator.extendOccurrences(database.eventsDao().getById(id)!!, targetMs)
        }
    }

    /** Moves the occurrence at [originalTs] to start at [newStartTs]; returns the exception. */
    private suspend fun move(master: Event, originalTs: Long, newStartTs: Long): Event =
        writer.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = originalTs,
            modifiedEvent = master.copy(id = 0, title = "Moved", startTs = newStartTs, endTs = newStartTs + hour, rrule = null),
            isLocal = true,
        )

    /** Changes the series' end time, which rewrites its rows to the default window. */
    private suspend fun changeSeriesTimes(masterId: Long) {
        val master = database.eventsDao().getById(masterId)!!
        writer.updateEvent(master.copy(endTs = master.endTs + 15 * 60_000L), isLocal = true)
    }

    private suspend fun rows(masterId: Long): List<Occurrence> =
        database.occurrencesDao().getForEvent(masterId).sortedBy { it.startTs }

    private suspend fun ruleRowsIn(masterId: Long, fromMs: Long, toMs: Long) =
        rows(masterId).filter { it.exceptionEventId == null && it.startTs in fromMs until toMs }

    /** The weekly series' instants in [fromMs, toMs), from the rule alone, in whole seconds. */
    private fun ruleInstants(series: Event, fromMs: Long, toMs: Long): List<Long> =
        generateSequence(series.startTs / 1000 * 1000) { it + week }.dropWhile { it < fromMs }.takeWhile { it < toMs }.toList()

    @Test
    fun `browsing past an override moved beyond the window leaves no gap`(): Unit = runBlocking {
        val series = createSeries("FREQ=WEEKLY", now + day)
        browseForwardTo(now + 4 * year)
        val original = ruleInstants(series, now + 4 * year - 3 * week, now + 4 * year).first()
        val exception = move(series, original, original + 2 * hour)
        changeSeriesTimes(series.id)

        // The rewrite kept the moved occurrence as a linked row past the window, and no rule
        // rows past the window: the state browsing starts from.
        val linked = rows(series.id).single { it.exceptionEventId == exception.id }
        assertEquals(original + 2 * hour, linked.startTs)
        assertTrue("no rule row past the window yet", ruleRowsIn(series.id, windowEnd + week, now + 3 * year).isEmpty())

        browseForwardTo(now + 3 * year)

        val expected = ruleInstants(series, windowEnd + week, now + 3 * year)
        val shown = ruleRowsIn(series.id, windowEnd + week, now + 3 * year).map { it.startTs }
        assertEquals("weekly rows between the window's end and 3 years ahead", expected, shown)
    }

    @Test
    fun `browsing past an override's original time does not show the original again`(): Unit = runBlocking {
        val series = createSeries("FREQ=WEEKLY", now + day)
        browseForwardTo(now + 4 * year)
        val original = ruleInstants(series, now + 4 * year - 3 * week, now + 4 * year).first()
        // Moved back into the window, onto a day the rule doesn't use.
        val movedTo = ruleInstants(series, now + year, now + year + week).first() + day
        val exception = move(series, original, movedTo)
        changeSeriesTimes(series.id)
        assertNotNull("the moved occurrence is linked", rows(series.id).singleOrNull { it.exceptionEventId == exception.id })

        browseForwardTo(now + 5 * year)

        val atOriginal = rows(series.id).filter { kotlin.math.abs(it.startTs - original) < 60_000 }
        assertTrue("no row at the overridden RECURRENCE-ID time: $atOriginal", atOriginal.isEmpty())
        assertEquals(1, rows(series.id).count { it.exceptionEventId == exception.id })
    }

    @Test
    fun `browsing back past an override moved before the window leaves no gap`(): Unit = runBlocking {
        // A series that began 5 years ago; the rewrite keeps one year back (the default lookback).
        val series = createSeries("FREQ=WEEKLY", now - 5 * year)
        val original = ruleInstants(series, now - 4 * year, now - 4 * year + 3 * week).first()
        val exception = move(series, original, original - 2 * hour)
        changeSeriesTimes(series.id)

        val linked = rows(series.id).single { it.exceptionEventId == exception.id }
        assertEquals(original - 2 * hour, linked.startTs)
        val lookbackStart = now - 365 * day
        assertTrue("no rule row before the lookback yet", ruleRowsIn(series.id, now - 3 * year, lookbackStart - week).isEmpty())

        browseBackTo(now - 3 * year)

        val expected = ruleInstants(series, now - 3 * year + week, lookbackStart - week)
        val shown = ruleRowsIn(series.id, now - 3 * year + week, lookbackStart - week).map { it.startTs }
        assertEquals("weekly rows between 3 years back and the lookback", expected, shown)
    }

    @Test
    fun `an override moved onto another occurrence's time keeps its link when browsing across it`(): Unit = runBlocking {
        val series = createSeries("FREQ=DAILY", now + day)
        browseForwardTo(now + 4 * year)
        val original = generateSequence(series.startTs / 1000 * 1000) { it + day }.first { it >= now + 4 * year - 10 * day }
        val exception = move(series, original, original + day)
        changeSeriesTimes(series.id)

        browseForwardTo(now + 5 * year)

        val atMovedTime = rows(series.id).filter { it.startTs == original + day }
        assertEquals("one row at the moved time", 1, atMovedTime.size)
        assertEquals("it is the moved occurrence", exception.id, atMovedTime.single().exceptionEventId)
    }

    /** Runs [passes] extension passes to [targetMs] and returns the engine calls they made. */
    private suspend fun engineCallsFor(passes: Int, targetMs: Long): Int {
        var calls = 0
        io.mockk.mockkObject(IcalDavRRuleEngine)
        try {
            io.mockk.every {
                IcalDavRRuleEngine.expandToTimestamps(any(), any(), any(), any(), any(), any(), any(), any())
            } answers { calls++; callOriginal() }
            repeat(passes) { extendEachTo(targetMs) }
        } finally {
            io.mockk.unmockkObject(IcalDavRRuleEngine)
        }
        return calls
    }

    @Test
    fun `a series ended by UNTIL is expanded once across repeated passes`(): Unit = runBlocking {
        val until = java.time.Instant.ofEpochMilli(now + 30 * day).toString().replace("-", "").replace(":", "").substringBefore(".") + "Z"
        createSeries("FREQ=WEEKLY;UNTIL=$until", now + day)
        assertEquals(1, engineCallsFor(passes = 3, targetMs = now + 6 * year))
    }

    @Test
    fun `a series ended by COUNT is expanded once across repeated passes`(): Unit = runBlocking {
        createSeries("FREQ=WEEKLY;COUNT=5", now + day)
        assertEquals(1, engineCallsFor(passes = 3, targetMs = now + 6 * year))
    }

    @Test
    fun `an hourly series is filled to the target in one pass and not expanded again`(): Unit = runBlocking {
        // Hourly from 13 months ago: more instances to the target than one engine call returns.
        val series = createSeries("FREQ=HOURLY", now - 395 * day)
        val target = now + 2 * year
        extendEachTo(target)

        val starts = ruleRowsIn(series.id, now, target).map { it.startTs }
        assertTrue("rows from now", starts.first() < now + hour)
        // The last instance is an hour before the exclusive target, floored to its second.
        assertTrue("rows to the target", starts.last() > target - hour - 1000)
        assertTrue("one row an hour", starts.zipWithNext().all { (a, b) -> b - a == hour })
        assertEquals("later passes don't expand it", 0, engineCallsFor(passes = 2, targetMs = target))
    }

    @Test
    fun `a rewrite of a series lets the next pass expand it again`(): Unit = runBlocking {
        val series = createSeries("FREQ=WEEKLY;COUNT=5", now + day)
        assertEquals(1, engineCallsFor(passes = 2, targetMs = now + 6 * year))
        changeSeriesTimes(series.id)
        assertEquals(1, engineCallsFor(passes = 2, targetMs = now + 6 * year))
    }

    @Test
    fun `deleting a moved occurrence past the window leaves no gap when browsing`(): Unit = runBlocking {
        val series = createSeries("FREQ=WEEKLY", now + day)
        browseForwardTo(now + 4 * year)
        val original = ruleInstants(series, now + 4 * year - 3 * week, now + 4 * year).first()
        move(series, original, original + 2 * hour)
        changeSeriesTimes(series.id)
        // Deleting the edited occurrence cancels its row and removes the exception.
        writer.deleteSingleOccurrence(series.id, original, isLocal = true)

        browseForwardTo(now + 3 * year)

        val expected = ruleInstants(series, windowEnd + week, now + 3 * year - week)
        val shown = ruleRowsIn(series.id, windowEnd + week, now + 3 * year - week).map { it.startTs }
        assertEquals("weekly rows between the window's end and 3 years ahead", expected, shown)
    }
}
