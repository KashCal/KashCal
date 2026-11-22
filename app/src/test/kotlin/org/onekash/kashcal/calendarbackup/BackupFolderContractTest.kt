package org.onekash.kashcal.calendarbackup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * The behaviour every [BackupFolder] keeps, run against [FakeBackupFolder] and against
 * [SafBackupFolder] over an in-memory document provider, so the fake the backup tests rely on
 * can't drift from the real folder.
 */
abstract class BackupFolderContractTest {

    protected abstract val folder: BackupFolder
    protected abstract val tree: String

    /** Adds a folder named [name] inside the tree. */
    protected abstract fun addSubfolder(name: String)

    private fun names() = runBlocking { folder.list(tree).map { it.name } }
    private fun read(uri: String) = runBlocking { folder.readLines(uri) { it.toList() } }

    @Test
    fun `a created file is listed and empty`() = runBlocking {
        val file = folder.create(tree, "Work.ics")
        assertEquals("Work.ics", file.name)
        assertEquals(listOf("Work.ics"), names())
        assertEquals(emptyList<String>(), read(file.uri))
    }

    @Test
    fun `written text reads back line by line`() = runBlocking {
        val file = folder.create(tree, "Work.ics")
        folder.write(file.uri, "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n")
        assertEquals(listOf("BEGIN:VCALENDAR", "END:VCALENDAR"), read(file.uri))
    }

    @Test
    fun `a shorter write leaves no old tail`() = runBlocking {
        val file = folder.create(tree, "Work.ics")
        folder.write(file.uri, "one\ntwo\nthree\n")
        folder.write(file.uri, "x\n")
        assertEquals(listOf("x"), read(file.uri))
    }

    @Test
    fun `copyInto replaces the target with the source bytes`() = runBlocking {
        val source = folder.create(tree, "Work.ics")
        folder.write(source.uri, "old\ncontent\n")
        val target = folder.create(tree, "Work_20261025T031005Z.ics")
        folder.write(target.uri, "much longer text that must go\nand more\n")
        folder.copyInto(source.uri, target.uri)
        assertEquals(listOf("old", "content"), read(target.uri))
    }

    @Test
    fun `creating a name already used fails and leaves one file`() = runBlocking {
        folder.create(tree, "Work.ics")
        try {
            folder.create(tree, "Work.ics")
            fail("expected NAME_TAKEN")
        } catch (e: BackupFolderException) {
            assertEquals(FolderFailure.NAME_TAKEN, e.failure)
        }
        assertEquals(listOf("Work.ics"), names())
    }

    @Test
    fun `delete removes the file`() = runBlocking {
        val file = folder.create(tree, "Work.ics")
        assertTrue(folder.delete(file.uri))
        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun `folders inside the tree are not listed`() = runBlocking {
        addSubfolder("Old")
        folder.create(tree, "Work.ics")
        assertEquals(listOf("Work.ics"), names())
    }

    @Test
    fun `describe gives the folder name and that files can be created`() = runBlocking {
        val info = folder.describe(tree)
        assertEquals("KashCal backups", info.displayName)
        assertTrue(info.canCreate)
    }

    @Test
    fun `releasing access drops the write grant`() = runBlocking {
        folder.takeAccess(tree)
        assertTrue(folder.hasWriteAccess(tree))
        folder.releaseAccess(tree)
        assertFalse(folder.hasWriteAccess(tree))
    }
}

class FakeBackupFolderContractTest : BackupFolderContractTest() {
    private val fake = FakeBackupFolder()
    override val folder: BackupFolder = fake
    override val tree = FakeBackupFolder.TREE
    override fun addSubfolder(name: String) {
        fake.subfolders += name
    }
}

class SafBackupFolderContractTest : BackupFolderContractTest() {
    private val provider = InMemoryDocumentProvider()
    override val folder: BackupFolder = provider.folder()
    override val tree = InMemoryDocumentProvider.TREE
    override fun addSubfolder(name: String) = provider.addDirectory(name)
}

/**
 * A document provider in memory, wired into [SafBackupFolder] through its provider functions.
 * It behaves like the platform's on-device provider: a taken name gets " (1)" before the
 * extension, and writes truncate unless [ignoreTruncation] is set.
 */
class InMemoryDocumentProvider {

    class Doc(var name: String, var bytes: ByteArray, val isDirectory: Boolean, var lastModified: Long)

    val docs = linkedMapOf<String, Doc>()
    var grant = false
    var ignoreTruncation = false
    var treeFlags = FLAG_DIR_SUPPORTS_CREATE
    var fileFlags = FLAG_SUPPORTS_DELETE or FLAG_SUPPORTS_WRITE
    var clock = 1_000L
    private var nextId = 1

    fun addDirectory(name: String) {
        docs["$TREE/document/${nextId++}"] = Doc(name, ByteArray(0), isDirectory = true, clock)
    }

    private fun freeName(name: String): String {
        if (docs.values.none { it.name == name }) return name
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var n = 1
        while (docs.values.any { it.name == "$base ($n).$ext" }) n++
        return "$base ($n).$ext"
    }

    private fun row(uri: String, doc: Doc) = SafBackupFolder.DocumentRow(
        uri = uri,
        name = doc.name,
        lastModified = doc.lastModified,
        size = doc.bytes.size.toLong(),
        flags = if (doc.isDirectory) 0 else fileFlags,
        isDirectory = doc.isDirectory,
    )

    fun folder(): SafBackupFolder = SafBackupFolder(
        io = Dispatchers.Unconfined,
        hasWriteGrant = { it == TREE && grant },
        queryTree = { tree ->
            if (tree == TREE) SafBackupFolder.DocumentRow(TREE, "KashCal backups", null, null, treeFlags, true) else null
        },
        queryChildren = { tree -> if (tree == TREE) docs.map { (uri, doc) -> row(uri, doc) } else null },
        queryDocument = { uri -> docs[uri]?.let { row(uri, it) } },
        openInput = { uri -> docs[uri]?.let { ByteArrayInputStream(it.bytes) } },
        openWrite = { uri ->
            val doc = docs[uri]
            doc?.let {
                object : OutputStream() {
                    val buffer = ByteArrayOutputStream()
                    override fun write(b: Int) = buffer.write(b)
                    override fun write(b: ByteArray, off: Int, len: Int) = buffer.write(b, off, len)
                    override fun close() {
                        val new = buffer.toByteArray()
                        doc.bytes = if (ignoreTruncation && doc.bytes.size > new.size) {
                            new + doc.bytes.copyOfRange(new.size, doc.bytes.size)
                        } else {
                            new
                        }
                        doc.lastModified = clock
                    }
                }
            }
        },
        createDocument = { tree, _, name ->
            if (tree != TREE) null else {
                val uri = "$TREE/document/${nextId++}"
                docs[uri] = Doc(freeName(name), ByteArray(0), isDirectory = false, clock)
                uri
            }
        },
        deleteDocument = { uri -> docs.remove(uri) != null },
        takePersistable = { if (it == TREE) grant = true },
        releasePersistable = { if (it == TREE) grant = false },
    )

    companion object {
        const val TREE = "content://test.documents/tree/backups"
        // DocumentsContract.Document flag values.
        const val FLAG_SUPPORTS_WRITE = 0x2
        const val FLAG_SUPPORTS_DELETE = 0x4
        const val FLAG_DIR_SUPPORTS_CREATE = 0x8
    }
}
