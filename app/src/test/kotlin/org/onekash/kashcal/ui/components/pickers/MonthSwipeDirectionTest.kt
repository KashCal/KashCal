package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.YearMonth
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale

/**
 * The month swipe in the inline date picker and the event form's date sheet goes the way the
 * arrows point: a swipe toward the reading direction's start shows the next month, as a pager
 * does, so right to left a swipe right is the next month and the Next month arrow sits on the
 * left. A short quick flick pages too, a short slow drag doesn't, and each swipe pages from the
 * month then shown. Run the class alone: Robolectric runs of more than one class hit a native
 * crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w411dp-h914dp-mdpi")
class MonthSwipeDirectionTest {

    @get:Rule
    val rule = createComposeRule()

    private val savedLocale = Locale.getDefault()

    @Before
    fun pin() = Locale.setDefault(Locale.US)

    @After
    fun restore() = Locale.setDefault(savedLocale)

    private val october = YearMonth.of(2026, 10)

    private enum class Picker { INLINE, SHEET }

    private class Host(month: YearMonth) {
        var month by mutableStateOf(month)
    }

    private fun render(picker: Picker, direction: LayoutDirection): Host {
        val host = Host(october)
        val edited = october.atDay(15)
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    when (picker) {
                        Picker.INLINE -> InlineDatePickerContent(
                            selectedDateMillis = edited.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                            displayedMonth = host.month,
                            onDateSelect = {},
                            onMonthChange = { host.month = it },
                        )
                        Picker.SHEET -> {
                            var showWheel by remember { mutableStateOf(false) }
                            DateTimeSheetGrid(
                                editedDate = edited,
                                displayedMonth = host.month,
                                range = null,
                                weekdayRowHeight = 32.dp,
                                firstDayOfWeek = Calendar.SUNDAY,
                                showMonthYearPicker = showWheel,
                                onShowMonthYearPickerChange = { showWheel = it },
                                onSelectDate = {},
                                onMonth = { host.month = it },
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        return host
    }

    private fun swipeGrid(right: Boolean) {
        rule.onNodeWithText("15").performTouchInput {
            if (right) swipeRight(startX = centerX, endX = centerX + 400f, durationMillis = 200)
            else swipeLeft(startX = centerX, endX = centerX - 400f, durationMillis = 200)
        }
        rule.waitForIdle()
    }

    private fun nextArrowIsOnTheLeft(): Boolean {
        val next = rule.onNodeWithContentDescription("Next month").fetchSemanticsNode().boundsInRoot
        val previous = rule.onNodeWithContentDescription("Previous month").fetchSemanticsNode().boundsInRoot
        return next.left < previous.left
    }

    @Test
    fun `inline picker left to right, a swipe left is the next month`() {
        val host = render(Picker.INLINE, LayoutDirection.Ltr)
        swipeGrid(right = false)
        assertEquals(YearMonth.of(2026, 11), host.month)
    }

    @Test
    fun `date sheet left to right, a swipe left is the next month`() {
        val host = render(Picker.SHEET, LayoutDirection.Ltr)
        swipeGrid(right = false)
        assertEquals(YearMonth.of(2026, 11), host.month)
    }

    @Test
    fun `inline picker right to left, the next arrow is on the left and a swipe right is the next month`() {
        val host = render(Picker.INLINE, LayoutDirection.Rtl)
        assertTrue("Next month arrow on the left", nextArrowIsOnTheLeft())
        swipeGrid(right = true)
        assertEquals(YearMonth.of(2026, 11), host.month)
    }

    @Test
    fun `date sheet right to left, the next arrow is on the left and a swipe right is the next month`() {
        val host = render(Picker.SHEET, LayoutDirection.Rtl)
        assertTrue("Next month arrow on the left", nextArrowIsOnTheLeft())
        swipeGrid(right = true)
        assertEquals(YearMonth.of(2026, 11), host.month)
    }

    @Test
    fun `inline picker right to left, a swipe left is the previous month`() {
        val host = render(Picker.INLINE, LayoutDirection.Rtl)
        swipeGrid(right = false)
        assertEquals(YearMonth.of(2026, 9), host.month)
    }

    @Test
    fun `date sheet right to left, a swipe left is the previous month`() {
        val host = render(Picker.SHEET, LayoutDirection.Rtl)
        swipeGrid(right = false)
        assertEquals(YearMonth.of(2026, 9), host.month)
    }

    @Test
    fun `inline picker left to right, a swipe right is the previous month`() {
        val host = render(Picker.INLINE, LayoutDirection.Ltr)
        swipeGrid(right = true)
        assertEquals(YearMonth.of(2026, 9), host.month)
    }

    @Test
    fun `date sheet left to right, a swipe right is the previous month`() {
        val host = render(Picker.SHEET, LayoutDirection.Ltr)
        swipeGrid(right = true)
        assertEquals(YearMonth.of(2026, 9), host.month)
    }

    @Test
    fun `inline picker, no month swipe while the month and year wheel shows`() {
        val host = render(Picker.INLINE, LayoutDirection.Ltr)
        swipeWithWheelOpen()
        assertEquals(october, host.month)
    }

    @Test
    fun `date sheet, no month swipe while the month and year wheel shows`() {
        val host = render(Picker.SHEET, LayoutDirection.Ltr)
        swipeWithWheelOpen()
        assertEquals(october, host.month)
    }

    /** Opens the month and year wheel from the month title, then swipes across the picker. */
    private fun swipeWithWheelOpen() {
        rule.onNodeWithContentDescription("Pick month and year").performClick()
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            swipeLeft(startX = centerX + 200f, endX = centerX - 200f, durationMillis = 200)
        }
        rule.waitForIdle()
    }

    private fun flick(distanceDp: Int, durationMillis: Long) {
        rule.onNodeWithText("15").performTouchInput {
            val distance = with(rule.density) { distanceDp.dp.toPx() }
            swipe(center, center - Offset(distance, 0f), durationMillis = durationMillis)
        }
        rule.waitForIdle()
    }

    @Test
    fun `inline picker, a short quick flick pages a month and a short slow drag doesn't`() {
        val host = render(Picker.INLINE, LayoutDirection.Ltr)
        flick(distanceDp = 16, durationMillis = 1_500)
        assertEquals(october, host.month)
        // 30dp in 50ms, 600dp a second: under the distance, over the fling speed.
        flick(distanceDp = 30, durationMillis = 50)
        assertEquals(YearMonth.of(2026, 11), host.month)
    }

    @Test
    fun `date sheet, a short quick flick pages a month and a short slow drag doesn't`() {
        val host = render(Picker.SHEET, LayoutDirection.Ltr)
        flick(distanceDp = 16, durationMillis = 1_500)
        assertEquals(october, host.month)
        flick(distanceDp = 30, durationMillis = 50)
        assertEquals(YearMonth.of(2026, 11), host.month)
    }

    @Test
    fun `inline picker, two swipes page two months`() {
        val host = render(Picker.INLINE, LayoutDirection.Ltr)
        swipeGrid(right = false)
        swipeGrid(right = false)
        assertEquals(YearMonth.of(2026, 12), host.month)
    }

    @Test
    fun `date sheet, two swipes page two months`() {
        val host = render(Picker.SHEET, LayoutDirection.Ltr)
        swipeGrid(right = false)
        swipeGrid(right = false)
        assertEquals(YearMonth.of(2026, 12), host.month)
    }
}
