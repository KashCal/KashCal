package org.onekash.kashcal.domain.reader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.CalendarProviderManager
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.rrule.RruleBuilder
import org.onekash.kashcal.domain.search.SearchOutcome
import org.onekash.kashcal.domain.search.SearchQuery
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The read every view, widget and print goes through returns a repeating series' occurrence
 * for any week, however its stored rows were left: rows from an old window (what an install
 * holds after the series' window passed), a week far past today's window, and a series
 * created on a far date. A search expands only the matched series whose stored rows lack a
 * date: back to DTSTART when the last or first occurrence is missing, so one that ended years
 * ago shows under Past, and forward only when the next one is. Real [EventReader],
 * [OccurrenceGenerator] and [EventWriter] over an in-memory database; no device calendars.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DisplayEventRepositoryCoverageTest {

    private lateinit var database: KashCalDatabase
    private lateinit var generator: OccurrenceGenerator
    private lateinit var writer: EventWriter
    private lateinit var display: DisplayEventRepository
    private var calendarId = 0L

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val year = 365 * day
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)

    @Before
    fun setUp(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        generator = OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(),
            TestDataStoreFactory.createWithSyncLookback(KashCalDataStore.DEFAULT_SYNC_PAST_DAYS),
        )
        writer = EventWriter(database, generator)
        val accountId = database.accountsDao().insert(Account(provider = AccountProvider.LOCAL, email = "local@example.test"))
        calendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = "local://cal", displayName = "Local", color = 1, isVisible = true)
        )
        val dataStore: KashCalDataStore = mockk {
            every { showDeclinedEvents } returns MutableStateFlow(true)
            every { hiddenDeviceCalendarIds } returns MutableStateFlow(emptySet())
            every { syncPastDays } returns MutableStateFlow(KashCalDataStore.DEFAULT_SYNC_PAST_DAYS)
            coEvery { getShowDeclinedEvents() } returns true
            coEvery { getDeviceCalendarsEnabled() } returns false
            coEvery { getEnabledDeviceCalendarIds() } returns emptySet()
        }
        val manager: CalendarProviderManager = mockk { every { changeSignal } returns MutableStateFlow(0) }
        display = DisplayEventRepository(
            EventReader(database), FakeCalendarProviderRepository(), manager, dataStore,
            database.attendeesDao(), database.accountsDao(), generator,
        )
        Unit
    }

    @After
    fun tearDown() = database.close()

    private fun ms(date: LocalDate, h: Int = 0) = date.atStartOfDay(zone).toInstant().toEpochMilli() + h * hour

    private fun series(title: String, startTs: Long) = Event(
        uid = "uid-$title", calendarId = calendarId, title = title,
        startTs = startTs, endTs = startTs + hour, dtstamp = 0L,
        rrule = "FREQ=WEEKLY", timezone = "UTC", syncStatus = SyncStatus.SYNCED,
    )

    /** A weekly series whose rows run from [rowsFrom] to [rowsTo], as an older install left it. */
    private suspend fun seriesWithRows(title: String, dtstart: Long, rowsFrom: Long, rowsTo: Long): Event {
        val id = database.eventsDao().insert(series(title, dtstart))
        val event = database.eventsDao().getById(id)!!
        generator.generateOccurrences(event, rowsFrom, rowsTo)
        return event
    }

    private fun titles(events: List<DisplayEvent>) = events.map { it.title }

    /** The week of [date], as the week grid reads it, waiting until [title] shows or 10 s pass. */
    private suspend fun weekShows(date: LocalDate, title: String): Boolean {
        val start = ms(date.minusDays(3))
        val end = ms(date.plusDays(4))
        return try {
            withTimeout(10_000) { display.getDisplayEventsForRange(start, end).first { title in titles(it) } }
            true
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            false
        }
    }

    @Test
    fun `this week shows a series whose stored rows ended a month ago`(): Unit = runBlocking {
        seriesWithRows("Old", ms(today.minusYears(3), 9), ms(today.minusYears(3)), ms(today.minusDays(30)))
        assertTrue("the week grid read", weekShows(today, "Old"))

        val dots = display.getDisplayEventsGroupedByDayOnce(
            DayPagerUtils.localDateToDayCode(today.minusDays(3)), DayPagerUtils.localDateToDayCode(today.plusDays(4)),
        )
        assertTrue("the month dots and widget read: $dots", dots.values.flatten().any { it.title == "Old" })
    }

    @Test
    fun `a week six years ahead shows a series created today`(): Unit = runBlocking {
        seriesWithRows("Weekly", ms(today, 9), ms(today), ms(today) + 720 * day)
        val far = today.plusYears(6)
        assertTrue("the week grid read", weekShows(far, "Weekly"))

        val month = display.getDisplayEventsForDateRange(
            DayPagerUtils.localDateToDayCode(far.minusDays(3)), DayPagerUtils.localDateToDayCode(far.plusDays(4)),
        )
        val shown = withTimeout(10_000) { month.first { m -> m.values.flatten().any { it.title == "Weekly" } } }
        assertTrue(shown.isNotEmpty())

        val print = display.getDisplayEventsForPrint(ms(far.minusDays(3)), ms(far.plusDays(4)), setOf(calendarId), emptySet())
        assertTrue("the print read: $print", titles(print).contains("Weekly"))
    }

    @Test
    fun `a repeating event created on a date six years ahead shows that week`(): Unit = runBlocking {
        val far = today.plusYears(6)
        writer.createEvent(series("Far", ms(far, 9)).copy(uid = ""), isLocal = true)
        assertTrue("the week grid read", weekShows(far.plusWeeks(1), "Far"))
    }

    @Test
    fun `an imported series six years ahead with one override shows its weekly occurrences`(): Unit = runBlocking {
        val far = today.plusYears(6)
        val master = series("Imported", ms(far, 9)).copy(uid = "import-1")
        val override = master.copy(
            title = "Imported moved", rrule = null,
            startTs = ms(far.plusWeeks(2), 11), endTs = ms(far.plusWeeks(2), 12),
            originalInstanceTime = ms(far.plusWeeks(2), 9),
        )
        writer.createImportedSeries(master, listOf(override), isLocal = true)
        assertTrue("a rule occurrence the week after", weekShows(far.plusWeeks(1), "Imported"))
        assertEquals(
            "the moved one shows once",
            1,
            titles(display.getDisplayEventsForRange(ms(far.plusWeeks(2).minusDays(3)), ms(far.plusWeeks(2).plusDays(4))).first())
                .count { it.startsWith("Imported") },
        )
    }

    // ---- search: only matched series lacking a date are expanded ----

    private suspend fun endedThreeYearsAgo(title: String): Event {
        val start = ms(today.minusYears(8), 9)
        val rrule = RruleBuilder.withUntil("FREQ=WEEKLY", ms(today.minusYears(3)))
        val id = database.eventsDao().insert(series(title, start).copy(rrule = rrule))
        val event = database.eventsDao().getById(id)!!
        generator.regenerateOccurrences(event)
        assertEquals("saved with no rows", 0, database.occurrencesDao().getForEvent(id).size)
        return event
    }

    private fun search(text: String) = display.observeSearch(SearchQuery.of(text), System.currentTimeMillis(), zone)

    @Test
    fun `a search lists a series that ended three years ago under Past at its last occurrence`(): Unit = runBlocking {
        val ended = endedThreeYearsAgo("Physio")

        // Before its rows land the series is dated by its own start; it moves once they do.
        val outcome = withTimeout(10_000) { search("physio").first { it.past.isNotEmpty() && it.past.single().displayTs > ended.startTs } }

        val lastRow = database.occurrencesDao().getForEvent(ended.id).maxOf { it.startTs }
        assertEquals(lastRow, outcome.past.single().displayTs)
        assertTrue(outcome.upcoming.isEmpty())
        assertEquals(ended.startTs, database.occurrencesDao().getForEvent(ended.id).minOf { it.startTs })
    }

    @Test
    fun `a search leaves unmatched series alone`(): Unit = runBlocking {
        val ended = endedThreeYearsAgo("Physio")
        val dailyId = database.eventsDao().insert(series("Daily walk", ms(today.minusYears(10), 7)).copy(rrule = "FREQ=DAILY"))
        generator.generateOccurrences(database.eventsDao().getById(dailyId)!!, ms(today), ms(today.plusDays(30)))
        val dailyRows = database.occurrencesDao().getForEvent(dailyId).size

        withTimeout(10_000) { search("physio").first { it.past.isNotEmpty() && it.past.single().displayTs > ended.startTs } }

        assertEquals(dailyRows, database.occurrencesDao().getForEvent(dailyId).size)
    }

    @Test
    fun `a search with no repeating match writes no rows`(): Unit = runBlocking {
        writer.createEvent(series("Dentist", ms(today.plusDays(2), 9)).copy(uid = "", rrule = null), isLocal = true)
        val dailyId = database.eventsDao().insert(series("Daily walk", ms(today.minusYears(10), 7)).copy(rrule = "FREQ=DAILY"))
        val rowsBefore = database.occurrencesDao().getForEvent(dailyId).size

        val outcome = searchAndSettle("dentist") { it.upcoming.isNotEmpty() }

        assertEquals(listOf("Dentist"), outcome.upcoming.map { it.displayEvent.title })
        assertEquals(rowsBefore, database.occurrencesDao().getForEvent(dailyId).size)
        assertNull(outcome.timing.coverageMs)
    }

    @Test
    fun `later emissions of the same search add no rows`(): Unit = runBlocking {
        val ended = endedThreeYearsAgo("Physio")
        val seen = mutableListOf<SearchOutcome>()
        val job = launch { search("physio").collect { seen += it } }
        withTimeout(10_000) { while (seen.none { it.past.isNotEmpty() && it.past.single().displayTs > ended.startTs }) delay(10) }
        val rows = database.occurrencesDao().getForEvent(ended.id).size
        val emitted = seen.size

        writer.createEvent(series("Physio follow-up", ms(today.plusDays(1), 9)).copy(uid = "", rrule = null), isLocal = true)
        withTimeout(10_000) { while (seen.size == emitted || seen.last().upcoming.isEmpty()) delay(10) }
        delay(300)

        assertEquals(rows, database.occurrencesDao().getForEvent(ended.id).size)
        job.cancel()
    }

    @Test
    fun `a date range years ahead finds a series whose rows stop near today`(): Unit = runBlocking {
        seriesWithRows("Choir", ms(today, 19), ms(today), ms(today.plusDays(60)))
        val far = today.plusYears(5)
        val range = ms(far)..(ms(far.plusDays(7)) - 1)

        val outcome = withTimeout(10_000) {
            display.observeSearch(SearchQuery.of("choir"), System.currentTimeMillis(), zone, range).first { !it.range.isNullOrEmpty() }
        }

        assertEquals(listOf("Choir"), outcome.range!!.map { it.displayEvent.title })
        assertTrue(outcome.range!!.single().displayTs >= range.first)
    }

    /**
     * Collects a search until [shown] holds, then keeps collecting for two seconds, the time a
     * series expansion launched by the search would need to write its rows. Ending the
     * collection earlier would cancel that work and hide it.
     */
    private suspend fun searchAndSettle(text: String, shown: (SearchOutcome) -> Boolean): SearchOutcome = coroutineScope {
        val seen = mutableListOf<SearchOutcome>()
        val job = launch { search(text).collect { seen += it } }
        withTimeout(10_000) { while (seen.none(shown)) delay(10) }
        delay(2_000)
        job.cancel()
        seen.last(shown)
    }

    @Test
    fun `a running series whose dates are stored gains no rows from a search`(): Unit = runBlocking {
        val standupId = database.eventsDao().insert(series("Standup", ms(today.minusYears(8), 9)).copy(rrule = "FREQ=DAILY"))
        val standup = database.eventsDao().getById(standupId)!!
        generator.generateOccurrences(standup, ms(today.minusDays(30)), ms(today.plusDays(60)))
        val before = database.occurrencesDao().getForEvent(standup.id)

        val outcome = searchAndSettle("standup") { it.upcoming.isNotEmpty() && it.past.isNotEmpty() }

        assertEquals(listOf("Standup"), outcome.past.map { it.displayEvent.title })
        val after = database.occurrencesDao().getForEvent(standup.id)
        assertEquals(before.size, after.size)
        assertEquals(before.minOf { it.startTs }, after.minOf { it.startTs })
    }

    @Test
    fun `a series whose stored rows all ended is extended forward only`(): Unit = runBlocking {
        val choir = seriesWithRows("Choir", ms(today.minusYears(5), 19), ms(today.minusYears(1)), ms(today.minusDays(30)))
        val before = database.occurrencesDao().getForEvent(choir.id)

        val outcome = withTimeout(10_000) { search("choir").first { it.upcoming.isNotEmpty() } }

        assertEquals(listOf("Choir"), outcome.upcoming.map { it.displayEvent.title })
        val after = database.occurrencesDao().getForEvent(choir.id)
        assertEquals("nothing written before the stored rows", before.minOf { it.startTs }, after.minOf { it.startTs })
        assertTrue(after.size > before.size)
    }

    @Test
    fun `a renamed occurrence alone writes no rows`(): Unit = runBlocking {
        val yoga = seriesWithRows("Yoga", ms(today.minusYears(8), 7), ms(today.minusDays(30)), ms(today.plusDays(60)))
        val instance = database.occurrencesDao().getForEvent(yoga.id).first { it.startTs > System.currentTimeMillis() }.startTs
        val renamedId = database.eventsDao().insert(
            yoga.copy(id = 0, title = "Morning stretch", rrule = null, startTs = instance, endTs = instance + hour,
                originalEventId = yoga.id, originalInstanceTime = instance)
        )
        generator.linkException(yoga.id, instance, database.eventsDao().getById(renamedId)!!)
        val rows = database.occurrencesDao().getForEvent(yoga.id).size

        val outcome = searchAndSettle("stretch") { it.upcoming.isNotEmpty() }

        assertEquals(listOf("Morning stretch"), outcome.upcoming.map { it.displayEvent.title })
        assertEquals(rows, database.occurrencesDao().getForEvent(yoga.id).size)
    }

    @Test
    fun `a series starting years ahead and viewed past its start is listed at its first occurrence`(): Unit = runBlocking {
        val first = today.plusYears(3)
        val created = writer.createEvent(series("Retreat", ms(first, 9)).copy(uid = ""), isLocal = true)
        val viewed = first.plusYears(1)
        assertTrue("the week a year past its start shows it", weekShows(viewed, "Retreat"))

        val outcome = searchAndSettle("retreat") { it.upcoming.isNotEmpty() }

        assertEquals(created.startTs, outcome.upcoming.single().displayTs)
    }

    @Test
    fun `a two-letter search does not expand the series it matches`(): Unit = runBlocking {
        val ended = endedThreeYearsAgo("Physio")

        val outcome = searchAndSettle("ph") { it.past.isNotEmpty() }

        assertEquals("dated by its own start", ended.startTs, outcome.past.single().displayTs)
        assertEquals(0, database.occurrencesDao().getForEvent(ended.id).size)
    }
}
