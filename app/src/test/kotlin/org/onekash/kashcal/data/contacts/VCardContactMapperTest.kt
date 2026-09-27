package org.onekash.kashcal.data.contacts

import android.content.ContentValues
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.vcard.VCardParser
import org.onekash.vcard.model.Contact
import org.onekash.vcard.model.ContactDate
import org.onekash.vcard.model.Email as VEmail
import org.onekash.vcard.model.ImHandle
import org.onekash.vcard.model.Phone as VPhone
import org.onekash.vcard.model.Photo as VPhoto
import org.onekash.vcard.model.StructuredName as VStructuredName
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that [VCardContactMapper] turns a [Contact] into the [ContactsContract] Data rows of
 * one RawContact.
 *
 * Fixtures (the same bodies the vcard-core parser tests use) go through the real
 * [VCardParser] first, so a parser change that reshapes the model is caught here too.
 *
 * The birthday and anniversary readers ([BaseContactEventRepository]) query
 * `Event.CONTENT_ITEM_TYPE` rows by `Event.TYPE` = `TYPE_BIRTHDAY` or `TYPE_ANNIVERSARY`. Any
 * other value means a synced date silently never reaches those calendars, so both the 4.0
 * `ANNIVERSARY` and the 3.0 `itemN.X-ABDATE` forms are asserted to land on the same constant.
 *
 * Robolectric supplies only the real `ContentValues`, as in `BuildEventValuesTest`; the mapper
 * touches no ContentResolver.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class VCardContactMapperTest {

    private val parser = VCardParser()

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("carddav/fixtures/$name")) {
            "fixture not found: $name"
        }.readBytes().decodeToString()

    private fun map(name: String): MappedContact =
        VCardContactMapper.toEntity(parser.parse(fixture(name)).single())

    private fun parse(name: String): Contact = parser.parse(fixture(name)).single()

    private fun List<ContentValues>.ofType(mime: String): List<ContentValues> =
        filter { it.getAsString(ContactsContract.Data.MIMETYPE) == mime }

    // ========== StructuredName ==========

    @Test
    fun `structured name row is always present with FN display name and N components`() {
        val rows = map("kashcal_full_v3.vcf").dataRows.ofType(StructuredName.CONTENT_ITEM_TYPE)
        val name = rows.single()
        assertEquals("Dr. KashCal Quincy Probe Jr.", name.getAsString(StructuredName.DISPLAY_NAME))
        assertEquals("KashCal", name.getAsString(StructuredName.GIVEN_NAME))
        assertEquals("Probe", name.getAsString(StructuredName.FAMILY_NAME))
        assertEquals("Quincy", name.getAsString(StructuredName.MIDDLE_NAME))
        assertEquals("Dr.", name.getAsString(StructuredName.PREFIX))
        assertEquals("Jr.", name.getAsString(StructuredName.SUFFIX))
    }

    @Test
    fun `empty FN derives display name from N`() {
        val rows = map("kashcal_empty_fn_v3.vcf").dataRows.ofType(StructuredName.CONTENT_ITEM_TYPE)
        assertEquals("Dr. KashCal Quincy Probe Jr.", rows.single().getAsString(StructuredName.DISPLAY_NAME))
    }

    @Test
    fun `phonetic name components map to the provider phonetic columns`() {
        val name = map("kashcal_field_fidelity_v3.vcf")
            .dataRows.ofType(StructuredName.CONTENT_ITEM_TYPE).single()
        assertEquals("kyashikaru", name.getAsString(StructuredName.PHONETIC_GIVEN_NAME))
        assertEquals("kuinshii", name.getAsString(StructuredName.PHONETIC_MIDDLE_NAME))
        assertEquals("puroobu", name.getAsString(StructuredName.PHONETIC_FAMILY_NAME))
    }

    @Test
    fun `multi-valued N components ride the single provider columns space-joined`() {
        val name = map("kashcal_field_fidelity_v3.vcf")
            .dataRows.ofType(StructuredName.CONTENT_ITEM_TYPE).single()
        assertEquals("Quincy Aloysius", name.getAsString(StructuredName.MIDDLE_NAME))
        assertEquals("Dr. Prof.", name.getAsString(StructuredName.PREFIX))
        assertEquals("Jr. III", name.getAsString(StructuredName.SUFFIX))
    }

    // ========== Nickname / Organization / Website / Note ==========

    @Test
    fun `nickname organization website note map to their mimetypes`() {
        val rows = map("kashcal_full_v3.vcf").dataRows

        assertEquals("Cal", rows.ofType(Nickname.CONTENT_ITEM_TYPE).single().getAsString(Nickname.NAME))

        val org = rows.ofType(Organization.CONTENT_ITEM_TYPE).single()
        assertEquals("KashCal Test Org", org.getAsString(Organization.COMPANY))
        assertEquals("Sync Division", org.getAsString(Organization.DEPARTMENT))
        assertEquals("Fixture Contact", org.getAsString(Organization.TITLE))

        assertEquals(
            "https://example.test/kashcal",
            rows.ofType(Website.CONTENT_ITEM_TYPE).single().getAsString(Website.URL),
        )

        assertEquals(
            "Synthetic exhaustive fixture for CardDAV mapper testing.",
            rows.ofType(Note.CONTENT_ITEM_TYPE).single().getAsString(Note.NOTE),
        )
    }

    @Test
    fun `ROLE maps to Organization JOB_DESCRIPTION distinct from TITLE`() {
        val org = map("kashcal_field_fidelity_v3.vcf")
            .dataRows.ofType(Organization.CONTENT_ITEM_TYPE).single()
        assertEquals("Fixture Contact", org.getAsString(Organization.TITLE))
        assertEquals("Chief Sync Officer", org.getAsString(Organization.JOB_DESCRIPTION))
    }

    @Test
    fun `organization row is emitted for a role even without company or title`() {
        val contact = Contact(
            version = "4.0",
            uid = "kashcal-role-only",
            structuredName = VStructuredName(given = "KashCal", family = "Probe"),
            displayName = "KashCal Probe",
            role = "Chief Sync Officer",
            rawVCard = "",
        )
        val org = VCardContactMapper.toEntity(contact)
            .dataRows.ofType(Organization.CONTENT_ITEM_TYPE).single()
        assertEquals("Chief Sync Officer", org.getAsString(Organization.JOB_DESCRIPTION))
        assertNull(org.getAsString(Organization.COMPANY))
        assertNull(org.getAsString(Organization.TITLE))
    }

    // ========== Email ==========

    @Test
    fun `emails are typed and preferred email is primary (3-0 TYPE=PREF)`() {
        val rows = map("kashcal_full_v3.vcf").dataRows.ofType(Email.CONTENT_ITEM_TYPE)

        val home = rows.single { it.getAsString(Email.ADDRESS) == "home@example.test" }
        assertEquals(Email.TYPE_HOME, home.getAsInteger(Email.TYPE))
        assertEquals(1, home.getAsInteger(Email.IS_PRIMARY))

        val work = rows.single { it.getAsString(Email.ADDRESS) == "work@example.test" }
        assertEquals(Email.TYPE_WORK, work.getAsInteger(Email.TYPE))
        // Not preferred, so not primary: 0 or null, never 1.
        assertNotEquals(1, work.getAsInteger(Email.IS_PRIMARY) ?: 0)
    }

    @Test
    fun `preferred email is primary across the 4-0 PREF=1 split`() {
        val rows = map("kashcal_full_v4.vcf").dataRows.ofType(Email.CONTENT_ITEM_TYPE)
        val home = rows.single { it.getAsString(Email.ADDRESS) == "home@example.test" }
        assertEquals(1, home.getAsInteger(Email.IS_PRIMARY))
    }

    // ========== Phone ==========

    @Test
    fun `phones are typed and read from 3-0 text form`() {
        val rows = map("kashcal_full_v3.vcf").dataRows.ofType(Phone.CONTENT_ITEM_TYPE)
        val cell = rows.single { it.getAsString(Phone.NUMBER) == "+15550000001" }
        assertEquals(Phone.TYPE_MOBILE, cell.getAsInteger(Phone.TYPE))
        val home = rows.single { it.getAsString(Phone.NUMBER) == "+15550000002" }
        assertEquals(Phone.TYPE_HOME, home.getAsInteger(Phone.TYPE))
    }

    @Test
    fun `4-0 tel URI numbers surface with tel prefix stripped`() {
        val rows = map("kashcal_full_v4.vcf").dataRows.ofType(Phone.CONTENT_ITEM_TYPE)
        val numbers = rows.map { it.getAsString(Phone.NUMBER) }
        assertTrue(numbers.contains("+15550000001"))
        assertTrue(numbers.contains("+15550000002"))
        assertTrue(numbers.none { it.startsWith("tel:") })
    }

    // ========== StructuredPostal ==========

    @Test
    fun `postal address maps the 7 vCard components`() {
        val adr = map("kashcal_full_v3.vcf").dataRows.ofType(StructuredPostal.CONTENT_ITEM_TYPE).single()
        assertEquals("1 Test St", adr.getAsString(StructuredPostal.STREET))
        assertEquals("Testville", adr.getAsString(StructuredPostal.CITY))
        assertEquals("CA", adr.getAsString(StructuredPostal.REGION))
        assertEquals("90000", adr.getAsString(StructuredPostal.POSTCODE))
        assertEquals("USA", adr.getAsString(StructuredPostal.COUNTRY))
    }

    @Test
    fun `postal address de-escapes semicolons and commas within components`() {
        val adr = map("kashcal_folding_and_escapes_v3.vcf")
            .dataRows.ofType(StructuredPostal.CONTENT_ITEM_TYPE).single()
        assertEquals("123 Semicolon; Street, Suite 4", adr.getAsString(StructuredPostal.STREET))
        assertEquals("Comma, City", adr.getAsString(StructuredPostal.CITY))
    }

    // ========== Im / Relation ==========

    @Test
    fun `im handles map to Im rows carrying the protocol`() {
        val rows = map("kashcal_full_v3.vcf").dataRows.ofType(Im.CONTENT_ITEM_TYPE)
        val xmpp = rows.single { it.getAsString(Im.DATA) == "cal@example.test" }
        // Every protocol is written as PROTOCOL_CUSTOM with its name in CUSTOM_PROTOCOL.
        assertEquals(Im.PROTOCOL_CUSTOM, xmpp.getAsInteger(Im.PROTOCOL))
        assertEquals("xmpp", xmpp.getAsString(Im.CUSTOM_PROTOCOL))
        assertTrue(rows.any { it.getAsString(Im.DATA) == "https://example.test/@kashcal" })
    }

    @Test
    fun `relations map to Relation rows with a spouse type`() {
        val rows = map("kashcal_full_v4.vcf").dataRows.ofType(Relation.CONTENT_ITEM_TYPE)
        val spouse = rows.single { it.getAsString(Relation.NAME) == "KashCal Spouse Probe" }
        assertEquals(Relation.TYPE_SPOUSE, spouse.getAsInteger(Relation.TYPE))
    }

    // ========== Custom X-ABLabel labels ==========

    @Test
    fun `custom-labeled email tel adr and url map to TYPE_CUSTOM carrying the label`() {
        val rows = map("kashcal_field_fidelity_v3.vcf").dataRows

        val email = rows.ofType(Email.CONTENT_ITEM_TYPE).single { it.getAsString(Email.ADDRESS) == "school@example.test" }
        assertEquals(Email.TYPE_CUSTOM, email.getAsInteger(Email.TYPE))
        assertEquals("School", email.getAsString(Email.LABEL))

        val phone = rows.ofType(Phone.CONTENT_ITEM_TYPE).single { it.getAsString(Phone.NUMBER) == "+15550009999" }
        assertEquals(Phone.TYPE_CUSTOM, phone.getAsInteger(Phone.TYPE))
        assertEquals("Beeper", phone.getAsString(Phone.LABEL))

        val adr = rows.ofType(StructuredPostal.CONTENT_ITEM_TYPE).single { it.getAsString(StructuredPostal.STREET) == "9 Custom Way" }
        assertEquals(StructuredPostal.TYPE_CUSTOM, adr.getAsInteger(StructuredPostal.TYPE))
        assertEquals("Vacation Home", adr.getAsString(StructuredPostal.LABEL))

        val web = rows.ofType(Website.CONTENT_ITEM_TYPE).single { it.getAsString(Website.URL) == "https://example.test/blog" }
        assertEquals(Website.TYPE_CUSTOM, web.getAsInteger(Website.TYPE))
        assertEquals("Blog", web.getAsString(Website.LABEL))
    }

    @Test
    fun `unlabeled website falls back to TYPE_OTHER with no custom label`() {
        val web = map("kashcal_full_v3.vcf").dataRows.ofType(Website.CONTENT_ITEM_TYPE).single()
        assertEquals(Website.TYPE_OTHER, web.getAsInteger(Website.TYPE))
        assertNull(web.getAsString(Website.LABEL))
    }

    // ========== CATEGORIES -> GroupMembership ==========

    @Test
    fun `categories map to one GroupMembership row each keyed by the category name`() {
        val rows = map("kashcal_full_v3.vcf").dataRows.ofType(GroupMembership.CONTENT_ITEM_TYPE)
        val labels = rows.map { it.getAsString(GroupMembership.GROUP_SOURCE_ID) }
        assertEquals(listOf("Family", "Test"), labels)
    }

    @Test
    fun `blank category label emits no GroupMembership row`() {
        val contact = Contact(
            version = "4.0",
            uid = "kashcal-blank-category",
            structuredName = VStructuredName(given = "KashCal", family = "Probe"),
            displayName = "KashCal Probe",
            categories = listOf("Family", "", "  "),
            rawVCard = "",
        )
        val rows = VCardContactMapper.toEntity(contact).dataRows.ofType(GroupMembership.CONTENT_ITEM_TYPE)
        assertEquals(listOf("Family"), rows.map { it.getAsString(GroupMembership.GROUP_SOURCE_ID) })
    }

    // ========== Photo ==========

    @Test
    fun `inline photo maps to a Photo blob row and carries no deferred url`() {
        val mapped = map("kashcal_photo_inline_v3.vcf")
        val photo = mapped.dataRows.ofType(Photo.CONTENT_ITEM_TYPE).single()
        val bytes = photo.getAsByteArray(Photo.PHOTO)
        assertNotNull(bytes)
        assertTrue(bytes!!.isNotEmpty())
        assertNull("inline photo needs no deferred fetch", mapped.photoUrl)
    }

    @Test
    fun `url-only photo is carried for deferred fetch and emits no blob row`() {
        val mapped = map("kashcal_full_v3.vcf")
        assertTrue(mapped.dataRows.ofType(Photo.CONTENT_ITEM_TYPE).isEmpty())
        assertEquals("https://example.test/photos/kashcal.jpg", mapped.photoUrl)
    }

    @Test
    fun `degenerate empty-bytes photo with a url falls back to the deferred url`() {
        // Zero-length bytes give no blob row; the URL must still be carried for the deferred
        // fetch, or the photo is lost.
        val contact = Contact(
            version = "4.0",
            uid = "kashcal-degenerate-photo",
            structuredName = VStructuredName(given = "KashCal", family = "Probe"),
            displayName = "KashCal Probe",
            photo = VPhoto(url = "https://example.test/photos/fallback.jpg", data = ByteArray(0)),
            rawVCard = "",
        )
        val mapped = VCardContactMapper.toEntity(contact)
        assertTrue(mapped.dataRows.ofType(Photo.CONTENT_ITEM_TYPE).isEmpty())
        assertEquals("https://example.test/photos/fallback.jpg", mapped.photoUrl)
    }

    @Test
    fun `an inline photo over the byte cap emits no blob row (would trip TransactionTooLargeException)`() {
        // The Photo blob goes into an applyBatch insert that crosses Binder (~1MB limit), so
        // an oversized inline body would fail the whole batch. It is dropped, as the URL fetch
        // caps its download at the same [MAX_PHOTO_SIZE_BYTES].
        val oversized = ByteArray((MAX_PHOTO_SIZE_BYTES + 1).toInt()) { 1 }
        val contact = Contact(
            version = "3.0",
            uid = "kashcal-oversized-inline-photo",
            structuredName = VStructuredName(given = "KashCal", family = "Probe"),
            displayName = "KashCal Probe",
            photo = VPhoto(data = oversized, contentType = "jpeg"),
            rawVCard = "",
        )
        val mapped = VCardContactMapper.toEntity(contact)
        assertTrue(
            "an over-cap inline blob must not be emitted",
            mapped.dataRows.ofType(Photo.CONTENT_ITEM_TYPE).isEmpty(),
        )
        assertNull("no URL was present, so nothing is deferred either", mapped.photoUrl)
    }

    @Test
    fun `an over-cap inline photo with a url falls back to the deferred url`() {
        // Some servers send both an oversized inline blob and a URI. When the bytes are too
        // large to write, the URL must still be carried for the deferred fetch, or the
        // contact gets no photo.
        val oversized = ByteArray((MAX_PHOTO_SIZE_BYTES + 1).toInt()) { 1 }
        val contact = Contact(
            version = "3.0",
            uid = "kashcal-oversized-inline-with-url",
            structuredName = VStructuredName(given = "KashCal", family = "Probe"),
            displayName = "KashCal Probe",
            photo = VPhoto(url = "https://example.test/photos/big.jpg", data = oversized, contentType = "jpeg"),
            rawVCard = "",
        )
        val mapped = VCardContactMapper.toEntity(contact)
        assertTrue(mapped.dataRows.ofType(Photo.CONTENT_ITEM_TYPE).isEmpty())
        assertEquals("https://example.test/photos/big.jpg", mapped.photoUrl)
    }

    @Test
    fun `an inline photo exactly at the byte cap is still emitted`() {
        val atCap = ByteArray(MAX_PHOTO_SIZE_BYTES.toInt()) { 1 }
        val contact = Contact(
            version = "3.0",
            uid = "kashcal-at-cap-inline-photo",
            structuredName = VStructuredName(given = "KashCal", family = "Probe"),
            displayName = "KashCal Probe",
            photo = VPhoto(data = atCap, contentType = "jpeg"),
            rawVCard = "",
        )
        val mapped = VCardContactMapper.toEntity(contact)
        val photo = mapped.dataRows.ofType(Photo.CONTENT_ITEM_TYPE).single()
        assertEquals(MAX_PHOTO_SIZE_BYTES.toInt(), photo.getAsByteArray(Photo.PHOTO).size)
    }

    // ========== Event rows the birthday and anniversary readers query ==========

    @Test
    fun `birthday maps to an Event row with TYPE_BIRTHDAY in ISO start date`() {
        val events = map("kashcal_full_v3.vcf").dataRows.ofType(Event.CONTENT_ITEM_TYPE)
        val bday = events.single { it.getAsInteger(Event.TYPE) == Event.TYPE_BIRTHDAY }
        assertEquals("1990-01-15", bday.getAsString(Event.START_DATE))
        // The birthday reader's own parser must read the emitted string back to the same
        // date; a literal alone could look right and still not parse.
        assertEquals(
            ContactEventDate(1, 15, 1990),
            ContactEventUtils.parseContactDate(bday.getAsString(Event.START_DATE)),
        )
    }

    @Test
    fun `4-0 native anniversary maps to an Event row with TYPE_ANNIVERSARY`() {
        val events = map("kashcal_full_v4.vcf").dataRows.ofType(Event.CONTENT_ITEM_TYPE)
        val anniv = events.single { it.getAsInteger(Event.TYPE) == Event.TYPE_ANNIVERSARY }
        assertEquals("2015-06-20", anniv.getAsString(Event.START_DATE))
    }

    @Test
    fun `3-0 itemN X-ABDATE anniversary reaches the same TYPE_ANNIVERSARY constant`() {
        // ez-vcard leaves the 3.0 itemN.X-ABDATE as a RawProperty and the parser routes it
        // onto Contact.anniversary by hand. Without that route a 3.0 anniversary is silently
        // dropped before it reaches the calendar.
        val events = map("kashcal_full_v3.vcf").dataRows.ofType(Event.CONTENT_ITEM_TYPE)
        val anniv = events.single { it.getAsInteger(Event.TYPE) == Event.TYPE_ANNIVERSARY }
        assertEquals("2015-06-20", anniv.getAsString(Event.START_DATE))
    }

    @Test
    fun `both anniversary syntaxes produce an identical Event start date`() {
        val v3 = map("kashcal_full_v3.vcf").dataRows.ofType(Event.CONTENT_ITEM_TYPE)
            .single { it.getAsInteger(Event.TYPE) == Event.TYPE_ANNIVERSARY }
        val v4 = map("kashcal_full_v4.vcf").dataRows.ofType(Event.CONTENT_ITEM_TYPE)
            .single { it.getAsInteger(Event.TYPE) == Event.TYPE_ANNIVERSARY }
        assertEquals(v3.getAsString(Event.START_DATE), v4.getAsString(Event.START_DATE))
    }

    @Test
    fun `year-less birthday still produces an Event row in the year-less start-date form`() {
        // A reduced-accuracy vCard date (--MM-DD, RFC 6350) has no LocalDate but must not be
        // dropped: it maps to the provider's year-less START_DATE, which
        // ContactEventUtils.parseContactDate reads back through its "--MM-DD" branch.
        val contact = parse("kashcal_partial_bday_v4.vcf")
        assertNull("fixture guards the year-less path", contact.birthday?.date)

        val events = VCardContactMapper.toEntity(contact).dataRows.ofType(Event.CONTENT_ITEM_TYPE)
        val bday = events.single { it.getAsInteger(Event.TYPE) == Event.TYPE_BIRTHDAY }
        assertEquals("--04-15", bday.getAsString(Event.START_DATE))
        // The reader's "--MM-DD" branch accepts it with a null year.
        assertEquals(
            ContactEventDate(4, 15, null),
            ContactEventUtils.parseContactDate(bday.getAsString(Event.START_DATE)),
        )

        // The year-less anniversary takes the same path.
        val anniv = events.single { it.getAsInteger(Event.TYPE) == Event.TYPE_ANNIVERSARY }
        assertEquals("--06-20", anniv.getAsString(Event.START_DATE))
        assertEquals(
            ContactEventDate(6, 20, null),
            ContactEventUtils.parseContactDate(anniv.getAsString(Event.START_DATE)),
        )
    }

    // ========== Untrusted-input robustness (constructed models) ==========

    private fun contact(
        emails: List<VEmail> = emptyList(),
        phones: List<VPhone> = emptyList(),
        imHandles: List<ImHandle> = emptyList(),
        birthday: ContactDate? = null,
    ): Contact = Contact(
        version = "4.0",
        uid = "kashcal-robustness",
        structuredName = VStructuredName(given = "KashCal", family = "Probe"),
        displayName = "KashCal Probe",
        emails = emails,
        phones = phones,
        imHandles = imHandles,
        birthday = birthday,
        rawVCard = "",
    )

    @Test
    fun `blank email phone and im values emit no phantom rows`() {
        val mapped = VCardContactMapper.toEntity(
            contact(
                emails = listOf(VEmail(address = ""), VEmail(address = "real@example.test")),
                phones = listOf(VPhone(number = ""), VPhone(number = "+15550000009")),
                imHandles = listOf(ImHandle(protocol = "xmpp", handle = ""), ImHandle(protocol = "xmpp", handle = "cal@example.test")),
            ),
        )
        // Only the non-blank value of each kind survives: a blank property line (some servers
        // store `EMAIL:` or `TEL:`) must not become a tappable empty row.
        assertEquals(1, mapped.dataRows.ofType(Email.CONTENT_ITEM_TYPE).size)
        assertEquals(1, mapped.dataRows.ofType(Phone.CONTENT_ITEM_TYPE).size)
        assertEquals(1, mapped.dataRows.ofType(Im.CONTENT_ITEM_TYPE).size)
    }

    @Test
    fun `at most one email and one phone are marked primary`() {
        val mapped = VCardContactMapper.toEntity(
            contact(
                emails = listOf(
                    VEmail(address = "a@example.test", preferred = true),
                    VEmail(address = "b@example.test", preferred = true),
                ),
                phones = listOf(
                    VPhone(number = "+15550000001", preferred = true),
                    VPhone(number = "+15550000002", preferred = true),
                ),
            ),
        )
        // The provider expects one primary per mimetype; only the first preferred gets it.
        assertEquals(1, mapped.dataRows.ofType(Email.CONTENT_ITEM_TYPE).count { it.getAsInteger(Email.IS_PRIMARY) == 1 })
        assertEquals(1, mapped.dataRows.ofType(Phone.CONTENT_ITEM_TYPE).count { it.getAsInteger(Phone.IS_PRIMARY) == 1 })
        // The first preferred address is the one that keeps IS_PRIMARY.
        val primaryEmail = mapped.dataRows.ofType(Email.CONTENT_ITEM_TYPE).single { it.getAsInteger(Email.IS_PRIMARY) == 1 }
        assertEquals("a@example.test", primaryEmail.getAsString(Email.ADDRESS))
    }

    @Test
    fun `free-text birthday the reader cannot parse emits no Event row`() {
        val mapped = VCardContactMapper.toEntity(contact(birthday = ContactDate(date = null, text = "circa 1990")))
        // A free-text date in START_DATE would silently never reach the birthday calendar, so
        // the mapper emits only values the reader accepts.
        assertNull(ContactEventUtils.parseContactDate("circa 1990"))
        assertTrue(mapped.dataRows.ofType(Event.CONTENT_ITEM_TYPE).isEmpty())
    }

    @Test
    fun `year-less text birthday still emits a readable Event row`() {
        // The free-text guard above must still accept the reduced-accuracy --MM-DD text.
        val mapped = VCardContactMapper.toEntity(contact(birthday = ContactDate(date = null, text = "--03-21")))
        val bday = mapped.dataRows.ofType(Event.CONTENT_ITEM_TYPE).single()
        assertEquals("--03-21", bday.getAsString(Event.START_DATE))
        assertEquals(ContactEventDate(3, 21, null), ContactEventUtils.parseContactDate(bday.getAsString(Event.START_DATE)))
    }

    // ========== Purity / completeness ==========

    @Test
    fun `full fixture yields exactly one row per expected mimetype group`() {
        val rows = map("kashcal_full_v3.vcf").dataRows
        // One StructuredName row always; the counts below cover six other mimetypes.
        assertEquals(1, rows.ofType(StructuredName.CONTENT_ITEM_TYPE).size)
        // Three EMAILs: home, work, and the grouped item1.EMAIL custom address.
        assertEquals(3, rows.ofType(Email.CONTENT_ITEM_TYPE).size)
        assertEquals(2, rows.ofType(Phone.CONTENT_ITEM_TYPE).size)
        assertEquals(1, rows.ofType(StructuredPostal.CONTENT_ITEM_TYPE).size)
        assertEquals(1, rows.ofType(Organization.CONTENT_ITEM_TYPE).size)
        assertEquals(1, rows.ofType(Note.CONTENT_ITEM_TYPE).size)
        assertEquals(1, rows.ofType(Website.CONTENT_ITEM_TYPE).size)
        // No Data row has a RAW_CONTACT_ID; the write layer adds that back-reference.
        assertTrue(rows.none { it.containsKey(ContactsContract.Data.RAW_CONTACT_ID) })
    }

    @Test
    fun `mapper carries the source contact for the write layer`() {
        val mapped = map("kashcal_full_v3.vcf")
        assertEquals("kashcal-fixture-0001", mapped.contact.uid)
    }
}
