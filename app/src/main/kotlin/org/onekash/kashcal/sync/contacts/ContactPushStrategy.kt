package org.onekash.kashcal.sync.contacts

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.carddav.CardDavContactReader
import org.onekash.kashcal.sync.carddav.contactResourceName
import org.onekash.kashcal.sync.carddav.model.CardDavAddressBook
import org.onekash.kashcal.sync.carddav.model.ContactDeleteResult
import org.onekash.kashcal.sync.carddav.model.ContactPrecondition
import org.onekash.kashcal.sync.carddav.model.ContactUploadResult
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.vcard.VCardWriter
import java.util.UUID
import javax.inject.Inject

/**
 * The result of a [ContactPushStrategy.push]: whether every change applied or cleanly
 * deferred ([clean]), and whether the enclosing pull must be skipped this run to avoid a
 * same-run duplicate ([pullUnsafe]).
 *
 * The two are distinct on purpose. [clean] gates every sync-token advance: a not-clean
 * push holds every book's cursor so the whole run replays (a transport error or a failed
 * local write-back left the run incomplete). [pullUnsafe] is the *much narrower* signal
 * that a **net-new** create confirmed a server resource (a `201/204`, or a `412` adopt of
 * a resource proven ours) but the local `_ID` write-back then failed — so the row still has
 * a blank href while the server holds the resource. Only then can the following pull find a
 * just-created server href absent from the device and mirror it as a SECOND row, because the
 * pull matches by href and that row's href is still blank.
 *
 * A net-new push failure whose creation state is *known to be nothing* is NOT pull-unsafe: a
 * server that *refuses* the create with an HTTP status (400/415/422/507/persistent 5xx) or an
 * `assignContactUid` failure before the PUT leaves nothing on the server to duplicate, so the
 * pull runs normally while the not-clean push holds the token — a persistently-rejected net-new
 * contact must not freeze all inbound sync for the account. The exception is a *transport*
 * failure (no HTTP status seen): the PUT may have committed server-side before the response was
 * lost, so the creation state is unknown and it IS treated as pull-unsafe. An existing-contact
 * push failure never sets [pullUnsafe] — its row already has an href the pull reconciles. So the
 * pull is skipped only when a resource may exist that this row isn't matched to yet; otherwise
 * it runs and still materializes unrelated inbound changes while the failed leg's token stays held.
 *
 * @property clean true when every change was applied or cleanly deferred; false when a
 *   transport/5xx error, a server rejection, or a failed local write-back left the run
 *   incomplete.
 * @property pullUnsafe true when a net-new create confirmed a server resource but its local
 *   write-back failed, or a transport failure left the create's outcome unknown — either way
 *   the pull must be skipped this run. An HTTP-status-refused create, a pre-PUT persist failure,
 *   an existing-contact failure, and every delete never set it.
 */
data class ContactPushOutcome(
    val clean: Boolean,
    val pullUnsafe: Boolean,
) {
    companion object {
        /** Applied or cleanly deferred: safe to advance the token and safe to pull. */
        val CLEAN = ContactPushOutcome(clean = true, pullUnsafe = false)

        /**
         * Not applied, so hold the token — but no server resource is at risk of being
         * duplicated (nothing was created), so the pull may still run this run.
         */
        val DEFERRED = ContactPushOutcome(clean = false, pullUnsafe = false)

        /**
         * A server resource was created (or proven to already exist as ours) but the local
         * row is not matched to it yet: hold the token AND skip the pull this run, or it
         * would mirror the resource as a duplicate.
         */
        val PULL_UNSAFE = ContactPushOutcome(clean = false, pullUnsafe = true)
    }
}

