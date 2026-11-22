package org.onekash.vcard

import org.junit.jupiter.api.Test
import org.onekash.vcard.model.Contact
import org.onekash.vcard.model.ContactDate
import org.onekash.vcard.model.Email
import org.onekash.vcard.model.ImHandle
import org.onekash.vcard.model.Phone
import org.onekash.vcard.model.Photo
import org.onekash.vcard.model.PostalAddress
import org.onekash.vcard.model.Relation
import org.onekash.vcard.model.StructuredName
import org.onekash.vcard.model.WebAddress
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [changedFields], the one definition of "the same contact" that [VCardWriter] uses to
 * pick the facets it rewrites and that contact sync uses to tell a matching server copy from a
 * conflicting one.
 */
class ContactFieldDiffTest {

    private val base = Contact(
        version = "3.0",
        uid = "uid-1",
        structuredName = StructuredName(family = "Doe", given = "Jane"),
        displayName = "Jane Doe",
        rawVCard = "",
    )

    @Test
    fun `identical contacts have no changed fields`() {
        assertTrue(changedFields(base, base.copy()).isEmpty())
    }

    @Test
    fun `each synced field changed alone is reported alone`() {
        val cases: Map<ContactField, Contact> = mapOf(
            ContactField.UID to base.copy(uid = "uid-2"),
            ContactField.KIND to base.copy(kind = "org"),
            ContactField.FORMATTED_NAME to base.copy(displayName = "J. Doe"),
            ContactField.STRUCTURED_NAME to base.copy(structuredName = StructuredName(family = "Roe", given = "Jane")),
            ContactField.NICKNAME to base.copy(nickname = "JD"),
            ContactField.ORGANIZATION to base.copy(organization = listOf("Example Org")),
            ContactField.TITLE to base.copy(title = "Engineer"),
            ContactField.ROLE to base.copy(role = "Lead"),
            ContactField.EMAILS to base.copy(emails = listOf(Email(address = "jane@example.test"))),
            ContactField.PHONES to base.copy(phones = listOf(Phone(number = "+1-555-0100"))),
            ContactField.ADDRESSES to base.copy(addresses = listOf(PostalAddress(street = "1 Way"))),
            ContactField.URLS to base.copy(urls = listOf(WebAddress(url = "https://example.test"))),
            ContactField.NOTES to base.copy(notes = listOf("note")),
            ContactField.CATEGORIES to base.copy(categories = listOf("Friends")),
            ContactField.PHOTO to base.copy(photo = Photo(data = byteArrayOf(1, 2, 3), contentType = "image/jpeg")),
            ContactField.BIRTHDAY to base.copy(birthday = ContactDate(date = LocalDate.of(1990, 5, 1))),
            ContactField.ANNIVERSARY to base.copy(anniversary = ContactDate(date = LocalDate.of(2010, 6, 1))),
            ContactField.RELATIONS to base.copy(relations = listOf(Relation(name = "John", type = "spouse"))),
            ContactField.IM_HANDLES to base.copy(imHandles = listOf(ImHandle(protocol = "xmpp", handle = "j@example.test"))),
        )
        assertEquals(ContactField.entries.toSet(), cases.keys, "every field has a case")
        cases.forEach { (field, changed) ->
            assertEquals(setOf(field), changedFields(changed, base), "only $field differs")
        }
    }

    @Test
    fun `a photo differing only in content type is not a change`() {
        val bytes = byteArrayOf(9, 8, 7)
        val a = base.copy(photo = Photo(data = bytes, contentType = "image/jpeg"))
        val b = base.copy(photo = Photo(data = bytes.copyOf(), contentType = null))
        assertTrue(changedFields(a, b).isEmpty())
    }

    @Test
    fun `a date without a year in its two spellings is not a change`() {
        val dashed = base.copy(birthday = ContactDate(text = "--05-01"))
        val placeholderYear = base.copy(birthday = ContactDate(date = LocalDate.of(1604, 5, 1)))
        assertTrue(changedFields(dashed, placeholderYear).isEmpty())
    }

    @Test
    fun `rawVCard and version are not synced fields`() {
        val other = base.copy(version = "4.0", rawVCard = "BEGIN:VCARD\r\nEND:VCARD\r\n")
        assertTrue(changedFields(other, base).isEmpty())
    }
}
