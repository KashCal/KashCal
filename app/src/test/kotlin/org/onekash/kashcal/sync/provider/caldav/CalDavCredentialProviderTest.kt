package org.onekash.kashcal.sync.provider.caldav

import android.util.Log
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.credential.AccountCredentials
import org.onekash.kashcal.data.credential.CredentialManager
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.auth.Credentials

/**
 * Unit tests for CalDavCredentialProvider.
 */
class CalDavCredentialProviderTest {

    private lateinit var credentialManager: CredentialManager
    private lateinit var accountRepository: AccountRepository
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
        accountRepository = mockk(relaxed = true)

        credentialProvider = CalDavCredentialProvider(credentialManager, accountRepository)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== getCredentials tests ====================

    @Test
    fun `getCredentials returns credentials when available`() = runTest {
        val accountId = 1L
        val accountCreds = AccountCredentials(
            username = "user@example.com",
            password = "password123",
            serverUrl = "https://nextcloud.example.com",
            trustInsecure = false
        )

        every { credentialManager.isEncryptionAvailable() } returns true
        coEvery { credentialManager.getCredentials(accountId) } returns accountCreds

        val result = credentialProvider.getCredentials(accountId)

        assertNotNull(result)
        assertEquals("user@example.com", result?.username)
        assertEquals("password123", result?.password)
        assertEquals("https://nextcloud.example.com", result?.serverUrl)
        assertFalse(result?.trustInsecure == true)
    }

    @Test
    fun `getCredentials includes trustInsecure from AccountCredentials`() = runTest {
        val accountId = 1L
        val accountCreds = AccountCredentials(
            username = "admin",
            password = "admin-pass",
            serverUrl = "https://self-signed.local",
            trustInsecure = true
        )

        every { credentialManager.isEncryptionAvailable() } returns true
        coEvery { credentialManager.getCredentials(accountId) } returns accountCreds

        val result = credentialProvider.getCredentials(accountId)

        assertNotNull(result)
        assertTrue(result?.trustInsecure == true)
    }

    @Test
    fun `getCredentials returns null when encryption unavailable`() = runTest {
        every { credentialManager.isEncryptionAvailable() } returns false

        val result = credentialProvider.getCredentials(1L)

        assertNull(result)
    }

    @Test
    fun `getCredentials returns null when no credentials stored`() = runTest {
        every { credentialManager.isEncryptionAvailable() } returns true
        coEvery { credentialManager.getCredentials(1L) } returns null

        val result = credentialProvider.getCredentials(1L)

        assertNull(result)
    }

    // ==================== getPrimaryCredentials tests ====================

    @Test
    fun `getPrimaryCredentials returns first enabled CalDAV account`() = runTest {
        val account = createAccount(1L, isEnabled = true)
        val accountCreds = AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server.com"
        )

        coEvery { accountRepository.getAccountsByProvider(AccountProvider.CALDAV) } returns listOf(account)
        every { credentialManager.isEncryptionAvailable() } returns true
        coEvery { credentialManager.getCredentials(1L) } returns accountCreds

        val result = credentialProvider.getPrimaryCredentials()

