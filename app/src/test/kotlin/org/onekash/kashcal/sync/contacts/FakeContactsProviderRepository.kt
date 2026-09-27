package org.onekash.kashcal.sync.contacts

/**
 * Models the Contacts Provider in memory as the one shared fake of
 * [ContactsProviderRepository], data-bearing so a wrong stub can't pass silently.
 *
 * Robolectric's `ShadowContentResolver` doesn't execute Contacts Provider writes (no provider
 * is registered), so the real [AndroidContactsProviderRepository] can't give
 * [existingEtagsByHref] a non-empty read-back, the signal a pull needs to tell changed from
 * unchanged. This fake keeps a per-account href to etag store: inserts add, replaces update
 * the etag in place (as the real in-place replace keeps the row), and deletes remove. Reads
 * reflect the current store, so a strategy's insert, replace, skip and orphan-delete routing
 * is observable end to end.
 *
 * Seed the store with [seed] for hrefs already on the device before a run; assert on
 * [insertCalls], [replaceCalls] and [deleteCalls] (each in call order) and the resulting
 * [hrefsFor].
 */
class FakeContactsProviderRepository : ContactsProviderRepository {

    // account name -> (href -> stored etag)
    private val store = HashMap<String, HashMap<String, String?>>()

    /** Every [insertContacts] call's argument, in call order. */
    val insertCalls = mutableListOf<List<MappedContactWrite>>()

    /** Every [replaceContacts] call's argument, in call order. */
    val replaceCalls = mutableListOf<List<MappedContactWrite>>()

    /** Every [deleteByHrefs] call's hrefs, in call order. */
    val deleteCalls = mutableListOf<List<String>>()

    /** Account names [ensureContactVisibility] was called for, in call order. */
    val ensureVisibilityCalls = mutableListOf<String>()

    // account name -> set of source ids (hrefs) with the photo-pending bit set
    private val pendingPhotos = HashMap<String, MutableSet<String>>()

    /** Photos written by [writePhotoAndClearPending], keyed sourceId -> bytes (per account). */
    private val writtenPhotos = HashMap<String, HashMap<String, ByteArray>>()

    /** Every [clearPhotoPending] call as (accountName, sourceId), in call order. */
    val clearPhotoPendingCalls = mutableListOf<Pair<String, String>>()

    /** When a failure, the matching verb returns it instead of mutating. */
    var insertResult: Result<Unit> = Result.success(Unit)
    var replaceResult: Result<Unit> = Result.success(Unit)
    var deleteResult: Result<Unit> = Result.success(Unit)
    var writePhotoResult: Result<Unit> = Result.success(Unit)

    /**
     * When set, [clearPhotoPending] throws this instead of returning: a collaborator breaking
     * its Result contract, so a caller's never-throws guard (the photo fetcher's) can be
     * exercised.
     */
    var clearPhotoPendingThrows: RuntimeException? = null

    /** Pre-populate the device state for [accountName]. */
    fun seed(accountName: String, href: String, etag: String?) {
        store.getOrPut(accountName) { HashMap() }[href] = etag
    }

    /** Mark [sourceId] as photo-pending under [accountName] (the fetcher's worklist). */
    fun seedPendingPhoto(accountName: String, sourceId: String) {
        pendingPhotos.getOrPut(accountName) { mutableSetOf() }.add(sourceId)
    }

    /** The photo bytes written for [sourceId], or null if none written. */
    fun writtenPhotoFor(accountName: String, sourceId: String): ByteArray? =
        writtenPhotos[accountName]?.get(sourceId)

    /** Whether [sourceId] still has the photo-pending bit set under [accountName]. */
    fun isPhotoPending(accountName: String, sourceId: String): Boolean =
        pendingPhotos[accountName]?.contains(sourceId) == true

    /** Current hrefs stored for [accountName] (the device state after a run). */
    fun hrefsFor(accountName: String): Set<String> = store[accountName]?.keys?.toSet() ?: emptySet()

    /** Current stored etag for one href, or null if absent or etag-less. */
    fun etagFor(accountName: String, href: String): String? = store[accountName]?.get(href)

    override suspend fun insertContacts(
        accountName: String,
        contacts: List<MappedContactWrite>,
    ): Result<Unit> {
        insertCalls += contacts
        if (insertResult.isFailure) return insertResult
        val m = store.getOrPut(accountName) { HashMap() }
        contacts.forEach { m[it.href] = it.etag }
        return Result.success(Unit)
    }

