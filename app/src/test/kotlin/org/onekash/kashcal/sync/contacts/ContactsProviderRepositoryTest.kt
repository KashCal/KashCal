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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Verifies [AndroidContactsProviderRepository] builds the correct
 * [ContentProviderOperation] batch to insert CardDAV-synced contacts into the
 * Android Contacts Provider, and scopes every write/delete to a single login's
 * system account.
 *
 * Robolectric reality (shadows-framework 4.16.1): `ShadowContentResolver`
 * CAPTURES an `applyBatch` but never EXECUTES it when no `ContentProvider` is
 * registered for `com.android.contacts` (there is none in this tree). It stores
 * the ops via `map.put(authority, ops)` — LAST batch wins, not appended — and
 * returns an EMPTY `ContentProviderResult[]`. Two consequences shape these
 * assertions:
 *  - The impl must NOT dereference `results[0]` (an empty array would AIOOBE on
 *    the first insert) — a sync-adapter insert doesn't need the returned id.
 *  - There is no row store to read back, and `getContentProviderOperations`
 *    shows only the LAST batch. So single-batch cases assert on the captured op
 *    list; multi-batch chunking/yield is asserted on the pure [buildBatches]
 *    output directly (its `getUri`/`isYieldAllowed`/`resolveValueBackReferences`
 *    are all public).
 *
 * Fixtures are parsed through the REAL [VCardParser] + mapped through the REAL
 * [VCardContactMapper] (the same committed bodies the mapper suite uses), so the
 * write layer stays in lockstep with the row set the mapper actually emits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ContactsProviderRepositoryTest {

    private val parser = VCardParser()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver: ContentResolver = context.contentResolver

    private fun repo(cr: ContentResolver = resolver) = AndroidContactsProviderRepository(cr)

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("carddav/fixtures/$name")) {
            "fixture not found: $name"
        }.readBytes().decodeToString()

    private fun mapped(name: String): MappedContact =
        VCardContactMapper.toEntity(parser.parse(fixture(name)).single())

    private fun write(name: String, href: String, etag: String? = "\"etag-$href\""): MappedContactWrite =
        MappedContactWrite(href = href, etag = etag, mapped = mapped(name))

    /**
     * Resolve an op's ContentValues, supplying a dummy prior result so a
     * `withValueBackReference(RAW_CONTACT_ID, base)` on a Data row resolves
     * without throwing (ContentUris.parseId needs a numeric id on the back-ref
     * uri). RawContact ops carry no back-ref, so this is a no-op for them.
     */
    private fun valuesOf(op: ContentProviderOperation): ContentValues {
        // Back-references are batch-relative, so a data row can reference any index
        // up to a full batch. Supply a generous result array, every entry id=1L, so
        // ANY back-ref resolves to RAW_CONTACT_ID=1 regardless of the contact's
        // position in the batch.
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

        // First op is the parent RawContact insert.
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
        // Fully isolate mirrored contacts from the user's existing device contacts:
        // never aggregate with a Google/local RawContact, so removing this account
        // (or purging its rows) can never collapse or delete a contact owned by
        // another account that merely shares a phone number or name. SUSPENDED still
        // permits a manual merge and leaves that aggregation link, so DISABLED is the
        // only mode that guarantees our purge touches nothing but our own rows.
        assertEquals(
            "mirrored contacts must not aggregate with any other account",
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

        // Every op's URI must be in sync-adapter mode and account-scoped, not just
        // the ContentValues — the provider reads these off the URI.
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
        // Yielding right after a RawContact insert (before its StructuredName) would
        // let a nameless contact commit if the batch failed partway — forbid it.
        assertTrue(
            "the RawContact insert must never be a yield point",
            ops.filter { it.isRawContactInsert() }.none { it.isYieldAllowed },
        )
    }

    // ---------- byte-bounded chunking (pure builder) ----------

    /**
     * A synthetic contact carrying one StructuredName row and one inline Photo blob
     * of [photoBytes] bytes — so its op-count is tiny (2) but its byte weight is
     * dominated by the blob. This is the shape that trips the Binder transaction
     * limit without ever tripping [AndroidContactsProviderRepository.MAX_OPS_PER_BATCH].
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
        // Each contact is ~400KB of photo; several of them pack well under the 100-op
        // cap, so ONLY a byte ceiling forces a split. Without one, they'd all land in a
        // single applyBatch and trip TransactionTooLargeException at the ~1MB Binder limit.
        val photoBytes = 400 * 1024
        val many = (1..6).map { photoWrite("/p$it.vcf", photoBytes) }
        val batches = repo().buildBatches(ACCOUNT_NAME, many)

        assertTrue("byte weight alone must force more than one batch", batches.size > 1)
        batches.forEach { batch ->
            // A single contact whose own blob exceeds the budget is allowed to occupy a
            // lone batch (a contact is never split); multi-contact batches must fit.
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
        // One contact whose blob alone exceeds the byte budget must still emit as a
        // single, whole batch — the byte ceiling never splits a contact mid-way.
        val huge = photoWrite("/huge.vcf", (AndroidContactsProviderRepository.MAX_BATCH_BYTES + 1).toInt())
        val batches = repo().buildBatches(ACCOUNT_NAME, listOf(huge))

        assertEquals("the whole contact stays in one batch", 1, batches.size)
        // RawContact + StructuredName + Photo, intact — never split by the byte ceiling.
        assertEquals("the whole contact stays intact", 3, batches.single().size)
    }

    // ---------- dedupe pre-filter query ----------

    @Test
    fun `existingSourceIds returns empty and does not crash when the provider yields nothing`() = runBlocking {
        // No provider registered -> query returns null -> graceful empty set. This is
        // the account-scoped pre-filter the pull caller must consult before insert.
        assertTrue(repo().existingSourceIds(ACCOUNT_NAME).isEmpty())
    }

    // ---------- ungrouped-visibility Settings row ----------

    @Test
    fun `ensureContactVisibility inserts a Settings row making ungrouped contacts visible`() = runBlocking {
        val result = repo().ensureContactVisibility(ACCOUNT_NAME)
        assertTrue(result.isSuccess)

        // Contacts under a custom account type with no group membership are hidden
        // by the Contacts Provider unless UNGROUPED_VISIBLE is set on the account's
        // Settings row. Without it the account shows but its contacts never appear.
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
        // The provider keys the Settings row by account; it reads name/type off the
        // ContentValues AND the row must target the Settings collection via a
        // sync-adapter, account-scoped URI.
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
        // full_v3 carries CATEGORIES:Family,Test -> the mapper emits two GroupMembership
        // rows keyed by GROUP_SOURCE_ID; the write layer must first insert a titled Group
        // with that SOURCE_ID so the membership resolves to a named (not blank) group.
        repo().insertContacts(ACCOUNT_NAME, listOf(write("kashcal_full_v3.vcf", "/full.vcf")))

        val groupInserts = shadowOf(resolver).insertStatements.filter {
            it.uri.toString().startsWith(ContactsContract.Groups.CONTENT_URI.toString())
        }
        val titles = groupInserts.map { it.contentValues.getAsString(ContactsContract.Groups.TITLE) }.toSet()
        assertEquals(setOf("Family", "Test"), titles)

        // SOURCE_ID must equal TITLE — it's the key the GroupMembership rows point at.
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
        // A Groups insert that throws must be swallowed: the contact itself still inserts
        // (the provider auto-creates a blank group), so category-group failure is not fatal.
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
        // The provider can surface an IllegalArgumentException (unknown authority/URI)
        // or an arbitrary Binder RuntimeException — neither is one of the three checked
        // types. The "does not throw" contract must still hold: fail the chunk, don't crash.
        val cr = mockk<ContentResolver>()
        every { cr.applyBatch(any(), any()) } throws IllegalArgumentException("Unknown URI")

        val result = repo(cr).insertContacts(ACCOUNT_NAME, listOf(write("kashcal_full_v3.vcf", "/full.vcf")))
        assertTrue("an unchecked runtime must degrade to Result.failure, not propagate", result.isFailure)
    }

    // ---------- deleteByHrefs (server-side deletes + replace first half) ----------

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
        // Every chunk keeps the account+type predicate — a chunk that dropped it
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

    // ---------- replaceContacts (change-as-replace) ----------

    @Test
    fun `replaceContacts updates an existing contact in place, preserving its RawContact id`() = runBlocking {
        // A present href resolves to an existing RawContact _ID; the replace must keep
        // that row (so the aggregate Contact id, starred flag, home-screen shortcut, and
        // lookup key survive) rather than delete+recreate it under a new id.
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
        // No RawContact is INSERTED — the existing row is retained, not recreated.
        assertTrue(
            "an in-place replace inserts no new RawContact (that would churn the _ID)",
            ops.none { it.isInsert && it.isRawContactInsert() },
        )
        // The RawContact row is UPDATED in place, carrying the new server etag on SYNC2.
        val update = ops.first { it.isUpdate }
        assertTrue("the update targets a RawContacts row", update.uri.toString().contains("raw_contacts"))
        assertEquals(
            "the in-place update carries the new etag",
            "\"etag-/p.vcf\"",
            valuesOf(update).getAsString(RawContacts.SYNC2),
        )
        // Old Data rows are cleared, then fresh ones re-inserted against the SAME retained id.
        assertTrue("a Data-row delete clears the stale rows", ops.any { it.isDelete })
        val dataInsert = ops.first { it.isInsert }
        assertEquals(
            "re-inserted Data rows reference the retained RawContact id, not a new one",
            55L,
            valuesOf(dataInsert).getAsLong(Data.RAW_CONTACT_ID),
        )
    }

    @Test
    fun `replaceContacts falls back to a fresh insert when the href has no existing row`() = runBlocking {
        // No existing RawContact resolves for this href (self-heal case): there's
        // nothing to preserve, so it goes through the normal insert path. The real
        // Robolectric resolver's resolve query returns null -> insert branch.
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
        // No provider registered -> query returns null -> graceful empty map. This is
        // the read-back the full pull uses to tell changed from unchanged.
        assertTrue(repo().existingEtagsByHref(ACCOUNT_NAME).isEmpty())
    }

    // ---------- pending-photo query (SYNC4 FLAG_PHOTO_PENDING worklist) ----------

    @Test
    fun `pendingPhotoSourceIds returns empty and does not crash when the provider yields nothing`() = runBlocking {
        // No provider registered -> query returns null -> graceful empty set.
        assertTrue(repo().pendingPhotoSourceIds(ACCOUNT_NAME).isEmpty())
    }

    @Test
    fun `pendingPhotoSourceIds returns only rows whose SYNC4 has the photo-pending bit`() = runBlocking {
        // ShadowContentResolver can't be pre-seeded with RawContact rows, so drive
        // the cursor through a mocked resolver: three rows, only two pending, one
        // with the pending bit among other bits set.
        val cr = mockk<ContentResolver>()
        val cursor = MatrixCursor(arrayOf(RawContacts.SOURCE_ID, RawContacts.SYNC4)).apply {
            addRow(arrayOf<Any?>("/pending.vcf", AndroidContactsProviderRepository.FLAG_PHOTO_PENDING))
            addRow(arrayOf<Any?>("/clean.vcf", 0))
            // pending bit OR an unrelated high bit -> still counts as pending.
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
        // Current SYNC4 = pending bit OR a high bit; the update must AND-NOT only the
        // pending bit, leaving the high bit intact (never zero the column).
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
        // Contact deleted between pull and fetch -> resolve query returns empty ->
        // no applyBatch, still success (nothing to leave pending).
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

    /**
     * A resolver whose RawContact-by-SOURCE_ID lookup returns a single row with the
     * given _ID and SYNC4, so the photo-write path resolves a target and proceeds
     * to applyBatch (which the caller stubs).
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
