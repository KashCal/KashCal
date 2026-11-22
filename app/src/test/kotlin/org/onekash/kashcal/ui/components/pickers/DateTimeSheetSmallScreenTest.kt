package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.components.EventFormState
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * Compose tests for [DateTimeSheet] on a small phone. Robolectric reports no status or
 * navigation bar, so the 592dp-tall window gives the sheet the same middle (468dp) a 360x640dp
 * phone with gesture navigation does. Run the class alone: Robolectric runs of more than one
 * class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h592dp-mdpi")
class DateTimeSheetSmallScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val savedLocale = Locale.getDefault()
    private val savedZone = TimeZone.getDefault()

    @Before
    fun pin() {
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }

    @After
    fun restore() {
        Locale.setDefault(savedLocale)
        TimeZone.setDefault(savedZone)
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun midnight(date: LocalDate) = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val oct27 = LocalDate.of(2026, 10, 27)

    private fun timedForm() = EventFormState(
        isAllDay = false,
        dateMillis = midnight(oct27), startHour = 9, startMinute = 0,
        endDateMillis = midnight(oct27), endHour = 9, endMinute = 30,
    )

    private fun allDayForm() = EventFormState(isAllDay = true, dateMillis = midnight(oct27), endDateMillis = midnight(oct27))

    private var density: Density? = null

    private fun render(form: EventFormState = timedForm()) {
        rule.setContent {
            density = LocalDensity.current
            var open by remember { mutableStateOf(true) }
            MaterialTheme {
                if (open) {
                    DateTimeSheet(
                        initial = form,
                        openingSide = DateSelectionMode.START,
                        defaultDurationMinutes = 30,
                        onCommit = { open = false },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun hourWheel() = rule.onAllNodesWithContentDescription("Wheel picker with 12 options")[0]

    private fun height(node: androidx.compose.ui.test.SemanticsNodeInteraction) =
        node.getUnclippedBoundsInRoot().let { it.bottom - it.top }

    @Test
    fun `a small phone shows a three-row wheel`() {
        render()

        assertEquals(32.dp * 3, height(hourWheel()))
    }

    @Test
    fun `a small phone has no timed length chips`() {
        render()

        rule.onNodeWithText("1h").assertDoesNotExist()
    }

    @Test
    fun `a small phone keeps the all-day day chips`() {
        render(form = allDayForm())

        rule.onNodeWithText("5 days").assertIsDisplayed()
    }

    @Test
    fun `day cells in the sheet are 48dp wide and tall on a small phone`() {
        render()

        val bounds = rule.onNodeWithText("15").getUnclippedBoundsInRoot()
        assertTrue("cell ${bounds.right - bounds.left} x ${bounds.bottom - bounds.top}",
            bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
    }

    @Test
    fun `at 200 percent font the middle scrolls and the switch and Done stay on screen`() {
        RuntimeEnvironment.setFontScale(2f)
        render()
        assertEquals("font scale did not take", 2f, density!!.fontScale, 0f)

        val expectedRow = density!!.sheetWheelRowMinHeight()
        assertEquals(expectedRow * 3, height(hourWheel()))
        rule.onNodeWithText("Starts", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Ends", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Done").assertIsDisplayed()
        val scrolls = SemanticsMatcher("scrolls vertically") {
            val range = it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            range != null && range.maxValue() > 0f
        }
        // The wheels scroll too; the sheet's middle is the one that spans the full width.
        val fullWidth = rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().maxOf { it.size.width }
        assertTrue("no full-width scrolling middle",
            rule.onAllNodes(scrolls).fetchSemanticsNodes().any { it.size.width == fullWidth })
    }

    @Test
    fun `at 200 percent font the switch and grid stay put when the end moves to the next day`() {
        RuntimeEnvironment.setFontScale(2f)
        render()
        val switchBefore = height(rule.onNodeWithText("Ends", substring = true))
        val headerBefore = rule.onNodeWithContentDescription("Previous month").getUnclippedBoundsInRoot().top

        rule.onNodeWithText("Ends", substring = true).performClick()
        rule.onNodeWithText("28").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Ends", substring = true).assert(hasText("Oct 28 9:30 AM", substring = true))
        assertEquals(switchBefore, height(rule.onNodeWithText("Ends", substring = true)))
        assertEquals(headerBefore, rule.onNodeWithContentDescription("Previous month").getUnclippedBoundsInRoot().top)
    }
}
