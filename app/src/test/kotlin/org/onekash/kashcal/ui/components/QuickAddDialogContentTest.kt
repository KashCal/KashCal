package org.onekash.kashcal.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.quickadd.QuickAddResult
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Robolectric wiring guards for the hardened Quick Add field. The pure
 * cap/counter logic is unit-tested separately; these confirm the composable is
 * wired to it — that Enter never becomes a newline in the field state, that the
 * cap holds through the input pipeline, and that the counter reveals/hides at
 * the right thresholds. Guards against the field silently reverting to
 * SingleLine or the counter thresholds regressing in a future edit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class QuickAddDialogContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(textFieldState: TextFieldState) {
        composeTestRule.setContent {
            MaterialTheme {
                QuickAddDialogContent(
                    textFieldState = textFieldState,
                    focusRequester = remember { FocusRequester() },
                    parseResult = QuickAddResult(title = "", startDate = LocalDate.now()),
                    isSaveEnabled = false,
                    isSaving = false,
                    placeholder = "Coffee tomorrow at 3pm",
                    timeFormat = "system",
                    onSave = {},
                    onExpand = {}
                )
            }
        }
    }

    private fun counterText(count: Int) = "$count/${QuickAddInputLimits.MAX_LENGTH}"

    private fun counterVisible(count: Int): Boolean =
        composeTestRule.onAllNodes(hasText(counterText(count), substring = true))
            .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun newlineInInput_isStrippedFromFieldState() {
        val state = TextFieldState()
        render(state)

        composeTestRule.onNode(hasSetTextAction()).performTextInput("Line one\nLine two")
        composeTestRule.waitForIdle()

        assertFalse(
            "Enter must never insert a newline into the field",
            state.text.toString().contains("\n")
        )
        assertEquals("Line oneLine two", state.text.toString())
    }

    @Test
    fun pasteBeyondCap_isTruncatedToLimit() {
        val state = TextFieldState()
        render(state)

        composeTestRule.onNode(hasSetTextAction()).performTextInput("a".repeat(600))
        composeTestRule.waitForIdle()

        assertEquals(
            QuickAddInputLimits.MAX_LENGTH,
            QuickAddInputLimits.graphemeCount(state.text.toString())
        )
    }

    @Test
    fun counterHidden_whenInputShort() {
        val state = TextFieldState()
        render(state)

        composeTestRule.onNode(hasSetTextAction()).performTextInput("short")
        composeTestRule.waitForIdle()

        assertFalse("Counter must be hidden well below the reveal threshold", counterVisible(5))
    }

    @Test
    fun counterVisible_atWarnThreshold() {
        val state = TextFieldState()
        render(state)

        composeTestRule.onNode(hasSetTextAction())
            .performTextInput("a".repeat(QuickAddInputLimits.COUNTER_REVEAL_THRESHOLD))
        composeTestRule.waitForIdle()

        assertTrue(
            "Counter must appear at the reveal threshold",
            counterVisible(QuickAddInputLimits.COUNTER_REVEAL_THRESHOLD)
        )
    }

    @Test
    fun counterVisible_atLimit() {
        val state = TextFieldState()
        render(state)

        composeTestRule.onNode(hasSetTextAction())
            .performTextInput("a".repeat(QuickAddInputLimits.MAX_LENGTH))
        composeTestRule.waitForIdle()

        assertTrue(
            "Counter must show N/500 at the cap",
            counterVisible(QuickAddInputLimits.MAX_LENGTH)
        )
    }
}
