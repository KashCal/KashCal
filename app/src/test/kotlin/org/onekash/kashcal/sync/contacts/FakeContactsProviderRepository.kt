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
}
