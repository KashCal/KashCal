package org.onekash.kashcal.ui.screens.settings

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.SettingsActivity
import org.onekash.kashcal.data.credential.UnifiedCredentialManager
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.kashCalProductionConfig
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.testutil.DeviceTestDatabases

/**
 * An iCloud account whose credentials are gone opens "Add iCloud" with its Apple ID prefilled,
 * so signing in re-attaches it (#371). Seeds a disabled iCloud row with an example.test address
 * and no credentials, opens Settings, and checks the sign-in sheet. It never taps Sign in, so it
 * never reaches Apple.
 *
 * Writes to the app's own database: run it only on an emulator or device without real KashCal
 * accounts, in its own instrumentation run. It skips itself when any iCloud account exists, and
 * deletes only the row it inserted.
 */
@RunWith(AndroidJUnit4::class)
class ICloudSignInPrefillDeviceTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: KashCalDatabase
    private var insertedId: Long? = null
    private var scenario: ActivityScenario<SettingsActivity>? = null

    @Before
    fun setUp() = runBlocking {
        // The app's configuration, so a test that creates the file leaves it complete.
        db = DeviceTestDatabases.file(context, KashCalDatabase.DATABASE_NAME)
            .kashCalProductionConfig()
            .build()
        assumeTrue(
            "an iCloud account exists; not running beside it",
            db.accountsDao().getByProvider(AccountProvider.ICLOUD).isEmpty()
        )

        val id = db.accountsDao().insert(
            Account(
                provider = AccountProvider.ICLOUD,
                email = APPLE_ID,
                displayName = "iCloud",
                principalUrl = null,
                homeSetUrl = null,
                isEnabled = false,
            )
        )
        insertedId = id
        assumeFalse("credentials exist for the seeded id", UnifiedCredentialManager(context).hasCredentials(id))
    }

    @After
    fun tearDown() = runBlocking {
        scenario?.close()
        insertedId?.let { db.accountsDao().deleteById(it) }
        db.close()
    }

    @Test
    fun add_icloud_opens_with_the_apple_id_of_the_account_without_credentials() {
        // Launched after the insert: the settings screen reads the iCloud account once, at start.
        scenario = ActivityScenario.launch(SettingsActivity::class.java)

        tapWhenShown(context.getString(R.string.accounts_row_label))
        tapWhenShown(context.getString(R.string.accounts_add_icloud))
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodes(hasText(APPLE_ID)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tapWhenShown(text: String) {
        composeRule.waitUntil(TIMEOUT_MS) { composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodes(hasText(text)).onFirst().performClick()
    }

    private companion object {
        const val APPLE_ID = "test@example.test"
        // The debug build runs interpreted on a software-emulated phone.
        const val TIMEOUT_MS = 120_000L
    }
}
