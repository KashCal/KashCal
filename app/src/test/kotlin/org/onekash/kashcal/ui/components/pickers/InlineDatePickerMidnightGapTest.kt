package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.testutil.withDeviceTimeZone
import org.onekash.kashcal.ui.util.SelectedDay
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar as JavaCalendar

/**
 * A tap in the inline date picker yields the tapped day even where the clocks skip the end of
 * that day. Greenland moves to summer time at 01:00 UTC on the last Sunday of March, which is
 * Saturday 23:00 local, so the Saturday's 23:00 to 24:00 never happens; the search sheet and the
 * time grids' go-to-date sheet carry the current time of day, so a tap on that Saturday at 23:xx
 * could resolve onto Sunday.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class InlineDatePickerMidnightGapTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * Shows the picker on [tapped]'s month with [selectedMs] selected, taps [tapped]'s day and
     * returns the value the tap hands on.
     */
    private fun tapMs(selectedMs: Long, tapped: LocalDate): Long {
        var yielded: Long? = null
        rule.setContent {
            MaterialTheme {
                var displayedMonth by remember { mutableStateOf(java.time.YearMonth.from(tapped)) }
                InlineDatePickerContent(
                    selectedDateMillis = selectedMs,
                    displayedMonth = displayedMonth,
                    onDateSelect = { yielded = it },
                    onMonthChange = { displayedMonth = it },
                    firstDayOfWeek = JavaCalendar.SUNDAY,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(tapped.dayOfMonth.toString()).performClick()
        rule.waitForIdle()
        return yielded!!
    }

    private fun tapYields(selectedMs: Long, tapped: LocalDate): LocalDate = SelectedDay.localDate(tapMs(selectedMs, tapped))

    private fun at(date: LocalDate, time: LocalTime): Long =
        date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `a tap on Nuuk's change Saturday at 23_15 yields that Saturday`(): Unit = withDeviceTimeZone("America/Nuuk") {
        val selected = at(LocalDate.of(2026, 3, 21), LocalTime.of(23, 15))
        assertEquals(LocalDate.of(2026, 3, 28), tapYields(selected, LocalDate.of(2026, 3, 28)))
    }

    @Test
    fun `a tap on Scoresbysund's change Saturday at 23_45 yields that Saturday`(): Unit = withDeviceTimeZone("America/Scoresbysund") {
        val selected = at(LocalDate.of(2026, 3, 21), LocalTime.of(23, 45))
        assertEquals(LocalDate.of(2026, 3, 28), tapYields(selected, LocalDate.of(2026, 3, 28)))
    }

    @Test
    fun `a tap on a normal Nuuk day keeps that day`(): Unit = withDeviceTimeZone("America/Nuuk") {
        val selected = at(LocalDate.of(2026, 3, 21), LocalTime.of(23, 15))
        assertEquals(LocalDate.of(2026, 3, 14), tapYields(selected, LocalDate.of(2026, 3, 14)))
    }

    @Test
    fun `a day Apia skipped yields the first instant after it`(): Unit = withDeviceTimeZone("Pacific/Apia") {
        // Samoa skipped 30 December 2011 when it moved across the date line.
        val selected = at(LocalDate.of(2011, 12, 20), LocalTime.of(10, 0))
        assertEquals(Instant.parse("2011-12-30T10:00:00Z"), Instant.ofEpochMilli(tapMs(selected, LocalDate.of(2011, 12, 30))))
    }
}