    override suspend fun existingSourceIds(accountName: String): Set<String> =
        store[accountName]?.keys?.toSet() ?: emptySet()

    override suspend fun existingEtagsByHref(accountName: String): Map<String, String?> =
        store[accountName]?.toMap() ?: emptyMap()

    override suspend fun deleteByHrefs(
        accountName: String,
        hrefs: Collection<String>,
    ): Result<Unit> {
        deleteCalls += hrefs.toList()
        if (deleteResult.isFailure) return deleteResult
        store[accountName]?.let { m -> hrefs.forEach { m.remove(it) } }
        return Result.success(Unit)
    }

    override suspend fun replaceContacts(
        accountName: String,
        contacts: List<MappedContactWrite>,
    ): Result<Unit> {
        replaceCalls += contacts
        if (replaceResult.isFailure) return replaceResult
        val m = store.getOrPut(accountName) { HashMap() }
        contacts.forEach { m[it.href] = it.etag } // in place: new etag, same href
        return Result.success(Unit)
    }

    override suspend fun pendingPhotoSourceIds(accountName: String): Set<String> =
        pendingPhotos[accountName]?.toSet() ?: emptySet()

    override suspend fun writePhotoAndClearPending(
        accountName: String,
        sourceId: String,
        bytes: ByteArray,
    ): Result<Unit> {
        if (writePhotoResult.isFailure) return writePhotoResult // left pending, not written
        writtenPhotos.getOrPut(accountName) { HashMap() }[sourceId] = bytes
        pendingPhotos[accountName]?.remove(sourceId)
        return Result.success(Unit)
    }

    override suspend fun clearPhotoPending(accountName: String, sourceId: String): Result<Unit> {
        clearPhotoPendingThrows?.let { throw it }
        clearPhotoPendingCalls += accountName to sourceId
        pendingPhotos[accountName]?.remove(sourceId)
        return Result.success(Unit)
    }

    /** Every [purgeAccount] call's account name, in call order. */
    val purgeCalls = mutableListOf<String>()

    /**
     * A call log shared across collaborators, for ordering assertions. [purgeAccount] and
     * [countRawContacts] append to it; a test can have a mocked account removal append here
     * too, then assert the scoped purge ran before the removal: the invariant that deleting our
     * rows must not depend on the OS cascade.
     */
    val operationLog = mutableListOf<String>()

    /**
     * When a failure, [purgeAccount] records the call and returns it without clearing the
     * store: revoked WRITE_CONTACTS, where the delete never runs.
     */
    var purgeResult: Result<Unit> = Result.success(Unit)

    /**
     * When non-null, [countRawContacts] returns this instead of the store size, for example a
     * leftover count or the 0 a failed read returns.
     */
    var countOverride: Int? = null

    override suspend fun purgeAccount(accountName: String): Result<Unit> {
        purgeCalls += accountName
        operationLog += "purge:$accountName"
        if (purgeResult.isFailure) return purgeResult
        store.remove(accountName)
        pendingPhotos.remove(accountName)
        writtenPhotos.remove(accountName)
        return Result.success(Unit)
    }

    override suspend fun countRawContacts(accountName: String): Int {
        operationLog += "count:$accountName"
        return countOverride ?: (store[accountName]?.size ?: 0)
    }

    override suspend fun ensureContactVisibility(accountName: String): Result<Unit> {
        ensureVisibilityCalls += accountName
        return Result.success(Unit)
    }

    /**
     * The device's pending set a run sees, seeded by the test (empty by default).
     * [markContactUploaded], [markNewContactUploaded], [hardDeleteTombstone] and
     * [restoreTombstone] prune the matching entry so a later read reflects the write, as the
     * real provider clears `DIRTY`, hard-deletes the tombstone or clears `DELETED`.
     */
    var pendingChanges = LocalContactChanges(edited = emptyList(), deleted = emptyList())

    /** Every [markContactUploaded] call as (accountName, href, newEtag), in order. */
    val markUploadedCalls = mutableListOf<Triple<String, String, String>>()

    /** Every [markContactUploaded] that moved a contact to a new href, as (old href, new href). */
    val movedHrefs = mutableListOf<Pair<String, String>>()

    /** One [assignContactUid] call: the UID persisted before a create, keyed by provider _ID. */
    data class AssignUid(val accountName: String, val localId: Long, val uid: String)

    /** Every [assignContactUid] call, in order. */
    val assignUidCalls = mutableListOf<AssignUid>()

