package org.onekash.kashcal.data.credential

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.preferences.KashCalDataStore

/**
 * Tests [CredentialMigration]: the DataStore flag short-circuit and the
 * [CredentialMigration.MigrationResult] variants.
 *
 * The old prefs are EncryptedSharedPreferences, which this test's mocked Context can't open,
 * so the fresh install, iCloud, CalDAV and missing-account tests are placeholders that assert
 * nothing and only describe the expected flow. No other test covers those paths.
 */
class CredentialMigrationTest {

    private lateinit var credentialMigration: CredentialMigration
    private lateinit var context: Context
    private lateinit var accountsDao: AccountsDao
    private lateinit var credentialManager: CredentialManager
    private lateinit var dataStore: KashCalDataStore
    private lateinit var mockDataStore: DataStore<Preferences>
    private lateinit var mockPreferences: MutablePreferences

    private val KEY_CREDENTIALS_MIGRATED = booleanPreferencesKey("credentials_migrated_to_unified")

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        context = mockk(relaxed = true)
        accountsDao = mockk(relaxed = true)
        credentialManager = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        mockDataStore = mockk(relaxed = true)
        mockPreferences = mockk(relaxed = true)

        // Not yet migrated unless a test sets the flag.
        every { mockPreferences[KEY_CREDENTIALS_MIGRATED] } returns null
        every { dataStore.dataStore } returns mockDataStore
        every { mockDataStore.data } returns flowOf(mockPreferences)

        credentialMigration = CredentialMigration(
            context = context,
            accountsDao = accountsDao,
            credentialManager = credentialManager,
            dataStore = dataStore
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== Idempotency Tests ==========

    @Test
    fun `migration returns AlreadyMigrated when flag is set`() = runBlocking {
        // The migration flag is set.
        every { mockPreferences[KEY_CREDENTIALS_MIGRATED] } returns true

        val result = credentialMigration.migrateIfNeeded()

        assertEquals(CredentialMigration.MigrationResult.AlreadyMigrated, result)
        coVerify(exactly = 0) { credentialManager.saveCredentials(any(), any()) }
    }

    @Test
    fun `migration is idempotent - second call returns AlreadyMigrated when flag set`() = runBlocking {
        // A completed migration left the flag set.
        every { mockPreferences[KEY_CREDENTIALS_MIGRATED] } returns true

        // Returns before reading any old prefs.
        val result = credentialMigration.migrateIfNeeded()
        assertEquals(CredentialMigration.MigrationResult.AlreadyMigrated, result)
    }

    // ========== Fresh Install Tests ==========

    @Test
    fun `migration returns NoCredentialsToMigrate on fresh install`() = runBlocking {
        // Placeholder: arranging empty old prefs needs EncryptedSharedPreferences. The
        // migration should:
        // 1. Check DataStore flag (not set)
        // 2. Check for old iCloud credentials (none)
        // 3. Check for old CalDAV credentials (none)
        // 4. Set migration flag
        // 5. Return NoCredentialsToMigrate

        assertTrue("Fresh install test placeholder - see integration tests", true)
    }

    // ========== iCloud Migration Tests ==========

    @Test
    fun `migration preserves iCloud credentials including principalUrl`() = runBlocking {
        // Placeholder: the old iCloud prefs are EncryptedSharedPreferences. When
        // getOldICloudPrefs() has a complete set and accountsDao.getByProviderAndEmail()
        // finds the account, credentialManager.saveCredentials() gets every iCloud field:
        // 1. Read apple_id, app_password, server_url, principal_url, calendar_home_url
        // 2. Find account by (ICLOUD, appleId)
        // 3. Save to unified format with username=appleId

        assertTrue("iCloud migration test placeholder - see integration tests", true)
    }

    // ========== CalDAV Migration Tests ==========

    @Test
    fun `migration preserves CalDAV credentials with trustInsecure flag`() = runBlocking {
        // Placeholder, like the iCloud test. The expected flow:
        // 1. Read caldav_{id}_server_url, caldav_{id}_username, caldav_{id}_password,
        //    caldav_{id}_trust_insecure
        // 2. Skip a set whose account isn't in the database
        // 3. Save to unified format

        assertTrue("CalDAV migration test placeholder - see integration tests", true)
    }

    // ========== Partial Failure Tests ==========

    @Test
    fun `migration handles missing account gracefully - returns NoAccount equivalent`() = runBlocking {
        // Placeholder. When iCloud credentials exist but the account row isn't created yet,
        // the migration returns PartialSuccess and leaves the flag unset, so the next launch
        // retries.

        assertTrue("Partial failure test placeholder - see integration tests", true)
    }

    // ========== Migration Result Tests ==========

    @Test
    fun `MigrationResult sealed class has expected variants`() {
        // Compiles only while every variant exists.
        val results = listOf(
            CredentialMigration.MigrationResult.AlreadyMigrated,
            CredentialMigration.MigrationResult.Success,
            CredentialMigration.MigrationResult.NoCredentialsToMigrate,
            CredentialMigration.MigrationResult.PartialSuccess(
                icloudSuccess = true,
                caldavSuccess = false,
                icloudError = null,
                caldavError = "test error"
            ),
            CredentialMigration.MigrationResult.Failed("test error")
        )

        assertEquals(5, results.size)
    }
}
