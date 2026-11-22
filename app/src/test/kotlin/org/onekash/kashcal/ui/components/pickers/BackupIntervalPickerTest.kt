package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.calendarbackup.CalendarBackupInterval
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class BackupIntervalPickerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ---------- the state holder ----------

    @Test
    fun `the state opens on the stored interval split into days and hours`() {
        val state = BackupIntervalPickerState(54)
        assertEquals(2, state.days)
        assertEquals(6, state.hours)
        assertEquals(CalendarBackupInterval.Check.Valid(54), state.check)
    }

    @Test
    fun `a chip sets both wheels`() {
        val state = BackupIntervalPickerState(12)
        state.choose(168)
        assertEquals(7, state.days)
        assertEquals(0, state.hours)
        assertEquals(168, state.selectedChip)
    }

    @Test
    fun `a custom value selects no chip`() {
        val state = BackupIntervalPickerState(12)
        state.days = 2
        state.hours = 6
        assertNull(state.selectedChip)
    }

    @Test
    fun `invalid choices are reported, not adjusted`() {
        val state = BackupIntervalPickerState(12)
        state.days = 0
        state.hours = 0
        assertEquals(CalendarBackupInterval.Check.TooShort, state.check)
        assertEquals(0, state.hours)
        state.days = 30
        state.hours = 5
        assertEquals(CalendarBackupInterval.Check.TooLong, state.check)
        assertEquals(5, state.hours)
    }

    // ---------- the picker ----------

    private fun render(state: BackupIntervalPickerState, onDone: (Int) -> Unit = {}) {
        composeTestRule.setContent {
            MaterialTheme { BackupIntervalPicker(state = state, onDone = onDone) }
        }
    }

    @Test
    fun `chips read 12 hours, 1 day and 7 days`() {
        render(BackupIntervalPickerState(12))
        listOf("12 hours", "1 day", "7 days").forEach { composeTestRule.onNodeWithText(it).assertExists() }
    }

    @Test
    fun `nothing is reported until Done, which reports the hours once`() {
        val done = mutableListOf<Int>()
        render(BackupIntervalPickerState(12)) { done += it }
        composeTestRule.onNodeWithText("7 days").performClick()
        assertEquals(emptyList<Int>(), done)
        composeTestRule.onNodeWithText("Every 7 days").assertExists()
        composeTestRule.onNodeWithText("Done").performClick()
        assertEquals(listOf(168), done)
    }

    @Test
    fun `zero shows an error and Done is off`() {
        val state = BackupIntervalPickerState(12).apply { hours = 0 }
        render(state)
        composeTestRule.onNodeWithText("Choose at least 1 hour.").assertExists()
        composeTestRule.onNodeWithText("Done").assertIsNotEnabled()
    }

    @Test
    fun `more than 30 days shows an error and Done is off`() {
        val state = BackupIntervalPickerState(720).apply { hours = 1 }
        render(state)
        composeTestRule.onNodeWithText("Choose 30 days or less.").assertExists()
        composeTestRule.onNodeWithText("Done").assertIsNotEnabled()
    }

    @Test
    fun `a valid custom value can be saved`() {
        val state = BackupIntervalPickerState(54)
        render(state)
        composeTestRule.onNodeWithText("Every 2 days 6 hours").assertExists()
        composeTestRule.onNodeWithText("Done").assertIsEnabled()
    }
}
