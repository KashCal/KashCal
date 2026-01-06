package org.onekash.kashcal.sync.provider.icloud

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages iCloud account credentials using encrypted storage.
 *
 * Uses EncryptedSharedPreferences with AES-256-GCM encryption for secure
 * credential storage. Credentials are never stored in plain text.
 *
 * Security:
 * - Keys encrypted with AES256-SIV
 * - Values encrypted with AES256-GCM
 * - Master key stored in Android Keystore
 *
 * Usage:
 * ```
 * @Inject lateinit var authManager: ICloudAuthManager
 *
 * // Save credentials
 * authManager.saveAccount(ICloudAccount(appleId = "...", appSpecificPassword = "..."))
 *
 * // Load credentials
 * val account = authManager.loadAccount()
 *
 * // Check if configured
 * if (authManager.isConfigured()) { ... }
 * ```
 */
@Singleton
class ICloudAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ICloudAuthManager"
        private const val PREFS_NAME = "icloud_credentials"

        // Preference keys
        private const val KEY_APPLE_ID = "apple_id"
        private const val KEY_APP_PASSWORD = "app_password"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_CALENDAR_HOME_URL = "calendar_home_url"
        private const val KEY_PRINCIPAL_URL = "principal_url"
        private const val KEY_IS_ENABLED = "is_enabled"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_SYNC_TOKEN = "sync_token"
        private const val KEY_DEFAULT_CALENDAR_URL = "default_calendar_url"
        private const val KEY_DEFAULT_CALENDAR_NAME = "default_calendar_name"
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
    fun isEncryptionAvailable(): Boolean {
        return encryptedPrefs != null
    }

    /**
     * Get error message if encryption failed.
     * Returns null if encryption is working.
     */
    fun getEncryptionError(): String? {
        return encryptionError?.message
    }

    /**
     * Save iCloud account credentials.
     *
     * @param account The account to save
     * @return true if saved successfully, false if encryption unavailable
     */
    fun saveAccount(account: ICloudAccount): Boolean {
        // encryptedPrefs uses EncryptedSharedPreferences with AES256 encryption
        // lgtm[kotlin/cleartext-storage-shared-prefs]
        val prefs = encryptedPrefs ?: return false

        prefs.edit().apply {
            putString(KEY_APPLE_ID, account.appleId)
            putString(KEY_APP_PASSWORD, account.appSpecificPassword)
            putString(KEY_SERVER_URL, account.serverUrl)
            putString(KEY_CALENDAR_HOME_URL, account.calendarHomeUrl)
            putString(KEY_PRINCIPAL_URL, account.principalUrl)
            putBoolean(KEY_IS_ENABLED, account.isEnabled)
            putLong(KEY_LAST_SYNC_TIME, account.lastSyncTime)
            putString(KEY_SYNC_TOKEN, account.syncToken)
            apply()
        }

        Log.d(TAG, "Saved iCloud account: ${account.appleId.take(3)}***")
        return true
    }

    /**
     * Load saved iCloud account.
     *
     * @return The account or null if not configured
     */
    fun loadAccount(): ICloudAccount? {
        val prefs = encryptedPrefs ?: return null

        val appleId = prefs.getString(KEY_APPLE_ID, null) ?: return null
        val appPassword = prefs.getString(KEY_APP_PASSWORD, null) ?: return null

        return ICloudAccount(
            appleId = appleId,
            appSpecificPassword = appPassword,
            serverUrl = prefs.getString(KEY_SERVER_URL, null)
                ?: ICloudAccount.DEFAULT_SERVER_URL,
            calendarHomeUrl = prefs.getString(KEY_CALENDAR_HOME_URL, null),
            principalUrl = prefs.getString(KEY_PRINCIPAL_URL, null),
            isEnabled = prefs.getBoolean(KEY_IS_ENABLED, true),
            lastSyncTime = prefs.getLong(KEY_LAST_SYNC_TIME, 0L),
            syncToken = prefs.getString(KEY_SYNC_TOKEN, null)
        )
    }

    /**
     * Check if iCloud sync is configured.
     */
    fun isConfigured(): Boolean {
        return loadAccount()?.hasCredentials() == true
    }

    /**
     * Check if iCloud sync is enabled.
     */
    fun isEnabled(): Boolean {
        return loadAccount()?.isReadyForSync() == true
    }

    /**
     * Update the sync token after a successful sync.
     */
    fun updateSyncToken(syncToken: String?) {
        encryptedPrefs?.edit()?.apply {
            putString(KEY_SYNC_TOKEN, syncToken)
            putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
            apply()
        }
    }

    /**
     * Update the calendar home URL after discovery.
     */
    fun updateCalendarHomeUrl(calendarHomeUrl: String, principalUrl: String?) {
        encryptedPrefs?.edit()?.apply {
            putString(KEY_CALENDAR_HOME_URL, calendarHomeUrl)
            putString(KEY_PRINCIPAL_URL, principalUrl)
            apply()
        }
    }

    /**
     * Enable or disable iCloud sync.
     */
    fun setEnabled(enabled: Boolean) {
        encryptedPrefs?.edit()?.putBoolean(KEY_IS_ENABLED, enabled)?.apply()
    }

    /**
     * Clear all stored credentials.
     * Use when user logs out.
     */
    fun clearAccount() {
        encryptedPrefs?.edit()?.clear()?.apply()
        Log.d(TAG, "Cleared iCloud credentials")
    }

    /**
     * Save the selected default calendar for new events.
     */
    fun saveDefaultCalendar(calendarUrl: String, calendarName: String) {
        encryptedPrefs?.edit()?.apply {
            putString(KEY_DEFAULT_CALENDAR_URL, calendarUrl)
            putString(KEY_DEFAULT_CALENDAR_NAME, calendarName)
            apply()
        }
    }

    /**
     * Get the default calendar URL for new events.
     */
    fun getDefaultCalendarUrl(): String? {
        return encryptedPrefs?.getString(KEY_DEFAULT_CALENDAR_URL, null)
    }

    /**
     * Get the default calendar name for display.
     */
    fun getDefaultCalendarName(): String? {
        return encryptedPrefs?.getString(KEY_DEFAULT_CALENDAR_NAME, null)
    }

    /**
     * Check if a default calendar has been selected.
     */
    fun hasDefaultCalendar(): Boolean {
        return getDefaultCalendarUrl() != null
    }

    /**
     * Get the last sync time in milliseconds.
     */
    fun getLastSyncTime(): Long {
        return encryptedPrefs?.getLong(KEY_LAST_SYNC_TIME, 0L) ?: 0L
    }
}
