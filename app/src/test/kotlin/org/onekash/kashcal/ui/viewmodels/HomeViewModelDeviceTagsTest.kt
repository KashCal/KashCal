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
import org.onekash.kashcal.ui.components.EventFormState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the device-event tag write path through [HomeViewModel.saveDeviceEvent]
 * over [FakeCalendarProviderRepository].
 *
 * Two rules:
 *  1. A create stores the form's tags. An update writes them only when the user
 *     edited the tag row (an edited empty set clears the row) and passes null
 *     otherwise, leaving the stored row alone. A single-occurrence edit and an
 *     unedited this-and-future split pass no tags, so the new row copies the
 *     series' tags, and a cross-calendar move carries the source's tags, or the
 *     edited set.
 *  2. A successful save records the stored tags, cleaned the way the repository
 *     stores them, in the shared registry via eventCoordinator.recordTagUsage,
 *     so new names gain a suggestion entry and become colorable, as on the Room
 *     save path. A save that stores no tags records nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class HomeViewModelDeviceTagsTest {

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
        deviceEventReader = fakeCalendarProviderRepository.deviceEventReader(),
        deviceEventWriter = fakeCalendarProviderRepository.deviceEventWriter(dataStore),
        attendeeBackfill = mockk(relaxed = true),
        contactEmailReader = mockk(relaxed = true),
        context = mockk(relaxed = true),
        ioDispatcher = testDispatcher,
    )

    @Test
    fun `creating a device event threads the typed tags into createEvent`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Lunch",
            selectedCalendarId = 42L,
            categories = listOf("Work", "Errand"),
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        assertEquals(1, fakeCalendarProviderRepository.createdEvents.size)
        assertEquals(listOf("Work", "Errand"), fakeCalendarProviderRepository.createdEvents[0].categories)
    }

    @Test
    fun `whole-event update threads the edited tags into updateEvent`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Lunch",
            selectedCalendarId = 42L,
            editingDeviceEventId = 100L,
            categories = listOf("Work"),
            categoriesEdited = true,
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        assertEquals(1, fakeCalendarProviderRepository.updatedEvents.size)
        assertEquals(listOf("Work"), fakeCalendarProviderRepository.updatedEvents[0].categories)
    }

    @Test
    fun `an open-and-save that never touched the tag row leaves the stored row untouched`() = runTest {
        // The form seeds categories from the loaded event but keeps
        // categoriesEdited = false until the user changes the tag set. An edit of
        // another field must pass categories = null so writeCategories leaves the
        // row alone; otherwise a load-time read race, or tags a sync adapter added
        // since load, would be silently clobbered.
        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Lunch (time changed)",
            selectedCalendarId = 42L,
            editingDeviceEventId = 100L,
            categories = listOf("Work"), // seeded from disk, not user-edited
            categoriesEdited = false,
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        assertEquals(1, fakeCalendarProviderRepository.updatedEvents.size)
        assertNull(
            "an unedited tag row must not rewrite the stored categories",
            fakeCalendarProviderRepository.updatedEvents[0].categories,
        )
        coVerify(exactly = 0) { eventCoordinator.recordTagUsage(any()) }
    }

    @Test
    fun `a successful create reconciles the applied tags into the shared registry`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Lunch",
            selectedCalendarId = 42L,
            categories = listOf("Work", "Errand"),
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        coVerify(exactly = 1) { eventCoordinator.recordTagUsage(listOf("Work", "Errand")) }
    }

    @Test
    fun `the registry records the cleaned names the provider actually stores`() = runTest {
        // The repository's encodeCategories strips backslashes, trims, and
        // collapses case-insensitive duplicates before storing. The registry must
        // record the same cleaned names, or a suggestion would resolve to a tag
        // that never persisted. The fixtures need cleaning, so recording the raw
        // form values fails the test; clean fixtures would make it tautological.
        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Lunch",
            selectedCalendarId = 42L,
            // "a\b" becomes "ab" (backslash stripped), "  Work " becomes "Work"
            // (trimmed), and "WORK" collapses into "Work" (case-insensitive duplicate).
            categories = listOf("a\\b", "  Work ", "WORK"),
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        coVerify(exactly = 1) { eventCoordinator.recordTagUsage(listOf("ab", "Work")) }
        // Never the raw form values.
        coVerify(exactly = 0) { eventCoordinator.recordTagUsage(listOf("a\\b", "  Work ", "WORK")) }
    }

    @Test
    fun `clearing all tags on an existing event threads a non-null empty list and skips the registry`() = runTest {
        // Removing every tag is an edited empty set (categoriesEdited = true), which
        // must reach updateEvent as a non-null empty list so writeCategories
        // deletes the stored row instead of leaving a stale value; the unedited
        // open-and-save passes null. The registry records nothing: an empty set
        // has no name.
        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Lunch",
            selectedCalendarId = 42L,
            editingDeviceEventId = 100L,
            categories = emptyList(),
            categoriesEdited = true,
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        assertEquals(1, fakeCalendarProviderRepository.updatedEvents.size)
        assertEquals(
            "clearing tags must send a non-null empty list so the stored row is deleted",
            emptyList<String>(),
            fakeCalendarProviderRepository.updatedEvents[0].categories,
        )
        coVerify(exactly = 0) { eventCoordinator.recordTagUsage(any()) }
    }

    @Test
    fun `a create with no tags does not touch the registry`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Lunch",
            selectedCalendarId = 42L,
            categories = emptyList(),
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        // An empty tag set is a no-op for the registry.
        coVerify(exactly = 0) { eventCoordinator.recordTagUsage(any()) }
    }

    @Test
    fun `a per-occurrence exception edit leaves tags untouched`() = runTest {
        // The occurrence branch goes to DeviceEventWriter.editSingleOccurrence,
        // whose createException takes no tag list (a new exception copies the
        // series' tags), so no tag row is rewritten and nothing is recorded in
        // the registry.
        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Standup",
            selectedCalendarId = 42L,
            editingDeviceEventId = 100L,
            editingOccurrenceTs = 1709280000000L,
            categories = listOf("Work"),
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        assertTrue("no whole-event create", fakeCalendarProviderRepository.createdEvents.isEmpty())
        assertTrue("no whole-event update", fakeCalendarProviderRepository.updatedEvents.isEmpty())
        assertEquals(1, fakeCalendarProviderRepository.createdExceptions.size)
        coVerify(exactly = 0) { eventCoordinator.recordTagUsage(any()) }
    }

    @Test
    fun `this-and-future edit without edited tags records no tag usage`() = runTest {
        // The tags weren't edited, so the split keeps the series' own tags
        // (categories null) and the recent-tags registry is not touched.
        val viewModel = createViewModel()
        advanceUntilIdle()

        val formState = EventFormState(
            title = "Standup",
            selectedCalendarId = 42L,
            editingDeviceEventId = 100L,
            editingOccurrenceTs = 1709280000000L,
            categories = listOf("Work"),
        )

        viewModel.saveDeviceEvent(formState, scope = EditScope.THIS_AND_FUTURE)
        advanceUntilIdle()

        assertTrue("no whole-event create", fakeCalendarProviderRepository.createdEvents.isEmpty())
        assertTrue("no whole-event update", fakeCalendarProviderRepository.updatedEvents.isEmpty())
        assertNull(fakeCalendarProviderRepository.editedFutureSeries.single().categories)
        coVerify(exactly = 0) { eventCoordinator.recordTagUsage(any()) }
    }

    @Test
    fun `a cross-calendar move carries the source event's existing tags to the target`() = runTest {
        // A move creates the event in the target and deletes the source, so the
        // new row must carry the source's tags, as it carries guests, or they'd be
        // lost. The user didn't touch the tag row, so the writer reads the set
        // from the stored source event.
        val viewModel = createViewModel()
        advanceUntilIdle()

        fakeCalendarProviderRepository.deviceEvents[100L] =
            deviceEvent(id = 100L, calendarId = 7L, categories = listOf("Work", "Errand"))

        val formState = EventFormState(
            title = "Lunch",
            selectedCalendarId = 42L, // differs from source calendar 7, so a move
            editingDeviceEventId = 100L,
            categories = listOf("Work", "Errand"), // seeded from disk, not edited
            categoriesEdited = false,
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        assertEquals(1, fakeCalendarProviderRepository.createdEvents.size)
        assertEquals(
            "move recreate must carry the source tags so they survive the move",
            listOf("Work", "Errand"),
            fakeCalendarProviderRepository.createdEvents[0].categories,
        )
        coVerify(exactly = 1) { eventCoordinator.recordTagUsage(listOf("Work", "Errand")) }
    }

    @Test
    fun `a cross-calendar move with edited tags carries the edited set`() = runTest {
        // When the user edits the tag row during a move, the edited set wins over
        // the source's stored tags, as edited guests do.
        val viewModel = createViewModel()
        advanceUntilIdle()

        fakeCalendarProviderRepository.deviceEvents[100L] =
            deviceEvent(id = 100L, calendarId = 7L, categories = listOf("Work"))

        val formState = EventFormState(
            title = "Lunch",
            selectedCalendarId = 42L, // move
            editingDeviceEventId = 100L,
            categories = listOf("Work", "Personal"),
            categoriesEdited = true,
        )

        viewModel.saveDeviceEvent(formState)
        advanceUntilIdle()

        assertEquals(1, fakeCalendarProviderRepository.createdEvents.size)
        assertEquals(
            listOf("Work", "Personal"),
            fakeCalendarProviderRepository.createdEvents[0].categories,
        )
        coVerify(exactly = 1) { eventCoordinator.recordTagUsage(listOf("Work", "Personal")) }
    }

    private fun deviceEvent(
        id: Long,
        calendarId: Long,
        categories: List<String> = emptyList(),
    ) = org.onekash.kashcal.data.calendar_provider.DeviceEvent(
        id = id,
        calendarId = calendarId,
        title = "Event $id",
        description = null,
        location = null,
        startTs = 0L,
        endTs = 0L,
        duration = null,
        isAllDay = false,
        rrule = null,
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
        categories = categories,
    )
}
