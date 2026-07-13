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
 * Verifies [ReminderScheduler.scheduleUpcomingReminders] suppresses
 * self-declined events before arming alarms (outcome 4: daily refresh
 * worker scan must not arm alarms for declined events).
 *
 * Multi-account isolation and lookup-miss fail-open are exercised by the
 * shared `selfDeclinedEventIds` helper's test
 * (`SelfDeclinedDetectorTest`); this class verifies the scheduler's
 * wiring around it.
 *
 * Uses Robolectric for the application context — ReminderScheduler builds a
 * PendingIntent in `scheduleAlarm`, which is a no-op stub on the JVM but
 * works correctly under Robolectric's shadowed PendingIntent.
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
        // Real Robolectric application context so PendingIntent/AlarmManager
        // resolve through their shadows. AlarmManager calls become no-ops.
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

        // Filter dependencies must NOT be touched when there are no events
        // (perf preservation — existing behavior).
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

        // No reminder insert should occur for the filtered-out event.
        coVerify(exactly = 0) { scheduledRemindersDao.insert(any()) }
    }

    @Test
    fun `accepted event still gets its reminder scheduled`() = runTest {
        val acct = account(1L)
        val cal = calendar(10L, accountId = 1L)
        val acceptedRow = row(eventId = 200L, calendarId = 10L)

        coEvery { eventReader.getEventsWithRemindersInRange(any(), any()) } returns listOf(acceptedRow)
        // No DECLINED rows for event 200 — user accepted.
        coEvery { attendeesDao.getDeclinedAttendeesForEvents(listOf(200L)) } returns emptyList()
        coEvery { accountsDao.getAllOnce() } returns listOf(acct)
        coEvery { calendarsDao.getAllOnce() } returns listOf(cal)
        coEvery { scheduledRemindersDao.findExisting(any(), any(), any()) } returns null
        coEvery { scheduledRemindersDao.insert(any()) } returns 1L

        newScheduler().scheduleUpcomingReminders()

        // Insert path runs because filter kept the row.
        coVerify(atLeast = 1) { scheduledRemindersDao.insert(any()) }
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
        coEvery { scheduledRemindersDao.insert(any()) } returns 1L

        newScheduler().scheduleUpcomingReminders()

        // Exactly one reminder inserted — for the accepted event 200.
        // (one offset "-PT15M" × one occurrence = 1 insert)
        coVerify(exactly = 1) { scheduledRemindersDao.insert(match { it.eventId == 200L }) }
        coVerify(exactly = 0) { scheduledRemindersDao.insert(match { it.eventId == 100L }) }
    }
}
