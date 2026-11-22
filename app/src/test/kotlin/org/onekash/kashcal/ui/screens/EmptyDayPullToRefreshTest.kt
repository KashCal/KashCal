package org.onekash.kashcal.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Checks that a pull down on an empty day in the month view's day list reaches pull-to-refresh.
 * Material3 pull-to-refresh only sees drags a descendant passes up through nested scroll, and the
 * empty day has no scrolling content of its own. A `pullToRefresh` Box around [EmptyDayState]
 * stands in for the home screen's, without the day pager between them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class EmptyDayPullToRefreshTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(onRefresh: () -> Unit) {
        composeTestRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("ptr")
                        .pullToRefresh(
                            isRefreshing = false,
                            state = rememberPullToRefreshState(),
                            enabled = true,
                            onRefresh = onRefresh,
                        )
                ) {
                    EmptyDayState(epochDay = 20_400L, modifier = Modifier.testTag("emptyDay"))
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `pull down on an empty day triggers refresh`() {
        var refreshed = false
        render(onRefresh = { refreshed = true })

        composeTestRule.onNodeWithTag("ptr").performTouchInput {
            down(Offset(centerX, top + 5f))
            moveBy(Offset(0f, 200f))
            moveBy(Offset(0f, 200f))
            moveBy(Offset(0f, 200f))
            up()
        }
        composeTestRule.waitForIdle()

        assertTrue("A downward drag on an empty day must reach pull-to-refresh", refreshed)
    }

    @Test
    fun `the empty day does not expose a scrollable region to accessibility`() {
        // The drag handler has no content to scroll, so TalkBack must not announce one. This
        // guards against swapping it for verticalScroll, which would set the axis range.
        render(onRefresh = {})

        val config = composeTestRule
            .onNodeWithTag("emptyDay", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config

        assertFalse(config.contains(SemanticsProperties.VerticalScrollAxisRange))
    }
}
