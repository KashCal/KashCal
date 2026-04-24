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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
}