/**
 * Pushes the device's pending contact edits and deletes back to CardDAV, the
 * mirror of the pull path. The pending set is the provider's own DIRTY/DELETED
 * flags (there is no separate operation queue): each locator carries the href,
 * uid, and the etag last seen on the server (SYNC2), which is all the context a
 * conditional PUT/DELETE needs.
 *
 * ## Upload model — GET before PUT, condition on the STORED etag
 *
 * The device holds no verbatim vCard body, so a body-preserving edit can't be
 * regenerated from the mapped fields alone (it would drop every X-prop, itemN
 * grouping, and unmapped parameter the server card carries). So an update first
 * GETs the current server body through [CardDavContactReader] and hands it to
 * [VCardWriter] as the patch base — the writer rewrites only the facets the user
 * changed and leaves everything else byte-faithful.
 *
 * The conditional PUT is keyed on the **stored** etag (the version the edit was
 * made against), never the etag just returned by the GET: if the GET etag differs
 * from the stored one the server copy moved on since the edit, so the edit is
 * **deferred** (left DIRTY) for the next pull to reconcile rather than clobbered
 * onto a newer server state. A create — or an update against a server that gave us
 * no etag — is a create-as-fresh with `If-None-Match: *`; it never sends
 * `If-Match(null)`.
 *
 * ## Deferral vs failure
 *
 * A "clean" outcome (this push returns `true`) means every change was either
 * applied or *cleanly deferred*. A PUT precondition failure (412/409), a GET-etag
 * mismatch, an unreadable patch base, or a permission denial all leave the edit's
 * DIRTY flag set for the next pull and are not failures. A DELETE precondition
 * failure is also clean, but is handled differently: rather than leave the
 * tombstone (whose etag a later pull would refresh, arming a real delete of the
 * edited server copy), the row is un-deleted so this run's pull re-materializes the
 * server copy (server-wins). Only a transport/5xx error or a provider write-back
 * that itself failed returns `false`, signalling the caller to hold the sync token
 * so the run is retried.
 *
 * ## Net-new device-created contacts
 *
 * A contact created on the device has a blank SOURCE_ID (href) AND no vCard UID of its
 * own (RFC 6350 §6.7.6 gives `UID` cardinality `*1`). Before its first create the push
 * synthesizes a globally-unique UID ([UUID]) and persists it to `SYNC1`
 * ([ContactsProviderRepository.assignContactUid], DIRTY kept set) — so the resource is
 * named `<uid>.vcf` by a value unique across every device on the account. A per-device
 * RawContact `_ID` would NOT be unique: two devices can each mint `_ID = 100`, collide on
 * one resource name, and (with blank UIDs) each adopt the other's server copy, losing one
 * contact. Naming by a synthesized global UID makes that collision structurally impossible.
 *
 * The create's success is then written back by the originating RawContact's provider `_ID`
 * ([ContactsProviderRepository.markNewContactUploaded]) rather than by href: the
 * freshly-minted server href (the path we PUT to) is stamped onto SOURCE_ID and DIRTY is
 * cleared, so the next pull matches the server copy to this existing row and skips it
 * instead of mirroring it as a second row. The stamped path matches the href form the
 * enumerate returns on every server the write path targets (verified live on Radicale,
 * Baikal, Nextcloud, and iCloud — all return the request path, not an absolute URL, for a
 * client-created member).
 *
 * If persisting the synthesized UID fails, the push does not PUT at all and reports the
 * edit as a clean-holding failure ([ContactPushOutcome.DEFERRED]): nothing was created
 * server-side, so the token is held but the pull is safe to run — it simply replays the
 * create next run once the UID persists. A server that *refuses* the create outright with an
 * HTTP status (400/415/422/507/persistent 5xx) is treated the same way: not-clean so the run
 * replays, but pull-safe, so a single contact the server keeps rejecting can never freeze all
 * inbound sync for the account. A *transport* failure (a lost response with no HTTP status) is
 * the one exception: the PUT may have committed before the response dropped, so the outcome is
 * unknown and the run is treated as pull-unsafe rather than risk mirroring a just-created row.
 *
 * If the create succeeds on the server but the local `_ID` write-back then fails
 * (WRITE_CONTACTS revoked mid-run, or a short provider batch), SOURCE_ID stays blank and
 * DIRTY stays set — the push reports the net-new edit [ContactPushOutcome.PULL_UNSAFE],
 * which makes the enclosing pull skip its whole reconciliation this run (no same-run
 * duplicate), since the server now holds a resource this row is not matched to. On the next run the
 * create is retried at the SAME resource name: the persisted `<uid>.vcf` is stable, so the
 * re-attempt's `If-None-Match: *` fails its precondition (the resource is already there),
 * and rather than swallow the 412 we GET the resource and adopt it when its UID matches the
 * persisted one — stamping SOURCE_ID + etag onto the originating row so a later pull matches
 * instead of mirroring a duplicate (see [adoptExistingCreate]). The one remaining gap is a
 * transient GET failure during that re-attempt window: it defers (clean), so the pull runs
 * and can insert a one-run duplicate, which self-heals once the adopt GET later succeeds.
 *
 * ## Known limitation — a re-edit during this contact's push window
 *
 * If the user edits a contact again in the narrow window between this push reading
 * it and the write-back clearing its DIRTY flag, that second edit resolves to
 * server-wins on a later pull rather than being pushed. This is inherent to the
 * flag-based, no-queue model: the DIRTY flag is a single bit with no version, so
 * the write-back can't tell "still the version I pushed" from "edited again since".
 * Preserving the second edit would need version-conditioned write-back *and*
 * pull-side conflict handling (the pull replaces purely on an etag difference and
 * does not exempt locally-dirty rows) — a three-way merge well beyond this scope.
 * The window is one network round-trip per contact and the outcome is consistent
 * with the documented server-wins conflict policy.
 *
 * Never throws for a bad contact: every per-item failure is logged and reported as
 * not-clean so one bad contact can't abort the whole push (cooperative
 * cancellation is the one thing that does propagate). The credential-bearing
 * [CardDavClient] is supplied per [push] call; only the provider repository is
 * injected.
 */
