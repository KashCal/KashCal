package org.onekash.kashcal.sync.auth

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Adversarial tests for credential and authentication handling.
 *
 * Tests edge cases:
 * - Account with missing credentials
 * - Multiple accounts (unique constraint)
 * - Account deletion during sync
 * - Credential format validation
 * - Sync failure tracking
 * - Account state transitions
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CredentialAdversarialTest {

    private lateinit var database: KashCalDatabase

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Account Creation Tests ====================

    @Test
    fun `create account with minimal fields`() = runTest {
        val account = Account(
            provider = "icloud",
            email = "test@icloud.com"
        )
        val accountId = database.accountsDao().insert(account)

        val saved = database.accountsDao().getById(accountId)
        assertNotNull(saved)
        assertEquals("icloud", saved?.provider)
        assertEquals("test@icloud.com", saved?.email)
        assertNull("Credential key should be null by default", saved?.credentialKey)
    }

    @Test
    fun `create account with all fields`() = runTest {
        val account = Account(
            provider = "icloud",
            email = "full@icloud.com",
            credentialKey = "keystore_alias_123",
            principalUrl = "https://caldav.icloud.com/123456/principal/",
            homeSetUrl = "https://caldav.icloud.com/123456/calendars/"
        )
        val accountId = database.accountsDao().insert(account)

        val saved = database.accountsDao().getById(accountId)
        assertNotNull(saved)
        assertEquals("https://caldav.icloud.com/123456/principal/", saved?.principalUrl)
        assertNotNull(saved?.credentialKey)
    }

    // ==================== Unique Constraint Tests ====================

    @Test
    fun `duplicate provider-email rejected`() = runTest {
        // First account succeeds
        val account1 = Account(provider = "icloud", email = "same@icloud.com")
        database.accountsDao().insert(account1)

        // Second with same provider+email should fail due to unique index
        val account2 = Account(provider = "icloud", email = "same@icloud.com")
        try {
            database.accountsDao().insert(account2)
            fail("Should throw on duplicate provider+email")
        } catch (e: Exception) {
            // Expected: UNIQUE constraint violation
            assertTrue(e.message?.contains("UNIQUE") == true || e.message?.contains("constraint") == true)
        }
    }

    @Test
    fun `same email different provider allowed`() = runTest {
        val account1 = Account(provider = "icloud", email = "multi@test.com")
        val account2 = Account(provider = "google", email = "multi@test.com")

        val id1 = database.accountsDao().insert(account1)
        val id2 = database.accountsDao().insert(account2)

        assertNotEquals("Should have different IDs", id1, id2)

        val accounts = database.accountsDao().getAllOnce()
        assertEquals("Should have 2 accounts", 2, accounts.size)
    }

    // ==================== Account Deletion Tests ====================

    @Test
    fun `delete account removes associated calendars`() = runTest {
        val accountId = database.accountsDao().insert(
            Account(provider = "icloud", email = "delete@icloud.com")
        )

        // Add calendars
        database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal1/",
                displayName = "Calendar 1",
                color = 0xFF2196F3.toInt()
            )
        )
        database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal2/",
                displayName = "Calendar 2",
                color = 0xFFFF5722.toInt()
            )
        )

        val calsBefore = database.calendarsDao().getByAccountIdOnce(accountId)
        assertEquals(2, calsBefore.size)

        // Delete account
        database.accountsDao().deleteById(accountId)

        // Calendars should be cascade deleted (if FK set up)
        val calsAfter = database.calendarsDao().getByAccountIdOnce(accountId)
        assertTrue("Calendars should be deleted with account", calsAfter.isEmpty())
    }

    @Test
    fun `delete non-existent account is safe`() = runTest {
        val nonExistentId = 99999L

        // Should not throw
        database.accountsDao().deleteById(nonExistentId)

        // Verify no crash
        assertTrue(true)
    }

    // ==================== Sync Failure Tracking Tests ====================

    @Test
    fun `track consecutive sync failures`() = runTest {
        val account = Account(
            provider = "icloud",
            email = "failures@icloud.com",
            consecutiveSyncFailures = 0
        )
        val accountId = database.accountsDao().insert(account)

        // Simulate failures
        repeat(5) {
            database.accountsDao().recordSyncFailure(accountId, System.currentTimeMillis())
        }

        val updated = database.accountsDao().getById(accountId)
        assertEquals(5, updated?.consecutiveSyncFailures)
    }

    @Test
    fun `reset failure count on successful sync`() = runTest {
        val account = Account(
            provider = "icloud",
            email = "reset@icloud.com",
            consecutiveSyncFailures = 10
        )
        val accountId = database.accountsDao().insert(account)

        // Simulate successful sync
        database.accountsDao().recordSyncSuccess(accountId, System.currentTimeMillis())

        val updated = database.accountsDao().getById(accountId)
        assertEquals(0, updated?.consecutiveSyncFailures)
        assertNotNull(updated?.lastSuccessfulSyncAt)
    }

    @Test
    fun `last sync timestamp tracking`() = runTest {
        val now = System.currentTimeMillis()
        val account = Account(
            provider = "icloud",
            email = "timestamp@icloud.com"
        )
        val accountId = database.accountsDao().insert(account)

        // Initial state - no sync yet
        val initial = database.accountsDao().getById(accountId)
        assertNull(initial?.lastSuccessfulSyncAt)

        // After successful sync
        database.accountsDao().recordSyncSuccess(accountId, now)

        val afterSync = database.accountsDao().getById(accountId)
        assertEquals(now, afterSync?.lastSuccessfulSyncAt)
    }

    // ==================== Credential Format Tests ====================

    @Test
    fun `email with special characters`() = runTest {
        val specialEmails = listOf(
            "test+tag@icloud.com" to "icloud",
            "test.name@example.com" to "caldav",
            "test_name@gmail.com" to "google",
            "TEST@ICLOUD.COM" to "icloud2"
        )

        specialEmails.forEach { (email, provider) ->
            val accountId = database.accountsDao().insert(
                Account(provider = provider, email = email)
            )
            val saved = database.accountsDao().getById(accountId)
            assertEquals("Should preserve email: $email", email, saved?.email)
        }
    }

    @Test
    fun `very long email address`() = runTest {
        val longEmail = "a".repeat(200) + "@icloud.com"
        val accountId = database.accountsDao().insert(
            Account(provider = "icloud", email = longEmail)
        )

        val saved = database.accountsDao().getById(accountId)
        assertEquals(longEmail, saved?.email)
    }

    @Test
    fun `empty credential key vs null`() = runTest {
        val accountWithEmpty = Account(
            provider = "icloud",
            email = "empty@icloud.com",
            credentialKey = ""
        )
        val accountWithNull = Account(
            provider = "icloud2",
            email = "null@icloud.com",
            credentialKey = null
        )

        val emptyId = database.accountsDao().insert(accountWithEmpty)
        val nullId = database.accountsDao().insert(accountWithNull)

        val savedEmpty = database.accountsDao().getById(emptyId)
        val savedNull = database.accountsDao().getById(nullId)

        assertEquals("", savedEmpty?.credentialKey)
        assertNull(savedNull?.credentialKey)
    }

    // ==================== Provider Tests ====================

    @Test
    fun `different providers supported`() = runTest {
        val providers = listOf("icloud", "google", "caldav", "local")

        providers.forEachIndexed { index, provider ->
            val accountId = database.accountsDao().insert(
                Account(provider = provider, email = "$provider$index@test.com")
            )
            val saved = database.accountsDao().getById(accountId)
            assertEquals(provider, saved?.provider)
        }
    }

    @Test
    fun `filter enabled accounts`() = runTest {
        database.accountsDao().insert(Account(provider = "icloud", email = "enabled1@test.com", isEnabled = true))
        database.accountsDao().insert(Account(provider = "icloud2", email = "disabled@test.com", isEnabled = false))
        database.accountsDao().insert(Account(provider = "google", email = "enabled2@test.com", isEnabled = true))

        val enabledAccounts = database.accountsDao().getEnabledAccounts()
        assertEquals(2, enabledAccounts.size)
        assertTrue(enabledAccounts.all { it.isEnabled })
    }

    // ==================== URL Validation Tests ====================

    @Test
    fun `CalDAV URLs with trailing slashes`() = runTest {
        val account = Account(
            provider = "icloud",
            email = "urls@icloud.com",
            principalUrl = "https://caldav.icloud.com/123/principal/",
            homeSetUrl = "https://caldav.icloud.com/123/calendars/"
        )
        val accountId = database.accountsDao().insert(account)

        val saved = database.accountsDao().getById(accountId)
        assertTrue(saved?.principalUrl?.endsWith("/") == true)
        assertTrue(saved?.homeSetUrl?.endsWith("/") == true)
    }

    @Test
    fun `CalDAV URLs without trailing slashes`() = runTest {
        val account = Account(
            provider = "caldav",
            email = "noslash@test.com",
            principalUrl = "https://caldav.example.com/principal",
            homeSetUrl = "https://caldav.example.com/calendars"
        )
        val accountId = database.accountsDao().insert(account)

        val saved = database.accountsDao().getById(accountId)
        assertFalse(saved?.principalUrl?.endsWith("/") == true)
    }

    // ==================== Account Enable/Disable Tests ====================

    @Test
    fun `toggle account enabled state`() = runTest {
        val accountId = database.accountsDao().insert(
            Account(provider = "icloud", email = "toggle@icloud.com", isEnabled = true)
        )

        // Disable
        database.accountsDao().setEnabled(accountId, false)
        var saved = database.accountsDao().getById(accountId)
        assertFalse(saved!!.isEnabled)

        // Re-enable
        database.accountsDao().setEnabled(accountId, true)
        saved = database.accountsDao().getById(accountId)
        assertTrue(saved!!.isEnabled)
    }

    // ==================== Lookup Tests ====================

    @Test
    fun `find account by provider and email`() = runTest {
        database.accountsDao().insert(Account(provider = "icloud", email = "lookup@icloud.com"))
        database.accountsDao().insert(Account(provider = "google", email = "lookup@google.com"))

        val found = database.accountsDao().getByProviderAndEmail("icloud", "lookup@icloud.com")
        assertNotNull(found)
        assertEquals("icloud", found?.provider)

        val notFound = database.accountsDao().getByProviderAndEmail("icloud", "notexist@icloud.com")
        assertNull(notFound)
    }

    @Test
    fun `get accounts with sync errors`() = runTest {
        val id1 = database.accountsDao().insert(
            Account(provider = "icloud", email = "noerror@test.com")
        )
        val id2 = database.accountsDao().insert(
            Account(provider = "google", email = "haserror@test.com")
        )

        // Simulate failure on second account
        database.accountsDao().recordSyncFailure(id2, System.currentTimeMillis())

        val errored = database.accountsDao().getAccountsWithSyncErrors()
        assertEquals(1, errored.size)
        assertEquals(id2, errored.first().id)
    }
}
