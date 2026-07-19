package org.onekash.kashcal.ui.components.hub

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.viewmodels.ViewMode

/**
 * Compose UI tests for [AccountHubScreen] — the full-screen destination that
 * replaced the overflow bottom sheet.
 */
@RunWith(AndroidJUnit4::class)
class AccountHubScreenComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setHub(
        currentViewMode: ViewMode = ViewMode.MONTH,
        pendingInvitesCount: Int = 0,
        userInitials: String = "",
        onInitialsChange: (String) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            AccountHubScreen(
                currentViewMode = currentViewMode,
                pendingInvitesCount = pendingInvitesCount,
                userInitials = userInitials,
                onInitialsChange = onInitialsChange,
                onInvitesClick = {},
                onJumpToDateClick = {},
                onShareAvailabilityClick = {},
                onInsightsClick = {},
                onSettingsClick = {},
                onAboutClick = {},
                onBack = onBack,
            )
        }
    }

    @Test
    fun rendersAllDestinationsInOrder() {
        setHub()
        composeTestRule.onNodeWithText("Invites").assertIsDisplayed()
        composeTestRule.onNodeWithText("Go to date").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share availability").assertIsDisplayed()
        composeTestRule.onNodeWithText("Insights").assertIsDisplayed()
        composeTestRule.onNodeWithText("Accounts & Settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("About").assertIsDisplayed()
        composeTestRule.onNodeWithText("Privacy & Security").assertIsDisplayed()
    }

    @Test
    fun insightsRowSelectedOnlyInInsightsView() {
        setHub(currentViewMode = ViewMode.INSIGHTS)
        composeTestRule.onNodeWithText("Insights").assertIsSelected()
    }

    @Test
    fun insightsRowNotSelectedInMonthView() {
        setHub(currentViewMode = ViewMode.MONTH)
        composeTestRule.onNodeWithText("Insights").assertIsNotSelected()
    }

    @Test
    fun backArrowInvokesOnBack() {
        var backCalled = false
        setHub(onBack = { backCalled = true })
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assert(backCalled)
    }

    @Test
    fun editingInitialsInlineSavesNormalizedValue() {
        var saved: String? = null
        setHub(userInitials = "", onInitialsChange = { saved = it })

        // Tap the hero avatar to enter edit mode, type, and save.
        composeTestRule.onNodeWithContentDescription("Edit your initials").performClick()
        composeTestRule.onNodeWithText("Initials").performTextInput("john")
        composeTestRule.onNodeWithText("Save").performClick()

        assert(saved == "JO") { "Expected normalized 'JO' but was $saved" }
    }
}
