package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Robolectric copies of two chip-detour cases for the self-echo guard in [RecurrencePickerRow].
 *
 * The guard (`lastEmitted` checked in a LaunchedEffect) keeps a large inbound INTERVAL across a
 * Custom, preset, Custom chip detour: it recognizes the parent echoing the picker's own emission
 * and skips rebuilding the selections. The instrumentation test `RecurrencePickerComposeTest`
 * covers the same cases on a device, but it doesn't run in the PR-gated `testDebugUnitTest`
 * sweep; this class does, so a regression fails before merge.
 *
 * Run the class in isolation, since Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class RecurrencePickerChipDetourComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Sun 2025-01-05 00:00 UTC. Chip labels are locale-sensitive, so the locale is pinned.
    private val mondayJan5Millis = 1736035200000L

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

    @Test
    fun chipDetour_preservesInterval200_throughWeeklyThenCustom() {
        // The parent stores each emission verbatim in observed state, so the picker recomposes
        // with its own echo. Tapping Weekly emits a preset (interval 1); tapping Custom again
        // must not reset the stored interval: the self-echo guard skips the rebuild, so
        // INTERVAL=200 survives. Without the guard the final emission drops to INTERVAL=1.
        var emitted: String? = "FREQ=WEEKLY;INTERVAL=200;BYDAY=MO"
        composeTestRule.setContent {
            MaterialTheme {
                var rrule by remember { mutableStateOf<String?>("FREQ=WEEKLY;INTERVAL=200;BYDAY=MO") }
                RecurrencePickerRow(
                    selectedRrule = rrule,
                    startDateMillis = mondayJan5Millis,
                    isExpanded = true,
                    onToggle = {},
                    onSelect = { rrule = it; emitted = it },
                )
            }
        }

        composeTestRule.onNodeWithText("Weekly").performClick()
        composeTestRule.onNodeWithText("Custom").performClick()

        composeTestRule.runOnIdle {
            assertTrue(
                "expected INTERVAL=200 preserved across Custom->Weekly->Custom, got $emitted",
                emitted!!.contains("INTERVAL=200"),
            )
        }
    }

    @Test
    fun chipDetour_losesInterval200_whenParentNormalizesEmittedRrule() {
        // The self-echo comparison is byte equality. A parent that normalizes the emission (here
        // it appends a trailing space) breaks that equality, takes the external-reset path and,
        // by design, loses the large interval. If the comparison becomes tolerant, this test
        // fails so the behavior change is seen in review.
        var emitted: String? = "FREQ=WEEKLY;INTERVAL=200;BYDAY=MO"
        composeTestRule.setContent {
            MaterialTheme {
                var rrule by remember { mutableStateOf<String?>("FREQ=WEEKLY;INTERVAL=200;BYDAY=MO") }
                RecurrencePickerRow(
                    selectedRrule = rrule,
                    startDateMillis = mondayJan5Millis,
                    isExpanded = true,
                    onToggle = {},
                    onSelect = { next -> val norm = next?.let { "$it " }; rrule = norm; emitted = norm },
                )
            }
        }

        composeTestRule.onNodeWithText("Weekly").performClick()
        composeTestRule.onNodeWithText("Custom").performClick()

        composeTestRule.runOnIdle {
            assertTrue(
                "contract: byte-equality required; normalizing parent must lose INTERVAL, got $emitted",
                !emitted!!.contains("INTERVAL=200"),
            )
        }
    }
}
