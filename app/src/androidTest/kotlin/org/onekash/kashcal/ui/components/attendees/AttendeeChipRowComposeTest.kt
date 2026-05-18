package org.onekash.kashcal.ui.components.attendees

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rendering-side tests for [AttendeeChipRow]. The pure-logic side
 * (mode computation, F6 collapsed-sort) is covered in
 * `app/src/test/.../AttendeeChipRowStateTest.kt`. These tests verify
 * that each render mode actually paints the right visual.
 *
 * Lives in androidTest/ because Compose UI testing's
 * `androidx.ui.test.junit4` is androidTestImplementation only.
 */
@RunWith(AndroidJUnit4::class)
class AttendeeChipRowComposeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun emptyList_rendersNothing() {
        rule.setContent {
            ThemedRow(models = emptyList(), isCurrentUserOnList = false)
        }
        rule.onNodeWithTag(TEST_TAG_CHIP_ROW).assertDoesNotExist()
        rule.onNodeWithTag(TEST_TAG_LAVENDER_COUNT).assertDoesNotExist()
    }

    @Test
    fun offList_rendersLavenderCountChip() {
        rule.setContent {
            ThemedRow(
                models = (1..5).map { model("u$it@example.test", sortOrder = it - 1) },
                isCurrentUserOnList = false
            )
        }
        rule.onNodeWithTag(TEST_TAG_LAVENDER_COUNT).assertIsDisplayed()
        rule.onNodeWithTag(TEST_TAG_CHIP_ROW).assertDoesNotExist()
    }

    /**
     * v23.7.18: tapping the lavender count chip flips expanded → row swaps
     * to the inline FlowRow showing all chips with a "Show less" disclosure.
     */
    @Test
    fun offList_tapLavenderChip_swapsToInlineFlowRow() {
        rule.setContent {
            ThemedRow(
                models = (1..5).map { model("u$it@example.test", sortOrder = it - 1) },
                isCurrentUserOnList = false
            )
        }
        rule.onNodeWithTag(TEST_TAG_LAVENDER_COUNT).performClick()
        // Lavender pill gone, chip row visible with all 5 chips + Show less.
        rule.onNodeWithTag(TEST_TAG_LAVENDER_COUNT).assertDoesNotExist()
        rule.onNodeWithTag(TEST_TAG_CHIP_ROW).assertIsDisplayed()
        rule.onAllNodesWithTag(TEST_TAG_CHIP).assertCountEquals(5)
        rule.onNodeWithTag(TEST_TAG_SHOW_MORE).assertIsDisplayed()
    }

    /**
     * v23.7.18: tapping Show less in an expanded off-list row collapses
     * back to the lavender pill.
     */
    @Test
    fun offList_expanded_tapShowLess_collapsesBackToLavender() {
        rule.setContent {
            ThemedRow(
                models = (1..5).map { model("u$it@example.test", sortOrder = it - 1) },
                isCurrentUserOnList = false
            )
        }
        rule.onNodeWithTag(TEST_TAG_LAVENDER_COUNT).performClick()
        rule.onNodeWithTag(TEST_TAG_SHOW_MORE).performClick()
        rule.onNodeWithTag(TEST_TAG_LAVENDER_COUNT).assertIsDisplayed()
        rule.onNodeWithTag(TEST_TAG_CHIP_ROW).assertDoesNotExist()
    }

    @Test
    fun onList_with3Attendees_rendersAll3Chips_noShowMore() {
        rule.setContent {
            ThemedRow(
                models = listOf(
                    model("alice@example.test", sortOrder = 0, isYou = true),
                    model("bob@example.test", sortOrder = 1),
                    model("carol@example.test", sortOrder = 2)
                ),
                isCurrentUserOnList = true
            )
        }
        rule.onAllNodesWithTag(TEST_TAG_CHIP).assertCountEquals(3)
        rule.onNodeWithTag(TEST_TAG_SHOW_MORE).assertDoesNotExist()
    }

    @Test
    fun onList_with4Attendees_collapsed_rendersShowMore() {
        rule.setContent {
            ThemedRow(
                models = (0..3).map { model("u$it@example.test", sortOrder = it) },
                isCurrentUserOnList = true
            )
        }
        rule.onAllNodesWithTag(TEST_TAG_CHIP).assertCountEquals(3)
        rule.onNodeWithTag(TEST_TAG_SHOW_MORE).assertIsDisplayed()
    }

    @Test
    fun onList_with4Attendees_tappingShowMore_expandsInline() {
        rule.setContent {
            ThemedRow(
                models = (0..3).map { model("u$it@example.test", sortOrder = it) },
                isCurrentUserOnList = true
            )
        }
        rule.onNodeWithTag(TEST_TAG_SHOW_MORE).performClick()
        // After expanding, all 4 chips visible and Show More gone
        rule.onAllNodesWithTag(TEST_TAG_CHIP).assertCountEquals(4)
        rule.onNodeWithTag(TEST_TAG_SHOW_MORE).assertDoesNotExist()
    }

    @Test
    fun onList_with5Attendees_youAtSortOrder4_collapsed_renders4ChipsKeepingYouFirst() {
        // F6 contract: pin You at index 0; show 3 wire-first others; +N more.
        rule.setContent {
            ThemedRow(
                models = listOf(
                    model("bob@example.test", sortOrder = 0),
                    model("carol@example.test", sortOrder = 1),
                    model("dave@example.test", sortOrder = 2),
                    model("eve@example.test", sortOrder = 3),
                    model("alice@example.test", sortOrder = 4, isYou = true)
                ),
                isCurrentUserOnList = true
            )
        }
        rule.onAllNodesWithTag(TEST_TAG_CHIP).assertCountEquals(4)
        rule.onNodeWithTag(TEST_TAG_SHOW_MORE).assertIsDisplayed()
        // Alice's chip renders the literal "You" (replaces displayName entirely
        // when isYou=true — see AttendeeChip.kt visibleName branch).
        rule.onNodeWithText("You").assertIsDisplayed()
    }

    @Test
    fun chipTap_revealsExpandedRow() {
        rule.setContent {
            ThemedRow(
                models = listOf(
                    model("alice@example.test", sortOrder = 0, isYou = true)
                ),
                isCurrentUserOnList = true
            )
        }
        // Initially expanded chip body is collapsed (no email visible)
        rule.onNodeWithText("alice@example.test").assertDoesNotExist()
        // Tap the chip
        rule.onNodeWithTag(TEST_TAG_CHIP).performClick()
        // Email now visible
        rule.onNodeWithText("alice@example.test").assertIsDisplayed()
    }

    /**
     * Bug 3: chip's `expanded` state must survive Flow re-emits with a fresh
     * `AttendeeUiModel` instance for the same `bareAddress`. Without
     * `key(bareAddress, sortOrder)` at the parent FlowRow, slot identity is
     * positional and the new instance gets a fresh `remember` slot.
     */
    @Test
    fun chipExpansion_survivesReEmitOfSameAddress() {
        val initial = listOf(model("alice@example.test", sortOrder = 0, isYou = true))
        // Same bareAddress, fresh instance — simulates a Flow re-emit.
        val reemitted = listOf(model("alice@example.test", sortOrder = 0, isYou = true))
        rule.setContent {
            DriverWithSwap(initial = initial, swapTo = reemitted)
        }
        rule.onNodeWithTag(TEST_TAG_CHIP).performClick()
        rule.onNodeWithText("alice@example.test").assertIsDisplayed()
        // Re-emit
        rule.onNodeWithTag("swap").performClick()
        // Email still visible — slot survived because key(bareAddress, sortOrder)
        // pinned the chip's identity.
        rule.onNodeWithText("alice@example.test").assertIsDisplayed()
    }

    /**
     * Bug 3 cont.: removing a sibling chip from the list must not collapse
     * the expansion of a chip that's still present.
     */
    @Test
    fun chipExpansion_survivesSiblingRemoval() {
        val initial = listOf(
            model("alice@example.test", sortOrder = 0, isYou = true),
            model("bob@example.test", sortOrder = 1)
        )
        // Bob removed; Alice still present
        val shrunk = listOf(model("alice@example.test", sortOrder = 0, isYou = true))
        rule.setContent {
            DriverWithSwap(initial = initial, swapTo = shrunk)
        }
        // Tap Alice
        rule.onAllNodesWithTag(TEST_TAG_CHIP)[0].performClick()
        rule.onNodeWithText("alice@example.test").assertIsDisplayed()
        rule.onNodeWithTag("swap").performClick()
        // Alice's expansion preserved despite Bob disappearing
        rule.onNodeWithText("alice@example.test").assertIsDisplayed()
    }

    /**
     * F1: row-level `expanded` (the "+N more" disclosure) must survive Flow
     * re-emits — same root cause as Bug 3 but at the parent slot.
     */
    @Test
    fun showMoreDisclosure_survivesReEmit() {
        val initial = (0..3).map { model("u$it@example.test", sortOrder = it) }
        // Same models — simulates an idempotent Flow re-emit.
        val reemitted = (0..3).map { model("u$it@example.test", sortOrder = it) }
        rule.setContent {
            DriverWithSwap(
                initial = initial,
                swapTo = reemitted,
                isCurrentUserOnList = true
            )
        }
        // Initially collapsed — Show More visible
        rule.onNodeWithTag(TEST_TAG_SHOW_MORE).assertIsDisplayed()
        // Tap to expand
        rule.onNodeWithTag(TEST_TAG_SHOW_MORE).performClick()
        rule.onAllNodesWithTag(TEST_TAG_CHIP).assertCountEquals(4)
        // Re-emit
        rule.onNodeWithTag("swap").performClick()
        // Still expanded — all 4 chips visible, no Show More disclosure
        rule.onAllNodesWithTag(TEST_TAG_CHIP).assertCountEquals(4)
        rule.onNodeWithTag(TEST_TAG_SHOW_MORE).assertDoesNotExist()
    }

    /**
     * F3: degenerate-server emission with two AttendeeUiModel sharing the
     * same bareAddress (different sortOrder) must not throw. The compound
     * `key(bareAddress, sortOrder)` distinguishes them.
     */
    @Test
    fun duplicateBareAddress_doesNotThrowOnRender() {
        // Two attendees with the same address but distinct sortOrder.
        val models = listOf(
            model("dup@example.test", sortOrder = 0),
            model("dup@example.test", sortOrder = 1)
        )
        rule.setContent {
            ThemedRow(models = models, isCurrentUserOnList = true)
        }
        rule.onAllNodesWithTag(TEST_TAG_CHIP).assertCountEquals(2)
    }

    /**
     * v23.7.17: chip with `isYou=true` renders the literal "You" — the
     * `displayName` (e.g., "alice", "rkash") is suppressed entirely. Reverse
     * direction: chip with `isYou=false` shows the displayName.
     */
    @Test
    fun chip_withIsYou_rendersLiteralYouAndSuppressesDisplayName() {
        rule.setContent {
            ThemedRow(
                models = listOf(
                    model("alice@example.test", sortOrder = 0, isYou = true),
                    model("bob@example.test", sortOrder = 1)
                ),
                isCurrentUserOnList = true
            )
        }
        // Alice's chip says "You" — her displayName "alice" is NOT in the row.
        rule.onNodeWithText("You").assertIsDisplayed()
        rule.onNodeWithText("alice").assertDoesNotExist()
        // Bob's chip still shows his displayName.
        rule.onNodeWithText("bob").assertIsDisplayed()
    }

    /**
     * v23.7.17 F2: tapping "+N more" expands the row AND swaps the AssistChip
     * label to "Show less". A second tap collapses back, restoring the
     * "+N more" label. Single button toggles — no separate affordance.
     */
    @Test
    fun showMoreToggle_swapsLabelAndCollapsesBack() {
        rule.setContent {
            ThemedRow(
                models = (0..4).map { model("u$it@example.test", sortOrder = it) },
                isCurrentUserOnList = true
            )
        }
        // Initially collapsed: "+2 more" visible (5 total, 3 shown).
        rule.onNodeWithText("+2 more").assertIsDisplayed()
        rule.onNodeWithTag(TEST_TAG_SHOW_MORE).performClick()
        // Expanded: all 5 chips, AssistChip label is now "Show less".
        rule.onAllNodesWithTag(TEST_TAG_CHIP).assertCountEquals(5)
        rule.onNodeWithText("Show less").assertIsDisplayed()
        // Collapse back.
        rule.onNodeWithTag(TEST_TAG_SHOW_MORE).performClick()
        rule.onAllNodesWithTag(TEST_TAG_CHIP).assertCountEquals(3)
        rule.onNodeWithText("+2 more").assertIsDisplayed()
    }

    @Composable
    private fun ThemedRow(models: List<AttendeeUiModel>, isCurrentUserOnList: Boolean) {
        MaterialTheme {
            AttendeeChipRow(models = models, isCurrentUserOnList = isCurrentUserOnList)
        }
    }

    /**
     * Test scaffold that drives a swap between two model lists. Tapping the
     * "swap" Text node simulates a Flow re-emit. Used to verify chip + row
     * state survives parent recomposition.
     */
    @Composable
    private fun DriverWithSwap(
        initial: List<AttendeeUiModel>,
        swapTo: List<AttendeeUiModel>,
        isCurrentUserOnList: Boolean = true
    ) {
        var current by remember { mutableStateOf(initial) }
        MaterialTheme {
            Column {
                Text(
                    text = "swap",
                    modifier = Modifier
                        .testTag("swap")
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { current = swapTo })
                        }
                )
                AttendeeChipRow(
                    models = current,
                    isCurrentUserOnList = isCurrentUserOnList
                )
            }
        }
    }

    private fun model(
        addr: String,
        sortOrder: Int,
        isYou: Boolean = false,
        isOrganizer: Boolean = false
    ) = AttendeeUiModel(
        displayName = addr.substringBefore('@'),
        bareAddress = addr,
        status = AttendeeStatus.NeedsAction,
        isYou = isYou,
        isOrganizer = isOrganizer,
        sortOrder = sortOrder
    )
}
