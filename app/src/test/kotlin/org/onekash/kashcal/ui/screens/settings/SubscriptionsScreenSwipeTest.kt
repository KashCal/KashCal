package org.onekash.kashcal.ui.screens.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Tests Calendar Feeds' two-step delete: a swipe reveals Delete without deleting, the Delete tap
 * deletes, one row is open at a time, and scrolling or tapping elsewhere closes the open row.
 *
 * Runs under Robolectric; run the class in isolation given the repo's multi-class native-crash
 * flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w411dp-h891dp-mdpi")
class SubscriptionsScreenSwipeTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(resId: Int) = context.getString(resId)
    private var originalLocale: Locale? = null

    @Before
    fun pinLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        originalLocale?.let { Locale.setDefault(it) }
    }

    private fun feed(id: Long?, name: String) = IcsSubscriptionUiModel(
        id = id, url = "https://feeds.example.test/$name.ics", name = name, color = 0xFF1E88E5.toInt(),
    )

    private val subscriptions = mutableStateListOf<IcsSubscriptionUiModel>()
    private val deleted = mutableListOf<Long>()
    private val toggled = mutableListOf<Pair<Long, Boolean>>()
    private val refreshed = mutableListOf<Long>()
    private var accountFeedsOpened = 0

    private fun show(vararg rows: IcsSubscriptionUiModel) {
        subscriptions.addAll(rows)
        rule.setContent {
            MaterialTheme {
                SubscriptionsScreen(
                    subscriptions = subscriptions.toList(),
                    onNavigateBack = {},
                    onAddSubscription = { _, _, _ -> },
                    onToggleSubscription = { id, on -> toggled += id to on },
                    // Like the ViewModel's staged delete: the row leaves the list at once.
                    onDeleteSubscription = { id -> deleted += id; subscriptions.removeAll { it.id == id } },
                    onRefreshSubscription = { refreshed += it },
                    onUpdateSubscription = { _, _, _, _ -> },
                    showAddFromAccount = true,
                    onOpenAccountFeeds = { accountFeedsOpened++ },
                )
            }
        }
    }

    private fun left(name: String): Dp = rule.onNodeWithText(name).getUnclippedBoundsInRoot().left

    private fun swipe(name: String) {
        rule.onNodeWithText(name).performTouchInput { swipeLeft() }
        rule.waitForIdle()
    }

    private val deleteButton get() = hasText(str(R.string.action_delete)) and hasClickAction()

    @Test
    fun `the list names the swipe while it has feeds`() {
        show(feed(1, "Feed A"))
        rule.onNodeWithText(str(R.string.subscriptions_swipe_hint)).assertExists()

        swipe("Feed A")
        rule.onNode(deleteButton).performClick()
        rule.waitForIdle()

        rule.onNodeWithText(str(R.string.subscriptions_swipe_hint)).assertDoesNotExist()
    }

    @Test
    fun `a full swipe reveals Delete and doesn't delete`() {
        show(feed(1, "Feed A"), feed(2, "Feed B"))
        val closedLeft = left("Feed A")

        swipe("Feed A")

        assertEquals(emptyList<Long>(), deleted)
        assertEquals(closedLeft - 96.dp, left("Feed A"))
        rule.onNode(deleteButton).assertExists()
    }

    @Test
    fun `tapping the revealed Delete deletes that feed once`() {
        show(feed(1, "Feed A"), feed(2, "Feed B"))
        swipe("Feed B")

        rule.onNode(deleteButton).performClick()
        rule.waitForIdle()

        assertEquals(listOf(2L), deleted)
        rule.onNodeWithText("Feed B").assertDoesNotExist()
    }

    @Test
    fun `opening a second row closes the first`() {
        show(feed(1, "Feed A"), feed(2, "Feed B"))
        val closedLeft = left("Feed A")

        swipe("Feed A")
        swipe("Feed B")

        assertEquals(closedLeft, left("Feed A"))
        assertEquals(closedLeft - 96.dp, left("Feed B"))
    }

    @Test
    fun `while a row is open, a tap on another row, switch or refresh only closes it`() {
        show(feed(1, "Feed A"), feed(2, "Feed B"))
        val closedLeft = left("Feed A")

        swipe("Feed A")
        rule.onAllNodes(isToggleable())[1].performClick()
        rule.waitForIdle()
        assertEquals(closedLeft, left("Feed A"))

        swipe("Feed A")
        rule.onAllNodesWithContentDescription(str(R.string.cd_refresh))[1].performClick()
        rule.waitForIdle()
        assertEquals(closedLeft, left("Feed A"))

        swipe("Feed A")
        rule.onNodeWithText("Feed B").performClick()
        rule.waitForIdle()
        assertEquals(closedLeft, left("Feed A"))

        assertEquals(emptyList<Pair<Long, Boolean>>(), toggled)
        assertEquals(emptyList<Long>(), refreshed)
        rule.onNodeWithText(str(R.string.dialog_edit_subscription)).assertDoesNotExist()
    }

    @Test
    fun `with no row open, the switch, refresh and row tap work as before`() {
        show(feed(1, "Feed A"), feed(2, "Feed B"))

        rule.onAllNodes(isToggleable())[1].performClick()
        rule.onAllNodesWithContentDescription(str(R.string.cd_refresh))[1].performClick()
        rule.onNodeWithText("Feed B").performClick()
        rule.waitForIdle()

        assertEquals(listOf(2L to false), toggled)
        assertEquals(listOf(2L), refreshed)
        rule.onNodeWithText(str(R.string.dialog_edit_subscription)).assertExists()
    }

    @Test
    fun `the account and ICS add buttons close an open row and then act`() {
        show(feed(1, "Feed A"))
        val closedLeft = left("Feed A")

        swipe("Feed A")
        rule.onNodeWithText(str(R.string.account_feeds_add)).performClick()
        rule.waitForIdle()
        assertEquals(closedLeft, left("Feed A"))
        assertEquals(1, accountFeedsOpened)

        swipe("Feed A")
        rule.onNodeWithText(str(R.string.subscriptions_add_ics)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.dialog_add_subscription)).assertExists()
        assertEquals(closedLeft, left("Feed A"))
    }

    @Test
    fun `the holiday button closes an open row and opens the holiday picker`() {
        show(feed(1, "Feed A"))
        val closedLeft = left("Feed A")

        swipe("Feed A")
        rule.onNodeWithText(str(R.string.holiday_catalog_add)).performClick()
        rule.waitForIdle()

        rule.onNodeWithText(str(R.string.holiday_catalog_search_label)).assertExists()
        assertEquals(closedLeft, left("Feed A"))
    }

    @Test
    fun `scrolling the list closes an open row`() {
        show(*(1L..30L).map { feed(it, "Feed $it") }.toTypedArray())
        val closedLeft = left("Feed 5")

        swipe("Feed 5")
        assertEquals(closedLeft - 96.dp, left("Feed 5"))

        // A short scroll, so the open row stays on screen and closes rather than leaving.
        rule.onNode(hasScrollAction()).performTouchInput { swipeUp(startY = centerY, endY = centerY - 120f) }
        rule.waitForIdle()

        rule.onNodeWithText("Feed 5").assertExists()
        assertEquals(closedLeft, left("Feed 5"))
    }

    @Test
    fun `after Undo the row is back closed and its next tap opens edit`() {
        show(feed(1, "Feed A"), feed(2, "Feed B"))
        swipe("Feed A")
        rule.onNode(deleteButton).performClick()
        rule.waitForIdle()

        // Undo: the staged row comes back.
        subscriptions.add(0, feed(1, "Feed A"))
        rule.waitForIdle()
        rule.onNodeWithText("Feed A").assertExists()
        assertEquals(left("Feed B"), left("Feed A"))

        rule.onNodeWithText("Feed A").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.dialog_edit_subscription)).assertExists()
    }

    @Test
    fun `a feed not yet saved neither reveals Delete nor offers the action`() {
        show(feed(null, "Unsaved"))
        val closedLeft = left("Unsaved")

        swipe("Unsaved")

        assertEquals(closedLeft, left("Unsaved"))
        rule.onNode(deleteButton).assertDoesNotExist()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and hasText("Unsaved"))
            .assertDoesNotExist()
    }
}
