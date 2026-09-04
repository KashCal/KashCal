package org.onekash.kashcal.sync.contacts

import org.onekash.kashcal.data.contacts.MappedContact
import org.onekash.vcard.model.Contact

/**
 * One CardDAV-synced contact ready to be written to the Android Contacts
 * Provider: the mapped Data-row set plus the sync coordinates that live on the
 * RawContact SYNC columns rather than in the row body.
 *
 * The SYNC layout is settled (see the design doc): SOURCE_ID = [href],
 * SYNC1 = the contact's UID (or blank when the body carried none — RFC 6350
 * §6.7.6 gives `UID` cardinality `*1`), SYNC2 = [etag], SYNC3 = a content hash,
 * SYNC4 = a flag bitset. [href] is the CRUD locator and the account-unique,
 * always-present key Android's aggregation relies on; a blank UID is never a
 * reconciliation match key downstream.
 *
 * @property href the resource href exactly as returned by the server.
 * @property etag the entity tag, or null when the server omitted one.
 * @property mapped the mimetype-tagged Data rows for this one RawContact,
 *   already emitted by [org.onekash.kashcal.data.contacts.VCardContactMapper]
 *   (its `dataRows[0]` is the StructuredName; the write layer never synthesizes
 *   its own).
 * @property isReadOnly whether the owning address book is read-only. When true the
 *   RawContact is written non-editable (`RAW_CONTACT_IS_READ_ONLY = 1`) so the user
 *   cannot edit a contact that could never be pushed back; when false the row is
 *   editable, so a user edit flips the provider's DIRTY bit — the signal the push
 *   path reads. Defaults to read-only so a forgotten call site fails safe (never
 *   silently makes a contact editable that has nowhere to push).
 */
data class MappedContactWrite(
    val href: String,
    val etag: String?,
    val mapped: MappedContact,
    val isReadOnly: Boolean = true,
)

/**
 * A locally-edited contact awaiting push to the server, read back off the
 * Contacts Provider from a RawContact the provider flagged `DIRTY` after a
 * device-side edit.
 *
 * Book-agnostic by design: it carries the raw sync locators ([href], [uid],
 * [storedEtag]) exactly as they sit on the RawContact SYNC columns, plus the
 * device field values reverse-mapped into [contact]. The push strategy joins
 * each locator to its discovered address book (by [href]) to learn the book's
 * write-back policy and serialization version — this layer never reaches for a
 * book.
 *
 * @property href the resource `SOURCE_ID`; blank for a contact created on the
 *   device that has never been pushed (no server resource yet — a create).
 * @property uid the `SYNC1` UID (blank when the original body carried none).
 * @property storedEtag the `SYNC2` ETag the row was last written with — the
 *   If-Match validator for a conditional PUT. Null/blank when the server omitted
 *   one, in which case the push has no validator and falls back to create-as-fresh.
 * @property contact the device field values reverse-mapped by
 *   [org.onekash.kashcal.data.contacts.DeviceContactRowMapper]. Its `rawVCard`
 *   and `version` are defaults here (not stored on Data rows); the push strategy
 *   composes the real serialization base (server body for a patch, blank for a
 *   fresh generate) at its observed book version.
 * @property localId the originating RawContact's provider `_ID`. The stable,
 *   always-present key a net-new create writes its freshly-minted server href back
 *   against — a device-created contact has a blank [href], so it can only be
 *   reached by `_ID`. Defaults to `0L` for a caller that doesn't populate it (no
 *   `_ID` is ever 0, so a net-new write-back keyed on `0L` safely no-ops rather
 *   than stamping the wrong row).
 */
data class LocalContactEdit(
    val href: String,
    val uid: String,
    val storedEtag: String?,
    val contact: Contact,
    val localId: Long = 0L,
)

