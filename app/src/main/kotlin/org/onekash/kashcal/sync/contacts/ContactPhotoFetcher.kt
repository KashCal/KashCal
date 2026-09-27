package org.onekash.kashcal.sync.contacts

import android.util.Log
import org.onekash.kashcal.data.contacts.VCardContactMapper
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.carddav.CardDavContactReader
import org.onekash.kashcal.sync.carddav.model.CardDavAddressBook
import org.onekash.kashcal.sync.client.model.CalDavResult
import javax.inject.Inject

/**
 * Drains the photo-pending worklist after a contact pull. For every RawContact
 * [ContactsProviderRepository.pendingPhotoSourceIds] returns, it re-reads the vCard, recovers
 * the remote-URL photo, downloads it over the account's authenticated client, and writes it as
 * a Photo blob while clearing the pending flag.
 *
 * Why a second pass and not a fetch during the pull:
 *  - The pending-row query doesn't depend on the server delta, so a photo whose download
 *    failed transiently on an earlier run is retried on the next sync, delta included,
 *    without a full listing.
 *  - The network fetch stays out of the pull's insert and replace writes, so a slow or failing
 *    gateway can't stall or partially fail the device copy.
 *
 * The photo URL is never persisted: a multiget of only the pending hrefs recovers it from
 * [org.onekash.kashcal.data.contacts.MappedContact.photoUrl]. This keeps the DSID-bearing
 * iCloud gateway URL, an account-identifying value, out of the local database.
 *
 * Per pending contact:
 *  - URL recovered: [CardDavClient.fetchPhoto], then on success
 *    [ContactsProviderRepository.writePhotoAndClearPending]. Failure handling is on
 *    [fetchAndWrite].
 *  - No URL on re-read (the photo was removed on the server, or became inline and was written
 *    by the pull): [ContactsProviderRepository.clearPhotoPending], so a stale flag isn't
 *    retried forever.
 *  - Href in no discovered book (its home failed to list this run, or the book was removed):
 *    left pending, never fetched against the wrong collection.
 *
 * With nothing pending this costs one provider query; the multiget runs only for inserted or
 * changed URL-photo contacts and those still retrying.
 *
 * Runs inside [ContactSyncWorker]'s process-wide lock, after the pull's writes, so nothing
 * else changes the pending flags while it reads them.
 */
