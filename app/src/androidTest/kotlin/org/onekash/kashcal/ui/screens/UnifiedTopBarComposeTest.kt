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
 * Tests the top bar of the Settings root and the Accounts, Subscriptions and Birthdays &
 * Anniversaries screens: each shows the app name as a title and a back arrow, back calls
 * `onNavigateBack` on Accounts and Subscriptions, and the root shows no "Settings" text.
 *
 * Each of these screens passes `R.string.settings_title` as its `SettingsTopAppBar` title, so
 * the app-name and no-"Settings" assertions don't match the screens as built.
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
        // Settings root has no body heading, so a "Settings" node could only be the bar title,
        // which this test forbids.
        rule.onNodeWithText(context.getString(R.string.settings_title)).assertDoesNotExist()
    }
}
