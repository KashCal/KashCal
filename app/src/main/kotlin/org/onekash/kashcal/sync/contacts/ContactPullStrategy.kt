package org.onekash.kashcal.sync.contacts

import android.util.Log
import org.onekash.kashcal.data.contacts.VCardContactMapper
import org.onekash.kashcal.data.db.dao.AddressBookDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.AddressBook
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.carddav.CardDavContactReader
import org.onekash.kashcal.sync.carddav.model.CardDavAddressBook
import org.onekash.kashcal.sync.client.model.CalDavResult
import javax.inject.Inject

/**
 * Outcome of a [ContactPullStrategy] run.
 */
sealed class ContactPullResult {
    /**
     * The run completed, possibly with failed books ([booksFailed]). Counts span every book.
     *
     * @property inserted contacts new to the device (href absent locally).
     * @property replaced contacts whose server etag differed from the device's.
     * @property skipped contacts whose etag matched (not fetched, not written).
     * @property deleted device contacts the server no longer lists (orphan sweep).
     * @property booksFailed books whose contents this run couldn't confirm: a home-set that
     *   failed to list, a failed enumeration, fetch or device write, or a held push. Any value
     *   above 0 skips the orphan sweep.
     */
    data class Success(
        val inserted: Int,
        val replaced: Int,
        val skipped: Int,
        val deleted: Int,
        val booksFailed: Int,
    ) : ContactPullResult()

    /**
     * The run couldn't proceed: a discovery step before listing failed, or no book was found and
     * a home-set failed to list.
     */
    data class Error(val code: Int, val message: String, val isRetryable: Boolean) : ContactPullResult()
}

/**
 * Contact sync for one CardDAV login: re-discover its address books, push local edits and
 * deletes ([ContactPushStrategy]), then reconcile the books' contents onto the Android Contacts
 * Provider. The push runs first so the pull reconciles against a server that already has the
 * local changes.
 *
 * This parallels the calendar [org.onekash.kashcal.sync.strategy.PullStrategy] in shape but
 * shares no code with it (`CardDavCalDavIsolationTest`). As a sync-layer component it uses
 * [AddressBookDao] directly, and it writes contacts only through [ContactsProviderRepository].
 *
 * Each run:
 *  - Re-discover the books. "No books" is never cached, so a book created on the server
 *    later appears on the next sync.
 *  - Delta (RFC 6578) when a sync-token is stored: a sync-collection REPORT, paging while
 *    the server reports 507 truncation (§3.6). Deletes on this path come only from the
 *    server's removed set, never from a listing diff, so a truncated listing can't delete
 *    real contacts. A rejected token (403/410) falls back to a full listing; any other
 *    failure holds the token for next run.
 *  - ctag skip (servers without sync-tokens): when the ctag matches the one stored after
 *    the last full listing and the device isn't wiped, skip enumeration.
 *  - Full listing otherwise (first sync, no token and no ctag match, rejected token):
 *    PROPFIND the book's hrefs and etags.
 *  - Each listed href is checked against [ContactsProviderRepository.existingEtagsByHref]:
 *    insert a new href, replace a changed etag, skip a match. Skipped contacts are
 *    never fetched.
 *  - Persist the book's sync-token and ctag when the book and the push both succeeded.
 *
 * ## Orphan sweep
 *
 * After the books, device contacts whose href no book listed are deleted. The provider store
 * is account-scoped, so the sweep diffs against the union of every book's hrefs, never per book
 * (that would delete other books' contacts). It runs only when the union is the server's
 * complete state: every book fully listed, no failed book or home-set, no book taken by delta
 * or ctag skip (those add no hrefs), and at least one book discovered. Zero discovered books
 * says nothing about the server's contacts, so it never sweeps; a discovered book that lists
 * zero cards means the collection was emptied, so it does.
 *
 * Fetches are batched and sequential.
 */
