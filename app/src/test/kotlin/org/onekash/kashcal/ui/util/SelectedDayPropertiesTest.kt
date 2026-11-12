package org.onekash.kashcal.ui.util

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.localDate
import io.kotest.property.arbitrary.localTime
import io.kotest.property.checkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.testutil.PropertyRuns
import org.onekash.kashcal.testutil.SplitZone
import org.onekash.kashcal.testutil.resolveProjectRoot
import org.onekash.kashcal.testutil.withDeviceTimeZone
import org.onekash.kashcal.testutil.withSplitDeviceTimeZone
import org.onekash.kashcal.ui.components.pickers.untilDisplayMillis
import org.onekash.kashcal.ui.components.pickers.untilForPickedDate
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.model.MonthGrid
import org.onekash.kashcal.ui.viewmodels.DateFilter
import org.onekash.kashcal.ui.viewmodels.DeviceHomeViewModelTestFactory
import org.onekash.kashcal.ui.viewmodels.HomeViewModel
import org.onekash.kashcal.util.DateTimeUtils
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.zone.ZoneOffsetTransition
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Locale

/**
 * Properties of the selected day over generated dates, times of day, first days of week and cell
 * positions, with the real [HomeViewModel.selectDate] in between: a tap selects and highlights
 * the tapped day in every zone, split ([SplitZone], as Android reads dates after its cutoff year)
 * or not, and in real zones the result equals the Calendar-based code the grids ran before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SelectedDayPropertiesTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun newViewModel(): HomeViewModel {
        val factory = DeviceHomeViewModelTestFactory()
        val device = FakeCalendarProviderRepository()
        return factory.create(device.deviceEventReader(), device.deviceEventWriter(factory.dataStore), dispatcher)
    }

    /** Dates well inside the pagers' range. */
    private val dates = Arb.localDate(LocalDate.of(1926, 1, 1), LocalDate.of(2125, 12, 31))
    private val firstDays = Arb.element(0, Calendar.SUNDAY, Calendar.MONDAY, Calendar.SATURDAY)

    /** Which grid shows the tapped day: its own month's, or a neighbour's as a padding day. */
    private enum class Shown { OWN, PREVIOUS_MONTH, NEXT_MONTH }
    private val shownIn = Arb.element(Shown.entries)

    private data class Tap(val grid: MonthGrid, val cell: MonthGrid.DayCell)

    /** Finds [date]'s cell in the grid [shown] picks, falling back to its own month's grid. */
    private fun tapFor(date: LocalDate, shown: Shown, firstDay: Int): Tap {
        val own = YearMonth.from(date)
        val month = when (shown) {
            Shown.OWN -> own
            // A grid's next-month padding holds the start of the following month.
            Shown.PREVIOUS_MONTH -> own.minusMonths(1)
            Shown.NEXT_MONTH -> own.plusMonths(1)
        }
        val code = DayPagerUtils.localDateToDayCode(date)
        fun find(ym: YearMonth): Tap? {
            val grid = MonthGrid.compute(ym.year, ym.monthValue - 1, firstDay)
            val cell = grid.weeks.flatten().firstOrNull { MonthGrid.computeDayCodeForCell(it, grid.year, grid.month) == code }
            return cell?.let { Tap(grid, it) }
        }
        return find(month) ?: find(own)!!
    }

    private fun highlighted(grid: MonthGrid, selectedDate: Long, isSelected: (MonthGrid.DayCell, Long) -> Boolean) =
        grid.weeks.flatten().filter { isSelected(it, selectedDate) }
            .map { MonthGrid.computeDayCodeForCell(it, grid.year, grid.month) }

    /** The grids' highlight: the cell's day code against the selected day's, read once. */
    private fun newHighlight(grid: MonthGrid) = { cell: MonthGrid.DayCell, selected: Long ->
        SelectedDay.isSelectedCell(cell.position, MonthGrid.computeDayCodeForCell(cell, grid.year, grid.month), DayPagerUtils.msToDayCode(selected))
    }

    /** Checks the tap and highlight [tapMs] and [isSelected] give, through the real selectDate. */
    private suspend fun checkTapsSelectTheTappedDay(
        zone: SplitZone,
        iterations: Int,
        tapMs: (Tap, LocalTime) -> Long,
        isSelected: (MonthGrid) -> (MonthGrid.DayCell, Long) -> Boolean,
    ) {
        val viewModel = newViewModel()
        val times = Arb.localTime()
        checkAll(PropertyRuns.config(iterations), dates, times, firstDays, shownIn) { date, now, firstDay, shown ->
            val tap = tapFor(date, shown, firstDay)
            viewModel.selectDate(tapMs(tap, now))
            val selected = viewModel.uiState.value.selectedDate
            val code = DayPagerUtils.localDateToDayCode(date)
            assertEquals("selected day, $zone", code, DayPagerUtils.msToDayCode(selected))
            val expected = if (tap.cell.position == MonthGrid.DayPosition.MonthDate) listOf(code) else emptyList()
            assertEquals("highlighted cells, $zone", expected, highlighted(tap.grid, selected, isSelected(tap.grid)))
            val todayMs = DayPagerUtils.getTodayMidnightMs()
            assertEquals("day pager page, $zone", DayPagerUtils.dateToPage(DayPagerUtils.dayCodeToMs(code), todayMs), DayPagerUtils.dateToPage(selected, todayMs))
        }
    }

    /** The grids' tap: the start of the cell's day code, as both grids hand it to selectDate. */
    private val newTap = { tap: Tap, _: LocalTime ->
        DayPagerUtils.dayCodeToMs(MonthGrid.computeDayCodeForCell(tap.cell, tap.grid.year, tap.grid.month))
    }

    @Test
    fun `a tap selects and highlights the tapped day in every zone`() = runBlocking {
        for (zone in SplitZone.entries) {
            withSplitDeviceTimeZone(zone) {
                runBlocking { checkTapsSelectTheTappedDay(zone, 150, newTap, ::newHighlight) }
            }
        }
    }

    @Test
    fun `the Calendar-based tap and highlight miss the tapped day in split zones`() {
        // Why the split zone exists: on these dates the grids' former code highlighted the
        // previous day (or selected the next one), and only a split zone shows it on the JVM.
        for (zone in listOf(SplitZone.BERLIN, SplitZone.NEW_YORK)) {
            withSplitDeviceTimeZone(zone) {
                assertThrows(AssertionError::class.java) {
                    runBlocking { checkTapsSelectTheTappedDay(zone, 150, CalendarGridCode::tapMs, CalendarGridCode::isSelected) }
                }
            }
        }
    }

    @Test
    fun `in real zones the tap and highlight equal the Calendar-based code`() = runBlocking {
        for (zone in SplitZone.entries) {
            withDeviceTimeZone(zone.id) {
                runBlocking {
                    val viewModel = newViewModel()
                    checkAll(PropertyRuns.config(150), dates, Arb.localTime(), firstDays, shownIn) { date, now, firstDay, shown ->
                        val tap = tapFor(date, shown, firstDay)
                        viewModel.selectDate(CalendarGridCode.tapMs(tap, now))
                        val before = viewModel.uiState.value.selectedDate
                        val highlightBefore = highlighted(tap.grid, before, CalendarGridCode.isSelected(tap.grid))
                        viewModel.selectDate(newTap(tap, now))
                        val after = viewModel.uiState.value.selectedDate
                        assertEquals("selected date, ${zone.id}", before, after)
                        assertEquals("highlight, ${zone.id}", highlightBefore, highlighted(tap.grid, after, newHighlight(tap.grid)))
                    }
                }
            }
        }
    }

    @Test
    fun `the selected day's month is the tapped day's month in every zone`() = runBlocking {
        for (zone in SplitZone.entries) {
            withSplitDeviceTimeZone(zone) {
                runBlocking {
                    val viewModel = newViewModel()
                    checkAll(PropertyRuns.config(150), dates, firstDays, shownIn) { date, firstDay, shown ->
                        val tap = tapFor(date, shown, firstDay)
                        viewModel.selectDate(newTap(tap, LocalTime.NOON))
                        val selected = viewModel.uiState.value.selectedDate
                        val month = YearMonth.from(date)
                        assertEquals("month, $zone", date.year to date.monthValue - 1, SelectedDay.yearMonth0(selected))
                        assertTrue("in its month, $zone", SelectedDay.isInMonth(selected, month.year, month.monthValue - 1))
                        val previous = month.minusMonths(1)
                        assertFalse("in the month before, $zone", SelectedDay.isInMonth(selected, previous.year, previous.monthValue - 1))
                    }
                }
            }
        }
    }

    @Test
    fun `the Calendar-based month read misses the 1st of a summer month in split zones`() {
        // Why the month checks moved to java.time: the selected 1st read back with Calendar
        // was the previous month's last day, so the views showed the month before.
        for (zone in listOf(SplitZone.BERLIN, SplitZone.NEW_YORK)) {
            withSplitDeviceTimeZone(zone) {
                val viewModel = newViewModel()
                viewModel.selectDate(DayPagerUtils.dayCodeToMs(20260701))
                val read = Calendar.getInstance().apply { timeInMillis = viewModel.uiState.value.selectedDate }
                assertEquals("Calendar month, $zone", 5, read.get(Calendar.MONTH))
                assertEquals("java.time month, $zone", 2026 to 6, SelectedDay.yearMonth0(viewModel.uiState.value.selectedDate))
            }
        }
    }

    @Test
    fun `a Year view month tap selects the 1st of that month in every zone`() = runBlocking {
        for (zone in SplitZone.entries) {
            withSplitDeviceTimeZone(zone) {
                runBlocking {
                    val viewModel = newViewModel()
                    checkAll(PropertyRuns.config(150), dates) { date ->
                        viewModel.selectDate(SelectedDay.monthStartMs(date.year, date.monthValue - 1))
                        val expected = DayPagerUtils.localDateToDayCode(date.withDayOfMonth(1))
                        assertEquals("1st of the month, $zone", expected, DayPagerUtils.msToDayCode(viewModel.uiState.value.selectedDate))
                    }
                }
            }
        }
    }

    @Test
    fun `in real zones the day label equals the SimpleDateFormat text in every app locale`() = runBlocking {
        val original = Locale.getDefault()
        try {
            for (locale in appLocales()) {
                Locale.setDefault(locale)
                withDeviceTimeZone("Europe/Berlin") {
                    runBlocking {
                        val viewModel = newViewModel()
                        checkAll(PropertyRuns.config(20), dates) { date ->
                            viewModel.selectDate(DayPagerUtils.dayCodeToMs(DayPagerUtils.localDateToDayCode(date)))
                            val selected = viewModel.uiState.value.selectedDate
                            // The label's former formatter.
                            val before = SimpleDateFormat(DateTimeUtils.localizedPattern("yMMMMd"), locale).format(selected)
                            assertEquals("label, ${locale.toLanguageTag()}", before, viewModel.uiState.value.selectedDayLabel)
                        }
                    }
                }
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `a date picker tap is read as the tapped day by every consumer in every zone`() = runBlocking {
        for (zone in SplitZone.entries) {
            withSplitDeviceTimeZone(zone) {
                runBlocking {
                    val viewModel = newViewModel()
                    checkAll(PropertyRuns.config(150), dates, Arb.localTime(), dates) { tapped, timeOfDay, selectedDay ->
                        val selectedMs = selectedDay.atTime(timeOfDay).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val tapMs = SelectedDay.dateAtTimeOfDayMs(tapped, selectedMs)
                        // Week, 3-day, Planner and Jump to date.
                        assertEquals("week date, $zone", tapped, WeekViewUtils.epochMsToDate(tapMs))
                        // Until, timed and all-day.
                        for (allDay in listOf(false, true)) {
                            val until = untilForPickedDate(tapMs, allDay, "Europe/Berlin")
                            assertEquals("Until day, all-day=$allDay, $zone", tapped, SelectedDay.localDate(untilDisplayMillis(until, allDay, "Europe/Berlin")))
                        }
                        // Search: two taps on the same day make a single-day filter on that day.
                        viewModel.onSearchDateSelected(tapMs)
                        viewModel.onSearchDateSelected(tapMs)
                        val filter = viewModel.uiState.value.searchDateFilter
                        assertTrue("single-day filter, $zone", filter is DateFilter.SingleDay)
                        val range = filter!!.getTimeRange(ZoneId.systemDefault(), Calendar.SUNDAY)
                        assertEquals("search day, $zone", tapped, SelectedDay.localDate(range.first))
                    }
                }
            }
        }
    }

    /**
     * The Calendar-based picker value, or the start of [tapped] where that value left the tapped
     * day (the clocks skip the end of it, as in Greenland every March): the one change the
     * java.time picker makes on purpose.
     */
    private fun expectedPickerTap(selectedMs: Long, tapped: LocalDate): Long {
        val calendarValue = PickerCalendarCode.tapMs(selectedMs, tapped.dayOfMonth)
        return if (SelectedDay.localDate(calendarValue) == tapped) calendarValue
        else tapped.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    @Test
    fun `in real zones a date picker tap equals the Calendar-based value`() = runBlocking {
        val zones = SplitZone.entries.map { it.id } + listOf(
            "Europe/London", "America/Los_Angeles", "Australia/Lord_Howe",
            // Gaps that end at midnight (Greenland) and gaps that start at it (Chile, Cuba).
            "America/Nuuk", "America/Scoresbysund", "America/Santiago", "America/Havana",
        )
        // Zones that change their clocks every year of 2020-2026.
        val dstZones = setOf(
            "Europe/Berlin", "America/New_York", "Australia/Sydney", "Europe/London", "America/Los_Angeles",
            "Australia/Lord_Howe", "America/Santiago", "America/Havana",
        )
        val leftTheDay = mutableMapOf<String, Int>()
        for (zone in zones) {
            withDeviceTimeZone(zone) {
                runBlocking {
                    checkAll(PropertyRuns.config(150), dates, Arb.localTime(), Arb.int(1..31)) { selectedDay, timeOfDay, dayPick ->
                        val selectedMs = selectedDay.atTime(timeOfDay).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val tapped = YearMonth.from(SelectedDay.localDate(selectedMs)).let { it.atDay(minOf(dayPick, it.lengthOfMonth())) }
                        assertEquals("tap value, $zone", expectedPickerTap(selectedMs, tapped), SelectedDay.dateAtTimeOfDayMs(tapped, selectedMs))
                    }
                }
                // The days on both sides of each clock change of 2020-2026 (rule-generated changes
                // included), tapped with every hour and half hour of a neighbouring day of the same
                // month as the selected time: a changing day's own instants never fall in its gap.
                val zoneId = ZoneId.systemDefault()
                val changeDays = clockChanges(zoneId, 2020, 2026)
                    .flatMap { listOf(it.dateTimeBefore.toLocalDate(), it.dateTimeAfter.toLocalDate()) }
                    .distinct()
                assertTrue("clock changes found in $zone", zone !in dstZones || changeDays.size >= 14)
                for (day in changeDays) {
                    val neighbour = if (day.dayOfMonth == 1) day.plusDays(1) else day.minusDays(1)
                    for (minuteOfDay in 0 until 24 * 60 step 30) {
                        val wall = neighbour.atStartOfDay().plusMinutes(minuteOfDay.toLong())
                        val selectedMs = ZonedDateTime.ofLocal(wall, zoneId, null).toInstant().toEpochMilli()
                        if (SelectedDay.localDate(PickerCalendarCode.tapMs(selectedMs, day.dayOfMonth)) != day) {
                            leftTheDay[zone] = (leftTheDay[zone] ?: 0) + 1
                        }
                        assertEquals("tap value on $day, $zone", expectedPickerTap(selectedMs, day), SelectedDay.dateAtTimeOfDayMs(day, selectedMs))
                    }
                }
            }
        }
        // The exception is reached where it exists and nowhere else.
        for (zone in listOf("America/Nuuk", "America/Scoresbysund")) {
            assertTrue("Calendar value left the day in $zone", (leftTheDay[zone] ?: 0) > 0)
        }
        assertEquals("Calendar value left the day elsewhere", emptySet<String>(), leftTheDay.keys - setOf("America/Nuuk", "America/Scoresbysund"))
    }

    /** The zone's clock changes from [fromYear] to [toYear], rule-generated ones included. */
    private fun clockChanges(zoneId: ZoneId, fromYear: Int, toYear: Int): List<ZoneOffsetTransition> {
        val changes = mutableListOf<ZoneOffsetTransition>()
        var transition = zoneId.rules.nextTransition(LocalDate.of(fromYear, 1, 1).atStartOfDay(zoneId).toInstant())
        while (transition != null && transition.dateTimeBefore.year <= toYear) {
            changes += transition
            transition = zoneId.rules.nextTransition(transition.instant)
        }
        return changes
    }

    /** Greenland's March change Saturdays, whose 23:00 to 24:00 never happens, to [lastYear]. */
    private fun greenlandChangeSaturdays(zoneId: ZoneId, lastYear: Int): List<LocalDate> =
        clockChanges(zoneId, 2024, lastYear)
            .filter { it.isGap && it.dateTimeAfter.toLocalDate() != it.dateTimeBefore.toLocalDate() }
            .map { it.dateTimeBefore.toLocalDate() }

    @Test
    fun `a date picker tap on a Greenland change Saturday at 23_xx is read as that Saturday`() = runBlocking {
        for (zone in listOf("America/Nuuk", "America/Scoresbysund")) {
            withDeviceTimeZone(zone) {
                runBlocking {
                    val zoneId = ZoneId.systemDefault()
                    val saturdays = greenlandChangeSaturdays(zoneId, 2125)
                    assertTrue("change Saturdays found in $zone", saturdays.size > 90)
                    val viewModel = newViewModel()
                    for (tapped in saturdays) for (minute in 0..59) {
                        // The search sheet and the time grids' go-to-date sheet carry the current
                        // time of day: here 23:xx on the Friday before.
                        val selectedMs = tapped.minusDays(1).atTime(23, minute).atZone(zoneId).toInstant().toEpochMilli()
                        val tapMs = SelectedDay.dateAtTimeOfDayMs(tapped, selectedMs)
                        assertEquals("week date, $zone", tapped, WeekViewUtils.epochMsToDate(tapMs))
                        for (allDay in listOf(false, true)) {
                            val until = untilForPickedDate(tapMs, allDay, zone)
                            assertEquals("Until day, all-day=$allDay, $zone", tapped, SelectedDay.localDate(untilDisplayMillis(until, allDay, zone)))
                        }
                        viewModel.onSearchDateSelected(tapMs)
                        viewModel.onSearchDateSelected(tapMs)
                        val filter = viewModel.uiState.value.searchDateFilter
                        assertTrue("single-day filter, $zone", filter is DateFilter.SingleDay)
                        val range = filter!!.getTimeRange(zoneId, Calendar.SUNDAY)
                        assertEquals("search day, $zone", tapped, SelectedDay.localDate(range.first))
                    }
                }
            }
        }
    }

    @Test
    fun `the date picker's first-column offset equals the Calendar-based one`() = runBlocking {
        checkAll(PropertyRuns.config(300), dates, firstDays) { date, firstDay ->
            val first = YearMonth.from(date).atDay(1)
            val calendar = Calendar.getInstance().apply { clear(); set(first.year, first.monthValue - 1, 1) }
            assertEquals("offset of $first, first day $firstDay", DateTimeUtils.getFirstDayOffset(calendar, firstDay), DateTimeUtils.getDayOfWeekOffset(first, firstDay))
        }
        Unit
    }

    @Test
    fun `in real zones the date picker header equals the SimpleDateFormat text in every app locale`() = runBlocking {
        val original = Locale.getDefault()
        try {
            for (locale in appLocales()) {
                Locale.setDefault(locale)
                val pattern = DateTimeUtils.localizedPattern("yMMMM")
                // The formatter the picker header uses.
                val formatter = DateTimeUtils.localizedFormatter("yMMMM")
                checkAll(PropertyRuns.config(20), dates) { date ->
                    val month = YearMonth.from(date)
                    val calendar = Calendar.getInstance().apply { clear(); set(month.year, month.monthValue - 1, 1) }
                    assertEquals("header, ${locale.toLanguageTag()}", SimpleDateFormat(pattern, locale).format(calendar.time), month.format(formatter))
                }
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    /** A copy of the date picker's former Calendar tap: the displayed day at the selected time. */
    private object PickerCalendarCode {
        fun tapMs(selectedMs: Long, day: Int): Long {
            // The callers built the displayed month from the selected date.
            val displayed = Calendar.getInstance().apply { timeInMillis = selectedMs }
            val dayCal = (displayed.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, day) }
            val orig = Calendar.getInstance().apply { timeInMillis = selectedMs }
            dayCal.set(Calendar.HOUR_OF_DAY, orig.get(Calendar.HOUR_OF_DAY))
            dayCal.set(Calendar.MINUTE, orig.get(Calendar.MINUTE))
            return dayCal.timeInMillis
        }
    }

    /**
     * The app's locales, from its values-* folders ("en-rGB" is en-GB). Tags only, so a folder
     * like "th" stays Gregorian: the JDK's own SimpleDateFormat uses the Buddhist calendar for
     * th-TH, which the app never asks for.
     */
    private fun appLocales(): List<Locale> {
        val res = File(resolveProjectRoot(), "app/src/main/res")
        val folders = res.listFiles().orEmpty().map { it.name }
            .filter { it.startsWith("values-") && it.removePrefix("values-").matches(Regex("[a-z]{2,3}(-r[A-Z]{2})?")) }
        return (folders.map { Locale.forLanguageTag(it.removePrefix("values-").replace("-r", "-")) } + Locale.US).distinct()
    }

    /**
     * A copy of the Month grids' former Calendar code: a tap set the cell's date on a Calendar
     * holding the current time of day, and the highlight read the selected date's fields back.
     */
    private object CalendarGridCode {
        fun tapMs(tap: Tap, now: LocalTime): Long {
            val (year, month0) = when (tap.cell.position) {
                MonthGrid.DayPosition.MonthDate -> tap.grid.year to tap.grid.month
                MonthGrid.DayPosition.InDate ->
                    if (tap.grid.month == 0) (tap.grid.year - 1) to 11 else tap.grid.year to (tap.grid.month - 1)
                MonthGrid.DayPosition.OutDate ->
                    if (tap.grid.month == 11) (tap.grid.year + 1) to 0 else tap.grid.year to (tap.grid.month + 1)
            }
            return Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, now.hour)
                set(Calendar.MINUTE, now.minute)
                set(Calendar.SECOND, now.second)
                set(Calendar.MILLISECOND, now.nano / 1_000_000)
                set(year, month0, tap.cell.dayOfMonth)
            }.timeInMillis
        }

        fun isSelected(grid: MonthGrid): (MonthGrid.DayCell, Long) -> Boolean = { cell, selectedDate ->
            val selected = Calendar.getInstance().apply { timeInMillis = selectedDate }
            cell.position == MonthGrid.DayPosition.MonthDate &&
                selected.get(Calendar.YEAR) == grid.year && selected.get(Calendar.MONTH) == grid.month &&
                selected.get(Calendar.DAY_OF_MONTH) == cell.dayOfMonth
        }
    }
}
