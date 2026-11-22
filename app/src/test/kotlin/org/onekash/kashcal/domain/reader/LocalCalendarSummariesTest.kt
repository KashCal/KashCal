package org.onekash.kashcal.domain.reader

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.coordinator.OfflineCalendarFixture
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.testutil.insertOfflineCalendar
import org.onekash.kashcal.ui.model.CalendarGroup
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * [EventReader.getLocalCalendarSummaries]: every offline calendar with the number of events a
 * user sees in it, live, in the drawer's order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LocalCalendarSummariesTest {

    private lateinit var fixture: OfflineCalendarFixture
    private val reader get() = fixture.reader
    private val db get() = fixture.db

    private val hour = 3_600_000L
    private val start = System.currentTimeMillis() + 2 * 86_400_000L

    private var builtInId = 0L

    @Before
    fun setUp() = runBlocking {
        fixture = OfflineCalendarFixture(ApplicationProvider.getApplicationContext())
        builtInId = fixture.coordinator.ensureLocalCalendarExists()
    }

    @After
    fun tearDown() = fixture.close()

    private fun event(calendarId: Long, rrule: String? = null, uid: String = "sum-${UUID.randomUUID()}") = Event(
        uid = uid,
        calendarId = calendarId,
        title = "Event",
        startTs = start,
        endTs = start + hour,
        dtstamp = 0L,
        rrule = rrule,
    )

    private suspend fun summaries() = reader.getLocalCalendarSummaries().first()

    @Test
    fun `lists the offline calendars and nothing else`() = runBlocking {
        val second = db.insertOfflineCalendar("Work")
        fixture.insertCalendarUnder(AccountProvider.ICS, "https://feeds.example.test/holidays.ics", "Holidays")
        fixture.insertCalendarUnder(AccountProvider.CONTACTS, "local://contact_birthdays", "Birthdays")
        fixture.insertCalendarUnder(AccountProvider.CALDAV, "https://dav.example.test/cal/personal/", "Personal")
        fixture.insertCalendarUnder(AccountProvider.ICLOUD, "https://caldav.example.test/123456789/calendars/home/", "Home")

        assertEquals(setOf(builtInId, second), summaries().map { it.calendar.id }.toSet())
    }

    @Test
    fun `counts one-offs and series once, not exceptions or deleted rows`() = runBlocking {
        val id = db.insertOfflineCalendar("Work")
        val events = db.eventsDao()
        events.insert(event(id))
        val seriesUid = "sum-series-${UUID.randomUUID()}"
        val seriesId = events.insert(event(id, rrule = "FREQ=WEEKLY;COUNT=4", uid = seriesUid))
        events.insert(
            event(id, uid = seriesUid).copy(originalEventId = seriesId, originalInstanceTime = start + 7 * 86_400_000L)
        )
        events.insert(event(id).copy(syncStatus = SyncStatus.PENDING_DELETE))

        val counts = summaries().associate { it.calendar.id to it.eventCount }
        assertEquals(2, counts[id])
        assertEquals(0, counts[builtInId])
    }

    @Test
    fun `the count matches what a delete reports`() = runBlocking {
        val id = db.insertOfflineCalendar("Work")
        val events = db.eventsDao()
        events.insert(event(id))
        val seriesUid = "sum-series-${UUID.randomUUID()}"
        val seriesId = events.insert(event(id, rrule = "FREQ=WEEKLY;COUNT=4", uid = seriesUid))
        events.insert(
            event(id, uid = seriesUid).copy(originalEventId = seriesId, originalInstanceTime = start + 7 * 86_400_000L)
        )
        events.insert(event(id).copy(syncStatus = SyncStatus.PENDING_DELETE))

        val listed = summaries().single { it.calendar.id == id }.eventCount
        assertEquals(listed, fixture.coordinator.deleteLocalCalendar(id).getOrThrow())
    }

    @Test
    fun `emits as events and calendars change`() = runBlocking {
        val id = db.insertOfflineCalendar("Work")
        reader.getLocalCalendarSummaries().test(timeout = 5_000.milliseconds) {
            assertEquals(0, awaitItem().single { it.calendar.id == id }.eventCount)

            fixture.coordinator.createEvent(event(id), calendarId = id)
            assertEquals(1, awaitItem().single { it.calendar.id == id }.eventCount)

            val added = fixture.coordinator.createLocalCalendar("Trips", 0, "Local").getOrThrow()
            assertTrue(awaitItem().any { it.calendar.id == added })

            fixture.coordinator.renameLocalCalendar(added, "Holidays", "Local").getOrThrow()
            assertEquals("Holidays", awaitItem().single { it.calendar.id == added }.calendar.displayName)

            fixture.coordinator.deleteLocalCalendar(id).getOrThrow()
            assertTrue(awaitItem().none { it.calendar.id == id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an unrelated write doesn't re-emit the same list`() = runBlocking {
        val caldav = fixture.insertCalendarUnder(AccountProvider.CALDAV, "https://dav.example.test/cal/personal/", "Personal")
        reader.getLocalCalendarSummaries().test(timeout = 5_000.milliseconds) {
            assertEquals(0, awaitItem().single().eventCount)
            db.eventsDao().insert(event(caldav))
            // Room re-runs the query on its own thread; give it time to deliver a repeat.
            delay(1_000)
            expectNoEvents()
            db.eventsDao().insert(event(builtInId))
            assertEquals(1, awaitItem().single().eventCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sorts by the localized name, as the drawer does`() = runBlocking {
        db.insertOfflineCalendar("Beta")
        db.insertOfflineCalendar("alpha")
        val localize = { c: Calendar ->
            if (c.caldavUrl == LocalCalendarInitializer.LOCAL_CALENDAR_URL) "Zuhause" else c.displayName
        }

        val listed = reader.getLocalCalendarSummaries(localize).first().map { localize(it.calendar) }
        assertEquals(listOf("alpha", "Beta", "Zuhause"), listed)

        val calendars = db.calendarsDao().getCalendarsByProvider("local").first()
        val drawer = CalendarGroup.fromCalendarsAndAccounts(
            calendars, db.accountsDao().getAllOnce(), localLabel = "Offline", icsLabel = "Feeds", localizeCalendarName = localize
        ).single().calendars.map { it.displayName }
        assertEquals(drawer, listed)

        assertEquals(
            listOf("alpha", "Beta", "Local"),
            reader.getLocalCalendarSummaries().first().map { it.calendar.displayName }
        )
    }
}
