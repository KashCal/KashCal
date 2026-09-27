package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.screens.AccountSettingsScreen
import org.onekash.kashcal.ui.screens.AccountSettingsUiState

/**
 * Tests that the Accounts, Subscriptions and Birthdays & Anniversaries screens each render
 * their screen name once as a body heading, and that the Settings root shows no "Settings" text.
 *
 * Each nested screen's bar title is `R.string.settings_title`, not its screen name, so the one
 * node carrying the screen name is the body heading.
 */
@RunWith(AndroidJUnit4::class)
class NestedSettingsBodyHeadingComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val accountsTitle = context.getString(R.string.accounts_title)
    private val subscriptionsTitle = context.getString(R.string.subscriptions_title)
    private val birthdaysTitle = context.getString(R.string.birthdays_anniversaries_row_label)
    private val settingsTitle = context.getString(R.string.settings_title)

    @Test
    fun accountsScreen_rendersBodyHeading() {
        rule.setContent {
            MaterialTheme {
                AccountsScreen(
                    iCloudAccount = null,
                    showAddICloud = true,
                    calDavAccounts = emptyList(),
                    onNavigateBack = {},
                    onAddICloud = {},
                    onICloudSignOut = {},
                    onAddCalDav = {},
                    onCalDavSignOut = {},
                )
            }
        }
        // The bar title is "Settings", so the one "Accounts" node is the body heading.
        rule.onAllNodesWithText(accountsTitle).assertCountEquals(1)
    }

    @Test
    fun subscriptionsScreen_rendersBodyHeading() {
        rule.setContent {
            MaterialTheme {
                SubscriptionsScreen(
                    subscriptions = emptyList(),
                    onNavigateBack = {},
                    onAddSubscription = { _, _, _ -> },
                    onToggleSubscription = { _, _ -> },
                    onDeleteSubscription = {},
                    onRefreshSubscription = {},
                    onUpdateSubscription = { _, _, _, _ -> },
                )
            }
        }
        rule.onAllNodesWithText(subscriptionsTitle).assertCountEquals(1)
    }

    @Test
    fun birthdaysAndAnniversariesScreen_rendersBodyHeading() {
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
        rule.onAllNodesWithText(birthdaysTitle).assertCountEquals(1)
    }

    /**
     * Expects no "Settings" node on the Settings root, neither a body heading nor a bar title.
     * The root passes `R.string.settings_title` as its bar title, which this count of 0 rejects.
     */
    @Test
    fun accountSettingsScreen_doesNotRenderSettingsAsBodyHeading() {
        rule.setContent {
            MaterialTheme {
                AccountSettingsScreen(
                    uiState = AccountSettingsUiState(),
                    onNavigateBack = {},
                )
            }
        }
        rule.onAllNodesWithText(settingsTitle).assertCountEquals(0)
    }
}
