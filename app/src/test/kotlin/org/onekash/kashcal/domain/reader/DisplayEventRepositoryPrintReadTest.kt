package org.onekash.kashcal.domain.reader

import android.provider.CalendarContract.Attendees
import androidx.room.Room
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.CalendarProviderManager
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.HOUR
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.LOCAL_CAL
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.OWNER
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.SYNCED_CAL
import org.onekash.kashcal.data.calendar_provider.SqliteCalendarProvider
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tests the print read ([DisplayEventRepository.getDisplayEventsForPrint]): the calendars the
 * user ticks, visible or hidden, for exactly the printed week. Room events come from a real
 * [EventReader] over an in-memory database; device events from the real provider repository
 * over [SqliteCalendarProvider], whose Instances window matches the platform, so the device
 * read's one-day widening on each side shows up. The device zone is Berlin
 * ([DeviceRoundTripFixture]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DisplayEventRepositoryPrintReadTest {

    private val f = DeviceRoundTripFixture()
    private lateinit var database: KashCalDatabase
    private lateinit var generator: OccurrenceGenerator
    private lateinit var display: DisplayEventRepository

    private var showDeclined = true
    private var deviceFeatureOn = true
    private var enabledDeviceIds = setOf(SYNCED_CAL, LOCAL_CAL)

    private var accountId = 0L
    private var workId = 0L
    private var hiddenId = 0L

    // Mon 2024-03-04 to Sun 2024-03-10 in Berlin, half-open at the next Monday's midnight.
    private val weekStart = LocalDate.of(2024, 3, 4)
    private lateinit var zone: ZoneId
    private var startMs = 0L
    private var endMs = 0L

    @Before
    fun setUp() {
        f.setUp()
        zone = ZoneId.systemDefault()
        startMs = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        endMs = weekStart.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()

        database = Room.inMemoryDatabaseBuilder(f.context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        generator = OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault()
        )
        runTest {
            accountId = database.accountsDao().insert(Account(provider = AccountProvider.ICLOUD, email = OWNER))
            workId = database.calendarsDao().insert(
                Calendar(accountId = accountId, caldavUrl = "https://example.test/work/", displayName = "Work", color = 1, isVisible = true)
            )
            hiddenId = database.calendarsDao().insert(
                Calendar(accountId = accountId, caldavUrl = "https://example.test/hidden/", displayName = "Hidden", color = 2, isVisible = false)
            )
        }

        val dataStore: KashCalDataStore = mockk {
            coEvery { getShowDeclinedEvents() } answers { showDeclined }
            coEvery { getDeviceCalendarsEnabled() } answers { deviceFeatureOn }
            coEvery { getEnabledDeviceCalendarIds() } answers { enabledDeviceIds }
        }
        // The print read is one-shot: it never listens to the change signal.
        val manager: CalendarProviderManager = mockk()
        display = DisplayEventRepository(
            EventReader(database), f.repository, manager, dataStore,
            database.attendeesDao(), database.accountsDao(), generator,
        )
    }

    @After
    fun tearDown() {
        database.close()
        f.tearDown()
    }

    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private suspend fun roomEvent(
        title: String,
        start: Long,
        end: Long = start + HOUR,
        calendarId: Long = workId,
        allDay: Boolean = false,
    ): Event {
        val event = Event(
            uid = "uid-$title",
            calendarId = calendarId,
            title = title,
            startTs = start,
            endTs = end,
            isAllDay = allDay,
            dtstamp = 0L,
            syncStatus = SyncStatus.SYNCED,
        )
        val created = event.copy(id = database.eventsDao().insert(event))
        generator.generateOccurrences(created, start - 14 * DAY_MS, end + 14 * DAY_MS)
        return created
    }

    private suspend fun deviceEvent(
        title: String,
        start: Long,
        end: Long = start + HOUR,
        calendarId: Long = SYNCED_CAL,
        allDay: Boolean = false,
    ): Long = f.writer.createEvent(
        f.draft(title = title, startTs = start, endTs = end, isAllDay = allDay, calendarId = calendarId)
    ).getOrThrow().eventId

    private suspend fun printed(
        room: Set<Long> = setOf(workId),
        device: Set<Long> = setOf(SYNCED_CAL),
    ): List<String> = display.getDisplayEventsForPrint(startMs, endMs, room, device).map { it.title }

    @Test
    fun `a ticked hidden Room calendar prints and an unticked visible one does not`() = runTest {
        roomEvent("Work review", at(weekStart, 10))
        roomEvent("Private", at(weekStart, 12), calendarId = hiddenId)

        assertEquals(listOf("Private"), printed(room = setOf(hiddenId), device = emptySet()))
        assertEquals(listOf("Work review", "Private"), printed(room = setOf(workId, hiddenId), device = emptySet()))
    }

    @Test
    fun `a cancelled occurrence is left out, as in the Week view`() = runTest {
        val event = roomEvent("Standup", at(weekStart, 9))
        roomEvent("Lunch", at(weekStart, 12))
        database.occurrencesDao().markCancelled(event.id, event.startTs)

        assertEquals(listOf("Lunch"), printed(device = emptySet()))
    }

    @Test
    fun `a self-declined Room event follows the show-declined setting`() = runTest {
        val event = roomEvent("Brown-bag talk", at(weekStart, 12))
        database.attendeesDao().insertAll(
            listOf(Attendee(eventId = event.id, address = "mailto:$OWNER", partstat = "DECLINED"))
        )

        showDeclined = true
        val shown = display.getDisplayEventsForPrint(startMs, endMs, setOf(workId), emptySet())
        assertEquals(listOf(true), shown.map { it.isDeclinedByMe })

        showDeclined = false
        assertTrue(printed(device = emptySet()).isEmpty())
    }

    @Test
    fun `a self-declined device event follows the show-declined setting`() = runTest {
        val id = deviceEvent("Offsite", at(weekStart, 14))
        f.provider.insertRow(
            SqliteCalendarProvider.ATTENDEES, Attendees.EVENT_ID to id, Attendees.ATTENDEE_EMAIL to OWNER,
            Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_INVITED,
        )
        f.writer.replyRsvp(id, SYNCED_CAL, Attendees.ATTENDEE_STATUS_DECLINED)

        showDeclined = true
        assertEquals(listOf("Offsite"), printed(room = emptySet()))
        showDeclined = false
        assertTrue(printed(room = emptySet()).isEmpty())
    }

    @Test
    fun `a hidden device calendar prints when ticked, an unticked or disabled one does not`() = runTest {
        deviceEvent("Work sync", at(weekStart, 9), calendarId = SYNCED_CAL)
        deviceEvent("Personal", at(weekStart, 18), calendarId = LOCAL_CAL)

        // Hidden from view is not passed in: the user's tick decides.
        assertEquals(listOf("Personal"), printed(room = emptySet(), device = setOf(LOCAL_CAL)))

        enabledDeviceIds = setOf(SYNCED_CAL)
        assertTrue(printed(room = emptySet(), device = setOf(LOCAL_CAL)).isEmpty())

        enabledDeviceIds = setOf(SYNCED_CAL, LOCAL_CAL)
        deviceFeatureOn = false
        assertTrue(printed(room = emptySet(), device = setOf(SYNCED_CAL, LOCAL_CAL)).isEmpty())
    }

    @Test
    fun `device events the day before and the day after the week are left out`() = runTest {
        deviceEvent("Saturday before", at(weekStart.minusDays(2), 10))
        deviceEvent("Sunday before", at(weekStart.minusDays(1), 22))
        deviceEvent("Monday", at(weekStart, 10))
        deviceEvent("Sunday", at(weekStart.plusDays(6), 21))
        deviceEvent("Monday after", at(weekStart.plusDays(7), 0, 30))
        val nextMonday = weekStart.plusDays(7).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        deviceEvent("All day after", nextMonday, nextMonday + DAY_MS - 1, allDay = true)

        assertEquals(listOf("Monday", "Sunday"), printed(room = emptySet()))
    }

    @Test
    fun `a device all-day event inside the week is kept by its day`() = runTest {
        val sunday = weekStart.plusDays(6).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        deviceEvent("Last-day all day", sunday, sunday + DAY_MS - 1, allDay = true)

        assertEquals(listOf("Last-day all day"), printed(room = emptySet()))
    }

    @Test
    fun `Room events touching the week's edges are left out`() = runTest {
        roomEvent("Ends at week start", startMs - HOUR, startMs)
        roomEvent("Starts at week end", endMs, endMs + HOUR)
        roomEvent("Spans into the week", startMs - HOUR, startMs + HOUR)
        roomEvent("Zero length at start", startMs, startMs)

        assertEquals(
            setOf("Spans into the week", "Zero length at start"),
            printed(device = emptySet()).toSet(),
        )
    }

    @Test
    fun `Room all-day events are kept by day code, not by their UTC start`() = runTest {
        val utcMidnight = { d: LocalDate -> d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
        roomEvent("Day before", utcMidnight(weekStart.minusDays(1)), utcMidnight(weekStart) - 1, allDay = true)
        roomEvent("First day", utcMidnight(weekStart), utcMidnight(weekStart.plusDays(1)) - 1, allDay = true)
        roomEvent("Day after", utcMidnight(weekStart.plusDays(7)), utcMidnight(weekStart.plusDays(8)) - 1, allDay = true)

        assertEquals(listOf("First day"), printed(device = emptySet()))
    }

    @Test
    fun `Room and device events come back as one list sorted by start`() = runTest {
        roomEvent("Room late", at(weekStart, 15))
        deviceEvent("Device early", at(weekStart, 8))
        roomEvent("Room middle", at(weekStart, 11))

        val events = display.getDisplayEventsForPrint(startMs, endMs, setOf(workId), setOf(SYNCED_CAL))
        assertEquals(listOf("Device early", "Room middle", "Room late"), events.map { it.title })
        assertEquals(listOf(true, false, false), events.map { it is DisplayEvent.Device })
    }

    @Test
    fun `nothing ticked reads nothing`() = runTest {
        roomEvent("Work review", at(weekStart, 10))
        deviceEvent("Work sync", at(weekStart, 9))

        assertTrue(printed(room = emptySet(), device = emptySet()).isEmpty())
    }

    @Test
    fun `a calendar permission failure prints the Room events only`() = runTest {
        roomEvent("Work review", at(weekStart, 10))
        val failing: CalendarProviderRepository = mockk {
            coEvery { getInstancesForDayRange(any(), any(), any(), any()) } throws SecurityException("revoked")
        }
        val dataStore: KashCalDataStore = mockk {
            coEvery { getShowDeclinedEvents() } returns true
            coEvery { getDeviceCalendarsEnabled() } returns true
            coEvery { getEnabledDeviceCalendarIds() } returns setOf(SYNCED_CAL)
        }
        val repository = DisplayEventRepository(
            EventReader(database), failing, mockk(), dataStore, database.attendeesDao(), database.accountsDao(), generator,
        )

        val events = repository.getDisplayEventsForPrint(startMs, endMs, setOf(workId), setOf(SYNCED_CAL))
        assertEquals(listOf("Work review"), events.map { it.title })
    }

    private companion object {
        const val DAY_MS = 24 * HOUR
    }
}
