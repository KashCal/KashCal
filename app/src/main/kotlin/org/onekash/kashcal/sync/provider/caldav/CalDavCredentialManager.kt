package org.onekash.kashcal.sync.provider.caldav

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages CalDAV account credentials using encrypted storage.
 *
 * Uses EncryptedSharedPreferences with AES-256-GCM encryption for secure
 * credential storage. Credentials are never stored in plain text.
 *
 * Storage pattern:
 * - Keys are prefixed with account ID: `caldav_{accountId}_{field}`
 * - Stored in separate file: "caldav_credentials" (not shared with iCloud)
 * - Each account's credentials are independent
 *
 * Security:
 * - Keys encrypted with AES256-SIV
 * - Values encrypted with AES256-GCM
 * - Master key stored in Android Keystore
 *
 * Usage:
 * ```
 * @Inject lateinit var credentialManager: CalDavCredentialManager
 *
 * // Save credentials for an account
 * credentialManager.saveCredentials(accountId, CalDavCredentials(...))
 *
 * // Load credentials
 * val credentials = credentialManager.getCredentials(accountId)
 *
 * // Delete when account is removed
 * credentialManager.deleteCredentials(accountId)
 * ```
 */
@Singleton
class CalDavCredentialManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CalDavCredentialManager"
        private const val PREFS_NAME = "caldav_credentials"

        // Key suffixes
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TRUST_INSECURE = "trust_insecure"
    }

    // Track encryption initialization error
    private var encryptionError: Exception? = null

    /**
     * Create master key for encryption.
     * Uses AES256-GCM key scheme stored in Android Keystore.
     */
    private val masterKey: MasterKey? by lazy {
        try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MasterKey: ${e.message}", e)
            encryptionError = e
            null
        }
    }

    /**
     * Create encrypted SharedPreferences.
     * Falls back to null if encryption is not available.
     */
    private val encryptedPrefs: SharedPreferences? by lazy {
        try {
            masterKey?.let { key ->
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences: ${e.message}", e)
            encryptionError = e
            null
        }
    }

    /**
     * Check if encryption is available.
     * Returns false if device doesn't support required encryption.
     */
    fun isEncryptionAvailable(): Boolean = encryptedPrefs != null

    /**
     * Get error message if encryption failed.
     * Returns null if encryption is working.
     */
    fun getEncryptionError(): String? = encryptionError?.message

    /**
     * Save CalDAV credentials for an account.
     *
     * @param accountId The database account ID
     * @param credentials The credentials to save
     * @return true if saved successfully, false if encryption unavailable
     */
    fun saveCredentials(accountId: Long, credentials: CalDavCredentials): Boolean {
        val prefs = encryptedPrefs ?: return false
        // encryptedPrefs uses EncryptedSharedPreferences with AES256 encryption
        // lgtm[kotlin/cleartext-storage-shared-prefs]
        prefs.edit {
            putString(keyFor(accountId, KEY_SERVER_URL), credentials.serverUrl)
            putString(keyFor(accountId, KEY_USERNAME), credentials.username)
            putString(keyFor(accountId, KEY_PASSWORD), credentials.password)
            putBoolean(keyFor(accountId, KEY_TRUST_INSECURE), credentials.trustInsecure)
        }
        Log.d(TAG, "Saved credentials for account $accountId: ${credentials.username.take(3)}***")
        return true
    }

    /**
     * Get CalDAV credentials for an account.
     *
     * @param accountId The database account ID
     * @return The credentials or null if not found or encryption unavailable
     */
    fun getCredentials(accountId: Long): CalDavCredentials? {
        val prefs = encryptedPrefs ?: return null
        val serverUrl = prefs.getString(keyFor(accountId, KEY_SERVER_URL), null) ?: return null
        val username = prefs.getString(keyFor(accountId, KEY_USERNAME), null) ?: return null
        val password = prefs.getString(keyFor(accountId, KEY_PASSWORD), null) ?: return null
        val trustInsecure = prefs.getBoolean(keyFor(accountId, KEY_TRUST_INSECURE), false)
        return CalDavCredentials(
            serverUrl = serverUrl,
            username = username,
            password = password,
            trustInsecure = trustInsecure
        )
    }

    /**
     * Delete CalDAV credentials for an account.
     * IMPORTANT: Call this BEFORE deleting the account from Room to prevent orphaned credentials.
     *
     * @param accountId The database account ID
     */
    fun deleteCredentials(accountId: Long) {
        encryptedPrefs?.edit {
            remove(keyFor(accountId, KEY_SERVER_URL))
            remove(keyFor(accountId, KEY_USERNAME))
            remove(keyFor(accountId, KEY_PASSWORD))
            remove(keyFor(accountId, KEY_TRUST_INSECURE))
        }
        Log.d(TAG, "Deleted credentials for account $accountId")
    }

    /**
     * Check if credentials exist for an account.
     *
     * @param accountId The database account ID
     * @return true if credentials exist, false otherwise
     */
    fun hasCredentials(accountId: Long): Boolean {
        return encryptedPrefs?.contains(keyFor(accountId, KEY_PASSWORD)) == true
    }

    /**
     * Get all account IDs with stored credentials.
     * Useful for cleanup and debugging.
     *
     * @return Set of account IDs with stored credentials
     */
    fun getAllAccountIds(): Set<Long> {
        val prefs = encryptedPrefs ?: return emptySet()
        return prefs.all.keys
            .filter { it.startsWith("caldav_") && it.endsWith("_$KEY_PASSWORD") }
            .mapNotNull { key ->
                // Extract account ID from "caldav_{accountId}_password"
                val idPart = key.removePrefix("caldav_").removeSuffix("_$KEY_PASSWORD")
                idPart.toLongOrNull()
            }
            .toSet()
    }

    /**
     * Clear all CalDAV credentials.
     * Use when user wants to remove all CalDAV accounts.
     */
    fun clearAllCredentials() {
        encryptedPrefs?.edit { clear() }
        Log.d(TAG, "Cleared all CalDAV credentials")
    }

    /**
     * Build preference key for an account field.
     */
    private fun keyFor(accountId: Long, field: String): String {
        return "caldav_${accountId}_$field"
    }
}

/**
 * CalDAV account credentials.
 *
 * @property serverUrl The CalDAV server URL (e.g., "https://nextcloud.example.com")
 * @property username The username for authentication
 * @property password The password for authentication
 * @property trustInsecure Whether to trust self-signed certificates for this server
 */
data class CalDavCredentials(
    val serverUrl: String,
    val username: String,
    val password: String,
    val trustInsecure: Boolean = false
)
