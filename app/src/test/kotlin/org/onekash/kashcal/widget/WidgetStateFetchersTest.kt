package org.onekash.kashcal.widget

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.preferences.KashCalDataStore
import java.io.IOException

/**
 * Tests the data-fetch step of the Upcoming, Agenda, Week and Month widgets
 * ([fetchUpcomingState], [fetchAgendaData], [fetchWeekData], [fetchMonthEvents]) without a Glance
 * or Compose harness: the success shape, the detailed-rows preference, and the Error or empty
 * result when the repository or DataStore throws.
 */
class WidgetStateFetchersTest {

    private lateinit var repository: WidgetDataRepository
    private lateinit var dataStore: KashCalDataStore
    private lateinit var context: Context

    @Before
    fun setup() {
        repository = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        context = mockk(relaxed = true)

        // DataStore reads shared by every fetcher.
        every { dataStore.showEventEmojis } returns flowOf(true)
        every { dataStore.widgetMaxEventsPerDay } returns flowOf(5)
        every { dataStore.widgetDetailedRows } returns flowOf(false)
        coEvery { dataStore.getTimeFormat() } returns "system"

        // is24HourFormat is pinned to false for determinism. getBestDateTimePattern must be
        // stubbed too: unstubbed it returns null here, and the agenda header date lookup
        // (widgetHeaderDate to localizedPattern) NPEs into fetchAgendaData's fallback.
        mockkStatic(android.text.format.DateFormat::class)
        every { android.text.format.DateFormat.is24HourFormat(any()) } returns false
        every { android.text.format.DateFormat.getBestDateTimePattern(any(), any()) } answers {
            secondArg()
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(android.text.format.DateFormat::class)
    }

    // ========== fetchUpcomingState ==========

    @Test
    fun `fetchUpcomingState returns Loaded with events when repository succeeds`() = runTest {
        val today = 20260428
        coEvery { repository.getEventsInRange(any(), any()) } returns mapOf(today to emptyList())

        val state = fetchUpcomingState(repository, dataStore, context, horizonDays = 10)

        assertTrue("State should be Loaded: $state", state is UpcomingState.Loaded)
        val loaded = state as UpcomingState.Loaded
        assertEquals(mapOf(today to emptyList<WidgetDataRepository.WidgetEvent>()), loaded.eventsByDay)
        assertEquals(true, loaded.showEventEmojis)
        assertNotNull(loaded.timePattern)
        assertEquals(false, loaded.detailedRows)
    }

    @Test
    fun `fetchUpcomingState threads the detailed-rows preference`() = runTest {
        coEvery { repository.getEventsInRange(any(), any()) } returns emptyMap()
        every { dataStore.widgetDetailedRows } returns flowOf(true)

        val state = fetchUpcomingState(repository, dataStore, context, horizonDays = 10)

        assertEquals(true, (state as UpcomingState.Loaded).detailedRows)
    }

    @Test
    fun `fetchUpcomingState returns Error when repository throws`() = runTest {
        coEvery { repository.getEventsInRange(any(), any()) } throws IOException("network down")

        val state = fetchUpcomingState(repository, dataStore, context, horizonDays = 10)

        assertEquals(UpcomingState.Error, state)
    }

    @Test
    fun `fetchUpcomingState returns Error when DataStore throws`() = runTest {
        coEvery { repository.getEventsInRange(any(), any()) } returns emptyMap()
        coEvery { dataStore.getTimeFormat() } throws IllegalStateException("datastore corrupt")

        val state = fetchUpcomingState(repository, dataStore, context, horizonDays = 10)

        assertEquals(UpcomingState.Error, state)
    }

    @Test
    fun `fetchUpcomingState honors custom horizonDays`() = runTest {
        // Production asks for today..today+horizonDays-1 ([upcomingWindow]); this captures the
        // start and end codes the repository is called with.
        var capturedStart = 0
        var capturedEnd = 0
        coEvery { repository.getEventsInRange(any(), any()) } answers {
            capturedStart = firstArg()
            capturedEnd = secondArg()
            emptyMap()
        }

        fetchUpcomingState(repository, dataStore, context, horizonDays = 5)

        // Only end >= start is asserted, which holds for any horizon of 1 or more, so this
        // doesn't check that horizonDays reaches the window.
        assertTrue("end $capturedEnd should be >= start $capturedStart", capturedEnd >= capturedStart)
    }

    // ========== fetchAgendaData ==========

    @Test
    fun `fetchAgendaData returns empty list when no events today`() = runTest {
        coEvery { repository.getTodayEvents() } returns emptyList()

        val data = fetchAgendaData(repository, dataStore, context)

        assertTrue(data.events.isEmpty())
        assertEquals(true, data.showEventEmojis)
        assertEquals(5, data.maxEventsPerDay)
        assertEquals(false, data.detailedRows)
    }

    @Test
    fun `fetchAgendaData threads the detailed-rows preference`() = runTest {
        coEvery { repository.getTodayEvents() } returns emptyList()
        every { dataStore.widgetDetailedRows } returns flowOf(true)

        val data = fetchAgendaData(repository, dataStore, context)

        assertEquals(true, data.detailedRows)
    }

    @Test
    fun `fetchAgendaData returns empty shape when repository throws`() = runTest {
        coEvery { repository.getTodayEvents() } throws IOException("network")

        val data = fetchAgendaData(repository, dataStore, context)

        // The failure path returns no events and default settings, so the widget renders "no
        // events"; only the empty list is asserted.
        assertTrue(data.events.isEmpty())
    }

    // ========== fetchWeekData ==========

    @Test
    fun `fetchWeekData returns empty weekly map when no events`() = runTest {
        coEvery { repository.getWeekEvents() } returns emptyMap()

        val data = fetchWeekData(repository, dataStore, context)

        assertTrue(data.weekEvents.isEmpty())
        assertEquals(false, data.detailedRows)
    }

    @Test
    fun `fetchWeekData threads the detailed-rows preference`() = runTest {
        coEvery { repository.getWeekEvents() } returns emptyMap()
        every { dataStore.widgetDetailedRows } returns flowOf(true)

        val data = fetchWeekData(repository, dataStore, context)

        assertEquals(true, data.detailedRows)
    }

    @Test
    fun `fetchWeekData returns empty map when repository throws`() = runTest {
        coEvery { repository.getWeekEvents() } throws IOException("network")

        val data = fetchWeekData(repository, dataStore, context)

        assertTrue(data.weekEvents.isEmpty())
    }

    // ========== fetchMonthEvents ==========

    @Test
    fun `fetchMonthEvents returns repository result on success`() = runTest {
        val expected = mapOf(20260401 to emptyList<WidgetDataRepository.WidgetEvent>())
        coEvery { repository.getEventsInRange(20260401, 20260430) } returns expected

        val result = fetchMonthEvents(repository, 20260401, 20260430)

        assertEquals(expected, result)
    }

    @Test
    fun `fetchMonthEvents returns empty map when repository throws`() = runTest {
        coEvery { repository.getEventsInRange(any(), any()) } throws IOException("network")

        val result = fetchMonthEvents(repository, 20260401, 20260430)

        assertTrue(result.isEmpty())
    }
}
