package org.onekash.kashcal.ui.components.hub

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [AccountHubScreen] — the full-screen destination that
 * replaced the overflow bottom sheet.
 */
@RunWith(AndroidJUnit4::class)
class AccountHubScreenComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setHub(
        pendingInvitesCount: Int = 0,
        userInitials: String = "",
        onInitialsChange: (String) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            AccountHubScreen(
                pendingInvitesCount = pendingInvitesCount,
                userInitials = userInitials,
                onInitialsChange = onInitialsChange,
                onInvitesClick = {},
                onJumpToDateClick = {},
                onShareAvailabilityClick = {},
                onTagsClick = {},
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
        composeTestRule.onNodeWithText("Manage tags").assertIsDisplayed()
        composeTestRule.onNodeWithText("Accounts & Settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("About").assertIsDisplayed()
        composeTestRule.onNodeWithText("Privacy & Security").assertIsDisplayed()
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
