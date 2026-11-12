package org.onekash.kashcal.domain.reader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.CalendarProviderManager
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.search.SearchOutcome
import org.onekash.kashcal.domain.search.SearchQuery
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * [DisplayEventRepository.observeSearch] over a real Room database and the shared
 * [FakeCalendarProviderRepository]: Room and device matches split by Upcoming, Past and hidden
 * calendars, a revoked permission, re-runs on the device change signal and their bounds, the
 * device window, and the words the device search gets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DisplayEventRepositorySearchTest {

    private lateinit var database: KashCalDatabase
    private lateinit var writer: EventWriter
    private lateinit var display: DisplayEventRepository
    private val fake = FakeCalendarProviderRepository()
    private val changeSignal = MutableStateFlow(0)
    private val hiddenDevice = MutableStateFlow(emptySet<Long>())
    private val syncPastDays = MutableStateFlow(KashCalDataStore.DEFAULT_SYNC_PAST_DAYS)
    private var showDeclined = true
    private var calendarId = 0L
    private var hiddenCalendarId = 0L

    private val zone = ZoneId.of("UTC")
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = System.currentTimeMillis() / hour * hour
    private val today = LocalDate.now(zone)

    @Before
    fun setUp(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val generator = OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(),
            TestDataStoreFactory.createWithSyncLookback(KashCalDataStore.DEFAULT_SYNC_PAST_DAYS),
        )
        writer = EventWriter(database, generator)
        val accountId = database.accountsDao().insert(Account(provider = AccountProvider.LOCAL, email = "local@example.test"))
        calendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = "local://cal", displayName = "Local", color = 1, isVisible = true)
        )
        hiddenCalendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = "local://old", displayName = "Old", color = 2, isVisible = false)
        )
        val dataStore: KashCalDataStore = mockk {
            every { hiddenDeviceCalendarIds } returns hiddenDevice
            every { syncPastDays } returns this@DisplayEventRepositorySearchTest.syncPastDays
            coEvery { getShowDeclinedEvents() } answers { showDeclined }
            coEvery { getDeviceCalendarsEnabled() } returns true
            coEvery { getEnabledDeviceCalendarIds() } returns setOf(DEVICE_CAL, HIDDEN_DEVICE_CAL)
        }
        val manager: CalendarProviderManager = mockk { every { changeSignal } returns this@DisplayEventRepositorySearchTest.changeSignal }
        display = DisplayEventRepository(
            EventReader(database), fake, manager, dataStore, database.attendeesDao(), database.accountsDao(), generator,
        )
        Unit
    }

    @After
    fun tearDown() = database.close()

    private suspend fun create(title: String, start: Long, calendar: Long = calendarId, rrule: String? = null): Long =
        writer.createEvent(
            Event(
                uid = "", calendarId = calendar, title = title, startTs = start, endTs = start + hour, dtstamp = 0L,
                rrule = rrule, timezone = "UTC", syncStatus = SyncStatus.SYNCED,
            ),
            isLocal = true,
        ).id

    private fun device(eventId: Long, title: String, start: Long, calendar: Long = DEVICE_CAL) = DeviceCalendarInstance(
        instanceId = eventId * 1000 + start / day, eventId = eventId, title = title, description = "", location = "",
        startTs = start, endTs = start + hour,
        startDay = DayPagerUtils.msToDayCode(start), endDay = DayPagerUtils.msToDayCode(start + hour),
        isAllDay = false, hasRrule = false, rrule = null, reminders = emptyList(), calendarId = calendar,
        calendarDisplayName = "Phone", calendarColor = 0, eventColor = null, status = 1, availability = 0,
        hasAlarm = false, selfAttendeeStatus = 0, isWritable = true, originalId = null, originalInstanceTime = null,
        timezone = "UTC", eventStartTs = start,
    )

    private suspend fun search(text: String, range: LongRange? = null, until: (SearchOutcome) -> Boolean = { true }) =
        withTimeout(10_000) { display.observeSearch(SearchQuery.of(text), now, zone, range).first(until) }

    private fun titles(hits: List<org.onekash.kashcal.domain.search.SearchHit>) = hits.map { it.displayEvent.title }

    @Test
    fun `past-only matches from both sources are counted under Past`(): Unit = runBlocking {
        create("Dermatology checkup", now - 60 * day)
        create("Dermatology results", now - 400 * day)
        fake.instances = listOf(device(1, "Dermatology scan", now - 30 * day))

        val outcome = search("dermatology")

        assertTrue(outcome.upcoming.isEmpty())
        assertEquals(listOf("Dermatology scan", "Dermatology checkup", "Dermatology results"), titles(outcome.past))
    }

    @Test
    fun `a weekly series is listed once under each side`(): Unit = runBlocking {
        create("Standup", now - 21 * day + hour, rrule = "FREQ=WEEKLY")

        val outcome = search("standup") { it.upcoming.isNotEmpty() && it.past.isNotEmpty() }

        assertEquals(1, outcome.upcoming.size)
        assertEquals(1, outcome.past.size)
        assertEquals(now + hour, outcome.upcoming.single().displayTs)
        assertEquals(now - 7 * day + hour, outcome.past.single().displayTs)
    }

    @Test
    fun `matches in hidden calendars are split out on both sources`(): Unit = runBlocking {
        create("Dentist", now + day)
        create("Dentist old", now + 2 * day, calendar = hiddenCalendarId)
        fake.instances = listOf(device(1, "Dentist phone", now + 3 * day, calendar = HIDDEN_DEVICE_CAL))
        hiddenDevice.value = setOf(HIDDEN_DEVICE_CAL)

        val outcome = search("dentist")

        assertEquals(listOf("Dentist"), titles(outcome.upcoming))
        assertEquals(listOf("Dentist old", "Dentist phone"), titles(outcome.hiddenUpcoming))
        assertTrue("hidden device calendars are still searched", fake.searchCalls.single().calendarIds.contains(HIDDEN_DEVICE_CAL))
    }

    @Test
    fun `a revoked calendar permission keeps the Room matches`(): Unit = runBlocking {
        create("Dentist", now + day)
        fake.instances = listOf(device(1, "Dentist phone", now + 3 * day))
        fake.shouldThrowSecurityException = true

        val outcome = search("dentist")

        assertEquals(listOf("Dentist"), titles(outcome.upcoming))
    }

    @Test
    fun `a device change while open searches again and re-emits`(): Unit = runBlocking {
        val seen = mutableListOf<SearchOutcome>()
        val job = display.observeSearch(SearchQuery.of("dentist"), now, zone).onEach { seen += it }.launchIn(this)
        withTimeout(10_000) { while (seen.isEmpty()) kotlinx.coroutines.delay(10) }

        fake.instances = listOf(device(1, "Dentist phone", now + 3 * day))
        changeSignal.value = 1

        withTimeout(10_000) { while (seen.last().upcoming.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(listOf("Dentist phone"), titles(seen.last().upcoming))
        job.cancel()
    }

    @Test
    fun `a burst of Room writes does not run the device search again`(): Unit = runBlocking {
        val seen = mutableListOf<SearchOutcome>()
        val job = display.observeSearch(SearchQuery.of("dentist"), now, zone).onEach { seen += it }.launchIn(this)
        withTimeout(10_000) { while (seen.isEmpty()) kotlinx.coroutines.delay(10) }

        repeat(10) { create("Dentist $it", now + (it + 1) * day) }
        withTimeout(10_000) { while (seen.last().upcoming.size < 10) kotlinx.coroutines.delay(10) }

        assertTrue("device searches: ${fake.searchCalls.size}", fake.searchCalls.size <= 2)
        job.cancel()
    }

    @Test
    fun `the device search covers the sync lookback to two years ahead`(): Unit = runBlocking {
        search("dentist")

        val call = fake.searchCalls.single()
        assertEquals(DayPagerUtils.localDateToDayCode(today.minusDays(KashCalDataStore.DEFAULT_SYNC_PAST_DAYS.toLong())), call.startDayCode)
        assertEquals(DayPagerUtils.localDateToDayCode(today.plusYears(2)), call.endDayCode)
    }

    @Test
    fun `a range searches the device by the range's own days`(): Unit = runBlocking {
        val far = today.plusYears(3)
        val start = far.atStartOfDay(zone).toInstant().toEpochMilli()
        fake.instances = listOf(device(1, "Dentist far", start + 10 * hour))

        val outcome = search("dentist", range = start..(start + 7 * day - 1))

        assertEquals(listOf("Dentist far"), titles(outcome.range!!))
        assertTrue("outside the default window", outcome.upcoming.isEmpty())
        assertEquals(DayPagerUtils.localDateToDayCode(far), fake.searchCalls.last().startDayCode)
    }

    @Test
    fun `the device search gets the same capped words as Room`(): Unit = runBlocking {
        val text = "\"\" " + (1..31).joinToString(" ") { "w$it" }

        search(text)

        val sent = fake.searchCalls.single().query
        assertEquals(SearchQuery.of(text).words, SearchQuery.of(sent).words)
        assertEquals(30, SearchQuery.of(sent).words.size)
    }

    @Test
    fun `each hit says where it matched, guests included on both sources`(): Unit = runBlocking {
        val id = create("Checkup", now + day)
        database.attendeesDao().insertAll(
            listOf(org.onekash.kashcal.data.db.entity.Attendee(eventId = id, address = "mailto:amara@example.test", displayName = "Dr. Amara Lee"))
        )
        create("Lee family dinner", now + 2 * day)
        fake.instances = listOf(device(7, "Scan", now + 3 * day))
        fake.deviceAttendees[7] = listOf(
            org.onekash.kashcal.data.calendar_provider.DeviceAttendee(id = 1, name = "Kim Lee", email = "kim@example.test", relationship = 0, status = 0)
        )

        val outcome = search("lee")

        val byTitle = outcome.upcoming.associateBy { it.displayEvent.title }
        assertEquals(org.onekash.kashcal.domain.search.MatchReason(org.onekash.kashcal.domain.search.MatchField.GUEST, "Dr. Amara Lee"), byTitle.getValue("Checkup").reason)
        assertEquals(null, byTitle.getValue("Lee family dinner").reason)
        assertEquals("Kim Lee", byTitle.getValue("Scan").reason?.text)
    }

    @Test
    fun `a lookback changed while search is open shows in its results`(): Unit = runBlocking {
        val seen = mutableListOf<SearchOutcome>()
        val job = display.observeSearch(SearchQuery.of("orthodontist"), now, zone).onEach { seen += it }.launchIn(this)
        withTimeout(10_000) { while (seen.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(KashCalDataStore.DEFAULT_SYNC_PAST_DAYS, seen.last().lookbackDays)

        // The empty state's Sync settings link opens the setting; the user changes it and comes
        // back.
        syncPastDays.value = 730

        withTimeout(10_000) { while (seen.last().lookbackDays != 730) kotlinx.coroutines.delay(10) }
        assertEquals(730, seen.last().lookbackDays)
        job.cancel()
    }

    @Test
    fun `with All events the device search goes back ten years`(): Unit = runBlocking {
        syncPastDays.value = Int.MAX_VALUE

        val outcome = search("dentist")

        assertEquals(DayPagerUtils.localDateToDayCode(today.minusYears(10)), fake.searchCalls.single().startDayCode)
        assertEquals(Int.MAX_VALUE, outcome.lookbackDays)
    }

    @Test
    fun `the device search hides declined events when the setting says so`(): Unit = runBlocking {
        showDeclined = false
        search("dentist")
        assertTrue(fake.searchCalls.single().hideDeclined)
    }

    @Test
    fun `a calendar hidden in the app splits again without searching again`(): Unit = runBlocking {
        create("Dentist", now + day)
        val seen = mutableListOf<SearchOutcome>()
        val job = display.observeSearch(SearchQuery.of("dentist"), now, zone).onEach { seen += it }.launchIn(this)
        withTimeout(10_000) { while (seen.lastOrNull()?.upcoming?.size != 1) kotlinx.coroutines.delay(10) }
        val searches = fake.searchCalls.size

        database.calendarsDao().getById(calendarId)!!.let { database.calendarsDao().update(it.copy(isVisible = false)) }

        withTimeout(10_000) { while (seen.last().hiddenUpcoming.size != 1) kotlinx.coroutines.delay(10) }
        assertTrue(seen.last().upcoming.isEmpty())
        assertEquals(searches, fake.searchCalls.size)
        job.cancel()
    }

    private companion object {
        const val DEVICE_CAL = 50L
        const val HIDDEN_DEVICE_CAL = 60L
    }
}
