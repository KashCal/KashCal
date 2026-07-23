package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertAny
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAncestors
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.ui.appicon.AppIconPreset
import org.onekash.kashcal.ui.shared.REMINDER_OFF
import org.onekash.kashcal.ui.shared.ReminderOption
import org.onekash.kashcal.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Locale

/**
 * Accessibility contract for the single-select picker bottom sheets in Settings.
 *
 * Each option row must expose itself to TalkBack as a radio button carrying its
 * selected/unselected state, and the option list must be a selectable group so the
 * reader can convey "N of M" — replacing the earlier bare tappable row whose only
 * selection cue was a trailing checkmark. This pins that contract at the surface the
 * user navigates, across every distinct sheet composable (including both list bodies
 * of the two files that host two sheets each), so a dropped group modifier or a
 * missed row is caught rather than passing on code review alone.
 *
 * Runs under Robolectric; run the class in isolation given the repo's multi-class
 * native-crash flake. Each @Test renders exactly one sheet (fresh compose rule per
 * method) to keep within-class composition state lean.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class PickerSheetSelectableSemanticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var originalLocale: Locale? = null

    @Before
    fun pinLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        originalLocale?.let { Locale.setDefault(it) }
    }

    private fun isRadioButton() =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    /**
     * Shared invariant for every picker: exactly [radioCount] radio-button rows exist;
     * the [selectedLabel] row is a selected, RadioButton-role selectable inside a
     * SelectableGroup; the [unselectedLabel] row is an unselected selectable.
     */
    private fun assertRadioGroupSemantics(
        selectedLabel: String,
        unselectedLabel: String,
        radioCount: Int,
    ) {
        with(composeTestRule) {
            // Only the option rows are radios — no toggle/footer/nav row leaked in.
            onAllNodes(isRadioButton()).assertCountEquals(radioCount)

            onNodeWithText(selectedLabel)
                .assert(isSelectable())
                .assert(isRadioButton())
                .assertIsSelected()

            // The list body is a selectable group so TalkBack conveys "N of M".
            onNodeWithText(selectedLabel).onAncestors()
                .assertAny(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))

            onNodeWithText(unselectedLabel)
                .assert(isSelectable())
                .assertIsNotSelected()
        }
    }

    // ==================== Time format ====================

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `time format rows are grouped radio buttons and tap selects`() {
        var picked: String? = null
        composeTestRule.setContent {
            MaterialTheme {
                TimeFormatSheet(
                    sheetState = rememberModalBottomSheetState(),
                    currentFormat = KashCalDataStore.TIME_FORMAT_12H,
                    onFormatSelect = { picked = it },
                    onDismiss = {},
                )
            }
        }
        assertRadioGroupSemantics(selectedLabel = "12-hour", unselectedLabel = "24-hour", radioCount = 3)
        composeTestRule.onNodeWithText("24-hour").performClick()
        assertEquals(KashCalDataStore.TIME_FORMAT_24H, picked)
    }

    // ==================== First day of week ====================

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `first day of week rows are grouped radio buttons and tap selects`() {
        var picked: Int? = null
        composeTestRule.setContent {
            MaterialTheme {
                FirstDayOfWeekSheet(
                    sheetState = rememberModalBottomSheetState(),
                    currentValue = Calendar.MONDAY,
                    onSelect = { picked = it },
                    onDismiss = {},
                )
            }
        }
        assertRadioGroupSemantics(selectedLabel = "Monday", unselectedLabel = "Saturday", radioCount = 4)
        composeTestRule.onNodeWithText("Saturday").performClick()
        assertEquals(Calendar.SATURDAY, picked)
    }

    // ==================== Theme ====================

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `theme rows are grouped radio buttons and tap selects`() {
        var picked: ThemeMode? = null
        composeTestRule.setContent {
            MaterialTheme {
                ThemeSheet(
                    sheetState = rememberModalBottomSheetState(),
                    currentMode = ThemeMode.LIGHT,
                    onModeSelect = { picked = it },
                    onDismiss = {},
                )
            }
        }
        assertRadioGroupSemantics(selectedLabel = "Light", unselectedLabel = "Dark", radioCount = 3)
        composeTestRule.onNodeWithText("Dark").performClick()
        assertEquals(ThemeMode.DARK, picked)
    }

    // ==================== App icon ====================

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `app icon rows are grouped radio buttons, footer stays a button, tap selects`() {
        var picked: AppIconPreset? = null
        composeTestRule.setContent {
            MaterialTheme {
                AppIconSheet(
                    sheetState = rememberModalBottomSheetState(),
                    currentPreset = AppIconPreset.DEFAULT,
                    onPresetSelect = { picked = it },
                    onSupportClick = {},
                    onDismiss = {},
                )
            }
        }
        // radioCount = 3 asserts the "Support KashCal" footer (a Role.Button action row)
        // and the info note were NOT converted to radios.
        assertRadioGroupSemantics(selectedLabel = "Default", unselectedLabel = "Supporter", radioCount = 3)
        composeTestRule.onNodeWithText("Support KashCal")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        composeTestRule.onNodeWithText("Supporter").performClick()
        assertEquals(AppIconPreset.SUPPORTER, picked)
    }

    // ==================== Sync frequency ====================

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `sync frequency rows are grouped radio buttons and tap selects`() {
        var picked: Long? = null
        composeTestRule.setContent {
            MaterialTheme {
                SyncFrequencySheet(
                    sheetState = rememberModalBottomSheetState(),
                    currentIntervalMs = 15 * 60 * 1000L,
                    onSelect = { picked = it },
                    onDismiss = {},
                )
            }
        }
        assertRadioGroupSemantics(selectedLabel = "15 minutes", unselectedLabel = "Manual only", radioCount = 7)
        composeTestRule.onNodeWithText("Manual only").performClick()
        assertEquals(Long.MAX_VALUE, picked)
    }

    // ==================== Sync lookback ====================

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `sync lookback rows are grouped radio buttons and tap selects`() {
        var picked: Int? = null
        composeTestRule.setContent {
            MaterialTheme {
                SyncLookbackSheet(
                    sheetState = rememberModalBottomSheetState(),
                    currentDays = 90,
                    onSelect = { picked = it },
                    onDismiss = {},
                )
            }
        }
        assertRadioGroupSemantics(selectedLabel = "3 months", unselectedLabel = "All events", radioCount = 6)
        composeTestRule.onNodeWithText("All events").performClick()
        assertEquals(Int.MAX_VALUE, picked)
    }

    // ==================== Default event length (DisplayOptionsSheet) ====================

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `event duration rows are grouped radio buttons and tap selects`() {
        var picked: Int? = null
        composeTestRule.setContent {
            MaterialTheme {
                EventDurationSheet(
                    sheetState = rememberModalBottomSheetState(),
                    defaultEventDuration = 15,
                    onEventDurationChange = { picked = it },
                    onDismiss = {},
                )
            }
        }
        assertRadioGroupSemantics(selectedLabel = "15 minutes", unselectedLabel = "1 hour", radioCount = 4)
        composeTestRule.onNodeWithText("1 hour").performClick()
        assertEquals(60, picked)
    }

    // ==================== Widget event limit (DisplayOptionsSheet, second Column) ====================

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `widget event limit rows are grouped radio buttons and tap selects`() {
        var picked: Int? = null
        composeTestRule.setContent {
            MaterialTheme {
                WidgetEventLimitSheet(
                    sheetState = rememberModalBottomSheetState(),
                    currentLimit = 5,
                    onLimitChange = { picked = it },
                    onDismiss = {},
                )
            }
        }
        assertRadioGroupSemantics(selectedLabel = "5 per day", unselectedLabel = "8 per day", radioCount = 5)
        composeTestRule.onNodeWithText("8 per day").performClick()
        assertEquals(8, picked)
    }

    // ==================== Default alert: AlertPickerSheet ====================

    private val timedOptions = listOf(
        ReminderOption("None", REMINDER_OFF),
        ReminderOption("15 minutes before", 15),
        ReminderOption("1 hour before", 60),
    )

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `alert picker preset rows and custom row are grouped radio buttons, tap on a preset selects`() {
        var picked: Int? = null
        composeTestRule.setContent {
            MaterialTheme {
                AlertPickerSheet(
                    sheetState = rememberModalBottomSheetState(),
                    title = "Timed event alert",
                    options = timedOptions,
                    currentValue = 15,
                    isAllDay = false,
                    use24Hour = false,
                    onSelect = { picked = it },
                    onDismiss = {},
                )
            }
        }
        // 3 presets + the Custom row = 4 radios (Custom is a selectable group member too).
        assertRadioGroupSemantics(selectedLabel = "15 minutes before", unselectedLabel = "1 hour before", radioCount = 4)
        // The Custom row is a radio member but unselected (current value is a preset).
        // Its tap opens the wheel rather than firing onSelect, so it is asserted for
        // membership only — not tapped for a value callback.
        composeTestRule.onNodeWithText("Custom")
            .assert(isRadioButton())
            .assertIsNotSelected()
        // The value callback is verified on a real preset row.
        composeTestRule.onNodeWithText("1 hour before").performClick()
        assertEquals(60, picked)
    }

    // ==================== Default alert: SingleAlertPickerSheet (second Column in AlertsSheet) ====================

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `single alert picker rows are grouped radio buttons and tap selects`() {
        var picked: Int? = null
        composeTestRule.setContent {
            MaterialTheme {
                SingleAlertPickerSheet(
                    sheetState = rememberModalBottomSheetState(),
                    title = "All-day event alert",
                    options = timedOptions,
                    currentValue = 15,
                    onSelect = { picked = it },
                    onDismiss = {},
                )
            }
        }
        assertRadioGroupSemantics(selectedLabel = "15 minutes before", unselectedLabel = "1 hour before", radioCount = 3)
        composeTestRule.onNodeWithText("1 hour before").performClick()
        assertEquals(60, picked)
    }
}
