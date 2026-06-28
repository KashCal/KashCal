package org.onekash.kashcal.ui.viewmodels

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceAttendee
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus
import org.onekash.kashcal.ui.components.attendees.AttendeeStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for HomeViewModel.replyDeviceRsvp — the device-event self-RSVP write.
 *
 * The invariant: it updates ONLY the user's own attendee row (matched by the
 * calendar's owner email), found by its provider _ID, and no-ops when the user
 * has no self row. Robolectric is required: the status mapping and self-row
 * matching reference CalendarContract.Attendees constants.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class HomeViewModelDeviceRsvpTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var eventCoordinator: EventCoordinator
    private lateinit var eventReader: EventReader
    private lateinit var displayEventRepository: DisplayEventRepository
    private lateinit var dataStore: KashCalDataStore
    private lateinit var accountRepository: AccountRepository
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var fakeCalendarProviderRepository: FakeCalendarProviderRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        eventCoordinator = mockk(relaxed = true)
        eventReader = mockk(relaxed = true)
        displayEventRepository = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        fakeCalendarProviderRepository = FakeCalendarProviderRepository()

        every { networkMonitor.isOnline } returns MutableStateFlow(true)
        every { networkMonitor.isMetered } returns MutableStateFlow(false)
        every { syncScheduler.observeImmediateSyncStatus() } returns MutableStateFlow(SyncStatus.Idle)
        every { syncScheduler.lastSyncChanges } returns MutableStateFlow(emptyList())
        every { syncScheduler.showBannerForSync } returns MutableStateFlow(false)
        every { syncScheduler.setShowBannerForSync(any()) } answers {}
        every { syncScheduler.resetBannerFlag() } answers {}

        coEvery { dataStore.defaultCalendarId } returns MutableStateFlow(null)
        coEvery { dataStore.defaultReminderMinutes } returns MutableStateFlow(15)
        coEvery { dataStore.defaultAllDayReminder } returns MutableStateFlow(1440)
        coEvery { dataStore.defaultEventDuration } returns MutableStateFlow(20)
        coEvery { dataStore.timeFormat } returns MutableStateFlow("system")
        coEvery { dataStore.showEventEmojis } returns MutableStateFlow(true)
        coEvery { dataStore.onboardingDismissed } returns MutableStateFlow(true)

        every { eventCoordinator.getAllCalendars() } returns MutableStateFlow(emptyList())
        every { eventReader.getVisibleOccurrencesInRange(any(), any()) } returns MutableStateFlow(emptyList())
        every { eventReader.getVisibleOccurrencesForDay(any()) } returns MutableStateFlow(emptyList())
        every { eventReader.getVisibleOccurrencesWithEventsForDay(any()) } returns MutableStateFlow(emptyList())

        coEvery { accountRepository.getAccountsByProvider(any()) } returns emptyList()
        coEvery { accountRepository.hasCredentials(any()) } returns false

        every { displayEventRepository.deviceCalendarChangeSignal } returns MutableStateFlow(0)
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
        calendarProviderRepository = fakeCalendarProviderRepository,
        attendeeBackfill = mockk(relaxed = true),
        contactEmailReader = mockk(relaxed = true),
        context = mockk(relaxed = true),
        ioDispatcher = testDispatcher,
    )

    private fun deviceCalendar(id: Long, owner: String) = DeviceCalendar(
        id = id,
        displayName = "Cal",
        color = 0,
        accountName = "acct",
        accountType = "com.google",
        visible = true,
        accessLevel = 700,
        ownerAccount = owner,
    )

    @Test
    fun `replyDeviceRsvp updates only the user's own row by id`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        fakeCalendarProviderRepository.calendars = listOf(deviceCalendar(7L, "me@example.com"))
        fakeCalendarProviderRepository.deviceAttendees[55L] = listOf(
            DeviceAttendee(id = 1L, name = "Me", email = "me@example.com", relationship = 1, status = 0),
            DeviceAttendee(id = 2L, name = "Alice", email = "alice@example.com", relationship = 1, status = 0),
        )

        viewModel.replyDeviceRsvp(eventId = 55L, calendarId = 7L, status = AttendeeStatus.Accepted)
        advanceUntilIdle()

        assertEquals(1, fakeCalendarProviderRepository.selfRsvpUpdates.size)
        val update = fakeCalendarProviderRepository.selfRsvpUpdates[0]
        assertEquals(55L, update.eventId)
        assertEquals(1L, update.attendeeId) // the "me@" row, NOT alice's id=2
        assertEquals(android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED, update.status)
    }

    @Test
    fun `replyDeviceRsvp no-ops when the user has no self row`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        fakeCalendarProviderRepository.calendars = listOf(deviceCalendar(7L, "me@example.com"))
        // Only other guests; the owner isn't on the list.
        fakeCalendarProviderRepository.deviceAttendees[55L] = listOf(
            DeviceAttendee(id = 2L, name = "Alice", email = "alice@example.com", relationship = 1, status = 0),
        )

        viewModel.replyDeviceRsvp(eventId = 55L, calendarId = 7L, status = AttendeeStatus.Declined)
        advanceUntilIdle()

        assertTrue("no self row → no write", fakeCalendarProviderRepository.selfRsvpUpdates.isEmpty())
    }
}
