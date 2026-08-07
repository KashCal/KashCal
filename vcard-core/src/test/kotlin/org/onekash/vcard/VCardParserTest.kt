package org.onekash.vcard

import org.junit.jupiter.api.Test
import org.onekash.vcard.model.Contact
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parses the committed fixtures through [VCardParser] and asserts the neutral
 * model, re-confirming in-tree the ez-vcard 0.12.2 property surfacing the plan
 * probed out-of-tree. The load-bearing case is that a 3.0 Apple
 * `itemN.X-ABDATE`+`X-ABLabel="Anniversary"` reaches the same
 * [Contact.anniversary] field as a 4.0 native `ANNIVERSARY` — the code path that
 * would otherwise silently drop 3.0 anniversaries.
 */
class VCardParserTest {

    private val parser = VCardParser()

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "fixture not found: $name"
        }.readBytes().decodeToString()

    private fun parseSingle(name: String): Contact = parser.parse(fixture(name)).single()

    @Test
    fun `full 3-0 fixture maps standard properties into the neutral model`() {
        val c = parseSingle("kashcal_full_v3.vcf")

        assertEquals("3.0", c.version)
        assertEquals("kashcal-fixture-0001", c.uid)

        assertEquals("Probe", c.structuredName.family)
        assertEquals("KashCal", c.structuredName.given)
        assertEquals("Quincy", c.structuredName.middle)
        assertEquals("Dr.", c.structuredName.prefix)
        assertEquals("Jr.", c.structuredName.suffix)
        assertEquals("Dr. KashCal Quincy Probe Jr.", c.displayName)

        assertEquals("Cal", c.nickname)
        assertEquals(listOf("KashCal Test Org", "Sync Division"), c.organization)
        assertEquals("Fixture Contact", c.title)
        assertContains(c.urls.map { it.url }, "https://example.test/kashcal")
        assertContains(c.notes, "Synthetic exhaustive fixture for CardDAV mapper testing.")
        assertEquals(listOf("Family", "Test"), c.categories)

        // BDAY -> full date on the model.
        assertEquals(LocalDate.of(1990, 1, 15), c.birthday?.date)
    }

    @Test
    fun `preferred email is detected from 3-0 TYPE=PREF`() {
        val c = parseSingle("kashcal_full_v3.vcf")
        val preferred = c.emails.filter { it.preferred }
        assertEquals(listOf("home@example.test"), preferred.map { it.address })
        // The non-preferred WORK email is still present, not marked preferred.
        val work = c.emails.single { it.address == "work@example.test" }
        assertFalse(work.preferred)
    }

    @Test
    fun `3-0 phones read from text form`() {
        val c = parseSingle("kashcal_full_v3.vcf")
        assertContains(c.phones.map { it.number }, "+15550000001")
        assertContains(c.phones.map { it.number }, "+15550000002")
    }

    @Test
    fun `3-0 Apple raw properties are hand-routed`() {
        val c = parseSingle("kashcal_full_v3.vcf")

        // itemN.X-ABDATE + X-ABLabel="Anniversary" -> anniversary (NOT auto-typed by ez-vcard).
        assertEquals(LocalDate.of(2015, 6, 20), c.anniversary?.date)

        // itemN.X-ABRELATEDNAMES -> relation.
        val spouse = c.relations.single { it.name == "KashCal Spouse Probe" }
        assertEquals("spouse", spouse.type?.lowercase())

        // X-SOCIALPROFILE -> social/IMPP-style handle (raw property, not an ez-vcard Impp).
        val social = c.imHandles.single { it.handle == "https://example.test/@kashcal" }
        assertEquals("twitter", social.protocol)

        // The genuine IMPP handle also surfaces (ez-vcard strips the scheme into protocol).
        val xmpp = c.imHandles.single { it.handle == "cal@example.test" }
        assertEquals("xmpp", xmpp.protocol)
    }

    @Test
    fun `URI photo surfaces as a url`() {
        val c = parseSingle("kashcal_full_v3.vcf")
        val photo = assertNotNull(c.photo)
        assertEquals("https://example.test/photos/kashcal.jpg", photo.url)
        assertNull(photo.data)
    }

    @Test
    fun `full 4-0 fixture maps native rich fields`() {
        val c = parseSingle("kashcal_full_v4.vcf")

        assertEquals("4.0", c.version)
        assertEquals("urn:uuid:kashcal-v4-0002", c.uid)

        // Native 4.0 ANNIVERSARY reaches the SAME field the 3.0 raw form does.
        assertEquals(LocalDate.of(2015, 6, 20), c.anniversary?.date)
        assertEquals(LocalDate.of(1990, 1, 15), c.birthday?.date)

        // RELATED;TYPE=spouse
        val spouse = c.relations.single()
        assertEquals("KashCal Spouse Probe", spouse.name)
        assertEquals("spouse", spouse.type?.lowercase())

        // IMPP native handle.
        assertTrue(c.imHandles.any { it.handle.contains("cal@example.test") })
    }

    @Test
    fun `native 4-0 KIND surfaces on the model`() {
        // The committed 4.0 fixture carries KIND:individual.
        val c = parseSingle("kashcal_full_v4.vcf")
        assertEquals("individual", c.kind)
    }

    @Test
    fun `4-0 KIND group surfaces so groups can be filtered`() {
        val body = "BEGIN:VCARD\r\n" +
            "VERSION:4.0\r\n" +
            "UID:urn:uuid:team-4\r\n" +
            "KIND:group\r\n" +
            "FN:Marketing Team\r\n" +
            "MEMBER:urn:uuid:member-a\r\n" +
            "MEMBER:urn:uuid:member-b\r\n" +
            "END:VCARD\r\n"
        val c = parser.parse(body).single()
        assertEquals("group", c.kind)
    }

    @Test
    fun `3-0 Apple group vCard surfaces kind as group`() {
        // vCard 3.0 has no native KIND; Apple servers mark a distribution list with
        // the extended X-ADDRESSBOOKSERVER-KIND:group property. It must reach the
        // same [Contact.kind] value so the same group filter catches both syntaxes.
        val body = "BEGIN:VCARD\r\n" +
            "VERSION:3.0\r\n" +
            "UID:team-3\r\n" +
            "FN:Marketing Team\r\n" +
            "N:Marketing Team;;;;\r\n" +
            "X-ADDRESSBOOKSERVER-KIND:group\r\n" +
            "X-ADDRESSBOOKSERVER-MEMBER:urn:uuid:member-a\r\n" +
            "END:VCARD\r\n"
        val c = parser.parse(body).single()
        assertEquals("group", c.kind)
    }

    @Test
    fun `a body with no KIND has a null kind`() {
        // The committed 3.0 fixture carries neither native KIND nor the Apple form.
        val c = parseSingle("kashcal_full_v3.vcf")
        assertNull(c.kind)
    }

    @Test
    fun `preferred email detected from 4-0 PREF=1`() {
        val c = parseSingle("kashcal_full_v4.vcf")
        val preferred = c.emails.filter { it.preferred }
        assertEquals(listOf("home@example.test"), preferred.map { it.address })
    }

    @Test
    fun `4-0 tel URI has the tel prefix stripped`() {
        val c = parseSingle("kashcal_full_v4.vcf")
        assertContains(c.phones.map { it.number }, "+15550000001")
        assertContains(c.phones.map { it.number }, "+15550000002")
        assertTrue(c.phones.none { it.number.startsWith("tel:") }, "tel: scheme must be stripped")
    }

    @Test
    fun `both anniversary syntaxes reach the anniversary field`() {
        val v3 = parseSingle("kashcal_full_v3.vcf")
        val v4 = parseSingle("kashcal_full_v4.vcf")
        assertEquals(v4.anniversary?.date, v3.anniversary?.date)
        assertEquals(LocalDate.of(2015, 6, 20), v3.anniversary?.date)
    }

    @Test
    fun `version is read from the body not the request`() {
        val body = fixture("kashcal_full_v3.vcf")
        // Even asking for 4.0, a VERSION:3.0 body parses as 3.0.
        val c = parser.parse(body, requestedVersion = "4.0").single()
        assertEquals("3.0", c.version)
    }

    @Test
    fun `missing UID surfaces as blank`() {
        val c = parseSingle("kashcal_no_uid_v3.vcf")
        assertEquals("", c.uid)
        assertEquals("KashCal NoUID Probe", c.displayName)
    }

    @Test
    fun `folding unfolds long lines and ORG ADR de-escape`() {
        val c = parseSingle("kashcal_folding_and_escapes_v3.vcf")

        // The long NOTE was folded across the 75-octet boundary; it must unfold to one line.
        val note = c.notes.single()
        assertTrue(
            note.startsWith("This is a deliberately long note") && note.endsWith("space character."),
            "note should be fully unfolded",
        )
        assertFalse(note.contains("\n"), "unfolded note must contain no newline")

        // ORG de-escapes \, and \; and \& into a two-component org.
        assertEquals(listOf("KashCal, Inc.", "R&D; Sync"), c.organization)

        // ADR de-escapes \; and \, within components.
        val adr = c.addresses.single()
        assertEquals("123 Semicolon; Street, Suite 4", adr.street)
        assertEquals("Comma, City", adr.locality)
        assertEquals("CA", adr.region)
        assertEquals("90001", adr.postalCode)
        assertEquals("USA", adr.country)
    }

    @Test
    fun `inline base64 photo surfaces as bytes with a content type`() {
        val c = parseSingle("kashcal_photo_inline_v3.vcf")
        val photo = assertNotNull(c.photo)
        assertNull(photo.url)
        assertNotNull(photo.data)
        assertTrue(photo.data!!.isNotEmpty())
        assertEquals("png", photo.contentType?.lowercase())
    }

    @Test
    fun `empty FN derives display name from N`() {
        val c = parseSingle("kashcal_empty_fn_v3.vcf")
        assertEquals("Dr. KashCal Quincy Probe Jr.", c.displayName)
    }

    @Test
    fun `raw vCard text is retained for round trip`() {
        val body = fixture("kashcal_full_v3.vcf")
        val c = parser.parse(body).single()
        assertContains(c.rawVCard, "X-CUSTOM-PROP:retain-me")
    }

    @Test
    fun `parses from raw bytes`() {
        val bytes = fixture("kashcal_full_v4.vcf").encodeToByteArray()
        val c = parser.parse(bytes).single()
        assertEquals("4.0", c.version)
    }

    @Test
    fun `year-less BDAY and ANNIVERSARY are retained as partial-date text`() {
        // RFC 6350 §4.3.1 reduced-accuracy dates (unknown-year birthday) resolve
        // to no full LocalDate; the value must survive as text, not be dropped.
        val c = parseSingle("kashcal_partial_bday_v4.vcf")

        assertNull(c.birthday?.date)
        assertEquals("--04-15", c.birthday?.text)

        assertNull(c.anniversary?.date)
        assertEquals("--06-20", c.anniversary?.text)
    }

    @Test
    fun `ROLE surfaces distinct from TITLE`() {
        val c = parseSingle("kashcal_field_fidelity_v3.vcf")
        assertEquals("Fixture Contact", c.title)
        assertEquals("Chief Sync Officer", c.role)
    }

    @Test
    fun `multi-valued N components are space-joined not truncated`() {
        val c = parseSingle("kashcal_field_fidelity_v3.vcf")
        // N:Probe;KashCal;Quincy Aloysius;Dr. Prof.;Jr. III — each extra value retained.
        assertEquals("Quincy Aloysius", c.structuredName.middle)
        assertEquals("Dr. Prof.", c.structuredName.prefix)
        assertEquals("Jr. III", c.structuredName.suffix)
    }

    @Test
    fun `X-PHONETIC name hints surface on the structured name`() {
        val c = parseSingle("kashcal_field_fidelity_v3.vcf")
        assertEquals("kyashikaru", c.structuredName.phoneticGiven)
        assertEquals("kuinshii", c.structuredName.phoneticMiddle)
        assertEquals("puroobu", c.structuredName.phoneticFamily)
    }

    @Test
    fun `X-ABLabel custom labels attach to grouped email tel adr and url`() {
        val c = parseSingle("kashcal_field_fidelity_v3.vcf")

        val email = c.emails.single { it.address == "school@example.test" }
        assertEquals("School", email.label)

        val phone = c.phones.single { it.number == "+15550009999" }
        assertEquals("Beeper", phone.label)

        val adr = c.addresses.single { it.street == "9 Custom Way" }
        assertEquals("Vacation Home", adr.label)

        val url = c.urls.single { it.url == "https://example.test/blog" }
        assertEquals("Blog", url.label)
    }

    @Test
    fun `unlabeled url carries a null label`() {
        val c = parseSingle("kashcal_full_v3.vcf")
        val url = c.urls.single { it.url == "https://example.test/kashcal" }
        assertNull(url.label)
    }

    @Test
    fun `malformed tel URI degrades to the raw number instead of dropping the contact`() {
        // Some servers emit a 4.0 TEL as a tel: URI whose global number does not
        // start with "+" (a spec violation ez-vcard rejects). The phone must survive
        // as its raw text and, crucially, the rest of the contact must parse — a bad
        // number costs only that number, never the whole contact.
        val body = """
            BEGIN:VCARD
            VERSION:4.0
            UID:kashcal-badtel-0001
            FN:KashCal BadTel Probe
            TEL;VALUE=uri:tel:5550100
            EMAIL:badtel@example.test
            END:VCARD
        """.trimIndent()

        val c = parser.parse(body).single()

        assertEquals("KashCal BadTel Probe", c.displayName)
        assertEquals("badtel@example.test", c.emails.single().address)
        // The phone survives, carrying the raw digits (tel: scheme stripped).
        assertEquals("5550100", c.phones.single().number)
    }

    @Test
    fun `contact with an unparseable tel URI still yields the other fields`() {
        // A TEL declared as VALUE=uri but not a valid tel URI at all: ez-vcard cannot
        // build a TelUri, so the number falls back to raw text. The contact's name
        // and email must still come through rather than the body being discarded.
        val body = """
            BEGIN:VCARD
            VERSION:4.0
            UID:kashcal-badtel-0002
            FN:KashCal BadUri Probe
            TEL;VALUE=uri:not-a-tel-uri
            EMAIL:baduri@example.test
            END:VCARD
        """.trimIndent()

        val c = parser.parse(body).single()

        assertEquals("KashCal BadUri Probe", c.displayName)
        assertEquals("baduri@example.test", c.emails.single().address)
        assertEquals("not-a-tel-uri", c.phones.single().number)
    }

    @Test
    fun `empty and malformed bodies yield no contacts without throwing`() {
        // A sync/import path feeds untrusted bytes here; parsing must degrade to an
        // empty list rather than throw on empty or non-vCard input.
        assertTrue(parser.parse("").isEmpty())
        assertTrue(parser.parse(ByteArray(0)).isEmpty())
        assertTrue(parser.parse("not a vcard at all").isEmpty())
    }
}
