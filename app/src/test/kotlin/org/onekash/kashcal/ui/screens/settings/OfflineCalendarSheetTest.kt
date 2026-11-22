package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.initializer.LocalCalendarException
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.initializer.LocalCalendarRefusal
import org.onekash.kashcal.domain.model.LocalCalendarSummary
import org.onekash.kashcal.ui.components.backInLatestDialog
import org.onekash.kashcal.ui.components.dialogCount
import org.onekash.kashcal.ui.components.enterTouchModeInShownDialogs
import org.onekash.kashcal.ui.components.pickers.swatchTestTag
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * A calendar's sheet, where changes apply at once: Rename edits the name in place (save, cancel,
 * keyboard Done, blank and duplicate names, a failure, a second tap while saving), Color opens
 * the palette, Export is off for an empty calendar and closes the sheet before exporting, and the
 * built-in calendar's sheet has its info button and no Rename.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class OfflineCalendarSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val dodgerBlue = 0xFF1E90FF.toInt()
    private val builtInInfo = "The built-in calendar can't be renamed or deleted. New events go here when no calendar is chosen."

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    private fun summary(name: String, events: Int = 3, builtIn: Boolean = false) = LocalCalendarSummary(
        Calendar(
            id = if (builtIn) 1L else 2L,
            accountId = 1L,
            caldavUrl = if (builtIn) LocalCalendarInitializer.LOCAL_CALENDAR_URL else "local://calendar/test",
            displayName = if (builtIn) LocalCalendarInitializer.LOCAL_CALENDAR_DISPLAY_NAME else name,
            color = if (builtIn) 0xFF9E9E9E.toInt() else dodgerBlue,
        ),
        events,
    )

    private val events = mutableListOf<String>()
    private var renamedTo: String? = null

    private fun render(
        initial: LocalCalendarSummary = summary("Work"),
        onRename: suspend (String) -> Result<Unit> = { renamedTo = it; Result.success(Unit) },
    ) {
        composeTestRule.setContent {
            var shown by remember { mutableStateOf(initial) }
            MaterialTheme {
                OfflineCalendarSheet(
                    summary = shown,
                    onRename = { name ->
                        onRename(name).onSuccess {
                            shown = shown.copy(calendar = shown.calendar.copy(displayName = name))
                        }
                    },
                    onRecolor = { events += "recolor $it" },
                    onExport = { events += "export" },
                    onDismiss = { events += "dismiss" },
                )
            }
        }
    }

    private fun startRename() = composeTestRule.onNodeWithText("Rename").performClick()

    private fun nameField() = composeTestRule.onNode(hasSetTextAction())

    @Test
    fun `a calendar's sheet shows its name, Rename, Color and Export, with no Save button`() {
        render()
        composeTestRule.onNodeWithText("Rename").assertIsDisplayed()
        composeTestRule.onNodeWithText("Color").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dodger Blue").assertIsDisplayed()
        composeTestRule.onNodeWithText("Export calendar").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun `Rename edits in place and save keeps the new name`() {
        render()
        startRename()
        nameField().performTextClearance()
        nameField().performTextInput("  Office ")
        composeTestRule.onNodeWithContentDescription("Save name").performClick()
        composeTestRule.waitForIdle()

        assertEquals("Office", renamedTo)
        composeTestRule.onNode(hasSetTextAction()).assertDoesNotExist()
        composeTestRule.onNodeWithText("Rename").assertIsDisplayed()
        assertTrue("the sheet shows the new name", composeTestRule.onAllNodesWithText("Office").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `keyboard Done saves the name`() {
        render()
        startRename()
        nameField().performTextClearance()
        nameField().performTextInput("Office")
        nameField().performImeAction()
        composeTestRule.waitForIdle()
        assertEquals("Office", renamedTo)
    }

    @Test
    fun `cancel leaves the name as it was`() {
        render()
        startRename()
        nameField().performTextClearance()
        nameField().performTextInput("Office")
        composeTestRule.onNodeWithContentDescription("Cancel rename").performClick()
        assertNull(renamedTo)
        composeTestRule.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun `save is off for a blank name`() {
        render()
        startRename()
        nameField().performTextClearance()
        nameField().performTextInput("   ")
        composeTestRule.onNodeWithContentDescription("Save name").assertIsNotEnabled()
    }

    @Test
    fun `a duplicate name shows inline and the field stays open`() {
        render(onRename = { Result.failure(LocalCalendarException(LocalCalendarRefusal.DUPLICATE_NAME)) })
        startRename()
        nameField().performTextClearance()
        nameField().performTextInput("Trips")
        composeTestRule.onNodeWithContentDescription("Save name").performClick()
        composeTestRule.onNodeWithText("An offline calendar named \"Trips\" already exists").assertIsDisplayed()
        nameField().assertExists()
    }

    @Test
    fun `a name too similar to another shows which calendar`() {
        render(onRename = {
            Result.failure(LocalCalendarException(LocalCalendarRefusal.SIMILAR_NAME, conflictingName = "Home"))
        })
        startRename()
        nameField().performTextClearance()
        nameField().performTextInput("Home!")
        composeTestRule.onNodeWithContentDescription("Save name").performClick()
        composeTestRule.onNodeWithText("Too similar to \"Home\". Choose a different name.").assertIsDisplayed()
        nameField().assertExists()
    }

    @Test
    fun `any other failure shows the general error`() {
        render(onRename = { Result.failure(IllegalStateException("disk full")) })
        startRename()
        nameField().performTextInput("x")
        composeTestRule.onNodeWithContentDescription("Save name").performClick()
        composeTestRule.onNodeWithText("Something went wrong. Please try again.").assertIsDisplayed()
    }

    @Test
    fun `a second tap while the rename runs sends nothing`() {
        val gate = CompletableDeferred<Result<Unit>>()
        var calls = 0
        render(onRename = { calls++; gate.await() })
        startRename()
        nameField().performTextInput("x")
        composeTestRule.onNodeWithContentDescription("Save name").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Save name").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Save name").performClick()
        assertEquals(1, calls)
        gate.complete(Result.failure(IllegalStateException("x")))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Save name").assertIsEnabled()
    }

    @Test
    fun `Color opens the palette`() {
        render()
        composeTestRule.onNodeWithText("Color").performClick()
        composeTestRule.onNodeWithText("More colors").assertIsDisplayed()
    }

    @Test
    fun `only a different color is applied`() {
        assertEquals(dodgerBlue, recolorTarget(picked = dodgerBlue, current = 0xFFFF6347.toInt()))
        assertNull(recolorTarget(picked = dodgerBlue, current = dodgerBlue))
    }

    @Test
    fun `Export closes the sheet first, then exports`() {
        render()
        composeTestRule.onNodeWithText("Export calendar").performClick()
        assertEquals(listOf("dismiss", "export"), events)
    }

    @Test
    fun `Export is off for a calendar with no events`() {
        render(initial = summary("Work", events = 0))
        composeTestRule.onNodeWithText("Export calendar").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Export calendar").performClick()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `the built-in sheet has its info, Color and Export, and no Rename`() {
        render(initial = summary("Local", builtIn = true))
        composeTestRule.onNodeWithText("Local").assertIsDisplayed()
        composeTestRule.onNodeWithText("Rename").assertDoesNotExist()
        composeTestRule.onNodeWithText("Custom").assertIsDisplayed()
        composeTestRule.onNodeWithText("Export calendar").assertIsDisplayed()
        composeTestRule.onNodeWithText(builtInInfo).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("About the built-in calendar").performClick()
        composeTestRule.onNodeWithText(builtInInfo).assertIsDisplayed()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a long name stays on one line in the sheet`() {
        val long = "A very long offline calendar name that keeps going well past the edge of the sheet"
        render(initial = summary(long))
        val nodes = composeTestRule.onAllNodesWithText(long, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        for (node in nodes) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertEquals(1, layouts.single().lineCount)
            assertTrue(layouts.single().isLineEllipsized(0))
        }
    }

    // ---- Color ----

    private val tomato = 0xFFFF6347.toInt()

    private fun openColor() {
        composeTestRule.onNodeWithText("Color").performClick()
        composeTestRule.waitForIdle()
    }

    private fun state(expected: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected)

    @Test
    fun `Color opens the palette inside the sheet and says so`() {
        render()
        composeTestRule.waitForIdle()
        val before = composeTestRule.dialogCount()
        composeTestRule.onNode(state("collapsed")).assertExists()
        openColor()
        composeTestRule.onNodeWithText("More colors").assertIsDisplayed()
        composeTestRule.onNode(state("expanded")).assertExists()
        assertEquals(before, composeTestRule.dialogCount())
    }

    @Test
    fun `a different color recolors and closes the palette`() {
        render()
        openColor()
        composeTestRule.onNodeWithTag(swatchTestTag(tomato)).performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("recolor $tomato"), events)
        composeTestRule.onNodeWithText("More colors").assertDoesNotExist()
    }

    @Test
    fun `the current color closes the palette without recoloring`() {
        render()
        openColor()
        composeTestRule.onNodeWithTag(swatchTestTag(dodgerBlue)).performClick()
        composeTestRule.waitForIdle()
        assertTrue(events.isEmpty())
        composeTestRule.onNodeWithText("More colors").assertDoesNotExist()
    }

    @Test
    fun `Back with the palette open closes only the palette`() {
        render()
        openColor()
        composeTestRule.backInLatestDialog()
        assertTrue(events.isEmpty())
        composeTestRule.onNodeWithText("More colors").assertDoesNotExist()
        composeTestRule.onNodeWithText("Export calendar").assertIsDisplayed()
        composeTestRule.backInLatestDialog()
        assertEquals(listOf("dismiss"), events)
    }

    @Test
    fun `opening Color takes focus from the rename field`() {
        render()
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { enterTouchModeInShownDialogs() }
        startRename()
        nameField().performClick()
        nameField().assertIsFocused()
        openColor()
        nameField().assertIsNotFocused()
    }
}
