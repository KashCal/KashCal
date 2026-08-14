package org.onekash.kashcal.sync.carddav

import org.onekash.kashcal.sync.carddav.model.CardDavAddressBook
import org.onekash.kashcal.sync.carddav.model.CardDavContactData
import org.onekash.kashcal.sync.carddav.model.ContactSyncReport
import org.onekash.kashcal.sync.carddav.model.PhotoBytes
import org.onekash.kashcal.sync.client.model.CalDavResult

/**
 * A fake address book for [FakeCardDavClient]: the collection metadata the client
 * discovers plus the contacts it holds. The pull strategy enumerates hrefs one
 * book at a time and multigets them, so the fake keeps contacts grouped by book
 * — this is what lets a test model two books, a newly-appearing book, or a book
 * that fails to enumerate (so the orphan sweep is skipped for it) faithfully.
 *
 * @property book the collection as [FakeCardDavClient.listAddressBooks] returns it.
 * @property contacts the resources under this collection, resolved by
 *   [FakeCardDavClient.listAllContactHrefs] and [FakeCardDavClient.fetchContactsByHref].
 * @property listError when set, [FakeCardDavClient.listAllContactHrefs] returns
 *   this instead of the hrefs — models a per-book enumerate failure.
 * @property syncReports programmed [FakeCardDavClient.syncCollection] responses
 *   for the incremental path, consumed one per call in order — this is what lets
 *   a test model 507 paging (a run of truncated pages followed by a final
 *   non-truncated one), a server-reported delete, or a token-invalid error. When
 *   the queue is empty the client returns a coherent empty delta (nothing changed
 *   since the caller's token), so an incremental probe of an unchanged book is a
 *   no-op rather than an error.
 */
class FakeAddressBook(
    val book: CardDavAddressBook,
    val contacts: MutableList<CardDavContactData> = mutableListOf(),
    var listError: CalDavResult.Error? = null,
    val syncReports: ArrayDeque<CalDavResult<ContactSyncReport>> = ArrayDeque(),
)

/**
 * The canonical fake of the [CardDavClient] read surface — one shared test double
 * for the interface (project convention), data-bearing on every method a test
 * needs rather than a relaxed mock, so a silent wrong-stub can't hide behind a
 * green suite.
 *
 * Two usage shapes, both programmable:
 *  - **Reader tests** construct it with a flat [bodies] pool (+ optional
 *    [fetchError]) and exercise only [fetchContactsByHref]; the discovery methods
 *    return harmless defaults.
 *  - **Pull-strategy tests** populate [books] with [FakeAddressBook]s (each with
 *    its own contacts), set the discovery chain vars, and drive a full sync.
 *    [fetchContactsByHref] resolves an href against every book's contacts plus the
 *    loose [bodies] pool, mirroring how multiget resolves an href list regardless
 *    of which collection enumerated it.
 *
 * The incremental [syncCollection] path is programmed per book via
 * [FakeAddressBook.syncReports] (consumed in order, one per call). When a book's
 * queue is empty the client returns a coherent empty delta echoing the caller's
 * token — an incremental probe of an unchanged book is a no-op, not an error — so
 * a full-sync test whose book later carries a persisted token stays a no-op on the
 * incremental leg rather than spuriously failing.
 */
