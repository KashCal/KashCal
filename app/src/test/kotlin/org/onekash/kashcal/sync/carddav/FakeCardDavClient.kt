package org.onekash.kashcal.sync.carddav

import org.onekash.kashcal.sync.carddav.model.CardDavAddressBook
import org.onekash.kashcal.sync.carddav.model.CardDavContactData
import org.onekash.kashcal.sync.carddav.model.ContactDeleteResult
import org.onekash.kashcal.sync.carddav.model.ContactPrecondition
import org.onekash.kashcal.sync.carddav.model.ContactSyncReport
import org.onekash.kashcal.sync.carddav.model.ContactUploadResult
import org.onekash.kashcal.sync.carddav.model.PhotoBytes
import org.onekash.kashcal.sync.client.model.CalDavResult

/**
 * Holds one fake address book for [FakeCardDavClient]: the collection metadata and its contacts.
 *
 * The pull strategy lists hrefs one book at a time, so contacts are grouped by book. That lets a
 * test model two books, a book that appears later, or a book that fails to list (which skips the
 * orphan sweep).
 *
 * @property book the collection as [FakeCardDavClient.listAddressBooks] returns it.
 * @property contacts the resources under this collection, served by
 *   [FakeCardDavClient.listAllContactHrefs] and [FakeCardDavClient.fetchContactsByHref].
 * @property listError when set, [FakeCardDavClient.listAllContactHrefs] returns this instead of
 *   the hrefs: a per-book listing failure.
 * @property syncReports programmed [FakeCardDavClient.syncCollection] replies for the delta,
 *   consumed one per call in order: 507 paging (truncated pages, then a final one), a
 *   server-reported delete, or a token-invalid error. When the queue is empty the client returns
 *   an empty delta echoing the caller's token, so a delta on an unchanged book is a no-op, not an
 *   error.
 */
class FakeAddressBook(
    val book: CardDavAddressBook,
    val contacts: MutableList<CardDavContactData> = mutableListOf(),
    var listError: CalDavResult.Error? = null,
    val syncReports: ArrayDeque<CalDavResult<ContactSyncReport>> = ArrayDeque(),
)

/**
 * Fakes every [CardDavClient] method: the one shared test double for the interface.
 *
 * Every method returns programmed data, not a relaxed default, so a wrong stub can't hide behind
 * a green suite. Two usage shapes:
 *  - Reader tests pass a flat [bodies] pool (and optionally [fetchError]) and call only
 *    [fetchContactsByHref]; the discovery methods return harmless defaults.
 *  - Pull-strategy tests fill [books] with [FakeAddressBook]s, set the discovery chain vars and
 *    drive a sync. [fetchContactsByHref] resolves an href against every book's contacts plus the
 *    loose [bodies] pool, whichever collection listed it.
 *
 * [syncCollection] replies are programmed per book ([FakeAddressBook.syncReports]). With an empty
 * queue it returns an empty delta echoing the caller's token, so a full-sync test whose book
 * later carries a stored token stays a no-op on the delta instead of failing.
 */
