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
 * Pins the cross-component contract that [org.onekash.kashcal.ui.screens.settings.AlertPickerSheet]
 * relies on for its "keep the current setting" behavior: when the wheel is opened on a
 * negative seed and the user taps Done WITHOUT dialing, the wheel hands that exact seed
 * back — it does not coerce it to 0 or decompose it.
 *
 * AlertPickerSheet seeds a negative sentinel for values the wheel can't represent (all-day
 * 9-AM offsets, off-grid customs) and treats "sentinel handed back" as "user didn't dial,
 * preserve currentValue". If a future change to WheelDurationPicker clamps an untouched
 * negative seed to 0, that preserve path would silently break and un-scrolled Done would
 * reset the alert. This test fails first if that contract regresses.
 *
 * Runs under Robolectric; run in isolation given the repo's multi-class native-crash flake.
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
