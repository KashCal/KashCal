package org.onekash.kashcal.domain.reader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
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
 * created on a far date. Real [EventReader], [OccurrenceGenerator] and [EventWriter] over an
 * in-memory database; no device calendars.
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
}
