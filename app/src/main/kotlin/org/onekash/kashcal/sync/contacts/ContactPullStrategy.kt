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
     * The run completed (possibly with some books failing to enumerate — see
     * [booksFailed]). Counts are across every enumerated book.
     *
     * @property inserted contacts new to the device (href absent locally).
     * @property replaced contacts whose server etag differed from the device's.
     * @property skipped contacts whose etag matched (not fetched, not written).
     * @property deleted device contacts the server no longer lists (orphan sweep).
     * @property booksFailed count of books whose contents we could not fully
     *   confirm this run — from any stage: a home-set that failed to list, a book
     *   that failed to enumerate its hrefs, a fetch that failed, or a device write
     *   that failed. When > 0 the orphan sweep is deliberately skipped, because an
     *   unconfirmed book must not read as "server has zero hrefs" and trigger a
     *   mass delete.
     */
    data class Success(
        val inserted: Int,
        val replaced: Int,
        val skipped: Int,
        val deleted: Int,
        val booksFailed: Int,
    ) : ContactPullResult()

    /**
     * The run could not proceed — a systemic failure (discovery auth/permission,
     * or every book failing) rather than a per-book hiccup.
     */
    data class Error(val code: Int, val message: String, val isRetryable: Boolean) : ContactPullResult()
}

/**
 * Full-sync pull for CardDAV contacts — the read-only mirror step: re-discover a
 * login's address books, fetch their contents, and reconcile them onto the
 * Android Contacts Provider so the device reflects the server's current state.
 *
 * This is the CardDAV sibling of the calendar
 * [org.onekash.kashcal.sync.strategy.PullStrategy] — parallel in shape, NOT
 * shared code (the isolation firewall forbids reaching across). It is a
 * domain/sync-layer component, so it uses [AddressBookDao] directly (the
 * "no DAO from a ViewModel" rule does not apply; the calendar PullStrategy
 * likewise injects its DAOs), and writes contacts only through the
 * [ContactsProviderRepository] fence.
 *
 * Reconciliation per run, per book:
 *  - **Re-discover** the books every run (never cache "no books" — a book created
 *    on the server after enrolment must appear on the next sync).
 *  - **Incremental (RFC 6578) when a sync-token is stored**: issue a
 *    sync-collection REPORT, paging while the server reports 507 truncation
 *    (§3.6), and apply only the changed/deleted the server reports. On this path
 *    deletes come ONLY from the server's reported removed set — never a
 *    full-enumeration diff — so a silently truncated listing can't false-delete
 *    real contacts. An invalid/expired token (403/410) falls back to a full
 *    listing; a transient failure holds the token and retries next run.
 *  - **ctag cheap-skip** (no-token servers only): when no sync-token is stored to
 *    drive a delta but the collection ctag matches the value persisted after the
 *    last full-list (and the device isn't wiped), nothing changed — skip the
 *    enumeration entirely. Like the delta path, a skipped book disables the union
 *    orphan sweep, since it contributes no hrefs to the union.
 *  - **Full listing otherwise** (initial sync / no token+no matching ctag / invalid
 *    token): enumerate the book's hrefs+etags via PROPFIND.
 *  - Either source routes each href against the device read-back
 *    ([ContactsProviderRepository.existingEtagsByHref]): **insert** when the href
 *    is new, **replace** when the etag differs, **skip** when it matches. Skipping
 *    means unchanged contacts are never re-fetched or re-materialized.
 *  - **Orphan sweep** (full-listing books only): delete device contacts whose href
 *    is in none of the enumerated books — computed ONCE against the UNION across
 *    all books, never per-book (a per-book delete would wipe other books' contacts,
 *    since the provider store is account-scoped, not book-scoped). The sweep is
 *    skipped entirely if any book failed to enumerate OR any book synced by delta
 *    (whose full href set isn't in the union), so a transient error or a partial
 *    delta can never masquerade as "the server has zero contacts."
 *  - Persist each book's sync-token + ctag after a successful run.
 *
 * Parallel multiget is deliberately deferred; fetches are sequential-batched.
 */
