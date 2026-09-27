package org.onekash.kashcal.sync.adapter

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.onekash.kashcal.MainActivity
import org.onekash.kashcal.sync.adapter.KashCalAuthenticator.Companion.ACCOUNT_TYPE

/**
 * Registers the calendar [ACCOUNT_TYPE] with AccountManager; a stub that holds no credentials.
 *
 * Credentials live in [org.onekash.kashcal.data.credential.CredentialManager]
 * (EncryptedSharedPreferences). The registered type is what lets [KashCalSyncAdapter] link to
 * CalendarProvider. Without it, Android doesn't recognize KashCal as a calendar app for
 * `content://com.android.calendar` intent routing.
 */
class KashCalAuthenticator(
    private val context: Context
) : AbstractAccountAuthenticator(context) {

    companion object {
        const val ACCOUNT_TYPE = "org.onekash.kashcal"
    }

    override fun addAccount(
        response: AccountAuthenticatorResponse,
        accountType: String,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?
    ): Bundle {
        // Settings > Add account opens the app: accounts are created in KashCal's own UI.
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
