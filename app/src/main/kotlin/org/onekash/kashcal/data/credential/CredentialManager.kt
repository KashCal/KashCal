package org.onekash.kashcal.data.credential

/**
 * Unified credential storage interface for all account types.
 *
 * Uses a single account-keyed storage pattern. Credentials are stored using
 * EncryptedSharedPreferences with Android Keystore.
 *
 * Storage format: `account_{id}_{field}` in `unified_credentials` prefs file
 *
 * Usage:
 * ```kotlin
 * // Save credentials for account
 * credentialManager.saveCredentials(accountId, AccountCredentials(
 *     username = "user@example.com",
 *     password = "app-specific-password",
 *     serverUrl = "https://caldav.example.com"
 * ))
 *
 * // Retrieve credentials
 * val creds = credentialManager.getCredentials(accountId)
 * ```
 */
interface CredentialManager {

    /**
     * Check if encryption is available on this device.
     * Returns false if Android Keystore is unavailable or locked.
     */
    fun isEncryptionAvailable(): Boolean

    /**
     * Save credentials for an account.
     *
     * @param accountId Room database account ID
     * @param credentials Complete credential data
     * @return true if saved successfully, false on encryption failure
     */
    suspend fun saveCredentials(accountId: Long, credentials: AccountCredentials): Boolean

    /**
     * Retrieve credentials for an account.
     *
     * @param accountId Room database account ID
     * @return Credentials if found and decrypted, null otherwise
     */
    suspend fun getCredentials(accountId: Long): AccountCredentials?

    /**
     * Check if credentials exist for an account.
     *
     * @param accountId Room database account ID
     * @return true if credentials are stored (may still fail decryption)
     */
    suspend fun hasCredentials(accountId: Long): Boolean

    /**
     * Delete credentials for an account.
     * Silently succeeds if no credentials exist.
     *
     * @param accountId Room database account ID
     */
    suspend fun deleteCredentials(accountId: Long)

    /**
     * Clear all stored credentials.
     * Used for debugging or complete app reset.
     */
    suspend fun clearAllCredentials()
}
