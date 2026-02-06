package org.onekash.kashcal.sync.provider.caldav

import android.graphics.Color
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.credential.AccountCredentials
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
import org.onekash.kashcal.sync.quirks.CalDavQuirks

/**
 * Unit tests for CalDavAccountDiscoveryService.
 *
 * Tests verify:
 * - URL normalization
 * - Discovery flow (principal -> calendar home -> calendars)
 * - Account and calendar creation/update
 * - Credential saving
 * - Error handling (auth, network, SSL)
 * - Calendar refresh
 * - Account removal
 */
class CalDavAccountDiscoveryServiceTest {

    private lateinit var calDavClientFactory: CalDavClientFactory
    private lateinit var accountRepository: AccountRepository
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var mockClient: CalDavClient
    private lateinit var discoveryService: CalDavAccountDiscoveryService

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        // Mock android.graphics.Color.parseColor
        mockkStatic(Color::class)
        every { Color.parseColor(any()) } answers {
            val colorStr = firstArg<String>()
            // Simple hex color parsing for tests
            parseHexColor(colorStr)
        }

        calDavClientFactory = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
        calendarRepository = mockk(relaxed = true)
        mockClient = mockk(relaxed = true)

        // Factory returns our mock client
        every { calDavClientFactory.createClient(any(), any()) } returns mockClient

