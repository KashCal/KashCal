package org.onekash.kashcal.sync.provider.caldav

import android.util.Log
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.auth.CredentialProvider
import org.onekash.kashcal.sync.auth.Credentials
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CredentialProvider implementation for generic CalDAV accounts.
 *
 * Unlike ICloudCredentialProvider which uses a single set of credentials,
 * this provider supports multiple CalDAV accounts with independent credentials.
 * Each account's credentials are keyed by accountId in encrypted storage.
 *
 * Architecture:
 * ```
 * CalDavSyncWorker
 *       |
 * CalDavCredentialProvider (this class)
 *       |
 * CalDavCredentialManager (EncryptedSharedPreferences)
 *       |
 * Android Keystore (AES-256-GCM)
 * ```
 *
 * Usage:
 * ```
 * // Get credentials for sync
 * val credentials = credentialProvider.getCredentials(accountId)
 *
 * // Save credentials after account setup
 * credentialProvider.saveCredentials(accountId, credentials)
 *
 * // Check if specific account has credentials
 * if (credentialProvider.hasCredentials(accountId)) { ... }
 *
 * // Get self-signed certificate setting
 * val trustInsecure = credentialProvider.getTrustInsecure(accountId)
 * ```
 */
@Singleton
class CalDavCredentialProvider @Inject constructor(
    private val credentialManager: CalDavCredentialManager,
    private val accountsDao: AccountsDao
) : CredentialProvider {

    companion object {
        private const val TAG = "CalDavCredentialProvider"
    }

    /**
     * Get credentials for a specific CalDAV account.
     *
     * @param accountId The database account ID
     * @return Credentials if available, null if not configured
     */
    override suspend fun getCredentials(accountId: Long): Credentials? {
        if (!credentialManager.isEncryptionAvailable()) {
            Log.w(TAG, "Encryption not available: ${credentialManager.getEncryptionError()}")
            return null
        }

        val caldavCredentials = credentialManager.getCredentials(accountId)
        if (caldavCredentials == null) {
            Log.d(TAG, "No credentials for account $accountId")
            return null
        }

        Log.d(TAG, "Loaded credentials for account $accountId: ${caldavCredentials.username.take(3)}***")

        return Credentials(
            username = caldavCredentials.username,
            password = caldavCredentials.password,
            serverUrl = caldavCredentials.serverUrl,
            trustInsecure = caldavCredentials.trustInsecure
        )
    }

    /**
     * Get credentials for the "primary" CalDAV account.
     * Since CalDAV supports multiple accounts, this returns credentials for
     * the first enabled CalDAV account found, or null if none exist.
     *
     * For explicit account access, use [getCredentials(accountId)] instead.
     *
     * @return Credentials for first enabled CalDAV account, or null
     */
    override suspend fun getPrimaryCredentials(): Credentials? {
        // Find first enabled CalDAV account
        val caldavAccounts = accountsDao.getByProvider(AccountProvider.CALDAV)
        val enabledAccount = caldavAccounts.firstOrNull { account -> account.isEnabled }

        return if (enabledAccount != null) {
            getCredentials(enabledAccount.id)
        } else {
            null
        }
    }

    /**
     * Check if credentials are available for an account.
     *
     * @param accountId The database account ID
     * @return true if credentials exist
     */
    override suspend fun hasCredentials(accountId: Long): Boolean {
        return credentialManager.hasCredentials(accountId)
    }

    /**
     * Check if any CalDAV accounts have credentials configured.
     *
     * @return true if at least one CalDAV account has credentials
     */
    override suspend fun hasAnyCredentials(): Boolean {
        return credentialManager.getAllAccountIds().isNotEmpty()
    }

    /**
     * Save credentials for a CalDAV account.
     *
     * @param accountId The database account ID
     * @param credentials The credentials to save
     * @return true if saved successfully
     */
    override suspend fun saveCredentials(accountId: Long, credentials: Credentials): Boolean {
        if (!credentialManager.isEncryptionAvailable()) {
            Log.e(TAG, "Cannot save credentials: encryption not available")
            return false
        }

        val caldavCredentials = CalDavCredentials(
            serverUrl = credentials.serverUrl,
            username = credentials.username,
            password = credentials.password,
            trustInsecure = false // Default to secure, update via setTrustInsecure()
        )

        val saved = credentialManager.saveCredentials(accountId, caldavCredentials)
        if (saved) {
            Log.i(TAG, "Saved credentials for account $accountId: ${credentials.username.take(3)}***")

            // Update account credential key
            updateAccountCredentialKey(accountId, credentials.username)
        }

        return saved
    }

    /**
     * Save credentials with self-signed certificate trust setting.
     *
     * @param accountId The database account ID
     * @param credentials The credentials to save
     * @param trustInsecure Whether to trust self-signed certificates
     * @return true if saved successfully
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

        val caldavCredentials = CalDavCredentials(
            serverUrl = credentials.serverUrl,
            username = credentials.username,
            password = credentials.password,
            trustInsecure = trustInsecure
        )

        val saved = credentialManager.saveCredentials(accountId, caldavCredentials)
        if (saved) {
            Log.i(TAG, "Saved credentials for account $accountId with trustInsecure=$trustInsecure")
            updateAccountCredentialKey(accountId, credentials.username)
        }

        return saved
    }

    /**
     * Delete credentials for a CalDAV account.
     * IMPORTANT: Call this BEFORE deleting the account from Room.
     *
     * @param accountId The database account ID
     * @return true if deleted (or didn't exist)
     */
    override suspend fun deleteCredentials(accountId: Long): Boolean {
        credentialManager.deleteCredentials(accountId)
        Log.i(TAG, "Deleted credentials for account $accountId")

        // Clear credential key from database account
        clearAccountCredentialKey(accountId)

        return true
    }

    /**
     * Clear all stored CalDAV credentials.
     * Use when user wants to remove all CalDAV accounts.
     */
    override suspend fun clearAllCredentials() {
        credentialManager.clearAllCredentials()
        Log.i(TAG, "Cleared all CalDAV credentials")
    }

    /**
     * Check if an account should trust self-signed certificates.
     *
     * @param accountId The database account ID
     * @return true if trustInsecure is enabled, false otherwise
     */
    fun getTrustInsecure(accountId: Long): Boolean {
        val credentials = credentialManager.getCredentials(accountId)
        return credentials?.trustInsecure ?: false
    }

    /**
     * Update the trust setting for an account.
     *
     * @param accountId The database account ID
     * @param trustInsecure Whether to trust self-signed certificates
     * @return true if updated successfully
     */
    fun setTrustInsecure(accountId: Long, trustInsecure: Boolean): Boolean {
        val credentials = credentialManager.getCredentials(accountId) ?: return false
        return credentialManager.saveCredentials(accountId, credentials.copy(trustInsecure = trustInsecure))
    }

    /**
     * Get the raw CalDavCredentials with all fields including trustInsecure.
     * Used by CalDavClientFactory to configure SSL trust.
     *
     * @param accountId The database account ID
     * @return CalDavCredentials or null if not found
     */
    fun getCalDavCredentials(accountId: Long): CalDavCredentials? {
        return credentialManager.getCredentials(accountId)
    }

    /**
     * Update the credential_key field in the Account entity.
     * This links the database account to the encrypted credential storage.
     */
    private suspend fun updateAccountCredentialKey(accountId: Long, credentialKey: String) {
        try {
            val account = accountsDao.getById(accountId)
            if (account != null) {
                accountsDao.update(account.copy(credentialKey = credentialKey))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update account credential key: ${e.message}")
        }
    }

    /**
     * Clear the credential_key field in the Account entity.
     */
    private suspend fun clearAccountCredentialKey(accountId: Long) {
        try {
            val account = accountsDao.getById(accountId)
            if (account != null) {
                accountsDao.update(account.copy(credentialKey = null))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear account credential key: ${e.message}")
        }
    }
}
