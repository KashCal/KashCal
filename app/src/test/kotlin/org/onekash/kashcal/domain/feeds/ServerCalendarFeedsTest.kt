package org.onekash.kashcal.domain.feeds

import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.auth.CredentialProvider
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.CalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.provider.ProviderRegistry
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.sync.quirks.CalDavQuirks.ParsedWebcalFeed

/** Tests [ServerCalendarFeeds]: which accounts qualify, and each account's feed listing. */
class ServerCalendarFeedsTest {

    private val home = "https://dav.example.test/calendars/user/"
    private val caldav = Account(
        id = 7, provider = AccountProvider.CALDAV, email = "user@example.test",
        displayName = "Home server", homeSetUrl = home,
    )
    private val credentials = Credentials("user", "pass", "https://dav.example.test/")

    private lateinit var accountRepository: AccountRepository
    private lateinit var providerRegistry: ProviderRegistry
    private lateinit var credentialProvider: CredentialProvider
    private lateinit var clientFactory: CalDavClientFactory
    private lateinit var client: CalDavClient
    private lateinit var quirks: CalDavQuirks
    private lateinit var feeds: ServerCalendarFeeds

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0

        accountRepository = mockk()
        providerRegistry = mockk()
        credentialProvider = mockk()
        clientFactory = mockk()
        client = mockk()
        quirks = mockk()

        coEvery { accountRepository.getAccountById(7) } returns caldav
        every { providerRegistry.getCredentialProvider(AccountProvider.CALDAV) } returns credentialProvider
        every { providerRegistry.getQuirksForAccount(caldav) } returns quirks
        coEvery { credentialProvider.getCredentials(7) } returns credentials
        every { clientFactory.createClient(credentials, quirks) } returns client

        feeds = ServerCalendarFeeds(accountRepository, providerRegistry, clientFactory)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun listing(vararg parsed: ParsedWebcalFeed) {
        coEvery { client.listWebcalFeeds(home) } returns CalDavResult.success(parsed.toList())
    }

    private fun feed(source: String?, name: String? = "Feed", color: String? = null, href: String = "/c/x/") =
        ParsedWebcalFeed(href = href, displayName = name, color = color, source = source)

    // ========== Feed accounts ==========

    @Test
    fun `feed accounts are the iCloud and CalDAV accounts and follow changes`() = runTest {
        val icloud = Account(id = 1, provider = AccountProvider.ICLOUD, email = "i@example.test")
        val ics = Account(id = 2, provider = AccountProvider.ICS, email = "subscriptions")
        val local = Account(id = 3, provider = AccountProvider.LOCAL, email = "local")
        val contacts = Account(id = 4, provider = AccountProvider.CONTACTS, email = "contacts")
        val disabled = caldav.copy(id = 8, isEnabled = false)
        val rows = MutableStateFlow(listOf(icloud, ics, local, contacts, caldav, disabled))
        every { accountRepository.getAllAccountsFlow() } returns rows

        val seen = mutableListOf<List<FeedAccount>>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            feeds.observeFeedAccounts().toList(seen)
        }

        val first = seen.single()
        assertEquals(listOf(1L, 7L, 8L), first.map { it.id })
        assertEquals("iCloud", first[0].displayName)
        assertEquals("Home server", first[1].displayName)
        assertEquals("user@example.test", first[1].email)

