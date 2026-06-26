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
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.notification.ReminderNotificationChannels
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [ReminderScheduler.hasLiveOccurrenceForReminder] — the occurrence-level
 * fire-time guard.
 *
 * Bug: a reminder armed for one instance of a recurring series keeps firing after
 * that single instance is cancelled by a background CalDAV pull (organizer skips
 * "next Tuesday's standup"). The whole-event guard [ReminderScheduler.shouldFireReminder]
 * misses it because the master event is still live; the cancellation is
 * occurrence-level. Two representations both hit this:
 *  - EXDATE on the master: [org.onekash.kashcal.domain.generator.OccurrenceGenerator]
 *    deletes + reinserts the series' occurrence rows excluding the EXDATE'd one, so
 *    no row exists at that slot.
 *  - cancelled exception: the occurrence row remains with is_cancelled = 1.
 *
 * The trap this guard must avoid is OVER-suppression — silently dropping reminders
 * for valid events. The lookup key differs between exceptions and masters: a reminder
 * for a modified instance is keyed under the exception event's id, but the occurrence
 * row stores event_id = master and exception_event_id = exception. So the guard
 * branches on [Event.isException].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReminderSchedulerOccurrenceGuardTest {

    private lateinit var context: Context
    private lateinit var scheduledRemindersDao: ScheduledRemindersDao
    private lateinit var eventReader: EventReader
    private lateinit var channels: ReminderNotificationChannels
    private lateinit var attendeesDao: AttendeesDao
    private lateinit var accountsDao: AccountsDao
    private lateinit var calendarsDao: CalendarsDao
    private lateinit var occurrencesDao: OccurrencesDao

    private val occurrenceTime = System.currentTimeMillis() + 24L * 60 * 60 * 1000

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

    private fun event(
        id: Long,
        originalEventId: Long? = null,
        rrule: String? = null
    ): Event = Event(
        id = id,
        uid = "uid-$id",
        calendarId = 10L,
        title = "Event $id",
        startTs = occurrenceTime,
        endTs = occurrenceTime + 60_000,
        dtstamp = 0L,
        rrule = rrule,
        originalEventId = originalEventId,
        syncStatus = SyncStatus.SYNCED
    )

    private fun occurrence(
        eventId: Long,
        startTs: Long = occurrenceTime,
        isCancelled: Boolean = false,
        exceptionEventId: Long? = null
    ): Occurrence = Occurrence(
        eventId = eventId,
        calendarId = 10L,
        startTs = startTs,
        endTs = startTs + 60_000,
        startDay = 20250101,
        endDay = 20250101,
        isCancelled = isCancelled,
        exceptionEventId = exceptionEventId
    )

    private fun reminder(eventId: Long): ScheduledReminder = ScheduledReminder(
        id = 1L,
        eventId = eventId,
        occurrenceTime = occurrenceTime,
        triggerTime = occurrenceTime - 900_000,
        reminderOffset = "-PT15M",
        eventTitle = "Event $eventId",
        calendarColor = 0xFF0000,
        status = ReminderStatus.PENDING
    )

    // ==================== Still fires (must NOT over-suppress) ====================

    @Test
    fun `fires for a non-recurring event with a live occurrence at its slot`() = runTest {
        val masterId = 100L
        coEvery { eventReader.getEventById(masterId) } returns event(masterId)
        coEvery { occurrencesDao.getOccurrenceNearTime(masterId, occurrenceTime) } returns
            occurrence(eventId = masterId)

        assertTrue(newScheduler().hasLiveOccurrenceForReminder(reminder(masterId)))
    }

    @Test
    fun `fires for a timed recurring instance whose row start_ts drifts within tolerance`() = runTest {
        // The reminder's occurrenceTime can differ from the regenerated row's
        // start_ts by sub-second amounts after re-expansion. The DAO's 60s
        // tolerance bridges it; the guard must honor whatever the DAO returns.
        val masterId = 200L
        coEvery { eventReader.getEventById(masterId) } returns event(masterId, rrule = "FREQ=WEEKLY")
        coEvery { occurrencesDao.getOccurrenceNearTime(masterId, occurrenceTime) } returns
            occurrence(eventId = masterId, startTs = occurrenceTime - 500)

        assertTrue(newScheduler().hasLiveOccurrenceForReminder(reminder(masterId)))
    }

    @Test
    fun `fires for an all-day event with a live occurrence at its slot`() = runTest {
        val masterId = 250L
        coEvery { eventReader.getEventById(masterId) } returns event(masterId, rrule = "FREQ=DAILY")
        coEvery { occurrencesDao.getOccurrenceNearTime(masterId, occurrenceTime) } returns
            occurrence(eventId = masterId)

        assertTrue(newScheduler().hasLiveOccurrenceForReminder(reminder(masterId)))
    }

    @Test
    fun `fires for an edited recurring instance keyed under the exception event id`() = runTest {
        // MAIN TRAP: reminder.eventId is the exception event id, but the occurrence
        // row stores event_id = master / exception_event_id = exception. Looking up
        // by getOccurrenceNearTime(exceptionId, ...) returns null and would wrongly
        // suppress. The guard must branch to getByExceptionEventId.
        val masterId = 300L
        val exceptionId = 301L
        coEvery { eventReader.getEventById(exceptionId) } returns
            event(exceptionId, originalEventId = masterId)
        coEvery { occurrencesDao.getByExceptionEventId(exceptionId) } returns
            occurrence(eventId = masterId, exceptionEventId = exceptionId)
        // A naive lookup keyed on the exception id finds nothing — proves the branch.
        coEvery { occurrencesDao.getOccurrenceNearTime(exceptionId, any()) } returns null

        assertTrue(newScheduler().hasLiveOccurrenceForReminder(reminder(exceptionId)))
    }

    // ==================== Suppressed (the fix) ====================

    @Test
    fun `suppresses an EXDATE'd instance of a live series (no row at the slot)`() = runTest {
        val masterId = 400L
        coEvery { eventReader.getEventById(masterId) } returns event(masterId, rrule = "FREQ=WEEKLY")
        coEvery { occurrencesDao.getOccurrenceNearTime(masterId, occurrenceTime) } returns null

        assertFalse(newScheduler().hasLiveOccurrenceForReminder(reminder(masterId)))
    }

    @Test
    fun `suppresses a cancelled-exception instance (row is_cancelled = 1)`() = runTest {
        val masterId = 500L
        val exceptionId = 501L
        coEvery { eventReader.getEventById(exceptionId) } returns
            event(exceptionId, originalEventId = masterId)
        coEvery { occurrencesDao.getByExceptionEventId(exceptionId) } returns
            occurrence(eventId = masterId, isCancelled = true, exceptionEventId = exceptionId)

        assertFalse(newScheduler().hasLiveOccurrenceForReminder(reminder(exceptionId)))
    }

    @Test
    fun `suppresses an EXDATE'd instance whose master row is cancelled in place`() = runTest {
        // Local single-occurrence delete marks the master's row is_cancelled = 1
        // rather than removing it. Still a suppression.
        val masterId = 600L
        coEvery { eventReader.getEventById(masterId) } returns event(masterId, rrule = "FREQ=WEEKLY")
        coEvery { occurrencesDao.getOccurrenceNearTime(masterId, occurrenceTime) } returns
            occurrence(eventId = masterId, isCancelled = true)

        assertFalse(newScheduler().hasLiveOccurrenceForReminder(reminder(masterId)))
    }

    // ==================== Defensive ====================

    @Test
    fun `suppresses safely when the event lookup returns null`() = runTest {
        // Event deleted between the whole-event guard and this one, or a stale
        // eventId. Must not throw; treat as no live occurrence.
        val masterId = 700L
        coEvery { eventReader.getEventById(masterId) } returns null

        assertFalse(newScheduler().hasLiveOccurrenceForReminder(reminder(masterId)))
    }
}
