package org.onekash.kashcal.ui.screens.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Tests the Calendar Feeds "Add from account" button and the sheet it opens: the title's inline
 * info, each section state, the Retry callback, and the feed rows' switch semantics.
 *
 * Runs under Robolectric; run the class in isolation given the repo's multi-class native-crash
 * flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class AccountFeedsSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(resId: Int) = context.getString(resId)
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

    private val usRow = AccountFeedRow(
        url = "https://feeds.example.test/us.ics", name = "US Holidays",
        host = "feeds.example.test", color = 0xFF1E88E5.toInt(),
    )

    private fun section(id: Long, name: String, content: AccountFeedsContent) =
        AccountFeedsSection(accountId = id, displayName = name, maskedEmail = "u***@example.test", content = content)

    private val switches = mutableListOf<Triple<Long, String, Boolean>>()
    private val retries = mutableListOf<Long>()

    private fun renderSheet(vararg sections: AccountFeedsSection) {
        composeTestRule.setContent {
            MaterialTheme {
                AccountFeedsSheet(
                    state = AccountFeedsSheetState(sections.toList()),
                    onSwitch = { accountId, url, on -> switches += Triple(accountId, url, on) },
                    onRetry = { retries += it },
                    onDismiss = {},
                )
            }
        }
    }

    private fun feedRow(name: String) = composeTestRule.onNode(
        hasText(name, substring = true) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
        useUnmergedTree = false,
    )

    @Test
    fun `the explanation stays hidden until the title's info button opens it inline`() {
        renderSheet(section(7, "Nextcloud", AccountFeedsContent.Checking))
        val title = str(R.string.account_feeds_add)

        composeTestRule.onNodeWithText(title).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.account_feeds_note)).assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription(str(R.string.cd_about_setting).format(title))
            .performClick()

        composeTestRule.onNodeWithText(str(R.string.account_feeds_note)).assertIsDisplayed()
    }

    @Test
    fun `each section shows its account name, masked email and listing state`() {
        renderSheet(
            section(7, "Nextcloud", AccountFeedsContent.Checking),
            section(8, "iCloud", AccountFeedsContent.NoFeeds),
            section(9, "Home Radicale", AccountFeedsContent.Failed),
        )

        composeTestRule.onNodeWithText("Nextcloud").assertIsDisplayed()
        composeTestRule.onNodeWithText("iCloud").assertIsDisplayed()
        composeTestRule.onNodeWithText("Home Radicale").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("u***@example.test").assertCountEquals(3)
        composeTestRule.onNodeWithText(str(R.string.account_feeds_checking)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.account_feeds_none)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.account_feeds_failed)).assertIsDisplayed()
    }

    @Test
    fun `Retry calls back for that account only`() {
        renderSheet(
            section(7, "Nextcloud", AccountFeedsContent.NoFeeds),
            section(9, "Home Radicale", AccountFeedsContent.Failed),
        )

        composeTestRule.onNodeWithText(str(R.string.action_retry)).performClick()

        assertEquals(listOf(9L), retries)
    }

    @Test
    fun `no sections shows that there are no feeds`() {
        renderSheet()

        composeTestRule.onNodeWithText(str(R.string.account_feeds_none)).assertIsDisplayed()
    }

    @Test
    fun `a feed row is one switch labelled with its name and host`() {
        renderSheet(section(7, "Nextcloud", AccountFeedsContent.Feeds(listOf(usRow))))

        feedRow("US Holidays").assertIsDisplayed().assertIsOff().assertIsEnabled()
        feedRow("feeds.example.test").assertIsDisplayed()
    }

    @Test
    fun `tapping an off row switches it on, and an on row off, reporting its section's account`() {
        renderSheet(
            section(
                7, "Nextcloud",
                AccountFeedsContent.Feeds(
                    listOf(usRow, usRow.copy(url = "https://feeds.example.test/de.ics", name = "German Holidays", isOn = true))
                ),
            )
        )

        feedRow("US Holidays").performClick()
        feedRow("German Holidays").assertIsOn().performClick()

        assertEquals(
            listOf(
                Triple(7L, "https://feeds.example.test/us.ics", true),
                Triple(7L, "https://feeds.example.test/de.ics", false),
            ),
            switches,
        )
    }

    @Test
    fun `a busy row is disabled and doesn't call back`() {
        renderSheet(section(7, "Nextcloud", AccountFeedsContent.Feeds(listOf(usRow.copy(isOn = true, isBusy = true)))))

        feedRow("US Holidays").assertIsOn().assertIsNotEnabled().performClick()

        assertEquals(emptyList<Triple<Long, String, Boolean>>(), switches)
    }

    @Test
    fun `Calendar Feeds shows Add from account only while its flag is on, and the tap opens it`() {
        var opened = 0
        val show = mutableStateOf(false)
        composeTestRule.setContent {
            MaterialTheme {
                SubscriptionsScreen(
                    subscriptions = emptyList(),
                    onNavigateBack = {},
                    onAddSubscription = { _, _, _ -> },
                    onToggleSubscription = { _, _ -> },
                    onDeleteSubscription = {},
                    onRefreshSubscription = {},
                    onUpdateSubscription = { _, _, _, _ -> },
                    showAddFromAccount = show.value,
                    onOpenAccountFeeds = { opened++ },
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.account_feeds_add), substring = true).assertDoesNotExist()

        show.value = true
        composeTestRule.onNodeWithText(str(R.string.account_feeds_add), substring = true)
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, opened)
    }
}
