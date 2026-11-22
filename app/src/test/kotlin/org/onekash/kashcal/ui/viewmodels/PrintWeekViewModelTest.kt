package org.onekash.kashcal.ui.viewmodels

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.contacts.ContactEventType
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.planner.PlannerTestEvents
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.ui.print.PrintCalendarGroup
import org.onekash.kashcal.ui.print.PrintCalendarKey
import org.onekash.kashcal.ui.print.PrintCalendarRow
import org.onekash.kashcal.ui.print.PrintCalendarSection
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Tests [PrintWeekViewModel]: what the print sheet starts with each time, how its choices
 * change, the read and document a Print makes, and where each print result goes. Uses a real
 * file-backed DataStore and real string resources; the two readers are strict mocks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PrintWeekViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var dataStore: KashCalDataStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var repository: DisplayEventRepository
    private lateinit var reader: EventReader
    private lateinit var savedZone: TimeZone

    private val ny = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 10, 26)
    private val sunday = LocalDate.of(2026, 10, 25)
    private var printEvents: List<DisplayEvent> = emptyList()
    private val readStart = slot<Long>()
    private val readEnd = slot<Long>()
    private val readRoom = slot<Set<Long>>()
    private val readDevice = slot<Set<Long>>()

    private val work = PrintCalendarRow(PrintCalendarKey.Room(1L), "Work", 0xFF1E88E5.toInt(), hidden = false)
    private val private = PrintCalendarRow(PrintCalendarKey.Room(2L), "Private", 0xFF8E24AA.toInt(), hidden = true)
    private val kids = PrintCalendarRow(PrintCalendarKey.Device(100L), "Kids school", 0xFFFB8C00.toInt(), hidden = false)
    private val sections = listOf(
        PrintCalendarSection("alex@example.test", listOf(PrintCalendarGroup(null, listOf(work, private))), "room_2"),
        PrintCalendarSection("Device calendars", listOf(PrintCalendarGroup("alex@gmail.example", listOf(kids))), "device"),
    )

    @Before
    fun setUp() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ny))
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dataStoreFile = File(context.filesDir, "test_print_vm_${System.nanoTime()}.preferences_pb")
        dataStore = KashCalDataStore(context, PreferenceDataStoreFactory.create(scope = dataStoreScope) { dataStoreFile })
        repository = mockk {
            coEvery {
                getDisplayEventsForPrint(capture(readStart), capture(readEnd), capture(readRoom), capture(readDevice))
            } answers { printEvents }
        }
        // The series look-up reads only for changed occurrences; none of these events is one.
        reader = mockk()
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
        dataStoreFile.delete()
        TimeZone.setDefault(savedZone)
    }

    private fun viewModel(device24h: Boolean = false) = PrintWeekViewModel(
        displayEventRepository = repository,
        eventReader = reader,
        dataStore = dataStore,
        context = context,
        workDispatcher = testDispatcher,
        is24HourDevice = { device24h },
        localeProvider = { Locale.US },
        todayProvider = { today },
    )

    private fun ms(date: LocalDate) = date.atStartOfDay(ny).toInstant().toEpochMilli()

    private fun timed(id: Long, day: LocalDate, h: Int, m: Int, eh: Int, em: Int, title: String) =
        PlannerTestEvents.room(
            id = id, startTs = PlannerTestEvents.at(day, h, m, zone = ny),
            endTs = PlannerTestEvents.at(day, eh, em, zone = ny), zone = ny, title = title,
        )

    private suspend fun PrintWeekViewModel.nextEvent(): PrintEvent? =
        withTimeoutOrNull(1_000) { events.first() }

    // ==================== Opening ====================

    @Test
    fun `opening picks the anchor's week by KashCal's first day of week`() {
        val vm = viewModel()
        vm.open(today, Calendar.SUNDAY, sections)
        assertEquals(sunday, vm.uiState.value.weekStart)
        assertEquals("Oct 25 – 31, 2026", vm.uiState.value.weekRangeLabel)

        vm.open(today, Calendar.MONDAY, sections)
        assertEquals(today, vm.uiState.value.weekStart)

        vm.open(today, Calendar.SATURDAY, sections)
        assertEquals(LocalDate.of(2026, 10, 24), vm.uiState.value.weekStart)
    }

    @Test
    fun `each open ticks both pages and exactly the visible calendars`() {
        val vm = viewModel()
        vm.open(today, Calendar.SUNDAY, sections)
        val state = vm.uiState.value
        assertTrue(state.isOpen)
        assertTrue(state.includeGrid && state.includeDetails)
        assertEquals(setOf(work.key, kids.key), state.selected)

        vm.toggleGrid()
        vm.toggleCalendar(private.key)
        vm.toggleCalendar(work.key)
        vm.dismiss()
        assertFalse(vm.uiState.value.isOpen)

        vm.open(today, Calendar.SUNDAY, sections)
        assertTrue(vm.uiState.value.includeGrid)
        assertEquals(setOf(work.key, kids.key), vm.uiState.value.selected)
    }

    @Test
    fun `previous and next move a week`() {
        val vm = viewModel()
        vm.open(today, Calendar.SUNDAY, sections)
        vm.nextWeek()
        assertEquals(sunday.plusDays(7), vm.uiState.value.weekStart)
        assertEquals("Nov 1 – 7, 2026", vm.uiState.value.weekRangeLabel)
        vm.previousWeek()
        vm.previousWeek()
        assertEquals(sunday.minusDays(7), vm.uiState.value.weekStart)
    }

    @Test
    fun `Print is enabled only with a page and a calendar ticked, and the counts follow`() {
        val vm = viewModel()
        vm.open(today, Calendar.SUNDAY, sections)
        assertTrue(vm.uiState.value.canPrint)
        assertEquals(2, vm.uiState.value.pageCount)
        assertEquals(2, vm.uiState.value.selectedCount)
        assertEquals(3, vm.uiState.value.listedCount)

        vm.toggleGrid(); vm.toggleDetails()
        assertEquals(0, vm.uiState.value.pageCount)
        assertFalse(vm.uiState.value.canPrint)

        vm.toggleDetails()
        vm.toggleCalendar(work.key); vm.toggleCalendar(kids.key)
        assertEquals(0, vm.uiState.value.selectedCount)
        assertFalse(vm.uiState.value.canPrint)
    }

    // ==================== Printing ====================

    @Test
    fun `Print reads the ticked calendars for exactly the shown week`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.open(LocalDate.of(2026, 11, 2), Calendar.SUNDAY, sections)
        vm.toggleCalendar(private.key)
        vm.print()
        advanceUntilIdle()

        assertTrue(vm.nextEvent() is PrintEvent.Request)
        val nov1 = LocalDate.of(2026, 11, 1)
        assertEquals(ms(nov1), readStart.captured)
        assertEquals(ms(nov1.plusDays(7)), readEnd.captured)
        // The week holds the fall-back day, so it is 169 hours long.
        assertEquals(169L * 3_600_000L, readEnd.captured - readStart.captured)
        assertEquals(setOf(1L, 2L), readRoom.captured)
        assertEquals(setOf(100L), readDevice.captured)
    }

    @Test
    fun `the document follows KashCal's time format over the phone's`() = runTest(testDispatcher) {
        printEvents = listOf(timed(1, sunday.plusDays(1), 9, 30, 10, 30, "Design review"))

        dataStore.setTimeFormat("12h")
        val twelve = viewModel(device24h = true)
        twelve.open(today, Calendar.SUNDAY, sections); twelve.print(); advanceUntilIdle()
        assertTrue((twelve.nextEvent() as PrintEvent.Request).html.contains("9:30–10:30 am"))

        dataStore.setTimeFormat("24h")
        val twentyFour = viewModel(device24h = false)
        twentyFour.open(today, Calendar.SUNDAY, sections); twentyFour.print(); advanceUntilIdle()
        assertTrue((twentyFour.nextEvent() as PrintEvent.Request).html.contains("09:30–10:30"))

        dataStore.setTimeFormat("system")
        val system = viewModel(device24h = true)
        system.open(today, Calendar.SUNDAY, sections); system.print(); advanceUntilIdle()
        assertTrue((system.nextEvent() as PrintEvent.Request).html.contains("09:30–10:30"))
    }

    @Test
    fun `the request carries the job name and the printed calendars`() = runTest(testDispatcher) {
        printEvents = listOf(timed(1, sunday.plusDays(1), 9, 30, 10, 30, "Design review"))
        val vm = viewModel()
        vm.open(today, Calendar.SUNDAY, sections)
        vm.print(); advanceUntilIdle()

        val request = vm.nextEvent() as PrintEvent.Request
        assertEquals("KashCal – Week of Oct 25", request.jobName)
        assertTrue(request.html.contains("<title>KashCal – Week of Oct 25</title>"))
        assertTrue(request.html.contains(">Work</span>"))
        assertTrue(request.html.contains(">Kids school</span>"))
        assertFalse(request.html.contains(">Private</span>"))
        assertTrue(request.html.contains("2 of 3 calendars"))
    }

    @Test
    fun `a contact birthday prints with its age title`() = runTest(testDispatcher) {
        val alex = PlannerTestEvents.roomAllDay(5, sunday.plusDays(3), zone = ny).let {
            it.copy(
                event = it.event.copy(
                    title = "Alex",
                    caldavUrl = ContactEventType.BIRTHDAY.getCaldavUrl("lookup", 10, 28),
                    description = "birthYear:2021",
                )
            )
        }
        printEvents = listOf(alex)
        val vm = viewModel()
        vm.open(today, Calendar.SUNDAY, sections)
        vm.print(); advanceUntilIdle()

        val html = (vm.nextEvent() as PrintEvent.Request).html
        assertTrue(html.contains("Alex&#39;s 5th Birthday"))
        assertFalse(html.contains("birthYear"))
    }

    @Test
    fun `a second Print while one is under way does nothing`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.open(today, Calendar.SUNDAY, sections)
        vm.print()
        vm.print()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isPrinting)
        assertTrue(vm.nextEvent() is PrintEvent.Request)
        assertNull(vm.nextEvent())
        coVerify(exactly = 1) { repository.getDisplayEventsForPrint(any(), any(), any(), any()) }
    }

    @Test
    fun `a failed read shows the error in the sheet and leaves it usable`() = runTest(testDispatcher) {
        coEvery { repository.getDisplayEventsForPrint(any(), any(), any(), any()) } throws IllegalStateException("db closed")
        val vm = viewModel()
        vm.open(today, Calendar.SUNDAY, sections)
        vm.print(); advanceUntilIdle()

        assertEquals(PrintEvent.SheetError(context.getString(org.onekash.kashcal.R.string.print_failed)), vm.nextEvent())
        assertTrue(vm.uiState.value.isOpen)
        assertFalse(vm.uiState.value.isPrinting)
        assertTrue(vm.uiState.value.canPrint)
    }

    @Test
    fun `closing the sheet while the document builds prints nothing`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.open(today, Calendar.SUNDAY, sections)
        vm.print()
        vm.dismiss()
        advanceUntilIdle()

        assertNull(vm.nextEvent())
        assertFalse(vm.uiState.value.isPrinting)
    }

    @Test
    fun `reopening while an old document builds drops the old one`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.open(today, Calendar.SUNDAY, sections)
        vm.print()
        vm.open(today.plusDays(7), Calendar.SUNDAY, sections)
        advanceUntilIdle()

        assertNull(vm.nextEvent())
        vm.print(); advanceUntilIdle()
        val request = vm.nextEvent() as PrintEvent.Request
        assertEquals("KashCal – Week of Nov 1", request.jobName)
    }

    @Test
    fun `the week label is written in the language's own order`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val vm = viewModel()
            vm.open(today, Calendar.MONDAY, sections)
            val label = vm.uiState.value.weekRangeLabel
            assertTrue(label, label.startsWith("26."))
            assertTrue(label, label.contains("2026"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `the System first day follows the locale, as the Week view does`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val vm = viewModel()
            vm.open(today.plusDays(2), 0, sections)
            assertEquals(today, vm.uiState.value.weekStart)
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `titles print as the app shows them, with the emoji setting`() = runTest(testDispatcher) {
        val lunch = timed(1, sunday.plusDays(1), 12, 0, 13, 0, "Team lunch")
        printEvents = listOf(lunch)

        dataStore.setShowEventEmojis(false)
        val plain = viewModel()
        plain.open(today, Calendar.SUNDAY, sections); plain.print(); advanceUntilIdle()
        assertTrue((plain.nextEvent() as PrintEvent.Request).html.contains(">Team lunch</span>"))

        dataStore.setShowEventEmojis(true)
        val withEmoji = viewModel()
        withEmoji.open(today, Calendar.SUNDAY, sections); withEmoji.print(); advanceUntilIdle()
        val shown = org.onekash.kashcal.ui.components.formatDisplayEventTitle(lunch, true, context.resources)
        assertTrue(shown != "Team lunch")
        assertTrue((withEmoji.nextEvent() as PrintEvent.Request).html.contains(">$shown</span>"))
    }

    // ==================== Print results ====================

    @Test
    fun `Started closes the sheet and a later failure goes to the home screen`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.open(today, Calendar.SUNDAY, sections)
        vm.print(); advanceUntilIdle()
        vm.nextEvent()

        vm.onPrintStarted()
        assertFalse(vm.uiState.value.isOpen)
        assertFalse(vm.uiState.value.isPrinting)

        vm.onPrintFailed(); advanceUntilIdle()
        assertEquals(PrintEvent.HomeError(context.getString(org.onekash.kashcal.R.string.print_failed)), vm.nextEvent())
    }

    @Test
    fun `a failure before hand-off shows in the sheet`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.open(today, Calendar.SUNDAY, sections)
        vm.print(); advanceUntilIdle()
        vm.nextEvent()

        vm.onPrintFailed(); advanceUntilIdle()
        assertTrue(vm.nextEvent() is PrintEvent.SheetError)
        assertTrue(vm.uiState.value.isOpen)
        assertFalse(vm.uiState.value.isPrinting)
    }

    @Test
    fun `a cancelled print can be started again`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.open(today, Calendar.SUNDAY, sections)
        vm.print(); advanceUntilIdle()
        vm.nextEvent()

        vm.onPrintCancelled()
        assertFalse(vm.uiState.value.isPrinting)
        vm.print(); advanceUntilIdle()
        assertTrue(vm.nextEvent() is PrintEvent.Request)
    }

    @Test
    fun `dismissing while printing clears it`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.open(today, Calendar.SUNDAY, sections)
        vm.print(); advanceUntilIdle()
        vm.nextEvent()
        vm.dismiss()
        assertFalse(vm.uiState.value.isPrinting)
        assertFalse(vm.uiState.value.isOpen)
    }

    @Test
    fun `printing writes no setting`() = runTest(testDispatcher) {
        val before = dataStoreFile.takeIf { it.exists() }?.readBytes()
        val vm = viewModel()
        vm.open(today, Calendar.SUNDAY, sections)
        vm.toggleGrid(); vm.toggleCalendar(private.key); vm.nextWeek()
        vm.print(); advanceUntilIdle()
        val after = dataStoreFile.takeIf { it.exists() }?.readBytes()
        assertEquals(before?.toList(), after?.toList())
    }
}
