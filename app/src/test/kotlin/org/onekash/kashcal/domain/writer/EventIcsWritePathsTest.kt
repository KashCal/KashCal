package org.onekash.kashcal.domain.writer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.EventIcsStore
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.EventIcsPart
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the event writes that keep, copy or ignore a stored ICS without naming it: a move keeps
 * the row and so its ICS, a "this and future" split starts the new series from the old one's,
 * and reminders fall back to the row's own list for an event whose stored pieces can't be read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EventIcsWritePathsTest {

    private lateinit var context: Context
    private lateinit var db: KashCalDatabase
    private lateinit var store: EventIcsStore
    private lateinit var writer: EventWriter
    private var calendarA = 0L
    private var calendarB = 0L

    private val start = System.currentTimeMillis() + 2 * 86_400_000L

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabases.inMemory(context).allowMainThreadQueries().build()
        store = EventIcsStore(db)
        writer = EventWriter(db, OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault()), store)
        val accountId = db.accountsDao().insert(Account(provider = AccountProvider.LOCAL, email = "local"))
        calendarA = db.calendarsDao().insert(Calendar(accountId = accountId, caldavUrl = "local://a", displayName = "A", color = 0))
        calendarB = db.calendarsDao().insert(Calendar(accountId = accountId, caldavUrl = "local://b", displayName = "B", color = 0))
    }

    @After
    fun tearDown() = db.close()

    private fun event(rrule: String? = null, reminders: List<String>? = null, alarmCount: Int = 0) = Event(
        uid = "", calendarId = calendarA, title = "E", startTs = start, endTs = start + 3_600_000, dtstamp = 0,
        rrule = rrule, reminders = reminders, alarmCount = alarmCount, timezone = "UTC",
    )

    @Test
    fun `moving an event to another calendar keeps its stored ICS`() = runBlocking {
        val created = writer.createEvent(event(), isLocal = true, ics = "STORED")
        writer.moveEventToCalendar(created.id, calendarB)
        assertEquals(calendarB, db.eventsDao().getById(created.id)!!.calendarId)
        assertEquals("STORED", store.readOwn(created.id))
    }

    @Test
    fun `splitting a series starts the new series from the old one's stored ICS`() = runBlocking {
        val series = writer.createEvent(event(rrule = "FREQ=DAILY;COUNT=10"), isLocal = true, ics = "SERIES TEXT")
        val split = writer.splitSeries(series.id, start + 3 * 86_400_000L, series.copy(startTs = start + 3 * 86_400_000L, endTs = start + 3 * 86_400_000L + 3_600_000), isLocal = true)
        assertTrue(split.id != series.id)
        assertEquals("SERIES TEXT", store.readOwn(split.id))
        assertEquals("SERIES TEXT", store.readOwn(series.id))
    }

    @Test
    fun `an event whose stored pieces can't be read still gets its reminders and the scan goes on`() = runBlocking {
        val broken = writer.createEvent(event(reminders = listOf("-PT15M"), alarmCount = 5), isLocal = true)
        db.eventIcsDao().insertAll(listOf(EventIcsPart(broken.id, 0, "BEGIN"), EventIcsPart(broken.id, 2, "END")))
        val fine = writer.createEvent(event(reminders = listOf("-PT30M")), isLocal = true)
        val reader = EventReader(db, store)
        assertNull(reader.getStoredIcsOrNull(db.eventsDao().getById(broken.id)!!))

        val scheduler = ReminderScheduler(
            context = context,
            scheduledRemindersDao = db.scheduledRemindersDao(),
            eventReader = reader,
            channels = mockk(relaxed = true),
            attendeesDao = db.attendeesDao(),
            accountsDao = db.accountsDao(),
            calendarsDao = db.calendarsDao(),
            occurrencesDao = db.occurrencesDao(),
        )
        scheduler.scheduleUpcomingReminders()

        assertEquals(listOf("-PT15M"), db.scheduledRemindersDao().getAllForEvent(broken.id).map { it.reminderOffset }.distinct())
        assertEquals(listOf("-PT30M"), db.scheduledRemindersDao().getAllForEvent(fine.id).map { it.reminderOffset }.distinct())
    }
}
