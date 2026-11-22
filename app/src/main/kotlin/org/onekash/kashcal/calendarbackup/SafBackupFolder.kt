package org.onekash.kashcal.calendarbackup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SyncFailedException

/**
 * Implements [BackupFolder] on the Storage Access Framework. Each provider call is a function,
 * so tests can script any provider behaviour; [forResolver] wires the real ones.
 *
 * Writes open the document in mode "rwt" through [openWrite], never `openOutputStream` without a
 * mode, whose default "w" is not guaranteed to truncate (ContentResolver.openFileDescriptor), and
 * a cloud storage provider has refused "wt". FLAG_SUPPORTS_WRITE is not checked: some cloud
 * providers leave it unset on files they write. The caller reads a file back to catch a provider
 * that keeps the old tail.
 */
class SafBackupFolder(
    private val io: CoroutineDispatcher,
    private val hasWriteGrant: (tree: String) -> Boolean,
    /** The tree's own document row, or null when the provider returned nothing. */
    private val queryTree: (tree: String) -> DocumentRow?,
    /** The rows directly inside the tree, or null when the provider returned nothing. */
    private val queryChildren: (tree: String) -> List<DocumentRow>?,
    private val queryDocument: (uri: String) -> DocumentRow?,
    private val openInput: (uri: String) -> InputStream?,
    /** Opens the document in mode "rwt"; closing the stream must report every error. */
    private val openWrite: (uri: String) -> OutputStream?,
    /** Creates a document and returns its URI, or null when the provider made none. */
    private val createDocument: (tree: String, mimeType: String, name: String) -> String?,
    private val deleteDocument: (uri: String) -> Boolean,
    private val takePersistable: (tree: String) -> Unit,
    private val releasePersistable: (tree: String) -> Unit,
) : BackupFolder {

    /** One document as the provider describes it. */
    data class DocumentRow(
        val uri: String,
        val name: String?,
        val lastModified: Long?,
        val size: Long?,
        val flags: Int,
        val isDirectory: Boolean,
    )

    override suspend fun hasWriteAccess(tree: String): Boolean = call { hasWriteGrant(tree) }

    override suspend fun describe(tree: String): FolderInfo = call {
        val row = queryTree(tree) ?: throw BackupFolderException(FolderFailure.MISSING)
        FolderInfo(row.name, row.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0)
    }

    override suspend fun list(tree: String): List<FolderFile> = call {
        val rows = queryChildren(tree) ?: throw BackupFolderException(FolderFailure.MISSING)
        rows.filter { !it.isDirectory && it.name != null }.map { it.toFolderFile(it.name!!) }
    }

    override suspend fun <T> readLines(file: String, block: (Sequence<String>) -> T): T = call {
        val input = openInput(file) ?: throw BackupFolderException(FolderFailure.FAILED)
        input.bufferedReader(Charsets.UTF_8).use { block(it.lineSequence()) }
    }

    override suspend fun create(tree: String, name: String): FolderFile = call {
        val uri = createDocument(tree, MIME_TYPE, name)
            ?: throw BackupFolderException(FolderFailure.FAILED)
        val row = queryDocument(uri)
        if (row?.name != name) {
            // The provider kept an existing file of that name and called this one "name (1)".
            runCatching { deleteDocument(uri) }
            throw BackupFolderException(FolderFailure.NAME_TAKEN)
        }
        row.toFolderFile(name)
    }

    private fun DocumentRow.toFolderFile(name: String) = FolderFile(
        uri = uri,
        name = name,
        lastModified = lastModified?.takeIf { it > 0 },
        sizeBytes = size,
        supportsDelete = flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0,
    )

    override suspend fun write(file: String, text: String) = call {
        val output = openWrite(file) ?: throw BackupFolderException(FolderFailure.FAILED)
        output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    override suspend fun copyInto(source: String, target: String) = call {
        val input = openInput(source) ?: throw BackupFolderException(FolderFailure.FAILED)
        input.use {
            val output = openWrite(target) ?: throw BackupFolderException(FolderFailure.FAILED)
            output.use { out -> input.copyTo(out, COPY_BUFFER_BYTES) }
        }
        Unit
    }

    override suspend fun delete(file: String): Boolean = call { deleteDocument(file) }

    override suspend fun takeAccess(tree: String) = call { takePersistable(tree) }

    override suspend fun releaseAccess(tree: String) {
        try {
            call { releasePersistable(tree) }
        } catch (e: BackupFolderException) {
            // The grant is already gone (folder deleted, provider uninstalled): nothing to release.
        }
    }

    /** Runs [block] on [io] and turns provider exceptions into [BackupFolderException]. */
    private suspend fun <T> call(block: () -> T): T = withContext(io) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupFolderException) {
            throw e
        } catch (e: SecurityException) {
            throw BackupFolderException(FolderFailure.NO_ACCESS, e)
        } catch (e: FileNotFoundException) {
            throw BackupFolderException(FolderFailure.MISSING, e)
        } catch (e: UnsupportedOperationException) {
            throw BackupFolderException(FolderFailure.UNSUPPORTED, e)
        } catch (e: Exception) {
            // A provider can throw anything across the binder (IllegalArgument, IllegalState).
            throw BackupFolderException(FolderFailure.FAILED, e)
        }
    }

    companion object {
        private const val MIME_TYPE = "text/calendar"
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val ACCESS_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        private val ROW_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        /** Wires every provider call to [context]'s ContentResolver. */
        fun forResolver(context: Context, io: CoroutineDispatcher): SafBackupFolder {
            val resolver = context.contentResolver
            return SafBackupFolder(
                io = io,
                hasWriteGrant = { tree ->
                    resolver.persistedUriPermissions.any {
                        it.uri.toString() == tree && it.isWritePermission
                    }
                },
                queryTree = { tree ->
                    val treeUri = tree.toUri()
                    queryRows(resolver, treeRoot(treeUri), treeUri)?.firstOrNull()
                },
                queryChildren = { tree ->
                    val treeUri = tree.toUri()
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                        treeUri, DocumentsContract.getTreeDocumentId(treeUri)
                    )
                    queryRows(resolver, children, treeUri, refuseWhileLoading = true)
                },
                queryDocument = { uri ->
                    val documentUri = uri.toUri()
                    queryRows(resolver, documentUri, documentUri)?.firstOrNull()
                },
                openInput = { resolver.openInputStream(it.toUri()) },
                openWrite = { uri -> resolver.openFileDescriptor(uri.toUri(), "rwt")?.let(::CheckedOutput) },
                createDocument = { tree, mimeType, name ->
                    DocumentsContract.createDocument(resolver, treeRoot(tree.toUri()), mimeType, name)
                        ?.toString()
                },
                deleteDocument = { DocumentsContract.deleteDocument(resolver, it.toUri()) },
                takePersistable = { resolver.takePersistableUriPermission(it.toUri(), ACCESS_FLAGS) },
                releasePersistable = { resolver.releasePersistableUriPermission(it.toUri(), ACCESS_FLAGS) },
            )
        }

        private fun treeRoot(tree: Uri): Uri =
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

        /**
         * Reads document rows from [uri]. A row's own URI is built under [tree] when [tree] is a
         * tree URI, so later calls keep the tree's grant; null when the provider returns no
         * cursor, as a DocumentsProvider does for a document it can't find. With
         * [refuseWhileLoading], a listing the provider marks as still loading
         * (DocumentsContract.EXTRA_LOADING) fails: acting on a partial list would create a
         * second copy of a file it doesn't show yet.
         */
        private fun queryRows(
            resolver: ContentResolver,
            uri: Uri,
            tree: Uri,
            refuseWhileLoading: Boolean = false,
        ): List<DocumentRow>? =
            resolver.query(uri, ROW_COLUMNS, null, null, null)?.use { cursor ->
                if (refuseWhileLoading && cursor.extras?.getBoolean(DocumentsContract.EXTRA_LOADING) == true) {
                    throw BackupFolderException(FolderFailure.FAILED)
                }
                val rows = mutableListOf<DocumentRow>()
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0)
                    val rowUri = if (DocumentsContract.isTreeUri(tree)) {
                        DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
                    } else {
                        uri
                    }
                    rows += DocumentRow(
                        uri = rowUri.toString(),
                        name = cursor.getString(1),
                        lastModified = if (cursor.isNull(2)) null else cursor.getLong(2),
                        size = if (cursor.isNull(3)) null else cursor.getLong(3),
                        flags = if (cursor.isNull(4)) 0 else cursor.getInt(4),
                        isDirectory = cursor.getString(5) == DocumentsContract.Document.MIME_TYPE_DIR,
                    )
                }
                rows
            }
    }
}

/**
 * The write side of an "rwt" descriptor. [close] flushes, syncs where the descriptor is a file
 * (a pipe can't sync), asks the descriptor whether the provider reported an error, and closes it;
 * any of these failing throws.
 */
private class CheckedOutput(private val pfd: ParcelFileDescriptor) : OutputStream() {
    private val out = FileOutputStream(pfd.fileDescriptor)

    override fun write(b: Int) = out.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

    override fun close() {
        try {
            out.flush()
            try {
                out.fd.sync()
            } catch (e: SyncFailedException) {
                // Pipes and sockets can't sync; the provider owns durability for those.
            }
            pfd.checkError()
        } finally {
            pfd.close()
        }
    }
}