class FakeCardDavClient(
    bodies: List<CardDavContactData> = emptyList(),
    private val fetchError: CalDavResult.Error? = null,
) : CardDavClient {

    /** Contacts not attached to a [FakeAddressBook], for reader tests. */
    private val looseBodies: MutableList<CardDavContactData> = bodies.toMutableList()

    /** Address books this login exposes; populate for pull-strategy tests. */
    val books: MutableList<FakeAddressBook> = mutableListOf()

    // ---------- programmable discovery chain ----------

    /** [discoverWellKnown] result; null echoes the input server URL. */
    var wellKnownUrl: String? = null

    /** When set, [discoverWellKnown] answers this error instead. */
    var wellKnownError: CalDavResult.Error? = null

    /** [discoverPrincipal] result. */
    var principalUrl: String = "https://dav.example.test/principals/me/"

    /**
     * Server URLs for which [discoverPrincipal] returns a 404 instead of [principalUrl]. Models a
     * stale discovered context path (a TXT `path=` the server no longer honors) so a test can
     * assert the caller retries at the host root. Arguments are recorded in
     * [discoverPrincipalCalls].
     */
    val principalErrorUrls: MutableSet<String> = mutableSetOf()

    /**
     * Every [discoverWellKnown] server URL, in call order. Lets a test assert the well-known step
     * is skipped when discovery seeds from a stored principal.
     */
    val discoverWellKnownCalls: MutableList<String> = mutableListOf()

    /** Every [discoverPrincipal] server URL, in call order. */
    val discoverPrincipalCalls: MutableList<String> = mutableListOf()

    /**
     * Every [discoverAddressBookHome] principal URL, in call order. Lets a test assert the
     * home-set was requested directly from the seeded principal.
     */
    val discoverAddressBookHomeCalls: MutableList<String> = mutableListOf()

    /**
     * Principal URLs for which [discoverAddressBookHome] returns a 500 instead of
     * [addressBookHomes]. Models a principal with no `addressbook-home-set` (the real client
     * returns 500 for an empty home-set) so a test can assert the seed path falls through to the
     * well-known chain.
     */
    val addressBookHomeErrorUrls: MutableSet<String> = mutableSetOf()

    /** [discoverAddressBookHome] result (RFC allows more than one home). */
    val addressBookHomes: MutableList<String> = mutableListOf("https://dav.example.test/ab/")

    /** [getSyncToken] result: the token a full listing stores for next time. */
    var syncToken: String? = null

    /**
     * When set, [listAddressBooks] returns this instead of the book list: a home-set that fails
     * to list during discovery. A book that fails its href listing is [FakeAddressBook.listError].
     */
    var listAddressBooksError: CalDavResult.Error? = null

    // ---------- call tracking (reader tests assert these) ----------

    var fetchCalls = 0
        private set

    /** Size of each [fetchContactsByHref] call, in call order, so tests can assert batching. */
    val batchSizes: MutableList<Int> = mutableListOf()

    /**
     * Every [fetchContactsByHref] call's (addressBookUrl, hrefs), in call order. Lets a test
     * assert a multiget targeted the right collection with the right hrefs, for example the photo
     * fetcher grouping pending hrefs back to their own book.
     */
    val fetchByHrefCalls = mutableListOf<Pair<String, List<String>>>()

    /**
     * Counts calls to every method except [fetchContactsByHref] and [fetchPhoto]. A reader test
     * asserts it stays 0 to prove the reader touches only the fetch surface.
     */
    var nonFetchCalls = 0
        private set

    /**
     * Names of the [getSyncToken] and [listAllContactHrefs] calls, in call order. Lets a test
     * assert the full listing takes the token before listing, so the stored token never reflects
     * a server state newer than the listing.
     */
    val callOrder = mutableListOf<String>()

    // ========== Discovery ==========

    override suspend fun discoverWellKnown(serverUrl: String): CalDavResult<String> {
        nonFetchCalls++
        discoverWellKnownCalls += serverUrl
        wellKnownError?.let { return it }
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
        // No programmed report left: an unchanged book. Echo the caller's token so the
        // strategy stores the same sync-token; a delta on an unchanged book is a no-op.
        return CalDavResult.success(
            ContactSyncReport(syncToken = syncToken, changed = emptyList(), deleted = emptyList()),
        )
    }

    /**
     * Counts [listAllContactHrefs] calls. A book synced by delta must never take this full
     * listing, which is what keeps a truncated listing from deleting contacts.
     */
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
        // Match hrefs by exact string: the sync path keys the device-etag map, write
        // grouping and delete-by-href on the raw href. A requested href with no body (the
        // collection self-href, a deleted contact, an href the server omitted) is absent
        // from the reply, as with a real multiget.
        return CalDavResult.success(pool.filter { it.href in hrefs })
    }

    // ---------- photo fetch (programmable per URL) ----------

    /**
     * Programmed [fetchPhoto] results keyed by the exact photo URL. A URL absent from the map
     * returns [defaultPhotoResult], which models a foreign-host refusal or any other failure
     * without the test listing every URL. Photo tests seed the URLs they expect and assert on
     * [fetchPhotoCalls].
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

    // ---------- write verbs (programmable) ----------

    /** Every [putContact] call's (resourceUrl, vcardBody, precondition), in order. */
    val putContactCalls = mutableListOf<Triple<String, String, ContactPrecondition>>()

    /** Every [deleteContact] call's (resourceUrl, etag), in order. */
    val deleteContactCalls = mutableListOf<Pair<String, String>>()

    /** [putContact] result; defaults to a success echoing no server etag. */
    var putContactResult: ContactUploadResult = ContactUploadResult.Success(etag = null)

    /** [deleteContact] result; defaults to a clean delete. */
    var deleteContactResult: ContactDeleteResult = ContactDeleteResult.Deleted

    /**
     * When set, [putContact] throws this instead of returning, to exercise a caller's exception
     * handling (for example, cancellation must propagate, not be swallowed as a failed item).
     */
    var putContactThrows: Throwable? = null

    override suspend fun putContact(
        resourceUrl: String,
        vcardBody: String,
        precondition: ContactPrecondition,
    ): ContactUploadResult {
        nonFetchCalls++
        putContactCalls += Triple(resourceUrl, vcardBody, precondition)
        putContactThrows?.let { throw it }
        return putContactResult
    }

    override suspend fun deleteContact(resourceUrl: String, etag: String): ContactDeleteResult {
        nonFetchCalls++
        deleteContactCalls += resourceUrl to etag
        return deleteContactResult
    }

    private fun bookByUrl(url: String): FakeAddressBook? =
        books.firstOrNull { it.book.url == url || it.book.href == url }
}
