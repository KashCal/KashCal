package org.onekash.kashcal.ui.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [stepSwipe]: one step per sideways drag past the distance or the fling speed, signed by
 * the reading direction, and only the ways its flags allow at the moment the drag ends. Run the
 * class alone: Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class StepSwipeTest {

    @get:Rule
    val rule = createComposeRule()

    private class Host {
        var enabled by mutableStateOf(true)
        var canGoBack by mutableStateOf(true)
        var canGoForward by mutableStateOf(true)
        val steps = mutableListOf<Int>()
    }

    private fun render(direction: LayoutDirection = LayoutDirection.Ltr): Host {
        val host = Host()
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("area")
                        .stepSwipe(host.enabled, host.canGoBack, host.canGoForward) { host.steps += it }
                )
            }
        }
        rule.waitForIdle()
        return host
    }

    private fun area() = rule.onNodeWithTag("area")

    private fun swipeAreaLeft() {
        area().performTouchInput { swipeLeft() }
        rule.waitForIdle()
    }

    private fun swipeAreaRight() {
        area().performTouchInput { swipeRight() }
        rule.waitForIdle()
    }

    @Test
    fun `a drag switched off before it ends is no step`() {
        val host = render()
        val distance = with(rule.density) { 60.dp.toPx() }
        area().performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(-distance / 6, 0f)) }
        }
        rule.waitForIdle()
        // The month and year wheel opening mid-drag switches the swipe off.
        host.enabled = false
        rule.waitForIdle()
        area().performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(emptyList<Int>(), host.steps)
    }

    @Test
    fun `left to right, a swipe left is a step forward and a swipe right a step back`() {
        val host = render()
        swipeAreaLeft()
        swipeAreaRight()
        assertEquals(listOf(1, -1), host.steps)
    }

    @Test
    fun `right to left, a swipe right is a step forward and a swipe left a step back`() {
        val host = render(LayoutDirection.Rtl)
        swipeAreaRight()
        swipeAreaLeft()
        assertEquals(listOf(1, -1), host.steps)
    }

    @Test
    fun `a short slow swipe is no step`() {
        val host = render()
        area().performTouchInput {
            val distance = with(rule.density) { 16.dp.toPx() }
            swipe(center, center - Offset(distance, 0f), durationMillis = 1_500)
        }
        rule.waitForIdle()
        assertEquals(emptyList<Int>(), host.steps)
    }

    @Test
    fun `a short fast flick is one step`() {
        val host = render()
        area().performTouchInput {
            // 30dp in 50ms, 600dp a second: under the distance, over the fling speed.
            val distance = with(rule.density) { 30.dp.toPx() }
            swipe(center, center - Offset(distance, 0f), durationMillis = 50)
        }
        rule.waitForIdle()
        assertEquals(listOf(1), host.steps)
    }

    @Test
    fun `switched off, a swipe is no step`() {
        val host = render()
        host.enabled = false
        rule.waitForIdle()
        swipeAreaLeft()
        swipeAreaRight()
        assertEquals(emptyList<Int>(), host.steps)
    }

    @Test
    fun `no step forward past the end, a step back still works`() {
        val host = render()
        host.canGoForward = false
        rule.waitForIdle()
        swipeAreaLeft()
        swipeAreaRight()
        assertEquals(listOf(-1), host.steps)
    }

    @Test
    fun `each drag reads the flags as they are when it ends`() {
        val host = render()
        swipeAreaLeft()
        host.canGoForward = false
        rule.waitForIdle()
        swipeAreaLeft()
        host.canGoForward = true
        rule.waitForIdle()
        swipeAreaLeft()
        assertEquals(listOf(1, 1), host.steps)
    }

    @Test
    fun `stepFromDrag needs the distance or the speed past its threshold`() {
        val d = 100f
        val v = 1_000f
        assertEquals(0, stepFromDrag(0f, 0f, d, v))
        assertEquals(0, stepFromDrag(-99f, -999f, d, v))
        assertEquals(1, stepFromDrag(-100f, 0f, d, v))
        assertEquals(-1, stepFromDrag(100f, 0f, d, v))
        assertEquals(1, stepFromDrag(-10f, -1_000f, d, v))
        assertEquals(-1, stepFromDrag(10f, 1_000f, d, v))
        assertEquals(0, stepFromDrag(99f, 999f, d, v))
    }

    @Test
    fun `stepFromDrag follows a fast fling over the distance`() {
        // Dragged far toward the start, then flung back toward the end: the fling wins.
        assertEquals(-1, stepFromDrag(-300f, 2_000f, 100f, 1_000f))
        assertEquals(1, stepFromDrag(300f, -2_000f, 100f, 1_000f))
    }
}
