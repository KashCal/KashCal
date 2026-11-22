package org.onekash.kashcal.calendarbackup

/**
 * In-memory [BackupFolder] shared by the backup tests. One tree, [TREE], holds files by name;
 * the switches script the provider behaviour the real folder has to survive.
 */
class FakeBackupFolder(
    private val now: () -> Long = { 1_000L },
) : BackupFolder {

    class StoredFile(var bytes: ByteArray, var lastModified: Long?)

    /** Files by name, in creation order. */
    val files = linkedMapOf<String, StoredFile>()

    /** Names of folders inside the tree, which [list] leaves out. */
    val subfolders = mutableSetOf<String>()

    var displayName: String? = "KashCal backups"
    var hasGrant = true
    var canCreate = true
    var deleteSupported = true

    /** The tree is gone: every call fails with [FolderFailure.MISSING]. */
    var missing = false

    /** Names differing only in case are one file, as on shared storage. */
    var caseInsensitive = false

    /** A create of a name already used gets `name (1)` and fails with NAME_TAKEN, as SAF does. */
    var renameTakenNames = true

    /**
     * Writes overwrite from the start without cutting off what follows, like a provider that
     * ignores truncation.
     */
    var ignoreTruncation = false

    /**
     * Makes the named operation fail: "list", "read", "create", "write", "copy", "delete", "take"
     * or "describe".
     */
    val failures = mutableMapOf<String, FolderFailure>()

    /** Makes write fail for one file name only. */
    val failWritesTo = mutableMapOf<String, FolderFailure>()

    val takenGrants = mutableListOf<String>()
    val releasedGrants = mutableListOf<String>()
    val operations = mutableListOf<String>()

    fun put(name: String, text: String, lastModified: Long? = now()) {
        files[name] = StoredFile(text.toByteArray(Charsets.UTF_8), lastModified)
    }

    fun text(name: String): String? = files[name]?.bytes?.toString(Charsets.UTF_8)

    private fun uriOf(name: String) = "$TREE/document/$name"
    private fun nameOf(uri: String) = uri.removePrefix("$TREE/document/")

    private fun check(operation: String) {
        operations += operation
        if (missing) throw BackupFolderException(FolderFailure.MISSING)
        failures[operation]?.let { throw BackupFolderException(it) }
    }

    override suspend fun hasWriteAccess(tree: String): Boolean = hasGrant && tree == TREE

    override suspend fun describe(tree: String): FolderInfo {
        check("describe")
        return FolderInfo(displayName, canCreate)
    }

    override suspend fun list(tree: String): List<FolderFile> {
        check("list")
        return files.map { (name, file) ->
            FolderFile(uriOf(name), name, file.lastModified, file.bytes.size.toLong(), deleteSupported)
        }
    }

    override suspend fun <T> readLines(file: String, block: (Sequence<String>) -> T): T {
        check("read")
        val stored = files[nameOf(file)] ?: throw BackupFolderException(FolderFailure.MISSING)
        return stored.bytes.toString(Charsets.UTF_8).reader().buffered().use { block(it.lineSequence()) }
    }

    override suspend fun create(tree: String, name: String): FolderFile {
        check("create")
        if (!canCreate) throw BackupFolderException(FolderFailure.UNSUPPORTED)
        val taken = if (caseInsensitive) {
            (files.keys + subfolders).any { it.equals(name, ignoreCase = true) }
        } else {
            name in files || name in subfolders
        }
        if (taken) {
            if (renameTakenNames) throw BackupFolderException(FolderFailure.NAME_TAKEN)
            throw BackupFolderException(FolderFailure.FAILED)
        }
        files[name] = StoredFile(ByteArray(0), now())
        return FolderFile(uriOf(name), name, now(), 0, deleteSupported)
    }

    override suspend fun write(file: String, text: String) {
        check("write")
        val name = nameOf(file)
        failWritesTo[name]?.let { throw BackupFolderException(it) }
        replace(name, text.toByteArray(Charsets.UTF_8))
    }

    override suspend fun copyInto(source: String, target: String) {
        check("copy")
        val bytes = files[nameOf(source)]?.bytes ?: throw BackupFolderException(FolderFailure.MISSING)
        replace(nameOf(target), bytes.copyOf())
    }

    private fun replace(name: String, bytes: ByteArray) {
        val stored = files[name] ?: throw BackupFolderException(FolderFailure.MISSING)
        stored.bytes = if (ignoreTruncation && stored.bytes.size > bytes.size) {
            bytes + stored.bytes.copyOfRange(bytes.size, stored.bytes.size)
        } else {
            bytes
        }
        stored.lastModified = now()
    }

    override suspend fun delete(file: String): Boolean {
        check("delete")
        if (!deleteSupported) throw BackupFolderException(FolderFailure.UNSUPPORTED)
        return files.remove(nameOf(file)) != null
    }

    override suspend fun takeAccess(tree: String) {
        check("take")
        takenGrants += tree
        hasGrant = true
    }

    override suspend fun releaseAccess(tree: String) {
        releasedGrants += tree
        if (tree == TREE) hasGrant = false
    }

    companion object {
        const val TREE = "content://test.documents/tree/backups"
    }
}
