package org.onekash.kashcal.sync.contacts

import org.onekash.kashcal.data.contacts.MappedContact
import org.onekash.vcard.model.Contact

/**
 * One synced contact to write to the Contacts Provider: the mapped Data rows plus the sync
 * coordinates stored on the RawContact's SYNC columns.
 *
 * Column layout: SOURCE_ID = [href], SYNC1 = the vCard UID (blank when the body has none;
 * RFC 6350 §6.7.6 makes UID optional), SYNC2 = [etag], SYNC3 = a content hash, SYNC4 = a flag
 * bitset. [href] is the locator and the account-unique key aggregation relies on; a blank UID
 * is never used to match rows.
 *
 * @property href the resource href exactly as the server returned it.
 * @property etag the entity tag, or null when the server omitted one.
 * @property mapped the Data rows for this RawContact from
 *   [org.onekash.kashcal.data.contacts.VCardContactMapper]. `dataRows[0]` is the
 *   StructuredName; the write layer never adds its own.
 * @property isReadOnly the owning address book is read-only. True writes the RawContact with
 *   `RAW_CONTACT_IS_READ_ONLY = 1`, so the user can't make an edit that can never be pushed.
 *   False leaves it editable, and a user edit sets DIRTY, which the push reads. Defaults to
 *   true so a call site that forgets it can't make a contact editable with nowhere to push.
 */
data class MappedContactWrite(
    val href: String,
    val etag: String?,
    val mapped: MappedContact,
    val isReadOnly: Boolean = true,
)

/**
 * A contact edited on the device (the provider set `DIRTY`), waiting to be pushed.
 *
 * It carries the SYNC-column locators ([href], [uid], [storedEtag]) and the device fields in
 * [contact], but no address book: the push strategy finds the book by [href] to get its
 * read-only flag and vCard version.
 *
 * @property href `SOURCE_ID`; blank for a device-created contact never pushed (a create).
 * @property uid the `SYNC1` UID; blank when the original body had none.
 * @property storedEtag the `SYNC2` etag the row was last written with, used as `If-Match`.
 *   Null or blank when the server gave none; the push then creates as fresh.
 * @property contact the device fields, reverse-mapped by
 *   [org.onekash.kashcal.data.contacts.DeviceContactRowMapper]. `rawVCard` and `version` are
 *   defaults (Data rows don't store them); the push supplies the real base (the server body
 *   for a patch, blank for a fresh write) at the book's version.
 * @property localId the RawContact `_ID`. A device-created contact has a blank [href], so its
 *   create is written back by `_ID`. Defaults to `0L`; no real `_ID` is 0, so a write-back
 *   keyed on `0L` is a no-op instead of stamping the wrong row.
 */
data class LocalContactEdit(
    val href: String,
    val uid: String,
    val storedEtag: String?,
    val contact: Contact,
    val localId: Long = 0L,
)

/**
 * A contact deleted on the device, waiting for a server DELETE. The provider soft-deletes a
 * sync-adapter RawContact (`DELETED = 1`) and keeps it until the adapter hard-deletes it.
 *
 * @property href `SOURCE_ID`; blank for a device-created contact deleted before any push
 *   (nothing on the server; only the tombstone is removed).
 * @property storedEtag the `SYNC2` etag, used as `If-Match` on the DELETE. Null or blank when
 *   the server gave none.
 */
data class LocalContactTombstone(
    val href: String,
    val storedEtag: String?,
)

/**
 * Contacts edited ([edited]) or deleted ([deleted]) on the device for one login, read from
 * the provider's `DIRTY`/`DELETED` flags. There is no Room queue; the flags are the pending set.
 */
data class LocalContactChanges(
    val edited: List<LocalContactEdit>,
    val deleted: List<LocalContactTombstone>,
)

/**
 * Why a provider write-back failed. An enum, so a failure never carries a provider exception
 * message (which can contain an href, email or URL) into a log or a `Result.failure`.
 */
enum class ContactWriteFailure {
    /** WRITE_CONTACTS was revoked mid-write. */
    PERMISSION_DENIED,

    /** The provider rejected or couldn't apply the batch. */
    PROVIDER_ERROR,

    /**
     * `applyBatch` returned fewer results than ops submitted, so at least one op was
     * dropped. Per-op counts can be wrong; a short result array is the reliable signal.
     */
    PARTIAL_APPLY,
}

/**
 * The cause on a write-back [Result.failure]. Holds only [failure], never the provider
 * exception, so no PII reaches the failure message.
 */
class ContactWriteException(val failure: ContactWriteFailure) : Exception(failure.name)

