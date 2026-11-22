package org.onekash.kashcal.ui.components.pickers

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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
import java.time.LocalDate
import java.time.YearMonth
import java.util.Calendar
import java.util.Locale

/**
 * Compose tests for [DateTimeSheetGrid], the date and time sheet's own month grid: its exact
 * height in 5- and 6-week months and with the month and year wheel open, 48dp touch targets, the
 * month name as a button, short weekday names in the week's order, selected and range-end states,
 * right-to-left order and the month swipe. Run the class alone: Robolectric runs of more than one
 * class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w411dp-h914dp-mdpi")
class DateTimeSheetGridTest {

    @get:Rule
    val rule = createComposeRule()

    private val savedLocale = Locale.getDefault()

    @Before
    fun pin() = Locale.setDefault(Locale.US)

    @After
    fun restore() = Locale.setDefault(savedLocale)

    private class Host {
        var month by mutableStateOf(YearMonth.of(2026, 10))
        var picked: LocalDate? = null
    }

    private fun render(
        edited: LocalDate = LocalDate.of(2026, 10, 27),
        month: YearMonth = YearMonth.of(2026, 10),
        range: ClosedRange<LocalDate>? = null,
        firstDayOfWeek: Int = Calendar.SUNDAY,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ): Host {
        val host = Host().also { it.month = month }
        rule.setContent {
            var showWheel by remember { mutableStateOf(false) }
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Box(Modifier.testTag("grid")) {
                        DateTimeSheetGrid(
                            editedDate = edited,
                            displayedMonth = host.month,
                            range = range,
                            weekdayRowHeight = 32.dp,
                            firstDayOfWeek = firstDayOfWeek,
                            showMonthYearPicker = showWheel,
                            onShowMonthYearPickerChange = { showWheel = it },
                            onSelectDate = { host.picked = it },
                            onMonth = { host.month = it },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        return host
    }

    private fun height(tag: String): Dp = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.bottom - it.top }

    private fun bounds(text: String) = rule.onNodeWithText(text).getUnclippedBoundsInRoot()

    private val gridHeight = 48.dp + 32.dp + 48.dp * 6

    // ---- sizes ----

    @Test
    fun `the grid is the month header, the weekday row and six 48dp rows`() {
        render()

        assertEquals(gridHeight, height("grid"))
    }

    @Test
    fun `a six-week month has the same height`() {
        render(edited = LocalDate.of(2026, 8, 3), month = YearMonth.of(2026, 8))

        assertEquals(gridHeight, height("grid"))
    }

    @Test
    fun `opening the month and year wheel keeps the height`() {
        render()

        rule.onNodeWithContentDescription("Pick month and year").performClick()
        rule.waitForIdle()

        assertEquals(gridHeight, height("grid"))
        rule.onNodeWithContentDescription("Show calendar").assertExists()
    }

    @Test
    fun `day cells, arrows and the month name are 48dp touch targets`() {
        render()

        val cell = bounds("15")
        assertEquals(48.dp, cell.bottom - cell.top)
        rule.onNodeWithContentDescription("Previous month").assertTouchHeightIsEqualTo(48.dp)
        rule.onNodeWithContentDescription("Next month").assertTouchHeightIsEqualTo(48.dp)
        val monthButton = rule.onNodeWithContentDescription("Pick month and year").getUnclippedBoundsInRoot()
        assertEquals(48.dp, monthButton.bottom - monthButton.top)
    }

    @Config(qualifiers = "en-rUS-w360dp-h640dp-mdpi")
    @Test
    fun `day cells are 48dp wide on a 360dp phone`() {
        render()

        val cell = bounds("15")
        assertTrue("cell width ${cell.right - cell.left}", cell.right - cell.left >= 48.dp)
    }

    // ---- the month name ----

    @Test
    fun `the month name reads as a button naming the month`() {
        render()

        rule.onNodeWithContentDescription("Pick month and year")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "October 2026"))
    }

    // ---- weekday names ----

    @Test
    fun `weekday names are short and start on Sunday`() {
        render()

        assertTrue(bounds("Sun").left < bounds("Mon").left)
        assertTrue(bounds("Fri").left < bounds("Sat").left)
    }

    @Test
    fun `a Monday-first week puts Sunday last`() {
        render(firstDayOfWeek = Calendar.MONDAY)

        assertTrue(bounds("Mon").left < bounds("Tue").left)
        assertTrue(bounds("Sat").left < bounds("Sun").left)
    }

    // ---- states ----

    @Test
    fun `the edited day reads as selected`() {
        render()

        rule.onNodeWithText("27").assertIsSelected()
        rule.onNodeWithText("28").assertIsNotSelected()
    }

    @Test
    fun `the range ends read as start and end`() {
        render(edited = LocalDate.of(2026, 10, 31), range = LocalDate.of(2026, 10, 27)..LocalDate.of(2026, 10, 31))

        rule.onNodeWithText("27").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Starts"))
        rule.onNodeWithText("31").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Ends"))
        rule.onNodeWithText("31").assertIsSelected()
    }

    @Test
    fun `a tap picks the day`() {
        val host = render()

        rule.onNodeWithText("20").performClick()

        assertEquals(LocalDate.of(2026, 10, 20), host.picked)
    }

    // ---- right to left ----

    @Test
    fun `right to left puts later days on the left`() {
        render(direction = LayoutDirection.Rtl)

        // Oct 1 and 2 (Thursday and Friday) share a row.
        assertTrue(bounds("2").left < bounds("1").left)
    }

    // ---- month changes ----

    @Test
    fun `a swipe moves to the next month`() {
        val host = render()

        rule.onNodeWithText("15").performTouchInput { swipeLeft(startX = centerX, endX = centerX - 400f, durationMillis = 200) }
        rule.waitForIdle()

        assertEquals(YearMonth.of(2026, 11), host.month)
        rule.onNodeWithText("November 2026").assertExists()
    }

    @Test
    fun `the arrows move a month back and forth`() {
        val host = render()

        rule.onNodeWithContentDescription("Next month").performClick()
        rule.waitForIdle()
        assertEquals(YearMonth.of(2026, 11), host.month)

        rule.onNodeWithContentDescription("Previous month").performClick()
        rule.waitForIdle()
        assertEquals(YearMonth.of(2026, 10), host.month)
    }
}
