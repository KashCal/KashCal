package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.util.CalendarSpan
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale

/**
 * The inline date picker (search, Go to date, Jump to date, a repeat's Until) and the event
 * form's date sheet stay inside the [CalendarSpan]: the arrows and swipes stop at its first and
 * last month, from a month outside it they only move toward it, and the year wheel offers its
 * years. Opening on a date outside the span keeps that date until the user picks another.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w411dp-h914dp-mdpi")
class DateEntrySpanTest {

    @get:Rule
    val rule = createComposeRule()

    private val savedLocale = Locale.getDefault()

    @Before
    fun pin() = Locale.setDefault(Locale.US)

    @After
    fun restore() = Locale.setDefault(savedLocale)

    private val first = CalendarSpan.firstMonth()
    private val last = CalendarSpan.lastMonth()

    private enum class Picker { INLINE, SHEET }

    private class Host(month: YearMonth) {
        var month by mutableStateOf(month)
        val changes = mutableListOf<YearMonth>()
    }

    private fun render(picker: Picker, month: YearMonth): Host {
        val host = Host(month)
        val edited = month.atDay(15)
        rule.setContent {
            MaterialTheme {
                when (picker) {
                    Picker.INLINE -> InlineDatePickerContent(
                        selectedDateMillis = edited.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        displayedMonth = host.month,
                        onDateSelect = {},
                        onMonthChange = { host.changes += it; host.month = it },
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
                            onMonth = { host.changes += it; host.month = it },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        return host
    }

    private fun next() = rule.onNodeWithContentDescription("Next month")
    private fun previous() = rule.onNodeWithContentDescription("Previous month")

    private fun swipe(left: Boolean) {
        rule.onNodeWithText("15").performTouchInput {
            if (left) swipeLeft(startX = centerX, endX = centerX - 400f, durationMillis = 200)
            else swipeRight(startX = centerX, endX = centerX + 400f, durationMillis = 200)
        }
        rule.waitForIdle()
    }

    private fun stopsAtTheLastMonth(picker: Picker) {
        val host = render(picker, last)
        next().assertIsNotEnabled()
        next().performClick()
        swipe(left = true)
        assertEquals(last, host.month)
        previous().assertIsEnabled()
        assertEquals(emptyList<YearMonth>(), host.changes)
    }

    private fun stopsAtTheFirstMonth(picker: Picker) {
        val host = render(picker, first)
        previous().assertIsNotEnabled()
        previous().performClick()
        swipe(left = false)
        assertEquals(first, host.month)
        next().assertIsEnabled()
        assertEquals(emptyList<YearMonth>(), host.changes)
    }

    private fun movesOnlyTowardTheSpanFromAfterIt(picker: Picker) {
        val outside = YearMonth.of(last.year + 124, 5)
        val host = render(picker, outside)
        next().assertIsNotEnabled()
        swipe(left = true)
        assertEquals("next does nothing", outside, host.month)
        previous().performClick()
        rule.waitForIdle()
        assertEquals(outside.minusMonths(1), host.month)
    }

    private fun movesOnlyTowardTheSpanFromBeforeIt(picker: Picker) {
        val outside = YearMonth.of(first.year - 176, 5)
        val host = render(picker, outside)
        previous().assertIsNotEnabled()
        swipe(left = false)
        assertEquals("previous does nothing", outside, host.month)
        next().performClick()
        rule.waitForIdle()
        assertEquals(outside.plusMonths(1), host.month)
    }

    private fun wheelOffersTheSpanYearsAndKeepsAnOutsideMonth(picker: Picker) {
        val outside = YearMonth.of(last.year + 124, 5)
        val host = render(picker, outside)
        rule.onNodeWithContentDescription("Pick month and year").performClick()
        rule.waitForIdle()
        // The wheel stands on the span's last year; past it the circular wheel wraps to the first.
        rule.onNodeWithText("${last.year}").assertExists()
        rule.onNodeWithText("${last.year + 1}").assertDoesNotExist()
        rule.onNodeWithText("${first.year}").assertExists()
        assertEquals("opening the wheel changes nothing", emptyList<YearMonth>(), host.changes)
    }

    @Test fun `the inline picker stops at the span's last month`() = stopsAtTheLastMonth(Picker.INLINE)
    @Test fun `the inline picker stops at the span's first month`() = stopsAtTheFirstMonth(Picker.INLINE)
    @Test fun `the date sheet stops at the span's last month`() = stopsAtTheLastMonth(Picker.SHEET)
    @Test fun `the date sheet stops at the span's first month`() = stopsAtTheFirstMonth(Picker.SHEET)

    @Test fun `the inline picker on a month after the span moves only back`() = movesOnlyTowardTheSpanFromAfterIt(Picker.INLINE)
    @Test fun `the inline picker on a month before the span moves only forward`() = movesOnlyTowardTheSpanFromBeforeIt(Picker.INLINE)
    @Test fun `the date sheet on a month after the span moves only back`() = movesOnlyTowardTheSpanFromAfterIt(Picker.SHEET)
    @Test fun `the date sheet on a month before the span moves only forward`() = movesOnlyTowardTheSpanFromBeforeIt(Picker.SHEET)

    @Test fun `the inline picker's wheel offers the span's years`() = wheelOffersTheSpanYearsAndKeepsAnOutsideMonth(Picker.INLINE)
    @Test fun `the date sheet's wheel offers the span's years`() = wheelOffersTheSpanYearsAndKeepsAnOutsideMonth(Picker.SHEET)

    @Test
    fun `a month pick on the wheel from a year after the span lands in the span's last year`() {
        val outside = YearMonth.of(last.year + 124, 5)
        val host = render(Picker.SHEET, outside)
        rule.onNodeWithContentDescription("Pick month and year").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Wheel picker with 12 options").performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 60f, durationMillis = 600)
        }
        rule.waitForIdle()

        assertTrue("changes ${host.changes}", host.changes.isNotEmpty())
        assertEquals(last.year, host.changes.last().year)
    }

    @Test
    fun `the year overlay on a month after the span goes to the month its wheel shows`() {
        // The month pager keeps a spare year past the span, so the Month view can show Jan of
        // the year after it; the overlay's wheel shows that month in the span's last year.
        var picked: Pair<Int, Int>? = null
        rule.setContent {
            MaterialTheme {
                org.onekash.kashcal.ui.components.YearOverlay(
                    visible = true,
                    currentYear = last.year + 1,
                    currentMonth = 0,
                    onMonthSelected = { year, month -> picked = year to month },
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("${last.year}").assertExists()
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()

        assertEquals(last.year to 0, picked)
    }

    @Test
    fun `inside the span the inline picker's arrows move a month both ways`() {
        // The date sheet's case is DateTimeSheetGridTest's arrow test.
        val month = YearMonth.from(LocalDate.now())
        val host = render(Picker.INLINE, month)
        next().performClick()
        rule.waitForIdle()
        assertEquals(month.plusMonths(1), host.month)
        previous().performClick()
        rule.waitForIdle()
        assertEquals(month, host.month)
    }
}
