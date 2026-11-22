package org.onekash.kashcal.sync.contacts

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.contacts.VCardContactMapper
import org.onekash.kashcal.sync.adapter.KashCalContactsAuthenticator
import org.onekash.vcard.model.Contact
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves in SQL what an upload acknowledgement leaves on the row: the real
 * [AndroidContactsProviderRepository] writes through [SqliteContactsProvider], which applies the
 * platform's VERSION and DIRTY rules, so these outcomes hold on a phone, not only in the shape
 * of the batch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ContactUploadAcknowledgementSqlTest {

    private lateinit var provider: SqliteContactsProvider
    private lateinit var repo: AndroidContactsProviderRepository
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        provider = SqliteContactsProvider.install()
        repo = SqliteContactsProvider.repository(context)
    }

    @After
    fun tearDown() = provider.db.close()

    private fun seedDirty(href: String = HREF, etag: String? = "\"e1\"", version: Long = 7L, dirty: Int = 1): Long {
        val id = provider.insertRow(
            SqliteContactsProvider.RAW,
            RawContacts.ACCOUNT_NAME to ACCOUNT,
            RawContacts.ACCOUNT_TYPE to TYPE,
            RawContacts.SOURCE_ID to href,
            RawContacts.SYNC1 to "uid-1",
            RawContacts.SYNC2 to etag,
            RawContacts.DIRTY to dirty,
            RawContacts.VERSION to version,
        )
        provider.insertRow(
            SqliteContactsProvider.DATA,
            Data.RAW_CONTACT_ID to id,
            Data.MIMETYPE to StructuredName.CONTENT_ITEM_TYPE,
            StructuredName.DISPLAY_NAME to "Jane Doe",
        )
        return id
    }

    private fun raw(id: Long) = provider.rawContact(id)!!

    /** A user edit in the Contacts app: an app (non-sync-adapter) Data write. */
    private fun userEditsNote(rawId: Long, text: String) {
        context.contentResolver.insert(
            Data.CONTENT_URI,
            ContentValues().apply {
                put(Data.RAW_CONTACT_ID, rawId)
                put(Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
                put(Note.NOTE, text)
            },
        )
    }

    @Test
    fun `an unchanged row is marked synced with the new etag`() = runBlocking {
        val id = seedDirty()
        val read = repo.pendingLocalChanges(ACCOUNT).edited.single()

        assertTrue(repo.markContactUploaded(ACCOUNT, HREF, "\"e2\"", read.rawContactVersion).isSuccess)

        assertEquals("0", raw(id)[RawContacts.DIRTY])
        assertEquals("\"e2\"", raw(id)[RawContacts.SYNC2])
        assertTrue(repo.pendingLocalChanges(ACCOUNT).edited.isEmpty())
    }

    @Test
    fun `an edit made after the scan stays pending with the acknowledged etag`() = runBlocking {
        val id = seedDirty()
        val read = repo.pendingLocalChanges(ACCOUNT).edited.single()
        userEditsNote(id, "second")

        assertTrue(repo.markContactUploaded(ACCOUNT, HREF, "\"e2\"", read.rawContactVersion).isSuccess)

        assertEquals("the newer edit keeps DIRTY", "1", raw(id)[RawContacts.DIRTY])
        assertEquals("\"e2\"", raw(id)[RawContacts.SYNC2])
        val pending = repo.pendingLocalChanges(ACCOUNT).edited.single()
        assertEquals(listOf("second"), pending.contact.notes)
        assertEquals("\"e2\"", pending.storedEtag)
    }

    @Test
    fun `a redirected upload moves SOURCE_ID`() = runBlocking {
        val id = seedDirty()
        val read = repo.pendingLocalChanges(ACCOUNT).edited.single()

        repo.markContactUploaded(ACCOUNT, HREF, "\"e2\"", read.rawContactVersion, newHref = "/ab/moved.vcf")

        assertEquals("/ab/moved.vcf", raw(id)[RawContacts.SOURCE_ID])
        assertEquals("0", raw(id)[RawContacts.DIRTY])
    }

    @Test
    fun `a create is stamped by _ID, and persisting its UID first leaves VERSION alone`() = runBlocking {
        val id = seedDirty(href = "", etag = null)
        val read = repo.pendingLocalChanges(ACCOUNT).edited.single()
        assertTrue(repo.assignContactUid(ACCOUNT, id, "generated-uid").isSuccess)
        assertEquals("7", raw(id)[RawContacts.VERSION])

        assertTrue(repo.markNewContactUploaded(ACCOUNT, id, "/ab/generated-uid.vcf", "\"c1\"", read.rawContactVersion).isSuccess)

        assertEquals("/ab/generated-uid.vcf", raw(id)[RawContacts.SOURCE_ID])
        assertEquals("\"c1\"", raw(id)[RawContacts.SYNC2])
        assertEquals("0", raw(id)[RawContacts.DIRTY])
    }

    @Test
    fun `the scan returns the VERSION it read`() = runBlocking {
        seedDirty(version = 41L)
        assertEquals(41L, repo.pendingLocalChanges(ACCOUNT).edited.single().rawContactVersion)
    }

    @Test
    fun `a blank stored etag reads back as no etag`() = runBlocking {
        seedDirty(etag = "")
        assertNull(repo.existingEtagsByHref(ACCOUNT)[HREF])
        assertNull(repo.pendingLocalChanges(ACCOUNT).edited.single().storedEtag)
    }

    @Test
    fun `a replace over a row with a newer edit overwrites its Data and etag and leaves DIRTY set`() = runBlocking {
        val id = seedDirty()
        userEditsNote(id, "phone edit")

        assertTrue(repo.replaceContacts(ACCOUNT, listOf(serverWrite(HREF, "\"srv\"", note = "server"))).isSuccess)

        assertEquals("1", raw(id)[RawContacts.DIRTY])
        assertEquals("\"srv\"", raw(id)[RawContacts.SYNC2])
        assertEquals(listOf("server"), repo.pendingLocalChanges(ACCOUNT).edited.single().contact.notes)
    }

    @Test
    fun `a differing adopt, stamp with no etag then replace, ends synced holding the server copy`() = runBlocking {
        val id = seedDirty(href = "", etag = null)
        userEditsNote(id, "phone")
        val read = repo.pendingLocalChanges(ACCOUNT).edited.single()

        assertTrue(repo.markNewContactUploaded(ACCOUNT, id, HREF, "", read.rawContactVersion).isSuccess)
        assertTrue(repo.replaceContacts(ACCOUNT, listOf(serverWrite(HREF, "\"srv\"", note = "server"))).isSuccess)

        assertEquals(HREF, raw(id)[RawContacts.SOURCE_ID])
        assertEquals("\"srv\"", raw(id)[RawContacts.SYNC2])
        assertEquals("0", raw(id)[RawContacts.DIRTY])
        val notes = provider.rows(SqliteContactsProvider.DATA, "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?", id.toString(), Note.CONTENT_ITEM_TYPE)
            .map { it[Note.NOTE] }
        assertEquals(listOf("server"), notes)
        assertTrue(repo.pendingLocalChanges(ACCOUNT).edited.isEmpty())
    }

    /**
     * Pins the acknowledgement's order-independent form: the etag is stamped whatever the row's
     * DIRTY, and only DIRTY depends on VERSION. No real path reaches a clean row whose VERSION
     * moved (only the sequential sync worker writes these rows), so this documents the
     * semantics; it is not a bug reproduction.
     */
    @Test
    fun `the etag is stamped even on a clean row whose VERSION moved`() = runBlocking {
        val id = seedDirty(dirty = 0, version = 9L)

        repo.markContactUploaded(ACCOUNT, HREF, "\"e3\"", uploadedVersion = 7L)

        assertEquals("\"e3\"", raw(id)[RawContacts.SYNC2])
        assertEquals("0", raw(id)[RawContacts.DIRTY])
    }

    private fun serverWrite(href: String, etag: String, note: String): MappedContactWrite = MappedContactWrite(
        href = href,
        etag = etag,
        mapped = VCardContactMapper.toEntity(
            Contact(
                version = "3.0",
                uid = "uid-1",
                structuredName = org.onekash.vcard.model.StructuredName(family = "Doe", given = "Jane"),
                displayName = "Jane Doe",
                notes = listOf(note),
                rawVCard = "",
            ),
        ),
        isReadOnly = false,
    )

    private companion object {
        const val ACCOUNT = "user@example.test"
        const val TYPE = KashCalContactsAuthenticator.ACCOUNT_TYPE
        const val HREF = "/ab/jane.vcf"
    }
}
