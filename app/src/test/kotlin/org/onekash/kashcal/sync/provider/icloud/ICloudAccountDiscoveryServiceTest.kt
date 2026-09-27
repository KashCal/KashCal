package org.onekash.kashcal.sync.provider.icloud

import android.graphics.Color
import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Tests [ICloudAccountDiscoveryService] over a mocked [CalDavClient] and mocked repositories.
 *
 * Covers the discovery flow (principal, home, calendars, then account and calendar rows), auth,
 * server, rate-limit and exception errors with their user-facing messages, color parsing, account
 * removal, credential-save failure (#55), persisting scheduling-delivery discovery, and
 * [ICloudAccountDiscoveryService.refreshCalendars]: probing before removing an unlisted
 * calendar, matching a regional listed URL to its canonical row, color refresh, and keeping the
 * local ctag and sync-token (#249).
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

        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        mockkStatic(Color::class)
        every { Color.parseColor(any()) } answers {
            val clean = firstArg<String>().removePrefix("#")
            when (clean.length) {
                6 -> (0xFF000000 or java.lang.Long.parseLong(clean, 16)).toInt()
                8 -> java.lang.Long.parseLong(clean, 16).toInt()
                else -> 0
            }
        }

        clientFactory = mockk(relaxed = true)
        credentialProvider = mockk(relaxed = true)
        calDavClient = mockk(relaxed = true)
        icloudQuirks = ICloudQuirks()
        accountRepository = mockk(relaxed = true)
        calendarRepository = mockk(relaxed = true)

        every { clientFactory.createClient(any(), any()) } returns calDavClient

        // Default: no existing account, credential save succeeds
        coEvery { accountRepository.getAccountByProviderAndEmail(any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { accountRepository.saveCredentials(any(), any()) } returns true
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        coEvery { calendarRepository.createCalendar(any()) } returns 1L

        // Scheduling-delivery discovery (RFC 6638 §2, §2.1.1) defaults. CalDavResult is sealed,
        // so the relaxed mock needs explicit stubs.
        coEvery { calDavClient.discoverScheduleOutboxUrl(any()) } returns CalDavResult.success(null)
        coEvery { calDavClient.supportsAutoSchedule(any()) } returns CalDavResult.success(false)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Color::class)
        unmockkStatic(Log::class)
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
        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.success(testPrincipalUrl)
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(listOf(testHomeUrl))
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(testCalDavCalendars)

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(result is DiscoveryResult.Success)
        val success = result as DiscoveryResult.Success
        assertEquals(testAppleId, success.account.email)
        assertEquals(2, success.calendars.size)

        coVerify { accountRepository.createAccount(match { it.email == testAppleId }) }

        coVerify(exactly = 2) { calendarRepository.createCalendar(any()) }
    }

    @Test
    fun `discovery persists scheduling-delivery discovery`() = runTest {
        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.success(testPrincipalUrl)
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(listOf(testHomeUrl))
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(testCalDavCalendars)
        coEvery { calDavClient.discoverScheduleOutboxUrl(any()) } returns
            CalDavResult.success("https://caldav.icloud.com/123/calendars/outbox/")
        coEvery { calDavClient.supportsAutoSchedule(any()) } returns CalDavResult.success(true)

        val service = createService()
        service.discoverAndCreateAccount(testAppleId, testPassword)

        // Entry-surface guard: removing the wiring must fail here.
        coVerify { accountRepository.updateScheduleOutboxUrl(1L, "https://caldav.icloud.com/123/calendars/outbox/") }
        coVerify { calendarRepository.updateAutoScheduleSupported(1L, true) }
    }

    @Test
    fun `discovery creates client with credentials via factory`() = runTest {
        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.success(testPrincipalUrl)
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(listOf(testHomeUrl))
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(testCalDavCalendars)

        val service = createService()
        service.discoverAndCreateAccount(testAppleId, testPassword)

        io.mockk.verify { clientFactory.createClient(match { it.username == testAppleId && it.password == testPassword }, any()) }
    }

    @Test
    fun `discovery updates existing account if found`() = runTest {
        coEvery { accountRepository.getAccountByProviderAndEmail(any(), any()) } returns testDbAccount

        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.success(testPrincipalUrl)
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(listOf(testHomeUrl))
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(testCalDavCalendars)

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(result is DiscoveryResult.Success)

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
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(listOf(testHomeUrl))
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(calendarsWithReminders)

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(result is DiscoveryResult.Success)
        // The service creates every calendar the client lists; [ICloudQuirks] does the filtering.
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
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(listOf(testHomeUrl))
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
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(listOf(testHomeUrl))
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(calendarsWithICloudColors)

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(result is DiscoveryResult.Success)
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
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(listOf(testHomeUrl))
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(calendarsWithInvalidColors)

        val service = createService()
        val result = service.discoverAndCreateAccount(testAppleId, testPassword)

        assertTrue(result is DiscoveryResult.Success)
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

    @Test
    fun `removeAccountByEmail does not log the full email address`() = runTest {
        coEvery { accountRepository.getAccountByProviderAndEmail(AccountProvider.ICLOUD, testAppleId) } returns testDbAccount
        val logMessages = mutableListOf<String>()
        every { Log.i(any(), capture(logMessages)) } returns 0

        val service = createService()
        service.removeAccountByEmail(testAppleId)

        assertTrue(
            "Expected the removal log line to be emitted; captured: $logMessages",
            logMessages.any { it.contains("Removing iCloud account") }
        )
        assertTrue(
            "No log line should contain the unmasked email; captured: $logMessages",
            logMessages.none { it.contains(testAppleId) }
        )
        assertTrue(
            "Removal log should contain the masked email; captured: $logMessages",
            logMessages.any { it.contains("tes***@***.com") }
        )
    }

    // ==================== Credential Save Failure Tests (Issue #55) ====================

    @Test
    fun `discoverAndCreateAccount returns error when credentials fail to save`() = runTest {
        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.success(testPrincipalUrl)
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(listOf(testHomeUrl))
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(testCalDavCalendars)

        // An EncryptedSharedPreferences failure, e.g. a broken Android Keystore
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
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(listOf(testHomeUrl))
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(testCalDavCalendars)
        coEvery { accountRepository.saveCredentials(any(), any()) } returns false

        val service = createService()
        service.discoverAndCreateAccount(testAppleId, testPassword)

        // The account can't sync without credentials, so it is deleted.
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

    // ==================== refreshCalendars: confirm before removing ====================

    private fun icloudCalendar(id: Long, slug: String) = Calendar(
        id = id,
        accountId = 1L,
        caldavUrl = "https://caldav.icloud.com/123/calendars/$slug/",
        displayName = slug,
        color = 0xFF4CAF50.toInt(),
        ctag = "ctag",
        isReadOnly = false,
        isDefault = false,
        isVisible = true
    )

    private fun stubICloudRefresh(existing: List<Calendar>, listed: List<CalDavCalendar>) {
        coEvery { accountRepository.getAccountById(1L) } returns testDbAccount
        coEvery { credentialProvider.getCredentials(1L) } returns
            Credentials(testAppleId, testPassword, "https://caldav.icloud.com")
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(listOf(testHomeUrl))
        coEvery { calendarRepository.getCalendarsForAccountOnce(1L) } returns existing
        coEvery { calendarRepository.getCalendarByUrl(any()) } answers {
            existing.firstOrNull { it.caldavUrl == firstArg<String>() }
        }
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(listed)
    }

    @Test
    fun `refreshCalendars keeps every iCloud calendar when the listing is empty and probes are unreadable`() = runTest {
        val personal = icloudCalendar(1L, "personal")
        val work = icloudCalendar(2L, "work")
        stubICloudRefresh(listOf(personal, work), emptyList())
        coEvery { calDavClient.probeCalendarCollection(any()) } returns
            CalDavResult.error(500, "resourcetype not found in response")

        val result = createService().refreshCalendars(1L)

        assertTrue("Expected Success, got $result", result is DiscoveryResult.Success)
        coVerify(exactly = 0) { calendarRepository.deleteCalendar(any()) }
        assertEquals(
            setOf(personal.id, work.id),
            (result as DiscoveryResult.Success).calendars.map { it.id }.toSet()
        )
    }

    @Test
    fun `refreshCalendars keeps an unlisted iCloud calendar the server says still exists`() = runTest {
        val personal = icloudCalendar(1L, "personal")
        stubICloudRefresh(listOf(personal), emptyList())
        coEvery { calDavClient.probeCalendarCollection(personal.caldavUrl) } returns CalDavResult.success(true)

        createService().refreshCalendars(1L)

        coVerify(exactly = 0) { calendarRepository.deleteCalendar(any()) }
    }

    @Test
    fun `refreshCalendars removes an unlisted iCloud calendar the server answers 404 for`() = runTest {
        val deleted = icloudCalendar(1L, "old-trip")
        stubICloudRefresh(listOf(deleted), emptyList())
        coEvery { calDavClient.probeCalendarCollection(deleted.caldavUrl) } returns
            CalDavResult.notFoundError("Resource not found")

        createService().refreshCalendars(1L)

        coVerify { calendarRepository.deleteCalendar(deleted.id) }
    }

    @Test
    fun `an iCloud refresh whose listings were all refused says so`() = runTest {
        val cal = icloudCalendar(1L, "home")
        stubICloudRefresh(listOf(cal), emptyList())
        coEvery { calDavClient.listCalendars(any()) } returns
            CalDavResult.error(CalDavResult.CODE_TRANSPORT_REFUSED, "refused", isRetryable = true)

        val result = createService().refreshCalendars(1L)

        assertEquals(DiscoveryErrorReason.INSECURE_CONNECTION_REFUSED, (result as DiscoveryResult.Error).reason)
    }

    @Test
    fun `refreshCalendars removes an unlisted iCloud calendar the server answers 403 for`() = runTest {
        val deleted = icloudCalendar(1L, "old-share")
        stubICloudRefresh(listOf(deleted), emptyList())
        coEvery { calDavClient.probeCalendarCollection(deleted.caldavUrl) } returns
            CalDavResult.error(403, "Permission denied")

        createService().refreshCalendars(1L)

        coVerify { calendarRepository.deleteCalendar(deleted.id) }
    }

    @Test
    fun `refreshCalendars treats a regional listed URL as the stored canonical calendar and does not probe it`() = runTest {
        val personal = icloudCalendar(1L, "personal")
        stubICloudRefresh(
            listOf(personal),
            listOf(
                CalDavCalendar(
                    href = "/123/calendars/personal/",
                    url = "https://p42-caldav.icloud.com/123/calendars/personal/",
                    displayName = "personal",
                    color = null,
                    ctag = "ctag",
                    isReadOnly = false
                )
            )
        )

        createService().refreshCalendars(1L)

        coVerify(exactly = 0) { calDavClient.probeCalendarCollection(any()) }
        coVerify(exactly = 0) { calendarRepository.deleteCalendar(any()) }
    }

    // ==================== refreshCalendars Color Refresh ====================

    @Test
    fun `refreshCalendars adopts server color change for existing calendar`() = runTest {
        val existingCalendar = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://caldav.icloud.com/123/calendars/personal",
            displayName = "Personal",
            color = 0xFF4CAF50.toInt(), // green
            ctag = "old-ctag",
            isReadOnly = false,
            isDefault = false,
            isVisible = true
        )

        coEvery { accountRepository.getAccountById(1L) } returns testDbAccount
        coEvery { credentialProvider.getCredentials(1L) } returns
            Credentials(testAppleId, testPassword, "https://caldav.icloud.com")
        coEvery { calDavClient.discoverCalendarHome(any()) } returns
            CalDavResult.success(listOf(testHomeUrl))
        coEvery { calendarRepository.getCalendarsForAccountOnce(1L) } returns listOf(existingCalendar)
        coEvery { calendarRepository.getCalendarByUrl(existingCalendar.caldavUrl) } returns existingCalendar
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(
            listOf(
                CalDavCalendar(
                    href = "/123/calendars/personal",
                    url = "https://caldav.icloud.com/123/calendars/personal",
                    displayName = "Personal",
                    color = "#FF0000FF", // iCloud #RRGGBBAA: RR=FF GG=00 BB=00 AA=FF, opaque red
                    ctag = "new-ctag",
                    isReadOnly = false
                )
            )
        )

        val service = createService()
        service.refreshCalendars(1L)

        // iCloud #FF0000FF (RRGGBBAA) becomes Android #FFFF0000 (AARRGGBB), opaque red
        coVerify { calendarRepository.updateCalendar(match { it.color == 0xFFFF0000.toInt() }) }
    }

    @Test
    fun `refreshCalendars preserves local color when server returns null color`() = runTest {
        val existingCalendar = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://caldav.icloud.com/123/calendars/personal",
            displayName = "Personal",
            color = 0xFF4CAF50.toInt(),
            ctag = "old-ctag",
            isReadOnly = false,
            isDefault = false,
            isVisible = true
        )
        val localColor = existingCalendar.color

        coEvery { accountRepository.getAccountById(1L) } returns testDbAccount
        coEvery { credentialProvider.getCredentials(1L) } returns
            Credentials(testAppleId, testPassword, "https://caldav.icloud.com")
        coEvery { calDavClient.discoverCalendarHome(any()) } returns
            CalDavResult.success(listOf(testHomeUrl))
        coEvery { calendarRepository.getCalendarsForAccountOnce(1L) } returns listOf(existingCalendar)
        coEvery { calendarRepository.getCalendarByUrl(existingCalendar.caldavUrl) } returns existingCalendar
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(
            listOf(
                CalDavCalendar(
                    href = "/123/calendars/personal",
                    url = "https://caldav.icloud.com/123/calendars/personal",
                    displayName = "Personal",
                    color = null,
                    ctag = "new-ctag",
                    isReadOnly = false
                )
            )
        )

        val service = createService()
        service.refreshCalendars(1L)

        coVerify { calendarRepository.updateCalendar(match { it.color == localColor }) }
    }

    // ==================== refreshCalendars keeps ctag and sync-token (#249) ====================

    @Test
    fun `refreshCalendars preserves local ctag when server returns new ctag`() = runTest {
        val existingCalendar = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://caldav.icloud.com/123/calendars/personal",
            displayName = "Personal",
            color = 0xFF4CAF50.toInt(),
            ctag = "old-ctag",
            syncToken = "old-token",
            isReadOnly = false,
            isDefault = false,
            isVisible = true
        )

        coEvery { accountRepository.getAccountById(1L) } returns testDbAccount
        coEvery { credentialProvider.getCredentials(1L) } returns
            Credentials(testAppleId, testPassword, "https://caldav.icloud.com")
        coEvery { calDavClient.discoverCalendarHome(any()) } returns
            CalDavResult.success(listOf(testHomeUrl))
        coEvery { calendarRepository.getCalendarsForAccountOnce(1L) } returns listOf(existingCalendar)
        coEvery { calendarRepository.getCalendarByUrl(existingCalendar.caldavUrl) } returns existingCalendar
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(
            listOf(
                CalDavCalendar(
                    href = "/123/calendars/personal",
                    url = "https://caldav.icloud.com/123/calendars/personal",
                    displayName = "Personal",
                    color = null,
                    ctag = "new-server-ctag",
                    isReadOnly = false
                )
            )
        )

        val service = createService()
        service.refreshCalendars(1L)

        coVerify { calendarRepository.updateCalendar(match { it.ctag == "old-ctag" }) }
    }

    @Test
    fun `refreshCalendars preserves local syncToken when server returns new ctag`() = runTest {
        // CalDavCalendar has no syncToken field, so .copy() keeps it today. This pins the
        // contract so adding syncToken to the discovery model can't silently overwrite it.
        val existingCalendar = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://caldav.icloud.com/123/calendars/personal",
            displayName = "Personal",
            color = 0xFF4CAF50.toInt(),
            ctag = "old-ctag",
            syncToken = "old-token",
            isReadOnly = false,
            isDefault = false,
            isVisible = true
        )

        coEvery { accountRepository.getAccountById(1L) } returns testDbAccount
        coEvery { credentialProvider.getCredentials(1L) } returns
            Credentials(testAppleId, testPassword, "https://caldav.icloud.com")
        coEvery { calDavClient.discoverCalendarHome(any()) } returns
            CalDavResult.success(listOf(testHomeUrl))
        coEvery { calendarRepository.getCalendarsForAccountOnce(1L) } returns listOf(existingCalendar)
        coEvery { calendarRepository.getCalendarByUrl(existingCalendar.caldavUrl) } returns existingCalendar
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(
            listOf(
                CalDavCalendar(
                    href = "/123/calendars/personal",
                    url = "https://caldav.icloud.com/123/calendars/personal",
                    displayName = "Personal",
                    color = null,
                    ctag = "new-server-ctag",
                    isReadOnly = false
                )
            )
        )

        val service = createService()
        service.refreshCalendars(1L)

        coVerify { calendarRepository.updateCalendar(match { it.syncToken == "old-token" }) }
    }

    @Test
    fun `discoverAndCreateAccount preserves local ctag for existing calendar URLs`() = runTest {
        val existingCalendar = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://caldav.icloud.com/123/calendars/personal",
            displayName = "Personal",
            color = 0xFF4CAF50.toInt(),
            ctag = "old-ctag",
            syncToken = "old-token",
            isReadOnly = false,
            isDefault = false,
            isVisible = true
        )

        coEvery { accountRepository.getAccountByProviderAndEmail(any(), any()) } returns testDbAccount
        coEvery { calDavClient.discoverPrincipal(any()) } returns CalDavResult.success(testPrincipalUrl)
        coEvery { calDavClient.discoverCalendarHome(any()) } returns CalDavResult.success(listOf(testHomeUrl))
        coEvery { calDavClient.listCalendars(any()) } returns CalDavResult.success(
            listOf(
                CalDavCalendar(
                    href = "/123/calendars/personal",
                    url = "https://caldav.icloud.com/123/calendars/personal",
                    displayName = "Personal",
                    color = "#FF2196F3",
                    ctag = "new-server-ctag",
                    isReadOnly = false
                )
            )
        )
        coEvery { calendarRepository.getCalendarByUrl(existingCalendar.caldavUrl) } returns existingCalendar

        val service = createService()
        service.discoverAndCreateAccount(testAppleId, testPassword)

        coVerify {
            calendarRepository.updateCalendar(
                match { it.ctag == "old-ctag" && it.syncToken == "old-token" }
            )
        }
    }
}
