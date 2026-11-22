package org.onekash.kashcal.data.credential

/**
 * Stores account credentials, keyed by Room account ID, for every account type.
 *
 * Credentials live in EncryptedSharedPreferences backed by the Android Keystore, as
 * `account_{id}_{field}` in the `unified_credentials` prefs file.
 *
 * ```kotlin
 * credentialManager.saveCredentials(accountId, AccountCredentials(
 *     username = "user@example.com",
 *     password = "app-specific-password",
 *     serverUrl = "https://caldav.example.com"
 * ))
 * val creds = credentialManager.getCredentials(accountId)
 * ```
 */
interface CredentialManager {

    /** Returns false if the Android Keystore is unavailable or locked. */
    fun isEncryptionAvailable(): Boolean

    /** Saves [credentials] for [accountId]; returns false when encryption is unavailable. */
    suspend fun saveCredentials(accountId: Long, credentials: AccountCredentials): Boolean

    /**
     * Returns the credentials for [accountId], or null if they are missing, a stored value fails
     * to decrypt, or the store is unavailable.
     */
    suspend fun getCredentials(accountId: Long): AccountCredentials?

    /**
     * Returns true if the password key is stored for [accountId]; the value may still fail to
     * decrypt. Returns false also when the key lookup can't be encrypted.
     */
    suspend fun hasCredentials(accountId: Long): Boolean

    /** Deletes the credentials for [accountId]; silently succeeds when none exist. */
    suspend fun deleteCredentials(accountId: Long)

    /** Deletes every account's stored credentials. */
    suspend fun clearAllCredentials()
}
