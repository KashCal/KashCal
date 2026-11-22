package org.onekash.kashcal.calendarbackup

/** A file directly inside the backup folder. [uri] is the document's content URI. */
data class FolderFile(
    val uri: String,
    val name: String,
    /** Milliseconds since the epoch, or null when the provider doesn't say. */
    val lastModified: Long?,
    val sizeBytes: Long?,
    val supportsDelete: Boolean,
)

/** The backup folder itself. */
data class FolderInfo(
    val displayName: String?,
    /** Whether the provider lets new files be created in it (FLAG_DIR_SUPPORTS_CREATE). */
    val canCreate: Boolean,
)

/** Why a folder operation failed. */
enum class FolderFailure {
    /** KashCal holds no write grant for the folder, or the provider refused access. */
    NO_ACCESS,

    /** The folder or file is gone: deleted, moved, or on storage that was removed. */
    MISSING,

    /** The provider doesn't support the operation, such as creating or deleting files. */
    UNSUPPORTED,

    /** The provider created the file under another name, as for a name already taken. */
    NAME_TAKEN,

    /** Anything else, which may work on a later try. */
    FAILED,
}

/** Carries a [FolderFailure] out of a [BackupFolder] call. */
class BackupFolderException(
    val failure: FolderFailure,
    cause: Throwable? = null,
) : Exception(failure.name, cause)

/**
 * Reads and writes the user-chosen backup folder, a Storage Access Framework document tree.
 *
 * Every call runs off the main thread and reports a failure only as [BackupFolderException];
 * cancellation passes through. Files are addressed by content URI strings.
 */
interface BackupFolder {

    /** Returns true when KashCal holds a persisted write grant for [tree]. */
    suspend fun hasWriteAccess(tree: String): Boolean

    /** Returns [tree]'s name and whether files can be created in it. */
    suspend fun describe(tree: String): FolderInfo

    /** Returns the files directly inside [tree]; folders inside it are left out. */
    suspend fun list(tree: String): List<FolderFile>

    /** Runs [block] over [file]'s lines, read as UTF-8 one at a time, and closes the file. */
    suspend fun <T> readLines(file: String, block: (Sequence<String>) -> T): T

    /**
     * Creates an empty file named [name] in [tree]. A provider that picked another name (such as
     * `name (1).ics`) gets that file deleted and the call fails with [FolderFailure.NAME_TAKEN].
     */
    suspend fun create(tree: String, name: String): FolderFile

    /** Replaces [file]'s content with [text] in UTF-8. */
    suspend fun write(file: String, text: String)

    /** Replaces [target]'s content with [source]'s bytes, copied in small chunks. */
    suspend fun copyInto(source: String, target: String)

    /** Deletes [file]; returns false when the provider reports it wasn't deleted. */
    suspend fun delete(file: String): Boolean

    /** Keeps read and write access to [tree] across restarts. */
    suspend fun takeAccess(tree: String)

    /** Gives up the access [takeAccess] kept; a grant that is already gone is not an error. */
    suspend fun releaseAccess(tree: String)
}
