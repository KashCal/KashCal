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
 * Copies credentials once from the old prefs files into [CredentialManager]'s unified format.
 *
 * Sources:
 * - iCloud: one unkeyed set in `icloud_credentials` (`apple_id`, `app_password`)
 * - CalDAV: account-keyed sets in `caldav_credentials` (`caldav_{id}_username`)
 *
 * Target: account-keyed sets in `unified_credentials` (`account_{id}_username`).
 *
 * A DataStore flag makes it run once. Nothing deletes the old prefs files. The flag is set when
 * every source that has credentials migrates; a source whose prefs can't be opened counts as
 * having none. Otherwise the flag stays unset and the next app launch retries.
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

        private val KEY_CREDENTIALS_MIGRATED = booleanPreferencesKey("credentials_migrated_to_unified")

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

    /** Outcome of [migrateIfNeeded]. */
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

    /** Runs the migration unless it's done; then returns [MigrationResult.AlreadyMigrated]. */
    suspend fun migrateIfNeeded(): MigrationResult {
        if (hasMigrationCompleted()) {
            Log.d(TAG, "Credentials already migrated, skipping")
            return MigrationResult.AlreadyMigrated
        }

        Log.i(TAG, "Starting credential migration")

        val hasICloudCreds = hasOldICloudCredentials()
        val hasCalDavCreds = hasOldCalDavCredentials()

        if (!hasICloudCreds && !hasCalDavCreds) {
            Log.i(TAG, "No credentials to migrate (fresh install)")
            setMigrationComplete()
            return MigrationResult.NoCredentialsToMigrate
        }

        var icloudSuccess = !hasICloudCreds  // Nothing to migrate counts as success
        var caldavSuccess = !hasCalDavCreds
        var icloudError: String? = null
        var caldavError: String? = null

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

        if (hasCalDavCreds) {
            try {
                caldavSuccess = migrateCalDavCredentials()
            } catch (e: Exception) {
                Log.e(TAG, "CalDAV migration failed", e)
                caldavError = e.message
            }
        }

        if (icloudSuccess && caldavSuccess) {
            setMigrationComplete()
            Log.i(TAG, "Credential migration completed successfully")
            return MigrationResult.Success
        }

        // Leave the flag unset so the next launch retries.
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
     * Copies the unkeyed iCloud set to the iCloud account whose email is the Apple ID. Returns
     * false if the prefs can't be opened, the set is incomplete, no such account exists yet, or
     * the save fails.
     */
    private suspend fun migrateICloudCredentials(): Boolean {
        val oldPrefs = getOldICloudPrefs() ?: return false

        val appleId = oldPrefs.getString(ICLOUD_KEY_APPLE_ID, null) ?: return false
        val appPassword = oldPrefs.getString(ICLOUD_KEY_APP_PASSWORD, null) ?: return false

        Log.d(TAG, "Found iCloud credentials for: ${appleId.take(3)}***")

        // The Apple ID is the account's email in Room.
        val account = accountsDao.getByProviderAndEmail(AccountProvider.ICLOUD, appleId)
        if (account == null) {
            Log.w(TAG, "No iCloud account found in database for: ${appleId.take(3)}***")
            // Expected when the user signed in but the account row isn't created yet; the
            // next launch retries.
            return false
        }

        val serverUrl = oldPrefs.getString(ICLOUD_KEY_SERVER_URL, null)
            ?: AccountCredentials.ICLOUD_DEFAULT_SERVER_URL
        val principalUrl = oldPrefs.getString(ICLOUD_KEY_PRINCIPAL_URL, null)
        val calendarHomeSet = oldPrefs.getString(ICLOUD_KEY_CALENDAR_HOME_URL, null)

        val credentials = AccountCredentials(
            username = appleId,
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
     * Copies every `caldav_{id}_*` set to account `{id}`. A set that is incomplete or whose account
     * doesn't exist is skipped and doesn't block completion. Returns false if the prefs can't be
     * opened or a save fails.
     */
    private suspend fun migrateCalDavCredentials(): Boolean {
        val oldPrefs = getOldCalDavPrefs() ?: return false

        // An account ID counts as stored when its password key exists.
        val accountIds = oldPrefs.all.keys
            .filter { it.startsWith("caldav_") && it.endsWith("_password") }
            .mapNotNull { key ->
                val idPart = key.removePrefix("caldav_").removeSuffix("_password")
                idPart.toLongOrNull()
            }
            .toSet()

        if (accountIds.isEmpty()) {
            Log.d(TAG, "No CalDAV credentials to migrate")
            return true
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
                principalUrl = null,  // The old CalDAV format didn't store these
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
     * Opens the old iCloud prefs, or returns null if they can't be opened. A missing file opens
     * as empty prefs.
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

    /** Opens the old CalDAV prefs like [getOldICloudPrefs]. */
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