        assertNotNull(result)
        assertEquals("user", result?.username)
    }

    @Test
    fun `getPrimaryCredentials returns null when no CalDAV accounts`() = runTest {
        coEvery { accountRepository.getAccountsByProvider(AccountProvider.CALDAV) } returns emptyList()

        val result = credentialProvider.getPrimaryCredentials()

        assertNull(result)
    }

    @Test
    fun `getPrimaryCredentials skips disabled accounts`() = runTest {
        val disabledAccount = createAccount(1L, isEnabled = false)
        val enabledAccount = createAccount(2L, isEnabled = true)
        val accountCreds = AccountCredentials(
            username = "user2",
            password = "pass",
            serverUrl = "https://server.com"
        )

        coEvery { accountRepository.getAccountsByProvider(AccountProvider.CALDAV) } returns listOf(disabledAccount, enabledAccount)
        every { credentialManager.isEncryptionAvailable() } returns true
        coEvery { credentialManager.getCredentials(2L) } returns accountCreds

        val result = credentialProvider.getPrimaryCredentials()

        assertNotNull(result)
        assertEquals("user2", result?.username)
        coVerify { credentialManager.getCredentials(2L) }
        coVerify(exactly = 0) { credentialManager.getCredentials(1L) }
    }

    // ==================== hasCredentials tests ====================

    @Test
    fun `hasCredentials returns true when credentials exist`() = runTest {
        coEvery { credentialManager.hasCredentials(1L) } returns true

        val result = credentialProvider.hasCredentials(1L)

        assertTrue(result)
    }

    @Test
    fun `hasCredentials returns false when no credentials`() = runTest {
        coEvery { credentialManager.hasCredentials(1L) } returns false

        val result = credentialProvider.hasCredentials(1L)

        assertFalse(result)
    }

    // ==================== hasAnyCredentials tests ====================

    @Test
    fun `hasAnyCredentials returns true when accounts have credentials`() = runTest {
        val account1 = createAccount(1L)
        val account2 = createAccount(2L)
        coEvery { accountRepository.getAccountsByProvider(AccountProvider.CALDAV) } returns listOf(account1, account2)
        coEvery { credentialManager.hasCredentials(1L) } returns true

        val result = credentialProvider.hasAnyCredentials()

        assertTrue(result)
    }

    @Test
    fun `hasAnyCredentials returns false when no accounts`() = runTest {
        coEvery { accountRepository.getAccountsByProvider(AccountProvider.CALDAV) } returns emptyList()

        val result = credentialProvider.hasAnyCredentials()

        assertFalse(result)
    }

    @Test
    fun `hasAnyCredentials returns false when accounts have no credentials`() = runTest {
        val account1 = createAccount(1L)
        coEvery { accountRepository.getAccountsByProvider(AccountProvider.CALDAV) } returns listOf(account1)
        coEvery { credentialManager.hasCredentials(1L) } returns false

        val result = credentialProvider.hasAnyCredentials()

        assertFalse(result)
    }

    // ==================== saveCredentials tests ====================

    @Test
    fun `saveCredentials saves to manager`() = runTest {
        val accountId = 1L
        val credentials = Credentials(
            username = "user@example.com",
            password = "password",
            serverUrl = "https://caldav.example.com"
        )

        every { credentialManager.isEncryptionAvailable() } returns true
        coEvery { credentialManager.saveCredentials(accountId, any()) } returns true

        val result = credentialProvider.saveCredentials(accountId, credentials)

        assertTrue(result)
        coVerify { credentialManager.saveCredentials(accountId, any()) }
    }

    @Test
    fun `saveCredentials returns false when encryption unavailable`() = runTest {
        val credentials = Credentials("user", "pass", "https://server.com")

        every { credentialManager.isEncryptionAvailable() } returns false

        val result = credentialProvider.saveCredentials(1L, credentials)

        assertFalse(result)
        coVerify(exactly = 0) { credentialManager.saveCredentials(any(), any()) }
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

        every { credentialManager.isEncryptionAvailable() } returns true
        coEvery { credentialManager.saveCredentials(accountId, any()) } returns true

        val result = credentialProvider.saveCredentialsWithTrust(accountId, credentials, trustInsecure = true)

        assertTrue(result)
        coVerify {
            credentialManager.saveCredentials(accountId, match { it.trustInsecure })
        }
    }

    // ==================== deleteCredentials tests ====================

    @Test
    fun `deleteCredentials removes from manager`() = runTest {
        val accountId = 1L

        val result = credentialProvider.deleteCredentials(accountId)

        assertTrue(result)
        coVerify { credentialManager.deleteCredentials(accountId) }
    }

    // ==================== clearAllCredentials tests ====================

    @Test
    fun `clearAllCredentials delegates to manager`() = runTest {
        credentialProvider.clearAllCredentials()

        coVerify { credentialManager.clearAllCredentials() }
    }

    // ==================== getTrustInsecure tests ====================

    @Test
    fun `getTrustInsecure returns true when enabled`() = runTest {
        val creds = AccountCredentials("user", "pass", "https://server.com", trustInsecure = true)
        coEvery { credentialManager.getCredentials(1L) } returns creds

        val result = credentialProvider.getTrustInsecure(1L)

        assertTrue(result)
    }

    @Test
    fun `getTrustInsecure returns false when disabled`() = runTest {
        val creds = AccountCredentials("user", "pass", "https://server.com", trustInsecure = false)
        coEvery { credentialManager.getCredentials(1L) } returns creds

        val result = credentialProvider.getTrustInsecure(1L)

        assertFalse(result)
    }

    @Test
    fun `getTrustInsecure returns false when no credentials`() = runTest {
        coEvery { credentialManager.getCredentials(1L) } returns null

        val result = credentialProvider.getTrustInsecure(1L)

        assertFalse(result)
    }

    // ==================== setTrustInsecure tests ====================

    @Test
    fun `setTrustInsecure updates existing credentials`() = runTest {
        val creds = AccountCredentials("user", "pass", "https://server.com", trustInsecure = false)
        coEvery { credentialManager.getCredentials(1L) } returns creds
        coEvery { credentialManager.saveCredentials(1L, any()) } returns true

        val result = credentialProvider.setTrustInsecure(1L, true)

        assertTrue(result)
        coVerify {
            credentialManager.saveCredentials(1L, match { it.trustInsecure })
        }
    }

    @Test
    fun `setTrustInsecure returns false when no credentials`() = runTest {
        coEvery { credentialManager.getCredentials(1L) } returns null

        val result = credentialProvider.setTrustInsecure(1L, true)

        assertFalse(result)
        coVerify(exactly = 0) { credentialManager.saveCredentials(any(), any()) }
    }

    // ==================== getAccountCredentials tests ====================

    @Test
    fun `getAccountCredentials returns raw credentials`() = runTest {
        val creds = AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server.com",
            trustInsecure = true
        )
        coEvery { credentialManager.getCredentials(1L) } returns creds

        val result = credentialProvider.getAccountCredentials(1L)

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
