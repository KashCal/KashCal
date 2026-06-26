package org.onekash.kashcal.reminder.scheduler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.OccurrencesDao
import org.onekash.kashcal.data.db.dao.ScheduledRemindersDao
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.notification.ReminderNotificationChannels
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [ReminderScheduler.shouldFireReminder] — the fire-time guard that
 * decides whether a reminder alarm should still post a notification.
 *
 * Bug: a reminder alarm armed for a Room-backed event keeps firing after the
 * event is deleted (row gone) or soft-deleted (PENDING_DELETE, e.g. a CalDAV
 * delete not yet pushed, or a server-side delete pulled in the background).
 * The notification is built from data denormalized onto the reminder row, so
 * it posts "blind." This guard mirrors the device path's
 * `DeviceCalendarReminderScheduler.shouldFireReminder` and covers all
 * Room-backed providers at once (local, iCloud, CalDAV, ICS, birthdays,
 * anniversaries — they all share the events table).
 *
 * Robolectric application context: ReminderScheduler builds PendingIntents in
 * other methods; the context is required to construct it even though
 * shouldFireReminder itself only reads via EventReader.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReminderSchedulerFireGuardTest {

    private lateinit var context: Context
    private lateinit var scheduledRemindersDao: ScheduledRemindersDao
    private lateinit var eventReader: EventReader
    private lateinit var channels: ReminderNotificationChannels
    private lateinit var attendeesDao: AttendeesDao
    private lateinit var accountsDao: AccountsDao
    private lateinit var calendarsDao: CalendarsDao
    private lateinit var occurrencesDao: OccurrencesDao

    private val tomorrow = System.currentTimeMillis() + 24L * 60 * 60 * 1000

    @Before
    fun setup() {
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

    private fun event(id: Long, syncStatus: SyncStatus = SyncStatus.SYNCED): Event = Event(
        id = id,
        uid = "uid-$id",
        calendarId = 10L,
        title = "Event $id",
        startTs = tomorrow,
        endTs = tomorrow + 60_000,
        dtstamp = 0L,
        syncStatus = syncStatus
    )

    @Test
    fun `shouldFireReminder returns false when event no longer exists`() = runTest {
        coEvery { eventReader.getEventById(100L) } returns null

        assertFalse(newScheduler().shouldFireReminder(100L))
    }

    @Test
    fun `shouldFireReminder returns false when event is soft-deleted (PENDING_DELETE)`() = runTest {
        coEvery { eventReader.getEventById(100L) } returns event(100L, SyncStatus.PENDING_DELETE)

        assertFalse(newScheduler().shouldFireReminder(100L))
    }

    @Test
    fun `shouldFireReminder returns true for a live synced event`() = runTest {
        coEvery { eventReader.getEventById(100L) } returns event(100L, SyncStatus.SYNCED)

        assertTrue(newScheduler().shouldFireReminder(100L))
    }
}
