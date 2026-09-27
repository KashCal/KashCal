package org.onekash.kashcal.sync.contacts

import android.util.Log
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
 * Result of [ContactPushStrategy.push].
 *
 * [clean] gates every sync-token advance: a push that is not clean holds every book's token so
 * the whole run replays.
 *
 * [pullUnsafe] is narrower. The pull matches rows by href, so while a net-new contact's href is
 * still blank, a server resource created for it would be mirrored as a second row. The pull is
 * skipped this run only when such a resource may exist:
 * - a create was confirmed (2xx, or a 412 adopt of a resource proven ours) but the `_ID`
 *   write-back failed
 * - a create failed in transport (code 0): the PUT may have committed before the response was lost
 * - a create threw unexpectedly ([runNewUpload]): the throw may have come after the server
 *   created the resource
 *
 * Everything else leaves the pull safe even when not clean: an HTTP refusal of a create, a UID
 * persist failure before the PUT, a request the transport guard refused to send, any
 * existing-contact failure (its href lets the pull reconcile) and every delete. One contact the
 * server keeps rejecting therefore can't block inbound sync for the account.
 *
 * @property clean every change was applied or cleanly deferred.
 * @property pullUnsafe the pull must be skipped this run.
 */
data class ContactPushOutcome(
    val clean: Boolean,
    val pullUnsafe: Boolean,
) {
    companion object {
        /** Applied or cleanly deferred: advance the token and pull. */
        val CLEAN = ContactPushOutcome(clean = true, pullUnsafe = false)

        /** Not applied and nothing created: hold the token, the pull still runs. */
        val DEFERRED = ContactPushOutcome(clean = false, pullUnsafe = false)

        /**
         * A server resource may exist that no local row is matched to: hold the token and skip
         * the pull, which would otherwise mirror the resource as a duplicate.
         */
        val PULL_UNSAFE = ContactPushOutcome(clean = false, pullUnsafe = true)
    }
}

/**
 * Pushes pending device contact edits and deletes to CardDAV. There is no operation queue: the
 * pending set is the provider's DIRTY/DELETED flags, and each locator carries the href, UID and
 * last-seen server etag (SYNC2), which is all a conditional PUT or DELETE needs.
 *
 * ## Updates: GET before PUT, conditioned on the stored etag
 *
 * The device keeps no verbatim vCard, and one rebuilt from the mapped fields would drop every
 * X-property, itemN group and unmapped parameter. So an update GETs the server body through
 * [CardDavContactReader] and gives it to [VCardWriter] as the patch base; the writer rewrites
 * only the changed facets and leaves the rest untouched.
 *
 * The PUT's `If-Match` is the stored etag (the version the edit was made against), not the one
 * the GET returned. If they differ the server copy changed after the edit, so the edit stays
 * DIRTY for the next pull to reconcile instead of overwriting the newer copy. A create, or an
 * update with no stored etag, sends `If-None-Match: *`, never an empty `If-Match`.
 *
 * ## Deferral vs failure
 *
 * A PUT 412/409, a GET etag mismatch, an unreadable patch base and a 403 leave the edit DIRTY
 * and still count as clean. A DELETE 412/409 is also clean but un-deletes the row, so this
 * run's pull restores the server copy (server wins); a kept tombstone would get its etag
 * refreshed by a later pull and the next push would delete the edited copy. Only a server or
 * transport error (a `Failed` PUT or DELETE, or a patch-base GET error other than 404/410), an
 * unexpected throw, or a failed provider write-back is not clean.
 *
 * ## Net-new contacts
 *
 * A contact created on the device has a blank SOURCE_ID (href) and usually no vCard UID
 * (RFC 6350 §6.7.6 makes UID optional). Before the first PUT the push generates a UUID,
 * persists it to SYNC1 ([ContactsProviderRepository.assignContactUid], DIRTY stays set) and
 * names the resource `<uid>.vcf`. The RawContact `_ID` can't be the name: two devices can both
 * mint `_ID = 100`, collide on one resource and each adopt the other's copy, losing a contact.
 *
 * On success the new href is stamped onto SOURCE_ID and DIRTY cleared by the row's `_ID`
 * ([ContactsProviderRepository.markNewContactUploaded]), so the next pull matches the server
 * copy instead of mirroring it. Radicale, Baikal, Nextcloud and iCloud list a client-created
 * member by its request path, the form stamped here (verified live).
 *
 * If that write-back fails (WRITE_CONTACTS revoked mid-run, a short provider batch), the next
 * run's create targets the same `<uid>.vcf`, gets a 412, GETs the resource and adopts it when
 * the UID matches ([adoptExistingCreate]). A transient GET failure during that retry defers
 * cleanly, so the pull can insert a duplicate for one run; it clears once the adopt succeeds.
 * Which outcomes skip the pull is documented on [ContactPushOutcome].
 *
 * ## Known limitation: a re-edit during the push
 *
 * An edit made between this push reading a contact and clearing its DIRTY flag is lost to
 * server wins on a later pull. DIRTY is one bit with no version, so the write-back can't tell
 * the pushed version from a newer edit. Keeping it would need a version-conditioned write-back
 * and pull-side conflict handling (the pull replaces on any etag change and doesn't exempt
 * dirty rows). The window is one round trip per contact, and the result matches the
 * server-wins conflict policy.
 *
 * One bad contact never aborts the push: each item's failure is logged and reported as not
 * clean, and only cancellation propagates. The credential-bearing [CardDavClient] is passed
 * per [push] call; only the provider repository is injected.
 */
