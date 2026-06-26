package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.viewmodels.ShareAvailabilityUiState

/**
 * Compose UI tests for ShareAvailabilitySheet. Drives the stateless variant
 * (ShareAvailabilitySheetContent) so we
 * don't need a real ViewModel or ModalBottomSheet host in the test harness.
 */
@RunWith(AndroidJUnit4::class)
class ShareAvailabilitySheetComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sheetTitle = context.getString(R.string.share_availability_sheet_title)
    private val shareLabel = context.getString(R.string.share_availability_share_button)
    private val allDayTitle = context.getString(R.string.share_availability_all_day_toggle_title)

    private fun nonEmptyState(): ShareAvailabilityUiState =
        ShareAvailabilityUiState(
            days = 7,
            workStartMin = 9 * 60,
            workEndMin = 17 * 60,
            includeAllDay = false,
            blocks = emptyList(),
            previewText = "Free over the next 7 days (09:00 – 17:00):\n\nMon May 25: 10:00 – 12:00",
            isShareEnabled = true,
            isLoading = false
        )

    private fun emptyState(): ShareAvailabilityUiState =
        ShareAvailabilityUiState(
            days = 7,
            workStartMin = 9 * 60,
            workEndMin = 17 * 60,
            includeAllDay = false,
            blocks = emptyList(),
            previewText = context.getString(R.string.share_availability_empty),
            isShareEnabled = false,
            isLoading = false
        )

    @Test
    fun sheet_rendersTitle() {
        rule.setContent {
            MaterialTheme {
                ShareAvailabilitySheetContent(
                    uiState = nonEmptyState(),
                    is24Hour = true,
                    onDaysPreview = {},
                    onDaysCommit = {},
                    onHoursPreview = { _, _ -> },
                    onHoursCommit = {},
                    onAllDayToggle = {},
                    onShare = {},
                    onDismiss = {}
                )
            }
        }
        rule.onNodeWithText(sheetTitle).assertIsDisplayed()
    }

    @Test
    fun sheet_rendersAllDayToggleTitle() {
        rule.setContent {
            MaterialTheme {
                ShareAvailabilitySheetContent(
                    uiState = nonEmptyState(),
                    is24Hour = true,
                    onDaysPreview = {},
                    onDaysCommit = {},
                    onHoursPreview = { _, _ -> },
                    onHoursCommit = {},
                    onAllDayToggle = {},
                    onShare = {},
                    onDismiss = {}
                )
            }
        }
        rule.onNodeWithText(allDayTitle).assertIsDisplayed()
    }

    @Test
    fun sheet_rendersPreviewBody() {
        val state = nonEmptyState()
        rule.setContent {
            MaterialTheme {
                ShareAvailabilitySheetContent(
                    uiState = state,
                    is24Hour = true,
                    onDaysPreview = {},
                    onDaysCommit = {},
                    onHoursPreview = { _, _ -> },
                    onHoursCommit = {},
                    onAllDayToggle = {},
                    onShare = {},
                    onDismiss = {}
                )
            }
        }
        // The preview text is rendered verbatim somewhere in the sheet.
        rule.onNodeWithText(state.previewText, substring = true).assertIsDisplayed()
    }

    @Test
    fun shareButton_disabled_whenIsShareEnabledFalse() {
        rule.setContent {
            MaterialTheme {
                ShareAvailabilitySheetContent(
                    uiState = emptyState(),
                    is24Hour = true,
                    onDaysPreview = {},
                    onDaysCommit = {},
                    onHoursPreview = { _, _ -> },
                    onHoursCommit = {},
                    onAllDayToggle = {},
                    onShare = {},
                    onDismiss = {}
                )
            }
        }
        rule.onNodeWithText(shareLabel).assertIsNotEnabled()
    }

    @Test
    fun shareButton_enabled_whenIsShareEnabledTrue() {
        rule.setContent {
            MaterialTheme {
                ShareAvailabilitySheetContent(
                    uiState = nonEmptyState(),
                    is24Hour = true,
                    onDaysPreview = {},
                    onDaysCommit = {},
                    onHoursPreview = { _, _ -> },
                    onHoursCommit = {},
                    onAllDayToggle = {},
                    onShare = {},
                    onDismiss = {}
                )
            }
        }
        rule.onNodeWithText(shareLabel).assertIsEnabled()
    }

    @Test
    fun shareButton_invokesOnShareWithPreviewText_whenTapped() {
        var captured: String? = null
        val state = nonEmptyState()
        rule.setContent {
            MaterialTheme {
                ShareAvailabilitySheetContent(
                    uiState = state,
                    is24Hour = true,
                    onDaysPreview = {},
                    onDaysCommit = {},
                    onHoursPreview = { _, _ -> },
                    onHoursCommit = {},
                    onAllDayToggle = {},
                    onShare = { captured = it },
                    onDismiss = {}
                )
            }
        }
        rule.onNodeWithText(shareLabel).performClick()
        assertEquals(state.previewText, captured)
    }

    @Test
    fun allDayToggle_invokesCallback_whenTapped() {
        var newValue: Boolean? = null
        rule.setContent {
            MaterialTheme {
                ShareAvailabilitySheetContent(
                    uiState = nonEmptyState(),
                    is24Hour = true,
                    onDaysPreview = {},
                    onDaysCommit = {},
                    onHoursPreview = { _, _ -> },
                    onHoursCommit = {},
                    onAllDayToggle = { newValue = it },
                    onShare = {},
                    onDismiss = {}
                )
            }
        }
        rule.onNodeWithText(allDayTitle).performClick()
        assertTrue("Toggle should have flipped on tap", newValue == true)
    }

    @Test
    fun daysLabel_reflectsState() {
        val state = nonEmptyState().copy(days = 3)
        rule.setContent {
            MaterialTheme {
                ShareAvailabilitySheetContent(
                    uiState = state,
                    is24Hour = true,
                    onDaysPreview = {},
                    onDaysCommit = {},
                    onHoursPreview = { _, _ -> },
                    onHoursCommit = {},
                    onAllDayToggle = {},
                    onShare = {},
                    onDismiss = {}
                )
            }
        }
        val expected = context.resources.getQuantityString(R.plurals.share_availability_days_hero, 3, 3)
        rule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun workingHours_24hMode_rendersAxisAndPillsIn24h() {
        rule.setContent {
            MaterialTheme {
                ShareAvailabilitySheetContent(
                    uiState = nonEmptyState(),
                    is24Hour = true,
                    onDaysPreview = {},
                    onDaysCommit = {},
                    onHoursPreview = { _, _ -> },
                    onHoursCommit = {},
                    onAllDayToggle = {},
                    onShare = {},
                    onDismiss = {}
                )
            }
        }
        // 24h axis labels (00 / 06 / 12 / 18) and pills (09:00 / 17:00).
        rule.onNodeWithText("00").assertIsDisplayed()
        rule.onNodeWithText("06").assertIsDisplayed()
        rule.onNodeWithText("18").assertIsDisplayed()
        rule.onNodeWithText("09:00").assertIsDisplayed()
        rule.onNodeWithText("17:00").assertIsDisplayed()
    }

    @Test
    fun workingHours_12hMode_rendersAxisAndPillsIn12h() {
        rule.setContent {
            MaterialTheme {
                ShareAvailabilitySheetContent(
                    uiState = nonEmptyState(),
                    is24Hour = false,
                    onDaysPreview = {},
                    onDaysCommit = {},
                    onHoursPreview = { _, _ -> },
                    onHoursCommit = {},
                    onAllDayToggle = {},
                    onShare = {},
                    onDismiss = {}
                )
            }
        }
        // 12h axis labels and pills ("12 AM" / "6 AM" / "6 PM" / "9:00 AM" / "5:00 PM").
        rule.onNodeWithText("12 AM").assertIsDisplayed()
        rule.onNodeWithText("6 AM").assertIsDisplayed()
        rule.onNodeWithText("6 PM").assertIsDisplayed()
        rule.onNodeWithText("9:00 AM").assertIsDisplayed()
        rule.onNodeWithText("5:00 PM").assertIsDisplayed()
    }
}
