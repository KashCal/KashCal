package org.onekash.kashcal.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Month
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

/**
 * Checks that the year view shows all twelve months in the room it is given, on phones of
 * different heights and in landscape, without scrolling at the normal font size, and that a
 * larger font scrolls the page instead of shrinking the days.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "en-rUS-w800dp-h1300dp-mdpi")
class YearViewFitTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val year = Calendar.getInstance().get(Calendar.YEAR)

    private fun render(size: DpSize) {
        composeTestRule.setContent {
            MaterialTheme {
                Box(Modifier.size(size).testTag("room")) {
                    YearViewContent(
                        eventDots = persistentMapOf(),
                        firstDayOfWeek = Calendar.MONDAY,
                        pendingNavigateToToday = false,
                        onNavigateToTodayConsumed = {},
                        onMonthClick = { _, _ -> },
                        onYearChanged = {},
                        onBackToMonth = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun assertWholeYearFits(size: DpSize) {
        render(size)
        val room = composeTestRule.onNodeWithTag("room").fetchSemanticsNode().boundsInRoot
        val janHeight = monthNode(Month.JANUARY).size.height
        for (month in Month.entries) {
            val label = "${month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} $year"
            // boundsInRoot is clipped to the parents, so a cut-off month would still look inside.
            // A Column squeezes the children it runs out of room for, so a month cut short also
            // comes out shorter than January.
            val node = monthNode(month)
            assertTrue("$label is ${node.size.height}px tall, January $janHeight", node.size.height == janHeight)
            val bottom = node.positionInRoot.y + node.size.height
            val right = node.positionInRoot.x + node.size.width
            assertTrue("$label ends at $bottom, room ends at ${room.bottom}", bottom <= room.bottom + 0.5f)
            assertTrue("$label ends at $right, room ends at ${room.right}", right <= room.right + 0.5f)
        }
    }

    private fun monthNode(month: Month) = composeTestRule
        .onNodeWithContentDescription("${month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} $year")
        .fetchSemanticsNode()

    @Test
    fun `the whole year fits a short phone`() = assertWholeYearFits(DpSize(360.dp, 460.dp))

    @Test
    fun `the whole year fits a tall phone`() = assertWholeYearFits(DpSize(412.dp, 700.dp))

    @Test
    fun `the whole year fits a landscape phone`() = assertWholeYearFits(DpSize(780.dp, 300.dp))

    @Test
    @Config(qualifiers = "en-rUS-w800dp-h1300dp-420dpi")
    fun `the whole year fits a short phone at a phone's density`() =
        assertWholeYearFits(DpSize(360.dp, 460.dp))

    @Test
    @Config(qualifiers = "en-rUS-w800dp-h1300dp-420dpi")
    fun `the whole year fits a tall phone at a phone's density`() =
        assertWholeYearFits(DpSize(411.dp, 640.dp))

    @Test
    @Config(qualifiers = "en-rUS-w800dp-h1300dp-420dpi", fontScale = 1.5f)
    fun `at a large font size the page scrolls and month names and weekday letters are whole`() {
        render(DpSize(411.dp, 640.dp))
        // Only the page scrolls vertically; the year strip and the pager scroll sideways.
        val vertical = composeTestRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNodes()
        assertEquals("the year page scrolls", 1, vertical.size)
        for (text in listOf("Aug", "Sep", "M", "W")) {
            val node = composeTestRule.onAllNodesWithText(text, useUnmergedTree = true)
                .fetchSemanticsNodes().first()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertFalse("\"$text\" is cut at font scale 1.5", layouts.single().didOverflowHeight)
            assertTrue(
                "\"$text\" is ${layouts.single().size.height}px in a ${node.size.height}px row",
                layouts.single().size.height <= node.size.height,
            )
        }
    }

    @Test
    fun `each year in the top strip is at least 40dp tall to tap`() {
        assertWholeYearFits(DpSize(412.dp, 700.dp))
        val button = composeTestRule.onNodeWithText("$year", useUnmergedTree = false).fetchSemanticsNode()
        // mdpi: a pixel is a dp.
        assertTrue("the $year button is ${button.size.height}px tall", button.size.height >= 40)
    }

    @Test
    fun `day cells at a larger font size are no smaller than at the normal size`() {
        // 411dp by 600dp at 420dpi, the room under the year strip on a 640dp-tall phone; the
        // month name and weekday rows are as tall as their text measures at each font size.
        val normal = yearLayout(411.dp, 600.dp, Density(2.625f, 1.0f), labelHeight = 16.dp, weekdayHeight = 13.24.dp)
        val large = yearLayout(
            411.dp, 600.dp, Density(2.625f, 1.5f), labelHeight = 23.24.dp, weekdayHeight = 16.76.dp,
            baseLabelHeight = 16.dp, baseWeekdayHeight = 13.24.dp,
        )
        assertTrue("cell ${large.cell} at font scale 1.5, ${normal.cell} at 1.0", large.cell > normal.cell)
        // 34px x 1.5 = 51px, capped by the widest cell the width allows: 3 columns of 45px,
        // scrolling.
        assertEquals(with(Density(2.625f)) { 45.toDp() }, large.cell)
        assertEquals(3, large.columns)
        assertTrue(large.scrolls)
    }

    @Test
    fun `at the normal font size the base row heights change nothing`() {
        val plain = yearLayout(411.dp, 600.dp, Density(2.625f, 1.0f), labelHeight = 16.dp, weekdayHeight = 13.24.dp)
        val withBase = yearLayout(
            411.dp, 600.dp, Density(2.625f, 1.0f), labelHeight = 16.dp, weekdayHeight = 13.24.dp,
            baseLabelHeight = 40.dp, baseWeekdayHeight = 40.dp,
        )
        assertEquals(plain, withBase)
        assertEquals(YearLayout(columns = 3, cell = with(Density(2.625f)) { 34.toDp() }, scrolls = false), plain)
    }

    @Test
    fun `a slightly larger font on a landscape phone keeps days in proportion with the most columns that fit them`() {
        // 891dp by 360dp at 420dpi: six columns of 48px days at font scale 1.0. At 1.15 the days
        // must reach ceil(48 x 1.15) = 56px; six columns leave only about 51px across, so the page
        // takes four columns, the most whose cells reach 56px, and scrolls rather than jumping to
        // three wide columns.
        val normal = yearLayout(891.dp, 360.dp, Density(2.625f, 1.0f), labelHeight = 16.dp, weekdayHeight = 13.24.dp)
        val large = yearLayout(
            891.dp, 360.dp, Density(2.625f, 1.15f), labelHeight = 18.4.dp, weekdayHeight = 14.2.dp,
            baseLabelHeight = 16.dp, baseWeekdayHeight = 13.24.dp,
        )
        val floor = with(Density(2.625f)) { kotlin.math.ceil(normal.cell.roundToPx() * 1.15f - 0.001f).toInt().toDp() }
        assertEquals("$normal", 6, normal.columns)
        assertEquals("$normal -> $large", floor, large.cell)
        assertEquals("$normal -> $large", 4, large.columns)
        assertTrue("$large", large.scrolls)
    }

    @Test
    fun `a page too short for every layout scrolls with three columns`() {
        // A phone in split screen: no column count reaches the smallest cell, and once the page
        // scrolls only the width limits the cells.
        val layout = yearLayout(411.dp, 330.dp, Density(2.625f), labelHeight = 16.dp, weekdayHeight = 10.dp)
        assertTrue(layout.scrolls)
        assertEquals(3, layout.columns)
        assertTrue("cell ${layout.cell}", layout.cell > 14.dp)
    }
}
