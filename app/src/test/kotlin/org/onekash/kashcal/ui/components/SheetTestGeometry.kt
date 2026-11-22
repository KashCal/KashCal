package org.onekash.kashcal.ui.components

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** The height a bottom sheet test measures against: the tallest root, the sheet's own window. */
internal fun ComposeContentTestRule.sheetWindowHeight(): Float =
    onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.size.height }.toFloat()

/** Where [node] starts, as a fraction of `sheetWindowHeight` (0 = top, 1 = bottom). */
internal fun ComposeContentTestRule.topFraction(node: SemanticsNodeInteraction): Float =
    node.fetchSemanticsNode().boundsInWindow.top / sheetWindowHeight()

/**
 * Returns the measured size of every node with a pane title; Material3 sets one on the sheet.
 * Sizes, not window bounds: bounds are clipped while the sheet slides up.
 */
internal fun ComposeContentTestRule.sheetSizes(): List<IntSize> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).fetchSemanticsNodes().map { it.size }

/** The sheet's own node: the tallest pane-title node, since a sheet may set a pane title inside. */
private fun ComposeContentTestRule.sheetNode(): SemanticsNode =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).fetchSemanticsNodes()
        .maxBy { it.size.height }

/** Where the sheet's top edge sits, as a fraction of `sheetWindowHeight` (0 = top). */
internal fun ComposeContentTestRule.sheetTopFraction(): Float =
    sheetNode().boundsInWindow.top / sheetWindowHeight()

/** The sheet's measured height over `sheetWindowHeight`. */
internal fun ComposeContentTestRule.sheetHeightFraction(): Float =
    sheetNode().size.height / sheetWindowHeight()

/**
 * Asserts the sheet is fully open at its own height: all of its measured height shows and its
 * bottom meets the window's. A sheet stopped at half height shows only part of itself.
 */
internal fun ComposeContentTestRule.assertSheetOpenAtItsHeight() {
    val sheet = sheetNode()
    assertEquals("sheet shows only part of its height", sheet.size.height.toFloat(), sheet.boundsInWindow.height, 1f)
    assertEquals("sheet bottom off the window's", sheetWindowHeight(), sheet.boundsInWindow.bottom, 1f)
}

/**
 * Asserts all of this node is on screen: laid out at a real height and not cut by the window's
 * edge. `assertIsDisplayed` passes for a button only partly showing.
 */
internal fun SemanticsNodeInteraction.assertFullyOnScreen() {
    assertIsDisplayed()
    val node = fetchSemanticsNode()
    assertTrue("laid out at height ${node.size.height}", node.size.height > 0)
    assertEquals("cut off: ${node.boundsInWindow}", node.size.height.toFloat(), node.boundsInWindow.height)
}

/** How far this vertical scrollable has scrolled, in pixels. */
internal fun SemanticsNodeInteraction.scrollOffset(): Float =
    fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

/** Drags up across this node from near its bottom to near its top. */
internal fun SemanticsNodeInteraction.dragUp() {
    performTouchInput { swipeUp(startY = bottom - 10f, endY = top + 10f, durationMillis = 300) }
}

/** Drags down across this node from near its top to near its bottom. */
internal fun SemanticsNodeInteraction.dragDown() {
    performTouchInput { swipeDown(startY = top + 10f, endY = bottom - 10f, durationMillis = 200) }
}