/**
 * A locally-deleted contact awaiting a server delete. The provider soft-deletes
 * a sync-adapter RawContact (sets `DELETED = 1`) and keeps the row until the
 * adapter has pushed the delete and hard-deleted it.
 *
 * @property href the resource `SOURCE_ID`; blank for a device-created contact
 *   deleted before it was ever pushed (nothing to delete server-side, just clean
 *   up the local tombstone).
 * @property storedEtag the `SYNC2` ETag — the If-Match validator for a
 *   conditional DELETE. Null/blank when the server omitted one.
 */
data class LocalContactTombstone(
    val href: String,
    val storedEtag: String?,
)

/**
 * The device-side pending set for one login's account: contacts the user edited
 * ([edited]) or deleted ([deleted]) on the device, awaiting push. Derived purely
 * from the provider's `DIRTY` / `DELETED` flags — there is no Room queue; the
 * flags ARE the pending set.
 */
data class LocalContactChanges(
    val edited: List<LocalContactEdit>,
    val deleted: List<LocalContactTombstone>,
)

/**
 * How a provider write-back failed, kept as a closed enum so a failure never
 * carries a provider exception message (which could embed a contact href, email,
 * or URL) into a log or a `Result.failure`.
 */
enum class ContactWriteFailure {
    /** WRITE_CONTACTS was revoked mid-write. */
    PERMISSION_DENIED,

    /** The provider rejected or could not apply the batch. */
    PROVIDER_ERROR,

    /**
     * `applyBatch` returned fewer results than ops submitted — at least one op
     * was silently dropped. Per-op counts can lie, but a short result array is
     * the reliable at-op-granularity signal that the write did not fully apply.
     */
    PARTIAL_APPLY,
}

/**
 * The typed cause on a write-back [Result.failure]. Carries only the [failure]
 * classification — never the underlying provider exception, so no PII reaches a
 * failure string.
 */
class ContactWriteException(val failure: ContactWriteFailure) : Exception(failure.name)

/**
 * The only surface allowed to WRITE synced contacts to the Android Contacts
 * Provider. Mirrors the device-calendar isolation:
 * [org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository] is the
 * only surface touching `CalendarContract` writes;
 * `ContactsProviderWriteBoundaryTest` fences this one to `sync/contacts/`.
 *
 * **Every operation is hard-scoped to a single login's system account** (name +
 * type). There is no cross-account sync: one login's pull must never read,
 * edit, or delete another login's contacts. The account predicate on every
 * write and delete is the load-bearing invariant of this layer.
 *
 * **Two-way.** The pull side is a full local mirror (insert new, replace changed,
 * delete server-removed) so the device reflects the server. The push side reads
 * the provider's own `DIRTY` / `DELETED` flags as the pending set and records the
 * server outcome back onto the RawContact SYNC columns ([markContactUploaded],
 * [markNewContactUploaded], [hardDeleteTombstone], [restoreTombstone]). All writes
 * run in sync-adapter mode so the provider attributes rows to the account and
 * doesn't spin a dirty-loop back at us from our own write-backs.
 */
interface ContactsProviderRepository {

    /**
     * Insert [contacts] under the account named [accountName].
     *
     * **Insert-only — the caller MUST pre-filter.** This never checks for an
     * existing RawContact with the same SOURCE_ID; calling it twice for the same
     * href duplicates the contact (SOURCE_ID uniqueness is convention, not a DB
     * constraint). A full re-pull must subtract [existingSourceIds] before
     * calling this, or every contact re-inserts.
     *
     * Ops are batched (chunked well under the Binder transaction limit) with a
     * yield point on the last op of each contact so a RawContact and its Data
     * rows always commit together. A provider/permission failure fails only the
     * offending chunk as [Result.failure]; it does not throw.
     */
    suspend fun insertContacts(
        accountName: String,
        contacts: List<MappedContactWrite>,
    ): Result<Unit>

    /**
     * The set of SOURCE_IDs (hrefs) already present under [accountName]. The
     * account-scoped pre-filter [insertContacts] documents: subtract these from
     * a pull's hrefs before inserting. Empty when permission is denied or the
     * account has no contacts yet.
     */
    suspend fun existingSourceIds(accountName: String): Set<String>

