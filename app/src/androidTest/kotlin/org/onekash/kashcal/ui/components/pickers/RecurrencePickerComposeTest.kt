package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for RecurrencePickerRow.
 *
 * Drives the picker through its public composable surface so the same
 * composition lifecycle that runs in the event form runs here. The
 * round-trip case is the user-observable contract for the data-loss bug fix:
 * opening an event with INTERVAL > 1 and saving without further interaction
 * must emit the same RRULE the form was opened with.
 */
@RunWith(AndroidJUnit4::class)
class RecurrencePickerComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val mondayJanFifth2026Millis = 1736035200000L // Mon 2026-01-05 00:00 UTC

    @Test
    fun rowOpens_withCustomChipState_whenInboundIntervalIsFour() {
        composeTestRule.setContent {
            MaterialTheme {
                var rrule by remember { mutableStateOf<String?>("FREQ=WEEKLY;INTERVAL=4;BYDAY=MO") }
                RecurrencePickerRow(
                    selectedRrule = rrule,
                    startDateMillis = mondayJanFifth2026Millis,
                    isExpanded = true,
                    onToggle = {},
                    onSelect = { rrule = it },
                )
            }
        }

        // Custom builder is rendered only when CUSTOM is selected.
        composeTestRule.onNodeWithText("Repeat every").assertIsDisplayed()
        composeTestRule.onNodeWithText("4").assertIsDisplayed()
        composeTestRule.onNodeWithText("Week").assertIsDisplayed()
    }

    @Test
    fun roundTrip_preservesIntervalFour_whenUserNudgesStepperUpThenDown() {
        val emitted = mutableStateOf<String?>("FREQ=WEEKLY;INTERVAL=4;BYDAY=MO")
        composeTestRule.setContent {
            MaterialTheme {
                RecurrencePickerRow(
                    selectedRrule = emitted.value,
                    startDateMillis = mondayJanFifth2026Millis,
                    isExpanded = true,
                    onToggle = {},
                    onSelect = { emitted.value = it },
                )
            }
        }

        // Open Custom builder is already visible (INTERVAL=4 lands on CUSTOM).
        // Nudge stepper up to 5, then back to 4 — final emission must be INTERVAL=4.
        composeTestRule.onNodeWithContentDescription("Increase interval").performClick()
        composeTestRule.onNodeWithContentDescription("Decrease interval").performClick()

        composeTestRule.runOnIdle {
            assertTrue(
                "expected emitted RRULE to retain INTERVAL=4 after up/down nudge, got ${emitted.value}",
                emitted.value!!.contains("INTERVAL=4"),
            )
        }
    }

    @Test
    fun chipDetour_preservesInterval200_throughChipClicks() {
        // The parent's selectedRrule is observed via mutableStateOf so the
        // composable actually recomposes when onSelect fires — without this,
        // the test passes for the wrong reason: the parameter never updates,
        // remember(parsed) never re-keys, and the holder's stored interval=200
        // sails through trivially. The real bug only surfaces when the parent
        // round-trips an emitted RRULE back into the picker.
        val emitted = mutableStateOf<String?>("FREQ=WEEKLY;INTERVAL=200;BYDAY=MO")
        composeTestRule.setContent {
            MaterialTheme {
                RecurrencePickerRow(
                    selectedRrule = emitted.value,
                    startDateMillis = mondayJanFifth2026Millis,
                    isExpanded = true,
                    onToggle = {},
                    onSelect = { emitted.value = it },
                )
            }
        }

        // Inbound INTERVAL=200 → Custom selected. Tap Weekly chip to detour
        // through a preset (parent receives FREQ=WEEKLY, recomposition
        // re-parses it as interval=1), then tap Custom again. The picker must
        // recognize the echo as its own emission and not reset interval to 1.
        composeTestRule.onNodeWithText("Weekly").performClick()
        composeTestRule.onNodeWithText("Custom").performClick()

        composeTestRule.runOnIdle {
            assertTrue(
                "expected INTERVAL=200 preserved across Custom→Weekly→Custom, got ${emitted.value}",
                emitted.value!!.contains("INTERVAL=200"),
            )
        }
    }

    @Test
    fun stepperPlusButton_isDisabled_whenIntervalAt200() {
        composeTestRule.setContent {
            MaterialTheme {
                var rrule by remember { mutableStateOf<String?>("FREQ=WEEKLY;INTERVAL=200;BYDAY=MO") }
                RecurrencePickerRow(
                    selectedRrule = rrule,
                    startDateMillis = mondayJanFifth2026Millis,
                    isExpanded = true,
                    onToggle = {},
                    onSelect = { rrule = it },
                )
            }
        }

        // At interval=200 (above the 99 ceiling), '+' must be disabled while
        // '-' remains enabled so the user can decrement back into range.
        composeTestRule.onNodeWithContentDescription("Increase interval").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Decrease interval").assertIsEnabled()
    }

    @Test
    fun stepperMinusButton_decrementsFrom200To199() {
        val emitted = mutableStateOf<String?>("FREQ=WEEKLY;INTERVAL=200;BYDAY=MO")
        composeTestRule.setContent {
            MaterialTheme {
                RecurrencePickerRow(
                    selectedRrule = emitted.value,
                    startDateMillis = mondayJanFifth2026Millis,
                    isExpanded = true,
                    onToggle = {},
                    onSelect = { emitted.value = it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Decrease interval").performClick()

        composeTestRule.runOnIdle {
            assertTrue(
                "expected INTERVAL=199 after one '-' tap from 200, got ${emitted.value}",
                emitted.value!!.contains("INTERVAL=199"),
            )
        }
    }

    @Test
    fun chipDetour_losesInterval200_whenParentNormalizesEmittedRrule() {
        // Pins the verbatim-storage contract: the self-echo guard inside
        // RecurrencePickerRow compares selectedRrule to lastEmitted by
        // byte-equality. A parent that normalizes on the way in — trimming,
        // reordering BYDAY, dropping redundant tokens — breaks that equality,
        // fires the external-reset path, and silently regresses the chip-
        // detour fix.
        //
        // This test simulates a normalizing parent (appends a trailing
        // space) and asserts INTERVAL=200 is LOST. If a future change
        // replaces byte-equality with a tolerant comparison, this test
        // will fail — that's a deliberate behavior change worth surfacing
        // at review time, not silently shipping.
        val emitted = mutableStateOf<String?>("FREQ=WEEKLY;INTERVAL=200;BYDAY=MO")
        composeTestRule.setContent {
            MaterialTheme {
                RecurrencePickerRow(
                    selectedRrule = emitted.value,
                    startDateMillis = mondayJanFifth2026Millis,
                    isExpanded = true,
                    onToggle = {},
                    onSelect = { emitted.value = it?.let { rrule -> "$rrule " } },
                )
            }
        }

        composeTestRule.onNodeWithText("Weekly").performClick()
        composeTestRule.onNodeWithText("Custom").performClick()

        composeTestRule.runOnIdle {
            assertTrue(
                "contract: byte-equality required; normalizing parent must lose INTERVAL, got ${emitted.value}",
                !emitted.value!!.contains("INTERVAL=200"),
            )
        }
    }

    @Test
    fun chipDetour_preservesWkstSunday_throughChipClicks() {
        // CalDAV-pulled rule with explicit WKST=SU. Tapping Weekly chip emits
        // a clean preset (no WKST), parent recomposes with that, then user
        // taps Custom again. The picker's self-echo guard must keep its
        // stored parsedWkst=SU so the final emission still has WKST=SU —
        // not WKST=MO from a Monday-week device default. Without preservation,
        // a no-op edit silently shifts occurrences for biweekly multi-day
        // rules where Sunday and Monday land in different ISO weeks.
        val emitted = mutableStateOf<String?>("FREQ=WEEKLY;INTERVAL=2;BYDAY=SA,SU;WKST=SU")
        composeTestRule.setContent {
            MaterialTheme {
                RecurrencePickerRow(
                    selectedRrule = emitted.value,
                    startDateMillis = mondayJanFifth2026Millis,
                    isExpanded = true,
                    onToggle = {},
                    onSelect = { emitted.value = it },
                )
            }
        }

        composeTestRule.onNodeWithText("Weekly").performClick()
        composeTestRule.onNodeWithText("Custom").performClick()

        composeTestRule.runOnIdle {
            assertTrue(
                "expected WKST=SU preserved across Custom→Weekly→Custom, got ${emitted.value}",
                emitted.value!!.contains("WKST=SU"),
            )
        }
    }

    @Test
    fun loadedRuleWithoutWkst_emitsNoWkstOnNoOpSave_evenOnSundayWeekDevice() {
        // Headline bug: opening a CalDAV-pulled biweekly multi-day rule that
        // omitted WKST on a Sunday-first-day device used to silently inject
        // WKST=SU on save, shifting RFC §3.3.10 default-MO occurrence
        // anchoring. The fix: picker passes deviceWkst=null when isNewRule
        // is false. The no-op "save" here is a chip re-tap that triggers
        // notifyChange without otherwise changing user intent.
        val emitted = mutableStateOf<String?>("FREQ=WEEKLY;INTERVAL=2;BYDAY=SA,SU")
        composeTestRule.setContent {
            MaterialTheme {
                RecurrencePickerRow(
                    selectedRrule = emitted.value,
                    startDateMillis = mondayJanFifth2026Millis,
                    isExpanded = true,
                    onToggle = {},
                    onSelect = { emitted.value = it },
                    firstDayOfWeek = java.util.Calendar.SUNDAY,
                )
            }
        }

        // Re-tap the already-selected Custom chip → notifyChange fires,
        // emitting the loaded state back through the holder. Must not
        // inject WKST=SU.
        composeTestRule.onNodeWithText("Custom").performClick()

        composeTestRule.runOnIdle {
            assertTrue(
                "loaded rule that omitted WKST must round-trip without injection, got ${emitted.value}",
                !emitted.value!!.contains("WKST="),
            )
        }
    }

    @Test
    fun clearAndRebuild_emitsDeviceWkst_evenWhenLoadedRuleOmittedWkst() {
        // Edit-then-clear-then-rebuild: user opens an event with a loaded rule
        // that omitted WKST, taps Never to clear the recurrence, then rebuilds
        // a biweekly multi-day rule in the same sheet. The rebuilt rule is
        // conceptually authored fresh, so on a Sunday-first-day device it must
        // emit WKST=SU — same as if the user had started from a brand-new
        // event. The first composition captured isNewRule=false (selectedRrule
        // was the loaded rule); the Never tap emits null, which flips
        // isNewRule to true so the subsequent Custom tap's emission picks up
        // the device wkst through the builder gate.
        val emitted = mutableStateOf<String?>("FREQ=WEEKLY;INTERVAL=2;BYDAY=SA,SU")
        composeTestRule.setContent {
            MaterialTheme {
                RecurrencePickerRow(
                    selectedRrule = emitted.value,
                    startDateMillis = mondayJanFifth2026Millis,
                    isExpanded = true,
                    onToggle = {},
                    onSelect = { emitted.value = it },
                    firstDayOfWeek = java.util.Calendar.SUNDAY,
                )
            }
        }

        composeTestRule.onNodeWithText("Never").performClick()
        composeTestRule.onNodeWithText("Custom").performClick()

        composeTestRule.runOnIdle {
            assertTrue(
                "rebuilt rule after clear must emit WKST=SU on Sunday-week device, got ${emitted.value}",
                emitted.value!!.contains("WKST=SU"),
            )
        }
    }

    @Test
    fun rowOpens_withWeeklyChipState_whenIntervalIsOne() {
        composeTestRule.setContent {
            MaterialTheme {
                RecurrencePickerRow(
                    selectedRrule = "FREQ=WEEKLY;BYDAY=MO",
                    startDateMillis = mondayJanFifth2026Millis,
                    isExpanded = true,
                    onToggle = {},
                    onSelect = {},
                )
            }
        }

        // Weekly preset selected → Custom builder hidden. "Repeat every" only
        // appears inside the Custom builder.
        composeTestRule.onNodeWithText("Weekly").assertIsDisplayed()
    }
}
