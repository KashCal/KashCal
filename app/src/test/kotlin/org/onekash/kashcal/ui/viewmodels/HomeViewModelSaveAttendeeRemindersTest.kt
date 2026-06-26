package org.onekash.kashcal.ui.viewmodels

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus

/**
 * Tests that [HomeViewModel.saveAttendeeReminders] forwards a list of
 * minute integers to [EventCoordinator.saveAttendeeReminders] without
 * touching DAOs directly. The actual local-only DAO write contract is
 * tested at the writer level in `EventWriterAttendeeRemindersTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelSaveAttendeeRemindersTest {

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
        ioDispatcher = testDispatcher
    )

    @Test
    fun `saveAttendeeReminders forwards reminders to coordinator`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.saveAttendeeReminders(eventId = 42L, reminders = listOf(15, 30))
        advanceUntilIdle()

        // List<Int> minutes flows through; the coordinator/writer layer
        // is responsible for serializing to ISO-8601 List<String>.
        coVerify { eventCoordinator.saveAttendeeReminders(42L, listOf(15, 30)) }
    }

    @Test
    fun `saveAttendeeReminders accepts empty list (clear all reminders)`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.saveAttendeeReminders(eventId = 42L, reminders = emptyList())
        advanceUntilIdle()

        coVerify { eventCoordinator.saveAttendeeReminders(42L, emptyList()) }
    }

    // ===== form attendees thread through saveEvent → coordinator =====

    @Test
    fun `saveEvent forwards picked attendees as mapped entities`() = runTest {
        val attSlot = slot<List<org.onekash.kashcal.data.db.entity.Attendee>>()
        coEvery { eventCoordinator.getLocalCalendarId() } returns 1L
        coEvery { eventCoordinator.createEvent(any(), any(), capture(attSlot)) } answers {
            firstArg<org.onekash.kashcal.data.db.entity.Event>()
        }
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.saveEvent(
            org.onekash.kashcal.ui.components.EventFormState(
                title = "Planning",
                selectedCalendarId = 1L,
                attendees = listOf(
                    org.onekash.kashcal.data.db.entity.Attendee(
                        eventId = 0,
                        address = "mailto:bob@example.test",
                        displayName = "Bob",
                        partstat = "NEEDS-ACTION",
                        sortOrder = 0
                    )
                ),
                attendeesEdited = true
            )
        )
        advanceUntilIdle()

        assertTrue(attSlot.isCaptured)
        assertEquals(listOf("mailto:bob@example.test"), attSlot.captured.map { it.address })
        assertEquals("NEEDS-ACTION", attSlot.captured.first().partstat)
    }

    @Test
    fun `saveEvent passes null attendees when the form has none`() = runTest {
        coEvery { eventCoordinator.getLocalCalendarId() } returns 1L
        coEvery { eventCoordinator.createEvent(any(), any(), any()) } answers {
            firstArg<org.onekash.kashcal.data.db.entity.Event>()
        }
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.saveEvent(
            org.onekash.kashcal.ui.components.EventFormState(title = "Solo", selectedCalendarId = 1L)
        )
        advanceUntilIdle()

        // Empty form attendees → coordinator receives null (table left untouched),
        // never an empty list (which would clear).
        coVerify { eventCoordinator.createEvent(any(), any(), attendees = null) }
    }
}
