package org.onekash.kashcal.sync.adapter

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.onekash.kashcal.MainActivity
import org.onekash.kashcal.sync.adapter.KashCalContactsAuthenticator.Companion.ACCOUNT_TYPE

/**
 * Stub AccountAuthenticator for the ContactsProvider registration.
 *
 * This authenticator does NOT manage real credentials — those are handled by
 * [org.onekash.kashcal.data.credential.CredentialManager] in EncryptedSharedPreferences.
 *
 * Its sole purpose is to register the dedicated [ACCOUNT_TYPE] with Android's
 * AccountManager so a per-login, email-named contacts account can be created
 * (see [ContactSystemAccountRegistrar]). A distinct type keeps contacts an
 * independent Contacts source, separate from the singleton calendar account
 * ([KashCalAuthenticator], type `org.onekash.kashcal`). Without a registered
 * account type Android purges any RawContacts written under it.
 */
class KashCalContactsAuthenticator(
    private val context: Context
) : AbstractAccountAuthenticator(context) {

    companion object {
        const val ACCOUNT_TYPE = "org.onekash.kashcal.contacts"
    }

    override fun addAccount(
        response: AccountAuthenticatorResponse,
        accountType: String,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?
    ): Bundle {
        // Settings > Add account → open KashCal app.
        // Account creation happens through KashCal's own UI, not system settings.
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return Bundle().apply {
            putParcelable(AccountManager.KEY_INTENT, intent)
        }
    }

    override fun getAuthToken(
        response: AccountAuthenticatorResponse,
        account: Account,
        authTokenType: String,
        options: Bundle?
    ): Bundle = Bundle().apply {
        putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
    }

    override fun getAuthTokenLabel(authTokenType: String): String = ""

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse,
        account: Account,
        options: Bundle?
    ): Bundle? = null

    override fun updateCredentials(
        response: AccountAuthenticatorResponse,
        account: Account,
        authTokenType: String?,
        options: Bundle?
    ): Bundle = throw UnsupportedOperationException()

    override fun hasFeatures(
        response: AccountAuthenticatorResponse,
        account: Account,
        features: Array<out String>
    ): Bundle = Bundle().apply {
        putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)
    }

    override fun editProperties(
        response: AccountAuthenticatorResponse,
        accountType: String
    ): Bundle = throw UnsupportedOperationException()
}
