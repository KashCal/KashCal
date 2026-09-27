package org.onekash.kashcal.sync.provider.caldav

import android.util.Log
import org.onekash.kashcal.data.credential.AccountCredentials
import org.onekash.kashcal.data.credential.CredentialManager
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.auth.CredentialProvider
import org.onekash.kashcal.sync.auth.Credentials
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the credentials of generic CalDAV accounts, keyed by account ID, and carries each
 * account's `trustInsecure` (self-signed certificate) setting.
 *
 * ```
 * CalDavSyncWorker, ContactSyncWorker (through ProviderRegistry)
 *       |
 * CalDavCredentialProvider (this class)
 *       |
 * CredentialManager (UnifiedCredentialManager)
 *       |
 * EncryptedSharedPreferences → Android Keystore (AES-256-GCM)
 * ```
 */
@Singleton
class CalDavCredentialProvider @Inject constructor(
    private val credentialManager: CredentialManager,
    private val accountRepository: AccountRepository
) : CredentialProvider {

    companion object {
        private const val TAG = "CalDavCredentialProvider"
    }

    /** Returns null also when encryption is unavailable. */
    override suspend fun getCredentials(accountId: Long): Credentials? {
        if (!credentialManager.isEncryptionAvailable()) {
            Log.w(TAG, "Encryption not available")
            return null
        }

        val accountCredentials = credentialManager.getCredentials(accountId)
        if (accountCredentials == null) {
            Log.d(TAG, "No credentials for account $accountId")
            return null
        }

        Log.d(TAG, "Loaded credentials for account $accountId: ${accountCredentials.username.take(3)}***")

        return Credentials(
            username = accountCredentials.username,
            password = accountCredentials.password,
            serverUrl = accountCredentials.serverUrl,
            trustInsecure = accountCredentials.trustInsecure
        )
    }

    /** Picks the first enabled CalDAV account; callers knowing the account use [getCredentials]. */
    override suspend fun getPrimaryCredentials(): Credentials? {
        val caldavAccounts = accountRepository.getAccountsByProvider(AccountProvider.CALDAV)
        val enabledAccount = caldavAccounts.firstOrNull { account -> account.isEnabled }

        return if (enabledAccount != null) {
            getCredentials(enabledAccount.id)
        } else {
            null
        }
    }

    override suspend fun hasCredentials(accountId: Long): Boolean {
        return credentialManager.hasCredentials(accountId)
    }

    override suspend fun hasAnyCredentials(): Boolean {
        val caldavAccounts = accountRepository.getAccountsByProvider(AccountProvider.CALDAV)
        return caldavAccounts.any { credentialManager.hasCredentials(it.id) }
    }

    /** Returns false when encryption is unavailable. */
    override suspend fun saveCredentials(accountId: Long, credentials: Credentials): Boolean {
        if (!credentialManager.isEncryptionAvailable()) {
            Log.e(TAG, "Cannot save credentials: encryption not available")
            return false
        }

        val accountCredentials = AccountCredentials(
            username = credentials.username,
            password = credentials.password,
            serverUrl = credentials.serverUrl,
            trustInsecure = credentials.trustInsecure
        )

        val saved = credentialManager.saveCredentials(accountId, accountCredentials)
        if (saved) {
            Log.i(TAG, "Saved credentials for account $accountId: ${credentials.username.take(3)}***")
        }

        return saved
    }

    /**
     * Saves [credentials] with [trustInsecure] in place of their own setting, like
     * [saveCredentials]. Returns true on success.
     */
    suspend fun saveCredentialsWithTrust(
        accountId: Long,
        credentials: Credentials,
        trustInsecure: Boolean
    ): Boolean {
        if (!credentialManager.isEncryptionAvailable()) {
            Log.e(TAG, "Cannot save credentials: encryption not available")
            return false
        }

        val accountCredentials = AccountCredentials(
            username = credentials.username,
            password = credentials.password,
            serverUrl = credentials.serverUrl,
            trustInsecure = trustInsecure
        )

        val saved = credentialManager.saveCredentials(accountId, accountCredentials)
        if (saved) {
            Log.i(TAG, "Saved credentials for account $accountId with trustInsecure=$trustInsecure")
        }

        return saved
    }

    override suspend fun deleteCredentials(accountId: Long): Boolean {
        credentialManager.deleteCredentials(accountId)
        Log.i(TAG, "Deleted credentials for account $accountId")
        return true
    }

    /** Clears every stored credential of every provider, iCloud included, not only CalDAV's. */
    override suspend fun clearAllCredentials() {
        credentialManager.clearAllCredentials()
        Log.i(TAG, "Cleared all CalDAV credentials")
    }

    /** Returns whether the account trusts self-signed certificates; false if none are stored. */
    suspend fun getTrustInsecure(accountId: Long): Boolean {
        val credentials = credentialManager.getCredentials(accountId)
        return credentials?.trustInsecure ?: false
    }

    /** Updates the account's `trustInsecure`; returns false if no credentials are stored. */
    suspend fun setTrustInsecure(accountId: Long, trustInsecure: Boolean): Boolean {
        val credentials = credentialManager.getCredentials(accountId) ?: return false
        return credentialManager.saveCredentials(accountId, credentials.copy(trustInsecure = trustInsecure))
    }

    /**
     * Returns the stored [AccountCredentials] with every field, or null if none are stored.
     * Nothing in the app calls it today.
     */
    suspend fun getAccountCredentials(accountId: Long): AccountCredentials? {
        return credentialManager.getCredentials(accountId)
    }
}
