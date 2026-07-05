package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the swipe-to-delete gesture on a subscription row has a Switch
 * Access / TalkBack equivalent: a "Delete" custom accessibility action wired to
 * the same onDelete callback. Android requires gesture-only functionality to
 * also be reachable as a selectable control or custom action.
 *
 * Runs under Robolectric in the unit source set (no emulator).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class SwipeableSubscriptionItemActionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val deleteLabel =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_delete)

    private val subscription = IcsSubscriptionUiModel(
        id = 42L,
        url = "https://example.com/cal.ics",
        name = "My Calendar",
        color = 0xFF4CAF50.toInt(),
        enabled = true,
    )

    private fun renderWith(onDelete: (Long) -> Unit) {
        composeTestRule.setContent {
            MaterialTheme {
                SwipeableSubscriptionItem(
                    subscription = subscription,
                    onToggle = { _, _ -> },
                    onDelete = onDelete,
                    onRefresh = {},
                    onEdit = {},
                )
            }
        }
    }

    /** Finds the "Delete" CustomAccessibilityAction anywhere in the tree. */
    private fun findDeleteAction(): CustomAccessibilityAction? {
        val root: SemanticsNodeInteraction = composeTestRule.onRoot()
        // Walk the merged tree collecting any CustomActions lists.
        fun collect(node: androidx.compose.ui.semantics.SemanticsNode): List<CustomAccessibilityAction> =
            (node.config.getOrNull(SemanticsActions.CustomActions) ?: emptyList()) +
                node.children.flatMap { collect(it) }
        return collect(root.fetchSemanticsNode())
            .firstOrNull { it.label == deleteLabel }
    }

    @Test
    fun `row renders subscription name`() {
        renderWith(onDelete = {})
        composeTestRule.onNodeWithText("My Calendar").assertIsDisplayed()
    }

    @Test
    fun `exposes a Delete custom accessibility action`() {
        renderWith(onDelete = {})
        assertNotNull(
            "Swipe-to-delete must have a Delete custom action for Switch Access / TalkBack",
            findDeleteAction(),
        )
    }

    @Test
    fun `Delete custom action invokes onDelete with the subscription id`() {
        var deletedId: Long? = null
        renderWith(onDelete = { deletedId = it })

        val action = findDeleteAction()
        assertNotNull(action)
        val handled = action!!.action()

        assertTrue("custom action should report it handled the invocation", handled)
        assertTrue("onDelete should be called with id 42", deletedId == 42L)
    }
}
