package org.onekash.kashcal.sync.contacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.OperationApplicationException
import android.database.Cursor
import android.net.Uri
import android.os.RemoteException
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onekash.kashcal.data.contacts.DeviceContactRowMapper
import org.onekash.kashcal.sync.adapter.KashCalContactsAuthenticator
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implements [ContactsProviderRepository] over the Android Contacts Provider.
 *
 * Holds every write of synced contacts to `ContactsContract`; `ContactsProviderWriteBoundaryTest`
 * keeps Contacts Provider writes inside `sync/contacts/`. It follows
 * [org.onekash.kashcal.data.calendar_provider.AndroidCalendarProviderRepository]'s `applyBatch`
 * and `Result`/catch shape.
 *
 * All writes go through a sync-adapter URI ([syncAdapterUri]) carrying
 * `CALLER_IS_SYNCADAPTER=true` and the account name and type, so the provider attributes rows to
 * the login's account and doesn't set a DIRTY flag that would spin a write-back loop.
 *
 * The account type is fixed to [KashCalContactsAuthenticator.ACCOUNT_TYPE]; the caller supplies
 * only the per-login account name (the email). Every write and delete is scoped to both, which
 * keeps one login's sync from touching another login's contacts or the calendar account's.
 */
@Singleton
class AndroidContactsProviderRepository @Inject constructor(
    private val contentResolver: ContentResolver,
    private val photoTranscoder: ContactPhotoTranscoder,
) : ContactsProviderRepository {

    override suspend fun insertContacts(
        accountName: String,
        contacts: List<MappedContactWrite>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (contacts.isEmpty()) return@withContext Result.success(Unit)

        // Create a titled Group for every CATEGORY before the membership rows reference it,
        // or a GroupMembership keyed by GROUP_SOURCE_ID makes the provider auto-create an
        // untitled group and the label shows blank.
        ensureGroups(accountName, contacts)

        val batches = buildBatches(accountName, contacts)
        for (batch in batches) {
            try {
                // Robolectric's ShadowContentResolver returns an empty result array (no
                // provider registered), and the RAW_CONTACT_ID back-references resolve
                // within the batch, so the ids aren't needed. Never dereference the result.
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
                // Keeps insertContacts from throwing (the interface contract) on unchecked
                // provider errors, e.g. IllegalArgumentException on an unresolvable URI or a
                // Binder-relayed RuntimeException.
                Log.w(TAG, "Contact insert chunk failed unexpectedly", e)
                return@withContext Result.failure(e)
            }
        }
        Result.success(Unit)
    }

    /**
     * Ensures a titled [Groups] row exists under [accountName] for every CATEGORY in
     * [contacts], so a `GroupMembership` keyed by `GROUP_SOURCE_ID` resolves to a named group.
     * The category name is both `SOURCE_ID` (the key the membership rows point at) and `TITLE`.
     *
     * Idempotent: only groups missing by SOURCE_ID are inserted. A failure is logged, not
     * fatal: the provider still auto-creates a blank group and the contact isn't lost.
     */
    private fun ensureGroups(accountName: String, contacts: List<MappedContactWrite>) {
        // The same names the mapper emits as GROUP_SOURCE_ID rows.
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

    /** Returns the non-blank SOURCE_IDs of this account's existing groups. */
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
     * Returns the non-blank values of the SOURCE_ID column [projection] at [uri] under this
     * account, for both the RawContacts and Groups reads. Callers handle errors.
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
                // Includes tombstones on purpose. A device-deleted row (DELETED=1) awaits a
                // DELETE push; keeping it here means an unchanged server etag is skipped
                // (the delete stays pending) and not re-inserted as a live row, and a device
                // holding only tombstones isn't read as wiped. The etag refresh this could
                // cause is stopped at the replace target lookup, where it would happen:
                // resolveRawContactIdsByHref excludes DELETED rows.
                accountScopeSelection(),
                accountScopeArgs(accountName),
                null,
            )?.use { cursor ->
                val out = HashMap<String, String?>(cursor.count)
                while (cursor.moveToNext()) {
                    val href = cursor.getString(0)?.takeIf { it.isNotEmpty() } ?: continue
                    // SYNC2 (etag) is blank when the server omitted an ETag; the null tells
                    // the caller there's no validator, so it replaces.
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
                    // SYNC4 is a nullable INTEGER; null means no flags. The pending bit is
                    // tested in code because a SQL bitwise selection behaves inconsistently on
                    // a null column.
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
     * Resolves the RawContact at [sourceId] and applies [buildPhotoWriteBatch] to it: the body
     * of [writePhotoAndClearPending] (non-null [bytes]) and [clearPhotoPending] (null). A
     * source id that no longer resolves (deleted between pull and fetch) is a no-op success;
     * a permission or provider failure returns [Result.failure].
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

    /** The `_ID` and current `SYNC4` flags of a RawContact found by SOURCE_ID. */
    private data class RawContactTarget(val rawContactId: Long, val flags: Int)

    /**
     * Returns the RawContact `_ID` and `SYNC4` flags for [sourceId] under this account, or null
     * when no row exists. Tombstones (`DELETED = 1`) match too. Account-scoped, so a source id
     * never resolves another login's row or the calendar account's.
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
            // Chunk the SOURCE_ID IN list so a large orphan sweep stays under SQLite's
            // bound-variable limit ([MAX_DELETE_IDS_PER_QUERY]).
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

        // Update in place so the RawContact _ID survives a server edit, and with it the
        // aggregate Contact id and everything keyed on it: the starred flag, home-screen
        // shortcuts, the lookup key. A delete and re-insert would mint a new _ID and
        // silently drop them. Hrefs without a live row are inserted.
        val existingIds = resolveRawContactIdsByHref(accountName, contacts.map { it.href })

        // Groups for the whole set first, so a membership row on either path resolves to a
        // titled group.
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
     * Maps each href in [hrefs] that has a live RawContact under this account to its `_ID`.
     * Absent hrefs are left out and go to the insert path. Account-scoped, so an href never
     * resolves another login's row or the calendar account's.
     */
    private fun resolveRawContactIdsByHref(
        accountName: String,
        hrefs: List<String>,
    ): Map<String, Long> {
        if (hrefs.isEmpty()) return emptyMap()
        return contentResolver.query(
            syncAdapterUri(RawContacts.CONTENT_URI, accountName),
            arrayOf(RawContacts.SOURCE_ID, RawContacts._ID),
            // Live rows only. A tombstone as the replace target would get its etag
            // refreshed, arming the queued DELETE to destroy the server copy someone just
            // edited. A delete-pending href is inserted as a new row instead.
            "${accountScopeSelection()} AND ${RawContacts.DELETED} = 0",
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
     * Builds the ops that replace one contact in place on [rawContactId], keeping its `_ID`:
     * update the RawContact's SYNC columns, delete its Data rows, insert the mapped Data rows.
     * The mapper emits the complete row set, so replacing every Data row is simpler than a
     * per-field diff, and the RawContact row (and what the aggregate Contact keys on it)
     * survives.
     */
    private fun buildInPlaceReplaceBatch(
        accountName: String,
        rawContactId: Long,
        contact: MappedContactWrite,
    ): List<ContentProviderOperation> {
        val rawUri = syncAdapterUri(RawContacts.CONTENT_URI, accountName)
        val dataUri = syncAdapterUri(Data.CONTENT_URI, accountName)
        val ops = ArrayList<ContentProviderOperation>(2 + contact.mapped.dataRows.size)

        // The RawContact's sync columns: etag, hash, flags.
        ops.add(
            ContentProviderOperation.newUpdate(
                ContentUris.withAppendedId(rawUri, rawContactId),
            ).withValues(rawContactValues(contact)).build(),
        )
        // Delete the Data rows, then insert the current set, an inline Photo row included;
        // a URL photo sets the SYNC4 pending flag through rawContactValues.
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
            // A read failure must not report leftovers that may not exist; 0 means can't tell.
            Log.w(TAG, "READ_CONTACTS revoked; countRawContacts returns 0", e)
            0
        } catch (e: Exception) {
            Log.w(TAG, "countRawContacts query failed", e)
            0
        }
    }

    override suspend fun ensureContactVisibility(accountName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Contacts under a custom account type with no group membership are hidden by
            // default; UNGROUPED_VISIBLE shows them.
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

    override suspend fun pendingLocalChanges(accountName: String): LocalContactChanges =
        withContext(Dispatchers.IO) {
            try {
                LocalContactChanges(
                    edited = queryDirtyEdits(accountName),
                    deleted = queryTombstones(accountName),
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "READ_CONTACTS revoked; pendingLocalChanges returns empty")
                LocalContactChanges(emptyList(), emptyList())
            } catch (e: CancellationException) {
                // Propagate: an empty pending set would let a cancelled run succeed having
                // pushed nothing.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "pendingLocalChanges scan failed")
                LocalContactChanges(emptyList(), emptyList())
            }
        }

    /**
     * Reads the account's `DIRTY = 1 AND DELETED = 0` RawContacts (user edits and
     * device-created contacts) as [LocalContactEdit]s. The locators are read in full before the
     * per-row Data reads so no cursor stays open across a nested query. `DELETED = 0` leaves a
     * soft-deleted dirty row to the tombstone scan.
     */
    private fun queryDirtyEdits(accountName: String): List<LocalContactEdit> {
        data class Locator(val id: Long, val href: String, val uid: String, val etag: String?)

        val locators = contentResolver.query(
            syncAdapterUri(RawContacts.CONTENT_URI, accountName),
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1, RawContacts.SYNC2),
            "${accountScopeSelection()} AND ${RawContacts.DIRTY} = 1 AND ${RawContacts.DELETED} = 0",
            accountScopeArgs(accountName),
            null,
        )?.use { cursor ->
            val out = ArrayList<Locator>(cursor.count)
            while (cursor.moveToNext()) {
                out.add(
                    Locator(
                        id = cursor.getLong(0),
                        href = cursor.getString(1).orEmpty(),
                        uid = cursor.getString(2).orEmpty(),
                        etag = cursor.getString(3)?.takeIf { it.isNotEmpty() },
                    ),
                )
            }
            out
        }.orEmpty()

        if (locators.isEmpty()) return emptyList()

        // Group titles, once per scan, so a GroupMembership row with only a GROUP_ROW_ID (a
        // label the user added in the Contacts app, GROUP_SOURCE_ID blank) maps back to its
        // category instead of silently dropping.
        val groupTitles = queryGroupTitles(accountName)

        return locators.map { loc ->
            val mapped = DeviceContactRowMapper.toContact(
                readDataRows(accountName, loc.id),
                uid = loc.uid,
                groupTitlesById = groupTitles,
            )
            LocalContactEdit(
                href = loc.href,
                uid = loc.uid,
                storedEtag = loc.etag,
                // version, rawVCard and kind aren't on Data rows and keep their defaults; the
                // push composes the body at the book's version. The UID comes from SYNC1.
                // The photo is normalized ([ContactPhotoTranscoder]) so strict servers
                // store it.
                contact = mapped.copy(photo = photoTranscoder.normalize(mapped.photo)),
                // The only stable key a net-new contact (blank SOURCE_ID) can be written back
                // against after its server create.
                localId = loc.id,
            )
        }
    }

    /**
     * Maps this account's `Groups._ID` to `TITLE` (blank titles skipped), for a
     * [android.provider.ContactsContract.CommonDataKinds.GroupMembership] row that carries only a
     * `GROUP_ROW_ID`. A read failure returns an empty map (only GROUP_SOURCE_ID categories
     * resolve) and doesn't abort the edit scan; a cancellation propagates.
     */
    private fun queryGroupTitles(accountName: String): Map<Long, String> =
        try {
            contentResolver.query(
                syncAdapterUri(Groups.CONTENT_URI, accountName),
                arrayOf(Groups._ID, Groups.TITLE),
                accountScopeSelection(),
                accountScopeArgs(accountName),
                null,
            )?.use { cursor ->
                val out = HashMap<Long, String>(cursor.count)
                while (cursor.moveToNext()) {
                    val title = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    out[cursor.getLong(0)] = title
                }
                out
            }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            // Log the message only, never the throwable: a provider exception that echoes
            // the account-scoped URI would leak the account name to logcat. The other
            // edit-scan guards do the same.
            Log.w(TAG, "READ_CONTACTS revoked; group titles unresolved (labels may drop)")
            emptyMap()
        } catch (e: Exception) {
            Log.w(TAG, "Group title query failed; labels may drop")
            emptyMap()
        }

    /** Reads the account's `DELETED = 1` RawContacts as [LocalContactTombstone]s. */
    private fun queryTombstones(accountName: String): List<LocalContactTombstone> =
        contentResolver.query(
            syncAdapterUri(RawContacts.CONTENT_URI, accountName),
            arrayOf(RawContacts.SOURCE_ID, RawContacts.SYNC2),
            "${accountScopeSelection()} AND ${RawContacts.DELETED} = 1",
            accountScopeArgs(accountName),
            null,
        )?.use { cursor ->
            val out = ArrayList<LocalContactTombstone>(cursor.count)
            while (cursor.moveToNext()) {
                out.add(
                    LocalContactTombstone(
                        href = cursor.getString(0).orEmpty(),
                        storedEtag = cursor.getString(1)?.takeIf { it.isNotEmpty() },
                    ),
                )
            }
            out
        }.orEmpty()

    /** Reads every Data row of one RawContact as [ContentValues], for the reverse mapper. */
    private fun readDataRows(accountName: String, rawContactId: Long): List<ContentValues> =
        contentResolver.query(
            syncAdapterUri(Data.CONTENT_URI, accountName),
            null,
            "${Data.RAW_CONTACT_ID} = ?",
            arrayOf(rawContactId.toString()),
            null,
        )?.use { cursor ->
            val out = ArrayList<ContentValues>(cursor.count)
            while (cursor.moveToNext()) out.add(cursorRowToValues(cursor))
            out
        }.orEmpty()

    /**
     * Copies the current cursor row into [ContentValues], keeping each column's type. The
     * platform `DatabaseUtils.cursorRowToContentValues` coerces to String, which would break an
     * INTEGER column (`Email.TYPE`, `IS_PRIMARY`) or a BLOB photo for the reverse mapper's
     * `getAsInteger` and `getAsByteArray` reads.
     */
    private fun cursorRowToValues(cursor: Cursor): ContentValues {
        val values = ContentValues(cursor.columnCount)
        for (i in 0 until cursor.columnCount) {
            val name = cursor.getColumnName(i)
            when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> {} // leave absent
                Cursor.FIELD_TYPE_INTEGER -> values.put(name, cursor.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> values.put(name, cursor.getDouble(i))
                Cursor.FIELD_TYPE_BLOB -> values.put(name, cursor.getBlob(i))
                else -> values.put(name, cursor.getString(i))
            }
        }
        return values
    }

    override suspend fun markContactUploaded(
        accountName: String,
        href: String,
        newEtag: String,
        newHref: String?,
    ): Result<Unit> = applyScopedIdWrite("mark-uploaded", resolveRawContactId(accountName, href)) { id ->
        ContentProviderOperation.newUpdate(rawContactByIdUri(accountName, id))
            .apply { if (newHref != null) withValue(RawContacts.SOURCE_ID, newHref) }
            .withValue(RawContacts.SYNC2, newEtag)
            // Clear DIRTY in the same sync-adapter write. Sync-adapter mode stops the
            // provider setting DIRTY again on this write, which would spin the push
            // forever; clearing it takes the row out of the next scan.
            .withValue(RawContacts.DIRTY, 0)
            .build()
    }

    override suspend fun assignContactUid(
        accountName: String,
        localId: Long,
        uid: String,
    ): Result<Unit> =
        // By _ID: a net-new row's SOURCE_ID is still blank. A 0L (unset) _ID is a no-op
        // and stamps no row.
        applyScopedIdWrite("assign-uid", localId.takeIf { it > 0L }) { id ->
            ContentProviderOperation.newUpdate(rawContactByIdUri(accountName, id))
                // Persist the generated UID so it names the resource and retries reuse it.
                // DIRTY stays 1 so the row stays pending until the server create clears it;
                // sync-adapter mode keeps this write from counting as a new edit.
                .withValue(RawContacts.SYNC1, uid)
                .withValue(RawContacts.DIRTY, 1)
                .build()
        }

    override suspend fun markNewContactUploaded(
        accountName: String,
        localId: Long,
        href: String,
        newEtag: String,
    ): Result<Unit> =
        // By _ID, never a SOURCE_ID lookup: a net-new row's SOURCE_ID is still blank, so an
        // href lookup would find nothing. A 0L (unset) _ID passes null to
        // applyScopedIdWrite, a no-op that stamps no row.
        applyScopedIdWrite("mark-new-uploaded", localId.takeIf { it > 0L }) { id ->
            ContentProviderOperation.newUpdate(rawContactByIdUri(accountName, id))
                // The new server href, so the next pull matches this row and skips it
                // instead of inserting a duplicate.
                .withValue(RawContacts.SOURCE_ID, href)
                .withValue(RawContacts.SYNC2, newEtag)
                // Clear DIRTY in the same sync-adapter write so this write-back isn't read as
                // a new edit, which would spin the push forever.
                .withValue(RawContacts.DIRTY, 0)
                .build()
        }

    override suspend fun hardDeleteTombstone(accountName: String, href: String): Result<Unit> =
        applyScopedIdWrite("hard-delete-tombstone", resolveTombstoneId(accountName, href)) { id ->
            // A delete through the sync-adapter URI is a hard delete; a non-adapter delete
            // only sets DELETED again, leaving the row forever.
            ContentProviderOperation.newDelete(rawContactByIdUri(accountName, id)).build()
        }

    override suspend fun restoreTombstone(accountName: String, href: String): Result<Unit> =
        applyScopedIdWrite("restore-tombstone", resolveTombstoneId(accountName, href)) { id ->
            ContentProviderOperation.newUpdate(rawContactByIdUri(accountName, id))
                .withValue(RawContacts.DELETED, 0)
                // Clear DIRTY too, or the restored row comes back as a pending edit on the
                // next scan.
                .withValue(RawContacts.DIRTY, 0)
                .build()
        }

    /**
     * Applies the single op [buildOp] builds for [rawContactId] and checks the applied-op count.
     * A null [rawContactId] (no matching row, or an unset `_ID`) is a no-op success. A short
     * `applyBatch` result is a [Result.failure]: per-op counts can lie, but a short result array
     * reliably means the write didn't fully apply. Failures are classified as
     * [ContactWriteFailure]; no provider message (which could embed an href, email or URL)
     * reaches the log or the failure.
     */
    private suspend fun applyScopedIdWrite(
        label: String,
        rawContactId: Long?,
        buildOp: (Long) -> ContentProviderOperation,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val id = rawContactId ?: return@withContext Result.success(Unit)
        val ops = arrayListOf(buildOp(id))
        try {
            val results = contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            if (results.size < ops.size) {
                Log.w(TAG, "$label: applyBatch returned ${results.size} of ${ops.size} ops")
                Result.failure(ContactWriteException(ContactWriteFailure.PARTIAL_APPLY))
            } else {
                Result.success(Unit)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "$label: WRITE_CONTACTS revoked")
            Result.failure(ContactWriteException(ContactWriteFailure.PERMISSION_DENIED))
        } catch (e: OperationApplicationException) {
            Log.w(TAG, "$label: batch failed to apply")
            Result.failure(ContactWriteException(ContactWriteFailure.PROVIDER_ERROR))
        } catch (e: RemoteException) {
            Log.w(TAG, "$label: Contacts Provider unavailable")
            Result.failure(ContactWriteException(ContactWriteFailure.PROVIDER_ERROR))
        } catch (e: CancellationException) {
            // Not a provider error: let it unwind, or the write-back reads as a recoverable
            // failure that holds the sync-token.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$label: batch failed unexpectedly")
            Result.failure(ContactWriteException(ContactWriteFailure.PROVIDER_ERROR))
        }
    }

    /**
     * Returns the `_ID` of the RawContact at [href] under this account, or null. A tombstone
     * matches too ([resolveRawContact] doesn't filter DELETED).
     */
    private fun resolveRawContactId(accountName: String, href: String): Long? =
        resolveRawContact(accountName, href)?.rawContactId

    /**
     * Returns the `_ID` of the soft-deleted (`DELETED = 1`) RawContact at [href] under this
     * account, or null. The `DELETED = 1` filter keeps a tombstone write off a live row that
     * shares the href.
     */
    private fun resolveTombstoneId(accountName: String, href: String): Long? =
        contentResolver.query(
            syncAdapterUri(RawContacts.CONTENT_URI, accountName),
            arrayOf(RawContacts._ID),
            "${accountScopeSelection()} AND ${RawContacts.SOURCE_ID} = ? AND ${RawContacts.DELETED} = 1",
            accountScopeArgs(accountName) + href,
            null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    /** Returns the sync-adapter, account-scoped URI of one RawContact by `_ID`. */
    private fun rawContactByIdUri(accountName: String, rawContactId: Long): Uri =
        ContentUris.withAppendedId(syncAdapterUri(RawContacts.CONTENT_URI, accountName), rawContactId)

    /**
     * Builds the [ContentProviderOperation] batches for [contacts]. No I/O, so the chunking and
     * yield invariants are unit-testable without a provider.
     *
     * Invariants:
     * - A RawContact insert and all its Data rows sit in one batch, never split across an
     *   `applyBatch` boundary, and each contact's last op is its yield point, so a partial
     *   failure can't commit a nameless RawContact.
     * - A batch is bounded by both the op count ([MAX_OPS_PER_BATCH]) and the inline-blob bytes
     *   ([MAX_BATCH_BYTES]). An inline photo can be hundreds of KB, so a few photo contacts fit
     *   under the op cap yet exceed the ~1MB Binder `applyBatch` transaction limit and throw
     *   `TransactionTooLargeException`. A contact whose own blob exceeds the budget takes a
     *   batch alone; the byte limit never splits a contact.
     * - Data rows back-reference their RawContact by its index within the batch, so the base
     *   index restarts at 0 in each batch.
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
            // One RawContact plus its Data rows, kept whole.
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
                    // With zero Data rows the RawContact is the yield point, so the invariant
                    // holds. The mapper always emits a StructuredName, so rows isn't empty.
                    .withYieldAllowed(rows.isEmpty())
                    .build()
            )

            rows.forEachIndexed { i, values ->
                val isLastRowOfContact = i == rows.lastIndex
                current.add(
                    ContentProviderOperation.newInsert(dataUri)
                        .withValues(ContentValues(values))
                        .withValueBackReference(Data.RAW_CONTACT_ID, base)
                        // Yield only on each contact's last op, never after the RawContact
                        // insert, which would let a nameless contact commit.
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
     * Estimates a contact's share of the `applyBatch` transaction size as the sum of its
     * `ByteArray` Data values. Inline blobs (a photo can be hundreds of KB) dominate and are
     * what trips the Binder limit; the text columns are negligible, so this lower bound is
     * enough.
     */
    private fun contactBlobBytes(contact: MappedContactWrite): Long =
        contact.mapped.dataRows.sumOf { values ->
            values.keySet().sumOf { key -> (values.get(key) as? ByteArray)?.size?.toLong() ?: 0L }
        }

    /**
     * Builds the one batch that attaches a photo and clears the photo-pending flag on
     * [rawContactId], whose SYNC4 is [currentFlags]. No I/O, so the ops are unit-testable
     * without a provider.
     *
     * With [bytes]: delete any Photo Data row, then insert the new blob, so blob and flag
     * commit together and a retry can't duplicate the photo. With null [bytes]: only the flag
     * clear (the re-read vCard has no URL photo, or the fetch failed permanently).
     *
     * The flag clear ANDs [currentFlags] with the inverse of [FLAG_PHOTO_PENDING], keeping every
     * other bit; it never zeroes the column.
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
            // Delete, then insert, so a retry or a changed photo never leaves two Photo
            // Data rows on one RawContact.
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

    /** Builds the SYNC columns, read-only flag and aggregation mode of a contact's RawContact. */
    private fun rawContactValues(contact: MappedContactWrite): ContentValues =
        ContentValues().apply {
            put(RawContacts.SOURCE_ID, contact.href)
            // Blank, not null, when the body has no UID; a blank SYNC1 is never used to
            // match rows ([MappedContactWrite]).
            put(RawContacts.SYNC1, contact.mapped.contact.uid)
            put(RawContacts.SYNC2, contact.etag)
            put(RawContacts.SYNC3, contentHash(contact))
            put(RawContacts.SYNC4, flagsFor(contact))
            // Editable only when the book is writable: an editable row lets the provider set
            // DIRTY on a user edit (the push signal); a read-only book's contacts could
            // never be pushed back.
            put(RawContacts.RAW_CONTACT_IS_READ_ONLY, if (contact.isReadOnly) 1 else 0)
            // Isolate synced contacts from every other account. DISABLED, not SUSPENDED:
            // SUSPENDED only stops automatic aggregation and still lets a manual merge join
            // the row to another account's contact, and once joined, removing this account
            // can recompute that aggregate and collapse the other account's contact.
            // DISABLED keeps the row a standalone contact that never links to another
            // account's, so purging or removing this account only touches its own rows.
            put(RawContacts.AGGREGATION_MODE, RawContacts.AGGREGATION_MODE_DISABLED)
        }

    /**
     * Returns the SHA-256 of the verbatim vCard body the row was mapped from (not the row set),
     * so it changes only when the server bytes change. Written to SYNC3; nothing reads SYNC3
     * today.
     */
    private fun contentHash(contact: MappedContactWrite): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(contact.mapped.contact.rawVCard.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Returns the SYNC4 bitset: [FLAG_PHOTO_PENDING] when a remote-URL photo awaits fetch. */
    private fun flagsFor(contact: MappedContactWrite): Int {
        var flags = 0
        if (contact.mapped.photoUrl != null) flags = flags or FLAG_PHOTO_PENDING
        return flags
    }

    /**
     * Appends the sync-adapter query params the provider reads: `CALLER_IS_SYNCADAPTER=true` and
     * the account name and type. Sync-adapter mode lets this class write the SOURCE_ID and SYNC
     * columns and avoids a DIRTY write-back loop.
     */
    private fun syncAdapterUri(uri: Uri, accountName: String): Uri =
        uri.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, accountName)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, KashCalContactsAuthenticator.ACCOUNT_TYPE)
            .build()

    /** Returns the selection scoping a query or delete to one login's account name and type. */
    private fun accountScopeSelection(): String =
        "${RawContacts.ACCOUNT_NAME} = ? AND ${RawContacts.ACCOUNT_TYPE} = ?"

    private fun accountScopeArgs(accountName: String): Array<String> =
        arrayOf(accountName, KashCalContactsAuthenticator.ACCOUNT_TYPE)

    companion object {
        private const val TAG = "ContactsProviderRepo"

        /**
         * Ops per `applyBatch`, well under the practical Binder limit of about 500 ops; a
         * contact is never split across this boundary.
         */
        const val MAX_OPS_PER_BATCH = 100

        /**
         * Inline-blob bytes per `applyBatch`, well under the ~1MB process-wide Binder
         * transaction buffer; one inline photo can be up to
         * [org.onekash.kashcal.data.contacts.MAX_PHOTO_SIZE_BYTES] (950KB). A contact heavier
         * than the budget takes a batch alone; rules on [buildBatches].
         */
        const val MAX_BATCH_BYTES = 512L * 1024

        /** SYNC4 bit: a remote-URL photo was seen but not yet fetched. */
        const val FLAG_PHOTO_PENDING = 1

        /**
         * Max hrefs per `SOURCE_ID IN` delete, under SQLite's SQLITE_MAX_VARIABLE_NUMBER (999 on
         * old Androids) with headroom for the two account-scope args on every chunk.
         */
        const val MAX_DELETE_IDS_PER_QUERY = 400
    }
}
