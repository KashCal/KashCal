package org.onekash.kashcal.sync.contacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.OperationApplicationException
import android.net.Uri
import android.os.RemoteException
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onekash.kashcal.sync.adapter.KashCalContactsAuthenticator
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android Contacts Provider implementation of [ContactsProviderRepository].
 *
 * The only class in the tree that WRITES synced contacts to `ContactsContract`
 * (enforced by `ContactsProviderWriteBoundaryTest`). It mirrors
 * [org.onekash.kashcal.data.calendar_provider.AndroidCalendarProviderRepository]'s
 * `applyBatch` + `Result`/catch shape.
 *
 * All writes go through a sync-adapter URI ([syncAdapterUri]) carrying
 * `CALLER_IS_SYNCADAPTER=true` and the account name/type, so the provider
 * attributes rows to the login's account and does not raise a DIRTY flag that
 * would spin a write-back loop.
 *
 * The account type is fixed to [KashCalContactsAuthenticator.ACCOUNT_TYPE]; the
 * caller supplies only the per-login account NAME (the email). Every write and
 * delete is scoped to both, which is what keeps one login's pull from touching
 * another login's — or the calendar account's — contacts.
 */
@Singleton
class AndroidContactsProviderRepository @Inject constructor(
    private val contentResolver: ContentResolver,
) : ContactsProviderRepository {

    override suspend fun insertContacts(
        accountName: String,
        contacts: List<MappedContactWrite>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (contacts.isEmpty()) return@withContext Result.success(Unit)

        // Provision a titled Group for every CATEGORY before the membership rows
        // reference it: a GroupMembership keyed by GROUP_SOURCE_ID would otherwise
        // make the provider auto-create an UNTITLED group, showing the label as blank.
        ensureGroups(accountName, contacts)

        val batches = buildBatches(accountName, contacts)
        for (batch in batches) {
            try {
                // Robolectric's ShadowContentResolver returns an EMPTY result array
                // (no provider registered), and a real sync-adapter insert doesn't
                // need the returned ids — the RAW_CONTACT_ID back-references are
                // resolved within the batch. So never dereference the result.
                contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(batch))
            } catch (e: SecurityException) {
                Log.w(TAG, "WRITE_CONTACTS revoked mid-sync; contact insert chunk skipped", e)
                return@withContext Result.failure(e)
            } catch (e: OperationApplicationException) {
                Log.w(TAG, "Contact insert chunk failed to apply", e)
                return@withContext Result.failure(e)
            } catch (e: RemoteException) {
                Log.w(TAG, "Contacts Provider unavailable during insert chunk", e)
                return@withContext Result.failure(e)
            } catch (e: Exception) {
                // Honor the "does not throw" contract for unchecked runtimes the
                // provider can still surface — e.g. IllegalArgumentException on an
                // unresolvable URI, or a Binder-relayed RuntimeException.
                Log.w(TAG, "Contact insert chunk failed unexpectedly", e)
                return@withContext Result.failure(e)
            }
        }
        Result.success(Unit)
    }

    /**
     * Ensure a titled [Groups] row exists under [accountName] for every distinct
     * CATEGORY the batch references, so a `GroupMembership` keyed by
     * `GROUP_SOURCE_ID` resolves to a named group. Uses `SOURCE_ID = TITLE = the
     * category name` — the category is both the display title and the stable key
     * the membership rows point at.
     *
     * Idempotent: only groups not already present (by SOURCE_ID) are inserted, so
     * every re-pull is safe and cheap. Best-effort — a failure here is logged but
     * not fatal, since the provider still auto-creates a (blank) group and the
     * contact itself is not lost.
     */
    private fun ensureGroups(accountName: String, contacts: List<MappedContactWrite>) {
        // Read categories off the source model directly — the same names the mapper
        // emits as GROUP_SOURCE_ID rows — rather than re-scanning every Data row.
        val wanted = contacts
            .flatMap { it.mapped.contact.categories }
            .filter { it.isNotBlank() }
            .toSet()
        if (wanted.isEmpty()) return

        try {
            val groupsUri = syncAdapterUri(Groups.CONTENT_URI, accountName)
            val existing = existingGroupSourceIds(groupsUri, accountName)
            for (name in wanted - existing) {
                val values = ContentValues().apply {
                    put(Groups.SOURCE_ID, name)
                    put(Groups.TITLE, name)
                    put(Groups.GROUP_VISIBLE, 1)
                    put(Groups.SHOULD_SYNC, 1)
                }
                contentResolver.insert(groupsUri, values)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_CONTACTS revoked; group provisioning skipped", e)
        } catch (e: Exception) {
            Log.w(TAG, "Group provisioning failed; memberships may show untitled", e)
        }
    }

    /** SOURCE_IDs of groups already present under this account (blank-filtered). */
    private fun existingGroupSourceIds(groupsUri: Uri, accountName: String): Set<String> =
        querySourceIdSet(groupsUri, Groups.SOURCE_ID, accountName)

    override suspend fun existingSourceIds(accountName: String): Set<String> = withContext(Dispatchers.IO) {
        try {
            querySourceIdSet(
                syncAdapterUri(RawContacts.CONTENT_URI, accountName),
                RawContacts.SOURCE_ID,
                accountName,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS revoked; existingSourceIds returns empty", e)
            emptySet()
        } catch (e: Exception) {
            Log.w(TAG, "existingSourceIds query failed", e)
            emptySet()
        }
    }

    /**
     * Query the non-blank values of a single-column SOURCE_ID [projection] under this
     * account's scope. The shared cursor loop behind both the RawContacts and Groups
     * source-id reads; callers own their URI construction and error handling.
     */
    private fun querySourceIdSet(uri: Uri, projection: String, accountName: String): Set<String> =
        contentResolver.query(
            uri,
            arrayOf(projection),
            accountScopeSelection(),
            accountScopeArgs(accountName),
            null,
        )?.use { cursor ->
            val out = HashSet<String>(cursor.count)
            while (cursor.moveToNext()) {
                cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
            out
        }.orEmpty()

    override suspend fun existingEtagsByHref(accountName: String): Map<String, String?> = withContext(Dispatchers.IO) {
        try {
            contentResolver.query(
                syncAdapterUri(RawContacts.CONTENT_URI, accountName),
                arrayOf(RawContacts.SOURCE_ID, RawContacts.SYNC2),
                accountScopeSelection(),
                accountScopeArgs(accountName),
                null,
            )?.use { cursor ->
                val out = HashMap<String, String?>(cursor.count)
                while (cursor.moveToNext()) {
                    val href = cursor.getString(0)?.takeIf { it.isNotEmpty() } ?: continue
                    // SYNC2 (etag) may be null/blank when the server omitted an ETag;
                    // keep the null so the caller treats it as "no validator -> replace".
                    out[href] = cursor.getString(1)?.takeIf { it.isNotEmpty() }
                }
                out
            }.orEmpty()
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS revoked; existingEtagsByHref returns empty", e)
            emptyMap()
        } catch (e: Exception) {
            Log.w(TAG, "existingEtagsByHref query failed", e)
            emptyMap()
        }
    }

    override suspend fun pendingPhotoSourceIds(accountName: String): Set<String> = withContext(Dispatchers.IO) {
        try {
            contentResolver.query(
                syncAdapterUri(RawContacts.CONTENT_URI, accountName),
                arrayOf(RawContacts.SOURCE_ID, RawContacts.SYNC4),
                accountScopeSelection(),
                accountScopeArgs(accountName),
                null,
            )?.use { cursor ->
                val out = HashSet<String>()
                while (cursor.moveToNext()) {
                    val href = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    // SYNC4 is a nullable INTEGER; a null/absent flag column is "no
                    // flags set". Filter the pending bit in code rather than a SQL
                    // bitwise selection, which behaves inconsistently on a null column.
                    val flags = if (cursor.isNull(1)) 0 else cursor.getInt(1)
                    if (flags and FLAG_PHOTO_PENDING != 0) out.add(href)
                }
                out
            }.orEmpty()
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS revoked; pendingPhotoSourceIds returns empty", e)
            emptySet()
        } catch (e: Exception) {
            Log.w(TAG, "pendingPhotoSourceIds query failed", e)
            emptySet()
        }
    }

    override suspend fun writePhotoAndClearPending(
        accountName: String,
        sourceId: String,
        bytes: ByteArray,
    ): Result<Unit> = applyPhotoPendingBatch(accountName, sourceId, bytes)

    override suspend fun clearPhotoPending(
        accountName: String,
        sourceId: String,
    ): Result<Unit> = applyPhotoPendingBatch(accountName, sourceId, bytes = null)

    /**
     * Shared body for [writePhotoAndClearPending] (bytes != null) and
     * [clearPhotoPending] (bytes == null): resolve the RawContact by [sourceId],
     * build the batch via [buildPhotoWriteBatch], and apply it. A source id that
     * no longer resolves (contact deleted between pull and fetch) is a no-op
     * success; permission/provider failure is a graceful [Result.failure].
     */
    private suspend fun applyPhotoPendingBatch(
        accountName: String,
        sourceId: String,
        bytes: ByteArray?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val target = resolveRawContact(accountName, sourceId)
                ?: return@withContext Result.success(Unit) // gone between pull and fetch
            val ops = buildPhotoWriteBatch(accountName, target.rawContactId, target.flags, bytes)
            contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_CONTACTS revoked; photo write left pending", e)
            Result.failure(e)
        } catch (e: OperationApplicationException) {
            Log.w(TAG, "Photo write batch failed to apply; left pending", e)
            Result.failure(e)
        } catch (e: RemoteException) {
            Log.w(TAG, "Contacts Provider unavailable during photo write; left pending", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "Photo write batch failed unexpectedly; left pending", e)
            Result.failure(e)
        }
    }

    /** The `_ID` and current `SYNC4` flags of a RawContact identified by SOURCE_ID. */
    private data class RawContactTarget(val rawContactId: Long, val flags: Int)

    /**
     * Resolve the RawContact `_ID` + current `SYNC4` flags for [sourceId] under
     * this account, or null when no such row exists. Account-scoped so a source id
     * can never resolve a row belonging to another login (or the calendar type).
     */
    private fun resolveRawContact(accountName: String, sourceId: String): RawContactTarget? =
        contentResolver.query(
            syncAdapterUri(RawContacts.CONTENT_URI, accountName),
            arrayOf(RawContacts._ID, RawContacts.SYNC4),
            "${accountScopeSelection()} AND ${RawContacts.SOURCE_ID} = ?",
            accountScopeArgs(accountName) + sourceId,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getLong(0)
            val flags = if (cursor.isNull(1)) 0 else cursor.getInt(1)
            RawContactTarget(id, flags)
        }

    override suspend fun deleteByHrefs(
        accountName: String,
        hrefs: Collection<String>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (hrefs.isEmpty()) return@withContext Result.success(Unit)

        try {
            val uri = syncAdapterUri(RawContacts.CONTENT_URI, accountName)
            // Chunk the SOURCE_ID IN (…) list so a large orphan sweep stays under
            // SQLite's bound-variable ceiling; the two account-predicate args ride
            // on every chunk, hence the cap leaves headroom below the limit.
            var deleted = 0
            for (chunk in hrefs.chunked(MAX_DELETE_IDS_PER_QUERY)) {
                val placeholders = chunk.joinToString(",") { "?" }
                val selection =
                    "${accountScopeSelection()} AND ${RawContacts.SOURCE_ID} IN ($placeholders)"
                val args = accountScopeArgs(accountName) + chunk.toTypedArray()
                deleted += contentResolver.delete(uri, selection, args)
            }
            Log.i(TAG, "Deleted $deleted RawContacts by href for this login")
            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_CONTACTS revoked; deleteByHrefs skipped", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "deleteByHrefs failed", e)
            Result.failure(e)
        }
    }

    override suspend fun replaceContacts(
        accountName: String,
        contacts: List<MappedContactWrite>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (contacts.isEmpty()) return@withContext Result.success(Unit)

        // In-place update preserves the RawContact _ID across a server edit, so the
        // aggregate Contact id — and everything keyed on it: the user's starred flag,
        // home-screen shortcuts, and the stable lookup key — survives. A delete+recreate
        // would mint a new _ID and silently drop all of them. So resolve which hrefs
        // already have a row, update those in place, and insert only the genuinely-new
        // ones fresh.
        val existingIds = resolveRawContactIdsByHref(accountName, contacts.map { it.href })

        // CATEGORY groups are provisioned for the whole set up front (idempotent), so a
        // membership row on either path resolves to a titled group rather than a blank one.
        ensureGroups(accountName, contacts)

        val (toUpdate, toInsert) = contacts.partition { existingIds.containsKey(it.href) }

        for (contact in toUpdate) {
            val rawContactId = existingIds.getValue(contact.href)
            val ops = buildInPlaceReplaceBatch(accountName, rawContactId, contact)
            try {
                contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
            } catch (e: SecurityException) {
                Log.w(TAG, "WRITE_CONTACTS revoked mid-sync; in-place replace skipped", e)
                return@withContext Result.failure(e)
            } catch (e: OperationApplicationException) {
                Log.w(TAG, "In-place replace batch failed to apply", e)
                return@withContext Result.failure(e)
            } catch (e: RemoteException) {
                Log.w(TAG, "Contacts Provider unavailable during in-place replace", e)
                return@withContext Result.failure(e)
            } catch (e: Exception) {
                Log.w(TAG, "In-place replace batch failed unexpectedly", e)
                return@withContext Result.failure(e)
            }
        }

        if (toInsert.isNotEmpty()) return@withContext insertContacts(accountName, toInsert)
        Result.success(Unit)
    }

    /**
     * Map each href in [hrefs] that currently resolves to a RawContact under this
     * account to its `_ID`. Absent hrefs are simply not in the map (nothing to
     * preserve — they go through the fresh-insert path). Account-scoped so a href
     * can never resolve a row belonging to another login or the calendar type.
     */
    private fun resolveRawContactIdsByHref(
        accountName: String,
        hrefs: List<String>,
    ): Map<String, Long> {
        if (hrefs.isEmpty()) return emptyMap()
        return contentResolver.query(
            syncAdapterUri(RawContacts.CONTENT_URI, accountName),
            arrayOf(RawContacts.SOURCE_ID, RawContacts._ID),
            accountScopeSelection(),
            accountScopeArgs(accountName),
            null,
        )?.use { cursor ->
            val wanted = hrefs.toHashSet()
            val out = HashMap<String, Long>(minOf(hrefs.size, cursor.count))
            while (cursor.moveToNext()) {
                val href = cursor.getString(0)?.takeIf { it in wanted } ?: continue
                out[href] = cursor.getLong(1)
            }
            out
        }.orEmpty()
    }

    /**
     * Ops to replace one contact IN PLACE on the RawContact [rawContactId] (retaining
     * its _ID): update the RawContact's own SYNC columns, delete all its existing Data
     * rows, then re-insert the fresh mapped Data rows against the retained id. The
     * mapper already emits the complete authoritative row set, so a wholesale Data-row
     * replace is simpler and less error-prone than a per-field diff — while the parent
     * RawContact row (and everything the aggregate Contact keys on it) survives.
     */
    private fun buildInPlaceReplaceBatch(
        accountName: String,
        rawContactId: Long,
        contact: MappedContactWrite,
    ): List<ContentProviderOperation> {
        val rawUri = syncAdapterUri(RawContacts.CONTENT_URI, accountName)
        val dataUri = syncAdapterUri(Data.CONTENT_URI, accountName)
        val ops = ArrayList<ContentProviderOperation>(2 + contact.mapped.dataRows.size)

        // Refresh the RawContact's own sync columns (etag/hash/flags) on the retained row.
        ops.add(
            ContentProviderOperation.newUpdate(
                ContentUris.withAppendedId(rawUri, rawContactId),
            ).withValues(rawContactValues(contact)).build(),
        )
        // Clear the stale Data rows, then re-insert the current set. The blob-carrying
        // Photo row rides in here too; a URL photo re-flags SYNC4 via rawContactValues.
        ops.add(
            ContentProviderOperation.newDelete(dataUri)
                .withSelection("${Data.RAW_CONTACT_ID} = ?", arrayOf(rawContactId.toString()))
                .build(),
        )
        for (values in contact.mapped.dataRows) {
            ops.add(
                ContentProviderOperation.newInsert(dataUri)
                    .withValues(ContentValues(values))
                    .withValue(Data.RAW_CONTACT_ID, rawContactId)
                    .build(),
            )
        }
        return ops
    }

    override suspend fun purgeAccount(accountName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val deleted = contentResolver.delete(
                syncAdapterUri(RawContacts.CONTENT_URI, accountName),
                accountScopeSelection(),
                accountScopeArgs(accountName),
            )
            Log.i(TAG, "Purged $deleted synced RawContacts for this login")
            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_CONTACTS revoked; purgeAccount skipped", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "purgeAccount delete failed", e)
            Result.failure(e)
        }
    }

    override suspend fun countRawContacts(accountName: String): Int = withContext(Dispatchers.IO) {
        try {
            contentResolver.query(
                syncAdapterUri(RawContacts.CONTENT_URI, accountName),
                arrayOf(RawContacts._ID),
                accountScopeSelection(),
                accountScopeArgs(accountName),
                null,
            )?.use { it.count } ?: 0
        } catch (e: SecurityException) {
            // A read failure must not report phantom leftovers; treat as "can't tell".
            Log.w(TAG, "READ_CONTACTS revoked; countRawContacts returns 0", e)
            0
        } catch (e: Exception) {
            Log.w(TAG, "countRawContacts query failed", e)
            0
        }
    }

    override suspend fun ensureContactVisibility(accountName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Contacts under a custom account type with no group-membership rows are
            // hidden by default; UNGROUPED_VISIBLE overrides that so they always show.
            val values = ContentValues().apply {
                put(ContactsContract.Settings.ACCOUNT_NAME, accountName)
                put(ContactsContract.Settings.ACCOUNT_TYPE, KashCalContactsAuthenticator.ACCOUNT_TYPE)
                put(ContactsContract.Settings.SHOULD_SYNC, 1)
                put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
            }
            contentResolver.insert(syncAdapterUri(ContactsContract.Settings.CONTENT_URI, accountName), values)
            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_CONTACTS revoked; ensureContactVisibility skipped", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "ensureContactVisibility failed", e)
            Result.failure(e)
        }
    }

    /**
     * Build the [ContentProviderOperation] batches for [contacts]. Pure (no I/O)
     * so the chunking and yield-boundary invariants are unit-testable without a
     * provider.
     *
     * Invariants:
     * - A RawContact insert and all its Data rows sit in ONE batch (a contact is
     *   never split across an `applyBatch` boundary), and the last op of each
     *   contact is the yield point — so a partial failure can't commit a
     *   nameless RawContact.
     * - A batch is bounded by BOTH the op count ([MAX_OPS_PER_BATCH]) AND the
     *   cumulative inline-blob bytes ([MAX_BATCH_BYTES]): an inline contact photo
     *   can be hundreds of KB, so a handful of photo-carrying contacts fits well
     *   under the op cap yet can still exceed the ~1MB Binder `applyBatch`
     *   transaction limit and throw `TransactionTooLargeException`. A single
     *   contact whose own blob exceeds the byte budget is still kept whole (it
     *   takes a lone batch) — the byte ceiling never splits a contact mid-way.
     * - Data rows back-reference their parent RawContact by its index WITHIN the
     *   batch (back-references are batch-relative), so the base index resets to 0
     *   at each new batch.
     */
    internal fun buildBatches(
        accountName: String,
        contacts: List<MappedContactWrite>,
    ): List<List<ContentProviderOperation>> {
        val rawUri = syncAdapterUri(RawContacts.CONTENT_URI, accountName)
        val dataUri = syncAdapterUri(Data.CONTENT_URI, accountName)

        val batches = ArrayList<List<ContentProviderOperation>>()
        var current = ArrayList<ContentProviderOperation>()
        var currentBytes = 0L

        for (contact in contacts) {
            // opsForContact = 1 RawContact + its data rows; keep it whole.
            val contactOpCount = 1 + contact.mapped.dataRows.size
            val contactBytes = contactBlobBytes(contact)
            val overOps = current.size + contactOpCount > MAX_OPS_PER_BATCH
            val overBytes = currentBytes + contactBytes > MAX_BATCH_BYTES
            if (current.isNotEmpty() && (overOps || overBytes)) {
                batches.add(current)
                current = ArrayList()
                currentBytes = 0L
            }

            val base = current.size // batch-relative index of this RawContact
            val rows = contact.mapped.dataRows
            current.add(
                ContentProviderOperation.newInsert(rawUri)
                    .withValues(rawContactValues(contact))
                    // A contact with zero data rows makes the RawContact itself the
                    // yield point so the boundary invariant still holds. Defensive:
                    // the mapper always emits a StructuredName, so rows is non-empty.
                    .withYieldAllowed(rows.isEmpty())
                    .build()
            )

            rows.forEachIndexed { i, values ->
                val isLastRowOfContact = i == rows.lastIndex
                current.add(
                    ContentProviderOperation.newInsert(dataUri)
                        .withValues(ContentValues(values))
                        .withValueBackReference(Data.RAW_CONTACT_ID, base)
                        // Yield on the LAST op of each contact only — never after the
                        // RawContact insert, which would let a nameless contact commit.
                        .withYieldAllowed(isLastRowOfContact)
                        .build()
                )
            }
            currentBytes += contactBytes
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
    }

    /**
     * Estimate a contact's contribution to the `applyBatch` transaction size. The
     * cost is dominated by inline blob columns (a contact PHOTO is up to hundreds of
     * KB); the mimetype/text columns are comparatively negligible, so summing the
     * `ByteArray` values across the contact's data rows is a safe lower bound that
     * captures the term that actually trips the Binder limit.
     */
    private fun contactBlobBytes(contact: MappedContactWrite): Long =
        contact.mapped.dataRows.sumOf { values ->
            values.keySet().sumOf { key -> (values.get(key) as? ByteArray)?.size?.toLong() ?: 0L }
        }

    /**
     * Build the one-batch operation list that attaches a photo and/or clears the
     * photo-pending flag on the RawContact [rawContactId] whose current SYNC4 is
     * [currentFlags]. Pure (no I/O) so the ops shape is unit-testable without a
     * live provider.
     *
     * When [bytes] is non-null: delete any existing Photo Data row first, then
     * insert the new blob — so blob and flag commit atomically and a retry cannot
     * duplicate the photo. When [bytes] is null: no blob ops, only the flag clear
     * (a pending contact whose re-read vCard dropped its URL photo).
     *
     * The flag clear is a bitwise AND-NOT of [FLAG_PHOTO_PENDING] against the READ
     * value of SYNC4 — it preserves every other bit and never zeroes the column.
     */
    internal fun buildPhotoWriteBatch(
        accountName: String,
        rawContactId: Long,
        currentFlags: Int,
        bytes: ByteArray?,
    ): List<ContentProviderOperation> {
        val dataUri = syncAdapterUri(Data.CONTENT_URI, accountName)
        val rawUri = syncAdapterUri(RawContacts.CONTENT_URI, accountName)
        val ops = ArrayList<ContentProviderOperation>()

        if (bytes != null) {
            // Delete-then-insert the Photo row so a retry (or a changed photo) can
            // never leave two Photo Data rows for the same RawContact.
            ops.add(
                ContentProviderOperation.newDelete(dataUri)
                    .withSelection(
                        "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
                        arrayOf(rawContactId.toString(), Photo.CONTENT_ITEM_TYPE),
                    )
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(dataUri)
                    .withValue(Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                    .withValue(Photo.PHOTO, bytes)
                    .build()
            )
        }

        // Clear only the pending bit; AND-NOT preserves any other SYNC4 flag.
        ops.add(
            ContentProviderOperation.newUpdate(rawUri)
                .withSelection("${RawContacts._ID} = ?", arrayOf(rawContactId.toString()))
                .withValue(RawContacts.SYNC4, currentFlags and FLAG_PHOTO_PENDING.inv())
                .build()
        )
        return ops
    }

    /** The SYNC columns + read-only flag for a contact's RawContact row. */
    private fun rawContactValues(contact: MappedContactWrite): ContentValues =
        ContentValues().apply {
            put(RawContacts.SOURCE_ID, contact.href)
            // Blank (not null) when the body carried no UID: a blank SYNC1 is never
            // a reconciliation match key, so it must not collide with another blank.
            put(RawContacts.SYNC1, contact.mapped.contact.uid)
            put(RawContacts.SYNC2, contact.etag)
            put(RawContacts.SYNC3, contentHash(contact))
            put(RawContacts.SYNC4, flagsFor(contact))
            put(RawContacts.RAW_CONTACT_IS_READ_ONLY, 1)
            // Fully isolate mirrored contacts from every other account. DISABLED (not
            // SUSPENDED) is deliberate: SUSPENDED only stops *automatic* aggregation
            // but still lets a RawContact join another account's contact via a manual
            // merge, and once joined, removing our account can cascade into recomputing
            // that aggregate and collapse the other account's contact. DISABLED keeps
            // our row a standalone contact that never links to a Google/local contact,
            // so purging or removing our account can only ever touch our own rows.
            put(RawContacts.AGGREGATION_MODE, RawContacts.AGGREGATION_MODE_DISABLED)
        }

    /**
     * Content hash for cheap no-op detection on re-pull (and later local-dirty
     * comparison). Hashes the verbatim vCard body — the full document the row was
     * mapped from — not the row set, so it changes iff the server bytes change.
     */
    private fun contentHash(contact: MappedContactWrite): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(contact.mapped.contact.rawVCard.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** SYNC4 flag bitset. Photo-pending is set when a remote-URL photo awaits fetch. */
    private fun flagsFor(contact: MappedContactWrite): Int {
        var flags = 0
        if (contact.mapped.photoUrl != null) flags = flags or FLAG_PHOTO_PENDING
        return flags
    }

    /**
     * Append the sync-adapter query params the provider reads off the URI:
     * `CALLER_IS_SYNCADAPTER=true` plus the account name/type. Writing in
     * sync-adapter mode is what lets us own the SOURCE_ID/SYNC columns and avoids
     * a DIRTY write-back loop.
     */
    private fun syncAdapterUri(uri: Uri, accountName: String): Uri =
        uri.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, accountName)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, KashCalContactsAuthenticator.ACCOUNT_TYPE)
            .build()

    /** Selection scoping a query/delete to one login's account (name AND type). */
    private fun accountScopeSelection(): String =
        "${RawContacts.ACCOUNT_NAME} = ? AND ${RawContacts.ACCOUNT_TYPE} = ?"

    private fun accountScopeArgs(accountName: String): Array<String> =
        arrayOf(accountName, KashCalContactsAuthenticator.ACCOUNT_TYPE)

    companion object {
        private const val TAG = "ContactsProviderRepo"

        /**
         * Ops per `applyBatch`. Kept well under the ~500-op practical Binder
         * transaction ceiling; a contact is never split across this boundary.
         */
        const val MAX_OPS_PER_BATCH = 100

        /**
         * Cumulative inline-blob byte ceiling per `applyBatch`. The Binder
         * transaction buffer is ~1MB shared process-wide, so this stays well under
         * it (an inline contact photo alone can be ~950KB — see the mapper's
         * `MAX_PHOTO_SIZE_BYTES`). A handful of photo-carrying contacts fits under
         * the op cap yet would overflow one transaction; this bound splits them.
         * A single contact heavier than the budget is still kept whole in a lone
         * batch — a contact is never split across an `applyBatch` boundary.
         */
        const val MAX_BATCH_BYTES = 512L * 1024

        /** SYNC4 bit: a remote-URL photo was seen but not yet fetched. */
        const val FLAG_PHOTO_PENDING = 1

        /**
         * Max hrefs per `SOURCE_ID IN (…)` delete. Well under SQLite's
         * SQLITE_MAX_VARIABLE_NUMBER (999 on old Androids); the two
         * account-scope args ride on every chunk, so the cap leaves headroom.
         */
        const val MAX_DELETE_IDS_PER_QUERY = 400
    }
}