class ContactPushStrategy @Inject constructor(
    private val contactsProvider: ContactsProviderRepository,
) {

    private val writer = VCardWriter()

    /**
     * Push every pending edit and delete for [accountName] to the server, reading
     * patch bases through [client] against the collections in [books] (this run's
     * discovered address books). Deletes are pushed before uploads.
     *
     * Returns a [ContactPushOutcome]: `clean` is true when every change was applied or
     * cleanly deferred (a not-clean push holds every book's sync token so the run
     * replays); `pullUnsafe` is true only when a **net-new** (blank-href) edit ended
     * not-clean, the single case where letting the pull run this same run could mirror a
     * just-created server resource as a duplicate row (see [ContactPushOutcome]).
     */
    suspend fun push(
        accountName: String,
        books: List<CardDavAddressBook>,
        client: CardDavClient,
    ): ContactPushOutcome {
        val changes = contactsProvider.pendingLocalChanges(accountName)
        if (changes.edited.isEmpty() && changes.deleted.isEmpty()) {
            return ContactPushOutcome.CLEAN
        }

        val reader = CardDavContactReader(client)
        var clean = true
        var pullUnsafe = false

        // Deletes first: a delete-then-recreate at the same href must not race the
        // recreate's create-as-fresh against a still-present resource. A delete creates
        // no new server row, so a failed delete never makes the pull unsafe.
        for (tombstone in changes.deleted) {
            clean = runItem { applyDelete(accountName, tombstone, books, client) } && clean
        }
        for (edit in changes.edited) {
            val outcome = if (edit.href.isBlank()) {
                // A net-new create is the only edit that can be pull-unsafe, and only when it
                // confirmed a server resource whose local write-back then failed —
                // applyNewUpload draws that distinction and returns the precise outcome.
                runNewUpload { applyNewUpload(accountName, edit, books, reader, client) }
            } else {
                // An existing-contact failure has an href the pull reconciles correctly, so it
                // is never pull-unsafe; carry only its clean/not-clean signal.
                if (runItem { applyExistingUpload(accountName, edit, books, reader, client) }) {
                    ContactPushOutcome.CLEAN
                } else {
                    ContactPushOutcome.DEFERRED
                }
            }
            clean = outcome.clean && clean
            pullUnsafe = pullUnsafe || outcome.pullUnsafe
        }
        return ContactPushOutcome(clean = clean, pullUnsafe = pullUnsafe)
    }

    /** Runs one push item, degrading an unexpected throw to not-clean rather than aborting the push. */
    private suspend inline fun runItem(block: () -> Boolean): Boolean =
        try {
            block()
        } catch (e: CancellationException) {
            // The worker was stopped mid-push: abort cooperatively rather than
            // logging one item as failed and pressing on to the next.
            throw e
        } catch (e: Exception) {
            // Log the type only, never the throwable: a downstream message could
            // embed an href or email, and this write path must keep PII out of logs.
            Log.w(TAG, "Contact push item failed unexpectedly; left pending: ${e.javaClass.simpleName}")
            false
        }

    /**
     * Like [runItem] but for a net-new create, which carries a [ContactPushOutcome] rather
     * than a bare success bit. An unexpected throw is degraded to [ContactPushOutcome.PULL_UNSAFE]:
     * the throw could land after the server created the resource but before the write-back,
     * and an extra one-run pull-skip is harmless whereas running the pull could duplicate a
     * just-created row.
     */
    private suspend inline fun runNewUpload(block: () -> ContactPushOutcome): ContactPushOutcome =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Contact push item failed unexpectedly; left pending: ${e.javaClass.simpleName}")
            ContactPushOutcome.PULL_UNSAFE
        }

    private suspend fun applyDelete(
        accountName: String,
        tombstone: LocalContactTombstone,
        books: List<CardDavAddressBook>,
        client: CardDavClient,
    ): Boolean {
        val href = tombstone.href
        if (href.isBlank()) {
            // Created on the device and deleted before it was ever uploaded: nothing
            // exists on the server, so just drop the local tombstone.
            return contactsProvider.hardDeleteTombstone(accountName, href).isSuccess
        }
        // Book not discovered this run (its home failed to enumerate, or it was
        // removed): leave the tombstone for the next run. The DELETED flag persists
        // independently of the sync token, so this is a clean deferral.
        val book = bookForHref(href, books) ?: return true
        if (book.isReadOnly) {
            // A read-only collection can't accept the DELETE; un-delete locally so the
            // mirror keeps reflecting the server rather than silently diverging.
            return contactsProvider.restoreTombstone(accountName, href).isSuccess
        }
        val etag = tombstone.storedEtag
        if (etag.isNullOrBlank()) {
            // No known version to condition the DELETE on; drop locally rather than
            // send a malformed If-Match.
            return contactsProvider.hardDeleteTombstone(accountName, href).isSuccess
        }
        return when (client.deleteContact(resolveResourceUrl(href, book.url), etag)) {
            ContactDeleteResult.Deleted, ContactDeleteResult.AlreadyGone ->
                contactsProvider.hardDeleteTombstone(accountName, href).isSuccess
            // Server copy changed since our version: we can't delete a newer state out
            // from under a concurrent editor. Un-delete locally so this run's pull
            // re-materializes the server's current copy (server-wins), rather than
            // leaving a tombstone whose etag a later pull would refresh — which would
            // then delete the edited server copy for real on the next push.
            ContactDeleteResult.PreconditionFailed ->
                contactsProvider.restoreTombstone(accountName, href).isSuccess
            is ContactDeleteResult.Failed -> false
        }
    }

    /**
     * Create a net-new device contact (blank href) in the first writable book, returning the
     * precise [ContactPushOutcome]: pull-unsafe ONLY when a server resource was confirmed but
     * its local write-back failed; a server-refused create or a pre-PUT persist failure holds
     * the token but stays pull-safe.
     */
    private suspend fun applyNewUpload(
        accountName: String,
        edit: LocalContactEdit,
        books: List<CardDavAddressBook>,
        reader: CardDavContactReader,
        client: CardDavClient,
    ): ContactPushOutcome {
        // No writable book discovered this run: leave DIRTY, hold nothing against it (clean).
        val book = books.firstOrNull { !it.isReadOnly } ?: return ContactPushOutcome.CLEAN
        // A device-created contact has no UID (RFC 6350 §6.7.6), so synthesize a globally-unique
        // one and persist it to SYNC1 BEFORE the PUT. Naming the resource <uid>.vcf by a global
        // UID (not the per-device RawContact _ID) is what stops two devices colliding on the same
        // name and adopting each other's copy. A re-attempt reads the SAME persisted UID, so it
        // targets the SAME resource and can 412+adopt. A UID-bearing contact keeps its <uid>.vcf name.
        val synthesized = edit.uid.isBlank()
        val uid = if (synthesized) UUID.randomUUID().toString() else edit.uid
        if (synthesized) {
            // Persist first: if this fails, do NOT create. Nothing exists server-side, so this is
            // a clean-holding deferral (pull-safe) that replays next run once the UID persists.
            if (contactsProvider.assignContactUid(accountName, edit.localId, uid).isFailure) {
                return ContactPushOutcome.DEFERRED
            }
        }
        val url = book.url.trimEnd('/') + "/" + contactResourceName(uid)
        val createdHref = pathOf(url)
        val body = writer.write(edit.contact.copy(uid = uid, rawVCard = ""), book.vcardVersion)
        val result = client.putContact(url, body, ContactPrecondition.IfAbsent)
        // A precondition failure means a resource already occupies our deterministic,
        // UID-derived name. That is almost always THIS contact from a prior run whose
        // local write-back failed (SOURCE_ID never got stamped), so adopt it rather than
        // loop the 412 forever: GET the resource and, when its UID matches, stamp the
        // created href + etag onto the originating row — closing the duplicate a later
        // pull would otherwise mirror. Any doubt defers cleanly (see adoptExistingCreate).
        if (result == ContactUploadResult.PreconditionFailed) {
            return adoptExistingCreate(accountName, edit, uid, createdHref, book, reader)
        }
        return handleNewUploadResult(accountName, edit.localId, createdHref, result)
    }

    private suspend fun applyExistingUpload(
        accountName: String,
        edit: LocalContactEdit,
        books: List<CardDavAddressBook>,
        reader: CardDavContactReader,
        client: CardDavClient,
    ): Boolean {
        val href = edit.href
        val book = bookForHref(href, books) ?: return true
        if (book.isReadOnly) {
            // A read-only collection would 403 every PUT forever. Skip the upload (before
            // the GET-before-PUT round-trip) and leave the edit DIRTY: it uploads if the
            // book later becomes writable, rather than looping a guaranteed 403 each sync.
            // This is a deferral, not a server-wins drop — nothing local is discarded.
            return true
        }
        val storedEtag = edit.storedEtag
        if (storedEtag.isNullOrBlank()) {
            // The server never gave us a version; create-as-fresh rather than
            // If-Match(null), which no server would honor.
            return putFresh(accountName, href, book, edit.contact, client)
        }
        return when (val read = reader.readContacts(book.url, listOf(href), book.vcardVersion)) {
            is CalDavResult.Error ->
                // Whole collection gone (404/410): recreate as fresh. A single deleted
                // member is not an error — it comes back 207 with the href simply
                // absent, which the Success branch below defers as base == null.
                // Transient/5xx: hold and retry.
                if (read.code == 404 || read.code == 410) putFresh(accountName, href, book, edit.contact, client)
                else false
            is CalDavResult.Success -> {
                val base = read.data.contacts.firstOrNull { it.href == href }
                when {
                    // Patch base absent or unparseable: defer, never PUT blind.
                    base == null -> true
                    // Server copy moved on since the edit was made: defer, don't clobber.
                    base.etag != storedEtag -> true
                    else -> {
                        val url = resolveResourceUrl(href, book.url)
                        val body = writer.write(
                            edit.contact.copy(rawVCard = base.contact.rawVCard), book.vcardVersion,
                        )
                        val result = client.putContact(url, body, ContactPrecondition.IfMatch(storedEtag))
                        // The conditional target vanished mid-flight: retry once as fresh.
                        if (result is ContactUploadResult.Gone) putFresh(accountName, href, book, edit.contact, client)
                        else handleUploadResult(accountName, href, result)
                    }
                }
            }
        }
    }

    /** Upload [contact] to [href] as a brand-new resource (`If-None-Match: *`). */
    private suspend fun putFresh(
        accountName: String,
        href: String,
        book: CardDavAddressBook,
        contact: org.onekash.vcard.model.Contact,
        client: CardDavClient,
    ): Boolean {
        val url = resolveResourceUrl(href, book.url)
        val body = writer.write(contact.copy(rawVCard = ""), book.vcardVersion)
        return handleUploadResult(accountName, href, client.putContact(url, body, ContactPrecondition.IfAbsent))
    }

    private suspend fun handleUploadResult(
        accountName: String,
        href: String,
        result: ContactUploadResult,
    ): Boolean = onUploadOutcome(result) { success ->
        contactsProvider.markContactUploaded(accountName, href, success.etag.orEmpty()).isSuccess
    }

    /**
     * The net-new counterpart to [handleUploadResult]: a created contact has no prior
     * href, so its success is written back by the originating RawContact's [localId]
     * ([ContactsProviderRepository.markNewContactUploaded]) — stamping the new [href]
     * onto SOURCE_ID and clearing DIRTY so the next pull matches, not duplicates.
     *
     * Unlike [handleUploadResult] it returns a full [ContactPushOutcome], because the
     * pull-safety of a net-new failure depends on WHETHER a resource was created:
     * a [ContactUploadResult.Success] whose write-back fails is [ContactPushOutcome.PULL_UNSAFE]
     * (the server holds a resource this row isn't matched to). A [ContactUploadResult.Failed]
     * splits by whether the creation state is known: a real HTTP refusal (non-zero status)
     * created nothing and is a pull-safe [ContactPushOutcome.DEFERRED], but a transport failure
     * (code 0: the response was lost, so the create may have committed) is unknown-state and
     * is [ContactPushOutcome.PULL_UNSAFE]. 412 is intercepted by the caller (the adopt path)
     * and never reaches here.
     */
    private suspend fun handleNewUploadResult(
        accountName: String,
        localId: Long,
        href: String,
        result: ContactUploadResult,
    ): ContactPushOutcome = when (result) {
        is ContactUploadResult.Success ->
            if (contactsProvider.markNewContactUploaded(accountName, localId, href, result.etag.orEmpty()).isSuccess) {
                ContactPushOutcome.CLEAN
            } else {
                ContactPushOutcome.PULL_UNSAFE
            }
        // 403 no write privilege / target vanished: clean deferral, nothing created.
        ContactUploadResult.PermissionDenied, ContactUploadResult.Gone -> ContactPushOutcome.CLEAN
        // Intercepted by applyNewUpload before this call; mapped defensively for exhaustiveness.
        ContactUploadResult.PreconditionFailed -> ContactPushOutcome.CLEAN
        // A transport failure (code 0: lost response / connection reset, always retryable) is
        // NOT a refusal — the PUT may have committed server-side before the response was lost,
        // so the creation state is unknown. Running the pull this cycle could then mirror a
        // just-created resource as a duplicate, so treat unknown-state as pull-unsafe. A real
        // HTTP refusal (non-zero status: 400/415/422/507/persistent 5xx) created nothing, so it
        // stays a pull-safe deferral — one rejected contact must never freeze all inbound sync.
        is ContactUploadResult.Failed ->
            if (result.code == 0) ContactPushOutcome.PULL_UNSAFE else ContactPushOutcome.DEFERRED
    }

    /**
     * Adopt the server resource a net-new create's `If-None-Match: *` collided with.
     * A collision at our deterministic `<uid>.vcf` name — where [uid] is the contact's
     * own UID, or the globally-unique UID synthesized and persisted for a device-created
     * contact — is almost always THIS contact from an earlier run whose write-back failed,
     * so GET the resource and, when its UID matches [uid], stamp the created [createdHref]
     * + etag onto the originating row by its `_ID` (clearing DIRTY) so the next pull
     * matches it instead of mirroring a second row.
     *
     * Because [uid] is globally unique even for a device-created contact (it was persisted
     * to SYNC1 before the first create), the match is a real UID comparison, never a
     * blank-vs-blank one — so a second device that minted its own distinct UID can never be
     * mistaken for this contact. Never adopts a resource it cannot prove is ours: a
     * missing/unreadable resource, a UID mismatch (a genuine foreign name collision), or a
     * transient GET error all leave the row DIRTY and return clean — the same clean
     * deferral swallowing the 412 gave, retried on a later run.
     *
     * Adoption relies on the server preserving the vCard UID it was PUT: a server that
     * rewrites UID on store (rare for CardDAV members, unlike the ORGANIZER rewriting some
     * calendar servers do) would fail the match and defer cleanly rather than adopt, which
     * is the safe outcome — no data loss, just a replay.
     */
    private suspend fun adoptExistingCreate(
        accountName: String,
        edit: LocalContactEdit,
        uid: String,
        createdHref: String,
        book: CardDavAddressBook,
        reader: CardDavContactReader,
    ): ContactPushOutcome {
        val read = reader.readContacts(book.url, listOf(createdHref), book.vcardVersion)
        if (read is CalDavResult.Success) {
            val existing = read.data.contacts.firstOrNull { it.href == createdHref }
            if (existing != null && existing.contact.uid == uid) {
                // The resource exists and is proven ours: stamp it. If the stamp succeeds the
                // row now matches; if it fails, the server still holds a resource this row
                // isn't matched to — pull-unsafe this run so the pull can't mirror a duplicate.
                return if (contactsProvider.markNewContactUploaded(
                        accountName, edit.localId, createdHref, existing.etag.orEmpty(),
                    ).isSuccess
                ) {
                    ContactPushOutcome.CLEAN
                } else {
                    ContactPushOutcome.PULL_UNSAFE
                }
            }
        }
        // Not ours, unreadable, or a transient GET error: defer cleanly and retry on a later
        // run — the same clean deferral swallowing the 412 gave.
        return ContactPushOutcome.CLEAN
    }

    /**
     * The shared clean/not-clean policy for a PUT outcome: only [onSuccess] (the
     * write-back that records the server's etag) differs between an href-keyed update
     * and a net-new create, so the deferral semantics for every non-success outcome
     * live here once. 412/409 (the server copy wins the next pull), 403 (no write
     * privilege), and Gone (target vanished, the next pull reconciles) are all clean
     * deferrals that hold DIRTY; only a transport/5xx [ContactUploadResult.Failed] —
     * or a write-back that itself failed — is not-clean.
     */
    private suspend fun onUploadOutcome(
        result: ContactUploadResult,
        onSuccess: suspend (ContactUploadResult.Success) -> Boolean,
    ): Boolean = when (result) {
        is ContactUploadResult.Success -> onSuccess(result)
        ContactUploadResult.PreconditionFailed -> true
        ContactUploadResult.PermissionDenied -> true
        ContactUploadResult.Gone -> true
        is ContactUploadResult.Failed -> false
    }

    /**
     * The discovered book whose collection path is a prefix of [href]'s path (longest
     * match wins for nested collections), or null when none matches. Path-only compare
     * tolerates a server-relative href against an absolute book URL; the trailing-slash
     * normalization keeps `/ab/default` from swallowing a sibling `/ab/default-2/…`.
     */
    private fun bookForHref(href: String, books: List<CardDavAddressBook>): CardDavAddressBook? {
        val hrefPath = pathOf(href)
        return books
            .filter { hrefPath.startsWith(withTrailingSlash(pathOf(it.url))) }
            .maxByOrNull { pathOf(it.url).length }
    }

    /**
     * Absolute resource URL for a PUT/DELETE: an already-absolute href is used
     * verbatim; a server-relative href is resolved against the book URL's scheme and
     * authority.
     */
    private fun resolveResourceUrl(href: String, bookUrl: String): String {
        if (href.startsWith("http", ignoreCase = true)) return href
        val base = try {
            val uri = java.net.URI(bookUrl)
            "${uri.scheme}://${uri.authority}"
        } catch (_: Exception) {
            bookUrl.trimEnd('/')
        }
        return if (href.startsWith("/")) "$base$href" else "$base/$href"
    }

    private fun withTrailingSlash(path: String): String =
        if (path.endsWith("/")) path else "$path/"

    private fun pathOf(urlOrPath: String): String =
        try {
            java.net.URI(urlOrPath).path ?: urlOrPath
        } catch (_: Exception) {
            urlOrPath
        }

    companion object {
        private const val TAG = "ContactPushStrategy"
    }
}
