package org.onekash.kashcal.ui.viewmodels

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.quickadd.ParseConfidence
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class QuickAddViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val eventCoordinator: EventCoordinator = mockk(relaxed = true)
    private val dataStore: KashCalDataStore = mockk(relaxed = true)

    private lateinit var viewModel: QuickAddViewModel

    // Reference: Monday April 13, 2026, 10:00 AM
    private val reference = LocalDateTime.of(2026, 4, 13, 10, 0)
    private val zone = ZoneId.of("America/New_York")

    private var originalLocale: Locale? = null

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)

        // Default DataStore stubs — mock the Flow properties
        every { dataStore.defaultEventDuration } returns flowOf(60)
        every { dataStore.defaultReminderMinutes } returns flowOf(15)
        every { dataStore.defaultAllDayReminder } returns flowOf(720) // 12 hours

        // Default calendar — use DataStore preference (matches EventFormSheet pattern)
        coEvery { dataStore.getDefaultCalendar() } returns DefaultCalendar.Room(1L)
        coEvery { eventCoordinator.getLocalCalendarId() } returns 1L

        // createEvent returns the event passed in (with id set)
        val eventSlot = slot<Event>()
        coEvery { eventCoordinator.createEvent(capture(eventSlot), any()) } answers {
            eventSlot.captured.copy(id = 42L)
        }

        viewModel = QuickAddViewModel(eventCoordinator, dataStore, testDispatcher)
        viewModel.setReferenceTime(reference)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        TimeZone.setDefault(null)
        originalLocale?.let { Locale.setDefault(it) }
    }

    // ==================== Parsing ====================

    @Test
    fun `onInputChanged parses Coffee tomorrow at 3pm`() = runTest {
        viewModel.onInputChanged("Coffee tomorrow at 3pm")
        advanceUntilIdle()

        val result = viewModel.parseResult.value
        assertEquals("Coffee", result.title)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
        assertEquals(LocalTime.of(15, 0), result.startTime)
        assertEquals(ParseConfidence.HIGH, result.confidence)
    }

    @Test
    fun `onInputChanged with empty input produces empty result`() = runTest {
        viewModel.onInputChanged("")
        advanceUntilIdle()

        val result = viewModel.parseResult.value
        assertEquals("", result.title)
        assertNull(result.startTime)
    }

    @Test
    fun `setReferenceTime affects date resolution`() = runTest {
        val customRef = LocalDateTime.of(2026, 12, 25, 9, 0)
        viewModel.setReferenceTime(customRef)
        viewModel.onInputChanged("tomorrow")
        advanceUntilIdle()

        val result = viewModel.parseResult.value
        assertEquals(LocalDate.of(2026, 12, 26), result.startDate)
    }

    @Test
    fun `onInputChanged uses system locale for ambiguous slash date - UK`() = runTest {
        Locale.setDefault(Locale.UK)
        viewModel.onInputChanged("meeting 5/10/2026")
        advanceUntilIdle()

        val result = viewModel.parseResult.value
        assertEquals("meeting", result.title)
        assertEquals(LocalDate.of(2026, 10, 5), result.startDate)
    }

    @Test
    fun `onInputChanged uses system locale for ambiguous slash date - US`() = runTest {
        Locale.setDefault(Locale.US)
        viewModel.onInputChanged("meeting 5/10/2026")
        advanceUntilIdle()

        val result = viewModel.parseResult.value
        assertEquals("meeting", result.title)
        assertEquals(LocalDate.of(2026, 5, 10), result.startDate)
    }

    // ==================== isSaveEnabled ====================

    @Test
    fun `isSaveEnabled is false when input empty`() = runTest {
        viewModel.onInputChanged("")
        advanceUntilIdle()

        assertFalse(viewModel.isSaveEnabled.value)
    }

    @Test
    fun `isSaveEnabled is false when title blank - date time only`() = runTest {
        viewModel.onInputChanged("tomorrow at 3pm")
        advanceUntilIdle()

        assertFalse(viewModel.isSaveEnabled.value)
    }

    @Test
    fun `isSaveEnabled is true when title non-blank`() = runTest {
        viewModel.onInputChanged("Coffee tomorrow at 3pm")
        advanceUntilIdle()

        assertTrue(viewModel.isSaveEnabled.value)
    }

    @Test
    fun `isSaveEnabled is true for title only input`() = runTest {
        viewModel.onInputChanged("Coffee with Sarah")
        advanceUntilIdle()

        assertTrue(viewModel.isSaveEnabled.value)
    }

    // ==================== save() — timed event ====================

    @Test
    fun `save creates event with correct fields`() = runTest {
        viewModel.onInputChanged("Coffee tomorrow at 3pm")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val event = result.getOrNull()!!

        assertEquals("Coffee", event.title)
        assertFalse(event.isAllDay)
        assertNotNull(event.uid)
        assertTrue(event.uid.isNotBlank())
        assertNotNull(event.timezone)

        // Start time: April 14, 2026 3:00 PM in local zone
        val expectedStartTs = LocalDateTime.of(2026, 4, 14, 15, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expectedStartTs, event.startTs)
    }

    @Test
    fun `save with no end time uses default duration`() = runTest {
        every { dataStore.defaultEventDuration } returns flowOf(60) // 60 minutes

        viewModel.onInputChanged("Coffee tomorrow at 3pm")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        val event = result.getOrNull()!!
        val expectedStartTs = LocalDateTime.of(2026, 4, 14, 15, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val expectedEndTs = expectedStartTs + 60 * 60 * 1000L // +1 hour
        assertEquals(expectedEndTs, event.endTs)
    }

    @Test
    fun `save with parsed end time sets correct endTs`() = runTest {
        viewModel.onInputChanged("Meeting tomorrow 2-4pm")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        val event = result.getOrNull()!!
        val expectedStartTs = LocalDateTime.of(2026, 4, 14, 14, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val expectedEndTs = LocalDateTime.of(2026, 4, 14, 16, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expectedStartTs, event.startTs)
        assertEquals(expectedEndTs, event.endTs)
    }

    @Test
    fun `save sets location from parsed result`() = runTest {
        viewModel.onInputChanged("Coffee tomorrow at 3pm at Blue Bottle")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        val event = result.getOrNull()!!
        assertEquals("Blue Bottle", event.location)
    }

    @Test
    fun `save sets rrule from parsed result`() = runTest {
        viewModel.onInputChanged("Standup every Monday at 10am")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        val event = result.getOrNull()!!
        assertNotNull(event.rrule)
        assertTrue(event.rrule!!.contains("FREQ=WEEKLY"))
    }

    // ==================== Multi-day events (issue #194 follow-up: Bug A) ====================

    @Test
    fun `save for all-day multi-day 'Conference Friday to Sunday' sets endTs to end-of-Sunday`() = runTest {
        viewModel.onInputChanged("Conference Friday to Sunday")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        val event = result.getOrNull()!!
        assertTrue("Multi-day 'Friday to Sunday' with no time is all-day", event.isAllDay)
        // Friday Apr 17 → Sunday Apr 19. startTs is Fri UTC midnight.
        val expectedStart = LocalDate.of(2026, 4, 17)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val sundayMidnightUtc = LocalDate.of(2026, 4, 19)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val expectedEnd = sundayMidnightUtc + (24 * 60 * 60 * 1000L) - 1
        assertEquals(expectedStart, event.startTs)
        assertEquals(expectedEnd, event.endTs)
    }

    @Test
    fun `save for timed multi-day without explicit end time uses default duration on end date`() = runTest {
        every { dataStore.defaultEventDuration } returns flowOf(60) // 60 minutes

        viewModel.onInputChanged("Trip Monday to Wednesday at 3pm")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        val event = result.getOrNull()!!
        assertFalse(event.isAllDay)
        // Parser: startDate=Mon Apr 20 (bare "Monday" from ref Mon Apr 13 advances 7),
        //         endDate=Wed Apr 22, startTime=15:00, endTime=null
        // VM should produce: startTs = Mon Apr 20 15:00, endTs = Wed Apr 22 15:00 + 60min = Wed Apr 22 16:00
        val expectedStart = LocalDateTime.of(2026, 4, 20, 15, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val expectedEnd = LocalDateTime.of(2026, 4, 22, 16, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expectedStart, event.startTs)
        assertEquals(expectedEnd, event.endTs)
    }

    @Test
    fun `toCalendarIntentData for all-day multi-day sets endTimeMillis to end-of-Sunday`() = runTest {
        viewModel.onInputChanged("Conference Friday to Sunday")
        advanceUntilIdle()

        val intentData = viewModel.toCalendarIntentData()

        assertTrue(intentData.isAllDay)
        assertNotNull(intentData.startTimeMillis)
        assertNotNull(intentData.endTimeMillis)
        val sundayMidnightUtc = LocalDate.of(2026, 4, 19)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val expectedEnd = sundayMidnightUtc + (24 * 60 * 60 * 1000L) - 1
        assertEquals(expectedEnd, intentData.endTimeMillis)
    }

    @Test
    fun `toCalendarIntentData for timed multi-day with implicit end time preserves endDate`() = runTest {
        every { dataStore.defaultEventDuration } returns flowOf(60)

        viewModel.onInputChanged("Trip Monday to Wednesday at 3pm")
        advanceUntilIdle()

        val intentData = viewModel.toCalendarIntentData()

        assertFalse(intentData.isAllDay)
        // Wed Apr 22 15:00 + 60min = Wed Apr 22 16:00
        val expectedEnd = LocalDateTime.of(2026, 4, 22, 16, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expectedEnd, intentData.endTimeMillis)
    }

    @Test
    fun `save sets default reminder as ISO duration`() = runTest {
        every { dataStore.defaultReminderMinutes } returns flowOf(15)

        viewModel.onInputChanged("Coffee tomorrow at 3pm")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        val event = result.getOrNull()!!
        assertNotNull(event.reminders)
        assertTrue(event.reminders!!.contains("-PT15M"))
    }

    @Test
    fun `save with REMINDER_OFF sets reminders null`() = runTest {
        every { dataStore.defaultReminderMinutes } returns flowOf(KashCalDataStore.REMINDER_OFF)

        viewModel.onInputChanged("Coffee tomorrow at 3pm")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        val event = result.getOrNull()!!
        assertNull(event.reminders)
    }

    @Test
    fun `save calls eventCoordinator createEvent with calendar id`() = runTest {
        viewModel.onInputChanged("Coffee tomorrow at 3pm")
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        coVerify { eventCoordinator.createEvent(any(), eq(1L)) }
    }

    // ==================== save() — all-day event ====================

    @Test
    fun `save with all-day event sets isAllDay and null timezone`() = runTest {
        viewModel.onInputChanged("Holiday tomorrow")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        val event = result.getOrNull()!!
        assertTrue(event.isAllDay)
        assertNull(event.timezone)
    }

    @Test
    fun `save with all-day event uses UTC midnight timestamps`() = runTest {
        viewModel.onInputChanged("Holiday tomorrow")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        val event = result.getOrNull()!!
        val expectedStartTs = LocalDate.of(2026, 4, 14)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val expectedEndTs = expectedStartTs + (24 * 60 * 60 * 1000) - 1 // end-of-day
        assertEquals(expectedStartTs, event.startTs)
        assertEquals(expectedEndTs, event.endTs)
    }

    @Test
    fun `save with all-day event uses defaultAllDayReminder`() = runTest {
        every { dataStore.defaultAllDayReminder } returns flowOf(720) // 12 hours

        viewModel.onInputChanged("Holiday tomorrow")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        val event = result.getOrNull()!!
        assertNotNull(event.reminders)
        // 720 minutes = 12 hours = -PT12H
        assertTrue(event.reminders!!.any { it.contains("12H") })
    }

    @Test
    fun `save with all-day and REMINDER_OFF sets reminders null`() = runTest {
        every { dataStore.defaultAllDayReminder } returns flowOf(KashCalDataStore.REMINDER_OFF)

        viewModel.onInputChanged("Holiday tomorrow")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        val event = result.getOrNull()!!
        assertNull(event.reminders)
    }

    // ==================== save() — failure ====================

    @Test
    fun `save with no writable calendar returns failure`() = runTest {
        coEvery { dataStore.getDefaultCalendar() } returns null
        coEvery { eventCoordinator.getLocalCalendarId() } throws IllegalStateException("No calendar")

        viewModel.onInputChanged("Coffee tomorrow at 3pm")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        assertTrue(result.isFailure)
    }

    @Test
    fun `save with device calendar default throws DeviceCalendarException`() = runTest {
        coEvery { dataStore.getDefaultCalendar() } returns DefaultCalendar.Device(99L)

        viewModel.onInputChanged("Coffee tomorrow at 3pm")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DeviceCalendarException)
    }

    @Test
    fun `save with null default uses local calendar`() = runTest {
        coEvery { dataStore.getDefaultCalendar() } returns null
        coEvery { eventCoordinator.getLocalCalendarId() } returns 5L

        viewModel.onInputChanged("Coffee tomorrow at 3pm")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val event = result.getOrThrow()
        assertEquals(5L, event.calendarId)
    }

    // ==================== toCalendarIntentData() ====================

    @Test
    fun `toCalendarIntentData maps fields correctly`() = runTest {
        viewModel.onInputChanged("Coffee tomorrow at 3pm at Blue Bottle")
        advanceUntilIdle()

        val intentData = viewModel.toCalendarIntentData()

        assertEquals("Coffee", intentData.title)
        assertEquals("Blue Bottle", intentData.location)
        assertFalse(intentData.isAllDay)
        assertNotNull(intentData.startTimeMillis)

        val expectedStartTs = LocalDateTime.of(2026, 4, 14, 15, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expectedStartTs, intentData.startTimeMillis)
    }

    @Test
    fun `toCalendarIntentData with rrule maps rrule`() = runTest {
        viewModel.onInputChanged("Standup every Monday at 10am")
        advanceUntilIdle()

        val intentData = viewModel.toCalendarIntentData()

        assertNotNull(intentData.rrule)
        assertTrue(intentData.rrule!!.contains("FREQ=WEEKLY"))
    }

    @Test
    fun `toCalendarIntentData with empty input returns minimal data`() = runTest {
        viewModel.onInputChanged("")
        advanceUntilIdle()

        val intentData = viewModel.toCalendarIntentData()

        assertNull(intentData.title)
        assertNull(intentData.location)
        assertNull(intentData.rrule)
    }

    @Test
    fun `toCalendarIntentData for all-day event sets isAllDay true`() = runTest {
        viewModel.onInputChanged("Holiday tomorrow")
        advanceUntilIdle()

        val intentData = viewModel.toCalendarIntentData()

        assertTrue(intentData.isAllDay)
    }

    // ==================== cross-midnight edge case ====================

    @Test
    fun `save with end time before start time crosses midnight`() = runTest {
        viewModel.onInputChanged("Party tomorrow 10pm to 1am")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        val event = result.getOrNull()!!
        assertTrue("endTs (${event.endTs}) should be > startTs (${event.startTs})",
            event.endTs > event.startTs)
    }

    // ==================== resetState ====================

    @Test
    fun `resetState clears input and parse result`() = runTest {
        viewModel.onInputChanged("Coffee tomorrow at 3pm")
        advanceUntilIdle()
        assertTrue(viewModel.isSaveEnabled.value)

        viewModel.resetState()

        assertEquals("", viewModel.inputText.value)
        assertEquals("", viewModel.parseResult.value.title)
        assertFalse(viewModel.isSaveEnabled.value)
        assertFalse(viewModel.isSaving.value)
    }

    // ==================== save() — calendarId ====================

    @Test
    fun `save uses Room calendar id from DataStore preference`() = runTest {
        coEvery { dataStore.getDefaultCalendar() } returns DefaultCalendar.Room(7L)

        viewModel.onInputChanged("Coffee tomorrow at 3pm")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        val event = result.getOrThrow()
        assertEquals(7L, event.calendarId)
        coVerify { eventCoordinator.createEvent(any(), eq(7L)) }
    }

    // ==================== save() — isSaving flag ====================

    @Test
    fun `save resets isSaving after completion`() = runTest {
        viewModel.onInputChanged("Coffee tomorrow at 3pm")
        advanceUntilIdle()

        assertFalse(viewModel.isSaving.value)
        viewModel.save()
        advanceUntilIdle()

        assertFalse(viewModel.isSaving.value)
    }

    @Test
    fun `save resets isSaving after failure`() = runTest {
        coEvery { dataStore.getDefaultCalendar() } returns null
        coEvery { eventCoordinator.getLocalCalendarId() } throws IllegalStateException("No calendar")

        viewModel.onInputChanged("Coffee tomorrow at 3pm")
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        assertFalse(viewModel.isSaving.value)
    }

    // ==================== toCalendarIntentData() — all-day with start millis ====================

    @Test
    fun `toCalendarIntentData for all-day event returns UTC midnight startTimeMillis`() = runTest {
        viewModel.onInputChanged("Holiday tomorrow")
        advanceUntilIdle()

        val intentData = viewModel.toCalendarIntentData()

        assertTrue(intentData.isAllDay)
        assertNotNull(intentData.startTimeMillis)
        val expectedStartTs = LocalDate.of(2026, 4, 14)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(expectedStartTs, intentData.startTimeMillis)
    }

    // ==================== toCalendarIntentData() — default duration ====================

    @Test
    fun `toCalendarIntentData with no end time uses default duration`() = runTest {
        every { dataStore.defaultEventDuration } returns flowOf(45) // 45 minutes

        viewModel.onInputChanged("Coffee tomorrow at 3pm")
        advanceUntilIdle()

        val intentData = viewModel.toCalendarIntentData()

        val expectedStartTs = LocalDateTime.of(2026, 4, 14, 15, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val expectedEndTs = expectedStartTs + 45 * 60 * 1000L
        assertEquals(expectedStartTs, intentData.startTimeMillis)
        assertEquals(expectedEndTs, intentData.endTimeMillis)
    }

    @Test
    fun `toCalendarIntentData with parsed end time uses parsed end`() = runTest {
        viewModel.onInputChanged("Meeting tomorrow 2-4pm")
        advanceUntilIdle()

        val intentData = viewModel.toCalendarIntentData()

        val expectedStartTs = LocalDateTime.of(2026, 4, 14, 14, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val expectedEndTs = LocalDateTime.of(2026, 4, 14, 16, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expectedStartTs, intentData.startTimeMillis)
        assertEquals(expectedEndTs, intentData.endTimeMillis)
    }

    // ==================== save() — endTs with default duration varies ====================

    @Test
    fun `save with custom default duration uses that duration`() = runTest {
        every { dataStore.defaultEventDuration } returns flowOf(30) // 30 minutes

        viewModel.onInputChanged("Coffee tomorrow at 3pm")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        val event = result.getOrNull()!!
        val expectedStartTs = LocalDateTime.of(2026, 4, 14, 15, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val expectedEndTs = expectedStartTs + 30 * 60 * 1000L // +30 min
        assertEquals(expectedEndTs, event.endTs)
    }

    // ==================== save() — title-only input (no date/time) ====================

    @Test
    fun `save with title only creates all-day event on reference date`() = runTest {
        viewModel.onInputChanged("Coffee with Sarah")
        advanceUntilIdle()

        val result = viewModel.save()
        advanceUntilIdle()

        val event = result.getOrNull()!!
        assertEquals("Coffee with Sarah", event.title)
        assertTrue(event.isAllDay)
        assertNull(event.timezone)
        // All-day on reference date (April 13) at UTC midnight
        val expectedStartTs = LocalDate.of(2026, 4, 13)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(expectedStartTs, event.startTs)
    }
}
