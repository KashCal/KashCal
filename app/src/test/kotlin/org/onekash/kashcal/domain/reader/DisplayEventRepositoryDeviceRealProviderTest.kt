package org.onekash.kashcal.domain.reader

import android.provider.CalendarContract.Attendees
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.CalendarProviderManager
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.DAY
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.DAY0
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.DAY_CODE
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.HOUR
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.LOCAL_CAL
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.OWNER
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.SYNCED_CAL
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.T0
import org.onekash.kashcal.data.calendar_provider.SqliteCalendarProvider
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.DisplayEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests what the grid and the widgets get for device events: [DisplayEventRepository] over the
 * real provider repository with no Room events, bucketed by day, honouring the calendar
 * selection and the show-declined setting. The one-shot read the widgets use
 * ([DisplayEventRepository.getDisplayEventsGroupedByDayOnce]) matches the grid's Flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DisplayEventRepositoryDeviceRealProviderTest {

    private val f = DeviceRoundTripFixture()
    private var showDeclined = true
    private var enabledIds = setOf(SYNCED_CAL, LOCAL_CAL)
    private val hiddenFlow = MutableStateFlow(emptySet<Long>())
    private var hiddenIds: Set<Long>
        get() = hiddenFlow.value
        set(value) { hiddenFlow.value = value }

    private lateinit var display: DisplayEventRepository

    @Before
    fun setUp() {
        f.setUp()
        val eventReader: EventReader = mockk {
            every { getVisibleOccurrencesWithEventsInRangeFlow(any(), any()) } returns flowOf(emptyList())
        }
        val manager: CalendarProviderManager = mockk {
            every { changeSignal } returns MutableStateFlow(0)
        }
        val dataStore: KashCalDataStore = mockk {
            coEvery { getShowDeclinedEvents() } answers { showDeclined }
            coEvery { getDeviceCalendarsEnabled() } returns true
            coEvery { getEnabledDeviceCalendarIds() } answers { enabledIds }
            coEvery { getHiddenDeviceCalendarIds() } answers { hiddenIds }
            every { hiddenDeviceCalendarIds } returns hiddenFlow
            every { showDeclinedEvents } answers { MutableStateFlow(showDeclined) }
        }
        // No Room events, so the declined-policy lookups are never reached; the
        // strict mocks throw if they are. The grid's flow also listens for guest
        // changes.
        val attendeesDao: AttendeesDao = mockk {
            every { attendeesChangeSignal() } returns flowOf(0)
        }
        val accountsDao: AccountsDao = mockk()
        // These tests are about the device half; the series expansion a read runs is stubbed.
        val generator: OccurrenceGenerator = mockk { coJustRun { ensureCovered(any(), any(), any(), any(), any()) } }
        display = DisplayEventRepository(eventReader, f.repository, manager, dataStore, attendeesDao, accountsDao, generator)
    }

    @After
    fun tearDown() = f.tearDown()

    private suspend fun create(draftTitle: String, start: Long = T0, end: Long = T0 + HOUR, allDay: Boolean = false, calendarId: Long = SYNCED_CAL): Long =
        f.writer.createEvent(f.draft(title = draftTitle, startTs = start, endTs = end, isAllDay = allDay, calendarId = calendarId)).getOrThrow().eventId

    private suspend fun titlesByDay(start: Int = DAY_CODE, end: Int = DAY_CODE + 3): Map<Int, List<String>> =
        display.getDisplayEventsGroupedByDayOnce(start, end)
            .mapValues { (_, events) -> events.map { (it as DisplayEvent.Device).instance.title } }
            .filterValues { it.isNotEmpty() }

    @Test
    fun `timed, all-day and multi-day device events land in the days they occupy`() = runTest {
        create("Timed")
        create("All day", start = DAY0 + DAY, end = DAY0 + 2 * DAY - 1, allDay = true)
        create("Three days", start = DAY0 + DAY, end = DAY0 + 4 * DAY - 1, allDay = true)
        // 22:00 Berlin on the 5th until 01:00 on the 6th.
        create("Late", start = T0 + 6 * HOUR, end = T0 + 9 * HOUR)

        val byDay = titlesByDay()
        assertEquals(listOf("Timed", "Late"), byDay[DAY_CODE])
        assertEquals(setOf("All day", "Three days", "Late"), byDay[DAY_CODE + 1]!!.toSet())
        assertEquals(listOf("Three days"), byDay[DAY_CODE + 2])
        assertEquals(listOf("Three days"), byDay[DAY_CODE + 3])
    }

    @Test
    fun `events of hidden or unticked calendars are left out`() = runTest {
        create("Work", calendarId = SYNCED_CAL)
        create("Personal", calendarId = LOCAL_CAL)

        hiddenIds = setOf(LOCAL_CAL)
        assertEquals(listOf("Work"), titlesByDay()[DAY_CODE])

        hiddenIds = emptySet()
        enabledIds = setOf(LOCAL_CAL)
        assertEquals(listOf("Personal"), titlesByDay()[DAY_CODE])
    }

    @Test
    fun `hiding a device calendar updates the open views`() = runTest {
        create("Work", calendarId = SYNCED_CAL)
        create("Personal", calendarId = LOCAL_CAL)
        val titles = { events: List<DisplayEvent>? ->
            events.orEmpty().map { (it as DisplayEvent.Device).instance.title }.sorted()
        }
        val start = DAY0
        val end = DAY0 + DAY - 1

        // The month grid and Planner, the week grid and agenda, and the day pager, as each
        // view collects them while it shows.
        display.getDisplayEventsForDateRange(DAY_CODE, DAY_CODE).test {
            assertEquals(listOf("Personal", "Work"), titles(awaitItem()[DAY_CODE]))
            hiddenIds = setOf(LOCAL_CAL)
            assertEquals(listOf("Work"), titles(awaitItem()[DAY_CODE]))
            // Showing it again brings its events back.
            hiddenIds = emptySet()
            assertEquals(listOf("Personal", "Work"), titles(awaitItem()[DAY_CODE]))
            cancelAndIgnoreRemainingEvents()
        }
        display.getDisplayEventsForRange(start, end).test {
            assertEquals(listOf("Personal", "Work"), titles(awaitItem()))
            hiddenIds = setOf(SYNCED_CAL)
            assertEquals(listOf("Personal"), titles(awaitItem()))
            cancelAndIgnoreRemainingEvents()
        }
        hiddenIds = emptySet()
        display.getDisplayEventsForDayRange(T0).test {
            assertEquals(listOf("Personal", "Work"), titles(awaitItem()[DAY_CODE]))
            hiddenIds = setOf(LOCAL_CAL)
            assertEquals(listOf("Work"), titles(awaitItem()[DAY_CODE]))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a declined device event shows only while declined events are shown`() = runTest {
        val id = create("Offsite")
        f.provider.insertRow(
            SqliteCalendarProvider.ATTENDEES, Attendees.EVENT_ID to id, Attendees.ATTENDEE_EMAIL to OWNER,
            Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_INVITED,
        )
        f.writer.replyRsvp(id, SYNCED_CAL, Attendees.ATTENDEE_STATUS_DECLINED)

        showDeclined = true
        assertEquals(listOf("Offsite"), titlesByDay()[DAY_CODE])

        showDeclined = false
        assertTrue(titlesByDay().isEmpty())
    }

    @Test
    fun `the widget read and the grid read agree`() = runTest {
        create("Timed")
        create("All day", start = DAY0, end = DAY0 + DAY - 1, allDay = true)

        val once = display.getDisplayEventsGroupedByDayOnce(DAY_CODE, DAY_CODE)
        val grid = display.getDisplayEventsForDateRange(DAY_CODE, DAY_CODE).first()
        val titles = { events: List<DisplayEvent>? -> events?.map { (it as DisplayEvent.Device).instance.title } }
        assertEquals(listOf("Timed", "All day").sorted(), titles(once[DAY_CODE])!!.sorted())
        assertEquals(titles(once[DAY_CODE]), titles(grid[DAY_CODE]))
    }
}