        rows.value = listOf(ics)
        assertEquals("the same collector sees the change", 2, seen.size)
        assertEquals(emptyList<FeedAccount>(), seen.last())
        collector.cancel()
    }

    // ========== Listing ==========

    @Test
    fun `lists the account's calendar home through a client built from its credentials and quirks`() = runTest {
        listing(feed("https://h.example.test/a.ics"))

        val result = feeds.listFeeds(7)

        assertTrue(result is FeedListing.Feeds)
        coVerify(exactly = 1) { client.listWebcalFeeds(home) }
        verify(exactly = 1) { clientFactory.createClient(credentials, quirks) }
    }

    @Test
    fun `each usable feed gets its cleaned URL, server name and server color`() = runTest {
        listing(
            feed("webcal://h.example.test/a.ics", name = " US Holidays ", color = "#1E88E5FF"),
            feed("https://h.example.test/b.ics", name = "Six", color = "#43A047"),
            feed("https://h.example.test/c.ics", name = "Bare", color = "E53935"),
        )

        val rows = (feeds.listFeeds(7) as FeedListing.Feeds).feeds

        assertEquals(
            listOf(
                ServerFeed("https://h.example.test/a.ics", "US Holidays", 0xFF1E88E5.toInt()),
                ServerFeed("https://h.example.test/b.ics", "Six", 0xFF43A047.toInt()),
                ServerFeed("https://h.example.test/c.ics", "Bare", 0xFFE53935.toInt()),
            ),
            rows,
        )
    }

    @Test
    fun `an alpha byte in RRGGBBAA moves to the front`() = runTest {
        listing(feed("https://h.example.test/a.ics", color = "#c84b7b80"))

        val row = (feeds.listFeeds(7) as FeedListing.Feeds).feeds.single()

        assertEquals(0x80c84b7b.toInt(), row.color)
    }

    @Test
    fun `a missing name falls back to the host and a missing or bad color to null`() = runTest {
        listing(
            feed("https://feeds.example.test/a.ics", name = null, color = null),
            feed("https://other.example.test/b.ics", name = "  ", color = "not a color"),
        )

        val rows = (feeds.listFeeds(7) as FeedListing.Feeds).feeds

        assertEquals(listOf("feeds.example.test", "other.example.test"), rows.map { it.name })
        assertTrue(rows.all { it.color == null })
    }

    @Test
    fun `feeds without a usable URL are left out and a URL listed twice appears once`() = runTest {
        listing(
            feed(null, href = "/c/broken/"),
            feed("ftp://h.example.test/a.ics", href = "/c/ftp/"),
            feed("/remote.php/dav/x", href = "/c/relative/"),
            feed("https://h.example.test/g.ics", name = "First", href = "/c/one/"),
            feed("HTTPS://H.Example.test/g.ics", name = "Second", href = "/c/two/"),
        )

        val rows = (feeds.listFeeds(7) as FeedListing.Feeds).feeds

        assertEquals(listOf(ServerFeed("https://h.example.test/g.ics", "First", null)), rows)
    }

    @Test
    fun `a listing with no usable feed is an empty list, not a failure`() = runTest {
        listing(feed(null))

        assertEquals(FeedListing.Feeds(emptyList()), feeds.listFeeds(7))
    }

    // ========== Failures ==========

    @Test
    fun `a server error is Failed`() = runTest {
        coEvery { client.listWebcalFeeds(home) } returns CalDavResult.error(401, "no")

        assertEquals(FeedListing.Failed, feeds.listFeeds(7))
    }

    @Test
    fun `a throw while listing is Failed`() = runTest {
        coEvery { client.listWebcalFeeds(home) } throws IllegalStateException("boom")

        assertEquals(FeedListing.Failed, feeds.listFeeds(7))
    }

    @Test
    fun `cancellation is rethrown, not reported as Failed`() = runTest {
        coEvery { client.listWebcalFeeds(home) } throws CancellationException("closed")

        try {
            feeds.listFeeds(7)
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("closed", e.message)
        }
    }

    @Test
    fun `a missing account is Failed without any request`() = runTest {
        coEvery { accountRepository.getAccountById(99) } returns null

        assertEquals(FeedListing.Failed, feeds.listFeeds(99))
        coVerify(exactly = 0) { client.listWebcalFeeds(any()) }
    }

    @Test
    fun `a provider without feeds is Failed without any request`() = runTest {
        coEvery { accountRepository.getAccountById(2) } returns
            Account(id = 2, provider = AccountProvider.ICS, email = "subscriptions", homeSetUrl = home)

        assertEquals(FeedListing.Failed, feeds.listFeeds(2))
        verify(exactly = 0) { clientFactory.createClient(any(), any()) }
    }

    @Test
    fun `no calendar home is Failed without any request`() = runTest {
        coEvery { accountRepository.getAccountById(7) } returns caldav.copy(homeSetUrl = null)

        assertEquals(FeedListing.Failed, feeds.listFeeds(7))
        verify(exactly = 0) { clientFactory.createClient(any(), any()) }
    }

    @Test
    fun `no stored credentials is Failed without any request`() = runTest {
        coEvery { credentialProvider.getCredentials(7) } returns null

        assertEquals(FeedListing.Failed, feeds.listFeeds(7))
        verify(exactly = 0) { clientFactory.createClient(any(), any()) }
    }

    @Test
    fun `no credential provider or no quirks is Failed without any request`() = runTest {
        every { providerRegistry.getCredentialProvider(AccountProvider.CALDAV) } returns null
        assertEquals(FeedListing.Failed, feeds.listFeeds(7))

        every { providerRegistry.getCredentialProvider(AccountProvider.CALDAV) } returns credentialProvider
        every { providerRegistry.getQuirksForAccount(caldav) } returns null
        assertEquals(FeedListing.Failed, feeds.listFeeds(7))

        verify(exactly = 0) { clientFactory.createClient(any(), any()) }
    }
}
