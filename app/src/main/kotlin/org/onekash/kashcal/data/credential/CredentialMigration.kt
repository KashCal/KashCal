package org.onekash.kashcal.data.credential

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.model.AccountProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles one-time migration of credentials from old format to unified format.
 *
 * Migration sources:
 * - iCloud: Single-key format in `icloud_credentials` (apple_id, app_password)
 * - CalDAV: Account-keyed format in `caldav_credentials` (caldav_{id}_username)
 *
 * Migration target:
 * - Unified: Account-keyed format in `unified_credentials` (account_{id}_username)
 *
 * Safety:
 * - Idempotent: DataStore flag prevents duplicate migration
 * - Non-destructive: Old credentials preserved until Phase 12 cleanup
 * - Failure-safe: Partial migration retries on next app launch
 */
@Singleton
class CredentialMigration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountsDao: AccountsDao,
    private val credentialManager: CredentialManager,
    private val dataStore: KashCalDataStore
) {
    companion object {
        private const val TAG = "CredentialMigration"

        // DataStore key for migration flag
        private val KEY_CREDENTIALS_MIGRATED = booleanPreferencesKey("credentials_migrated_to_unified")

        // Old preferences file names
        private const val ICLOUD_PREFS_NAME = "icloud_credentials"
        private const val CALDAV_PREFS_NAME = "caldav_credentials"

        // Old iCloud keys (single-key format)
        private const val ICLOUD_KEY_APPLE_ID = "apple_id"
        private const val ICLOUD_KEY_APP_PASSWORD = "app_password"
        private const val ICLOUD_KEY_SERVER_URL = "server_url"
        private const val ICLOUD_KEY_CALENDAR_HOME_URL = "calendar_home_url"
        private const val ICLOUD_KEY_PRINCIPAL_URL = "principal_url"

        // Old CalDAV keys (account-keyed format)
        private const val CALDAV_KEY_SERVER_URL = "server_url"
        private const val CALDAV_KEY_USERNAME = "username"
        private const val CALDAV_KEY_PASSWORD = "password"
        private const val CALDAV_KEY_TRUST_INSECURE = "trust_insecure"
    }

    /**
     * Results of migration attempt.
     */
    sealed class MigrationResult {
        data object AlreadyMigrated : MigrationResult()
        data object Success : MigrationResult()
        data object NoCredentialsToMigrate : MigrationResult()
        data class PartialSuccess(
            val icloudSuccess: Boolean,
            val caldavSuccess: Boolean,
            val icloudError: String? = null,
            val caldavError: String? = null
        ) : MigrationResult()
        data class Failed(val error: String) : MigrationResult()
    }

    /**
     * Run migration if needed.
     *
     * Safe to call multiple times - will return AlreadyMigrated if previously completed.
     */
    suspend fun migrateIfNeeded(): MigrationResult {
        // Check if already migrated (atomic flag)
        if (hasMigrationCompleted()) {
            Log.d(TAG, "Credentials already migrated, skipping")
            return MigrationResult.AlreadyMigrated
        }

        Log.i(TAG, "Starting credential migration")

        // Check if there are any credentials to migrate
        val hasICloudCreds = hasOldICloudCredentials()
        val hasCalDavCreds = hasOldCalDavCredentials()

        if (!hasICloudCreds && !hasCalDavCreds) {
            Log.i(TAG, "No credentials to migrate (fresh install)")
            setMigrationComplete()
            return MigrationResult.NoCredentialsToMigrate
        }

        var icloudSuccess = !hasICloudCreds  // True if nothing to migrate
        var caldavSuccess = !hasCalDavCreds
        var icloudError: String? = null
        var caldavError: String? = null

        // Migrate iCloud credentials
        if (hasICloudCreds) {
            try {
                icloudSuccess = migrateICloudCredentials()
                if (!icloudSuccess) {
                    icloudError = "Account not found in database"
                }
            } catch (e: Exception) {
                Log.e(TAG, "iCloud migration failed", e)
                icloudError = e.message
            }
        }

        // Migrate CalDAV credentials
        if (hasCalDavCreds) {
            try {
                caldavSuccess = migrateCalDavCredentials()
            } catch (e: Exception) {
                Log.e(TAG, "CalDAV migration failed", e)
                caldavError = e.message
            }
        }

        // Mark migration complete if both succeeded
        if (icloudSuccess && caldavSuccess) {
            setMigrationComplete()
            Log.i(TAG, "Credential migration completed successfully")
            return MigrationResult.Success
        }

        // Partial success - don't set flag, retry on next launch
        Log.w(TAG, "Partial migration: iCloud=$icloudSuccess, CalDAV=$caldavSuccess")
        return MigrationResult.PartialSuccess(
            icloudSuccess = icloudSuccess,
            caldavSuccess = caldavSuccess,
            icloudError = icloudError,
            caldavError = caldavError
        )
    }

    private suspend fun hasMigrationCompleted(): Boolean {
        return dataStore.dataStore.data.first()[KEY_CREDENTIALS_MIGRATED] == true
    }

    private suspend fun setMigrationComplete() {
        dataStore.dataStore.edit { prefs ->
            prefs[KEY_CREDENTIALS_MIGRATED] = true
        }
        Log.d(TAG, "Migration flag set")
    }

    /**
     * Migrate iCloud credentials from single-key format to account-keyed.
     *
     * Old format: apple_id, app_password (no account ID prefix)
     * New format: account_{id}_username, account_{id}_password
     */
    private suspend fun migrateICloudCredentials(): Boolean {
        val oldPrefs = getOldICloudPrefs() ?: return false

        // Read old format
        val appleId = oldPrefs.getString(ICLOUD_KEY_APPLE_ID, null) ?: return false
        val appPassword = oldPrefs.getString(ICLOUD_KEY_APP_PASSWORD, null) ?: return false

        Log.d(TAG, "Found iCloud credentials for: ${appleId.take(3)}***")

        // Find account ID from Room (appleId == email in Account table)
        val account = accountsDao.getByProviderAndEmail(AccountProvider.ICLOUD, appleId)
        if (account == null) {
            Log.w(TAG, "No iCloud account found in database for: ${appleId.take(3)}***")
            // This is expected if user signed in but account wasn't created yet
            // Migration will retry on next launch
            return false
        }

        // Read optional fields
        val serverUrl = oldPrefs.getString(ICLOUD_KEY_SERVER_URL, null)
            ?: AccountCredentials.ICLOUD_DEFAULT_SERVER_URL
        val principalUrl = oldPrefs.getString(ICLOUD_KEY_PRINCIPAL_URL, null)
        val calendarHomeSet = oldPrefs.getString(ICLOUD_KEY_CALENDAR_HOME_URL, null)

        // Write to unified format
        val credentials = AccountCredentials(
            username = appleId,  // appleId stored in username field
            password = appPassword,
            serverUrl = serverUrl,
            trustInsecure = false,  // iCloud never uses self-signed certs
            principalUrl = principalUrl,
            calendarHomeSet = calendarHomeSet
        )

        val success = credentialManager.saveCredentials(account.id, credentials)
        if (success) {
            Log.i(TAG, "Migrated iCloud credentials for account ${account.id}")
        } else {
            Log.e(TAG, "Failed to save migrated iCloud credentials")
        }
        return success
    }

    /**
     * Migrate CalDAV credentials from old format to unified format.
     *
     * Old format: caldav_{id}_username (in caldav_credentials file)
     * New format: account_{id}_username (in unified_credentials file)
     */
    private suspend fun migrateCalDavCredentials(): Boolean {
        val oldPrefs = getOldCalDavPrefs() ?: return false

        // Find all account IDs with stored credentials
        val accountIds = oldPrefs.all.keys
            .filter { it.startsWith("caldav_") && it.endsWith("_password") }
            .mapNotNull { key ->
                val idPart = key.removePrefix("caldav_").removeSuffix("_password")
                idPart.toLongOrNull()
            }
            .toSet()

        if (accountIds.isEmpty()) {
            Log.d(TAG, "No CalDAV credentials to migrate")
            return true  // Nothing to migrate is success
        }

        Log.d(TAG, "Found ${accountIds.size} CalDAV account(s) to migrate")

        var allSuccess = true
        for (accountId in accountIds) {
            val serverUrl = oldPrefs.getString(caldavKeyFor(accountId, CALDAV_KEY_SERVER_URL), null)
            val username = oldPrefs.getString(caldavKeyFor(accountId, CALDAV_KEY_USERNAME), null)
            val password = oldPrefs.getString(caldavKeyFor(accountId, CALDAV_KEY_PASSWORD), null)
            val trustInsecure = oldPrefs.getBoolean(caldavKeyFor(accountId, CALDAV_KEY_TRUST_INSECURE), false)

            if (serverUrl == null || username == null || password == null) {
                Log.w(TAG, "Incomplete CalDAV credentials for account $accountId, skipping")
                continue
            }

            // Verify account exists in database
            val account = accountsDao.getById(accountId)
            if (account == null) {
                Log.w(TAG, "CalDAV account $accountId not found in database, skipping")
                continue
            }

            val credentials = AccountCredentials(
                username = username,
                password = password,
                serverUrl = serverUrl,
                trustInsecure = trustInsecure,
                principalUrl = null,  // CalDAV didn't store these in old format
                calendarHomeSet = null
            )

            val success = credentialManager.saveCredentials(accountId, credentials)
            if (success) {
                Log.i(TAG, "Migrated CalDAV credentials for account $accountId")
            } else {
                Log.e(TAG, "Failed to save migrated CalDAV credentials for account $accountId")
                allSuccess = false
            }
        }

        return allSuccess
    }

    private fun caldavKeyFor(accountId: Long, field: String): String {
        return "caldav_${accountId}_$field"
    }

    private fun hasOldICloudCredentials(): Boolean {
        val prefs = getOldICloudPrefs() ?: return false
        return prefs.contains(ICLOUD_KEY_APPLE_ID) && prefs.contains(ICLOUD_KEY_APP_PASSWORD)
    }

    private fun hasOldCalDavCredentials(): Boolean {
        val prefs = getOldCalDavPrefs() ?: return false
        return prefs.all.keys.any { it.startsWith("caldav_") && it.endsWith("_password") }
    }

    /**
     * Get old iCloud encrypted SharedPreferences.
     * Returns null if encryption unavailable or prefs don't exist.
     */
    private fun getOldICloudPrefs(): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                ICLOUD_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not open old iCloud prefs: ${e.message}")
            null
        }
    }

    /**
     * Get old CalDAV encrypted SharedPreferences.
     * Returns null if encryption unavailable or prefs don't exist.
     */
    private fun getOldCalDavPrefs(): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                CALDAV_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not open old CalDAV prefs: ${e.message}")
            null
        }
    }
}
