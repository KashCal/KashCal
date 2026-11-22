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
 * Stores credentials in EncryptedSharedPreferences; they are never stored in plain text.
 *
 * All accounts share the `unified_credentials` prefs file, keyed `account_{accountId}_{field}`.
 * The `account_ids` key holds a comma-separated index of account IDs.
 *
 * Keys are encrypted with AES256-SIV and values with AES256-GCM, under a master key in the
 * Android Keystore. The file is excluded from Android backup (`backup_rules.xml`,
 * `data_extraction_rules.xml`).
 *
 * When the Keystore or prefs can't be created, reads return null or empty, writes are no-ops
 * and [saveCredentials] returns false. A store whose keysets the master key no longer decrypts
 * is deleted and created again (`EncryptedStoreOpener`); only this file is deleted, never the
 * master key, which the legacy files [CredentialMigration] reads share.
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

    // The last MasterKey or prefs creation failure, for [getEncryptionError].
    private var encryptionError: Exception? = null

    /** The AES256-GCM master key in the Android Keystore, or null if it can't be created. */
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

    /** The encrypted prefs, or null if encryption is unavailable. */
    private val encryptedPrefs: SharedPreferences? by lazy {
        val key = masterKey ?: return@lazy null
        val result = EncryptedStoreOpener.open(
            create = {
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            },
            reset = {
                Log.w(TAG, "Stored keysets no longer decrypt; deleting the unreadable credential store")
                // Clearing first empties the copy this process caches; deleting removes the file.
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit(commit = true) { clear() }
                context.deleteSharedPreferences(PREFS_NAME)
            },
        )
        result.error?.let { Log.e(TAG, "Failed to create EncryptedSharedPreferences: ${it.message}", it) }
        encryptionError = result.error
        result.prefs
    }

    override fun isEncryptionAvailable(): Boolean = encryptedPrefs != null

    /** Returns the encryption setup failure's message, or null if encryption works. */
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

            addAccountIdToIndex(accountId)

            Log.d(TAG, "Saved credentials for account $accountId: ${credentials.username.take(3)}***")
            true
        }
    }

    override suspend fun getCredentials(accountId: Long): AccountCredentials? {
        return withContext(Dispatchers.IO) {
            val prefs = encryptedPrefs ?: return@withContext null

            try {
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
            } catch (e: SecurityException) {
                Log.w(TAG, "Stored credentials failed to decrypt")
                null
            }
        }
    }

    override suspend fun hasCredentials(accountId: Long): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                encryptedPrefs?.contains(keyFor(accountId, KEY_PASSWORD)) == true
            } catch (e: SecurityException) {
                Log.w(TAG, "Credential lookup failed to encrypt its key")
                false
            }
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

    /** Returns the account IDs in the `account_ids` index. */
    suspend fun getAllAccountIds(): Set<Long> {
        return withContext(Dispatchers.IO) {
            val prefs = encryptedPrefs ?: return@withContext emptySet()
            readAccountIds(prefs)
        }
    }

    /** Stores the account's discovered CalDAV URLs; a null argument keeps the stored value. */
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

    private fun keyFor(accountId: Long, field: String): String {
        return "account_${accountId}_$field"
    }

    /**
     * Reads the account-id index. An index that fails to decrypt reads as empty, so a save or a
     * delete still succeeds and the next save rewrites it.
     */
    private fun readAccountIds(prefs: SharedPreferences): Set<Long> {
        val idsString = try {
            prefs.getString(KEY_ACCOUNT_IDS, null)
        } catch (e: SecurityException) {
            Log.w(TAG, "Account index failed to decrypt; reading it as empty")
            null
        } ?: return emptySet()
        return idsString.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()
    }

    private fun addAccountIdToIndex(accountId: Long) {
        val prefs = encryptedPrefs ?: return
        val currentIds = readAccountIds(prefs).toMutableSet()

        if (currentIds.add(accountId)) {
            prefs.edit {
                putString(KEY_ACCOUNT_IDS, currentIds.joinToString(","))
            }
        }
    }

    private fun removeAccountIdFromIndex(accountId: Long) {
        val prefs = encryptedPrefs ?: return
        val currentIds = readAccountIds(prefs).toMutableSet()

        if (currentIds.remove(accountId)) {
            prefs.edit {
                putString(KEY_ACCOUNT_IDS, currentIds.joinToString(","))
            }
        }
    }
}
