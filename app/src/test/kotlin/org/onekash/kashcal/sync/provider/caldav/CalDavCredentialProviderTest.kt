package org.onekash.kashcal.sync.provider.caldav

import android.util.Log
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.auth.Credentials

/**
 * Unit tests for CalDavCredentialProvider.
 */
class CalDavCredentialProviderTest {

    private lateinit var credentialManager: CalDavCredentialManager
    private lateinit var accountsDao: AccountsDao
    private lateinit var credentialProvider: CalDavCredentialProvider

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        credentialManager = mockk(relaxed = true)
        accountsDao = mockk(relaxed = true)

        credentialProvider = CalDavCredentialProvider(credentialManager, accountsDao)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== getCredentials tests ====================

    @Test
    fun `getCredentials returns credentials when available`() = runTest {
        val accountId = 1L
        val caldavCreds = CalDavCredentials(
            serverUrl = "https://nextcloud.example.com",
            username = "user@example.com",
            password = "password123",
            trustInsecure = false
        )

        every { credentialManager.isEncryptionAvailable() } returns true
        every { credentialManager.getCredentials(accountId) } returns caldavCreds

        val result = credentialProvider.getCredentials(accountId)

        assertNotNull(result)
        assertEquals("user@example.com", result?.username)
        assertEquals("password123", result?.password)
        assertEquals("https://nextcloud.example.com", result?.serverUrl)
        assertFalse(result?.trustInsecure == true)
    }

    @Test
    fun `getCredentials includes trustInsecure from CalDavCredentials`() = runTest {
        val accountId = 1L
        val caldavCreds = CalDavCredentials(
            serverUrl = "https://self-signed.local",
            username = "admin",
            password = "admin-pass",
            trustInsecure = true
        )

        every { credentialManager.isEncryptionAvailable() } returns true
        every { credentialManager.getCredentials(accountId) } returns caldavCreds

        val result = credentialProvider.getCredentials(accountId)

        assertNotNull(result)
        assertTrue(result?.trustInsecure == true)
    }

    @Test
    fun `getCredentials returns null when encryption unavailable`() = runTest {
        every { credentialManager.isEncryptionAvailable() } returns false
        every { credentialManager.getEncryptionError() } returns "Keystore error"

        val result = credentialProvider.getCredentials(1L)

        assertNull(result)
    }

    @Test
    fun `getCredentials returns null when no credentials stored`() = runTest {
        every { credentialManager.isEncryptionAvailable() } returns true
        every { credentialManager.getCredentials(1L) } returns null

        val result = credentialProvider.getCredentials(1L)

        assertNull(result)
    }

    // ==================== getPrimaryCredentials tests ====================

    @Test
    fun `getPrimaryCredentials returns first enabled CalDAV account`() = runTest {
        val account = createAccount(1L, isEnabled = true)
        val caldavCreds = CalDavCredentials(
            serverUrl = "https://server.com",
            username = "user",
            password = "pass"
        )

        coEvery { accountsDao.getByProvider(AccountProvider.CALDAV) } returns listOf(account)
        every { credentialManager.isEncryptionAvailable() } returns true
        every { credentialManager.getCredentials(1L) } returns caldavCreds

        val result = credentialProvider.getPrimaryCredentials()

        assertNotNull(result)
        assertEquals("user", result?.username)
    }

    @Test
    fun `getPrimaryCredentials returns null when no CalDAV accounts`() = runTest {
        coEvery { accountsDao.getByProvider(AccountProvider.CALDAV) } returns emptyList()

        val result = credentialProvider.getPrimaryCredentials()

        assertNull(result)
    }

    @Test
    fun `getPrimaryCredentials skips disabled accounts`() = runTest {
        val disabledAccount = createAccount(1L, isEnabled = false)
        val enabledAccount = createAccount(2L, isEnabled = true)
        val caldavCreds = CalDavCredentials(
            serverUrl = "https://server.com",
            username = "user2",
            password = "pass"
        )

        coEvery { accountsDao.getByProvider(AccountProvider.CALDAV) } returns listOf(disabledAccount, enabledAccount)
        every { credentialManager.isEncryptionAvailable() } returns true
        every { credentialManager.getCredentials(2L) } returns caldavCreds

        val result = credentialProvider.getPrimaryCredentials()

        assertNotNull(result)
        assertEquals("user2", result?.username)
        verify { credentialManager.getCredentials(2L) }
        verify(exactly = 0) { credentialManager.getCredentials(1L) }
    }

    // ==================== hasCredentials tests ====================

    @Test
    fun `hasCredentials returns true when credentials exist`() = runTest {
        every { credentialManager.hasCredentials(1L) } returns true

        val result = credentialProvider.hasCredentials(1L)

        assertTrue(result)
    }

    @Test
    fun `hasCredentials returns false when no credentials`() = runTest {
        every { credentialManager.hasCredentials(1L) } returns false

        val result = credentialProvider.hasCredentials(1L)

        assertFalse(result)
    }

    // ==================== hasAnyCredentials tests ====================

    @Test
    fun `hasAnyCredentials returns true when accounts have credentials`() = runTest {
        every { credentialManager.getAllAccountIds() } returns setOf(1L, 2L)

        val result = credentialProvider.hasAnyCredentials()

        assertTrue(result)
    }

    @Test
    fun `hasAnyCredentials returns false when no accounts`() = runTest {
        every { credentialManager.getAllAccountIds() } returns emptySet()

        val result = credentialProvider.hasAnyCredentials()

        assertFalse(result)
    }

    // ==================== saveCredentials tests ====================

