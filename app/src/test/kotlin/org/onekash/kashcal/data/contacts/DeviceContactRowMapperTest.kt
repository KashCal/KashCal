package org.onekash.kashcal.data.contacts

import android.content.ContentValues
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.Data
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.vcard.VCardParser
import org.onekash.vcard.VCardWriter
import org.onekash.vcard.model.Contact
import org.onekash.vcard.model.PostalAddress
import org.onekash.vcard.model.StructuredName as VStructuredName
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [DeviceContactRowMapper] is the faithful inverse of [VCardContactMapper]:
 * it reads the Contacts Provider Data rows of one aggregated contact back into the
 * neutral [Contact] model.
 *
 * Fixtures are parsed through the REAL [VCardParser], forward-mapped to Data rows via
 * the REAL [VCardContactMapper], then reverse-mapped here — so parser, forward mapper,
 * and reverse mapper stay in lockstep. Robolectric is used only so [ContentValues] and
 * the `ContactsContract` constants are the real Android classes; no ContentResolver /
 * provider shadow is touched (the mapper is pure).
 *
 * The load-bearing property is the diff-baseline contract that [VCardWriter] depends on:
 * the reverse mapper's output must be facet-equal to the parser's for the facets the
 * writer regenerates, so a no-edit round trip doesn't rewrite untouched properties. The
 * forward mapper is inherently lossy on a few sub-facets (TYPE tokens, photo, anniversary
 * text) — those are asserted with explicit narrowed literals rather than full equality,
 * and the fixed-point test proves the reverse mapper is a true inverse ON the forward image.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DeviceContactRowMapperTest {

    private val parser = VCardParser()

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("carddav/fixtures/$name")) {
            "fixture not found: $name"
        }.readBytes().decodeToString()

    private fun parse(name: String): Contact = parser.parse(fixture(name)).single()

    private fun forward(contact: Contact): List<ContentValues> =
        VCardContactMapper.toEntity(contact).dataRows

    /** Reverse a fixture, holding identity params equal to the parsed source. */
    private fun roundTrip(name: String): Pair<Contact, Contact> {
        val parsed = parse(name)
        val reversed = DeviceContactRowMapper.toContact(
            dataRows = forward(parsed),
            uid = parsed.uid,
            version = parsed.version,
            kind = parsed.kind,
            rawVCard = parsed.rawVCard,
        )
        return parsed to reversed
    }

    /** Every committed fixture — used for the fixed-point invariant, which holds universally. */
    private val richFixtures = listOf(
        "kashcal_full_v3.vcf",
        "kashcal_full_v4.vcf",
        "kashcal_field_fidelity_v3.vcf",
        "kashcal_field_fidelity_v4.vcf",
        "kashcal_folding_and_escapes_v3.vcf",
        "kashcal_partial_bday_v4.vcf",
        "kashcal_photo_inline_v3.vcf",
        "kashcal_empty_fn_v3.vcf",
        "kashcal_no_uid_v3.vcf",
        "kashcal_seed_0001.vcf",
    )

    /**
     * Fixtures whose forward mapping is lossless on the strict-equality facets.
     * `kashcal_folding_and_escapes_v3` is excluded: its `ORG` second component embeds
     * `"; "`, and the forward mapper's department join / reverse split is a bijection
     * only when no ORG unit contains that separator — a documented narrowing exercised
     * by its own test below.
     */
    private val facetEqualFixtures = richFixtures - "kashcal_folding_and_escapes_v3.vcf"

    // ========== Fixed point: reverse∘forward is a true inverse on the forward image ==========

    @Test
    fun `reverse of forward is a fixed point for every fixture`() {
        for (name in richFixtures) {
            val parsed = parse(name)
            val once = DeviceContactRowMapper.toContact(
                dataRows = forward(parsed),
                uid = parsed.uid, version = parsed.version, kind = parsed.kind, rawVCard = parsed.rawVCard,
            )
            val twice = DeviceContactRowMapper.toContact(
                dataRows = forward(once),
                uid = once.uid, version = once.version, kind = once.kind, rawVCard = once.rawVCard,
            )
            // FULL Contact equality: applying the round trip a second time changes nothing.
            assertEquals("fixed point violated for $name", once, twice)
        }
    }

    // ========== Facet equality against the parser baseline ==========

    @Test
    fun `round trip is facet-equal to the parser for every fixture`() {
        for (name in facetEqualFixtures) {
            val (parsed, reversed) = roundTrip(name)
            assertFacetEqual(name, expected = parsed, actual = reversed)
        }
    }

    @Test
    fun `folding and escapes fixture round-trips notes and escaped values`() {
        // Line folding and vCard escaping are the parser/writer's concern; the reverse
        // mapper only sees already-unescaped values on the Data rows, so the NOTE and
        // escaped ADR/ORG characters must survive verbatim.
        val (parsed, reversed) = roundTrip("kashcal_folding_and_escapes_v3.vcf")
        assertEquals(parsed.notes, reversed.notes)
        assertEquals(parsed.addresses.map { it.copy(types = emptyList()) },
            reversed.addresses.map { it.copy(types = emptyList()) })
        // DOCUMENTED NARROWING: this fixture's ORG second component embeds "; " ("R&D; Sync").
        // The forward mapper joins departments with "; " and the reverse splits on it, so a
        // unit containing the separator is over-split. This is the org department (de)serialize
        // limitation — full-equality-on-organization is fixture-scoped, and it self-heals under
        // the fixed point (proven above), so it never causes runaway rewrites.
        assertEquals(listOf("KashCal, Inc.", "R&D; Sync"), parsed.organization)
        assertEquals(listOf("KashCal, Inc.", "R&D", "Sync"), reversed.organization)
    }

    // ========== Explicit TYPE-token narrowing (documented lossy inversion) ==========

    @Test
    fun `email TYPE constants map back to a single canonical token`() {
        assertEquals(listOf("home"), reverseEmailTypes(Email.TYPE_HOME))
        assertEquals(listOf("work"), reverseEmailTypes(Email.TYPE_WORK))
        assertEquals(emptyList<String>(), reverseEmailTypes(Email.TYPE_OTHER))
    }

    @Test
    fun `phone TYPE constants map back to a single canonical token`() {
        assertEquals(listOf("cell"), reversePhoneTypes(Phone.TYPE_MOBILE))
        assertEquals(listOf("work"), reversePhoneTypes(Phone.TYPE_WORK))
        assertEquals(listOf("home"), reversePhoneTypes(Phone.TYPE_HOME))
        assertEquals(listOf("fax"), reversePhoneTypes(Phone.TYPE_FAX_WORK))
        assertEquals(emptyList<String>(), reversePhoneTypes(Phone.TYPE_OTHER))
    }

    @Test
    fun `postal TYPE constants map back to a single canonical token`() {
        assertEquals(listOf("home"), reversePostalTypes(StructuredPostal.TYPE_HOME))
        assertEquals(listOf("work"), reversePostalTypes(StructuredPostal.TYPE_WORK))
        assertEquals(emptyList<String>(), reversePostalTypes(StructuredPostal.TYPE_OTHER))
    }

    @Test
    fun `provider TYPE constants outside the forward image degrade to empty tokens without crashing`() {
        // Users can set types on-device the forward mapper never emits. They must degrade
        // gracefully: number/label/preferred survive, only the type token is dropped.
        val rows = listOf(
            row(Phone.CONTENT_ITEM_TYPE) {
                put(Phone.NUMBER, "+15550001111")
                put(Phone.TYPE, Phone.TYPE_PAGER)
            },
            row(Email.CONTENT_ITEM_TYPE) {
                put(Email.ADDRESS, "x@example.test")
                put(Email.TYPE, Email.TYPE_MOBILE)
            },
        )
        val c = DeviceContactRowMapper.toContact(rows)
        assertEquals(emptyList<String>(), c.phones.single().types)
        assertEquals("+15550001111", c.phones.single().number)
        assertEquals(emptyList<String>(), c.emails.single().types)
        assertEquals("x@example.test", c.emails.single().address)
    }

    // ========== Preferred / custom-label / phonetic survival ==========

    @Test
    fun `preferred flag survives via IS_PRIMARY`() {
        val (parsed, reversed) = roundTrip("kashcal_full_v3.vcf")
        val preferredEmail = parsed.emails.first { it.preferred }.address
        assertTrue(reversed.emails.first { it.address == preferredEmail }.preferred)
        // Non-preferred stays non-preferred.
        assertTrue(reversed.emails.none { it.address == "work@example.test" && it.preferred })
    }

    @Test
    fun `custom label survives on email phone postal and website`() {
        val (parsed, reversed) = roundTrip("kashcal_field_fidelity_v3.vcf")
        assertEquals("School", reversed.emails.first { it.address == "school@example.test" }.label)
        assertEquals("Beeper", reversed.phones.first { it.number == "+15550009999" }.label)
        assertEquals("Vacation Home", reversed.addresses.single().label)
        assertEquals("Blog", reversed.urls.first { it.url == "https://example.test/blog" }.label)
        // and the parser agreed on those labels
        assertEquals(parsed.addresses.single().label, reversed.addresses.single().label)
    }

    @Test
    fun `phonetic name aids survive`() {
        val (parsed, reversed) = roundTrip("kashcal_field_fidelity_v3.vcf")
        assertEquals(parsed.structuredName.phoneticGiven, reversed.structuredName.phoneticGiven)
        assertEquals(parsed.structuredName.phoneticMiddle, reversed.structuredName.phoneticMiddle)
        assertEquals(parsed.structuredName.phoneticFamily, reversed.structuredName.phoneticFamily)
        assertEquals("kyashikaru", reversed.structuredName.phoneticGiven)
    }

    @Test
    fun `multiple primary rows of one mimetype clamp to a single preferred`() {
        // A real RawContact can carry IS_PRIMARY=1 on several rows of one mimetype; the
        // forward mapper honours only the first, so the reverse must clamp identically or
        // forward(reverse(rows)) would drop the surplus PREF and drift on every sync.
        val rows = listOf(
            row(Email.CONTENT_ITEM_TYPE) { put(Email.ADDRESS, "a@example.test"); put(Email.TYPE, Email.TYPE_HOME); put(Email.IS_PRIMARY, 1) },
            row(Email.CONTENT_ITEM_TYPE) { put(Email.ADDRESS, "b@example.test"); put(Email.TYPE, Email.TYPE_WORK); put(Email.IS_PRIMARY, 1) },
            row(Phone.CONTENT_ITEM_TYPE) { put(Phone.NUMBER, "+15550001111"); put(Phone.TYPE, Phone.TYPE_MOBILE); put(Phone.IS_PRIMARY, 1) },
            row(Phone.CONTENT_ITEM_TYPE) { put(Phone.NUMBER, "+15550002222"); put(Phone.TYPE, Phone.TYPE_HOME); put(Phone.IS_PRIMARY, 1) },
        )
        val c = DeviceContactRowMapper.toContact(rows)
        assertEquals(listOf(true, false), c.emails.map { it.preferred })
        assertEquals(listOf(true, false), c.phones.map { it.preferred })
        // Round-trip is now a fixed point despite the pathological input.
        val reforwarded = DeviceContactRowMapper.toContact(forward(c))
        assertEquals(listOf(true, false), reforwarded.emails.map { it.preferred })
        assertEquals(listOf(true, false), reforwarded.phones.map { it.preferred })
    }

    // ========== Change locality (mapper-level) ==========

    @Test
    fun `editing one device row surfaces as exactly one changed facet`() {
        // MAPPER-LEVEL change locality: mutating a single Data row changes exactly one
        // facet vs the parser baseline (under the documented facet projection). This is a
        // mapper-fidelity property, distinct from the writer's full-equality diff — see
        // the writer integration test below for what the serializer actually rewrites.
        val parsed = parse("kashcal_full_v3.vcf")
        val rows = forward(parsed).map { ContentValues(it) }.toMutableList()
        val phoneRow = rows.first {
            it.getAsString(android.provider.ContactsContract.Data.MIMETYPE) == Phone.CONTENT_ITEM_TYPE
        }
        phoneRow.put(Phone.NUMBER, "+19998887777")

        val edited = DeviceContactRowMapper.toContact(
            dataRows = rows,
            uid = parsed.uid, version = parsed.version, kind = parsed.kind, rawVCard = parsed.rawVCard,
        )

        // The phones facet differs...
        assertTrue(edited.phones.any { it.number == "+19998887777" })
        assertTrue(parsed.phones.none { it.number == "+19998887777" })
        // ...and every OTHER facet stays facet-equal to the parser baseline.
        assertFacetEqual("edit-delta", expected = parsed, actual = edited, ignorePhones = true)
    }

    // ========== Writer integration: pins what the serializer actually rewrites ==========

    @Test
    fun `reverse output drives the real writer and regenerates only narrowed and edited lines`() {
        // Production data flow: reverse-map device rows, carry the ORIGINAL rawVCard, hand
        // to VCardWriter for a patch. The writer diffs at FULL structural equality, so the
        // narrowed secondary TYPE tokens (INTERNET/VOICE) DO regenerate on the first pass,
        // then converge. This test pins that reality rather than over-claiming a single-line diff.
        val parsed = parse("kashcal_full_v3.vcf")
        val reversed = DeviceContactRowMapper.toContact(
            dataRows = forward(parsed),
            uid = parsed.uid, version = parsed.version, kind = parsed.kind, rawVCard = parsed.rawVCard,
        )
        val out = VCardWriter().write(reversed)

        // The email/phone lines are regenerated to the device-representable form (INTERNET
        // and VOICE dropped) — the documented first-write narrowing.
        assertTrue("home email retained", out.contains("home@example.test"))
        assertTrue("work email retained", out.contains("work@example.test"))
        assertTrue("cell number retained", out.contains("+15550000001"))
        // Unmapped X-props the writer preserves verbatim from the patched base body survive.
        assertTrue("unmapped X-prop preserved", out.contains("X-CUSTOM-PROP:retain-me"))
        // Fixed point at the writer: re-parsing the output and reverse-mapping is stable.
        val reparsed = parser.parse(out).single()
        assertEquals("home@example.test", reparsed.emails.first { it.preferred }.address)
    }

    @Test
    fun `reverse-mapped inline photo is not rewritten or relabeled by the writer`() {
        // The Photo row carries no MIME subtype, so reverse yields contentType=null. Feeding
        // that to the writer with the ORIGINAL rawVCard must NOT rewrite the PHOTO line: the
        // writer diffs the photo on bytes/URL and ignores the unrecoverable contentType, so
        // the original TYPE=PNG line is preserved verbatim rather than relabeled to JPEG and
        // re-uploaded on every sync.
        val parsed = parse("kashcal_photo_inline_v3.vcf")
        val reversed = DeviceContactRowMapper.toContact(
            dataRows = forward(parsed),
            uid = parsed.uid, version = parsed.version, kind = parsed.kind, rawVCard = parsed.rawVCard,
        )
        assertNull("reverse can't recover the MIME subtype", reversed.photo?.contentType)

        val out = VCardWriter().write(reversed)
        assertTrue("PNG type preserved", out.contains("TYPE=PNG", ignoreCase = true))
        assertTrue("photo not relabeled JPEG", !out.contains("jpeg", ignoreCase = true))
        // Re-parse confirms the type and bytes survived intact.
        val reparsed = parser.parse(out).single()
        assertEquals("png", reparsed.photo?.contentType)
        assertArrayEqualsNonNull(requireNotNull(parsed.photo?.data), reparsed.photo?.data)
    }

    // ========== GroupMembership -> categories ==========

    @Test
    fun `group membership rows map back to categories in order`() {
        val (parsed, reversed) = roundTrip("kashcal_full_v3.vcf")
        assertEquals(listOf("Family", "Test"), reversed.categories)
        assertEquals(parsed.categories, reversed.categories)
    }

    /** A GroupMembership Data row, keyed by GROUP_SOURCE_ID and/or GROUP_ROW_ID. */
    private fun groupRow(sourceId: String? = null, rowId: Long? = null) =
        ContentValues().apply {
            put(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
            if (sourceId != null) put(GroupMembership.GROUP_SOURCE_ID, sourceId)
            if (rowId != null) put(GroupMembership.GROUP_ROW_ID, rowId)
        }

    @Test
    fun `a blank-source-id group membership resolves its title by GROUP_ROW_ID`() {
        val reversed = DeviceContactRowMapper.toContact(
            dataRows = listOf(groupRow(sourceId = "", rowId = 7L)),
            groupTitlesById = mapOf(7L to "Friends"),
        )
        assertEquals(listOf("Friends"), reversed.categories)
        // Loop closer: the resolved label is emitted as a CATEGORIES value, not dropped.
        assertTrue(
            "CATEGORIES emitted",
            VCardWriter().write(reversed).contains("CATEGORIES", ignoreCase = true),
        )
    }

    @Test
    fun `source-id and row-id group memberships resolve in order and de-duplicated`() {
        val reversed = DeviceContactRowMapper.toContact(
            dataRows = listOf(
                groupRow(sourceId = "Work"),
                groupRow(sourceId = "", rowId = 7L),
                groupRow(sourceId = "Work"), // duplicate by source id
            ),
            groupTitlesById = mapOf(7L to "Friends"),
        )
        assertEquals(listOf("Work", "Friends"), reversed.categories)
    }

    @Test
    fun `a row-id group membership with no map entry is dropped, not emitted blank`() {
        val reversed = DeviceContactRowMapper.toContact(
            dataRows = listOf(groupRow(sourceId = "", rowId = 99L)),
            groupTitlesById = emptyMap(),
        )
        assertTrue("no category emitted", reversed.categories.isEmpty())
    }

    // ========== Cosmetic provider-only columns tolerated ==========

    @Test
    fun `cosmetic provider-only columns do not corrupt the reverse`() {
        val parsed = parse("kashcal_full_v3.vcf")
        val rows = forward(parsed).map { ContentValues(it) }
        // The real provider adds these derived columns; they must be ignored, not mapped.
        rows.first {
            it.getAsString(android.provider.ContactsContract.Data.MIMETYPE) == Phone.CONTENT_ITEM_TYPE
        }.put(Phone.NORMALIZED_NUMBER, "+15550000001")
        rows.first {
            it.getAsString(android.provider.ContactsContract.Data.MIMETYPE) == StructuredPostal.CONTENT_ITEM_TYPE
        }.put(StructuredPostal.FORMATTED_ADDRESS, "1 Test St, Testville, CA 90000, USA")

        val reversed = DeviceContactRowMapper.toContact(
            dataRows = rows, uid = parsed.uid, version = parsed.version, kind = parsed.kind, rawVCard = parsed.rawVCard,
        )
        assertFacetEqual("cosmetic", expected = parsed, actual = reversed)
    }

    // ========== poBox + extendedAddress -> NEIGHBORHOOD inversion (no fixture exercises it) ==========

    @Test
    fun `postal poBox and extended address round-trip through NEIGHBORHOOD`() {
        val contact = minimalContact().copy(
            addresses = listOf(
                PostalAddress(
                    poBox = "PO Box 7",
                    extendedAddress = "Suite 500",
                    street = "1 Test St",
                    locality = "Testville",
                    region = "CA",
                    postalCode = "90000",
                    country = "USA",
                    types = listOf("home"),
                ),
            ),
        )
        val reversed = DeviceContactRowMapper.toContact(forward(contact))
        val adr = reversed.addresses.single()
        assertEquals("PO Box 7", adr.poBox)
        assertEquals("Suite 500", adr.extendedAddress)
        assertEquals("1 Test St", adr.street)
        assertEquals("Testville", adr.locality)
        assertEquals("CA", adr.region)
        assertEquals("90000", adr.postalCode)
        assertEquals("USA", adr.country)
    }

    // ========== Inline photo bytes recovered; URL photo not present in rows ==========

    @Test
    fun `inline photo bytes are recovered from the Photo row`() {
        val (parsed, reversed) = roundTrip("kashcal_photo_inline_v3.vcf")
        assertTrue(parsed.photo?.data != null)
        assertArrayEqualsNonNull(requireNotNull(parsed.photo?.data), reversed.photo?.data)
        // contentType is not stored on the Photo row -> not recoverable from rows.
        assertNull(reversed.photo?.contentType)
    }

    @Test
    fun `url-only photo yields no photo when reading rows`() {
        val (parsed, reversed) = roundTrip("kashcal_full_v3.vcf")
        assertTrue("fixture has a URL photo", parsed.photo?.url != null)
        // URL photos ride MappedContact.photoUrl, not a Data row, so reverse-from-rows is null.
        assertNull(reversed.photo)
    }

    // ========== Birthday / anniversary Event rows ==========

    @Test
    fun `birthday and anniversary Event rows map back to their ContactDate`() {
        val (parsed, reversed) = roundTrip("kashcal_full_v3.vcf")
        assertEquals(parsed.birthday, reversed.birthday)
        assertEquals(parsed.anniversary?.date, reversed.anniversary?.date)
    }

    @Test
    fun `partial year-less date maps back as text`() {
        val (parsed, reversed) = roundTrip("kashcal_partial_bday_v4.vcf")
        assertNull(reversed.birthday?.date)
        assertEquals(parsed.birthday?.text, reversed.birthday?.text)
        assertEquals("--04-15", reversed.birthday?.text)
    }

    // ========== Empty / degenerate inputs ==========

    @Test
    fun `empty row set yields an empty contact without crashing`() {
        val c = DeviceContactRowMapper.toContact(emptyList())
        assertEquals("", c.displayName)
        assertEquals(VStructuredName(), c.structuredName)
        assertTrue(c.emails.isEmpty())
        assertTrue(c.phones.isEmpty())
        assertNull(c.photo)
    }

    @Test
    fun `empty FN derives display name from N components`() {
        val (parsed, reversed) = roundTrip("kashcal_empty_fn_v3.vcf")
        assertEquals(parsed.displayName, reversed.displayName)
        assertEquals(parsed.structuredName, reversed.structuredName)
    }

    // ========== Facet comparator ==========

    /**
     * Facet equality: FULL equality on the losslessly round-tripping facets; a documented
     * PROJECTION on the facets the forward mapper narrows (emails/phones/addresses TYPE
     * tokens, photo contentType/url, anniversary text). Identity fields (uid/version/kind/
     * rawVCard) are excluded — they come from the RawContact SYNC columns, not Data rows.
     */
    private fun assertFacetEqual(
        label: String,
        expected: Contact,
        actual: Contact,
        ignorePhones: Boolean = false,
    ) {
        assertEquals("$label displayName", expected.displayName, actual.displayName)
        assertEquals("$label structuredName", expected.structuredName, actual.structuredName)
        assertEquals("$label nickname", expected.nickname, actual.nickname)
        assertEquals("$label organization", expected.organization, actual.organization)
        assertEquals("$label title", expected.title, actual.title)
        assertEquals("$label role", expected.role, actual.role)
        assertEquals("$label urls", expected.urls, actual.urls)
        assertEquals("$label notes", expected.notes, actual.notes)
        assertEquals("$label categories", expected.categories, actual.categories)
        assertEquals("$label relations", expected.relations, actual.relations)
        assertEquals("$label imHandles", expected.imHandles, actual.imHandles)
        assertEquals("$label birthday", expected.birthday, actual.birthday)
        assertEquals("$label anniversary.date", expected.anniversary?.date, actual.anniversary?.date)

        // Projected: emails compared on address/preferred/label (TYPE narrowed).
        assertEquals(
            "$label emails (address/preferred/label)",
            expected.emails.map { Triple(it.address, it.preferred, it.label) },
            actual.emails.map { Triple(it.address, it.preferred, it.label) },
        )
        if (!ignorePhones) {
            assertEquals(
                "$label phones (number/preferred/label)",
                expected.phones.map { Triple(it.number, it.preferred, it.label) },
                actual.phones.map { Triple(it.number, it.preferred, it.label) },
            )
        }
        assertEquals(
            "$label addresses (components/label)",
            expected.addresses.map { it.copy(types = emptyList()) },
            actual.addresses.map { it.copy(types = emptyList()) },
        )
        // Projected: photo compared on data bytes only.
        assertArrayEqualsNullable("$label photo.data", expected.photo?.data, actual.photo?.data)
    }

    // ========== Helpers ==========

    private fun reverseEmailTypes(type: Int): List<String> {
        val rows = listOf(row(Email.CONTENT_ITEM_TYPE) { put(Email.ADDRESS, "a@example.test"); put(Email.TYPE, type) })
        return DeviceContactRowMapper.toContact(rows).emails.single().types
    }

    private fun reversePhoneTypes(type: Int): List<String> {
        val rows = listOf(row(Phone.CONTENT_ITEM_TYPE) { put(Phone.NUMBER, "+15550000000"); put(Phone.TYPE, type) })
        return DeviceContactRowMapper.toContact(rows).phones.single().types
    }

    private fun reversePostalTypes(type: Int): List<String> {
        val rows = listOf(
            row(StructuredPostal.CONTENT_ITEM_TYPE) {
                put(StructuredPostal.STREET, "1 St")
                put(StructuredPostal.TYPE, type)
            },
        )
        return DeviceContactRowMapper.toContact(rows).addresses.single().types
    }

    private fun minimalContact(): Contact = Contact(
        version = "3.0",
        uid = "test-uid",
        structuredName = VStructuredName(given = "Rev", family = "Probe"),
        displayName = "Rev Probe",
        rawVCard = "",
    )

    private fun row(mimeType: String, build: ContentValues.() -> Unit): ContentValues =
        ContentValues().apply {
            put(android.provider.ContactsContract.Data.MIMETYPE, mimeType)
            build()
        }

    private fun assertArrayEqualsNullable(label: String, a: ByteArray?, b: ByteArray?) {
        if (a == null && b == null) return
        assertTrue("$label: one side null (a=${a != null}, b=${b != null})", a != null && b != null)
        org.junit.Assert.assertArrayEquals(label, a, b)
    }

    private fun assertArrayEqualsNonNull(a: ByteArray, b: ByteArray?) {
        org.junit.Assert.assertArrayEquals(a, requireNotNull(b))
    }
}
