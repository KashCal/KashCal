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
 * Compose UI tests for [RecurrencePickerRow].
 *
 * They drive the picker's public composable, so the composition lifecycle is the event form's.
 * Most tests echo each emission back as selectedRrule, as the form does, and check that a rule
 * opened with INTERVAL > 1 or an explicit WKST keeps it through stepper nudges and chip
 * detours. Others cover the chip a rule opens on (Custom or Weekly), the stepper's 99 ceiling
 * and a '-' step from 200, the string-equality self-echo contract, and when the device's week
 * start is written as WKST.
 */
@RunWith(AndroidJUnit4::class)
class RecurrencePickerComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val mondayJanFifth2026Millis = 1736035200000L // Sun 2025-01-05 00:00 UTC

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

        // The Custom builder renders only when CUSTOM is selected.
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

        // INTERVAL=4 opens on CUSTOM, so the builder is visible. Nudge the stepper up to 5
        // and back; the final emission is INTERVAL=4.
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
        // The parent's selectedRrule is a mutableStateOf so the picker recomposes when
        // onSelect fires. With a fixed parameter the holder's stored interval=200 would pass
        // trivially; the loss shows only when the parent echoes an emitted RRULE back.
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

        // INTERVAL=200 opens on Custom. Tapping Weekly detours through a preset (the parent
        // gets FREQ=WEEKLY, which re-parses as interval=1), then Custom again. The picker
        // must recognize the echo as its own emission and not reset the interval to 1.
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

        // At interval=200, above the 99 ceiling, '+' is disabled while '-' stays enabled so
        // the user can step back down.
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
        // Pins the verbatim-storage contract: RecurrencePickerRow's self-echo check compares
        // selectedRrule to lastEmitted by string equality. A parent that normalizes the rule
        // (trimming, reordering BYDAY, dropping redundant tokens) breaks that equality and
        // fires the external-reset path, so the chip detour loses the interval.
        //
        // The parent here appends a trailing space, and INTERVAL=200 is lost. A tolerant
        // comparison would fail this test; that change should be a deliberate one.
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
        // A synced rule with an explicit WKST=SU. Tapping Weekly emits a preset without WKST,
        // the parent recomposes with it, then Custom is tapped again. The self-echo check
        // keeps the holder's parsedWkst=SU, so the final emission has WKST=SU, not the
        // device's week start. Otherwise a no-op edit silently shifts the occurrences of a
        // biweekly multi-day rule, since WKST decides which week a Sunday falls in
        // (RFC 5545 §3.3.10).
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
        // A loaded biweekly multi-day rule without WKST, on a Sunday-first-day device, must not
        // gain WKST=SU on save: RFC 5545 §3.3.10 defaults WKST to MO, so injecting SU shifts
        // occurrences. The picker passes deviceWkst = null when isNewRule is false. The
        // no-op save here is a chip re-tap, which calls notifyChange without changing
        // anything else.
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

        // Re-tapping the selected Custom chip fires notifyChange, which emits the loaded state
        // back through the holder.
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
        // The user opens a loaded rule without WKST, taps Never to clear the recurrence, then
        // rebuilds a biweekly multi-day rule in the same sheet. The rebuilt rule counts as
        // new, so on a Sunday-first-day device it emits WKST=SU, as a new event would. The
        // first composition set isNewRule = false (selectedRrule was the loaded rule); the
        // Never tap emits null, which sets isNewRule to true, so the Custom tap's emission
        // gets the device's week start.
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

        // Interval 1 opens on the Weekly preset, which hides the Custom builder and its
        // "Repeat every"; only the Weekly chip's presence is asserted.
        composeTestRule.onNodeWithText("Weekly").assertIsDisplayed()
    }
}
