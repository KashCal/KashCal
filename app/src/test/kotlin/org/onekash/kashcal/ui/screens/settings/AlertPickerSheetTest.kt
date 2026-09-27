package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.shared.ReminderOption
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Tests [AlertPickerSheet], the timed and all-day default-alert picker, and its Done commit rule
 * [committedAlertValue].
 *
 * All-day presets (900 = "9 AM the day before") are 9 AM offsets, not raw durations, so they must
 * never seed the duration wheel: it would decompose them into a wrong day count. The picker
 * always opens on the preset list, and Custom opened from a preset seeds the wheel neutrally.
 * The wheel commits only on Done, never mid-scroll, and an un-dialed Done keeps the current value.
 *
 * Runs under Robolectric; run the class in isolation given the repo's multi-class
 * native-crash flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class AlertPickerSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val allDayOptions = listOf(
        ReminderOption("None", org.onekash.kashcal.ui.shared.REMINDER_OFF),
        ReminderOption("9 AM day of event", -540),
        ReminderOption("1 day before", 900),
        ReminderOption("1 week before", 9540),
    )

    @OptIn(ExperimentalMaterial3Api::class)
    private fun renderAllDay(currentValue: Int, onSelect: (Int) -> Unit = {}) {
        Locale.setDefault(Locale.US)
        composeTestRule.setContent {
            MaterialTheme {
                AlertPickerSheet(
                    sheetState = rememberModalBottomSheetState(),
                    title = "All-day event alert",
                    options = allDayOptions,
                    currentValue = currentValue,
                    isAllDay = true,
                    use24Hour = false,
                    onSelect = onSelect,
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun `all-day default value shows the preset list, not the wheel`() {
        // 900 = "9 AM the day before" (a preset offset). It must not auto-open the duration
        // wheel, which would decompose the 9 AM offset into a wrong day count.
        renderAllDay(currentValue = 900)
        composeTestRule.onNodeWithText("1 day before").assertIsDisplayed()
        composeTestRule.onNodeWithText("Custom").assertIsDisplayed()
    }

    @Test
    fun `all-day preset selection invokes callback with the exact 9 AM offset`() {
        var picked: Int? = null
        renderAllDay(currentValue = 900, onSelect = { picked = it })
        composeTestRule.onNodeWithText("1 week before").performClick()
        // Preset offset preserved exactly (9540 = 9 AM one week before), not re-derived.
        org.junit.Assert.assertEquals(9540, picked)
    }

    @Test
    fun `all-day Custom opens the wheel`() {
        renderAllDay(currentValue = 900)
        composeTestRule.onNodeWithText("Custom").performClick()
        composeTestRule.waitForIdle()
        // The wheel's Done button shows.
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun `Custom wheel has a Back control that returns to the preset list`() {
        renderAllDay(currentValue = 900)
        composeTestRule.onNodeWithText("Custom").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Back").performClick()
        composeTestRule.waitForIdle()
        // Back on the preset list.
        composeTestRule.onNodeWithText("1 day before").assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun renderTimed(
        currentValue: Int,
        onSelect: (Int) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        Locale.setDefault(Locale.US)
        val timedOptions = listOf(
            ReminderOption("None", org.onekash.kashcal.ui.shared.REMINDER_OFF),
            ReminderOption("15 minutes before", 15),
            ReminderOption("1 hour before", 60),
        )
        composeTestRule.setContent {
            MaterialTheme {
                AlertPickerSheet(
                    sheetState = rememberModalBottomSheetState(),
                    title = "Timed event alert",
                    options = timedOptions,
                    currentValue = currentValue,
                    isAllDay = false,
                    use24Hour = false,
                    onSelect = onSelect,
                    onDismiss = onDismiss,
                )
            }
        }
    }

    @Test
    fun `saved custom timed value shows preset list with a selected Custom row, not the wheel`() {
        // 45 is not a preset. It must not auto-open the wheel and trap the user; the preset
        // list stays reachable with Custom marked selected.
        renderTimed(currentValue = 45)
        composeTestRule.onNodeWithText("15 minutes before").assertIsDisplayed()
        // Custom row reflects the saved custom duration.
        composeTestRule.onNodeWithText("Custom (45 minutes before)").assertIsDisplayed()
    }

    @Test
    fun `saved custom timed value can be switched back to a preset`() {
        var picked: Int? = null
        renderTimed(currentValue = 45, onSelect = { picked = it })
        composeTestRule.onNodeWithText("1 hour before").performClick()
        assertEquals(60, picked)
    }

    // ==================== Custom wheel: commit only on Done, never mid-scroll ====================

    @Test
    fun `scrolling the custom wheel does not commit or dismiss before Done`() {
        // The wheel emits onDurationSelected on every change of its centered item, mid-fling
        // included. Wired to commit and dismiss, it would close the sheet on the first scroll
        // tick before the user reaches their value. Scrolling must not commit; only Done does.
        var selectCount = 0
        var dismissCount = 0
        renderTimed(currentValue = 15, onSelect = { selectCount++ }, onDismiss = { dismissCount++ })
        composeTestRule.onNodeWithText("Custom").performClick()
        composeTestRule.waitForIdle()

        // Scroll a wheel (the picker exposes each wheel via cd_wheel_picker semantics).
        composeTestRule.onAllNodes(hasContentDescription("Wheel picker with 24 options"))
            .onFirst()
            .performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        // Still on the wheel, nothing committed or dismissed by the scroll alone.
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
        assertEquals("scroll must not commit a value", 0, selectCount)
        assertEquals("scroll must not dismiss the sheet", 0, dismissCount)
    }

    @Test
    fun `Done commits the scrolled custom value once and dismisses`() {
        // Asserts the committed value, not only the call count: Done must commit what the
        // wheel scrolled to, not the neutral seed (0) or a stale staged value. If Done
        // committed the seed, count-only assertions would still pass but this would not.
        var picked: Int? = null
        var dismissCount = 0
        renderTimed(currentValue = 15, onSelect = { picked = it }, onDismiss = { dismissCount++ })
        composeTestRule.onNodeWithText("Custom").performClick()
        composeTestRule.waitForIdle()

        // Scroll the minute wheel off its neutral start, then commit.
        composeTestRule.onAllNodes(hasContentDescription("Wheel picker with 12 options"))
            .onFirst()
            .performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Done").performClick()
        composeTestRule.waitForIdle()

        assertEquals("Done dismisses the sheet exactly once", 1, dismissCount)
        // currentValue = 15 is a preset, so the wheel seeds the keep-current sentinel and
        // opens neutral (0). A working scroll commits the dialed duration; a swipe that
        // silently did nothing would fall back to the keep-current 15. Asserting != 15 and
        // > 0 tells the two apart; "> 0" alone would pass on the fallback too.
        assertNotNull("Done must commit a value", picked)
        assertTrue(
            "committed value must be the dialed duration, not the keep-current fallback (15)",
            picked!! > 0 && picked != 15,
        )
    }

    @Test
    fun `timed preset preserved when Custom opened and Done tapped without scrolling`() {
        // Opening Custom from a preset seeds the wheel neutrally (0d 0h 0m). Tapping Done
        // without scrolling must keep the existing value, not reset it to 0 ("at time of
        // event").
        var picked: Int? = null
        renderTimed(currentValue = 15, onSelect = { picked = it })
        composeTestRule.onNodeWithText("Custom").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Done").performClick()
        composeTestRule.waitForIdle()
        assertEquals("un-scrolled Done must preserve the existing preset", 15, picked)
    }

    @Test
    fun `all-day preset preserved when Custom opened and Done tapped without scrolling`() {
        // 900 = "9 AM the day before", a preset, so the wheel opens neutral. Un-scrolled Done
        // must keep 900, not drop it to None.
        var picked: Int? = null
        renderAllDay(currentValue = 900, onSelect = { picked = it })
        composeTestRule.onNodeWithText("Custom").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Done").performClick()
        composeTestRule.waitForIdle()
        assertEquals("un-scrolled Done must preserve the all-day preset offset", 900, picked)
    }

    @Test
    fun `all-day non-preset negative offset preserved on un-scrolled Done`() {
        // An all-day offset that isn't a preset, such as a synced or imported -600, shows as a
        // selected "Custom (...)" row. The wheel can't represent a negative offset, so opening
        // it and tapping Done without scrolling must keep -600, not drop to None.
        var picked: Int? = null
        renderAllDay(currentValue = -600, onSelect = { picked = it })
        composeTestRule.onNodeWithText("Custom", substring = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Done").performClick()
        composeTestRule.waitForIdle()
        assertEquals("un-scrolled Done must preserve a non-representable offset", -600, picked)
    }

    @Test
    fun `off-grid positive custom preserved on un-scrolled Done, not rounded to a wheel step`() {
        // 23 min is a custom duration off the 5-min wheel grid, as another client can sync.
        // Seeded into the wheel it would snap to 25, so it seeds the keep-current sentinel:
        // an un-scrolled Done must preserve 23, not silently commit 25. Only a dial changes
        // the value.
        var picked: Int? = null
        renderTimed(currentValue = 23, onSelect = { picked = it })
        composeTestRule.onNodeWithText("Custom", substring = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Done").performClick()
        composeTestRule.waitForIdle()
        assertEquals("un-scrolled Done must preserve the off-grid custom, not round it", 23, picked)
    }

    @Test
    fun `on-grid positive custom is editable and preserved on un-scrolled Done`() {
        // 45 min is on the 5-min grid, so it seeds the wheel editable; an un-scrolled Done
        // must still commit 45 (the round-trip is lossless).
        var picked: Int? = null
        renderTimed(currentValue = 45, onSelect = { picked = it })
        composeTestRule.onNodeWithText("Custom", substring = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Done").performClick()
        composeTestRule.waitForIdle()
        assertEquals("un-scrolled Done must preserve an on-grid custom", 45, picked)
    }

    @Test
    fun `Back from the custom wheel never commits a value`() {
        // Back is the non-committing escape hatch: it returns to the preset list
        // without writing anything, so a user who opened Custom by mistake loses nothing.
        var selectCount = 0
        var dismissCount = 0
        renderTimed(currentValue = 15, onSelect = { selectCount++ }, onDismiss = { dismissCount++ })
        composeTestRule.onNodeWithText("Custom").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Back").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("15 minutes before").assertIsDisplayed()
        assertEquals("Back must not commit", 0, selectCount)
        assertEquals("Back must not dismiss the sheet", 0, dismissCount)
    }

    // ==================== committedAlertValue (Done commit logic) ====================
    // The all-day neutral-to-None and keep-current branches of the Done commit are hard to
    // reach reliably through the wheel's gestures, so they're pinned on the pure function.

    @Test
    fun `committedAlertValue keeps current when the wheel was never dialed`() {
        // Timed preset preserved.
        assertEquals(15, committedAlertValue(WHEEL_KEEP_CURRENT, currentValue = 15, isAllDay = false))
        // All-day non-representable offset preserved (e.g. 9 AM day of = -540).
        assertEquals(-540, committedAlertValue(WHEEL_KEEP_CURRENT, currentValue = -540, isAllDay = true))
    }

    @Test
    fun `committedAlertValue commits None for an all-day value dialed to neutral`() {
        // A midnight (<= 0) all-day alarm is meaningless -> None.
        assertEquals(
            org.onekash.kashcal.ui.shared.REMINDER_OFF,
            committedAlertValue(staged = 0, currentValue = 900, isAllDay = true),
        )
    }

    @Test
    fun `committedAlertValue keeps timed zero as at-time-of-event`() {
        // Timed 0 is the valid "at time of event" value, not converted to None.
        assertEquals(0, committedAlertValue(staged = 0, currentValue = 60, isAllDay = false))
    }

    @Test
    fun `committedAlertValue commits a dialed positive duration verbatim`() {
        assertEquals(45, committedAlertValue(staged = 45, currentValue = 15, isAllDay = false))
        assertEquals(2340, committedAlertValue(staged = 2340, currentValue = 900, isAllDay = true))
    }
}
