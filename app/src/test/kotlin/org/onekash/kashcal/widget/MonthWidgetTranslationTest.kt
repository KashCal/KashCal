package org.onekash.kashcal.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.model.MonthGrid
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Calendar
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Runs the month widget's content through Glance's real RemoteViews translation, so the widget's
 * "Can't show content" failure is caught here rather than on a device.
 *
 * That message is Glance's error layout, shown after translation throws
 * `IllegalStateException("There are too many views")`: each widget size translates from a fixed
 * pool of 500 view IDs. The throw comes from Glance's own ID allocator inside
 * `GlanceRemoteViews.compose`, which runs the same under Robolectric as on a device, so these tests
 * assert that `compose` does not throw. They use no cost model: whatever the translator allocates
 * is what is tested.
 *
 * The dangerous months are ordinary ones, not packed ones. A single day with three events gives its
 * week three slot rows across all seven columns, and every tappable element costs three IDs (Glance
 * wraps it in a box plus a ripple image). A month where most weeks have one busy day is the shape
 * that overflowed in #373, and the randomized test is weighted toward it.
 *
 * Cases shared with the API 31 run live in [MonthWidgetTranslationCases]; this class adds the
 * randomized months and the picker preview.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonthWidgetTranslationTest : MonthWidgetTranslationCases() {

    /**
     * Random months weighted toward the overflow shape. Reproducible: override with Gradle
     * `-Pmonthwidget.fuzz.seed=<n>` and `-Pmonthwidget.fuzz.iterations=<n>` (forwarded into the
     * test JVM by app/build.gradle.kts).
     */
    @Test
    fun `random months never overflow the view pool`() {
        val seed = System.getProperty("monthwidget.fuzz.seed")?.toLongOrNull() ?: DEFAULT_FUZZ_SEED
        val iterations = System.getProperty("monthwidget.fuzz.iterations")?.toIntOrNull() ?: DEFAULT_FUZZ_ITERATIONS
        val random = Random(seed)
        var deepMonths = 0

        repeat(iterations) { iteration ->
            val month = randomMonth(random)
            if (month.deepWeeks >= 5) deepMonths++
            for (size in listOf(LARGE, minimalThreeRowSize(month.weekCount))) {
                assertThreeRows(size, month.weekCount)
                try {
                    render(month.fixture, size)
                } catch (e: IllegalStateException) {
                    fail(
                        "overflow at seed=$seed iteration=$iteration size=$size: ${month.summary} (${e.message}). " +
                            "Reproduce: -Pmonthwidget.fuzz.seed=$seed -Pmonthwidget.fuzz.iterations=${iteration + 1}"
                    )
                }
            }
        }

        // Fails if the generator stops producing the overflow shape often enough to test it.
        assertTrue(
            "only $deepMonths of $iterations months had 5+ weeks with a three-deep day (seed=$seed)",
            deepMonths >= iterations / 5
        )
    }

    @Test
    fun `month picker preview translates at its published size`() {
        // The widget-picker preview goes through the same translation as a placed widget, so a
        // preview that overflows shows the picker's placeholder, not the month.
        val previewSize = WidgetPreviewSizes.MONTH.sizes.single()
        val result = runBlocking { GlanceRemoteViews().compose(context = context, size = previewSize) { MonthPreviewContent(context) } }
        assertNotNull(result.remoteViews)
    }

    private class RandomMonth(val fixture: MonthFixture, val weekCount: Int, val deepWeeks: Int, val summary: String)

    /**
     * One month of random events, generated per week: most weeks get one or two busy days (three to
     * five events), other days get at most one event, plus a few multi-day spans placed in every
     * day they cover. `today` is passed explicitly, inside or outside the month.
     */
    private fun randomMonth(random: Random): RandomMonth {
        val year = random.nextInt(2025, 2029)
        val month0 = random.nextInt(0, 12)
        val firstDayOfWeek = if (random.nextBoolean()) Calendar.SUNDAY else Calendar.MONDAY
        val grid = MonthGrid.compute(year, month0, firstDayOfWeek)
        val weeks = visibleWeeks(grid).map { wk -> wk.map { MonthGrid.computeDayCodeForCell(it, year, month0) } }
        val allCodes = grid.weeks.flatten().map { MonthGrid.computeDayCodeForCell(it, year, month0) }
        val events = mutableMapOf<Int, MutableList<WidgetDataRepository.WidgetEvent>>()
        var nextId = 1L
        var deepWeeks = 0

        weeks.forEach { codes ->
            val busyCols = if (random.nextDouble() < 0.8) (0..6).shuffled(random).take(random.nextInt(1, 3)) else emptyList()
            if (busyCols.isNotEmpty()) deepWeeks++
            codes.forEachIndexed { col, code ->
                val count = if (col in busyCols) random.nextInt(3, 6) else if (random.nextDouble() < 0.3) 1 else 0
                repeat(count) { events.getOrPut(code) { mutableListOf() } += event(nextId++, code, code, random.nextInt(3)) }
            }
        }
        val spanCount = random.nextInt(0, 5)
        repeat(spanCount) {
            val start = random.nextInt(allCodes.size)
            val end = (start + random.nextInt(1, 10)).coerceAtMost(allCodes.size - 1)
            val span = event(nextId++, allCodes[start], allCodes[end], random.nextInt(3))
            for (i in start..end) events.getOrPut(allCodes[i]) { mutableListOf() } += span
        }
        val today = if (random.nextBoolean()) LocalDate.of(year, month0 + 1, random.nextInt(1, 29)) else OUTSIDE_TODAY
        val showWeekNumbers = random.nextBoolean()
        val fixture = MonthFixture(grid, year, month0, firstDayOfWeek, events.mapValues { it.value.toList() }, showWeekNumbers, today)
        return RandomMonth(
            fixture, weeks.size, deepWeeks,
            "$year-${month0 + 1} weeks=${weeks.size} fdow=$firstDayOfWeek wk#=$showWeekNumbers today=$today " +
                "deepWeeks=$deepWeeks spans=$spanCount days=${events.size}"
        )
    }

    private companion object {
        const val DEFAULT_FUZZ_SEED = 373L
        const val DEFAULT_FUZZ_ITERATIONS = 60
    }
}

