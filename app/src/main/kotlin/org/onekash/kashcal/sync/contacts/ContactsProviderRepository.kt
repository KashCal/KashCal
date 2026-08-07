package org.onekash.kashcal.sync.contacts

import org.onekash.kashcal.data.contacts.MappedContact

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
 */
data class MappedContactWrite(
    val href: String,
    val etag: String?,
    val mapped: MappedContact,
)

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
 * **Read-only relative to the *server*** — there is no PUT / write-back / two-way
 * sync. *Locally* it is a full mirror: it inserts new contacts, replaces changed
 * ones, and deletes server-removed ones, so the device reflects the server's
 * current state. All writes run in sync-adapter mode so the provider attributes
 * rows to the account and doesn't spin a dirty-loop back at us.
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
}
