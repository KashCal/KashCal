package org.onekash.kashcal.ui.viewmodels

import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus
import org.onekash.kashcal.ui.components.EventFormState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guard tests for the device-event guest write path (add/remove).
 *
 * The load-bearing invariant: the device save path is disjoint from the
 * Room/iTIP save path. A device guest edit must bridge picker emails into
 * provider-shaped DeviceAttendee rows and reach
 * calendarProviderRepository.createEvent/updateEvent — while
 * eventCoordinator (the Room/CalDAV write path) is NEVER invoked on the device
 * branch. saveDeviceEvent and saveEvent are disjoint today; these tests lock
 * that in so a future merge of the two paths regresses loudly.
 *
 * Robolectric is required because pickerAttendeesToDevice references
 * CalendarContract.Attendees constants (stubbed to 0 under plain JVM).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class HomeViewModelDeviceAttendeeWriteTest {

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

    @Test
    fun `creating a device event bridges picker emails into DeviceAttendee rows`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Lunch",
            selectedCalendarId = 42L,
            attendees = listOf(Attendee(eventId = 0L, address = "mailto:alice@example.com", displayName = "Alice")),
            attendeesEdited = true,
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        assertEquals(1, fakeCalendarProviderRepository.createdEvents.size)
        val written = fakeCalendarProviderRepository.createdEvents[0].attendees
        assertEquals(listOf("alice@example.com"), written?.map { it.email })
    }

    @Test
    fun `device save never invokes the Room coordinator create or update`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Lunch",
            selectedCalendarId = 42L,
            attendees = listOf(Attendee(eventId = 0L, address = "alice@example.com")),
            attendeesEdited = true,
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        // The Room/CalDAV write path (and its attendees arg) must not be hit on
        // the device branch — that's the regression seam being locked in.
        coVerify(exactly = 0) { eventCoordinator.createEvent(any(), any(), attendees = any()) }
        coVerify(exactly = 0) { eventCoordinator.updateEvent(any(), attendees = any()) }
    }

    @Test
    fun `Room event save never writes attendees through the device provider`() = runTest {
        // The mirror of the device->Room guard above: a Room (non-device) save
        // with edited attendees must route through eventCoordinator only and
        // never touch the CalendarProvider write path — otherwise Room
        // attendees (with their iTIP wire fields) could leak onto a device
        // event, re-opening the CalDAV scheduling hazards on the wrong path.
        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Lunch",
            selectedCalendarId = 7L,
            // isDeviceCalendar defaults to false → Room save path.
            attendees = listOf(Attendee(eventId = 0L, address = "alice@example.com")),
            attendeesEdited = true,
        )

        viewModel.saveEvent(formState)
        advanceUntilIdle()

        assertTrue(
            "Room save must not create device events",
            fakeCalendarProviderRepository.createdEvents.isEmpty(),
        )
        assertTrue(
            "Room save must not update device events",
            fakeCalendarProviderRepository.updatedEvents.isEmpty(),
        )
    }

    @Test
    fun `updating a device event passes the edited guests to updateEvent`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Lunch",
            selectedCalendarId = 42L,
            editingDeviceEventId = 100L,
            attendees = listOf(Attendee(eventId = 0L, address = "bob@example.com")),
            attendeesEdited = true,
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        assertEquals(1, fakeCalendarProviderRepository.updatedEvents.size)
        assertEquals(listOf("bob@example.com"), fakeCalendarProviderRepository.updatedEvents[0].attendees?.map { it.email })
    }

    @Test
    fun `unedited device save passes null attendees so existing rows survive`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Lunch",
            selectedCalendarId = 42L,
            editingDeviceEventId = 100L,
            attendeesEdited = false,
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        assertEquals(1, fakeCalendarProviderRepository.updatedEvents.size)
        assertNull(fakeCalendarProviderRepository.updatedEvents[0].attendees)
    }

    @Test
    fun `per-occurrence device guest edit is not persisted as attendees`() = runTest {
        // Per spec, recurring single-occurrence guest edits are out of scope:
        // the occurrence branch routes to createException, which carries no
        // attendees arg — so a guest edit there is intentionally NOT written.
        val viewModel = createViewModel()
        advanceUntilIdle()

        fakeCalendarProviderRepository.deviceEvents[100L] = deviceEvent(id = 100L, rrule = "FREQ=DAILY")

        val formState = EventFormState(
            title = "Standup",
            selectedCalendarId = 42L,
            editingDeviceEventId = 100L,
            editingOccurrenceTs = 1709280000000L,
            attendees = listOf(Attendee(eventId = 0L, address = "carol@example.com")),
            attendeesEdited = true,
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        // Routed to exception creation, not a whole-event create/update.
        assertTrue("no whole-event create", fakeCalendarProviderRepository.createdEvents.isEmpty())
        assertTrue("no whole-event update", fakeCalendarProviderRepository.updatedEvents.isEmpty())
        assertEquals(1, fakeCalendarProviderRepository.createdExceptions.size)
    }

    private fun deviceEvent(
        id: Long,
        rrule: String? = null,
    ) = org.onekash.kashcal.data.calendar_provider.DeviceEvent(
        id = id,
        calendarId = 42L,
        title = "Event $id",
        description = null,
        location = null,
        startTs = 0L,
        endTs = 0L,
        duration = null,
        isAllDay = false,
        rrule = rrule,
        rdate = null,
        exdate = null,
        exrule = null,
        timezone = "UTC",
        originalId = null,
        originalInstanceTime = null,
        status = 1,
        availability = 0,
        accessLevel = 700,
        calendarColor = null,
        eventColor = null,
    )
}
