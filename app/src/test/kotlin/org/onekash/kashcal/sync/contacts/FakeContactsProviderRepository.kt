package org.onekash.kashcal.sync.contacts

/**
 * The canonical in-memory fake of [ContactsProviderRepository] — one shared test
 * double for the interface (project convention), data-bearing rather than a
 * relaxed mock so a wrong-stub can't pass silently.
 *
 * Robolectric's `ShadowContentResolver` cannot execute Contacts Provider writes
 * (no provider is registered), so the real [AndroidContactsProviderRepository]
 * can never feed [existingEtagsByHref] a non-empty read-back — which is the exact
 * signal a pull strategy needs to tell *changed* from *unchanged*. This fake
 * therefore models the provider as a live per-account href→etag store: inserts
 * add, replaces remove-then-add (mirroring the real change-as-replace), and
 * deletes remove. Its read-backs reflect the current store, so a strategy's
 * insert / replace / skip / orphan-delete routing is observable end to end.
 *
 * Seed the store with [seed] to model "these hrefs already exist on the device"
 * before a run; assert on [insertCalls] / [replaceCalls] / [deleteCalls] (each
 * captured in invocation order) plus the resulting [hrefsFor] state.
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

    /** Every (sourceId) [clearPhotoPending] was called for, in call order (per account). */
    val clearPhotoPendingCalls = mutableListOf<Pair<String, String>>()

    /** When set, the matching verb returns this failure instead of mutating. */
    var insertResult: Result<Unit> = Result.success(Unit)
    var replaceResult: Result<Unit> = Result.success(Unit)
    var deleteResult: Result<Unit> = Result.success(Unit)
    var writePhotoResult: Result<Unit> = Result.success(Unit)

    /**
     * When set, [clearPhotoPending] throws this instead of returning — models a
     * collaborator violating its Result envelope, so a caller's never-throws
     * contract (e.g. the photo fetcher's outer guard) can be exercised.
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

    /** Current hrefs stored for [accountName] (post-run device state). */
    fun hrefsFor(accountName: String): Set<String> = store[accountName]?.keys?.toSet() ?: emptySet()

    /** Current stored etag for one href, or null if absent/etag-less. */
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
        contacts.forEach { m[it.href] = it.etag } // in-place update: refresh the etag, href retained
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
     * A cross-collaborator invocation log for ordering assertions. [purgeAccount]
     * and [countRawContacts] append to it; a test can wire the (mocked)
     * account-registrar's `removeAccount` to append here too, then assert the
     * scoped purge ran BEFORE the account removal (the delete-our-rows-first
     * invariant that must not depend on the OS cascade).
     */
    val operationLog = mutableListOf<String>()

    /** When failure, [purgeAccount] records the call and returns it WITHOUT clearing
     *  the store — models revoked WRITE_CONTACTS (the delete never runs). */
    var purgeResult: Result<Unit> = Result.success(Unit)

    /**
     * When non-null, [countRawContacts] returns this instead of the real store
     * size — models a read the provider couldn't answer. Distinct from a real 0:
     * lets a test assert the caller treats "can't verify" differently from
     * "verified empty".
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
     * The device-side pending set a run should see, seeded by the test (default
     * empty). [markContactUploaded] / [hardDeleteTombstone] / [restoreTombstone]
     * prune the matching entry so a subsequent read reflects the write, mirroring
     * the real provider clearing DIRTY / hard-deleting the tombstone.
     */
    var pendingChanges = LocalContactChanges(edited = emptyList(), deleted = emptyList())

    /** Every [markContactUploaded] call as (accountName, href, newEtag), in order. */
    val markUploadedCalls = mutableListOf<Triple<String, String, String>>()

    /** One [assignContactUid] call: the pre-create UID persistence keyed by provider _ID. */
    data class AssignUid(val accountName: String, val localId: Long, val uid: String)

    /** Every [assignContactUid] call, in order. */
    val assignUidCalls = mutableListOf<AssignUid>()

    /** When failure, [assignContactUid] records the call and returns it without persisting. */
    var assignUidResult: Result<Unit> = Result.success(Unit)

    /** One [markNewContactUploaded] call: the net-new write-back keyed by provider _ID. */
    data class MarkNewUploaded(val accountName: String, val localId: Long, val href: String, val newEtag: String)

    /** Every [markNewContactUploaded] call, in order. */
    val markNewUploadedCalls = mutableListOf<MarkNewUploaded>()

    /** When failure, [markNewContactUploaded] records the call and returns it unmutated. */
    var markNewUploadedResult: Result<Unit> = Result.success(Unit)

    /** Every [hardDeleteTombstone] call as (accountName, href), in order. */
    val hardDeleteCalls = mutableListOf<Pair<String, String>>()

    /** Every [restoreTombstone] call as (accountName, href), in order. */
    val restoreCalls = mutableListOf<Pair<String, String>>()

    /** When failure, the matching write-back verb records the call and returns it unmutated. */
    var markUploadedResult: Result<Unit> = Result.success(Unit)
    var hardDeleteResult: Result<Unit> = Result.success(Unit)
    var restoreResult: Result<Unit> = Result.success(Unit)

    override suspend fun pendingLocalChanges(accountName: String): LocalContactChanges = pendingChanges

    override suspend fun markContactUploaded(
        accountName: String,
        href: String,
        newEtag: String,
    ): Result<Unit> {
        markUploadedCalls += Triple(accountName, href, newEtag)
        if (markUploadedResult.isFailure) return markUploadedResult
        store.getOrPut(accountName) { HashMap() }[href] = newEtag
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
        // Persist the synthesized UID onto the matching pending edit (its SYNC1), KEEPING it
        // in the pending set (DIRTY still set) — mirroring the real provider. A re-attempt on
        // a later run then reads the SAME UID and re-targets the same resource rather than
        // re-synthesizing a fresh one.
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
        // The originating row is now synced under its freshly-minted server href, and its
        // net-new pending edit (keyed by the provider _ID, since its href was blank) is
        // pruned — mirroring the real provider stamping SOURCE_ID + clearing DIRTY.
        store.getOrPut(accountName) { HashMap() }[href] = newEtag
        pendingChanges = pendingChanges.copy(edited = pendingChanges.edited.filterNot { it.localId == localId })
        return Result.success(Unit)
    }

    /**
     * The number of distinct device contact rows currently modeled for [accountName]:
     * every synced row (in [store]) plus every net-new pending edit not yet synced (a
     * blank-href [LocalContactEdit]). A net-new create that fails to write its server
     * href back leaves BOTH a synced row under the new href AND its still-pending
     * blank-href edit — the post-sync duplicate this count exposes (2 vs the expected 1).
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