class ContactPushStrategy @Inject constructor(
    private val contactsProvider: ContactsProviderRepository,
) {

    private val writer = VCardWriter()

    /**
     * Pushes every pending edit and delete for [accountName], reading patch bases through
     * [client] from [books] (this run's discovered address books). Deletes go first.
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

        // Deletes first, so a delete-then-recreate at the same href doesn't send its
        // `If-None-Match: *` while the old resource still exists. A failed delete creates
        // nothing, so it never makes the pull unsafe.
        for (tombstone in changes.deleted) {
            clean = runItem { applyDelete(accountName, tombstone, books, client) } && clean
        }
        for (edit in changes.edited) {
            val outcome = if (edit.href.isBlank()) {
                // Only a net-new create can be pull-unsafe; see runNewUpload and applyNewUpload.
                runNewUpload { applyNewUpload(accountName, edit, books, reader, client) }
            } else {
                // An existing contact has an href the pull reconciles, so it is never pull-unsafe.
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

    /** Runs one push item. An unexpected throw returns false instead of aborting the push. */
    private suspend inline fun runItem(block: () -> Boolean): Boolean =
        try {
            block()
        } catch (e: CancellationException) {
            // The worker was stopped: propagate instead of logging this item as failed.
            throw e
        } catch (e: Exception) {
            // Log the exception type, never the throwable: its message can carry an href or
            // an email address.
            Log.w(TAG, "Contact push item failed unexpectedly; left pending: ${e.javaClass.simpleName}")
            false
        }

    /**
     * [runItem] for a net-new create. An unexpected throw returns [ContactPushOutcome.PULL_UNSAFE]:
     * it may have come after the server created the resource and before the write-back, and
     * skipping one pull is harmless while running it could duplicate the row.
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
            // Created and deleted on the device before any upload: nothing is on the server,
            // so drop the tombstone.
            return contactsProvider.hardDeleteTombstone(accountName, href).isSuccess
        }
        // Book not discovered this run (its home failed to enumerate, or it was removed):
        // keep the tombstone. DELETED doesn't depend on the sync token, so this is a clean
        // deferral.
        val book = bookForHref(href, books) ?: return true
        if (book.isReadOnly) {
            // A read-only collection refuses the DELETE; un-delete locally so the device
            // keeps matching the server.
            return contactsProvider.restoreTombstone(accountName, href).isSuccess
        }
        val etag = tombstone.storedEtag
        if (etag.isNullOrBlank()) {
            // No etag to condition the DELETE on, and an empty If-Match is malformed:
            // drop locally.
            return contactsProvider.hardDeleteTombstone(accountName, href).isSuccess
        }
        return when (client.deleteContact(resolveResourceUrl(href, book.url), etag)) {
            ContactDeleteResult.Deleted, ContactDeleteResult.AlreadyGone ->
                contactsProvider.hardDeleteTombstone(accountName, href).isSuccess
            // The server copy changed after our version. Un-delete so this run's pull
            // restores it (server wins). A kept tombstone would get its etag refreshed by a
            // later pull, and the next push would then delete the edited copy.
            ContactDeleteResult.PreconditionFailed ->
                contactsProvider.restoreTombstone(accountName, href).isSuccess
            is ContactDeleteResult.Failed -> false
        }
    }

    /**
     * Creates a net-new device contact (blank href) in the first writable book. Which results
     * are pull-unsafe is documented on [ContactPushOutcome].
     */
    private suspend fun applyNewUpload(
        accountName: String,
        edit: LocalContactEdit,
        books: List<CardDavAddressBook>,
        reader: CardDavContactReader,
        client: CardDavClient,
    ): ContactPushOutcome {
        // No writable book this run: leave DIRTY and report clean.
        val book = books.firstOrNull { !it.isReadOnly } ?: return ContactPushOutcome.CLEAN
        // Persist a generated UID to SYNC1 before the PUT (the class doc explains why the name
        // can't be the _ID). A retry reads the same UID, targets the same <uid>.vcf and can
        // adopt it on 412. A contact that already has a UID keeps its name.
        val synthesized = edit.uid.isBlank()
        val uid = if (synthesized) UUID.randomUUID().toString() else edit.uid
        if (synthesized) {
            // If persisting fails, don't create. Nothing exists on the server, so this defers
            // pull-safe and replays next run.
            if (contactsProvider.assignContactUid(accountName, edit.localId, uid).isFailure) {
                return ContactPushOutcome.DEFERRED
            }
        }
        val url = book.url.trimEnd('/') + "/" + contactResourceName(uid)
        val createdHref = pathOf(url)
        val body = writer.write(edit.contact.copy(uid = uid, rawVCard = ""), book.vcardVersion)
        val result = client.putContact(url, body, ContactPrecondition.IfAbsent)
        // 412: something already occupies our UID-derived name, almost always this contact
        // from an earlier run whose write-back failed. Adopt it instead of hitting the 412 on
        // every run.
        if (result == ContactUploadResult.PreconditionFailed) {
            return adoptExistingCreate(accountName, edit, uid, createdHref, book, reader)
        }
        // A create the server redirected lives where it landed, not at the name we chose.
        val storedHref = (result as? ContactUploadResult.Success)?.finalUrl?.let { hrefFor(it, book.url) } ?: createdHref
        return handleNewUploadResult(accountName, edit.localId, storedHref, result)
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
            // A read-only collection would 403 every PUT. Skip the GET and PUT and leave the
            // edit DIRTY: it uploads if the book becomes writable, and nothing local is lost.
            return true
        }
        val storedEtag = edit.storedEtag
        if (storedEtag.isNullOrBlank()) {
            // The server never gave an etag: create-as-fresh with `If-None-Match: *`.
            return putFresh(accountName, href, book, edit.contact, client)
        }
        return when (val read = reader.readContacts(book.url, listOf(href), book.vcardVersion)) {
            is CalDavResult.Error ->
                // Collection gone (404/410): recreate as fresh. A deleted member isn't an
                // error: it comes back in a 207 without the href, which the Success branch
                // defers as base == null. Anything else: hold and retry.
                if (read.code == 404 || read.code == 410) putFresh(accountName, href, book, edit.contact, client)
                else false
            is CalDavResult.Success -> {
                val base = read.data.contacts.firstOrNull { it.href == href }
                when {
                    // Patch base absent or unparseable: defer, never PUT blind.
                    base == null -> true
                    // The server copy changed after the edit: defer, don't overwrite.
                    base.etag != storedEtag -> true
                    else -> {
                        val url = resolveResourceUrl(href, book.url)
                        val body = writer.write(
                            edit.contact.copy(rawVCard = base.contact.rawVCard), book.vcardVersion,
                        )
                        val result = client.putContact(url, body, ContactPrecondition.IfMatch(storedEtag))
                        // The If-Match target disappeared mid-flight: retry once as fresh.
                        if (result is ContactUploadResult.Gone) putFresh(accountName, href, book, edit.contact, client)
                        else handleUploadResult(accountName, href, book.url, result)
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
        return handleUploadResult(accountName, href, book.url, client.putContact(url, body, ContactPrecondition.IfAbsent))
    }

    private suspend fun handleUploadResult(
        accountName: String,
        href: String,
        bookUrl: String,
        result: ContactUploadResult,
    ): Boolean = onUploadOutcome(result) { success ->
        val movedTo = success.finalUrl?.let { hrefFor(it, bookUrl) }
        contactsProvider.markContactUploaded(accountName, href, success.etag.orEmpty(), newHref = movedTo).isSuccess
    }

    /**
     * The href to store for a vCard the server redirected to [finalUrl]: its encoded
     * path when it stayed on the address book's server (the form pulls list and match
     * exactly), else the whole URL, which [resolveResourceUrl] passes through as is.
     */
    private fun hrefFor(finalUrl: String, bookUrl: String): String {
        val landed = finalUrl.toHttpUrlOrNull() ?: return finalUrl
        val book = bookUrl.toHttpUrlOrNull() ?: return finalUrl
        val sameServer = landed.scheme == book.scheme && landed.host == book.host && landed.port == book.port
        return if (sameServer) landed.encodedPath else finalUrl
    }

    /**
     * [handleUploadResult] for a net-new create. The contact has no href yet, so success is
     * written back by the row's [localId] ([ContactsProviderRepository.markNewContactUploaded]),
     * stamping [href] onto SOURCE_ID and clearing DIRTY. Returns a full [ContactPushOutcome]
     * because pull safety depends on whether a resource may exist. A 412 never reaches here;
     * [applyNewUpload] adopts it.
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
        // 403 or target gone: clean deferral, nothing created.
        ContactUploadResult.PermissionDenied, ContactUploadResult.Gone -> ContactPushOutcome.CLEAN
        // applyNewUpload handles 412 before this call; mapped for exhaustiveness.
        ContactUploadResult.PreconditionFailed -> ContactPushOutcome.CLEAN
        // Code 0 is a transport failure: the PUT may have committed before the response was
        // lost, so a resource may exist (pull-unsafe). Any other code, an HTTP refusal or a
        // request the transport guard refused to send, created nothing (pull-safe).
        is ContactUploadResult.Failed ->
            if (result.code == 0) ContactPushOutcome.PULL_UNSAFE else ContactPushOutcome.DEFERRED
    }

    /**
     * Adopts the resource a net-new create's `If-None-Match: *` hit at `<uid>.vcf`. When a GET
     * shows its UID equals [uid], stamps [createdHref] and its etag onto the row by `_ID`
     * (clearing DIRTY) so the next pull matches it.
     *
     * [uid] is the contact's own UID or the one persisted to SYNC1 before the first create, so
     * the match is never blank against blank and another device's contact can't pass it. A
     * missing or unreadable resource, a UID mismatch (a real name collision) or a GET error
     * leaves the row DIRTY and returns clean, to retry next run. A server that rewrote the UID
     * on store would fail the match on every run and keep deferring; no data is lost.
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
                // Proven ours: stamp it. If the stamp fails the row is still unmatched, so
                // this run is pull-unsafe.
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
        // Not ours, unreadable, or a GET error: defer cleanly and retry next run.
        return ContactPushOutcome.CLEAN
    }

    /**
     * Clean or not clean for a PUT to an href-keyed contact; [onSuccess] records the server's
     * etag. 412/409 (the next pull brings the server copy), 403 and Gone (the next pull
     * reconciles) keep DIRTY and are clean. [ContactUploadResult.Failed] or a failed
     * [onSuccess] is not clean. Net-new creates use [handleNewUploadResult] instead.
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
     * The discovered book whose path is the longest prefix of [href]'s path, or null.
     * Comparing paths lets a server-relative href match an absolute book URL; the trailing
     * slash keeps `/ab/default` from matching a sibling `/ab/default-2/…`.
     */
    private fun bookForHref(href: String, books: List<CardDavAddressBook>): CardDavAddressBook? {
        val hrefPath = pathOf(href)
        return books
            .filter { hrefPath.startsWith(withTrailingSlash(pathOf(it.url))) }
            .maxByOrNull { pathOf(it.url).length }
    }

    /**
     * Absolute URL for a PUT or DELETE: an absolute href as is, a server-relative one against
     * the book URL's scheme and authority.
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
