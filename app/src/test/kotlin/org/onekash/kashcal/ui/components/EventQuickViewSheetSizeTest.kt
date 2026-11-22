package org.onekash.kashcal.ui.components

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Event
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Compose tests for how [EventQuickViewSheet] sits on a phone-sized screen (#418): an event too
 * tall for the window opens full with its details scrolling and Edit, Delete and More pinned at
 * the bottom, while a short event keeps its small, content-sized sheet. Read from window bounds
 * and the sheet's own node. Native graphics, so text wraps at its real width (the default
 * mode measures text as almost zero wide and a long title stays one line). Run the class alone:
 * Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class EventQuickViewSheetSizeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val savedLocale = Locale.getDefault()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(savedLocale)

    private val longTitle = (1..16).joinToString(" ") {
        "Part $it of a planning review with budget, hiring, roadmap, launch dates and risks."
    }
    private val longNotes = (1..60).joinToString("\n") { "Note line $it" }

    private fun event(
        title: String,
        description: String? = null,
        location: String? = null,
        rrule: String? = null,
    ) = Event(
        uid = "u1",
        calendarId = 1,
        title = title,
        description = description,
        location = location,
        rrule = rrule,
        startTs = 1_791_234_000_000L,
        endTs = 1_791_237_600_000L,
        timezone = "UTC",
        dtstamp = 1_000,
    )

    private class Calls {
        var edit = 0
        var delete = 0
        var duplicate = 0
        var dismissed = 0
    }

    private fun render(event: Event, readOnly: Boolean = false): Calls {
        val calls = Calls()
        composeTestRule.setContent {
            MaterialTheme {
                EventQuickViewSheet(
                    event = event,
                    calendarColor = 0xFF4CAF50.toInt(),
                    calendarName = "Work",
                    isReadOnlyCalendar = readOnly,
                    onDismiss = { calls.dismissed++ },
                    onEdit = { calls.edit++ },
                    onDeleteSingle = { calls.delete++ },
                    onDuplicate = { calls.duplicate++ },
                )
            }
        }
        composeTestRule.waitForIdle()
        return calls
    }

    private fun button(id: Int): SemanticsNodeInteraction =
        composeTestRule.onNodeWithContentDescription(context.getString(id))

    private fun bounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInWindow

    /** The details' scrollable: the one holding the event's [title]. */
    private fun detailsScrollable(title: String): SemanticsNodeInteraction =
        composeTestRule.onNode(hasScrollAction() and hasAnyDescendant(hasText(title, substring = true)))

    /** The notes section's own scrollable, inside the details but without the [title]. */
    private fun notesScrollable(title: String): SemanticsNodeInteraction =
        composeTestRule.onNode(
            hasScrollAction() and hasAnyDescendant(hasText("Note line 1", substring = true)) and
                !hasAnyDescendant(hasText(title, substring = true))
        )

    private fun assertActionsDisplayed() {
        button(R.string.action_edit).assertFullyOnScreen()
        button(R.string.action_delete).assertFullyOnScreen()
        button(R.string.cd_more_options).assertFullyOnScreen()
    }

    private fun sheetTop(): Float = composeTestRule.sheetTopFraction()

    // ---- an event too tall for the window ----

    @Test
    fun `a long event opens at full height`() {
        render(event(longTitle))

        assertTrue("sheet top at ${sheetTop()}, expected at the top", sheetTop() < 0.1f)
        composeTestRule.assertSheetOpenAtItsHeight()
    }

    @Test
    fun `a long event shows edit, delete and more without scrolling`() {
        val calls = render(event(longTitle))

        assertActionsDisplayed()
        button(R.string.action_edit).performClick()
        assertEquals(1, calls.edit)
    }

    @Test
    fun `a drag up scrolls a long event's details while the buttons stay put`() {
        render(event(longTitle))
        val editBefore = bounds(button(R.string.action_edit))
        val titleTopBefore = bounds(composeTestRule.onNodeWithText(longTitle)).top
        val details = detailsScrollable("Part 1 of")

        details.dragUp()
        composeTestRule.waitForIdle()

        assertTrue("details did not scroll", details.scrollOffset() > 0f)
        val titleTopAfter = bounds(composeTestRule.onNodeWithText(longTitle)).top
        assertTrue("title top $titleTopBefore -> $titleTopAfter", titleTopAfter < titleTopBefore)
        assertEquals(editBefore, bounds(button(R.string.action_edit)))
        assertTrue("the sheet stays full, top at ${sheetTop()}", sheetTop() < 0.1f)
    }

    @Test
    fun `a long one-off event's delete confirm shows on screen`() {
        val calls = render(event(longTitle))

        button(R.string.action_delete).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(context.getString(R.string.action_cancel)).assertFullyOnScreen()
        val confirm = composeTestRule.onNodeWithText(context.getString(R.string.action_confirm))
        confirm.assertFullyOnScreen()
        confirm.performClick()
        assertEquals(1, calls.delete)
    }

    @Test
    fun `a long event on a read-only calendar shows duplicate and share without scrolling`() {
        val calls = render(event(longTitle), readOnly = true)

        button(R.string.action_share).assertFullyOnScreen()
        button(R.string.action_duplicate).assertFullyOnScreen()
        button(R.string.action_duplicate).performClick()
        assertEquals(1, calls.duplicate)
    }

    @Test
    fun `a drag down from the top of a long event's details closes the sheet`() {
        val calls = render(event(longTitle))

        detailsScrollable("Part 1 of").dragDown()
        composeTestRule.waitForIdle()

        assertEquals(1, calls.dismissed)
    }

    @Test
    fun `a long event with long notes shows the buttons and both parts scroll`() {
        render(event(longTitle, description = longNotes))
        assertActionsDisplayed()
        val details = detailsScrollable("Part 1 of")

        // Drags the details to their end, where the notes section sits.
        repeat(30) {
            val range = details.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (range.value() < range.maxValue()) details.dragUp()
            composeTestRule.waitForIdle()
        }
        assertTrue("details did not scroll", details.scrollOffset() > 0f)

        val notes = notesScrollable("Part 1 of")
        notes.dragUp()
        composeTestRule.waitForIdle()
        assertTrue("notes did not scroll", notes.scrollOffset() > 0f)
        assertActionsDisplayed()
    }

    // ---- shorter events ----

    @Test
    fun `a short event keeps its small sheet with the buttons shown`() {
        render(event("Standup"))

        composeTestRule.assertSheetOpenAtItsHeight()
        val height = composeTestRule.sheetHeightFraction()
        assertTrue("sheet height $height, expected under half", height < 0.5f)
        assertActionsDisplayed()
    }

    // Taller than half the window but shorter than all of it: the size Material would stop at
    // half height, with the buttons below the screen's edge.
    @Test
    fun `a mid-height event opens at its own height with the buttons shown`() {
        render(event(MID_TITLE, location = "Conference room B, second floor", rrule = "FREQ=WEEKLY;BYDAY=MO"))

        composeTestRule.assertSheetOpenAtItsHeight()
        val height = composeTestRule.sheetHeightFraction()
        assertTrue("sheet height $height, expected between half and full", height > 0.5f && height < 1f)
        assertActionsDisplayed()
    }

    @Test
    fun `a short event with long notes opens at its height and its notes scroll on their own`() {
        render(event("Standup", description = longNotes))
        composeTestRule.assertSheetOpenAtItsHeight()
        assertActionsDisplayed()
        val editBefore = bounds(button(R.string.action_edit))
        val notes = notesScrollable("Standup")

        notes.dragUp()
        composeTestRule.waitForIdle()

        assertTrue("notes did not scroll", notes.scrollOffset() > 0f)
        assertEquals(editBefore, bounds(button(R.string.action_edit)))
    }

    private companion object {
        const val MID_TITLE = "Quarterly planning review with the whole product and design team"
    }
}
