package org.onekash.kashcal.sync.provider.icloud

import android.util.Log
import org.onekash.kashcal.sync.auth.CredentialProvider
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.data.db.dao.AccountsDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CredentialProvider implementation using ICloudAuthManager.
 *
 * Provides credentials from encrypted storage for CalDAV sync operations.
 * Currently supports single iCloud account; multi-account support planned.
 *
 * Architecture:
 * ```
 * CalDavSyncWorker
 *       |
 * ICloudCredentialProvider
 *       |
 * ICloudAuthManager (EncryptedSharedPreferences)
 *       |
 * Android Keystore (AES-256-GCM)
 * ```
 *
 * Security:
 * - Credentials encrypted at rest using AES-256-GCM
 * - Master key stored in Android Keystore
 * - No credentials logged (uses toSafeString())
 */
@Singleton
class ICloudCredentialProvider @Inject constructor(
    private val authManager: ICloudAuthManager,
    private val accountsDao: AccountsDao
) : CredentialProvider {

    companion object {
        private const val TAG = "ICloudCredentialProvider"

        // Currently we only support a single iCloud account
        // This ID is used as a placeholder for the primary account
        const val PRIMARY_ACCOUNT_ID = 1L
    }

    override suspend fun getCredentials(accountId: Long): Credentials? {
        // For now, we only support the primary iCloud account
        // Multi-account support will require mapping accountId to credential keys
        return getPrimaryCredentials()
    }

    override suspend fun getPrimaryCredentials(): Credentials? {
        if (!authManager.isEncryptionAvailable()) {
            Log.w(TAG, "Encryption not available: ${authManager.getEncryptionError()}")
            return null
        }

        val account = authManager.loadAccount() ?: return null

        if (!account.hasCredentials()) {
            Log.d(TAG, "No credentials configured")
            return null
        }

        Log.d(TAG, "Loaded credentials for: ${account.appleId}")

        return Credentials(
            username = account.appleId,
            password = account.appSpecificPassword,
            serverUrl = account.serverUrl
        )
    }

    override suspend fun hasCredentials(accountId: Long): Boolean {
        return getPrimaryCredentials() != null
    }

    override suspend fun hasAnyCredentials(): Boolean {
        return authManager.isConfigured()
    }

    override suspend fun saveCredentials(accountId: Long, credentials: Credentials): Boolean {
        if (!authManager.isEncryptionAvailable()) {
            Log.e(TAG, "Cannot save credentials: encryption not available")
            return false
        }

        val account = ICloudAccount(
            appleId = credentials.username,
            appSpecificPassword = credentials.password,
            serverUrl = credentials.serverUrl,
            isEnabled = true
        )

        val saved = authManager.saveAccount(account)
        if (saved) {
            Log.i(TAG, "Saved credentials for: ${credentials.username}")

            // Also update the database account with credential reference
            updateAccountCredentialKey(accountId, credentials.username)
        }

        return saved
    }

    override suspend fun deleteCredentials(accountId: Long): Boolean {
        authManager.clearAccount()
        Log.i(TAG, "Deleted credentials for account: $accountId")

        // Clear credential key from database account
        clearAccountCredentialKey(accountId)

        return true
    }

    override suspend fun clearAllCredentials() {
        authManager.clearAccount()
        Log.i(TAG, "Cleared all credentials")
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

    /**
     * Get discovered URLs from the stored account.
     * Used after initial discovery to avoid re-discovering.
     */
    fun getDiscoveredUrls(): DiscoveredUrls? {
        val account = authManager.loadAccount() ?: return null
        if (!account.hasDiscoveredCalendarHome()) return null

        return DiscoveredUrls(
            principalUrl = account.principalUrl,
            calendarHomeUrl = account.calendarHomeUrl
        )
    }

    /**
     * Save discovered URLs after successful PROPFIND discovery.
     */
    fun saveDiscoveredUrls(principalUrl: String?, calendarHomeUrl: String) {
        authManager.updateCalendarHomeUrl(calendarHomeUrl, principalUrl)
        Log.d(TAG, "Saved discovered URLs: home=$calendarHomeUrl")
    }

    /**
     * Get the sync token for incremental sync.
     */
    fun getSyncToken(): String? {
        return authManager.loadAccount()?.syncToken
    }

    /**
     * Update sync token after successful sync.
     */
    fun updateSyncToken(token: String?) {
        authManager.updateSyncToken(token)
    }
}

/**
 * Discovered CalDAV URLs from PROPFIND.
 */
data class DiscoveredUrls(
    val principalUrl: String?,
    val calendarHomeUrl: String?
)
