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

    /** Returns the credentials for [accountId], or null if missing or the store is unavailable. */
    suspend fun getCredentials(accountId: Long): AccountCredentials?

    /** Returns true if credentials are stored for [accountId]; they may still fail to decrypt. */
    suspend fun hasCredentials(accountId: Long): Boolean

    /** Deletes the credentials for [accountId]; silently succeeds when none exist. */
    suspend fun deleteCredentials(accountId: Long)

    /** Deletes every account's stored credentials. */
    suspend fun clearAllCredentials()
}
