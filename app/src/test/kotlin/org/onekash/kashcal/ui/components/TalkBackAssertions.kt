package org.onekash.kashcal.ui.components

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.junit.Assert.assertEquals

/**
 * Asserts the content shows TalkBack no image. Compose gives an Image a semantics node with the
 * image role only when it has a content description, so a decorative image leaves none.
 */
internal fun ComposeContentTestRule.assertNoImageForTalkBack() {
    val images = onAllNodes(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image),
        useUnmergedTree = true,
    ).fetchSemanticsNodes()
    assertEquals("images TalkBack would announce", 0, images.size)
}
