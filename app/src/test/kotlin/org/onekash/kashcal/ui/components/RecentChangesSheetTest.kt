package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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
 * Compose tests for [RecentChangesSheet]: day headers newest first, a swipe or the Dismiss
 * accessibility action dismisses a row with no snackbar, Clear all and its Undo (shown in the
 * sheet) each call back once, each row names its calendar for TalkBack, a tap opens a live row
 * only, and an empty state. Hiding the rows is the caller's; these tests stub it. Run the class
 * alone: Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h9999dp-mdpi")
class RecentChangesSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val savedLocale = Locale.getDefault()
    private val day = 24 * 3_600_000L
    private val now = 1_791_234_000_000L

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(savedLocale)

    private fun item(id: Long, title: String, detectedAt: Long = now, type: ChangeType = ChangeType.NEW, eventId: Long? = id) =
        RecentChangeItem(
            id = id, calendarId = 1, eventUid = title, instanceTs = 0, eventId = eventId, changeType = type,
            title = title, startTs = now + day, endTs = now + day + 3_600_000, isAllDay = false,
            isRecurring = false, detectedAt = detectedAt, changedFields = emptySet(), previousStartTs = null,
            previousIsAllDay = null, calendarName = "Work", calendarColor = 0
        )

    private class Calls {
        val dismissed = mutableListOf<Long>()
        var clearAll = 0
        var undo = 0
        var undoShown = 0
        val opened = mutableListOf<Long>()
    }

    private fun render(items: List<RecentChangeItem>, calls: Calls = Calls()): Calls {
        composeTestRule.setContent {
            var cleared by remember { mutableStateOf(emptyList<Long>()) }
            MaterialTheme {
                RecentChangesSheet(
                    items = items,
                    timePattern = "h:mm a",
                    clearedIds = cleared,
                    onDismissRow = { calls.dismissed += it },
                    onClearAll = { calls.clearAll++; cleared = items.map { it.id } },
                    onUndoClear = { calls.undo++; cleared = emptyList() },
                    onUndoShown = { calls.undoShown++; cleared = emptyList() },
                    onOpen = { calls.opened += it.id },
                    onDismiss = {},
                    now = now,
                )
            }
        }
        return calls
    }

    @Test
    fun `rows sit under their arrival day, newest first`() {
        render(listOf(item(1, "Dentist"), item(2, "Standup", detectedAt = now - day)))

        val today = composeTestRule.onNodeWithText("Today").fetchSemanticsNode().boundsInRoot.top
        val dentist = composeTestRule.onNodeWithText("Dentist").fetchSemanticsNode().boundsInRoot.top
        val yesterday = composeTestRule.onNodeWithText("Yesterday").fetchSemanticsNode().boundsInRoot.top
        val standup = composeTestRule.onNodeWithText("Standup").fetchSemanticsNode().boundsInRoot.top
        assertTrue(today < dentist && dentist < yesterday && yesterday < standup)
    }

    @Test
    fun `each row names its calendar for TalkBack`() {
        render(listOf(item(1, "Dentist").copy(calendarName = "Family")))

        composeTestRule.onNodeWithContentDescription("Family").assertExists()
    }

    @Test
    fun `a swipe dismisses the row with no undo`() {
        val calls = render(listOf(item(1, "Dentist")))

        // Swipe across the whole row, the way a finger crosses the dismiss threshold.
        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .performTouchInput { swipeLeft(startX = right - 1f, endX = left, durationMillis = 200) }
        composeTestRule.waitForIdle()

        assertEquals(listOf(1L), calls.dismissed)
        composeTestRule.onAllNodesWithText("Undo").assertCountEquals(0)
    }

    @Test
    fun `the Dismiss accessibility action dismisses the row`() {
        val calls = render(listOf(item(1, "Dentist")))

        val row = composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
        val action = row.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Dismiss" }
        composeTestRule.runOnIdle { action.action() }

        assertEquals(listOf(1L), calls.dismissed)
    }

    @Test
    fun `clear all and its undo each call back once, and the undo offer closes`() {
        val calls = render(listOf(item(1, "Dentist"), item(2, "Standup")))

        composeTestRule.onNodeWithText("Clear all").performClick()
        composeTestRule.onNodeWithText("Undo").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, calls.clearAll)
        assertEquals(1, calls.undo)
        composeTestRule.onAllNodesWithText("Recent changes cleared").assertCountEquals(0)
    }

    @Test
    fun `a tap opens a live row and a deleted one does nothing`() {
        val calls = render(listOf(item(1, "Dentist"), item(2, "Gone", type = ChangeType.DELETED, eventId = null)))

        composeTestRule.onNodeWithText("Dentist").performClick()
        composeTestRule.onNodeWithText("Gone").performClick()

        assertEquals(listOf(1L), calls.opened)
    }

    @Test
    fun `an empty log says so and Clear all is off`() {
        val calls = render(emptyList())

        composeTestRule.onNodeWithText("No recent changes").fetchSemanticsNode()
        composeTestRule.onNodeWithText("Clear all").performClick()

        assertEquals(0, calls.clearAll)
    }
}
