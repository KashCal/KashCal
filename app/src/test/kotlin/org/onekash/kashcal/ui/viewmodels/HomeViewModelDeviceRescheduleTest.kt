package org.onekash.kashcal.ui.viewmodels

import android.content.Context
import android.provider.CalendarContract
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.R
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.error.CalendarError
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus
import java.time.LocalDate
import java.util.TimeZone

/**
 * Tests drag-to-reschedule of device-calendar (CalendarProvider) events through
 * the public rescheduleEvent and confirmReschedule surface, over
 * [FakeCalendarProviderRepository]:
 * - the drag keeps colour and availability on a one-off, a modified
 *   occurrence, a new exception and a split, and invents no colour;
 * - an empty stored RRULE gets an end time and no duration;
 * - a failed or permission-revoked write shows the failure snackbar, and a
 *   successful device or Room drag the success one;
 * - an all-events drag of a series is refused without writing, while a
 *   one-off or modified occurrence still moves;
 * - both snackbars come from string resources, without exception details.
 *
 * The provider update writes AVAILABILITY and EVENT_COLOR on every call, so a
 * drag must carry the instance's current values or it silently resets the
 * event to busy and strips its colour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelDeviceRescheduleTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var eventCoordinator: EventCoordinator
    private lateinit var eventReader: EventReader
    private lateinit var displayEventRepository: DisplayEventRepository
    private lateinit var dataStore: KashCalDataStore
    private lateinit var accountRepository: AccountRepository
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var context: Context

    private lateinit var fakeCalendarProviderRepository: FakeCalendarProviderRepository

    private val targetDate = LocalDate.of(2024, 3, 5)
    private val targetStartMinutes = 600

    private val occurrenceStartTs = 1_709_650_800_000L // 2024-03-05T15:00:00Z
    private val oneHourMs = 3_600_000L
    private val oneWeekMs = 7 * 24 * oneHourMs

    private val colour = 0xFF112233.toInt()
    private val availabilityFree = CalendarContract.Events.AVAILABILITY_FREE
    private val availabilityTentative = CalendarContract.Events.AVAILABILITY_TENTATIVE

    // Sentinels only a resource lookup can produce, so a test can tell the success
    // snackbar from the failure one and catch a regression to hardcoded text.
    private val rescheduledMessage = "res:snackbar_event_rescheduled"
    private val rescheduleFailedMessage = "res:snackbar_reschedule_failed"

    private lateinit var savedZone: TimeZone

    @Before
    fun setup() {
        // Pins the device zone to the event's zone, so the drops at 10:00 on the
        // occurrence's own day stay same-day drops whatever zone the host runs in.
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        Dispatchers.setMain(testDispatcher)

        eventCoordinator = mockk(relaxed = true)
        eventReader = mockk(relaxed = true)
        displayEventRepository = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.getString(R.string.snackbar_event_rescheduled) } returns rescheduledMessage
        every { context.getString(R.string.snackbar_reschedule_failed) } returns rescheduleFailedMessage

        fakeCalendarProviderRepository = FakeCalendarProviderRepository()

        every { networkMonitor.isOnline } returns MutableStateFlow(true)
        every { networkMonitor.isMetered } returns MutableStateFlow(false)

        every { syncScheduler.observeImmediateSyncStatus() } returns MutableStateFlow(SyncStatus.Idle)
        every { syncScheduler.lastSyncChanges } returns MutableStateFlow(emptyList())
        val bannerFlagFlow = MutableStateFlow(false)
        every { syncScheduler.showBannerForSync } returns bannerFlagFlow
        every { syncScheduler.setShowBannerForSync(any()) } answers { bannerFlagFlow.value = firstArg() }
        every { syncScheduler.resetBannerFlag() } answers { bannerFlagFlow.value = false }

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
        TimeZone.setDefault(savedZone)
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
        context = context,
        ioDispatcher = testDispatcher
    )

    private fun deviceInstance(
        eventId: Long = 100L,
        hasRrule: Boolean = false,
        rrule: String? = null,
        originalId: Long? = null,
        originalInstanceTime: Long? = null,
        eventColor: Int? = colour,
        availability: Int = availabilityFree,
        eventStartTs: Long = occurrenceStartTs,
    ) = DeviceCalendarInstance(
        instanceId = 900L + eventId,
        eventId = eventId,
        title = "Standup",
        description = "",
        location = "",
        startTs = occurrenceStartTs,
        endTs = occurrenceStartTs + oneHourMs,
        startDay = 0,
        endDay = 0,
        isAllDay = false,
        hasRrule = hasRrule,
        rrule = rrule,
        reminders = listOf(15),
        calendarId = 42L,
        calendarDisplayName = "Work",
        calendarColor = 0xFF445566.toInt(),
        eventColor = eventColor,
        status = 1,
        availability = availability,
        hasAlarm = true,
        selfAttendeeStatus = 0,
        isWritable = true,
        originalId = originalId,
        originalInstanceTime = originalInstanceTime,
        timezone = "America/New_York",
        eventStartTs = eventStartTs,
    )

    private fun recurringInstance() = deviceInstance(
        hasRrule = true,
        rrule = "FREQ=WEEKLY;BYDAY=TU",
        eventStartTs = occurrenceStartTs - oneWeekMs,
    )

    /**
     * Drags a recurring occurrence: the drop opens the scope sheet, then the user picks [scope].
     */
    private fun HomeViewModel.dragRecurringAndChoose(instance: DeviceCalendarInstance, scope: EditScope) {
        rescheduleEvent(DisplayEvent.Device(instance), targetDate, targetStartMinutes)
        assertNotNull(uiState.value.pendingDragReschedule)
        assertEquals(
            "a same-day device drop blocks no scope",
            emptySet<EditScope>(), uiState.value.pendingDragReschedule!!.blockedScopes,
        )
        confirmReschedule(scope)
    }

    // ==================== Drag keeps colour and availability ====================

    @Test
    fun `dragging a non-recurring device event keeps its colour and availability`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rescheduleEvent(DisplayEvent.Device(deviceInstance()), targetDate, targetStartMinutes)
        advanceUntilIdle()

        val update = fakeCalendarProviderRepository.updatedEvents.single()
        assertEquals(100L, update.eventId)
        assertEquals(colour, update.eventColor)
        assertEquals(availabilityFree, update.availability)
    }

    @Test
    fun `dragging an event whose stored repeat rule is empty writes an end time and no duration`() = runTest {
        // The provider treats an empty RRULE column as "not recurring", so the
        // drag must write a DTEND; a DURATION would be dropped and leave the old end.
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rescheduleEvent(
            DisplayEvent.Device(deviceInstance(hasRrule = false, rrule = "")),
            targetDate,
            targetStartMinutes,
        )
        advanceUntilIdle()

        val update = fakeCalendarProviderRepository.updatedEvents.single()
        assertEquals(100L, update.eventId)
        assertNotNull(update.endTs)
        assertEquals(oneHourMs, update.endTs!! - update.startTs)
        assertNull(update.duration)
    }

    @Test
    fun `dragging a modified occurrence keeps its colour and availability`() = runTest {
        val exception = deviceInstance(
            eventId = 150L,
            originalId = 100L,
            originalInstanceTime = occurrenceStartTs,
        )
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rescheduleEvent(DisplayEvent.Device(exception), targetDate, targetStartMinutes)
        advanceUntilIdle()

        val update = fakeCalendarProviderRepository.updatedEvents.single()
        assertEquals(150L, update.eventId)
        assertEquals(colour, update.eventColor)
        assertEquals(availabilityFree, update.availability)
    }

    @Test
    fun `moving one occurrence of a recurring device event keeps colour and availability on the new exception`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dragRecurringAndChoose(recurringInstance(), EditScope.THIS_EVENT)
        advanceUntilIdle()

        val exception = fakeCalendarProviderRepository.createdExceptions.single()
        assertEquals(100L, exception.masterEventId)
        assertEquals(colour, exception.eventColor)
        assertEquals(availabilityFree, exception.availability)
    }

    @Test
    fun `moving this and future occurrences of a recurring device event keeps colour and availability`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dragRecurringAndChoose(recurringInstance(), EditScope.THIS_AND_FUTURE)
        advanceUntilIdle()

        val split = fakeCalendarProviderRepository.editedFutureSeries.single()
        assertEquals(100L, split.masterEventId)
        assertEquals(colour, split.eventColor)
        assertEquals(availabilityFree, split.availability)
    }

    @Test
    fun `dragging a device event without its own colour does not invent one and keeps availability`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rescheduleEvent(
            DisplayEvent.Device(deviceInstance(eventColor = null, availability = availabilityTentative)),
            targetDate,
            targetStartMinutes,
        )
        advanceUntilIdle()

        val update = fakeCalendarProviderRepository.updatedEvents.single()
        assertNull(update.eventColor)
        assertEquals(availabilityTentative, update.availability)
    }

    // ==================== Failed writes show the failure snackbar ====================
    // Nothing renders the error dialog state, so the snackbar is what the user sees.

    @Test
    fun `failed drag of a non-recurring device event shows the failure snackbar`() = runTest {
        fakeCalendarProviderRepository.writeFailure = CalendarError.DeviceCalendar.WriteFailed("provider rejected")
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rescheduleEvent(DisplayEvent.Device(deviceInstance()), targetDate, targetStartMinutes)
        advanceUntilIdle()

        assertEquals(rescheduleFailedMessage, viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `failed single-occurrence move of a recurring device event shows the failure snackbar`() = runTest {
        fakeCalendarProviderRepository.writeFailure = CalendarError.DeviceCalendar.WriteFailed("provider rejected")
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dragRecurringAndChoose(recurringInstance(), EditScope.THIS_EVENT)
        advanceUntilIdle()

        assertEquals(rescheduleFailedMessage, viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `failed this-and-future move of a recurring device event shows the failure snackbar`() = runTest {
        fakeCalendarProviderRepository.writeFailure = CalendarError.DeviceCalendar.WriteFailed("provider rejected")
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dragRecurringAndChoose(recurringInstance(), EditScope.THIS_AND_FUTURE)
        advanceUntilIdle()

        assertEquals(rescheduleFailedMessage, viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `drag after calendar permission is revoked shows the failure snackbar`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        fakeCalendarProviderRepository.shouldThrowSecurityException = true

        viewModel.rescheduleEvent(DisplayEvent.Device(deviceInstance()), targetDate, targetStartMinutes)
        advanceUntilIdle()

        assertEquals(rescheduleFailedMessage, viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `successful device drags show the success snackbar and no error`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rescheduleEvent(DisplayEvent.Device(deviceInstance()), targetDate, targetStartMinutes)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.currentError)
        assertEquals(rescheduledMessage, viewModel.uiState.value.pendingSnackbarMessage)

        viewModel.clearSnackbar()
        viewModel.dragRecurringAndChoose(recurringInstance(), EditScope.THIS_EVENT)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.currentError)
        assertEquals(rescheduledMessage, viewModel.uiState.value.pendingSnackbarMessage)

        viewModel.clearSnackbar()
        viewModel.dragRecurringAndChoose(recurringInstance(), EditScope.THIS_AND_FUTURE)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.currentError)
        assertEquals(rescheduledMessage, viewModel.uiState.value.pendingSnackbarMessage)

        assertEquals(1, fakeCalendarProviderRepository.updatedEvents.size)
        assertEquals(1, fakeCalendarProviderRepository.createdExceptions.size)
        assertEquals(1, fakeCalendarProviderRepository.editedFutureSeries.size)
    }

    @Test
    fun `successful drag of a Room event still updates it and shows the success snackbar`() = runTest {
        val (roomEvent, event) = roomDisplayEvent()
        coEvery { eventCoordinator.updateEvent(any(), any()) } returns event
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rescheduleEvent(roomEvent, targetDate, targetStartMinutes)
        advanceUntilIdle()

        coVerify(exactly = 1) { eventCoordinator.updateEvent(any(), any()) }
        assertNull(viewModel.uiState.value.currentError)
        assertEquals(rescheduledMessage, viewModel.uiState.value.pendingSnackbarMessage)
    }

    // ==================== All-events drag on a recurring device event ====================

    @Test
    fun `all-events drag of a recurring device event is refused without writing`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rescheduleEvent(
            DisplayEvent.Device(recurringInstance()),
            targetDate,
            targetStartMinutes,
            EditScope.ALL_EVENTS,
        )
        advanceUntilIdle()

        assertNoProviderWrites()
        assertNull(viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `choosing all events from the scope sheet for a recurring device event is refused without writing`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dragRecurringAndChoose(recurringInstance(), EditScope.ALL_EVENTS)
        advanceUntilIdle()

        assertNoProviderWrites()
        assertNull(viewModel.uiState.value.pendingDragReschedule)
        assertNull(viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `all-events drag of a non-recurring device event still reschedules it`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rescheduleEvent(
            DisplayEvent.Device(deviceInstance()),
            targetDate,
            targetStartMinutes,
            EditScope.ALL_EVENTS,
        )
        advanceUntilIdle()

        assertEquals(100L, fakeCalendarProviderRepository.updatedEvents.single().eventId)
    }

    @Test
    fun `all-events drag of a modified occurrence still reschedules that occurrence`() = runTest {
        val exception = deviceInstance(
            eventId = 150L,
            originalId = 100L,
            originalInstanceTime = occurrenceStartTs,
        )
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rescheduleEvent(DisplayEvent.Device(exception), targetDate, targetStartMinutes, EditScope.ALL_EVENTS)
        advanceUntilIdle()

        assertEquals(150L, fakeCalendarProviderRepository.updatedEvents.single().eventId)
    }

    private fun assertNoProviderWrites() {
        assertEquals(emptyList<Any>(), fakeCalendarProviderRepository.updatedEvents)
        assertEquals(emptyList<Any>(), fakeCalendarProviderRepository.createdExceptions)
        assertEquals(emptyList<Any>(), fakeCalendarProviderRepository.editedFutureSeries)
    }

    // ==================== Snackbar text is translatable ====================

    @Test
    fun `successful drag shows the localized rescheduled message`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rescheduleEvent(DisplayEvent.Device(deviceInstance()), targetDate, targetStartMinutes)
        advanceUntilIdle()

        assertEquals(rescheduledMessage, viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `drag that throws shows the localized failure message without exception details`() = runTest {
        val (roomEvent, _) = roomDisplayEvent()
        coEvery { eventCoordinator.updateEvent(any(), any()) } throws RuntimeException("secret-detail")
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rescheduleEvent(roomEvent, targetDate, targetStartMinutes)
        advanceUntilIdle()

        val message = viewModel.uiState.value.pendingSnackbarMessage
        assertEquals(rescheduleFailedMessage, message)
        assertFalse(message!!.contains("secret-detail"))
    }

    private fun roomDisplayEvent(): Pair<DisplayEvent.Room, Event> {
        val event = Event(
            id = 7L,
            uid = "room-uid",
            calendarId = 1L,
            title = "Review",
            startTs = occurrenceStartTs,
            endTs = occurrenceStartTs + oneHourMs,
            dtstamp = occurrenceStartTs,
        )
        val occurrence = Occurrence(
            eventId = 7L,
            calendarId = 1L,
            startTs = occurrenceStartTs,
            endTs = occurrenceStartTs + oneHourMs,
            startDay = 20240305,
            endDay = 20240305,
        )
        return DisplayEvent.Room(event, occurrence, calendar = null) to event
    }
}
