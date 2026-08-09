package org.onekash.kashcal.sync.contacts

import android.util.Log
import org.onekash.kashcal.data.contacts.VCardContactMapper
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.carddav.CardDavContactReader
import org.onekash.kashcal.sync.carddav.model.CardDavAddressBook
import org.onekash.kashcal.sync.client.model.CalDavResult
import javax.inject.Inject

/**
 * Drains the photo-pending worklist after a contact pull: for every RawContact
 * whose `SYNC4` carries [ContactsProviderRepository.pendingPhotoSourceIds] the
 * fetcher re-reads its vCard, recovers the remote-URL photo, downloads the bytes
 * over the account's authenticated client, and writes them as a Photo blob while
 * clearing the pending flag.
 *
 * Why a deferred second pass rather than fetching inline during the pull:
 *  - The pending-row query is **independent of the server delta**, so a photo whose
 *    download hit a transient failure (offline, 5xx, timeout) on an earlier run is
 *    retried on the next sync — incremental included — WITHOUT forcing a full
 *    re-pull.
 *  - It keeps the network photo fetch out of the pull's insert/replace transaction,
 *    so a slow or failing gateway can never stall or partially fail the mirror.
 *
 * The deferred-fetch URL is **not persisted** anywhere: it is recovered by a
 * targeted multiget of only the pending hrefs and read off
 * [org.onekash.kashcal.data.contacts.MappedContact.photoUrl]. This keeps the
 * DSID-bearing iCloud gateway URL out of the local database, consistent with the
 * redaction discipline for account-identifying values.
 *
 * Per pending contact:
 *  - **URL recovered** → [CardDavClient.fetchPhoto]; on success
 *    [ContactsProviderRepository.writePhotoAndClearPending]. On a **retryable**
 *    failure (offline, 5xx, 429, 408, timeout, a photo-gateway 401) the contact is
 *    left pending (logged) for a later retry; on a **permanent** failure (404 gone,
 *    foreign-host-refused, non-raster type, over the byte cap) the flag is cleared so
 *    it isn't retried forever. The permanent set is deliberately narrow: it is exactly
 *    the failures where the *same URL* can never succeed as-is. When the vCard's PHOTO
 *    reference later changes (a different URL, or a switch to inline), the body hash
 *    (SYNC3) changes and the replace re-arms the flag. Note this self-heal is keyed on
 *    the vCard changing, NOT on the image bytes changing behind a stable URL — an
 *    iCloud gateway URL is stable across a photo edit, so a cleared flag there does not
 *    re-arm on a bytes-only change. That is why transient statuses (and a gateway 401)
 *    stay retryable rather than clearing: for a stable URL, clearing is effectively
 *    permanent.
 *  - **No URL on re-read** (photo removed on the server, or it became inline and was
 *    already written on the pull) → [ContactsProviderRepository.clearPhotoPending]
 *    so a stale flag isn't retried forever.
 *  - **Href resolves to no discovered book** (its home failed to enumerate this run,
 *    or the book was removed) → skipped and left pending; never mis-fetched against
 *    the wrong collection.
 *
 * Steady-state the pending set is empty, so this is zero-cost; the extra multiget
 * only runs for newly-inserted/changed URL-photo contacts, once each.
 *
 * Runs inside the pull's process-wide sync lock, strictly after the delete-then-
 * insert replace, so there is no concurrency hazard with the pending flag it reads.
 */