/**
 * The only way to write synced contacts to the Contacts Provider;
 * `ContactsProviderWriteBoundaryTest` keeps those writes inside `sync/contacts/`. It is the
 * contacts counterpart of [org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository].
 *
 * Every operation is scoped to one login's system account (name and type). There is no
 * cross-account sync: one login's sync never reads, edits or deletes another's contacts. The
 * account predicate on every write and delete is this layer's core invariant.
 *
 * The pull side mirrors the server (insert new, replace changed, delete removed). The push
 * side reads the provider's `DIRTY`/`DELETED` flags as the pending set and records the server
 * outcome on the SYNC columns ([markContactUploaded], [markNewContactUploaded],
 * [hardDeleteTombstone], [restoreTombstone]). All writes run in sync-adapter mode, so the
 * provider attributes rows to the account and our own write-backs don't set DIRTY again.
 *
 * Unless noted, methods don't throw: a permission denial or provider error returns
 * [Result.failure], and a read returns empty.
 */
interface ContactsProviderRepository {

    /**
     * Inserts [contacts] under [accountName].
     *
     * Insert only: nothing checks for an existing RawContact with the same SOURCE_ID, and
     * SOURCE_ID uniqueness is not a DB constraint, so inserting an href twice duplicates the
     * contact. Callers pass only new hrefs (the pull decides with [existingEtagsByHref]).
     *
     * Ops are batched well under the Binder transaction limit. Batch ends and yield points
     * fall only on contact boundaries, so a RawContact commits with its Data rows. Stops at
     * the first failed batch and returns
     * [Result.failure]; earlier batches stay committed.
     */
    suspend fun insertContacts(
        accountName: String,
        contacts: List<MappedContactWrite>,
    ): Result<Unit>

    /** SOURCE_IDs (hrefs) present under [accountName]. */
    suspend fun existingSourceIds(accountName: String): Set<String>

    /**
     * Every contact's `SOURCE_ID` (href) under [accountName], mapped to its stored `SYNC2`
     * etag.
     *
     * The pull compares server etags against this to insert (href absent), replace (etag
     * differs) or skip (etag matches). Without it every pull would [replaceContacts] every
     * contact, churning RawContacts and dropping Android's cross-account aggregation links.
     *
     * A null value means the server gave no etag, so there is nothing to compare: treat it as
     * changed.
     */
    suspend fun existingEtagsByHref(accountName: String): Map<String, String?>

    /**
     * Deletes the RawContacts under [accountName] whose `SOURCE_ID` is in [hrefs]. The pull
     * uses it for the server's removed set and the orphan sweep.
     *
     * Every statement is scoped by `ACCOUNT_NAME` and `ACCOUNT_TYPE`; name alone could reach
     * the calendar account type when two logins share an email. The `IN (…)` list is chunked
     * to stay under SQLite's bound-variable limit. Empty [hrefs] issues no delete.
     */
    suspend fun deleteByHrefs(accountName: String, hrefs: Collection<String>): Result<Unit>

    /**
     * Rewrites [contacts] under [accountName] after a server change, keeping each contact's
     * device-side state.
     *
     * An href with a live RawContact is updated in place: SYNC columns refreshed, Data rows
     * deleted and re-inserted against the same `_ID`. Keeping the `_ID` keeps the aggregate
     * contact id, and with it the starred flag, home-screen shortcuts and lookup key; a
     * delete and re-insert would lose all three. Data rows are replaced whole, not diffed,
     * because the mapper emits the complete row set. An href with no live row is inserted.
     *
     * This only updates the device copy; nothing is sent to the server.
     */
    suspend fun replaceContacts(
        accountName: String,
        contacts: List<MappedContactWrite>,
    ): Result<Unit>

    /**
     * Deletes every RawContact owned by [accountName] (name and type), on sign-out or account
     * deletion. The type check keeps it off the calendar account, which may share the name.
     */
    suspend fun purgeAccount(accountName: String): Result<Unit>

    /**
     * RawContacts present under [accountName] (name and type). After the system account is
     * removed this should be 0; anything else means the OS removal didn't cascade, and the
     * caller runs [purgeAccount]. Returns 0 on permission denial or query failure, so a read
     * error never looks like leftover rows.
     */
    suspend fun countRawContacts(accountName: String): Int

    /**
     * `SOURCE_ID`s under [accountName] with the photo-pending `SYNC4` bit: contacts whose
     * vCard referenced a photo URL the pull didn't fetch.
     *
     * The photo fetcher's worklist. It doesn't depend on the server delta, so a fetch that
     * failed on an earlier run is retried on the next sync, delta syncs included.
     */
    suspend fun pendingPhotoSourceIds(accountName: String): Set<String>

    /**
     * Writes [bytes] as the photo of the RawContact [sourceId] under [accountName] and clears
     * its photo-pending bit, in one `applyBatch`.
     *
     * The batch deletes any existing Photo row first, so a retry or changed photo never leaves
     * two. The bit is cleared with an AND-NOT that keeps the other `SYNC4` bits. A [sourceId]
     * with no RawContact (deleted between pull and fetch) is a no-op success. On failure the
     * contact stays pending for a later retry.
     */
    suspend fun writePhotoAndClearPending(
        accountName: String,
        sourceId: String,
        bytes: ByteArray,
    ): Result<Unit>

