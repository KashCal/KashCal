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
 * Robolectric mirror of the self-echo / chip-detour guard in [RecurrencePickerRow].
 *
 * The guard (lastEmitted + LaunchedEffect discrimination) keeps a large inbound
 * INTERVAL alive across a Custom -> preset -> Custom chip detour by recognizing
 * the parent echoing our own emission and skipping the holder rebuild. That
 * behavior is exercised on-device by RecurrencePickerComposeTest, but those are
 * AndroidJUnit4 instrumentation tests that don't run in the PR-gated
 * testDebugUnitTest sweep. This class mirrors the two load-bearing cases under
 * Robolectric so a regression is caught pre-merge, not only on a device run.
 *
 * Run the class in isolation given the repo's known multi-class native-crash flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class RecurrencePickerChipDetourComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Mon 2026-01-05 00:00 UTC — chip labels are locale-sensitive, so pin the locale.
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
        // The parent stores each emission verbatim into an observed state, so the
        // picker actually recomposes with its own echo. Tapping Weekly emits a
        // clean preset (interval=1); tapping Custom again must NOT reset the stored
        // interval — the self-echo guard skips the holder rebuild, so INTERVAL=200
        // survives. Without the guard the final emission drops to INTERVAL=1.
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
        // Contract guard: the self-echo comparison is byte-equality. A parent that
        // normalizes on the way in (here, appends a trailing space) breaks that
        // equality, fires the external-reset path, and — by design — loses the
        // large interval. If a future change makes the comparison tolerant, this
        // test fails, surfacing the behavior change at review time.
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