class ContactPhotoFetcher @Inject constructor(
    private val contactsProvider: ContactsProviderRepository,
) {

    /**
     * Fetch and write every pending photo for [accountName], reading vCards through
     * [client] (already carrying the account's credentials) against the collections
     * in [books] (this run's discovered address books). A no-op when nothing is
     * pending. Never throws — every per-contact failure is logged and leaves the
     * contact pending so the overall sync still reports success.
     */
    suspend fun fetchPending(
        accountName: String,
        books: List<CardDavAddressBook>,
        client: CardDavClient,
    ) {
        val pending = contactsProvider.pendingPhotoSourceIds(accountName)
        if (pending.isEmpty()) return

        val reader = CardDavContactReader(client)

        // Group each pending href under the book whose collection path is its prefix,
        // so the re-read multiget targets the right collection. An href matching no
        // book is dropped here (left pending) rather than fetched against the wrong one.
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
            // Defensive outer guard: the never-throws contract otherwise rests on
            // every collaborator honoring its CalDavResult/Result envelope. An
            // unexpected unchecked throw here must degrade to "left pending, logged"
            // for this book rather than propagate up and fail the whole contact sync.
            try {
                fetchBook(accountName, book, hrefs, reader, client)
            } catch (e: Exception) {
                Log.w(TAG, "Photo fetch failed unexpectedly for book '${book.displayName}'; left pending", e)
            }
        }
    }

    /** Re-read [hrefs] in [book], then fetch+write (or clear) each recovered photo. */
    private suspend fun fetchBook(
        accountName: String,
        book: CardDavAddressBook,
        hrefs: List<String>,
        reader: CardDavContactReader,
        client: CardDavClient,
    ) {
        when (val read = reader.readContacts(book.url, hrefs, book.vcardVersion)) {
            is CalDavResult.Success -> {
                // Map href -> recovered photo URL (null when the re-read carries no
                // URL photo). A body can technically hold several vCards; take the
                // first non-null photo URL for the href.
                val urlByHref = HashMap<String, String?>()
                for (rc in read.data.contacts) {
                    val url = VCardContactMapper.toEntity(rc.contact).photoUrl
                    if (urlByHref[rc.href] == null) urlByHref[rc.href] = url
                }
                for (href in hrefs) {
                    // An href the re-read didn't return (deleted between pull and
                    // now) has no entry; leave it pending — a later sweep removes
                    // the RawContact entirely, so clearing its flag would be moot.
                    if (!urlByHref.containsKey(href)) {
                        Log.w(TAG, "Pending contact absent on re-read; left pending")
                        continue
                    }
                    val photoUrl = urlByHref[href]
                    if (photoUrl == null) {
                        // Stale flag: the URL photo is gone (removed or now inline).
                        contactsProvider.clearPhotoPending(accountName, href)
                    } else {
                        fetchAndWrite(accountName, href, photoUrl, client)
                    }
                }
            }
            is CalDavResult.Error -> {
                // Couldn't re-read this book's pending hrefs; leave them all pending
                // for the next run rather than guessing.
                Log.w(TAG, "Re-read for pending photos failed for book '${book.displayName}': ${read.code} ${read.message}")
            }
        }
    }

    /**
     * Fetch the photo at [photoUrl] and write it. A *retryable* failure (offline,
     * 5xx, 429, 408, timeout, a photo-gateway 401) leaves [href] pending for the next
     * sync. A *non-retryable* failure (404 gone, foreign-host-refused, non-raster
     * type, over the byte cap) clears the pending flag instead of retrying it forever —
     * the fetch can never succeed for this URL. A later vCard change that alters the
     * PHOTO reference re-arms the flag on replace (via the SYNC3 body-hash change), so
     * such contacts self-heal; a bytes-only change behind a stable URL does not re-arm,
     * which is precisely why only genuinely-unfixable statuses clear the flag.
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
                    // Offline / 5xx / 429 / 408 / timeout / gateway 401: retry later.
                    Log.w(TAG, "Photo fetch failed (${photo.code}); contact left pending for retry")
                } else {
                    // Permanent (404 gone / foreign-host-refused / non-raster / over
                    // cap): give up and clear the flag so it isn't retried forever.
                    Log.w(TAG, "Photo fetch permanently failed (${photo.code}); clearing pending flag")
                    contactsProvider.clearPhotoPending(accountName, href)
                }
            }
        }
    }

    /**
     * The discovered book whose collection path is a prefix of [href]'s path, or
     * null when none matches. Comparing by path (not the full URL) tolerates the
     * href being server-relative while the book URL is absolute. Prefers the
     * longest matching prefix so nested collections resolve to the deepest book.
     *
     * The book path is normalized to a trailing `/` before the compare so the
     * prefix match respects path-segment boundaries — a book at `/ab/default`
     * must not swallow an href under a sibling `/ab/default-2/…`.
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

    /** Path component of a URL or already-relative href; falls back to the input. */
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
