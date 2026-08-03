package org.onekash.vcard

import ezvcard.Ezvcard
import ezvcard.VCardVersion
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dependency smoke test for the vCard library, and a build-level re-confirmation
 * of the dependency footprint the contact-sync design relies on.
 *
 * The footprint was originally checked against a standalone jar outside this
 * build. This pins the same facts inside the Gradle graph so they cannot silently
 * regress:
 *
 *  - The library resolves and parses a `text/vcard` body (both 3.0 and 4.0).
 *  - The `text/vcard` read path works with `jsoup` (hCard) and `jackson` (jCard)
 *    **excluded**. A successful parse here IS the exclusion-safety proof: if
 *    either artifact were needed on this path, class-loading would fail with
 *    `NoClassDefFoundError` instead of parsing. (freemarker is deliberately kept,
 *    because the reader class-loads it — its absence would fail this test too.)
 *
 * Property-level classification (which properties auto-type vs. surface raw) is
 * out of scope here — that belongs with the mapper, alongside the fixture corpus.
 */
class EzVcardIntegrationTest {

    @Test
    fun `parses a vCard 3-0 text body with jsoup and jackson excluded`() {
        val body = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Ada Lovelace
            N:Lovelace;Ada;;;
            EMAIL;TYPE=WORK:ada@example.test
            TEL;TYPE=CELL:+1-555-0142
            END:VCARD
        """.trimIndent()

        val cards = Ezvcard.parse(body).all()

        assertEquals(1, cards.size, "expected exactly one parsed card")
        val card = cards[0]
        assertEquals(VCardVersion.V3_0, card.version)
        assertEquals("Ada Lovelace", card.formattedName?.value)
        assertEquals("ada@example.test", card.emails.singleOrNull()?.value)
    }

    @Test
    fun `parses a vCard 4-0 text body with jsoup and jackson excluded`() {
        val body = """
            BEGIN:VCARD
            VERSION:4.0
            FN:Grace Hopper
            N:Hopper;Grace;;;
            EMAIL;PREF=1:grace@example.test
            TEL;VALUE=uri:tel:+1-555-0173
            END:VCARD
        """.trimIndent()

        val cards = Ezvcard.parse(body).all()

        assertEquals(1, cards.size, "expected exactly one parsed card")
        val card = cards[0]
        assertEquals(VCardVersion.V4_0, card.version)
        assertEquals("Grace Hopper", card.formattedName?.value)
        val email = card.emails.singleOrNull()
        assertEquals("grace@example.test", email?.value)
        // 4.0 preference is expressed via PREF=1 (parsed as getPref()), not a TYPE token.
        assertTrue(email?.pref == 1, "expected PREF=1 to surface as getPref()==1 on the 4.0 path")
    }
}
