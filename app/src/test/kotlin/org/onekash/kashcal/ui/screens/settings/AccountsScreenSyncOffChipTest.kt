package org.onekash.kashcal.ui.screens.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An account whose sync is turned off shows a Sync off badge at the end of its row in
 * [AccountsScreen]; an account with sync on looks as before. The badge is independent of the
 * Sync issue warning, and TalkBack hears it in the row description.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class AccountsScreenSyncOffChipTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun str(resId: Int) = context.getString(resId)

    private fun render(
        iCloud: ICloudAccountUiModel? = null,
        calDav: List<CalDavAccountUiModel> = emptyList(),
        onObserve: (Long) -> Unit = {},
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                AccountsScreen(
                    iCloudAccount = iCloud,
                    showAddICloud = iCloud == null,
                    calDavAccounts = calDav,
                    onNavigateBack = {},
                    onAddICloud = {},
                    onICloudSignOut = {},
                    onAddCalDav = {},
                    onCalDavSignOut = {},
                    onObserveAccountDetail = onObserve,
                )
            }
        }
    }

    @Test
    fun `an iCloud account with sync off shows the badge and TalkBack hears it`() {
        render(iCloud = ICloudAccountUiModel(accountId = 1L, email = "user@example.test", calendarCount = 2, isEnabled = false))

        composeTestRule.onNodeWithText(str(R.string.badge_sync_off), useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(str(R.string.accounts_cd_sync_off), substring = true).assertExists()
    }

    @Test
    fun `accounts with sync on show no badge`() {
        render(
            iCloud = ICloudAccountUiModel(accountId = 1L, email = "user@example.test", calendarCount = 2),
            calDav = listOf(CalDavAccountUiModel(id = 2L, email = "dav@example.test", displayName = "Home", calendarCount = 1)),
        )

        composeTestRule.onNodeWithText(str(R.string.badge_sync_off), useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(str(R.string.accounts_cd_sync_off), substring = true).assertDoesNotExist()
    }

    @Test
    fun `sync off and a sync issue show together`() {
        render(
            calDav = listOf(
                CalDavAccountUiModel(
                    id = 2L,
                    email = "dav@example.test",
                    displayName = "Home",
                    calendarCount = 1,
                    consecutiveSyncFailures = SyncWarningConstants.SYNC_FAILURE_THRESHOLD,
                    lastSuccessfulSyncAt = null,
                    isEnabled = false,
                )
            )
        )

        composeTestRule.onNodeWithText(str(R.string.badge_sync_off), useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.accounts_sync_issue), useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(str(R.string.accounts_cd_sync_warning), useUnmergedTree = true).assertExists()
        // TalkBack hears sync off before the warning.
        composeTestRule.onNodeWithContentDescription(
            ", " + str(R.string.accounts_cd_sync_off) + ", " + str(R.string.accounts_cd_sync_warning),
            substring = true
        ).assertExists()
    }

    @Test
    fun `a long name keeps the badge on the row before the chevron`() {
        val longName = "A very long self-hosted calendar server account name that keeps going"
        render(calDav = listOf(CalDavAccountUiModel(id = 3L, email = "dav@example.test", displayName = longName, calendarCount = 1, isEnabled = false)))

        val badge = composeTestRule.onNodeWithText(str(R.string.badge_sync_off), useUnmergedTree = true)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val row = composeTestRule.onNodeWithContentDescription(longName, substring = true).getUnclippedBoundsInRoot()

        assertTrue("badge has width", badge.width > 0.dp)
        // The chevron (24dp) and the row's end padding (16dp) follow the badge.
        assertTrue("badge ends before the chevron: $badge in $row", badge.right <= row.right - 40.dp)
    }

    @Test
    fun `tapping an account with sync off still opens its detail`() {
        val opened = mutableListOf<Long>()
        render(
            iCloud = ICloudAccountUiModel(accountId = 9L, email = "user@example.test", calendarCount = 2, isEnabled = false),
            calDav = listOf(CalDavAccountUiModel(id = 4L, email = "dav@example.test", displayName = "Home", calendarCount = 1, isEnabled = false)),
            onObserve = { opened += it },
        )

        composeTestRule.onNodeWithText("Home").performClick()
        composeTestRule.onNodeWithText(str(R.string.provider_icloud)).performClick()

        assertEquals(listOf(4L, 9L), opened)
    }

    @Test
    fun `the badge renders its label`() {
        composeTestRule.setContent { MaterialTheme { SyncOffBadge() } }

        composeTestRule.onNodeWithText(str(R.string.badge_sync_off)).assertIsDisplayed()
    }

    @Test
    fun `the badge sits on the name line, not the subtitle`() {
        render(calDav = listOf(CalDavAccountUiModel(id = 6L, email = "dav@example.test", displayName = "Home", calendarCount = 1, isEnabled = false)))

        val badge = composeTestRule.onNodeWithText(str(R.string.badge_sync_off), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val name = composeTestRule.onNodeWithText("Home", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val subtitle = composeTestRule.onNodeWithText("@example.test", substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val badgeCentre = (badge.top + badge.bottom) / 2

        assertTrue("badge centre $badgeCentre within name $name", badgeCentre >= name.top && badgeCentre <= name.bottom)
        assertTrue("badge above the subtitle: $badge vs $subtitle", badge.bottom <= subtitle.top)
    }
}
