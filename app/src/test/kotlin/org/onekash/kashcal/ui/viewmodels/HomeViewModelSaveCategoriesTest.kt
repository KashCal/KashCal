package org.onekash.kashcal.ui.viewmodels

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus
import org.onekash.kashcal.ui.components.EventFormState

/**
 * Verifies that tags entered in the event form are persisted on the create and
 * update save paths — the sibling-mapper risk where any of the five Event
 * build/copy branches in [HomeViewModel.saveEvent] could silently drop them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelSaveCategoriesTest {

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
        coEvery { eventCoordinator.getLocalCalendarId() } returns 1L
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = HomeViewModel(
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
    fun `create path persists categories`() = runTest(testDispatcher) {
        val captured = slot<Event>()
        coEvery { eventCoordinator.createEvent(capture(captured), any(), any()) } answers { captured.captured }

        val vm = createViewModel()
        vm.saveEvent(
            EventFormState(
                title = "Standup",
                selectedCalendarId = 1L,
                categories = listOf("work", "urgent"),
            )
        )

        assertEquals(listOf("work", "urgent"), captured.captured.categories)
    }

    @Test
    fun `create path with no categories persists null`() = runTest(testDispatcher) {
        val captured = slot<Event>()
        coEvery { eventCoordinator.createEvent(capture(captured), any(), any()) } answers { captured.captured }

        val vm = createViewModel()
        vm.saveEvent(EventFormState(title = "Standup", selectedCalendarId = 1L))

        assertEquals(null, captured.captured.categories)
    }

    @Test
    fun `same-calendar update path persists categories`() = runTest(testDispatcher) {
        val existing = Event(
            id = 42L,
            uid = "u42",
            calendarId = 1L,
            title = "Old",
            startTs = 1_000,
            endTs = 4_600_000,
            timezone = "UTC",
            dtstamp = 1_000,
        )
        coEvery { eventCoordinator.getEventById(42L) } returns existing
        val captured = slot<Event>()
        coEvery { eventCoordinator.updateEvent(capture(captured), any()) } answers { captured.captured }

        val vm = createViewModel()
        vm.saveEvent(
            EventFormState(
                title = "New",
                selectedCalendarId = 1L,
                editingEventId = 42L,
                isEditMode = true,
                categories = listOf("focus"),
            )
        )

        assertEquals(listOf("focus"), captured.captured.categories)
    }

    private fun master() = Event(
        id = 42L,
        uid = "u42",
        calendarId = 1L,
        title = "Master",
        startTs = 1_000,
        endTs = 4_600_000,
        timezone = "UTC",
        rrule = "FREQ=DAILY",
        dtstamp = 1_000,
    )

    @Test
    fun `single-occurrence exception path persists categories`() = runTest(testDispatcher) {
        coEvery { eventCoordinator.getEventById(42L) } returns master()
        // Capture the changes lambda and apply it to the master to see what gets written.
        val changesSlot = slot<(Event) -> Event>()
        coEvery {
            eventCoordinator.editSingleOccurrence(any(), any(), any(), capture(changesSlot))
        } answers { changesSlot.captured(master()) }

        val vm = createViewModel()
        vm.saveEvent(
            EventFormState(
                title = "Edited",
                selectedCalendarId = 1L,
                editingEventId = 42L,
                isEditMode = true,
                editingOccurrenceTs = 90_000_000L,
                categories = listOf("focus"),
            )
        )

        assertEquals(listOf("focus"), changesSlot.captured(master()).categories)
    }

    @Test
    fun `this-and-future split path persists categories`() = runTest(testDispatcher) {
        coEvery { eventCoordinator.getEventById(42L) } returns master()
        val changesSlot = slot<(Event) -> Event>()
        coEvery {
            eventCoordinator.editThisAndFuture(any(), any(), any(), capture(changesSlot))
        } answers { changesSlot.captured(master()) }

        val vm = createViewModel()
        vm.saveEvent(
            EventFormState(
                title = "Edited",
                selectedCalendarId = 1L,
                editingEventId = 42L,
                isEditMode = true,
                editingOccurrenceTs = 90_000_000L,
                categories = listOf("travel"),
            ),
            scope = EditScope.THIS_AND_FUTURE,
        )

        assertEquals(listOf("travel"), changesSlot.captured(master()).categories)
    }

    @Test
    fun `calendar-move update path persists categories`() = runTest(testDispatcher) {
        val existing = master().copy(rrule = null, calendarId = 1L)
        coEvery { eventCoordinator.getEventById(42L) } returns existing
        coEvery { eventCoordinator.moveEventToCalendar(42L, 2L) } returns Unit
        // After the move, saveEvent re-fetches by id then applies field changes.
        coEvery { eventCoordinator.getEventById(42L) } returnsMany listOf(existing, existing.copy(calendarId = 2L))
        val captured = slot<Event>()
        coEvery { eventCoordinator.updateEvent(capture(captured), any()) } answers { captured.captured }

        val vm = createViewModel()
        vm.saveEvent(
            EventFormState(
                title = "Moved",
                selectedCalendarId = 2L,
                editingEventId = 42L,
                isEditMode = true,
                categories = listOf("health"),
            )
        )

        assertEquals(listOf("health"), captured.captured.categories)
    }
}