    /**
     * Map of every present contact's `SOURCE_ID` (href) to its stored `SYNC2`
     * (the server ETag it was last written with) under [accountName].
     *
     * The change-detection read-back a full re-pull needs: compare each server
     * href's current etag against this map to decide **insert** (href absent),
     * **replace** (href present, etag differs), or **skip** (etag matches).
     * Without it a re-pull can't tell changed from unchanged and would
     * [replaceContacts] every existing contact every run — churning RawContacts
     * and discarding Android's cross-account aggregation links each time.
     *
     * The etag value is nullable: a contact whose server omitted an ETag stored a
     * null/blank `SYNC2`, so a null in this map means "no validator to compare" —
     * treat as changed (replace) rather than skipping. Empty when permission is
     * denied or the account has no contacts yet.
     */
    suspend fun existingEtagsByHref(accountName: String): Map<String, String?>

    /**
     * Delete the RawContacts under [accountName] whose `SOURCE_ID` is in [hrefs].
     * The per-contact delete verb: a full sync passes the hrefs the server no
     * longer lists (orphan sweep), and [replaceContacts] uses it as the first
     * half of change-as-replace.
     *
     * Scoped by `ACCOUNT_NAME` **and** `ACCOUNT_TYPE` on every statement (a
     * name-only predicate could cross into the calendar account type when two
     * logins share an email). The `SOURCE_ID IN (…)` list is chunked so a large
     * href set stays under SQLite's bound-variable ceiling. Empty [hrefs] is a
     * no-op that issues no delete. Graceful [Result.failure] on permission denial.
     */
    suspend fun deleteByHrefs(accountName: String, hrefs: Collection<String>): Result<Unit>

    /**
     * Re-materialize [contacts] under [accountName] to reflect a server change,
     * **preserving each contact's device-side state**.
     *
     * For a href that already has a RawContact, this updates that row IN PLACE —
     * retaining its `_ID` — by refreshing the RawContact's SYNC columns, deleting its
     * existing Data rows, and re-inserting the fresh mapped set against the same id.
     * Because the `_ID` is stable, the aggregate Contact id survives, and with it
     * everything keyed on it: the user's **starred** flag, home-screen **shortcuts**,
     * and the stable **lookup key**. (A delete+recreate would mint a new `_ID` and
     * silently drop all of them.) The Data rows are replaced wholesale rather than
     * field-diffed — the mapper already emits the complete authoritative row set, so a
     * clean row replace is simpler and less error-prone than a per-field merge.
     *
     * A href with no existing row (self-heal) falls back to a fresh insert. The caller
     * routes only contacts whose etag actually changed here, via [existingEtagsByHref],
     * so unchanged contacts are never touched. Empty [contacts] is a no-op. Graceful
     * [Result.failure] on permission denial.
     *
     * Read-only relative to the server: this is a local mirror update, not a write-back.
     */
    suspend fun replaceContacts(
        accountName: String,
        contacts: List<MappedContactWrite>,
    ): Result<Unit>

    /**
     * Delete every RawContact owned by [accountName] (both name AND type). Used
     * on sign-out / account deletion. Scoped so it can never touch the calendar
     * account (which shares neither the contacts type nor, necessarily, a
     * distinct name). Graceful [Result.failure] on permission denial.
     */
    suspend fun purgeAccount(accountName: String): Result<Unit>

    /**
     * The number of RawContacts currently present under [accountName] (name AND
     * type). The post-purge verification: after a sign-out/disable removes the
     * system account, this must read 0 — a non-zero result means the OS account
     * removal did NOT cascade-delete the synced RawContacts (they'd otherwise
     * linger as account-less contacts on the device), so the caller re-runs the
     * scoped [purgeAccount]. Returns 0 when permission is denied or the query
     * fails, so a read error never masquerades as leftover rows.
     */
    suspend fun countRawContacts(accountName: String): Int