    /**
     * Clears the photo-pending bit on the RawContact [sourceId] under [accountName] without
     * writing a photo.
     *
     * For a pending contact whose re-fetched vCard no longer references a photo URL (removed,
     * or replaced by an inline photo the pull already wrote), so it isn't retried forever.
     * Same bit-preserving AND-NOT as [writePhotoAndClearPending]. A [sourceId] with no
     * RawContact is a no-op success.
     */
    suspend fun clearPhotoPending(accountName: String, sourceId: String): Result<Unit>

    /**
     * Makes ungrouped contacts under [accountName] visible in the Contacts app.
     *
     * The provider hides contacts that belong to no group, and synced contacts have no group
     * memberships, so without this the account shows in Settings but its contacts don't.
     * Sets `UNGROUPED_VISIBLE = 1` and `SHOULD_SYNC = 1` on the account's
     * [android.provider.ContactsContract.Settings] row.
     *
     * Idempotent (the Settings insert upserts); the pull calls it every run.
     */
    suspend fun ensureContactVisibility(accountName: String): Result<Unit>

    /**
     * The pending set under [accountName]: every `DIRTY` RawContact (a user edit or a
     * device-created contact) as a [LocalContactEdit] and every `DELETED` one as a
     * [LocalContactTombstone].
     *
     * Scoped by `ACCOUNT_NAME` and `ACCOUNT_TYPE`. Returns locators only; the push matches
     * them to books. An empty result on a read failure loses nothing: the flags persist and
     * the next run finds them again.
     */
    suspend fun pendingLocalChanges(accountName: String): LocalContactChanges

    /**
     * Records that the contact at [href] under [accountName] was pushed: sets `SYNC2` to
     * [newEtag] and clears `DIRTY` in one sync-adapter write, so the provider doesn't mark
     * the row dirty again and loop the push.
     *
     * An [href] that no longer resolves (deleted between scan and push) is a no-op success. A
     * short `applyBatch` result is a [Result.failure]. On failure the contact stays `DIRTY`.
     *
     * [newHref], when given, replaces `SOURCE_ID`: the server redirected the PUT and the vCard
     * now lives there.
     */
    suspend fun markContactUploaded(
        accountName: String,
        href: String,
        newEtag: String,
        newHref: String? = null,
    ): Result<Unit>

    /**
     * Writes [uid] to `SYNC1` of the net-new device contact [localId] under [accountName],
     * keeping `DIRTY` set so it stays pending.
     *
     * A contact created in the Contacts app has no vCard UID, so the push generates one
     * before the first create and persists it here. The UID names the resource (`<uid>.vcf`)
     * and goes into the body, so two devices on the account can't collide on a name, and a
     * retry after a failed write-back targets the same resource and proves ownership by UID.
     *
     * `DIRTY` is cleared only when the server create succeeds. Like the other write-backs
     * this is a sync-adapter write, so it doesn't count as a new edit. A [localId] of `0L` is
     * a no-op success. A short `applyBatch` result is a [Result.failure]; on failure the
     * caller skips the PUT and defers.
     */
    suspend fun assignContactUid(
        accountName: String,
        localId: Long,
        uid: String,
    ): Result<Unit>

    /**
     * Records that the net-new device contact [localId] under [accountName] was created on
     * the server: sets `SOURCE_ID` to [href] and `SYNC2` to [newEtag] and clears `DIRTY`, in
     * one sync-adapter write.
     *
     * [markContactUploaded] for a contact with no prior server resource: its `SOURCE_ID` was
     * blank, so it is addressed by [localId]. Stamping the href lets the next pull match the
     * server copy to this row instead of mirroring it as a duplicate.
     *
     * A [localId] of `0L` (no real `_ID` is 0) is a no-op success, so it can't stamp the wrong
     * row. A short `applyBatch` result is a [Result.failure]. On failure the contact stays
     * `DIRTY`.
     */
    suspend fun markNewContactUploaded(
        accountName: String,
        localId: Long,
        href: String,
        newEtag: String,
    ): Result<Unit>

    /**
     * Hard-deletes the tombstoned (`DELETED = 1`) RawContact at [href] under [accountName]
     * through the sync-adapter URI, after its server delete. A live row at the same href is
     * never touched; no tombstone at [href] is a no-op success. A short `applyBatch` result
     * is a [Result.failure].
     */
    suspend fun hardDeleteTombstone(accountName: String, href: String): Result<Unit>

    /**
     * Reverts a local delete: clears `DELETED` and `DIRTY` on the RawContact at [href] under
     * [accountName], so the next pull restores it from the server. Used when the delete can't
     * be pushed (read-only book, or the server copy changed). No tombstone at [href] is a
     * no-op success. A short `applyBatch` result is a [Result.failure].
     */
    suspend fun restoreTombstone(accountName: String, href: String): Result<Unit>
}
