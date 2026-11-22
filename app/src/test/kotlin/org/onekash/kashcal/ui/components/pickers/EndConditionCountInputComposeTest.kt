package org.onekash.kashcal.ui.components.pickers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.rrule.EndCondition
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Tests the "After N occurrences" field in [EndConditionSelector]: select-all on focus,
 * per-keystroke counts, edits refused whole, restore of the last valid count on leaving the
 * field, the After radio never sending a count of 0, and no count sent outside Count mode.
 *
 * Run the class in isolation, since Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class EndConditionCountInputComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Mon 2026-01-05 00:00 UTC.
    private val mondayMillis = 1767571200000L

    private var originalLocale: Locale? = null

    private val emitted = mutableListOf<EndCondition>()

    @Before
    fun pinLocaleToUS() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        originalLocale?.let { Locale.setDefault(it) }
    }

    private fun setSelector(initial: EndCondition) {
        composeTestRule.setContent {
            MaterialTheme {
                var endCondition by remember { mutableStateOf(initial) }
                Column {
                    EndConditionSelector(
                        endCondition = endCondition,
                        startDateMillis = mondayMillis,
                        onEndConditionChange = { endCondition = it; emitted += it },
                    )
                    BasicTextField(
                        state = rememberTextFieldState("other"),
                        modifier = Modifier.testTag(OTHER_FIELD),
                    )
                }
            }
        }
    }

    private fun countField() =
        composeTestRule.onNode(
            hasSetTextAction() and hasAnySibling(hasText("After")) and !hasTestTag(OTHER_FIELD)
        )

    private fun selection(): TextRange? =
        countField().fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange)

    @Test
    fun focusingTap_selectsWholeCount() {
        setSelector(EndCondition.Count(10))
        countField().performClick()
        composeTestRule.runOnIdle { assertEquals(TextRange(0, 2), selection()) }
    }

    @Test
    fun typedDigits_replaceTheCountAndEmitPerKeystroke() {
        setSelector(EndCondition.Count(10))
        countField().performClick()
        countField().performTextInput("2")
        countField().performTextInput("5")
        countField().assertTextEquals("25")
        composeTestRule.runOnIdle {
            assertEquals(listOf(EndCondition.Count(2), EndCondition.Count(25)), emitted)
        }
    }

    @Test
    fun fourthDigit_isRejectedWhole() {
        setSelector(EndCondition.Count(10))
        countField().performTextReplacement("1234")
        countField().assertTextEquals("10")
        composeTestRule.runOnIdle { assertTrue(emitted.isEmpty()) }
    }

    @Test
    fun clearThenDone_restoresTheLastValidCount() {
        setSelector(EndCondition.Count(10))
        countField().performClick()
        countField().performTextClearance()
        countField().performImeAction()
        countField().assertTextEquals("10")
        composeTestRule.runOnIdle { assertTrue(emitted.isEmpty()) }
    }

    @Test
    fun zeroThenLeaving_restoresTheLastValidCount() {
        setSelector(EndCondition.Count(10))
        countField().performClick()
        countField().performTextInput("2")
        countField().performTextInput("5")
        countField().performTextClearance()
        countField().performTextInput("0")
        composeTestRule.onNodeWithTag(OTHER_FIELD).performClick()
        countField().assertTextEquals("25")
        composeTestRule.runOnIdle {
            assertEquals(listOf(EndCondition.Count(2), EndCondition.Count(25)), emitted)
        }
    }

    @Test
    fun leadingZeros_areDroppedOnLeave() {
        setSelector(EndCondition.Count(10))
        countField().performClick()
        countField().performTextInput("0")
        countField().performTextInput("0")
        countField().performTextInput("7")
        countField().performImeAction()
        countField().assertTextEquals("7")
        composeTestRule.runOnIdle { assertEquals(listOf(EndCondition.Count(7)), emitted) }
    }

    @Test
    fun switchIntoCountModeFromOutside_seedsTheField() {
        val condition = mutableStateOf<EndCondition>(EndCondition.Never)
        composeTestRule.setContent {
            MaterialTheme {
                EndConditionSelector(
                    endCondition = condition.value,
                    startDateMillis = mondayMillis,
                    onEndConditionChange = { condition.value = it },
                )
            }
        }
        // Disabled outside Count mode, the field has no SetText action, so find it by its text.
        val field = composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
        field.assertTextEquals("10")
        condition.value = EndCondition.Count(42)
        field.assertTextEquals("42")
    }

    @Test
    fun openedWithNever_sendsNothing() {
        setSelector(EndCondition.Never)
        composeTestRule.runOnIdle { assertTrue("got $emitted", emitted.isEmpty()) }
    }

    @Test
    fun openedWithUntil_sendsNothing() {
        setSelector(EndCondition.Until(mondayMillis + 30L * 86_400_000L))
        composeTestRule.runOnIdle { assertTrue("got $emitted", emitted.isEmpty()) }
    }

    @Test
    fun afterRadio_reusesTheLastValidCount_acrossNever() {
        setSelector(EndCondition.Count(10))
        countField().performClick()
        countField().performTextInput("2")
        countField().performTextInput("5")
        countField().performImeAction()
        composeTestRule.onNodeWithText("Never").performClick()
        emitted.clear()
        tapAfterRadio()
        countField().assertTextEquals("25")
        composeTestRule.runOnIdle { assertEquals(listOf(EndCondition.Count(25)), emitted) }
    }

    @Test
    fun afterRadio_neverSendsCountZero() {
        setSelector(EndCondition.Count(10))
        countField().performClick()
        countField().performTextInput("0")
        // The radio box takes no focus, so the field still reads 0 when it is tapped.
        tapAfterRadio()
        composeTestRule.runOnIdle {
            assertTrue("got $emitted", emitted.none { it is EndCondition.Count && it.count < 1 })
            assertEquals(EndCondition.Count(10), emitted.last())
        }
    }

    /**
     * Taps the After radio: the clickable without text on the same line as the "After" label
     * (the Never option has a radio of its own).
     */
    private fun tapAfterRadio() {
        val label = composeTestRule.onNodeWithText("After").fetchSemanticsNode().boundsInRoot
        val radios = composeTestRule.onAllNodes(
            hasClickAction() and !hasSetTextAction() and
                SemanticsMatcher.keyNotDefined(SemanticsProperties.Text)
        )
        val index = radios.fetchSemanticsNodes().indexOfFirst { node ->
            node.boundsInRoot.center.y in label.top..label.bottom && node.boundsInRoot.right <= label.left
        }
        assertTrue("no radio beside the After label", index >= 0)
        radios[index].performClick()
    }

    private companion object {
        const val OTHER_FIELD = "other-field"
    }
}
