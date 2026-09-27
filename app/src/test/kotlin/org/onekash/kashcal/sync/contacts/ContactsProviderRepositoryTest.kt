package org.onekash.kashcal.sync.contacts

import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.OperationApplicationException
import android.database.MatrixCursor
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.contacts.MappedContact
import org.onekash.kashcal.data.contacts.VCardContactMapper
import org.onekash.kashcal.sync.adapter.KashCalContactsAuthenticator
import org.onekash.vcard.VCardParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests the [ContentProviderOperation] batches, queries and write-backs
 * [AndroidContactsProviderRepository] issues against the Android Contacts Provider, and that
 * every read and write is scoped to one login's system account.
 *
 * Robolectric 4.16.1's `ShadowContentResolver` captures an `applyBatch` but doesn't execute
 * it when no `ContentProvider` is registered for `com.android.contacts` (none is in this
 * tree). It stores the ops with `map.put(authority, ops)`, so the last batch wins, and
 * returns an empty `ContentProviderResult[]`. Two consequences:
 *  - The insert path must not dereference `results[0]` (an empty array would throw on the
 *    first insert); a sync-adapter insert doesn't need the returned id.
 *  - There is no row store to read back, and `getContentProviderOperations` shows only the
 *    last batch. Single-batch cases assert on the captured ops; multi-batch chunking and
 *    yield are asserted on the [AndroidContactsProviderRepository.buildBatches] output (its
 *    `getUri`, `isYieldAllowed` and `resolveValueBackReferences` are public).
 *
 * Tests that need rows or a thrown provider error use a mocked [ContentResolver].
 *
 * Fixtures go through the real [VCardParser] and [VCardContactMapper] (the committed bodies
 * the mapper suite uses), so the write layer stays in step with the rows the mapper emits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ContactsProviderRepositoryTest {

    private val parser = VCardParser()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver: ContentResolver = context.contentResolver

    // A transcoder whose fake codec returns fixed JPEG bytes, so a WebP/HEIF thumbnail
    // is normalized deterministically without a real (Robolectric-absent) decoder.
    private val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x11)

    private fun repo(
        cr: ContentResolver = resolver,
        transcoder: ContactPhotoTranscoder = ContactPhotoTranscoder { fakeJpeg },
    ) = AndroidContactsProviderRepository(cr, transcoder)

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("carddav/fixtures/$name")) {
            "fixture not found: $name"
        }.readBytes().decodeToString()

    private fun mapped(name: String): MappedContact =
        VCardContactMapper.toEntity(parser.parse(fixture(name)).single())

    private fun write(
        name: String,
        href: String,
        etag: String? = "\"etag-$href\"",
        isReadOnly: Boolean = true,
    ): MappedContactWrite =
        MappedContactWrite(href = href, etag = etag, mapped = mapped(name), isReadOnly = isReadOnly)

    /**
     * Resolves an op's ContentValues against dummy prior results, so a
     * `withValueBackReference(RAW_CONTACT_ID, base)` on a Data row resolves without throwing
     * (`ContentUris.parseId` needs a numeric id on the back-reference URI). Ops without a
     * back-reference come back unchanged.
     */
    private fun valuesOf(op: ContentProviderOperation): ContentValues {
        // Back-references are batch-relative, so a Data row can point at any index up to a
        // full batch. Every entry has id 1, so any back-reference resolves to
        // RAW_CONTACT_ID = 1 wherever the contact sits in the batch.
        val backRefs = Array(AndroidContactsProviderRepository.MAX_OPS_PER_BATCH) {
            ContentProviderResult(ContentUris.withAppendedId(RawContacts.CONTENT_URI, 1L))
        }
        return op.resolveValueBackReferences(backRefs, backRefs.size)!!
    }

    /** The RawContact insert is the one carrying SOURCE_ID; Data rows never do. */
    private fun ContentProviderOperation.isRawContactInsert(): Boolean =
        valuesOf(this).containsKey(RawContacts.SOURCE_ID)

    private fun capturedOps(): List<ContentProviderOperation> =
        shadowOf(resolver).getContentProviderOperations(ContactsContract.AUTHORITY)

    // ---------- single-contact insert op structure ----------

    @Test
    fun `insert emits a RawContact followed by data rows back-referencing it`() = runBlocking {
        repo().insertContacts(ACCOUNT_NAME, listOf(write("kashcal_full_v3.vcf", "/full.vcf")))

        val ops = capturedOps()
        assertTrue("expected at least a RawContact + one data row", ops.size >= 2)

        // First op is the RawContact insert.
        assertTrue(ops.first().isInsert)
        assertTrue("first op must be the RawContact (carries SOURCE_ID)", ops.first().isRawContactInsert())

        // Data rows follow and back-reference the RawContact at batch index 0.
        val dataOps = ops.drop(1)
        assertTrue("expected data rows after the RawContact", dataOps.isNotEmpty())
        dataOps.forEach { op ->
            assertTrue(op.isInsert)
            assertFalse("data rows must not carry SOURCE_ID", op.isRawContactInsert())
            // RAW_CONTACT_ID resolves from the back-reference (dummy id 1 above).
            assertEquals(1L, valuesOf(op).getAsLong(Data.RAW_CONTACT_ID))
        }
    }

    @Test
    fun `write layer does not synthesize a second StructuredName`() = runBlocking {
        val src = write("kashcal_full_v3.vcf", "/full.vcf")
        // Guard: the mapper already emits exactly one StructuredName as dataRows[0].
        val mapperNames = src.mapped.dataRows.count {
            it.getAsString(Data.MIMETYPE) == StructuredName.CONTENT_ITEM_TYPE
        }
        assertEquals("mapper contract: exactly one StructuredName", 1, mapperNames)

        repo().insertContacts(ACCOUNT_NAME, listOf(src))

        val nameOps = capturedOps().filter {
            valuesOf(it).getAsString(Data.MIMETYPE) == StructuredName.CONTENT_ITEM_TYPE
        }
        assertEquals("write layer must not double-insert StructuredName", 1, nameOps.size)
    }

    // ---------- SYNC-column mapping ----------

    @Test
    fun `RawContact carries the settled SYNC columns and read-only flag`() = runBlocking {
        val href = "/full.vcf"
        val etag = "\"abc-123\""
        repo().insertContacts(ACCOUNT_NAME, listOf(write("kashcal_full_v3.vcf", href, etag)))

        val raw = capturedOps().first { it.isRawContactInsert() }
        val v = valuesOf(raw)

        assertEquals("SOURCE_ID = href", href, v.getAsString(RawContacts.SOURCE_ID))
        assertEquals("SYNC1 = UID", "kashcal-fixture-0001", v.getAsString(RawContacts.SYNC1))
        assertEquals("SYNC2 = ETag", etag, v.getAsString(RawContacts.SYNC2))
        assertNotNull("SYNC3 = content hash", v.getAsString(RawContacts.SYNC3))
        assertTrue(v.getAsString(RawContacts.SYNC3).isNotBlank())
        // full_v3 has a URI PHOTO -> photoUrl != null -> photo-pending bit set.
        assertEquals(
            "SYNC4 photo-pending bit set when photoUrl != null",
            AndroidContactsProviderRepository.FLAG_PHOTO_PENDING,
            v.getAsInteger(RawContacts.SYNC4),
        )
        assertEquals("server-owned rows are read-only on device", 1, v.getAsInteger(RawContacts.RAW_CONTACT_IS_READ_ONLY))
        // Synced contacts never aggregate with another account's RawContact, so removing
        // this account or purging its rows can't collapse or delete a contact of another
        // account that shares a phone number or name. SUSPENDED still permits a manual
        // merge and leaves that aggregation link, so only DISABLED keeps the purge to our
        // own rows.
        assertEquals(
            "mirrored contacts must not aggregate with any other account",
            RawContacts.AGGREGATION_MODE_DISABLED,
            v.getAsInteger(RawContacts.AGGREGATION_MODE),
        )
    }

    @Test
    fun `a writable book's contact is inserted editable (RAW_CONTACT_IS_READ_ONLY = 0)`() = runBlocking {
        // A contact from a writable address book must be editable on the device, so the
        // provider sets DIRTY when the user edits it, the signal the push reads.
        // Editability is per book: only the read-only flag changes; aggregation and the
        // SYNC columns match the read-only case.
        repo().insertContacts(
            ACCOUNT_NAME,
            listOf(write("kashcal_full_v3.vcf", "/full.vcf", isReadOnly = false)),
        )

        val raw = capturedOps().first { it.isRawContactInsert() }
        val v = valuesOf(raw)
        assertEquals(
            "a writable book's contacts must be editable on device",
            0,
            v.getAsInteger(RawContacts.RAW_CONTACT_IS_READ_ONLY),
        )
        // Editability doesn't change aggregation: still disabled.
        assertEquals(
            "editability must not change the aggregation isolation",
            RawContacts.AGGREGATION_MODE_DISABLED,
            v.getAsInteger(RawContacts.AGGREGATION_MODE),
        )
    }

    @Test
    fun `no photo url leaves the photo-pending bit clear`() = runBlocking {
        // Inline-photo fixture emits the Photo blob directly -> photoUrl == null.
        repo().insertContacts(ACCOUNT_NAME, listOf(write("kashcal_photo_inline_v3.vcf", "/inline.vcf")))
        val raw = capturedOps().first { it.isRawContactInsert() }
        assertEquals(0, valuesOf(raw).getAsInteger(RawContacts.SYNC4))
    }

    // ---------- sync-adapter contract on the URI ----------

    @Test
    fun `insert uri carries CALLER_IS_SYNCADAPTER and the account name plus type`() = runBlocking {
        repo().insertContacts(ACCOUNT_NAME, listOf(write("kashcal_full_v3.vcf", "/full.vcf")))

        // Every op's URI must be in sync-adapter mode and account-scoped, not only the
        // ContentValues: the provider reads these off the URI.
        capturedOps().forEach { op ->
            val uri = op.uri
            assertEquals("true", uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
            assertEquals(ACCOUNT_NAME, uri.getQueryParameter(RawContacts.ACCOUNT_NAME))
            assertEquals(ACCOUNT_TYPE, uri.getQueryParameter(RawContacts.ACCOUNT_TYPE))
        }
    }

    // ---------- no-UID case ----------

    @Test
    fun `two distinct-href blank-UID contacts insert as two rows with distinct source ids`() = runBlocking {
        repo().insertContacts(
            ACCOUNT_NAME,
            listOf(
                write("kashcal_no_uid_v3.vcf", "/a.vcf"),
                write("kashcal_no_uid_v3.vcf", "/b.vcf"),
            ),
        )

        val raws = capturedOps().filter { it.isRawContactInsert() }.map { valuesOf(it) }
        assertEquals("both contacts insert a RawContact", 2, raws.size)
        assertEquals(
            "SOURCE_IDs (hrefs) are distinct",
            setOf("/a.vcf", "/b.vcf"),
            raws.mapNotNull { it.getAsString(RawContacts.SOURCE_ID) }.toSet(),
        )
        // Blank UID -> blank SYNC1 (never a match key downstream), never null-crash.
        raws.forEach { assertTrue(it.getAsString(RawContacts.SYNC1).isNullOrEmpty()) }
    }

    // ---------- chunk / yield boundary (pure builder) ----------

    @Test
    fun `batches cap at 100 ops and never split a contact across a batch`() {
        val many = (1..60).map { write("kashcal_full_v3.vcf", "/c$it.vcf") }
        val batches = repo().buildBatches(ACCOUNT_NAME, many)

        assertTrue("enough contacts to force more than one batch", batches.size > 1)
        batches.forEach { batch ->
            assertTrue("no batch exceeds 100 ops", batch.size <= 100)
            assertTrue("a batch begins on a contact boundary", batch.first().isRawContactInsert())
        }
    }

    @Test
    fun `yield falls on the last op of each contact, never on the RawContact insert`() {
        val many = (1..12).map { write("kashcal_full_v3.vcf", "/c$it.vcf") }
        val ops = repo().buildBatches(ACCOUNT_NAME, many).flatten()

        val yielded = ops.filter { it.isYieldAllowed }
        assertEquals("one yield per contact", many.size, yielded.size)
        // Yielding right after a RawContact insert (before its StructuredName) would let a
        // nameless contact commit if the batch failed partway.
        assertTrue(
            "the RawContact insert must never be a yield point",
            ops.filter { it.isRawContactInsert() }.none { it.isYieldAllowed },
        )
    }

    // ---------- byte-bounded chunking (pure builder) ----------

    /**
     * Builds a contact with one StructuredName row and one inline Photo blob of [photoBytes]
     * bytes: two Data rows, but a byte weight dominated by the blob. This shape trips the
     * Binder transaction limit without tripping
     * [AndroidContactsProviderRepository.MAX_OPS_PER_BATCH].
     */
    private fun photoWrite(href: String, photoBytes: Int): MappedContactWrite {
        val rows = listOf(
            ContentValues().apply {
                put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                put(StructuredName.DISPLAY_NAME, "Contact $href")
            },
            ContentValues().apply {
                put(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                put(Photo.PHOTO, ByteArray(photoBytes) { 0x7F })
            },
        )
        val contact = org.onekash.vcard.model.Contact(
            version = "4.0",
            uid = "uid-$href",
            structuredName = org.onekash.vcard.model.StructuredName(given = "Contact"),
            displayName = "Contact $href",
            rawVCard = "BEGIN:VCARD\r\nVERSION:4.0\r\nEND:VCARD\r\n",
        )
        return MappedContactWrite(
            href = href,
            etag = "\"etag-$href\"",
            mapped = MappedContact(contact = contact, dataRows = rows),
        )
    }

    /** Sum of inline-blob (ByteArray) bytes across every data row in a batch. */
    private fun batchBlobBytes(batch: List<ContentProviderOperation>): Long =
        batch.sumOf { op ->
            val values = valuesOf(op)
            values.keySet().sumOf { key ->
                (values.get(key) as? ByteArray)?.size?.toLong() ?: 0L
            }
        }

    @Test
    fun `a batch's inline-photo bytes stay under the transaction budget`() {
        // Each contact carries ~400KB of photo and several fit well under the 100-op cap,
        // so only a byte ceiling forces a split. Without one they'd land in a single
        // applyBatch and trip TransactionTooLargeException at the ~1MB Binder limit.
        val photoBytes = 400 * 1024
        val many = (1..6).map { photoWrite("/p$it.vcf", photoBytes) }
        val batches = repo().buildBatches(ACCOUNT_NAME, many)

        assertTrue("byte weight alone must force more than one batch", batches.size > 1)
        batches.forEach { batch ->
            // A contact whose own blob exceeds the budget may occupy a batch alone (a
            // contact is never split); multi-contact batches must fit.
            val rawContacts = batch.count { it.isRawContactInsert() }
            if (rawContacts > 1) {
                assertTrue(
                    "a multi-contact batch must stay under the byte budget",
                    batchBlobBytes(batch) <= AndroidContactsProviderRepository.MAX_BATCH_BYTES,
                )
            }
            assertTrue("a batch still begins on a contact boundary", batch.first().isRawContactInsert())
        }
    }

    @Test
    fun `a lone oversized-photo contact still gets its own batch rather than being split`() {
        // A contact whose blob alone exceeds the byte budget still goes out as one whole
        // batch: the byte ceiling never splits a contact.
        val huge = photoWrite("/huge.vcf", (AndroidContactsProviderRepository.MAX_BATCH_BYTES + 1).toInt())
        val batches = repo().buildBatches(ACCOUNT_NAME, listOf(huge))

        assertEquals("the whole contact stays in one batch", 1, batches.size)
        // RawContact, StructuredName and Photo, together in the one batch.
        assertEquals("the whole contact stays intact", 3, batches.single().size)
    }

    // ---------- existingSourceIds query ----------

    @Test
    fun `existingSourceIds returns empty and does not crash when the provider yields nothing`() = runBlocking {
        // No provider registered -> query returns null -> empty set.
        assertTrue(repo().existingSourceIds(ACCOUNT_NAME).isEmpty())
    }

    // ---------- ungrouped-visibility Settings row ----------

    @Test
    fun `ensureContactVisibility inserts a Settings row making ungrouped contacts visible`() = runBlocking {
        val result = repo().ensureContactVisibility(ACCOUNT_NAME)
        assertTrue(result.isSuccess)

        // The Contacts Provider hides contacts of a custom account type that have no group
        // membership unless UNGROUPED_VISIBLE is set on the account's Settings row.
        // Without it the account shows but its contacts never appear.
        val insert = shadowOf(resolver).insertStatements.last()
        val v = insert.contentValues
        assertEquals(
            "UNGROUPED_VISIBLE must be 1 so groupless synced contacts are visible",
            1,
            v.getAsInteger(ContactsContract.Settings.UNGROUPED_VISIBLE),
        )
        assertEquals(
            "SHOULD_SYNC hints the account's contacts are syncable",
            1,
            v.getAsInteger(ContactsContract.Settings.SHOULD_SYNC),
        )
    }

    @Test
    fun `ensureContactVisibility writes the Settings row scoped to the account name and type`() = runBlocking {
        repo().ensureContactVisibility(ACCOUNT_NAME)

        val insert = shadowOf(resolver).insertStatements.last()
        // The provider keys the Settings row by account, read from the ContentValues, and
        // the insert targets Settings through a sync-adapter, account-scoped URI.
        assertEquals(ACCOUNT_NAME, insert.contentValues.getAsString(ContactsContract.Settings.ACCOUNT_NAME))
        assertEquals(ACCOUNT_TYPE, insert.contentValues.getAsString(ContactsContract.Settings.ACCOUNT_TYPE))

        val uri = insert.uri
        assertEquals("true", uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
        assertEquals(ACCOUNT_NAME, uri.getQueryParameter(RawContacts.ACCOUNT_NAME))
        assertEquals(ACCOUNT_TYPE, uri.getQueryParameter(RawContacts.ACCOUNT_TYPE))
    }

    @Test
    fun `ensureContactVisibility SecurityException fails gracefully`() = runBlocking {
        val cr = mockk<ContentResolver>()
        every { cr.insert(any(), any()) } throws SecurityException("WRITE_CONTACTS revoked")

        val result = repo(cr).ensureContactVisibility(ACCOUNT_NAME)
        assertTrue("a provider failure returns Result.failure, not a crash", result.isFailure)
    }

    // ---------- CATEGORIES -> titled Group provisioning ----------

    @Test
    fun `insert provisions a titled Group for each category before the membership rows`() = runBlocking {
        // full_v3 carries CATEGORIES:Family,Test, so the mapper emits two GroupMembership
        // rows keyed by GROUP_SOURCE_ID. The write layer must first insert a titled Group
        // with that SOURCE_ID so each membership resolves to a named group, not a blank one.
        repo().insertContacts(ACCOUNT_NAME, listOf(write("kashcal_full_v3.vcf", "/full.vcf")))

        val groupInserts = shadowOf(resolver).insertStatements.filter {
            it.uri.toString().startsWith(ContactsContract.Groups.CONTENT_URI.toString())
        }
        val titles = groupInserts.map { it.contentValues.getAsString(ContactsContract.Groups.TITLE) }.toSet()
        assertEquals(setOf("Family", "Test"), titles)

        // SOURCE_ID must equal TITLE: it's the key the GroupMembership rows point at.
        groupInserts.forEach {
            val v = it.contentValues
            assertEquals(
                v.getAsString(ContactsContract.Groups.TITLE),
                v.getAsString(ContactsContract.Groups.SOURCE_ID),
            )
            assertEquals("groups are visible", 1, v.getAsInteger(ContactsContract.Groups.GROUP_VISIBLE))
        }
    }

    @Test
    fun `group provisioning uris are sync-adapter mode and account-scoped`() = runBlocking {
        repo().insertContacts(ACCOUNT_NAME, listOf(write("kashcal_full_v3.vcf", "/full.vcf")))

        val groupInserts = shadowOf(resolver).insertStatements.filter {
            it.uri.toString().startsWith(ContactsContract.Groups.CONTENT_URI.toString())
        }
        assertTrue("categories present -> at least one group insert", groupInserts.isNotEmpty())
        groupInserts.forEach {
            val uri = it.uri
            assertEquals("true", uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
            assertEquals(ACCOUNT_NAME, uri.getQueryParameter(RawContacts.ACCOUNT_NAME))
            assertEquals(ACCOUNT_TYPE, uri.getQueryParameter(RawContacts.ACCOUNT_TYPE))
        }
    }

    @Test
    fun `insert without categories provisions no groups`() = runBlocking {
        // no_uid_v3 carries no CATEGORIES -> no group insert should be issued.
        repo().insertContacts(ACCOUNT_NAME, listOf(write("kashcal_no_uid_v3.vcf", "/a.vcf")))

        val groupInserts = shadowOf(resolver).insertStatements.filter {
            it.uri.toString().startsWith(ContactsContract.Groups.CONTENT_URI.toString())
        }
        assertTrue("no categories -> no group provisioning", groupInserts.isEmpty())
    }

    @Test
    fun `group provisioning failure does not fail the contact insert`() = runBlocking {
        // A Groups insert that throws is swallowed: the contact still inserts (the provider
        // auto-creates a blank group), so a category-group failure isn't fatal.
        val cr = mockk<ContentResolver>(relaxed = true)
        every { cr.query(any(), any(), any(), any(), any()) } returns null
        every {
            cr.insert(match { it.toString().startsWith(ContactsContract.Groups.CONTENT_URI.toString()) }, any())
        } throws SecurityException("WRITE_CONTACTS revoked for groups")

        val result = repo(cr).insertContacts(ACCOUNT_NAME, listOf(write("kashcal_full_v3.vcf", "/full.vcf")))
        assertTrue("group provisioning failure must not fail the contact insert", result.isSuccess)
    }

    // ---------- account-scoped delete + multi-account isolation ----------

    @Test
    fun `purgeAccount deletes scoped by BOTH account name and type`() = runBlocking {
        repo().purgeAccount(ACCOUNT_NAME)

        val del = shadowOf(resolver).deleteStatements.last()
        val where = del.where.orEmpty()
        assertTrue("selection filters by ACCOUNT_NAME", where.contains(RawContacts.ACCOUNT_NAME))
        assertTrue("selection filters by ACCOUNT_TYPE", where.contains(RawContacts.ACCOUNT_TYPE))
        val args = del.selectionArgs?.toList().orEmpty()
        assertTrue("account name bound", args.contains(ACCOUNT_NAME))
        assertTrue(
            "account type bound — name-only would cross the calendar type",
            args.contains(ACCOUNT_TYPE),
        )
    }

    // ---------- error surface ----------

    @Test
    fun `applyBatch OperationApplicationException fails the chunk gracefully`() = runBlocking {
        val cr = mockk<ContentResolver>()
        every { cr.applyBatch(any(), any()) } throws OperationApplicationException("boom")

        val result = repo(cr).insertContacts(ACCOUNT_NAME, listOf(write("kashcal_full_v3.vcf", "/full.vcf")))
        assertTrue("a provider failure returns Result.failure, not a crash", result.isFailure)
    }

    @Test
    fun `applyBatch SecurityException (WRITE_CONTACTS revoked) fails gracefully`() = runBlocking {
        val cr = mockk<ContentResolver>()
        every { cr.applyBatch(any(), any()) } throws SecurityException("WRITE_CONTACTS revoked")

        val result = repo(cr).insertContacts(ACCOUNT_NAME, listOf(write("kashcal_full_v3.vcf", "/full.vcf")))
        assertTrue(result.isFailure)
    }

    @Test
    fun `applyBatch unchecked runtime (unresolvable uri) fails gracefully, honoring the no-throw contract`() = runBlocking {
        // The provider can throw an IllegalArgumentException (unknown authority or URI) or
        // any Binder RuntimeException, neither one of the three checked types. The
        // no-throw contract must still hold: fail the chunk, don't crash.
        val cr = mockk<ContentResolver>()
        every { cr.applyBatch(any(), any()) } throws IllegalArgumentException("Unknown URI")

        val result = repo(cr).insertContacts(ACCOUNT_NAME, listOf(write("kashcal_full_v3.vcf", "/full.vcf")))
        assertTrue("an unchecked runtime must degrade to Result.failure, not propagate", result.isFailure)
    }

    // ---------- deleteByHrefs (server removed set and orphan sweep) ----------

    @Test
    fun `deleteByHrefs scopes by account name AND type AND SOURCE_ID IN`() = runBlocking {
        repo().deleteByHrefs(ACCOUNT_NAME, listOf("/a.vcf", "/b.vcf"))

        val del = shadowOf(resolver).deleteStatements.last()
        val where = del.where.orEmpty()
        assertTrue("selection filters by ACCOUNT_NAME", where.contains(RawContacts.ACCOUNT_NAME))
        assertTrue("selection filters by ACCOUNT_TYPE", where.contains(RawContacts.ACCOUNT_TYPE))
        assertTrue("selection filters by SOURCE_ID IN (...)", where.contains(RawContacts.SOURCE_ID))

        val args = del.selectionArgs?.toList().orEmpty()
        assertTrue("account name bound", args.contains(ACCOUNT_NAME))
        assertTrue(
            "account type bound — name-only would cross the calendar type",
            args.contains(ACCOUNT_TYPE),
        )
        assertTrue("both hrefs bound as SOURCE_ID args", args.containsAll(listOf("/a.vcf", "/b.vcf")))
    }

    @Test
    fun `deleteByHrefs with empty hrefs issues no delete statement`() = runBlocking {
        val before = shadowOf(resolver).deleteStatements.size
        val result = repo().deleteByHrefs(ACCOUNT_NAME, emptyList())

        assertTrue(result.isSuccess)
        assertEquals(
            "empty hrefs must not issue a delete (would match nothing or, worse, everything)",
            before,
            shadowOf(resolver).deleteStatements.size,
        )
    }

    @Test
    fun `deleteByHrefs chunks a large href set into multiple scoped deletes`() = runBlocking {
        val many = (1..900).map { "/c$it.vcf" }
        repo().deleteByHrefs(ACCOUNT_NAME, many)

        val deletes = shadowOf(resolver).deleteStatements
        assertTrue("a 900-href delete must chunk into more than one statement", deletes.size > 1)
        // Every chunk keeps the account name and type predicate; a chunk that dropped it
        // could delete across the account boundary.
        deletes.forEach { del ->
            val where = del.where.orEmpty()
            assertTrue("each chunk scopes by ACCOUNT_NAME", where.contains(RawContacts.ACCOUNT_NAME))
            assertTrue("each chunk scopes by ACCOUNT_TYPE", where.contains(RawContacts.ACCOUNT_TYPE))
            assertTrue("each chunk carries the type arg", del.selectionArgs.orEmpty().contains(ACCOUNT_TYPE))
        }
    }

    @Test
    fun `deleteByHrefs SecurityException (WRITE_CONTACTS revoked) fails gracefully`() = runBlocking {
        val cr = mockk<ContentResolver>()
        every { cr.delete(any(), any(), any()) } throws SecurityException("WRITE_CONTACTS revoked")

        val result = repo(cr).deleteByHrefs(ACCOUNT_NAME, listOf("/a.vcf"))
        assertTrue("a provider failure returns Result.failure, not a crash", result.isFailure)
    }

    // ---------- replaceContacts (in-place update) ----------

    @Test
    fun `replaceContacts updates an existing contact in place, preserving its RawContact id`() = runBlocking {
        // A present href resolves to an existing RawContact _ID. The replace keeps that row,
        // so the aggregate Contact id, starred flag, home-screen shortcut and lookup key
        // survive; a delete and re-create would mint a new id.
        val cr = mockk<ContentResolver>()
        every { cr.query(any(), any(), any(), any(), any()) } returns
            MatrixCursor(arrayOf(RawContacts.SOURCE_ID, RawContacts._ID)).apply {
                addRow(arrayOf<Any?>("/p.vcf", 55L))
            }
        val batch = slot<ArrayList<ContentProviderOperation>>()
        every { cr.applyBatch(eq(ContactsContract.AUTHORITY), capture(batch)) } returns emptyArray()

        val result = repo(cr).replaceContacts(ACCOUNT_NAME, listOf(write("kashcal_photo_inline_v3.vcf", "/p.vcf")))
        assertTrue(result.isSuccess)

        val ops = batch.captured
        // No RawContact is inserted: the existing row is kept.
        assertTrue(
            "an in-place replace inserts no new RawContact (that would churn the _ID)",
            ops.none { it.isInsert && it.isRawContactInsert() },
        )
        // The RawContact row is updated in place, carrying the new server etag on SYNC2.
        val update = ops.first { it.isUpdate }
        assertTrue("the update targets a RawContacts row", update.uri.toString().contains("raw_contacts"))
        assertEquals(
            "the in-place update carries the new etag",
            "\"etag-/p.vcf\"",
            valuesOf(update).getAsString(RawContacts.SYNC2),
        )
        // Old Data rows are deleted, then new ones inserted against the same kept id.
        assertTrue("a Data-row delete clears the stale rows", ops.any { it.isDelete })
        val dataInsert = ops.first { it.isInsert }
        assertEquals(
            "re-inserted Data rows reference the retained RawContact id, not a new one",
            55L,
            valuesOf(dataInsert).getAsLong(Data.RAW_CONTACT_ID),
        )
    }

    @Test
    fun `replaceContacts resolves existing rows excluding locally-deleted (tombstoned) rows`() = runBlocking {
        // The href-to-_ID lookup behind an in-place replace must not match a tombstone
        // (DELETED=1). Matching one would refresh the tombstone's etag under a concurrent
        // server edit, arming the queued DELETE to destroy the edited server copy. With the
        // lookup scoped to live rows, a delete-pending row goes to the insert path.
        val cr = mockk<ContentResolver>()
        val selection = slot<String>()
        every { cr.query(any(), any(), capture(selection), any(), any()) } returns
            MatrixCursor(arrayOf(RawContacts.SOURCE_ID, RawContacts._ID)).apply {
                addRow(arrayOf<Any?>("/p.vcf", 55L))
            }
        every { cr.applyBatch(eq(ContactsContract.AUTHORITY), any()) } returns emptyArray()

        repo(cr).replaceContacts(ACCOUNT_NAME, listOf(write("kashcal_photo_inline_v3.vcf", "/p.vcf")))

        assertTrue(
            "the href-to-_ID resolve must exclude tombstoned rows",
            selection.captured.contains("${RawContacts.DELETED} = 0"),
        )
    }

    @Test
    fun `replaceContacts falls back to a fresh insert when the href has no existing row`() = runBlocking {
        // No RawContact resolves for this href, so there's nothing to keep and it goes
        // through the insert path. The Robolectric resolver's lookup query returns null.
        repo().replaceContacts(ACCOUNT_NAME, listOf(write("kashcal_full_v3.vcf", "/new.vcf")))

        val raw = capturedOps().first { it.isRawContactInsert() }
        assertEquals(
            "a href with no existing row is inserted fresh with SOURCE_ID = href",
            "/new.vcf",
            valuesOf(raw).getAsString(RawContacts.SOURCE_ID),
        )
    }

    @Test
    fun `replaceContacts with empty list is a no-op`() = runBlocking {
        val delsBefore = shadowOf(resolver).deleteStatements.size
        val result = repo().replaceContacts(ACCOUNT_NAME, emptyList())

        assertTrue(result.isSuccess)
        assertEquals(delsBefore, shadowOf(resolver).deleteStatements.size)
        assertTrue("no inserts either", capturedOps().isEmpty())
    }

    // ---------- existingEtagsByHref (change-detection read-back) ----------

    @Test
    fun `existingEtagsByHref returns empty and does not crash when the provider yields nothing`() = runBlocking {
        // No provider registered -> query returns null -> empty map. This is the read-back
        // the pull uses to tell changed from unchanged.
        assertTrue(repo().existingEtagsByHref(ACCOUNT_NAME).isEmpty())
    }

    @Test
    fun `existingEtagsByHref keeps tombstoned rows in change-detection`() = runBlocking {
        // A locally deleted contact (DELETED=1) awaiting its DELETE push must stay in
        // change detection. Dropping it would let a transient delete failure plus a full
        // listing re-insert the deleted contact (an unchanged server etag must skip, not
        // resurrect), and would misread a device holding only tombstones as wiped. The
        // etag-refresh hazard is closed at the in-place replace's lookup
        // (`resolveRawContactIdsByHref` excludes DELETED), not here.
        val cr = mockk<ContentResolver>()
        val selection = slot<String>()
        every { cr.query(any(), any(), capture(selection), any(), any()) } returns
            MatrixCursor(arrayOf(RawContacts.SOURCE_ID, RawContacts.SYNC2))

        repo(cr).existingEtagsByHref(ACCOUNT_NAME)

        assertTrue("scoped by ACCOUNT_NAME", selection.captured.contains(RawContacts.ACCOUNT_NAME))
        assertFalse(
            "change-detection must NOT filter out tombstoned rows",
            selection.captured.contains("${RawContacts.DELETED} = 0"),
        )
    }

    // ---------- pending-photo query (SYNC4 FLAG_PHOTO_PENDING worklist) ----------

    @Test
    fun `pendingPhotoSourceIds returns empty and does not crash when the provider yields nothing`() = runBlocking {
        // No provider registered -> query returns null -> empty set.
        assertTrue(repo().pendingPhotoSourceIds(ACCOUNT_NAME).isEmpty())
    }

    @Test
    fun `pendingPhotoSourceIds returns only rows whose SYNC4 has the photo-pending bit`() = runBlocking {
        // ShadowContentResolver can't be seeded with RawContact rows, so a mocked resolver
        // serves the cursor: four rows, two pending, one of those with another bit set too.
        val cr = mockk<ContentResolver>()
        val cursor = MatrixCursor(arrayOf(RawContacts.SOURCE_ID, RawContacts.SYNC4)).apply {
            addRow(arrayOf<Any?>("/pending.vcf", AndroidContactsProviderRepository.FLAG_PHOTO_PENDING))
            addRow(arrayOf<Any?>("/clean.vcf", 0))
            // Pending bit plus an unrelated high bit still counts as pending.
            addRow(arrayOf<Any?>("/pendingplus.vcf", AndroidContactsProviderRepository.FLAG_PHOTO_PENDING or 0b1000))
            addRow(arrayOf<Any?>("/nullflags.vcf", null)) // null SYNC4 = no flags
        }
        every { cr.query(any(), any(), any(), any(), any()) } returns cursor

        val pending = repo(cr).pendingPhotoSourceIds(ACCOUNT_NAME)
        assertEquals(setOf("/pending.vcf", "/pendingplus.vcf"), pending)
    }

    @Test
    fun `pendingPhotoSourceIds queries scoped to the account name and type`() = runBlocking {
        val cr = mockk<ContentResolver>()
        val selection = slot<String>()
        val args = slot<Array<String>>()
        every { cr.query(any(), any(), capture(selection), capture(args), any()) } returns
            MatrixCursor(arrayOf(RawContacts.SOURCE_ID, RawContacts.SYNC4))

        repo(cr).pendingPhotoSourceIds(ACCOUNT_NAME)

        assertTrue("scoped by ACCOUNT_NAME", selection.captured.contains(RawContacts.ACCOUNT_NAME))
        assertTrue("scoped by ACCOUNT_TYPE", selection.captured.contains(RawContacts.ACCOUNT_TYPE))
        assertTrue("account name bound", args.captured.contains(ACCOUNT_NAME))
        assertTrue("account type bound", args.captured.contains(ACCOUNT_TYPE))
    }

    @Test
    fun `pendingPhotoSourceIds SecurityException fails to empty, not a crash`() = runBlocking {
        val cr = mockk<ContentResolver>()
        every { cr.query(any(), any(), any(), any(), any()) } throws SecurityException("READ_CONTACTS revoked")
        assertTrue(repo(cr).pendingPhotoSourceIds(ACCOUNT_NAME).isEmpty())
    }

    // ---------- writePhotoAndClearPending (blob + flag move together) ----------

    @Test
    fun `writePhotoAndClearPending applies a delete-insert-update batch in one applyBatch`() = runBlocking {
        val cr = photoResolver(rawContactId = 42L, currentFlags = FLAG_PHOTO_PENDING)
        val batch = slot<ArrayList<ContentProviderOperation>>()
        every { cr.applyBatch(eq(ContactsContract.AUTHORITY), capture(batch)) } returns emptyArray()

        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x42)
        val result = repo(cr).writePhotoAndClearPending(ACCOUNT_NAME, "/pending.vcf", bytes)
        assertTrue(result.isSuccess)

        val ops = batch.captured
        assertEquals("delete + insert + update in ONE batch", 3, ops.size)
        assertTrue("first op deletes the old Photo row", ops[0].isDelete)
        assertTrue("second op inserts the new blob", ops[1].isInsert)
        assertTrue("third op updates SYNC4", ops[2].isUpdate)

        // Insert carries the exact photo bytes + Photo mimetype under the RawContact.
        val insertValues = valuesOf(ops[1])
        assertEquals(Photo.CONTENT_ITEM_TYPE, insertValues.getAsString(Data.MIMETYPE))
        assertEquals(42L, insertValues.getAsLong(Data.RAW_CONTACT_ID))
        assertArrayEquals("photo blob written verbatim", bytes, insertValues.getAsByteArray(Photo.PHOTO))
    }

    @Test
    fun `writePhotoAndClearPending clears only the pending bit and preserves other SYNC4 bits`() = runBlocking {
        // Current SYNC4 is the pending bit plus a high bit; the update must AND-NOT only the
        // pending bit and keep the high bit (never zero the column).
        val other = 0b1000
        val cr = photoResolver(rawContactId = 7L, currentFlags = FLAG_PHOTO_PENDING or other)
        val batch = slot<ArrayList<ContentProviderOperation>>()
        every { cr.applyBatch(any(), capture(batch)) } returns emptyArray()

        repo(cr).writePhotoAndClearPending(ACCOUNT_NAME, "/p.vcf", byteArrayOf(1))

        val update = batch.captured.first { it.isUpdate }
        assertEquals(
            "AND-NOT clears the pending bit but keeps the other bit",
            other,
            valuesOf(update).getAsInteger(RawContacts.SYNC4),
        )
    }

    @Test
    fun `writePhotoAndClearPending is a no-op success when the source id no longer resolves`() = runBlocking {
        // Contact deleted between pull and fetch: the lookup returns no row, so no
        // applyBatch, and still a success (nothing to leave pending).
        val cr = mockk<ContentResolver>()
        every { cr.query(any(), any(), any(), any(), any()) } returns
            MatrixCursor(arrayOf(RawContacts._ID, RawContacts.SYNC4)) // empty
        val result = repo(cr).writePhotoAndClearPending(ACCOUNT_NAME, "/gone.vcf", byteArrayOf(1))
        assertTrue("a vanished source id is a no-op success", result.isSuccess)
        verify(exactly = 0) { cr.applyBatch(any(), any()) }
    }

    @Test
    fun `writePhotoAndClearPending SecurityException leaves the contact pending (failure, no crash)`() = runBlocking {
        val cr = photoResolver(rawContactId = 1L, currentFlags = FLAG_PHOTO_PENDING)
        every { cr.applyBatch(any(), any()) } throws SecurityException("WRITE_CONTACTS revoked")
        val result = repo(cr).writePhotoAndClearPending(ACCOUNT_NAME, "/p.vcf", byteArrayOf(1))
        assertTrue("credential revocation mid-write must not crash", result.isFailure)
    }

    @Test
    fun `writePhotoAndClearPending write ops target sync-adapter, account-scoped URIs`() = runBlocking {
        val cr = photoResolver(rawContactId = 3L, currentFlags = FLAG_PHOTO_PENDING)
        val batch = slot<ArrayList<ContentProviderOperation>>()
        every { cr.applyBatch(any(), capture(batch)) } returns emptyArray()

        repo(cr).writePhotoAndClearPending(ACCOUNT_NAME, "/p.vcf", byteArrayOf(1))

        batch.captured.forEach { op ->
            val uri = op.uri
            assertEquals("true", uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
            assertEquals(ACCOUNT_NAME, uri.getQueryParameter(RawContacts.ACCOUNT_NAME))
            assertEquals(ACCOUNT_TYPE, uri.getQueryParameter(RawContacts.ACCOUNT_TYPE))
        }
    }

    // ---------- clearPhotoPending (stale flag, no blob) ----------

    @Test
    fun `clearPhotoPending updates SYNC4 only and writes no photo blob`() = runBlocking {
        val cr = photoResolver(rawContactId = 9L, currentFlags = FLAG_PHOTO_PENDING)
        val batch = slot<ArrayList<ContentProviderOperation>>()
        every { cr.applyBatch(any(), capture(batch)) } returns emptyArray()

        val result = repo(cr).clearPhotoPending(ACCOUNT_NAME, "/stale.vcf")
        assertTrue(result.isSuccess)

        val ops = batch.captured
        assertEquals("only the SYNC4 update, no Photo delete/insert", 1, ops.size)
        assertTrue(ops.single().isUpdate)
        assertEquals(0, valuesOf(ops.single()).getAsInteger(RawContacts.SYNC4))
    }

    @Test
    fun `clearPhotoPending is a no-op success when the source id no longer resolves`() = runBlocking {
        val cr = mockk<ContentResolver>()
        every { cr.query(any(), any(), any(), any(), any()) } returns
            MatrixCursor(arrayOf(RawContacts._ID, RawContacts.SYNC4))
        val result = repo(cr).clearPhotoPending(ACCOUNT_NAME, "/gone.vcf")
        assertTrue(result.isSuccess)
        verify(exactly = 0) { cr.applyBatch(any(), any()) }
    }

    // ---------- pendingLocalChanges (DIRTY -> edit, DELETED -> tombstone) ----------

    /**
     * Builds a resolver that serves the reads
     * [AndroidContactsProviderRepository.pendingLocalChanges] issues: the Groups read by its
     * projection, and by selection substring the DIRTY edit scan, the DELETED tombstone scan and
     * the per-RawContact Data read. Each call builds a new cursor, so a repeated per-row Data read
     * never gets a consumed one.
     */
    private fun changesResolver(
        dirty: () -> MatrixCursor,
        deleted: () -> MatrixCursor,
        data: () -> MatrixCursor,
        groups: () -> MatrixCursor? = { null },
    ): ContentResolver {
        val cr = mockk<ContentResolver>()
        every { cr.query(any(), any(), any(), any(), any()) } answers {
            // Route the Groups._ID -> TITLE read by its projection: its selection is the
            // bare account scope, which the DIRTY and DELETED scans also contain, so
            // selection routing would collide.
            val projection = arg<Array<String>?>(1)
            if (projection != null && projection.contains(ContactsContract.Groups.TITLE)) {
                return@answers groups()
            }
            when (val selection = arg<String?>(2)) {
                null -> null
                else -> when {
                    selection.contains(Data.RAW_CONTACT_ID) -> data()
                    selection.contains(RawContacts.DIRTY) -> dirty()
                    selection.contains(RawContacts.DELETED) -> deleted()
                    else -> null
                }
            }
        }
        return cr
    }

    private fun emptyCursor(vararg columns: String) = MatrixCursor(columns)

    private fun dirtyRow(id: Long, href: String, uid: String, etag: String?) =
        MatrixCursor(arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1, RawContacts.SYNC2))
            .apply { addRow(arrayOf<Any?>(id, href, uid, etag)) }

    private fun structuredNameData(displayName: String, given: String, family: String) =
        MatrixCursor(
            arrayOf(
                Data.MIMETYPE,
                StructuredName.DISPLAY_NAME,
                StructuredName.GIVEN_NAME,
                StructuredName.FAMILY_NAME,
            ),
        ).apply { addRow(arrayOf<Any?>(StructuredName.CONTENT_ITEM_TYPE, displayName, given, family)) }

    /** Builds a Data cursor with a StructuredName row and a Photo row carrying [photoBytes]. */
    private fun nameAndPhotoData(displayName: String, photoBytes: ByteArray) =
        MatrixCursor(arrayOf(Data.MIMETYPE, StructuredName.DISPLAY_NAME, Photo.PHOTO)).apply {
            addRow(arrayOf<Any?>(StructuredName.CONTENT_ITEM_TYPE, displayName, null))
            addRow(arrayOf<Any?>(Photo.CONTENT_ITEM_TYPE, null, photoBytes))
        }

    /**
     * Builds a Data cursor with a StructuredName row and a GroupMembership row whose
     * GROUP_SOURCE_ID is blank and whose GROUP_ROW_ID is [groupRowId]: the shape of a user
     * label added in the Contacts app. DISPLAY_NAME and GROUP_ROW_ID are both DATA1, so one
     * "data1" column carries the name on the name row and the group row id on the
     * membership row.
     */
    private fun nameAndGroupData(displayName: String, groupRowId: Long) =
        MatrixCursor(
            arrayOf(
                Data.MIMETYPE,
                StructuredName.DISPLAY_NAME, // == GroupMembership.GROUP_ROW_ID == "data1"
                GroupMembership.GROUP_SOURCE_ID,
            ),
        ).apply {
            addRow(arrayOf<Any?>(StructuredName.CONTENT_ITEM_TYPE, displayName, null))
            addRow(arrayOf<Any?>(GroupMembership.CONTENT_ITEM_TYPE, groupRowId, ""))
        }

    @Test
    fun `pendingLocalChanges returns only DIRTY non-deleted rows, reverse-mapped with their sync locators`() = runBlocking {
        val cr = changesResolver(
            dirty = { dirtyRow(100L, "/edited.vcf", "uid-1", "\"etag-1\"") },
            deleted = { emptyCursor(RawContacts.SOURCE_ID, RawContacts.SYNC2) },
            data = { structuredNameData("Alice Edited", "Alice", "Edited") },
        )

        val changes = repo(cr).pendingLocalChanges(ACCOUNT_NAME)

        assertEquals("one dirty edit", 1, changes.edited.size)
        val edit = changes.edited.single()
        assertEquals("href = SOURCE_ID", "/edited.vcf", edit.href)
        assertEquals("uid = SYNC1", "uid-1", edit.uid)
        assertEquals("stored etag = SYNC2", "\"etag-1\"", edit.storedEtag)
        // The provider _ID is carried on the edit so a net-new contact (blank SOURCE_ID) can
        // be written back by its _ID, since it has no href yet.
        assertEquals("local id = _ID", 100L, edit.localId)
        // The device fields were reverse-mapped through DeviceContactRowMapper, and the UID
        // was set on the model (it isn't stored on Data rows).
        assertEquals("reverse-mapped display name", "Alice Edited", edit.contact.displayName)
        assertEquals("uid threaded onto the model", "uid-1", edit.contact.uid)
        assertTrue("no tombstones", changes.deleted.isEmpty())
    }

    @Test
    fun `pendingLocalChanges returns DELETED rows as tombstones carrying href and stored etag`() = runBlocking {
        val cr = changesResolver(
            dirty = { emptyCursor(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1, RawContacts.SYNC2) },
            deleted = {
                MatrixCursor(arrayOf(RawContacts.SOURCE_ID, RawContacts.SYNC2))
                    .apply { addRow(arrayOf<Any?>("/gone.vcf", "\"etag-gone\"")) }
            },
            data = { structuredNameData("unused", "x", "y") },
        )

        val changes = repo(cr).pendingLocalChanges(ACCOUNT_NAME)

        assertTrue("no edits", changes.edited.isEmpty())
        assertEquals("one tombstone", 1, changes.deleted.size)
        val tomb = changes.deleted.single()
        assertEquals("/gone.vcf", tomb.href)
        assertEquals("\"etag-gone\"", tomb.storedEtag)
    }

    @Test
    fun `pendingLocalChanges resolves a blank-source-id group label via the Groups row-id map`() = runBlocking {
        val cr = changesResolver(
            dirty = { dirtyRow(100L, "/edited.vcf", "uid-1", "\"etag-1\"") },
            deleted = { emptyCursor(RawContacts.SOURCE_ID, RawContacts.SYNC2) },
            data = { nameAndGroupData("Alice Edited", groupRowId = 5L) },
            groups = {
                MatrixCursor(arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE))
                    .apply { addRow(arrayOf<Any?>(5L, "Friends")) }
            },
        )

        val changes = repo(cr).pendingLocalChanges(ACCOUNT_NAME)

        // The user label (GROUP_ROW_ID -> local Groups._ID, GROUP_SOURCE_ID blank) resolves
        // to its title through the scan's Groups map and lands in the model's categories,
        // so the push writes it as a CATEGORIES value instead of dropping it.
        val edit = changes.edited.single()
        assertTrue("user label resolved into categories", edit.contact.categories.contains("Friends"))
    }

    @Test
    fun `a dirty contact with a WebP thumbnail comes back transcoded to JPEG`() = runBlocking {
        // A device WebP thumbnail carries no MIME type. The transcoder passed to the
        // repository converts it to JPEG in the dirty-edit scan so a strict server stores it.
        val webp = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, 0x1A, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20,
        )
        val cr = changesResolver(
            dirty = { dirtyRow(100L, "/edited.vcf", "uid-1", "\"etag-1\"") },
            deleted = { emptyCursor(RawContacts.SOURCE_ID, RawContacts.SYNC2) },
            data = { nameAndPhotoData("Alice Edited", webp) },
        )

        val edit = repo(cr).pendingLocalChanges(ACCOUNT_NAME).edited.single()

        // Bytes are the JPEG codec output and contentType is cleared, so the writer sniffs
        // them and labels image/jpeg instead of octet-stream on a 4.0 book.
        assertNull("transcoded photo carries no contentType label", edit.contact.photo?.contentType)
        assertArrayEquals(fakeJpeg, edit.contact.photo?.data)
    }

    @Test
    fun `a failing Groups read degrades to source-id-only categories without aborting the edit scan`() = runBlocking {
        val cr = changesResolver(
            dirty = { dirtyRow(100L, "/edited.vcf", "uid-1", "\"etag-1\"") },
            deleted = { emptyCursor(RawContacts.SOURCE_ID, RawContacts.SYNC2) },
            data = { nameAndGroupData("Alice Edited", groupRowId = 5L) },
            groups = { throw SecurityException("READ_CONTACTS revoked") },
        )

        val changes = repo(cr).pendingLocalChanges(ACCOUNT_NAME)

        // The Groups read failed but the dirty contact is still returned: the blank
        // source-id label drops (only SOURCE_ID-keyed groups remain) instead of the whole
        // scan collapsing to an empty pending set.
        val edit = changes.edited.single()
        assertTrue("unresolved label dropped, not crashed", edit.contact.categories.isEmpty())
    }

    @Test
    fun `a cancelled Groups read propagates rather than reporting an empty pending set`() {
        val cr = changesResolver(
            dirty = { dirtyRow(100L, "/edited.vcf", "uid-1", "\"etag-1\"") },
            deleted = { emptyCursor(RawContacts.SOURCE_ID, RawContacts.SYNC2) },
            data = { nameAndGroupData("Alice Edited", groupRowId = 5L) },
            groups = { throw CancellationException("cancelled mid-scan") },
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { repo(cr).pendingLocalChanges(ACCOUNT_NAME) }
        }
    }

    @Test
    fun `pendingLocalChanges scans are scoped by account name AND type`() = runBlocking {
        // Collect each query's (selection, args) inside the stub instead of capturing: the
        // query count varies and captureNullable isn't available in this mockk.
        val issued = mutableListOf<Pair<String?, Array<String>?>>()
        val cr = mockk<ContentResolver>()
        every { cr.query(any(), any(), any(), any(), any()) } answers {
            val selection = arg<String?>(2)
            @Suppress("UNCHECKED_CAST")
            issued += selection to (arg<Any?>(3) as? Array<String>)
            when {
                selection == null -> null
                selection.contains(Data.RAW_CONTACT_ID) -> structuredNameData("A", "a", "b")
                selection.contains(RawContacts.DIRTY) -> dirtyRow(1L, "/e.vcf", "u", "\"e\"")
                selection.contains(RawContacts.DELETED) -> emptyCursor(RawContacts.SOURCE_ID, RawContacts.SYNC2)
                else -> null
            }
        }

        repo(cr).pendingLocalChanges(ACCOUNT_NAME)

        // Both RawContact scans (dirty and deleted) must carry the account name and type
        // predicate; a name-only scan could cross into the calendar account type.
        val scans = issued.filter { (sel, _) ->
            sel != null && (sel.contains(RawContacts.DIRTY) || sel.contains(RawContacts.DELETED))
        }
        assertTrue("both RawContact scans present", scans.size >= 2)
        scans.forEach { (sel, args) ->
            assertTrue("scoped by ACCOUNT_NAME", sel!!.contains(RawContacts.ACCOUNT_NAME))
            assertTrue("scoped by ACCOUNT_TYPE", sel.contains(RawContacts.ACCOUNT_TYPE))
            assertTrue("account name bound", args.orEmpty().contains(ACCOUNT_NAME))
            assertTrue("account type bound", args.orEmpty().contains(ACCOUNT_TYPE))
        }
    }

    @Test
    fun `pendingLocalChanges returns empty and does not crash when the provider yields nothing`() = runBlocking {
        // No provider registered -> query returns null -> empty pending set.
        val changes = repo().pendingLocalChanges(ACCOUNT_NAME)
        assertTrue(changes.edited.isEmpty() && changes.deleted.isEmpty())
    }

    @Test
    fun `pendingLocalChanges SecurityException fails to empty, not a crash`() = runBlocking {
        val cr = mockk<ContentResolver>()
        every { cr.query(any(), any(), any(), any(), any()) } throws SecurityException("READ_CONTACTS revoked")
        val changes = repo(cr).pendingLocalChanges(ACCOUNT_NAME)
        assertTrue(changes.edited.isEmpty() && changes.deleted.isEmpty())
    }

    // ---------- markContactUploaded (SYNC2 + DIRTY=0 via sync-adapter) ----------

    @Test
    fun `markContactUploaded clears DIRTY and stores the new etag via a sync-adapter, account-scoped write`() = runBlocking {
        val cr = resolverFor(rawContactId = 88L)
        val batch = slot<ArrayList<ContentProviderOperation>>()
        every { cr.applyBatch(eq(ContactsContract.AUTHORITY), capture(batch)) } returns arrayOf(ContentProviderResult(1))

        val result = repo(cr).markContactUploaded(ACCOUNT_NAME, "/x.vcf", "\"new-etag\"")
        assertTrue(result.isSuccess)

        val op = batch.captured.single()
        assertTrue("marks the row in place, an update not an insert", op.isUpdate)
        val v = valuesOf(op)
        assertEquals("SYNC2 = the server's post-PUT etag", "\"new-etag\"", v.getAsString(RawContacts.SYNC2))
        assertEquals("DIRTY cleared so the adapter's own write is not re-detected", 0, v.getAsInteger(RawContacts.DIRTY))
        // Targets the resolved RawContact _ID through a sync-adapter, account-scoped URI;
        // sync-adapter mode stops the provider re-flagging DIRTY on our write.
        assertEquals(88L, ContentUris.parseId(op.uri))
        assertEquals("true", op.uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
        assertEquals(ACCOUNT_NAME, op.uri.getQueryParameter(RawContacts.ACCOUNT_NAME))
        assertEquals(ACCOUNT_TYPE, op.uri.getQueryParameter(RawContacts.ACCOUNT_TYPE))
    }

    @Test
    fun `markContactUploaded moves the contact to the href the server redirected it to`() = runBlocking {
        val cr = resolverFor(rawContactId = 88L)
        val batch = slot<ArrayList<ContentProviderOperation>>()
        every { cr.applyBatch(eq(ContactsContract.AUTHORITY), capture(batch)) } returns arrayOf(ContentProviderResult(1))

        repo(cr).markContactUploaded(ACCOUNT_NAME, "/x.vcf", "\"e2\"", newHref = "/moved/x.vcf")

        val v = valuesOf(batch.captured.single())
        assertEquals("/moved/x.vcf", v.getAsString(RawContacts.SOURCE_ID))
        assertEquals("\"e2\"", v.getAsString(RawContacts.SYNC2))
    }

    @Test
    fun `markContactUploaded without a new href leaves SOURCE_ID alone`() = runBlocking {
        val cr = resolverFor(rawContactId = 88L)
        val batch = slot<ArrayList<ContentProviderOperation>>()
        every { cr.applyBatch(eq(ContactsContract.AUTHORITY), capture(batch)) } returns arrayOf(ContentProviderResult(1))

        repo(cr).markContactUploaded(ACCOUNT_NAME, "/x.vcf", "\"e2\"")

        assertFalse(valuesOf(batch.captured.single()).containsKey(RawContacts.SOURCE_ID))
    }

    @Test
    fun `markContactUploaded resolve query is scoped by account name, type, and source id`() = runBlocking {
        val selection = slot<String>()
        val args = slot<Array<String>>()
        val cr = mockk<ContentResolver>()
        every { cr.query(any(), any(), capture(selection), capture(args), any()) } returns
            MatrixCursor(arrayOf(RawContacts._ID, RawContacts.SYNC4)).apply { addRow(arrayOf<Any?>(5L, 0)) }
        every { cr.applyBatch(any(), any()) } returns arrayOf(ContentProviderResult(1))

        repo(cr).markContactUploaded(ACCOUNT_NAME, "/x.vcf", "\"e\"")

        assertTrue("scoped by ACCOUNT_NAME", selection.captured.contains(RawContacts.ACCOUNT_NAME))
        assertTrue("scoped by ACCOUNT_TYPE", selection.captured.contains(RawContacts.ACCOUNT_TYPE))
        assertTrue("resolved by SOURCE_ID", selection.captured.contains(RawContacts.SOURCE_ID))
        assertTrue("account name bound", args.captured.contains(ACCOUNT_NAME))
        assertTrue("account type bound", args.captured.contains(ACCOUNT_TYPE))
        assertTrue("href bound", args.captured.contains("/x.vcf"))
    }

    @Test
    fun `markContactUploaded is a no-op success when the href no longer resolves`() = runBlocking {
        val cr = mockk<ContentResolver>()
        every { cr.query(any(), any(), any(), any(), any()) } returns
            MatrixCursor(arrayOf(RawContacts._ID, RawContacts.SYNC4)) // empty
        val result = repo(cr).markContactUploaded(ACCOUNT_NAME, "/gone.vcf", "\"e\"")
        assertTrue("a vanished href is a no-op success", result.isSuccess)
        verify(exactly = 0) { cr.applyBatch(any(), any()) }
    }

    @Test
    fun `markContactUploaded treats a short applyBatch result as failure, never a swallowed success`() = runBlocking {
        // The provider dropped the op mid-batch: the result array is shorter than the ops
        // submitted. Per-op counts can lie, but a short array reliably means the write
        // didn't fully apply, so it must be a failure.
        val cr = resolverFor(rawContactId = 1L)
        every { cr.applyBatch(any(), any()) } returns emptyArray()
        val result = repo(cr).markContactUploaded(ACCOUNT_NAME, "/x.vcf", "\"e\"")
        assertTrue("a short applyBatch result must be a Result.failure", result.isFailure)
    }

    @Test
    fun `markContactUploaded classifies a SecurityException without leaking a message`() = runBlocking {
        val cr = resolverFor(rawContactId = 1L)
        every { cr.applyBatch(any(), any()) } throws SecurityException("WRITE_CONTACTS revoked for /secret.vcf")
        val result = repo(cr).markContactUploaded(ACCOUNT_NAME, "/x.vcf", "\"e\"")
        assertTrue(result.isFailure)
        val cause = result.exceptionOrNull()
        assertTrue("failure is a typed enum, not the raw provider exception", cause is ContactWriteException)
        assertEquals(ContactWriteFailure.PERMISSION_DENIED, (cause as ContactWriteException).failure)
        // PII the provider put in its message must never reach the failure.
        assertFalse("no href/PII in the failure", cause.message.orEmpty().contains("/secret.vcf"))
    }

    // ---------- markNewContactUploaded (net-new create: stamp SOURCE_ID by _ID) ----------

    @Test
    fun `markNewContactUploaded stamps SOURCE_ID and etag and clears DIRTY on the given _ID via a sync-adapter write`() = runBlocking {
        // A net-new device contact has a blank SOURCE_ID, so the write-back is keyed by the
        // provider _ID with no SOURCE_ID lookup (this mock stubs no query).
        val cr = mockk<ContentResolver>()
        val batch = slot<ArrayList<ContentProviderOperation>>()
        every { cr.applyBatch(eq(ContactsContract.AUTHORITY), capture(batch)) } returns arrayOf(ContentProviderResult(1))

        val result = repo(cr).markNewContactUploaded(
            ACCOUNT_NAME, localId = 77L, href = "/ab/default/uid1.vcf", newEtag = "\"srv-1\"",
        )
        assertTrue(result.isSuccess)

        val op = batch.captured.single()
        assertTrue("stamps the originating row in place, an update not an insert", op.isUpdate)
        val v = valuesOf(op)
        assertEquals("SOURCE_ID = the created server href", "/ab/default/uid1.vcf", v.getAsString(RawContacts.SOURCE_ID))
        assertEquals("SYNC2 = the server's post-create etag", "\"srv-1\"", v.getAsString(RawContacts.SYNC2))
        assertEquals("DIRTY cleared so the adapter's own write is not re-detected", 0, v.getAsInteger(RawContacts.DIRTY))
        // Targets the originating RawContact by its provider _ID (the href was blank) through
        // a sync-adapter, account-scoped URI: the only way to reach a row with no SOURCE_ID.
        assertEquals(77L, ContentUris.parseId(op.uri))
        assertEquals("true", op.uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
        assertEquals(ACCOUNT_NAME, op.uri.getQueryParameter(RawContacts.ACCOUNT_NAME))
        assertEquals(ACCOUNT_TYPE, op.uri.getQueryParameter(RawContacts.ACCOUNT_TYPE))
    }

    @Test
    fun `markNewContactUploaded resolves nothing and is a no-op success when the local id is unset`() = runBlocking {
        // An unset _ID (0L) must never write blind: no query, no applyBatch.
        val cr = mockk<ContentResolver>()
        val result = repo(cr).markNewContactUploaded(ACCOUNT_NAME, localId = 0L, href = "/x.vcf", newEtag = "\"e\"")
        assertTrue("an unset local id is a no-op success", result.isSuccess)
        verify(exactly = 0) { cr.query(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { cr.applyBatch(any(), any()) }
    }

    // ---------- assignContactUid (pre-create UID: stamp SYNC1 by _ID, keep DIRTY) ----------

    @Test
    fun `assignContactUid persists the synthesized UID on SYNC1 by _ID and KEEPS DIRTY set via a sync-adapter write`() = runBlocking {
        // A device-created contact has no UID. Before its first PUT the push generates a
        // unique one and persists it to SYNC1, so a retry on a later run reuses the same UID,
        // and with it the same resource name, instead of a new one; the UID-based name is
        // what stops two devices colliding. DIRTY must stay set: the row is still pending.
        val cr = mockk<ContentResolver>()
        val batch = slot<ArrayList<ContentProviderOperation>>()
        every { cr.applyBatch(eq(ContactsContract.AUTHORITY), capture(batch)) } returns arrayOf(ContentProviderResult(1))

        val result = repo(cr).assignContactUid(
            ACCOUNT_NAME, localId = 88L, uid = "11111111-2222-3333-4444-555555555555",
        )
        assertTrue(result.isSuccess)

        val op = batch.captured.single()
        assertTrue("stamps the originating row in place, an update not an insert", op.isUpdate)
        val v = valuesOf(op)
        assertEquals("SYNC1 = the synthesized UID", "11111111-2222-3333-4444-555555555555", v.getAsString(RawContacts.SYNC1))
        assertEquals(
            "DIRTY stays SET — the row is still pending its first upload, unlike a post-upload write-back",
            1,
            v.getAsInteger(RawContacts.DIRTY),
        )
        // Targets the originating RawContact by its provider _ID (SOURCE_ID is still blank)
        // through a sync-adapter, account-scoped URI.
        assertEquals(88L, ContentUris.parseId(op.uri))
        assertEquals("true", op.uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
        assertEquals(ACCOUNT_NAME, op.uri.getQueryParameter(RawContacts.ACCOUNT_NAME))
        assertEquals(ACCOUNT_TYPE, op.uri.getQueryParameter(RawContacts.ACCOUNT_TYPE))
    }

    @Test
    fun `assignContactUid is a no-op success when the local id is unset`() = runBlocking {
        // An unset _ID (0L) must never write blind: no query, no applyBatch.
        val cr = mockk<ContentResolver>()
        val result = repo(cr).assignContactUid(ACCOUNT_NAME, localId = 0L, uid = "any")
        assertTrue("an unset local id is a no-op success", result.isSuccess)
        verify(exactly = 0) { cr.query(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { cr.applyBatch(any(), any()) }
    }

    @Test
    fun `assignContactUid classifies a SecurityException as failure without leaking a message`() = runBlocking {
        val cr = mockk<ContentResolver>()
        every { cr.applyBatch(any(), any()) } throws SecurityException("WRITE_CONTACTS revoked")
        val result = repo(cr).assignContactUid(ACCOUNT_NAME, localId = 5L, uid = "u")
        assertTrue("a provider failure returns Result.failure, not a crash", result.isFailure)
    }

    // ---------- hardDeleteTombstone (hard delete a DELETED row) ----------

    @Test
    fun `hardDeleteTombstone hard-deletes the tombstoned row via a sync-adapter URI`() = runBlocking {
        val cr = tombstoneResolver(rawContactId = 55L)
        val batch = slot<ArrayList<ContentProviderOperation>>()
        every { cr.applyBatch(any(), capture(batch)) } returns arrayOf(ContentProviderResult(1))

        val result = repo(cr).hardDeleteTombstone(ACCOUNT_NAME, "/gone.vcf")
        assertTrue(result.isSuccess)

        val op = batch.captured.single()
        assertTrue("a hard delete", op.isDelete)
        assertEquals(55L, ContentUris.parseId(op.uri))
        // A sync-adapter delete is a hard delete; a non-adapter delete only sets DELETED.
        assertEquals("true", op.uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
        assertEquals(ACCOUNT_NAME, op.uri.getQueryParameter(RawContacts.ACCOUNT_NAME))
        assertEquals(ACCOUNT_TYPE, op.uri.getQueryParameter(RawContacts.ACCOUNT_TYPE))
    }

    @Test
    fun `hardDeleteTombstone resolves only DELETED rows and is a no-op when none matches`() = runBlocking {
        val selection = slot<String>()
        val cr = mockk<ContentResolver>()
        every { cr.query(any(), any(), capture(selection), any(), any()) } returns
            MatrixCursor(arrayOf(RawContacts._ID)) // no tombstone matches
        val result = repo(cr).hardDeleteTombstone(ACCOUNT_NAME, "/live.vcf")

        assertTrue("no tombstone -> no-op success (never hard-delete a live row)", result.isSuccess)
        assertTrue("resolve constrained to DELETED rows", selection.captured.contains(RawContacts.DELETED))
        verify(exactly = 0) { cr.applyBatch(any(), any()) }
    }

    // ---------- restoreTombstone (revert a local delete) ----------

    @Test
    fun `restoreTombstone clears DELETED and DIRTY so the next pull re-downloads`() = runBlocking {
        val cr = tombstoneResolver(rawContactId = 9L)
        val batch = slot<ArrayList<ContentProviderOperation>>()
        every { cr.applyBatch(any(), capture(batch)) } returns arrayOf(ContentProviderResult(1))

        val result = repo(cr).restoreTombstone(ACCOUNT_NAME, "/keep.vcf")
        assertTrue(result.isSuccess)

        val op = batch.captured.single()
        assertTrue(op.isUpdate)
        val v = valuesOf(op)
        assertEquals("DELETED cleared -> row is live again", 0, v.getAsInteger(RawContacts.DELETED))
        // DIRTY is cleared too: left set, the restored row would reappear as a pending edit
        // on the next scan.
        assertEquals("DIRTY cleared so the restore is not re-detected as an edit", 0, v.getAsInteger(RawContacts.DIRTY))
        assertEquals(9L, ContentUris.parseId(op.uri))
        assertEquals("true", op.uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
    }

    @Test
    fun `restoreTombstone is a no-op success when no tombstone resolves`() = runBlocking {
        val cr = mockk<ContentResolver>()
        every { cr.query(any(), any(), any(), any(), any()) } returns MatrixCursor(arrayOf(RawContacts._ID))
        val result = repo(cr).restoreTombstone(ACCOUNT_NAME, "/gone.vcf")
        assertTrue(result.isSuccess)
        verify(exactly = 0) { cr.applyBatch(any(), any()) }
    }

    /**
     * Builds a resolver whose every query returns one row with [rawContactId] and SYNC4 = 0,
     * so a write-back finds its target and reaches applyBatch (which the caller stubs).
     */
    private fun resolverFor(rawContactId: Long): ContentResolver {
        val cr = mockk<ContentResolver>()
        every { cr.query(any(), any(), any(), any(), any()) } returns
            MatrixCursor(arrayOf(RawContacts._ID, RawContacts.SYNC4)).apply {
                addRow(arrayOf<Any?>(rawContactId, 0))
            }
        return cr
    }

    /** Builds a resolver whose every query returns one `_ID` row, the tombstone to act on. */
    private fun tombstoneResolver(rawContactId: Long): ContentResolver {
        val cr = mockk<ContentResolver>()
        every { cr.query(any(), any(), any(), any(), any()) } returns
            MatrixCursor(arrayOf(RawContacts._ID)).apply { addRow(arrayOf<Any?>(rawContactId)) }
        return cr
    }

    /**
     * Builds a resolver whose every query returns one row with [rawContactId] and
     * [currentFlags] as SYNC4, so the photo write finds its target and reaches applyBatch
     * (which the caller stubs).
     */
    private fun photoResolver(rawContactId: Long, currentFlags: Int): ContentResolver {
        val cr = mockk<ContentResolver>()
        every { cr.query(any(), any(), any(), any(), any()) } returns
            MatrixCursor(arrayOf(RawContacts._ID, RawContacts.SYNC4)).apply {
                addRow(arrayOf<Any?>(rawContactId, currentFlags))
            }
        return cr
    }

    private companion object {
        const val ACCOUNT_NAME = "alice@example.test"
        val ACCOUNT_TYPE = KashCalContactsAuthenticator.ACCOUNT_TYPE
        val FLAG_PHOTO_PENDING = AndroidContactsProviderRepository.FLAG_PHOTO_PENDING
    }
}
