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
 * "Can't show content" failure that only appears on a device is caught here instead.
 *
 * That launcher message is Glance swapping in its error layout after
 * `IllegalStateException("There are too many views")`: every widget has a bounded pool of view IDs
 * (a few hundred), and a composition whose translated tree allocates more than the pool throws. A
 * tall month widget rendering event titles across 6 week rows is the composition most at risk, so
 * this asserts it translates a busy month at a large size without exhausting the pool.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonthWidgetTranslationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * View-count ceiling for the worst-case month. Below the device's 500-ID pool with margin, so a
     * regression that inflates the per-cell cost (an un-collapsed event, an extra row) trips this
     * test before it can reach a device and show "Can't show content". A fully-booked six-week month
     * currently measures ~370 in dots mode and ~310 in titles mode; 400 leaves headroom above both.
     */
    private val SAFE_VIEW_CEILING = 400

    /**
     * View-count ceiling for the titles-mode BAR path, which legitimately costs more per event than
     * the pill/dot path: a multi-day [SpanBar] carries a `.background()`, and Glance renders a
     * backgrounded element as a wrapper plus a backing image view around the text, so each bar is a
     * few views rather than one. The densest month a user could construct (see [busyMonthWithSpans])
     * still translates well under the device's ~500 view-ID pool; this bound sits below that pool
     * with margin so a regression that inflates the per-bar cost trips here — before it can reach a
     * device and show "Can't show content" — while not holding the bar path to the pill path's
     * tighter [SAFE_VIEW_CEILING], which it was never meant to meet.
     */
    private val SPAN_MODE_VIEW_CEILING = 490

    /** Every in-month day carries [perDay] timed events — the worst case for the view budget. */
    private fun busyMonth(grid: MonthGrid, year: Int, month0: Int, perDay: Int): Map<Int, List<WidgetDataRepository.WidgetEvent>> {
        val colors = intArrayOf(0xFF2196F3.toInt(), 0xFF43A047.toInt(), 0xFFF57C00.toInt(), 0xFF7E57C2.toInt())
        val byDay = mutableMapOf<Int, List<WidgetDataRepository.WidgetEvent>>()
        grid.weeks.flatten().forEach { cell ->
            val dayCode = MonthGrid.computeDayCodeForCell(cell, year, month0)
            byDay[dayCode] = (0 until perDay).map { i ->
                WidgetDataRepository.WidgetEvent(
                    eventId = dayCode * 10L + i,
                    occurrenceStartTs = 0L,
                    title = "Event $i on $dayCode",
                    startTs = 0L,
                    endTs = 0L,
                    isAllDay = false,
                    calendarColor = colors[i % colors.size],
                    isPast = false,
                    isDeviceEvent = false,
                    startDay = dayCode
                )
            }
        }
        return byDay
    }

    /**
     * Like [busyMonth] but every week also carries [barsPerWeek] staggered multi-day events, which
     * [busyMonth]'s single-day pills never produce. A multi-day event renders as a continuous
     * [SpanBar] and every bar segment carries its own clickable (deep-link on the lane's first,
     * go-to-date on the rest), so a week tiled with short, non-mergeable bars is the worst case for
     * the bar path's share of the view-ID pool — the path the pill-only fixtures cannot measure.
     * Each bar covers two adjacent columns and is placed in both day buckets it spans, matching how
     * the repository buckets a multi-day event so the span layout picks it up.
     */
    private fun busyMonthWithSpans(
        grid: MonthGrid,
        year: Int,
        month0: Int,
        perDay: Int,
        barsPerWeek: Int
    ): Map<Int, List<WidgetDataRepository.WidgetEvent>> {
        val colors = intArrayOf(0xFF2196F3.toInt(), 0xFF43A047.toInt(), 0xFFF57C00.toInt(), 0xFF7E57C2.toInt())
        val byDay = mutableMapOf<Int, MutableList<WidgetDataRepository.WidgetEvent>>()
        grid.weeks.forEach { week ->
            val codes = week.map { MonthGrid.computeDayCodeForCell(it, year, month0) }
            // Single-day pills on every cell (the existing worst case).
            codes.forEach { dayCode ->
                val list = byDay.getOrPut(dayCode) { mutableListOf() }
                for (i in 0 until perDay) {
                    list += WidgetDataRepository.WidgetEvent(
                        eventId = dayCode * 10L + i,
                        occurrenceStartTs = 0L,
                        title = "Event $i on $dayCode",
                        startTs = 0L,
                        endTs = 0L,
                        isAllDay = false,
                        calendarColor = colors[i % colors.size],
                        isPast = false,
                        isDeviceEvent = false,
                        startDay = dayCode
                    )
                }
            }
            // Staggered two-day bars: bar b covers columns [2b mod 6, +1], so the first three tile
            // one lane as three distinct SpanBars and the next three overlap into a second lane —
            // maximizing the count of clickable bars per week rather than one wide merged bar.
            for (b in 0 until barsPerWeek) {
                val startCol = (b * 2) % 6
                val endCol = startCol + 1
                val bar = WidgetDataRepository.WidgetEvent(
                    eventId = 900_000L + codes.first() * 10L + b,
                    occurrenceStartTs = b.toLong(),
                    title = "Bar $b",
                    startTs = 0L,
                    endTs = 0L,
                    isAllDay = b % 2 == 0,
                    calendarColor = colors[b % colors.size],
                    isPast = false,
                    isDeviceEvent = false,
                    startDay = codes[startCol],
                    endDay = codes[endCol]
                )
                for (c in startCol..endCol) {
                    byDay.getOrPut(codes[c]) { mutableListOf() } += bar
                }
            }
        }
        return byDay.mapValues { it.value.toList() }
    }

    private fun translate(size: DpSize, content: @androidx.compose.runtime.Composable () -> Unit) = runBlocking {
        GlanceRemoteViews().compose(context = context, size = size, content = content)
    }

    /** Inflate the translated RemoteViews and count every View — the real device view budget. */
    private fun countViews(rv: android.widget.RemoteViews): Int {
        val root = rv.apply(context, android.widget.FrameLayout(context))
        fun walk(v: android.view.View): Int =
            if (v is android.view.ViewGroup) 1 + (0 until v.childCount).sumOf { walk(v.getChildAt(it)) } else 1
        return walk(root)
    }

    /**
     * A device gives each widget a hard pool of 500 view IDs; a translated tree that allocates more
     * throws and the host shows "Can't show content". Robolectric's translation under-counts the
     * device, so hold both render modes well under the pool with margin. This asserts the worst case
     * in each mode — a fully-booked six-week month, three events on every cell — stays inside
     * [SAFE_VIEW_CEILING] at the size that mode renders at, catching a regression that adds views
     * back (an un-collapsed event cell, an extra row) before it can reach a device.
     */
    @Test
    fun `both render modes stay well under the view pool on a fully booked month`() {
        val year = 2026
        val month0 = 2 // March 2026 spans a full 6x7 grid.
        val grid = MonthGrid.compute(year, month0, Calendar.SUNDAY)
        val events = busyMonth(grid, year, month0, perDay = 3)

        // Dots mode: dots are the small-widget floor — a widget shorter than the two-row titles
        // threshold shows them — so an intentionally tiny size forces the dots fallback. Its view
        // count is size-independent: every in-month cell draws up to three dots regardless.
        val dots = translate(DpSize(250.dp, 220.dp)) {
            GlanceTheme { MonthWidgetContent(grid, events, 0, year, month0, Calendar.SUNDAY) }
        }
        val dotsViews = countViews(dots.remoteViews)
        assertTrue("dots-mode view count $dotsViews exceeds ceiling $SAFE_VIEW_CEILING", dotsViews < SAFE_VIEW_CEILING)

        // Titles mode: a large widget renders the capped event rows — the layout that crashed on device.
        val titles = translate(DpSize(400.dp, 600.dp)) {
            GlanceTheme { MonthWidgetContent(grid, events, 0, year, month0, Calendar.SUNDAY) }
        }
        val titlesViews = countViews(titles.remoteViews)
        assertTrue("titles-mode view count $titlesViews exceeds ceiling $SAFE_VIEW_CEILING", titlesViews < SAFE_VIEW_CEILING)
    }

    @Test
    fun `titles mode translates a busy large widget without exhausting the view pool`() {
        // A 6-week month at a large widget size renders the maximum number of event rows, the
        // configuration that overflowed the view-ID pool on device and showed "Can't show content".
        val year = 2026
        val month0 = 2 // March 2026 spans a full 6x7 grid.
        val grid = MonthGrid.compute(year, month0, Calendar.SUNDAY)
        val events = busyMonth(grid, year, month0, perDay = 3)

        val result = translate(DpSize(400.dp, 600.dp)) {
            GlanceTheme {
                MonthWidgetContent(
                    monthGrid = grid,
                    monthEvents = events,
                    monthOffset = 0,
                    targetYear = year,
                    targetMonth0 = month0,
                    firstDayOfWeek = Calendar.SUNDAY
                )
            }
        }

        // Reaching here without IllegalStateException("There are too many views") is the guarantee.
        assertNotNull(result.remoteViews)
    }

    @Test
    fun `titles mode with week numbers and multi-day span bars stays under the view pool`() {
        // busyMonth renders only single-day pills; this adds staggered multi-day span bars and turns
        // on the week-number gutter — the densest titles-mode layout, and the only one that exercises
        // the SpanBar path at all. Pill-only fixtures never render a bar, so without this the bar
        // path's share of the view-ID pool is unmeasured. Bars cost more per event than pills (the
        // bar's background makes Glance emit a wrapper and a backing image view around the text), so
        // this holds the bar path to [SPAN_MODE_VIEW_CEILING] rather than the tighter pill ceiling.
        val year = 2026
        val month0 = 2 // March 2026 spans a full 6x7 grid.
        val grid = MonthGrid.compute(year, month0, Calendar.SUNDAY)
        val events = busyMonthWithSpans(grid, year, month0, perDay = 3, barsPerWeek = 6)

        val result = translate(DpSize(400.dp, 600.dp)) {
            GlanceTheme {
                MonthWidgetContent(
                    monthGrid = grid,
                    monthEvents = events,
                    monthOffset = 0,
                    targetYear = year,
                    targetMonth0 = month0,
                    firstDayOfWeek = Calendar.SUNDAY,
                    showWeekNumbers = true
                )
            }
        }

        // Reaching here without IllegalStateException("There are too many views") already proves the
        // tree did not overflow the pool; the count bound then guards against creeping back toward it.
        val views = countViews(result.remoteViews)
        assertTrue(
            "titles-mode-with-spans view count $views exceeds ceiling $SPAN_MODE_VIEW_CEILING",
            views < SPAN_MODE_VIEW_CEILING
        )
    }

    @Test
    fun `month picker preview translates at its published size`() {
        // The widget-picker preview is published through the same RemoteViews translation as a
        // placed widget (via setWidgetPreviews), so a preview that overflows the view pool shows
        // the picker's placeholder instead of the month. This composes the real preview body at
        // the size the registrar publishes it (WidgetPreviewSizes.MONTH), guarding that path too.
        val previewSize = WidgetPreviewSizes.MONTH.sizes.single()

        val result = translate(previewSize) {
            MonthPreviewContent(context)
        }

        assertNotNull(result.remoteViews)
    }
}
