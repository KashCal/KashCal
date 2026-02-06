package org.onekash.kashcal.data.credential

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified credential storage for all account types.
 *
 * Uses EncryptedSharedPreferences with AES-256-GCM encryption for secure
 * credential storage. Credentials are never stored in plain text.
 *
 * Storage pattern:
 * - Keys are prefixed with "account_": `account_{accountId}_{field}`
 * - All accounts share the same prefs file: "unified_credentials"
 * - Account IDs tracked in `account_ids` key (comma-separated) for efficient lookup
 *
 * Security:
 * - Keys encrypted with AES256-SIV
 * - Values encrypted with AES256-GCM
 * - Master key stored in Android Keystore
 * - Excluded from Android backup (see backup_rules.xml)
 */
@Singleton
class UnifiedCredentialManager @Inject constructor(
    @ApplicationContext private val context: Context
) : CredentialManager {

    companion object {
        private const val TAG = "UnifiedCredentialMgr"
        private const val PREFS_NAME = "unified_credentials"

        // Key suffixes for credential fields
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TRUST_INSECURE = "trust_insecure"
        private const val KEY_PRINCIPAL_URL = "principal_url"
        private const val KEY_CALENDAR_HOME_SET = "calendar_home_set"

        // Index key for efficient account ID lookup
        private const val KEY_ACCOUNT_IDS = "account_ids"
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

    override fun isEncryptionAvailable(): Boolean = encryptedPrefs != null

    /**
     * Get error message if encryption failed.
     * Returns null if encryption is working.
     */
    fun getEncryptionError(): String? = encryptionError?.message

    override suspend fun saveCredentials(accountId: Long, credentials: AccountCredentials): Boolean {
        return withContext(Dispatchers.IO) {
            val prefs = encryptedPrefs ?: return@withContext false

            // encryptedPrefs uses EncryptedSharedPreferences with AES256 encryption
            // lgtm[kotlin/cleartext-storage-shared-prefs]
            prefs.edit {
                putString(keyFor(accountId, KEY_USERNAME), credentials.username)
                putString(keyFor(accountId, KEY_PASSWORD), credentials.password)
                putString(keyFor(accountId, KEY_SERVER_URL), credentials.serverUrl)
                putBoolean(keyFor(accountId, KEY_TRUST_INSECURE), credentials.trustInsecure)
                credentials.principalUrl?.let {
                    putString(keyFor(accountId, KEY_PRINCIPAL_URL), it)
                }
                credentials.calendarHomeSet?.let {
                    putString(keyFor(accountId, KEY_CALENDAR_HOME_SET), it)
                }
            }

            // Update account IDs index
            addAccountIdToIndex(accountId)

            Log.d(TAG, "Saved credentials for account $accountId: ${credentials.username.take(3)}***")
            true
        }
    }

    override suspend fun getCredentials(accountId: Long): AccountCredentials? {
        return withContext(Dispatchers.IO) {
            val prefs = encryptedPrefs ?: return@withContext null

            val username = prefs.getString(keyFor(accountId, KEY_USERNAME), null)
                ?: return@withContext null
            val password = prefs.getString(keyFor(accountId, KEY_PASSWORD), null)
                ?: return@withContext null
            val serverUrl = prefs.getString(keyFor(accountId, KEY_SERVER_URL), null)
                ?: return@withContext null

            AccountCredentials(
                username = username,
                password = password,
                serverUrl = serverUrl,
                trustInsecure = prefs.getBoolean(keyFor(accountId, KEY_TRUST_INSECURE), false),
                principalUrl = prefs.getString(keyFor(accountId, KEY_PRINCIPAL_URL), null),
                calendarHomeSet = prefs.getString(keyFor(accountId, KEY_CALENDAR_HOME_SET), null)
            )
        }
    }

    override suspend fun hasCredentials(accountId: Long): Boolean {
        return withContext(Dispatchers.IO) {
            encryptedPrefs?.contains(keyFor(accountId, KEY_PASSWORD)) == true
        }
    }

    override suspend fun deleteCredentials(accountId: Long) {
        withContext(Dispatchers.IO) {
            encryptedPrefs?.edit {
                remove(keyFor(accountId, KEY_USERNAME))
                remove(keyFor(accountId, KEY_PASSWORD))
                remove(keyFor(accountId, KEY_SERVER_URL))
                remove(keyFor(accountId, KEY_TRUST_INSECURE))
                remove(keyFor(accountId, KEY_PRINCIPAL_URL))
                remove(keyFor(accountId, KEY_CALENDAR_HOME_SET))
            }

            // Remove from account IDs index
            removeAccountIdFromIndex(accountId)

            Log.d(TAG, "Deleted credentials for account $accountId")
        }
    }

    override suspend fun clearAllCredentials() {
        withContext(Dispatchers.IO) {
            encryptedPrefs?.edit { clear() }
            Log.d(TAG, "Cleared all credentials")
        }
    }

    /**
     * Get all account IDs with stored credentials.
     * Useful for migration and debugging.
     */
    suspend fun getAllAccountIds(): Set<Long> {
        return withContext(Dispatchers.IO) {
            val prefs = encryptedPrefs ?: return@withContext emptySet()
            val idsString = prefs.getString(KEY_ACCOUNT_IDS, null)
                ?: return@withContext emptySet()

            idsString.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .toSet()
        }
    }

    /**
     * Update CalDAV discovery URLs for an account.
     * Called after successful CalDAV discovery.
     */
    suspend fun updateDiscoveryUrls(
        accountId: Long,
        principalUrl: String?,
        calendarHomeSet: String?
    ) {
        withContext(Dispatchers.IO) {
            encryptedPrefs?.edit {
                if (principalUrl != null) {
                    putString(keyFor(accountId, KEY_PRINCIPAL_URL), principalUrl)
                }
                if (calendarHomeSet != null) {
                    putString(keyFor(accountId, KEY_CALENDAR_HOME_SET), calendarHomeSet)
                }
            }
        }
    }

    /**
     * Build preference key for an account field.
     */
    private fun keyFor(accountId: Long, field: String): String {
        return "account_${accountId}_$field"
    }

    /**
     * Add account ID to the index for efficient lookup.
     */
    private fun addAccountIdToIndex(accountId: Long) {
        val prefs = encryptedPrefs ?: return
        val currentIds = prefs.getString(KEY_ACCOUNT_IDS, null)
            ?.split(",")
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.toMutableSet()
            ?: mutableSetOf()

        if (currentIds.add(accountId)) {
            prefs.edit {
                putString(KEY_ACCOUNT_IDS, currentIds.joinToString(","))
            }
        }
    }

    /**
     * Remove account ID from the index.
     */
    private fun removeAccountIdFromIndex(accountId: Long) {
        val prefs = encryptedPrefs ?: return
        val currentIds = prefs.getString(KEY_ACCOUNT_IDS, null)
            ?.split(",")
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.toMutableSet()
            ?: return

        if (currentIds.remove(accountId)) {
            prefs.edit {
                putString(KEY_ACCOUNT_IDS, currentIds.joinToString(","))
            }
        }
    }
}
