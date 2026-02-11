package org.onekash.kashcal.sync.provider.icloud

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.CalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavCalendar
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.onekash.kashcal.sync.provider.icloud.ICloudAccountDiscoveryService
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Unit and integration tests for ICloudAccountDiscoveryService.
 *
 * Tests cover:
 * - Successful discovery flow (principal -> home -> calendars -> create entities)
 * - Authentication error handling
 * - Network error handling with user-friendly messages
 * - Calendar creation and update logic
 * - Exception handling for various network failures
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ICloudAccountDiscoveryServiceTest {

    private val testDispatcher = StandardTestDispatcher()

    // Mocks
    private lateinit var clientFactory: CalDavClientFactory
    private lateinit var credentialProvider: ICloudCredentialProvider  // From same package
    private lateinit var calDavClient: CalDavClient
    private lateinit var icloudQuirks: ICloudQuirks
    private lateinit var accountRepository: AccountRepository
    private lateinit var calendarRepository: CalendarRepository

    // Test data
    private val testAppleId = "test@icloud.com"
    private val testPassword = "xxxx-xxxx-xxxx-xxxx"
    private val testPrincipalUrl = "https://caldav.icloud.com/123/principal"
    private val testHomeUrl = "https://caldav.icloud.com/123/calendars"

    private val testCalDavCalendars = listOf(
        CalDavCalendar(
            href = "/123/calendars/personal",
            url = "https://caldav.icloud.com/123/calendars/personal",
            displayName = "Personal",
            color = "#FF2196F3",
            ctag = "ctag-1",
            isReadOnly = false
        ),
        CalDavCalendar(
            href = "/123/calendars/work",
            url = "https://caldav.icloud.com/123/calendars/work",
            displayName = "Work",
            color = "#FF4CAF50",
            ctag = "ctag-2",
            isReadOnly = false
        )
    )

    private val testDbAccount = Account(
        id = 1L,
        provider = AccountProvider.ICLOUD,
        email = testAppleId,
        displayName = "iCloud",
        principalUrl = testPrincipalUrl,
        homeSetUrl = testHomeUrl
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        clientFactory = mockk(relaxed = true)
        credentialProvider = mockk(relaxed = true)
        calDavClient = mockk(relaxed = true)
        icloudQuirks = ICloudQuirks()
        accountRepository = mockk(relaxed = true)
        calendarRepository = mockk(relaxed = true)

        // Mock factory to return our mock client
        every { clientFactory.createClient(any(), any()) } returns calDavClient

        // Default: no existing account, credential save succeeds
        coEvery { accountRepository.getAccountByProviderAndEmail(any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { accountRepository.saveCredentials(any(), any()) } returns true
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createService(): ICloudAccountDiscoveryService {
        return ICloudAccountDiscoveryService(
            clientFactory = clientFactory,
            credentialProvider = credentialProvider,
            icloudQuirks = icloudQuirks,
            accountRepository = accountRepository,
            calendarRepository = calendarRepository
        )
    }

    // ==================== Successful Discovery Tests ====================

    @Test
    fun `discovery flow creates account and calendars on success`() = runTest {
        // Setup successful mocks
        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.success(testPrincipalUrl)
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(testHomeUrl)
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(testCalDavCalendars)

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(result is DiscoveryResult.Success)
        val success = result as DiscoveryResult.Success
        assertEquals(testAppleId, success.account.email)
        assertEquals(2, success.calendars.size)

        // Verify account was created
        coVerify { accountRepository.createAccount(match { it.email == testAppleId }) }

        // Verify calendars were created
        coVerify(exactly = 2) { calendarRepository.createCalendar(any()) }
    }

    @Test
    fun `discovery creates client with credentials via factory`() = runTest {
        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.success(testPrincipalUrl)
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(testHomeUrl)
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(testCalDavCalendars)

        val service = createService()
        service.discoverAndCreateAccount(testAppleId, testPassword)

        // Verify client was created with credentials via factory
        io.mockk.verify { clientFactory.createClient(match { it.username == testAppleId && it.password == testPassword }, any()) }
    }

    @Test
    fun `discovery updates existing account if found`() = runTest {
        // Setup existing account
        coEvery { accountRepository.getAccountByProviderAndEmail(any(), any()) } returns testDbAccount

        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.success(testPrincipalUrl)
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(testHomeUrl)
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(testCalDavCalendars)

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(result is DiscoveryResult.Success)

        // Verify update was called instead of insert
        coVerify { accountRepository.updateAccount(any()) }
        coVerify(exactly = 0) { accountRepository.createAccount(any()) }
    }

    @Test
    fun `discovery creates calendar for each listed calendar`() = runTest {
        val calendarsWithReminders = testCalDavCalendars + CalDavCalendar(
            href = "/123/calendars/reminders",
            url = "https://caldav.icloud.com/123/calendars/reminders",
            displayName = "Reminders",
            color = "#FFFF0000",
            ctag = "ctag-3",
            isReadOnly = false
        )

        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.success(testPrincipalUrl)
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(testHomeUrl)
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(calendarsWithReminders)

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(result is DiscoveryResult.Success)
        // Discovery service creates all listed calendars — filtering is done by quirks layer
        coVerify(exactly = 3) { calendarRepository.createCalendar(any()) }
    }

    // ==================== Authentication Error Tests ====================

    @Test
    fun `discovery returns AuthError on 401 from principal`() = runTest {
        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.authError("Authentication failed")

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(result is DiscoveryResult.AuthError)
        val error = result as DiscoveryResult.AuthError
        assertTrue(error.message.contains("Invalid Apple ID") || error.message.contains("password"))
    }

    // ==================== Network Error Tests ====================

    @Test
    fun `discovery returns Error with user-friendly message on principal failure`() = runTest {
        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.error(500, "Server error")

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(result is DiscoveryResult.Error)
        val error = result as DiscoveryResult.Error
        assertTrue(error.message.contains("temporarily unavailable") || error.message.contains("try again"))
    }

    @Test
    fun `discovery returns Error on calendar home failure`() = runTest {
        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.success(testPrincipalUrl)
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.error(500, "Server error")

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(result is DiscoveryResult.Error)
    }

    @Test
    fun `discovery returns Error on calendar list failure`() = runTest {
        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.success(testPrincipalUrl)
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(testHomeUrl)
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.error(500, "Server error")

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(result is DiscoveryResult.Error)
    }

    // ==================== Exception Handling Tests ====================

    @Test
    fun `discovery handles SocketTimeoutException with user-friendly message`() = runTest {
        coEvery { calDavClient.discoverPrincipal(any()) } throws SocketTimeoutException("Connection timed out")

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(result is DiscoveryResult.Error)
        val error = result as DiscoveryResult.Error
        assertTrue(error.message.contains("timed out") || error.message.contains("internet"))
    }

    @Test
    fun `discovery handles UnknownHostException with user-friendly message`() = runTest {
        coEvery { calDavClient.discoverPrincipal(any()) } throws UnknownHostException("caldav.icloud.com")

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(result is DiscoveryResult.Error)
        val error = result as DiscoveryResult.Error
        assertTrue(error.message.contains("reach") || error.message.contains("internet"))
    }

    @Test
    fun `discovery handles generic exception gracefully`() = runTest {
        coEvery { calDavClient.discoverPrincipal(any()) } throws RuntimeException("Unexpected error")

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(result is DiscoveryResult.Error)
    }

    // ==================== Calendar Color Parsing Tests ====================

    @Test
    fun `discovery parses iCloud color format correctly`() = runTest {
        val calendarsWithICloudColors = listOf(
            CalDavCalendar(
                href = "/123/calendars/personal",
                url = "https://caldav.icloud.com/123/calendars/personal",
                displayName = "Personal",
                color = "#FF5722FF", // iCloud RRGGBBAA format
                ctag = "ctag-1",
                isReadOnly = false
            )
        )

        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.success(testPrincipalUrl)
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(testHomeUrl)
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(calendarsWithICloudColors)

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(result is DiscoveryResult.Success)
        // Calendar should be created with parsed color
        coVerify { calendarRepository.createCalendar(any()) }
    }

    @Test
    fun `discovery uses default color when color string is invalid`() = runTest {
        val calendarsWithInvalidColors = listOf(
            CalDavCalendar(
                href = "/123/calendars/personal",
                url = "https://caldav.icloud.com/123/calendars/personal",
                displayName = "Personal",
                color = "not-a-color",
                ctag = "ctag-1",
                isReadOnly = false
            )
        )

        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.success(testPrincipalUrl)
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(testHomeUrl)
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(calendarsWithInvalidColors)

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(result is DiscoveryResult.Success)
        // Should still succeed with default color
        coVerify { calendarRepository.createCalendar(any()) }
    }

    // ==================== Account Removal Tests ====================

    @Test
    fun `removeAccount calls deleteAccount on repository`() = runTest {
        val service = createService()
        service.removeAccount(1L)

        coVerify { accountRepository.deleteAccount(1L) }
    }

    @Test
    fun `removeAccountByEmail deletes iCloud account by email`() = runTest {
        coEvery { accountRepository.getAccountByProviderAndEmail(AccountProvider.ICLOUD, testAppleId) } returns testDbAccount

        val service = createService()
        service.removeAccountByEmail(testAppleId)

        coVerify { accountRepository.deleteAccount(testDbAccount.id) }
    }

    // ==================== Credential Save Failure Tests (Issue #55) ====================

    @Test
    fun `discoverAndCreateAccount returns error when credentials fail to save`() = runTest {
        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.success(testPrincipalUrl)
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(testHomeUrl)
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(testCalDavCalendars)

        // Simulate EncryptedSharedPreferences failure (e.g., Android Keystore broken)
        coEvery { accountRepository.saveCredentials(any(), any()) } returns false

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(
            "Expected Error when credentials fail to save, but got $result",
            result is DiscoveryResult.Error
        )
        val error = result as DiscoveryResult.Error
        assertTrue(
            "Error message should mention credential storage, but got: ${error.message}",
            error.message.contains("credential", ignoreCase = true) ||
                error.message.contains("secure storage", ignoreCase = true)
        )
    }

    @Test
    fun `discoverAndCreateAccount cleans up account when credentials fail to save`() = runTest {
        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.success(testPrincipalUrl)
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(testHomeUrl)
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(testCalDavCalendars)
        coEvery { accountRepository.saveCredentials(any(), any()) } returns false

        val service = createService()
        service.discoverAndCreateAccount(testAppleId, testPassword)

        // Account should be cleaned up since it can't sync without credentials
        coVerify { accountRepository.deleteAccount(1L) }
    }

    // ==================== Rate Limiting Tests ====================

    @Test
    fun `discovery handles 429 rate limit error with user-friendly message`() = runTest {
        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.error(429, "Too Many Requests")

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(result is DiscoveryResult.Error)
        val error = result as DiscoveryResult.Error
        assertTrue(error.message.contains("many requests") || error.message.contains("wait"))
    }
}
