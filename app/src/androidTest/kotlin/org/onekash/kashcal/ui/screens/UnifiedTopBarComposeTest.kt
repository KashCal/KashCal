package org.onekash.kashcal.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.screens.settings.AccountsScreen
import org.onekash.kashcal.ui.screens.settings.BirthdaysAndAnniversariesScreen
import org.onekash.kashcal.ui.screens.settings.SubscriptionsScreen

/**
 * Top-bar contract for the four full-page settings screens: each renders the
 * app-name title with a back-arrow navigation icon, and no screen-name string
 * appears in the top bar (catches `title = { Text(R.string.X_title) }` drift).
 */
@RunWith(AndroidJUnit4::class)
class UnifiedTopBarComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val appName = context.getString(R.string.app_name)
    private val backCd = context.getString(R.string.cd_back)
    private val accountsBackCd = context.getString(R.string.accounts_cd_back)
    private val subscriptionsBackCd = context.getString(R.string.subscriptions_cd_back)

    private val screenNameTitles = listOf(
        context.getString(R.string.settings_title),
        context.getString(R.string.accounts_title),
        context.getString(R.string.subscriptions_title),
        context.getString(R.string.birthdays_anniversaries_row_label)
    )

    @Test
    fun accountSettingsScreen_rendersAppNameTitle() {
        rule.setContent {
            MaterialTheme {
                AccountSettingsScreen(
                    uiState = AccountSettingsUiState(),
                    onNavigateBack = {},
                )
            }
        }
        rule.onNodeWithText(appName).assertIsDisplayed()
        rule.onNodeWithContentDescription(backCd).assertIsDisplayed()
    }

    @Test
    fun accountsScreen_rendersAppNameTitle_andBackInvokesCallback() {
        var backInvoked = false
        rule.setContent {
            MaterialTheme {
                AccountsScreen(
                    iCloudAccount = null,
                    showAddICloud = true,
                    calDavAccounts = emptyList(),
                    onNavigateBack = { backInvoked = true },
                    onAddICloud = {},
                    onICloudSignOut = {},
                    onAddCalDav = {},
                    onCalDavSignOut = {},
                )
            }
        }
        rule.onNodeWithText(appName).assertIsDisplayed()
        rule.onNodeWithContentDescription(accountsBackCd).performClick()
        assertTrue("onNavigateBack should be invoked when back arrow tapped", backInvoked)
    }

    @Test
    fun subscriptionsScreen_rendersAppNameTitle_andBackInvokesCallback() {
        var backInvoked = false
        rule.setContent {
            MaterialTheme {
                SubscriptionsScreen(
                    subscriptions = emptyList(),
                    onNavigateBack = { backInvoked = true },
                    onAddSubscription = { _, _, _ -> },
                    onToggleSubscription = { _, _ -> },
                    onDeleteSubscription = {},
                    onRefreshSubscription = {},
                    onUpdateSubscription = { _, _, _, _ -> },
                )
            }
        }
        rule.onNodeWithText(appName).assertIsDisplayed()
        rule.onNodeWithContentDescription(subscriptionsBackCd).performClick()
        assertTrue("onNavigateBack should be invoked when back arrow tapped", backInvoked)
    }

    @Test
    fun birthdaysAndAnniversariesScreen_rendersAppNameTitle() {
        rule.setContent {
            MaterialTheme {
                BirthdaysAndAnniversariesScreen(
                    birthdaysEnabled = false,
                    birthdaysColor = 0xFF2196F3.toInt(),
                    birthdaysReminder = 0,
                    birthdayCount = 0,
                    anniversariesEnabled = false,
                    anniversariesColor = 0xFFFF5252.toInt(),
                    anniversariesReminder = 0,
                    anniversaryCount = 0,
                    hasPermission = false,
                    timeFormat = "system",
                    onToggleBirthdays = {},
                    onBirthdaysColorChange = {},
                    onBirthdaysReminderChange = {},
                    onToggleAnniversaries = {},
                    onAnniversariesColorChange = {},
                    onAnniversariesReminderChange = {},
                    onNavigateBack = {},
                )
            }
        }
        rule.onNodeWithText(appName).assertIsDisplayed()
        rule.onNodeWithContentDescription(backCd).assertIsDisplayed()
    }

    @Test
    fun accountSettingsScreen_doesNotRenderScreenNameInTitle() {
        rule.setContent {
            MaterialTheme {
                AccountSettingsScreen(
                    uiState = AccountSettingsUiState(),
                    onNavigateBack = {},
                )
            }
        }
        // The screen-name title "Settings" must not be rendered as the bar title.
        // (Settings root has no body heading either, so the only place it could
        // legitimately render is the bar title — which the unified contract forbids.)
        rule.onNodeWithText(context.getString(R.string.settings_title)).assertDoesNotExist()
    }
}
