package org.onekash.kashcal.domain.reader

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.CalendarProviderManager
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.ui.components.buildAgendaListModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * Tests that the day an occurrence is shown under follows the device zone at read time, not the
 * zone the device had when the occurrence rows were generated (#422).
 *
 * The series is a weekly 2-hour event generated in one zone; the device zone then moves 17 hours
 * (Los Angeles to Tokyo, or back) and the views read it through the real [EventReader] and
 * [DisplayEventRepository] over an in-memory database. Nov 3 2026 12:00 in Los Angeles is
 * Nov 4 05:00 in Tokyo (US daylight saving ended Nov 1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DisplayEventRepositoryZoneChangeTest {

    private lateinit var savedZone: TimeZone
    private lateinit var database: KashCalDatabase
    private lateinit var generator: OccurrenceGenerator
    private lateinit var display: DisplayEventRepository
    private var calendarId = 0L

    @Before
    fun setUp() {
        savedZone = TimeZone.getDefault()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        generator = OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault()
        )
        runTest {
            val accountId = database.accountsDao().insert(
                Account(provider = AccountProvider.CALDAV, email = "user@example.test")
            )
            calendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = accountId, caldavUrl = "https://example.test/life/",
                    displayName = "Life", color = 1, isVisible = true,
                )
            )
        }

        // Device calendars off, so only Room occurrences are read.
        val dataStore: KashCalDataStore = mockk {
            every { showDeclinedEvents } returns flowOf(true)
            every { hiddenDeviceCalendarIds } returns flowOf(emptySet())
            coEvery { getShowDeclinedEvents() } returns true
            coEvery { getDeviceCalendarsEnabled() } returns false
        }
        val manager: CalendarProviderManager = mockk {
            every { changeSignal } returns MutableStateFlow(0)
        }
        val provider: CalendarProviderRepository = mockk()
        display = DisplayEventRepository(
            EventReader(database), provider, manager, dataStore,
            database.attendeesDao(), database.accountsDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
        TimeZone.setDefault(savedZone)
    }

    private fun setDeviceZone(id: String) = TimeZone.setDefault(TimeZone.getTimeZone(id))

    private fun ms(date: LocalDate, hour: Int, zone: String): Long =
        date.atTime(hour, 0).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    /** Creates a weekly 2-hour series in [zone] and generates its rows with the device there. */
    private suspend fun weeklySeries(first: LocalDate, hour: Int, byDay: String, zone: String) {
        setDeviceZone(zone)
        val start = ms(first, hour, zone)
        val event = Event(
            uid = "uid-zsf",
            calendarId = calendarId,
            title = TITLE,
            startTs = start,
            endTs = start + 2 * HOUR_MS,
            timezone = zone,
            rrule = "FREQ=WEEKLY;BYDAY=$byDay",
            dtstamp = 0L,
            syncStatus = SyncStatus.SYNCED,
        )
        val created = event.copy(id = database.eventsDao().insert(event))
        generator.generateOccurrences(created, start - 7 * DAY_MS, start + 60 * DAY_MS)
    }

    private suspend fun titlesOnDayOnce(dayCode: Int): List<String> =
        display.getDisplayEventsGroupedByDayOnce(dayCode, dayCode)[dayCode].orEmpty().map { it.title }

    private suspend fun pagerDays(centerDay: LocalDate): Map<Int, List<String>> =
        display.getDisplayEventsForDayRange(
            centerDay.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        ).first().mapValues { (_, events) -> events.map { it.title } }

    private suspend fun weekStartDays(weekStart: LocalDate): List<Int> {
        val zone = ZoneId.systemDefault()
        val events = display.getDisplayEventsForRange(
            weekStart.atStartOfDay(zone).toInstant().toEpochMilli(),
            weekStart.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
        ).first()
        return events.filter { it.title == TITLE }.map { it.startDay }
    }

    // Los Angeles to Tokyo: Tue Nov 3 12:00-14:00 PST is Wed Nov 4 05:00-07:00 JST.

    @Test
    fun `after moving east, the single-day read lists the occurrence on its Tokyo day`() = runTest {
        weeklySeries(LocalDate.of(2026, 10, 13), 12, "TU", LA)
        setDeviceZone(TOKYO)

        assertEquals(listOf(TITLE), titlesOnDayOnce(20261104))
        assertEquals(emptyList<String>(), titlesOnDayOnce(20261103))
    }

    @Test
    fun `after moving east, the day pager files the occurrence under its Tokyo day`() = runTest {
        weeklySeries(LocalDate.of(2026, 10, 13), 12, "TU", LA)
        setDeviceZone(TOKYO)

        val days = pagerDays(LocalDate.of(2026, 11, 4))
        assertEquals(listOf(TITLE), days[20261104].orEmpty())
        assertEquals(emptyList<String>(), days[20261103].orEmpty())
    }

    @Test
    fun `after moving east, the week grid and agenda read the Tokyo day`() = runTest {
        weeklySeries(LocalDate.of(2026, 10, 13), 12, "TU", LA)
        setDeviceZone(TOKYO)

        // Week of Sun Nov 1 in Tokyo holds exactly the Nov 3 PST occurrence.
        assertEquals(listOf(20261104), weekStartDays(LocalDate.of(2026, 11, 1)))

        val zone = ZoneId.systemDefault()
        val events = display.getDisplayEventsForRange(
            LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant().toEpochMilli(),
            LocalDate.of(2026, 11, 8).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
        ).first()
        val agenda = buildAgendaListModel(events, todayDayCode = 20261103)
        assertEquals(listOf(20261104), agenda.groups.map { it.dayCode })
    }

    // Tokyo to Los Angeles: Wed Nov 4 05:00-07:00 JST is Tue Nov 3 12:00-14:00 PST.

    @Test
    fun `after moving west, the single-day read lists the occurrence on its Los Angeles day`() = runTest {
        weeklySeries(LocalDate.of(2026, 10, 14), 5, "WE", TOKYO)
        setDeviceZone(LA)

        assertEquals(listOf(TITLE), titlesOnDayOnce(20261103))
        assertEquals(emptyList<String>(), titlesOnDayOnce(20261104))
    }

    @Test
    fun `after moving west, the day pager files the occurrence under its Los Angeles day`() = runTest {
        weeklySeries(LocalDate.of(2026, 10, 14), 5, "WE", TOKYO)
        setDeviceZone(LA)

        val days = pagerDays(LocalDate.of(2026, 11, 3))
        assertEquals(listOf(TITLE), days[20261103].orEmpty())
        assertEquals(emptyList<String>(), days[20261104].orEmpty())
    }

    @Test
    fun `after moving west, the week grid reads the Los Angeles day`() = runTest {
        weeklySeries(LocalDate.of(2026, 10, 14), 5, "WE", TOKYO)
        setDeviceZone(LA)

        assertEquals(listOf(20261103), weekStartDays(LocalDate.of(2026, 11, 1)))
    }

    private companion object {
        const val TITLE = "ZSF meeting"
        const val LA = "America/Los_Angeles"
        const val TOKYO = "Asia/Tokyo"
        const val HOUR_MS = 60 * 60 * 1000L
        const val DAY_MS = 24 * HOUR_MS
    }
}
