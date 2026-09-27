package org.onekash.kashcal.sync.provider.caldav

import android.graphics.Color
import android.util.Log
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.CalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavCalendar
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.discovery.DiscoveryResult

/**
 * Tests [CalDavAccountDiscoveryService.discoverCalendars] with RFC 6764 well-known discovery,
 * a step discoverAndCreateAccount doesn't have. The app calls discoverCalendars when adding a
 * CalDAV account. Covers several providers, the fallback when well-known fails, and path
 * probing after a redirect.
 *
 * `CalDavAccountDiscoveryServiceTest` covers the rest of the service.
 */
class CalDavAccountDiscoveryServiceWellKnownTest {

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

        discoveryService = CalDavAccountDiscoveryService(
            calDavClientFactory,
            accountRepository,
            calendarRepository
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun parseHexColor(colorStr: String): Int {
        val clean = if (colorStr.startsWith("#")) colorStr.substring(1) else colorStr
        return when (clean.length) {
            6 -> (0xFF000000 or java.lang.Long.parseLong(clean, 16)).toInt()
            8 -> java.lang.Long.parseLong(clean, 16).toInt()
            else -> 0
        }
    }

    // ==================== Fastmail Tests (Issue #51) ====================

    @Test
    fun `discoverCalendars - Fastmail - well-known discovery succeeds`() = runTest {
        // The well-known result goes to principal discovery unchanged, so Fastmail's
        // /dav/principals/user/email/ path isn't cut to /dav (#51).

        val principalUrl = "https://caldav.fastmail.com/dav/principals/user/user@fastmail.com/"
        val calendarHomeUrl = "https://caldav.fastmail.com/dav/calendars/user/user@fastmail.com/"

        setupSuccessfulDiscovery(
            wellKnownUrl = principalUrl,
            principalUrl = principalUrl,
            calendarHomeUrl = calendarHomeUrl,
            calendars = listOf(
                CalDavCalendar(
                    href = "/dav/calendars/user/user@fastmail.com/Default/",
                    url = "https://caldav.fastmail.com/dav/calendars/user/user@fastmail.com/Default/",
                    displayName = "Default",
                    color = "#2952A3",
                    ctag = "ctag1",
                    isReadOnly = false
                )
            )
        )

        val result = discoveryService.discoverCalendars(
            serverUrl = "https://caldav.fastmail.com",
            username = "user@fastmail.com",
            password = "pass"
        )

        assertTrue("Expected CalendarsFound but got $result", result is DiscoveryResult.CalendarsFound)
        val found = result as DiscoveryResult.CalendarsFound
        assertEquals(1, found.calendars.size)
        assertEquals("Default", found.calendars[0].displayName)
    }

    @Test
    fun `discoverCalendars - Fastmail - all three user URL formats discover calendars`() = runTest {
        // Every common Fastmail URL form discovers calendars through the well-known
        // redirect (#51).

        val principalUrl = "https://caldav.fastmail.com/dav/principals/user/user@fastmail.com/"
        val calendarHomeUrl = "https://caldav.fastmail.com/dav/calendars/user/user@fastmail.com/"

        val urls = listOf(
            "caldav.fastmail.com",
            "fastmail.com",
            "www.fastmail.com"
        )

        for (url in urls) {
            clearMocks(mockClient, answers = true)

            setupSuccessfulDiscovery(
                wellKnownUrl = principalUrl,
                principalUrl = principalUrl,
                calendarHomeUrl = calendarHomeUrl,
                calendars = listOf(
                    CalDavCalendar(
                        href = "/dav/calendars/user/user@fastmail.com/Default/",
                        url = "https://caldav.fastmail.com/dav/calendars/user/user@fastmail.com/Default/",
                        displayName = "Default",
                        color = "#2952A3",
                        ctag = "ctag1",
                        isReadOnly = false
                    )
                )
            )

            val result = discoveryService.discoverCalendars(
                serverUrl = url,
                username = "user@fastmail.com",
                password = "pass"
            )

            assertTrue(
                "Expected CalendarsFound for URL '$url' but got $result",
                result is DiscoveryResult.CalendarsFound
            )
            val found = result as DiscoveryResult.CalendarsFound
            assertEquals("Expected 1 calendar for URL '$url'", 1, found.calendars.size)
            assertEquals("Default", found.calendars[0].displayName)
        }
    }

    // ==================== Provider Regression Tests ====================

    @Test
    fun `discoverCalendars - Nextcloud - well-known discovery succeeds`() = runTest {
        val principalUrl = "https://nc.example.com/remote.php/dav/principals/users/admin/"
        val calendarHomeUrl = "https://nc.example.com/remote.php/dav/calendars/admin/"

        setupSuccessfulDiscovery(
            wellKnownUrl = "https://nc.example.com/remote.php/dav",
            principalUrl = principalUrl,
            calendarHomeUrl = calendarHomeUrl,
            calendars = listOf(
                CalDavCalendar(
                    href = "/remote.php/dav/calendars/admin/personal/",
                    url = "https://nc.example.com/remote.php/dav/calendars/admin/personal/",
                    displayName = "Personal",
                    color = "#0082C9",
                    ctag = "ctag1",
                    isReadOnly = false
                )
            )
        )

        val result = discoveryService.discoverCalendars(
            serverUrl = "https://nc.example.com",
            username = "admin",
            password = "pass"
        )

        assertTrue("Expected CalendarsFound but got $result", result is DiscoveryResult.CalendarsFound)
        val found = result as DiscoveryResult.CalendarsFound
        assertEquals(1, found.calendars.size)
        assertEquals("Personal", found.calendars[0].displayName)
    }

    @Test
    fun `discoverCalendars - Baikal - well-known discovery succeeds`() = runTest {
        val principalUrl = "https://baikal.example.com/dav.php/principals/admin/"
        val calendarHomeUrl = "https://baikal.example.com/dav.php/calendars/admin/"

        setupSuccessfulDiscovery(
            wellKnownUrl = "https://baikal.example.com/dav.php",
            principalUrl = principalUrl,
            calendarHomeUrl = calendarHomeUrl,
            calendars = listOf(
                CalDavCalendar(
                    href = "/dav.php/calendars/admin/default/",
                    url = "https://baikal.example.com/dav.php/calendars/admin/default/",
                    displayName = "Default calendar",
                    color = "#00ACED",
                    ctag = "ctag1",
                    isReadOnly = false
                )
            )
        )

        val result = discoveryService.discoverCalendars(
            serverUrl = "https://baikal.example.com",
            username = "admin",
            password = "pass"
        )

        assertTrue("Expected CalendarsFound but got $result", result is DiscoveryResult.CalendarsFound)
        val found = result as DiscoveryResult.CalendarsFound
        assertEquals(1, found.calendars.size)
        assertEquals("Default calendar", found.calendars[0].displayName)
    }

    @Test
    fun `discoverCalendars - Stalwart - well-known discovery succeeds`() = runTest {
        val principalUrl = "http://localhost:8085/dav/cal/principals/user/"
        val calendarHomeUrl = "http://localhost:8085/dav/cal/calendars/user/"

        setupSuccessfulDiscovery(
            wellKnownUrl = "http://localhost:8085/dav/cal",
            principalUrl = principalUrl,
            calendarHomeUrl = calendarHomeUrl,
            calendars = listOf(
                CalDavCalendar(
                    href = "/dav/cal/calendars/user/default/",
                    url = "http://localhost:8085/dav/cal/calendars/user/default/",
                    displayName = "My Calendar",
                    color = "#3F51B5",
                    ctag = "ctag1",
                    isReadOnly = false
                )
            )
        )

        val result = discoveryService.discoverCalendars(
            serverUrl = "http://localhost:8085",
            username = "user",
            password = "pass"
        )

        assertTrue("Expected CalendarsFound but got $result", result is DiscoveryResult.CalendarsFound)
        val found = result as DiscoveryResult.CalendarsFound
        assertEquals(1, found.calendars.size)
        assertEquals("My Calendar", found.calendars[0].displayName)
    }

    @Test
    fun `discoverCalendars - mailbox_org - well-known discovery succeeds`() = runTest {
        val principalUrl = "https://dav.mailbox.org/caldav/principals/user@mailbox.org/"
        val calendarHomeUrl = "https://dav.mailbox.org/caldav/calendars/user@mailbox.org/"

        setupSuccessfulDiscovery(
            wellKnownUrl = "https://dav.mailbox.org/caldav",
            principalUrl = principalUrl,
            calendarHomeUrl = calendarHomeUrl,
            calendars = listOf(
                CalDavCalendar(
                    href = "/caldav/calendars/user@mailbox.org/Calendar/",
                    url = "https://dav.mailbox.org/caldav/calendars/user@mailbox.org/Calendar/",
                    displayName = "Calendar",
                    color = "#FF9800",
                    ctag = "ctag1",
                    isReadOnly = false
                )
            )
        )

        val result = discoveryService.discoverCalendars(
            serverUrl = "https://dav.mailbox.org",
            username = "user@mailbox.org",
            password = "pass"
        )

        assertTrue("Expected CalendarsFound but got $result", result is DiscoveryResult.CalendarsFound)
        val found = result as DiscoveryResult.CalendarsFound
        assertEquals(1, found.calendars.size)
        assertEquals("Calendar", found.calendars[0].displayName)
    }

    @Test
    fun `discoverCalendars - well-known not supported - falls back to direct principal`() = runTest {
        // When well-known returns an error, principal discovery uses the normalized URL.

        coEvery { mockClient.discoverWellKnown(any()) } returns
            CalDavResult.Error(404, "Not found")

        coEvery { mockClient.discoverPrincipal("https://radicale.example.com") } returns
            CalDavResult.Success("https://radicale.example.com/user/")

        coEvery { mockClient.discoverCalendarHome("https://radicale.example.com/user/") } returns
            CalDavResult.Success(listOf("https://radicale.example.com/user/"))

        coEvery { mockClient.listCalendars("https://radicale.example.com/user/") } returns
            CalDavResult.Success(
                listOf(
                    CalDavCalendar(
                        href = "/user/calendar.ics/",
                        url = "https://radicale.example.com/user/calendar.ics/",
                        displayName = "calendar",
                        color = "#795548",
                        ctag = "ctag1",
                        isReadOnly = false
                    )
                )
            )

        val result = discoveryService.discoverCalendars(
            serverUrl = "https://radicale.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue("Expected CalendarsFound but got $result", result is DiscoveryResult.CalendarsFound)
        val found = result as DiscoveryResult.CalendarsFound
        assertEquals(1, found.calendars.size)
        assertEquals("calendar", found.calendars[0].displayName)

        coVerify { mockClient.discoverWellKnown("https://radicale.example.com") }
        coVerify { mockClient.discoverPrincipal("https://radicale.example.com") }
    }

    @Test
    fun `discoverCalendars - well-known auth error still returns AuthError`() = runTest {
        // Even after well-known succeeds, a 401 from principal discovery is an AuthError.

        coEvery { mockClient.discoverWellKnown(any()) } returns
            CalDavResult.Success("https://server.com/dav")

        coEvery { mockClient.discoverPrincipal("https://server.com/dav") } returns
            CalDavResult.Error(401, "Unauthorized")

        val result = discoveryService.discoverCalendars(
            serverUrl = "https://server.com",
            username = "user",
            password = "wrongpass"
        )

        assertTrue("Expected AuthError but got $result", result is DiscoveryResult.AuthError)
        val authError = result as DiscoveryResult.AuthError
        assertTrue(
            "Expected 'Invalid username or password' but got: ${authError.message}",
            authError.message.contains("Invalid username or password")
        )
    }

    // ==================== Trailing Slash + Probing Tests (Issue #54) ====================

    @Test
    fun `discoverCalendars - well-known redirect preserves trailing slash`() = runTest {
        // Davis's well-known redirects to /dav/; the trailing slash must be kept.
        val principalUrl = "https://davis.example.com/dav/principals/user/"
        val calendarHomeUrl = "https://davis.example.com/dav/calendars/user/"

        coEvery { mockClient.discoverWellKnown(any()) } returns
            CalDavResult.Success("https://davis.example.com/dav/")
        coEvery { mockClient.discoverPrincipal("https://davis.example.com/dav/") } returns
            CalDavResult.Success(principalUrl)
        coEvery { mockClient.discoverCalendarHome(principalUrl) } returns
            CalDavResult.Success(listOf(calendarHomeUrl))
        coEvery { mockClient.listCalendars(calendarHomeUrl) } returns
            CalDavResult.Success(listOf(
                CalDavCalendar("/dav/calendars/user/default/", "https://davis.example.com/dav/calendars/user/default/", "Default", "#0000FF", "ctag1", false)
            ))

        val result = discoveryService.discoverCalendars(
            serverUrl = "https://davis.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue("Expected CalendarsFound but got $result", result is DiscoveryResult.CalendarsFound)
        coVerify { mockClient.discoverPrincipal("https://davis.example.com/dav/") }
    }

    @Test
    fun `discoverCalendars - probes paths when well-known and root fail`() = runTest {
        // Well-known fails, the root fails too (an HTML page), and probing finds /dav/.
        coEvery { mockClient.discoverWellKnown(any()) } returns
            CalDavResult.Error(404, "Not found")
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

        val result = discoveryService.discoverCalendars(
            serverUrl = "https://davis.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue("Expected CalendarsFound but got $result", result is DiscoveryResult.CalendarsFound)
    }

    @Test
    fun `discoverCalendars - probes use original URL not well-known redirect URL as base`() = runTest {
        // Well-known redirects to another host, which then fails. Probing uses the entered
        // host, not the redirect's.
        coEvery { mockClient.discoverWellKnown("https://myserver.example.com") } returns
            CalDavResult.Success("https://other-host.example.com/dav/")
        coEvery { mockClient.discoverPrincipal("https://other-host.example.com/dav/") } returns
            CalDavResult.Error(500, "Server error")
        coEvery { mockClient.discoverPrincipal("https://myserver.example.com/dav/") } returns
            CalDavResult.Success("https://myserver.example.com/dav/principals/user/")
        coEvery { mockClient.discoverCalendarHome(any()) } returns
            CalDavResult.Success(listOf("https://myserver.example.com/dav/calendars/user/"))
        coEvery { mockClient.listCalendars(any()) } returns
            CalDavResult.Success(listOf(
                CalDavCalendar("/dav/calendars/user/default/", "https://myserver.example.com/dav/calendars/user/default/", "Default", "#0000FF", "ctag1", false)
            ))

        val result = discoveryService.discoverCalendars(
            serverUrl = "https://myserver.example.com",
            username = "user",
            password = "pass"
        )

        assertTrue("Expected CalendarsFound but got $result", result is DiscoveryResult.CalendarsFound)
        coVerify { mockClient.discoverPrincipal("https://myserver.example.com/dav/") }
    }

    // ==================== Helper Methods ====================

    private fun setupSuccessfulDiscovery(
        wellKnownUrl: String,
        principalUrl: String,
        calendarHomeUrl: String,
        calendars: List<CalDavCalendar>
    ) {
        coEvery { mockClient.discoverWellKnown(any()) } returns CalDavResult.Success(wellKnownUrl)
        coEvery { mockClient.discoverPrincipal(wellKnownUrl) } returns CalDavResult.Success(principalUrl)
        coEvery { mockClient.discoverCalendarHome(principalUrl) } returns CalDavResult.Success(listOf(calendarHomeUrl))
        coEvery { mockClient.listCalendars(calendarHomeUrl) } returns CalDavResult.Success(calendars)
    }
}
