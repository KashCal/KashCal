package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.permission.LocalNetworkPermissionState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Compose tests for the Android 17+ local-network banner in [AddSubscriptionDialog].
 *
 * The banner brings the ICS "Add subscription" flow to parity with the CalDAV
 * sign-in sheet: for a LAN URL it proactively offers to grant ACCESS_LOCAL_NETWORK,
 * so a http://192.168.x.x feed (e.g. a Sonarr .ics) can actually be reached. It is
 * inline and never blocks the URL field.
 *
 * Runs under Robolectric; run the class in isolation given the repo's multi-class
 * native-crash flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class AddSubscriptionDialogLanBannerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // R.string.local_network_banner_title / label_calendar_url
    private val bannerTitle = "Local network access needed"
    private val urlLabel = "Calendar URL"

    private fun render(
        initialUrl: String? = null,
        state: LocalNetworkPermissionState,
    ) {
        Locale.setDefault(Locale.US)
        composeTestRule.setContent {
            MaterialTheme {
                AddSubscriptionDialog(
                    initialUrl = initialUrl,
                    onDismiss = {},
                    onAdd = { _, _, _ -> },
                    localNetworkPermissionState = state,
                    onRequestLocalNetwork = {},
                    onDialogOpened = {},
                )
            }
        }
    }

    @Test
    fun `typing a LAN url shows the banner on Android 17`() {
        render(state = LocalNetworkPermissionState.NotRequested)

        composeTestRule.onNodeWithText(urlLabel)
            .performTextInput("http://192.168.178.82:8989/feed/v3/calendar/sonarr.ics?apikey=abc")

        composeTestRule.onNodeWithText(bannerTitle).assertIsDisplayed()
    }

    @Test
    fun `typing a public url does not show the banner`() {
        render(state = LocalNetworkPermissionState.NotRequested)

        composeTestRule.onNodeWithText(urlLabel)
            .performTextInput("https://example.com/holidays.ics")

        composeTestRule.onNodeWithText(bannerTitle).assertDoesNotExist()
    }

    @Test
    fun `pre-Android-17 never shows the banner even for a LAN url`() {
        render(state = LocalNetworkPermissionState.NotRequired)

        composeTestRule.onNodeWithText(urlLabel)
            .performTextInput("http://192.168.178.82:8989/feed.ics")

        composeTestRule.onNodeWithText(bannerTitle).assertDoesNotExist()
    }

    @Test
    fun `a pre-filled LAN url shows the banner proactively on open`() {
        render(
            initialUrl = "http://10.0.0.5/webcal.ics",
            state = LocalNetworkPermissionState.NotRequested,
        )

        composeTestRule.onNodeWithText(bannerTitle).assertIsDisplayed()
    }
}
