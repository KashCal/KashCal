package org.onekash.kashcal.data.credential

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins how [UnifiedCredentialManager] behaves when its encrypted store breaks, on the real
 * Android Keystore (#370): a keyset the master key no longer decrypts, a stored value that fails
 * to decrypt, a damaged account-id index, and the legacy credential files a recovery must leave
 * readable.
 *
 * Destructive: each test clears `unified_credentials` and `caldav_credentials` and deletes the
 * app's default master key, so run it only on an emulator or device without real KashCal
 * accounts. It skips itself when the app's database holds any CalDAV or iCloud account.
 */
@RunWith(AndroidJUnit4::class)
class UnifiedCredentialManagerFailureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val credentials = AccountCredentials(
        username = "user@example.com",
        password = "secret",
        serverUrl = "https://caldav.example.com",
    )

    @Before
    fun setUp() {
        assumeTrue("the app has CalDAV or iCloud accounts; not wiping their credentials", noSyncedAccounts())
        resetStore()
    }

    private fun noSyncedAccounts(): Boolean {
        val file = context.getDatabasePath("kashcal.db")
        if (!file.exists()) return true
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM accounts WHERE provider IN ('caldav', 'icloud')", null).use {
                it.moveToFirst() && it.getLong(0) == 0L
            }
        }
    }

    @After
    fun tearDown() {
        if (noSyncedAccounts()) resetStore()
    }

    @Test
    fun after_the_master_key_is_lost_the_store_recovers_and_stays_usable_across_launches() = runBlocking {
        assertTrue(UnifiedCredentialManager(context).saveCredentials(1L, credentials))

        deleteKey(DEFAULT_MASTER_KEY_ALIAS)

        // A new instance is what a new app launch gets.
        val manager = UnifiedCredentialManager(context)
        assertTrue("store recovered", manager.isEncryptionAvailable())
        assertNull("old credentials are gone", manager.getCredentials(1L))
        assertTrue(manager.saveCredentials(1L, credentials))
        assertEquals(credentials, manager.getCredentials(1L))

        val nextLaunch = UnifiedCredentialManager(context)
        assertTrue(nextLaunch.isEncryptionAvailable())
        assertEquals(credentials, nextLaunch.getCredentials(1L))
    }

    @Test
    fun a_value_that_fails_to_decrypt_reads_as_missing_and_can_be_saved_again() = runBlocking {
        assertTrue(UnifiedCredentialManager(context).saveCredentials(1L, credentials))

        // Overwrite every encrypted value, leaving the two keysets intact so the file still opens.
        val raw = rawPrefs()
        raw.edit(commit = true) {
            raw.all.keys.filterNot { it.startsWith(KEYSET_PREFIX) }.forEach { putString(it, GARBAGE) }
        }

        val reopened = UnifiedCredentialManager(context)
        assertTrue(reopened.isEncryptionAvailable())
        assertNull(reopened.getCredentials(1L))
        assertTrue(reopened.saveCredentials(1L, credentials))
        assertEquals(credentials, reopened.getCredentials(1L))
    }

    @Test
    fun a_damaged_account_id_index_still_lets_credentials_be_saved_and_deleted() = runBlocking {
        val manager = UnifiedCredentialManager(context)
        assertTrue(manager.saveCredentials(1L, credentials))
        val afterFirst = rawPrefs().all.toMap()
        assertTrue(manager.saveCredentials(2L, credentials))
        // The index is the only entry both saves wrote: present before and changed by the second.
        val indexKey = rawPrefs().all.entries.single { (key, value) ->
            !key.startsWith(KEYSET_PREFIX) && key in afterFirst && afterFirst[key] != value
        }.key
        rawPrefs().edit(commit = true) { putString(indexKey, GARBAGE) }

        val reopened = UnifiedCredentialManager(context)
        assertTrue(reopened.saveCredentials(3L, credentials))
        assertEquals(credentials, reopened.getCredentials(3L))
        reopened.deleteCredentials(1L)
        assertNull(reopened.getCredentials(1L))
        assertEquals(credentials, reopened.getCredentials(2L))
    }

    @Test
    fun a_recovery_leaves_a_legacy_file_under_the_master_key_readable() = runBlocking {
        val defaultKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        encryptedPrefs(LEGACY_CALDAV_PREFS, defaultKey).edit(commit = true) { putString("caldav_1_username", "legacy-user") }
        // A unified store whose keysets another key wrote fails to decrypt (AEADBadTagException).
        val otherKey = MasterKey.Builder(context, OTHER_MASTER_KEY_ALIAS).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        encryptedPrefs(PREFS_NAME, otherKey).edit(commit = true) { putString("account_1_username", "x") }

        val manager = UnifiedCredentialManager(context)
        assertTrue("store recovered", manager.isEncryptionAvailable())
        assertTrue(manager.saveCredentials(1L, credentials))

        assertEquals("legacy-user", encryptedPrefs(LEGACY_CALDAV_PREFS, defaultKey).getString("caldav_1_username", null))
    }

    private fun encryptedPrefs(name: String, key: MasterKey): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        name,
        key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private fun rawPrefs(): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun resetStore() {
        clearPrefsFile(PREFS_NAME)
        clearPrefsFile(LEGACY_CALDAV_PREFS)
        deleteKey(DEFAULT_MASTER_KEY_ALIAS)
        deleteKey(OTHER_MASTER_KEY_ALIAS)
    }

    // Clears the in-memory copy the process caches as well as the file on disk.
    private fun clearPrefsFile(name: String) {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit(commit = true) { clear() }
        context.deleteSharedPreferences(name)
    }

    private fun deleteKey(alias: String) {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    private companion object {
        const val PREFS_NAME = "unified_credentials"
        const val LEGACY_CALDAV_PREFS = "caldav_credentials"
        const val DEFAULT_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
        const val OTHER_MASTER_KEY_ALIAS = "kashcal_test_other_master_key"
        const val KEYSET_PREFIX = "__androidx_security_crypto_encrypted_prefs_"
        val GARBAGE: String = Base64.encodeToString(ByteArray(64), Base64.DEFAULT)
    }
}
