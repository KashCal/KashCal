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
import org.onekash.kashcal.data.calendar_provider.DeviceEvent
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
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
 * Tests for moving a DEVICE-calendar event to another device calendar while
 * editing it in the same save.
 *
 * Android's CalendarProvider treats CALENDAR_ID as effectively create-time
 * ("in general a calendar_id should not be modified after insertion"; sync
 * adapters misbehave if it changes), so a move must be delete-old +
 * insert-new — carrying the edited fields into the new event. Same-calendar
 * edits stay a plain in-place update.
 *
 * Scope: non-recurring device events. Recurring device events cannot be moved
 * (the calendar picker is disabled for them in the form), so a recurring edit
 * always stays an in-place update.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class HomeViewModelDeviceCalendarMoveTest {

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
        dataStore = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        repo = FakeCalendarProviderRepository()

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
        every { displayEventRepository.deviceCalendarChangeSignal } returns MutableStateFlow(0)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        repo.reset()
    }

    private fun createViewModel(): HomeViewModel = HomeViewModel(
        eventCoordinator = eventCoordinator,
        eventReader = eventReader,
        displayEventRepository = displayEventRepository,
        dataStore = dataStore,
        accountRepository = accountRepository,
        syncScheduler = syncScheduler,
        networkMonitor = networkMonitor,
        calendarProviderRepository = repo,
        attendeeBackfill = mockk(relaxed = true),
        contactEmailReader = mockk(relaxed = true),
        context = mockk(relaxed = true),
        ioDispatcher = testDispatcher,
    )

    private fun deviceEvent(
        id: Long,
        calendarId: Long,
        rrule: String? = null,
    ) = DeviceEvent(
        id = id,
        calendarId = calendarId,
        title = "Room 1",
        description = "Room 1 note",
        location = null,
        startTs = 1_000_000L,
        endTs = 1_003_600_000L,
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
        accessLevel = 0,
        calendarColor = null,
        eventColor = null,
    )

    // ---- non-recurring move: delete-old + insert-new with edited fields ----

    @Test
    fun `moving a non-recurring device event to another calendar recreates it in the target with edits`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Event currently lives in calendar 1.
        repo.deviceEvents[100L] = deviceEvent(id = 100L, calendarId = 1L)

        // User edits title/note AND picks calendar 2.
        val formState = EventFormState(
            title = "Room 2",
            description = "Room 2 note",
            selectedCalendarId = 2L,
            editingDeviceEventId = 100L,
            isDeviceCalendar = true,
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        // A new event was created in the TARGET calendar carrying the EDITED fields.
        assertEquals(1, repo.createdEvents.size)
        val created = repo.createdEvents[0]
        assertEquals(2L, created.calendarId)
        assertEquals("Room 2", created.title)
        assertEquals("Room 2 note", created.description)

        // The old event was deleted.
        assertTrue("old event must be deleted", repo.deletedEventIds.contains(100L))

        // NOT a plain in-place update (that would silently drop the move).
        assertTrue("move must not be a plain update", repo.updatedEvents.isEmpty())
    }

    @Test
    fun `non-recurring move creates in target before deleting source (no data loss on create failure)`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        repo.deviceEvents[100L] = deviceEvent(id = 100L, calendarId = 1L)
        // Force the create to fail.
        repo.failCreateOnCall = 1

        val formState = EventFormState(
            title = "Room 2",
            selectedCalendarId = 2L,
            editingDeviceEventId = 100L,
            isDeviceCalendar = true,
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        // CREATE failed → source must NOT be deleted (event survives in calendar 1).
        assertTrue("source must survive a failed create", repo.deletedEventIds.isEmpty())
    }

    @Test
    fun `unedited-attendees move carries the original guests into the recreated event`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        repo.deviceEvents[100L] = deviceEvent(id = 100L, calendarId = 1L)
        // Original event has a guest the user did not touch.
        repo.deviceAttendees[100L] = listOf(
            DeviceAttendee(id = 1L, name = "Guest", email = "guest@example.com", relationship = 1, status = 0)
        )

        val formState = EventFormState(
            title = "Room 2",
            selectedCalendarId = 2L,
            editingDeviceEventId = 100L,
            isDeviceCalendar = true,
            attendeesEdited = false, // user didn't touch guests
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        // The recreated event must keep the original guest — a move that dropped
        // it would silently uninvite everyone.
        assertEquals(1, repo.createdEvents.size)
        assertEquals(
            listOf("guest@example.com"),
            repo.createdEvents[0].attendees?.map { it.email },
        )
    }

    // ---- same-calendar edit stays a plain update (no move) ----

    @Test
    fun `editing a device event without changing calendar stays an in-place update`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        repo.deviceEvents[100L] = deviceEvent(id = 100L, calendarId = 1L)

        val formState = EventFormState(
            title = "Room 1 edited",
            selectedCalendarId = 1L, // same calendar
            editingDeviceEventId = 100L,
            isDeviceCalendar = true,
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        assertEquals(1, repo.updatedEvents.size)
        assertTrue("no recreate on same-calendar edit", repo.createdEvents.isEmpty())
        assertTrue("no delete on same-calendar edit", repo.deletedEventIds.isEmpty())
    }

    // ---- the source organizer must not become a guest on a cross-account move ----

    @Test
    fun `move strips the source organizer row so it is not recreated as a guest`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        repo.deviceEvents[100L] = deviceEvent(id = 100L, calendarId = 1L)
        // Source event's attendee set as the provider returns it: the organizer
        // row (RELATIONSHIP_ORGANIZER) plus a real guest.
        repo.deviceAttendees[100L] = listOf(
            DeviceAttendee(
                id = 1L, name = "Me", email = "me@personal.example",
                relationship = android.provider.CalendarContract.Attendees.RELATIONSHIP_ORGANIZER,
                status = android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED,
            ),
            DeviceAttendee(id = 2L, name = "Guest", email = "guest@example.com", relationship = 1, status = 0),
        )

        val formState = EventFormState(
            title = "Room 2",
            selectedCalendarId = 2L,
            editingDeviceEventId = 100L,
            isDeviceCalendar = true,
            attendeesEdited = false,
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        assertEquals(1, repo.createdEvents.size)
        val carried = repo.createdEvents[0].attendees?.map { it.email }.orEmpty()
        // The real guest carries over; the source organizer does NOT (createEvent
        // writes a fresh organizer for the target calendar's owner).
        assertTrue("guest carried", carried.contains("guest@example.com"))
        assertTrue(
            "source organizer must not be carried as a guest, was $carried",
            !carried.contains("me@personal.example"),
        )
    }

    // ---- a failed source-delete after a successful create is non-fatal ----

    @Test
    fun `move succeeds even when the source delete fails (event is safely in target)`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        repo.deviceEvents[100L] = deviceEvent(id = 100L, calendarId = 1L)
        repo.failDelete = true // create succeeds, delete fails

        val formState = EventFormState(
            title = "Room 2",
            selectedCalendarId = 2L,
            editingDeviceEventId = 100L,
            isDeviceCalendar = true,
        )

        val result = viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        // The target copy exists; a delete failure leaves a source orphan but must
        // NOT report the whole save as failed (a failure invites a duplicating retry).
        assertEquals(1, repo.createdEvents.size)
        assertTrue("save must succeed despite delete failure", result.isSuccess)
    }

    // ---- making a non-recurring event recurring WHILE moving still moves it ----

    @Test
    fun `adding recurrence while changing calendar still moves the event`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Source is currently non-recurring.
        repo.deviceEvents[100L] = deviceEvent(id = 100L, calendarId = 1L, rrule = null)

        // User makes it recurring AND picks a different calendar in one save.
        val formState = EventFormState(
            title = "Room 2",
            selectedCalendarId = 2L,
            editingDeviceEventId = 100L,
            isDeviceCalendar = true,
            rrule = "FREQ=DAILY",
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        // Must move (recreate in target + delete old), NOT silently drop the
        // calendar change via an in-place update.
        assertEquals("must recreate in target", 1, repo.createdEvents.size)
        assertEquals("recreated in target calendar", 2L, repo.createdEvents[0].calendarId)
        assertTrue("old event deleted", repo.deletedEventIds.contains(100L))
        assertTrue("not a plain in-place update", repo.updatedEvents.isEmpty())
    }

    // ---- recurring device event: never moved (defensive; picker is disabled) ----

    @Test
    fun `recurring device event edit stays in-place even if a different calendar is selected`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        repo.deviceEvents[100L] = deviceEvent(id = 100L, calendarId = 1L, rrule = "FREQ=DAILY")

        val formState = EventFormState(
            title = "Standup",
            selectedCalendarId = 2L, // different calendar, but recurring
            editingDeviceEventId = 100L,
            isDeviceCalendar = true,
            rrule = "FREQ=DAILY",
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        // Recurring move is out of scope: must NOT delete+recreate.
        assertTrue("recurring event must not be recreated", repo.createdEvents.isEmpty())
        assertTrue("recurring event must not be deleted", repo.deletedEventIds.isEmpty())
        assertEquals("recurring edit stays a plain update", 1, repo.updatedEvents.size)
    }
}
