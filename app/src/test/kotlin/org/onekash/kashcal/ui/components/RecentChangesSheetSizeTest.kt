package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.changes.RecentChangeItem
import org.onekash.kashcal.sync.model.ChangeType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Compose tests for how [RecentChangesSheet] sits on a phone-sized screen: full height whatever
 * its row count, the same size when its rows arrive after it opened, a list that scrolls, drags
 * that scroll or close it, and Clear all's Undo as a row under the title. Read from where
 * the title sits in the window and from the sheet's measured size. Run the class alone:
 * Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class RecentChangesSheetSizeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val savedLocale = Locale.getDefault()
    private val day = 24 * 3_600_000L
    private val now = 1_791_234_000_000L

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(savedLocale)

    private fun item(id: Long) = RecentChangeItem(
        id = id, calendarId = 1, eventUid = "u$id", instanceTs = 0, eventId = id, changeType = ChangeType.NEW,
        title = "Row $id", startTs = now + day, endTs = now + day + 3_600_000, isAllDay = false,
        isRecurring = false, detectedAt = now, changedFields = emptySet(), previousStartTs = null,
        previousIsAllDay = null, calendarName = "Work", calendarColor = 0
    )

    private class Calls {
        var undo = 0
        var undoShown = 0
        var dismissed = 0
    }

    /** Renders the sheet; with [lateMs] set, it opens empty and gets its rows that much later. */
    private fun render(count: Int, lateMs: Long? = null, calls: Calls = Calls()): Calls {
        val items = (1L..count).map(::item)
        composeTestRule.setContent {
            var cleared by remember { mutableStateOf(emptyList<Long>()) }
            var shown by remember { mutableStateOf(if (lateMs == null) items else emptyList()) }
            if (lateMs != null) LaunchedEffect(Unit) { delay(lateMs); shown = items }
            MaterialTheme {
                RecentChangesSheet(
                    items = if (cleared.isEmpty()) shown else emptyList(),
                    timePattern = "h:mm a",
                    clearedIds = cleared,
                    onDismissRow = {},
                    onClearAll = { cleared = items.map { it.id } },
                    onUndoClear = { calls.undo++; cleared = emptyList() },
                    onUndoShown = { calls.undoShown++; cleared = emptyList() },
                    onOpen = {},
                    onDismiss = { calls.dismissed++ },
                    now = now,
                )
            }
        }
        composeTestRule.waitForIdle()
        return calls
    }

    private fun top(text: String): Float =
        composeTestRule.onNodeWithText(text).fetchSemanticsNode().boundsInWindow.top

    private fun titleTop(): Float = composeTestRule.topFraction(composeTestRule.onNodeWithText("Recent changes"))

    /**
     * Returns the measured size of every node with a pane title; Material3 sets one on the sheet.
     * Sizes, not window bounds: bounds are clipped while the sheet slides up.
     */
    private fun sheetSizes(): List<IntSize> =
        composeTestRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))
            .fetchSemanticsNodes().map { it.size }

    // ---- full height ----

    @Test
    fun `an empty sheet opens at full height`() {
        render(count = 0)

        assertTrue("title at ${titleTop()}, expected at the top", titleTop() < 0.2f)
    }

    @Test
    fun `a sheet with one row opens at full height`() {
        render(count = 1)

        assertTrue("title at ${titleTop()}, expected at the top", titleTop() < 0.2f)
    }

    @Test
    fun `a sheet with a few rows opens at full height`() {
        render(count = 4)

        assertTrue("title at ${titleTop()}, expected at the top", titleTop() < 0.2f)
        composeTestRule.onNodeWithText("Row 4").assertIsDisplayed()
    }

    @Test
    fun `a sheet with more rows than fit opens full and its list scrolls`() {
        render(count = 40)

        assertTrue("title at ${titleTop()}, expected at the top", titleTop() < 0.2f)
        composeTestRule.onNodeWithText("Row 40").assertIsNotDisplayed()

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Row 40"))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Row 40").assertIsDisplayed()
        assertTrue("the sheet stays full, title at ${titleTop()}", titleTop() < 0.2f)
    }

    // A content-sized sheet changes size when its rows arrive late; this pins the full-height
    // sheet's size.
    @Test
    fun `rows that arrive during the opening animation leave the sheet's size alone`() {
        composeTestRule.mainClock.autoAdvance = false
        render(count = 40, lateMs = 100)
        composeTestRule.mainClock.advanceTimeBy(50)
        val before = sheetSizes()
        assertTrue("no sheet found", before.isNotEmpty())

        composeTestRule.mainClock.advanceTimeBy(3_000)
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()

        assertEquals(before, sheetSizes())
        assertTrue("title at ${titleTop()}, expected at the top", titleTop() < 0.2f)
    }

    @Test
    fun `rows that arrive after the sheet settled leave its size alone`() {
        composeTestRule.mainClock.autoAdvance = false
        render(count = 40, lateMs = 1_500)
        composeTestRule.mainClock.advanceTimeBy(1_000)
        val before = sheetSizes()
        assertTrue("no sheet found", before.isNotEmpty())

        composeTestRule.mainClock.advanceTimeBy(3_000)
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()

        assertEquals(before, sheetSizes())
        assertTrue("title at ${titleTop()}, expected at the top", titleTop() < 0.2f)
    }

    // ---- drags ----

    @Test
    fun `a drag up on a full sheet's list scrolls it`() {
        render(count = 40)

        composeTestRule.onNode(hasScrollAction())
            .performTouchInput { swipeUp(startY = bottom - 10f, endY = top + 10f, durationMillis = 300) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Row 1").assertIsNotDisplayed()
        assertTrue("the sheet stays full, title at ${titleTop()}", titleTop() < 0.2f)
    }

    @Test
    fun `a drag up on a short list keeps the sheet open and full`() {
        val calls = render(count = 4)

        composeTestRule.onNode(hasScrollAction())
            .performTouchInput { swipeUp(startY = bottom - 10f, endY = top + 10f, durationMillis = 300) }
        composeTestRule.waitForIdle()

        assertEquals(0, calls.dismissed)
        assertTrue("title at ${titleTop()}, expected at the top", titleTop() < 0.2f)
    }

    @Test
    fun `a drag down from the list's top closes the sheet`() {
        val calls = render(count = 4)

        composeTestRule.onNode(hasScrollAction())
            .performTouchInput { swipeDown(startY = top + 10f, endY = bottom - 10f, durationMillis = 200) }
        composeTestRule.waitForIdle()

        assertEquals(1, calls.dismissed)
    }

    // ---- Clear all's Undo ----

    @Test
    fun `clear all keeps the sheet full with the undo row under the title`() {
        render(count = 40)

        composeTestRule.onNodeWithText("Clear all").performClick()
        composeTestRule.waitForIdle()

        assertTrue("the sheet stays full, title at ${titleTop()}", titleTop() < 0.2f)
        val titleBottom = composeTestRule.onNodeWithText("Recent changes").fetchSemanticsNode().boundsInWindow.bottom
        val undoRowTop = top("Recent changes cleared")
        assertTrue("undo row at $undoRowTop, title ends at $titleBottom", undoRowTop >= titleBottom)
        assertTrue("undo row above the empty text", undoRowTop < top("No recent changes"))
        composeTestRule.onNodeWithText("Undo").assertIsDisplayed()
    }

    @Test
    fun `the undo row is announced as it appears`() {
        render(count = 3)
        val politeRegion = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        composeTestRule.onNode(politeRegion).assertDoesNotExist()

        composeTestRule.onNodeWithText("Clear all").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNode(politeRegion and hasAnyDescendant(hasText("Recent changes cleared"))).assertExists()
    }

    @Test
    fun `undo restores the rows and the sheet stays full`() {
        val calls = render(count = 40)
        composeTestRule.onNodeWithText("Clear all").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Undo").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, calls.undo)
        composeTestRule.onNodeWithText("Row 1").assertIsDisplayed()
        assertTrue("title at ${titleTop()}, expected at the top", titleTop() < 0.2f)
    }

    @Test
    fun `the undo row times out once and goes`() {
        val calls = render(count = 3)
        composeTestRule.onNodeWithText("Clear all").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.mainClock.advanceTimeBy(4_500)
        composeTestRule.waitForIdle()

        assertEquals(1, calls.undoShown)
        assertEquals(0, calls.undo)
        assertEquals(0, composeTestRule.onAllNodesWithText("Recent changes cleared").fetchSemanticsNodes().size)
    }
}
