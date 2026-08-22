package org.onekash.vcard

import org.junit.jupiter.api.Test
import org.onekash.vcard.model.Contact
import org.onekash.vcard.model.ContactDate
import org.onekash.vcard.model.Email
import org.onekash.vcard.model.Phone
import org.onekash.vcard.model.Photo
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
    fun `editing a 3-0 Apple raw anniversary is a documented no-op preserve`() {
        // The 3.0 fixture stores the anniversary as the Apple raw itemN.X-ABDATE +
        // X-ABLabel form, not a native property. It is in the PRESERVE-ONLY set: an
        // isolated edit to it is intentionally NOT applied on the write path (the
        // original raw value is carried through verbatim). This pins the exact
        // patch-vs-regenerate boundary — the mainstream editable fields ARE applied;
        // this dual-syntax field is a documented limitation, not a regression.
        val original = parse("kashcal_full_v3.vcf")
        assertEquals(LocalDate.of(2015, 6, 20), original.anniversary?.date)

        val edited = original.copy(anniversary = ContactDate(date = LocalDate.of(2020, 1, 1)))
        val reparsed = parser.parse(writer.write(edited, "3.0")).single()

        assertEquals(LocalDate.of(2015, 6, 20), reparsed.anniversary?.date)
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
