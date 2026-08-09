package org.onekash.kashcal.sync.contacts

import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.AddressBook
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.carddav.FakeAddressBook
import org.onekash.kashcal.sync.carddav.FakeCardDavClient
import org.onekash.kashcal.sync.carddav.model.CardDavAddressBook
import org.onekash.kashcal.sync.carddav.model.CardDavContactData
import org.onekash.kashcal.sync.carddav.model.ContactSyncItem
import org.onekash.kashcal.sync.carddav.model.ContactSyncReport
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the full-sync core of [ContactPullStrategy]: re-discover books, route each
 * server contact to insert / replace / skip against the device read-back, sweep
 * orphans across the union of ALL books (never per-book), and persist token+ctag.
 *
 * Doubles: the shared [FakeCardDavClient] (real discovery + multiget data), a real
 * in-memory Room [org.onekash.kashcal.data.db.dao.AddressBookDao], the shared
 * data-bearing [FakeContactsProviderRepository] (Robolectric's ShadowContentResolver
 * can't execute provider writes, so the real repo could never feed a non-empty
 * read-back — the exact signal routing depends on), and the real
 * [org.onekash.kashcal.data.contacts.VCardContactMapper] inside the strategy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ContactPullStrategyTest {

    private lateinit var database: KashCalDatabase
    private lateinit var provider: FakeContactsProviderRepository
    private lateinit var strategy: ContactPullStrategy
    private var accountId: Long = 0

    private val account: Account
        get() = Account(id = accountId, provider = AccountProvider.ICLOUD, email = ACCOUNT_NAME)

    @Before
    fun setup() = runTest {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KashCalDatabase::class.java,
        ).allowMainThreadQueries().build()

        accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.ICLOUD, email = ACCOUNT_NAME),
        )

        provider = FakeContactsProviderRepository()
        // Real fetcher over the same fake provider: with no pending photos seeded it
        // is an exact no-op, so the full-sync assertions below are unaffected.
        strategy = ContactPullStrategy(database.addressBookDao(), provider, ContactPhotoFetcher(provider))
    }

    @After
    fun tearDown() {
        database.close()
        unmockkAll()
    }

    // ---------- helpers ----------

    private fun vcard(uid: String, fn: String): String =
        "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:$uid\r\nFN:$fn\r\nN:$fn;;;;\r\nEND:VCARD\r\n"

    private fun contact(href: String, etag: String, uid: String = href, fn: String = "Person $href") =
        CardDavContactData(href = href, url = "$BOOK_URL$href", etag = etag, vcardBody = vcard(uid, fn))

    private fun book(
        url: String = BOOK_URL,
        ctag: String? = "ctag-1",
        contacts: MutableList<CardDavContactData> = mutableListOf(),
    ) = FakeAddressBook(
        book = CardDavAddressBook(href = url, url = url, displayName = "Contacts", ctag = ctag, vcardVersion = "3.0"),
        contacts = contacts,
    )

    private fun clientWith(vararg books: FakeAddressBook, syncToken: String? = "token-1"): FakeCardDavClient =
        FakeCardDavClient().apply {
            addressBookHomes.clear()
            addressBookHomes += HOME_URL
            this.syncToken = syncToken
            this.books += books
        }

    /**
     * Persist a stored sync-token for [url] before a run, so the next
     * [ContactPullStrategy.sync] takes the incremental sync-collection branch
     * rather than a full listing (a book with no stored token full-lists).
     */
    private suspend fun seedStoredToken(url: String, token: String) {
        database.addressBookDao().upsert(
            AddressBook(accountId = accountId, url = url, displayName = "Contacts", syncToken = token),
        )
    }

    // ---------- full sync populates ----------

    @Test
    fun `full sync inserts every server contact and persists book plus token`() = runTest {
        val client = clientWith(book(contacts = mutableListOf(contact("/a.vcf", "e1"), contact("/b.vcf", "e2"))))

        val result = strategy.sync(account, SERVER_URL, client)

        assertTrue(result is ContactPullResult.Success)
        val success = result as ContactPullResult.Success
        assertEquals("both inserted", 2, success.inserted)
        assertEquals(0, success.replaced)
        assertEquals(0, success.deleted)

        assertEquals("device now holds both hrefs", setOf("/a.vcf", "/b.vcf"), provider.hrefsFor(ACCOUNT_NAME))

        val persisted = database.addressBookDao().getByAccountIdOnce(accountId).single()
        assertEquals(BOOK_URL, persisted.url)
        assertEquals("sync-token persisted", "token-1", persisted.syncToken)
        assertEquals("ctag persisted", "ctag-1", persisted.ctag)
    }

    @Test
    fun `sync ensures ungrouped-contact visibility for the account`() = runTest {
        val client = clientWith(book(contacts = mutableListOf(contact("/a.vcf", "e1"))))

        strategy.sync(account, SERVER_URL, client)

        // Every run re-asserts the account's UNGROUPED_VISIBLE Settings row so
        // accounts enabled before this fix (whose row is unset) self-heal on their
        // next pull rather than staying invisible forever.
        assertEquals(listOf(ACCOUNT_NAME), provider.ensureVisibilityCalls)
    }

    // ---------- changed re-materializes ----------

    @Test
    fun `a contact whose etag changed on the server is replaced, not re-inserted`() = runTest {
        provider.seed(ACCOUNT_NAME, "/a.vcf", "old-etag")
        val client = clientWith(book(contacts = mutableListOf(contact("/a.vcf", "new-etag"))))

        val result = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success

        assertEquals("changed contact goes through replace", 1, result.replaced)
        assertEquals(0, result.inserted)
        assertEquals("device etag updated to the server's", "new-etag", provider.etagFor(ACCOUNT_NAME, "/a.vcf"))
    }

    // ---------- unchanged is skipped ----------

    @Test
    fun `a contact whose etag is unchanged is neither fetched nor written`() = runTest {
        provider.seed(ACCOUNT_NAME, "/a.vcf", "same-etag")
        val client = clientWith(book(contacts = mutableListOf(contact("/a.vcf", "same-etag"))))

        val result = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success

        assertEquals("unchanged contact is skipped", 1, result.skipped)
        assertEquals(0, result.inserted)
        assertEquals(0, result.replaced)
        assertTrue("no insert call for the unchanged contact", provider.insertCalls.isEmpty())
        assertTrue("no replace call for the unchanged contact", provider.replaceCalls.isEmpty())
        assertEquals(
            "unchanged contact must not be fetched over the wire",
            0,
            client.fetchCalls,
        )
    }

    // ---------- server delete -> orphan removed ----------

    @Test
    fun `a contact the server no longer lists is deleted from the device`() = runTest {
        provider.seed(ACCOUNT_NAME, "/gone.vcf", "e-gone")
        provider.seed(ACCOUNT_NAME, "/keep.vcf", "e-keep")
        val client = clientWith(book(contacts = mutableListOf(contact("/keep.vcf", "e-keep"))))

        val result = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success

        assertEquals("the orphan is swept", 1, result.deleted)
        assertFalse("orphan gone from device", provider.hrefsFor(ACCOUNT_NAME).contains("/gone.vcf"))
        assertTrue("still-listed contact survives", provider.hrefsFor(ACCOUNT_NAME).contains("/keep.vcf"))
    }

    // ---------- orphan sweep is union-wide, never per-book ----------

    @Test
    fun `orphan sweep spans all books - a contact in book B is not deleted by book A's absence`() = runTest {
        // Both contacts already on device; each lives in a different book. A per-book
        // sweep would see book A's server list (only /a.vcf) and wrongly delete /b.vcf.
        provider.seed(ACCOUNT_NAME, "/a.vcf", "ea")
        provider.seed(ACCOUNT_NAME, "/b.vcf", "eb")
        val bookA = book(url = "${HOME_URL}bookA/", contacts = mutableListOf(contact("/a.vcf", "ea")))
        val bookB = book(url = "${HOME_URL}bookB/", contacts = mutableListOf(contact("/b.vcf", "eb")))
        val client = clientWith(bookA, bookB)

        val result = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success

        assertEquals("nothing is an orphan — both are server-listed across the two books", 0, result.deleted)
        assertEquals(setOf("/a.vcf", "/b.vcf"), provider.hrefsFor(ACCOUNT_NAME))
        assertTrue("no delete statement issued at all", provider.deleteCalls.isEmpty())
    }

    // ---------- a failed book must not trigger a mass delete ----------

    @Test
    fun `a book that fails to enumerate disables the orphan sweep entirely`() = runTest {
        // /a.vcf is on device and lives in the book that FAILS to enumerate. If the
        // failed book were treated as "server has zero hrefs", /a.vcf would be swept.
        provider.seed(ACCOUNT_NAME, "/a.vcf", "ea")
        provider.seed(ACCOUNT_NAME, "/b.vcf", "eb")
        val bookOk = book(url = "${HOME_URL}ok/", contacts = mutableListOf(contact("/b.vcf", "eb")))
        val bookBroken = book(url = "${HOME_URL}broken/", contacts = mutableListOf(contact("/a.vcf", "ea"))).apply {
            listError = org.onekash.kashcal.sync.client.model.CalDavResult.Error(503, "unavailable", isRetryable = true)
        }
        val client = clientWith(bookOk, bookBroken)

        val result = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success

        assertEquals("a failed enumerate must skip the sweep, not delete", 0, result.deleted)
        assertTrue("no delete issued when any book failed", provider.deleteCalls.isEmpty())
        assertEquals("the failed book is reported", 1, result.booksFailed)
        assertTrue("both device contacts survive", provider.hrefsFor(ACCOUNT_NAME).containsAll(listOf("/a.vcf", "/b.vcf")))
    }

    // ---------- discovery failure must not masquerade as an empty server ----------

    @Test
    fun `a total discovery listing failure surfaces an error and never sweeps`() = runTest {
        // The account has contacts on device. The only home-set transiently fails to
        // list its books. This must NOT read as "server has zero contacts" and wipe
        // the device — it's a systemic error, and no delete may be issued.
        provider.seed(ACCOUNT_NAME, "/a.vcf", "ea")
        provider.seed(ACCOUNT_NAME, "/b.vcf", "eb")
        val client = clientWith().apply {
            listAddressBooksError =
                org.onekash.kashcal.sync.client.model.CalDavResult.Error(503, "unavailable", isRetryable = true)
        }

        val result = strategy.sync(account, SERVER_URL, client)

        assertTrue("a total discovery failure is a systemic error, not a Success", result is ContactPullResult.Error)
        assertTrue("no delete issued when discovery failed", provider.deleteCalls.isEmpty())
        assertEquals("device contacts untouched", setOf("/a.vcf", "/b.vcf"), provider.hrefsFor(ACCOUNT_NAME))
    }

    // ---------- stale context path falls back to the host root ----------

    @Test
    fun `principal discovery failing at a discovered context path retries at the host root`() = runTest {
        // The seed URL carries a context path (as a stale DNS TXT path= would). The
        // server no longer serves a principal there, but does at the host root. The
        // run must retry at root and complete rather than failing the whole account.
        val contextPathUrl = "https://dav.example.test/stale-carddav/"
        val client = clientWith(book(contacts = mutableListOf(contact("/a.vcf", "e1")))).apply {
            principalErrorUrls += contextPathUrl
        }

        val result = strategy.sync(account, contextPathUrl, client)

        assertTrue("the run recovers via the root retry", result is ContactPullResult.Success)
        assertEquals("contact synced after the fallback", setOf("/a.vcf"), provider.hrefsFor(ACCOUNT_NAME))
        assertEquals(
            "principal discovery is attempted at the context path first, then the host root",
            listOf(contextPathUrl, "https://dav.example.test"),
            client.discoverPrincipalCalls,
        )
    }

    @Test
    fun `principal discovery is not retried when the seed already is the host root`() = runTest {
        // No context path to fall back from: a genuine principal failure at the root
        // must surface as an error, not loop or spuriously succeed.
        val rootUrl = "https://dav.example.test"
        val client = clientWith(book(contacts = mutableListOf(contact("/a.vcf", "e1")))).apply {
            principalErrorUrls += rootUrl
        }

        val result = strategy.sync(account, rootUrl, client)

        assertTrue("a root-level principal failure is a systemic error", result is ContactPullResult.Error)
        assertEquals("no redundant retry at the same root", listOf(rootUrl), client.discoverPrincipalCalls)
    }

    @Test
    fun `a host root seed with a trailing slash is not retried against the same location`() = runTest {
        // The host root can arrive with a trailing slash; trimming it must make the
        // no-retry guarantee hold, so a genuine failure here isn't re-attempted.
        val rootWithSlash = "https://dav.example.test/"
        val client = clientWith(book(contacts = mutableListOf(contact("/a.vcf", "e1")))).apply {
            principalErrorUrls += rootWithSlash
        }

        val result = strategy.sync(account, rootWithSlash, client)

        assertTrue("a root-level principal failure is a systemic error", result is ContactPullResult.Error)
        assertEquals(
            "a trailing-slash root is not retried against the trimmed-identical root",
            listOf(rootWithSlash),
            client.discoverPrincipalCalls,
        )
    }

    // ---------- re-discovery ----------

    @Test
    fun `re-discovery picks up a newly created book on a later run`() = runTest {
        val bookA = book(url = "${HOME_URL}bookA/", contacts = mutableListOf(contact("/a.vcf", "ea")))
        val client = clientWith(bookA)

        strategy.sync(account, SERVER_URL, client)
        assertEquals(setOf("/a.vcf"), provider.hrefsFor(ACCOUNT_NAME))

        // Server grows a second book; a re-run must discover and sync it.
        client.books += book(url = "${HOME_URL}bookB/", contacts = mutableListOf(contact("/b.vcf", "eb")))
        val result = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success

        assertEquals("the new book's contact is inserted", 1, result.inserted)
        assertEquals(setOf("/a.vcf", "/b.vcf"), provider.hrefsFor(ACCOUNT_NAME))
        assertEquals("two books persisted", 2, database.addressBookDao().getByAccountIdOnce(accountId).size)
    }

    // ---------- incremental sync-collection (RFC 6578) ----------

    @Test
    fun `initial run with no stored token full-lists, then the next run syncs incrementally`() = runTest {
        val bk = book(contacts = mutableListOf(contact("/a.vcf", "e1")))
        val client = clientWith(bk)

        // Run 1: no stored token -> full listing enumerates and persists the token.
        val first = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success
        assertEquals("first run full-lists and inserts", 1, first.inserted)
        assertEquals("first run used the full-listing path", 1, client.listAllHrefsCalls)
        assertTrue("first run did not probe sync-collection", client.syncCollectionCalls.isEmpty())
        assertEquals("token persisted for the next run", "token-1",
            database.addressBookDao().getByAccountIdOnce(accountId).single().syncToken)

        // Run 2: token now stored -> incremental delta reports a new contact.
        bk.contacts += contact("/b.vcf", "e2")
        bk.syncReports += CalDavResult.success(
            ContactSyncReport(syncToken = "token-2", changed = listOf(ContactSyncItem("/b.vcf", "e2")), deleted = emptyList()),
        )

        val second = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success
        assertEquals("second run inserts only the delta's new contact", 1, second.inserted)
        assertEquals("second run used the incremental path", listOf(BOOK_URL to "token-1"), client.syncCollectionCalls)
        assertEquals("no further full listing on the incremental run", 1, client.listAllHrefsCalls)
        assertEquals("device holds both contacts", setOf("/a.vcf", "/b.vcf"), provider.hrefsFor(ACCOUNT_NAME))
        assertEquals("advanced token persisted", "token-2",
            database.addressBookDao().getByAccountIdOnce(accountId).single().syncToken)
    }

    @Test
    fun `a truncated 507 delta is paged to completion on the returned token`() = runTest {
        // Seed an existing device contact so the device isn't empty: a stored token
        // with a wiped device now self-heals to a full listing, which is a different
        // path — this test exercises delta pagination, the normal populated-device case.
        provider.seed(ACCOUNT_NAME, "/seed.vcf", "e-seed")
        seedStoredToken(BOOK_URL, "token-1")
        val bk = book(contacts = mutableListOf(contact("/a.vcf", "e1"), contact("/b.vcf", "e2")))
        // Page 1 truncated (partial), page 2 completes — RFC 6578 §3.6: the caller
        // MUST re-issue on the returned token until not truncated.
        bk.syncReports += CalDavResult.success(
            ContactSyncReport(syncToken = "token-2", changed = listOf(ContactSyncItem("/a.vcf", "e1")), deleted = emptyList(), truncated = true),
        )
        bk.syncReports += CalDavResult.success(
            ContactSyncReport(syncToken = "token-3", changed = listOf(ContactSyncItem("/b.vcf", "e2")), deleted = emptyList(), truncated = false),
        )
        val client = clientWith(bk)

        val result = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success

        assertEquals("both pages' contacts are inserted", 2, result.inserted)
        assertEquals(
            "the truncation is paged: first on the stored token, then on the returned one",
            listOf(BOOK_URL to "token-1", BOOK_URL to "token-2"),
            client.syncCollectionCalls,
        )
        assertEquals("never falls back to a full listing", 0, client.listAllHrefsCalls)
        assertEquals("device holds both delta contacts plus the pre-existing seed",
            setOf("/seed.vcf", "/a.vcf", "/b.vcf"), provider.hrefsFor(ACCOUNT_NAME))
        assertEquals("the final page's token is persisted", "token-3",
            database.addressBookDao().getByAccountIdOnce(accountId).single().syncToken)
    }

    @Test
    fun `deletes on the incremental path come only from the server delta, never a union sweep`() = runTest {
        // /keep.vcf is on device, unchanged, and NOT in the delta's changed set. A
        // full-enumeration orphan sweep would wrongly delete it (it isn't in the
        // enumerated union); the incremental path must delete ONLY /gone.vcf, which
        // the server explicitly reported removed. This is the incremental-path
        // false-delete fix: a truncated full enumeration can no longer sweep contacts.
        provider.seed(ACCOUNT_NAME, "/gone.vcf", "e-gone")
        provider.seed(ACCOUNT_NAME, "/keep.vcf", "e-keep")
        seedStoredToken(BOOK_URL, "token-1")
        val bk = book(contacts = mutableListOf(contact("/keep.vcf", "e-keep")))
        bk.syncReports += CalDavResult.success(
            ContactSyncReport(syncToken = "token-2", changed = emptyList(), deleted = listOf("/gone.vcf")),
        )
        val client = clientWith(bk)

        val result = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success

        assertEquals("only the server-reported delete is applied", 1, result.deleted)
        assertEquals("exactly the server's delete href is removed", listOf(listOf("/gone.vcf")), provider.deleteCalls)
        assertFalse("the server-removed contact is gone", provider.hrefsFor(ACCOUNT_NAME).contains("/gone.vcf"))
        assertTrue("the unchanged, un-enumerated contact is NOT swept", provider.hrefsFor(ACCOUNT_NAME).contains("/keep.vcf"))
        assertEquals("never full-lists on the incremental path", 0, client.listAllHrefsCalls)
    }

    @Test
    fun `the full-listing path probes the sync-token before enumerating, never after`() = runTest {
        // The persisted token must reflect a server state at or before the
        // enumeration. If it were probed AFTER listAllContactHrefs, a contact
        // created between the listing and the probe would be missing this run yet
        // covered by the token, so the next delta would skip it forever.
        val client = clientWith(book(contacts = mutableListOf(contact("/a.vcf", "e1"))))

        strategy.sync(account, SERVER_URL, client)

        val probe = client.callOrder.indexOf("getSyncToken")
        val list = client.callOrder.indexOf("listAllContactHrefs")
        assertTrue("both the token probe and the listing ran", probe >= 0 && list >= 0)
        assertTrue("the token is probed before the listing, not after", probe < list)
    }

    @Test
    fun `a failed delta delete holds the token so the removal replays next run`() = runTest {
        // The server reports /gone.vcf removed, but the device delete fails
        // (transient provider error). Advancing to the delta's token would step
        // past the server's removed set — the server never re-reports /gone.vcf,
        // so it would be orphaned on the device forever. The token must be held.
        provider.seed(ACCOUNT_NAME, "/gone.vcf", "e-gone")
        seedStoredToken(BOOK_URL, "token-1")
        provider.deleteResult = Result.failure(RuntimeException("provider unavailable"))
        val bk = book(contacts = mutableListOf())
        bk.syncReports += CalDavResult.success(
            ContactSyncReport(syncToken = "token-2", changed = emptyList(), deleted = listOf("/gone.vcf")),
        )
        val client = clientWith(bk)

        val result = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success

        assertEquals("the failed delete counts the book failed", 1, result.booksFailed)
        assertEquals("nothing counts as deleted when the delete failed", 0, result.deleted)
        assertEquals(
            "the stored token is HELD, not advanced past the un-applied removal",
            "token-1",
            database.addressBookDao().getByAccountIdOnce(accountId).single().syncToken,
        )
    }

    @Test
    fun `a stored token with an empty device self-heals to a full listing`() = runTest {
        // The wipe-recovery case the app-side purge hooks can't cover: the contacts
        // were removed out-of-band (user deletes the account in Android Settings, an
        // OS purge, a failed prior write) so the device holds ZERO of this account's
        // contacts, yet the server-side cursor survived. A plain delta against the
        // still-valid token reports only *changes* and would leave the account empty
        // forever. With nothing on the device, the run must full-list and re-fetch
        // every server contact instead of taking the incremental path.
        seedStoredToken(BOOK_URL, "token-1")
        // No provider.seed(...) — the device mirror is empty.
        val bk = book(contacts = mutableListOf(contact("/a.vcf", "e1"), contact("/b.vcf", "e2")))
        val client = clientWith(bk)

        val result = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success

        assertEquals("every server contact is re-inserted", 2, result.inserted)
        assertTrue("the stored token must NOT drive an incremental delta here", client.syncCollectionCalls.isEmpty())
        assertEquals("the empty device forces a full listing", 1, client.listAllHrefsCalls)
        assertEquals("the device is repopulated", setOf("/a.vcf", "/b.vcf"), provider.hrefsFor(ACCOUNT_NAME))
        assertEquals("a fresh token is persisted after the full re-sync", "token-1",
            database.addressBookDao().getByAccountIdOnce(accountId).single().syncToken)
    }

    @Test
    fun `a stored token with contacts still on the device stays on the incremental path`() = runTest {
        // Guard the self-heal's blast radius: when the device DOES hold contacts for
        // the account, a stored token must still take the incremental delta path —
        // the empty-device check must not force a full listing on every normal sync.
        provider.seed(ACCOUNT_NAME, "/a.vcf", "e1")
        seedStoredToken(BOOK_URL, "token-1")
        val bk = book(contacts = mutableListOf(contact("/a.vcf", "e1")))
        bk.syncReports += CalDavResult.success(
            ContactSyncReport(syncToken = "token-2", changed = emptyList(), deleted = emptyList()),
        )
        val client = clientWith(bk)

        val result = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success

        assertEquals("the non-empty device stays incremental", listOf(BOOK_URL to "token-1"), client.syncCollectionCalls)
        assertEquals("no full listing when the device already holds contacts", 0, client.listAllHrefsCalls)
        assertEquals("nothing changed in the delta", 0, result.inserted)
        assertEquals("advanced token persisted", "token-2",
            database.addressBookDao().getByAccountIdOnce(accountId).single().syncToken)
    }

    @Test
    fun `an invalid or expired sync-token falls back to a full listing`() = runTest {
        // Device has a stale contact; the stored token is rejected by the server.
        provider.seed(ACCOUNT_NAME, "/a.vcf", "old-etag")
        seedStoredToken(BOOK_URL, "expired-token")
        val bk = book(contacts = mutableListOf(contact("/a.vcf", "new-etag")))
        // 410 Gone (RFC 6578 §3.6): the token is invalid; the client surfaces it
        // non-retryable so the strategy re-syncs the book from a full listing.
        bk.syncReports += CalDavResult.error(410, "Sync token invalid", isRetryable = false)
        val client = clientWith(bk)

        val result = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success

        assertEquals("the invalid token was probed", listOf(BOOK_URL to "expired-token"), client.syncCollectionCalls)
        assertEquals("it fell back to a full listing", 1, client.listAllHrefsCalls)
        assertEquals("the changed contact is replaced via the full-listing path", 1, result.replaced)
        assertEquals("device etag updated to the server's", "new-etag", provider.etagFor(ACCOUNT_NAME, "/a.vcf"))
        assertEquals("a fresh token is persisted after the full re-sync", "token-1",
            database.addressBookDao().getByAccountIdOnce(accountId).single().syncToken)
    }

    // ---------- an unreadable contact holds the cursor (the R8/parse-skip fix) ----------

    @Test
    fun `a book with an unreadable contact holds its sync-token and re-lists next run`() = runTest {
        // One readable contact, one body the read yields no contact from (junk parses
        // to zero cards in ez-vcard — the testable stand-in for the R8/stripped-ctor
        // failure that returned nothing on release builds). The good contact
        // materializes, but the book is left UNCONFIRMED, so its sync-token must NOT
        // advance. Next run must full-list again and re-fetch the held href rather
        // than orphan it behind a cursor that falsely claims it is synced (RFC 6578 §3.1).
        val badBody = "this is not a vcard at all"
        val bk = book(
            contacts = mutableListOf(
                contact("/good.vcf", "eg"),
                CardDavContactData(href = "/bad.vcf", url = "$BOOK_URL/bad.vcf", etag = "eb", vcardBody = badBody),
            ),
        )
        val client = clientWith(bk)

        val first = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success

        assertEquals("the readable contact still inserts", 1, first.inserted)
        assertEquals("the unreadable contact marks the book failed", 1, first.booksFailed)
        assertEquals(
            "the sync-token is HELD (null), never advanced past the unread contact",
            null,
            database.addressBookDao().getByAccountIdOnce(accountId).single().syncToken,
        )

        // Next run: the held null token forces a full listing again (no delta), and
        // the previously-unreadable href is re-fetched instead of being skipped.
        val listsBefore = client.listAllHrefsCalls
        strategy.sync(account, SERVER_URL, client)

        assertTrue(
            "the held book is re-enumerated next run, not skipped by an incremental delta",
            client.listAllHrefsCalls > listsBefore,
        )
        assertTrue("no incremental probe while the cursor is held", client.syncCollectionCalls.isEmpty())
        assertTrue(
            "the previously-unreadable href is re-fetched next run",
            client.fetchByHrefCalls.any { it.second.contains("/bad.vcf") },
        )
    }

    @Test
    fun `a KIND group contact is confirmed so the book advances its token and never re-lists`() = runTest {
        // A group vCard is a deliberate DROP, not a parse failure — it must NOT hold
        // the book's cursor. Otherwise any book containing a distribution list would
        // re-list forever. A clean run (one real person + one group) advances the
        // token, and the next run takes the cheap incremental path.
        val groupBody =
            "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:team\r\nFN:Team\r\nN:Team;;;;\r\n" +
                "X-ADDRESSBOOKSERVER-KIND:group\r\nEND:VCARD\r\n"
        val bk = book(
            contacts = mutableListOf(
                contact("/alice.vcf", "ea"),
                CardDavContactData(href = "/team.vcf", url = "$BOOK_URL/team.vcf", etag = "et", vcardBody = groupBody),
            ),
        )
        val client = clientWith(bk)

        val first = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success

        assertEquals("only the real person inserts; the group is dropped", 1, first.inserted)
        assertEquals("a group drop is NOT a failure", 0, first.booksFailed)
        assertEquals(
            "the token advances after a clean run (no held cursor for a deliberate drop)",
            "token-1",
            database.addressBookDao().getByAccountIdOnce(accountId).single().syncToken,
        )

        // Next run: the stored token drives the incremental path — the group did not
        // wedge the book into a permanent re-list.
        strategy.sync(account, SERVER_URL, client)

        assertEquals("the book advanced to the incremental path", listOf(BOOK_URL to "token-1"), client.syncCollectionCalls)
        assertEquals("no re-listing after the group-containing run", 1, client.listAllHrefsCalls)
    }

    // ---------- ctag skip on no-sync-token servers ----------

    /**
     * Persist a stored ctag (and no sync-token) for [url] before a run, so the
     * next [ContactPullStrategy.sync] takes the ctag-skip decision on the
     * full-listing path (a book with no token can't drive an incremental delta).
     */
    private suspend fun seedStoredCtag(url: String, ctag: String) {
        database.addressBookDao().upsert(
            AddressBook(accountId = accountId, url = url, displayName = "Contacts", ctag = ctag, syncToken = null),
        )
    }

    @Test
    fun `an unchanged ctag with no sync-token skips enumeration entirely`() = runTest {
        // A no-sync-token server full-lists every run. When the collection ctag is
        // unchanged since the last full-list AND the device still holds contacts,
        // nothing changed — the enumeration is skipped, saving the PROPFIND.
        provider.seed(ACCOUNT_NAME, "/a.vcf", "e1")
        seedStoredCtag(BOOK_URL, "ctag-1")
        val client = clientWith(book(ctag = "ctag-1", contacts = mutableListOf(contact("/a.vcf", "e1"))))

        val result = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success

        assertEquals("no enumeration when ctag unchanged", 0, client.listAllHrefsCalls)
        assertTrue("no incremental probe either (no token)", client.syncCollectionCalls.isEmpty())
        assertEquals("nothing inserted", 0, result.inserted)
        assertEquals("nothing replaced", 0, result.replaced)
        assertEquals("nothing deleted", 0, result.deleted)
        assertTrue("no delete issued", provider.deleteCalls.isEmpty())
        assertEquals("device untouched", setOf("/a.vcf"), provider.hrefsFor(ACCOUNT_NAME))
    }

    @Test
    fun `a changed ctag with no sync-token still full-lists`() = runTest {
        // When the server bumps the ctag, the cheap-skip is off and the book is
        // fully enumerated as before, so real changes are still applied.
        provider.seed(ACCOUNT_NAME, "/a.vcf", "e1")
        seedStoredCtag(BOOK_URL, "ctag-old")
        val client = clientWith(book(ctag = "ctag-new", contacts = mutableListOf(contact("/a.vcf", "e2"))))

        val result = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success

        assertEquals("a changed ctag forces the full listing", 1, client.listAllHrefsCalls)
        assertEquals("the changed contact is replaced", 1, result.replaced)
        assertEquals("device etag updated", "e2", provider.etagFor(ACCOUNT_NAME, "/a.vcf"))
    }

    @Test
    fun `an unchanged ctag with an empty device still full-lists to self-heal`() = runTest {
        // Guard the skip's blast radius: if the device holds NONE of this account's
        // contacts (out-of-band purge) but the stored ctag matches, skipping would
        // leave the account empty forever. The empty device forces a full listing.
        seedStoredCtag(BOOK_URL, "ctag-1")
        // No provider.seed(...) — the device mirror is empty.
        val client = clientWith(book(ctag = "ctag-1", contacts = mutableListOf(contact("/a.vcf", "e1"))))

        val result = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success

        assertEquals("an empty device forces enumeration despite the matching ctag", 1, client.listAllHrefsCalls)
        assertEquals("the server contact is re-inserted", 1, result.inserted)
        assertEquals("device repopulated", setOf("/a.vcf"), provider.hrefsFor(ACCOUNT_NAME))
    }

    @Test
    fun `a ctag-skipped book disables the orphan sweep so other books' contacts survive`() = runTest {
        // Book A is ctag-unchanged (skipped, contributes no hrefs to the union) and
        // Book B enumerates normally. A union sweep would see only Book B's hrefs and
        // wrongly delete Book A's /a.vcf. The skip must disable the sweep, like the
        // delta path, so /a.vcf survives.
        provider.seed(ACCOUNT_NAME, "/a.vcf", "ea")
        provider.seed(ACCOUNT_NAME, "/b.vcf", "eb")
        seedStoredCtag("${HOME_URL}bookA/", "ctag-A")
        val bookA = book(url = "${HOME_URL}bookA/", ctag = "ctag-A", contacts = mutableListOf(contact("/a.vcf", "ea")))
        val bookB = book(url = "${HOME_URL}bookB/", ctag = "ctag-B", contacts = mutableListOf(contact("/b.vcf", "eb")))
        val client = clientWith(bookA, bookB)

        val result = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success

        assertEquals("book A skipped, book B enumerated", 1, client.listAllHrefsCalls)
        assertEquals("no orphan sweep when a book was ctag-skipped", 0, result.deleted)
        assertTrue("no delete issued", provider.deleteCalls.isEmpty())
        assertEquals("both books' contacts survive", setOf("/a.vcf", "/b.vcf"), provider.hrefsFor(ACCOUNT_NAME))
    }

    // ---------- deferred photo fetch is wired into the pull ----------

    @Test
    fun `a pending photo is fetched and written at the end of the pull`() = runTest {
        // A contact already on device carries the photo-pending flag; its book lists
        // it with a URL photo. The pull's deferred fetch step must download and write
        // the photo (proving fetchPending runs with the discovered book + client).
        val photoUrl = "https://gateway.icloud.com/photo/a.jpg"
        val urlPhotoBody =
            "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:a\r\nFN:A\r\nN:A;;;;\r\nPHOTO;VALUE=URI:$photoUrl\r\nEND:VCARD\r\n"
        // A real contact href lives UNDER its collection path, which is what the
        // deferred fetch's book-grouping matches on.
        val href = "/ab/default/a.vcf"
        val bk = book(
            contacts = mutableListOf(
                CardDavContactData(href = href, url = "$BOOK_URL$href", etag = "e1", vcardBody = urlPhotoBody),
            ),
        )
        val client = clientWith(bk)
        client.photoResults[photoUrl] = CalDavResult.success(
            org.onekash.kashcal.sync.carddav.model.PhotoBytes(byteArrayOf(7, 7, 7), "image/jpeg"),
        )
        provider.seedPendingPhoto(ACCOUNT_NAME, href)

        strategy.sync(account, SERVER_URL, client)

        assertEquals("the pending photo URL was fetched", listOf(photoUrl), client.fetchPhotoCalls)
        assertArrayEquals("the photo bytes were written", byteArrayOf(7, 7, 7), provider.writtenPhotoFor(ACCOUNT_NAME, href))
        assertFalse("the pending flag is cleared", provider.isPhotoPending(ACCOUNT_NAME, href))
    }

    @Test
    fun `a pull with no pending photos never touches the photo fetch path`() = runTest {
        val client = clientWith(book(contacts = mutableListOf(contact("/a.vcf", "e1"))))

        strategy.sync(account, SERVER_URL, client)

        assertTrue("no photo GET issued when nothing is pending", client.fetchPhotoCalls.isEmpty())
    }

    // ---------- iCloud self-href on the delta path ----------

    @Test
    fun `the collection self-href in a delta is ignored, never held as unreadable`() = runTest {
        // iCloud's sync-collection REPORT lists the collection ITSELF in its changed
        // set, without a trailing slash and with no resourcetype, so it slips past the
        // parser's self-row filter into the requested href set. The multiget drops it
        // (a non-contact collection href 400s the whole batch), so it never yields a
        // contact. It must NOT be counted unreadable: otherwise the book would hold its
        // cursor forever AND booksFailed>0 would permanently disable the orphan sweep.
        // This reproduces the regression at the delta path, where iCloud actually
        // delivers it — the unit layer was previously blind to it because the fake and
        // the example tests only ever used clean member hrefs.
        provider.seed(ACCOUNT_NAME, "/keep.vcf", "e-keep")
        seedStoredToken(BOOK_URL, "token-1")
        val bk = book(contacts = mutableListOf(contact("/keep.vcf", "e-keep"), contact("/new.vcf", "e-new")))
        // The slashless collection self-href, exactly as iCloud reports it.
        val selfHref = BOOK_URL.trimEnd('/')
        bk.syncReports += CalDavResult.success(
            ContactSyncReport(
                syncToken = "token-2",
                changed = listOf(ContactSyncItem(selfHref, null), ContactSyncItem("/new.vcf", "e-new")),
                deleted = emptyList(),
            ),
        )
        val client = clientWith(bk)

        val result = strategy.sync(account, SERVER_URL, client) as ContactPullResult.Success

        assertEquals("the real new contact is inserted", 1, result.inserted)
        assertEquals("the self-href does not fail the book", 0, result.booksFailed)
        assertEquals(
            "the token advances — the self-href never holds the cursor",
            "token-2",
            database.addressBookDao().getByAccountIdOnce(accountId).single().syncToken,
        )

        // Next run: the book stays on the cheap incremental path; the self-href never
        // wedged it into a permanent re-list, and the device matches the server.
        strategy.sync(account, SERVER_URL, client)
        assertEquals(
            "stays on the incremental path across runs",
            listOf(BOOK_URL to "token-1", BOOK_URL to "token-2"),
            client.syncCollectionCalls,
        )
        assertEquals("device converged to the server set", setOf("/keep.vcf", "/new.vcf"), provider.hrefsFor(ACCOUNT_NAME))
    }

    // ---------- model-based convergence (the poisoned-cursor class) ----------

    @Test
    fun `random run sequences with transient failures always converge device to server`() = runTest {
        // Property guard for the poisoned-cursor class: no matter how server mutations
        // and transient per-run read failures interleave, once the system quiesces the
        // device MUST equal the server (RFC 6578 §3.1 — keep syncing until reconciled).
        // A cursor that ever advances past a contact the run didn't actually retrieve
        // breaks this: on the incremental path the delta never re-reports it, so it is
        // orphaned permanently. This drives that path with a small model of a real
        // sync-collection server, which re-delivers the same changes on an un-advanced
        // token — so a correctly HELD cursor heals and a wrongly ADVANCED one orphans.
        val rng = java.util.Random(20260809L)

        // The server's current truth (href -> etag) and each contact's body. /c1.vcf is
        // pinned present so the device never fully empties (an empty device would force
        // the self-heal full-listing path, off-model for this incremental property).
        val server = LinkedHashMap<String, String>()
        val bodies = HashMap<String, String>()
        val hrefs = (1..6).map { "/c$it.vcf" }
        fun put(href: String) {
            server[href] = "e-${href.drop(1)}-${rng.nextInt(100000)}"
            bodies[href] = vcard(href, "Person $href")
        }
        put("/c1.vcf")

        // The (token, state) the client last acknowledged by persisting an advanced
        // token. Deltas are recomputed from here every run, so a held token re-delivers.
        var ackedToken: String? = "token-0"
        var ackedState = HashMap(server)
        var tokenSeq = 0

        val bk = book(ctag = null)
        // The initial full listing persists this token, putting the book on the delta
        // path; delta tokens (token-1, token-2, ...) advance from there.
        val client = clientWith(bk, syncToken = "token-0")

        fun reflectServerIntoBook() {
            bk.contacts.clear()
            server.forEach { (h, e) -> bk.contacts += CardDavContactData(h, "$BOOK_URL$h", e, bodies[h]!!) }
        }

        // Establish the initial cursor via a full listing.
        reflectServerIntoBook()
        strategy.sync(account, SERVER_URL, client)

        fun programDelta(injectFailure: Boolean) {
            reflectServerIntoBook()
            // What a real server reports for the client's current (possibly held) token:
            // everything changed since ackedState, plus everything removed since then.
            val changed = server.filter { (h, e) -> ackedState[h] != e }.map { ContactSyncItem(it.key, it.value) }
            val deleted = (ackedState.keys - server.keys).toList()
            // Corrupt ONE changed body so the reader yields no contact for it: the run
            // must hold (not advance), and the next run re-delivers and heals.
            if (injectFailure && changed.isNotEmpty()) {
                val victim = changed[rng.nextInt(changed.size)].href
                bk.contacts.replaceAll { if (it.href == victim) it.copy(vcardBody = "not a vcard") else it }
            }
            bk.syncReports.clear()
            bk.syncReports += CalDavResult.success(
                ContactSyncReport(syncToken = "token-${++tokenSeq}", changed = changed, deleted = deleted),
            )
        }

        suspend fun runOnceThenReconcileAck() {
            strategy.sync(account, SERVER_URL, client)
            // If the persisted token advanced, the run fully reconciled to the current
            // server state; snapshot it as the new acked baseline. If it was held, the
            // baseline is unchanged and the next delta re-delivers.
            val stored = database.addressBookDao().getByAccountIdOnce(accountId).single().syncToken
            if (stored != ackedToken) {
                ackedToken = stored
                ackedState = HashMap(server)
            }
        }

        repeat(20) {
            // 1-2 mutations: add/modify any href, or delete one of c2..c6 (c1 pinned).
            repeat(1 + rng.nextInt(2)) {
                if (rng.nextInt(4) == 0) {
                    val victim = hrefs.drop(1)[rng.nextInt(hrefs.size - 1)]
                    server.remove(victim)
                } else {
                    put(hrefs[rng.nextInt(hrefs.size)])
                }
            }
            programDelta(injectFailure = rng.nextInt(10) < 4)
            runOnceThenReconcileAck()
        }

        // Quiesce: stop mutating and injecting failures, and let it settle. A correct
        // cursor converges within one clean run; give it a few for any held tail.
        repeat(4) {
            programDelta(injectFailure = false)
            runOnceThenReconcileAck()
        }

        assertTrue("the server must be non-empty for a meaningful convergence check", server.isNotEmpty())
        assertEquals(
            "after settling, the device href set must equal the server's — no orphan left behind a cursor",
            server.keys,
            provider.hrefsFor(ACCOUNT_NAME),
        )
        server.forEach { (href, etag) ->
            assertEquals("device etag for $href must match the server", etag, provider.etagFor(ACCOUNT_NAME, href))
        }
    }

    private companion object {
        const val ACCOUNT_NAME = "alice@example.test"
        const val SERVER_URL = "https://dav.example.test/"
        const val HOME_URL = "https://dav.example.test/ab/"
        const val BOOK_URL = "https://dav.example.test/ab/default/"
    }
}
