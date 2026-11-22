package org.onekash.kashcal.sync.contacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.adapter.KashCalContactsAuthenticator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins each platform rule [SqliteContactsProvider] models, citing the AOSP ContactsProvider
 * source it follows (main and android12-release agree). Rows are seeded with
 * [SqliteContactsProvider.insertRow], which runs no rule, and changed through the resolver.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SqliteContactsProviderTest {

    private lateinit var provider: SqliteContactsProvider
    private lateinit var resolver: ContentResolver
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        provider = SqliteContactsProvider.install()
        resolver = context.contentResolver
    }

    @After
    fun tearDown() = provider.db.close()

    // ---- helpers ----

    private fun seedRaw(account: String? = ACCOUNT, dirty: Int = 0, readOnly: Int = 0, version: Long = 5L): Long =
        provider.insertRow(
            SqliteContactsProvider.RAW,
            RawContacts.ACCOUNT_NAME to account,
            RawContacts.ACCOUNT_TYPE to account?.let { TYPE },
            RawContacts.SOURCE_ID to "/a.vcf",
            RawContacts.DIRTY to dirty,
            RawContacts.RAW_CONTACT_IS_READ_ONLY to readOnly,
            RawContacts.VERSION to version,
        )

    private fun seedNote(rawId: Long, text: String = "note"): Long =
        provider.insertRow(
            SqliteContactsProvider.DATA,
            Data.RAW_CONTACT_ID to rawId,
            Data.MIMETYPE to Note.CONTENT_ITEM_TYPE,
            Note.NOTE to text,
        )

    private fun syncAdapter(uri: Uri, account: String = ACCOUNT): Uri = uri.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, account)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, TYPE)
        .build()

    private fun raw(id: Long) = provider.rawContact(id)!!
    private fun version(id: Long) = raw(id)[RawContacts.VERSION]!!.toLong()
    private fun dirty(id: Long) = raw(id)[RawContacts.DIRTY]!!.toInt()

    private fun noteValues(rawId: Long, text: String) = ContentValues().apply {
        put(Data.RAW_CONTACT_ID, rawId)
        put(Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
        put(Note.NOTE, text)
    }

    // ---- VERSION ----

    /**
     * ContactsProvider2.insertData calls TransactionContext.rawContactUpdated for every caller;
     * flushTransactionalChanges applies UPDATE_RAW_CONTACT_SET_VERSION_SQL once per raw contact
     * at the next flush (here, the end of the batch).
     */
    @Test
    fun `Data inserts bump VERSION once per raw contact between flushes, for a sync adapter too`() {
        val id = seedRaw()
        resolver.applyBatch(
            ContactsContract.AUTHORITY,
            arrayListOf(
                ContentProviderOperation.newInsert(syncAdapter(Data.CONTENT_URI)).withValues(noteValues(id, "a")).build(),
                ContentProviderOperation.newInsert(syncAdapter(Data.CONTENT_URI)).withValues(noteValues(id, "b")).build(),
            ),
        )
        assertEquals(6L, version(id))
    }

    /**
     * ContactsProvider2.updateInTransaction and deleteInTransaction call flushTransactionalChanges
     * first, so an update later in a batch sees an earlier Data insert's VERSION bump, and inserts
     * on either side of it count twice.
     */
    @Test
    fun `an update in a batch flushes earlier Data inserts first`() {
        val id = seedRaw(version = 5L)
        val results = resolver.applyBatch(
            ContactsContract.AUTHORITY,
            arrayListOf(
                ContentProviderOperation.newInsert(syncAdapter(Data.CONTENT_URI)).withValues(noteValues(id, "a")).build(),
                ContentProviderOperation.newUpdate(ContentUris.withAppendedId(syncAdapter(RawContacts.CONTENT_URI), id))
                    .withSelection("${RawContacts.VERSION} = ?", arrayOf("5"))
                    .withValue(RawContacts.SYNC2, "stale").build(),
                ContentProviderOperation.newInsert(syncAdapter(Data.CONTENT_URI)).withValues(noteValues(id, "b")).build(),
            ),
        )
        assertEquals("the guarded update saw VERSION 6, not 5", 0, results[1].count)
        assertNull(raw(id)[RawContacts.SYNC2])
        assertEquals(7L, version(id))
    }

    /** ContactsDatabaseHelper trigger data_updated: one VERSION bump per Data row updated. */
    @Test
    fun `a Data update bumps VERSION`() {
        val id = seedRaw()
        val dataId = seedNote(id)
        resolver.update(
            ContentUris.withAppendedId(syncAdapter(Data.CONTENT_URI), dataId),
            ContentValues().apply { put(Note.NOTE, "changed") },
            null,
            null,
        )
        assertEquals(6L, version(id))
    }

    /** ContactsDatabaseHelper trigger data_deleted: one VERSION bump per Data row deleted. */
    @Test
    fun `a Data delete bumps VERSION`() {
        val id = seedRaw()
        seedNote(id)
        resolver.delete(syncAdapter(Data.CONTENT_URI), "${Data.RAW_CONTACT_ID} = ?", arrayOf(id.toString()))
        assertEquals(6L, version(id))
    }

    /**
     * ContactsDatabaseHelper trigger raw_contacts_marked_deleted: VERSION + 1 when DELETED changes.
     */
    @Test
    fun `a DELETED change bumps VERSION`() {
        val id = seedRaw()
        resolver.update(
            ContentUris.withAppendedId(syncAdapter(RawContacts.CONTENT_URI), id),
            ContentValues().apply { put(RawContacts.DELETED, 1) },
            null,
            null,
        )
        assertEquals(6L, version(id))
    }

    /**
     * ContactsProvider2.updateRawContact never calls rawContactUpdated; only the DELETED trigger
     * bumps.
     */
    @Test
    fun `writing SYNC columns, SOURCE_ID or DIRTY leaves VERSION alone`() {
        val id = seedRaw(dirty = 1)
        resolver.update(
            ContentUris.withAppendedId(syncAdapter(RawContacts.CONTENT_URI), id),
            ContentValues().apply {
                put(RawContacts.SYNC1, "uid")
                put(RawContacts.SYNC2, "etag")
                put(RawContacts.SYNC3, "hash")
                put(RawContacts.SYNC4, "1")
                put(RawContacts.SOURCE_ID, "/b.vcf")
                put(RawContacts.DIRTY, 0)
            },
            null,
            null,
        )
        assertEquals(5L, version(id))
        assertEquals(0, dirty(id))
    }

    // ---- DIRTY ----

    /** TransactionContext.markRawContactDirtyAndChanged adds to the dirty set only for an app. */
    @Test
    fun `an app Data write marks the raw contact DIRTY and a sync adapter's does not`() {
        val app = seedRaw()
        val sync = seedRaw()
        resolver.insert(Data.CONTENT_URI, noteValues(app, "app"))
        resolver.insert(syncAdapter(Data.CONTENT_URI), noteValues(sync, "sync"))
        assertEquals(1, dirty(app))
        assertEquals(0, dirty(sync))
    }

    /** ContactsProvider2.insertRawContact never sets DIRTY; only a Data write does. */
    @Test
    fun `an app raw contact insert is not DIRTY by itself`() {
        val uri = resolver.insert(
            RawContacts.CONTENT_URI,
            ContentValues().apply {
                put(RawContacts.ACCOUNT_NAME, ACCOUNT)
                put(RawContacts.ACCOUNT_TYPE, TYPE)
            },
        )!!
        assertEquals(0, dirty(ContentUris.parseId(uri)))
    }

    /** ContactsProvider2.updateData appends `is_read_only = 0` for an app. */
    @Test
    fun `an app Data update skips read-only Data rows`() {
        val id = seedRaw()
        val dataId = provider.insertRow(
            SqliteContactsProvider.DATA,
            Data.RAW_CONTACT_ID to id,
            Data.MIMETYPE to Note.CONTENT_ITEM_TYPE,
            Note.NOTE to "locked",
            Data.IS_READ_ONLY to 1,
        )
        val count = resolver.update(
            ContentUris.withAppendedId(Data.CONTENT_URI, dataId),
            ContentValues().apply { put(Note.NOTE, "changed") },
            null,
            null,
        )
        assertEquals(0, count)
    }

    /**
     * ContactsProvider2.updateRawContact: an app's STARRED change marks DIRTY; no VERSION trigger
     * fires.
     */
    @Test
    fun `an app STARRED update marks DIRTY without a VERSION change`() {
        val id = seedRaw()
        resolver.update(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, id),
            ContentValues().apply { put(RawContacts.STARRED, 1) },
            null,
            null,
        )
        assertEquals(1, dirty(id))
        assertEquals(5L, version(id))
    }

    // ---- selections and account scoping ----

    /** ContactsProvider2 updateInTransaction RAW_CONTACTS_ID: `_id = ? AND (selection)`. */
    @Test
    fun `a raw_contacts row update ANDs its selection with the id`() {
        val id = seedRaw(version = 5L)
        val uri = ContentUris.withAppendedId(syncAdapter(RawContacts.CONTENT_URI), id)
        val miss = resolver.update(uri, ContentValues().apply { put(RawContacts.SYNC2, "x") }, "${RawContacts.VERSION} = ?", arrayOf("4"))
        val hit = resolver.update(uri, ContentValues().apply { put(RawContacts.SYNC2, "y") }, "${RawContacts.VERSION} = ?", arrayOf("5"))
        assertEquals(0, miss)
        assertEquals(1, hit)
        assertEquals("y", raw(id)[RawContacts.SYNC2])
    }

    /** ContactsProvider2.updateRawContacts appends `raw_contact_is_read_only = 0` for an app. */
    @Test
    fun `an app update of a read-only raw contact matches nothing`() {
        val id = seedRaw(readOnly = 1)
        val count = resolver.update(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, id),
            ContentValues().apply { put(RawContacts.STARRED, 1) },
            null,
            null,
        )
        assertEquals(0, count)
    }

    /**
     * ContactsProvider2: directory RAW_CONTACTS updates use appendAccountIdToSelection and DATA
     * deletes appendAccountToSelection, while RAW_CONTACTS_ID updates ignore the account
     * parameters.
     */
    @Test
    fun `account parameters scope directory writes but not a raw_contacts row`() {
        val mine = seedRaw(account = ACCOUNT)
        val other = seedRaw(account = OTHER)
        seedNote(mine)
        seedNote(other)

        resolver.update(syncAdapter(RawContacts.CONTENT_URI), ContentValues().apply { put(RawContacts.SYNC2, "dir") }, null, null)
        assertEquals("dir", raw(mine)[RawContacts.SYNC2])
        assertNull(raw(other)[RawContacts.SYNC2])

        resolver.delete(syncAdapter(Data.CONTENT_URI), null, null)
        assertEquals(0, provider.rows(SqliteContactsProvider.DATA, "${Data.RAW_CONTACT_ID} = ?", mine.toString()).size)
        assertEquals(1, provider.rows(SqliteContactsProvider.DATA, "${Data.RAW_CONTACT_ID} = ?", other.toString()).size)

        resolver.update(
            ContentUris.withAppendedId(syncAdapter(RawContacts.CONTENT_URI, ACCOUNT), other),
            ContentValues().apply { put(RawContacts.SYNC2, "byId") },
            null,
            null,
        )
        assertEquals("a row URI isn't scoped by the account parameters", "byId", raw(other)[RawContacts.SYNC2])
    }

    // ---- deletes ----

    /** ContactsProvider2.deleteRawContact and markRawContactAsDeleted. */
    @Test
    fun `an app delete soft-deletes and a sync adapter delete removes the row and its Data`() {
        val soft = seedRaw()
        val hard = seedRaw()
        seedNote(hard)
        resolver.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, soft), null, null)
        resolver.delete(ContentUris.withAppendedId(syncAdapter(RawContacts.CONTENT_URI), hard), null, null)

        assertEquals("1", raw(soft)[RawContacts.DELETED])
        assertEquals(1, dirty(soft))
        assertNull(provider.rawContact(hard))
        assertTrue(provider.rows(SqliteContactsProvider.DATA, "${Data.RAW_CONTACT_ID} = ?", hard.toString()).isEmpty())
    }

    /** ContactsProvider2.deleteRawContact: rawContactIsLocal deletes immediately. */
    @Test
    fun `an app delete of a raw contact without an account removes it`() {
        val local = seedRaw(account = null)
        resolver.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, local), null, null)
        assertNull(provider.rawContact(local))
    }

    /**
     * ContactsProvider2 deleteInTransaction RAW_CONTACTS_ID deletes by id and ignores the
     * selection.
     */
    @Test
    fun `a raw_contacts row delete ignores its selection`() {
        val id = seedRaw()
        resolver.delete(ContentUris.withAppendedId(syncAdapter(RawContacts.CONTENT_URI), id), "${RawContacts.VERSION} = ?", arrayOf("999"))
        assertNull(provider.rawContact(id))
    }

    /** ContactsProvider2 deleteInTransaction RAW_CONTACTS_ID ignores the account parameters too. */
    @Test
    fun `a raw_contacts row delete is not scoped by the account parameters`() {
        val other = seedRaw(account = OTHER)
        resolver.delete(ContentUris.withAppendedId(syncAdapter(RawContacts.CONTENT_URI, ACCOUNT), other), null, null)
        assertNull(provider.rawContact(other))
    }

    @Test
    fun `numeric columns compare string selection args as numbers`() {
        val id = seedRaw(version = 10L)
        val count = resolver.update(
            ContentUris.withAppendedId(syncAdapter(RawContacts.CONTENT_URI), id),
            ContentValues().apply { put(RawContacts.SYNC2, "x") },
            "${RawContacts.VERSION} = ?",
            arrayOf("10"),
        )
        assertEquals(1, count)
    }

    // ---- batches, columns, groups ----

    /** AbstractContactsProvider.applyBatch runs the batch in one transaction. */
    @Test
    fun `a batch whose operation throws rolls back`() {
        val id = seedRaw()
        assertThrows(Exception::class.java) {
            resolver.applyBatch(
                ContactsContract.AUTHORITY,
                arrayListOf(
                    ContentProviderOperation.newUpdate(ContentUris.withAppendedId(syncAdapter(RawContacts.CONTENT_URI), id))
                        .withValue(RawContacts.SYNC2, "kept?").build(),
                    ContentProviderOperation.newInsert(syncAdapter(Data.CONTENT_URI))
                        .withValue(Data.MIMETYPE, Note.CONTENT_ITEM_TYPE).build(), // no raw_contact_id
                ),
            )
        }
        assertNull(raw(id)[RawContacts.SYNC2])
    }

    @Test
    fun `writing a column the table doesn't have throws`() {
        val id = seedRaw()
        assertThrows(IllegalArgumentException::class.java) {
            resolver.update(
                ContentUris.withAppendedId(syncAdapter(RawContacts.CONTENT_URI), id),
                ContentValues().apply { put("sync_2", "typo") },
                null,
                null,
            )
        }
    }

    /**
     * DataRowHandlerForGroupMembership resolves GROUP_SOURCE_ID to the account's group, creating
     * it.
     */
    @Test
    fun `a membership inserted by group_sourceid resolves to the account's group and reads back`() {
        val id = seedRaw()
        resolver.insert(
            syncAdapter(Data.CONTENT_URI),
            ContentValues().apply {
                put(Data.RAW_CONTACT_ID, id)
                put(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
                put(GroupMembership.GROUP_SOURCE_ID, "Friends")
            },
        )
        val row = provider.rows(SqliteContactsProvider.DATA, "${Data.RAW_CONTACT_ID} = ?", id.toString()).single()
        assertEquals("Friends", row[GroupMembership.GROUP_SOURCE_ID])
        assertEquals(1, provider.rows(SqliteContactsProvider.GROUPS).size)
    }

    // ---- the real repository over it ----

    @Test
    fun `the real repository reads a dirty raw contact with the VERSION it holds`() = runBlocking {
        val id = provider.insertRow(
            SqliteContactsProvider.RAW,
            RawContacts.ACCOUNT_NAME to ACCOUNT,
            RawContacts.ACCOUNT_TYPE to TYPE,
            RawContacts.SOURCE_ID to "/ab/jane.vcf",
            RawContacts.SYNC1 to "uid-jane",
            RawContacts.SYNC2 to "\"e1\"",
            RawContacts.DIRTY to 1,
            RawContacts.VERSION to 12L,
        )
        provider.insertRow(
            SqliteContactsProvider.DATA,
            Data.RAW_CONTACT_ID to id,
            Data.MIMETYPE to StructuredName.CONTENT_ITEM_TYPE,
            StructuredName.DISPLAY_NAME to "Jane Doe",
            StructuredName.GIVEN_NAME to "Jane",
            StructuredName.FAMILY_NAME to "Doe",
        )

        val edit = SqliteContactsProvider.repository(context).pendingLocalChanges(ACCOUNT).edited.single()

        assertEquals("/ab/jane.vcf", edit.href)
        assertEquals("\"e1\"", edit.storedEtag)
        assertEquals(12L, edit.rawContactVersion)
        assertEquals("Jane Doe", edit.contact.displayName)
    }

    private companion object {
        const val ACCOUNT = "user@example.test"
        const val OTHER = "other@example.test"
        const val TYPE = KashCalContactsAuthenticator.ACCOUNT_TYPE
    }
}