class FakeCardDavClient(
    bodies: List<CardDavContactData> = emptyList(),
    private val fetchError: CalDavResult.Error? = null,
) : CardDavClient {

    /** Contacts not attached to a [FakeAddressBook] — the reader-test shape. */
    private val looseBodies: MutableList<CardDavContactData> = bodies.toMutableList()

    /** Address books this login exposes; populate for pull-strategy tests. */
    val books: MutableList<FakeAddressBook> = mutableListOf()

    // ---------- programmable discovery chain ----------

    /** [discoverWellKnown] result; null echoes the input server URL. */
    var wellKnownUrl: String? = null

    /** [discoverPrincipal] result. */
    var principalUrl: String = "https://dav.example.test/principals/me/"

    /**
     * Server URLs for which [discoverPrincipal] returns an error instead of
     * [principalUrl] — models a stale discovered context path (a TXT `path=` the
     * server no longer honors) so a test can assert the caller retries at the host
     * root. Every [discoverPrincipal] argument is recorded in [discoverPrincipalCalls].
     */
    val principalErrorUrls: MutableSet<String> = mutableSetOf()

    /** Every [discoverWellKnown] server URL, in call order. Lets a test assert the
     *  well-known step was SKIPPED when discovery seeds from a stored principal. */
    val discoverWellKnownCalls: MutableList<String> = mutableListOf()

    /** Every [discoverPrincipal] server URL, in call order. */
    val discoverPrincipalCalls: MutableList<String> = mutableListOf()

    /** Every [discoverAddressBookHome] principal URL, in call order. Lets a test
     *  assert the home-set was PROPFINDed directly against the seeded principal. */
    val discoverAddressBookHomeCalls: MutableList<String> = mutableListOf()

    /**
     * Principal URLs for which [discoverAddressBookHome] returns an error instead of
     * [addressBookHomes] — models a principal that carries no `addressbook-home-set`
     * (the empty-home-set 500 the real client returns) so a test can assert the
     * seed path falls through to the well-known chain rather than failing.
     */
    val addressBookHomeErrorUrls: MutableSet<String> = mutableSetOf()

    /** [discoverAddressBookHome] result (RFC allows more than one home). */
    val addressBookHomes: MutableList<String> = mutableListOf("https://dav.example.test/ab/")

    /** [getSyncToken] result — the token a full-sync run persists for next time. */
    var syncToken: String? = null

    /**
     * When set, [listAddressBooks] returns this instead of the book list — models
     * a home-set that fails to enumerate during discovery (distinct from a book
     * that fails its per-href listing, which is [FakeAddressBook.listError]).
     */
    var listAddressBooksError: CalDavResult.Error? = null

    // ---------- call tracking (reader tests assert these) ----------

    var fetchCalls = 0
        private set

    /** Size of each [fetchContactsByHref] call, in call order — lets tests assert batching. */
    val batchSizes: MutableList<Int> = mutableListOf()

    /**
     * Every [fetchContactsByHref] call's (addressBookUrl, hrefs), in call order.
     * Lets a test assert that a multiget targeted the RIGHT collection with the
     * RIGHT hrefs — e.g. the photo fetcher grouping pending hrefs back to their
     * own book so each re-read hits the correct address book.
     */
    val fetchByHrefCalls = mutableListOf<Pair<String, List<String>>>()

    /**
     * Count of calls to any method OTHER than [fetchContactsByHref]. A reader test
     * asserts this stays 0 to prove the reader touches only the fetch surface —
     * the seam guard the old per-test fake enforced by throwing on those methods.
     */
    var nonFetchCalls = 0
        private set

    /**
     * Names of the discovery/enumeration methods invoked, in call order. Lets a
     * test assert *ordering* between methods (e.g. the full-listing path must probe
     * [getSyncToken] BEFORE [listAllContactHrefs], so the persisted token can never
     * reflect a server state newer than this run's enumeration).
     */
    val callOrder = mutableListOf<String>()

    // ========== Discovery ==========

    override suspend fun discoverWellKnown(serverUrl: String): CalDavResult<String> {
        nonFetchCalls++
        discoverWellKnownCalls += serverUrl
        return CalDavResult.success(wellKnownUrl ?: serverUrl)
    }

    override suspend fun discoverPrincipal(serverUrl: String): CalDavResult<String> {
        nonFetchCalls++
        discoverPrincipalCalls += serverUrl
        if (serverUrl in principalErrorUrls) {
            return CalDavResult.error(404, "no principal at $serverUrl")
        }
        return CalDavResult.success(principalUrl)
    }

    override suspend fun discoverAddressBookHome(principalUrl: String): CalDavResult<List<String>> {
        nonFetchCalls++
        discoverAddressBookHomeCalls += principalUrl
        if (principalUrl in addressBookHomeErrorUrls) {
            return CalDavResult.error(500, "no addressbook-home-set at $principalUrl")
        }
        return CalDavResult.success(addressBookHomes.toList())
    }

    override suspend fun listAddressBooks(addressBookHomeUrl: String): CalDavResult<List<CardDavAddressBook>> {
        nonFetchCalls++
        return listAddressBooksError ?: CalDavResult.success(books.map { it.book })
    }

    // ========== Change Detection ==========

    override suspend fun getCtag(addressBookUrl: String): CalDavResult<String?> {
        nonFetchCalls++
        return CalDavResult.success(bookByUrl(addressBookUrl)?.book?.ctag)
    }

    override suspend fun getSyncToken(addressBookUrl: String): CalDavResult<String?> {
        nonFetchCalls++
        callOrder += "getSyncToken"
        return CalDavResult.success(syncToken)
    }

    // ========== Fetching ==========

    /** Every [syncCollection] call's (addressBookUrl, token) argument, in call order. */
    val syncCollectionCalls = mutableListOf<Pair<String, String?>>()

    override suspend fun syncCollection(
        addressBookUrl: String,
        syncToken: String?,
    ): CalDavResult<ContactSyncReport> {
        nonFetchCalls++
        syncCollectionCalls += addressBookUrl to syncToken
        val book = bookByUrl(addressBookUrl)
        val programmed = book?.syncReports?.removeFirstOrNull()
        if (programmed != null) return programmed
        // No programmed report left: model an unchanged book — nothing changed
        // since the caller's token, and echo it back so the strategy persists the
        // same cursor. An unchanged incremental probe must be a no-op, not an error.
        return CalDavResult.success(
            ContactSyncReport(syncToken = syncToken, changed = emptyList(), deleted = emptyList()),
        )
    }

    /** Count of [listAllContactHrefs] calls — a book synced via the incremental
     *  delta must never hit this full-enumeration path (the false-delete invariant). */
    var listAllHrefsCalls = 0
        private set

    override suspend fun listAllContactHrefs(addressBookUrl: String): CalDavResult<List<Pair<String, String?>>> {
        nonFetchCalls++
        listAllHrefsCalls++
        callOrder += "listAllContactHrefs"
        val book = bookByUrl(addressBookUrl) ?: return CalDavResult.success(emptyList())
        book.listError?.let { return it }
        return CalDavResult.success(book.contacts.map { it.href to it.etag })
    }

    override suspend fun fetchContactsByHref(
        addressBookUrl: String,
        hrefs: List<String>,
        vcardVersion: String,
    ): CalDavResult<List<CardDavContactData>> {
        fetchCalls++
        batchSizes += hrefs.size
        fetchByHrefCalls += addressBookUrl to hrefs
        fetchError?.let { return it }
        val pool = looseBodies + books.flatMap { it.contacts }
        // Resolve requested hrefs by exact string, mirroring the stable-href identity
        // the whole sync path assumes (device-etag map, write grouping, delete-by-href
        // all key on the raw href). A requested href with no matching body — the
        // collection self-href, a deleted contact, an href the server omitted — is
        // simply absent from the response, exactly as the real multiget behaves.
        return CalDavResult.success(pool.filter { it.href in hrefs })
    }

    // ---------- photo fetch (programmable per URL) ----------

    /**
     * Programmed [fetchPhoto] results keyed by the exact photo URL. A URL absent
     * from the map returns [defaultPhotoResult] — models a foreign-host refuse (or
     * any generic failure) without the test enumerating every URL. Photo fetch
     * tests seed the URLs they expect and assert on [fetchPhotoCalls].
     */
    val photoResults: MutableMap<String, CalDavResult<PhotoBytes>> = mutableMapOf()

    /** Returned by [fetchPhoto] for a URL not present in [photoResults]. */
    var defaultPhotoResult: CalDavResult<PhotoBytes> =
        CalDavResult.error(0, "no photo programmed", isRetryable = false)

    /** Every photo URL passed to [fetchPhoto], in call order. */
    val fetchPhotoCalls = mutableListOf<String>()

    override suspend fun fetchPhoto(photoUrl: String): CalDavResult<PhotoBytes> {
        fetchPhotoCalls += photoUrl
        return photoResults[photoUrl] ?: defaultPhotoResult
    }

    private fun bookByUrl(url: String): FakeAddressBook? =
        books.firstOrNull { it.book.url == url || it.book.href == url }
}