    /**
     * The `SOURCE_ID`s (hrefs) of RawContacts under [accountName] whose `SYNC4`
     * has the photo-pending bit set — contacts whose vCard named a remote-URL
     * photo the pull could not inline, so the fetch was deferred.
     *
     * This is the worklist the photo fetcher drains: it is independent of the
     * server delta, so a fetch that failed on an earlier run is retried on the
     * next sync (incremental included) without forcing a full re-pull. Empty when
     * permission is denied or nothing is pending.
     */
    suspend fun pendingPhotoSourceIds(accountName: String): Set<String>

    /**
     * Attach a fetched photo [bytes] to the RawContact identified by [sourceId]
     * under [accountName], and clear its photo-pending `SYNC4` bit — in ONE
     * `applyBatch` so the blob and the flag move together.
     *
     * The batch deletes any existing Photo Data row for the RawContact before
     * inserting the new one, so a retry (or a changed photo) never leaves two
     * Photo rows. The pending bit is cleared by a bitwise AND-NOT read-modify-write
     * that preserves every other `SYNC4` bit. A [sourceId] that no longer resolves
     * to a RawContact (deleted between pull and fetch) is a no-op success.
     * Graceful [Result.failure] on permission denial — the contact is left pending
     * for a later retry.
     */
    suspend fun writePhotoAndClearPending(
        accountName: String,
        sourceId: String,
        bytes: ByteArray,
    ): Result<Unit>

    /**
     * Clear the photo-pending `SYNC4` bit on the RawContact [sourceId] under
     * [accountName] WITHOUT writing any photo blob.
     *
     * For a pending contact whose re-fetched vCard no longer carries a URL photo
     * (the photo was removed, or changed to an inline blob already written on the
     * pull) — clearing the stale flag stops it from being retried forever. Shares
     * the same bit-preserving AND-NOT read-modify-write as
     * [writePhotoAndClearPending]. A [sourceId] that no longer resolves is a no-op
     * success. Graceful [Result.failure] on permission denial.
     */
    suspend fun clearPhotoPending(accountName: String, sourceId: String): Result<Unit>

    /**
     * Force ungrouped contacts under [accountName] to be visible in the device's
     * Contacts app.
     *
     * The Contacts Provider hides a contact whose RawContacts belong to no group
     * (RFC-synced contacts under our custom account type have no group-membership
     * rows). Without this the account label shows in Settings but every synced
     * contact stays invisible. Setting `UNGROUPED_VISIBLE = 1` on the account's
     * [android.provider.ContactsContract.Settings] row overrides that default so
     * groupless contacts are always shown; `SHOULD_SYNC = 1` marks the account's
     * contacts as syncable.
     *
     * Idempotent (a Settings insert for an existing account upserts), so the pull
     * calls it every run — accounts enabled before this existed self-heal on their
     * next sync. Graceful [Result.failure] on permission denial.
     */
    suspend fun ensureContactVisibility(accountName: String): Result<Unit>

    /**
     * The device-side pending set under [accountName]: every RawContact the
     * provider flagged `DIRTY` (a user edit — or a device-created contact) as an
     * [LocalContactEdit], and every `DELETED` RawContact as a
     * [LocalContactTombstone].
     *
     * Scoped by `ACCOUNT_NAME` **and** `ACCOUNT_TYPE` (a name-only scan could
     * cross into the calendar account type). Returns raw locators only —
     * book-agnostic; the push strategy joins each to its discovered book. Empty
     * when permission is denied or nothing is pending, so a read failure never
     * masquerades as "nothing changed" in a way that loses a real edit (the next
     * run re-detects it — the flags persist).
     */
    suspend fun pendingLocalChanges(accountName: String): LocalContactChanges

    /**
     * Record that the contact at [href] under [accountName] was pushed: set its
     * `SYNC2` to [newEtag] (the server's post-PUT validator) and clear its `DIRTY`
     * flag, in a `CALLER_IS_SYNCADAPTER` write so the provider does not re-flag the
     * row as dirty from our own write — which would otherwise loop the push forever.
     *
     * A [href] that no longer resolves (deleted between scan and push) is a no-op
     * success. The write's applied-op count is validated (a short `applyBatch`
     * result is a [Result.failure], never a swallowed success). Graceful
     * [Result.failure] on permission denial — the contact stays `DIRTY` for a retry.
     */
    suspend fun markContactUploaded(
        accountName: String,
        href: String,
        newEtag: String,
    ): Result<Unit>

