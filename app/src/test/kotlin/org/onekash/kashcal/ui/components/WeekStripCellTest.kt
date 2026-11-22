package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.components.pickers.DayCellStyle
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the date cell pieces the Week header and the week strips share and reads what they draw:
 * the 11sp weekday letter, and the day number's circle, 34dp at the default font, square and
 * grown around the number at large fonts, never wider than its cell, filled or ringed by its
 * [DayCellStyle]. mdpi, so 1dp is 1px.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class WeekStripCellTest {

    @get:Rule
    val rule = createComposeRule()

    private val tag = "circle"
    private var colors: androidx.compose.material3.ColorScheme? = null

    private fun showCircle(day: Int, style: DayCellStyle = DayCellStyle.PLAIN, fontScale: Float = 1f, cellWidth: Float? = null) {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                MaterialTheme {
                    colors = MaterialTheme.colorScheme
                    Box(Modifier.padding(10.dp).then(if (cellWidth != null) Modifier.width(cellWidth.dp) else Modifier)) {
                        WeekStripDayCircle(
                            date = LocalDate.of(2026, 10, day),
                            style = style,
                            modifier = Modifier.testTag(tag),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun circle(): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun numberLayout(): Pair<Rect, TextLayoutResult> {
        val node = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes().single()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return node.boundsInRoot to layouts.single()
    }

    @Test
    fun `a one-digit number's circle is 34dp`() {
        showCircle(9)
        assertEquals(34f, circle().width, 0.5f)
        assertEquals(34f, circle().height, 0.5f)
    }

    @Test
    fun `a two-digit number's circle is 34dp`() {
        showCircle(28)
        assertEquals(34f, circle().width, 0.5f)
        assertEquals(34f, circle().height, 0.5f)
    }

    @Test
    fun `at font scale 2 the circle grows around the number and stays round`() {
        showCircle(28, fontScale = 2f)
        val c = circle()
        assertEquals("square: $c", c.width, c.height, 0.5f)
        assertTrue("grown: $c", c.width > 34f)
        val (text, layout) = numberLayout()
        val top = text.top + layout.getLineTop(0)
        val bottom = text.top + layout.getLineBottom(0)
        val left = text.left + layout.getLineLeft(0)
        val right = text.left + layout.getLineRight(0)
        assertTrue("number $left..$right x $top..$bottom inside $c", left >= c.left - 0.5f && right <= c.right + 0.5f && top >= c.top - 0.5f && bottom <= c.bottom + 0.5f)
    }

    @Test
    fun `the circle never gets wider than its cell`() {
        showCircle(28, fontScale = 2f, cellWidth = 40f)
        val c = circle()
        assertEquals("capped: $c", 40f, c.width, 0.5f)
        assertEquals("square: $c", c.width, c.height, 0.5f)
    }

    private fun pixel(x: Float, y: Float): Color = rule.onRoot().captureToImage().toPixelMap()[x.toInt(), y.toInt()]

    /** A pixel inside the circle but clear of the number. */
    private fun fillPixel(): Color = circle().let { pixel(it.left + 6f, it.center.y) }

    private fun close(a: Color, b: Color) =
        maxOf(kotlin.math.abs(a.red - b.red), kotlin.math.abs(a.green - b.green), kotlin.math.abs(a.blue - b.blue)) < 0.06f

    @Test
    fun `the day you're on is a dark circle`() {
        showCircle(16, DayCellStyle.SELECTED)
        assertTrue("fill ${fillPixel()} vs ${colors!!.inverseSurface}", close(fillPixel(), colors!!.inverseSurface))
    }

    @Test
    fun `today is a tonal circle with an outline ring`() {
        showCircle(16, DayCellStyle.TODAY)
        assertTrue("fill ${fillPixel()} vs ${colors!!.primaryContainer}", close(fillPixel(), colors!!.primaryContainer))
        // The 1px ring blends with its neighbours, so check it is darker than the fill all round.
        val c = circle()
        val r = c.width / 2 - 0.5f
        val ringDarker = (0 until 8).count { i ->
            val a = i * Math.PI / 4
            val p = pixel(c.center.x + (r * cos(a)).toFloat(), c.center.y + (r * sin(a)).toFloat())
            p.green < colors!!.primaryContainer.green - 0.1f
        }
        assertTrue("ring at $ringDarker of 8 points", ringDarker >= 6)
    }

    @Test
    fun `a plain day has no fill`() {
        showCircle(14)
        val outside = pixel(circle().left - 4f, circle().center.y)
        assertTrue("fill ${fillPixel()} vs background $outside", close(fillPixel(), outside))
    }

    @Test
    fun `weekday letters are grey and weekend letters and numbers red`() {
        rule.setContent {
            MaterialTheme {
                colors = MaterialTheme.colorScheme
                androidx.compose.foundation.layout.Row {
                    WeekStripLetter(date = LocalDate.of(2026, 10, 14))
                    WeekStripLetter(date = LocalDate.of(2026, 10, 17))
                    WeekStripDayCircle(date = LocalDate.of(2026, 10, 18), style = DayCellStyle.PLAIN)
                }
            }
        }
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes().sortedBy { it.boundsInRoot.left }
        val styles = nodes.map { n ->
            val layouts = mutableListOf<TextLayoutResult>()
            n.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            layouts.single().layoutInput.style.color
        }
        assertEquals("Wednesday", colors!!.onSurfaceVariant, styles[0])
        assertEquals("Saturday", colors!!.error, styles[1])
        assertEquals("Sunday's number", colors!!.error, styles[2])
    }

    @Test
    fun `the weekday letter is 11sp`() {
        rule.setContent {
            MaterialTheme { WeekStripLetter(date = LocalDate.of(2026, 10, 14)) }
        }
        val (_, layout) = numberLayout()
        assertEquals(11.sp, layout.layoutInput.style.fontSize)
        assertEquals("W", layout.layoutInput.text.text)
    }
}
