package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.reader.PendingInvitation
import org.onekash.kashcal.ui.components.attendees.AttendeeStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Compose tests for how [InvitationInboxSheet] sits on a phone-sized screen, read from where its
 * list (or empty message) starts in the window: full height whatever its card count, a list that
 * scrolls, a drag down that closes it, the empty message centered and closing it on a tap, and
 * an RSVP calling back. Run the class alone: Robolectric runs of more than one class hit a
 * native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class InvitationInboxSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val savedLocale = Locale.getDefault()
    private val start = 1_791_234_000_000L

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(savedLocale)

    private fun invitation(id: Long) = PendingInvitation(
        event = Event(
            id = id, calendarId = 1L, uid = "uid-$id", title = "Invite $id",
            startTs = start + id * 86_400_000L, endTs = start + id * 86_400_000L + 3_600_000L, dtstamp = 0L
        ),
        occurrenceStartTs = start + id * 86_400_000L,
        occurrenceEndTs = start + id * 86_400_000L + 3_600_000L,
        accountId = 1L,
        calendarColor = 0,
        organizerLabel = "Alex"
    )

    private class Calls {
        val rsvps = mutableListOf<Pair<Long, AttendeeStatus>>()
        var dismissed = 0
    }

    private fun render(count: Int): Calls {
        val calls = Calls()
        composeTestRule.setContent {
            MaterialTheme {
                InvitationInboxSheet(
                    invitations = (1L..count).map(::invitation),
                    timePattern = "h:mm a",
                    onRsvp = { id, status -> calls.rsvps += id to status },
                    onDismiss = { calls.dismissed++ },
                )
            }
        }
        composeTestRule.waitForIdle()
        return calls
    }

    /** Where the card list starts, as a fraction of the window height. */
    private fun listTop(): Float = composeTestRule.topFraction(composeTestRule.onNode(hasScrollAction()))

    @Test
    fun `an empty inbox opens at full height and its message closes it`() {
        val calls = render(count = 0)

        // The tap area holding the message fills the sheet below its handle, the message centered.
        val area = composeTestRule.onNode(hasClickAction() and hasText("All caught up"))
        val top = composeTestRule.topFraction(area)
        assertTrue("empty area starts at $top, expected at the top", top < 0.2f)
        val areaBounds = area.fetchSemanticsNode().boundsInWindow
        val bottom = areaBounds.bottom / composeTestRule.sheetWindowHeight()
        assertTrue("empty area ends at $bottom, expected at the bottom", bottom > 0.9f)
        val text = composeTestRule.onNodeWithText("All caught up", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow
        assertEquals(areaBounds.center.y, text.center.y, 2f)
        composeTestRule.onNodeWithText("All caught up").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, calls.dismissed)
    }

    @Test
    fun `one invitation opens at full height`() {
        render(count = 1)

        val top = listTop()
        assertTrue("list starts at $top, expected at the top", top < 0.2f)
    }

    @Test
    fun `a drag down from the list's top closes the sheet`() {
        val calls = render(count = 1)

        composeTestRule.onNode(hasScrollAction())
            .performTouchInput { swipeDown(startY = top + 10f, endY = bottom - 10f, durationMillis = 200) }
        composeTestRule.waitForIdle()

        assertEquals(1, calls.dismissed)
    }

    @Test
    fun `many invitations open full and the list scrolls`() {
        render(count = 10)

        val top = listTop()
        assertTrue("list starts at $top, expected near the top", top < 0.2f)
        composeTestRule.onNodeWithText("Invite 10").assertIsNotDisplayed()

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Invite 10"))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Invite 10").assertIsDisplayed()
    }

    @Test
    fun `an RSVP on a card calls back with that invitation`() {
        val calls = render(count = 1)

        composeTestRule.onAllNodesWithText("Yes")[0].performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(1L to AttendeeStatus.Accepted), calls.rsvps)
    }
}
