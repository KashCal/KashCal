package org.onekash.kashcal.ui.components

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** The height a bottom sheet test measures against: the tallest root, the sheet's own window. */
internal fun ComposeContentTestRule.sheetWindowHeight(): Float =
    onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.size.height }.toFloat()

/** Where [node] starts, as a fraction of `sheetWindowHeight` (0 = top, 1 = bottom). */
internal fun ComposeContentTestRule.topFraction(node: SemanticsNodeInteraction): Float =
    node.fetchSemanticsNode().boundsInWindow.top / sheetWindowHeight()