class ContactPullStrategy @Inject constructor(
    private val addressBookDao: AddressBookDao,
    private val contactsProvider: ContactsProviderRepository,
    private val photoFetcher: ContactPhotoFetcher,
    private val pushStrategy: ContactPushStrategy,
) {

    /**
     * Syncs [account]'s contacts against [serverUrl] with [client], which carries the
     * account's credentials. `account.email` is the system-account name every provider
     * write is scoped to.
     */
    suspend fun sync(
        account: Account,
        serverUrl: String,
        client: CardDavClient,
    ): ContactPullResult {
        val accountName = account.email
        val reader = CardDavContactReader(client)

        // Synced contacts have no group membership, so without ungrouped visibility the
        // account shows in the Contacts app but its contacts don't. Set every run
        // (idempotent) so older accounts get it without a re-enable.
        contactsProvider.ensureContactVisibility(accountName)

        // ---- Re-discover the address books every run ----
        val discovery = when (val discovered = discoverBooks(client, serverUrl, account.principalUrl)) {
            is CalDavResult.Success -> discovered.data
            is CalDavResult.Error -> return ContactPullResult.Error(
                code = discovered.code,
                message = discovered.message,
                isRetryable = discovered.isRetryable,
            )
        }
        val books = discovery.books

        // ---- Push local edits and deletes before snapshotting the device ----
        // Pushing first means the pull reconciles against a server that has the local
        // changes, and a push that deletes the account's last contact leaves an empty
        // device, which the wiped-device check below answers with a full listing. A push
        // that isn't clean holds every book's sync-token so the whole run replays
        // (RFC 6578 §3.1) instead of advancing past a change not yet reconciled.
        val pushOutcome = pushStrategy.push(accountName, books, client)

        // A server resource may exist that no local row is matched to yet
        // ([ContactPushOutcome.pullUnsafe]), so the pull would mirror it as a second row.
        // Skip everything (listing, delta, sweep, photos) and keep every token. Other push
        // failures still pull; the `pushOutcome.clean` checks below hold their tokens.
        // booksFailed is at least 1 so a run with zero books still reads as a replay.
        if (pushOutcome.pullUnsafe) {
            return ContactPullResult.Success(
                inserted = 0, replaced = 0, skipped = 0, deleted = 0,
                booksFailed = maxOf(books.size, 1),
            )
        }

        var inserted = 0
        var replaced = 0
        var skipped = 0
        // From the delta's removed set (in the loop) and from the orphan sweep.
        var deleted = 0
        // A home-set that failed to list adds no hrefs to the union, so it counts as failed.
        var booksFailed = discovery.homesFailed

        // Every href the fully listed books returned; the orphan sweep diffs against it.
        val serverHrefsUnion = HashSet<String>()

        // Set when a book took the delta or ctag-skip path and so added no hrefs to the union.
        var sweepUnsafe = false

        // Device state, read once: href -> stored etag for this account.
        val deviceEtags = contactsProvider.existingEtagsByHref(accountName)

        // Writes a batch; returns the count written, or null on failure (the book is then
        // partial and counts as failed).
        suspend fun applyWrite(
            writes: List<MappedContactWrite>,
            failLabel: String,
            action: suspend (List<MappedContactWrite>) -> Result<Unit>,
        ): Int? {
            if (writes.isEmpty()) return 0
            return if (action(writes).isSuccess) {
                writes.size
            } else {
                Log.w(TAG, failLabel)
                null
            }
        }

        // Inserts new hrefs, replaces changed ones and skips unchanged ones from a server
        // (href -> etag) list. Used by both the full-listing and delta paths.
        suspend fun materialize(
            bookUrl: String,
            vcardVersion: String,
            isReadOnly: Boolean,
            serverList: List<Pair<String, String?>>,
        ): MaterializeOutcome {
            val toInsert = ArrayList<String>()
            val toReplace = ArrayList<String>()
            var skippedHere = 0
            for ((href, serverEtag) in serverList) {
                if (!deviceEtags.containsKey(href)) {
                    toInsert += href
                } else {
                    val deviceEtag = deviceEtags[href]
                    // A null etag can't prove the contact unchanged, so it's replaced.
                    if (deviceEtag == null || deviceEtag != serverEtag) toReplace += href else skippedHere++
                }
            }

            // Fetch only inserts and replacements.
            val needed = toInsert + toReplace
            if (needed.isEmpty()) return MaterializeOutcome(0, 0, skippedHere, ok = true)

            return when (val read = reader.readContacts(bookUrl, needed, vcardVersion)) {
                is CalDavResult.Success -> {
                    // One resource can hold several vCards, so group by href; associate
                    // would keep only the last.
                    val writesByHref = read.data.contacts.groupBy(
                        keySelector = { it.href },
                        valueTransform = { rc ->
                            MappedContactWrite(
                                href = rc.href,
                                etag = rc.etag,
                                mapped = VCardContactMapper.toEntity(rc.contact),
                                isReadOnly = isReadOnly,
                            )
                        },
                    )
                    val insertWrites = toInsert.flatMap { writesByHref[it].orEmpty() }
                    val replaceWrites = toReplace.flatMap { writesByHref[it].orEmpty() }
                    // A write failure (e.g. permission revoked mid-run) leaves this book
                    // partial: ok=false.
                    val insertedNow = applyWrite(insertWrites, "Insert failed for $bookUrl") {
                        contactsProvider.insertContacts(accountName, it)
                    }
                    val replacedNow = applyWrite(replaceWrites, "Replace failed for $bookUrl") {
                        contactsProvider.replaceContacts(accountName, it)
                    }
                    // An href requested but not read (the body threw, parsed to zero vCards,
                    // or was omitted) leaves the book unconfirmed: ok=false holds its
                    // cursor so the next run re-fetches it (RFC 6578 §3.1). Dropped group
                    // cards don't count as unreadable.
                    if (read.data.unreadableHrefs.isNotEmpty()) {
                        Log.w(TAG, "Unreadable contacts in $bookUrl: ${read.data.unreadableHrefs.size} href(s) held for retry")
                    }
                    MaterializeOutcome(
                        inserted = insertedNow ?: 0,
                        replaced = replacedNow ?: 0,
                        skipped = skippedHere,
                        ok = insertedNow != null && replacedNow != null && read.data.unreadableHrefs.isEmpty(),
                    )
                }
                is CalDavResult.Error -> {
                    // Like a failed enumeration: the book's contents are unconfirmed.
                    Log.w(TAG, "Fetch failed for $bookUrl: ${read.code} ${read.message}")
                    MaterializeOutcome(0, 0, skippedHere, ok = false)
                }
            }
        }

        for (book in books) {
            // Read the stored row before the upsert: the discovered book has no token, so
            // the stored cursors are carried through the upsert instead of being nulled.
            val storedBook = addressBookDao.getByAccountIdAndUrl(account.id, book.url)
            val storedToken = storedBook?.syncToken
            val storedCtag = storedBook?.ctag

            // Upsert keeps the book id stable and the stored cursors in place, so a hold
            // or a ctag skip needs no re-persist and a crash can't null them. The
            // discovered ctag is stored only after a successful enumeration below.
            val bookId = addressBookDao.upsert(
                book.toEntity(account.id).copy(syncToken = storedToken, ctag = storedCtag),
            )

            // A stored cursor with none of the account's contacts on the device means the
            // device copy was purged (account removed in Settings, an OS purge, a failed
            // write). A delta would report only changes and leave the account empty for
            // good, so take the full listing. Checked for the whole account, not per book,
            // so a populated account keeps the delta path.
            val deviceWiped = deviceEtags.isEmpty()

            // ---- Delta path: a stored token drives sync-collection ----
            if (storedToken != null && !deviceWiped) {
                when (val delta = collectDelta(client, book.url, storedToken)) {
                    is DeltaResult.Ready -> {
                        // A delta adds no hrefs to the union.
                        sweepUnsafe = true
                        val outcome = materialize(book.url, book.vcardVersion, book.isReadOnly, delta.changed)
                        inserted += outcome.inserted
                        replaced += outcome.replaced
                        skipped += outcome.skipped
                        // Deletes come only from the server's removed set.
                        val deletesOk = delta.deleted.isEmpty() ||
                            contactsProvider.deleteByHrefs(accountName, delta.deleted).isSuccess
                        if (deletesOk) deleted += delta.deleted.size
                        if (pushOutcome.clean && outcome.ok && deletesOk) {
                            // Advance only when the whole delta applied and the push was clean.
                            // Otherwise the stored cursor stays, so the same delta replays;
                            // advancing would skip its removed set or an undelivered local
                            // change for good.
                            addressBookDao.updateSyncToken(bookId, syncToken = delta.newToken, ctag = book.ctag)
                        } else {
                            booksFailed++
                        }
                        continue
                    }
                    is DeltaResult.Failed -> {
                        // The stored cursor is already held; retry next run.
                        Log.w(TAG, "Incremental sync failed for ${book.url}: ${delta.code} ${delta.message}")
                        booksFailed++
                        continue
                    }
                    is DeltaResult.TokenInvalid -> {
                        // Fall through to the full listing, which adds this book to the union.
                        Log.w(TAG, "Sync token invalid for ${book.url}; falling back to full listing")
                    }
                }
            }

            // ---- ctag skip: no token, collection unchanged since the last full listing ----
            // Requires no stored token (a token server took the delta path), a non-null
            // matching ctag (null can't prove unchanged) and a device that wasn't wiped.
            // A skipped book adds no hrefs to the union, so the sweep is off for this run.
            if (storedToken == null && !deviceWiped &&
                book.ctag != null && book.ctag == storedCtag
            ) {
                sweepUnsafe = true
                Log.d(TAG, "ctag unchanged for ${book.url}; skipping enumeration")
                continue
            }

            // ---- Full-listing path: first sync, no token, or a rejected token ----
            // Take the token before listing. A token taken after would cover a contact
            // created between the two, so this run misses it and the next delta skips it
            // for good. Taken first, such a contact is either listed now or re-reported by
            // the next delta.
            val newToken = (client.getSyncToken(book.url) as? CalDavResult.Success)?.data

            val hrefsResult = client.listAllContactHrefs(book.url)
            if (hrefsResult is CalDavResult.Error) {
                // Other books continue; the failure turns off the orphan sweep.
                Log.w(TAG, "Enumerate failed for ${book.url}: ${hrefsResult.code} ${hrefsResult.message}")
                booksFailed++
                continue
            }
            val serverList = (hrefsResult as CalDavResult.Success).data
            serverHrefsUnion += serverList.map { it.first }

            val outcome = materialize(book.url, book.vcardVersion, book.isReadOnly, serverList)
            inserted += outcome.inserted
            replaced += outcome.replaced
            skipped += outcome.skipped
            if (pushOutcome.clean && outcome.ok) {
                // Also requires a clean push, which holds every book's cursor, not only the
                // failed one.
                addressBookDao.updateSyncToken(bookId, syncToken = newToken, ctag = book.ctag)
            } else {
                // A partial book keeps its stored cursor so the next run re-lists and
                // re-fetches it. Advancing would leave the missed contacts off the device
                // until some unrelated server change re-reports them (RFC 6578 §3.1).
                booksFailed++
            }
        }

        // ---- Orphan sweep (conditions in the class doc) ----
        // Zero books can come from an empty home-set or a broken well-known that ends at
        // a principal with no books; sweeping then would delete every device contact.
        if (booksFailed == 0 && !sweepUnsafe && books.isNotEmpty()) {
            // The pre-run snapshot is enough: hrefs inserted this run are in the union.
            val orphans = deviceEtags.keys - serverHrefsUnion
            if (orphans.isNotEmpty() && contactsProvider.deleteByHrefs(accountName, orphans).isSuccess) {
                deleted += orphans.size
            }
        } else if (booksFailed > 0) {
            Log.w(TAG, "Skipping orphan sweep: $booksFailed book(s) failed to enumerate")
        } else if (books.isEmpty() && deviceEtags.isNotEmpty()) {
            Log.w(TAG, "Skipping orphan sweep: discovery returned no address books; keeping ${deviceEtags.size} device contact(s)")
        }

        // ---- Photo fetch ----
        // After reconciliation, so the pending flags include this run's writes. It doesn't
        // depend on the delta, so a photo that failed on an earlier run is retried here.
        // Never throws: a photo failure doesn't fail the sync.
        photoFetcher.fetchPending(accountName, books, client)

        return ContactPullResult.Success(
            inserted = inserted,
            replaced = replaced,
            skipped = skipped,
            deleted = deleted,
            booksFailed = booksFailed,
        )
    }

    /**
     * The discovered books, and how many home-sets failed to list (these count as failed books).
     */
    private data class Discovery(val books: List<CardDavAddressBook>, val homesFailed: Int)

    /**
     * Discovers and lists the address books.
     *
     * Seeds from the account's stored CalDAV principal ([storedPrincipal]) when it shares the
     * CardDAV base's scheme, host and port. The principal is shared by CalDAV and CardDAV
     * (RFC 3744) and carries `addressbook-home-set`, so this skips the well-known step, which
     * fails on a redirect that drops a non-standard port or a 405 to the well-known PROPFIND
     * and then finds zero contacts at the host root. Split-host providers put CardDAV on
     * another host and keep the well-known chain.
     *
     * If the seed doesn't apply or yields no home-set, discovery falls back to
     * well-known -> principal -> home-set, so a server that only answers via well-known still
     * works. [storedPrincipal] is null only for rows migrated from before that column existed.
     *
     * A failure before listing is returned as an error. A home-set that fails to list is
     * counted; if no book was found and a home failed to list, that is returned as an error, so an
     * empty list never reads as "the server has zero contacts".
     */
    private suspend fun discoverBooks(
        client: CardDavClient,
        serverUrl: String,
        storedPrincipal: String?,
    ): CalDavResult<Discovery> {
        // Seed from the stored principal when it shares the CardDAV base's authority.
        if (storedPrincipal != null && sameAuthority(storedPrincipal, serverUrl)) {
            val seededHomes = client.discoverAddressBookHome(storedPrincipal)
            if (seededHomes is CalDavResult.Success && seededHomes.data.isNotEmpty()) {
                return listBooksAcrossHomes(client, seededHomes.data)
            }
            val reason = if (seededHomes is CalDavResult.Error) {
                "errored (${seededHomes.code})"
            } else {
                "returned no home-set"
            }
            Log.w(TAG, "Stored-principal seed $reason; falling back to well-known discovery")
        }

        val wellKnown = client.discoverWellKnown(serverUrl)
        // A well-known redirect the transport guard refused (e.g. to plain http elsewhere)
        // doesn't end discovery: it continues from the account's base URL (the CalDAV home
        // host, a DNS SRV result or a provider's pinned host). Later requests are guarded
        // the same way.
        if (wellKnown is CalDavResult.Error && wellKnown.code != CalDavResult.CODE_TRANSPORT_REFUSED) return wellKnown
        val base = (wellKnown as? CalDavResult.Success)?.data ?: serverUrl

        // A context path (e.g. a DNS TXT `path=` the server no longer honors) can fail
        // while the host root still answers. RFC 6764 §6 treats the path as a hint, so
        // retry once at the host root when that differs from the base.
        var principal = client.discoverPrincipal(base)
        if (principal is CalDavResult.Error) {
            val root = hostRootOf(base)
            // Trim the trailing slash so a base that is already the root isn't retried.
            if (root != null && root != base.trimEnd('/')) {
                Log.w(TAG, "Principal discovery failed at the context path; retrying at the host root")
                principal = client.discoverPrincipal(root)
            }
        }
        if (principal is CalDavResult.Error) return principal

        val homes = client.discoverAddressBookHome((principal as CalDavResult.Success).data)
        if (homes is CalDavResult.Error) return homes
        return listBooksAcrossHomes(client, (homes as CalDavResult.Success).data)
    }

    /**
     * Lists the books under every home-set, for both discovery paths. A failed home is
     * counted; no books plus a failed listing returns that error, never an empty success
     * that would let the orphan sweep delete every contact.
     */
    private suspend fun listBooksAcrossHomes(
        client: CardDavClient,
        homes: List<String>,
    ): CalDavResult<Discovery> {
        val allBooks = ArrayList<CardDavAddressBook>()
        var homesFailed = 0
        var lastError: CalDavResult.Error? = null
        for (home in homes) {
            when (val listed = client.listAddressBooks(home)) {
                is CalDavResult.Success -> allBooks += listed.data
                is CalDavResult.Error -> {
                    Log.w(TAG, "listAddressBooks failed for $home: ${listed.message}")
                    homesFailed++
                    lastError = listed
                }
            }
        }
        if (allBooks.isEmpty() && lastError != null) return lastError
        return CalDavResult.success(Discovery(allBooks, homesFailed))
    }

    /**
     * True when [a] and [b] share scheme, host and port. Split-host providers and
     * SRV-redirected bases differ and fall back to well-known discovery.
     */
    private fun sameAuthority(a: String, b: String): Boolean {
        val keyA = authorityKey(a) ?: return false
        val keyB = authorityKey(b) ?: return false
        return keyA == keyB
    }

    /**
     * `scheme://host:port` for comparison, or null when [url] doesn't parse or has no host.
     * Scheme and host are lowercased (RFC 3986: case-insensitive) and the default port filled
     * in, so URLs differing only in case or an explicit default port match.
     */
    private fun authorityKey(url: String): String? = try {
        val uri = java.net.URI(url)
        val host = uri.host?.lowercase()
        if (host.isNullOrBlank()) {
            null
        } else {
            val scheme = uri.scheme?.lowercase()
            val port = if (uri.port != -1) uri.port else defaultPortForScheme(scheme)
            "$scheme://$host:$port"
        }
    } catch (_: Exception) {
        null
    }

    private fun defaultPortForScheme(scheme: String?): Int = when (scheme) {
        "https" -> 443
        "http" -> 80
        else -> -1
    }

    /**
     * [url] without its path (`https://dav.example.test/carddav/` -> `https://dav.example.test`),
     * or null when it doesn't parse or has no host.
     */
    private fun hostRootOf(url: String): String? = try {
        val uri = java.net.URI(url)
        if (uri.host.isNullOrBlank()) {
            null
        } else {
            val port = if (uri.port == -1) "" else ":${uri.port}"
            "${uri.scheme}://${uri.host}$port"
        }
    } catch (_: Exception) {
        null
    }

    private fun CardDavAddressBook.toEntity(accountId: Long): AddressBook =
        AddressBook(
            accountId = accountId,
            url = url,
            displayName = displayName,
            description = description,
            vcardVersion = vcardVersion,
            ctag = ctag,
            isReadOnly = isReadOnly,
        )

    /** Counts for one book; [ok] false means the book is partial and counts as failed. */
    private data class MaterializeOutcome(
        val inserted: Int,
        val replaced: Int,
        val skipped: Int,
        val ok: Boolean,
    )

    /** Outcome of the incremental sync-collection leg for one book. */
    private sealed class DeltaResult {
        /** All pages drained: changed (href, etag) pairs, removed hrefs, and the next token. */
        data class Ready(
            val changed: List<Pair<String, String?>>,
            val deleted: List<String>,
            val newToken: String?,
        ) : DeltaResult()

        /** The stored token was rejected (403/410): take the full listing. */
        data object TokenInvalid : DeltaResult()

        /** Any other error, or truncation that didn't advance: keep the token, retry next run. */
        data class Failed(val code: Int, val message: String) : DeltaResult()
    }

    /**
     * Drains the RFC 6578 delta for [addressBookUrl] from [storedToken]. A 507-truncated page
     * returns a token for the partial state, and the client re-issues with it until the
     * result is complete (§3.6).
     */
    private suspend fun collectDelta(
        client: CardDavClient,
        addressBookUrl: String,
        storedToken: String,
    ): DeltaResult {
        val changed = ArrayList<Pair<String, String?>>()
        val deleted = ArrayList<String>()
        var token: String? = storedToken
        var pages = 0
        while (true) {
            when (val result = client.syncCollection(addressBookUrl, token)) {
                is CalDavResult.Success -> {
                    val report = result.data
                    report.changed.forEach { changed += it.href to it.etag }
                    deleted += report.deleted
                    if (!report.truncated) {
                        return DeltaResult.Ready(changed, deleted, report.syncToken)
                    }
                    // A truncated page must advance the token, or this would loop on it.
                    val next = report.syncToken
                    if (next == null || next == token || ++pages > MAX_SYNC_PAGES) {
                        Log.w(TAG, "Truncated sync-collection did not advance for $addressBookUrl; retrying next run")
                        return DeltaResult.Failed(507, "truncation did not advance")
                    }
                    token = next
                }
                is CalDavResult.Error -> {
                    // 403/410: expired or invalid token (RFC 6578 §3.6).
                    return if (result.code == 403 || result.code == 410) {
                        DeltaResult.TokenInvalid
                    } else {
                        DeltaResult.Failed(result.code, result.message)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ContactPullStrategy"

        /** Cap on truncated pages per book, for a server that never finishes. */
        private const val MAX_SYNC_PAGES = 1000
    }
}
