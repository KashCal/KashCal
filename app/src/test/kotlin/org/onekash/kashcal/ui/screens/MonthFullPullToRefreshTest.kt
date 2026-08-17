package org.onekash.kashcal.ui.screens

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.model.DisplayEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar as JavaCalendar

/**
 * Regression guard for the full-height month view (MONTH_FULL) missing
 * pull-to-refresh.
 *
 * Material3 pull-to-refresh is nested-scroll driven: it only sees a drag when a
 * descendant propagates vertical scroll deltas up the nested-scroll chain. The
 * full-height month grid is fit-to-screen (weighted rows) with no scrollable
 * child, so the enclosing pull-to-refresh Box never received a gesture and the
 * pull silently did nothing. The fix donates the vertical gesture to the parent
 * with a zero-consuming `scrollable` modifier on the month page.
 *
 * The wrapper here mirrors production: a `pullToRefresh` Box wrapping a
 * `HorizontalPager` (month paging) whose page hosts the donor Column + the real
 * [FullHeightMonthGrid]. Keeping the pager in the replica is deliberate — the
 * key interaction to protect is that the vertical donor coexists with the
 * pager's horizontal swipe without either stealing the other's gesture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class MonthFullPullToRefreshTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Composable
    private fun MonthFullUnderTest(
        withDonor: Boolean,
        pagerState: PagerState,
        onRefresh: () -> Unit,
    ) {
        val pullToRefreshState = rememberPullToRefreshState()
        // Called unconditionally to satisfy the composition rules; only wired in
        // when `withDonor` is true.
        val scrollDonor = rememberScrollableState { 0f }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("ptr")
                .pullToRefresh(
                    isRefreshing = false,
                    state = pullToRefreshState,
                    enabled = true,
                    onRefresh = onRefresh,
                )
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = true,
            ) { page ->
                val columnModifier = if (withDonor) {
                    Modifier
                        .fillMaxSize()
                        .testTag("monthPage")
                        .scrollable(orientation = Orientation.Vertical, state = scrollDonor)
                } else {
                    Modifier.fillMaxSize().testTag("monthPage")
                }
                Column(modifier = columnModifier, verticalArrangement = Arrangement.Top) {
                    FullHeightMonthGrid(
                        year = 2026,
                        month = page,
                        selectedDate = 0L,
                        monthEventsMap = persistentMapOf<Int, ImmutableList<DisplayEvent>>(),
                        onDateSelected = {},
                        firstDayOfWeekPref = JavaCalendar.MONDAY,
                        showWeekNumbers = false,
                        showEventEmojis = false,
                        refreshKey = 0,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    private fun renderWith(withDonor: Boolean, onRefresh: () -> Unit = {}): PagerState {
        lateinit var pager: PagerState
        composeTestRule.setContent {
            MaterialTheme {
                pager = rememberPagerState(initialPage = 1) { 3 }
                MonthFullUnderTest(withDonor = withDonor, pagerState = pager, onRefresh = onRefresh)
            }
        }
        composeTestRule.waitForIdle()
        return pager
    }

    private fun swipeDownOnGrid() {
        composeTestRule.onNodeWithTag("ptr").performTouchInput {
            down(Offset(centerX, top + 5f))
            moveBy(Offset(0f, 200f))
            moveBy(Offset(0f, 200f))
            moveBy(Offset(0f, 200f))
            up()
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `pull down on the full-height month grid triggers refresh`() {
        var refreshed = false
        renderWith(withDonor = true, onRefresh = { refreshed = true })

        swipeDownOnGrid()

        assertTrue(
            "A downward drag on the fit-to-screen month grid must reach pull-to-refresh",
            refreshed,
        )
    }

    @Test
    fun `without the scroll donor the fit-to-screen grid cannot trigger refresh`() {
        // Proves the donor is load-bearing: a plain (non-scrollable) Column never
        // propagates vertical deltas, so this is exactly the broken behavior the
        // fix addresses. If this ever starts passing, the grid gained a scrollable
        // of its own and the donor may be reconsidered.
        var refreshed = false
        renderWith(withDonor = false, onRefresh = { refreshed = true })

        swipeDownOnGrid()

        assertFalse(
            "A non-scrollable Column should not feed the nested-scroll pull-to-refresh",
            refreshed,
        )
    }

    @Test
    fun `horizontal swipe still pages between months with the donor present`() {
        // The donor is orientation-locked to vertical; it must not steal the
        // pager's horizontal swipe.
        val pager = renderWith(withDonor = true)
        assertTrue("precondition: starts on page 1", pager.currentPage == 1)

        composeTestRule.onNodeWithTag("ptr").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 3_000) { pager.currentPage == 2 }

        assertTrue(
            "A horizontal swipe must still advance the month pager (donor is vertical-only)",
            pager.currentPage == 2,
        )
    }

    @Test
    fun `the scroll donor does not expose a scrollable region to accessibility`() {
        // A bare `scrollable` registers ScrollBy actions but no ScrollAxisRange;
        // TalkBack's scrollable-region announcement and ACTION_SCROLL_FORWARD/
        // BACKWARD are driven by the axis range, so the fit-to-screen page must
        // not advertise itself as a scrollable region.
        renderWith(withDonor = true)

        val config = composeTestRule
            .onNodeWithTag("monthPage", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config

        assertFalse(
            "donor must not advertise a vertical scroll axis range to a11y",
            config.contains(SemanticsProperties.VerticalScrollAxisRange),
        )
    }
}
