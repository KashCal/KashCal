package org.onekash.kashcal.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.model.MonthGrid
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Runs the month widget's content through Glance's real RemoteViews translation — the layer the
 * composition-tree unit-test harness ([runGlanceAppWidgetUnitTest]) does not exercise — so the
 * "content can't be displayed" failure that otherwise only appears on a device is caught here.
 *
 * That launcher message is Glance swapping in its error layout after
 * `IllegalStateException("There are too many views")`: every widget translates from a fixed pool of
 * view IDs, and a composition whose translated tree allocates more than the pool throws. The throw
 * happens DURING `GlanceRemoteViews.compose` (at `TranslationContext.nextViewId`), and Robolectric
 * enforces the same pool as a device — so the real gate is simply that `compose` does not throw on
 * the worst case. (An earlier version of this suite asserted a walked-view-count stayed under a
 * hand-picked ceiling; that counts the inflated tree, a different and looser quantity than the
 * allocator that actually throws, and it was calibrated against a month that only spanned five
 * visible weeks — so it passed while real six-week months crashed. The no-throw assertion below is
 * the correct gate.)
 *
 * The densest case is a genuine SIX-week month at a large size with single-day pills AND multi-day
 * span bars: the bars and the "+n" overflow markers are the heavy elements. The adaptive row chooser
 * ([chooseMonthWidgetRowCount]) keeps such a month inside the pool by stepping its rows down (or to
 * dots); these tests prove the worst cases translate cleanly and that the step-down engages.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonthWidgetTranslationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val colors = intArrayOf(0xFF2196F3.toInt(), 0xFF43A047.toInt(), 0xFFF57C00.toInt(), 0xFF7E57C2.toInt())

    /**
     * A month that spans the full SIX visible weeks — the worst case for the view budget. Chosen by
     * asking the widget's own [visibleWeeks] rather than hardcoding, so it can't silently regress to
     * a five-week month (the bug the previous fixture had with March 2026). May 2026 is one such.
     */
    private val sixWeekMonth: Pair<Int, Int> = run {
        var found: Pair<Int, Int>? = null
        outer@ for (y in 2026..2028) for (m in 0..11) {
            if (visibleWeeks(MonthGrid.compute(y, m, Calendar.SUNDAY)).size == 6) { found = y to m; break@outer }
        }
        requireNotNull(found) { "no six-week month found in range" }
    }

    /** Every in-month day carries [perDay] single-day timed events. */
    private fun pillMonth(grid: MonthGrid, year: Int, month0: Int, perDay: Int): MutableMap<Int, MutableList<WidgetDataRepository.WidgetEvent>> {
        val byDay = mutableMapOf<Int, MutableList<WidgetDataRepository.WidgetEvent>>()
        grid.weeks.flatten().forEach { cell ->
            val dayCode = MonthGrid.computeDayCodeForCell(cell, year, month0)
            repeat(perDay) { i ->
                byDay.getOrPut(dayCode) { mutableListOf() }.add(
                    WidgetDataRepository.WidgetEvent(
                        eventId = dayCode * 10L + i, occurrenceStartTs = i.toLong(), title = "Event $i on $dayCode",
                        startTs = 0L, endTs = 0L, isAllDay = false, calendarColor = colors[i % colors.size],
                        isPast = false, isDeviceEvent = false, startDay = dayCode
                    )
                )
            }
        }
        return byDay
    }

    /** [pillMonth] plus three staggered two-day span bars per week — the pills+bars MIX that, at the
     *  height-derived three rows, overflows the pool (confirmed on pre-fix code); the fix steps it
     *  down. Each bar is placed in both day buckets it spans, as the repository does. */
    private fun pillAndBarMonth(grid: MonthGrid, year: Int, month0: Int, perDay: Int): Map<Int, List<WidgetDataRepository.WidgetEvent>> {
        val byDay = pillMonth(grid, year, month0, perDay)
        grid.weeks.forEach { week ->
            val codes = week.map { MonthGrid.computeDayCodeForCell(it, year, month0) }
            listOf(0, 2, 4).forEachIndexed { k, sc ->
                val bar = WidgetDataRepository.WidgetEvent(
                    eventId = 900_000L + codes.first() * 10L + k, occurrenceStartTs = k.toLong(), title = "Bar $k",
                    startTs = 0L, endTs = 0L, isAllDay = k % 2 == 0, calendarColor = colors[k % colors.size],
                    isPast = false, isDeviceEvent = false, startDay = codes[sc], endDay = codes[sc + 1]
                )
                byDay.getOrPut(codes[sc]) { mutableListOf() }.add(bar)
                byDay.getOrPut(codes[sc + 1]) { mutableListOf() }.add(bar)
            }
        }
        return byDay.mapValues { it.value.toList() }
    }

    private fun translate(size: DpSize, content: @androidx.compose.runtime.Composable () -> Unit) = runBlocking {
        GlanceRemoteViews().compose(context = context, size = size, content = content)
    }

    private fun render(size: DpSize, events: Map<Int, List<WidgetDataRepository.WidgetEvent>>, showWeekNumbers: Boolean) =
        translate(size) {
            GlanceTheme {
                MonthWidgetContent(
                    monthGrid = MonthGrid.compute(sixWeekMonth.first, sixWeekMonth.second, Calendar.SUNDAY),
                    monthEvents = events, monthOffset = 0,
                    targetYear = sixWeekMonth.first, targetMonth0 = sixWeekMonth.second,
                    firstDayOfWeek = Calendar.SUNDAY, showWeekNumbers = showWeekNumbers
                )
            }
        }

    // Verify the guaranteed floor FIRST: everything the fix relies on collapses to dots, so dots
    // must itself be safe on the worst-case six-week month before the titles gate can trust it.
    @Test
    fun `dots mode does not overflow the pool on a dense six-week month`() {
        val (y, m) = sixWeekMonth
        val grid = MonthGrid.compute(y, m, Calendar.SUNDAY)
        val events = pillAndBarMonth(grid, y, m, perDay = 3)
        // A short widget renders dots regardless of density.
        val result = render(DpSize(250.dp, 220.dp), events, showWeekNumbers = true)
        assertNotNull("dots mode threw on a dense six-week month", result.remoteViews)
    }

    @Test
    fun `titles mode does not overflow on a dense six-week pills-and-bars month`() {
        val (y, m) = sixWeekMonth
        val grid = MonthGrid.compute(y, m, Calendar.SUNDAY)
        val events = pillAndBarMonth(grid, y, m, perDay = 3)
        // Reaching the assertion without IllegalStateException("There are too many views") is the
        // guarantee. This fixture overflowed at the height-derived three rows on pre-fix code; the
        // adaptive chooser now steps it down so it translates.
        val result = render(DpSize(400.dp, 600.dp), events, showWeekNumbers = true)
        assertNotNull(result.remoteViews)
    }

    @Test
    fun `titles mode does not overflow on a fully pill-packed six-week month`() {
        val (y, m) = sixWeekMonth
        val grid = MonthGrid.compute(y, m, Calendar.SUNDAY)
        val events = pillMonth(grid, y, m, perDay = 3).mapValues { it.value.toList() }
        val result = render(DpSize(400.dp, 600.dp), events, showWeekNumbers = false)
        assertNotNull(result.remoteViews)
    }

    @Test
    fun `adaptive chooser steps the dense six-week mix below full height`() {
        val (y, m) = sixWeekMonth
        val grid = MonthGrid.compute(y, m, Calendar.SUNDAY)
        val events = pillAndBarMonth(grid, y, m, perDay = 3)
        val weekDayCodes = visibleWeeks(grid).map { wk -> wk.map { MonthGrid.computeDayCodeForCell(it, y, m) } }
        // At the height that fits three rows, this mix would overflow, so the chooser must return
        // fewer than three (but still >= 1: dots is the last resort, not the first).
        val rows = chooseMonthWidgetRowCount(weekDayCodes, events, heightDerivedMax = 3, showWeekNumbers = true, hasTodayInMonth = false).rows
        assertTrue("expected a reduced row count in 1..2, got $rows", rows in 1..2)
    }

    @Test
    fun `month picker preview translates at its published size`() {
        // The widget-picker preview is published through the same RemoteViews translation as a
        // placed widget (via setWidgetPreviews), so a preview that overflows shows the picker's
        // placeholder instead of the month. Compose the real preview body at the published size.
        val previewSize = WidgetPreviewSizes.MONTH.sizes.single()
        val result = translate(previewSize) { MonthPreviewContent(context) }
        assertNotNull(result.remoteViews)
    }
}
