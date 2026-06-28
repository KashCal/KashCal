package org.onekash.kashcal.ui.viewmodels

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.util.TimeZone

/**
 * Tests for HomeViewModel.getDeviceEventForQuickView().
 *
 * Verifies that widget taps correctly find device calendar events,
 * including all-day events in negative UTC offsets where UTC midnight
 * maps to the previous local day.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelDeviceQuickViewTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var eventCoordinator: EventCoordinator
    private lateinit var eventReader: EventReader
    private lateinit var displayEventRepository: DisplayEventRepository
    private lateinit var dataStore: KashCalDataStore
    private lateinit var accountRepository: AccountRepository
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var fakeCalendarProviderRepository: FakeCalendarProviderRepository

    private var savedTimeZone: TimeZone? = null

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        savedTimeZone = TimeZone.getDefault()

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
        every { syncScheduler.setShowBannerForSync(any()) } answers { }
        every { syncScheduler.resetBannerFlag() } answers { }

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
        TimeZone.setDefault(savedTimeZone)
    }

    private fun createViewModel(): HomeViewModel {
        return HomeViewModel(
            eventCoordinator = eventCoordinator,
            eventReader = eventReader,
            displayEventRepository = displayEventRepository,
            dataStore = dataStore,
            accountRepository = accountRepository,
            syncScheduler = syncScheduler,
            networkMonitor = networkMonitor,
            calendarProviderRepository = fakeCalendarProviderRepository,
            attendeeBackfill = io.mockk.mockk(relaxed = true),
            contactEmailReader = io.mockk.mockk(relaxed = true),
            context = io.mockk.mockk(relaxed = true),
            ioDispatcher = testDispatcher
        )
    }

    private fun makeDeviceInstance(
        eventId: Long,
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean,
        title: String = "Test Event"
    ): DeviceCalendarInstance {
        val startDay = DateTimeUtils.eventTsToDayCode(startTs, isAllDay)
        val endDay = DateTimeUtils.eventTsToDayCode(endTs, isAllDay)
        return DeviceCalendarInstance(
            instanceId = eventId * 1000,
            eventId = eventId,
            title = title,
            description = "",
            location = "",
            startTs = startTs,
            endTs = endTs,
            startDay = startDay,
            endDay = endDay,
            isAllDay = isAllDay,
            hasRrule = false,
            rrule = null,
            reminders = emptyList(),
            calendarId = 1L,
            calendarDisplayName = "Test Calendar",
            calendarColor = 0xFF0000FF.toInt(),
            eventColor = null,
            status = 1,
            availability = 0,
            hasAlarm = false,
            selfAttendeeStatus = 0,
            isWritable = true,
            originalId = null,
            originalInstanceTime = null,
            timezone = "UTC",
            eventStartTs = startTs,
        )
    }

    // ==================== Timed Event Tests ====================

    @Test
    fun `timed event found by eventId and startTs`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")) // UTC-5

        // Timed event: March 7, 2026 10:00 AM ET = 15:00 UTC
        val startTs = Instant.parse("2026-03-07T15:00:00Z").toEpochMilli()
        val endTs = Instant.parse("2026-03-07T16:00:00Z").toEpochMilli()
        val instance = makeDeviceInstance(42L, startTs, endTs, isAllDay = false)
        val displayEvent = DisplayEvent.Device(instance)
        val dayCode = 20260307

        coEvery {
            displayEventRepository.getDisplayEventsGroupedByDayOnce(dayCode, dayCode)
        } returns persistentMapOf(dayCode to persistentListOf(displayEvent))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.getDeviceEventForQuickView(42L, startTs)
        advanceUntilIdle()

        assertNotNull("Timed event should be found", result)
        assertEquals(42L, result!!.instance.eventId)
    }

    @Test
    fun `returns null for non-existent eventId`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))

        val startTs = Instant.parse("2026-03-07T15:00:00Z").toEpochMilli()

        coEvery {
            displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any())
        } returns persistentMapOf()

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.getDeviceEventForQuickView(999L, startTs)
        advanceUntilIdle()

        assertNull("Non-existent event should return null", result)
    }

    // ==================== All-Day Event Timezone Tests ====================

    @Test
    fun `all-day event found in negative UTC offset`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")) // UTC-5

        // All-day event March 7: CalendarProvider BEGIN = March 7 00:00 UTC
        // In UTC-5: eventTsToDayCode(ts, false) = March 6 (wrong!)
        // In UTC:   eventTsToDayCode(ts, true) = March 7 (correct)
        val startTs = Instant.parse("2026-03-07T00:00:00Z").toEpochMilli()
        val endTs = Instant.parse("2026-03-07T23:59:59.999Z").toEpochMilli()
        val instance = makeDeviceInstance(42L, startTs, endTs, isAllDay = true, title = "All Day Event")
        val displayEvent = DisplayEvent.Device(instance)

        val correctDayCode = 20260307 // event's actual day (computed with isAllDay=true)
        val wrongDayCode = 20260306   // what isAllDay=false would give in UTC-5

        // The repository returns the event under its correct day code
        coEvery {
            displayEventRepository.getDisplayEventsGroupedByDayOnce(wrongDayCode, correctDayCode)
        } returns persistentMapOf(correctDayCode to persistentListOf(displayEvent))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.getDeviceEventForQuickView(42L, startTs)
        advanceUntilIdle()

        assertNotNull("All-day event should be found even in negative UTC offset", result)
        assertEquals("All Day Event", result!!.title)
    }

    @Test
    fun `all-day event found in positive UTC offset`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata")) // UTC+5:30

        // All-day event March 7: CalendarProvider BEGIN = March 7 00:00 UTC
        // In UTC+5:30: eventTsToDayCode(ts, false) = March 7 05:30 local = still March 7
        // Both day codes are the same, so single-day query suffices
        val startTs = Instant.parse("2026-03-07T00:00:00Z").toEpochMilli()
        val endTs = Instant.parse("2026-03-07T23:59:59.999Z").toEpochMilli()
        val instance = makeDeviceInstance(42L, startTs, endTs, isAllDay = true)
        val displayEvent = DisplayEvent.Device(instance)

        val dayCode = 20260307

        coEvery {
            displayEventRepository.getDisplayEventsGroupedByDayOnce(dayCode, dayCode)
        } returns persistentMapOf(dayCode to persistentListOf(displayEvent))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.getDeviceEventForQuickView(42L, startTs)
        advanceUntilIdle()

        assertNotNull("All-day event should be found in positive UTC offset", result)
    }

    @Test
    fun `all-day event found in UTC timezone`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

        val startTs = Instant.parse("2026-03-07T00:00:00Z").toEpochMilli()
        val endTs = Instant.parse("2026-03-07T23:59:59.999Z").toEpochMilli()
        val instance = makeDeviceInstance(42L, startTs, endTs, isAllDay = true)
        val displayEvent = DisplayEvent.Device(instance)

        val dayCode = 20260307

        coEvery {
            displayEventRepository.getDisplayEventsGroupedByDayOnce(dayCode, dayCode)
        } returns persistentMapOf(dayCode to persistentListOf(displayEvent))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.getDeviceEventForQuickView(42L, startTs)
        advanceUntilIdle()

        assertNotNull("All-day event should be found in UTC", result)
    }

    // ==================== getDeviceEventDayCode Tests ====================
    // Used by the external-VIEW-intent fallback when no occurrence timestamp is supplied:
    // resolve the event's start day so the app can navigate there instead of landing on today.

    @Test
    fun `getDeviceEventDayCode returns start day code for timed event`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")) // UTC-5

        // March 7, 2026 10:00 ET = 15:00 UTC
        val startTs = Instant.parse("2026-03-07T15:00:00Z").toEpochMilli()
        fakeCalendarProviderRepository.deviceEvents[42L] =
            makeDeviceEvent(id = 42L, startTs = startTs, isAllDay = false)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.getDeviceEventDayCode(42L)
        advanceUntilIdle()

        assertEquals(20260307, result)
    }

    @Test
    fun `getDeviceEventDayCode honors isAllDay in negative UTC offset`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")) // UTC-5

        // All-day March 7: BEGIN = March 7 00:00 UTC. Interpreted as timed in UTC-5 this is
        // March 6; interpreted all-day (UTC) it is March 7. The day code must use isAllDay.
        val startTs = Instant.parse("2026-03-07T00:00:00Z").toEpochMilli()
        fakeCalendarProviderRepository.deviceEvents[42L] =
            makeDeviceEvent(id = 42L, startTs = startTs, isAllDay = true)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.getDeviceEventDayCode(42L)
        advanceUntilIdle()

        assertEquals(20260307, result)
    }

    @Test
    fun `getDeviceEventDayCode returns null for non-existent event`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.getDeviceEventDayCode(999L)
        advanceUntilIdle()

        assertNull("Missing device event should yield null day code", result)
    }

    // ==================== getDeviceEventForQuickViewById Tests ====================
    // Used by external VIEW intents that carry only the event ID (no occurrence timestamp):
    // resolve the event's own start (first occurrence) so the quick-view sheet can open.

    @Test
    fun `getDeviceEventForQuickViewById opens at the event's start occurrence`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")) // UTC-5

        // March 7, 2026 10:00 ET = 15:00 UTC
        val startTs = Instant.parse("2026-03-07T15:00:00Z").toEpochMilli()
        val endTs = Instant.parse("2026-03-07T16:00:00Z").toEpochMilli()
        fakeCalendarProviderRepository.deviceEvents[42L] =
            makeDeviceEvent(id = 42L, startTs = startTs, isAllDay = false)

        val instance = makeDeviceInstance(42L, startTs, endTs, isAllDay = false, title = "Standup")
        val displayEvent = DisplayEvent.Device(instance)
        val dayCode = 20260307
        coEvery {
            displayEventRepository.getDisplayEventsGroupedByDayOnce(dayCode, dayCode)
        } returns persistentMapOf(dayCode to persistentListOf(displayEvent))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.getDeviceEventForQuickViewById(42L)
        advanceUntilIdle()

        assertNotNull("Event should resolve from its own start", result)
        assertEquals(42L, result!!.instance.eventId)
        assertEquals("Standup", result.title)
    }

    @Test
    fun `getDeviceEventForQuickViewById returns null for non-existent event`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.getDeviceEventForQuickViewById(999L)
        advanceUntilIdle()

        assertNull("Missing device event should yield null", result)
    }

    @Test
    fun `getDeviceEventForQuickViewById opens recurring event at its next instance, not its first`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

        // Weekly series whose master DTSTART (first occurrence) is far in the past, but the
        // series is still active. The sheet must open at the NEXT instance, not the 2030-far
        // future first one. Use a fixed future timestamp so the test is deterministic.
        val firstOccurrence = Instant.parse("2023-01-02T09:00:00Z").toEpochMilli()
        val nextOccurrence = Instant.parse("2030-06-10T09:00:00Z").toEpochMilli()
        val nextEnd = Instant.parse("2030-06-10T10:00:00Z").toEpochMilli()
        fakeCalendarProviderRepository.deviceEvents[42L] =
            makeDeviceEvent(id = 42L, startTs = firstOccurrence, isAllDay = false, rrule = "FREQ=WEEKLY")
        // The Fake resolves the next instance from this list (begin >= now).
        fakeCalendarProviderRepository.instances = listOf(
            makeDeviceInstance(42L, nextOccurrence, nextEnd, isAllDay = false, title = "Weekly sync")
        )

        val nextDayCode = DateTimeUtils.eventTsToDayCode(nextOccurrence, isAllDay = false)
        coEvery {
            displayEventRepository.getDisplayEventsGroupedByDayOnce(nextDayCode, nextDayCode)
        } returns persistentMapOf(
            nextDayCode to persistentListOf(
                DisplayEvent.Device(
                    makeDeviceInstance(42L, nextOccurrence, nextEnd, isAllDay = false, title = "Weekly sync")
                )
            )
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.getDeviceEventForQuickViewById(42L)
        advanceUntilIdle()

        assertNotNull("Recurring event should resolve at its next instance", result)
        assertEquals(nextOccurrence, result!!.startTs)
    }

    @Test
    fun `getDeviceEventForQuickViewById finds a recurring instance whose start is earlier today than now`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

        // The instance start is a few hours BEFORE the real clock (modelling an all-day
        // occurrence whose UTC-midnight BEGIN precedes a mid-day "now", or a timed occurrence
        // already started today). The lookup uses System.currentTimeMillis() as its anchor, so
        // without the lower-bound pad this instance (start < now) would be filtered out and the
        // sheet would skip to next week. With the pad it must resolve to THIS instance.
        val now = System.currentTimeMillis()
        val instanceStart = now - 6L * 60L * 60L * 1000L // 6 hours ago
        val instanceEnd = instanceStart + 60L * 60L * 1000L
        fakeCalendarProviderRepository.deviceEvents[42L] =
            makeDeviceEvent(id = 42L, startTs = now - 90L * 24L * 60L * 60L * 1000L, isAllDay = false, rrule = "FREQ=WEEKLY")
        fakeCalendarProviderRepository.instances = listOf(
            makeDeviceInstance(42L, instanceStart, instanceEnd, isAllDay = false, title = "Earlier today")
        )

        val timedDayCode = DateTimeUtils.eventTsToDayCode(instanceStart, isAllDay = false)
        val allDayDayCode = DateTimeUtils.eventTsToDayCode(instanceStart, isAllDay = true)
        coEvery {
            displayEventRepository.getDisplayEventsGroupedByDayOnce(
                minOf(timedDayCode, allDayDayCode),
                maxOf(timedDayCode, allDayDayCode),
            )
        } returns persistentMapOf(
            timedDayCode to persistentListOf(
                DisplayEvent.Device(
                    makeDeviceInstance(42L, instanceStart, instanceEnd, isAllDay = false, title = "Earlier today")
                )
            )
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.getDeviceEventForQuickViewById(42L)
        advanceUntilIdle()

        assertNotNull("Recurring instance starting earlier today (start < now) must still resolve", result)
        assertEquals(instanceStart, result!!.startTs)
    }

    @Test
    fun `getDeviceEventForQuickViewById returns null for an ended recurring series`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

        // Recurring event with no remaining future instances. The caller falls back to
        // navigate-to-date; here we just assert the lookup yields null (no sheet).
        val firstOccurrence = Instant.parse("2023-01-02T09:00:00Z").toEpochMilli()
        fakeCalendarProviderRepository.deviceEvents[42L] =
            makeDeviceEvent(id = 42L, startTs = firstOccurrence, isAllDay = false, rrule = "FREQ=WEEKLY;COUNT=3")
        fakeCalendarProviderRepository.instances = emptyList() // no future occurrence

        val viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.getDeviceEventForQuickViewById(42L)
        advanceUntilIdle()

        assertNull("Ended recurring series should yield null (caller falls back to date nav)", result)
    }

    // ==================== getDeviceEventAttendeeState ====================

    @Test
    fun `getDeviceEventAttendeeState returns empty when event has no attendees`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.getDeviceEventAttendeeState(eventId = 42L, calendarId = 1L)
        advanceUntilIdle()

        assertEquals(0, state.models.size)
        org.junit.Assert.assertFalse(state.isCurrentUserOnList)
    }

    @Test
    fun `getDeviceEventAttendeeState maps attendees and marks owner as on-list`() = runTest {
        fakeCalendarProviderRepository.calendars = listOf(
            org.onekash.kashcal.data.calendar_provider.DeviceCalendar(
                id = 1L,
                displayName = "Work",
                color = 0,
                accountName = "me@example.com",
                accountType = "com.google",
                visible = true,
                accessLevel = 700,
                ownerAccount = "me@example.com",
            )
        )
        fakeCalendarProviderRepository.deviceAttendees[42L] = listOf(
            org.onekash.kashcal.data.calendar_provider.DeviceAttendee(
                id = 1L, name = "Me", email = "me@example.com",
                relationship = android.provider.CalendarContract.Attendees.RELATIONSHIP_ORGANIZER,
                status = android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED,
            ),
            org.onekash.kashcal.data.calendar_provider.DeviceAttendee(
                id = 2L, name = "Bob", email = "bob@example.com",
                relationship = android.provider.CalendarContract.Attendees.RELATIONSHIP_ATTENDEE,
                status = android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_NONE,
            ),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.getDeviceEventAttendeeState(eventId = 42L, calendarId = 1L)
        advanceUntilIdle()

        // Organizer-flag mapping is asserted under Robolectric in
        // AttendeeUiModelFromDeviceTest; this plain-JVM VM test covers the
        // plumbing (count + owner→on-list by email match, both constant-free).
        assertEquals(2, state.models.size)
        org.junit.Assert.assertTrue("Owner should be on the list", state.isCurrentUserOnList)
    }

    @Test
    fun `getDeviceEventAttendeeState with null calendarId marks no one as on-list`() = runTest {
        fakeCalendarProviderRepository.deviceAttendees[42L] = listOf(
            org.onekash.kashcal.data.calendar_provider.DeviceAttendee(
                id = 1L, name = "Bob", email = "bob@example.com",
                relationship = android.provider.CalendarContract.Attendees.RELATIONSHIP_ATTENDEE,
                status = android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_NONE,
            ),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.getDeviceEventAttendeeState(eventId = 42L, calendarId = null)
        advanceUntilIdle()

        assertEquals(1, state.models.size)
        org.junit.Assert.assertFalse(state.isCurrentUserOnList)
    }

    private fun makeDeviceEvent(
        id: Long,
        startTs: Long,
        isAllDay: Boolean,
        rrule: String? = null,
    ) = org.onekash.kashcal.data.calendar_provider.DeviceEvent(
        id = id,
        calendarId = 1L,
        title = "Event $id",
        description = null,
        location = null,
        startTs = startTs,
        endTs = if (isAllDay) null else startTs + 3_600_000L,
        duration = null,
        isAllDay = isAllDay,
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