class ContactPhotoFetcher @Inject constructor(
    private val contactsProvider: ContactsProviderRepository,
) {

    /**
     * Fetches and writes every pending photo for [accountName], reading vCards through [client]
     * (which carries the account's credentials) from [books], this run's discovered address
     * books. A no-op when nothing is pending. Never throws, so a photo failure never fails the
     * sync: each failure is logged, and the contact stays pending unless [fetchAndWrite] judges
     * it permanent.
     */
    suspend fun fetchPending(
        accountName: String,
        books: List<CardDavAddressBook>,
        client: CardDavClient,
    ) {
        val pending = contactsProvider.pendingPhotoSourceIds(accountName)
        if (pending.isEmpty()) return

        val reader = CardDavContactReader(client)

        // Group each pending href under the book whose path is its prefix, so the re-read
        // multiget targets the right collection. An href in no book stays pending.
        val byBook = HashMap<CardDavAddressBook, MutableList<String>>()
        for (href in pending) {
            val book = bookForHref(href, books)
            if (book == null) {
                Log.w(TAG, "Pending photo href resolves to no discovered book; left pending")
                continue
            }
            byBook.getOrPut(book) { ArrayList() }.add(href)
        }

        for ((book, hrefs) in byBook) {
            // Keeps fetchPending from throwing when a collaborator throws instead of
            // returning its CalDavResult or Result: the book stays pending and the contact
            // sync goes on.
            try {
                fetchBook(accountName, book, hrefs, reader, client)
            } catch (e: Exception) {
                Log.w(TAG, "Photo fetch failed unexpectedly for book '${book.displayName}'; left pending", e)
            }
        }
    }

    /** Re-reads [hrefs] in [book], then fetches and writes, or clears, each recovered photo. */
    private suspend fun fetchBook(
        accountName: String,
        book: CardDavAddressBook,
        hrefs: List<String>,
        reader: CardDavContactReader,
        client: CardDavClient,
    ) {
        when (val read = reader.readContacts(book.url, hrefs, book.vcardVersion)) {
            is CalDavResult.Success -> {
                // href -> recovered photo URL, null when the re-read has no URL photo. A body
                // can hold several vCards; the first non-null URL for the href wins.
                val urlByHref = HashMap<String, String?>()
                for (rc in read.data.contacts) {
                    val url = VCardContactMapper.toEntity(rc.contact).photoUrl
                    if (urlByHref[rc.href] == null) urlByHref[rc.href] = url
                }
                for (href in hrefs) {
                    // An href the re-read didn't return (deleted since the pull) stays
                    // pending: a later pull removes the RawContact, so clearing is moot.
                    if (!urlByHref.containsKey(href)) {
                        Log.w(TAG, "Pending contact absent on re-read; left pending")
                        continue
                    }
                    val photoUrl = urlByHref[href]
                    if (photoUrl == null) {
                        // Stale flag: the URL photo was removed or is now inline.
                        contactsProvider.clearPhotoPending(accountName, href)
                    } else {
                        fetchAndWrite(accountName, href, photoUrl, client)
                    }
                }
            }
            is CalDavResult.Error -> {
                // The re-read failed; every pending href in this book waits for the next run.
                Log.w(TAG, "Re-read for pending photos failed for book '${book.displayName}': ${read.code} ${read.message}")
            }
        }
    }

    /**
     * Fetches the photo at [photoUrl] and writes it to [href]'s contact.
     *
     * [CardDavClient.fetchPhoto] decides which failures are retryable. A retryable one
     * (offline, timeout, 5xx, 429, 408, an empty body, a photo-gateway 401) leaves [href]
     * pending for the next sync. A permanent one (404, any other status such as 403, 410 or an
     * unfollowed redirect, a foreign host, a non-raster type, over the byte cap) clears the
     * flag so it isn't retried forever.
     *
     * A cleared flag comes back only when the vCard changes: its etag changes, the pull
     * replaces the contact, and the replace sets the flag again if the body still has a URL
     * photo. An image changed behind a stable URL doesn't change the vCard, and an iCloud
     * gateway URL is stable across a photo edit, so for such a URL clearing is permanent.
     * That is why transient failures and a gateway 401 stay pending.
     */
    private suspend fun fetchAndWrite(
        accountName: String,
        href: String,
        photoUrl: String,
        client: CardDavClient,
    ) {
        when (val photo = client.fetchPhoto(photoUrl)) {
            is CalDavResult.Success -> {
                val written = contactsProvider.writePhotoAndClearPending(accountName, href, photo.data.bytes)
                if (written.isFailure) {
                    Log.w(TAG, "Photo write failed; contact left pending for retry")
                }
            }
            is CalDavResult.Error -> {
                if (photo.isRetryable) {
                    // Retryable (see the KDoc): leave pending.
                    Log.w(TAG, "Photo fetch failed (${photo.code}); contact left pending for retry")
                } else {
                    // Permanent (see the KDoc): clear the flag so it isn't retried forever.
                    Log.w(TAG, "Photo fetch permanently failed (${photo.code}); clearing pending flag")
                    contactsProvider.clearPhotoPending(accountName, href)
                }
            }
        }
    }

    /**
     * Returns the discovered book whose collection path is the longest prefix of [href]'s path,
     * so nested collections resolve to the deepest book, or null when none matches. Paths, not
     * full URLs, are compared because the href may be server-relative while the book URL is
     * absolute.
     *
     * The book path gets a trailing `/` first so the match stops at a segment boundary: a book at
     * `/ab/default` must not take an href under a sibling `/ab/default-2/`.
     */
    private fun bookForHref(href: String, books: List<CardDavAddressBook>): CardDavAddressBook? {
        val hrefPath = pathOf(href)
        return books
            .filter { hrefPath.startsWith(withTrailingSlash(pathOf(it.url))) }
            .maxByOrNull { pathOf(it.url).length }
    }

    /** Ensures a collection path ends in `/` so prefix matching stops at a segment boundary. */
    private fun withTrailingSlash(path: String): String =
        if (path.endsWith("/")) path else "$path/"

    /** Returns the path of a URL or relative href, or the input when it doesn't parse. */
    private fun pathOf(urlOrPath: String): String =
        try {
            java.net.URI(urlOrPath).path ?: urlOrPath
        } catch (_: Exception) {
            urlOrPath
        }

    companion object {
        private const val TAG = "ContactPhotoFetcher"
    }
}
