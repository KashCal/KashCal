package org.onekash.kashcal.ui.screens.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tapping Sign in again in the account detail sheet closes that sheet before the sign-in sheet
 * opens, so the two never stack, and hands the account to [AccountsScreen]'s callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class AccountsScreenSignInAgainTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun str(resId: Int) = context.getString(resId)

    @Test
    fun `Sign in again closes the detail sheet and passes the account on`() {
        val signInAgainIds = mutableListOf<Long>()
        var clearedDetail = 0
        composeTestRule.setContent {
            MaterialTheme {
                AccountsScreen(
                    iCloudAccount = null,
                    showAddICloud = true,
                    calDavAccounts = listOf(CalDavAccountUiModel(id = 7L, email = "user@example.test", displayName = "Home", calendarCount = 1)),
                    onNavigateBack = {},
                    onAddICloud = {},
                    onICloudSignOut = {},
                    onAddCalDav = {},
                    onCalDavSignOut = {},
                    accountDetail = AccountDetailUiModel(
                        accountId = 7L,
                        provider = AccountProvider.CALDAV,
                        displayName = "Home",
                        email = "u***@example.test",
                        principalUrl = null,
                        calendarCount = 1,
                        isEnabled = true,
                        contactSyncEnabled = false,
                        contactCount = 0,
                        lastSuccessfulSyncAt = null,
                        consecutiveSyncFailures = 0,
                        needsSignIn = true,
                    ),
                    onClearAccountDetail = { clearedDetail++ },
                    onSignInAgain = { signInAgainIds += it },
                )
            }
        }

        composeTestRule.onNodeWithText("Home").performClick()
        composeTestRule.onNodeWithText(str(R.string.account_detail_sign_in_again)).assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(7L), signInAgainIds)
        assertEquals(1, clearedDetail)
        composeTestRule.onNodeWithText(str(R.string.account_detail_sign_in_again_subtitle)).assertDoesNotExist()
    }
}
