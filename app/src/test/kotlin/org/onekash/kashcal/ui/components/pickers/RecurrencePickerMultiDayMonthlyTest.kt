package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
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
 * Checks that the recurrence picker leaves a monthly rule on several days of the month
 * (BYMONTHDAY=1,15) alone until the user changes it: its monthly pattern can show only one day,
 * so an emission on open would drop the others.
 *
 * Run the class in isolation, since Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class RecurrencePickerMultiDayMonthlyTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Thu 2026-01-01 00:00 UTC.
    private val jan1Millis = 1767225600000L

    private val rule = "FREQ=MONTHLY;BYMONTHDAY=1,15"

    private var originalLocale: Locale? = null

    @Before
    fun pinLocaleToUS() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        originalLocale?.let { Locale.setDefault(it) }
    }

    private fun render(expanded: Boolean, emissions: MutableList<String?>) {
        composeTestRule.setContent {
            MaterialTheme {
                var rrule by remember { mutableStateOf<String?>(rule) }
                RecurrencePickerRow(
                    selectedRrule = rrule,
                    startDateMillis = jan1Millis,
                    isExpanded = expanded,
                    onToggle = {},
                    onSelect = { rrule = it; emissions += it },
                )
            }
        }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.waitForIdle()
    }

    @Test
    fun anUntouchedCollapsedPickerEmitsNothing() {
        val emissions = mutableListOf<String?>()
        render(expanded = false, emissions)
        assertEquals(emptyList<String?>(), emissions)
    }

    @Test
    fun anUntouchedExpandedPickerEmitsNothing() {
        val emissions = mutableListOf<String?>()
        render(expanded = true, emissions)
        assertEquals(emptyList<String?>(), emissions)
    }
}
