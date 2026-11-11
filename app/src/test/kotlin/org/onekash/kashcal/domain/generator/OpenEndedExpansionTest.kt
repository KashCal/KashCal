package org.onekash.kashcal.domain.generator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * An open-ended HOURLY or coarser series expands on any date however long ago it started:
 * through the engine for fixed UTC dates, and through the real writer and generator over Room
 * for this week and far back. Guards: weekly, monthly and yearly series from decades ago keep
 * their BY-part defaults from DTSTART, and open-ended SECONDLY and MINUTELY series end 10,000
 * instances after DTSTART.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class OpenEndedExpansionTest {

    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun utc(dt: LocalDateTime) = dt.toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun expand(rrule: String, dtstart: LocalDateTime, from: LocalDateTime, to: LocalDateTime, exdate: String? = null, rdate: String? = null) =
        IcalDavRRuleEngine.expandToTimestamps(rrule, utc(dtstart), utc(from), utc(to), "UTC", false, rdate, exdate)

    // ---- through the engine ----

    @Test
    fun `a daily series from 1998 has its occurrences in a week of November 2026`() {
        val dtstart = LocalDateTime.of(1998, 1, 5, 9, 0)
        val from = LocalDateTime.of(2026, 11, 8, 0, 0)
        val got = expand("FREQ=DAILY", dtstart, from, from.plusDays(7))
        assertEquals((0L until 7).map { utc(from.plusDays(it).withHour(9)) }, got)
    }

    @Test
    fun `an hourly series from two years before has its occurrences on a later day`() {
        val dtstart = LocalDateTime.of(2024, 11, 10, 0, 30)
        val from = LocalDateTime.of(2026, 11, 10, 0, 0)
        val got = expand("FREQ=HOURLY", dtstart, from, from.plusDays(1))
        assertEquals(24, got.size)
        assertEquals(utc(from.withMinute(30)), got.first())
    }

    // ---- guards: weekly, monthly and yearly series from decades ago ----

    @Test
    fun `guard - a three-weekly Monday and Wednesday series from 1990 in 2030`() {
        val dtstart = LocalDateTime.of(1990, 1, 1, 8, 0) // a Monday
        val from = LocalDateTime.of(2030, 3, 1, 0, 0)
        val to = from.plusMonths(3)
        val weeks = generateSequence(dtstart) { it.plusWeeks(3) }.takeWhile { it < to }
        val expected = weeks.flatMap { sequenceOf(it, it.plusDays(2)) }.filter { it >= from && it < to }.map { utc(it) }.toList()
        assertEquals(expected, expand("FREQ=WEEKLY;INTERVAL=3;BYDAY=MO,WE", dtstart, from, to))
    }

    @Test
    fun `guard - a monthly series on the 31st from 1990 in 2040`() {
        val dtstart = LocalDateTime.of(1990, 1, 31, 10, 0)
        val from = LocalDateTime.of(2040, 1, 1, 0, 0)
        val to = from.plusYears(1)
        // RFC 5545: a month without a 31st has no instance.
        val expected = (0L until 12).map { from.plusMonths(it) }.filter { it.toLocalDate().lengthOfMonth() == 31 }.map { utc(it.withDayOfMonth(31).withHour(10)) }
        assertEquals(expected, expand("FREQ=MONTHLY", dtstart, from, to))
    }

    @Test
    fun `guard - a yearly series on the Monday of week 20 from 1950 in 2040 to 2045`() {
        val dtstart = LocalDateTime.of(1950, 5, 15, 9, 0) // Monday of ISO week 20 of 1950
        val from = LocalDateTime.of(2040, 1, 1, 0, 0)
        val to = LocalDateTime.of(2045, 1, 1, 0, 0)
        val wf = java.time.temporal.WeekFields.ISO
        val expected = (2040..2044).map { y ->
            LocalDate.of(y, 6, 1).with(wf.weekBasedYear(), y.toLong()).with(wf.weekOfWeekBasedYear(), 20).with(java.time.DayOfWeek.MONDAY).atTime(9, 0)
        }.map { utc(it) }
        assertEquals(expected, expand("FREQ=YEARLY;BYWEEKNO=20;BYDAY=MO", dtstart, from, to))
    }

    // ---- guards: open-ended SECONDLY and MINUTELY series end 10,000 instances after DTSTART ----

    @Test
    fun `guard - an open-ended every-second series ends 10,000 seconds after DTSTART`() {
        val dtstart = LocalDateTime.of(2026, 1, 1, 0, 0)
        val lastHour = expand("FREQ=SECONDLY", dtstart, dtstart.plusHours(2), dtstart.plusHours(4))
        assertEquals(utc(dtstart.plusSeconds(9_999)), lastHour.last())
    }

    @Test
    fun `guard - an open-ended every-minute series ends 10,000 minutes after DTSTART`() {
        val dtstart = LocalDateTime.of(2026, 1, 1, 0, 0)
        val got = expand("FREQ=MINUTELY", dtstart, dtstart.plusDays(6), dtstart.plusDays(8))
        assertEquals(utc(dtstart.plusMinutes(9_999)), got.last())
    }

    @Test
    fun `guard - an every-second series from 1990 read in 2026 returns at once`() {
        var got: List<Long>? = null
        val worker = Thread {
            got = expand("FREQ=SECONDLY", LocalDateTime.of(1990, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 2, 0, 0))
        }
        worker.isDaemon = true
        worker.start()
        worker.join(30_000)
        assertTrue("the expansion finished", !worker.isAlive)
        assertEquals(emptyList<Long>(), got)
    }

    // ---- through the writer, generator and Room ----

    private lateinit var database: KashCalDatabase
    private lateinit var generator: OccurrenceGenerator
    private lateinit var writer: EventWriter
    private var calendarId = 0L
    private val now = System.currentTimeMillis()

    @Before
    fun setUp(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java).allowMainThreadQueries().build()
        generator = OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(),
            TestDataStoreFactory.createWithSyncLookback(KashCalDataStore.DEFAULT_SYNC_PAST_DAYS),
        )
        writer = EventWriter(database, generator)
        val accountId = database.accountsDao().insert(Account(provider = AccountProvider.LOCAL, email = "local@example.test"))
        calendarId = database.calendarsDao().insert(Calendar(accountId = accountId, caldavUrl = "local://cal", displayName = "Local", color = 1))
        Unit
    }

    @After
    fun tearDown() = database.close()

    private suspend fun createDaily(title: String, exdate: String? = null, rdate: String? = null): Event {
        val start = utc(LocalDateTime.of(1998, 1, 5, 9, 0))
        return writer.createEvent(
            Event(
                uid = "", calendarId = calendarId, title = title, startTs = start, endTs = start + hour,
                dtstamp = 0L, rrule = "FREQ=DAILY", timezone = "UTC", exdate = exdate, rdate = rdate,
                syncStatus = SyncStatus.SYNCED,
            ),
            isLocal = true,
        )
    }

    private suspend fun rowsIn(id: Long, from: Long, to: Long) =
        database.occurrencesDao().getForEvent(id).filter { it.startTs in from until to }.map { it.startTs }.sorted()

    @Test
    fun `a daily series from 1998 created today has rows this week once read`(): Unit = runBlocking {
        val series = createDaily("Vitamins")
        val weekStart = now / day * day
        generator.ensureCovered(weekStart, weekStart + 7 * day)
        assertEquals("one a day this week", 7, rowsIn(series.id, weekStart, weekStart + 7 * day).size)
    }

    @Test
    fun `an RDATE after the 10,000th daily instance doesn't leave the days before it empty`(): Unit = runBlocking {
        val exdate = utc(LocalDateTime.of(2010, 6, 1, 9, 0)).toString()
        val rdate = utc(LocalDate.ofEpochDay(now / day + 30).atTime(15, 0)).toString()
        val series = createDaily("Vitamins", exdate = exdate, rdate = rdate)
        val weekStart = now / day * day
        generator.ensureCovered(weekStart, weekStart + 7 * day)

        assertEquals("one a day this week", 7, rowsIn(series.id, weekStart, weekStart + 7 * day).size)
        val from = utc(LocalDateTime.of(2025, 5, 1, 0, 0))
        val days = rowsIn(series.id, from, weekStart).map { it / day }.distinct()
        assertEquals("every day from May 2025 to this week", (from / day until weekStart / day).toList(), days)
        assertTrue("the RDATE is there too", rowsIn(series.id, weekStart, weekStart + 40 * day).contains(rdate.toLong()))
    }

    private suspend fun createSeries(rrule: String, start: Long, exdate: String? = null): Event = writer.createEvent(
        Event(
            uid = "", calendarId = calendarId, title = "Series", startTs = start, endTs = start + hour,
            dtstamp = 0L, rrule = rrule, timezone = "UTC", exdate = exdate, syncStatus = SyncStatus.SYNCED,
        ),
        isLocal = true,
    )

    @Test
    fun `an EXDATE among one call's instances doesn't end a fill`(): Unit = runBlocking {
        val start = now / hour * hour + day
        val excluded = start + 5 * hour
        val series = createSeries("FREQ=HOURLY", start, exdate = excluded.toString())
        database.occurrencesDao().deleteForEvent(series.id)
        val target = start + 600 * day

        generator.extendOccurrences(series, target, floorMs = start)

        val expected = (0L until 600 * 24).map { start + it * hour }.filter { it != excluded }
        assertEquals(expected, rowsIn(series.id, start, target))
    }

    @Test
    fun `a fill of a series whose DTSTART has milliseconds ends`(): Unit = runBlocking {
        // Rows are whole seconds while the engine picks by the raw instant, 456 ms later.
        val start = now / hour * hour + day + 456
        val series = createSeries("FREQ=HOURLY", start)
        database.occurrencesDao().deleteForEvent(series.id)
        val target = start + 600 * day

        kotlinx.coroutines.withTimeout(60_000) { generator.extendOccurrences(series, target, floorMs = start) }

        val expected = (0L until 600 * 24).map { start - 456 + it * hour }
        assertEquals(expected, rowsIn(series.id, start - 456, target))
    }

    private val every4h = "FREQ=HOURLY;INTERVAL=4"
    private val since2000 = utc(LocalDateTime.of(2000, 1, 1, 0, 0))

    /** A series every 4 hours since 2000 whose rows run from 30 days ago to 30 days ahead. */
    private suspend fun every4hSeededNearToday(): Event {
        val series = createSeries(every4h, since2000)
        generator.generateOccurrences(series, now - 30 * day, now + 30 * day)
        return series
    }

    @Test
    fun `reading ten years back fills a series every 4 hours without a gap`(): Unit = runBlocking {
        val series = every4hSeededNearToday()
        val earliestBefore = rowsIn(series.id, Long.MIN_VALUE, Long.MAX_VALUE).first()
        val readFrom = now - 3650 * day

        generator.ensureCovered(readFrom, readFrom + 7 * day)

        val past = rowsIn(series.id, Long.MIN_VALUE, earliestBefore + 1)
        assertTrue("about 22,000 rows added: ${past.size}", past.size > 20_000)
        assertTrue("rows from before the read", past.first() <= readFrom)
        assertTrue("one row every 4 hours", past.zipWithNext().all { (a, b) -> b - a == 4 * hour })
    }

    @Test
    fun `reading 2001 adds nothing rather than a part with a gap`(): Unit = runBlocking {
        val series = every4hSeededNearToday()
        val before = rowsIn(series.id, Long.MIN_VALUE, Long.MAX_VALUE)
        val readFrom = utc(LocalDateTime.of(2001, 6, 1, 0, 0))

        generator.ensureCovered(readFrom, readFrom + 7 * day)

        // More than the row limit lies between 2001 and the rows, so the past fill adds none.
        val after = rowsIn(series.id, Long.MIN_VALUE, Long.MAX_VALUE)
        assertEquals("nothing before the earliest row", before.first(), after.first())
        assertTrue("rows stay continuous", after.zipWithNext().all { (a, b) -> b - a == 4 * hour })
    }
}
