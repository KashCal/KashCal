package org.onekash.kashcal.ui.screens.settings

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Contract for the account detail sheet's contact-sync affordance.
 *
 * CardDAV contact sync is a beta feature, so the Contacts toggle carries a
 * "Beta" badge inline after its label. The badge must appear only for
 * CardDAV-capable providers — the whole Contacts toggle is gated on
 * [AccountProvider.supportsCardDAV], so a local account shows neither.
 *
 * Runs under Robolectric; run the class in isolation given the repo's
 * multi-class native-crash flake.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class AccountDetailSheetTest {

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

    @Test
    fun `CardDAV account shows a Beta badge on the contacts toggle`() {
        renderSheet(AccountProvider.CALDAV)
        composeTestRule.onNodeWithText(str(R.string.account_detail_sync_contacts)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.badge_beta)).assertIsDisplayed()
    }

    @Test
    fun `CardDAV account shows an info button explaining contact sync`() {
        renderSheet(AccountProvider.CALDAV)
        val cd = str(R.string.cd_about_setting).format(str(R.string.account_detail_sync_contacts))
        composeTestRule.onNodeWithContentDescription(cd).assertIsDisplayed()
    }

    @Test
    fun `non-CardDAV account shows neither the contacts toggle nor a Beta badge`() {
        renderSheet(AccountProvider.LOCAL)
        composeTestRule.onNodeWithText(str(R.string.account_detail_sync_contacts)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.badge_beta)).assertDoesNotExist()
    }

    private fun renderSheet(provider: AccountProvider) {
        composeTestRule.setContent {
            MaterialTheme {
                AccountDetailSheet(
                    sheetState = rememberModalBottomSheetState(),
                    account = model(provider),
                    syncStatus = AccountDetailSyncStatus.Idle,
                    discoverStatus = AccountDetailDiscoverStatus.Idle,
                    onRename = {},
                    onSyncNow = {},
                    onToggleEnabled = {},
                    onToggleContactSync = {},
                    contactSyncPermissionNeeded = false,
                    contactSyncConfirmation = null,
                    onDismissContactSyncConfirmation = {},
                    onGrantContactsPermission = {},
                    onDiscoverCalendars = {},
                    onChangePassword = {},
                    onSignOut = {},
                    onDismiss = {},
                )
            }
        }
    }

    private fun model(provider: AccountProvider) = AccountDetailUiModel(
        accountId = 1L,
        provider = provider,
        displayName = "Test account",
        email = "u***@example.test",
        principalUrl = null,
        calendarCount = 2,
        isEnabled = true,
        contactSyncEnabled = false,
        contactCount = 0,
        lastSuccessfulSyncAt = null,
        consecutiveSyncFailures = 0,
    )
}
