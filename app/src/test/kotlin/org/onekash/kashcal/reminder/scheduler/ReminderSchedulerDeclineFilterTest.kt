package org.onekash.kashcal.reminder.scheduler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventWithOccurrenceAndColor
import org.onekash.kashcal.data.db.dao.OccurrencesDao
import org.onekash.kashcal.data.db.dao.ScheduledRemindersDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.notification.ReminderNotificationChannels

/**
 * Tests that [ReminderScheduler.scheduleUpcomingReminders], the refresh scan, arms no reminder
 * for an event the user declined, and still arms the accepted ones.
 *
 * Multi-account isolation and a lookup miss leaving the event's reminders armed are tested on
 * the `selfDeclinedEventIds` helper the scheduler shares with the display path
 * (`SelfDeclinedDetectorTest`); this class tests the scheduler's wiring around it, including
 * that an empty scan makes none of the filter's lookups.
 *
 * Runs under Robolectric so the PendingIntent and AlarmManager that `scheduleAlarm` uses resolve
 * through their shadows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReminderSchedulerDeclineFilterTest {

    private lateinit var context: Context
    private lateinit var scheduledRemindersDao: ScheduledRemindersDao
    private lateinit var eventReader: EventReader
    private lateinit var channels: ReminderNotificationChannels
    private lateinit var attendeesDao: AttendeesDao
    private lateinit var accountsDao: AccountsDao
    private lateinit var calendarsDao: CalendarsDao
    private lateinit var occurrencesDao: OccurrencesDao

    private val now = System.currentTimeMillis()
    private val tomorrow = now + 24L * 60 * 60 * 1000

    @Before
    fun setup() {
        // The Robolectric application context, so PendingIntent and AlarmManager resolve
        // through their shadows and no alarm fires.
        context = ApplicationProvider.getApplicationContext()

        scheduledRemindersDao = mockk(relaxed = true)
        eventReader = mockk(relaxed = true)
        channels = mockk(relaxed = true)
        attendeesDao = mockk(relaxed = true)
        accountsDao = mockk(relaxed = true)
        calendarsDao = mockk(relaxed = true)
        occurrencesDao = mockk(relaxed = true)
    }

    private fun newScheduler(): ReminderScheduler = ReminderScheduler(
        context = context,
        scheduledRemindersDao = scheduledRemindersDao,
        eventReader = eventReader,
        channels = channels,
        attendeesDao = attendeesDao,
        accountsDao = accountsDao,
        calendarsDao = calendarsDao,
        occurrencesDao = occurrencesDao
    )

    private fun account(
        id: Long,
        addresses: List<String> = listOf("mailto:alice@icloud.com")
    ): Account = Account(
        id = id,
        provider = AccountProvider.ICLOUD,
        email = "alice@icloud.com",
        calendarUserAddresses = addresses
    )

    private fun calendar(id: Long, accountId: Long): Calendar = Calendar(
        id = id,
        accountId = accountId,
        caldavUrl = "https://example.test/cal$id/",
        displayName = "Cal $id",
        color = 0
    )

    private fun event(id: Long, calendarId: Long, reminders: List<String> = listOf("-PT15M")): Event = Event(
        id = id,
        uid = "uid-$id",
        calendarId = calendarId,
        title = "Event $id",
        startTs = tomorrow,
        endTs = tomorrow + 60_000,
        dtstamp = 0L,
        reminders = reminders
    )

    private fun row(eventId: Long, calendarId: Long): EventWithOccurrenceAndColor =
        EventWithOccurrenceAndColor(
            event = event(eventId, calendarId),
            occurrenceStartTs = tomorrow,
            occurrenceEndTs = tomorrow + 60_000,
            calendarColor = 0,
            targetEventId = eventId
        )

    @Test
    fun `empty events-with-reminders short-circuits before DAO calls`() = runTest {
        coEvery { eventReader.getEventsWithRemindersInRange(any(), any()) } returns emptyList()

        newScheduler().scheduleUpcomingReminders()

        // With no event in range, the filter's lookups aren't made.
        coVerify(exactly = 0) { attendeesDao.getDeclinedAttendeesForEvents(any()) }
        coVerify(exactly = 0) { accountsDao.getAllOnce() }
        coVerify(exactly = 0) { calendarsDao.getAllOnce() }
    }

    @Test
    fun `declined event is filtered out before scheduling`() = runTest {
        val acct = account(1L)
        val cal = calendar(10L, accountId = 1L)
        val declinedRow = row(eventId = 100L, calendarId = 10L)

        coEvery { eventReader.getEventsWithRemindersInRange(any(), any()) } returns listOf(declinedRow)
        coEvery { attendeesDao.getDeclinedAttendeesForEvents(listOf(100L)) } returns listOf(
            Attendee(id = 0, eventId = 100L, address = "mailto:alice@icloud.com", partstat = "DECLINED")
        )
        coEvery { accountsDao.getAllOnce() } returns listOf(acct)
        coEvery { calendarsDao.getAllOnce() } returns listOf(cal)

        newScheduler().scheduleUpcomingReminders()

        // No reminder row for the declined event.
        coVerify(exactly = 0) { scheduledRemindersDao.insertIfAbsent(any()) }
    }

    @Test
    fun `accepted event still gets its reminder scheduled`() = runTest {
        val acct = account(1L)
        val cal = calendar(10L, accountId = 1L)
        val acceptedRow = row(eventId = 200L, calendarId = 10L)

        coEvery { eventReader.getEventsWithRemindersInRange(any(), any()) } returns listOf(acceptedRow)
        // No DECLINED rows for event 200: the user accepted.
        coEvery { attendeesDao.getDeclinedAttendeesForEvents(listOf(200L)) } returns emptyList()
        coEvery { accountsDao.getAllOnce() } returns listOf(acct)
        coEvery { calendarsDao.getAllOnce() } returns listOf(cal)
        coEvery { scheduledRemindersDao.findExisting(any(), any(), any()) } returns null
        coEvery { scheduledRemindersDao.insertIfAbsent(any()) } returns 1L

        newScheduler().scheduleUpcomingReminders()

        // The filter kept the row, so its reminder is inserted.
        coVerify(atLeast = 1) { scheduledRemindersDao.insertIfAbsent(any()) }
    }

    @Test
    fun `declined plus accepted - only accepted scheduled`() = runTest {
        val acct = account(1L)
        val cal = calendar(10L, accountId = 1L)
        val rows = listOf(
            row(eventId = 100L, calendarId = 10L), // declined
            row(eventId = 200L, calendarId = 10L)  // accepted
        )

        coEvery { eventReader.getEventsWithRemindersInRange(any(), any()) } returns rows
        coEvery { attendeesDao.getDeclinedAttendeesForEvents(listOf(100L, 200L)) } returns listOf(
            Attendee(id = 0, eventId = 100L, address = "mailto:alice@icloud.com", partstat = "DECLINED")
        )
        coEvery { accountsDao.getAllOnce() } returns listOf(acct)
        coEvery { calendarsDao.getAllOnce() } returns listOf(cal)
        coEvery { scheduledRemindersDao.findExisting(any(), any(), any()) } returns null
        coEvery { scheduledRemindersDao.insertIfAbsent(any()) } returns 1L

        newScheduler().scheduleUpcomingReminders()

        // One reminder row, for the accepted event 200: one offset ("-PT15M") on one
        // occurrence.
        coVerify(exactly = 1) { scheduledRemindersDao.insertIfAbsent(match { it.eventId == 200L }) }
        coVerify(exactly = 0) { scheduledRemindersDao.insertIfAbsent(match { it.eventId == 100L }) }
    }
}
