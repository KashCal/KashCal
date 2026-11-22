package org.onekash.vcard

import org.junit.jupiter.api.Test
import org.onekash.vcard.model.Contact
import org.onekash.vcard.model.ContactDate
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parses the committed fixtures and inline bodies through [VCardParser] and asserts the neutral
 * model ez-vcard 0.12.2 yields. The load-bearing case: a 3.0 Apple `itemN.X-ABDATE` with an
 * `Anniversary` `X-ABLabel` reaches the same [Contact.anniversary] field as a 4.0 native
 * `ANNIVERSARY`; without the hand-routing, 3.0 anniversaries would be silently dropped.
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

        // itemN.X-ABDATE + X-ABLabel="Anniversary" -> anniversary (ez-vcard leaves it raw).
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

        // Native 4.0 ANNIVERSARY reaches the same field the 3.0 raw form does.
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
        // vCard 3.0 has no native KIND; Apple servers mark a distribution list with the
        // extended X-ADDRESSBOOKSERVER-KIND:group property. It must reach the same
        // [Contact.kind] value so one group filter catches both syntaxes.
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
        // N:Probe;KashCal;Quincy,Aloysius;Dr.,Prof.;Jr.,III keeps every value of each component.
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
        // Some servers emit a 4.0 TEL as a tel: URI whose global number doesn't start
        // with "+" (a spec violation ez-vcard rejects). The phone survives as its raw
        // text and the rest of the contact parses: a bad number costs only that number,
        // never the whole contact.
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
        // A TEL declared as VALUE=uri but not a tel URI: ez-vcard can't build a TelUri,
        // so the number falls back to raw text. The name and email must still come
        // through; the body isn't discarded.
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
        // CardDAV sync feeds server bytes here; empty or non-vCard input must yield an
        // empty list, never throw.
        assertTrue(parser.parse("").isEmpty())
        assertTrue(parser.parse(ByteArray(0)).isEmpty())
        assertTrue(parser.parse("not a vcard at all").isEmpty())
    }

    // ========== Dates with no year ==========

    private fun card(vararg lines: String) = parser.parse(
        (listOf("BEGIN:VCARD", "VERSION:3.0", "UID:yearless-parser", "FN:Jane Doe", "N:Doe;Jane;;;") +
            lines + "END:VCARD").joinToString("\r\n", postfix = "\r\n"),
    ).single()

    @Test
    fun `a 1604 birthday reads as a date without a year`() {
        for (line in listOf(
            "BDAY;X-APPLE-OMIT-YEAR=1604:1604-03-15",
            "BDAY;X-APPLE-OMIT-YEAR=1604;VALUE=date:1604-03-15",
            "BDAY:1604-03-15",
        )) {
            assertEquals(ContactDate(text = "--03-15"), card(line).birthday, line)
        }
    }

    @Test
    fun `a 4-0 1604 anniversary reads as a date without a year`() {
        val c = parser.parse(
            "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:yearless-v4\r\nFN:Jane Doe\r\nANNIVERSARY:16040922\r\nEND:VCARD\r\n",
        ).single()
        assertEquals(ContactDate(text = "--09-22"), c.anniversary)
    }

    @Test
    fun `the omit-year parameter drops the year it names and no other`() {
        assertEquals(ContactDate(text = "--03-15"), card("BDAY;X-APPLE-OMIT-YEAR=1900:1900-03-15").birthday)
        assertEquals(
            ContactDate(date = LocalDate.of(1985, 3, 15)),
            card("BDAY;X-APPLE-OMIT-YEAR=1604:1985-03-15").birthday,
        )
    }

    @Test
    fun `a malformed omit-year parameter keeps the date`() {
        for (line in listOf(
            "BDAY;X-APPLE-OMIT-YEAR=abc:1985-03-15",
            "BDAY;X-APPLE-OMIT-YEAR=:1985-03-15",
            "BDAY;X-APPLE-OMIT-YEAR=1604;X-APPLE-OMIT-YEAR=abc:1985-03-15",
        )) {
            assertEquals(ContactDate(date = LocalDate.of(1985, 3, 15)), card(line).birthday, line)
        }
        assertEquals(
            ContactDate(text = "--03-15"),
            card("BDAY;X-APPLE-OMIT-YEAR=abc;X-APPLE-OMIT-YEAR=1985:1985-03-15").birthday,
            "a repeated parameter matches when any of its values is the year",
        )
    }

    @Test
    fun `a 1604 Apple anniversary reads as a date without a year`() {
        for (line in listOf("item1.X-ABDATE;X-APPLE-OMIT-YEAR=1604:1604-09-22", "item1.X-ABDATE:1604-09-22")) {
            val c = card(line, "item1.X-ABLabel:_\$!<Anniversary>!\$_")
            assertEquals(ContactDate(text = "--09-22"), c.anniversary, line)
        }
    }

    @Test
    fun `a dated Apple anniversary keeps its date and raw text`() {
        val c = card("item1.X-ABDATE:2015-06-20", "item1.X-ABLabel:_\$!<Anniversary>!\$_")
        assertEquals(ContactDate(date = LocalDate.of(2015, 6, 20), text = "2015-06-20"), c.anniversary)
    }
}