    /**
     * Assign a synthesized [uid] to the net-new device contact whose RawContact is
     * [localId] under [accountName]: write it to `SYNC1` while KEEPING the row `DIRTY`,
     * so the contact stays in the pending set but now carries a stable, globally-unique
     * identity.
     *
     * A contact created in the device Contacts app has no vCard UID (RFC 6350 §6.7.6
     * gives `UID` cardinality `*1`), so before its first create the push mints a
     * globally-unique UID and persists it here. That UID then names the resource
     * (`<uid>.vcf`) and goes into the vCard body, so (a) two devices sharing the account
     * can never collide on a resource name the way a per-device `_ID` would, and (b) a
     * re-attempt after a failed write-back reads the SAME persisted UID, targets the SAME
     * resource, and can prove ownership by a real UID rather than a blank-vs-blank match.
     *
     * DIRTY is deliberately left set: the write-back that clears it happens only once the
     * server create succeeds. Unlike the other write-backs this is a `CALLER_IS_SYNCADAPTER`
     * write too, so persisting the UID does not itself re-flag the row as a fresh edit. A
     * [localId] of `0L` (unpopulated) is a no-op success. The write's applied-op count is
     * validated (a short `applyBatch` result is a [Result.failure]); graceful
     * [Result.failure] on permission denial — the caller then skips the PUT and defers.
     */
    suspend fun assignContactUid(
        accountName: String,
        localId: Long,
        uid: String,
    ): Result<Unit>

    /**
     * Record that the net-new device contact whose RawContact is [localId] under
     * [accountName] was created on the server: stamp its `SOURCE_ID` to the freshly
     * minted [href], its `SYNC2` to [newEtag], and clear its `DIRTY` flag — all in one
     * `CALLER_IS_SYNCADAPTER` write so the row's own write-back is not re-detected as a
     * new edit.
     *
     * The counterpart to [markContactUploaded] for a contact that had **no** server
     * resource before this push: its `SOURCE_ID` was blank, so it cannot be resolved by
     * href and must be addressed by its stable provider [localId] instead. Stamping the
     * href here is what lets the next pull match the server copy to this existing row
     * (SOURCE_ID = the server href) and skip it, rather than mirroring it as a duplicate.
     *
     * A [localId] of `0L` (unpopulated — no real `_ID` is ever 0) is a no-op success, so
     * a locator that never carried an `_ID` can never stamp the wrong row. The write's
     * applied-op count is validated (a short `applyBatch` result is a [Result.failure]).
     * Graceful [Result.failure] on permission denial — the contact stays `DIRTY` for a retry.
     */
    suspend fun markNewContactUploaded(
        accountName: String,
        localId: Long,
        href: String,
        newEtag: String,
    ): Result<Unit>

    /**
     * Hard-delete the soft-deleted (`DELETED = 1`) RawContact at [href] under
     * [accountName] via the sync-adapter URI, once its server delete has been
     * pushed. Only a tombstoned row is touched (a live row at the same href is
     * never hard-deleted); a [href] that resolves to no tombstone is a no-op
     * success. Applied-op count validated; graceful [Result.failure] on denial.
     */
    suspend fun hardDeleteTombstone(accountName: String, href: String): Result<Unit>

    /**
     * Undo a local tombstone: clear the `DELETED` (and `DIRTY`) flag on the
     * RawContact at [href] under [accountName], so the next pull re-materializes
     * it from the server. Used when a delete must not be pushed — e.g. the owning
     * book is read-only, so the server copy is authoritative and the local delete
     * is reverted rather than uploaded. A [href] that resolves to no tombstone is
     * a no-op success. Applied-op count validated; graceful [Result.failure] on denial.
     */
    suspend fun restoreTombstone(accountName: String, href: String): Result<Unit>
}
