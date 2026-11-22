package org.onekash.kashcal.domain.coordinator

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.reminder.ReminderAlarmFixture
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Tests that moving a series re-arms its changed occurrences' reminders: a renamed occurrence
 * moves with the series ([EventWriter.updateEvent]), and its alarm is stored under its own id,
 * which re-arming the master alone doesn't reach. Real writer, occurrence generator and reminder
 * scheduler over Robolectric's AlarmManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SeriesMoveRemindersTest {

    private lateinit var fixture: ReminderAlarmFixture
    private lateinit var writer: EventWriter
    private lateinit var coordinator: EventCoordinator
    private val savedZone: TimeZone = TimeZone.getDefault()
    private val newYork = ZoneId.of("America/New_York")
    private val hour = 3_600_000L

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(newYork))
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = ReminderAlarmFixture(context)
        val db = fixture.database
        val generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault())
        writer = EventWriter(db, generator)
        coordinator = EventCoordinator(
            eventWriter = writer,
            eventReader = fixture.eventReader,
            occurrenceGenerator = generator,
            localCalendarInitializer = LocalCalendarInitializer(db),
            icsSubscriptionRepository = mockk(),
            contactBirthdayRepository = mockk(),
            contactAnniversaryRepository = mockk(),
            accountRepository = mockk(),
            syncScheduler = mockk(relaxed = true),
            reminderScheduler = fixture.scheduler,
            widgetUpdateManager = mockk(relaxed = true),
            inviteNotifier = mockk(relaxed = true),
            icsRefreshScheduleReconciler = mockk(relaxed = true),
            dataStore = TestDataStoreFactory.createDefault(),
        )
        runTest { fixture.seedCalendar() }
    }

    @After
    fun tearDown() {
        fixture.close()
        TimeZone.setDefault(savedZone)
    }

    private fun day(offset: Long, hourOfDay: Int): Long =
        ZonedDateTime.of(LocalDate.now(newYork).plusDays(offset), LocalTime.of(hourOfDay, 0), newYork).toInstant().toEpochMilli()

    @Test
    fun `a renamed occurrence's alarm moves with the series`() = runTest {
        val series = writer.createEvent(
            Event(
                uid = "", calendarId = fixture.calendarId, title = "Standup",
                startTs = day(2, 10), endTs = day(2, 10) + hour, timezone = "America/New_York",
                rrule = "FREQ=DAILY;COUNT=4", reminders = listOf("-PT15M"), alarmCount = 1, dtstamp = 0L,
            ),
            isLocal = true,
        )
        val renamedAt = day(3, 10)
        writer.editSingleOccurrence(series.id, renamedAt, series.copy(title = "Renamed", startTs = renamedAt, endTs = renamedAt + hour), isLocal = true)
        // Arm every alarm at the old times first, as the app has them before the move.
        coordinator.updateEvent(fixture.eventReader.getEventById(series.id)!!)
        val fifteenBefore = 15 * 60_000L
        assertTrue("${fixture.armedReminders().values}", (renamedAt - fifteenBefore) in fixture.armedReminders().values)
        val stored = fixture.eventReader.getEventById(series.id)!!

        coordinator.updateEvent(stored.copy(startTs = stored.startTs + hour, endTs = stored.endTs + hour))

        val triggers = fixture.armedReminders().values
        val fifteen = 15 * 60_000L
        assertTrue("$triggers", (renamedAt + hour - fifteen) in triggers)
        assertFalse("$triggers", (renamedAt - fifteen) in triggers)
    }

    @Test
    fun `a changed occurrence moved again keeps one alarm, at its new time`() = runTest {
        val series = writer.createEvent(
            Event(
                uid = "", calendarId = fixture.calendarId, title = "Standup",
                startTs = day(2, 10), endTs = day(2, 10) + hour, timezone = "America/New_York",
                rrule = "FREQ=DAILY;COUNT=4", reminders = listOf("-PT15M"), alarmCount = 1, dtstamp = 0L,
            ),
            isLocal = true,
        )
        val key = day(3, 10)
        coordinator.editSingleOccurrence(series.id, key) { it.copy(title = "Renamed") }
        val fifteen = 15 * 60_000L
        assertTrue("${fixture.armedReminders().values}", (key - fifteen) in fixture.armedReminders().values)

        // The renamed occurrence moved to 14:00, as the form's edit of that occurrence saves it.
        coordinator.editSingleOccurrence(series.id, key) { it.copy(title = "Renamed", startTs = day(3, 14), endTs = day(3, 14) + hour) }

        val triggers = fixture.armedReminders().values
        assertTrue("$triggers", (day(3, 14) - fifteen) in triggers)
        assertFalse("the old alarm is gone: $triggers", (key - fifteen) in triggers)
        assertEquals("one alarm at the new time: $triggers", 1, triggers.count { it == day(3, 14) - fifteen })
    }
}
