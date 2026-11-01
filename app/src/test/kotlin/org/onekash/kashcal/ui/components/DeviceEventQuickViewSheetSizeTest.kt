package org.onekash.kashcal.ui.components

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Rect
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
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.domain.model.DisplayEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Compose tests for how [DeviceEventQuickViewSheet] sits on a phone-sized screen (#418), the
 * device-calendar twin of `EventQuickViewSheetSizeTest`: a long event opens full with its
 * details scrolling and its buttons pinned at the bottom; a short event keeps its small sheet.
 * Native graphics, so text wraps at its real width. Run the class alone: Robolectric runs of
 * more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class DeviceEventQuickViewSheetSizeTest {

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

    private fun deviceEvent(title: String, description: String = "") = DisplayEvent.Device(
        DeviceCalendarInstance(
            instanceId = 1L,
            eventId = 100L,
            title = title,
            description = description,
            location = "",
            startTs = 1_791_234_000_000L,
            endTs = 1_791_237_600_000L,
            startDay = 20261101,
            endDay = 20261101,
            isAllDay = false,
            hasRrule = false,
            rrule = null,
            reminders = emptyList(),
            calendarId = 1L,
            calendarDisplayName = "Phone",
            calendarColor = 0xFF4CAF50.toInt(),
            eventColor = null,
            status = 1,
            availability = 0,
            hasAlarm = false,
            selfAttendeeStatus = 0,
            isWritable = true,
            originalId = null,
            originalInstanceTime = null,
            timezone = "UTC",
            eventStartTs = 1_791_234_000_000L,
        )
    )

    private class Calls {
        var edit = 0
        var delete = 0
        var duplicate = 0
        var dismissed = 0
    }

    private fun render(event: DisplayEvent.Device, canWrite: Boolean = true): Calls {
        val calls = Calls()
        composeTestRule.setContent {
            MaterialTheme {
                DeviceEventQuickViewSheet(
                    displayEvent = event,
                    hasWritePermission = canWrite,
                    isWritableCalendar = canWrite,
                    onDismiss = { calls.dismissed++ },
                    onEdit = { calls.edit++ },
                    onDelete = { calls.delete++ },
                    onDuplicate = { calls.duplicate++ },
                )
            }
        }
        composeTestRule.waitForIdle()
        return calls
    }

    /** The sheet's buttons other than More are text labels. */
    private fun labelled(id: Int): SemanticsNodeInteraction =
        composeTestRule.onNodeWithText(context.getString(id))

    private fun more(): SemanticsNodeInteraction =
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.cd_more_options))

    private fun bounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInWindow

    private fun sheetTop(): Float = composeTestRule.sheetTopFraction()

    /** The details' scrollable: the one holding the long title. */
    private fun details(): SemanticsNodeInteraction =
        composeTestRule.onNode(hasScrollAction() and hasAnyDescendant(hasText("Part 1 of", substring = true)))

    private fun assertActionsDisplayed() {
        labelled(R.string.action_edit).assertFullyOnScreen()
        labelled(R.string.action_delete).assertFullyOnScreen()
        more().assertFullyOnScreen()
    }

    // ---- an event too tall for the window ----

    @Test
    fun `a long device event opens full with its buttons shown`() {
        val calls = render(deviceEvent(longTitle))

        assertTrue("sheet top at ${sheetTop()}, expected at the top", sheetTop() < 0.1f)
        composeTestRule.assertSheetOpenAtItsHeight()
        assertActionsDisplayed()
        labelled(R.string.action_edit).performClick()
        assertEquals(1, calls.edit)
    }

    @Test
    fun `a long one-off device event deletes through the confirm on screen`() {
        val calls = render(deviceEvent(longTitle))

        labelled(R.string.action_delete).performClick()
        composeTestRule.waitForIdle()

        labelled(R.string.action_cancel).assertFullyOnScreen()
        labelled(R.string.action_confirm).assertFullyOnScreen()
        labelled(R.string.action_confirm).performClick()
        assertEquals(1, calls.delete)
    }

    @Test
    fun `a drag up scrolls a long device event's details while the buttons stay put`() {
        render(deviceEvent(longTitle))
        val editBefore = bounds(labelled(R.string.action_edit))

        details().dragUp()
        composeTestRule.waitForIdle()

        assertTrue("details did not scroll", details().scrollOffset() > 0f)
        assertEquals(editBefore, bounds(labelled(R.string.action_edit)))
        assertTrue("the sheet stays full, top at ${sheetTop()}", sheetTop() < 0.1f)
    }

    @Test
    fun `a drag down from the top of a long device event's details closes the sheet`() {
        val calls = render(deviceEvent(longTitle))

        details().dragDown()
        composeTestRule.waitForIdle()

        assertEquals(1, calls.dismissed)
    }

    @Test
    fun `a long device event without write access shows duplicate and share`() {
        val calls = render(deviceEvent(longTitle), canWrite = false)

        labelled(R.string.action_share).assertFullyOnScreen()
        labelled(R.string.action_duplicate).assertFullyOnScreen()
        labelled(R.string.action_duplicate).performClick()
        assertEquals(1, calls.duplicate)
    }

    // ---- shorter events ----

    @Test
    fun `a short device event keeps its small sheet with the buttons shown`() {
        render(deviceEvent("Standup"))

        composeTestRule.assertSheetOpenAtItsHeight()
        val height = composeTestRule.sheetHeightFraction()
        assertTrue("sheet height $height, expected under half", height < 0.5f)
        assertActionsDisplayed()
    }

    @Test
    fun `a short device event with a long description scrolls the description on its own`() {
        render(deviceEvent("Standup", description = longNotes))
        composeTestRule.assertSheetOpenAtItsHeight()
        assertActionsDisplayed()
        val editBefore = bounds(labelled(R.string.action_edit))
        val notes = composeTestRule.onNode(
            hasScrollAction() and hasAnyDescendant(hasText("Note line 1", substring = true)) and
                !hasAnyDescendant(hasText("Standup", substring = true))
        )

        notes.dragUp()
        composeTestRule.waitForIdle()

        assertTrue("description did not scroll", notes.scrollOffset() > 0f)
        assertEquals(editBefore, bounds(labelled(R.string.action_edit)))
    }
}
