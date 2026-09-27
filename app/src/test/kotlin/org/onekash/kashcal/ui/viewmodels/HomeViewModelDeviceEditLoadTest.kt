package org.onekash.kashcal.ui.viewmodels

import android.provider.CalendarContract
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceAttendee
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.calendar_provider.DeviceEvent
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus
import org.onekash.kashcal.testutil.TestDataStoreFactory

/**
 * Tests loading a device event into the edit form through the real [HomeViewModel] over
 * [FakeCalendarProviderRepository].
 *
 * The form must open the row the user tapped: a modified occurrence opens its own
 * exception row with that row's reminders, everything else opens the series master.
 * The calendar supplies the header name, colour, the writable flag and the "you"
 * identity for the guest chips; a calendar with a blank owner marks no guest as you.
 * Nothing loads when the event, a listed exception's row or the calendar is missing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelDeviceEditLoadTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var eventCoordinator: EventCoordinator
    private lateinit var eventReader: EventReader
    private lateinit var displayEventRepository: DisplayEventRepository
    private lateinit var dataStore: KashCalDataStore
    private lateinit var accountRepository: AccountRepository
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var repo: FakeCalendarProviderRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        eventCoordinator = mockk(relaxed = true)
        eventReader = mockk(relaxed = true)
        displayEventRepository = mockk(relaxed = true)
        dataStore = TestDataStoreFactory.createStrictForHomeViewModel()
        accountRepository = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        repo = FakeCalendarProviderRepository()

        every { networkMonitor.isOnline } returns MutableStateFlow(true)
        every { networkMonitor.isMetered } returns MutableStateFlow(false)
        every { syncScheduler.observeImmediateSyncStatus() } returns MutableStateFlow(SyncStatus.Idle)
        every { syncScheduler.lastSyncChanges } returns MutableStateFlow(emptyList())
        every { syncScheduler.showBannerForSync } returns MutableStateFlow(false)
        every { eventCoordinator.getAllCalendars() } returns MutableStateFlow(emptyList())
        every { eventReader.getVisibleOccurrencesInRange(any(), any()) } returns MutableStateFlow(emptyList())
        every { eventReader.getVisibleOccurrencesForDay(any()) } returns MutableStateFlow(emptyList())
        every { eventReader.getVisibleOccurrencesWithEventsForDay(any()) } returns MutableStateFlow(emptyList())
        coEvery { accountRepository.getAccountsByProvider(any()) } returns emptyList()
        coEvery { accountRepository.hasCredentials(any()) } returns false
        every { displayEventRepository.deviceCalendarChangeSignal } returns MutableStateFlow(0)

        repo.calendars = listOf(calendar)
        repo.deviceEvents[MASTER_ID] = deviceEvent(MASTER_ID, rrule = "FREQ=WEEKLY", title = "Standup")
        repo.eventReminders[MASTER_ID] = listOf(10)
        repo.deviceAttendees[MASTER_ID] = listOf(
            DeviceAttendee(1L, "Owner", OWNER, CalendarContract.Attendees.RELATIONSHIP_ORGANIZER, 1),
            DeviceAttendee(2L, "Guest", "guest@example.test", CalendarContract.Attendees.RELATIONSHIP_ATTENDEE, 0),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HomeViewModel = HomeViewModel(
        eventCoordinator = eventCoordinator,
        eventReader = eventReader,
        displayEventRepository = displayEventRepository,
        dataStore = dataStore,
        accountRepository = accountRepository,
        syncScheduler = syncScheduler,
        networkMonitor = networkMonitor,
        deviceEventReader = repo.deviceEventReader(),
        deviceEventWriter = repo.deviceEventWriter(dataStore),
        attendeeBackfill = mockk(relaxed = true),
        contactEmailReader = mockk(relaxed = true),
        context = mockk(relaxed = true),
        ioDispatcher = testDispatcher,
    )

    @Test
    fun `opening a series loads the master with its reminders, calendar and guests`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val data = viewModel.getDeviceEventForEdit(MASTER_ID)

        assertNotNull(data)
        data!!
        assertEquals(MASTER_ID, data.event.id)
        assertEquals("Standup", data.event.title)
        assertEquals(listOf(10), data.reminders)
        assertEquals("Work", data.calendarName)
        assertEquals(CAL_COLOR, data.calendarColor)
        assertTrue(data.isWritable)
        assertEquals(2, data.attendees.size)
        val owner = data.attendees.single { it.bareAddress == OWNER }
        assertTrue("the calendar owner is marked as you", owner.isYou)
        assertFalse(data.attendees.single { it.bareAddress == "guest@example.test" }.isYou)
    }

    @Test
    fun `opening a modified occurrence loads the exception row and its own reminders`() = runTest {
        repo.deviceEvents[EXCEPTION_ID] = deviceEvent(EXCEPTION_ID, title = "Standup (moved)", originalId = MASTER_ID)
        repo.eventReminders[EXCEPTION_ID] = listOf(30)
        repo.exceptionEvents[MASTER_ID to OCCURRENCE_TS] = EXCEPTION_ID
        val viewModel = createViewModel()
        advanceUntilIdle()

        val data = viewModel.getDeviceEventForEdit(MASTER_ID, occurrenceTs = OCCURRENCE_TS)

        assertEquals(EXCEPTION_ID, data!!.event.id)
        assertEquals("Standup (moved)", data.event.title)
        assertEquals("exception reminders, not the master's", listOf(30), data.reminders)
    }

    @Test
    fun `opening an unmodified occurrence loads the master`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val data = viewModel.getDeviceEventForEdit(MASTER_ID, occurrenceTs = OCCURRENCE_TS)

        assertEquals(MASTER_ID, data!!.event.id)
        assertEquals(listOf(10), data.reminders)
    }

    @Test
    fun `a missing event loads nothing`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertNull(viewModel.getDeviceEventForEdit(999L))
    }

    @Test
    fun `an exception that is listed but has no row loads nothing`() = runTest {
        repo.exceptionEvents[MASTER_ID to OCCURRENCE_TS] = EXCEPTION_ID
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertNull(viewModel.getDeviceEventForEdit(MASTER_ID, occurrenceTs = OCCURRENCE_TS))
    }

    @Test
    fun `an event whose calendar is gone loads nothing`() = runTest {
        repo.calendars = emptyList()
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertNull(viewModel.getDeviceEventForEdit(MASTER_ID))
    }

    @Test
    fun `a calendar without an owner marks no guest as you`() = runTest {
        repo.calendars = listOf(calendar.copy(ownerAccount = ""))
        val viewModel = createViewModel()
        advanceUntilIdle()

        val data = viewModel.getDeviceEventForEdit(MASTER_ID)

        assertTrue(data!!.attendees.none { it.isYou })
    }

    private companion object {
        const val MASTER_ID = 10L
        const val EXCEPTION_ID = 11L
        const val CAL_ID = 5L
        const val CAL_COLOR = 0xFF112233.toInt()
        const val OWNER = "me@example.test"
        const val OCCURRENCE_TS = 1_700_000_000_000L

        val calendar = DeviceCalendar(
            id = CAL_ID,
            displayName = "Work",
            color = CAL_COLOR,
            accountName = OWNER,
            accountType = "LOCAL",
            visible = true,
            accessLevel = CalendarContract.Calendars.CAL_ACCESS_OWNER,
            ownerAccount = OWNER,
        )

        fun deviceEvent(
            id: Long,
            title: String,
            rrule: String? = null,
            originalId: Long? = null,
        ) = DeviceEvent(
            id = id,
            calendarId = CAL_ID,
            title = title,
            description = null,
            location = null,
            startTs = 1_699_999_000_000L,
            endTs = if (rrule == null) 1_700_002_600_000L else null,
            duration = if (rrule == null) null else "PT1H",
            isAllDay = false,
            rrule = rrule,
            rdate = null,
            exdate = null,
            exrule = null,
            timezone = "UTC",
            originalId = originalId,
            originalInstanceTime = if (originalId != null) OCCURRENCE_TS else null,
            status = CalendarContract.Events.STATUS_CONFIRMED,
            availability = 0,
            accessLevel = 0,
            calendarColor = null,
            eventColor = null,
        )
    }
}
