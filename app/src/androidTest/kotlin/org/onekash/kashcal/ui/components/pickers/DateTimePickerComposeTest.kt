package org.onekash.kashcal.ui.components.pickers

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import java.util.Calendar as JavaCalendar

/**
 * Compose UI tests for DateTimeDisplayRow focus behavior.
 *
 * Regression guard for the keyboard-flicker bug: when a sibling TextField has
 * IME focus, tapping the start/end row must clear focus before the picker
 * sheet opens. Otherwise the keyboard dismisses just as the ModalBottomSheet
 * animates in, causing a visible flicker.
 */
@RunWith(AndroidJUnit4::class)
class DateTimePickerComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tappingStartRow_clearsFocusOnSiblingTextField() {
        assertRowTapClearsFocus(RowTarget.START)
    }

    @Test
    fun tappingEndRow_clearsFocusOnSiblingTextField() {
        assertRowTapClearsFocus(RowTarget.END)
    }

    private enum class RowTarget(val tapText: String) {
        START("10:00 AM"),
        END("11:00 AM")
    }

    private fun assertRowTapClearsFocus(target: RowTarget) {
        var handlerFired = false
        var isFocused = false
        val focusRequester = FocusRequester()

        val startMillis = dayAt(hour = 10)
        val endMillis = dayAt(hour = 11)

        composeTestRule.setContent {
            MaterialTheme {
                Column {
                    TextField(
                        value = "",
                        onValueChange = {},
                        label = { Text("Title") },
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .onFocusChanged { isFocused = it.isFocused }
                    )
                    DateTimeDisplayRow(
                        startDateMillis = startMillis,
                        startHour = 10,
                        startMinute = 0,
                        endDateMillis = endMillis,
                        endHour = 11,
                        endMinute = 0,
                        isAllDay = false,
                        onAllDayToggle = {},
                        onStartClick = { if (target == RowTarget.START) handlerFired = true },
                        onEndClick = { if (target == RowTarget.END) handlerFired = true }
                    )
                }
            }
        }

        composeTestRule.runOnIdle { focusRequester.requestFocus() }
        composeTestRule.waitForIdle()
        assertTrue("TextField should be focused before tap", isFocused)

        composeTestRule.onNodeWithText(target.tapText, substring = true).performClick()
        composeTestRule.waitForIdle()

        assertTrue("Row click handler should fire", handlerFired)
        assertFalse("TextField should lose focus after row tap", isFocused)
    }

    private fun dayAt(hour: Int): Long = JavaCalendar.getInstance().apply {
        set(2026, JavaCalendar.JANUARY, 15, hour, 0, 0)
    }.timeInMillis

    /**
     * Issue #238: tapping Done before the wheel fling settles must commit the
     * value visually centered at tap time, not the previously-stationary one.
     *
     * Drives the user-observable surface (DateTimeSheet) rather than the
     * VerticalWheelPicker building block, because the bug lives in the
     * settle-only callback contract between the picker and the sheet's
     * buffered localHour/localMinute.
     *
     * Uses manual clock control so the snap-fling animation is still in
     * progress when Done is tapped — the autoAdvance default would settle
     * the fling during waitForIdle and mask the pre-fix bug.
     */
    @Test
    fun datetimeSheet_done_mid_fling_commits_centered_hour() {
        var confirmedHour = -1
        val initialHour = 10
        val startMillis = dayAt(hour = initialHour)

        composeTestRule.setContent {
            MaterialTheme {
                DateTimeSheet(
                    selectedDateMillis = startMillis,
                    selectedHour = initialHour,
                    selectedMinute = 0,
                    isAllDay = false,
                    use24Hour = true,
                    onConfirm = { _, hour, _ -> confirmedHour = hour },
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.mainClock.autoAdvance = false

        // Hour wheel renders 24 options with the picker's content description.
        composeTestRule.onNodeWithContentDescription("Wheel picker with 24 options")
            .performTouchInput {
                swipeUp(startY = centerY, endY = centerY - 200f, durationMillis = 80)
            }

        // Advance enough frames for centerIndex to move off initialHour but
        // not enough for the snap-fling to settle. ~80ms is well inside the
        // typical 250-400ms snap animation.
        composeTestRule.mainClock.advanceTimeBy(80)

        // Tap Done while the fling is still mid-flight.
        val doneLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.action_done)
        composeTestRule.onAllNodesWithText(doneLabel)[0].performClick()

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()

        // The committed hour must reflect what was visually centered when Done
        // was tapped, not the pre-swipe initialHour. Pre-fix, this was always
        // initialHour (10); post-fix, it must have advanced.
        assertNotEquals(
            "DateTimeSheet committed pre-swipe hour — mid-fling Done dropped the new value",
            initialHour,
            confirmedHour
        )
    }
}