        discoveryService = CalDavAccountDiscoveryService(
            calDavClientFactory,
            accountRepository,
            calendarRepository
        )
    }

    /**
     * Simple hex color parser for tests (mimics android.graphics.Color.parseColor)
     */
    private fun parseHexColor(colorStr: String): Int {
        val clean = if (colorStr.startsWith("#")) colorStr.substring(1) else colorStr
        return when (clean.length) {
            6 -> (0xFF000000 or java.lang.Long.parseLong(clean, 16)).toInt()
            8 -> java.lang.Long.parseLong(clean, 16).toInt()
            else -> 0
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== URL Normalization Tests ====================

    @Test
    fun `discoverAndCreateAccount adds https if missing`() = runTest {
        setupSuccessfulDiscovery()

        discoveryService.discoverAndCreateAccount(
            serverUrl = "nextcloud.example.com",
            username = "user",
            password = "pass"
        )

        // Verify factory was called with normalized URL
        verify {
            calDavClientFactory.createClient(
                match<Credentials> { it.serverUrl == "https://nextcloud.example.com" },
                any()
            )
        }
    }

    @Test
    fun `discoverAndCreateAccount removes trailing slash`() = runTest {
        setupSuccessfulDiscovery()

        discoveryService.discoverAndCreateAccount(
            serverUrl = "https://nextcloud.example.com/",
            username = "user",
            password = "pass"
        )

        verify {
            calDavClientFactory.createClient(
                match<Credentials> { it.serverUrl == "https://nextcloud.example.com" },
                any()
            )
        }
    }

    @Test
    fun `discoverAndCreateAccount preserves http protocol`() = runTest {
        setupSuccessfulDiscovery()

        discoveryService.discoverAndCreateAccount(
            serverUrl = "http://localhost:8080",
            username = "user",
            password = "pass"
        )

        verify {
            calDavClientFactory.createClient(
                match<Credentials> { it.serverUrl == "http://localhost:8080" },
                any()
            )
        }
    }

    @Test
    fun `discoverAndCreateAccount returns error for invalid URL`() = runTest {
        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "://invalid",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.Error)
        assertTrue((result as DiscoveryResult.Error).message.contains("Invalid server URL"))
    }

    // ==================== Successful Discovery Tests ====================

    @Test
    fun `discoverAndCreateAccount creates account and calendars on success`() = runTest {
        setupSuccessfulDiscovery()

        coEvery { accountRepository.getAccountByProviderAndEmail(AccountProvider.CALDAV, "user") } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.Success)
        val success = result as DiscoveryResult.Success
        assertEquals(AccountProvider.CALDAV, success.account.provider)
        assertEquals("user", success.account.email)
        assertEquals("https://nextcloud.example.com/dav/calendars/user/", success.account.homeSetUrl)
        assertEquals(2, success.calendars.size)

        // Verify credentials were saved
        coVerify { accountRepository.saveCredentials(1L, any()) }
    }

    @Test
    fun `discoverAndCreateAccount updates existing account`() = runTest {
        setupSuccessfulDiscovery()

        val existingAccount = Account(
            id = 5L,
            provider = AccountProvider.CALDAV,
            email = "user",
            displayName = "Old Name",
            principalUrl = "old-principal",
            homeSetUrl = "old-home",
            isEnabled = false
        )
        coEvery { accountRepository.getAccountByProviderAndEmail(AccountProvider.CALDAV, "user") } returns existingAccount
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.Success)
        val success = result as DiscoveryResult.Success
        assertEquals(5L, success.account.id)
        assertTrue(success.account.isEnabled)

        coVerify { accountRepository.updateAccount(match { it.id == 5L && it.isEnabled }) }
    }

    @Test
    fun `discoverAndCreateAccount sets homeSetUrl for DefaultQuirks`() = runTest {
        setupSuccessfulDiscovery()

        coEvery { accountRepository.getAccountByProviderAndEmail(any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.Success)
        val success = result as DiscoveryResult.Success

        // CRITICAL: homeSetUrl must be set for DefaultQuirks
        assertNotNull(success.account.homeSetUrl)
        assertEquals("https://nextcloud.example.com/dav/calendars/user/", success.account.homeSetUrl)
    }

    @Test
    fun `discoverAndCreateAccount passes trustInsecure to credentials`() = runTest {
        setupSuccessfulDiscovery()

        coEvery { accountRepository.getAccountByProviderAndEmail(any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        discoveryService.discoverAndCreateAccount(
            serverUrl = "https://self-signed.local",
            username = "admin",
            password = "pass",
            trustInsecure = true
        )

        // Verify client factory received trustInsecure
        verify {
            calDavClientFactory.createClient(
                match<Credentials> { it.trustInsecure },
                any()
            )
        }

        // Verify credentials saved with trustInsecure
        coVerify {
            accountRepository.saveCredentials(1L, match<AccountCredentials> { it.trustInsecure })
        }
    }

    @Test
    fun `discoverAndCreateAccount skips reminder calendars`() = runTest {
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success(
            "https://nextcloud.example.com/dav/principals/user/"
        )
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            "https://nextcloud.example.com/dav/calendars/user/"
        )
        coEvery { mockClient.listCalendars(any()) } returns CalDavResult.Success(
            listOf(
                CalDavCalendar(
                    href = "/dav/calendars/user/personal/",
                    url = "https://nextcloud.example.com/dav/calendars/user/personal/",
                    displayName = "Personal",
                    color = "#FF0000",
                    ctag = "ctag1",
                    isReadOnly = false
                ),
                CalDavCalendar(
                    href = "/dav/calendars/user/reminders/",
                    url = "https://nextcloud.example.com/dav/calendars/user/reminders/",
                    displayName = "Reminders",
                    color = "#00FF00",
                    ctag = "ctag2",
                    isReadOnly = false
                ),
                CalDavCalendar(
                    href = "/dav/calendars/user/tasks/",
                    url = "https://nextcloud.example.com/dav/calendars/user/tasks/",
                    displayName = "Tasks",
                    color = "#0000FF",
                    ctag = "ctag3",
                    isReadOnly = false
                )
            )
        )

        coEvery { accountRepository.getAccountByProviderAndEmail(any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.Success)
        // Only Personal calendar should be created (Reminders and Tasks skipped)
        assertEquals(1, (result as DiscoveryResult.Success).calendars.size)
        assertEquals("Personal", result.calendars[0].displayName)
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `discoverAndCreateAccount returns AuthError on 401`() = runTest {
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Error(
            401, "Unauthorized"
        )

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "wrongpass"
        )

        assertTrue(result is DiscoveryResult.AuthError)
        assertTrue((result as DiscoveryResult.AuthError).message.contains("Invalid username or password"))
    }

    @Test
    fun `discoverAndCreateAccount returns Error on network failure`() = runTest {
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Error(
            0, "Network timeout"
        )

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.Error)
        assertTrue((result as DiscoveryResult.Error).message.contains("Network error"))
    }

    @Test
    fun `discoverAndCreateAccount returns SSL error with hint`() = runTest {
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Error(
            0, "SSL certificate problem"
        )

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://self-signed.local",
            username = "user",
            password = "pass",
            trustInsecure = false
        )

        assertTrue(result is DiscoveryResult.Error)
        val error = result as DiscoveryResult.Error
        assertTrue(error.message.contains("Trust insecure"))
    }

    @Test
    fun `discoverAndCreateAccount returns Error on 404`() = runTest {
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Error(
            404, "Not found"
        )

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://not-caldav.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.Error)
        assertTrue((result as DiscoveryResult.Error).message.contains("not found"))
    }

    @Test
    fun `discoverAndCreateAccount returns Error on 500`() = runTest {
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Error(
            500, "Internal server error"
        )

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.Error)
        assertTrue((result as DiscoveryResult.Error).message.contains("temporarily unavailable"))
    }

    @Test
    fun `discoverAndCreateAccount returns Error when no calendars found`() = runTest {
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success(
            "https://nextcloud.example.com/dav/principals/user/"
        )
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            "https://nextcloud.example.com/dav/calendars/user/"
        )
        coEvery { mockClient.listCalendars(any()) } returns CalDavResult.Success(emptyList())

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.Error)
        assertTrue((result as DiscoveryResult.Error).message.contains("No calendars found"))
    }

    // ==================== Calendar Refresh Tests ====================

    @Test
    fun `refreshCalendars updates existing calendars`() = runTest {
        val account = createAccount(1L)
        val existingCalendar = createCalendar(1L, account.id, "https://server/cal1/")

        coEvery { accountRepository.getAccountById(1L) } returns account
        coEvery { accountRepository.getCredentials(1L) } returns AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server"
        )
        coEvery { calendarRepository.getCalendarsForAccountOnce(1L) } returns listOf(existingCalendar)
        coEvery { calendarRepository.getCalendarByUrl("https://server/cal1/") } returns existingCalendar
        coEvery { mockClient.listCalendars(any()) } returns CalDavResult.Success(
            listOf(
                CalDavCalendar(
                    href = "/cal1/",
                    url = "https://server/cal1/",
                    displayName = "Updated Name",
                    color = "#FF0000",
                    ctag = "new-ctag",
                    isReadOnly = true
                )
            )
        )

        val result = discoveryService.refreshCalendars(1L)

        assertTrue(result is DiscoveryResult.Success)
        coVerify { calendarRepository.updateCalendar(match { it.displayName == "Updated Name" }) }
    }

    @Test
    fun `refreshCalendars removes deleted calendars`() = runTest {
        val account = createAccount(1L)
        val existingCalendar = createCalendar(1L, account.id, "https://server/deleted/")

        coEvery { accountRepository.getAccountById(1L) } returns account
        coEvery { accountRepository.getCredentials(1L) } returns AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server"
        )
        coEvery { calendarRepository.getCalendarsForAccountOnce(1L) } returns listOf(existingCalendar)
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { mockClient.listCalendars(any()) } returns CalDavResult.Success(emptyList())

        discoveryService.refreshCalendars(1L)

        coVerify { calendarRepository.deleteCalendar(existingCalendar.id) }
    }

    @Test
    fun `refreshCalendars returns AuthError when credentials missing`() = runTest {
        val account = createAccount(1L)

        coEvery { accountRepository.getAccountById(1L) } returns account
        coEvery { accountRepository.getCredentials(1L) } returns null

        val result = discoveryService.refreshCalendars(1L)

        assertTrue(result is DiscoveryResult.AuthError)
        assertTrue((result as DiscoveryResult.AuthError).message.contains("sign in again"))
    }

    @Test
    fun `refreshCalendars returns Error when account not found`() = runTest {
        coEvery { accountRepository.getAccountById(99L) } returns null

        val result = discoveryService.refreshCalendars(99L)

        assertTrue(result is DiscoveryResult.Error)
        assertTrue((result as DiscoveryResult.Error).message.contains("not found"))
    }

    // ==================== Account Removal Tests ====================

    @Test
    fun `removeAccount calls deleteAccount on repository`() = runTest {
        discoveryService.removeAccount(1L)

        // Repository handles all cleanup internally (credentials, reminders, pending ops)
        coVerify { accountRepository.deleteAccount(1L) }
    }

    @Test
    fun `removeAccountByEmail finds and deletes account`() = runTest {
        val account = createAccount(1L)
        coEvery { accountRepository.getAccountByProviderAndEmail(AccountProvider.CALDAV, "user@example.com") } returns account

        discoveryService.removeAccountByEmail("user@example.com")

        coVerify { accountRepository.deleteAccount(1L) }
    }

    // ==================== Server Display Name Tests ====================

    @Test
    fun `discoverAndCreateAccount extracts FastMail display name`() = runTest {
        setupSuccessfulDiscovery(serverUrl = "https://caldav.fastmail.com")

        coEvery { accountRepository.getAccountByProviderAndEmail(any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        discoveryService.discoverAndCreateAccount(
            serverUrl = "https://caldav.fastmail.com",
            username = "user",
            password = "pass"
        )

        coVerify {
            accountRepository.createAccount(match<Account> { it.displayName == "FastMail" })
        }
    }

    @Test
    fun `discoverAndCreateAccount uses hostname for unknown servers`() = runTest {
        setupSuccessfulDiscovery(serverUrl = "https://caldav.myserver.org")

        coEvery { accountRepository.getAccountByProviderAndEmail(any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        discoveryService.discoverAndCreateAccount(
            serverUrl = "https://caldav.myserver.org",
            username = "user",
            password = "pass"
        )

        coVerify {
            accountRepository.createAccount(match<Account> { it.displayName == "caldav.myserver.org" })
        }
    }

    // ==================== Color Parsing Tests ====================

    @Test
    fun `discoverAndCreateAccount parses RRGGBB color`() = runTest {
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success(
            "https://server/principal/"
        )
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            "https://server/calendars/"
        )
        coEvery { mockClient.listCalendars(any()) } returns CalDavResult.Success(
            listOf(
                CalDavCalendar(
                    href = "/cal/",
                    url = "https://server/cal/",
                    displayName = "Test",
                    color = "#FF5733",
                    ctag = "ctag",
                    isReadOnly = false
                )
            )
        )

        coEvery { accountRepository.getAccountByProviderAndEmail(any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://server",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.Success)
        val calendar = (result as DiscoveryResult.Success).calendars.first()
        assertEquals(0xFFFF5733.toInt(), calendar.color)
    }

    @Test
    fun `discoverAndCreateAccount parses RRGGBBAA color`() = runTest {
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success(
            "https://server/principal/"
        )
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            "https://server/calendars/"
        )
        coEvery { mockClient.listCalendars(any()) } returns CalDavResult.Success(
            listOf(
                CalDavCalendar(
                    href = "/cal/",
                    url = "https://server/cal/",
                    displayName = "Test",
                    color = "#FF5733CC",  // RRGGBBAA
                    ctag = "ctag",
                    isReadOnly = false
                )
            )
        )

        coEvery { accountRepository.getAccountByProviderAndEmail(any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://server",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.Success)
        val calendar = (result as DiscoveryResult.Success).calendars.first()
        // RRGGBBAA -> AARRGGBB
        assertEquals(0xCCFF5733.toInt(), calendar.color)
    }

    // ==================== Helper Methods ====================

    private fun setupSuccessfulDiscovery(serverUrl: String = "https://nextcloud.example.com") {
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success(
            "$serverUrl/dav/principals/user/"
        )
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            "$serverUrl/dav/calendars/user/"
        )
        coEvery { mockClient.listCalendars(any()) } returns CalDavResult.Success(
            listOf(
                CalDavCalendar(
                    href = "/dav/calendars/user/personal/",
                    url = "$serverUrl/dav/calendars/user/personal/",
                    displayName = "Personal",
                    color = "#FF0000",
                    ctag = "ctag1",
                    isReadOnly = false
                ),
                CalDavCalendar(
                    href = "/dav/calendars/user/work/",
                    url = "$serverUrl/dav/calendars/user/work/",
                    displayName = "Work",
                    color = "#00FF00",
                    ctag = "ctag2",
                    isReadOnly = false
                )
            )
        )
    }

    private fun createAccount(
        id: Long,
        homeSetUrl: String = "https://server/calendars/user/"
    ): Account {
        return Account(
            id = id,
            provider = AccountProvider.CALDAV,
            email = "user@example.com",
            displayName = "CalDAV Account",
            principalUrl = "https://server/principal/user/",
            homeSetUrl = homeSetUrl,
            isEnabled = true
        )
    }

    private fun createCalendar(
        id: Long,
        accountId: Long,
        caldavUrl: String
    ): Calendar {
        return Calendar(
            id = id,
            accountId = accountId,
            caldavUrl = caldavUrl,
            displayName = "Test Calendar",
            color = 0xFF4CAF50.toInt(),
            ctag = "ctag",
            isReadOnly = false,
            isDefault = false,
            isVisible = true
        )
    }
}
