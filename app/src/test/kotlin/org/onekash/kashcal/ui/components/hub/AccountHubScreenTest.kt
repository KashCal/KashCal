package org.onekash.kashcal.ui.components.hub

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Compose tests for [AccountHubScreen].
 *
 * The hub's own regressable surface is its callback wiring (each row invokes the
 * matching lambda, not a neighbour) and the hero's edit -> save flow; the
 * open/close of the overlay lives in the caller. The personalization section is
 * stubbed via the [AccountHubScreen] slot so no Hilt graph is needed. Runs under
 * Robolectric; run the class in isolation given the repo's multi-class
 * native-crash flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class AccountHubScreenTest {

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

    private class Callbacks {
        var invites = 0
        var jumpToDate = 0
        var shareAvailability = 0
        var tags = 0
        var settings = 0
        var about = 0
        var back = 0
        var initials: String? = null
        var appLockToggled: Boolean? = null
        var appPermissions = 0
    }

    private fun render(
        userInitials: String = "KC",
        appLockEnabled: Boolean = false,
        callbacks: Callbacks = Callbacks(),
    ) {
        composeTestRule.setContent {
            KashCalTheme {
                AccountHubScreen(
                    pendingInvitesCount = 0,
                    userInitials = userInitials,
                    onInitialsChange = { callbacks.initials = it },
                    onInvitesClick = { callbacks.invites++ },
                    onJumpToDateClick = { callbacks.jumpToDate++ },
                    onShareAvailabilityClick = { callbacks.shareAvailability++ },
                    onTagsClick = { callbacks.tags++ },
                    onSettingsClick = { callbacks.settings++ },
                    onAboutClick = { callbacks.about++ },
                    onBack = { callbacks.back++ },
                    appLockEnabled = appLockEnabled,
                    onToggleAppLock = { callbacks.appLockToggled = it },
                    onAppPermissionsClick = { callbacks.appPermissions++ },
                    // Stub the VM-backed personalization slot so no Hilt graph is needed.
                    makeItYours = { Text("make-it-yours-stub") },
                )
            }
        }
    }

    @Test
    fun `each destination row invokes only its own callback`() {
        val cb = Callbacks()
        render(callbacks = cb)

        composeTestRule.onNodeWithText("Invites").performClick()
        assertEquals(1, cb.invites)

        composeTestRule.onNodeWithText("Go to date").performClick()
        assertEquals(1, cb.jumpToDate)

        composeTestRule.onNodeWithText("Share availability").performClick()
        assertEquals(1, cb.shareAvailability)

        composeTestRule.onNodeWithText("Manage tags").performClick()
        assertEquals(1, cb.tags)

        composeTestRule.onNodeWithText("Accounts & Settings").performClick()
        assertEquals(1, cb.settings)

        composeTestRule.onNodeWithText("About").performClick()
        assertEquals(1, cb.about)

        // No sibling callback fired more than its own single tap.
        assertEquals(1, cb.invites)
        assertEquals(0, cb.back)
    }

    @Test
    fun `back arrow invokes the back callback`() {
        val cb = Callbacks()
        render(callbacks = cb)
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, cb.back)
    }

    @Test
    fun `hero edit then save persists normalized initials`() {
        val cb = Callbacks()
        render(userInitials = "KC", callbacks = cb)

        // Tap the avatar to enter edit mode.
        composeTestRule.onNodeWithContentDescription("Edit your initials").performClick()
        composeTestRule.waitForIdle()

        // Replace the draft and save; input is normalized to two uppercase letters.
        composeTestRule.onNodeWithText("KC").performTextClearance()
        composeTestRule.onNodeWithText("Initials").performTextInput("ann")
        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals("AN", cb.initials)
    }

    @Test
    fun `personalization slot renders under its section header`() {
        render()
        composeTestRule.onNodeWithText("Make it yours").assertIsDisplayed()
        composeTestRule.onNodeWithText("make-it-yours-stub").assertIsDisplayed()
    }

    @Test
    fun `all three section headers render`() {
        render()
        composeTestRule.onNodeWithText("Make it yours").assertIsDisplayed()
        composeTestRule.onNodeWithText("Own your calendar").assertIsDisplayed()
        composeTestRule.onNodeWithText("Privacy & Security").assertIsDisplayed()
    }

    @Test
    fun `the nested Widgets sub-header is gone`() {
        render()
        composeTestRule.onAllNodesWithText("Widgets").assertCountEquals(0)
    }

    @Test
    fun `app-lock row reflects state and toggling invokes the callback`() {
        val cb = Callbacks()
        render(appLockEnabled = false, callbacks = cb)
        composeTestRule.onNodeWithText("App lock").performClick()
        assertEquals(true, cb.appLockToggled)
    }

    @Test
    fun `app-permissions row renders and invokes its callback`() {
        val cb = Callbacks()
        render(callbacks = cb)
        composeTestRule.onNodeWithText("App permissions").performClick()
        assertEquals(1, cb.appPermissions)
    }

    @Test
    fun `data-ownership link renders in Privacy and security`() {
        render()
        composeTestRule.onNodeWithText("How your data stays yours").assertIsDisplayed()
    }
}
