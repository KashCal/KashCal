package org.onekash.kashcal.ui.components.pickers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assert
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the date and time sheet's [SheetSwitch] and [SheetChipsRow] drawn on their own:
 * the one-line halves at a height that doesn't follow their text, and the chips row in rows too
 * narrow for every chip. Robolectric measures text far narrower than a phone does, so the chips
 * row is squeezed into a narrow box. Run the class alone: Robolectric runs of more than one class
 * hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w411dp-h914dp-mdpi")
class DateTimeSheetPartsTest {

    @get:Rule
    val rule = createComposeRule()

    private class SwitchHost {
        var endValue by mutableStateOf("9:30 AM")
    }

    private fun renderSwitch(host: SwitchHost = SwitchHost()): SwitchHost {
        rule.setContent {
            MaterialTheme {
                SheetSwitch(
                    side = DateSelectionMode.START,
                    startLabel = "Starts", startValue = "9:00 AM",
                    endLabel = "Ends", endValue = host.endValue,
                    onFlip = {},
                )
            }
        }
        rule.waitForIdle()
        return host
    }

    private fun half(label: String) = rule.onNodeWithText(label, substring = true)

    private fun halfHeight(label: String) = half(label).getUnclippedBoundsInRoot().let { it.bottom - it.top }

    @Test
    fun `a half shows its label and value side by side on one line`() {
        renderSwitch()

        val label = rule.onNodeWithText("Starts", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val value = rule.onNodeWithText("9:00 AM", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("label ends ${label.right}, value starts ${value.left}", label.right <= value.left)
        val labelMiddle = (label.top + label.bottom) / 2
        val valueMiddle = (value.top + value.bottom) / 2
        assertTrue("middles $labelMiddle and $valueMiddle", (labelMiddle - valueMiddle).value in -1f..1f)
        half("Starts").assert(hasText("Starts")).assert(hasText("9:00 AM"))
    }

    @Test
    fun `a long value keeps the half's height`() {
        val host = renderSwitch()
        val before = halfHeight("Ends")

        host.endValue = "Thu, Oct 29 12:30 AM, a value far too long for its half"
        rule.waitForIdle()

        assertEquals(before, halfHeight("Ends"))
        rule.onNodeWithText(host.endValue, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `both halves are the same height with values of different lengths`() {
        renderSwitch(SwitchHost().apply { endValue = "Nov 2 9:30 AM, a much longer value" })

        assertEquals(halfHeight("Starts"), halfHeight("Ends"))
    }

    private val chips = listOf(
        SheetChip(value = 2, label = "2 days", spoken = "2 days", selected = false),
        SheetChip(value = 5, label = "5 days", spoken = "5 days", selected = true),
        SheetChip(value = 7, label = "7 days", spoken = "7 days", selected = false),
    )

    private fun renderChips(width: Int) {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(width.dp)) {
                    SheetChipsRow(chips = chips, onChip = {})
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `a narrow row shows only the chips that fit whole`() {
        renderChips(width = 90)

        rule.onNodeWithText("2 days").assertExists()
        rule.onNodeWithText("5 days").assertDoesNotExist()
        rule.onNodeWithText("7 days").assertDoesNotExist()
    }

    @Test
    fun `a single chip wider than the row is capped at the row`() {
        renderChips(width = 50)

        val chip = rule.onNodeWithText("2 days").getUnclippedBoundsInRoot()
        assertTrue("chip ${chip.left}..${chip.right}", chip.left >= 0.dp && chip.right <= 50.dp)
    }

    @Test
    fun `a wide row shows every chip at the same width, centered`() {
        renderChips(width = 411)

        val b = listOf("2 days", "5 days", "7 days").map { rule.onAllNodesWithText(it)[0].getUnclippedBoundsInRoot() }
        val widths = b.map { it.right - it.left }
        assertTrue("widths $widths", widths.all { (it - widths[0]).value in -0.5f..0.5f })
        // The row's middle sits on the box's middle, to within a pixel of layout rounding.
        val rowMiddle = (b.first().left + b.last().right) / 2
        assertTrue("row middle $rowMiddle", (rowMiddle - 205.5.dp).value in -1.5f..1.5f)
    }
}
