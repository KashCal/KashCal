package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Pins the contract [org.onekash.kashcal.ui.screens.settings.AlertPickerSheet] relies on to keep
 * the current setting: when [WheelDurationPicker] opens on a negative seed and the user taps Done
 * without dialing, it hands that exact seed back, neither coerced to 0 nor decomposed.
 *
 * AlertPickerSheet seeds the sentinel `WHEEL_KEEP_CURRENT` (Int.MIN_VALUE) unless the current
 * value is a custom one the wheel can represent: so for None, a preset, or a custom value that
 * isn't positive, isn't on the 5-minute grid or runs past 30 days. It reads the sentinel handed
 * back as "keep currentValue". If the wheel clamped an untouched negative seed to 0, Done without
 * scrolling would silently reset the alert.
 *
 * Runs under Robolectric; run in isolation, since Robolectric runs of more than one class hit a
 * native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class WheelDurationPickerContractTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `untouched negative seed is handed back unchanged on Done`() {
        Locale.setDefault(Locale.US)
        val seed = Int.MIN_VALUE
        var committed: Int? = null
        composeTestRule.setContent {
            MaterialTheme {
                WheelDurationPicker(
                    selectedMinutes = seed,
                    isAllDay = true,
                    use24Hour = false,
                    presets = emptyList(),
                    onDurationSelected = { committed = it },
                    onDismiss = {},
                )
            }
        }
        // Tap Done without touching any wheel.
        composeTestRule.onNodeWithText("Done").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            "an untouched negative seed must be returned unchanged (AlertPickerSheet's " +
                "keep-current sentinel depends on this)",
            seed,
            committed,
        )
    }
}
