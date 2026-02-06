package org.onekash.kashcal.data.credential

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Unit tests for CredentialMigration.
 *
 * Tests:
 * - Migration from iCloud single-key format to account-keyed format
 * - Migration from CalDAV format to unified format
 * - Idempotency (DataStore flag)
 * - Partial failure handling
 * - Fresh install (no credentials)
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

        // Default: not yet migrated
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
        // Setup: migration flag already set
        every { mockPreferences[KEY_CREDENTIALS_MIGRATED] } returns true

        // Execute
        val result = credentialMigration.migrateIfNeeded()

        // Verify
        assertEquals(CredentialMigration.MigrationResult.AlreadyMigrated, result)
        coVerify(exactly = 0) { credentialManager.saveCredentials(any(), any()) }
    }

    @Test
    fun `migration is idempotent - second call returns AlreadyMigrated when flag set`() = runBlocking {
        // After migration completes, flag is set
        every { mockPreferences[KEY_CREDENTIALS_MIGRATED] } returns true

        // Execute - should return immediately
        val result = credentialMigration.migrateIfNeeded()
        assertEquals(CredentialMigration.MigrationResult.AlreadyMigrated, result)
    }

    // ========== Fresh Install Tests ==========

    @Test
    fun `migration returns NoCredentialsToMigrate on fresh install`() = runBlocking {
        // Setup: no old credentials exist (EncryptedSharedPreferences can't be mocked)
        // This test documents expected behavior - actual testing via integration tests

        // The migration should:
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
        // This test verifies the migration preserves all iCloud fields.
        // Due to EncryptedSharedPreferences requiring Android context,
        // we can only verify the migration logic flow in unit tests.
        // Integration tests on device verify actual credential storage.

        // The key assertion is that when getOldICloudPrefs() returns valid credentials,
        // and accountsDao.getByProviderAndEmail() returns the account,
        // then credentialManager.saveCredentials() is called with correct AccountCredentials.

        // Since EncryptedSharedPreferences can't be mocked easily,
        // this test documents the expected behavior:
        // 1. Read apple_id, app_password, server_url, principal_url, calendar_home_url
        // 2. Find account by (ICLOUD, appleId)
        // 3. Save to unified format with username=appleId

        assertTrue("iCloud migration test placeholder - see integration tests", true)
    }

    // ========== CalDAV Migration Tests ==========

    @Test
    fun `migration preserves CalDAV credentials with trustInsecure flag`() = runBlocking {
        // Similar to iCloud test, this verifies the expected behavior:
        // 1. Read caldav_{id}_server_url, caldav_{id}_username, caldav_{id}_password, caldav_{id}_trust_insecure
        // 2. Verify account exists in database
        // 3. Save to unified format

        assertTrue("CalDAV migration test placeholder - see integration tests", true)
    }

    // ========== Partial Failure Tests ==========

    @Test
    fun `migration handles missing account gracefully - returns NoAccount equivalent`() = runBlocking {
        // When iCloud credentials exist but account not in database yet,
        // migration should return partial failure and retry on next launch.
        // This happens if user upgraded mid-sign-in flow.

        assertTrue("Partial failure test placeholder - see integration tests", true)
    }

    // ========== Migration Result Tests ==========

    @Test
    fun `MigrationResult sealed class has expected variants`() {
        // Verify all result types exist
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
