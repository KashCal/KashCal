package org.onekash.kashcal.sync.integration.multiserver

import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.onekash.kashcal.sync.provider.caldav.CalDavAccountDiscoveryService
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI

/**
 * Checks discovery against a server that offers contacts and no calendars: the local Baikal
 * with CalDAV switched off ([CardDavServerConfig.BAIKAL_CARDDAV]).
 *
 * The CardDAV walk must reach the login's writable `default` address book. On the CalDAV side
 * the principal must resolve and the calendar-home lookup must be the step that fails, and the
 * sign-in through [CalDavAccountDiscoveryService.discoverCalendars] must end in
 * [DiscoveryResult.Error], not [DiscoveryResult.AuthError] (the password is right), without
 * touching either repository.
 *
 * Skips only when the credentials are missing or the server is down; any other outcome is
 * asserted, because the fixture is a known local container.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration --tests '*ContactsOnlyServerDiscoveryTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ContactsOnlyServerDiscoveryTest {

    private val config = CardDavServerConfig.BAIKAL_CARDDAV
    private var client: CardDavClient? = null
    private var creds: ServerCredentials? = null

    @Before
    fun setup() {
        CardDavTestServerLoader.createClient(config)?.let {
            client = it.first
            creds = it.second
        }
    }

    private fun assumeReady() {
        assumeTrue("${config.name}: no credentials in local.properties", client != null)
        assumeTrue(
            "${config.name}: server unreachable at ${creds!!.davEndpoint}",
            CardDavTestServerLoader.isServerReachable(creds!!.davEndpoint),
        )
    }

    private fun <T> assertSuccess(step: String, result: CalDavResult<T>): T {
        assertTrue("$step failed: $result", result is CalDavResult.Success)
        return (result as CalDavResult.Success).data
    }

    @Test
    fun `contacts walk reaches the writable default address book`() = runBlocking {
        assumeReady()
        val c = client!!
        val cr = creds!!

        val endpoint = assertSuccess("well-known", c.discoverWellKnown(cr.serverUrl))
        // nginx in the container sends an absolute redirect with its own port 80 unless the
        // fixture sets `absolute_redirect off;` in /etc/nginx/conf.d/default.conf. The edit
        // lives in the container, so re-creating the container loses it. discoverWellKnown
        // returns the entered URL when the redirect leads nowhere, so the path is checked too.
        val fixtureHint = "well-known gave $endpoint: check the fixture's absolute_redirect off"
        assertEquals(fixtureHint, URI(cr.serverUrl).port, URI(endpoint).port)
        assertTrue(fixtureHint, URI(endpoint).path.startsWith("/dav.php"))

        val principalUrl = assertSuccess("principal", c.discoverPrincipal(endpoint))
        assertTrue("principal $principalUrl", principalUrl.endsWith("/dav.php/principals/testuser1/"))

        val homes = assertSuccess("address-book home", c.discoverAddressBookHome(principalUrl))
        assertEquals("homes $homes", 1, homes.size)
        assertTrue("home ${homes[0]}", homes[0].endsWith("/dav.php/addressbooks/testuser1/"))

        val books = assertSuccess("listing", c.listAddressBooks(homes[0]))
        val default = books.firstOrNull { it.url.endsWith("/default/") }
        assertNotNull("no default book in ${books.map { it.url }}", default)
        assertFalse("default book read-only", default!!.isReadOnly)
    }

    @Test
    fun `calendar sign-in finds no calendars, keeps the password valid and writes nothing`() = runBlocking {
        assumeReady()
        val cr = creds!!
        val factory = OkHttpCalDavClientFactory()

        val calDav = factory.createClient(
            Credentials(username = cr.username, password = cr.password, serverUrl = cr.davEndpoint),
            DefaultQuirks(cr.davEndpoint),
        )
        val principalUrl = assertSuccess("CalDAV principal", calDav.discoverPrincipal(cr.davEndpoint))
        val home = calDav.discoverCalendarHome(principalUrl)
        assertTrue("calendar home should be missing, got $home", home is CalDavResult.Error)

        // Strict mocks: discoverCalendars catches every exception, so an unstubbed call alone
        // would surface as the Error this test accepts. The verify below is what fails on a write.
        val accountRepository: AccountRepository = mockk()
        val calendarRepository: CalendarRepository = mockk()
        val service = CalDavAccountDiscoveryService(
            calDavClientFactory = factory,
            accountRepository = accountRepository,
            calendarRepository = calendarRepository,
        )

        val result = service.discoverCalendars(
            serverUrl = cr.davEndpoint,
            username = cr.username,
            password = cr.password,
        )
        println("CONTACTS_ONLY|$result")

        assertTrue("expected Error, got $result", result is DiscoveryResult.Error)
        verify { accountRepository wasNot Called }
        verify { calendarRepository wasNot Called }
    }
}