class ContactPullStrategy @Inject constructor(
    private val addressBookDao: AddressBookDao,
    private val contactsProvider: ContactsProviderRepository,
    private val photoFetcher: ContactPhotoFetcher,
) {

    /**
     * Run a full contact sync for [account] against [serverUrl] using [client]
     * (already carrying the account's credentials). [account.email] is the
     * system-account name every provider write is scoped to.
     */
    suspend fun sync(
        account: Account,
        serverUrl: String,
        client: CardDavClient,
    ): ContactPullResult {
        val accountName = account.email
        val reader = CardDavContactReader(client)

        // Force ungrouped-contact visibility every run: synced contacts carry no
        // group membership, so without this the account shows but its contacts stay
        // invisible in the Contacts app. Idempotent, so accounts enabled before this
        // existed self-heal here rather than needing a re-enable.
        contactsProvider.ensureContactVisibility(accountName)

        // ---- Re-discover the address books every run ----
        val discovery = when (val discovered = discoverBooks(client, serverUrl)) {
            is CalDavResult.Success -> discovered.data
            is CalDavResult.Error -> return ContactPullResult.Error(
                code = discovered.code,
                message = discovered.message,
                isRetryable = discovered.isRetryable,
            )
        }
        val books = discovery.books

        var inserted = 0
        var replaced = 0
        var skipped = 0
        // Deletes accrue from two sources: the incremental delta's server-reported
        // removed set (inline in the loop) and the full-sync union orphan sweep.
        var deleted = 0
        // Seed with discovery-level home-set listing failures: a home whose
        // listAddressBooks errored contributes no hrefs to the union, so the
        // orphan sweep MUST be disabled or it would delete that home's contacts
        // as "no longer on the server."
        var booksFailed = discovery.homesFailed

        // The union of every href the server currently lists, across ALL books.
        // Only complete when no book failed — otherwise the orphan sweep is unsafe.
        val serverHrefsUnion = HashSet<String>()

        // Set when any book took the incremental delta path. Such a book reports only
        // its *changes*, so its full href set is absent from serverHrefsUnion — the
        // union no longer represents the server's complete state, so the union sweep
        // MUST NOT run. Incremental deletes come from the server's own deleted set
        // instead, which is what stops a truncated enumeration from false-deleting.
        var sweepUnsafe = false

        // Device state read ONCE up front: href -> stored etag for this account.
        val deviceEtags = contactsProvider.existingEtagsByHref(accountName)

        // Insert and replace share the same shape: write, and on failure log +
        // disable the sweep (a partial mirror must not be swept). Returns the count
        // materialized on success, or null on a write failure so the caller bumps
        // booksFailed.
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

        // Route a server (href -> etag) list against the device read-back and
        // materialize it: insert new hrefs, replace changed ones, skip unchanged.
        // Shared by the full-listing and incremental-delta paths — both arrive at the
        // same insert/replace/skip decision from their own href source.
        suspend fun materialize(
            bookUrl: String,
            vcardVersion: String,
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
                    // Null-or-different (a null validator can't prove unchanged) -> replace.
                    if (deviceEtag == null || deviceEtag != serverEtag) toReplace += href else skippedHere++
                }
            }

            // Fetch only the hrefs that need materializing (skipped ones never hit the wire).
            val needed = toInsert + toReplace
            if (needed.isEmpty()) return MaterializeOutcome(0, 0, skippedHere, ok = true)

            return when (val read = reader.readContacts(bookUrl, needed, vcardVersion)) {
                is CalDavResult.Success -> {
                    // A single resource body can hold more than one vCard, so an
                    // href can map to several contacts — group (never associate,
                    // which would drop all but the last) so none are lost.
                    val writesByHref = read.data.contacts.groupBy(
                        keySelector = { it.href },
                        valueTransform = { rc ->
                            MappedContactWrite(
                                href = rc.href,
                                etag = rc.etag,
                                mapped = VCardContactMapper.toEntity(rc.contact),
                            )
                        },
                    )
                    val insertWrites = toInsert.flatMap { writesByHref[it].orEmpty() }
                    val replaceWrites = toReplace.flatMap { writesByHref[it].orEmpty() }
                    // A write failure (e.g. permission revoked mid-run) leaves the
                    // device in an unknown state for this book; ok=false so the sweep
                    // can't delete against a partial mirror.
                    val insertedNow = applyWrite(insertWrites, "Insert failed for $bookUrl") {
                        contactsProvider.insertContacts(accountName, it)
                    }
                    val replacedNow = applyWrite(replaceWrites, "Replace failed for $bookUrl") {
                        contactsProvider.replaceContacts(accountName, it)
                    }
                    // An href we requested but couldn't read (a body that threw or
                    // parsed to zero vCards, or one the server omitted) leaves this
                    // book unconfirmed: ok=false so the caller holds its sync cursor
                    // and re-fetches it next run rather than advancing past a contact
                    // it never actually mirrored (RFC 6578 §3.1 — the client must keep
                    // synchronizing until the collection reconciles). Group drops are
                    // NOT unreadable, so a book of only real+group contacts stays ok.
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
                    // Treat a fetch failure like an enumerate failure for the sweep:
                    // we can't confirm this book's contents, so don't let the sweep run.
                    Log.w(TAG, "Fetch failed for $bookUrl: ${read.code} ${read.message}")
                    MaterializeOutcome(0, 0, skippedHere, ok = false)
                }
            }
        }

        for (book in books) {
            // Read the stored row BEFORE the upsert: the discovered book carries no
            // token/ctag, so carry the persisted cursors through the upsert (below)
            // rather than letting a fresh entity's null defaults clobber them.
            val storedBook = addressBookDao.getByAccountIdAndUrl(account.id, book.url)
            val storedToken = storedBook?.syncToken
            val storedCtag = storedBook?.ctag

            // Persist/refresh the book row so its id is stable and its token/ctag
            // can be updated after enumeration. Preserve the stored cursors so a hold
            // (delta failure) or a ctag-skip needs no re-persist and no crash-window
            // nulls them out. The discovered book's own ctag is applied only after a
            // successful enumeration below, never here.
            val bookId = addressBookDao.upsert(
                book.toEntity(account.id).copy(syncToken = storedToken, ctag = storedCtag),
            )

            // Self-heal a wiped device: if a cursor is stored but the device holds
            // NONE of this account's contacts, the mirror was purged out-of-band
            // (the account removed in Android Settings, an OS purge, a failed prior
            // write) while the server-side token survived. A delta against that token
            // reports only *changes* and would leave the account empty forever, so
            // force the full-listing path to re-fetch everything. Guarded on the whole
            // device being empty (not per-book) so a normal sync of a populated
            // account still takes the cheap incremental path.
            val deviceWiped = deviceEtags.isEmpty()

            // ---- Incremental delta path: a stored token drives sync-collection ----
            if (storedToken != null && !deviceWiped) {
                when (val delta = collectDelta(client, book.url, storedToken)) {
                    is DeltaResult.Ready -> {
                        // A delta reports only changes, so this book's full href set is
                        // absent from the union — the union sweep must not run this run.
                        sweepUnsafe = true
                        val outcome = materialize(book.url, book.vcardVersion, delta.changed)
                        inserted += outcome.inserted
                        replaced += outcome.replaced
                        skipped += outcome.skipped
                        // Deletes come ONLY from the server's explicit removed set here.
                        val deletesOk = delta.deleted.isEmpty() ||
                            contactsProvider.deleteByHrefs(accountName, delta.deleted).isSuccess
                        if (deletesOk) deleted += delta.deleted.size
                        if (outcome.ok && deletesOk) {
                            // Advance to the delta's token only when the book fully
                            // applied. If any change or delete failed, hold the stored
                            // cursor (carried through the upsert) so the SAME delta
                            // replays next run: a token advance here would step past the
                            // server's removed set, orphaning the failed delete forever.
                            addressBookDao.updateSyncToken(bookId, syncToken = delta.newToken, ctag = book.ctag)
                        } else {
                            booksFailed++
                        }
                        continue
                    }
                    is DeltaResult.Failed -> {
                        // Transient: the stored cursor is already held (carried through
                        // the upsert), so just count the book failed and retry next run.
                        Log.w(TAG, "Incremental sync failed for ${book.url}: ${delta.code} ${delta.message}")
                        booksFailed++
                        continue
                    }
                    is DeltaResult.TokenInvalid -> {
                        // Fall through to the full-listing path below, which re-enters
                        // this book into the union so the sweep stays valid for it.
                        Log.w(TAG, "Sync token invalid for ${book.url}; falling back to full listing")
                    }
                }
            }

            // ---- ctag cheap-skip: unchanged collection, no token to drive a delta ----
            // A no-sync-token server full-lists every run, but the collection ctag
            // ("did anything change?") lets us skip that enumeration when it matches
            // the value stored after the last successful full-list. Gated on:
            //   - no stored token: a token server already took the cheap delta path;
            //   - a non-null, matching ctag: null on either side can't prove unchanged;
            //   - a non-wiped device: an out-of-band purge (deviceWiped) must re-fetch
            //     everything, so a matching ctag can't be trusted to mean "in sync".
            // A skipped book contributes no hrefs to the union, so the sweep MUST be
            // disabled for this run — exactly as the delta path does — or the union
            // sweep would false-delete this book's unchanged contacts.
            if (storedToken == null && !deviceWiped &&
                book.ctag != null && book.ctag == storedCtag
            ) {
                sweepUnsafe = true
                Log.d(TAG, "ctag unchanged for ${book.url}; skipping enumeration")
                continue
            }

            // ---- Full-listing path: initial sync, no token, or an invalid token ----
            // Probe the token BEFORE enumerating: it must reflect a server state at
            // or before the listing, never after. A contact created between the
            // listing and a later token probe would be absent from this run yet
            // covered by the token, so the next delta would skip it forever. Taken
            // first, the token is old enough that any such write is either enumerated
            // now or re-reported by the next delta (an idempotent re-materialize).
            val newToken = (client.getSyncToken(book.url) as? CalDavResult.Success)?.data

            val hrefsResult = client.listAllContactHrefs(book.url)
            if (hrefsResult is CalDavResult.Error) {
                // A per-book failure does not abort the others; but it MUST disable
                // the orphan sweep so a book we couldn't read isn't mistaken for empty.
                Log.w(TAG, "Enumerate failed for ${book.url}: ${hrefsResult.code} ${hrefsResult.message}")
                booksFailed++
                continue
            }
            val serverList = (hrefsResult as CalDavResult.Success).data
            serverHrefsUnion += serverList.map { it.first }

            val outcome = materialize(book.url, book.vcardVersion, serverList)
            inserted += outcome.inserted
            replaced += outcome.replaced
            skipped += outcome.skipped
            if (outcome.ok) {
                // Persist token/ctag for this book (the token probed before enumeration).
                addressBookDao.updateSyncToken(bookId, syncToken = newToken, ctag = book.ctag)
            } else {
                // A partial book (a write failed, or an href couldn't be read) must NOT
                // advance the cursor: doing so would step the delta past contacts we
                // never mirrored, orphaning them until an unrelated server change
                // re-reports them. Hold the stored cursor (carried through the upsert)
                // so the next run re-enumerates and re-fetches the missing hrefs. This
                // is what turns a transient parse/write failure into a self-healing
                // retry instead of a permanent gap (RFC 6578 §3.1).
                booksFailed++
            }
        }

        // ---- Orphan sweep: union-wide, only when every book was FULLY enumerated ----
        // Requires both no failed book AND no book synced by delta: a delta book's
        // hrefs aren't in the union, so a sweep would false-delete them. Those books
        // rely on the server's own deleted set applied inline above instead.
        if (booksFailed == 0 && !sweepUnsafe) {
            // The pre-run device snapshot (deviceEtags.keys) is the correct basis:
            // hrefs inserted this run are in serverHrefsUnion so they'd never be
            // swept anyway, and reusing it avoids a second full provider query.
            val orphans = deviceEtags.keys - serverHrefsUnion
            if (orphans.isNotEmpty() && contactsProvider.deleteByHrefs(accountName, orphans).isSuccess) {
                deleted += orphans.size
            }
        } else if (booksFailed > 0) {
            Log.w(TAG, "Skipping orphan sweep: $booksFailed book(s) failed to enumerate")
        }

        // ---- Deferred photo fetch: drain the photo-pending worklist ----
        // Runs strictly AFTER the insert/replace/delete reconciliation so the
        // pending flags it reads reflect this run's writes. Independent of the
        // server delta, so a photo that failed to download on an earlier run is
        // retried here without a full re-pull. A no-op when nothing is pending.
        // Never throws — a photo failure must not fail the overall contact sync.
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
     * The books discovered plus how many home-sets failed to list. [homesFailed]
     * carries forward into the orphan-sweep guard: a home we couldn't list has
     * hrefs we can't see, so its contacts must not be swept as server-removed.
     */
    private data class Discovery(val books: List<CardDavAddressBook>, val homesFailed: Int)

    /**
     * Re-run the discovery chain (well-known -> principal -> home-set ->
     * list books). Auth/permission failures at any pre-listing step are systemic
     * and surfaced as an error. A per-home listing failure is counted (not
     * swallowed): when at least one home listed successfully the run proceeds with
     * the sweep disabled, but if EVERY home failed to list and nothing was
     * discovered, that's surfaced as a systemic error so an empty book list can
     * never be mistaken for "the server has zero contacts."
     */
    private suspend fun discoverBooks(
        client: CardDavClient,
        serverUrl: String,
    ): CalDavResult<Discovery> {
        val wellKnown = client.discoverWellKnown(serverUrl)
        if (wellKnown is CalDavResult.Error) return wellKnown
        val base = (wellKnown as CalDavResult.Success).data

        // Principal discovery at the seed URL. When the seed carries a context path
        // (e.g. a DNS TXT `path=` the server has since stopped honoring) the principal
        // PROPFIND can fail there while the same server still answers at its host root.
        // RFC 6764 §6 treats the context path as a hint, not a contract, so fall back
        // to the bare host root once before giving up — but only when the root differs
        // from what we already tried, so a genuine root-level failure isn't retried.
        var principal = client.discoverPrincipal(base)
        if (principal is CalDavResult.Error) {
            val root = hostRootOf(base)
            // Compare against the trailing-slash-trimmed base so a seed that is already
            // the host root (with or without a trailing "/") isn't retried against the
            // effectively-identical location — a genuine root-level failure stands.
            if (root != null && root != base.trimEnd('/')) {
                Log.w(TAG, "Principal discovery failed at the context path; retrying at the host root")
                principal = client.discoverPrincipal(root)
            }
        }
        if (principal is CalDavResult.Error) return principal

        val homes = client.discoverAddressBookHome((principal as CalDavResult.Success).data)
        if (homes is CalDavResult.Error) return homes

        val allBooks = ArrayList<CardDavAddressBook>()
        var homesFailed = 0
        var lastError: CalDavResult.Error? = null
        for (home in (homes as CalDavResult.Success).data) {
            when (val listed = client.listAddressBooks(home)) {
                is CalDavResult.Success -> allBooks += listed.data
                is CalDavResult.Error -> {
                    Log.w(TAG, "listAddressBooks failed for $home: ${listed.message}")
                    homesFailed++
                    lastError = listed
                }
            }
        }
        // Discovered nothing AND a listing errored -> systemic failure, not an
        // empty address book. Returning success(emptyList) here would let the
        // orphan sweep wipe every device contact on a transient discovery error.
        if (allBooks.isEmpty() && lastError != null) return lastError
        return CalDavResult.success(Discovery(allBooks, homesFailed))
    }

    /**
     * The scheme + authority of [url] with no path (e.g.
     * `https://dav.example.test/carddav/` -> `https://dav.example.test`), or null if
     * [url] can't be parsed or carries no host. Used to retry principal discovery at
     * the host root when a discovered context path no longer serves a principal.
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

    /** What one book's route+fetch+write leg materialized, and whether it fully
     *  succeeded (a false [ok] means the book's mirror is partial → sweep-unsafe). */
    private data class MaterializeOutcome(
        val inserted: Int,
        val replaced: Int,
        val skipped: Int,
        val ok: Boolean,
    )

    /** Outcome of the incremental sync-collection leg for one book. */
    private sealed class DeltaResult {
        /** A complete delta (all 507 pages drained): changed href+etag pairs, the
         *  server-reported deleted hrefs, and the token to persist next. */
        data class Ready(
            val changed: List<Pair<String, String?>>,
            val deleted: List<String>,
            val newToken: String?,
        ) : DeltaResult()

        /** The stored token was rejected (403/410): re-sync this book from a full
         *  listing, which re-enters it into the orphan-sweep union. */
        data object TokenInvalid : DeltaResult()

        /** A transient failure (network/5xx, or a truncation that never advanced):
         *  hold the stored token and retry the delta next run; the book is unconfirmed. */
        data class Failed(val code: Int, val message: String) : DeltaResult()
    }

    /**
     * Drain the RFC 6578 sync-collection delta for [addressBookUrl] starting from
     * [storedToken], paging while the server reports truncation (§3.6: a 507
     * partial response returns a token for the partial state; the client MUST
     * re-issue on it until not truncated). Accumulates changed + deleted across
     * pages. A 403/410 → [DeltaResult.TokenInvalid]; any other error, or a
     * truncated page whose token doesn't advance, → [DeltaResult.Failed].
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
                    // Truncated: the returned token MUST advance the cursor, or we'd
                    // loop forever re-fetching the same partial page. Bail to a retry.
                    val next = report.syncToken
                    if (next == null || next == token || ++pages > MAX_SYNC_PAGES) {
                        Log.w(TAG, "Truncated sync-collection did not advance for $addressBookUrl; retrying next run")
                        return DeltaResult.Failed(507, "truncation did not advance")
                    }
                    token = next
                }
                is CalDavResult.Error -> {
                    // 403/410 is an expired/invalid token (RFC 6578 §3.6) — fall back
                    // to a full listing; anything else is a transient per-book failure.
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

        /** Safety cap on 507 continuation pages per book, so a misbehaving server
         *  that keeps reporting truncation without advancing can't loop forever. */
        private const val MAX_SYNC_PAGES = 1000
    }
}
