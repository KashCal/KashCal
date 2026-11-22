package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.LocalCalendarSummary
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Deleting an offline calendar from its row: the swipe's Delete (and its TalkBack action) turns
 * the row into an inline confirm, the "Also delete" box gates Delete when the calendar has
 * events, Cancel and other taps close it, a running delete keeps the confirm whatever else
 * happens, and a finished delete removes the row and is announced to TalkBack, with no snackbar.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class OfflineCalendarsDeleteTest {

    @get:Rule
    val rule = createComposeRule()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    private fun calendar(id: Long, name: String, builtIn: Boolean = false) = Calendar(
        id = id,
        accountId = 1L,
        caldavUrl = if (builtIn) LocalCalendarInitializer.LOCAL_CALENDAR_URL else "local://calendar/test-$id",
        displayName = if (builtIn) LocalCalendarInitializer.LOCAL_CALENDAR_DISPLAY_NAME else name,
        color = 0xFF9E9E9E.toInt(),
    )

    private val builtIn = LocalCalendarSummary(calendar(1, "Local", builtIn = true), 24)
    private val trips = LocalCalendarSummary(calendar(3, "Trips"), 0)
    private val work = LocalCalendarSummary(calendar(2, "Work"), 12)

    private var list by mutableStateOf(listOf(builtIn, trips, work))
    private val deleted = mutableListOf<Long>()

    private val confirmText = "Delete \"Work\" and its 12 events forever? Can't be undone."

    private fun show(onDelete: suspend (Long) -> Result<Int> = { id ->
        deleted += id
        list = list.filterNot { it.calendar.id == id }
        Result.success(12)
    }) {
        rule.setContent {
            MaterialTheme {
                OfflineCalendarsScreen(
                    calendars = list,
                    defaultCalendarId = null,
                    onNavigateBack = {},
                    onCreateCalendar = { _, _ -> Result.success(99L) },
                    onRenameCalendar = { _, _ -> Result.success(Unit) },
                    onRecolorCalendar = { _, _ -> Result.success(Unit) },
                    onExportCalendar = {},
                    onDeleteCalendar = onDelete,
                )
            }
        }
    }

    private fun askDelete(name: String) {
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and hasText(name))
            .performCustomAccessibilityActionWithLabel("Delete $name")
    }

    private fun deleteButton() = rule.onNode(hasText("Delete") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))

    private val alsoDeleteBox = hasText("Also delete 12 events")

    @Test
    fun `a calendar you added offers Delete to TalkBack and the built-in doesn't`() {
        show()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and hasText("Work")).assertExists()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and hasText("Local")).assertDoesNotExist()
    }

    @Test
    fun `Delete turns the row into the confirm, and Delete stays off until the box is ticked`() {
        show()
        askDelete("Work")
        rule.onNodeWithText(confirmText).assertIsDisplayed()
        deleteButton().assertIsNotEnabled()

        rule.onNode(alsoDeleteBox).performClick()
        deleteButton().assertIsEnabled()
    }

    @Test
    fun `the box is one checkbox TalkBack can toggle`() {
        show()
        askDelete("Work")
        rule.onNode(alsoDeleteBox)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .performClick()
        rule.onNode(alsoDeleteBox).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    }

    @Test
    fun `an empty calendar gets the confirm without the box`() {
        show()
        askDelete("Trips")
        rule.onNodeWithText("Delete \"Trips\" forever? It has no events.").assertIsDisplayed()
        rule.onNode(hasText("Also delete", substring = true)).assertDoesNotExist()
        deleteButton().assertIsEnabled()
    }

    @Test
    fun `Cancel puts the row back and the box starts unticked next time`() {
        show()
        askDelete("Work")
        rule.onNode(alsoDeleteBox).performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText(confirmText).assertDoesNotExist()
        rule.onNodeWithText("Work").assertIsDisplayed()

        askDelete("Work")
        rule.onNode(alsoDeleteBox).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        deleteButton().assertIsNotEnabled()
    }

    @Test
    fun `a tap on another row while the confirm shows only closes it`() {
        show()
        askDelete("Work")
        rule.onNodeWithText("Trips").performClick()
        rule.onNodeWithText(confirmText).assertDoesNotExist()
        rule.onNodeWithText("Rename").assertDoesNotExist()
    }

    @Test
    fun `a finished delete removes the row and is announced, with no snackbar`() {
        show()
        askDelete("Work")
        rule.onNode(alsoDeleteBox).performClick()
        deleteButton().performClick()
        rule.waitForIdle()

        assertEquals(listOf(2L), deleted)
        rule.onNodeWithText("Work").assertDoesNotExist()
        rule.onNode(hasContentDescription("Work deleted"))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onNode(hasText("Work", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `a failed delete brings the row back and announces nothing`() {
        show(onDelete = { Result.failure(IllegalStateException("disk full")) })
        askDelete("Trips")
        deleteButton().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Trips").assertIsDisplayed()
        rule.onNode(hasContentDescription("deleted", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `a running delete keeps its confirm through a scroll and another tap, and is sent once`() {
        val gate = CompletableDeferred<Result<Int>>()
        var calls = 0
        show(onDelete = { calls++; gate.await() })
        askDelete("Trips")
        deleteButton().performClick()
        rule.waitForIdle()

        rule.onNode(hasScrollAction()).performTouchInput { swipeUp(startY = centerY, endY = centerY - 120f) }
        rule.onNodeWithText("Work").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Delete \"Trips\" forever? It has no events.").assertIsDisplayed()
        deleteButton().assertIsNotEnabled()
        deleteButton().performClick()
        assertEquals(1, calls)

        gate.complete(Result.success(0))
    }

    @Test
    @Config(qualifiers = "w360dp-h400dp")
    fun `scrolling keeps an open confirm, so its Delete stays reachable`() {
        list = listOf(builtIn) + (10L..21L).map { LocalCalendarSummary(calendar(it, "Calendar $it"), 0) } + listOf(trips, work)
        show()
        // A row near the bottom: its confirm is taller than the row and runs past the screen.
        askDelete("Calendar 12")
        rule.onNode(hasScrollAction()).performTouchInput { swipeUp(startY = centerY, endY = centerY - 80f) }
        rule.waitForIdle()
        rule.onNode(hasText("Delete \"Calendar 12\" forever? It has no events.")).assertExists()
        deleteButton().assertExists()
    }

    @Test
    fun `two deletes in flight keep their own confirms, and a failure brings back only its row`() {
        val gates = mapOf(3L to CompletableDeferred<Result<Int>>(), 2L to CompletableDeferred<Result<Int>>())
        val calls = mutableListOf<Long>()
        show(onDelete = { id -> calls += id; gates.getValue(id).await() })
        askDelete("Trips")
        deleteButton().performClick()
        rule.waitForIdle()
        askDelete("Work")
        rule.onNode(alsoDeleteBox).performClick()
        rule.onAllNodes(hasText("Delete") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))[1].performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Delete \"Trips\" forever? It has no events.").assertExists()
        rule.onNodeWithText(confirmText).assertExists()
        assertEquals(listOf(3L, 2L), calls)

        gates.getValue(3L).complete(Result.failure(IllegalStateException("disk full")))
        rule.waitForIdle()
        rule.onNodeWithText("Trips").assertIsDisplayed()
        rule.onNodeWithText(confirmText).assertExists()
        gates.getValue(2L).complete(Result.success(12))
    }

    @Test
    fun `while a delete runs, one tap on another row opens it`() {
        val gate = CompletableDeferred<Result<Int>>()
        show(onDelete = { gate.await() })
        askDelete("Trips")
        deleteButton().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Work").performClick()
        rule.onNodeWithText("Rename").assertIsDisplayed()
        gate.complete(Result.success(0))
    }

    @Test
    fun `the announcement clears a while after it is made`() {
        show()
        askDelete("Trips")
        deleteButton().performClick()
        rule.waitForIdle()
        rule.onNode(hasContentDescription("Trips deleted")).assertExists()
        rule.mainClock.advanceTimeBy(5_000)
        rule.waitForIdle()
        rule.onNode(hasContentDescription("Trips deleted")).assertDoesNotExist()
    }
}
