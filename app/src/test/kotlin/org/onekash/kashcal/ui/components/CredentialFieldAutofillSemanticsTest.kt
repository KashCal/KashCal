package org.onekash.kashcal.ui.components

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.ui.screens.settings.CalDavConnectionState
import org.onekash.kashcal.ui.screens.settings.ChangePasswordSheet
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Autofill contract for every credential-entry field the user types into.
 *
 * Each account sign-in and password-change field must advertise an autofill
 * ContentType so the user's chosen credential provider can fill it (and offer to
 * save it), keeping secrets off the clipboard. This is a contract on the field's
 * exposed semantics, not on how they're produced: Compose's value-based text
 * field derives ContentType from KeyboardType (Email -> EmailAddress,
 * Password -> Password), so the identifier and password fields that set a
 * KeyboardType satisfy this incidentally — the test guards against that wiring
 * being dropped, and against a credential field that sets no KeyboardType (the
 * change-password field was such a gap).
 *
 * Password fields are pinned to the exact sensitive type; identifier fields are
 * pinned to "carries a content type" because EmailAddress is a cached singleton
 * but a combined value would not be (AndroidContentType defines no equals), so
 * keyIsDefined is the strongest assertion that stays robust either way.
 *
 * Runs under Robolectric; run the class in isolation given the repo's multi-class
 * native-crash flake. Each @Test renders exactly one sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class CredentialFieldAutofillSemanticsTest {

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

    private fun hasAutofillContentType() =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentType)

    private fun isPasswordField() =
        SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.Password)

    // --- iCloud sign-in ---

    @Test
    fun `iCloud Apple ID field advertises an autofill content type`() {
        renderICloud()
        composeTestRule.onNodeWithText(str(R.string.label_apple_id))
            .assert(hasAutofillContentType())
    }

    @Test
    fun `iCloud app-specific password field is marked as a password for autofill`() {
        renderICloud()
        composeTestRule.onNodeWithText(str(R.string.label_app_password))
            .assert(isPasswordField())
    }

    // --- CalDAV sign-in ---

    @Test
    fun `CalDAV username field advertises an autofill content type`() {
        renderCalDav()
        composeTestRule.onNodeWithText(str(R.string.label_username))
            .assert(hasAutofillContentType())
    }

    @Test
    fun `CalDAV password field is marked as a password for autofill`() {
        renderCalDav()
        composeTestRule.onNodeWithText(str(R.string.label_password))
            .assert(isPasswordField())
    }

    // --- Change password ---

    @Test
    fun `change-password field is marked as a password for autofill`() {
        renderChangePassword()
        composeTestRule.onNodeWithText(str(R.string.change_password_label_default))
            .assert(isPasswordField())
    }

    private fun renderICloud() {
        composeTestRule.setContent {
            MaterialTheme {
                ICloudSignInSheet(
                    appleId = "",
                    password = "",
                    showHelp = false,
                    error = null,
                    isConnecting = false,
                    onAppleIdChange = {},
                    onPasswordChange = {},
                    onToggleHelp = {},
                    onSignIn = {},
                    onDismiss = {},
                )
            }
        }
    }

    private fun renderCalDav() {
        composeTestRule.setContent {
            MaterialTheme {
                CalDavSignInSheet(
                    state = CalDavConnectionState.NotConnected(),
                    onServerUrlChange = {},
                    onDisplayNameChange = {},
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onTrustInsecureChange = {},
                    onDiscover = {},
                    onDismiss = {},
                )
            }
        }
    }

    private fun renderChangePassword() {
        composeTestRule.setContent {
            MaterialTheme {
                ChangePasswordSheet(
                    sheetState = rememberModalBottomSheetState(),
                    provider = AccountProvider.CALDAV,
                    isValidating = false,
                    error = null,
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
    }
}
