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
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.sync.client.CalDavClient
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
    private lateinit var calDavClient: CalDavClient
    private lateinit var accountsDao: AccountsDao
    private lateinit var calendarsDao: CalendarsDao

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
        provider = "icloud",
        email = testAppleId,
        displayName = "iCloud",
        principalUrl = testPrincipalUrl,
        homeSetUrl = testHomeUrl
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        calDavClient = mockk(relaxed = true)
        accountsDao = mockk(relaxed = true)
        calendarsDao = mockk(relaxed = true)

        // Default: no existing account
        coEvery { accountsDao.getByProviderAndEmail(any(), any()) } returns null
        coEvery { accountsDao.insert(any()) } returns 1L
        coEvery { calendarsDao.getByCaldavUrl(any()) } returns null
        coEvery { calendarsDao.insert(any()) } returns 1L
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createService(): ICloudAccountDiscoveryService {
        return ICloudAccountDiscoveryService(
            calDavClient = calDavClient,
            accountsDao = accountsDao,
            calendarsDao = calendarsDao
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
        coVerify { accountsDao.insert(match { it.email == testAppleId }) }

        // Verify calendars were created
        coVerify(exactly = 2) { calendarsDao.insert(any()) }
    }

    @Test
    fun `discovery sets credentials on client before discovery`() = runTest {
        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.success(testPrincipalUrl)
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(testHomeUrl)
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(testCalDavCalendars)

        val service = createService()
        service.discoverAndCreateAccount(testAppleId, testPassword)

        // Verify credentials were set
        coVerify { calDavClient.setCredentials(testAppleId, testPassword) }
    }

    @Test
    fun `discovery updates existing account if found`() = runTest {
        // Setup existing account
        coEvery { accountsDao.getByProviderAndEmail(any(), any()) } returns testDbAccount

        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.success(testPrincipalUrl)
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(testHomeUrl)
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(testCalDavCalendars)

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(result is DiscoveryResult.Success)

        // Verify update was called instead of insert
        coVerify { accountsDao.update(any()) }
        coVerify(exactly = 0) { accountsDao.insert(any()) }
    }

    @Test
    fun `discovery skips reminder calendars`() = runTest {
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
        // Only 2 calendars should be created (reminders skipped)
        coVerify(exactly = 2) { calendarsDao.insert(any()) }
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
        coVerify { calendarsDao.insert(any()) }
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
        coVerify { calendarsDao.insert(any()) }
    }

    // ==================== Account Removal Tests ====================

    @Test
    fun `removeAccount deletes account from database`() = runTest {
        coEvery { accountsDao.getById(1L) } returns testDbAccount

        val service = createService()
        service.removeAccount(1L)

        coVerify { accountsDao.delete(testDbAccount) }
    }

    @Test
    fun `removeAccountByEmail deletes iCloud account by email`() = runTest {
        coEvery { accountsDao.getByProviderAndEmail("icloud", testAppleId) } returns testDbAccount

        val service = createService()
        service.removeAccountByEmail(testAppleId)

        coVerify { accountsDao.delete(testDbAccount) }
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