/** The worst-case and regression cases on the API 31/32 translation path (view stubs). */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MonthWidgetTranslationApi31Test : MonthWidgetTranslationCases()

/**
 * Cases that run on every translation path: the reproduced month and the constructed worst cases.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
abstract class MonthWidgetTranslationCases {

    protected val context: Context = ApplicationProvider.getApplicationContext()

    protected class MonthFixture(
        val grid: MonthGrid,
        val year: Int,
        val month0: Int,
        val firstDayOfWeek: Int,
        val events: Map<Int, List<WidgetDataRepository.WidgetEvent>>,
        val showWeekNumbers: Boolean,
        val today: LocalDate
    )

    /** kind 0 = timed, 1 = all-day busy, 2 = all-day free. */
    protected fun event(id: Long, startDay: Int, endDay: Int, kind: Int = 0) = WidgetDataRepository.WidgetEvent(
        eventId = id,
        occurrenceStartTs = id,
        title = "Event $id",
        startTs = 0L,
        endTs = 0L,
        isAllDay = kind != 0,
        calendarColor = COLORS[(id % COLORS.size).toInt()],
        isPast = false,
        isDeviceEvent = false,
        startDay = startDay,
        isFree = kind == 2,
        endDay = endDay
    )

    protected fun render(fixture: MonthFixture, size: DpSize) = runBlocking {
        GlanceRemoteViews().compose(context = context, size = size) {
            GlanceTheme {
                MonthWidgetContent(
                    monthGrid = fixture.grid,
                    monthEvents = fixture.events,
                    monthOffset = 0,
                    targetYear = fixture.year,
                    targetMonth0 = fixture.month0,
                    firstDayOfWeek = fixture.firstDayOfWeek,
                    showWeekNumbers = fixture.showWeekNumbers,
                    today = fixture.today
                )
            }
        }
    }

    /**
     * Returns a 400dp-wide size 1dp taller than [weekCount] weeks need for three title rows, and
     * never below the titles-mode threshold.
     */
    protected fun minimalThreeRowSize(weekCount: Int): DpSize {
        val fontScale = context.resources.configuration.fontScale
        val perRow = TIMED_TITLE_ROW_HEIGHT_DP * fontScale + EVENT_ROW_GAP_DP
        val cell = DAY_NUMBER_BLOCK_HEIGHT_DP * fontScale + 3 * perRow
        val height = ceil(MONTH_HEADER_HEIGHT_DP + MONTH_DOW_ROW_HEIGHT_DP + weekCount * cell).toFloat() + 1f
        return DpSize(400.dp, height.coerceAtLeast(minWidgetHeightForTitlesDp(TITLES_MIN_ROWS, fontScale) + 1f).dp)
    }

    /** Asserts [size] is in titles mode with three rows, so no case runs at fewer. */
    protected fun assertThreeRows(size: DpSize, weekCount: Int) {
        val fontScale = context.resources.configuration.fontScale
        assertTrue("size $size is not in titles mode", size.height.value >= minWidgetHeightForTitlesDp(TITLES_MIN_ROWS, fontScale))
        val cellHeight = (size.height.value - MONTH_HEADER_HEIGHT_DP - MONTH_DOW_ROW_HEIGHT_DP) / weekCount
        assertEquals("size $size does not yield three rows", 3, maxEventRows(cellHeight, fontScale))
    }

    private val may2026 = MonthGrid.compute(2026, 4, Calendar.SUNDAY)
    private fun may2026Weeks() = visibleWeeks(may2026).map { wk -> wk.map { MonthGrid.computeDayCodeForCell(it, 2026, 4) } }
        .also { check(it.size == 6) { "May 2026 must span six visible weeks" } }

    /**
     * The month shape reported in #373, which overflowed at three rows: one day per week with three
     * events, optionally with three four-day events, some crossing a week boundary.
     */
    private fun oneBusyDayPerWeek(withSpans: Boolean, showWeekNumbers: Boolean): MonthFixture {
        val weeks = may2026Weeks()
        val events = mutableMapOf<Int, MutableList<WidgetDataRepository.WidgetEvent>>()
        var id = 1L
        weeks.forEach { codes -> repeat(3) { events.getOrPut(codes[3]) { mutableListOf() } += event(id++, codes[3], codes[3]) } }
        if (withSpans) {
            val flat = weeks.flatten()
            repeat(3) { b ->
                val start = 5 + b * 9
                val span = event(10_000L + b, flat[start], flat[start + 3], kind = 1)
                for (i in start..start + 3) events.getOrPut(flat[i]) { mutableListOf() } += span
            }
        }
        return MonthFixture(may2026, 2026, 4, Calendar.SUNDAY, events.mapValues { it.value.toList() }, showWeekNumbers, OUTSIDE_TODAY)
    }

    @Test
    fun `one busy day per week does not overflow`() {
        for (withSpans in listOf(false, true)) for (showWeekNumbers in listOf(false, true)) {
            assertThreeRows(LARGE, 6)
            try {
                render(oneBusyDayPerWeek(withSpans, showWeekNumbers), LARGE)
            } catch (e: IllegalStateException) {
                fail("overflow with spans=$withSpans weekNumbers=$showWeekNumbers: ${e.message}")
            }
        }
    }

    /**
     * The layout's worst case: a six-week month at three rows where every lane in every week is a
     * one-column bar on the first column (continuing from the previous week), pills on the middle
     * five columns, and a one-column bar on the last column (continuing into the next week). That
     * is seven elements per row, the most a row can hold. With [pillsPerDay] of 4 or more the last
     * lane becomes "+n" markers instead of pills.
     */
    protected fun edgeBarWorstCase(pillsPerDay: Int): MonthFixture {
        val weeks = may2026Weeks()
        val events = mutableMapOf<Int, MutableList<WidgetDataRepository.WidgetEvent>>()
        var id = 1L
        val gridCodes = weeks.flatten().toSet()
        fun addSpan(from: Int, to: Int) {
            val span = event(id++, from, to, kind = 1)
            listOf(from, to).distinct().forEach { code -> if (code in gridCodes) events.getOrPut(code) { mutableListOf() } += span }
        }
        val before = DayPagerUtils.localDateToDayCode(DayPagerUtils.dayCodeToLocalDate(weeks.first()[0]).minusDays(1))
        val after = DayPagerUtils.localDateToDayCode(DayPagerUtils.dayCodeToLocalDate(weeks.last()[6]).plusDays(1))
        repeat(3) { addSpan(before, weeks.first()[0]) }
        for (w in 0 until weeks.size - 1) repeat(3) { addSpan(weeks[w][6], weeks[w + 1][0]) }
        repeat(3) { addSpan(weeks.last()[6], after) }
        weeks.forEach { codes ->
            for (col in 1..5) repeat(pillsPerDay) { events.getOrPut(codes[col]) { mutableListOf() } += event(id++, codes[col], codes[col]) }
        }
        return MonthFixture(may2026, 2026, 4, Calendar.SUNDAY, events.mapValues { it.value.toList() }, true, LocalDate.of(2026, 5, 15))
    }

    @Test
    fun `seven-element rows in every week do not overflow`() {
        val fixture = edgeBarWorstCase(pillsPerDay = 3)
        // The fixture is the bound: every row holds seven runs, two of them tappable.
        worstCaseRuns(fixture).flatten().forEach { runs ->
            assertEquals(7, runs.size)
            assertEquals(2, runs.count { (it as? SlotRun.Pill)?.deepLink == true || (it as? SlotRun.Bar)?.deepLink == true })
        }
        assertThreeRows(LARGE, 6)
        render(fixture, LARGE)
    }

    @Test
    fun `seven-element rows with a last lane of plus-n markers do not overflow`() {
        val fixture = edgeBarWorstCase(pillsPerDay = 5)
        worstCaseRuns(fixture).forEach { week ->
            week.forEach { runs -> assertEquals(7, runs.size) }
            val last = week.last()
            assertTrue(last.first() is SlotRun.Bar && (last.first() as SlotRun.Bar).deepLink)
            assertTrue(last.subList(1, 6).all { it is SlotRun.Overflow })
            assertTrue(last.last() is SlotRun.Bar && !(last.last() as SlotRun.Bar).deepLink)
        }
        assertThreeRows(LARGE, 6)
        render(fixture, LARGE)
    }

    /** Returns the slot-row runs the widget renders for [fixture] at three rows, per week. */
    private fun worstCaseRuns(fixture: MonthFixture): List<List<List<SlotRun>>> =
        visibleWeeks(fixture.grid).map { wk ->
            val codes = wk.map { MonthGrid.computeDayCodeForCell(it, fixture.year, fixture.month0) }
            val render = computeMonthWidgetWeekRender(codes, fixture.events, 3)
            assertEquals("each week must fill three slot rows", 3, render.slots.size)
            render.slots.map { slotRowRuns(it) }
        }

    @Test
    fun `dots mode does not overflow on a fully booked six-week month`() {
        may2026Weeks()
        val events = mutableMapOf<Int, MutableList<WidgetDataRepository.WidgetEvent>>()
        var id = 1L
        may2026.weeks.flatten().map { MonthGrid.computeDayCodeForCell(it, 2026, 4) }.forEach { code ->
            repeat(4) { events.getOrPut(code) { mutableListOf() } += event(id++, code, code) }
        }
        val fixture = MonthFixture(may2026, 2026, 4, Calendar.SUNDAY, events.mapValues { it.value.toList() }, true, LocalDate.of(2026, 5, 15))
        val dots = DpSize(250.dp, 220.dp)
        assertTrue("size $dots must be dots mode", dots.height.value < minWidgetHeightForTitlesDp(TITLES_MIN_ROWS, context.resources.configuration.fontScale))
        render(fixture, dots)
    }

    protected companion object {
        val LARGE = DpSize(400.dp, 600.dp)
        /** A "today" outside every generated month, so no today marker is drawn. */
        val OUTSIDE_TODAY: LocalDate = LocalDate.of(2020, 1, 1)
        val COLORS = intArrayOf(0xFF2196F3.toInt(), 0xFF43A047.toInt(), 0xFFF57C00.toInt(), 0xFF7E57C2.toInt())

    }
}
