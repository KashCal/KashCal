package org.onekash.vcard

import org.junit.jupiter.api.Test
import org.onekash.vcard.model.Contact
import org.onekash.vcard.model.ContactDate
import org.onekash.vcard.model.Email
import org.onekash.vcard.model.ImHandle
import org.onekash.vcard.model.Phone
import org.onekash.vcard.model.Photo
import org.onekash.vcard.model.Relation
import org.onekash.vcard.model.StructuredName
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Serializes the neutral [Contact] model back to vCard text through [VCardWriter],
 * the inverse of [VCardParser]. The load-bearing behavior is round-trip fidelity:
 * a contact parsed from a body, edited in one field, and written back must change
 * ONLY that field and preserve every unmapped property from the original body —
 * the parse -> edit -> write -> re-parse identity that settles whether writing
 * should patch the stored body or regenerate from the lossy model.
 *
 * Whole-[Contact] equality is never asserted directly: a write always produces a
 * fresh [Contact.rawVCard] on re-parse, so comparisons normalize that one field.
 */
class VCardWriterTest {

    private val parser = VCardParser()
    private val writer = VCardWriter()

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "fixture not found: $name"
        }.readBytes().decodeToString()

    private fun parse(name: String): Contact = parser.parse(fixture(name)).single()

    /** Compare two contacts across all mapped facets, ignoring the volatile rawVCard. */
    private fun assertFacetsEqual(expected: Contact, actual: Contact) {
        assertEquals(expected.copy(rawVCard = actual.rawVCard), actual)
    }

    @Test
    fun `editing one phone round-trips and preserves every other mapped facet`() {
        val original = parse("kashcal_full_v3.vcf")
        val edited = original.copy(
            phones = original.phones.mapIndexed { i, p ->
                if (i == 0) p.copy(number = "+15559999999") else p
            },
        )

        val reparsed = parser.parse(writer.write(edited)).single()

        assertEquals("3.0", reparsed.version)
        assertEquals("+15559999999", reparsed.phones.first().number)
        assertFacetsEqual(edited, reparsed)
    }

    @Test
    fun `unmapped X-prop and a grouped Apple label survive an edit`() {
        val original = parse("kashcal_full_v3.vcf")
        val edited = original.copy(
            phones = original.phones.mapIndexed { i, p ->
                if (i == 0) p.copy(number = "+15559999999") else p
            },
        )

        val body = writer.write(edited)

        // The unmapped extended property is carried through verbatim.
        assertContains(body, "X-CUSTOM-PROP:retain-me")
        // The custom label on an UNEDITED grouped email is still recoverable.
        val custom = parser.parse(body).single().emails.single { it.address == "custom@example.test" }
        assertEquals("KashCalCustom", custom.label)
    }

    @Test
    fun `writing an unedited contact is near-identity and retains unmapped properties`() {
        val original = parse("kashcal_full_v3.vcf")

        val body = writer.write(original)
        val reparsed = parser.parse(body).single()

        assertContains(body, "X-CUSTOM-PROP:retain-me")
        assertFacetsEqual(original, reparsed)
    }

    @Test
    fun `from-scratch contact emits a valid card at the requested version`() {
        val scratch = Contact(
            version = "4.0",
            uid = "urn:uuid:scratch-0001",
            structuredName = StructuredName(family = "Scratch", given = "From"),
            displayName = "From Scratch",
            emails = listOf(Email(address = "from@example.test", types = listOf("home"), preferred = true)),
            phones = listOf(Phone(number = "+15551230000", types = listOf("cell"))),
            rawVCard = "",
        )

        val v3 = writer.write(scratch, "3.0")
        val v4 = writer.write(scratch, "4.0")

        assertTrue(v3.lineSequence().any { it.trim() == "VERSION:3.0" }, "3.0 body must carry VERSION:3.0")
        assertTrue(v4.lineSequence().any { it.trim() == "VERSION:4.0" }, "4.0 body must carry VERSION:4.0")

        val r3 = parser.parse(v3).single()
        assertEquals("From Scratch", r3.displayName)
        assertEquals("urn:uuid:scratch-0001", r3.uid)
        assertEquals("from@example.test", r3.emails.single().address)
        assertEquals("+15551230000", r3.phones.single().number)
        assertEquals("Scratch", r3.structuredName.family)
    }

    @Test
    fun `version defaults to the contact's own version when the argument is omitted`() {
        val scratch = Contact(
            version = "4.0",
            uid = "urn:uuid:scratch-0002",
            structuredName = StructuredName(given = "Default"),
            displayName = "Default Version",
            rawVCard = "",
        )

        val body = writer.write(scratch)

        assertTrue(body.lineSequence().any { it.trim() == "VERSION:4.0" })
    }

    @Test
    fun `editing a labeled email keeps its custom label`() {
        val original = parse("kashcal_field_fidelity_v3.vcf")
        val edited = original.copy(
            emails = original.emails.map { it.copy(address = "newschool@example.test") },
        )

        val reparsed = parser.parse(writer.write(edited)).single()
        val email = reparsed.emails.single()

        assertEquals("newschool@example.test", email.address)
        assertEquals("School", email.label)
    }

    @Test
    fun `a nameless from-scratch contact still emits a mandatory non-blank FN`() {
        // A device contact can carry only a phone (no name row at all): the reverse
        // mapper then yields a blank displayName and an all-null StructuredName. FN is
        // mandatory in a vCard (RFC 6350 6.2.1 / RFC 2426 3.1.1) and strict servers
        // reject a card that omits it, so a net-new (generate-mode) write MUST still
        // emit a non-blank FN synthesized from the best available identifier.
        val phoneOnly = Contact(
            version = "3.0",
            uid = "urn:uuid:nameless-0001",
            structuredName = StructuredName(),
            displayName = "",
            phones = listOf(Phone(number = "+15550142", types = listOf("cell"))),
            rawVCard = "",
        )

        val body = writer.write(phoneOnly, "3.0")

        val fnLine = body.lineSequence().firstOrNull { it.startsWith("FN") }
        assertNotNull(fnLine, "a written vCard must carry an FN property (mandatory per RFC 6350 6.2.1)")
        val fnValue = fnLine.substringAfter(":", "").trim()
        assertTrue(fnValue.isNotBlank(), "FN must be non-blank; got '$fnLine'")
        // Re-parsing must succeed and surface the synthesized display name.
        assertEquals(fnValue, parser.parse(body).single().displayName)
    }

    @Test
    fun `a nameless contact emits a structurally-present empty N that round-trips to no name`() {
        // N is optional per RFC 6350 6.2.2, but some servers reject a card that omits the
        // N property entirely while accepting an all-empty N:;;;;. A nameless generate-mode
        // contact must therefore emit a structurally-present N with empty components — no
        // fabricated name — and it must re-parse back to an all-null structured name so the
        // round-trip is unchanged.
        val phoneOnly = Contact(
            version = "3.0",
            uid = "urn:uuid:nameless-emptyn-0001",
            structuredName = StructuredName(),
            displayName = "",
            phones = listOf(Phone(number = "+15550142", types = listOf("cell"))),
            rawVCard = "",
        )

        val body = writer.write(phoneOnly, "3.0")

        val nLine = body.lineSequence().firstOrNull { it.startsWith("N:") || it.startsWith("N;") }
        assertNotNull(nLine, "a nameless card must still carry a structurally-present N property")
        // The N carries no name components — it must not fabricate a name from the phone.
        assertFalse(nLine.contains("15550142"), "empty N must not smuggle the phone into a name component; got '$nLine'")
        val reparsed = parser.parse(body).single()
        assertEquals(StructuredName(), reparsed.structuredName)
    }

    @Test
    fun `a contact with only a display name does not throw and yields one card`() {
        val solo = Contact(
            version = "3.0",
            uid = "",
            structuredName = StructuredName(),
            displayName = "Solo Contact",
            rawVCard = "",
        )

        val body = writer.write(solo)

        assertContains(body, "BEGIN:VCARD")
        assertContains(body, "END:VCARD")
        assertEquals("Solo Contact", parser.parse(body).single().displayName)
    }

    @Test
    fun `editing a 3-0 Apple raw anniversary applies and drops the stale raw idiom`() {
        // The 3.0 fixture stores the anniversary as the Apple raw itemN.X-ABDATE +
        // X-ABLabel form. ANNIVERSARY is a 4.0-only property ez-vcard drops from a 3.0
        // body, so a 3.0 card must carry the edit in that same raw idiom. The edit is
        // APPLIED (not preserved) and the STALE date is gone, so the body carries a
        // single, consistent anniversary rather than a contradictory double-emit.
        val original = parse("kashcal_full_v3.vcf")
        assertEquals(LocalDate.of(2015, 6, 20), original.anniversary?.date)

        val edited = original.copy(anniversary = ContactDate(date = LocalDate.of(2020, 1, 1)))
        val body = writer.write(edited, "3.0")
        val reparsed = parser.parse(body).single()

        assertEquals(LocalDate.of(2020, 1, 1), reparsed.anniversary?.date)
        assertFalse(body.contains("2015-06-20"), "the stale anniversary date must not linger")
        assertEquals(1, body.lineSequence().count { it.contains("X-ABDATE") }, "exactly one anniversary date line")
    }

    @Test
    fun `an unedited Apple raw anniversary is preserved verbatim, not converted`() {
        // Preserve-when-unchanged: an untouched dual-spelling facet keeps its original
        // Apple raw idiom byte-faithful rather than being rewritten as a native property.
        val original = parse("kashcal_full_v3.vcf")

        val body = writer.write(original, "3.0")

        assertContains(body, "X-ABDATE")
        assertEquals(LocalDate.of(2015, 6, 20), parser.parse(body).single().anniversary?.date)
    }

    @Test
    fun `editing an Apple raw relation applies and drops the stale raw idiom`() {
        val original = parse("kashcal_full_v3.vcf")
        assertEquals("KashCal Spouse Probe", original.relations.single().name)

        val edited = original.copy(
            relations = listOf(original.relations.single().copy(name = "New Partner Name")),
        )
        val body = writer.write(edited, "3.0")
        val reparsed = parser.parse(body).single()

        // RELATED is 4.0-only (dropped from a 3.0 body), so the 3.0 relation stays in the
        // Apple raw X-ABRELATEDNAMES idiom — carrying the NEW name, with the stale one gone.
        assertEquals("New Partner Name", reparsed.relations.single().name)
        assertFalse(body.contains("KashCal Spouse Probe"), "the stale relation name must not linger")
    }

    @Test
    fun `editing an X-SOCIALPROFILE IM handle applies and drops the stale raw idiom`() {
        val original = parse("kashcal_full_v3.vcf")
        val social = original.imHandles.single { it.protocol == "twitter" }

        val edited = original.copy(
            imHandles = original.imHandles.map {
                if (it.protocol == "twitter") it.copy(handle = "https://example.test/@renamed") else it
            },
        )
        val body = writer.write(edited, "3.0")
        val reparsed = parser.parse(body).single()

        assertTrue(
            reparsed.imHandles.any { it.handle.contains("@renamed") },
            "the edited handle round-trips",
        )
        assertFalse(body.contains("X-SOCIALPROFILE"), "the stale X-SOCIALPROFILE must not linger")
        assertFalse(body.contains(social.handle), "no reference to the pre-edit handle survives")
    }

    @Test
    fun `editing the kind on a 4-0 contact applies and round-trips`() {
        val scratch = Contact(
            version = "4.0",
            uid = "kind-edit-0001",
            structuredName = StructuredName(family = "Group", given = "The"),
            displayName = "The Group",
            kind = "individual",
            rawVCard = "",
        )
        // Give it a real prior body to patch, then flip KIND.
        val base = writer.write(scratch, "4.0")
        val parsedBase = parser.parse(base).single()
        assertEquals("individual", parsedBase.kind)

        val edited = parsedBase.copy(kind = "group")
        val reparsed = parser.parse(writer.write(edited, "4.0")).single()

        assertEquals("group", reparsed.kind)
    }

    @Test
    fun `kind on a 3-0 contact emits the Apple raw idiom and round-trips`() {
        // KIND is 4.0-only, so a 3.0 card must carry the group marker as Apple's
        // X-ADDRESSBOOKSERVER-KIND rather than a native KIND ez-vcard would drop.
        val scratch = Contact(
            version = "3.0",
            uid = "kind-30-0001",
            structuredName = StructuredName(family = "Group", given = "The"),
            displayName = "The Group",
            kind = "group",
            rawVCard = "",
        )

        val body = writer.write(scratch, "3.0")

        assertContains(body, "X-ADDRESSBOOKSERVER-KIND:group")
        assertEquals("group", parser.parse(body).single().kind)
    }

    @Test
    fun `a mixed-case KIND is emitted lower-cased so it converges on re-parse`() {
        // The parser lower-cases KIND on read; a producer yielding a mixed-case value must
        // be normalized on emission, else it would diff unequal and re-emit every sync.
        val scratch = Contact(
            version = "3.0",
            uid = "kind-case-0001",
            structuredName = StructuredName(family = "Group", given = "The"),
            displayName = "The Group",
            kind = "GROUP",
            rawVCard = "",
        )

        val body = writer.write(scratch, "3.0")

        assertContains(body, "X-ADDRESSBOOKSERVER-KIND:group")
        assertFalse(body.contains("GROUP"), "the mixed-case KIND must not survive verbatim")
        assertEquals("group", parser.parse(body).single().kind)
    }

    @Test
    fun `a mixed-case relation type is emitted lower-cased so it converges on re-parse`() {
        // Same convergence guard as KIND: the parser lower-cases the relation type on read.
        val scratch = Contact(
            version = "3.0",
            uid = "rel-case-0001",
            structuredName = StructuredName(family = "Partner", given = "The"),
            displayName = "The Partner",
            relations = listOf(Relation(name = "Jamie", type = "Spouse")),
            rawVCard = "",
        )

        val body = writer.write(scratch, "3.0")

        assertFalse(body.contains("Spouse"), "the mixed-case relation type must not survive verbatim")
        assertEquals("spouse", parser.parse(body).single().relations.single().type)
    }

    @Test
    fun `a mixed-case IM protocol is emitted lower-cased so it converges on re-parse`() {
        // Same convergence guard as KIND and relation type: the parser lower-cases the
        // IMPP protocol on read, so a mixed-case protocol must be normalized on emission
        // or the round-trip diff never settles and the contact re-pushes every sync.
        val scratch = Contact(
            version = "4.0",
            uid = "im-case-0001",
            structuredName = StructuredName(family = "Handle", given = "The"),
            displayName = "The Handle",
            imHandles = listOf(ImHandle(protocol = "Twitter", handle = "jamie")),
            rawVCard = "",
        )

        val body = writer.write(scratch, "4.0")

        assertFalse(body.contains("Twitter"), "the mixed-case IM protocol must not survive verbatim")
        assertEquals("twitter", parser.parse(body).single().imHandles.single().protocol)
    }

    @Test
    fun `an unparseable rawVCard falls back to generating from the model`() {
        val contact = Contact(
            version = "3.0",
            uid = "generated-from-garbage",
            structuredName = StructuredName(given = "Bad"),
            displayName = "Bad Raw",
            emails = listOf(Email(address = "bad@example.test")),
            rawVCard = "this is not a vcard at all",
        )

        val reparsed = parser.parse(writer.write(contact)).single()

        assertEquals("Bad Raw", reparsed.displayName)
        assertEquals("bad@example.test", reparsed.emails.single().address)
    }

    @Test
    fun `a multi-card rawVCard falls back to generating a single card`() {
        val twoCards = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Card A\r\nEND:VCARD\r\n" +
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Card B\r\nEND:VCARD\r\n"
        val contact = Contact(
            version = "3.0",
            uid = "multi-fallback",
            structuredName = StructuredName(),
            displayName = "Single Result",
            emails = listOf(Email(address = "single@example.test")),
            rawVCard = twoCards,
        )

        val cards = parser.parse(writer.write(contact))

        assertEquals(1, cards.size)
        assertEquals("Single Result", cards.single().displayName)
    }

    @Test
    fun `multiple preferred emails all round-trip at both versions`() {
        val original = parse("kashcal_full_v3.vcf")
        // The fixture marks only home@ preferred; mark work@ preferred too, so two
        // properties of the same facet carry the preference at once.
        val edited = original.copy(
            emails = original.emails.map { e ->
                if (e.address == "home@example.test" || e.address == "work@example.test") {
                    e.copy(preferred = true)
                } else {
                    e
                }
            },
        )

        listOf("3.0", "4.0").forEach { version ->
            val reparsed = parser.parse(writer.write(edited, version)).single()
            assertTrue(
                reparsed.emails.single { it.address == "home@example.test" }.preferred,
                "home@ must stay preferred at $version",
            )
            assertTrue(
                reparsed.emails.single { it.address == "work@example.test" }.preferred,
                "work@ must stay preferred at $version",
            )
        }
    }

    @Test
    fun `a photo missing only its contentType is preserved verbatim, never relabeled`() {
        // The device Contacts Photo row carries no MIME subtype, so a photo sourced
        // from a device round trip comes back with the same bytes but contentType=null.
        // Patch mode must treat that as UNCHANGED (a contentType-only delta), leaving the
        // original PHOTO line byte-faithful — not regenerate it and relabel PNG as JPEG.
        val original = parse("kashcal_photo_inline_v3.vcf")
        assertEquals("png", original.photo?.contentType)

        val fromDevice = original.copy(photo = original.photo!!.copy(contentType = null))
        val body = writer.write(fromDevice)

        assertTrue(body.contains("TYPE=PNG", ignoreCase = true), "PNG type preserved (ez-vcard lowercases the param)")
        assertFalse(body.contains("jpeg", ignoreCase = true), "must not relabel the PNG as JPEG")
        val reparsed = parser.parse(body).single()
        assertEquals("png", reparsed.photo?.contentType)
        assertTrue(reparsed.photo!!.data!!.contentEquals(original.photo!!.data!!))
    }

    @Test
    fun `a generated inline photo of unknown type is labeled from its bytes, not defaulted to JPEG`() {
        // A genuinely new inline photo with no contentType (e.g. reverse-mapped from a
        // device row) must be typed from its magic bytes rather than blindly stamped JPEG.
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02)
        val scratch = Contact(
            version = "3.0",
            uid = "urn:uuid:photo-sniff",
            structuredName = StructuredName(given = "Png"),
            displayName = "Png Probe",
            photo = Photo(data = pngBytes, contentType = null),
            rawVCard = "",
        )

        val body = writer.write(scratch, "3.0")

        assertTrue(body.contains("TYPE=PNG", ignoreCase = true), "PNG magic bytes must yield a PNG type")
        assertFalse(body.contains("jpeg", ignoreCase = true), "must not default unknown-type bytes to JPEG")
    }

    @Test
    fun `a generated WebP photo with no contentType is labeled webp, not defaulted to JPEG`() {
        // 'RIFF' + size + 'WEBP' — a device WebP thumbnail carries no MIME subtype, so the
        // type must come from the magic bytes rather than the JPEG default (else a
        // byte-preserving server stores the wrong TYPE).
        val webpBytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, 0x1A, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20,
        )
        val scratch = Contact(
            version = "3.0",
            uid = "urn:uuid:photo-webp",
            structuredName = StructuredName(given = "Webp"),
            displayName = "Webp Probe",
            photo = Photo(data = webpBytes, contentType = null),
            rawVCard = "",
        )

        val body30 = writer.write(scratch, "3.0")
        assertTrue(body30.contains("TYPE=webp", ignoreCase = true), "WebP magic bytes must yield TYPE=webp on 3.0")
        assertFalse(body30.contains("jpeg", ignoreCase = true), "must not default WebP bytes to JPEG")

        val body40 = writer.write(scratch.copy(version = "4.0"), "4.0")
        assertTrue(body40.contains("data:image/webp", ignoreCase = true), "WebP must carry data:image/webp on 4.0")
    }

    @Test
    fun `a generated HEIF photo with no contentType is labeled heic, not defaulted to JPEG`() {
        // ISOBMFF: box size, 'ftyp', 'heic' brand. A device HEIF thumbnail with no MIME.
        val heifBytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
            0x68, 0x65, 0x69, 0x63, 0x00, 0x00, 0x00, 0x00,
        )
        val scratch = Contact(
            version = "3.0",
            uid = "urn:uuid:photo-heif",
            structuredName = StructuredName(given = "Heif"),
            displayName = "Heif Probe",
            photo = Photo(data = heifBytes, contentType = null),
            rawVCard = "",
        )

        val body30 = writer.write(scratch, "3.0")
        assertTrue(body30.contains("TYPE=heic", ignoreCase = true), "HEIF magic bytes must yield TYPE=heic on 3.0")
        assertFalse(body30.contains("jpeg", ignoreCase = true), "must not default HEIF bytes to JPEG")

        val body40 = writer.write(scratch.copy(version = "4.0"), "4.0")
        assertTrue(body40.contains("data:image/heic", ignoreCase = true), "HEIF must carry data:image/heic on 4.0")
    }

    @Test
    fun `a JPEG photo with no contentType carries a real image media type on 4-0, not octet-stream`() {
        // A transcoded device photo arrives as JPEG bytes with contentType cleared to null,
        // so its type must be sniffed. A literal "jpeg" contentType would fall through
        // ez-vcard's predefined-constant match (its extension is "jpg") and serialize as
        // data:application/octet-stream on 4.0 — the exact mislabel a strict server drops.
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
        val scratch = Contact(
            version = "4.0",
            uid = "urn:uuid:photo-jpeg",
            structuredName = StructuredName(given = "Jay"),
            displayName = "Jay Probe",
            photo = Photo(data = jpegBytes, contentType = null),
            rawVCard = "",
        )

        val body = writer.write(scratch, "4.0")

        assertTrue(body.contains("data:image/jpeg", ignoreCase = true), "sniffed JPEG must carry data:image/jpeg on 4.0")
        assertFalse(body.contains("application/octet-stream", ignoreCase = true), "must not degrade to octet-stream")
    }

    @Test
    fun `inline photo bytes survive an unrelated edit`() {
        val original = parse("kashcal_photo_inline_v3.vcf")
        assertNotNull(original.photo?.data)

        val edited = original.copy(displayName = original.displayName + " Edited")
        val reparsed = parser.parse(writer.write(edited)).single()

        assertNotNull(reparsed.photo?.data)
        assertTrue(
            reparsed.photo!!.data!!.contentEquals(original.photo!!.data!!),
            "inline photo bytes must round-trip unchanged",
        )
    }
}