    @Test
    fun `saveCredentials saves to manager and updates account`() = runTest {
        val accountId = 1L
        val credentials = Credentials(
            username = "user@example.com",
            password = "password",
            serverUrl = "https://caldav.example.com"
        )
        val account = createAccount(accountId)

        every { credentialManager.isEncryptionAvailable() } returns true
        every { credentialManager.saveCredentials(accountId, any()) } returns true
        coEvery { accountsDao.getById(accountId) } returns account
        coEvery { accountsDao.update(any()) } just Runs

        val result = credentialProvider.saveCredentials(accountId, credentials)

        assertTrue(result)
        verify { credentialManager.saveCredentials(accountId, any()) }
        coVerify { accountsDao.update(match { it.credentialKey == "user@example.com" }) }
    }

    @Test
    fun `saveCredentials returns false when encryption unavailable`() = runTest {
        val credentials = Credentials("user", "pass", "https://server.com")

        every { credentialManager.isEncryptionAvailable() } returns false

        val result = credentialProvider.saveCredentials(1L, credentials)

        assertFalse(result)
        verify(exactly = 0) { credentialManager.saveCredentials(any(), any()) }
    }

    // ==================== saveCredentialsWithTrust tests ====================

    @Test
    fun `saveCredentialsWithTrust includes trust setting`() = runTest {
        val accountId = 1L
        val credentials = Credentials(
            username = "admin",
            password = "admin-pass",
            serverUrl = "https://self-signed.local"
        )
        val account = createAccount(accountId)

        every { credentialManager.isEncryptionAvailable() } returns true
        every { credentialManager.saveCredentials(accountId, any()) } returns true
        coEvery { accountsDao.getById(accountId) } returns account
        coEvery { accountsDao.update(any()) } just Runs

        val result = credentialProvider.saveCredentialsWithTrust(accountId, credentials, trustInsecure = true)

        assertTrue(result)
        verify {
            credentialManager.saveCredentials(accountId, match { it.trustInsecure })
        }
    }

    // ==================== deleteCredentials tests ====================

    @Test
    fun `deleteCredentials removes from manager and clears account key`() = runTest {
        val accountId = 1L
        val account = createAccount(accountId, credentialKey = "user@example.com")

        coEvery { accountsDao.getById(accountId) } returns account
        coEvery { accountsDao.update(any()) } just Runs

        val result = credentialProvider.deleteCredentials(accountId)

        assertTrue(result)
        verify { credentialManager.deleteCredentials(accountId) }
        coVerify { accountsDao.update(match { it.credentialKey == null }) }
    }

    // ==================== clearAllCredentials tests ====================

    @Test
    fun `clearAllCredentials delegates to manager`() = runTest {
        credentialProvider.clearAllCredentials()

        verify { credentialManager.clearAllCredentials() }
    }

    // ==================== getTrustInsecure tests ====================

    @Test
    fun `getTrustInsecure returns true when enabled`() {
        val creds = CalDavCredentials("https://server.com", "user", "pass", trustInsecure = true)
        every { credentialManager.getCredentials(1L) } returns creds

        val result = credentialProvider.getTrustInsecure(1L)

        assertTrue(result)
    }

    @Test
    fun `getTrustInsecure returns false when disabled`() {
        val creds = CalDavCredentials("https://server.com", "user", "pass", trustInsecure = false)
        every { credentialManager.getCredentials(1L) } returns creds

        val result = credentialProvider.getTrustInsecure(1L)

        assertFalse(result)
    }

    @Test
    fun `getTrustInsecure returns false when no credentials`() {
        every { credentialManager.getCredentials(1L) } returns null

        val result = credentialProvider.getTrustInsecure(1L)

        assertFalse(result)
    }

    // ==================== setTrustInsecure tests ====================

    @Test
    fun `setTrustInsecure updates existing credentials`() {
        val creds = CalDavCredentials("https://server.com", "user", "pass", trustInsecure = false)
        every { credentialManager.getCredentials(1L) } returns creds
        every { credentialManager.saveCredentials(1L, any()) } returns true

        val result = credentialProvider.setTrustInsecure(1L, true)

        assertTrue(result)
        verify {
            credentialManager.saveCredentials(1L, match { it.trustInsecure })
        }
    }

    @Test
    fun `setTrustInsecure returns false when no credentials`() {
        every { credentialManager.getCredentials(1L) } returns null

        val result = credentialProvider.setTrustInsecure(1L, true)

        assertFalse(result)
        verify(exactly = 0) { credentialManager.saveCredentials(any(), any()) }
    }

    // ==================== getCalDavCredentials tests ====================

    @Test
    fun `getCalDavCredentials returns raw credentials`() {
        val creds = CalDavCredentials(
            serverUrl = "https://server.com",
            username = "user",
            password = "pass",
            trustInsecure = true
        )
        every { credentialManager.getCredentials(1L) } returns creds

        val result = credentialProvider.getCalDavCredentials(1L)

        assertNotNull(result)
        assertEquals("https://server.com", result?.serverUrl)
        assertEquals("user", result?.username)
        assertEquals("pass", result?.password)
        assertTrue(result?.trustInsecure == true)
    }

    // ==================== Helper methods ====================

    private fun createAccount(
        id: Long,
        isEnabled: Boolean = true,
        credentialKey: String? = null
    ): Account {
        return Account(
            id = id,
            provider = AccountProvider.CALDAV,
            email = "user$id@example.com",
            displayName = "CalDAV Account $id",
            principalUrl = null,
            homeSetUrl = "https://caldav.example.com/calendars/user$id/",
            isEnabled = isEnabled,
            credentialKey = credentialKey
        )
    }
}
