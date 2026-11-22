package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.math.abs

/**
 * Compose tests that the time wheels recompose only the rows that change, and that the centered
 * labels still match the time. Row recompositions are counted with a [CompositionTracer] on the
 * row lambda of the wheel body that [VerticalWheelPicker] draws, which runs even when the row's
 * own content skips. Run the class alone: Robolectric runs of more than one class hit a native
 * crash.
 */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w411dp-h914dp-mdpi")
class WheelRecompositionTest {

    @get:Rule
    val rule = createComposeRule()

    private val savedLocale = Locale.getDefault()

    /** Counts compositions of the row lambda [VerticalWheelPicker] runs for each row. */
    private object RowTracer : CompositionTracer {
        private val rowLambda = Regex("""^org\.onekash\.kashcal\.ui\.components\.WheelPickerBody(\.<anonymous>){4} \(WheelTimePicker\.kt:\d+\)$""")
        var rows = 0

        override fun isTraceInProgress() = true

        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            if (rowLambda.matches(info)) rows++
        }

        override fun traceEventEnd() = Unit
    }

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
        RowTracer.rows = 0
        Composer.setTracer(RowTracer)
    }

    @After
    fun tearDown() {
        Composer.setTracer(null)
        Locale.setDefault(savedLocale)
    }

    // ---- a center change ----

    @Test
    fun `a one-row move recomposes only the rows that gain or lose the center`() {
        var selected by mutableIntStateOf(30)
        var firstCompositions = 0
        // One list for the whole test, as WheelTimePicker keeps its own.
        val minutes = (0..59).toList()
        rule.setContent {
            MaterialTheme {
                VerticalWheelPicker(
                    items = minutes,
                    selectedItem = selected,
                    onItemSelected = { selected = it },
                    visibleItems = 5,
                    itemHeight = 32.dp,
                    isCircular = true,
                ) { item, isSelected ->
                    remember { firstCompositions++ }
                    Text(text = "$item", color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        rule.waitForIdle()
        val rowsBefore = RowTracer.rows
        val firstBefore = firstCompositions

        selected = 31
        rule.waitForIdle()

        val recomposed = (RowTracer.rows - rowsBefore) - (firstCompositions - firstBefore)
        assertEquals("row recompositions after a one-row move", 2, recomposed)
    }

    // ---- a recomposition of the whole picker ----

    @Test
    fun `a time picker recomposition with the same time recomposes no rows`() = noRowsRecompose(use24Hour = false)

    @Test
    fun `a 24-hour time picker recomposition with the same time recomposes no rows`() = noRowsRecompose(use24Hour = true)

    private fun noRowsRecompose(use24Hour: Boolean) {
        var generation by mutableIntStateOf(0)
        rule.setContent {
            val seen = generation
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 9,
                    selectedMinute = 35,
                    // A new callback each generation recomposes the picker with the same time.
                    onTimeSelected = { _, _ -> check(seen >= 0) },
                    use24Hour = use24Hour,
                    visibleItems = 5,
                    itemHeight = 32.dp,
                )
            }
        }
        rule.waitForIdle()
        val rowsBefore = RowTracer.rows
        assertTrue("the tracer saw no rows: its pattern no longer matches the row lambda", rowsBefore > 0)

        generation++
        rule.waitForIdle()

        assertEquals("row recompositions", 0, RowTracer.rows - rowsBefore)
    }

    // ---- the centered labels ----

    private fun wheelCenter(): Dp = rule.onAllNodesWithContentDescription("Wheel picker", substring = true)[0]
        .getUnclippedBoundsInRoot().let { (it.top + it.bottom) / 2 }

    /** Asserts a node reading [text] sits at the wheels' center line. */
    private fun assertCentered(text: String) {
        val center = wheelCenter()
        val nearest = rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes()
            .map { node -> with(rule.density) { (node.boundsInRoot.center.y).toDp() } }
            .minByOrNull { abs((it - center).value) }
        assertTrue("no '$text' at the center ($center), nearest $nearest", nearest != null && abs((nearest - center).value) < 1f)
    }

    private fun renderTime(use24Hour: Boolean, onTime: (Int, Int) -> Unit = { _, _ -> }) {
        rule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 9,
                    selectedMinute = 35,
                    onTimeSelected = onTime,
                    use24Hour = use24Hour,
                    visibleItems = 5,
                    itemHeight = 32.dp,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `a 12-hour wheel centers 9, 35 and AM at 9 35`() {
        renderTime(use24Hour = false)

        assertCentered("9")
        assertCentered("35")
        assertCentered("AM")
    }

    @Test
    fun `a 24-hour wheel centers 09 and 35 at 9 35`() {
        renderTime(use24Hour = true)

        assertCentered("09")
        assertCentered("35")
    }

    @Test
    fun `after a one-row scroll the centered minute is the reported one`() {
        var reportedMinute = 35
        renderTime(use24Hour = false) { _, m -> reportedMinute = m }

        rule.onAllNodesWithContentDescription("Wheel picker with 12 options")[1]
            .performTouchInput { swipeUp(startY = centerY, endY = centerY - 32f, durationMillis = 600) }
        rule.waitForIdle()

        assertEquals("one row up", 40, reportedMinute)
        assertCentered("40")
    }
}
