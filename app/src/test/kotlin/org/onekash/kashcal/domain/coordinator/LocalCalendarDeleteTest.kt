package org.onekash.kashcal.domain.coordinator

import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.preferences.PreferencesKeys
import org.onekash.kashcal.domain.initializer.LocalCalendarException
import org.onekash.kashcal.domain.initializer.LocalCalendarRefusal
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.reminder.notification.ReminderNotificationManager
import org.onekash.kashcal.reminder.receiver.ReminderAlarmReceiver
import org.onekash.kashcal.testutil.insertOfflineCalendar
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.onekash.kashcal.testutil.TestDatabases
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Deleting an offline calendar through [EventCoordinator.deleteLocalCalendar], over real Room
 * with the pending-operation trigger, the real reminder scheduler on Robolectric's AlarmManager
 * and a real DataStore: what goes with it, what stays, where the default goes, and that a refused
 * or failed delete changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LocalCalendarDeleteTest {

    private lateinit var fixture: OfflineCalendarFixture
    private val coordinator get() = fixture.coordinator
    private val db get() = fixture.db

    private val hour = 3_600_000L
    private val day = 86_400_000L
    private val start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS).toEpochMilli()

    private var builtInId = 0L

    @Before
    fun setUp() = runBlocking {
        fixture = OfflineCalendarFixture(ApplicationProvider.getApplicationContext())
        builtInId = coordinator.ensureLocalCalendarExists()
    }

    @After
    fun tearDown() = fixture.close()

    /** What [seed] put in a calendar. */
    private data class Seeded(
        val calendarId: Long,
        val oneOffId: Long,
        val seriesId: Long,
        val exceptionId: Long,
        val reminderIds: List<Long>,
    ) {
        val eventIds get() = listOf(oneOffId, seriesId, exceptionId)
    }

    private fun event(calendarId: Long, title: String, startTs: Long, rrule: String? = null) = Event(
        uid = "delete-${UUID.randomUUID()}@example.test",
        calendarId = calendarId,
        title = title,
        startTs = startTs,
        endTs = startTs + hour,
        dtstamp = 0L,
        rrule = rrule,
        reminders = listOf("-PT15M"),
        alarmCount = 1,
    )

    /**
     * Gives [calendarId] a one-off with a guest, a daily series with one changed occurrence, and
     * their armed reminders.
     */
    private suspend fun seed(calendarId: Long): Seeded {
        val oneOff = coordinator.createEvent(
            event(calendarId, "Dentist", start),
            calendarId = calendarId,
            attendees = listOf(Attendee(eventId = 0, address = "mailto:guest@example.test")),
        )
        val seriesStart = start + 3 * hour
        val series = coordinator.createEvent(
            event(calendarId, "Standup", seriesStart, rrule = "FREQ=DAILY;COUNT=5"),
            calendarId = calendarId,
        )
        val exception = coordinator.editSingleOccurrence(series.id, seriesStart + day) {
            it.copy(title = "Moved standup")
        }
        val reminderIds = db.scheduledRemindersDao().getPendingForCalendar(calendarId).map { it.id }
        check(reminderIds.size >= 3) { "expected armed reminders, got $reminderIds" }
        check(fixture.armedReminders().keys.containsAll(reminderIds)) { "reminders not armed" }
        return Seeded(calendarId, oneOff.id, series.id, exception.id, reminderIds)
    }

    private fun rowsInCalendar(table: String, calendarId: Long): Int =
        TestDatabases.raw(db).query("SELECT COUNT(*) FROM $table WHERE calendar_id = ?", arrayOf(calendarId)).use {
            it.moveToFirst()
            it.getInt(0)
        }

    private suspend fun assertIntact(seeded: Seeded) {
        assertNotNull(db.calendarsDao().getById(seeded.calendarId))
        for (id in seeded.eventIds) assertNotNull("event $id", db.eventsDao().getById(id))
        assertTrue(rowsInCalendar("occurrences", seeded.calendarId) > 0)
        assertEquals(1, db.attendeesDao().getForEventOnce(seeded.oneOffId).size)
        for (id in seeded.reminderIds) assertNotNull("reminder row $id", db.scheduledRemindersDao().getById(id))
        assertTrue("alarms still armed", fixture.armedReminders().keys.containsAll(seeded.reminderIds))
    }

    private fun refusalOf(result: Result<*>) = fixture.refusalOf(result)

    private suspend fun storedDefault(): DefaultCalendar? = fixture.dataStore.getDefaultCalendar()

    // ---- what goes and what stays ----

    @Test
    fun `delete removes the calendar, its events, rows and alarms and counts the events`() = runBlocking {
        val seeded = seed(db.insertOfflineCalendar("Work"))
        // Offline writes queue nothing, so queue one by hand to show the delete trigger drops it.
        db.pendingOperationsDao().insert(
            PendingOperation(eventId = seeded.oneOffId, operation = PendingOperation.OPERATION_UPDATE)
        )

        val result = coordinator.deleteLocalCalendar(seeded.calendarId)

        assertEquals("a one-off and a series; the changed occurrence isn't counted", 2, result.getOrThrow())
        assertNull(db.calendarsDao().getById(seeded.calendarId))
        for (id in seeded.eventIds) assertNull("event $id", db.eventsDao().getById(id))
        assertEquals(0, rowsInCalendar("events", seeded.calendarId))
        assertEquals(0, rowsInCalendar("occurrences", seeded.calendarId))
        assertTrue(db.attendeesDao().getForEventOnce(seeded.oneOffId).isEmpty())
        for (id in seeded.reminderIds) assertNull("reminder row $id", db.scheduledRemindersDao().getById(id))
        assertTrue(db.pendingOperationsDao().getAllOnce().isEmpty())
        assertTrue(fixture.armedReminders().keys.none { it in seeded.reminderIds })
    }

    @Test
    fun `other calendars keep their events, reminders and alarms`() = runBlocking {
        val doomed = seed(db.insertOfflineCalendar("Work"))
        val other = seed(db.insertOfflineCalendar("Home"))
        val builtIn = seed(builtInId)

        coordinator.deleteLocalCalendar(doomed.calendarId).getOrThrow()

        assertIntact(other)
        assertIntact(builtIn)
    }

    @Test
    fun `deleting an empty calendar returns zero`() = runBlocking {
        val id = db.insertOfflineCalendar("Empty")

        assertEquals(0, coordinator.deleteLocalCalendar(id).getOrThrow())
        assertNull(db.calendarsDao().getById(id))
    }

    @Test
    fun `a successful delete refreshes widgets once and requests no sync`() = runBlocking {
        val seeded = seed(db.insertOfflineCalendar("Work"))
        clearMocks(fixture.widgets, answers = false)

        coordinator.deleteLocalCalendar(seeded.calendarId).getOrThrow()

        coVerify(exactly = 1) { fixture.widgets.updateAllWidgets(any()) }
        verify(exactly = 0) { fixture.syncScheduler.requestExpeditedSync(any()) }
    }

    @Test
    fun `an alarm that fires after the delete posts nothing`() = runBlocking {
        val seeded = seed(db.insertOfflineCalendar("Work"))
        coordinator.deleteLocalCalendar(seeded.calendarId).getOrThrow()
        val notifications = mockk<ReminderNotificationManager>()

        ReminderAlarmReceiver().handleAlarm(fixture.scheduler, notifications, seeded.reminderIds.first())

        verify { notifications wasNot io.mockk.Called }
    }

    // ---- the default calendar ----

    @Test
    fun `a deleted default moves to the built-in`() = runBlocking {
        val id = db.insertOfflineCalendar("Work")
        fixture.dataStore.setDefaultCalendar(DefaultCalendar.Room(id))

        coordinator.deleteLocalCalendar(id).getOrThrow()

        assertEquals(DefaultCalendar.Room(builtInId), storedDefault())
    }

    @Test
    fun `a deleted default held only by the legacy key moves to the built-in`() = runBlocking {
        val id = db.insertOfflineCalendar("Work")
        fixture.preferences.edit { it[PreferencesKeys.DEFAULT_CALENDAR_ID] = id }
        assertEquals(DefaultCalendar.Room(id), storedDefault())

        coordinator.deleteLocalCalendar(id).getOrThrow()

        assertEquals(DefaultCalendar.Room(builtInId), storedDefault())
    }

    @Test
    fun `another calendar or a device calendar as default is left alone`() = runBlocking {
        val doomed = db.insertOfflineCalendar("Work")
        val other = db.insertOfflineCalendar("Home")

        fixture.dataStore.setDefaultCalendar(DefaultCalendar.Room(other))
        coordinator.deleteLocalCalendar(doomed).getOrThrow()
        assertEquals(DefaultCalendar.Room(other), storedDefault())

        val second = db.insertOfflineCalendar("Trips")
        fixture.dataStore.setDefaultCalendar(DefaultCalendar.Device(second))
        coordinator.deleteLocalCalendar(second).getOrThrow()
        assertEquals("a device id equal to the deleted Room id is not the same calendar", DefaultCalendar.Device(second), storedDefault())
    }

    // ---- refused and failed deletes change nothing ----

    @Test
    fun `a failed row delete leaves events, reminders, alarms and the default as they were`() = runBlocking {
        val seeded = seed(db.insertOfflineCalendar("Work"))
        fixture.dataStore.setDefaultCalendar(DefaultCalendar.Room(seeded.calendarId))
        TestDatabases.raw(db).execSQL(
            "CREATE TRIGGER fail_cal_delete BEFORE DELETE ON calendars WHEN OLD.id = ${seeded.calendarId} " +
                "BEGIN SELECT RAISE(ABORT, 'test'); END"
        )
        clearMocks(fixture.widgets, answers = false)

        val result = coordinator.deleteLocalCalendar(seeded.calendarId)

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is LocalCalendarException)
        assertIntact(seeded)
        assertEquals(DefaultCalendar.Room(seeded.calendarId), storedDefault())
        coVerify(exactly = 0) { fixture.widgets.updateAllWidgets(any()) }
    }

    @Test
    fun `the built-in can't be deleted`() = runBlocking {
        val seeded = seed(builtInId)
        fixture.dataStore.setDefaultCalendar(DefaultCalendar.Room(builtInId))

        assertEquals(LocalCalendarRefusal.BUILT_IN, refusalOf(coordinator.deleteLocalCalendar(builtInId)))

        assertIntact(seeded)
        assertEquals(DefaultCalendar.Room(builtInId), storedDefault())
    }

    @Test
    fun `synced, feed, contact and unknown calendars are refused and nothing changes`() = runBlocking {
        val builtIn = seed(builtInId)
        val others = listOf(
            fixture.insertCalendarUnder(AccountProvider.ICLOUD, "https://caldav.example.test/123456789/calendars/home/", "Home"),
            fixture.insertCalendarUnder(AccountProvider.CALDAV, "https://dav.example.test/cal/personal/", "Personal"),
            fixture.insertCalendarUnder(AccountProvider.ICS, "https://feeds.example.test/holidays.ics", "Holidays"),
            fixture.insertCalendarUnder(AccountProvider.CONTACTS, "local://contact_birthdays", "Birthdays"),
        )
        val feedReminder = armedReminderIn(others[2])
        clearMocks(fixture.widgets, answers = false)

        for (id in others) {
            fixture.dataStore.setDefaultCalendar(DefaultCalendar.Room(id))
            assertEquals(LocalCalendarRefusal.NOT_LOCAL, refusalOf(coordinator.deleteLocalCalendar(id)))
            assertNotNull(db.calendarsDao().getById(id))
            assertEquals(DefaultCalendar.Room(id), storedDefault())
        }
        fixture.dataStore.setDefaultCalendar(DefaultCalendar.Room(9_999L))
        assertEquals(LocalCalendarRefusal.NOT_FOUND, refusalOf(coordinator.deleteLocalCalendar(9_999L)))
        assertEquals(DefaultCalendar.Room(9_999L), storedDefault())

        assertIntact(builtIn)
        assertTrue("a refused calendar keeps its alarm", feedReminder in fixture.armedReminders())
        coVerify(exactly = 0) { fixture.widgets.updateAllWidgets(any()) }
    }

    /** Stores an event with one pending reminder in [calendarId] and arms its alarm. */
    private suspend fun armedReminderIn(calendarId: Long): Long {
        val event = event(calendarId, "Feed item", start)
        val eventId = db.eventsDao().insert(event)
        val reminderId = db.scheduledRemindersDao().insert(
            ScheduledReminder(
                eventId = eventId,
                occurrenceTime = start,
                triggerTime = start - 15 * 60_000L,
                reminderOffset = "-PT15M",
                eventTitle = event.title,
                calendarColor = 0,
            )
        )
        check(fixture.scheduler.scheduleAlarm(reminderId, start - 15 * 60_000L))
        return reminderId
    }

    // ---- alarm cancel order ----

    @Test
    fun `the scheduler cancels the captured alarms only when the block returns`() = runBlocking {
        val seeded = seed(db.insertOfflineCalendar("Work"))

        val thrown = runCatching {
            fixture.scheduler.cancelCalendarAlarmsAfter(seeded.calendarId) { error("delete failed") }
        }
        assertTrue(thrown.isFailure)
        assertTrue(fixture.armedReminders().keys.containsAll(seeded.reminderIds))

        fixture.scheduler.cancelCalendarAlarmsAfter(seeded.calendarId) { }
        assertTrue(fixture.armedReminders().keys.none { it in seeded.reminderIds })
    }

    @Test
    fun `a reminder re-armed between the capture and the delete is still cancelled`() = runBlocking {
        val seeded = seed(db.insertOfflineCalendar("Work"))
        val rearmed = seeded.reminderIds.first()
        val newTime = start - 30 * 60_000L

        fixture.scheduler.cancelCalendarAlarmsAfter(seeded.calendarId) {
            assertTrue(fixture.scheduler.scheduleAlarm(rearmed, newTime))
            assertEquals(newTime, fixture.armedReminders()[rearmed])
            fixture.initializer.deleteCalendar(seeded.calendarId)
        }

        assertNull(fixture.armedReminders()[rearmed])
    }

    @Test
    fun `a caller cancelled during the row delete still gets alarms cancelled and the default moved`() = runBlocking {
        val seeded = seed(db.insertOfflineCalendar("Work"))
        fixture.dataStore.setDefaultCalendar(DefaultCalendar.Room(seeded.calendarId))
        val hookFired = AtomicBoolean(false)
        lateinit var job: Job
        fixture.onQuery = { sql ->
            if (sql.startsWith("DELETE FROM calendars") && hookFired.compareAndSet(false, true)) job.cancel()
        }

        job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            coordinator.deleteLocalCalendar(seeded.calendarId)
        }
        job.start()
        job.join()
        fixture.onQuery = null

        assertTrue("the hook cancelled the caller mid-delete", hookFired.get() && job.isCancelled)
        assertNull(db.calendarsDao().getById(seeded.calendarId))
        assertTrue(fixture.armedReminders().keys.none { it in seeded.reminderIds })
        assertEquals(DefaultCalendar.Room(builtInId), storedDefault())
    }
}
