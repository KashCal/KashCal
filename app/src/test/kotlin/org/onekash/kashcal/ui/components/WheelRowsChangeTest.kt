package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Compose tests that a [WheelTimePicker] keeps its time when its row count changes while it is
 * shown, as the date and time sheet's wheel does when a phone rotates (MainActivity keeps the
 * composition through rotation, so the wheel stays mounted). Run the class alone: Robolectric
 * runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w411dp-h914dp-mdpi")
class WheelRowsChangeTest {

    @get:Rule
    val rule = createComposeRule()

    private val savedLocale = Locale.getDefault()

    @Before
    fun pin() = Locale.setDefault(Locale.US)

    @After
    fun restore() = Locale.setDefault(savedLocale)

    private fun assertTimeKept(from: Int, to: Int, use24Hour: Boolean) {
        var rows by mutableIntStateOf(from)
        var hour = 10
        var minute = 30
        rule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = hour,
                    selectedMinute = minute,
                    onTimeSelected = { h, m -> hour = h; minute = m },
                    use24Hour = use24Hour,
                    visibleItems = rows,
                    itemHeight = 32.dp,
                )
            }
        }
        rule.waitForIdle()

        rows = to
        rule.waitForIdle()

        assertEquals("hour after $from -> $to rows", 10, hour)
        assertEquals("minute after $from -> $to rows", 30, minute)
    }

    @Test
    fun `going from five rows to three keeps the time`() = assertTimeKept(5, 3, use24Hour = false)

    @Test
    fun `going from three rows to five keeps the time`() = assertTimeKept(3, 5, use24Hour = false)

    @Test
    fun `a 24-hour wheel going from five rows to three keeps the time`() = assertTimeKept(5, 3, use24Hour = true)
}
