package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [SwipeRevealRow] (swipe reveals Delete, one haptic tick, one open row, accessibility
 * action, right-to-left) with two rows, and [RevealedRow]'s transitions.
 */
@OptIn(ExperimentalTestApi::class)
@Config(qualifiers = "w411dp-h891dp")
@RunWith(RobolectricTestRunner::class)
class SwipeRevealRowTest {

    @get:Rule
    val rule = createComposeRule()

    private val revealed = RevealedRow()
    private val haptics = mutableListOf<HapticFeedbackType>()
    private val deleted = mutableListOf<Long>()
    private var enabled by mutableStateOf(true)

    private fun show(enabled: Boolean = true, direction: LayoutDirection = LayoutDirection.Ltr) = rule.setContent {
        val recorder = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                haptics += hapticFeedbackType
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides direction, LocalHapticFeedback provides recorder) {
            MaterialTheme {
                Column {
                    for (id in listOf(1L, 2L)) {
                        SwipeRevealRow(
                            enabled = enabled && this@SwipeRevealRowTest.enabled,
                            open = revealed.openId == id,
                            onOpenChange = { revealed.set(id, it) },
                            deleteLabel = "Delete $id",
                            onDelete = { deleted += id },
                        ) {
                            Text("Row $id", it.testTag("row-$id").fillMaxWidth().padding(16.dp))
                        }
                    }
                }
            }
        }
    }

    private fun left(id: Long) = rule.onNodeWithTag("row-$id").getUnclippedBoundsInRoot().left

    @Test
    fun `a swipe toward the start opens the row with one haptic tick, a slow short one springs back`() {
        show()
        rule.onNodeWithTag("row-1").performTouchInput { swipeLeft(startX = right - 2f, endX = right - 30f, durationMillis = 1_000) }
        rule.waitForIdle()
        assertEquals(null, revealed.openId)
        assertEquals(0.dp, left(1))

        rule.onNodeWithTag("row-1").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(1L, revealed.openId)
        assertEquals(-96.dp, left(1))
        assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate), haptics)
    }

    @Test
    fun `opening a second row closes the first`() {
        show()
        rule.onNodeWithTag("row-1").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithTag("row-2").performTouchInput { swipeLeft() }
        rule.waitForIdle()

        assertEquals(2L, revealed.openId)
        assertEquals(0.dp, left(1))
        assertEquals(2, haptics.size)
    }

    @Test
    fun `closing from outside springs the row back and rearms the tick`() {
        show()
        rule.onNodeWithTag("row-1").performTouchInput { swipeLeft() }
        rule.waitForIdle()

        revealed.close()
        rule.waitForIdle()
        assertEquals(0.dp, left(1))

        rule.onNodeWithTag("row-1").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(2, haptics.size)
    }

    @Test
    fun `right to left the swipe goes the other way and the button sits at the left end`() {
        show(direction = LayoutDirection.Rtl)
        rule.onNodeWithTag("row-1").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(null, revealed.openId)

        rule.onNodeWithTag("row-1").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals(1L, revealed.openId)
        assertEquals(96.dp, left(1))
    }

    @Test
    fun `a disabled row neither swipes nor shows Delete`() {
        show(enabled = false)
        rule.onNodeWithTag("row-1").performTouchInput { swipeLeft() }
        rule.waitForIdle()

        assertEquals(null, revealed.openId)
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).assertDoesNotExist()
        rule.onAllNodesWithText("Delete", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `Delete is also an accessibility action on the row`() {
        show()
        // On the row's own node, the one TalkBack focuses.
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and hasText("Row 2"))
            .performCustomAccessibilityActionWithLabel("Delete 2")

        assertEquals(listOf(2L), deleted)
    }

    @Test
    fun `a closed row's Delete button is hidden from accessibility, a revealed one is a button`() {
        show()
        // TalkBack and Switch Access read the merged tree: no Delete text and no Delete button.
        rule.onAllNodesWithText("Delete").assertCountEquals(0)
        rule.onAllNodes(hasText("Delete") and hasClickAction()).assertCountEquals(0)

        rule.onNodeWithTag("row-1").performTouchInput { swipeLeft() }
        rule.waitForIdle()

        rule.onAllNodes(hasText("Delete") and hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun `tapping the revealed button deletes`() {
        show()
        rule.onNodeWithTag("row-1").performTouchInput { swipeLeft() }
        rule.waitForIdle()

        rule.onNode(hasText("Delete") and hasClickAction()).performClick()

        assertEquals(listOf(1L), deleted)
    }

    @Test
    fun `a drag that reaches the reveal and comes back short ticks once and stays closed`() {
        show()
        rule.onNodeWithTag("row-1").performTouchInput {
            down(centerRight - Offset(2f, 0f))
            moveBy(Offset(-400f, 0f))
            moveBy(Offset(380f, 0f))
            up()
        }
        rule.waitForIdle()

        assertEquals(null, revealed.openId)
        assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate), haptics)
    }

    @Test
    fun `an open row that turns plain reports closed`() {
        show()
        rule.onNodeWithTag("row-1").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(1L, revealed.openId)

        enabled = false
        rule.waitForIdle()

        assertEquals(null, revealed.openId)
    }

    @Test
    fun `a close that lands while the row is still settling open leaves it closed`() {
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("row-1").performTouchInput { swipeLeft() }
        // The finger is up; the row is still animating toward open.
        rule.mainClock.advanceTimeByFrame()

        revealed.close()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        assertEquals(null, revealed.openId)
        assertEquals(0.dp, left(1))
    }

    // ========== RevealedRow ==========

    @Test
    fun `RevealedRow keeps one open id, clears only its own, and reports what close closed`() {
        var ran = 0
        val row = RevealedRow()
        assertFalse("nothing to close", row.close())

        row.set(1L, true)
        row.set(2L, true)
        assertEquals("opening another replaces it", 2L, row.openId)

        row.set(1L, false)
        assertEquals("a stale close from another row is ignored", 2L, row.openId)

        assertTrue(row.close())
        assertEquals(null, row.openId)
        assertFalse(row.close())

        row.closeOr { ran++ }
        assertEquals("closeOr acts when nothing is open", 1, ran)
        row.set(1L, true)
        row.closeOr { ran++ }
        assertEquals("closeOr only closes when a row is open", 1, ran)
        assertEquals(null, row.openId)
    }

    @Test
    fun `RevealedRow keeps a row's inline confirm until something closes it`() {
        var ran = 0
        val row = RevealedRow()
        row.set(3L, true)
        row.confirmingId = 3L
        // The swiped row reports closed when the confirm replaces it; the confirm stays.
        row.set(3L, false)
        assertEquals(3L, row.confirmingId)

        row.set(4L, true)
        assertEquals("opening another row closes the confirm", null, row.confirmingId)

        row.close()
        row.confirmingId = 4L
        assertTrue("a showing confirm counts as something to close", row.close())
        assertEquals(null, row.confirmingId)
        assertEquals(null, row.openId)

        row.confirmingId = 4L
        row.closeOr { ran++ }
        assertEquals("a tap while a confirm shows only closes it", 0, ran)
        assertEquals(null, row.confirmingId)
    }
}