    /** When a failure, [assignContactUid] records the call and returns it without persisting. */
    var assignUidResult: Result<Unit> = Result.success(Unit)

    /** One [markNewContactUploaded] call: the net-new write-back keyed by provider _ID. */
    data class MarkNewUploaded(val accountName: String, val localId: Long, val href: String, val newEtag: String)

    /** Every [markNewContactUploaded] call, in order. */
    val markNewUploadedCalls = mutableListOf<MarkNewUploaded>()

    /** When a failure, [markNewContactUploaded] records the call and returns it unmutated. */
    var markNewUploadedResult: Result<Unit> = Result.success(Unit)

    /** Every [hardDeleteTombstone] call as (accountName, href), in order. */
    val hardDeleteCalls = mutableListOf<Pair<String, String>>()

    /** Every [restoreTombstone] call as (accountName, href), in order. */
    val restoreCalls = mutableListOf<Pair<String, String>>()

    /** When a failure, the matching write-back verb records the call and returns it unmutated. */
    var markUploadedResult: Result<Unit> = Result.success(Unit)
    var hardDeleteResult: Result<Unit> = Result.success(Unit)
    var restoreResult: Result<Unit> = Result.success(Unit)

    override suspend fun pendingLocalChanges(accountName: String): LocalContactChanges = pendingChanges

    override suspend fun markContactUploaded(
        accountName: String,
        href: String,
        newEtag: String,
        newHref: String?,
    ): Result<Unit> {
        markUploadedCalls += Triple(accountName, href, newEtag)
        if (newHref != null) movedHrefs += Pair(href, newHref)
        if (markUploadedResult.isFailure) return markUploadedResult
        val book = store.getOrPut(accountName) { HashMap() }
        if (newHref != null) book.remove(href)
        book[newHref ?: href] = newEtag
        pendingChanges = pendingChanges.copy(edited = pendingChanges.edited.filterNot { it.href == href })
        return Result.success(Unit)
    }

    override suspend fun assignContactUid(
        accountName: String,
        localId: Long,
        uid: String,
    ): Result<Unit> {
        assignUidCalls += AssignUid(accountName, localId, uid)
        if (assignUidResult.isFailure) return assignUidResult
        // Stamp the UID onto the matching pending edit (its SYNC1) and keep it pending
        // (DIRTY still set), as the real provider does. A retry on a later run reads the same
        // UID and targets the same resource instead of generating a new one.
        pendingChanges = pendingChanges.copy(
            edited = pendingChanges.edited.map {
                if (localId != 0L && it.localId == localId) it.copy(uid = uid) else it
            },
        )
        return Result.success(Unit)
    }

    override suspend fun markNewContactUploaded(
        accountName: String,
        localId: Long,
        href: String,
        newEtag: String,
    ): Result<Unit> {
        markNewUploadedCalls += MarkNewUploaded(accountName, localId, href, newEtag)
        if (markNewUploadedResult.isFailure) return markNewUploadedResult
        // The originating row is now synced under its new server href, and its net-new pending
        // edit (keyed by provider _ID, since its href was blank) is pruned, as the real
        // provider stamps SOURCE_ID and clears DIRTY.
        store.getOrPut(accountName) { HashMap() }[href] = newEtag
        pendingChanges = pendingChanges.copy(edited = pendingChanges.edited.filterNot { it.localId == localId })
        return Result.success(Unit)
    }

    /**
     * Counts the device contact rows modeled for [accountName]: every synced row in [store]
     * plus every blank-href pending [LocalContactEdit]. A net-new create whose href write-back
     * fails leaves both a synced row under the new href and its pending blank-href edit, the
     * duplicate this count exposes (2 instead of 1).
     */
    fun deviceRowCount(accountName: String): Int =
        (store[accountName]?.size ?: 0) + pendingChanges.edited.count { it.href.isBlank() }

    override suspend fun hardDeleteTombstone(accountName: String, href: String): Result<Unit> {
        hardDeleteCalls += accountName to href
        if (hardDeleteResult.isFailure) return hardDeleteResult
        store[accountName]?.remove(href)
        pendingChanges = pendingChanges.copy(deleted = pendingChanges.deleted.filterNot { it.href == href })
        return Result.success(Unit)
    }

    override suspend fun restoreTombstone(accountName: String, href: String): Result<Unit> {
        restoreCalls += accountName to href
        if (restoreResult.isFailure) return restoreResult
        pendingChanges = pendingChanges.copy(deleted = pendingChanges.deleted.filterNot { it.href == href })
        return Result.success(Unit)
    }
}
