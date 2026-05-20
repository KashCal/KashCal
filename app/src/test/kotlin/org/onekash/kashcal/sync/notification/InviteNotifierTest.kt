package org.onekash.kashcal.sync.notification

import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.reminder.notification.InviteNotificationManager

class InviteNotifierTest {

    private lateinit var attendeesDao: AttendeesDao
    private lateinit var notificationManager: InviteNotificationManager
    private lateinit var notifier: InviteNotifier

    private val account = Account(
        id = 1L,
        provider = AccountProvider.CALDAV,
        email = "self@example.test",
        calendarUserAddresses = listOf("mailto:self@example.test")
    )
    private val calendar = Calendar(
        id = 9L,
        accountId = account.id,
        caldavUrl = "https://server/cal/",
        displayName = "Work",
        color = -1
    )
    private val event = Event(
        id = 100L,
        uid = "evt-uid",
        calendarId = calendar.id,
        title = "Quarterly review",
        startTs = 1_700_000_000_000L,
        endTs = 1_700_003_600_000L,
        dtstamp = 1_700_000_000_000L,
        organizerEmail = "boss@example.test",
        organizerName = "Boss",
        syncStatus = SyncStatus.SYNCED
    )

    @Before
    fun setup() {
        attendeesDao = mockk(relaxed = true)
        notificationManager = mockk(relaxed = true)
        notifier = InviteNotifier(
            attendeesDao = attendeesDao,
            notificationManager = notificationManager
        )
    }

    @After
    fun tearDown() = clearAllMocks()

    private fun selfRow(notifiedAt: Long? = null, partstat: String = "NEEDS-ACTION") = Attendee(
        id = 50L,
        eventId = event.id,
        address = "mailto:self@example.test",
        partstat = partstat,
        notifiedAt = notifiedAt
    )
    private fun aliceRow() = Attendee(
        id = 51L,
        eventId = event.id,
        address = "mailto:alice@example.test",
        partstat = "ACCEPTED"
    )

    @Test
    fun `notifyNew fires for newly-arrived NEEDS-ACTION self row`() = runTest {
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(selfRow(), aliceRow())
        coEvery { attendeesDao.markNotified(50L, any()) } just Runs

        notifier.notifyNew(event, account)

        coVerify(exactly = 1) {
            notificationManager.showInvite(event = event, attendeeRowId = 50L, organizerLabel = "Boss")
        }
        coVerify { attendeesDao.markNotified(50L, any()) }
    }

    @Test
    fun `notifyNew falls back to organizer email when name is blank`() = runTest {
        val noNameEvent = event.copy(organizerName = null)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(selfRow())
        coEvery { attendeesDao.markNotified(any(), any()) } just Runs

        notifier.notifyNew(noNameEvent, account)

        coVerify {
            notificationManager.showInvite(
                event = noNameEvent,
                attendeeRowId = 50L,
                organizerLabel = "boss@example.test"
            )
        }
    }

    @Test
    fun `notifyNew skips when notified_at is already set (dedup)`() = runTest {
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(selfRow(notifiedAt = 999L))

        notifier.notifyNew(event, account)

        coVerify(exactly = 0) { notificationManager.showInvite(any(), any(), any()) }
        coVerify(exactly = 0) { attendeesDao.markNotified(any(), any()) }
    }

    @Test
    fun `notifyNew does not fire for ACCEPTED self row`() = runTest {
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(selfRow(partstat = "ACCEPTED"))

        notifier.notifyNew(event, account)

        coVerify(exactly = 0) { notificationManager.showInvite(any(), any(), any()) }
    }

    @Test
    fun `notifyNew does not fire for non-self attendee`() = runTest {
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(aliceRow())

        notifier.notifyNew(event, account)

        coVerify(exactly = 0) { notificationManager.showInvite(any(), any(), any()) }
    }

    @Test
    fun `notifyNew does not re-fire on stale-pull (dedup via merge-preserve)`() = runTest {
        // Simulates the race fix: user RSVPed ACCEPTED → optimistic write
        // set notified_at. Next pull race-returns NEEDS-ACTION; replaceForEvent's
        // merge-preserve kept notifiedAt non-null. notifier sees the row,
        // partstat=NEEDS-ACTION, but notified_at is set → no re-fire.
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(
            selfRow(notifiedAt = 1_500_000L, partstat = "NEEDS-ACTION")
        )

        notifier.notifyNew(event, account)

        coVerify(exactly = 0) { notificationManager.showInvite(any(), any(), any()) }
    }

    @Test
    fun `cancelForEvent forwards to notification manager for every row`() = runTest {
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(selfRow(), aliceRow())

        notifier.cancelForEvent(event.id)

        coVerify { notificationManager.cancelForEvent(event.id, 50L) }
        coVerify { notificationManager.cancelForEvent(event.id, 51L) }
    }

    @Test
    fun `notifyNew aborts cleanly when organizer fields are blank`() = runTest {
        val anonEvent = event.copy(organizerName = null, organizerEmail = null)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(selfRow())

        notifier.notifyNew(anonEvent, account)

        coVerify(exactly = 0) { notificationManager.showInvite(any(), any(), any()) }
    }
}
