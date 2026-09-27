package org.onekash.kashcal.ui.viewmodels

import android.provider.CalendarContract
import io.mockk.coVerify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceAttendee
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.calendar_provider.DeviceEvent
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.EventFormState
import java.time.LocalDate

/**
 * Tests changing an occurrence of a recurring device event, from a drag or a form save of this
 * event or this and future, through the real [HomeViewModel] and device writer over
 * [FakeCalendarProviderRepository]:
 * - a cancelled or deleted exception row is never written; a new exception is created, and the
 *   form opens the series. A live exception row is updated in place and opened.
 * - a drag keeps every reminder with its type, also when the dragged instance carries none; a
 *   new exception or future half gets the series reminders, and a form save writes its minutes.
 * - a new exception or future half gets the series' tags and guests; tags edited in the form go
 *   to the new series and are recorded as used. A drag of a modified occurrence rewrites neither.
 * - when the series' reminders, tags or guests can't be read, the drag or save shows the
 *   failure and writes nothing.
 *
 * These are wiring tests: they prove the ViewModel and writer route each case to the right
 * provider write. What the real provider queries return is pinned by
 * `CalendarProviderExceptionContractTest`, which runs the same assertions on the fake and on the
 * real repository over SQL.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelDeviceOccurrenceFidelityTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var factory: DeviceHomeViewModelTestFactory
    private lateinit var repo: FakeCalendarProviderRepository

    private val rescheduledMessage = DeviceHomeViewModelTestFactory.RESCHEDULED_MESSAGE
    private val rescheduleFailedMessage = DeviceHomeViewModelTestFactory.RESCHEDULE_FAILED_MESSAGE

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        factory = DeviceHomeViewModelTestFactory()
        repo = FakeCalendarProviderRepository()

        repo.deviceEvents[MASTER_ID] = masterRow()
        repo.calendars = listOf(calendar())
        // New rows get ids well clear of the seeded ones, so "the new exception" can't be the
        // master read back.
        repo.createdEventId = NEW_ROW_ID
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HomeViewModel = factory.create(
        deviceEventReader = repo.deviceEventReader(),
        deviceEventWriter = repo.deviceEventWriter(factory.dataStore),
        ioDispatcher = testDispatcher,
    )

    private fun calendar() = DeviceCalendar(
        id = CAL_ID, displayName = "Work", color = 0xFF445566.toInt(), accountName = "me@example.test",
        accountType = "com.example", visible = true, accessLevel = 700, ownerAccount = "me@example.test",
    )

    private fun masterRow() = DeviceEvent(
        id = MASTER_ID, calendarId = CAL_ID, title = "Standup", description = null, location = null,
        startTs = OCCURRENCE_TS - WEEK_MS, endTs = null, duration = "PT1H", isAllDay = false,
        rrule = "FREQ=WEEKLY;BYDAY=TU", rdate = null, exdate = null, exrule = null,
        timezone = "America/New_York", originalId = null, originalInstanceTime = null,
        status = CalendarContract.Events.STATUS_CONFIRMED, availability = 0, accessLevel = 700,
        calendarColor = null, eventColor = null,
    )

    private fun exceptionRow(id: Long, status: Int) = masterRow().copy(
        id = id, rrule = null, duration = null, startTs = OCCURRENCE_TS, endTs = OCCURRENCE_TS + HOUR_MS,
        originalId = MASTER_ID, originalInstanceTime = OCCURRENCE_TS, status = status, title = "Old dead copy",
    )

    /** A dead exception row for the occurrence: cancelled, or soft-deleted. */
    private fun seedDeadException(cancelled: Boolean) {
        val status = if (cancelled) CalendarContract.Events.STATUS_CANCELED else CalendarContract.Events.STATUS_CONFIRMED
        repo.deviceEvents[DEAD_ID] = exceptionRow(DEAD_ID, status)
        repo.exceptionEvents[MASTER_ID to OCCURRENCE_TS] = DEAD_ID
        if (!cancelled) repo.softDeletedEventIds.add(DEAD_ID)
    }

    private fun seedLiveException() {
        repo.deviceEvents[LIVE_ID] = exceptionRow(LIVE_ID, CalendarContract.Events.STATUS_CONFIRMED)
        repo.exceptionEvents[MASTER_ID to OCCURRENCE_TS] = LIVE_ID
    }

    /** The live occurrence the user sees in the grid (the master's instance). */
    private fun occurrenceInstance() = DeviceCalendarInstance(
        instanceId = 900L, eventId = MASTER_ID, title = "Standup", description = "", location = "",
        startTs = OCCURRENCE_TS, endTs = OCCURRENCE_TS + HOUR_MS, startDay = 0, endDay = 0, isAllDay = false,
        hasRrule = true, rrule = "FREQ=WEEKLY;BYDAY=TU", reminders = listOf(15), calendarId = CAL_ID,
        calendarDisplayName = "Work", calendarColor = 0xFF445566.toInt(), eventColor = null,
        status = CalendarContract.Events.STATUS_CONFIRMED, availability = 0, hasAlarm = true,
        selfAttendeeStatus = 0, isWritable = true, originalId = null, originalInstanceTime = null,
        timezone = "America/New_York", eventStartTs = OCCURRENCE_TS - WEEK_MS,
    )

    private fun HomeViewModel.dragOccurrence(scope: EditScope) {
        rescheduleEvent(DisplayEvent.Device(occurrenceInstance()), LocalDate.of(2024, 3, 5), 600)
        assertNotNull(uiState.value.pendingDragReschedule)
        confirmReschedule(scope)
    }

    /** A one-off event, or (with [originalId]) a modified occurrence, as the grid shows it. */
    private fun singleRowInstance(eventId: Long, originalId: Long? = null, reminders: List<Int>) =
        occurrenceInstance().copy(
            instanceId = 950L + eventId, eventId = eventId, hasRrule = false, rrule = null,
            originalId = originalId, originalInstanceTime = originalId?.let { OCCURRENCE_TS },
            eventStartTs = OCCURRENCE_TS, reminders = reminders,
        )

    private fun HomeViewModel.dragSingleRow(instance: DeviceCalendarInstance) {
        rescheduleEvent(DisplayEvent.Device(instance), LocalDate.of(2024, 3, 5), 600)
    }

    private val emailAndSms = listOf(
        FakeCalendarProviderRepository.ReminderRow(30, CalendarContract.Reminders.METHOD_EMAIL),
        FakeCalendarProviderRepository.ReminderRow(10, CalendarContract.Reminders.METHOD_SMS),
    )

    private fun occurrenceForm() = EventFormState(
        title = "Standup (moved)",
        dateMillis = OCCURRENCE_TS,
        endDateMillis = OCCURRENCE_TS,
        startHour = 11, startMinute = 0, endHour = 12, endMinute = 0,
        timezone = "America/New_York",
        selectedCalendarId = CAL_ID,
        reminders = listOf(15),
        rrule = "FREQ=WEEKLY;BYDAY=TU",
        isDeviceCalendar = true,
        editingDeviceEventId = MASTER_ID,
        editingOccurrenceTs = OCCURRENCE_TS,
    )

    // ---- one occurrence goes to the live row, never a dead one ----

    @Test
    fun `dragging one occurrence past a cancelled exception row creates a new exception`() = runTest {
        seedDeadException(cancelled = true)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dragOccurrence(EditScope.THIS_EVENT)
        advanceUntilIdle()

        assertTrue("the dead row is never written", repo.updatedEvents.isEmpty())
        val created = repo.createdExceptions.single()
        assertEquals(MASTER_ID, created.masterEventId)
        assertEquals(OCCURRENCE_TS, created.originalInstanceTime)
        assertEquals(rescheduledMessage, viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `dragging one occurrence past a deleted exception row creates a new exception`() = runTest {
        seedDeadException(cancelled = false)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dragOccurrence(EditScope.THIS_EVENT)
        advanceUntilIdle()

        assertTrue("the dead row is never written", repo.updatedEvents.isEmpty())
        assertEquals(MASTER_ID, repo.createdExceptions.single().masterEventId)
    }

    @Test
    fun `saving this event from the form past a cancelled exception row creates a new exception`() = runTest {
        seedDeadException(cancelled = true)
        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.saveDeviceEvent(occurrenceForm(), EditScope.THIS_EVENT)
        advanceUntilIdle()

        assertTrue("the dead row is never written", repo.updatedEvents.isEmpty())
        val created = repo.createdExceptions.single()
        assertEquals(OCCURRENCE_TS, created.originalInstanceTime)
        assertEquals(created.resultId, result.getOrNull())
    }

    @Test
    fun `saving this event from the form past a deleted exception row creates a new exception`() = runTest {
        seedDeadException(cancelled = false)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.saveDeviceEvent(occurrenceForm(), EditScope.THIS_EVENT)
        advanceUntilIdle()

        assertTrue("the dead row is never written", repo.updatedEvents.isEmpty())
        assertEquals(1, repo.createdExceptions.size)
    }

    @Test
    fun `a live exception row is still updated in place by the drag and the form`() = runTest {
        seedLiveException()
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dragOccurrence(EditScope.THIS_EVENT)
        advanceUntilIdle()
        viewModel.saveDeviceEvent(occurrenceForm(), EditScope.THIS_EVENT)
        advanceUntilIdle()

        assertEquals(listOf(LIVE_ID, LIVE_ID), repo.updatedEvents.map { it.eventId })
        assertTrue(repo.createdExceptions.isEmpty())
    }

    @Test
    fun `opening the form on an occurrence whose only exception row is dead loads the series`() = runTest {
        seedDeadException(cancelled = true)
        val viewModel = createViewModel()
        advanceUntilIdle()

        val data = viewModel.getDeviceEventForEdit(MASTER_ID, OCCURRENCE_TS)

        assertEquals(MASTER_ID, data?.event?.id)
        assertEquals("Standup", data?.event?.title)
    }

    @Test
    fun `opening the form on an occurrence with a live exception row loads that row`() = runTest {
        seedLiveException()
        val viewModel = createViewModel()
        advanceUntilIdle()

        val data = viewModel.getDeviceEventForEdit(MASTER_ID, OCCURRENCE_TS)

        assertEquals(LIVE_ID, data?.event?.id)
        assertNull(data?.event?.rrule)
    }

    // ---- a drag keeps every reminder and its type ----

    @Test
    fun `dragging a one-off event keeps its email and SMS reminders`() = runTest {
        repo.reminderRows[ONE_OFF_ID] = emailAndSms
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dragSingleRow(singleRowInstance(ONE_OFF_ID, reminders = listOf(10, 30)))
        advanceUntilIdle()

        assertEquals(ONE_OFF_ID, repo.updatedEvents.single().eventId)
        assertEquals(emailAndSms, repo.reminderRows[ONE_OFF_ID])
        assertEquals(rescheduledMessage, viewModel.uiState.value.pendingSnackbarMessage)
    }

    @Test
    fun `dragging a modified occurrence keeps its email and SMS reminders`() = runTest {
        repo.reminderRows[LIVE_ID] = emailAndSms
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dragSingleRow(singleRowInstance(LIVE_ID, originalId = MASTER_ID, reminders = listOf(10, 30)))
        advanceUntilIdle()

        assertEquals(LIVE_ID, repo.updatedEvents.single().eventId)
        assertEquals(emailAndSms, repo.reminderRows[LIVE_ID])
    }

    @Test
    fun `dragging an event whose reminders failed to load keeps the stored reminders`() = runTest {
        // The range load returns no reminders when the batch read fails, so the dragged
        // instance carries none even though the event has two.
        repo.reminderRows[ONE_OFF_ID] = emailAndSms
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dragSingleRow(singleRowInstance(ONE_OFF_ID, reminders = emptyList()))
        advanceUntilIdle()

        assertEquals(emailAndSms, repo.reminderRows[ONE_OFF_ID])
    }

    @Test
    fun `dragging one occurrence gives the new exception the series reminders with their types`() = runTest {
        repo.reminderRows[MASTER_ID] = emailAndSms
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dragOccurrence(EditScope.THIS_EVENT)
        advanceUntilIdle()

        val created = repo.createdExceptions.single()
        assertTrue(created.resultId != MASTER_ID)
        assertEquals(emailAndSms, repo.reminderRows[created.resultId])
        assertEquals(emailAndSms, repo.reminderRows[MASTER_ID])
    }

    @Test
    fun `dragging this and future gives the new series the series reminders with their types`() = runTest {
        repo.reminderRows[MASTER_ID] = emailAndSms
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dragOccurrence(EditScope.THIS_AND_FUTURE)
        advanceUntilIdle()

        val split = repo.editedFutureSeries.single()
        assertTrue(split.newEventId != MASTER_ID)
        assertEquals(emailAndSms, repo.reminderRows[split.newEventId])
        assertEquals(emailAndSms, repo.reminderRows[MASTER_ID])
    }

    @Test
    fun `a drag that can't read the series reminders shows the failure and writes nothing`() = runTest {
        for (scope in listOf(EditScope.THIS_EVENT, EditScope.THIS_AND_FUTURE)) {
            repo.reminderRows[MASTER_ID] = emailAndSms
            repo.failReminderCopy = true
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.dragOccurrence(scope)
            advanceUntilIdle()

            assertEquals("$scope shows the failure", rescheduleFailedMessage, viewModel.uiState.value.pendingSnackbarMessage)
            assertTrue("$scope creates no exception", repo.createdExceptions.isEmpty())
            assertTrue("$scope splits nothing", repo.editedFutureSeries.isEmpty())
            assertEquals(emailAndSms, repo.reminderRows[MASTER_ID])
        }
    }

    @Test
    fun `saving one occurrence from the form still writes the chosen reminder minutes`() = runTest {
        repo.reminderRows[MASTER_ID] = emailAndSms
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.saveDeviceEvent(occurrenceForm(), EditScope.THIS_EVENT)
        advanceUntilIdle()

        val created = repo.createdExceptions.single()
        assertEquals(listOf(15), created.reminders)
    }

    // ---- a new exception keeps the series' tags and guests ----

    private val seriesGuests = listOf(
        DeviceAttendee(1L, "Me", "me@example.test", CalendarContract.Attendees.RELATIONSHIP_ORGANIZER, CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED),
        DeviceAttendee(2L, "Ana", "ana@example.test", CalendarContract.Attendees.RELATIONSHIP_ATTENDEE, CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE),
        DeviceAttendee(3L, null, "bo@example.test", CalendarContract.Attendees.RELATIONSHIP_ATTENDEE, CalendarContract.Attendees.ATTENDEE_STATUS_NONE),
    )

    private val seriesTags = listOf("Work", "Weekly")

    private fun seedSeriesGuestsAndTags() {
        repo.deviceAttendees[MASTER_ID] = seriesGuests
        repo.eventCategories[MASTER_ID] = seriesTags
    }

    @Test
    fun `dragging one occurrence keeps the series tags and guests on the new exception`() = runTest {
        seedSeriesGuestsAndTags()
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dragOccurrence(EditScope.THIS_EVENT)
        advanceUntilIdle()

        assertNewRowHasSeriesGuestsAndTags(repo.createdExceptions.single().resultId)
    }

    @Test
    fun `saving one occurrence from the form keeps the series tags and guests on the new exception`() = runTest {
        seedSeriesGuestsAndTags()
        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.saveDeviceEvent(occurrenceForm(), EditScope.THIS_EVENT)
        advanceUntilIdle()

        val newId = result.getOrThrow()
        assertEquals(seriesGuests.map { it.email }, repo.deviceAttendees[newId].orEmpty().map { it.email })
        assertEquals(seriesTags, repo.eventCategories[newId])
    }

    @Test
    fun `dragging a modified occurrence leaves its own tags and guests alone`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dragSingleRow(singleRowInstance(LIVE_ID, originalId = MASTER_ID, reminders = listOf(15)))
        advanceUntilIdle()

        val update = repo.updatedEvents.single()
        assertEquals(LIVE_ID, update.eventId)
        assertNull("guests are not rewritten", update.attendees)
        assertNull("tags are not rewritten", update.categories)
    }

    @Test
    fun `a drag that can't read the series tags or guests shows the failure and creates no exception`() = runTest {
        seedSeriesGuestsAndTags()
        repo.failAttendeeAndTagCopy = true
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dragOccurrence(EditScope.THIS_EVENT)
        advanceUntilIdle()

        assertEquals(rescheduleFailedMessage, viewModel.uiState.value.pendingSnackbarMessage)
        assertTrue(repo.createdExceptions.isEmpty())
    }

    // ---- the future half of a split keeps the series' tags and guests ----

    private fun assertNewRowHasSeriesGuestsAndTags(newId: Long) {
        assertTrue(newId != MASTER_ID)
        assertEquals(seriesGuests.map { it.email }, repo.deviceAttendees[newId].orEmpty().map { it.email })
        assertEquals(seriesGuests.map { it.status }, repo.deviceAttendees[newId].orEmpty().map { it.status })
        assertEquals(seriesTags, repo.eventCategories[newId])
        assertEquals("the series keeps its guests", seriesGuests, repo.deviceAttendees[MASTER_ID])
        assertEquals("the series keeps its tags", seriesTags, repo.eventCategories[MASTER_ID])
    }

    @Test
    fun `saving this and future from the form keeps the series tags and guests on the new series`() = runTest {
        seedSeriesGuestsAndTags()
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.saveDeviceEvent(occurrenceForm(), EditScope.THIS_AND_FUTURE)
        advanceUntilIdle()

        assertNewRowHasSeriesGuestsAndTags(repo.editedFutureSeries.single().newEventId)
        coVerify(exactly = 0) { factory.eventCoordinator.recordTagUsage(any()) }
    }

    @Test
    fun `tags edited while saving this and future go to the new series and count as used`() = runTest {
        seedSeriesGuestsAndTags()
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.saveDeviceEvent(
            occurrenceForm().copy(categories = listOf("Travel"), categoriesEdited = true),
            EditScope.THIS_AND_FUTURE,
        )
        advanceUntilIdle()

        val newId = repo.editedFutureSeries.single().newEventId
        assertEquals(listOf("Travel"), repo.eventCategories[newId])
        assertEquals("guests still come from the series", seriesGuests.map { it.email }, repo.deviceAttendees[newId].orEmpty().map { it.email })
        assertEquals("the series keeps its tags", seriesTags, repo.eventCategories[MASTER_ID])
        coVerify(exactly = 1) { factory.eventCoordinator.recordTagUsage(listOf("Travel")) }
    }

    @Test
    fun `dragging this and future keeps the series tags and guests on the new series`() = runTest {
        seedSeriesGuestsAndTags()
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dragOccurrence(EditScope.THIS_AND_FUTURE)
        advanceUntilIdle()

        assertNewRowHasSeriesGuestsAndTags(repo.editedFutureSeries.single().newEventId)
    }

    @Test
    fun `a this-and-future save that can't read the series tags or guests shows an error and splits nothing`() = runTest {
        seedSeriesGuestsAndTags()
        repo.failAttendeeAndTagCopy = true
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.saveDeviceEvent(occurrenceForm(), EditScope.THIS_AND_FUTURE)
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.currentError)
        assertTrue(repo.editedFutureSeries.isEmpty())
        assertEquals(seriesTags, repo.eventCategories[MASTER_ID])
    }

    companion object {
        const val ONE_OFF_ID = 200L
        const val NEW_ROW_ID = 700L
        const val CAL_ID = 42L
        const val MASTER_ID = 100L
        const val DEAD_ID = 300L
        const val LIVE_ID = 301L
        const val OCCURRENCE_TS = 1_709_650_800_000L // 2024-03-05T15:00:00Z
        const val HOUR_MS = 3_600_000L
        const val WEEK_MS = 7 * 24 * HOUR_MS
    }
}
