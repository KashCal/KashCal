package org.onekash.kashcal.ui.screens

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
import org.onekash.kashcal.domain.monthfull.MAX_MONTH_FULL_ZOOM
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.pinch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
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
 * Checks the gestures on the full-height month page ([MonthFullPage]): pull-to-refresh, paging
 * between months, and the pinch that zooms the day cells.
 *
 * Material3 pull-to-refresh is nested-scroll driven: it sees a drag only when a descendant passes
 * vertical scroll deltas up the chain. The full-height grid fits the screen (weighted rows) with no
 * scrollable child, so without help the pull silently does nothing; the page's zero-consuming
 * `scrollable` hands the vertical gesture to pull-to-refresh. The wrapper is a `pullToRefresh` Box
 * around a `HorizontalPager` of real pages, as the home screen has it; the control case swaps the
 * page for a bare grid with no donor. The tests also check the donor exposes no scroll axis range
 * to accessibility, and that a pinch zooms without paging or refreshing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class MonthFullPullToRefreshTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Zoom values the page reported, in order. */
    private val zooms = mutableListOf<Float>()

    @Composable
    private fun MonthFullUnderTest(
        withDonor: Boolean,
        pagerState: PagerState,
        onRefresh: () -> Unit,
    ) {
        val pullToRefreshState = rememberPullToRefreshState()
        var zoom by remember { mutableFloatStateOf(1f) }
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
                if (withDonor) {
                    MonthFullPage(
                        year = 2026,
                        month = page,
                        selectedDate = 0L,
                        monthEventsMap = persistentMapOf<Int, ImmutableList<DisplayEvent>>(),
                        onDayTap = { _, _ -> },
                        firstDayOfWeek = JavaCalendar.MONDAY,
                        showWeekNumbers = false,
                        showEventEmojis = false,
                        nowMs = 0L,
                        todayDayCode = 0,
                        zoom = zoom,
                        onZoomChange = { zooms += it; zoom = it },
                        modifier = Modifier.testTag("monthPage"),
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize().testTag("monthPage"), verticalArrangement = Arrangement.Top) {
                        FullHeightMonthGrid(
                            year = 2026,
                            month = page,
                            selectedDate = 0L,
                            monthEventsMap = persistentMapOf<Int, ImmutableList<DisplayEvent>>(),
                            onDayTap = { _, _ -> },
                            firstDayOfWeekPref = JavaCalendar.MONDAY,
                            showWeekNumbers = false,
                            showEventEmojis = false,
                            nowMs = 0L,
                            todayDayCode = 0,
                            modifier = Modifier.weight(1f),
                        )
                    }
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
        // Shows the donor is load-bearing: a plain, non-scrollable Column never passes
        // vertical deltas up. If this starts failing, the grid gained a scrollable of its own
        // and the donor may be reconsidered.
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
        // The donor is vertical-only; it must not steal the pager's horizontal swipe.
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
        // A bare `scrollable` registers ScrollBy actions but no ScrollAxisRange. TalkBack's
        // scrollable-region announcement and ACTION_SCROLL_FORWARD and BACKWARD are driven by
        // the axis range, so the fit-to-screen page must not advertise itself as scrollable.
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

    // -------- Pinch --------

    private fun pinchOut(atY: Float? = null, from: Float = 40f, to: Float = 50f, durationMillis: Long = 400) {
        composeTestRule.onNodeWithTag("ptr").performTouchInput {
            val y = atY ?: centerY
            pinch(
                Offset(centerX - from, y), Offset(centerX - to, y),
                Offset(centerX + from, y), Offset(centerX + to, y),
                durationMillis = durationMillis,
            )
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `a pinch zooms the cells and a second pinch carries on from there`() {
        val pager = renderWith(withDonor = true)
        pinchOut()
        val first = zooms.last()
        assertTrue("first pinch zoomed in, got $first", first > 1f)
        assertTrue("first pinch stayed under the limit, got $first", first < MAX_MONTH_FULL_ZOOM)
        pinchOut(to = 48f)
        assertTrue("second pinch continued, got ${zooms.last()} after $first", zooms.last() > first)
        assertEquals("a pinch doesn't page", 1, pager.currentPage)
    }

    @Test
    fun `a slow pinch keeps following the fingers after the slop`() {
        renderWith(withDonor = true)
        pinchOut(from = 150f, to = 165f, durationMillis = 3_000)
        assertTrue("slow pinch reached ${zooms.lastOrNull()}", (zooms.lastOrNull() ?: 1f) > 1.09f)
    }

    @Test
    fun `a pinch over the weekday header zooms too`() {
        renderWith(withDonor = true)
        val header = composeTestRule.onNodeWithTag("monthPage").fetchSemanticsNode().boundsInRoot.top + 6f
        pinchOut(atY = header)
        assertTrue("header pinch zoomed, got $zooms", (zooms.lastOrNull() ?: 1f) > 1f)
    }

    @Test
    fun `a vertical pinch in the side margin zooms without refreshing or paging`() {
        // Fingers spreading along y give pull-to-refresh a vertical drag to see; only the pinch
        // consuming it keeps the refresh away. x sits in the grid's side padding.
        var refreshed = false
        val pager = renderWith(withDonor = true, onRefresh = { refreshed = true })
        composeTestRule.onNodeWithTag("ptr").performTouchInput {
            val x = left + 4f
            pinch(
                Offset(x, centerY - 40f), Offset(x, centerY - 160f),
                Offset(x, centerY + 40f), Offset(x, centerY + 160f),
                durationMillis = 400,
            )
        }
        composeTestRule.waitForIdle()
        assertTrue("it zoomed, got $zooms", (zooms.lastOrNull() ?: 1f) > 1f)
        assertFalse("a pinch must not refresh", refreshed)
        assertEquals("a pinch must not page", 1, pager.currentPage)
    }

    @Test
    fun `a pinch neither pages nor refreshes`() {
        var refreshed = false
        val pager = renderWith(withDonor = true, onRefresh = { refreshed = true })
        pinchOut(from = 40f, to = 120f)
        assertFalse("a pinch must not refresh", refreshed)
        assertEquals("a pinch must not page", 1, pager.currentPage)
        assertTrue("it zoomed", zooms.isNotEmpty())
    }
}
