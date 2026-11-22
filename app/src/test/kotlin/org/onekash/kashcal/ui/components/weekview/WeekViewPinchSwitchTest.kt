package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.LayoutDirection
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.AgendaWeekBarLogic
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime
import java.util.TimeZone

/**
 * Drives the real [WeekViewContent] with two fingers: a sideways pinch switches Week, 3-day and
 * Day one step on release, around the day under the fingers, after the grid fades out; short of
 * the threshold or at the chain's ends the columns spring back; meanwhile they stretch with the
 * fingers, without recomposing the pages; an up-and-down pinch still zooms the hours. mdpi, so
 * 1dp is 1px.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h1600dp-mdpi")
class WeekViewPinchSwitchTest {

    @get:Rule
    val rule = createComposeRule()

    private val today = LocalDate.now()
    private val weekStart = WeekViewUtils.weekPageToStartDate(WeekViewUtils.CENTER_WEEK_PAGE, java.util.Calendar.SUNDAY)
    private val savedZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreZone() = TimeZone.setDefault(savedZone)

    private class Host(days: Int) {
        var visibleDays by mutableIntStateOf(days)
        var pending by mutableStateOf<Int?>(null)
        val switches = mutableListOf<Pair<Int, LocalDate>>()
        val hourHeights = mutableListOf<Float>()
        var reschedules = 0
    }

    /**
     * Shows the grid; with [follow] the host plays the ViewModel: it switches to the pinched view
     * and asks the pager for the anchor's page.
     */
    private fun show(
        days: Int,
        follow: Boolean = true,
        rtl: Boolean = false,
        events: List<DisplayEvent> = emptyList(),
    ): Host {
        val host = Host(days)
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.LayoutDirection(
                    if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
                )
            ) {
                MaterialTheme {
                    WeekViewContent(
                        timedEvents = events.toImmutableList(),
                        allDayEvents = persistentListOf(),
                        isLoading = false,
                        error = null,
                        scrollPosition = 0,
                        savedScrollMinutes = 8 * 60,
                        visibleDays = host.visibleDays,
                        onDatePickerRequest = {},
                        onEventClick = {},
                        onScrollPositionChange = {},
                        onHourHeightChange = { host.hourHeights += it },
                        onReschedule = { _, _, _ -> host.reschedules++ },
                        pendingNavigateToPage = host.pending,
                        onNavigationConsumed = { host.pending = null },
                        onPinchViewSwitch = { target, date ->
                            host.switches += target to date
                            if (follow) {
                                host.visibleDays = target
                                host.pending = if (target == 7) WeekViewUtils.dateToWeekPage(date, java.util.Calendar.SUNDAY) else WeekViewUtils.dateToPage(date)
                            }
                        },
                        clock = MutableTestClock.at(today, LocalTime.of(7, 0)),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        rule.waitForIdle()
        return host
    }

    private fun hourColumn() = rule.onNodeWithTag(TEST_TAG_FIRST_TIME_LABEL).fetchSemanticsNode().boundsInRoot
    private fun gridLeft() = hourColumn().right
    private fun width() = rule.onRoot().fetchSemanticsNode().boundsInRoot.width

    /**
     * Returns the x centre of the [column]th day column from the left; in RTL the hour column is
     * on the right.
     */
    private fun columnCentreX(column: Int, days: Int, rtl: Boolean = false): Float {
        val left = if (rtl) 0f else gridLeft()
        val right = if (rtl) hourColumn().left else width()
        return left + (right - left) / days * (column + 0.5f)
    }
    private val gridY = 900f

    /** Spreads two fingers sideways around [x] from 80px apart to 80 x [ratio], then lifts them. */
    private fun sidewaysPinch(x: Float, ratio: Float, y: Float = gridY) {
        rule.onRoot().performTouchInput {
            pinch(Offset(x - 40f, y), Offset(x - 40f * ratio, y), Offset(x + 40f, y), Offset(x + 40f * ratio, y), durationMillis = 300)
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    @Test
    fun `spreading over Thursday in the week view opens the 3-day view on Thursday`() {
        val host = show(7)
        sidewaysPinch(columnCentreX(4, 7), 1.5f)
        assertEquals(listOf(3 to weekStart.plusDays(4)), host.switches)
    }

    @Test
    fun `spreading in the 3-day view opens the day under the fingers`() {
        val host = show(3)
        sidewaysPinch(columnCentreX(1, 3), 1.5f)
        assertEquals(listOf(1 to today.plusDays(1)), host.switches)
    }

    @Test
    fun `pinching in on the day view opens the 3-day view on that day`() {
        val host = show(1)
        sidewaysPinch(columnCentreX(0, 1), 0.6f)
        assertEquals(listOf(3 to today), host.switches)
    }

    @Test
    fun `pinching in on the 3-day view opens the week holding the day under the fingers`() {
        val host = show(3)
        sidewaysPinch(columnCentreX(2, 3), 0.6f)
        assertEquals(listOf(7 to today.plusDays(2)), host.switches)
    }

    @Test
    fun `two pinches in a row step from week to 3-day to day`() {
        val host = show(7)
        sidewaysPinch(columnCentreX(4, 7), 1.5f)
        sidewaysPinch(columnCentreX(1, 3), 1.5f)
        assertEquals(listOf(3 to weekStart.plusDays(4), 1 to weekStart.plusDays(5)), host.switches)
    }

    @Test
    fun `right to left the anchor is the day drawn under the fingers`() {
        val host = show(7, rtl = true)
        // In RTL the week's first day is the rightmost column; the column at index 1 from the
        // left is the week's sixth day.
        sidewaysPinch(columnCentreX(1, 7, rtl = true), 1.5f)
        assertEquals(listOf(3 to weekStart.plusDays(5)), host.switches)
    }

    @Test
    fun `a short spread switches nothing and leaves the hours alone`() {
        val host = show(7)
        sidewaysPinch(columnCentreX(3, 7), 1.2f)
        assertEquals(emptyList<Pair<Int, LocalDate>>(), host.switches)
        assertEquals(emptyList<Float>(), host.hourHeights)
    }

    @Test
    fun `nothing lies past the ends of the chain`() {
        val host = show(1)
        sidewaysPinch(columnCentreX(0, 1), 1.6f)
        assertEquals(emptyList<Pair<Int, LocalDate>>(), host.switches)
        assertEquals(emptyList<Float>(), host.hourHeights)
    }

    @Test
    fun `nothing lies past the week view either`() {
        val host = show(7)
        sidewaysPinch(columnCentreX(3, 7), 0.6f)
        assertEquals(emptyList<Pair<Int, LocalDate>>(), host.switches)
        assertEquals(emptyList<Float>(), host.hourHeights)
    }

    @Test
    fun `an up and down spread still zooms the hours and switches nothing`() {
        val host = show(3)
        val x = columnCentreX(1, 3)
        rule.onRoot().performTouchInput {
            pinch(Offset(x, gridY - 40f), Offset(x, gridY - 70f), Offset(x, gridY + 40f), Offset(x, gridY + 70f), durationMillis = 300)
        }
        rule.waitForIdle()
        assertTrue("hours zoomed: ${host.hourHeights}", (host.hourHeights.lastOrNull() ?: 0f) > 60f)
        assertEquals(emptyList<Pair<Int, LocalDate>>(), host.switches)
    }

    @Test
    fun `fingers side by side moved a little up and down don't jump the hours`() {
        val host = show(3)
        val x = columnCentreX(1, 3)
        rule.onRoot().performTouchInput {
            pinch(Offset(x - 40f, gridY), Offset(x - 40f, gridY - 10f), Offset(x + 40f, gridY), Offset(x + 40f, gridY + 10f), durationMillis = 300)
        }
        rule.waitForIdle()
        assertTrue("hours ${host.hourHeights}", host.hourHeights.all { it < 70f })
    }

    @Test
    fun `the switch waits for the fade and fires once`() {
        val host = show(7)
        rule.mainClock.autoAdvance = false
        val x = columnCentreX(4, 7)
        rule.onRoot().performTouchInput {
            pinch(Offset(x - 40f, gridY), Offset(x - 60f, gridY), Offset(x + 40f, gridY), Offset(x + 60f, gridY), durationMillis = 300)
        }
        assertEquals("not on the release frame", 0, host.switches.size)
        rule.mainClock.advanceTimeBy(1_000)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(1, host.switches.size)
    }

    // Header and column widths while the fingers are still down.

    private fun headerCell(date: LocalDate): Rect = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
        .first { n ->
            n.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                .any { it.startsWith(AgendaWeekBarLogic.cellContentDescription(date, false, false, "Today", "")) } &&
                n.boundsInRoot.height < 200f
        }.boundsInRoot

    private fun dayColumn(date: LocalDate): Rect = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).fetchSemanticsNodes()
        .first { n ->
            n.boundsInRoot.height > 200f && n.config[SemanticsProperties.ContentDescription].any {
                it.startsWith(AgendaWeekBarLogic.cellContentDescription(date, false, false, "Today", ""))
            }
        }.boundsInRoot

    /** Puts two fingers 80px apart around [x], spreads them to 80 x [ratio], keeps them down. */
    private fun holdSideways(x: Float, ratio: Float) {
        rule.onRoot().performTouchInput {
            down(0, Offset(x - 40f, gridY))
            down(1, Offset(x + 40f, gridY))
            for (step in 1..10) {
                val half = 40f * (1f + (ratio - 1f) * step / 10f)
                updatePointerTo(0, Offset(x - half, gridY))
                updatePointerTo(1, Offset(x + half, gridY))
                move()
            }
        }
        rule.waitForIdle()
    }

    private fun lift() {
        rule.onRoot().performTouchInput { up(0); up(1) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    @Test
    fun `while spreading the week's columns stretch up to 10 percent and spring back`() {
        show(7)
        val date = weekStart.plusDays(3)
        val before = headerCell(date).width
        holdSideways(columnCentreX(3, 7), 1.2f)
        val during = headerCell(date).width / before
        assertTrue("stretched $during", during > 1.01f && during <= 1.10f + 0.01f)
        lift()
        assertEquals(before, headerCell(date).width, 0.5f)
    }

    @Test
    fun `while pinching in the 3-day columns squeeze up to 10 percent`() {
        show(3)
        val date = today.plusDays(1)
        val before = dayColumn(date).width
        holdSideways(columnCentreX(1, 3), 0.6f)
        val during = dayColumn(date).width / before
        assertTrue("squeezed $during", during < 0.99f && during >= 0.90f - 0.01f)
        lift()
    }

    @Test
    fun `past the day view the column stretches at most 5 percent`() {
        show(1)
        val before = dayColumn(today).width
        holdSideways(columnCentreX(0, 1), 1.6f)
        val during = dayColumn(today).width / before
        assertTrue("stretched $during", during > 1.01f && during <= 1.05f + 0.01f)
        lift()
        assertEquals(before, dayColumn(today).width, 0.5f)
    }

    @Test
    fun `past the week view the columns squeeze at most 5 percent`() {
        show(7)
        val date = weekStart.plusDays(3)
        val before = headerCell(date).width
        holdSideways(columnCentreX(3, 7), 0.5f)
        val during = headerCell(date).width / before
        assertTrue("squeezed $during", during < 0.99f && during >= 0.95f - 0.01f)
        lift()
    }

    // After the release, and with a drag running.

    @Test
    fun `the stretch doesn't recompose the pages`() {
        // A recomposed page takes its date from the phone's date, which a zone change moves; the
        // headers keep theirs. So columns that stay under their headers weren't recomposed.
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+12"))
        show(3)
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT-14"))
        val shown = (0..2).map { (it to dayColumnDescription(it)) }
        holdSideways(columnCentreX(1, 3), 1.2f)
        assertEquals(shown, (0..2).map { (it to dayColumnDescription(it)) })
        lift()
    }

    /** Returns the date description of the on-screen column at [index], read left to right. */
    private fun dayColumnDescription(index: Int): String = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
        .fetchSemanticsNodes()
        .filter { it.boundsInRoot.height > 200f && it.boundsInRoot.width > 20f && it.boundsInRoot.left >= gridLeft() - 20f && it.boundsInRoot.right <= width() + 20f }
        .sortedBy { it.boundsInRoot.left }[index].config[SemanticsProperties.ContentDescription].single()

    private fun isBlue(c: androidx.compose.ui.graphics.Color) = c.blue > c.red + 0.3f

    /** Returns the bounds of the event block whose text contains [title]. */
    private fun blockBounds(title: String): Rect = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        .fetchSemanticsNodes()
        .first { n -> n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text.contains(title) } }.boundsInRoot

    @Test
    fun `after the switch the grid shows again on the target day`() {
        val target = weekStart.plusDays(4)
        val block = roomDisplayEvent(id = 1, title = "Review", date = target, hour = 9, durationMinutes = 120)
        val host = show(7, events = listOf(block))
        sidewaysPinch(columnCentreX(4, 7), 1.5f)
        assertEquals(3, host.visibleDays)
        assertEquals(AgendaWeekBarLogic.cellContentDescription(target, target == today, false, "Today", ""), dayColumnDescription(0))
        val review = blockBounds("Review")
        val pixel = rule.onRoot().captureToImage().toPixelMap()[review.right.toInt() - 6, review.bottom.toInt() - 6]
        assertTrue("grid visible again: $pixel", isBlue(pixel))
    }

    @Test
    fun `with nothing switched the grid comes back`() {
        val block = roomDisplayEvent(id = 1, title = "Review", date = weekStart.plusDays(4), hour = 9, durationMinutes = 120)
        show(7, follow = false, events = listOf(block))
        sidewaysPinch(columnCentreX(4, 7), 1.5f)
        val review = blockBounds("Review")
        val pixel = rule.onRoot().captureToImage().toPixelMap()[review.right.toInt() - 6, review.bottom.toInt() - 6]
        assertTrue("grid visible again: $pixel", isBlue(pixel))
    }

    @Test
    fun `a second finger during a drag doesn't switch the view`() {
        val day = weekStart.plusDays(2)
        val block = roomDisplayEvent(id = 1, title = "Review", date = day, hour = 9, durationMinutes = 120)
        val host = show(7, events = listOf(block))
        val review = blockBounds("Review")
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput {
            down(0, Offset(review.center.x, review.top + 4f))
            advanceEventTime(700)
            moveBy(0, Offset(0f, 60f))
            down(1, Offset(review.center.x + 60f, gridY + 200f))
            for (step in 1..10) {
                updatePointerTo(1, Offset(review.center.x + 60f + step * 10f, gridY + 200f))
                updatePointerTo(0, Offset(review.center.x - step * 10f, review.top + 64f))
                move()
            }
            up(1)
            up(0)
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(emptyList<Pair<Int, LocalDate>>(), host.switches)
        assertEquals(1, host.reschedules)
    }

    // Gestures that end oddly, and pinches in quick succession.

    @Test
    fun `a second pinch soon after the first still switches and leaves no stretch`() {
        val host = show(7)
        rule.mainClock.autoAdvance = false
        val x = columnCentreX(4, 7)
        rule.onRoot().performTouchInput {
            pinch(Offset(x - 40f, gridY), Offset(x - 60f, gridY), Offset(x + 40f, gridY), Offset(x + 60f, gridY), durationMillis = 100)
        }
        rule.mainClock.advanceTimeBy(QUICK_REPINCH_MS)
        val x2 = columnCentreX(1, 3)
        rule.onRoot().performTouchInput {
            pinch(Offset(x2 - 40f, gridY), Offset(x2 - 60f, gridY), Offset(x2 + 40f, gridY), Offset(x2 + 60f, gridY), durationMillis = 20)
        }
        rule.mainClock.advanceTimeBy(1_500)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(listOf(3 to weekStart.plusDays(4), 1 to weekStart.plusDays(5)), host.switches)
        val column = dayColumn(weekStart.plusDays(5))
        assertEquals("no stretch left", width() - gridLeft(), column.width, 2f)
    }

    @Test
    fun `a cancelled pinch switches nothing`() {
        val host = show(7)
        holdSideways(columnCentreX(4, 7), 1.5f)
        rule.onRoot().performTouchInput { cancel() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(emptyList<Pair<Int, LocalDate>>(), host.switches)
    }

    @Test
    fun `a third finger landing doesn't count as a spread`() {
        val host = show(7)
        val x = columnCentreX(3, 7)
        rule.onRoot().performTouchInput {
            down(0, Offset(x - 50f, gridY))
            down(1, Offset(x + 50f, gridY))
            for (step in 1..5) {
                updatePointerTo(0, Offset(x - 50f - step, gridY))
                updatePointerTo(1, Offset(x + 50f + step, gridY))
                move()
            }
            // A palm or third finger lands far off and stays down after the other two lift.
            down(2, Offset(x + 250f, gridY + 40f))
            move()
            up(0)
            up(1)
            up(2)
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(emptyList<Pair<Int, LocalDate>>(), host.switches)
    }

    @Test
    fun `a pinch-in that starts with the fingers close still switches`() {
        val host = show(1)
        val x = columnCentreX(0, 1)
        // 56px apart to 20px: well under 0.75 of where they started.
        rule.onRoot().performTouchInput {
            pinch(Offset(x - 28f, gridY), Offset(x - 10f, gridY), Offset(x + 28f, gridY), Offset(x + 10f, gridY), durationMillis = 300)
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(listOf(3 to today), host.switches)
    }
}

/** How soon after the first pinch's release the second one ends, inside the first's fallback. */
private const val QUICK_REPINCH_MS = 520L
