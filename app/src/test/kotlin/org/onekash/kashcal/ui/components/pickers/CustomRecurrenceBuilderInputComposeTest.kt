package org.onekash.kashcal.ui.components.pickers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Tests the typed "Repeat every" field in [CustomRecurrenceBuilder] and, through
 * [RecurrencePickerRow], the rule it produces: select-all on focus, per-keystroke emission,
 * steppers capped at [RECURRENCE_INTERVAL_MAX], the error for empty or 0, restore on leaving
 * the field, a synced value above the cap, and the field's size at a large font scale.
 *
 * Run the class in isolation, since Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class CustomRecurrenceBuilderInputComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Mon 2026-01-05 00:00 UTC.
    private val mondayMillis = 1767571200000L

    private var originalLocale: Locale? = null

    private val emitted = mutableListOf<Int>()

    @Before
    fun pinLocaleToUS() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        originalLocale?.let { Locale.setDefault(it) }
    }

    /** Composes the builder over a parent that stores each emission, plus a second field. */
    private fun setBuilder(initial: Int, wrap: @Composable (@Composable () -> Unit) -> Unit = { it() }) {
        composeTestRule.setContent {
            MaterialTheme {
                wrap {
                    var interval by remember { mutableStateOf(initial) }
                    Column {
                        CustomRecurrenceBuilder(
                            interval = interval,
                            unit = CustomRecurrenceUnit.DAY,
                            onIntervalChange = { interval = it; emitted += it },
                            onUnitChange = {},
                        )
                        BasicTextField(
                            state = rememberTextFieldState("other"),
                            modifier = Modifier.testTag(OTHER_FIELD),
                        )
                    }
                }
            }
        }
    }

    private fun field() = composeTestRule.onNodeWithContentDescription("Repeat every")
    private fun plus() = composeTestRule.onNodeWithContentDescription("Increase interval")
    private fun minus() = composeTestRule.onNodeWithContentDescription("Decrease interval")

    private val hasError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)
    private val hasNoError = SemanticsMatcher.keyNotDefined(SemanticsProperties.Error)

    private fun selection(): TextRange? =
        field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange)

    // ==================== Typing ====================

    @Test
    fun focusingTap_selectsWholeValue() {
        setBuilder(initial = 12)
        field().performClick()
        composeTestRule.runOnIdle { assertEquals(TextRange(0, 2), selection()) }
    }

    @Test
    fun typedDigit_replacesSelectedValue() {
        setBuilder(initial = 12)
        field().performClick()
        field().performTextInput("7")
        field().assertTextEquals("7")
        composeTestRule.runOnIdle { assertEquals(listOf(7), emitted) }
    }

    @Test
    fun eachValidKeystroke_emits() {
        setBuilder(initial = 2)
        field().performClick()
        // One call per character: performTextInput commits its whole string as one edit.
        field().performTextInput("3")
        field().performTextInput("5")
        field().performTextInput("4")
        field().assertTextEquals("354")
        composeTestRule.runOnIdle { assertEquals(listOf(3, 35, 354), emitted) }
    }

    @Test
    fun fastTyping_isNotOverwrittenByTheEchoedInterval() {
        setBuilder(initial = 2)
        field().performClick()
        // Let the focusing tap settle (the select-all runs a frame after it), then type with
        // no frame between keystrokes, so the parent's echo of 3 and 35 arrives mid-typing.
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.autoAdvance = false
        field().performTextInput("3")
        field().performTextInput("5")
        field().performTextInput("4")
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
        field().assertTextEquals("354")
        composeTestRule.runOnIdle { assertEquals(354, emitted.last()) }
    }

    @Test
    fun fourthDigit_isRejectedWhole() {
        setBuilder(initial = 354)
        field().performClick()
        field().performTextInputSelection(TextRange(3))
        field().performTextInput("0")
        field().assertTextEquals("354")
        composeTestRule.runOnIdle { assertTrue(emitted.isEmpty()) }
    }

    // ==================== Steppers ====================

    @Test
    fun plusAndMinus_stepByOne_emittingOncePerTap() {
        setBuilder(initial = 5)
        plus().performClick()
        field().assertTextEquals("6")
        minus().performClick()
        minus().performClick()
        field().assertTextEquals("4")
        composeTestRule.runOnIdle { assertEquals(listOf(6, 5, 4), emitted) }
    }

    @Test
    fun plus_stopsAtTheCap() {
        setBuilder(initial = 998)
        plus().assertIsEnabled()
        plus().performClick()
        field().assertTextEquals("999")
        plus().assertIsNotEnabled()
        minus().assertIsEnabled()
        composeTestRule.runOnIdle { assertEquals(listOf(999), emitted) }
    }

    @Test
    fun minus_stopsAtOne() {
        setBuilder(initial = 1)
        minus().assertIsNotEnabled()
        plus().assertIsEnabled()
    }

    // ==================== Empty or 0 ====================

    @Test
    fun clearedField_showsErrorAndDisablesSteppers() {
        setBuilder(initial = 5)
        field().assert(hasNoError)
        field().performClick()
        field().performTextClearance()
        field().assert(hasError)
        composeTestRule.onNodeWithText("Enter a number from 1 to 999").assertExists()
        plus().assertIsNotEnabled()
        minus().assertIsNotEnabled()
        composeTestRule.runOnIdle { assertTrue(emitted.isEmpty()) }
    }

    @Test
    fun zero_showsError() {
        setBuilder(initial = 5)
        field().performClick()
        field().performTextInput("0")
        field().assertTextEquals("0")
        field().assert(hasError)
        plus().assertIsNotEnabled()
        minus().assertIsNotEnabled()
        composeTestRule.runOnIdle { assertTrue(emitted.isEmpty()) }
    }

    // ==================== Restore on leave ====================

    @Test
    fun leavingAnEmptyField_restoresTheLastValidValue() {
        setBuilder(initial = 5)
        field().performClick()
        field().performTextClearance()
        composeTestRule.onNodeWithTag(OTHER_FIELD).performClick()
        field().assertTextEquals("5")
        field().assert(hasNoError)
        composeTestRule.onNodeWithText("Enter a number from 1 to 999").assertDoesNotExist()
        composeTestRule.runOnIdle { assertTrue(emitted.isEmpty()) }
    }

    @Test
    fun doneOnAZero_restoresTheLastValidValue() {
        setBuilder(initial = 5)
        field().performClick()
        field().performTextInput("0")
        field().performImeAction()
        field().assertTextEquals("5")
        field().assert(hasNoError)
        composeTestRule.runOnIdle { assertTrue(emitted.isEmpty()) }
    }

    @Test
    fun leadingZeros_areDroppedOnLeave() {
        setBuilder(initial = 5)
        field().performClick()
        field().performTextInput("0")
        field().performTextInput("0")
        field().performTextInput("7")
        field().performImeAction()
        field().assertTextEquals("7")
        composeTestRule.runOnIdle { assertEquals(listOf(7), emitted) }
    }

    // ==================== Above the cap ====================

    @Test
    fun syncedValueAboveTheCap_showsAsIs() {
        setBuilder(initial = 1200)
        field().assertTextEquals("1200")
        field().assert(hasNoError)
        plus().assertIsNotEnabled()
        minus().assertIsEnabled()
        composeTestRule.runOnIdle { assertTrue(emitted.isEmpty()) }
    }

    @Test
    fun syncedValueAboveTheCap_canShrinkButNotGrow() {
        setBuilder(initial = 1200)
        field().performClick()
        field().performTextInputSelection(TextRange(4))
        field().performTextInput("5")
        field().assertTextEquals("1200")
        field().performTextReplacement("120")
        field().assertTextEquals("120")
        composeTestRule.runOnIdle { assertEquals(listOf(120), emitted) }
    }

    // ==================== Size and semantics ====================

    @Test
    // Native graphics: the legacy default measures text at about 1px per character, so the
    // check could not fail there.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun fourDigits_fitAtFontScale2() {
        setBuilder(initial = 1200) { content ->
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density = density, fontScale = 2f)) {
                content()
            }
        }
        val node = field().fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val layout = results.single()
        val lineWidth = layout.getLineRight(0) - layout.getLineLeft(0)
        // The semantics node is the box inside the field's padding.
        val innerWidth = node.size.width
        assertTrue("text $lineWidth px wider than the field's $innerWidth px", lineWidth <= innerWidth)
        assertTrue("field ${node.size.height} px tall", node.size.height >= 48)
    }

    @Test
    fun fieldIsAtLeast48dpTall() {
        setBuilder(initial = 2)
        assertTrue(field().fetchSemanticsNode().size.height >= 48)
    }

    // ==================== Through the picker row ====================

    private val rules = mutableListOf<String?>()

    private fun setRow(initialRule: String) {
        composeTestRule.setContent {
            MaterialTheme {
                var rrule by remember { mutableStateOf<String?>(initialRule) }
                RecurrencePickerRow(
                    selectedRrule = rrule,
                    startDateMillis = mondayMillis,
                    isExpanded = true,
                    onToggle = {},
                    onSelect = { rrule = it; rules += it },
                )
            }
        }
    }

    @Test
    fun typing354_makesTheRowRead_every354Days() {
        setRow("FREQ=DAILY;INTERVAL=2")
        field().performClick()
        field().performTextInput("3")
        field().performTextInput("5")
        field().performTextInput("4")
        composeTestRule.onNodeWithText("Every 354 days").assertExists()
        composeTestRule.runOnIdle { assertTrue("got $rules", rules.last()!!.contains("INTERVAL=354")) }
    }

    @Test
    fun ruleWithInterval1200_opensWithoutEmitting() {
        setRow("FREQ=DAILY;INTERVAL=1200")
        field().assertTextEquals("1200")
        field().assert(hasNoError)
        composeTestRule.runOnIdle { assertTrue("got $rules", rules.isEmpty()) }
    }

    @Test
    fun clearingTheFieldWhileFocused_keepsTheLastValidRule() {
        setRow("FREQ=DAILY;INTERVAL=5")
        field().performClick()
        field().performTextClearance()
        field().assertIsFocused()
        composeTestRule.runOnIdle {
            rules.lastOrNull()?.let { assertTrue("got $it", it.contains("INTERVAL=5")) }
            rules.forEach { rule ->
                assertFalse("got $rule", rule!!.contains("INTERVAL=0"))
                assertFalse("got $rule", Regex("INTERVAL=(;|$)").containsMatchIn(rule))
            }
        }
    }

    private companion object {
        const val OTHER_FIELD = "other-field"
    }
}
