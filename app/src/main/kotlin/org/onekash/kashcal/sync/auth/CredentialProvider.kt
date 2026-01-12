package org.onekash.kashcal.sync.auth

import org.onekash.kashcal.util.maskEmail

/**
 * Provides credentials for CalDAV sync operations.
 *
 * This interface abstracts credential loading from the sync layer,
 * enabling:
 * - Production: Load from EncryptedSharedPreferences (ICloudAuthManager)
 * - Testing: Load from properties files or mocks
 *
 * Usage in sync layer:
 * ```
 * val credentials = credentialProvider.getCredentials(accountId)
 * if (credentials != null) {
 *     client.setCredentials(credentials.username, credentials.password)
 * }
 * ```
 */
interface CredentialProvider {

    /**
     * Get credentials for a specific account.
     *
     * @param accountId The database account ID
     * @return Credentials if available, null if not configured
     */
    suspend fun getCredentials(accountId: Long): Credentials?

    /**
     * Get credentials for the primary iCloud account.
     * Convenience method for single-account scenarios.
     *
     * @return Credentials if configured, null otherwise
     */
    suspend fun getPrimaryCredentials(): Credentials?

    /**
     * Check if credentials are available for an account.
     *
     * @param accountId The database account ID
     * @return true if credentials exist
     */
    suspend fun hasCredentials(accountId: Long): Boolean

    /**
     * Check if any credentials are configured.
     *
     * @return true if at least one account has credentials
     */
    suspend fun hasAnyCredentials(): Boolean

    /**
     * Save credentials for an account.
     *
     * @param accountId The database account ID
     * @param credentials The credentials to save
     * @return true if saved successfully
     */
    suspend fun saveCredentials(accountId: Long, credentials: Credentials): Boolean

    /**
     * Delete credentials for an account.
     *
     * @param accountId The database account ID
     * @return true if deleted (or didn't exist)
     */
    suspend fun deleteCredentials(accountId: Long): Boolean

    /**
     * Clear all stored credentials.
     * Use when user logs out of all accounts.
     */
    suspend fun clearAllCredentials()
}

/**
 * Credentials for CalDAV authentication.
 *
 * @property username The username (typically email for iCloud)
 * @property password The password (app-specific password for iCloud)
 * @property serverUrl Optional server URL override
 */
data class Credentials(
    val username: String,
    val password: String,
    val serverUrl: String = DEFAULT_ICLOUD_SERVER
) {
    companion object {
        const val DEFAULT_ICLOUD_SERVER = "https://caldav.icloud.com"
    }

    /**
     * Mask credentials for safe logging.
     * Never shows any password characters. Username is masked using maskEmail().
     */
    fun toSafeString(): String {
        return "Credentials(username=${username.maskEmail()}, password=****)"
    }
}
