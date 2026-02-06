package org.onekash.kashcal.sync.provider.icloud

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
 * CredentialProvider implementation for iCloud accounts.
 *
 * Delegates to unified CredentialManager for encrypted storage.
 * Supports multiple iCloud accounts via account-keyed credentials.
 *
 * Architecture:
 * ```
 * CalDavSyncWorker
 *       |
 * ICloudCredentialProvider (this class)
 *       |
 * CredentialManager (UnifiedCredentialManager)
 *       |
 * EncryptedSharedPreferences → Android Keystore (AES-256-GCM)
 * ```
 *
 * Security:
 * - Credentials encrypted at rest using AES-256-GCM
 * - Master key stored in Android Keystore
 * - Account-keyed storage format: account_{id}_{field}
 */
@Singleton
class ICloudCredentialProvider @Inject constructor(
    private val credentialManager: CredentialManager,
    private val accountRepository: AccountRepository
) : CredentialProvider {

    companion object {
        private const val TAG = "ICloudCredentialProvider"
    }

    override suspend fun getCredentials(accountId: Long): Credentials? {
        if (!credentialManager.isEncryptionAvailable()) {
            Log.w(TAG, "Encryption not available")
            return null
        }

        val accountCredentials = credentialManager.getCredentials(accountId) ?: return null

        Log.d(TAG, "Loaded credentials for account $accountId: ${accountCredentials.username.take(3)}***")

        return Credentials(
            username = accountCredentials.username,
            password = accountCredentials.password,
            serverUrl = accountCredentials.serverUrl
        )
    }

    override suspend fun getPrimaryCredentials(): Credentials? {
        // Find first enabled iCloud account
        val icloudAccounts = accountRepository.getAccountsByProvider(AccountProvider.ICLOUD)
        val enabledAccount = icloudAccounts.firstOrNull { it.isEnabled }

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
        val icloudAccounts = accountRepository.getAccountsByProvider(AccountProvider.ICLOUD)
        return icloudAccounts.any { credentialManager.hasCredentials(it.id) }
    }

    override suspend fun saveCredentials(accountId: Long, credentials: Credentials): Boolean {
        if (!credentialManager.isEncryptionAvailable()) {
            Log.e(TAG, "Cannot save credentials: encryption not available")
            return false
        }

        val accountCredentials = AccountCredentials(
            username = credentials.username,
            password = credentials.password,
            serverUrl = credentials.serverUrl,
            trustInsecure = false  // iCloud never uses self-signed certs
        )

        val saved = credentialManager.saveCredentials(accountId, accountCredentials)
        if (saved) {
            Log.i(TAG, "Saved credentials for account $accountId: ${credentials.username.take(3)}***")
        }

        return saved
    }

    override suspend fun deleteCredentials(accountId: Long): Boolean {
        credentialManager.deleteCredentials(accountId)
        Log.i(TAG, "Deleted credentials for account $accountId")
        return true
    }

    override suspend fun clearAllCredentials() {
        credentialManager.clearAllCredentials()
        Log.i(TAG, "Cleared all credentials")
    }

    /**
     * Get discovered URLs from the stored credentials.
     * Used after initial discovery to avoid re-discovering.
     */
    suspend fun getDiscoveredUrls(accountId: Long): DiscoveredUrls? {
        val creds = credentialManager.getCredentials(accountId) ?: return null
        if (creds.calendarHomeSet == null) return null

        return DiscoveredUrls(
            principalUrl = creds.principalUrl,
            calendarHomeUrl = creds.calendarHomeSet
        )
    }

    /**
     * Save discovered URLs after successful PROPFIND discovery.
     */
    suspend fun saveDiscoveredUrls(accountId: Long, principalUrl: String?, calendarHomeUrl: String) {
        val existingCreds = credentialManager.getCredentials(accountId)
        if (existingCreds != null) {
            val updated = existingCreds.copy(
                principalUrl = principalUrl,
                calendarHomeSet = calendarHomeUrl
            )
            credentialManager.saveCredentials(accountId, updated)
            Log.d(TAG, "Saved discovered URLs for account $accountId: home=$calendarHomeUrl")
        }
    }
}

/**
 * Discovered CalDAV URLs from PROPFIND.
 */
data class DiscoveredUrls(
    val principalUrl: String?,
    val calendarHomeUrl: String?
)
