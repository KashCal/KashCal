package org.onekash.kashcal.calendarbackup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * Tests how [SafBackupFolder] reads provider answers: which exceptions become which
 * [FolderFailure], the flags it checks, a provider rename on create, the streaming copy, and the
 * "rwt" mode its real wiring opens files with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SafBackupFolderTest {

    private val tree = InMemoryDocumentProvider.TREE

    private fun failureOf(block: suspend () -> Unit): FolderFailure? = runBlocking {
        try {
            block()
            null
        } catch (e: BackupFolderException) {
            e.failure
        }
    }

    private fun throwing(e: Exception) = SafBackupFolder(
        io = Dispatchers.Unconfined,
        hasWriteGrant = { throw e },
        queryTree = { throw e },
        queryChildren = { throw e },
        queryDocument = { throw e },
        openInput = { throw e },
        openWrite = { throw e },
        createDocument = { _, _, _ -> throw e },
        deleteDocument = { throw e },
        takePersistable = { throw e },
        releasePersistable = { throw e },
    )

    @Test
    fun `provider exceptions map to one failure kind`() {
        val cases = mapOf(
            SecurityException("revoked") to FolderFailure.NO_ACCESS,
            FileNotFoundException("gone") to FolderFailure.MISSING,
            UnsupportedOperationException("no") to FolderFailure.UNSUPPORTED,
            IllegalArgumentException("bad uri") to FolderFailure.FAILED,
            IllegalStateException("binder") to FolderFailure.FAILED,
            java.io.IOException("io") to FolderFailure.FAILED,
        )
        cases.forEach { (exception, expected) ->
            val folder = throwing(exception)
            assertEquals("list $exception", expected, failureOf { folder.list(tree) })
            assertEquals("write $exception", expected, failureOf { folder.write("$tree/document/1", "x") })
            assertEquals("read $exception", expected, failureOf { folder.readLines("$tree/document/1") { it.count() } })
            assertEquals("take $exception", expected, failureOf { folder.takeAccess(tree) })
        }
    }

    @Test
    fun `cancellation is not turned into a failure`() {
        val folder = throwing(CancellationException("stop"))
        try {
            runBlocking { folder.list(tree) }
            fail("expected cancellation")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }

    @Test
    fun `a tree the provider no longer answers for is missing`() {
        val folder = InMemoryDocumentProvider().folder()
        assertEquals(FolderFailure.MISSING, failureOf { folder.describe("content://other/tree/x") })
        assertEquals(FolderFailure.MISSING, failureOf { folder.list("content://other/tree/x") })
    }

    @Test
    fun `a provider rename on create deletes the new file and fails`() = runBlocking {
        val provider = InMemoryDocumentProvider()
        val folder = provider.folder()
        folder.create(tree, "Work.ics")
        assertEquals(FolderFailure.NAME_TAKEN, failureOf { folder.create(tree, "Work.ics") })
        assertEquals(listOf("Work.ics"), provider.docs.values.map { it.name })
    }

    @Test
    fun `flags decide create and delete support`() = runBlocking {
        val provider = InMemoryDocumentProvider()
        val folder = provider.folder()
        folder.create(tree, "Work.ics")
        assertTrue(folder.describe(tree).canCreate)
        assertTrue(folder.list(tree).single().supportsDelete)

        provider.treeFlags = 0
        provider.fileFlags = 0
        assertFalse(folder.describe(tree).canCreate)
        assertFalse(folder.list(tree).single().supportsDelete)
    }

    @Test
    fun `releasing a grant that is already gone is not an error`() {
        val folder = SafBackupFolder(
            io = Dispatchers.Unconfined,
            hasWriteGrant = { false },
            queryTree = { null },
            queryChildren = { null },
            queryDocument = { null },
            openInput = { null },
            openWrite = { null },
            createDocument = { _, _, _ -> null },
            deleteDocument = { false },
            takePersistable = {},
            releasePersistable = { throw SecurityException("no grant") },
        )
        assertEquals(null, failureOf { folder.releaseAccess(tree) })
    }

    @Test
    fun `a provider that opens nothing fails the call`() {
        val folder = SafBackupFolder(
            io = Dispatchers.Unconfined,
            hasWriteGrant = { true },
            queryTree = { null },
            queryChildren = { emptyList() },
            queryDocument = { null },
            openInput = { null },
            openWrite = { null },
            createDocument = { _, _, _ -> null },
            deleteDocument = { false },
            takePersistable = {},
            releasePersistable = {},
        )
        assertEquals(FolderFailure.FAILED, failureOf { folder.write("$tree/document/1", "x") })
        assertEquals(FolderFailure.FAILED, failureOf { folder.readLines("$tree/document/1") { it.count() } })
        assertEquals(FolderFailure.FAILED, failureOf { folder.create(tree, "Work.ics") })
    }

    @Test
    fun `copyInto streams in small chunks`() = runBlocking {
        val size = 32L * 1024 * 1024
        var largestRead = 0
        var written = 0L
        val source = object : InputStream() {
            var left = size
            override fun read(): Int = if (left-- > 0) 'a'.code else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                largestRead = maxOf(largestRead, len)
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt()
                left -= n
                return n
            }
        }
        val target = object : OutputStream() {
            override fun write(b: Int) {
                written++
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                written += len
            }
        }
        val folder = SafBackupFolder(
            io = Dispatchers.Unconfined,
            hasWriteGrant = { true },
            queryTree = { null },
            queryChildren = { null },
            queryDocument = { null },
            openInput = { source },
            openWrite = { target },
            createDocument = { _, _, _ -> null },
            deleteDocument = { false },
            takePersistable = {},
            releasePersistable = {},
        )
        folder.copyInto("$tree/document/1", "$tree/document/2")
        assertEquals(size, written)
        assertTrue("read $largestRead bytes at once", largestRead in 1..(64 * 1024))
    }

    @Test
    fun `the real wiring opens files for writing in mode rwt`() {
        val modes = mutableListOf<String>()
        val mode = slot<String>()
        val resolver = mockk<ContentResolver>()
        every { resolver.openFileDescriptor(any<Uri>(), capture(mode)) } answers {
            modes += mode.captured
            null
        }
        every { resolver.openInputStream(any()) } answers { "old".byteInputStream() }
        val context = mockk<Context>()
        every { context.contentResolver } returns resolver
        val folder = SafBackupFolder.forResolver(context, Dispatchers.Unconfined)
        assertEquals(FolderFailure.FAILED, failureOf { folder.write("content://p/tree/t/document/d", "x") })
        assertEquals(FolderFailure.FAILED, failureOf {
            folder.copyInto("content://p/tree/t/document/a", "content://p/tree/t/document/b")
        })
        assertEquals(listOf("rwt", "rwt"), modes)
    }
}
