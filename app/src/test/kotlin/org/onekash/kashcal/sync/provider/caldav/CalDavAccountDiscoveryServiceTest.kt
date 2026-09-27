package org.onekash.kashcal.sync.provider.caldav

import android.graphics.Color
import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.credential.AccountCredentials
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.CalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavCalendar
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.discovery.DiscoveryErrorReason
import org.onekash.kashcal.sync.discovery.DiscoveryResult

/**
 * Tests [CalDavAccountDiscoveryService]:
 * - URL normalization
 * - Discovery flow (principal -> calendar home -> calendars), including several home sets
 * - Account and calendar creation and update, and same-username accounts on two servers
 * - Credential saving, and cleanup when the save fails
 * - Error handling (auth, network, SSL, refused connections)
 * - Calendar refresh, including confirming a calendar is gone before deleting it
 * - Path probing, display names, color parsing
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

        mockkStatic(Color::class)
        every { Color.parseColor(any()) } answers {
            val colorStr = firstArg<String>()
            parseHexColor(colorStr)
        }

        calDavClientFactory = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
        calendarRepository = mockk(relaxed = true)
        mockClient = mockk(relaxed = true)

        every { calDavClientFactory.createClient(any(), any()) } returns mockClient

        // Default: credential save succeeds (overridden in credential-failure tests)
        coEvery { accountRepository.saveCredentials(any(), any()) } returns true

        discoveryService = CalDavAccountDiscoveryService(
            calDavClientFactory,
            accountRepository,
            calendarRepository
        )
    }

    /**
     * Stands in for android.graphics.Color.parseColor: `#RRGGBB` gets full alpha, `#AARRGGBB`
     * is taken as is, any other length gives 0.
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

        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(AccountProvider.CALDAV, "user", any()) } returns null
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

        coVerify { accountRepository.saveCredentials(1L, any()) }
    }

    @Test
    fun `discoverAndCreateAccount persists scheduling-delivery discovery`() = runTest {
        setupSuccessfulDiscovery()

        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(any(), any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        discoveryService.discoverAndCreateAccount(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass"
        )

        // Fails if discoverAndCreateAccount stops persisting these; the relaxed repository
        // mocks wouldn't notice a missing call.
        coVerify { accountRepository.updateScheduleOutboxUrl(1L, "https://nextcloud.example.com/dav/calendars/user/outbox/") }
        coVerify { calendarRepository.updateAutoScheduleSupported(1L, true) }
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
        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(AccountProvider.CALDAV, "user", any()) } returns existingAccount
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

        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(any(), any(), any()) } returns null
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

        // Sync builds DefaultQuirks from homeSetUrl and throws without it.
        assertNotNull(success.account.homeSetUrl)
        assertEquals("https://nextcloud.example.com/dav/calendars/user/", success.account.homeSetUrl)
    }

    @Test
    fun `discoverAndCreateAccount passes trustInsecure to credentials`() = runTest {
        setupSuccessfulDiscovery()

        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(any(), any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        discoveryService.discoverAndCreateAccount(
            serverUrl = "https://self-signed.local",
            username = "admin",
            password = "pass",
            trustInsecure = true
        )

        verify {
            calDavClientFactory.createClient(
                match<Credentials> { it.trustInsecure },
                any()
            )
        }

        coVerify {
            accountRepository.saveCredentials(1L, match<AccountCredentials> { it.trustInsecure })
        }
    }

    @Test
    fun `discoverAndCreateAccount creates calendar for each listed calendar`() = runTest {
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success(
            "https://nextcloud.example.com/dav/principals/user/"
        )
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            listOf("https://nextcloud.example.com/dav/calendars/user/")
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

        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(any(), any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.Success)
        // The service creates every listed calendar; filtering happens in the quirks.
        assertEquals(3, (result as DiscoveryResult.Success).calendars.size)
    }

    // ==================== Birthday Calendar Filter Tests ====================

    @Test
    fun `discoverAndCreateAccount includes birthday calendars`() = runTest {
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success(
            "https://nextcloud.example.com/dav/principals/user/"
        )
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            listOf("https://nextcloud.example.com/dav/calendars/user/")
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
                    href = "/dav/calendars/user/contact-birthdays/",
                    url = "https://nextcloud.example.com/dav/calendars/user/contact-birthdays/",
                    displayName = "Contact birthdays",
                    color = "#00FF00",
                    ctag = "ctag2",
                    isReadOnly = true
                )
            )
        )

        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(any(), any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.Success)
        // The service keeps every listed calendar; the quirks' component check drops
        // non-VEVENT collections.
        assertEquals(2, (result as DiscoveryResult.Success).calendars.size)
    }

    @Test
    fun `discoverAndCreateAccount includes calendar with birthday in name`() = runTest {
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success(
            "https://nextcloud.example.com/dav/principals/user/"
        )
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            listOf("https://nextcloud.example.com/dav/calendars/user/")
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
                    href = "/dav/calendars/user/party/",
                    url = "https://nextcloud.example.com/dav/calendars/user/party/",
                    displayName = "Birthday Party Planning",
                    color = "#00FF00",
                    ctag = "ctag2",
                    isReadOnly = false
                )
            )
        )

        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(any(), any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.Success)
        // The service doesn't filter by name.
        assertEquals(2, (result as DiscoveryResult.Success).calendars.size)
    }

    @Test
    fun `discoverCalendars includes birthday calendars`() = runTest {
        coEvery { mockClient.discoverWellKnown(any()) } returns CalDavResult.Error(404, "Not found")
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success(
            "https://nextcloud.example.com/dav/principals/user/"
        )
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            listOf("https://nextcloud.example.com/dav/calendars/user/")
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
                    href = "/dav/calendars/user/contact-birthdays/",
                    url = "https://nextcloud.example.com/dav/calendars/user/contact-birthdays/",
                    displayName = "Contact birthdays",
                    color = "#00FF00",
                    ctag = "ctag2",
                    isReadOnly = true
                )
            )
        )

        val result = discoveryService.discoverCalendars(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.CalendarsFound)
        val found = result as DiscoveryResult.CalendarsFound
        // The service keeps every listed calendar; the quirks' component check drops
        // non-VEVENT collections.
        assertEquals(2, found.calendars.size)
    }

    @Test
    fun `discoverCalendars includes calendar with birthday in name`() = runTest {
        coEvery { mockClient.discoverWellKnown(any()) } returns CalDavResult.Error(404, "Not found")
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success(
            "https://nextcloud.example.com/dav/principals/user/"
        )
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            listOf("https://nextcloud.example.com/dav/calendars/user/")
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
                    href = "/dav/calendars/user/party/",
                    url = "https://nextcloud.example.com/dav/calendars/user/party/",
                    displayName = "Birthday Party Planning",
                    color = "#00FF00",
                    ctag = "ctag2",
                    isReadOnly = false
                )
            )
        )

        val result = discoveryService.discoverCalendars(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.CalendarsFound)
        val found = result as DiscoveryResult.CalendarsFound
        // The service doesn't filter by name.
        assertEquals(2, found.calendars.size)
    }

    @Test
    fun `refreshCalendars does not filter birthday calendars`() = runTest {
        val account = createAccount(1L)

        coEvery { accountRepository.getAccountById(1L) } returns account
        coEvery { accountRepository.getCredentials(1L) } returns AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server"
        )
        coEvery { calendarRepository.getCalendarsForAccountOnce(1L) } returns emptyList()
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L
        coEvery { mockClient.discoverScheduleOutboxUrl(any()) } returns CalDavResult.Success(null)
        coEvery { mockClient.supportsAutoSchedule(any()) } returns CalDavResult.Success(false)
        coEvery { mockClient.listCalendars(any()) } returns CalDavResult.Success(
            listOf(
                CalDavCalendar(
                    href = "/cal/personal/",
                    url = "https://server/cal/personal/",
                    displayName = "Personal",
                    color = "#FF0000",
                    ctag = "ctag1",
                    isReadOnly = false
                ),
                CalDavCalendar(
                    href = "/cal/birthdays/",
                    url = "https://server/cal/birthdays/",
                    displayName = "Contact birthdays",
                    color = "#00FF00",
                    ctag = "ctag2",
                    isReadOnly = true
                )
            )
        )

        val result = discoveryService.refreshCalendars(1L)

        assertTrue(result is DiscoveryResult.Success)
        // Not filtered, as in discoverAndCreateAccount and discoverCalendars.
        coVerify(exactly = 2) { calendarRepository.createCalendar(any()) }
    }

    @Test
    fun `refreshCalendars re-probes scheduling-delivery discovery`() = runTest {
        val account = createAccount(1L)

        coEvery { accountRepository.getAccountById(1L) } returns account
        coEvery { accountRepository.getCredentials(1L) } returns AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server"
        )
        coEvery { calendarRepository.getCalendarsForAccountOnce(1L) } returns emptyList()
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 9L
        coEvery { mockClient.discoverScheduleOutboxUrl(any()) } returns
            CalDavResult.Success("https://server/calendars/user/outbox/")
        coEvery { mockClient.supportsAutoSchedule(any()) } returns CalDavResult.Success(true)
        coEvery { mockClient.listCalendars(any()) } returns CalDavResult.Success(
            listOf(
                CalDavCalendar(
                    href = "/cal/personal/",
                    url = "https://server/cal/personal/",
                    displayName = "Personal",
                    color = "#FF0000",
                    ctag = "ctag1",
                    isReadOnly = false
                )
            )
        )

        discoveryService.refreshCalendars(1L)

        // Fails if refreshCalendars stops persisting these.
        coVerify { accountRepository.updateScheduleOutboxUrl(1L, "https://server/calendars/user/outbox/") }
        coVerify { calendarRepository.updateAutoScheduleSupported(9L, true) }
    }

    @Test
    fun `discoverCalendars returns all listed calendars`() = runTest {
        coEvery { mockClient.discoverWellKnown(any()) } returns CalDavResult.Error(404, "Not found")
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success(
            "https://nextcloud.example.com/dav/principals/user/"
        )
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            listOf("https://nextcloud.example.com/dav/calendars/user/")
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

        val result = discoveryService.discoverCalendars(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.CalendarsFound)
        val found = result as DiscoveryResult.CalendarsFound
        // The service returns every listed calendar; filtering happens in the quirks.
        assertEquals(3, found.calendars.size)
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
        // A network error also triggers path probing (#54); every probe fails the same way.
        assertTrue((result as DiscoveryResult.Error).message.contains("CalDAV service not found"))
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
    fun `a refused connection is reported as such even after the fallback paths fail`() = runTest {
        // The server redirects discovery to plain http elsewhere: the principal lookup
        // and every probed fallback path are refused the same way.
        coEvery { mockClient.discoverWellKnown(any()) } returns
            CalDavResult.error(CalDavResult.CODE_TRANSPORT_REFUSED, "refused", isRetryable = true)
        coEvery { mockClient.discoverPrincipal(any()) } returns
            CalDavResult.error(CalDavResult.CODE_TRANSPORT_REFUSED, "refused", isRetryable = true)

        val created = discoveryService.discoverAndCreateAccount("https://dav.example.test", "user", "pass")
        val listed = discoveryService.discoverCalendars("https://dav.example.test", "user", "pass")

        assertEquals(DiscoveryErrorReason.INSECURE_CONNECTION_REFUSED, (created as DiscoveryResult.Error).reason)
        assertEquals(DiscoveryErrorReason.INSECURE_CONNECTION_REFUSED, (listed as DiscoveryResult.Error).reason)
    }

    @Test
    fun `a refused well-known followed by a working fallback does not blame the refusal`() = runTest {
        coEvery { mockClient.discoverWellKnown(any()) } returns
            CalDavResult.error(CalDavResult.CODE_TRANSPORT_REFUSED, "refused", isRetryable = true)
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success("https://dav.example.test/principals/user/")
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(listOf("https://dav.example.test/calendars/user/"))
        coEvery { mockClient.listCalendars(any()) } returns CalDavResult.Success(emptyList())

        val result = discoveryService.discoverCalendars("https://dav.example.test", "user", "pass")

        result as DiscoveryResult.Error
        assertTrue(result.message.contains("No calendars"))
        assertEquals(null, result.reason)
    }

    @Test
    fun `an ordinary failure carries no reason`() = runTest {
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Error(500, "Internal server error")

        val result = discoveryService.discoverAndCreateAccount("https://dav.example.test", "user", "pass")

        assertEquals(null, (result as DiscoveryResult.Error).reason)
    }

    @Test
    fun `a refresh whose listings were all refused says so`() = runTest {
        val cal = createCalendar(2L, 1L, "https://server/calendars/user/home/")
        stubRefreshAccount(listOf(cal))
        coEvery { mockClient.listCalendars(any()) } returns
            CalDavResult.error(CalDavResult.CODE_TRANSPORT_REFUSED, "refused", isRetryable = true)

        val result = discoveryService.refreshCalendars(1L)

        assertEquals(DiscoveryErrorReason.INSECURE_CONNECTION_REFUSED, (result as DiscoveryResult.Error).reason)
        coVerify(exactly = 0) { calendarRepository.deleteCalendar(any()) }
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
        // A 500 also triggers path probing (#54); every probe fails the same way.
        assertTrue((result as DiscoveryResult.Error).message.contains("CalDAV service not found"))
    }

    @Test
    fun `discoverAndCreateAccount returns Error when no calendars found`() = runTest {
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success(
            "https://nextcloud.example.com/dav/principals/user/"
        )
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            listOf("https://nextcloud.example.com/dav/calendars/user/")
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
    fun `refreshCalendars adopts server color change for existing calendar`() = runTest {
        val account = createAccount(1L)
        val existingCalendar = createCalendar(1L, account.id, "https://server/cal1/")
        // The helper gives existingCalendar the color 0xFF4CAF50 (green).

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
                    displayName = "Cal 1",
                    color = "#FF0000", // red
                    ctag = "new-ctag",
                    isReadOnly = false
                )
            )
        )

        discoveryService.refreshCalendars(1L)

        coVerify { calendarRepository.updateCalendar(match { it.color == 0xFFFF0000.toInt() }) }
    }

    @Test
    fun `refreshCalendars preserves local color when server returns null color`() = runTest {
        val account = createAccount(1L)
        val existingCalendar = createCalendar(1L, account.id, "https://server/cal1/")
        val localColor = existingCalendar.color

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
                    displayName = "Cal 1",
                    color = null, // server sends no calendar-color
                    ctag = "new-ctag",
                    isReadOnly = false
                )
            )
        )

        discoveryService.refreshCalendars(1L)

        coVerify { calendarRepository.updateCalendar(match { it.color == localColor }) }
    }

    // ==================== refreshCalendars keeps ctag/syncToken (#249) ====================

    @Test
    fun `refreshCalendars preserves local ctag when server returns new ctag`() = runTest {
        val account = createAccount(1L)
        val existingCalendar = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://server/cal1/",
            displayName = "Cal 1",
            color = 0xFF4CAF50.toInt(),
            ctag = "old-ctag",
            syncToken = "old-token",
            isReadOnly = false,
            isDefault = false,
            isVisible = true
        )

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
                    displayName = "Cal 1",
                    color = null,
                    ctag = "new-server-ctag",
                    isReadOnly = false
                )
            )
        )

        discoveryService.refreshCalendars(1L)

        coVerify { calendarRepository.updateCalendar(match { it.ctag == "old-ctag" }) }
    }

    @Test
    fun `refreshCalendars preserves local syncToken when server returns new ctag`() = runTest {
        // CalDavCalendar has no syncToken, so the .copy() update keeps it today. This pins
        // the contract so adding syncToken to the discovery model can't silently overwrite it.
        val account = createAccount(1L)
        val existingCalendar = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://server/cal1/",
            displayName = "Cal 1",
            color = 0xFF4CAF50.toInt(),
            ctag = "old-ctag",
            syncToken = "old-token",
            isReadOnly = false,
            isDefault = false,
            isVisible = true
        )

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
                    displayName = "Cal 1",
                    color = null,
                    ctag = "new-server-ctag",
                    isReadOnly = false
                )
            )
        )

        discoveryService.refreshCalendars(1L)

        coVerify { calendarRepository.updateCalendar(match { it.syncToken == "old-token" }) }
    }

    @Test
    fun `discoverAndCreateAccount preserves local ctag for existing calendar URLs`() = runTest {
        val existingCalendar = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://nextcloud.example.com/dav/calendars/user/personal/",
            displayName = "Personal",
            color = 0xFF4CAF50.toInt(),
            ctag = "old-ctag",
            syncToken = "old-token",
            isReadOnly = false,
            isDefault = false,
            isVisible = true
        )

        setupSuccessfulDiscovery()

        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(any(), any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        // Existing calendar at the personal URL; the work URL is new.
        coEvery {
            calendarRepository.getCalendarByUrl("https://nextcloud.example.com/dav/calendars/user/personal/")
        } returns existingCalendar
        coEvery {
            calendarRepository.getCalendarByUrl("https://nextcloud.example.com/dav/calendars/user/work/")
        } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 2L

        discoveryService.discoverAndCreateAccount(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass"
        )

        coVerify {
            calendarRepository.updateCalendar(
                match { it.ctag == "old-ctag" && it.syncToken == "old-token" }
            )
        }
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
        // The server confirms the calendar is gone when asked about it directly.
        coEvery { mockClient.probeCalendarCollection(existingCalendar.caldavUrl) } returns
            CalDavResult.notFoundError("Resource not found")

        discoveryService.refreshCalendars(1L)

        coVerify { calendarRepository.deleteCalendar(existingCalendar.id) }
    }

    // ==================== refreshCalendars: confirm before removing ====================

    private fun stubRefreshAccount(existing: List<Calendar>) {
        coEvery { accountRepository.getAccountById(1L) } returns createAccount(1L)
        coEvery { accountRepository.getCredentials(1L) } returns AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server"
        )
        coEvery { calendarRepository.getCalendarsForAccountOnce(1L) } returns existing
        coEvery { calendarRepository.getCalendarByUrl(any()) } answers {
            existing.firstOrNull { it.caldavUrl == firstArg<String>() }
        }
        coEvery { calendarRepository.createCalendar(any()) } returns 99L
    }

    private fun listed(calendar: Calendar) = CalDavCalendar(
        href = calendar.caldavUrl.removePrefix("https://server"),
        url = calendar.caldavUrl,
        displayName = calendar.displayName,
        color = null,
        ctag = "ctag",
        isReadOnly = false
    )

    @Test
    fun `refreshCalendars keeps every calendar when the listing comes back empty and probes are unreadable`() = runTest {
        val work = createCalendar(1L, 1L, "https://server/calendars/user/work/")
        val home = createCalendar(2L, 1L, "https://server/calendars/user/home/")
        stubRefreshAccount(listOf(work, home))
        // A hotspot login page parses as an empty listing...
        coEvery { mockClient.listCalendars(any()) } returns CalDavResult.Success(emptyList())
        // ...and the per-calendar probe can't read the page either.
        coEvery { mockClient.probeCalendarCollection(any()) } returns
            CalDavResult.error(500, "resourcetype not found in response")

        val result = discoveryService.refreshCalendars(1L)

        assertTrue("Expected Success, got $result", result is DiscoveryResult.Success)
        coVerify(exactly = 0) { calendarRepository.deleteCalendar(any()) }
        assertEquals(
            "Kept calendars are still reported, so the calendar count stays right",
            setOf(work.id, home.id),
            (result as DiscoveryResult.Success).calendars.map { it.id }.toSet()
        )
    }

    @Test
    fun `refreshCalendars keeps a calendar missing from a partial listing when the server says it still exists`() = runTest {
        val listedCal = createCalendar(1L, 1L, "https://server/calendars/user/work/")
        val unlisted = createCalendar(2L, 1L, "https://server/calendars/user/home/")
        stubRefreshAccount(listOf(listedCal, unlisted))
        coEvery { mockClient.listCalendars(any()) } returns CalDavResult.Success(listOf(listed(listedCal)))
        coEvery { mockClient.probeCalendarCollection(unlisted.caldavUrl) } returns CalDavResult.success(true)

        discoveryService.refreshCalendars(1L)

        coVerify(exactly = 0) { calendarRepository.deleteCalendar(any()) }
        coVerify { calendarRepository.updateCalendar(match { it.id == listedCal.id }) }
    }

    @Test
    fun `refreshCalendars removes an unlisted calendar the server answers 410 for`() = runTest {
        val gone = createCalendar(2L, 1L, "https://server/calendars/user/home/")
        stubRefreshAccount(listOf(gone))
        coEvery { mockClient.listCalendars(any()) } returns CalDavResult.Success(emptyList())
        coEvery { mockClient.probeCalendarCollection(gone.caldavUrl) } returns CalDavResult.error(410, "Gone")

        discoveryService.refreshCalendars(1L)

        coVerify { calendarRepository.deleteCalendar(gone.id) }
    }

    @Test
    fun `refreshCalendars removes an unlisted calendar that is no longer a calendar`() = runTest {
        val gone = createCalendar(2L, 1L, "https://server/calendars/user/home/")
        stubRefreshAccount(listOf(gone))
        coEvery { mockClient.listCalendars(any()) } returns CalDavResult.Success(emptyList())
        coEvery { mockClient.probeCalendarCollection(gone.caldavUrl) } returns CalDavResult.success(false)

        discoveryService.refreshCalendars(1L)

        coVerify { calendarRepository.deleteCalendar(gone.id) }
    }

    @Test
    fun `refreshCalendars removes an unlisted calendar the server answers 403 for`() = runTest {
        // Mailbox (Open-Xchange) answers 403, not 404, for a calendar that was deleted.
        val cal = createCalendar(2L, 1L, "https://server/calendars/user/home/")
        stubRefreshAccount(listOf(cal))
        coEvery { mockClient.listCalendars(any()) } returns CalDavResult.Success(emptyList())
        coEvery { mockClient.probeCalendarCollection(cal.caldavUrl) } returns
            CalDavResult.error(403, "Permission denied")

        discoveryService.refreshCalendars(1L)

        coVerify { calendarRepository.deleteCalendar(cal.id) }
    }

    @Test
    fun `refreshCalendars removes the calendars of a home set the account lost access to`() = runTest {
        // One home set answers 403 (a delegation or share was revoked) and so does each
        // of its calendars: they are gone for this account. The other home set's stay.
        val inRevokedHome = createCalendar(1L, 1L, "https://server/calendars/user/aaa/work/")
        val inGoodHome = createCalendar(2L, 1L, "https://server/calendars/user/bbb/shared/")
        stubRefreshAccount(listOf(inRevokedHome, inGoodHome))
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            listOf("https://server/calendars/user/aaa/", "https://server/calendars/user/bbb/")
        )
        coEvery { mockClient.listCalendars("https://server/calendars/user/aaa/") } returns
            CalDavResult.error(403, "Permission denied")
        coEvery { mockClient.listCalendars("https://server/calendars/user/bbb/") } returns
            CalDavResult.Success(listOf(listed(inGoodHome)))
        coEvery { mockClient.probeCalendarCollection(inRevokedHome.caldavUrl) } returns
            CalDavResult.error(403, "Permission denied")

        discoveryService.refreshCalendars(1L)

        coVerify(exactly = 1) { calendarRepository.deleteCalendar(inRevokedHome.id) }
        coVerify(exactly = 0) { calendarRepository.deleteCalendar(inGoodHome.id) }
    }

    @Test
    fun `refreshCalendars keeps calendars of a home set whose listing failed`() = runTest {
        val inFailedHome = createCalendar(1L, 1L, "https://server/calendars/user/aaa/work/")
        val inGoodHome = createCalendar(2L, 1L, "https://server/calendars/user/bbb/shared/")
        stubRefreshAccount(listOf(inFailedHome, inGoodHome))
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            listOf("https://server/calendars/user/aaa/", "https://server/calendars/user/bbb/")
        )
        coEvery { mockClient.listCalendars("https://server/calendars/user/aaa/") } returns
            CalDavResult.error(500, "Server error")
        coEvery { mockClient.listCalendars("https://server/calendars/user/bbb/") } returns
            CalDavResult.Success(listOf(listed(inGoodHome)))
        coEvery { mockClient.probeCalendarCollection(inFailedHome.caldavUrl) } returns CalDavResult.success(true)

        discoveryService.refreshCalendars(1L)

        coVerify(exactly = 0) { calendarRepository.deleteCalendar(any()) }
    }

    @Test
    fun `refreshCalendars probes only the calendars missing from the listing`() = runTest {
        val listedCal = createCalendar(1L, 1L, "https://server/calendars/user/work/")
        val unlisted = createCalendar(2L, 1L, "https://server/calendars/user/home/")
        stubRefreshAccount(listOf(listedCal, unlisted))
        coEvery { mockClient.listCalendars(any()) } returns CalDavResult.Success(listOf(listed(listedCal)))
        coEvery { mockClient.probeCalendarCollection(any()) } returns CalDavResult.success(true)

        discoveryService.refreshCalendars(1L)

        coVerify(exactly = 1) { mockClient.probeCalendarCollection(unlisted.caldavUrl) }
        coVerify(exactly = 0) { mockClient.probeCalendarCollection(listedCal.caldavUrl) }
    }

    @Test
    fun `refreshCalendars reports listed, new and kept calendars together`() = runTest {
        val listedCal = createCalendar(1L, 1L, "https://server/calendars/user/work/")
        val kept = createCalendar(2L, 1L, "https://server/calendars/user/home/")
        stubRefreshAccount(listOf(listedCal, kept))
        val brandNew = CalDavCalendar(
            href = "/calendars/user/new/",
            url = "https://server/calendars/user/new/",
            displayName = "New",
            color = null,
            ctag = "ctag",
            isReadOnly = false
        )
        coEvery { mockClient.listCalendars(any()) } returns
            CalDavResult.Success(listOf(listed(listedCal), brandNew))
        coEvery { mockClient.probeCalendarCollection(kept.caldavUrl) } returns
            CalDavResult.error(500, "resourcetype not found in response")

        val result = discoveryService.refreshCalendars(1L) as DiscoveryResult.Success

        // 2 existing + 1 new: the calendar count and the "new calendars" count both stay right.
        assertEquals(3, result.calendars.size)
        assertEquals(setOf(1L, 2L, 99L), result.calendars.map { it.id }.toSet())
    }

    @Test
    fun `refreshCalendars stops probing after a timeout and keeps the rest`() = runTest {
        val a = createCalendar(1L, 1L, "https://server/calendars/user/a/")
        val b = createCalendar(2L, 1L, "https://server/calendars/user/b/")
        val c = createCalendar(3L, 1L, "https://server/calendars/user/c/")
        stubRefreshAccount(listOf(a, b, c))
        coEvery { mockClient.listCalendars(any()) } returns CalDavResult.Success(emptyList())
        coEvery { mockClient.probeCalendarCollection(any()) } returns CalDavResult.timeoutError("Request timed out")

        discoveryService.refreshCalendars(1L)

        coVerify(exactly = 1) { mockClient.probeCalendarCollection(any()) }
        coVerify(exactly = 0) { calendarRepository.deleteCalendar(any()) }
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

        // AccountRepository.deleteAccount does all the cleanup, for example cancelling sync work
        // and reminders and deleting pending operations and credentials.
        coVerify { accountRepository.deleteAccount(1L) }
    }

    @Test
    fun `removeAccountByEmail finds and deletes account`() = runTest {
        val account = createAccount(1L)
        coEvery { accountRepository.getAccountByProviderAndEmail(AccountProvider.CALDAV, "user@example.com") } returns account

        discoveryService.removeAccountByEmail("user@example.com")

        coVerify { accountRepository.deleteAccount(1L) }
    }

    @Test
    fun `removeAccountByEmail does not log the full email address`() = runTest {
        val email = "user@example.com"
        val account = createAccount(1L)
        coEvery { accountRepository.getAccountByProviderAndEmail(AccountProvider.CALDAV, email) } returns account
        val logMessages = mutableListOf<String>()
        every { Log.i(any(), capture(logMessages)) } returns 0

        discoveryService.removeAccountByEmail(email)

        assertTrue(
            "Expected the removal log line to be emitted; captured: $logMessages",
            logMessages.any { it.contains("Removing CalDAV account") }
        )
        assertTrue(
            "No log line should contain the unmasked email; captured: $logMessages",
            logMessages.none { it.contains(email) }
        )
        assertTrue(
            "Removal log should contain the masked email; captured: $logMessages",
            logMessages.any { it.contains("use***@***.com") }
        )
    }

    // ==================== Account Collision Tests (Issue #69) ====================

    @Test
    fun `discoverAndCreateAccount creates separate account when same username on different server`() = runTest {
        setupSuccessfulDiscovery("https://server-b.example.com")

        // The lookup keyed on provider, username and home set finds no account on this server.
        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(any(), any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 2L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://server-b.example.com",
            username = "admin",
            password = "pass"
        )

        assertTrue("Expected Success, got $result", result is DiscoveryResult.Success)
        // A separate account is created; the other server's account isn't touched.
        coVerify { accountRepository.createAccount(any()) }
        coVerify(exactly = 0) { accountRepository.updateAccount(any()) }
    }

    @Test
    fun `createAccountWithSelectedCalendars creates separate account when same username on different server`() = runTest {
        // The lookup keyed on provider, username and home set finds no account on this server.
        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(any(), any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 2L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.createAccountWithSelectedCalendars(
            serverUrl = "https://server-b.example.com",
            username = "admin",
            password = "pass",
            trustInsecure = false,
            principalUrl = "https://server-b.example.com/dav/principals/admin/",
            calendarHomeUrl = "https://server-b.example.com/dav/calendars/admin/",
            selectedCalendars = listOf(
                org.onekash.kashcal.sync.discovery.DiscoveredCalendar(
                    href = "https://server-b.example.com/dav/calendars/admin/personal/",
                    displayName = "Personal",
                    color = 0xFF0000
                )
            )
        )

        assertTrue("Expected Success, got $result", result is DiscoveryResult.Success)
        // A separate account is created; the other server's account isn't touched.
        coVerify { accountRepository.createAccount(any()) }
        coVerify(exactly = 0) { accountRepository.updateAccount(any()) }
    }

    @Test
    fun `discoverAndCreateAccount updates existing account when re-adding same server`() = runTest {
        setupSuccessfulDiscovery("https://server-a.example.com")

        val existingAccount = Account(
            id = 1L,
            provider = AccountProvider.CALDAV,
            email = "user",
            displayName = "server-a.example.com",
            principalUrl = "https://server-a.example.com/dav/principals/user/",
            homeSetUrl = "https://server-a.example.com/dav/calendars/user/",
            isEnabled = true
        )
        // Same server: the lookup finds the existing account.
        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(any(), any(), any()) } returns existingAccount
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://server-a.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue("Expected Success, got $result", result is DiscoveryResult.Success)
        assertEquals(1L, (result as DiscoveryResult.Success).account.id)
        // Signing in again updates the account instead of adding a second one.
        coVerify { accountRepository.updateAccount(any()) }
        coVerify(exactly = 0) { accountRepository.createAccount(any()) }
    }

    @Test
    fun `discoverAndCreateAccount matches existing account despite trailing slash variation`() = runTest {
        // The server returns the home set without a trailing slash.
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success(
            "https://server.example.com/dav/principals/admin/"
        )
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            listOf("https://server.example.com/dav/calendars/admin")
        )
        coEvery { mockClient.listCalendars(any()) } returns CalDavResult.Success(
            listOf(
                CalDavCalendar(
                    href = "/dav/calendars/admin/personal/",
                    url = "https://server.example.com/dav/calendars/admin/personal/",
                    displayName = "Personal",
                    color = "#FF0000",
                    ctag = "ctag1",
                    isReadOnly = false
                )
            )
        )

        val existingAccount = Account(
            id = 1L,
            provider = AccountProvider.CALDAV,
            email = "admin",
            displayName = "server.example.com",
            principalUrl = "https://server.example.com/dav/principals/admin/",
            homeSetUrl = "https://server.example.com/dav/calendars/admin/",  // stored normalized
            isEnabled = true
        )
        // Normalization adds the trailing slash, so the lookup matches the stored account.
        coEvery {
            accountRepository.getAccountByProviderEmailAndHomeSetUrl(
                AccountProvider.CALDAV, "admin", "https://server.example.com/dav/calendars/admin/"
            )
        } returns existingAccount
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://server.example.com",
            username = "admin",
            password = "pass"
        )

        assertTrue("Expected Success, got $result", result is DiscoveryResult.Success)
        coVerify { accountRepository.updateAccount(any()) }
        coVerify(exactly = 0) { accountRepository.createAccount(any()) }
    }

    // ==================== URL Normalization Tests (Issue #69) ====================

    @Test
    fun `normalizeHomeSetUrl adds trailing slash`() {
        val result = discoveryService.normalizeHomeSetUrl("https://server.com/dav/calendars/admin")
        assertEquals("https://server.com/dav/calendars/admin/", result)
    }

    @Test
    fun `normalizeHomeSetUrl strips default https port`() {
        val result = discoveryService.normalizeHomeSetUrl("https://server.com:443/dav/calendars/admin/")
        assertEquals("https://server.com/dav/calendars/admin/", result)
    }

    @Test
    fun `normalizeHomeSetUrl strips default http port`() {
        val result = discoveryService.normalizeHomeSetUrl("http://server.com:80/dav/calendars/admin/")
        assertEquals("http://server.com/dav/calendars/admin/", result)
    }

    @Test
    fun `normalizeHomeSetUrl lowercases host but preserves path case`() {
        val result = discoveryService.normalizeHomeSetUrl("https://Server.Example.COM/dav/Calendars/Admin/")
        assertEquals("https://server.example.com/dav/Calendars/Admin/", result)
    }

    @Test
    fun `normalizeHomeSetUrl preserves non-default port`() {
        val result = discoveryService.normalizeHomeSetUrl("https://server.com:8443/dav/calendars/admin/")
        assertEquals("https://server.com:8443/dav/calendars/admin/", result)
    }

    // ==================== Server Display Name Tests ====================

    @Test
    fun `discoverAndCreateAccount extracts Fastmail display name`() = runTest {
        setupSuccessfulDiscovery(serverUrl = "https://caldav.fastmail.com")

        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(any(), any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        discoveryService.discoverAndCreateAccount(
            serverUrl = "https://caldav.fastmail.com",
            username = "user",
            password = "pass"
        )

        coVerify {
            accountRepository.createAccount(match<Account> { it.displayName == "Fastmail" })
        }
    }

    @Test
    fun `discoverAndCreateAccount uses hostname for unknown servers`() = runTest {
        setupSuccessfulDiscovery(serverUrl = "https://caldav.myserver.org")

        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(any(), any(), any()) } returns null
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
            listOf("https://server/calendars/")
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

        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(any(), any(), any()) } returns null
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
            listOf("https://server/calendars/")
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

        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(any(), any(), any()) } returns null
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
        // #RRGGBBAA is reordered to AARRGGBB.
        assertEquals(0xCCFF5733.toInt(), calendar.color)
    }

    // ==================== Multiple Calendar Home Set Tests (Issue #70) ====================

    @Test
    fun `discoverAndCreateAccount merges calendars from multiple home sets`() = runTest {
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success(
            "https://server.example.com/dav/principals/user/"
        )
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            listOf(
                "https://server.example.com/dav/calendars/user/aaa/",
                "https://server.example.com/dav/calendars/user/bbb/"
            )
        )
        coEvery { mockClient.listCalendars("https://server.example.com/dav/calendars/user/aaa/") } returns CalDavResult.Success(
            listOf(
                CalDavCalendar("/dav/calendars/user/aaa/personal/", "https://server.example.com/dav/calendars/user/aaa/personal/", "Personal", "#FF0000", "ctag1", false),
                CalDavCalendar("/dav/calendars/user/aaa/work/", "https://server.example.com/dav/calendars/user/aaa/work/", "Work", "#00FF00", "ctag2", false)
            )
        )
        coEvery { mockClient.listCalendars("https://server.example.com/dav/calendars/user/bbb/") } returns CalDavResult.Success(
            listOf(
                CalDavCalendar("/dav/calendars/user/bbb/shared/", "https://server.example.com/dav/calendars/user/bbb/shared/", "Shared", "#0000FF", "ctag3", true)
            )
        )

        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(any(), any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://server.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue("Expected Success, got $result", result is DiscoveryResult.Success)
        val success = result as DiscoveryResult.Success
        assertEquals(3, success.calendars.size)
        coVerify(exactly = 1) { mockClient.listCalendars("https://server.example.com/dav/calendars/user/aaa/") }
        coVerify(exactly = 1) { mockClient.listCalendars("https://server.example.com/dav/calendars/user/bbb/") }
        // The account keeps the first home set in sorted order.
        assertEquals("https://server.example.com/dav/calendars/user/aaa/", success.account.homeSetUrl)
    }

    @Test
    fun `discoverAndCreateAccount continues when one home set fails`() = runTest {
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success(
            "https://server.example.com/dav/principals/user/"
        )
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            listOf(
                "https://server.example.com/dav/calendars/user/aaa/",
                "https://server.example.com/dav/calendars/user/bbb/"
            )
        )
        coEvery { mockClient.listCalendars("https://server.example.com/dav/calendars/user/aaa/") } returns CalDavResult.Success(
            listOf(
                CalDavCalendar("/dav/calendars/user/aaa/personal/", "https://server.example.com/dav/calendars/user/aaa/personal/", "Personal", "#FF0000", "ctag1", false)
            )
        )
        coEvery { mockClient.listCalendars("https://server.example.com/dav/calendars/user/bbb/") } returns CalDavResult.Error(500, "Internal error")

        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(any(), any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://server.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue("Expected Success, got $result", result is DiscoveryResult.Success)
        val success = result as DiscoveryResult.Success
        assertEquals(1, success.calendars.size)
        assertEquals("Personal", success.calendars.first().displayName)
    }

    @Test
    fun `discoverCalendars merges calendars from multiple home sets`() = runTest {
        coEvery { mockClient.discoverWellKnown(any()) } returns CalDavResult.Error(404, "Not found")
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success(
            "https://server.example.com/dav/principals/user/"
        )
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            listOf(
                "https://server.example.com/dav/calendars/user/aaa/",
                "https://server.example.com/dav/calendars/user/bbb/"
            )
        )
        coEvery { mockClient.listCalendars("https://server.example.com/dav/calendars/user/aaa/") } returns CalDavResult.Success(
            listOf(
                CalDavCalendar("/dav/calendars/user/aaa/personal/", "https://server.example.com/dav/calendars/user/aaa/personal/", "Personal", "#FF0000", "ctag1", false)
            )
        )
        coEvery { mockClient.listCalendars("https://server.example.com/dav/calendars/user/bbb/") } returns CalDavResult.Success(
            listOf(
                CalDavCalendar("/dav/calendars/user/bbb/shared/", "https://server.example.com/dav/calendars/user/bbb/shared/", "Shared", "#0000FF", "ctag2", true)
            )
        )

        val result = discoveryService.discoverCalendars(
            serverUrl = "https://server.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue("Expected CalendarsFound, got $result", result is DiscoveryResult.CalendarsFound)
        val found = result as DiscoveryResult.CalendarsFound
        assertEquals(2, found.calendars.size)
        assertTrue(found.calendars.any { it.displayName == "Personal" })
        assertTrue(found.calendars.any { it.displayName == "Shared" })
    }

    @Test
    fun `discoverCalendars stores first sorted home URL in CalendarsFound`() = runTest {
        coEvery { mockClient.discoverWellKnown(any()) } returns CalDavResult.Error(404, "Not found")
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success(
            "https://server.example.com/dav/principals/user/"
        )
        // Returned in reverse order, so the result must be sorted.
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            listOf(
                "https://server.example.com/dav/calendars/user/zzz/",
                "https://server.example.com/dav/calendars/user/aaa/"
            )
        )
        coEvery { mockClient.listCalendars(any()) } returns CalDavResult.Success(
            listOf(
                CalDavCalendar("/cal/", "https://server.example.com/cal/", "Test", "#FF0000", "ctag1", false)
            )
        )

        val result = discoveryService.discoverCalendars(
            serverUrl = "https://server.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue("Expected CalendarsFound, got $result", result is DiscoveryResult.CalendarsFound)
        val found = result as DiscoveryResult.CalendarsFound
        assertEquals("https://server.example.com/dav/calendars/user/aaa/", found.calendarHomeUrl)
    }

    @Test
    fun `discoverCalendars continues when one home set fails`() = runTest {
        coEvery { mockClient.discoverWellKnown(any()) } returns CalDavResult.Error(404, "Not found")
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success(
            "https://server.example.com/dav/principals/user/"
        )
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            listOf(
                "https://server.example.com/dav/calendars/user/aaa/",
                "https://server.example.com/dav/calendars/user/bbb/"
            )
        )
        coEvery { mockClient.listCalendars("https://server.example.com/dav/calendars/user/aaa/") } returns CalDavResult.Error(403, "Forbidden")
        coEvery { mockClient.listCalendars("https://server.example.com/dav/calendars/user/bbb/") } returns CalDavResult.Success(
            listOf(
                CalDavCalendar("/dav/calendars/user/bbb/shared/", "https://server.example.com/dav/calendars/user/bbb/shared/", "Shared", "#0000FF", "ctag1", true)
            )
        )

        val result = discoveryService.discoverCalendars(
            serverUrl = "https://server.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue("Expected CalendarsFound, got $result", result is DiscoveryResult.CalendarsFound)
        val found = result as DiscoveryResult.CalendarsFound
        assertEquals(1, found.calendars.size)
        assertEquals("Shared", found.calendars.first().displayName)
    }

    @Test
    fun `refreshCalendars re-discovers home sets from principal`() = runTest {
        val account = createAccount(1L, "https://server/calendars/user/old/")

        coEvery { accountRepository.getAccountById(1L) } returns account
        coEvery { accountRepository.getCredentials(1L) } returns AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server",
            principalUrl = "https://server/principal/user/"
        )
        coEvery { calendarRepository.getCalendarsForAccountOnce(1L) } returns emptyList()
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L
        coEvery { mockClient.discoverCalendarHome("https://server/principal/user/") } returns CalDavResult.Success(
            listOf(
                "https://server/calendars/user/aaa/",
                "https://server/calendars/user/bbb/"
            )
        )
        coEvery { mockClient.listCalendars("https://server/calendars/user/aaa/") } returns CalDavResult.Success(
            listOf(CalDavCalendar("/cal/aaa/personal/", "https://server/cal/aaa/personal/", "Personal", "#FF0000", "ctag1", false))
        )
        coEvery { mockClient.listCalendars("https://server/calendars/user/bbb/") } returns CalDavResult.Success(
            listOf(CalDavCalendar("/cal/bbb/shared/", "https://server/cal/bbb/shared/", "Shared", "#0000FF", "ctag2", true))
        )

        val result = discoveryService.refreshCalendars(1L)

        assertTrue("Expected Success, got $result", result is DiscoveryResult.Success)
        coVerify(exactly = 2) { calendarRepository.createCalendar(any()) }
        // Home sets come from the principal, not the stored homeSetUrl.
        coVerify { mockClient.discoverCalendarHome("https://server/principal/user/") }
    }

    @Test
    fun `refreshCalendars falls back to stored URL when principal unavailable`() = runTest {
        val account = createAccount(1L, "https://server/calendars/user/")

        coEvery { accountRepository.getAccountById(1L) } returns account
        coEvery { accountRepository.getCredentials(1L) } returns AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server",
            principalUrl = "https://server/principal/user/"
        )
        coEvery { calendarRepository.getCalendarsForAccountOnce(1L) } returns emptyList()
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L
        coEvery { mockClient.discoverCalendarHome("https://server/principal/user/") } returns CalDavResult.Error(500, "Server error")
        // Re-discovery fails, so the stored homeSetUrl is listed.
        coEvery { mockClient.listCalendars("https://server/calendars/user/") } returns CalDavResult.Success(
            listOf(CalDavCalendar("/cal/personal/", "https://server/cal/personal/", "Personal", "#FF0000", "ctag1", false))
        )

        val result = discoveryService.refreshCalendars(1L)

        assertTrue("Expected Success, got $result", result is DiscoveryResult.Success)
        coVerify(exactly = 1) { calendarRepository.createCalendar(any()) }
    }

    // ==================== Helper Methods ====================

    private fun setupSuccessfulDiscovery(serverUrl: String = "https://nextcloud.example.com") {
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Success(
            "$serverUrl/dav/principals/user/"
        )
        coEvery { mockClient.discoverCalendarHome(any()) } returns CalDavResult.Success(
            listOf("$serverUrl/dav/calendars/user/")
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
        // Scheduling-delivery discovery (RFC 6638 §2, §2.1.1). CalDavResult is a sealed
        // type, so the relaxed mock can't synthesize a usable default; stub explicitly.
        coEvery { mockClient.discoverScheduleOutboxUrl(any()) } returns
            CalDavResult.Success("$serverUrl/dav/calendars/user/outbox/")
        coEvery { mockClient.supportsAutoSchedule(any()) } returns CalDavResult.Success(true)
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

    // ==================== Path Probing Tests (Issue #54) ====================

    @Test
    fun `discoverAndCreateAccount probes paths when root returns 404`() = runTest {
        coEvery { mockClient.discoverPrincipal("https://davis.example.com") } returns
            CalDavResult.Error(404, "Not found")
        coEvery { mockClient.discoverPrincipal("https://davis.example.com/dav/") } returns
            CalDavResult.Success("https://davis.example.com/dav/principals/user/")
        coEvery { mockClient.discoverCalendarHome(any()) } returns
            CalDavResult.Success(listOf("https://davis.example.com/dav/calendars/user/"))
        coEvery { mockClient.listCalendars(any()) } returns
            CalDavResult.Success(listOf(
                CalDavCalendar("/dav/calendars/user/default/", "https://davis.example.com/dav/calendars/user/default/", "Default", "#0000FF", "ctag1", false)
            ))
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://davis.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue("Expected Success but got $result", result is DiscoveryResult.Success)
    }

    @Test
    fun `discoverAndCreateAccount probes paths when root returns HTML`() = runTest {
        coEvery { mockClient.discoverPrincipal("https://davis.example.com") } returns
            CalDavResult.Error(500, "Principal URL not found in response")
        coEvery { mockClient.discoverPrincipal("https://davis.example.com/dav/") } returns
            CalDavResult.Success("https://davis.example.com/dav/principals/user/")
        coEvery { mockClient.discoverCalendarHome(any()) } returns
            CalDavResult.Success(listOf("https://davis.example.com/dav/calendars/user/"))
        coEvery { mockClient.listCalendars(any()) } returns
            CalDavResult.Success(listOf(
                CalDavCalendar("/dav/calendars/user/default/", "https://davis.example.com/dav/calendars/user/default/", "Default", "#0000FF", "ctag1", false)
            ))
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://davis.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue("Expected Success but got $result", result is DiscoveryResult.Success)
    }

    @Test
    fun `discoverAndCreateAccount finds Nextcloud on later probe`() = runTest {
        coEvery { mockClient.discoverPrincipal("https://nc.example.com") } returns
            CalDavResult.Error(404, "Not found")
        coEvery { mockClient.discoverPrincipal("https://nc.example.com/dav/") } returns
            CalDavResult.Error(404, "Not found")
        coEvery { mockClient.discoverPrincipal("https://nc.example.com/remote.php/dav/") } returns
            CalDavResult.Success("https://nc.example.com/remote.php/dav/principals/users/admin/")
        coEvery { mockClient.discoverCalendarHome(any()) } returns
            CalDavResult.Success(listOf("https://nc.example.com/remote.php/dav/calendars/admin/"))
        coEvery { mockClient.listCalendars(any()) } returns
            CalDavResult.Success(listOf(
                CalDavCalendar("/remote.php/dav/calendars/admin/personal/", "https://nc.example.com/remote.php/dav/calendars/admin/personal/", "Personal", "#0082C9", "ctag1", false)
            ))
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://nc.example.com",
            username = "admin",
            password = "pass"
        )

        assertTrue("Expected Success but got $result", result is DiscoveryResult.Success)
    }

    @Test
    fun `discoverAndCreateAccount returns error when all probes fail`() = runTest {
        coEvery { mockClient.discoverPrincipal(any()) } returns
            CalDavResult.Error(404, "Not found")

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://unknown.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.Error)
        val error = result as DiscoveryResult.Error
        assertTrue(
            "Error message should mention tried paths but got: ${error.message}",
            error.message.contains("Tried common server paths")
        )
    }

    @Test
    fun `discoverAndCreateAccount does not probe when URL has known path`() = runTest {
        coEvery { mockClient.discoverPrincipal("https://baikal.example.com/dav.php/") } returns
            CalDavResult.Error(500, "Internal server error")

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://baikal.example.com/dav.php/",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.Error)
        assertFalse(
            "Should not probe when URL already has known path, but got: ${(result as DiscoveryResult.Error).message}",
            result.message.contains("Tried common server paths")
        )
        coVerify(exactly = 1) { mockClient.discoverPrincipal(any()) }
    }

    @Test
    fun `discoverAndCreateAccount does not probe when URL has known path without trailing slash`() = runTest {
        coEvery { mockClient.discoverPrincipal("https://davis.example.com/dav") } returns
            CalDavResult.Error(404, "Not found")

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://davis.example.com/dav",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.Error)
        assertFalse(
            "Should not probe when URL has known path (without trailing slash), but got: ${(result as DiscoveryResult.Error).message}",
            result.message.contains("Tried common server paths")
        )
        coVerify(exactly = 1) { mockClient.discoverPrincipal(any()) }
    }

    @Test
    fun `discoverAndCreateAccount stops probing on auth error`() = runTest {
        coEvery { mockClient.discoverPrincipal("https://server.example.com") } returns
            CalDavResult.Error(404, "Not found")
        coEvery { mockClient.discoverPrincipal("https://server.example.com/dav/") } returns
            CalDavResult.authError("Authentication failed")

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://server.example.com",
            username = "user",
            password = "wrong"
        )

        assertTrue(result is DiscoveryResult.Error)
        coVerify(exactly = 2) { mockClient.discoverPrincipal(any()) }
    }

    @Test
    fun `discoverAndCreateAccount continues probing past 500 error`() = runTest {
        coEvery { mockClient.discoverPrincipal("https://server.example.com") } returns
            CalDavResult.Error(500, "Internal server error")
        coEvery { mockClient.discoverPrincipal("https://server.example.com/dav/") } returns
            CalDavResult.Error(500, "Internal server error")
        coEvery { mockClient.discoverPrincipal("https://server.example.com/remote.php/dav/") } returns
            CalDavResult.Success("https://server.example.com/remote.php/dav/principals/users/admin/")
        coEvery { mockClient.discoverCalendarHome(any()) } returns
            CalDavResult.Success(listOf("https://server.example.com/remote.php/dav/calendars/admin/"))
        coEvery { mockClient.listCalendars(any()) } returns
            CalDavResult.Success(listOf(
                CalDavCalendar("/remote.php/dav/calendars/admin/personal/", "https://server.example.com/remote.php/dav/calendars/admin/personal/", "Personal", "#0082C9", "ctag1", false)
            ))
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://server.example.com",
            username = "admin",
            password = "pass"
        )

        assertTrue("Expected Success but got $result", result is DiscoveryResult.Success)
        coVerify { mockClient.discoverPrincipal("https://server.example.com/dav/") }
        coVerify { mockClient.discoverPrincipal("https://server.example.com/remote.php/dav/") }
    }

    @Test
    fun `discoverAndCreateAccount probes correctly with port number`() = runTest {
        coEvery { mockClient.discoverPrincipal("https://localhost:8080") } returns
            CalDavResult.Error(404, "Not found")
        coEvery { mockClient.discoverPrincipal("https://localhost:8080/dav/") } returns
            CalDavResult.Success("https://localhost:8080/dav/principals/user/")
        coEvery { mockClient.discoverCalendarHome(any()) } returns
            CalDavResult.Success(listOf("https://localhost:8080/dav/calendars/user/"))
        coEvery { mockClient.listCalendars(any()) } returns
            CalDavResult.Success(listOf(
                CalDavCalendar("/dav/calendars/user/default/", "https://localhost:8080/dav/calendars/user/default/", "Default", "#0000FF", "ctag1", false)
            ))
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://localhost:8080",
            username = "user",
            password = "pass"
        )

        assertTrue("Expected Success but got $result", result is DiscoveryResult.Success)
        coVerify { mockClient.discoverPrincipal("https://localhost:8080/dav/") }
    }

    @Test
    fun `discoverAndCreateAccount does not probe on auth error from root`() = runTest {
        coEvery { mockClient.discoverPrincipal("https://server.example.com") } returns
            CalDavResult.authError("Authentication failed")

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://server.example.com",
            username = "user",
            password = "wrong"
        )

        assertTrue(result is DiscoveryResult.AuthError)
        coVerify(exactly = 1) { mockClient.discoverPrincipal(any()) }
    }

    @Test
    fun `discoverAndCreateAccount does not probe on SSL error from root`() = runTest {
        coEvery { mockClient.discoverPrincipal("https://self-signed.local") } returns
            CalDavResult.Error(0, "SSL certificate verification failed")

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://self-signed.local",
            username = "user",
            password = "pass"
        )

        assertTrue(result is DiscoveryResult.Error)
        assertTrue((result as DiscoveryResult.Error).message.contains("Trust insecure"))
        coVerify(exactly = 1) { mockClient.discoverPrincipal(any()) }
    }

    // ==================== Zoho Path Probing Tests (Issue #61) ====================

    @Test
    fun `discoverAndCreateAccount probes caldav without trailing slash before with slash`() = runTest {
        // Zoho returns 501 for /caldav/ but works with /caldav (no trailing slash).
        // KNOWN_CALDAV_PATHS has /caldav before /caldav/ so Zoho is found first.
        coEvery { mockClient.discoverPrincipal("https://calendar.zoho.com") } returns
            CalDavResult.Error(404, "Not found")
        coEvery { mockClient.discoverPrincipal("https://calendar.zoho.com/dav/") } returns
            CalDavResult.Error(404, "Not found")
        coEvery { mockClient.discoverPrincipal("https://calendar.zoho.com/remote.php/dav/") } returns
            CalDavResult.Error(404, "Not found")
        coEvery { mockClient.discoverPrincipal("https://calendar.zoho.com/dav.php/") } returns
            CalDavResult.Error(404, "Not found")
        coEvery { mockClient.discoverPrincipal("https://calendar.zoho.com/caldav") } returns
            CalDavResult.Success("https://calendar.zoho.com/caldav/user@example.com/")
        coEvery { mockClient.discoverCalendarHome(any()) } returns
            CalDavResult.Success(listOf("https://calendar.zoho.com/caldav/user@example.com/"))
        coEvery { mockClient.listCalendars(any()) } returns
            CalDavResult.Success(listOf(
                CalDavCalendar("/caldav/user@example.com/default/", "https://calendar.zoho.com/caldav/user@example.com/default/", "My Calendar", null, null, false)
            ))
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://calendar.zoho.com",
            username = "user@example.com",
            password = "pass"
        )

        assertTrue("Expected Success but got $result", result is DiscoveryResult.Success)
        // /caldav/ is never tried.
        coVerify(exactly = 0) { mockClient.discoverPrincipal("https://calendar.zoho.com/caldav/") }
    }

    // ==================== Credential Save Failure Tests (Issue #55) ====================

    @Test
    fun `discoverAndCreateAccount returns error when credentials fail to save`() = runTest {
        setupSuccessfulDiscovery()

        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(AccountProvider.CALDAV, "user", any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        // Secure storage fails, e.g. a broken Android Keystore.
        coEvery { accountRepository.saveCredentials(any(), any()) } returns false

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass"
        )

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
        setupSuccessfulDiscovery()

        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(AccountProvider.CALDAV, "user", any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L
        coEvery { accountRepository.saveCredentials(any(), any()) } returns false

        discoveryService.discoverAndCreateAccount(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass"
        )

        // An account without credentials can't sync, so it is deleted.
        coVerify { accountRepository.deleteAccount(1L) }
    }

    @Test
    fun `createAccountWithSelectedCalendars returns error when credentials fail to save`() = runTest {
        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(AccountProvider.CALDAV, "user", any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L
        coEvery { accountRepository.saveCredentials(any(), any()) } returns false

        val result = discoveryService.createAccountWithSelectedCalendars(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass",
            trustInsecure = false,
            principalUrl = "https://nextcloud.example.com/dav/principals/user/",
            calendarHomeUrl = "https://nextcloud.example.com/dav/calendars/user/",
            selectedCalendars = listOf(
                org.onekash.kashcal.sync.discovery.DiscoveredCalendar(
                    href = "/dav/calendars/user/personal/",
                    displayName = "Personal",
                    color = 0xFF0000
                )
            )
        )

        assertTrue(
            "Expected Error when credentials fail to save, but got $result",
            result is DiscoveryResult.Error
        )
    }

    @Test
    fun `createAccountWithSelectedCalendars cleans up account when credentials fail to save`() = runTest {
        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(AccountProvider.CALDAV, "user", any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L
        coEvery { accountRepository.saveCredentials(any(), any()) } returns false

        discoveryService.createAccountWithSelectedCalendars(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass",
            trustInsecure = false,
            principalUrl = "https://nextcloud.example.com/dav/principals/user/",
            calendarHomeUrl = "https://nextcloud.example.com/dav/calendars/user/",
            selectedCalendars = listOf(
                org.onekash.kashcal.sync.discovery.DiscoveredCalendar(
                    href = "/dav/calendars/user/personal/",
                    displayName = "Personal",
                    color = 0xFF0000
                )
            )
        )

        coVerify { accountRepository.deleteAccount(1L) }
    }

    // ==================== SSL Error Message Tests (Issue #56) ====================

    @Test
    fun `discoverAndCreateAccount shows different SSL error when trustInsecure already enabled`() = runTest {
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Error(
            0, "SSL certificate problem"
        )

        val result = discoveryService.discoverAndCreateAccount(
            serverUrl = "https://self-signed.local",
            username = "user",
            password = "pass",
            trustInsecure = true
        )

        assertTrue(result is DiscoveryResult.Error)
        val error = result as DiscoveryResult.Error
        assertFalse("Should not tell user to enable toggle that's already on", error.message.contains("Enable"))
        assertTrue("Should acknowledge toggle is enabled", error.message.contains("even with"))
    }

    @Test
    fun `discoverCalendars shows different SSL error when trustInsecure already enabled`() = runTest {
        coEvery { mockClient.discoverWellKnown(any()) } returns CalDavResult.Error(0, "SSL error")
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Error(
            0, "SSL certificate problem"
        )

        val result = discoveryService.discoverCalendars(
            serverUrl = "https://self-signed.local",
            username = "user",
            password = "pass",
            trustInsecure = true
        )

        assertTrue(result is DiscoveryResult.Error)
        val error = result as DiscoveryResult.Error
        assertFalse("Should not tell user to enable toggle that's already on", error.message.contains("Enable"))
        assertTrue("Should acknowledge toggle is enabled", error.message.contains("even with"))
    }

    @Test
    fun `discoverCalendars shows SSL hint when trustInsecure disabled`() = runTest {
        coEvery { mockClient.discoverWellKnown(any()) } returns CalDavResult.Error(0, "SSL error")
        coEvery { mockClient.discoverPrincipal(any()) } returns CalDavResult.Error(
            0, "SSL certificate problem"
        )

        val result = discoveryService.discoverCalendars(
            serverUrl = "https://self-signed.local",
            username = "user",
            password = "pass",
            trustInsecure = false
        )

        assertTrue(result is DiscoveryResult.Error)
        val error = result as DiscoveryResult.Error
        assertTrue("Should hint to enable toggle", error.message.contains("Trust insecure"))
    }

    @Test
    fun `discoverCalendars preserves explicit http scheme`() = runTest {
        setupSuccessfulDiscovery(serverUrl = "http://192.168.1.100:8080")

        coEvery { accountRepository.getAccountByProviderEmailAndHomeSetUrl(any(), any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        discoveryService.discoverCalendars(
            serverUrl = "http://192.168.1.100:8080",
            username = "user",
            password = "pass"
        )

        // An explicit http:// is kept, not upgraded to https://.
        verify {
            calDavClientFactory.createClient(
                match<Credentials> { it.serverUrl == "http://192.168.1.100:8080" },
                any()
            )
        }
    }

    // ==================== normalizeServerUrl Tests (Issue #54) ====================

    @Test
    fun `normalizeServerUrl preserves trailing slash on path`() = runTest {
        coEvery { mockClient.discoverPrincipal("https://example.com/dav/") } returns
            CalDavResult.Success("https://example.com/dav/principals/user/")
        coEvery { mockClient.discoverCalendarHome(any()) } returns
            CalDavResult.Success(listOf("https://example.com/dav/calendars/user/"))
        coEvery { mockClient.listCalendars(any()) } returns
            CalDavResult.Success(listOf(
                CalDavCalendar("/dav/calendars/user/default/", "https://example.com/dav/calendars/user/default/", "Default", "#0000FF", "ctag1", false)
            ))
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        discoveryService.discoverAndCreateAccount(
            serverUrl = "https://example.com/dav/",
            username = "user",
            password = "pass"
        )

        coVerify { mockClient.discoverPrincipal("https://example.com/dav/") }
    }

    @Test
    fun `normalizeServerUrl trims trailing slash on root`() = runTest {
        coEvery { mockClient.discoverPrincipal("https://example.com") } returns
            CalDavResult.Success("https://example.com/dav/principals/user/")
        coEvery { mockClient.discoverCalendarHome(any()) } returns
            CalDavResult.Success(listOf("https://example.com/dav/calendars/user/"))
        coEvery { mockClient.listCalendars(any()) } returns
            CalDavResult.Success(listOf(
                CalDavCalendar("/dav/calendars/user/default/", "https://example.com/dav/calendars/user/default/", "Default", "#0000FF", "ctag1", false)
            ))
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        discoveryService.discoverAndCreateAccount(
            serverUrl = "https://example.com/",
            username = "user",
            password = "pass"
        )

        coVerify { mockClient.discoverPrincipal("https://example.com") }
    }
}
